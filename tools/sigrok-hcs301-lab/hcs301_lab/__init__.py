##
## This file is part of the stm32-srx882s lab project.
##
## PulseView/libsigrokdecode protocol decoder for the HCS301-shaped frames
## used throughout this repo's lab (see tools/decode_hcs301.py, which this
## decoder mirrors). It reconstructs only the bit fields transmitted in the
## clear over the air: the 32-bit hopping block as received, the 28-bit
## serial, the button nibble, and the VLOW/repeat status bits. It does not
## attempt decryption and does not derive or guess any key material -- the
## hopping block's *contents* are only meaningful once decrypted, which is
## out of scope here, same as the existing Python tooling.
##
## This program is free software; you can redistribute it and/or modify
## it under the terms of the GNU General Public License as published by
## the Free Software Foundation; either version 2 of the License, or
## (at your option) any later version.
##

'''
HCS301-shaped lab frame decoder.

Decodes the clear-text bit fields of the pulse-distance-coded frame produced
by this repo's STM32/ESP32 lab transmitters and real HCS301-based remotes:
preamble, header bit, 32-bit hopping word (as transmitted -- still
ciphertext unless separately decrypted), 28-bit serial, 4-bit button, VLOW,
and repeat flag.

Operates purely on edge-to-edge timing on a single data-line channel, so it
works identically whichever polarity the probed signal has (transmitter
DATA pin or a receiver module's demodulated output) -- no polarity option
is needed, since only interval durations are used, never absolute levels.
'''

from .pd import Decoder
