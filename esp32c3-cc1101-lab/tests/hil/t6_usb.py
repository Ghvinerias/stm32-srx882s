#!/usr/bin/env python3
"""T6: ESP32 USB CDC protocol basics on a fresh link.

  * STATUS heartbeat: the device emits a STATUS packet within 2 s of the
    port opening (this is the parser's primary warm-up check)
  * a binary TX_COMMAND sent over the same stream is answered with an ACK
    with accepted=1
  * while the TX_command radio path is idle, the STM32 reference transmitter
    fires the codeword; the ESP receiver emits a FRAME packet on its own
    USB stream whose decoded fields match

This is also the STATUS-parsing reference test: whatever the STATUS payload
contains (clock_hz/total_edges/dropped_packets words), it must parse, CRC
valid and with the standard header fields present.
"""

import os
import sys

import hil_lib as lib
from t3_tx_frame import fields_match

TAG = "T6"
NAME = "usb"


def main() -> int:
    try:
        target = lib.pick_codeword()[:3]
    except Exception as error:
        print(f"{TAG} {NAME}: FAIL {error}")
        return 1
    try:
        esp = lib.open_device(lib.esp_port(), "esp")
    except Exception as error:
        print(f"{TAG} {NAME}: FAIL cannot open ESP serial port {lib.esp_port()}: {error}")
        return 1

    try:
        problems = []
        evidence = []

        # --- STATUS heartbeat within 2 s ---------------------------------
        status = esp.wait_status(2.0)
        if status is None:
            problems.append(f"no STATUS heartbeat within 2.0s [esp: {esp.stats()}]")
        else:
            words = {
                key: value
                for key, value in status.items()
                if key in ("clock_hz", "total_edges", "dropped_packets", "sequence")
            }
            evidence.append(f"status {words}")

        # --- TX_COMMAND ACK ----------------------------------------------
        esp.clear()
        esp.send_tx_command(*target, 3)
        ack = esp.wait_ack(lib.TYPE_TX_COMMAND, 5.0)
        if ack is None:
            problems.append(f"no ACK for TX_COMMAND accepted=1 [esp: {esp.stats()}]")
        else:
            evidence.append(
                f"ack accepted=1 hopping=0x{target[0]:08X} "
                f"serial=0x{target[1]:07X} button=0x{target[2]:X}"
            )

        # --- FRAME while the STM32 transmits ------------------------------
        if ack is not None:
            stm32 = lib.Device(lib.stm32_port(), "stm32").start()
            if stm32.ok():
                stm32.clear()
                stm32.send_tx_command(*target, 3)
                frame = esp.wait_frame(8.0)
                if frame is None:
                    problems.append(f"no FRAME on the ESP stream for the STM32 "
                                    f"TX [esp: {esp.stats()}]")
                else:
                    decoded, _scale = lib.decode_segment(frame["pulses_us"])
                    why = (
                        f"frame seq={frame['sequence']} pulses={frame['pulse_count']} "
                        f"undecodable"
                    )
                    if decoded is not None and fields_match(decoded, target) is None:
                        why = (
                            f"frame seq={frame['sequence']} pulses={frame['pulse_count']} "
                            f"hopping+serial+button match, repeat={decoded['repeat']}"
                        )
                    evidence.append(why)
                    if decoded is None:
                        problems.append("ESP emitted a FRAME that does not decode")
                    else:
                        fields_problem = fields_match(decoded, target)
                        if fields_problem:
                            problems.append(f"ESP FRAME fields differ: {fields_problem}")
            else:
                problems.append(f"STM32 port not usable for the frame step: "
                                f"{stm32.open_error()}")
            stm32.close()
        if problems:
            print(f"{TAG} {NAME}: FAIL " + "; ".join(problems) + " || " + " | ".join(evidence))
            return 1
        print(f"{TAG} {NAME}: PASS " + " | ".join(evidence))
        return 0
    except Exception as error:
        print(f"{TAG} {NAME}: FAIL {error.__class__.__name__}: {error}")
        return 1
    finally:
        esp.close()


if __name__ == "__main__":
    sys.exit(main())
