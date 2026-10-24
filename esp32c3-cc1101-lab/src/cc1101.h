#pragma once

#include <stddef.h>
#include <stdint.h>

// Minimal CC1101 driver for the ESP32-C3 lab node.
//
// The radio is used in asynchronous OOK mode at 433.92 MHz:
//   * GDO0 is the data INPUT to the CC1101 while it is in TX (RMT drives it)
//   * GDO2 is the sliced data OUTPUT while it is in RX (RMT captures it)
//
// The register set is Flipper Zero's public Ook270Async/Ook650Async preset
// (lib/subghz/devices/cc1101_configs.c), which is itself based on the TI E2E
// thread referenced there.  The CC1101 is half-duplex; callers must switch
// states with cc1101_set_tx()/cc1101_set_rx() around every transmission.

enum {
    CC1101_PRESET_OOK270 = 270,
    CC1101_PRESET_OOK650 = 650,
};

// Full reset + register configuration.  khz is 270 or 650.  Returns false if
// the SPI device does not identify itself, in which case the transport still
// runs so the host can see STATUS/ACK traffic.
bool cc1101_init(unsigned khz);

// Re-write the bandwidth-dependent registers (and re-enter RX).  Re-runs the
// full init if the chip was never initialised.
bool cc1101_set_preset(unsigned khz);

bool cc1101_set_tx(void);
bool cc1101_set_rx(void);
void cc1101_idle(void);

bool cc1101_is_initialized(void);

void cc1101_set_spi_hz(uint32_t hz);
uint32_t cc1101_spi_hz(void);
// SPI signal-integrity self-test (scratch register FSCTRL0, several clocks).
void cc1101_spi_selftest(char *out, size_t capacity);
// Bit-stickiness sweep of registers 0x00..0x2E (destructive: run `reinit` after).
void cc1101_reg_sweep(char *out, size_t capacity);

// Live status reads (valid only when initialised).
int cc1101_rssi_dbm(void);        // converted per datasheet (offset 74 dB)
uint8_t cc1101_pktstatus(void);   // bit2 = GDO2 level, bit0 = GDO0 level
uint8_t cc1101_marcstate(void);
unsigned cc1101_preset_khz(void);

// "RADIO CC1101 partnum=0x00 version=0x14 preset=OOK270 state=RX\r\n"
void cc1101_describe(char *out, size_t capacity);

// "REGS IOCFG2=0d IOCFG0=0d ... PATABLE=00 c0 00 ..." : live read-back of the
// registers that decide whether the CC1101 can modulate and radiate OOK.
void cc1101_dump_regs(char *out, size_t capacity);
