#pragma once
#include "stm32f1xx_hal.h"
#include "board.h"

void MX_GPIO_Init(void);
static inline void led_on(void)  { HAL_GPIO_WritePin(LED_GPIO_PORT, LED_PIN, GPIO_PIN_RESET); }
static inline void led_off(void) { HAL_GPIO_WritePin(LED_GPIO_PORT, LED_PIN, GPIO_PIN_SET); }
static inline void led_toggle(void) { HAL_GPIO_TogglePin(LED_GPIO_PORT, LED_PIN); }