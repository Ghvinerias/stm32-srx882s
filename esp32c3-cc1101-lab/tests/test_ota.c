// Host test for the OTA / bootloader protocol (packet types 6 and 7).
//
// Replays ota/OTA_VECTORS.json byte-for-byte through command_parse_feed(),
// using fake flash/RNG/reboot backends and OpenSSL SHA-256/HMAC, and checks
// every state-machine rule in ota/OTA_PROTOCOL.md.
//
// Build: make test (this target links -lcrypto).
#include <openssl/evp.h>
#include <openssl/hmac.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "ota_vectors.h"
#include "src/command_parse.h"
#include "src/ota_session.h"
#include "src/proto_transport.h"
#include "test_util.h"

#define WIRE_MAX 512U
#define IMAGE_MAX 4096U

static size_t hex_decode(const char *hex, uint8_t *out, size_t capacity);

// ---------------------------------------------------------------------------
// Fake backend
// ---------------------------------------------------------------------------

typedef struct {
    uint8_t secret[OTA_SECRET_LEN];
    int has_secret;
    size_t secret_length;
    uint8_t nonce[OTA_NONCE_LEN];
    uint32_t slot_size;
    uint32_t now;

    uint8_t image[IMAGE_MAX];
    uint32_t image_length;
    int begin_result;
    int write_result;
    int finish_result;

    int begin_calls;
    int write_calls;
    int finish_calls;
    int abort_calls;
    int sha_init_calls;
    int sha_update_calls;
    int sha_final_calls;
    int suspend_calls;
    int resume_calls;
    int reboot_app_calls;
    int reboot_bootloader_calls;
    int boot_partition_set;
    int random_calls;
    EVP_MD_CTX *sha;
} fake_t;

static fake_t fake;
static ota_backend_t test_backend;
static link_ctx_t links;

static void test_hmac(const uint8_t *key, size_t key_length,
                      const uint8_t *data, size_t length,
                      uint8_t out[OTA_SHA256_LEN]) {
    unsigned int out_length = 0;
    if (HMAC(EVP_sha256(), key, (int)key_length, data, length, out,
             &out_length) == NULL ||
        out_length != OTA_SHA256_LEN) {
        memset(out, 0, OTA_SHA256_LEN);
    }
}

static int fake_flash_begin(void *context, uint32_t image_size) {
    (void)context;
    ++fake.begin_calls;
    if (!fake.begin_result || image_size > fake.slot_size) {
        return 0;
    }
    fake.image_length = 0;
    return 1;
}

static int fake_flash_write(void *context, uint32_t offset,
                            const uint8_t *data, size_t length) {
    (void)context;
    ++fake.write_calls;
    if (!fake.write_result || offset != fake.image_length ||
        length > IMAGE_MAX - fake.image_length) {
        return 0;
    }
    memcpy(fake.image + fake.image_length, data, length);
    fake.image_length += (uint32_t)length;
    return 1;
}

static int fake_flash_finish(void *context) {
    (void)context;
    ++fake.finish_calls;
    if (!fake.finish_result) {
        return 0;
    }
    fake.boot_partition_set = 1;
    return 1;
}

static void fake_flash_abort(void *context) {
    (void)context;
    ++fake.abort_calls;
}

static void fake_sha_init(void *context) {
    (void)context;
    if (fake.sha != NULL) {
        EVP_MD_CTX_free(fake.sha);
    }
    fake.sha = EVP_MD_CTX_new();
    if (fake.sha != NULL) {
        (void)EVP_DigestInit_ex(fake.sha, EVP_sha256(), NULL);
    }
    ++fake.sha_init_calls;
}

static void fake_sha_update(void *context, const uint8_t *data,
                            size_t length) {
    (void)context;
    if (fake.sha != NULL) {
        (void)EVP_DigestUpdate(fake.sha, data, length);
    }
    ++fake.sha_update_calls;
}

static void fake_sha_final(void *context, uint8_t out[OTA_SHA256_LEN]) {
    (void)context;
    ++fake.sha_final_calls;
    if (fake.sha == NULL) {
        memset(out, 0, OTA_SHA256_LEN);
        return;
    }
    unsigned int length = 0;
    (void)EVP_DigestFinal_ex(fake.sha, out, &length);
    EVP_MD_CTX_free(fake.sha);
    fake.sha = NULL;
}

static void fake_hmac(void *context, const uint8_t *key, size_t key_length,
                      const uint8_t *data, size_t length,
                      uint8_t out[OTA_SHA256_LEN]) {
    (void)context;
    test_hmac(key, key_length, data, length, out);
}

static void fake_random(void *context, uint8_t *out, size_t length) {
    (void)context;
    ++fake.random_calls;
    for (size_t i = 0; i < length; ++i) {
        out[i] = i < OTA_NONCE_LEN ? fake.nonce[i] : 0U;
    }
}

static void fake_reboot_app(void *context) {
    (void)context;
    ++fake.reboot_app_calls;
}

static void fake_reboot_bootloader(void *context) {
    (void)context;
    ++fake.reboot_bootloader_calls;
}

static uint32_t fake_now_ms(void *context) {
    (void)context;
    return fake.now;
}

static void fake_rx_suspend(void *context) {
    (void)context;
    ++fake.suspend_calls;
}

static void fake_rx_resume(void *context) {
    (void)context;
    ++fake.resume_calls;
}

