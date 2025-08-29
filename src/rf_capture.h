#pragma once
#include "stm32f1xx_hal.h"
#include "ringbuf.h"
#include "classifier.h"

void rf_capture_init(void);
void rf_process(ProtocolCtx* ctx);

// Stats helpers (optional)
typedef struct {
    volatile uint32_t edges;
    volatile uint32_t overruns;
} rf_stats_t;

extern rf_stats_t g_rf_stats;