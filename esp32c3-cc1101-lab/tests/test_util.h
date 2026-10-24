#pragma once

#include <stdint.h>
#include <stdio.h>
#include <string.h>

static int test_failures;

static inline void expect(const char *label, int condition) {
    if (!condition) {
        printf("FAIL %s\n", label);
        ++test_failures;
    } else {
        printf("ok   %s\n", label);
    }
}

static inline int test_result(void) {
    if (test_failures == 0) {
        printf("ALL PASS\n");
        return 0;
    }
    printf("%d FAILURE(S)\n", test_failures);
    return 1;
}

#define REC_MAX 8192U

typedef struct {
    uint8_t data[REC_MAX];
    size_t length;
} rec_t;

static inline void rec_reset(rec_t *rec) {
    rec->length = 0U;
}

static inline void rec_append(rec_t *rec, const uint8_t *data, size_t length) {
    if (rec->length + length > REC_MAX) {
        printf("FAIL recorder overflow\n");
        ++test_failures;
        return;
    }
    memcpy(rec->data + rec->length, data, length);
    rec->length += length;
}

typedef struct {
    rec_t usb;
    rec_t ble;
    uint8_t deliver_mask;
} link_ctx_t;

static inline uint8_t link_write(void *context, uint8_t targets,
                          const uint8_t *data, size_t length) {
    link_ctx_t *ctx = (link_ctx_t *)context;
    uint8_t delivered = 0U;
    if ((targets & 0x01U) && (ctx->deliver_mask & 0x01U)) {
        rec_append(&ctx->usb, data, length);
        delivered |= 0x01U;
    }
    if ((targets & 0x02U) && (ctx->deliver_mask & 0x02U)) {
        rec_append(&ctx->ble, data, length);
        delivered |= 0x02U;
    }
    return delivered;
}