static void refresh_backend(void) {
    memset(&test_backend, 0, sizeof(test_backend));
    test_backend.context = &fake;
    test_backend.flash_begin = fake_flash_begin;
    test_backend.flash_write = fake_flash_write;
    test_backend.flash_finish = fake_flash_finish;
    test_backend.flash_abort = fake_flash_abort;
    test_backend.sha256_init = fake_sha_init;
    test_backend.sha256_update = fake_sha_update;
    test_backend.sha256_final = fake_sha_final;
    test_backend.hmac_sha256 = fake_hmac;
    test_backend.random_bytes = fake_random;
    test_backend.reboot_to_app = fake_reboot_app;
    test_backend.reboot_to_bootloader = fake_reboot_bootloader;
    test_backend.now_ms = fake_now_ms;
    test_backend.rx_suspend = fake_rx_suspend;
    test_backend.rx_resume = fake_rx_resume;
    test_backend.slot_size = fake.slot_size;
    test_backend.secret = fake.has_secret ? fake.secret : NULL;
    test_backend.secret_length = fake.has_secret ? fake.secret_length : 0U;
}

static void fake_reset(int with_secret) {
    memset(&fake, 0, sizeof(fake));
    memset(&links, 0, sizeof(links));
    links.deliver_mask = PROTO_LINK_ALL;
    fake.begin_result = 1;
    fake.write_result = 1;
    fake.finish_result = 1;
    fake.has_secret = with_secret;
    fake.secret_length = with_secret ? OTA_SECRET_LEN : 0U;
    fake.slot_size = OTA_VEC_SLOT_SIZE;
    fake.now = 1000U;
    (void)hex_decode(OTA_VEC_SECRET_HEX, fake.secret, sizeof(fake.secret));
    (void)hex_decode(OTA_VEC_NONCE_HEX, fake.nonce, sizeof(fake.nonce));
    refresh_backend();
    ota_session_init(&test_backend);
}

// ---------------------------------------------------------------------------
// Byte helpers
// ---------------------------------------------------------------------------

static int hex_nibble(char c) {
    if (c >= '0' && c <= '9') {
        return c - '0';
    }
    if (c >= 'a' && c <= 'f') {
        return c - 'a' + 10;
    }
    if (c >= 'A' && c <= 'F') {
        return c - 'A' + 10;
    }
    return -1;
}

static size_t hex_decode(const char *hex, uint8_t *out, size_t capacity) {
    size_t text_length = strlen(hex);
    if ((text_length & 1U) != 0U) {
        return 0;
    }
    size_t length = text_length / 2U;
    if (length > capacity) {
        return 0;
    }
    for (size_t i = 0; i < length; ++i) {
        int high = hex_nibble(hex[i * 2U]);
        int low = hex_nibble(hex[i * 2U + 1U]);
        if (high < 0 || low < 0) {
            return 0;
        }
        out[i] = (uint8_t)((high << 4) | low);
    }
    return length;
}

static void put_u32_le(uint8_t *out, uint32_t value) {
    out[0] = (uint8_t)(value & 0xFFU);
    out[1] = (uint8_t)((value >> 8U) & 0xFFU);
    out[2] = (uint8_t)((value >> 16U) & 0xFFU);
    out[3] = (uint8_t)(value >> 24U);
}

static size_t build_ota_resp(uint8_t op, uint8_t status,
                             const uint8_t *payload, size_t payload_length,
                             uint8_t *raw) {
    raw[0] = 1U;
    raw[1] = PROTO_TYPE_OTA_RESP;
    raw[2] = op;
    raw[3] = status;
    if (payload_length != 0U) {
        memcpy(&raw[4], payload, payload_length);
    }
    size_t length = 4U + payload_length;
    uint16_t crc = proto_crc16(raw, length);
    raw[length] = (uint8_t)(crc & 0xFFU);
    raw[length + 1U] = (uint8_t)(crc >> 8U);
    return length + 2U;
}

static size_t build_ota_cmd(uint8_t op, const uint8_t *payload,
                            size_t payload_length, uint8_t *raw) {
    raw[0] = 1U;
    raw[1] = PROTO_TYPE_OTA_CMD;
    raw[2] = op;
    if (payload_length != 0U) {
        memcpy(&raw[3], payload, payload_length);
    }
    size_t length = 3U + payload_length;
    uint16_t crc = proto_crc16(raw, length);
    raw[length] = (uint8_t)(crc & 0xFFU);
    raw[length + 1U] = (uint8_t)(crc >> 8U);
    return length + 2U;
}

static size_t vector_wire_to_raw(const char *wire_hex, uint8_t *raw,
                                 size_t capacity) {
    uint8_t wire[WIRE_MAX];
    size_t wire_length = hex_decode(wire_hex, wire, sizeof(wire));
    if (wire_length < 2U || wire[wire_length - 1U] != 0U) {
        return 0;
    }
    return proto_cobs_decode(wire, wire_length - 1U, raw, capacity);
}

static int rec_equals_wire(const rec_t *rec, const char *wire_hex) {
    uint8_t expected[WIRE_MAX];
    size_t length = hex_decode(wire_hex, expected, sizeof(expected));
    return length != 0U && rec->length == length &&
           memcmp(rec->data, expected, length) == 0;
}

static size_t rec_raw(const rec_t *rec, uint8_t *raw, size_t capacity) {
    if (rec->length < 2U || rec->data[rec->length - 1U] != 0U) {
        return 0;
    }
    return proto_cobs_decode(rec->data, rec->length - 1U, raw, capacity);
}

static int rec_equals_raw(const rec_t *rec, const uint8_t *expected,
                          size_t expected_length) {
    uint8_t raw[WIRE_MAX];
    size_t length = rec_raw(rec, raw, sizeof(raw));
    return length == expected_length &&
           memcmp(raw, expected, expected_length) == 0;
}

static int rec_status(const rec_t *rec) {
    uint8_t raw[WIRE_MAX];
    size_t length = rec_raw(rec, raw, sizeof(raw));
    if (length < 4U) {
        return -1;
    }
    return raw[3];
}

