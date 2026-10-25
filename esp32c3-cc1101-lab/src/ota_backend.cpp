#include "ota_backend.h"

#include <Arduino.h>
#include <esp_ota_ops.h>
#include <esp_random.h>
#include <esp_system.h>
#include <mbedtls/md.h>
#include <mbedtls/sha256.h>
#include <soc/rtc_cntl_reg.h>
#include <soc/soc.h>
#include <string.h>

#include "radio_rmt.h"

// ESP32-C3 OTA backend.  The portable state machine calls all of these; this
// file is the only place that touches ESP-IDF flash, mbedtls, the RTC download
// flag or the reboot API.

static ota_backend_t backend_state;
static const esp_partition_t *update_partition;
static esp_ota_handle_t ota_handle;
static bool ota_open;
static mbedtls_sha256_context sha_context;
static bool sha_active;

static uint8_t secret_bytes[OTA_SECRET_LEN];
static bool secret_valid;

static int backend_flash_begin(void *context, uint32_t image_size) {
    (void)context;

    if (ota_open) {
        (void)esp_ota_abort(ota_handle);
        ota_open = false;
    }
    if (sha_active) {
        mbedtls_sha256_free(&sha_context);
        sha_active = false;
    }

    update_partition = esp_ota_get_next_update_partition(NULL);
    if (update_partition == NULL) {
        return 0;
    }
    if ((size_t)image_size > update_partition->size) {
        return 0;
    }

    size_t erase_size =
        image_size == 0U ? (size_t)OTA_SIZE_UNKNOWN : (size_t)image_size;
    esp_err_t error =
        esp_ota_begin(update_partition, erase_size, &ota_handle);
    if (error != ESP_OK) {
        return 0;
    }
    ota_open = true;
    return 1;
}

static int backend_flash_write(void *context, uint32_t offset,
                               const uint8_t *data, size_t length) {
    (void)context;
    (void)offset; // esp_ota_write() is sequential; the session checked it
    if (!ota_open || (data == NULL && length != 0U)) {
        return 0;
    }
    return esp_ota_write(ota_handle, data, length) == ESP_OK ? 1 : 0;
}

static int backend_flash_finish(void *context) {
    (void)context;

    if (!ota_open) {
        return 0;
    }
    // esp_ota_end() invalidates the handle whether or not it succeeds.
    esp_err_t error = esp_ota_end(ota_handle);
    ota_open = false;
    if (error != ESP_OK) {
        return 0;
    }
    if (update_partition == NULL) {
        return 0;
    }
    return esp_ota_set_boot_partition(update_partition) == ESP_OK ? 1 : 0;
}

static void backend_flash_abort(void *context) {
    (void)context;
    if (ota_open) {
        (void)esp_ota_abort(ota_handle);
        ota_open = false;
    }
    if (sha_active) {
        mbedtls_sha256_free(&sha_context);
        sha_active = false;
    }
}

static void backend_sha_init(void *context) {
    (void)context;
    if (sha_active) {
        mbedtls_sha256_free(&sha_context);
    }
    mbedtls_sha256_init(&sha_context);
    mbedtls_sha256_starts(&sha_context, 0);
    sha_active = true;
}

static void backend_sha_update(void *context, const uint8_t *data,
                               size_t length) {
    (void)context;
    if (!sha_active) {
        return;
    }
    mbedtls_sha256_update(&sha_context, data, length);
}

static void backend_sha_final(void *context, uint8_t out[OTA_SHA256_LEN]) {
    (void)context;
    if (!sha_active) {
        memset(out, 0, OTA_SHA256_LEN);
        return;
    }
    mbedtls_sha256_finish(&sha_context, out);
    mbedtls_sha256_free(&sha_context);
    sha_active = false;
}

static void backend_hmac_sha256(void *context, const uint8_t *key,
                                size_t key_length, const uint8_t *data,
                                size_t length,
                                uint8_t out[OTA_SHA256_LEN]) {
    (void)context;
    const mbedtls_md_info_t *info =
        mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
    if (info == NULL || key == NULL || (data == NULL && length != 0U)) {
        memset(out, 0, OTA_SHA256_LEN);
        return;
    }
    if (mbedtls_md_hmac(info, key, key_length, data, length, out) != 0) {
        memset(out, 0, OTA_SHA256_LEN);
    }
}

static void backend_random_bytes(void *context, uint8_t *out, size_t length) {
    (void)context;
    size_t offset = 0;
    while (offset < length) {
        uint32_t value = esp_random();
        size_t copy = length - offset < sizeof(value) ? length - offset
                                                       : sizeof(value);
        memcpy(out + offset, &value, copy);
        offset += copy;
    }
}

static void backend_reboot_to_app(void *context) {
    (void)context;
    ESP.restart();
}

static void backend_reboot_to_bootloader(void *context) {
    (void)context;
    // ESP32-C3: force the ROM USB/UART downloader on the next reset instead
    // of booting the application.  (Header names verified against
    // esp32c3-libs 3.3.12; behaviour not verified on hardware.)
    REG_WRITE(RTC_CNTL_OPTION1_REG, RTC_CNTL_FORCE_DOWNLOAD_BOOT);
    esp_restart();
}

static uint32_t backend_now_ms(void *context) {
    (void)context;
    return (uint32_t)millis();
}

static void backend_rx_suspend(void *context) {
    (void)context;
    radio_rmt_rx_suspend();
}

static void backend_rx_resume(void *context) {
    (void)context;
    radio_rmt_rx_resume();
}

void ota_backend_init(void) {
    memset(&backend_state, 0, sizeof(backend_state));
    update_partition = esp_ota_get_next_update_partition(NULL);
    ota_open = false;
    sha_active = false;
    secret_valid = false;

    if (update_partition != NULL) {
        backend_state.slot_size = update_partition->size;
    }

#ifdef LAB_OTA_SECRET_HEX
    static const char secret_text[] = LAB_OTA_SECRET_HEX;
    if (ota_secret_from_hex(secret_text, secret_bytes)) {
        secret_valid = true;
    }
#endif

    if (secret_valid) {
        backend_state.secret = secret_bytes;
        backend_state.secret_length = OTA_SECRET_LEN;
    }

    backend_state.flash_begin = backend_flash_begin;
    backend_state.flash_write = backend_flash_write;
    backend_state.flash_finish = backend_flash_finish;
    backend_state.flash_abort = backend_flash_abort;
    backend_state.sha256_init = backend_sha_init;
    backend_state.sha256_update = backend_sha_update;
    backend_state.sha256_final = backend_sha_final;
    backend_state.hmac_sha256 = backend_hmac_sha256;
    backend_state.random_bytes = backend_random_bytes;
    backend_state.reboot_to_app = backend_reboot_to_app;
    backend_state.reboot_to_bootloader = backend_reboot_to_bootloader;
    backend_state.now_ms = backend_now_ms;
    backend_state.rx_suspend = backend_rx_suspend;
    backend_state.rx_resume = backend_rx_resume;
}

const ota_backend_t *ota_backend_get(void) { return &backend_state; }
