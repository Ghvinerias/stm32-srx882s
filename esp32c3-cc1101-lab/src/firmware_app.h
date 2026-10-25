#pragma once

// Device glue: owns the two transports, the command parser, the simulated
// receiver and the RMT/CC1101 radio.  Everything here is Arduino-only; the
// portable protocol logic lives in the .c files next to it and is covered by
// tests/.
void firmware_app_init(void);
void firmware_app_loop(void);