static const rec_t *link_rec(uint8_t link) {
    return link == PROTO_LINK_USB ? &links.usb : &links.ble;
}

static const rec_t *other_rec(uint8_t link) {
    return link == PROTO_LINK_USB ? &links.ble : &links.usb;
}

static void reset_recorders(void) {
    rec_reset(&links.usb);
    rec_reset(&links.ble);
}

static void feed_bytes(uint8_t link, const uint8_t *data, size_t length,
                       size_t chunk) {
    if (chunk == 0U) {
        chunk = length == 0U ? 1U : length;
    }
    for (size_t offset = 0; offset < length; offset += chunk) {
        size_t part = length - offset < chunk ? length - offset : chunk;
        command_parse_feed(link, data + offset, part);
    }
}

static void feed_wire_hex(uint8_t link, const char *wire_hex, size_t chunk) {
    uint8_t wire[WIRE_MAX];
    size_t length = hex_decode(wire_hex, wire, sizeof(wire));
    if (length == 0U) {
        expect("vector wire hex decodes", 0);
        return;
    }
    feed_bytes(link, wire, length, chunk);
}

static void feed_raw(uint8_t link, const uint8_t *raw, size_t length,
                     size_t chunk) {
    uint8_t wire[WIRE_MAX];
    size_t wire_length =
        proto_cobs_encode(raw, length, wire, sizeof(wire));
    if (wire_length == 0U) {
        expect("command encodes", 0);
        return;
    }
    feed_bytes(link, wire, wire_length, chunk);
}

// Sends a golden command packet and requires the exact golden response.
// A NULL response_wire only checks for status OK.
static void expect_exchange_wire(const char *label, uint8_t link,
                                 const char *command_wire, size_t chunk,
                                 const char *response_wire) {
    reset_recorders();
    feed_wire_hex(link, command_wire, chunk);
    if (response_wire == NULL) {
        expect(label, rec_status(link_rec(link)) == OTA_STATUS_OK);
        return;
    }
    expect(label, rec_equals_wire(link_rec(link), response_wire));
}

static void expect_status(const char *label, uint8_t link,
                          const char *command_wire, size_t chunk,
                          int status) {
    reset_recorders();
    feed_wire_hex(link, command_wire, chunk);
    expect(label, rec_status(link_rec(link)) == status);
}

static void make_image(uint8_t *image, size_t length) {
    for (size_t i = 0; i < length; ++i) {
        image[i] = (uint8_t)((i * 7U + 3U) & 0xFFU);
    }
}

static size_t build_begin_raw(uint32_t size, const uint8_t *sha,
                              const uint8_t *secret, uint8_t *raw) {
    uint8_t payload[4U + OTA_SHA256_LEN + OTA_SHA256_LEN];
    put_u32_le(payload, size);
    memcpy(&payload[4], sha, OTA_SHA256_LEN);

    uint8_t mac_input[9U + OTA_NONCE_LEN + 4U + OTA_SHA256_LEN];
    memcpy(mac_input, "OTA-BEGIN", 9U);
    memcpy(&mac_input[9], fake.nonce, OTA_NONCE_LEN);
    memcpy(&mac_input[9U + OTA_NONCE_LEN], payload, 4U);
    memcpy(&mac_input[9U + OTA_NONCE_LEN + 4U], sha, OTA_SHA256_LEN);
    test_hmac(secret, OTA_SECRET_LEN, mac_input, sizeof(mac_input),
              &payload[4U + OTA_SHA256_LEN]);

    return build_ota_cmd(OTA_OP_BEGIN, payload, sizeof(payload), raw);
}

static size_t build_bootloader_raw(const uint8_t *secret, uint8_t *raw) {
    uint8_t mac_input[6U + OTA_NONCE_LEN];
    memcpy(mac_input, "OTA-DL", 6U);
    memcpy(&mac_input[6], fake.nonce, OTA_NONCE_LEN);

    uint8_t payload[OTA_SHA256_LEN];
    test_hmac(secret, OTA_SECRET_LEN, mac_input, sizeof(mac_input), payload);
    return build_ota_cmd(OTA_OP_BOOTLOADER, payload, sizeof(payload), raw);
}

// ---------------------------------------------------------------------------
// Session helpers
// ---------------------------------------------------------------------------

static void start_hello(uint8_t link, size_t chunk, const char *name) {
    char label[96];
    reset_recorders();
    feed_wire_hex(link, OTA_VEC_HELLO_WIRE, chunk);
    snprintf(label, sizeof(label), "%s: HELLO_OK response matches vector",
             name);
    expect(label, rec_equals_wire(link_rec(link), OTA_VEC_HELLO_RESP_OK_WIRE));
    snprintf(label, sizeof(label), "%s: HELLO answers only the requesting link",
             name);
    expect(label, other_rec(link)->length == 0U);
}

static void start_begin(uint8_t link, size_t chunk, const char *name) {
    char label[96];
    reset_recorders();
    feed_wire_hex(link, OTA_VEC_BEGIN_WIRE, chunk);
    snprintf(label, sizeof(label), "%s: BEGIN_OK response matches vector",
             name);
    expect(label, rec_equals_wire(link_rec(link), OTA_VEC_BEGIN_RESP_OK_WIRE));
}

static void expect_next_offset_response(const char *label, uint8_t link,
                                        uint32_t next_offset) {
    uint8_t payload[4];
    put_u32_le(payload, next_offset);
    uint8_t expected[16];
    size_t expected_length =
        build_ota_resp(OTA_OP_CHUNK, OTA_STATUS_OK, payload, sizeof(payload),
                       expected);
    expect(label, rec_equals_raw(link_rec(link), expected, expected_length));
}

