#include "system_clock.h"

#if defined(BUILD_HOST)

int system_clock_configure(void) { return 1; }
uint32_t system_clock_hz(void) { return 72000000U; }

#else

#include "stm32f1xx_hal.h"

int system_clock_configure(void) {
    RCC_OscInitTypeDef oscillator = {0};
    oscillator.OscillatorType = RCC_OSCILLATORTYPE_HSE;
    oscillator.HSEState = RCC_HSE_ON;
    oscillator.HSEPredivValue = RCC_HSE_PREDIV_DIV1;
    oscillator.PLL.PLLState = RCC_PLL_ON;
    oscillator.PLL.PLLSource = RCC_PLLSOURCE_HSE;
    oscillator.PLL.PLLMUL = RCC_PLL_MUL9;

    if (HAL_RCC_OscConfig(&oscillator) == HAL_OK) {
        RCC_ClkInitTypeDef clocks = {0};
        clocks.ClockType = RCC_CLOCKTYPE_HCLK | RCC_CLOCKTYPE_SYSCLK |
                           RCC_CLOCKTYPE_PCLK1 | RCC_CLOCKTYPE_PCLK2;
        clocks.SYSCLKSource = RCC_SYSCLKSOURCE_PLLCLK;
        clocks.AHBCLKDivider = RCC_SYSCLK_DIV1;
        clocks.APB1CLKDivider = RCC_HCLK_DIV2;
        clocks.APB2CLKDivider = RCC_HCLK_DIV1;
        if (HAL_RCC_ClockConfig(&clocks, FLASH_LATENCY_2) == HAL_OK) {
            // 72 MHz PLL / 1.5 is the exact 48 MHz required by USB FS.
            __HAL_RCC_USB_CONFIG(RCC_USBCLKSOURCE_PLL_DIV1_5);
            SystemCoreClockUpdate();
            return 1;
        }
    }

    HAL_RCC_DeInit();
    SystemCoreClockUpdate();
    HAL_InitTick(TICK_INT_PRIORITY);
    return 0;
}

uint32_t system_clock_hz(void) {
    return HAL_RCC_GetHCLKFreq();
}

void SysTick_Handler(void) {
    HAL_IncTick();
}

#endif
