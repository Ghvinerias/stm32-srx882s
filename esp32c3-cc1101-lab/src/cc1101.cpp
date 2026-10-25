#include "cc1101.h"

#include <Arduino.h>
#include <SPI.h>

#include <stdio.h>

#include "lab_config.h"

// ---------------------------------------------------------------------------
// Register map (CC1101 datasheet, section 29).
//
// Preset provenance:
//   flipperdevices/flipperzero-firmware, lib/subghz/devices/cc1101_configs.c
//   subghz_device_cc1101_preset_ook_270khz_async_regs[] and
//   ..._ook_650khz_async_regs[].
// The same file documents the only register that matters for OOK edge
// fidelity on the receive side: MDMCFG4 (RX filter bandwidth), together with
// AGCCTRL0/AGCCTRL2 and FIFOTHR's ADC_RETENTION bit.
//
// Differences from Flipper's table, and why:
//   * IOCFG2 = 0x0D as well, because this board takes RX data from GDO2 so
//     GDO0 can stay dedicated to the async TX input.
//   * FREQ2/1/0 = 0x10/0xB0/0x71 for 433.92 MHz
//     (round(433.92e6 * 65536 / 26e6) = 1093745 = 0x10B071).
//   * MCSM1 = 0x0C: RXOFF_MODE = stay in RX, TXOFF_MODE = IDLE.  The driver
//     switches states explicitly, but this keeps the radio from dropping out
//     of RX between CC1101-internal events.
// ---------------------------------------------------------------------------

#include "cc1101_regs.h"

#define CC1101_READ_SINGLE 0x80U
#define CC1101_READ_BURST 0xC0U
#define CC1101_WRITE_BURST 0x40U

#define MARCSTATE_IDLE 0x01U
#define MARCSTATE_RX 0x0DU
#define MARCSTATE_TX 0x13U

struct cc1101_reg {
    uint8_t address;
    uint8_t value;
};

// Common OOK/async configuration.  Written in one pass after reset, then the
// preset-specific registers below.
static const cc1101_reg common_regs[] = {
    {CC1101_IOCFG2, 0x0D},   // GDO2: async serial data output
    {CC1101_IOCFG1, 0x2E},   // GDO1: unused
    {CC1101_IOCFG0, 0x2E},   // GDO0: hi-Z, async TX data INPUT (RX data is on GDO2)
    {CC1101_PKTCTRL0, 0x32}, // async serial, infinite packet, no whitening
    {CC1101_FSCTRL1, 0x06},  // IF = 152.34 kHz
    {CC1101_FSCTRL0, 0x00},
    {CC1101_FREQ2, 0x10}, // 433.92 MHz
    {CC1101_FREQ1, 0xB0},
    {CC1101_FREQ0, 0x71},
    {CC1101_MDMCFG2, 0x30}, // ASK/OOK, no preamble/sync
    {CC1101_MDMCFG1, 0x00},
    {CC1101_MDMCFG0, 0x00},
    {CC1101_DEVIATN, 0x00}, // unused in OOK
    {CC1101_MCSM1, 0x0C},   // TXOFF->IDLE, RXOFF->stay in RX
    {CC1101_MCSM0, 0x18},   // autocalibrate on IDLE->RX/TX
    {CC1101_FOCCFG, 0x18},  // no frequency offset compensation
    {CC1101_WORCTRL, 0xFB}, // WOR_RES = 2^15 periods (unused here)
    {CC1101_FREND1, 0xB6},
    {CC1101_FREND0, 0x11}, // PA_POWER=1: PATABLE[1] while keyed
    // SmartRF/Flipper tuning registers that were left at reset defaults.
    {CC1101_FSCAL3, 0xE9},
    {CC1101_FSCAL2, 0x2A},
    {CC1101_FSCAL1, 0x00},
    {CC1101_FSCAL0, 0x1F},
    {CC1101_TEST2, 0x81},
    {CC1101_TEST1, 0x35},
    {CC1101_TEST0, 0x09},
};

