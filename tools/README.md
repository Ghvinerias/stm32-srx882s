# RF capture tools

The firmware transmits COBS-framed binary packets with CRC-16. `rf_capture.py`
opens the serial port, validates packets, preserves the original byte stream,
and prints newline-delimited JSON for analysis.

Both tools also understand the radio-mode layer the ESP32-C3/CC1101 firmware
adds: `MODE_COMMAND` (type 8, `[ver][type=8][mode][crc16 LE]`, mode 0 = IDLE,
1 = RX) selects whether the receiver listens, every STATUS carries the current
mode in header flags bits 2..3 (0 IDLE, 1 RX, 2 TX), and an ACK's `accepted`
byte is kept raw (`accepted_raw`: 0 rejected/busy, 1 complete, 2 TX aborted by
MODE IDLE) next to the historical boolean `accepted`.

Live capture:

```bash
python rf_capture.py --port /dev/ttyUSB0 --save capture.bin
```

Replay an existing capture:

```bash
python rf_capture.py --input capture.bin
```

Write JSON to a file while preserving the binary stream:

```bash
python rf_capture.py --port /dev/ttyUSB0 \
  --save capture.bin --json capture.ndjson
```

`uart_log_parser.py` remains as a compatibility entry point for the same tool.

## Interactive console

`rf_console.py` is a curses TUI that records captures and sends TX/scenario
commands over the same USB CDC link, at the same time if you want to --
it's a full-duplex connection, so reads and writes don't block each other.

```bash
python rf_console.py --port /dev/ttyACM0
```

Keys: `r` toggles recording to the NDJSON path shown in the header (plus a
sibling `.bin` with the exact raw framed stream); `o` changes that path
without restarting; `c` opens the send menu (a custom TX_COMMAND, one of the
scenario words from `firmware/src/command_rx.c`, or a MODE_COMMAND to switch
the radio between IDLE and RX); `q` quits.

The one real timing caveat is firmware-side, not this tool: while
`lab_tx_send_codeword()` is bit-banging a transmission it disables
interrupts for that window (see `firmware/src/lab_tx.c`), so capture edges
that would land in that exact span aren't seen. That only matters if you
need to capture something arriving in the same few milliseconds as your own
outgoing TX -- recording a separate remote before/after a send is unaffected.

## Analysis

Three scripts read a capture and decode the HCS301/KeeLoq code words in it.
Timing is auto-calibrated per capture, because the encoder clock is an RC
oscillator and units differ by a few percent.

```bash
# Frame-level decode: button, serial, hopping word per frame.
python decode_hcs301.py captures/r3.ndjson

# Press-level summary: one row per burst, plus a rolling-code verdict.
python summarize_capture.py captures/r3.ndjson

# Raw code-word layout: every bit field, what is readable in the clear and
# what is inside the encrypted hopping word.
python keeloq_message_view.py captures/r3.ndjson
```

`keeloq_message_view.py` also contains the public KeeLoq block cipher and can
decrypt a hopping word if you supply a key you own
(`--key 0x...`, verify the implementation with `--selftest`). Without a key it
shows the source-block layout -- counter, discrimination, overflow, button --
marked as unreadable, because those fields are inside the ciphertext.

Run protocol tests with:

```bash
python -m unittest -v test_rf_capture.py
```
