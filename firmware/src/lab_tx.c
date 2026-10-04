#include "lab_tx.h"

#if defined(BUILD_HOST)

int lab_tx_init(void) { return 1; }
int lab_tx_send_stale_replay(void) { return 1; }
int lab_tx_send_codeword(uint32_t hopping, uint32_t serial, uint8_t button,
                         uint8_t frame_count) {
    (void)hopping;
    (void)serial;
    (void)button;
    (void)frame_count;
    return 1;
}
int lab_tx_run_scenario(uint8_t command) {
    (void)command;
    return 1;
}

#else

#include "stm32f1xx_hal.h"

// Edge-to-edge durations, in microseconds, from the first complete frame in
// tools/captures/keeloq-button1.ndjson. The frame has already been transmitted
// by the original remote, so a receiver enforcing rolling-code freshness must
// reject it. Keeping this as measured timing also validates the STX882 path
// before adding a synthetic packet encoder.
static const uint16_t stale_frame_us[] = {
    785, 758, 758, 761, 757, 759, 761, 754, 761, 762, 757, 4516,
    795, 1481, 797, 1477, 1139, 797, 1139, 1136, 1482, 1140, 1136,
    797, 1480, 795, 1136, 1483, 1140, 1137, 799, 1478, 798, 1482,
    795, 1136, 1137, 1482, 799, 1136, 1137, 1480, 795, 1482, 1141,
    795, 1136, 1481, 1141, 1136, 1137, 1141, 1140, 795, 1137, 1482,
    1139, 1137, 796, 1139, 1482, 796, 1480, 796, 1137, 1485, 792,
    1139, 1136, 1141, 1137, 1485, 795, 1477, 1141, 1137, 1140,
};

#define ORIGINAL_SERIAL 0x0FB58C0CU
#define COPIER_SERIAL 0x01114F9AU
#define ORIGINAL_BUTTON_1 0x02U
#define STALE_HOPPING 0xBBA8D1CAU

// Measured edge-spacing centers. Data uses one transition per three-unit bit
// cell: equal adjacent bits are MID; 0->1 is SHORT; 1->0 is LONG.
#define PREAMBLE_EDGE_US 760U
#define HEADER_BIT_ZERO_US 4512U
#define HEADER_BIT_ONE_US 4170U
#define DATA_SHORT_US 795U
#define DATA_MID_US 1138U
#define DATA_LONG_US 1482U
#define INTER_FRAME_GAP_US 15500U

static uint16_t plaintext_counter = 1U;
static uint32_t prng_state = 0x6D2B79F5U;

static void wait_timer4_us(uint16_t duration_us) {
    uint16_t start = (uint16_t)TIM4->CNT;
    while ((uint16_t)((uint16_t)TIM4->CNT - start) < duration_us) {
        // TIM4 is already configured by hw_capture at one tick per microsecond.
    }
}

int lab_tx_init(void) {
    __HAL_RCC_GPIOB_CLK_ENABLE();

    // Set the output latch low before enabling the pin to avoid a transmitter
    // glitch during initialization.
    GPIOB->BRR = GPIO_PIN_6;
    GPIO_InitTypeDef gpio = {0};
    gpio.Pin = GPIO_PIN_6;
    gpio.Mode = GPIO_MODE_OUTPUT_PP;
    gpio.Pull = GPIO_NOPULL;
    gpio.Speed = GPIO_SPEED_FREQ_HIGH;
    HAL_GPIO_Init(GPIOB, &gpio);
    return 1;
}

int lab_tx_send_stale_replay(void) {
    if ((TIM4->CR1 & TIM_CR1_CEN) == 0U) {
        return 0;
    }

    uint32_t primask = __get_PRIMASK();
    __disable_irq();

    // The capture began on a rising edge and reported initial_level=1.
    GPIOB->BSRR = GPIO_PIN_6;
    for (size_t i = 0; i < sizeof(stale_frame_us) / sizeof(stale_frame_us[0]);
         ++i) {
        wait_timer4_us(stale_frame_us[i]);
        if ((i & 1U) == 0U) {
            GPIOB->BRR = GPIO_PIN_6;
        } else {
            GPIOB->BSRR = GPIO_PIN_6;
        }
    }
    GPIOB->BRR = GPIO_PIN_6;

    if (!primask) {
        __enable_irq();
    }
    return 1;
}