static const cc1101_reg ook270_regs[] = {
    {CC1101_FIFOTHR, 0x47},  // ADC_RETENTION for RX filter > 100 kHz
    {CC1101_MDMCFG4, 0x67},  // RX filter 270.833 kHz
    {CC1101_MDMCFG3, 0x32},  // 3.79372 kBaud
    {CC1101_AGCCTRL2, 0x03}, // MAIN_TARGET 24 dB
    {CC1101_AGCCTRL1, 0x00},
    {CC1101_AGCCTRL0, 0x40}, // low hysteresis, 8-sample AGC
};

static const cc1101_reg ook650_regs[] = {
    {CC1101_FIFOTHR, 0x07},  // ADC_RETENTION for RX filter > 100 kHz
    {CC1101_MDMCFG4, 0x17},  // RX filter 650 kHz
    {CC1101_MDMCFG3, 0x32},  // 3.79372 kBaud
    {CC1101_AGCCTRL2, 0x07}, // MAIN_TARGET 42 dB
    {CC1101_AGCCTRL1, 0x00},
    {CC1101_AGCCTRL0, 0x91}, // medium hysteresis, 16-sample AGC
};

static const uint8_t ook_patable_values[8] = {
    0x00, LAB_CC1101_PATABLE_HIGH, 0x00, 0x00,
    0x00, 0x00,                    0x00, 0x00,
};


static uint32_t spi_hz = LAB_CC1101_SPI_HZ;
static bool initialized;
static uint8_t chip_partnum;
static uint8_t chip_version;
static unsigned preset_khz;

static void cs_low(void) {
    digitalWrite(LAB_PIN_CC1101_CS, LOW);
    delayMicroseconds(1);
}

static void cs_high(void) {
    delayMicroseconds(1);
    digitalWrite(LAB_PIN_CC1101_CS, HIGH);
}

static void spi_write(uint8_t address, uint8_t value) {
    SPI.beginTransaction(
        SPISettings(spi_hz, MSBFIRST, SPI_MODE0));
    cs_low();
    SPI.transfer(address);
    SPI.transfer(value);
    cs_high();
    SPI.endTransaction();
}

static uint8_t spi_read(uint8_t address) {
    SPI.beginTransaction(
        SPISettings(spi_hz, MSBFIRST, SPI_MODE0));
    cs_low();
    SPI.transfer(address);
    uint8_t value = (uint8_t)SPI.transfer(0x00);
    cs_high();
    SPI.endTransaction();
    return value;
}

static void spi_write_burst(uint8_t address, const uint8_t *data,
                            size_t length) {
    SPI.beginTransaction(
        SPISettings(spi_hz, MSBFIRST, SPI_MODE0));
    cs_low();
    SPI.transfer(address);
    for (size_t i = 0; i < length; ++i) {
        SPI.transfer(data[i]);
    }
    cs_high();
    SPI.endTransaction();
}

static void strobe(uint8_t address) {
    SPI.beginTransaction(
        SPISettings(spi_hz, MSBFIRST, SPI_MODE0));
    cs_low();
    SPI.transfer(address);
    cs_high();
    SPI.endTransaction();
}

static uint8_t marcstate(void) {
    return (uint8_t)(spi_read(CC1101_MARCSTATE | CC1101_READ_BURST) & 0x1FU);
}

static bool wait_state(uint8_t wanted, uint32_t timeout_us) {
    uint32_t start = micros();
    do {
        if (marcstate() == wanted) {
            return true;
        }
        delayMicroseconds(20);
    } while ((uint32_t)(micros() - start) < timeout_us);
    return marcstate() == wanted;
}

static void write_regs(const cc1101_reg *regs, size_t count) {
    for (size_t i = 0; i < count; ++i) {
        spi_write(regs[i].address, regs[i].value);
    }
}

static const cc1101_reg *preset_regs(unsigned khz, size_t *count) {
    if (khz == CC1101_PRESET_OOK650) {
        *count = sizeof(ook650_regs) / sizeof(ook650_regs[0]);
        return ook650_regs;
    }
    *count = sizeof(ook270_regs) / sizeof(ook270_regs[0]);
    return ook270_regs;
}

