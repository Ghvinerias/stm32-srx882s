# RF capture tools

The firmware transmits COBS-framed binary packets with CRC-16. `rf_capture.py`
opens the serial port, validates packets, preserves the original byte stream,
and prints newline-delimited JSON for analysis.

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

Run protocol tests with:

```bash
python -m unittest -v test_rf_capture.py
```
