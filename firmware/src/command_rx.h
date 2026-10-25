#pragma once

// Reassembles and dispatches incoming COBS+CRC16-framed command packets
// (TX_COMMAND / SCENARIO_COMMAND, see transport.h) received over the
// native USB CDC port. Replaces the old text-based lab console as the
// primary command path: this speaks the same binary protocol the capture
// stream uses, so both directions coexist on one CDC port without a mode
// switch.
void command_rx_init(void);
void command_rx_poll(void);
