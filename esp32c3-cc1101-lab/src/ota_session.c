#include "ota_session.h"

#include <string.h>

#include "proto_transport.h"

// ---------------------------------------------------------------------------
// OTA protocol state machine.  See ota_session.h and ota/OTA_PROTOCOL.md.
//
//   IDLE --HELLO--> NONCE --BEGIN(ok)--> RECEIVING --END(ok)--> IDLE + reboot
//
// ABORT, a bad BEGIN, 30 s without a valid packet and link loss all return to
// IDLE and discard a partially written image.  Only the link that sent HELLO
// may continue the session.  A nonce is consumed by one BEGIN or BOOTLOADER
// attempt, good or bad, and is never reused.
// ---------------------------------------------------------------------------

typedef enum {
    OTA_STATE_IDLE = 0,
    OTA_STATE_NONCE,
    OTA_STATE_RECEIVING,
} ota_state_t;

typedef struct {
    ota_state_t state;
    uint8_t owner; // PROTO_LINK_* bit of the link that sent HELLO
    uint8_t nonce[OTA_NONCE_LEN];
    uint8_t expected_sha[OTA_SHA256_LEN];
    uint32_t size;
    uint32_t next_offset;
    uint32_t last_activity_ms;
    uint8_t rx_suspended;

    // Last accepted chunk, kept to re-ACK a duplicate after a lost response.
    uint8_t prev_valid;
    uint32_t prev_offset;
    uint16_t prev_length;
    uint8_t prev_data[OTA_MAX_CHUNK];
} ota_session_state_t;

static const ota_backend_t *backend;
static ota_session_state_t session;

enum {
    REBOOT_NONE = 0,
    REBOOT_APP = 1,
    REBOOT_BOOTLOADER = 2,
};

static uint8_t pending_reboot;
static uint32_t reboot_at_ms;

static const uint8_t begin_label[] = "OTA-BEGIN";  // 9 bytes
static const uint8_t download_label[] = "OTA-DL";  // 6 bytes

static uint32_t get_u32_le(const uint8_t *data) {
    return (uint32_t)data[0] | ((uint32_t)data[1] << 8U) |
           ((uint32_t)data[2] << 16U) | ((uint32_t)data[3] << 24U);
}

static void put_u16_le(uint8_t *out, uint16_t value) {
    out[0] = (uint8_t)(value & 0xFFU);
    out[1] = (uint8_t)(value >> 8U);
}

static void put_u32_le(uint8_t *out, uint32_t value) {
    out[0] = (uint8_t)(value & 0xFFU);
    out[1] = (uint8_t)((value >> 8U) & 0xFFU);
    out[2] = (uint8_t)((value >> 16U) & 0xFFU);
    out[3] = (uint8_t)(value >> 24U);
}

// Branch-free compare: the MAC check must not leak how many leading bytes
// matched through timing.
static int constant_time_equal(const uint8_t *a, const uint8_t *b,
                               size_t length) {
    volatile uint8_t difference = 0;
    for (size_t i = 0; i < length; ++i) {
        difference = (uint8_t)(difference | (uint8_t)(a[i] ^ b[i]));
    }
    return difference == 0;
}

static uint32_t current_time_ms(void) {
    if (backend && backend->now_ms) {
        return backend->now_ms(backend->context);
    }
    return 0;
}

static int session_enabled(void) {
    return backend != NULL && backend->secret != NULL &&
           backend->secret_length == OTA_SECRET_LEN &&
           backend->flash_begin != NULL && backend->flash_write != NULL &&
           backend->flash_finish != NULL && backend->flash_abort != NULL &&
           backend->sha256_init != NULL && backend->sha256_update != NULL &&
           backend->sha256_final != NULL && backend->hmac_sha256 != NULL &&
           backend->random_bytes != NULL && backend->reboot_to_app != NULL &&
           backend->reboot_to_bootloader != NULL;
}

static void resume_rx(void) {
    if (session.rx_suspended) {
        if (backend && backend->rx_resume) {
            backend->rx_resume(backend->context);
        }
        session.rx_suspended = 0;
    }
}

// Discards a partial image (if any) and clears all session material.
static void abort_session(void) {
    if (session.state == OTA_STATE_RECEIVING && backend &&
        backend->flash_abort) {
        backend->flash_abort(backend->context);
    }
    resume_rx();
    memset(&session, 0, sizeof(session));
    session.state = OTA_STATE_IDLE;
}

// Clears the session after a successful END, where the backend has already
// consumed the OTA handle (no esp_ota_abort call).
static void finish_session(void) {
    resume_rx();
    memset(&session, 0, sizeof(session));
    session.state = OTA_STATE_IDLE;
}

