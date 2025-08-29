#include "stm32f1xx_hal.h"
#include "stm32f1xx_hal_conf.h"
#include "system_clock.c"
#include "gpio.h"
#include "uart.h"
#include "rf_capture.h"
#include "classifier.h"

int main(void)
{
    HAL_Init();
    SystemClock_Config();
    MX_GPIO_Init();
    MX_USART1_UART_Init();

    uart_puts("\r\nSTM32 433MHz Classifier starting...\r\n");

    rf_capture_init();  // sets up TIM2 timebase and EXTI on PA0

    ProtocolCtx proto = {0};
    classifier_init(&proto);

    uint32_t last_heartbeat = HAL_GetTick();

    while (1) {
        // Process captured pulses
        rf_process(&proto);

        // Periodic heartbeat
        if ((HAL_GetTick() - last_heartbeat) >= 1000) {
            led_toggle();
            last_heartbeat = HAL_GetTick();
        }
    }
}