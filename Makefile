# Project: STM32F1 433 MHz Protocol Classifier & Decoder (MVP)
# Toolchain: arm-none-eabi-gcc
# Requires: STM32CubeF1 package path provided via CUBE_DIR env var
# Example: export CUBE_DIR=~/STM32Cube/Repository/STM32Cube_FW_F1_V1.8.5

PROJECT := stm32-srx882s
TARGET  := firmware

# Toolchain
CC      := arm-none-eabi-gcc
CXX     := arm-none-eabi-g++
AS      := arm-none-eabi-as
LD      := arm-none-eabi-gcc
AR      := arm-none-eabi-ar
OBJCOPY := arm-none-eabi-objcopy
OBJDUMP := arm-none-eabi-objdump
SIZE    := arm-none-eabi-size

# MCU and flags
MCU     := cortex-m3
FCPU    := 72000000

CFLAGS  := -mcpu=$(MCU) -mthumb -mfloat-abi=soft -ffunction-sections -fdata-sections -Wall -Wextra -Wno-unused-parameter -std=c11
CFLAGS  += -O2 -g3 -DSTM32F103xB -DUSE_HAL_DRIVER
LDFLAGS := -mcpu=$(MCU) -mthumb -mfloat-abi=soft -Wl,--gc-sections -Wl,-Map,build/$(TARGET).map -T ld/stm32f103c8t6.ld

# Paths
CUBE_DRIVERS_INC := $(CUBE_DIR)/Drivers
CMSIS_INC  := $(CUBE_DRIVERS_INC)/CMSIS/Include
DEVICE_INC := $(CUBE_DRIVERS_INC)/CMSIS/Device/ST/STM32F1xx/Include
HAL_INC    := $(CUBE_DRIVERS_INC)/STM32F1xx_HAL_Driver/Inc

STARTUP   := $(CUBE_DRIVERS_INC)/CMSIS/Device/ST/STM32F1xx/Source/Templates/gcc/startup_stm32f103xb.s
SYSTEMSRC := $(CUBE_DRIVERS_INC)/CMSIS/Device/ST/STM32F1xx/Source/Templates/system_stm32f1xx.c
HAL_SRCS  := $(CUBE_DRIVERS_INC)/STM32F1xx_HAL_Driver/Src/stm32f1xx_hal.c \
             $(CUBE_DRIVERS_INC)/STM32F1xx_HAL_Driver/Src/stm32f1xx_hal_rcc.c \
             $(CUBE_DRIVERS_INC)/STM32F1xx_HAL_Driver/Src/stm32f1xx_hal_rcc_ex.c \
             $(CUBE_DRIVERS_INC)/STM32F1xx_HAL_Driver/Src/stm32f1xx_hal_gpio.c \
             $(CUBE_DRIVERS_INC)/STM32F1xx_HAL_Driver/Src/stm32f1xx_hal_dma.c \
             $(CUBE_DRIVERS_INC)/STM32F1xx_HAL_Driver/Src/stm32f1xx_hal_cortex.c \
             $(CUBE_DRIVERS_INC)/STM32F1xx_HAL_Driver/Src/stm32f1xx_hal_uart.c \
             $(CUBE_DRIVERS_INC)/STM32F1xx_HAL_Driver/Src/stm32f1xx_hal_tim.c \
             $(CUBE_DRIVERS_INC)/STM32F1xx_HAL_Driver/Src/stm32f1xx_hal_tim_ex.c

INCLUDES := -Iinc -Isrc -I$(CMSIS_INC) -I$(DEVICE_INC) -I$(HAL_INC)

SRCS := \
  src/main.c \
  src/system_clock.c \
  src/gpio.c \
  src/uart.c \
  src/ringbuf.c \
  src/rf_capture.c \
  src/classifier.c \
  src/decoders/ev1527.c \
  src/decoders/pt2262.c \
  src/decoders/oregon2.c

OBJS := $(SRCS:src/%.c=build/%.o) \
        build/system_stm32f1xx.o \
        $(HAL_SRCS:$(CUBE_DRIVERS_INC)/STM32F1xx_HAL_Driver/Src/%.c=build/hal/%.o)

DEPS := $(OBJS:.o=.d)

# Default target
all: check-cube build/$(TARGET).elf build/$(TARGET).bin

check-cube:
	@if [ ! -d "$(CUBE_DIR)" ]; then \
	  echo "Error: CUBE_DIR is not set or invalid. e.g. export CUBE_DIR=~/STM32Cube/Repository/STM32Cube_FW_F1_Vx.y.z"; \
	  exit 1; \
	fi

build:
	mkdir -p build build/hal build/decoders

build/%.o: src/%.c | build
	$(CC) $(CFLAGS) $(INCLUDES) -MMD -MP -c $< -o $@

build/hal/%.o: $(CUBE_DRIVERS_INC)/STM32F1xx_HAL_Driver/Src/%.c | build
	$(CC) $(CFLAGS) $(INCLUDES) -MMD -MP -c $< -o $@

build/system_stm32f1xx.o: $(SYSTEMSRC) | build
	$(CC) $(CFLAGS) $(INCLUDES) -MMD -MP -c $< -o $@

build/startup.o: $(STARTUP) | build
	$(CC) -c -x assembler-with-cpp -mcpu=$(MCU) -mthumb $< -o $@

build/$(TARGET).elf: build/startup.o $(OBJS)
	$(LD) $^ $(LDFLAGS) -o $@
	$(SIZE) $@

build/$(TARGET).bin: build/$(TARGET).elf
	$(OBJCOPY) -O binary $< $@

flash: build/$(TARGET).elf
	openocd -f interface/stlink.cfg -f target/stm32f1x.cfg -c "program $< verify reset exit"

monitor:
	python3 scripts/log_serial.py --port $(PORT) --baud 115200

test:
	python3 -m pip install -q -r scripts/requirements.txt || true
	python3 tests/test_runner.py

clean:
	rm -rf build

.PHONY: all clean flash monitor test check-cube

-include $(DEPS)