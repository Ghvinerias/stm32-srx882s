// Pins every CC1101 address the driver uses to the TI SWRS061I datasheet
// (table 45/46).  A previous revision had config registers 0x07..0x20 shifted
// down by one, so the chip never entered async-serial OOK mode.
#include "src/cc1101_regs.h"

#include "test_util.h"

int main(void) {
    expect("IOCFG2 0x00", CC1101_IOCFG2 == 0x00U);
    expect("IOCFG0 0x02", CC1101_IOCFG0 == 0x02U);
    expect("FIFOTHR 0x03", CC1101_FIFOTHR == 0x03U);
    expect("PKTCTRL0 0x08 (0x07 is PKTCTRL1)", CC1101_PKTCTRL0 == 0x08U);
    expect("FSCTRL1 0x0B (0x09 ADDR, 0x0A CHANNR)", CC1101_FSCTRL1 == 0x0BU);
    expect("FSCTRL0 0x0C", CC1101_FSCTRL0 == 0x0CU);
    expect("FREQ2 0x0D", CC1101_FREQ2 == 0x0DU);
    expect("FREQ1 0x0E", CC1101_FREQ1 == 0x0EU);
    expect("FREQ0 0x0F", CC1101_FREQ0 == 0x0FU);
    expect("MDMCFG4 0x10", CC1101_MDMCFG4 == 0x10U);
    expect("MDMCFG3 0x11", CC1101_MDMCFG3 == 0x11U);
    expect("MDMCFG2 0x12", CC1101_MDMCFG2 == 0x12U);
    expect("MDMCFG1 0x13", CC1101_MDMCFG1 == 0x13U);
    expect("MDMCFG0 0x14", CC1101_MDMCFG0 == 0x14U);
    expect("DEVIATN 0x15", CC1101_DEVIATN == 0x15U);
    expect("MCSM1 0x17 (0x16 is MCSM2)", CC1101_MCSM1 == 0x17U);
    expect("MCSM0 0x18", CC1101_MCSM0 == 0x18U);
    expect("FOCCFG 0x19", CC1101_FOCCFG == 0x19U);
    expect("AGCCTRL2 0x1B (0x1A is BSCFG)", CC1101_AGCCTRL2 == 0x1BU);
    expect("AGCCTRL1 0x1C", CC1101_AGCCTRL1 == 0x1CU);
    expect("AGCCTRL0 0x1D", CC1101_AGCCTRL0 == 0x1DU);
    expect("WORCTRL 0x20", CC1101_WORCTRL == 0x20U);
    expect("FREND1 0x21", CC1101_FREND1 == 0x21U);
    expect("FREND0 0x22", CC1101_FREND0 == 0x22U);
    expect("FSCAL3..0 0x23..0x26", CC1101_FSCAL3 == 0x23U &&
                                       CC1101_FSCAL2 == 0x24U &&
                                       CC1101_FSCAL1 == 0x25U &&
                                       CC1101_FSCAL0 == 0x26U);
    expect("TEST2..0 0x2C..0x2E", CC1101_TEST2 == 0x2CU &&
                                      CC1101_TEST1 == 0x2DU &&
                                      CC1101_TEST0 == 0x2EU);
    expect("PATABLE 0x3E", CC1101_PATABLE == 0x3EU);
    expect("status regs PARTNUM/VERSION/RSSI/MARCSTATE/PKTSTATUS",
           CC1101_PARTNUM == 0x30U && CC1101_VERSION == 0x31U &&
               CC1101_RSSI == 0x34U && CC1101_MARCSTATE == 0x35U &&
               CC1101_PKTSTATUS == 0x38U);
    expect("strobes SRES/SRX/STX/SIDLE/SFRX/SFTX",
           CC1101_SRES == 0x30U && CC1101_SRX == 0x34U && CC1101_STX == 0x35U &&
               CC1101_SIDLE == 0x36U && CC1101_SFRX == 0x3AU &&
               CC1101_SFTX == 0x3BU);
    return test_result();
}