static void send_resp(uint8_t link, uint8_t op, uint8_t status,
                      const uint8_t *payload, size_t payload_length) {
    (void)proto_send_ota_resp(op, status, payload, payload_length, link);
}

static size_t build_begin_mac_input(uint8_t *out, uint32_t size,
                                    const uint8_t *sha) {
    size_t offset = 0;
    memcpy(out + offset, begin_label, sizeof(begin_label) - 1U);
    offset += sizeof(begin_label) - 1U;
    memcpy(out + offset, session.nonce, OTA_NONCE_LEN);
    offset += OTA_NONCE_LEN;
    put_u32_le(out + offset, size);
    offset += 4U;
    memcpy(out + offset, sha, OTA_SHA256_LEN);
    offset += OTA_SHA256_LEN;
    return offset;
}

static void handle_hello(uint8_t link, const uint8_t *payload,
                         size_t payload_length, uint32_t now) {
    (void)payload;
    if (payload_length != 0U) {
        send_resp(link, OTA_OP_HELLO, OTA_STATUS_BAD_LENGTH, NULL, 0);
        return;
    }

    // A new HELLO replaces any previous nonce and discards a half-written
    // image, no matter which link owned it.
    abort_session();
    backend->random_bytes(backend->context, session.nonce, OTA_NONCE_LEN);
    session.state = OTA_STATE_NONCE;
    session.owner = link;
    session.last_activity_ms = now;

    uint8_t response[1U + 2U + 4U + OTA_NONCE_LEN];
    response[0] = (uint8_t)OTA_CAPS_BASE;
    put_u16_le(&response[1], (uint16_t)OTA_MAX_CHUNK);
    put_u32_le(&response[3], backend->slot_size);
    memcpy(&response[7], session.nonce, OTA_NONCE_LEN);
    send_resp(link, OTA_OP_HELLO, OTA_STATUS_OK, response, sizeof(response));
}

static void handle_begin(uint8_t link, const uint8_t *payload,
                         size_t payload_length, uint32_t now) {
    if (payload_length != 4U + OTA_SHA256_LEN + OTA_SHA256_LEN) {
        send_resp(link, OTA_OP_BEGIN, OTA_STATUS_BAD_LENGTH, NULL, 0);
        return;
    }
    if (session.state != OTA_STATE_NONCE || session.owner != link) {
        send_resp(link, OTA_OP_BEGIN, OTA_STATUS_BAD_STATE, NULL, 0);
        return;
    }

    uint32_t size = get_u32_le(payload);
    const uint8_t *sha = &payload[4];
    const uint8_t *received_mac = &payload[4 + OTA_SHA256_LEN];

    uint8_t mac_input[sizeof(begin_label) - 1U + OTA_NONCE_LEN + 4U +
                      OTA_SHA256_LEN];
    size_t mac_length = build_begin_mac_input(mac_input, size, sha);
    uint8_t expected_mac[OTA_SHA256_LEN];
    backend->hmac_sha256(backend->context, backend->secret,
                         backend->secret_length, mac_input, mac_length,
                         expected_mac);
    int mac_ok = constant_time_equal(expected_mac, received_mac, OTA_SHA256_LEN);

    // The nonce is single use even when the MAC is wrong or the image is too
    // large.  A fresh HELLO is required for every retry.
    memset(session.nonce, 0, sizeof(session.nonce));
    session.state = OTA_STATE_IDLE;
    session.owner = 0;
    session.prev_valid = 0;

    if (!mac_ok) {
        send_resp(link, OTA_OP_BEGIN, OTA_STATUS_BAD_MAC, NULL, 0);
        return;
    }
    if (size > backend->slot_size) {
        send_resp(link, OTA_OP_BEGIN, OTA_STATUS_TOO_BIG, NULL, 0);
        return;
    }

    if (!backend->flash_begin(backend->context, size)) {
        send_resp(link, OTA_OP_BEGIN, OTA_STATUS_FLASH_ERROR, NULL, 0);
        return;
    }
    if (backend->rx_suspend) {
        backend->rx_suspend(backend->context);
        session.rx_suspended = 1;
    }
    backend->sha256_init(backend->context);

    session.state = OTA_STATE_RECEIVING;
    session.owner = link;
    session.size = size;
    session.next_offset = 0U;
    session.last_activity_ms = now;
    memcpy(session.expected_sha, sha, OTA_SHA256_LEN);

    send_resp(link, OTA_OP_BEGIN, OTA_STATUS_OK, NULL, 0);
}

