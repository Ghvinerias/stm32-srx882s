#pragma once

#include "ota_session.h"

// ESP32-C3 implementation of the OTA backend callbacks (flash, mbedtls
// SHA-256/HMAC, esp_random, RMT RX gating, reboot).
//
// ota_backend_init() must be called once from firmware_app_init() before
// ota_session_init(ota_backend_get()).  If LAB_OTA_SECRET_HEX is undefined or
// is not exactly 64 hex characters, the backend exposes no secret and the
// session answers DISABLED to every op except ABORT.

#ifdef __cplusplus
extern "C" {
#endif

void ota_backend_init(void);
const ota_backend_t *ota_backend_get(void);

#ifdef __cplusplus
}
#endif
