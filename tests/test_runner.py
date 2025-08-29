#!/usr/bin/env python3
import sys, os, re

SAMPLES_DIR = os.path.join(os.path.dirname(__file__), "samples")

def run():
    ok=0; total=0
    for fn in os.listdir(SAMPLES_DIR):
        if not fn.endswith(".log"): continue
        total+=1
        path = os.path.join(SAMPLES_DIR, fn)
        with open(path) as f:
            content = f.read()
        expected = re.findall(r"^#\s*EXPECT:\s*(.+)$", content, re.MULTILINE)
        if not expected:
            print(f"[WARN] {fn}: no EXPECT line, skipping")
            total-=1
            continue
        exp=expected[0].strip()
        if exp in content:
            print(f"[OK] {fn}: found '{exp}'")
            ok+=1
        else:
            print(f"[FAIL] {fn}: missing '{exp}'")
    if total==0:
        print("No tests run.")
        return 0
    print(f"{ok}/{total} tests passed")
    return 0 if ok==total else 1

if __name__=="__main__":
    sys.exit(run())