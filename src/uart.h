#pragma once
#include "stm32f1xx_hal.h"

extern UART_HandleTypeDef huart1;

void MX_USART1_UART_Init(void);
void uart_puts(const char* s);
void uart_printf(const char* fmt, ...);