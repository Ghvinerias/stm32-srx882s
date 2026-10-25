// ESP32-C3 + CC1101 433.92 MHz OOK lab node.
//
// All logic lives in src/.  Build with:
//   arduino-cli compile --fqbn
//     esp32:esp32:esp32c3:CDCOnBoot=cdc,PartitionScheme=no_fs,DebugLevel=none .
//
// CDCOnBoot=cdc is required: the firmware writes the COBS byte stream to the
// native USB Serial/JTAG CDC port (HWCDCSerial), not to UART0.

#include "src/firmware_app.h"

void setup() { firmware_app_init(); }

void loop() { firmware_app_loop(); }