static void handle_chunk(uint8_t link, const uint8_t *payload,
                         size_t payload_length) {
    if (payload_length < 4U + 1U || payload_length > 4U + OTA_MAX_CHUNK) {
        send_resp(link, OTA_OP_CHUNK, OTA_STATUS_BAD_LENGTH, NULL, 0);
        return;
    }
    if (session.state != OTA_STATE_RECEIVING || session.owner != link) {
        send_resp(link, OTA_OP_CHUNK, OTA_STATUS_BAD_STATE, NULL, 0);
        return;
    }

    uint32_t offset = get_u32_le(payload);
    const uint8_t *data = &payload[4];
    size_t data_length = payload_length - 4U;

    uint8_t response[4];
    put_u32_le(response, session.next_offset);

    if (offset == session.next_offset) {
        // A chunk that runs past the size announced by BEGIN can never be
        // completed; report the current offset so the host resyncs.
        if ((uint32_t)data_length > session.size - session.next_offset) {
            send_resp(link, OTA_OP_CHUNK, OTA_STATUS_BAD_OFFSET, response,
                      sizeof(response));
            return;
        }
        if (!backend->flash_write(backend->context, offset, data,
                                  data_length)) {
            abort_session();
            send_resp(link, OTA_OP_CHUNK, OTA_STATUS_FLASH_ERROR, NULL, 0);
            return;
        }
        backend->sha256_update(backend->context, data, data_length);

        session.prev_valid = 1U;
        session.prev_offset = offset;
        session.prev_length = (uint16_t)data_length;
        memcpy(session.prev_data, data, data_length);

        session.next_offset += (uint32_t)data_length;
        put_u32_le(response, session.next_offset);
        send_resp(link, OTA_OP_CHUNK, OTA_STATUS_OK, response,
                  sizeof(response));
        return;
    }

    // Lost-ACK recovery: the immediately previous chunk may be repeated and
    // is acknowledged without writing it twice.
    if (session.prev_valid && offset == session.prev_offset &&
        data_length == session.prev_length &&
        memcmp(session.prev_data, data, data_length) == 0) {
        send_resp(link, OTA_OP_CHUNK, OTA_STATUS_OK, response,
                  sizeof(response));
        return;
    }

    send_resp(link, OTA_OP_CHUNK, OTA_STATUS_BAD_OFFSET, response,
              sizeof(response));
}

static void handle_end(uint8_t link, const uint8_t *payload,
                       size_t payload_length) {
    (void)payload;
    if (payload_length != 0U) {
        send_resp(link, OTA_OP_END, OTA_STATUS_BAD_LENGTH, NULL, 0);
        return;
    }
    if (session.state != OTA_STATE_RECEIVING || session.owner != link) {
        send_resp(link, OTA_OP_END, OTA_STATUS_BAD_STATE, NULL, 0);
        return;
    }

    uint8_t response[4];
    put_u32_le(response, session.next_offset);
    if (session.next_offset != session.size) {
        send_resp(link, OTA_OP_END, OTA_STATUS_BAD_OFFSET, response,
                  sizeof(response));
        return;
    }

    uint8_t digest[OTA_SHA256_LEN];
    backend->sha256_final(backend->context, digest);
    if (!constant_time_equal(digest, session.expected_sha, OTA_SHA256_LEN)) {
        abort_session();
        send_resp(link, OTA_OP_END, OTA_STATUS_BAD_HASH, NULL, 0);
        return;
    }

    if (!backend->flash_finish(backend->context)) {
        abort_session();
        send_resp(link, OTA_OP_END, OTA_STATUS_FLASH_ERROR, NULL, 0);
        return;
    }

    finish_session();
    pending_reboot = REBOOT_APP;
    reboot_at_ms = current_time_ms() + OTA_APP_REBOOT_DELAY_MS;
    send_resp(link, OTA_OP_END, OTA_STATUS_OK, NULL, 0);
}

static void handle_bootloader(uint8_t link, const uint8_t *payload,
                              size_t payload_length, uint32_t now) {
    if (payload_length != OTA_SHA256_LEN) {
        send_resp(link, OTA_OP_BOOTLOADER, OTA_STATUS_BAD_LENGTH, NULL, 0);
        return;
    }
    if (session.state != OTA_STATE_NONCE || session.owner != link) {
        send_resp(link, OTA_OP_BOOTLOADER, OTA_STATUS_BAD_STATE, NULL, 0);
        return;
    }

    uint8_t mac_input[sizeof(download_label) - 1U + OTA_NONCE_LEN];
    size_t mac_length = 0;
    memcpy(mac_input, download_label, sizeof(download_label) - 1U);
    mac_length += sizeof(download_label) - 1U;
    memcpy(mac_input + mac_length, session.nonce, OTA_NONCE_LEN);
    mac_length += OTA_NONCE_LEN;

    uint8_t expected_mac[OTA_SHA256_LEN];
    backend->hmac_sha256(backend->context, backend->secret,
                         backend->secret_length, mac_input, mac_length,
                         expected_mac);
    int mac_ok = constant_time_equal(expected_mac, payload, OTA_SHA256_LEN);

    // Consume the nonce and end the session even on a MAC failure.
    memset(session.nonce, 0, sizeof(session.nonce));
    session.state = OTA_STATE_IDLE;
    session.owner = 0;
    session.prev_valid = 0;

    if (!mac_ok) {
        send_resp(link, OTA_OP_BOOTLOADER, OTA_STATUS_BAD_MAC, NULL, 0);
        return;
    }

    pending_reboot = REBOOT_BOOTLOADER;
    reboot_at_ms = now + OTA_BOOTLOADER_REBOOT_DELAY_MS;
    send_resp(link, OTA_OP_BOOTLOADER, OTA_STATUS_OK, NULL, 0);
}

