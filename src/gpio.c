#include "gpio.h"

void MX_GPIO_Init(void)
{
    LED_GPIO_CLK_EN();
    RF_GPIO_CLK_EN();

    GPIO_InitTypeDef GPIO_InitStruct = {0};

    // LED PC13 (active low)
    GPIO_InitStruct.Pin   = LED_PIN;
    GPIO_InitStruct.Mode  = GPIO_MODE_OUTPUT_PP;
    GPIO_InitStruct.Pull  = GPIO_NOPULL;
    GPIO_InitStruct.Speed = GPIO_SPEED_FREQ_LOW;
    HAL_GPIO_Init(LED_GPIO_PORT, &GPIO_InitStruct);
    led_off();

    // RF input PA0 as EXTI later (input pull-up for noisy modules)
    GPIO_InitStruct.Pin  = RF_PIN;
    GPIO_InitStruct.Mode = GPIO_MODE_INPUT;
    GPIO_InitStruct.Pull = GPIO_PULLUP;
    HAL_GPIO_Init(RF_GPIO_PORT, &GPIO_InitStruct);
}