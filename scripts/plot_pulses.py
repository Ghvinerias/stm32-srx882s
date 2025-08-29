#!/usr/bin/env python3
import argparse
import matplotlib.pyplot as plt

def load_us(fname):
    us=[]
    with open(fname) as f:
        for ln in f:
            ln=ln.strip()
            if not ln or ln.startswith("#"): continue
            try:
                us.append(int(ln))
            except: pass
    return us

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("file", help="Text file with microsecond pulse gaps, one per line")
    args = ap.parse_args()
    us = load_us(args.file)
    t=[0]
    level=[0]
    val=0
    for u in us:
        t.append(t[-1]+u)
        val ^= 1
        level.append(val)
    plt.step(t, level, where='post')
    plt.xlabel("us")
    plt.ylabel("level")
    plt.title(args.file)
    plt.ylim(-0.5, 1.5)
    plt.grid(True, which='both', axis='both', alpha=0.3)
    plt.show()

if __name__ == "__main__":
    main()