static void toggle_pb6(uint8_t *level) {
    *level ^= 1U;
    if (*level) {
        GPIOB->BSRR = GPIO_PIN_6;
    } else {
        GPIOB->BRR = GPIO_PIN_6;
    }
}

static void send_bits_once(const uint8_t bits[66]) {
    uint8_t level = 1U;
    GPIOB->BSRR = GPIO_PIN_6;

    // Twelve preamble edges produce eleven measured edge-to-edge intervals.
    for (uint8_t i = 0; i < 11U; ++i) {
        wait_timer4_us(PREAMBLE_EDGE_US);
        toggle_pb6(&level);
    }

    // Preamble finishes low. The first data transition occurs earlier or
    // later in the cell depending on bit zero.
    wait_timer4_us(bits[0] ? HEADER_BIT_ONE_US : HEADER_BIT_ZERO_US);
    toggle_pb6(&level);

    for (uint8_t i = 0; i < 65U; ++i) {
        uint16_t duration = DATA_MID_US;
        if (!bits[i] && bits[i + 1U]) {
            duration = DATA_SHORT_US;
        } else if (bits[i] && !bits[i + 1U]) {
            duration = DATA_LONG_US;
        }
        wait_timer4_us(duration);
        toggle_pb6(&level);
    }
    GPIOB->BRR = GPIO_PIN_6;
}

int lab_tx_send_codeword(uint32_t hopping, uint32_t serial, uint8_t button,
                         uint8_t frame_count) {
    if ((TIM4->CR1 & TIM_CR1_CEN) == 0U || frame_count == 0U) {
        return 0;
    }
    serial &= 0x0FFFFFFFU;
    button &= 0x0FU;

    for (uint8_t frame = 0; frame < frame_count; ++frame) {
        uint8_t bits[66];
        for (uint8_t i = 0; i < 32U; ++i) {
            bits[i] = (uint8_t)((hopping >> i) & 1U);
        }
        for (uint8_t i = 0; i < 28U; ++i) {
            bits[32U + i] = (uint8_t)((serial >> i) & 1U);
        }
        for (uint8_t i = 0; i < 4U; ++i) {
            bits[60U + i] = (uint8_t)((button >> i) & 1U);
        }
        bits[64] = 0U;                 // VLOW
        bits[65] = frame == 0U ? 0U : 1U; // repeat

        uint32_t primask = __get_PRIMASK();
        __disable_irq();
        send_bits_once(bits);
        if (!primask) {
            __enable_irq();
        }

        if (frame + 1U < frame_count) {
            uint16_t start = (uint16_t)TIM4->CNT;
            while ((uint16_t)((uint16_t)TIM4->CNT - start) <
                   INTER_FRAME_GAP_US) {
            }
        }
    }
    return 1;
}

int lab_tx_run_scenario(uint8_t command) {
    switch (command) {
    case 'r':
        return lab_tx_send_stale_replay();
    case 's':
        return lab_tx_send_codeword(STALE_HOPPING, ORIGINAL_SERIAL,
                                    ORIGINAL_BUTTON_1, 3U);
    case 'z':
        return lab_tx_send_codeword(0U, ORIGINAL_SERIAL, ORIGINAL_BUTTON_1, 3U);
    case 'p': {
        // HCS301 plaintext organization before encryption: sync[15:0],
        // discrimination[25:16], overflow[27:26], button[31:28].
        uint32_t hopping = (uint32_t)plaintext_counter++ |
                           ((ORIGINAL_SERIAL & 0x3FFU) << 16) |
                           ((uint32_t)ORIGINAL_BUTTON_1 << 28);
        return lab_tx_send_codeword(hopping, ORIGINAL_SERIAL,
                                    ORIGINAL_BUTTON_1, 3U);
    }
    case 'x':
        prng_state ^= prng_state << 13;
        prng_state ^= prng_state >> 17;
        prng_state ^= prng_state << 5;
        return lab_tx_send_codeword(prng_state, ORIGINAL_SERIAL,
                                    ORIGINAL_BUTTON_1, 3U);
    case 'd':
        return lab_tx_send_codeword(0U, COPIER_SERIAL, ORIGINAL_BUTTON_1, 3U);
    default:
        return 0;
    }
}

#endif