static bool apply_configuration(unsigned khz) {
    write_regs(common_regs, sizeof(common_regs) / sizeof(common_regs[0]));

    size_t preset_count = 0;
    const cc1101_reg *preset = preset_regs(khz, &preset_count);
    write_regs(preset, preset_count);

    // Burst-write all eight PATABLE entries: address 0x3E with the burst bit.
    spi_write_burst(CC1101_PATABLE | CC1101_WRITE_BURST, ook_patable_values,
                    sizeof(ook_patable_values));

    preset_khz = khz;
    return cc1101_set_rx();
}

bool cc1101_init(unsigned khz) {
    initialized = false;

    pinMode(LAB_PIN_CC1101_CS, OUTPUT);
    digitalWrite(LAB_PIN_CC1101_CS, HIGH);

    SPI.begin(LAB_PIN_SPI_SCK, LAB_PIN_SPI_MISO, LAB_PIN_SPI_MOSI,
              LAB_PIN_CC1101_CS);

    // Datasheet reset sequence: CS high, then a low pulse, then SRES.
    digitalWrite(LAB_PIN_CC1101_CS, HIGH);
    delayMicroseconds(10);
    digitalWrite(LAB_PIN_CC1101_CS, LOW);
    delayMicroseconds(10);
    digitalWrite(LAB_PIN_CC1101_CS, HIGH);
    delayMicroseconds(40);
    strobe(CC1101_SRES);
    delay(1);

    if (!wait_state(MARCSTATE_IDLE, 20000U)) {
        return false;
    }

    chip_partnum = spi_read(CC1101_PARTNUM | CC1101_READ_BURST);
    chip_version = spi_read(CC1101_VERSION | CC1101_READ_BURST);
    // CC1101: PARTNUM 0x00, VERSION 0x14.  CC1100 (compatible enough for
    // this OOK use) reports 0x04.  All-ones means MISO is not connected.
    if (chip_partnum == 0xFFU && chip_version == 0xFFU) {
        return false;
    }

    // apply_configuration() ends by putting the radio in RX through the
    // public state-change helper, which refuses to run unless the driver is
    // marked ready.  Mark it ready first, then roll back on failure.
    initialized = true;
    if (!apply_configuration(khz)) {
        initialized = false;
        return false;
    }

    return true;
}

bool cc1101_set_preset(unsigned khz) {
    if (khz != CC1101_PRESET_OOK270 && khz != CC1101_PRESET_OOK650) {
        return false;
    }
    if (!initialized) {
        return cc1101_init(khz);
    }
    return apply_configuration(khz);
}

bool cc1101_set_tx(void) {
    if (!initialized) {
        return false;
    }
    strobe(CC1101_SIDLE);
    if (!wait_state(MARCSTATE_IDLE, 2000U)) {
        return false;
    }
    strobe(CC1101_SFTX);
    strobe(CC1101_STX);
    return wait_state(MARCSTATE_TX, 5000U);
}

bool cc1101_set_rx(void) {
    if (!initialized) {
        return false;
    }
    strobe(CC1101_SIDLE);
    if (!wait_state(MARCSTATE_IDLE, 2000U)) {
        return false;
    }
    strobe(CC1101_SFRX);
    strobe(CC1101_SRX);
    return wait_state(MARCSTATE_RX, 5000U);
}

void cc1101_idle(void) {
    if (!initialized) {
        return;
    }
    strobe(CC1101_SIDLE);
    (void)wait_state(MARCSTATE_IDLE, 2000U);
}

bool cc1101_is_initialized(void) { return initialized; }
unsigned cc1101_preset_khz(void) { return preset_khz; }

