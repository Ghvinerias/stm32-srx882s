#include "rf_capture.h"
#include "board.h"
#include "uart.h"

static TIM_HandleTypeDef htim2;
static ringbuf_u32_t rb;
rf_stats_t g_rf_stats = {0};

static volatile uint16_t last_cnt = 0;

static void tim2_timebase_init(void)
{
    __HAL_RCC_TIM2_CLK_ENABLE();

    htim2.Instance = TIM2;
    // 1 MHz timebase (1 us per tick)
    uint32_t pclk1 = HAL_RCC_GetPCLK1Freq(); // APB1 (36 MHz when SYSCLK=72MHz)
    // If APB1 prescaler != 1, timer clock is x2
    uint32_t tclk = (RCC->CFGR & RCC_CFGR_PPRE1_DIV1) ? pclk1 : (pclk1 * 2);
    uint32_t presc = (tclk / 1000000U) - 1U;

    htim2.Init.Prescaler         = presc;
    htim2.Init.CounterMode       = TIM_COUNTERMODE_UP;
    htim2.Init.Period            = 0xFFFF;
    htim2.Init.ClockDivision     = TIM_CLOCKDIVISION_DIV1;
    htim2.Init.AutoReloadPreload = TIM_AUTORELOAD_PRELOAD_DISABLE;
    if (HAL_TIM_Base_Init(&htim2) != HAL_OK) while (1);
    HAL_TIM_Base_Start(&htim2);
}

static void exti_rf_init(void)
{
    // Configure PA0 as EXTI0 (both edges)
    GPIO_InitTypeDef GPIO_InitStruct = {0};
    RF_GPIO_CLK_EN();

    GPIO_InitStruct.Pin  = RF_PIN;
    GPIO_InitStruct.Mode = GPIO_MODE_IT_RISING_FALLING;
    GPIO_InitStruct.Pull = GPIO_PULLUP;
    HAL_GPIO_Init(RF_GPIO_PORT, &GPIO_InitStruct);

    HAL_NVIC_SetPriority(EXTI0_IRQn, 1, 0);
    HAL_NVIC_EnableIRQ(EXTI0_IRQn);
}

void rf_capture_init(void)
{
    rb_init(&rb);
    tim2_timebase_init();
    exti_rf_init();
}

void HAL_GPIO_EXTI_Callback(uint16_t GPIO_Pin)
{
    if (GPIO_Pin != RF_PIN) return;

    uint16_t now = __HAL_TIM_GET_COUNTER(&htim2);
    uint16_t dt  = (uint16_t)(now - last_cnt); // handles wrap naturally
    last_cnt = now;

    // Push delta in microseconds
    if (!rb_push(&rb, (uint32_t)dt)) {
        g_rf_stats.overruns++;
    } else {
        g_rf_stats.edges++;
    }
}

void EXTI0_IRQHandler(void)
{
    HAL_GPIO_EXTI_IRQHandler(RF_PIN);
}

void rf_process(ProtocolCtx* ctx)
{
    uint32_t us;
    while (rb_pop(&rb, &us)) {
        classifier_feed(ctx, (uint32_t)us);
    }
}