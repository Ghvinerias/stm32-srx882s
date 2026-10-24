#pragma once

#include <stddef.h>
#include <stdint.h>

// OTA / bootloader protocol state machine (packet types 6 and 7).
//
// The normative wire description is ../ota/OTA_PROTOCOL.md, the reference
// encoder is ../ota/ota_ref.py and the golden packet bytes are
// ../ota/OTA_VECTORS.json.
//
// This file is portable C: no Arduino/ESP-IDF headers.  Every side effect
// (flash, SHA-256, HMAC, RNG, reboot, RMT RX suspend/resume, clock) is a
// callback in ota_backend_t, so the whole state machine runs in host tests.
//
// Framing (identical to types 1-5):
//   OTA_CMD  (6, host->dev): [1][6][op][payload][crc16 LE]
//   OTA_RESP (7, dev->host): [1][7][op][status][payload][crc16 LE]
// after COBS encoding and a 0x00 delimiter.  CRC-16/CCITT-FALSE.

#define OTA_MAX_CHUNK 192U
#define OTA_NONCE_LEN 16U
#define OTA_SHA256_LEN 32U
#define OTA_SECRET_LEN 32U

// The idle timer is measured from the last CRC-valid OTA packet.
#define OTA_SESSION_TIMEOUT_MS 30000U
// Reboot delays required by the spec ("~300 ms" / "~200 ms").
#define OTA_APP_REBOOT_DELAY_MS 300U
#define OTA_BOOTLOADER_REBOOT_DELAY_MS 200U

typedef enum {
    OTA_OP_HELLO = 1,
    OTA_OP_BEGIN = 2,
    OTA_OP_CHUNK = 3,
    OTA_OP_END = 4,
    OTA_OP_ABORT = 5,
    OTA_OP_BOOTLOADER = 6,
} ota_op_t;

typedef enum {
    OTA_STATUS_OK = 0,
    OTA_STATUS_BAD_STATE = 1,
    OTA_STATUS_BAD_LENGTH = 2,
    OTA_STATUS_DISABLED = 3,
    OTA_STATUS_BAD_MAC = 4,
    OTA_STATUS_TOO_BIG = 5,
    OTA_STATUS_BAD_HASH = 6,
    OTA_STATUS_BAD_OFFSET = 7,
    OTA_STATUS_FLASH_ERROR = 8,
} ota_status_t;

// HELLO response capability bits.  Bit 0 is the only bit defined today.
#define OTA_CAPS_BASE 1U

// Backend callbacks.  All return 1 for success / 0 for failure unless noted.
// context is passed through unchanged.  sha256_* form one incremental hash
// per BEGIN/END pair; hmac_sha256 is one-shot.
typedef struct {
    void *context;

    int (*flash_begin)(void *context, uint32_t image_size);
    int (*flash_write)(void *context, uint32_t offset, const uint8_t *data,
                       size_t length);
    // Must call esp_ota_end() and esp_ota_set_boot_partition().
    int (*flash_finish)(void *context);
    void (*flash_abort)(void *context);

    void (*sha256_init)(void *context);
    void (*sha256_update)(void *context, const uint8_t *data, size_t length);
    void (*sha256_final)(void *context, uint8_t out[OTA_SHA256_LEN]);

    void (*hmac_sha256)(void *context, const uint8_t *key, size_t key_length,
                        const uint8_t *data, size_t length,
                        uint8_t out[OTA_SHA256_LEN]);

    void (*random_bytes)(void *context, uint8_t *out, size_t length);

    void (*reboot_to_app)(void *context);
    void (*reboot_to_bootloader)(void *context);

    uint32_t (*now_ms)(void *context);
    void (*rx_suspend)(void *context);
    void (*rx_resume)(void *context);

    // Size of the inactive OTA slot in bytes (0x1F0000 with no_fs).
    uint32_t slot_size;

    // 32-byte shared secret.  NULL or any other length means DISABLED.
    const uint8_t *secret;
    size_t secret_length;
} ota_backend_t;

#ifdef __cplusplus
extern "C" {
#endif

// Resets the state machine (does not touch the backend).
void ota_session_init(const ota_backend_t *backend);

// Handles one already-CRC-checked OTA_CMD packet.  op/payload exclude the
// version/type/op header and the trailing CRC.  Responses are sent through
// proto_send_ota_resp() to `link` only.
void ota_session_handle(uint8_t link, uint8_t op, const uint8_t *payload,
                        size_t payload_length);

// Drives the idle timeout and the delayed reboot.  Call from the main loop
// with millis() (or any monotonic millisecond counter).
void ota_session_poll(uint32_t now_ms);

// Aborts a session owned by `link` (link disconnect/unplug).
void ota_session_link_lost(uint8_t link);

// 1 while a BEGIN-owned OTA session has RMT RX suspended.
int ota_session_rx_suspended(void);

// Parses exactly 64 hex characters into 32 bytes.  Returns 1 on success.
// Used by the ESP32 backend for the LAB_OTA_SECRET_HEX build define.
int ota_secret_from_hex(const char *hex, uint8_t out[OTA_SECRET_LEN]);

#ifdef __cplusplus
}
#endif
