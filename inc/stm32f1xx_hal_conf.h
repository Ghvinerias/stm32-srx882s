/* Minimal HAL configuration for this project */
#pragma once

#include "stm32f1xx_hal_rcc.h"
#include "stm32f1xx_hal_gpio.h"
#include "stm32f1xx_hal_cortex.h"
#include "stm32f1xx_hal_dma.h"
#include "stm32f1xx_hal_uart.h"
#include "stm32f1xx_hal_tim.h"

#define HAL_MODULE_ENABLED
#define HAL_RCC_MODULE_ENABLED
#define HAL_GPIO_MODULE_ENABLED
#define HAL_CORTEX_MODULE_ENABLED
#define HAL_DMA_MODULE_ENABLED
#define HAL_UART_MODULE_ENABLED
#define HAL_TIM_MODULE_ENABLED

#define HSE_VALUE    ((uint32_t)8000000)
#define HSI_VALUE    ((uint32_t)8000000)
#define LSE_VALUE    ((uint32_t)32768U)
#define LSI_VALUE    ((uint32_t)40000U)

#define TICK_INT_PRIORITY            0x0FU
#define USE_RTOS                     0
#define PREFETCH_ENABLE              1U

/* Assert */
#ifdef  USE_FULL_ASSERT
void assert_failed(uint8_t* file, uint32_t line);
#endif