void cc1101_describe(char *out, size_t capacity) {
    if (!out || capacity == 0U) {
        return;
    }
    if (!initialized) {
        snprintf(out, capacity,
                 "RADIO CC1101 NOT_READY partnum=0x%02x version=0x%02x\r\n",
                 chip_partnum, chip_version);
        return;
    }

    const char *state = "OTHER";
    uint8_t value = marcstate();
    if (value == MARCSTATE_IDLE) {
        state = "IDLE";
    } else if (value == MARCSTATE_RX) {
        state = "RX";
    } else if (value == MARCSTATE_TX) {
        state = "TX";
    }

    snprintf(out, capacity,
             "RADIO CC1101 partnum=0x%02x version=0x%02x preset=OOK%u "
             "freq=433.92MHz state=%s iocfg0=0x%02x pktstatus=0x%02x "
             "rssi=0x%02x\r\n",
             chip_partnum, chip_version, preset_khz, state,
             spi_read(CC1101_IOCFG0 | CC1101_READ_SINGLE),
             spi_read(CC1101_PKTSTATUS | CC1101_READ_BURST),
             spi_read(CC1101_RSSI | CC1101_READ_BURST));
}

void cc1101_dump_regs(char *out, size_t capacity) {
    if (!out || capacity == 0U) {
        return;
    }
    if (!initialized) {
        snprintf(out, capacity, "REGS NOT_READY\r\n");
        return;
    }
    static const struct {
        const char *name;
        uint8_t address;
    } regs[] = {
        {"IOCFG2", CC1101_IOCFG2},   {"IOCFG0", CC1101_IOCFG0},
        {"FIFOTHR", CC1101_FIFOTHR}, {"PKTCTRL0", CC1101_PKTCTRL0},
        {"FSCTRL1", CC1101_FSCTRL1}, {"FREQ2", CC1101_FREQ2},
        {"FREQ1", CC1101_FREQ1},     {"FREQ0", CC1101_FREQ0},
        {"MDMCFG4", CC1101_MDMCFG4}, {"MDMCFG3", CC1101_MDMCFG3},
        {"MDMCFG2", CC1101_MDMCFG2}, {"DEVIATN", CC1101_DEVIATN},
        {"MCSM1", CC1101_MCSM1},     {"MCSM0", CC1101_MCSM0},
        {"FREND1", CC1101_FREND1},   {"FREND0", CC1101_FREND0},
        {"AGCCTRL2", CC1101_AGCCTRL2}, {"AGCCTRL0", CC1101_AGCCTRL0},
    };
    size_t used = (size_t)snprintf(out, capacity, "REGS");
    for (size_t i = 0; i < sizeof(regs) / sizeof(regs[0]) && used < capacity;
         ++i) {
        used += (size_t)snprintf(out + used, capacity - used, " %s=%02x",
                                 regs[i].name,
                                 spi_read(regs[i].address | CC1101_READ_SINGLE));
    }
    uint8_t pa[8];
    SPI.beginTransaction(SPISettings(spi_hz, MSBFIRST, SPI_MODE0));
    cs_low();
    SPI.transfer(CC1101_PATABLE | CC1101_READ_BURST);
    for (size_t i = 0; i < sizeof(pa); ++i) {
        pa[i] = (uint8_t)SPI.transfer(0x00);
    }
    cs_high();
    SPI.endTransaction();
    if (used < capacity) {
        used += (size_t)snprintf(
            out + used, capacity - used,
            " PATABLE=%02x,%02x,%02x,%02x,%02x,%02x,%02x,%02x\r\n", pa[0],
            pa[1], pa[2], pa[3], pa[4], pa[5], pa[6], pa[7]);
    }
}

int cc1101_rssi_dbm(void) {
    int raw = spi_read(CC1101_RSSI | CC1101_READ_BURST);
    return raw >= 128 ? (raw - 256) / 2 - 74 : raw / 2 - 74;
}

uint8_t cc1101_pktstatus(void) {
    return spi_read(CC1101_PKTSTATUS | CC1101_READ_BURST);
}

uint8_t cc1101_marcstate(void) { return marcstate(); }

void cc1101_set_spi_hz(uint32_t hz) { spi_hz = hz; }
uint32_t cc1101_spi_hz(void) { return spi_hz; }

