#include "hw_capture.h"

#if defined(BUILD_HOST)

#include <stdio.h>
// Host stub: no hardware; user must fill pulses via tests
static uint32_t *stub_buf = NULL;
static size_t stub_len = 0;

void hw_capture_init(void) { }
void hw_capture_start(void) { }
size_t hw_capture_fetch_pulses(uint32_t *out_buf, size_t max_pulses) {
    if (!stub_buf || stub_len == 0) return 0;
    size_t n = (stub_len < max_pulses) ? stub_len : max_pulses;
    for (size_t i=0;i<n;i++) out_buf[i] = stub_buf[i];
    return n;
}

// Test helper: set the stub buffer
void _hw_capture_set_stub(uint32_t *buf, size_t len) { stub_buf = buf; stub_len = len; }

#else

#include "stm32f1xx_hal.h"
#include <string.h>

// DMA-based capture using TIM2 CH1 and DMA to collect CCR1 timestamps into a circular buffer.
// Fetch converts timestamps into deltas (microseconds) in a race-free manner.

#define TIM_DMA_BUFFER_SIZE 1024
static volatile uint32_t tim_dma_buffer[TIM_DMA_BUFFER_SIZE];
static volatile size_t tim_dma_head = 0; // index producer (updated by DMA hardware/callback)
static volatile uint32_t last_timestamp = 0;

TIM_HandleTypeDef htim2;
DMA_HandleTypeDef hdma_tim2_ch1;

void hw_capture_init(void) {
    __HAL_RCC_TIM2_CLK_ENABLE();
    __HAL_RCC_GPIOA_CLK_ENABLE();
    __HAL_RCC_DMA1_CLK_ENABLE();

    // Configure PA0 as AF input (TIM2_CH1)
    GPIO_InitTypeDef gi = {0};
    gi.Pin = GPIO_PIN_0;
    gi.Mode = GPIO_MODE_INPUT; // for F1, AFIO mapping not required for TIM2_CH1 on PA0
    gi.Pull = GPIO_NOPULL;
    HAL_GPIO_Init(GPIOA, &gi);

    // TIM2 base init: prescaler for 1MHz tick assuming 72MHz sysclk
    htim2.Instance = TIM2;
    htim2.Init.Prescaler = 72-1; // adjust if system clock differs
    htim2.Init.CounterMode = TIM_COUNTERMODE_UP;
    htim2.Init.Period = 0xFFFFFFFF;
    htim2.Init.ClockDivision = TIM_CLOCKDIVISION_DIV1;
    HAL_TIM_Base_Init(&htim2);

    TIM_IC_InitTypeDef ic = {0};
    ic.ICPolarity = TIM_INPUTCHANNELPOLARITY_BOTHEDGE;
    ic.ICSelection = TIM_ICSELECTION_DIRECTTI;
    ic.ICPrescaler = TIM_ICPSC_DIV1;
    ic.ICFilter = 0;
    HAL_TIM_IC_ConfigChannel(&htim2, &ic, TIM_CHANNEL_1);

    // Configure DMA: TIM2_CH1 -> memory circular
    hdma_tim2_ch1.Instance = DMA1_Channel5; // typical mapping for TIM2 CH1 on F1 series
    hdma_tim2_ch1.Init.Direction = DMA_PERIPH_TO_MEMORY;
    hdma_tim2_ch1.Init.PeriphInc = DMA_PINC_DISABLE;
    hdma_tim2_ch1.Init.MemInc = DMA_MINC_ENABLE;
    hdma_tim2_ch1.Init.PeriphDataAlignment = DMA_PDATAALIGN_WORD;
    hdma_tim2_ch1.Init.MemDataAlignment = DMA_MDATAALIGN_WORD;
    hdma_tim2_ch1.Init.Mode = DMA_CIRCULAR;
    hdma_tim2_ch1.Init.Priority = DMA_PRIORITY_HIGH;
    HAL_DMA_Init(&hdma_tim2_ch1);

    __HAL_LINKDMA(&htim2, hdma[TIM_DMA_ID_CC1], hdma_tim2_ch1);

    // Start capture with DMA
    HAL_TIM_Base_Start(&htim2);
    HAL_TIM_IC_Start_DMA(&htim2, TIM_CHANNEL_1, (uint32_t*)tim_dma_buffer, TIM_DMA_BUFFER_SIZE);

    // Enable DMA IRQ
    HAL_NVIC_SetPriority(DMA1_Channel5_IRQn, 5, 0);
    HAL_NVIC_EnableIRQ(DMA1_Channel5_IRQn);
}

void hw_capture_start(void) {
    // reset pointers
    tim_dma_head = 0;
    last_timestamp = 0;
    // Clear DMA buffer (optional)
    for (size_t i=0;i<TIM_DMA_BUFFER_SIZE;i++) tim_dma_buffer[i] = 0;
}

// Fetch converts the circular buffer into deltas. Returns number of deltas written (max_pulses).
size_t hw_capture_fetch_pulses(uint32_t *out_buf, size_t max_pulses) {
    // Read DMA current index: for circular DMA, remaining data length is in CNDTR
    uint32_t cndtr = hdma_tim2_ch1.Instance->CNDTR;
    size_t dma_pos = (TIM_DMA_BUFFER_SIZE - cndtr) % TIM_DMA_BUFFER_SIZE;
    // Process samples from tim_dma_head up to dma_pos (non-blocking)
    size_t written = 0;
    while (tim_dma_head != dma_pos && written < max_pulses) {
        uint32_t ts = tim_dma_buffer[tim_dma_head];
        uint32_t delta = ts - last_timestamp;
        last_timestamp = ts;
        out_buf[written++] = delta;
        tim_dma_head = (tim_dma_head + 1) % TIM_DMA_BUFFER_SIZE;
    }
    return written;
}

// DMA IRQ handler: clear flags (HAL will handle if linked). Provide ISR to satisfy weak symbol.
void DMA1_Channel5_IRQHandler(void) {
    HAL_DMA_IRQHandler(&hdma_tim2_ch1);
}

#endif
