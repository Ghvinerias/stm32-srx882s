#pragma once
#include <stddef.h>

// Initialize UART (USART1 PA9=TX)
void uart_init(void);

// Send a null-terminated string over UART (non-blocking wrapper)
void uart_send(const char *s);
