#!/usr/bin/env python3
"""T5: BLE carries the same wire protocol (the ESP32-C3 without USB).

  * scan for a device whose advertised name starts with KeeLoqLab-C3-
  * connect over BLE, subscribe to 6E400003-B5A3-F393-E0A9-E50E24DCCA9E
    (device -> host notifications)
  * STATUS packet within 2 s of subscribing
  * binary TX_COMMAND written to 6E400002-B5A3-F393-E0A9-E50E24DCCA9E in
    MTU-3 chunks, ACK accepted=1 expected back
  * optional (evidence only): the STM32 reference transmitter fires the
    same codeword on the air, and a FRAME notification arrives over BLE
"""

import asyncio
import os
import sys

import hil_lib as lib

TAG = "T5"
NAME = "ble"

STATUS_TIMEOUT = float(os.environ.get("HIL_T5_STATUS_S", "2.0"))
ACK_TIMEOUT = float(os.environ.get("HIL_T5_ACK_S", "5.0"))
FRAME_TIMEOUT = float(os.environ.get("HIL_T5_FRAME_S", "5.0"))
PREFIX = os.environ.get("HIL_BLE_PREFIX", lib.BLE_NAME_PREFIX)


async def main_async() -> tuple[bool, str]:
    try:
        target = lib.pick_codeword()[:3]
    except Exception as error:
        return False, str(error)

    try:
        session, name = await lib.ble_connect(PREFIX, scan_seconds=6.0)
    except Exception as error:
        return False, f"BLE connect to {PREFIX}* failed: {error}"

    problems: list[str] = []
    evidence = [
        f"connect ok name={name} mtu={getattr(session.client, 'mtu_size', '?')}"
    ]
    status = None
    try:
        status = await session.wait_status(STATUS_TIMEOUT)
    except Exception as error:
        problems.append(f"STATUS wait error: {error.__class__.__name__}: {error}")
    if status is None:
        problems.append(
            f"no STATUS packet within {STATUS_TIMEOUT:.0f}s [{session.stats()}]"
        )
    else:
        evidence.append(
            f"status seq={status['sequence']} clock_hz={status.get('clock_hz')} "
            f"edges={status.get('total_edges')}"
        )

    try:
        await session.write(lib.build_tx_command(*target, 3))
        ack = await session.wait_ack(lib.TYPE_TX_COMMAND, ACK_TIMEOUT)
        if ack is None:
            problems.append(f"no ACK for TX_COMMAND within {ACK_TIMEOUT:.0f}s "
                            f"[{session.stats()}]")
        elif ack.get("accepted") is not True:
            problems.append(f"ACK accepted={ack.get('accepted')!r}")
        else:
            evidence.append(f"ack accepted=1 for hopping=0x{target[0]:08X} "
                            f"serial=0x{target[1]:07X} button=0x{target[2]:X}")
    except Exception as error:
        problems.append(f"TX_COMMAND/ACK over BLE failed: {error.__class__.__name__}: {error}")

    # optional: STM32 fires the same codeword; the ESP notifies FRAME over BLE
    if os.environ.get("HIL_BLE_STM32_TX", "1") == "1":
        try:
            stm32 = lib.Device(lib.stm32_port(), "stm32").start()
            if stm32.ok():
                stm32.send_tx_command(*target, 3)
                frame = await session.next_packet(
                    lambda p: p.get("type") == "frame", FRAME_TIMEOUT
                )
                if frame is None:
                    lib.iter_report("note:", f"no BLE FRAME during STM32 TX")
                else:
                    evidence.append(
                        f"optional: ble frame seq={frame['sequence']} "
                        f"pulses={frame.get('pulse_count')} while STM32 transmitted"
                    )
            else:
                lib.iter_report("note:", "STM32 unavailable for optional "
                                        f"frame step: {stm32.open_error()}")
            stm32.close()
        except Exception as error:
            lib.iter_report("note:", f"optional STM32 step skipped: {error}")

    try:
        await session.close()
    except Exception:
        pass

    if problems:
        return False, "; ".join(problems) + " || " + " | ".join(evidence)
    return True, " | ".join(evidence)


def main() -> int:
    try:
        ok, evidence = asyncio.run(main_async())
    except Exception as error:
        print(f"{TAG} {NAME}: FAIL {error.__class__.__name__}: {error}")
        return 1
    print(f"{TAG} {NAME}: {'PASS' if ok else 'FAIL'} {evidence}".rstrip())
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
