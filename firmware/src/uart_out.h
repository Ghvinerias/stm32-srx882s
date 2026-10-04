#pragma once

#include <stddef.h>
#include <stdint.h>

// USART1 on PA9 TX / PA10 RX, 115200 8N1. TX is queued and DMA-driven.
int uart_init(void);
int uart_write(const uint8_t *data, size_t length);
size_t uart_available(void);
int uart_read_byte(uint8_t *value);
