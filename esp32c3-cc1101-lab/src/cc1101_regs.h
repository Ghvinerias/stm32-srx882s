#pragma once

// CC1101 register / strobe addresses, TI SWRS061I tables 45 and 46.
// Kept in a plain header so a host test pins them to the datasheet.

// Register addresses per TI SWRS061I table 45 (config registers 0x00-0x2E).
// NOTE: an earlier revision had every address from PKTCTRL0 upward shifted
// down by one (e.g. PKTCTRL0 at 0x07 = PKTCTRL1), so the radio never entered
// async-serial/OOK mode nor the intended frequency.
#define CC1101_IOCFG2 0x00U
#define CC1101_IOCFG1 0x01U
#define CC1101_IOCFG0 0x02U
#define CC1101_FIFOTHR 0x03U
#define CC1101_PKTCTRL0 0x08U
#define CC1101_FSCTRL1 0x0BU
#define CC1101_FSCTRL0 0x0CU
#define CC1101_FREQ2 0x0DU
#define CC1101_FREQ1 0x0EU
#define CC1101_FREQ0 0x0FU
#define CC1101_MDMCFG4 0x10U
#define CC1101_MDMCFG3 0x11U
#define CC1101_MDMCFG2 0x12U
#define CC1101_MDMCFG1 0x13U
#define CC1101_MDMCFG0 0x14U
#define CC1101_DEVIATN 0x15U
#define CC1101_MCSM1 0x17U
#define CC1101_MCSM0 0x18U
#define CC1101_FOCCFG 0x19U
#define CC1101_AGCCTRL2 0x1BU
#define CC1101_AGCCTRL1 0x1CU
#define CC1101_AGCCTRL0 0x1DU
#define CC1101_WORCTRL 0x20U
#define CC1101_FREND1 0x21U
#define CC1101_FREND0 0x22U
#define CC1101_FSCAL3 0x23U
#define CC1101_FSCAL2 0x24U
#define CC1101_FSCAL1 0x25U
#define CC1101_FSCAL0 0x26U
#define CC1101_TEST2 0x2CU
#define CC1101_TEST1 0x2DU
#define CC1101_TEST0 0x2EU
#define CC1101_PATABLE 0x3EU
#define CC1101_MARCSTATE 0x35U

#define CC1101_PARTNUM 0x30U
#define CC1101_VERSION 0x31U

#define CC1101_SRES 0x30U
#define CC1101_SRX 0x34U
#define CC1101_STX 0x35U
#define CC1101_SIDLE 0x36U
#define CC1101_SFRX 0x3AU
#define CC1101_SFTX 0x3BU
#define CC1101_RSSI 0x34U
#define CC1101_PKTSTATUS 0x38U