void ota_session_init(const ota_backend_t *new_backend) {
    backend = new_backend;
    memset(&session, 0, sizeof(session));
    session.state = OTA_STATE_IDLE;
    pending_reboot = REBOOT_NONE;
    reboot_at_ms = 0U;
}

void ota_session_handle(uint8_t link, uint8_t op, const uint8_t *payload,
                        size_t payload_length) {
    uint32_t now = current_time_ms();

    if (session.state != OTA_STATE_IDLE &&
        (uint32_t)(now - session.last_activity_ms) >=
            OTA_SESSION_TIMEOUT_MS) {
        abort_session();
    }

    // ABORT is always honoured, including when no secret is compiled in.
    if (op == OTA_OP_ABORT) {
        abort_session();
        send_resp(link, op, OTA_STATUS_OK, NULL, 0);
        return;
    }

    if (!session_enabled()) {
        send_resp(link, op, OTA_STATUS_DISABLED, NULL, 0);
        return;
    }

    if (session.state != OTA_STATE_IDLE) {
        // Any CRC-valid OTA packet counts as activity for the idle timer.
        session.last_activity_ms = now;
    }

    switch (op) {
    case OTA_OP_HELLO:
        handle_hello(link, payload, payload_length, now);
        break;
    case OTA_OP_BEGIN:
        handle_begin(link, payload, payload_length, now);
        break;
    case OTA_OP_CHUNK:
        handle_chunk(link, payload, payload_length);
        break;
    case OTA_OP_END:
        handle_end(link, payload, payload_length);
        break;
    case OTA_OP_BOOTLOADER:
        handle_bootloader(link, payload, payload_length, now);
        break;
    default:
        send_resp(link, op, OTA_STATUS_BAD_STATE, NULL, 0);
        break;
    }
}

void ota_session_poll(uint32_t now) {
    if (session.state != OTA_STATE_IDLE &&
        (uint32_t)(now - session.last_activity_ms) >=
            OTA_SESSION_TIMEOUT_MS) {
        abort_session();
    }

    if (pending_reboot == REBOOT_NONE) {
        return;
    }
    if ((int32_t)(now - reboot_at_ms) < 0) {
        return;
    }

    uint8_t kind = pending_reboot;
    pending_reboot = REBOOT_NONE;
    if (!backend) {
        return;
    }
    if (kind == REBOOT_APP && backend->reboot_to_app) {
        backend->reboot_to_app(backend->context);
    } else if (kind == REBOOT_BOOTLOADER && backend->reboot_to_bootloader) {
        backend->reboot_to_bootloader(backend->context);
    }
}

void ota_session_link_lost(uint8_t link) {
    if (session.state != OTA_STATE_IDLE && session.owner == link) {
        abort_session();
    }
}

int ota_session_rx_suspended(void) { return session.rx_suspended != 0; }

int ota_secret_from_hex(const char *hex, uint8_t out[OTA_SECRET_LEN]) {
    if (hex == NULL || strlen(hex) != OTA_SECRET_LEN * 2U) {
        return 0;
    }
    for (size_t i = 0; i < OTA_SECRET_LEN; ++i) {
        uint8_t value = 0U;
        for (size_t half = 0; half < 2U; ++half) {
            char c = hex[i * 2U + half];
            uint8_t nibble;
            if (c >= '0' && c <= '9') {
                nibble = (uint8_t)(c - '0');
            } else if (c >= 'a' && c <= 'f') {
                nibble = (uint8_t)(c - 'a' + 10);
            } else if (c >= 'A' && c <= 'F') {
                nibble = (uint8_t)(c - 'A' + 10);
            } else {
                return 0;
            }
            value = (uint8_t)((value << 4U) | nibble);
        }
        out[i] = value;
    }
    return 1;
}