static void run_full_flow(const char *name, uint8_t link, size_t chunk) {
    char label[128];
    uint8_t image[OTA_VEC_IMAGE_LEN];
    make_image(image, sizeof(image));

    fake_reset(1);
    command_parse_init(NULL, NULL);
    reset_recorders();

    start_hello(link, chunk, name);
    start_begin(link, chunk, name);

    uint32_t next_offset = 0U;
    for (size_t i = 0; i < OTA_VEC_CHUNK_COUNT; ++i) {
        reset_recorders();
        feed_wire_hex(link, OTA_VEC_CHUNKS[i].wire_hex, chunk);
        uint32_t data_length = OTA_VEC_IMAGE_LEN - OTA_VEC_CHUNKS[i].offset;
        if (data_length > OTA_VEC_MAX_CHUNK) {
            data_length = OTA_VEC_MAX_CHUNK;
        }
        next_offset = OTA_VEC_CHUNKS[i].offset + data_length;
        snprintf(label, sizeof(label), "%s: chunk %zu ACK is next_offset=%u",
                 name, i, (unsigned)next_offset);
        expect_next_offset_response(label, link, next_offset);
        if (i == 0U) {
            snprintf(label, sizeof(label),
                     "%s: first chunk ACK matches vector", name);
            expect(label, rec_equals_wire(link_rec(link),
                                          OTA_VEC_CHUNK_RESP_FIRST_WIRE));
        }
    }

    reset_recorders();
    feed_wire_hex(link, OTA_VEC_END_WIRE, chunk);
    snprintf(label, sizeof(label), "%s: END_OK response matches vector", name);
    expect(label, rec_equals_wire(link_rec(link), OTA_VEC_END_RESP_OK_WIRE));
    snprintf(label, sizeof(label), "%s: END set boot partition", name);
    expect(label, fake.finish_calls == 1 && fake.boot_partition_set == 1);
    snprintf(label, sizeof(label), "%s: image bytes match", name);
    expect(label,
           fake.image_length == OTA_VEC_IMAGE_LEN &&
               memcmp(fake.image, image, sizeof(image)) == 0);
    snprintf(label, sizeof(label), "%s: reboot is delayed", name);
    expect(label, fake.reboot_app_calls == 0);

    fake.now += OTA_APP_REBOOT_DELAY_MS - 1U;
    ota_session_poll(fake.now);
    snprintf(label, sizeof(label), "%s: no reboot before delay", name);
    expect(label, fake.reboot_app_calls == 0);

    fake.now += 1U;
    ota_session_poll(fake.now);
    snprintf(label, sizeof(label), "%s: reboot_to_app after delay", name);
    expect(label, fake.reboot_app_calls == 1);
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

int main(void) {
    proto_sink_t sink = {link_write, &links};
    proto_init(&sink, 0U);

    // ---- golden HELLO / BEGIN / 6 chunks / END, both links and chunkings ----
    run_full_flow("USB/1B", PROTO_LINK_USB, 1U);
    run_full_flow("USB/20B", PROTO_LINK_USB, 20U);
    run_full_flow("BLE/1B", PROTO_LINK_BLE, 1U);
    run_full_flow("BLE/20B", PROTO_LINK_BLE, 20U);

    // ---- BAD_MAC consumes the nonce ----
    fake_reset(1);
    command_parse_init(NULL, NULL);
    reset_recorders();
    start_hello(PROTO_LINK_USB, 64U, "bad-mac");
    expect_status("bad MAC BEGIN answers BAD_MAC", PROTO_LINK_USB,
                  OTA_VEC_BAD_MAC_BEGIN_WIRE, 64U, OTA_STATUS_BAD_MAC);
    expect("bad MAC BEGIN did not start flash", fake.begin_calls == 0);
    expect_status("nonce is single use after bad MAC", PROTO_LINK_USB,
                  OTA_VEC_BEGIN_WIRE, 64U, OTA_STATUS_BAD_STATE);

    // ---- TOO_BIG consumes the nonce too ----
    fake_reset(1);
    command_parse_init(NULL, NULL);
    reset_recorders();
    start_hello(PROTO_LINK_USB, 64U, "too-big");
    {
        uint8_t sha[OTA_SHA256_LEN];
        (void)hex_decode(OTA_VEC_IMAGE_SHA256_HEX, sha, sizeof(sha));
        uint8_t raw[WIRE_MAX];
        size_t raw_length = build_begin_raw(OTA_VEC_IMAGE_LEN, sha,
                                            fake.secret, raw);
        uint8_t expected[WIRE_MAX];
        size_t expected_length =
            hex_decode(OTA_VEC_BEGIN_RAW, expected, sizeof(expected));
        expect("constructed BEGIN equals vector",
               raw_length == expected_length &&
                   memcmp(raw, expected, raw_length) == 0);

        raw_length = build_begin_raw(OTA_VEC_SLOT_SIZE + 1U, sha,
                                     fake.secret, raw);
        reset_recorders();
        feed_raw(PROTO_LINK_USB, raw, raw_length, 64U);
        expect("BEGIN larger than slot answers TOO_BIG",
               rec_status(link_rec(PROTO_LINK_USB)) == OTA_STATUS_TOO_BIG);
        expect("TOO_BIG did not start flash", fake.begin_calls == 0);
    }
    expect_status("nonce is single use after TOO_BIG", PROTO_LINK_USB,
                  OTA_VEC_BEGIN_WIRE, 64U, OTA_STATUS_BAD_STATE);

    // ---- END with the wrong digest -> BAD_HASH and discard ----
    fake_reset(1);
    command_parse_init(NULL, NULL);
    reset_recorders();
    start_hello(PROTO_LINK_USB, 64U, "bad-hash");
    {
        uint8_t wrong_sha[OTA_SHA256_LEN];
        memset(wrong_sha, 0, sizeof(wrong_sha));
        uint8_t raw[WIRE_MAX];
        size_t raw_length = build_begin_raw(OTA_VEC_IMAGE_LEN, wrong_sha,
                                            fake.secret, raw);
        reset_recorders();
        feed_raw(PROTO_LINK_USB, raw, raw_length, 64U);
        expect("BEGIN with wrong SHA is accepted (MAC covers it)",
               rec_status(link_rec(PROTO_LINK_USB)) == OTA_STATUS_OK);
    }
    for (size_t i = 0; i < OTA_VEC_CHUNK_COUNT; ++i) {
        feed_wire_hex(PROTO_LINK_USB, OTA_VEC_CHUNKS[i].wire_hex, 64U);
    }
    expect("wrong-SHA image reaches the declared size",
           fake.image_length == OTA_VEC_IMAGE_LEN);
    expect_status("END with wrong SHA answers BAD_HASH", PROTO_LINK_USB,
                  OTA_VEC_END_WIRE, 64U, OTA_STATUS_BAD_HASH);
    expect("BAD_HASH discards the partial image", fake.abort_calls == 1);
    expect("BAD_HASH does not set the boot partition",
           fake.boot_partition_set == 0);

    // ---- BAD_OFFSET with resync ----
    fake_reset(1);
    command_parse_init(NULL, NULL);
    reset_recorders();
    start_hello(PROTO_LINK_USB, 64U, "bad-offset");
    start_begin(PROTO_LINK_USB, 64U, "bad-offset");
    expect_exchange_wire("offset 0 accepted", PROTO_LINK_USB,
                         OTA_VEC_CHUNKS[0].wire_hex, 64U, NULL);
    reset_recorders();
    feed_wire_hex(PROTO_LINK_USB, OTA_VEC_CHUNKS[2].wire_hex, 64U);
    expect("skipped chunk answers BAD_OFFSET vector",
           rec_equals_wire(link_rec(PROTO_LINK_USB),
                           OTA_VEC_CHUNK_RESP_BAD_OFFSET_WIRE));
    expect("skipped chunk did not write", fake.write_calls == 1);
    for (size_t i = 1; i < OTA_VEC_CHUNK_COUNT; ++i) {
        expect_exchange_wire("resynced chunk accepted", PROTO_LINK_USB,
                             OTA_VEC_CHUNKS[i].wire_hex, 64U, NULL);
    }
    expect_status("END after resync is OK", PROTO_LINK_USB, OTA_VEC_END_WIRE,
                  64U, OTA_STATUS_OK);

    // ---- duplicate immediately previous chunk is re-ACKed, not rewritten ----
    fake_reset(1);
    command_parse_init(NULL, NULL);
    reset_recorders();
    start_hello(PROTO_LINK_USB, 64U, "duplicate");
    start_begin(PROTO_LINK_USB, 64U, "duplicate");
    feed_wire_hex(PROTO_LINK_USB, OTA_VEC_CHUNKS[0].wire_hex, 64U);
    {
        int writes = fake.write_calls;
        int updates = fake.sha_update_calls;
        reset_recorders();
        feed_wire_hex(PROTO_LINK_USB, OTA_VEC_CHUNKS[0].wire_hex, 64U);
        expect("duplicate chunk re-ACKs with next_offset",
               rec_equals_wire(link_rec(PROTO_LINK_USB),
                               OTA_VEC_CHUNK_RESP_FIRST_WIRE));
        expect("duplicate chunk is not rewritten",
               fake.write_calls == writes);
        expect("duplicate chunk is not hashed twice",
               fake.sha_update_calls == updates);
    }
    expect_exchange_wire("next chunk after duplicate accepted", PROTO_LINK_USB,
                         OTA_VEC_CHUNKS[1].wire_hex, 64U, NULL);

    // ---- per-link ownership ----
    fake_reset(1);
    command_parse_init(NULL, NULL);
    reset_recorders();
    start_hello(PROTO_LINK_USB, 64U, "ownership");
    expect_status("BEGIN from the other link is rejected", PROTO_LINK_BLE,
                  OTA_VEC_BEGIN_WIRE, 64U, OTA_STATUS_BAD_STATE);
    expect("other-link BEGIN did not start flash", fake.begin_calls == 0);
    expect_status("BEGIN from the owning link still works", PROTO_LINK_USB,
                  OTA_VEC_BEGIN_WIRE, 64U, OTA_STATUS_OK);
    expect_status("CHUNK from the other link is rejected", PROTO_LINK_BLE,
                  OTA_VEC_CHUNKS[0].wire_hex, 64U, OTA_STATUS_BAD_STATE);
    expect("other-link CHUNK did not write", fake.write_calls == 0);
    expect_exchange_wire("CHUNK from the owning link works", PROTO_LINK_USB,
                         OTA_VEC_CHUNKS[0].wire_hex, 64U, NULL);
    for (size_t i = 1; i < OTA_VEC_CHUNK_COUNT; ++i) {
        expect_exchange_wire("remaining chunk from owning link works",
                             PROTO_LINK_USB, OTA_VEC_CHUNKS[i].wire_hex, 64U,
                             NULL);
    }
    expect_status("END from the other link is rejected", PROTO_LINK_BLE,
                  OTA_VEC_END_WIRE, 64U, OTA_STATUS_BAD_STATE);
    expect_status("END from the owning link works", PROTO_LINK_USB,
                  OTA_VEC_END_WIRE, 64U, OTA_STATUS_OK);

    // ---- a new HELLO replaces a half-written session ----
    fake_reset(1);
    command_parse_init(NULL, NULL);
    reset_recorders();
    start_hello(PROTO_LINK_USB, 64U, "hello-replace");
    start_begin(PROTO_LINK_USB, 64U, "hello-replace");
    feed_wire_hex(PROTO_LINK_USB, OTA_VEC_CHUNKS[0].wire_hex, 64U);
    reset_recorders();
    feed_wire_hex(PROTO_LINK_USB, OTA_VEC_HELLO_WIRE, 64U);
    expect("HELLO during RECEIVING discards the partial image",
           fake.abort_calls == 1);
    expect("HELLO during RECEIVING resumes RMT RX", fake.resume_calls == 1);
    expect("HELLO during RECEIVING answers HELLO_OK",
           rec_equals_wire(link_rec(PROTO_LINK_USB),
                           OTA_VEC_HELLO_RESP_OK_WIRE));
    expect_status("CHUNK after replacement HELLO is BAD_STATE", PROTO_LINK_USB,
                  OTA_VEC_CHUNKS[0].wire_hex, 64U, OTA_STATUS_BAD_STATE);
    start_begin(PROTO_LINK_USB, 64U, "hello-replace");

    // ---- link loss aborts only the owning session ----
    fake_reset(1);
    command_parse_init(NULL, NULL);
    reset_recorders();
    start_hello(PROTO_LINK_USB, 64U, "link-loss");
    start_begin(PROTO_LINK_USB, 64U, "link-loss");
    feed_wire_hex(PROTO_LINK_USB, OTA_VEC_CHUNKS[0].wire_hex, 64U);
    ota_session_link_lost(PROTO_LINK_BLE);
    expect("link loss on the other link keeps the session",
           fake.abort_calls == 0);
    ota_session_link_lost(PROTO_LINK_USB);
    expect("link loss on the owner discards the partial image",
           fake.abort_calls == 1);
    expect("link loss resumes RMT RX", fake.resume_calls == 1);
    expect_status("CHUNK after link loss is BAD_STATE", PROTO_LINK_USB,
                  OTA_VEC_CHUNKS[0].wire_hex, 64U, OTA_STATUS_BAD_STATE);

    // ---- END before the declared size is BAD_OFFSET, session survives ----
    fake_reset(1);
    command_parse_init(NULL, NULL);
    reset_recorders();
    start_hello(PROTO_LINK_USB, 64U, "short-end");
    start_begin(PROTO_LINK_USB, 64U, "short-end");
    feed_wire_hex(PROTO_LINK_USB, OTA_VEC_CHUNKS[0].wire_hex, 64U);
    reset_recorders();
    feed_wire_hex(PROTO_LINK_USB, OTA_VEC_END_WIRE, 64U);
    {
        uint8_t payload[4];
        put_u32_le(payload, OTA_VEC_MAX_CHUNK);
        uint8_t expected[16];
        size_t expected_length = build_ota_resp(
            OTA_OP_END, OTA_STATUS_BAD_OFFSET, payload, sizeof(payload),
            expected);
        expect("short END answers BAD_OFFSET with next_offset",
               rec_equals_raw(link_rec(PROTO_LINK_USB), expected,
                              expected_length));
    }
    expect("short END does not finish flash", fake.finish_calls == 0);
    for (size_t i = 1; i < OTA_VEC_CHUNK_COUNT; ++i) {
        expect_exchange_wire("chunk after short END accepted", PROTO_LINK_USB,
                             OTA_VEC_CHUNKS[i].wire_hex, 64U, NULL);
    }
    expect_status("END after the remaining chunks is OK", PROTO_LINK_USB,
                  OTA_VEC_END_WIRE, 64U, OTA_STATUS_OK);

    // ---- flash failures abort and discard ----
    fake_reset(1);
    command_parse_init(NULL, NULL);
    reset_recorders();
    start_hello(PROTO_LINK_USB, 64U, "write-error");
    start_begin(PROTO_LINK_USB, 64U, "write-error");
    fake.write_result = 0;
    reset_recorders();
    feed_wire_hex(PROTO_LINK_USB, OTA_VEC_CHUNKS[0].wire_hex, 64U);
    expect("flash write failure answers FLASH_ERROR",
           rec_status(link_rec(PROTO_LINK_USB)) == OTA_STATUS_FLASH_ERROR);
    expect("flash write failure discards the image", fake.abort_calls == 1);
    expect("flash write failure resumes RMT RX", fake.resume_calls == 1);
    expect_status("CHUNK after flash write failure is BAD_STATE",
                  PROTO_LINK_USB, OTA_VEC_CHUNKS[0].wire_hex, 64U,
                  OTA_STATUS_BAD_STATE);

    fake_reset(1);
    command_parse_init(NULL, NULL);
    reset_recorders();
    start_hello(PROTO_LINK_USB, 64U, "finish-error");
    start_begin(PROTO_LINK_USB, 64U, "finish-error");
    for (size_t i = 0; i < OTA_VEC_CHUNK_COUNT; ++i) {
        feed_wire_hex(PROTO_LINK_USB, OTA_VEC_CHUNKS[i].wire_hex, 64U);
    }
    fake.finish_result = 0;
    reset_recorders();
    feed_wire_hex(PROTO_LINK_USB, OTA_VEC_END_WIRE, 64U);
    expect("flash finish failure answers FLASH_ERROR",
           rec_status(link_rec(PROTO_LINK_USB)) == OTA_STATUS_FLASH_ERROR);
    expect("flash finish failure discards the image", fake.abort_calls == 1);
    expect("flash finish failure does not set the boot partition",
           fake.boot_partition_set == 0);

    // ---- BEGIN flash failure consumes the nonce ----
    fake_reset(1);
    command_parse_init(NULL, NULL);
    reset_recorders();
    fake.begin_result = 0;
    start_hello(PROTO_LINK_USB, 64U, "begin-error");
    expect_status("flash begin failure answers FLASH_ERROR", PROTO_LINK_USB,
                  OTA_VEC_BEGIN_WIRE, 64U, OTA_STATUS_FLASH_ERROR);
    expect_status("BEGIN after flash begin failure is BAD_STATE",
                  PROTO_LINK_USB, OTA_VEC_BEGIN_WIRE, 64U,
                  OTA_STATUS_BAD_STATE);

    // ---- unknown op ----
    fake_reset(1);
    command_parse_init(NULL, NULL);
    reset_recorders();
    {
        uint8_t raw[WIRE_MAX];
        size_t raw_length = build_ota_cmd(99U, NULL, 0U, raw);
        feed_raw(PROTO_LINK_USB, raw, raw_length, 64U);
        expect("unknown OTA op answers BAD_STATE",
               rec_status(link_rec(PROTO_LINK_USB)) == OTA_STATUS_BAD_STATE);
    }

    // ---- idle timeout (30 s) ----
    fake_reset(1);
    command_parse_init(NULL, NULL);
    reset_recorders();
    start_hello(PROTO_LINK_USB, 64U, "idle");
    start_begin(PROTO_LINK_USB, 64U, "idle");
    fake.now += OTA_SESSION_TIMEOUT_MS - 1U;
    ota_session_poll(fake.now);
    expect("session survives just under 30 s", fake.abort_calls == 0);
    fake.now += 1U;
    ota_session_poll(fake.now);
    expect("30 s idle aborts and discards the image", fake.abort_calls == 1);
    expect("idle abort resumes RMT RX", fake.resume_calls == 1);
    expect_status("CHUNK after idle timeout is BAD_STATE", PROTO_LINK_USB,
                  OTA_VEC_CHUNKS[0].wire_hex, 64U, OTA_STATUS_BAD_STATE);
    expect_status("new HELLO works after idle timeout", PROTO_LINK_USB,
                  OTA_VEC_HELLO_WIRE, 64U, OTA_STATUS_OK);

    // ---- ABORT ----
    fake_reset(1);
    command_parse_init(NULL, NULL);
    reset_recorders();
    start_hello(PROTO_LINK_USB, 64U, "abort");
    start_begin(PROTO_LINK_USB, 64U, "abort");
    feed_wire_hex(PROTO_LINK_USB, OTA_VEC_CHUNKS[0].wire_hex, 64U);
    reset_recorders();
    feed_wire_hex(PROTO_LINK_USB, OTA_VEC_ABORT_WIRE, 64U);
    {
        uint8_t expected[16];
        size_t expected_length = build_ota_resp(OTA_OP_ABORT, OTA_STATUS_OK,
                                                NULL, 0, expected);
        expect("ABORT answers OK",
               rec_equals_raw(link_rec(PROTO_LINK_USB), expected,
                              expected_length));
    }
    expect("ABORT discards the partial image", fake.abort_calls == 1);
    expect("ABORT resumes RMT RX", fake.resume_calls == 1);
    expect_status("CHUNK after ABORT is BAD_STATE", PROTO_LINK_USB,
                  OTA_VEC_CHUNKS[0].wire_hex, 64U, OTA_STATUS_BAD_STATE);
    expect_status("ABORT outside a session is OK", PROTO_LINK_USB,
                  OTA_VEC_ABORT_WIRE, 64U, OTA_STATUS_OK);

    // ---- BOOTLOADER good/bad MAC ----
    fake_reset(1);
    command_parse_init(NULL, NULL);
    reset_recorders();
    start_hello(PROTO_LINK_USB, 64U, "bootloader");
    {
        uint8_t raw[WIRE_MAX];
        size_t raw_length = build_bootloader_raw(fake.secret, raw);
        uint8_t expected[WIRE_MAX];
        size_t expected_length =
            hex_decode(OTA_VEC_BOOTLOADER_RAW, expected, sizeof(expected));
        expect("constructed BOOTLOADER equals vector",
               raw_length == expected_length &&
                   memcmp(raw, expected, raw_length) == 0);
    }
    expect_status("BOOTLOADER with good MAC is OK", PROTO_LINK_USB,
                  OTA_VEC_BOOTLOADER_WIRE, 64U, OTA_STATUS_OK);
    expect("BOOTLOADER reboot is delayed", fake.reboot_bootloader_calls == 0);
    fake.now += OTA_BOOTLOADER_REBOOT_DELAY_MS - 1U;
    ota_session_poll(fake.now);
    expect("no bootloader reboot before delay",
           fake.reboot_bootloader_calls == 0);
    fake.now += 1U;
    ota_session_poll(fake.now);
    expect("reboot_to_bootloader after delay",
           fake.reboot_bootloader_calls == 1);
    expect("BOOTLOADER does not reboot to app", fake.reboot_app_calls == 0);

    fake_reset(1);
    command_parse_init(NULL, NULL);
    reset_recorders();
    start_hello(PROTO_LINK_USB, 64U, "bootloader-bad");
    {
        uint8_t zero_secret[OTA_SECRET_LEN];
        memset(zero_secret, 0, sizeof(zero_secret));
        uint8_t raw[WIRE_MAX];
        size_t raw_length = build_bootloader_raw(zero_secret, raw);
        reset_recorders();
        feed_raw(PROTO_LINK_USB, raw, raw_length, 64U);
        expect("BOOTLOADER with bad MAC answers BAD_MAC",
               rec_status(link_rec(PROTO_LINK_USB)) == OTA_STATUS_BAD_MAC);
    }
    expect("bad BOOTLOADER MAC does not reboot",
           fake.reboot_bootloader_calls == 0);
    expect_status("bad BOOTLOADER MAC consumes the nonce", PROTO_LINK_USB,
                  OTA_VEC_BOOTLOADER_WIRE, 64U, OTA_STATUS_BAD_STATE);

    // ---- DISABLED without a valid secret ----
    fake_reset(0);
    command_parse_init(NULL, NULL);
    reset_recorders();
    expect_status("HELLO is DISABLED without a secret", PROTO_LINK_USB,
                  OTA_VEC_HELLO_WIRE, 64U, OTA_STATUS_DISABLED);
    expect_status("BEGIN is DISABLED without a secret", PROTO_LINK_USB,
                  OTA_VEC_BEGIN_WIRE, 64U, OTA_STATUS_DISABLED);
    expect_status("CHUNK is DISABLED without a secret", PROTO_LINK_USB,
                  OTA_VEC_CHUNKS[0].wire_hex, 64U, OTA_STATUS_DISABLED);
    expect_status("END is DISABLED without a secret", PROTO_LINK_USB,
                  OTA_VEC_END_WIRE, 64U, OTA_STATUS_DISABLED);
    expect_status("BOOTLOADER is DISABLED without a secret", PROTO_LINK_USB,
                  OTA_VEC_BOOTLOADER_WIRE, 64U, OTA_STATUS_DISABLED);
    expect_status("ABORT still works without a secret", PROTO_LINK_USB,
                  OTA_VEC_ABORT_WIRE, 64U, OTA_STATUS_OK);

    expect("64-hex secret parses",
           ota_secret_from_hex(OTA_VEC_SECRET_HEX, fake.secret) == 1);
    expect("63-hex secret is rejected",
           ota_secret_from_hex(
               "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d"
               "1e",
               fake.secret) == 0);
    expect("non-hex secret is rejected",
           ota_secret_from_hex(
               "zz0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d"
               "1e1f",
               fake.secret) == 0);

    fake_reset(1);
    fake.secret_length = OTA_SECRET_LEN - 1U;
    refresh_backend();
    ota_session_init(&test_backend);
    command_parse_init(NULL, NULL);
    reset_recorders();
    expect_status("wrong secret length is DISABLED", PROTO_LINK_USB,
                  OTA_VEC_HELLO_WIRE, 64U, OTA_STATUS_DISABLED);

    // ---- chunk data length 0 / > 192 ----
    fake_reset(1);
    command_parse_init(NULL, NULL);
    reset_recorders();
    start_hello(PROTO_LINK_USB, 64U, "bad-length");
    start_begin(PROTO_LINK_USB, 64U, "bad-length");
    {
        uint8_t raw[WIRE_MAX];
        size_t raw_length = build_ota_cmd(OTA_OP_CHUNK, NULL, 0, raw);
        reset_recorders();
        feed_raw(PROTO_LINK_USB, raw, raw_length, 64U);
        expect("CHUNK without an offset/data is BAD_LENGTH",
               rec_status(link_rec(PROTO_LINK_USB)) == OTA_STATUS_BAD_LENGTH);

        uint8_t payload[4U + OTA_MAX_CHUNK + 1U];
        put_u32_le(payload, 0U);
        memset(&payload[4], 0x5A, OTA_MAX_CHUNK + 1U);
        raw_length = build_ota_cmd(OTA_OP_CHUNK, payload, sizeof(payload),
                                   raw);
        reset_recorders();
        feed_raw(PROTO_LINK_USB, raw, raw_length, 64U);
        expect("CHUNK with 193 data bytes is BAD_LENGTH",
               rec_status(link_rec(PROTO_LINK_USB)) == OTA_STATUS_BAD_LENGTH);

        payload[0] = 0U;
        payload[1] = 0U;
        payload[2] = 0U;
        payload[3] = 0U;
        raw_length = build_ota_cmd(OTA_OP_CHUNK, payload, 4U, raw);
        reset_recorders();
        feed_raw(PROTO_LINK_USB, raw, raw_length, 64U);
        expect("CHUNK with only an offset is BAD_LENGTH",
               rec_status(link_rec(PROTO_LINK_USB)) == OTA_STATUS_BAD_LENGTH);
    }
    expect_exchange_wire("valid chunk after BAD_LENGTH works", PROTO_LINK_USB,
                         OTA_VEC_CHUNKS[0].wire_hex, 64U, NULL);

    // ---- corrupted CRC is dropped silently, session survives ----
    fake_reset(1);
    command_parse_init(NULL, NULL);
    reset_recorders();
    start_hello(PROTO_LINK_USB, 64U, "crc");
    start_begin(PROTO_LINK_USB, 64U, "crc");
    {
        command_parse_stats_t before = command_parse_stats();
        uint8_t raw[WIRE_MAX];
        size_t raw_length =
            vector_wire_to_raw(OTA_VEC_CHUNKS[0].wire_hex, raw, sizeof(raw));
        expect("chunk vector decodes", raw_length != 0U);
        raw[raw_length - 1U] ^= 0xFFU;
        uint8_t wire[WIRE_MAX];
        size_t wire_length =
            proto_cobs_encode(raw, raw_length, wire, sizeof(wire));
        reset_recorders();
        feed_bytes(PROTO_LINK_USB, wire, wire_length, 64U);
        expect("corrupted CRC produces no response",
               link_rec(PROTO_LINK_USB)->length == 0U);
        expect("corrupted CRC increments rejected",
               command_parse_stats().rejected == before.rejected + 1U);
    }
    expect_exchange_wire("session survives a corrupted chunk",
                         PROTO_LINK_USB, OTA_VEC_CHUNKS[0].wire_hex, 64U,
                         NULL);

    return test_result();
}
