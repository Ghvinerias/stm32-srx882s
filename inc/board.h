#pragma once

// Board pin mapping for Blue Pill-like STM32F103C8T6
// RF data input from 433 MHz receiver
#define RF_GPIO_PORT      GPIOA
#define RF_GPIO_CLK_EN()  __HAL_RCC_GPIOA_CLK_ENABLE()
#define RF_PIN            GPIO_PIN_0        // PA0 -> EXTI0

// UART for logs
#define UARTx                 USART1
#define UARTx_INSTANCE        USART1
#define UARTx_CLK_EN()        __HAL_RCC_USART1_CLK_ENABLE()
#define UARTx_TX_GPIO_PORT    GPIOA
#define UARTx_TX_PIN          GPIO_PIN_9
#define UARTx_RX_GPIO_PORT    GPIOA
#define UARTx_RX_PIN          GPIO_PIN_10

// LED
#define LED_GPIO_PORT     GPIOC
#define LED_GPIO_CLK_EN() __HAL_RCC_GPIOC_CLK_ENABLE()
#define LED_PIN           GPIO_PIN_13