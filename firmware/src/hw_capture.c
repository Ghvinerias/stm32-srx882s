#include "hw_capture.h"

#if defined(BUILD_HOST)

int hw_capture_init(void) { return 1; }
int hw_capture_start(void) { return 1; }
int hw_capture_poll(capture_frame_t *out_frame) {
    (void)out_frame;
    return 0;
}
uint16_t hw_capture_dropped_frames(void) { return 0; }
uint32_t hw_capture_total_edges(void) { return 0; }

#else

#include "stm32f1xx_hal.h"
#include <string.h>

#define DMA_SAMPLE_COUNT 512U
#define DMA_HALF_COUNT (DMA_SAMPLE_COUNT / 2U)
#define FRAME_GAP_US 8000U
#define FRAME_MIN_PULSES 16U

static volatile uint32_t dma_samples[DMA_SAMPLE_COUNT];
static volatile uint32_t dma_chunks;
static uint32_t consumed_samples;
static uint16_t dropped_frames;
static uint8_t line_level;
static uint8_t frame_active;
static capture_frame_t current_frame;
static uint16_t last_edge_timestamp;
static uint32_t extended_timestamp;

TIM_HandleTypeDef htim4;
DMA_HandleTypeDef hdma_tim4_ch3;

static uint32_t tim4_clock_hz(void) {
    uint32_t clock = HAL_RCC_GetPCLK1Freq();
    if ((RCC->CFGR & RCC_CFGR_PPRE1) != RCC_HCLK_DIV1) {
        clock *= 2U;
    }
    return clock;
}

static uint32_t producer_total(void) {
    uint32_t before;
    uint32_t after;
    uint32_t remaining;

    do {
        before = dma_chunks;
        remaining = __HAL_DMA_GET_COUNTER(&hdma_tim4_ch3);
        after = dma_chunks;
    } while (before != after);

    uint32_t position = DMA_SAMPLE_COUNT - remaining;
    return before * DMA_HALF_COUNT + (position % DMA_HALF_COUNT);
}

static void reset_partial_frame(void) {
    memset(&current_frame, 0, sizeof(current_frame));
    frame_active = 0;
}

static int finish_frame(capture_frame_t *out_frame) {
    if (current_frame.pulse_count < FRAME_MIN_PULSES) {
        if (dropped_frames != UINT16_MAX) {
            dropped_frames++;
        }
        reset_partial_frame();
        return 0;
    }

    *out_frame = current_frame;
    reset_partial_frame();
    return 1;
}

int hw_capture_init(void) {
    __HAL_RCC_TIM4_CLK_ENABLE();
    __HAL_RCC_GPIOB_CLK_ENABLE();
    __HAL_RCC_DMA1_CLK_ENABLE();

    // SRX882R CS is active high. Set its output latch before changing PB7 to
    // push-pull output so the receiver never sees an unintended low pulse.
    HAL_GPIO_WritePin(GPIOB, GPIO_PIN_7, GPIO_PIN_SET);
    GPIO_InitTypeDef gpio = {0};
    gpio.Pin = GPIO_PIN_7;
    gpio.Mode = GPIO_MODE_OUTPUT_PP;
    gpio.Pull = GPIO_NOPULL;
    gpio.Speed = GPIO_SPEED_FREQ_LOW;
    HAL_GPIO_Init(GPIOB, &gpio);

    gpio.Pin = GPIO_PIN_8;
    gpio.Mode = GPIO_MODE_INPUT;
    // Keep an unplugged or disabled receiver input from floating. The SRX882R
    // DATA output actively drives this weak internal pull-down.
    gpio.Pull = GPIO_PULLDOWN;
    HAL_GPIO_Init(GPIOB, &gpio);

    uint32_t timer_clock = tim4_clock_hz();
    if (timer_clock < 1000000U) {
        return 0;
    }

    htim4.Instance = TIM4;
    htim4.Init.Prescaler = (timer_clock / 1000000U) - 1U;
    htim4.Init.CounterMode = TIM_COUNTERMODE_UP;
    htim4.Init.Period = 0xFFFFU;
    htim4.Init.ClockDivision = TIM_CLOCKDIVISION_DIV1;
    htim4.Init.AutoReloadPreload = TIM_AUTORELOAD_PRELOAD_DISABLE;
    if (HAL_TIM_Base_Init(&htim4) != HAL_OK) {
        return 0;
    }

    TIM_IC_InitTypeDef capture = {0};
    capture.ICPolarity = TIM_INPUTCHANNELPOLARITY_BOTHEDGE;
    capture.ICSelection = TIM_ICSELECTION_DIRECTTI;
    capture.ICPrescaler = TIM_ICPSC_DIV1;
    capture.ICFilter = 0;
    if (HAL_TIM_IC_ConfigChannel(&htim4, &capture, TIM_CHANNEL_3) != HAL_OK) {
        return 0;
    }

    hdma_tim4_ch3.Instance = DMA1_Channel5;
    hdma_tim4_ch3.Init.Direction = DMA_PERIPH_TO_MEMORY;
    hdma_tim4_ch3.Init.PeriphInc = DMA_PINC_DISABLE;
    hdma_tim4_ch3.Init.MemInc = DMA_MINC_ENABLE;
    hdma_tim4_ch3.Init.PeriphDataAlignment = DMA_PDATAALIGN_WORD;
    hdma_tim4_ch3.Init.MemDataAlignment = DMA_MDATAALIGN_WORD;
    hdma_tim4_ch3.Init.Mode = DMA_CIRCULAR;
    hdma_tim4_ch3.Init.Priority = DMA_PRIORITY_VERY_HIGH;
    if (HAL_DMA_Init(&hdma_tim4_ch3) != HAL_OK) {
        return 0;
    }

    __HAL_LINKDMA(&htim4, hdma[TIM_DMA_ID_CC3], hdma_tim4_ch3);
    HAL_NVIC_SetPriority(DMA1_Channel5_IRQn, 1, 0);
    HAL_NVIC_EnableIRQ(DMA1_Channel5_IRQn);
    return 1;
}

