#include "uart_out.h"

#if defined(BUILD_HOST)

#include <stdio.h>

int uart_init(void) { return 1; }
int uart_write(const uint8_t *data, size_t length) {
    return fwrite(data, 1, length, stdout) == length;
}
size_t uart_available(void) { return 65535U; }
int uart_read_byte(uint8_t *value) {
    (void)value;
    return 0;
}

#else

#include "stm32f1xx_hal.h"

#define UART_TX_BUFFER_SIZE 2048U
#define UART_TX_BUFFER_MASK (UART_TX_BUFFER_SIZE - 1U)

static uint8_t tx_buffer[UART_TX_BUFFER_SIZE];
static volatile uint16_t tx_head;
static volatile uint16_t tx_tail;
static volatile uint16_t dma_length;
static volatile uint8_t dma_active;

UART_HandleTypeDef huart1;
DMA_HandleTypeDef hdma_usart1_tx;

static size_t available_locked(void) {
    return (tx_tail - tx_head - 1U) & UART_TX_BUFFER_MASK;
}

static void start_tx_locked(void) {
    if (dma_active || tx_head == tx_tail) {
        return;
    }

    uint16_t contiguous = tx_head > tx_tail
                              ? (uint16_t)(tx_head - tx_tail)
                              : (uint16_t)(UART_TX_BUFFER_SIZE - tx_tail);
    dma_length = contiguous;
    dma_active = 1;
    if (HAL_UART_Transmit_DMA(&huart1, &tx_buffer[tx_tail], contiguous) != HAL_OK) {
        dma_active = 0;
        dma_length = 0;
    }
}

int uart_init(void) {
    __HAL_RCC_USART1_CLK_ENABLE();
    __HAL_RCC_GPIOA_CLK_ENABLE();
    __HAL_RCC_DMA1_CLK_ENABLE();

    GPIO_InitTypeDef gpio = {0};
    gpio.Pin = GPIO_PIN_9;
    gpio.Mode = GPIO_MODE_AF_PP;
    gpio.Speed = GPIO_SPEED_FREQ_HIGH;
    HAL_GPIO_Init(GPIOA, &gpio);

    gpio.Pin = GPIO_PIN_10;
    gpio.Mode = GPIO_MODE_INPUT;
    gpio.Pull = GPIO_NOPULL;
    HAL_GPIO_Init(GPIOA, &gpio);

    hdma_usart1_tx.Instance = DMA1_Channel4;
    hdma_usart1_tx.Init.Direction = DMA_MEMORY_TO_PERIPH;
    hdma_usart1_tx.Init.PeriphInc = DMA_PINC_DISABLE;
    hdma_usart1_tx.Init.MemInc = DMA_MINC_ENABLE;
    hdma_usart1_tx.Init.PeriphDataAlignment = DMA_PDATAALIGN_BYTE;
    hdma_usart1_tx.Init.MemDataAlignment = DMA_MDATAALIGN_BYTE;
    hdma_usart1_tx.Init.Mode = DMA_NORMAL;
    hdma_usart1_tx.Init.Priority = DMA_PRIORITY_MEDIUM;
    if (HAL_DMA_Init(&hdma_usart1_tx) != HAL_OK) {
        return 0;
    }

    huart1.Instance = USART1;
    huart1.Init.BaudRate = 115200;
    huart1.Init.WordLength = UART_WORDLENGTH_8B;
    huart1.Init.StopBits = UART_STOPBITS_1;
    huart1.Init.Parity = UART_PARITY_NONE;
    huart1.Init.Mode = UART_MODE_TX_RX;
    huart1.Init.HwFlowCtl = UART_HWCONTROL_NONE;
    huart1.Init.OverSampling = UART_OVERSAMPLING_16;
    __HAL_LINKDMA(&huart1, hdmatx, hdma_usart1_tx);
    if (HAL_UART_Init(&huart1) != HAL_OK) {
        return 0;
    }

    tx_head = 0;
    tx_tail = 0;
    dma_length = 0;
    dma_active = 0;

    HAL_NVIC_SetPriority(DMA1_Channel4_IRQn, 2, 0);
    HAL_NVIC_EnableIRQ(DMA1_Channel4_IRQn);
    HAL_NVIC_SetPriority(USART1_IRQn, 2, 1);
    HAL_NVIC_EnableIRQ(USART1_IRQn);
    return 1;
}

size_t uart_available(void) {
    uint32_t primask = __get_PRIMASK();
    __disable_irq();
    size_t available = available_locked();
    if (!primask) {
        __enable_irq();
    }
    return available;
}

int uart_read_byte(uint8_t *value) {
    if (!value) {
        return 0;
    }
    if (__HAL_UART_GET_FLAG(&huart1, UART_FLAG_ORE)) {
        volatile uint32_t discard = huart1.Instance->SR;
        discard = huart1.Instance->DR;
        (void)discard;
        return 0;
    }
    if (!__HAL_UART_GET_FLAG(&huart1, UART_FLAG_RXNE)) {
        return 0;
    }
    *value = (uint8_t)huart1.Instance->DR;
    return 1;
}

int uart_write(const uint8_t *data, size_t length) {
    if (!data || length == 0 || length >= UART_TX_BUFFER_SIZE) {
        return 0;
    }

    uint32_t primask = __get_PRIMASK();
    __disable_irq();
    if (available_locked() < length) {
        if (!primask) {
            __enable_irq();
        }
        return 0;
    }

    for (size_t i = 0; i < length; ++i) {
        tx_buffer[tx_head] = data[i];
        tx_head = (uint16_t)((tx_head + 1U) & UART_TX_BUFFER_MASK);
    }
    start_tx_locked();
    if (!primask) {
        __enable_irq();
    }
    return 1;
}

void HAL_UART_TxCpltCallback(UART_HandleTypeDef *huart) {
    if (huart->Instance != USART1) {
        return;
    }
    tx_tail = (uint16_t)((tx_tail + dma_length) & UART_TX_BUFFER_MASK);
    dma_length = 0;
    dma_active = 0;
    start_tx_locked();
}

void DMA1_Channel4_IRQHandler(void) {
    HAL_DMA_IRQHandler(&hdma_usart1_tx);
}

void USART1_IRQHandler(void) {
    HAL_UART_IRQHandler(&huart1);
}

#endif
