# hcs301_lab -- PulseView/sigrok protocol decoder

Decodes the HCS301-shaped frames used throughout this lab, live in
PulseView, over whatever channel you probe (STX882 TX data pin, or a
receiver module's demodulated output -- either polarity, since it only
uses edge-to-edge timing). Mirrors `tools/decode_hcs301.py`: preamble,
header bit, 32-bit hopping word (as transmitted -- still ciphertext,
not decrypted here), 28-bit serial, 4-bit button, VLOW, repeat. Per-device
RC-oscillator drift is handled by calibrating a timing scale off each
frame's own preamble, same idea as `decode_hcs301.py`'s auto-calibration --
no manual tuning needed.

Does not decrypt the hopping word and does not derive or guess any key
material -- same scope as the rest of this repo's tooling.

## Install

Copy the decoder folder into libsigrokdecode's system decoder path:

```bash
sudo cp -r hcs301_lab /usr/share/libsigrokdecode/decoders/
```

Restart PulseView (or `sigrok-cli`) if it was already running. It'll show
up as **"HCS301 lab"** in the protocol decoder search/add list.

## Use

1. Capture with your logic analyzer, one channel wired to the data line
   you want to inspect.
2. Add the decoder, assign its `Data` input to that channel.
3. Three annotation rows appear:
   - **Fields**: preamble block, header bit, individual data bits
   - **Decoded fields**: hopping/serial/button/VLOW+repeat, each spanning
     its bit range
   - **Frame**: one-line summary per frame
     (`hopping=0x... serial=0x... button=0x... repeat=... (scale=...)`)
   - **Warnings**: anything that didn't parse as a valid frame (desyncs,
     invalid bit-cell transitions, truncated captures)

## Verification

Checked against two real captures from this lab's R3 remote
(`tools/captures/r3-usb-sanity.ndjson`, `r3-usb-standalone.ndjson`) via a
stub `sigrokdecode` harness driving the real `pd.py` code with the
captures' actual `pulses_us` edge timings: 34/34 and 23/23 frames decoded,
every hopping/serial/button value matching `decode_hcs301.py`'s output for
the same frames exactly.
