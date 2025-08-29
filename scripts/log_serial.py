#!/usr/bin/env python3
import argparse, sys, time
import serial

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", required=True)
    ap.add_argument("--baud", type=int, default=115200)
    ap.add_argument("--save", help="Save raw log to file")
    args = ap.parse_args()

    ser = serial.Serial(args.port, args.baud, timeout=1)
    print(f"Connected to {args.port} @ {args.baud}", file=sys.stderr)

    outf = open(args.save, "w") if args.save else None
    try:
        while True:
            line = ser.readline().decode(errors="ignore")
            if not line:
                continue
            print(line, end="")
            if outf:
                outf.write(line)
                outf.flush()
    except KeyboardInterrupt:
        pass
    finally:
        if outf: outf.close()
        ser.close()

if __name__ == "__main__":
    main()