// Walks every byte value through FSCTRL0 (harmless scratch register) at several
// SPI clocks and reports mismatches, to expose marginal signal integrity.
void cc1101_spi_selftest(char *out, size_t capacity) {
    if (!out || capacity == 0U) {
        return;
    }
    static const uint32_t speeds[] = {4000000U, 2000000U, 1000000U, 500000U,
                                      250000U};
    uint32_t saved = spi_hz;
    uint8_t saved_reg = spi_read(CC1101_FSCTRL0 | CC1101_READ_SINGLE);
    size_t used = (size_t)snprintf(out, capacity, "SPITEST");
    for (size_t i = 0; i < sizeof(speeds) / sizeof(speeds[0]) && used < capacity;
         ++i) {
        spi_hz = speeds[i];
        unsigned bad = 0, first_w = 0, first_r = 0;
        for (unsigned v = 0; v < 256U; ++v) {
            spi_write(CC1101_FSCTRL0, (uint8_t)v);
            uint8_t r = spi_read(CC1101_FSCTRL0 | CC1101_READ_SINGLE);
            if (r != v) {
                if (bad == 0U) {
                    first_w = v;
                    first_r = r;
                }
                ++bad;
            }
        }
        used += (size_t)snprintf(out + used, capacity - used,
                                 " %ukHz:bad=%u(first %02x->%02x)",
                                 (unsigned)(speeds[i] / 1000U), bad, first_w,
                                 first_r);
    }
    // Detail: a few patterns on two scratch registers, read back twice.
    static const uint8_t probes[] = {0x40, 0x80, 0xC0, 0xFF, 0x55, 0xAA, 0x3F};
    static const uint8_t regs2[] = {CC1101_FSCTRL0, CC1101_DEVIATN};
    spi_hz = 1000000U;
    for (size_t r = 0; r < sizeof(regs2) && used < capacity; ++r) {
        uint8_t keep = spi_read(regs2[r] | CC1101_READ_SINGLE);
        used += (size_t)snprintf(out + used, capacity - used, " [r%02x", regs2[r]);
        for (size_t i = 0; i < sizeof(probes) && used < capacity; ++i) {
            spi_write(regs2[r], probes[i]);
            uint8_t a = spi_read(regs2[r] | CC1101_READ_SINGLE);
            uint8_t b = spi_read(regs2[r] | CC1101_READ_SINGLE);
            used += (size_t)snprintf(out + used, capacity - used, " %02x>%02x,%02x",
                                     probes[i], a, b);
        }
        spi_write(regs2[r], keep);
        used += (size_t)snprintf(out + used, capacity - used, "]");
    }
    spi_hz = saved;
    spi_write(CC1101_FSCTRL0, saved_reg);
    if (used < capacity) {
        snprintf(out + used, capacity - used, "\r\n");
    }
}

// For every config register 0x00..0x2E write 0xFF, 0x55, 0xAA, 0x00 and report
// which bits actually stick (fixed/reserved bits, SPI faults, clones).  The
// chip must be re-initialised afterwards (caller does cc1101_init()).
void cc1101_reg_sweep(char *out, size_t capacity) {
    if (!out || capacity == 0U) {
        return;
    }
    size_t used = (size_t)snprintf(out, capacity, "SWEEP (addr:ff,55,aa,00 readbacks)");
    cc1101_idle();
    for (uint8_t a = 0; a <= 0x2EU && used < capacity; ++a) {
        if (a == 0x07U || a == 0x08U) { /* leave packet engine alone */ }
        uint8_t r[4];
        const uint8_t v[4] = {0xFF, 0x55, 0xAA, 0x00};
        for (int i = 0; i < 4; ++i) {
            spi_write(a, v[i]);
            r[i] = spi_read(a | CC1101_READ_SINGLE);
        }
        if (r[0] != 0xFF || r[1] != 0x55 || r[2] != 0xAA || r[3] != 0x00) {
            used += (size_t)snprintf(out + used, capacity - used, " %02x:%02x,%02x,%02x,%02x",
                                     a, r[0], r[1], r[2], r[3]);
        }
    }
    if (used < capacity) {
        snprintf(out + used, capacity - used, "\r\n");
    }
}
