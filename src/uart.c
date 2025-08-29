#include "uart.h"
#include "board.h"
#include <stdarg.h>
#include <stdio.h>

UART_HandleTypeDef huart1;

void MX_USART1_UART_Init(void)
{
    __HAL_RCC_GPIOA_CLK_ENABLE();
    UARTx_CLK_EN();

    GPIO_InitTypeDef GPIO_InitStruct = {0};
    // TX
    GPIO_InitStruct.Pin   = UARTx_TX_PIN;
    GPIO_InitStruct.Mode  = GPIO_MODE_AF_PP;
    GPIO_InitStruct.Speed = GPIO_SPEED_FREQ_HIGH;
    HAL_GPIO_Init(UARTx_TX_GPIO_PORT, &GPIO_InitStruct);
    // RX
    GPIO_InitStruct.Pin  = UARTx_RX_PIN;
    GPIO_InitStruct.Mode = GPIO_MODE_INPUT;
    GPIO_InitStruct.Pull = GPIO_NOPULL;
    HAL_GPIO_Init(UARTx_RX_GPIO_PORT, &GPIO_InitStruct);

    huart1.Instance          = UARTx_INSTANCE;
    huart1.Init.BaudRate     = 115200;
    huart1.Init.WordLength   = UART_WORDLENGTH_8B;
    huart1.Init.StopBits     = UART_STOPBITS_1;
    huart1.Init.Parity       = UART_PARITY_NONE;
    huart1.Init.Mode         = UART_MODE_TX_RX;
    huart1.Init.HwFlowCtl    = UART_HWCONTROL_NONE;
    huart1.Init.OverSampling = UART_OVERSAMPLING_16;
    if (HAL_UART_Init(&huart1) != HAL_OK) {
        while (1);
    }
}

void uart_puts(const char* s)
{
    HAL_UART_Transmit(&huart1, (uint8_t*)s, (uint16_t)strlen(s), HAL_MAX_DELAY);
}

void uart_printf(const char* fmt, ...)
{
    static char buf[256];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    uart_puts(buf);
}