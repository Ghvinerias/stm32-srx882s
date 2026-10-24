# HIL tests (run on the fit4 bench)

```
rsync -a esp32c3-cc1101-lab/ tools/ ...   # see ../../BENCH.md
cd ~/stm32-srx882s/esp32c3-cc1101-lab/tests/hil
export HIL_ESP_PORT=/dev/serial/by-id/usb-Espressif_USB_JTAG_serial_debug_unit_68:9D:D2:79:2A:A4-if00
export HIL_STM32_PORT=/dev/serial/by-id/usb-EE_Lab_Blue_Pill_RF_Lab_RF433-001-if00
~/venv/bin/python hil_lib.py --selftest      # no hardware needed
./run_all.sh                                  # or HIL_TESTS="t1 t3" ./run_all.sh
```

Logic analyzer: D0 = SRX882 data out (over the air), D1 = STX882 data in.

| Test | Proves |
|---|---|
| T1 | `radio`/`regs`/`gpio`: PARTNUM 0x00, VERSION 0x14, state RX, register table read back exactly as written (catches address/SPI bugs) |
| T2 | `carrier`: CC1101 reaches TX and keys for the requested time (SRX882 AGC makes D0 informational only) |
| T3 | binary TX_COMMAND over USB -> ACK -> real-PWM frames on D0 reduce+decode to the sent codeword (>=19/20) AND the STM32 reports full 77-pulse FRAMEs with the same hopping/serial/button (the oracle: its path decodes the real remote 154/154) |
| T4 | STM32 TX (STX882) -> ESP FRAME over USB matches (needs a working STM32 STX882 path) |
| T5 | BLE: STATUS, TX_COMMAND/ACK (and FRAME if T4 source works). After re-flashing the ESP run `sudo systemctl restart bluetooth` first (stale GATT cache gives "Unlikely Error") |
| T6 | USB STATUS heartbeat, ACK, FRAME |

Gotchas fixed while bringing this up: sigrok `.sr` chunks must be read in
numeric order (`logic-1-10` sorts before `logic-1-2`), per-channel edge
extraction must mask the channel bit, text replies share the byte stream with
COBS packets, and `build_tx_command()` already returns wire bytes (do not COBS
it again).

BLE note: BlueZ throttles rapid reconnects (connect bursts fail with GATT
'Unlikely Error' before the ESP sees a subscribe).  Leave >= 6 s between T5 runs
(12/12 passed that way; 2 s spacing failed ~50 %).  `radio` prints a `BLE` line
(conn/sub/mtu, connect/disconnect/subscribe event counts, notify count) for
diagnosis.

`watch_rx.py [seconds]` is the manual real-remote receive check.
