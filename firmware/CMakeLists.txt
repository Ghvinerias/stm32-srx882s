cmake_minimum_required(VERSION 3.15)
project(stm32-srx882s C ASM)

# Set STM32F4 family and toolchain
set(CMAKE_SYSTEM_NAME Generic)
set(CMAKE_SYSTEM_PROCESSOR arm)

# Add source files
add_executable(stm32-srx882s
    src/main.c
    src/protocol_classifier.c
    src/protocol_decoder.c
)

# TODO: Add STM32 HAL/LL, linker script, startup files

# Example: set board type
set(BOARD NUCLEO-F411RE)

# TODO: Add cross-compilation flags

# TODO: Add unit tests