int hw_capture_start(void) {
    dma_chunks = 0;
    consumed_samples = 0;
    dropped_frames = 0;
    extended_timestamp = 0;
    reset_partial_frame();
    memset((void *)dma_samples, 0, sizeof(dma_samples));
    line_level = HAL_GPIO_ReadPin(GPIOB, GPIO_PIN_8) == GPIO_PIN_SET ? 1U : 0U;

    if (HAL_TIM_Base_Start(&htim4) != HAL_OK) {
        return 0;
    }
    return HAL_TIM_IC_Start_DMA(&htim4, TIM_CHANNEL_3,
                                (uint32_t *)dma_samples,
                                DMA_SAMPLE_COUNT) == HAL_OK;
}

int hw_capture_poll(capture_frame_t *out_frame) {
    if (!out_frame) {
        return 0;
    }

    uint32_t produced = producer_total();
    uint32_t pending = produced - consumed_samples;
    if (pending > DMA_SAMPLE_COUNT) {
        if (dropped_frames != UINT16_MAX) {
            dropped_frames++;
        }
        consumed_samples = produced;
        reset_partial_frame();
        line_level = HAL_GPIO_ReadPin(GPIOB, GPIO_PIN_8) == GPIO_PIN_SET ? 1U : 0U;
        return 0;
    }

    while (consumed_samples != produced) {
        uint16_t timestamp = (uint16_t)dma_samples[consumed_samples % DMA_SAMPLE_COUNT];
        consumed_samples++;

        line_level ^= 1U;

        if (!frame_active) {
            frame_active = 1;
            extended_timestamp = timestamp;
            current_frame.timestamp_us = extended_timestamp;
            current_frame.initial_level = line_level;
            last_edge_timestamp = timestamp;
            continue;
        }

        uint16_t duration = (uint16_t)(timestamp - last_edge_timestamp);
        extended_timestamp += duration;
        last_edge_timestamp = timestamp;

        if (duration >= FRAME_GAP_US) {
            capture_frame_t completed = current_frame;
            int valid = completed.pulse_count >= FRAME_MIN_PULSES;
            if (!valid && dropped_frames != UINT16_MAX) {
                dropped_frames++;
            }

            reset_partial_frame();
            frame_active = 1;
            current_frame.timestamp_us = extended_timestamp;
            current_frame.initial_level = line_level;

            if (valid) {
                *out_frame = completed;
                return 1;
            }
            continue;
        }

        if (current_frame.pulse_count < HW_CAPTURE_MAX_PULSES) {
            current_frame.pulses[current_frame.pulse_count++] = duration;
        } else {
            current_frame.flags |= HW_CAPTURE_FLAG_TRUNCATED;
        }
    }

    if (frame_active) {
        uint16_t now = (uint16_t)__HAL_TIM_GET_COUNTER(&htim4);
        if ((uint16_t)(now - last_edge_timestamp) >= FRAME_GAP_US) {
            return finish_frame(out_frame);
        }
    }

    return 0;
}

uint16_t hw_capture_dropped_frames(void) {
    return dropped_frames;
}

uint32_t hw_capture_total_edges(void) {
    return producer_total();
}

void HAL_TIM_IC_CaptureHalfCpltCallback(TIM_HandleTypeDef *htim) {
    if (htim->Instance == TIM4) {
        dma_chunks++;
    }
}

void HAL_TIM_IC_CaptureCallback(TIM_HandleTypeDef *htim) {
    if (htim->Instance == TIM4) {
        dma_chunks++;
    }
}

void DMA1_Channel5_IRQHandler(void) {
    HAL_DMA_IRQHandler(&hdma_tim4_ch3);
}

#endif
