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

Radio modes: the ESP boots in IDLE (CC1101 idle, RMT capture off, no FRAME
packets). Anything that expects FRAMEs switches it to RX first (T4, T5, T6,
`watch_rx.py`); T4/T5/T6 put it back to IDLE at the end so T7's boot-state
check still sees a quiet device. TX is transient during a burst and the
device returns to the mode it was in before.

| Test | Proves |
|---|---|
| T1 | `radio`/`regs`/`gpio`: PARTNUM 0x00, VERSION 0x14, state RX/IDLE, mode idle at boot, register table read back exactly as written (catches address/SPI bugs) |
| T2 | `carrier`: CC1101 reaches TX and keys for the requested time (SRX882 AGC makes D0 informational only) |
| T3 | binary TX_COMMAND over USB -> ACK -> real-PWM frames on D0 reduce+decode to the sent codeword (>=19/20) AND the STM32 reports full 77-pulse FRAMEs with the same hopping/serial/button (the oracle: its path decodes the real remote 154/154) |
| T4 | STM32 TX (STX882) -> ESP FRAME over USB matches (needs a working STM32 STX882 path) |
| T5 | BLE: STATUS, TX_COMMAND/ACK (and FRAME if T4 source works). After re-flashing the ESP run `sudo systemctl restart bluetooth` first (stale GATT cache gives "Unlikely Error") |
| T6 | USB STATUS heartbeat, ACK, FRAME; puts the radio into RX (boot default is IDLE) |
| T7 | modes: boot is IDLE with no FRAMEs, MODE_COMMAND rx -> ACK + immediate STATUS + rx_irq climbing, unknown mode rejected (accepted=0), MODE_COMMAND idle -> ACK + STATUS + rx_irq stops, and the `mode` / `mode rx` / `mode idle` text equivalents |
| T8 | 50-frame TX_COMMAND: ACK only after the burst (~5.3 s, accepted_raw=1), STATUS mode TX during it then back to IDLE, D0 shows 50 decodable frames spaced 106.5±2 ms, the STM32 oracle reports 50 FRAMEs, `radio` still answers mid-burst and a second TX_COMMAND is rejected at once (accepted_raw=0) |
| T9 | abort: MODE_COMMAND idle mid-burst -> TX ACK accepted_raw=2 within 0.6 s, MODE ACK accepted=1, STATUS TX->IDLE, D0 shows 5..30 frames and 3 s of silence afterwards |

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
