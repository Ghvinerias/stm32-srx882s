"""
UART Log Parser for STM32 433 MHz Protocol Classifier
"""
import sys
import re

def parse_log_line(line):
    # Example: [PROTO:EV1527] RAW:101010110110 Payload:0x1A2F3C
    m = re.match(r"\[PROTO:(.+?)\].*Payload:([0-9a-fA-Fx]+)", line)
    if m:
        proto, payload = m.groups()
        return {"protocol": proto, "payload": payload}
    return None

def main():
    for line in sys.stdin:
        result = parse_log_line(line)
        if result:
            print(result)

if __name__ == "__main__":
    main()
