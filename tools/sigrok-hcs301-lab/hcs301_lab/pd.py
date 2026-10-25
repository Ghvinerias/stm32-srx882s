##
## This file is part of the stm32-srx882s lab project.
## See __init__.py for scope/disclaimer: clear-text fields only, no
## decryption, no key derivation.
##
## This program is free software; you can redistribute it and/or modify
## it under the terms of the GNU General Public License as published by
## the Free Software Foundation; either version 2 of the License, or
## (at your option) any later version.
##

import sigrokdecode as srd

# Measured timing centers, in microseconds, mirrored from
# firmware/src/lab_tx.c and tools/decode_hcs301.py. Per-device RC-oscillator
# drift is handled by calibrating a scale factor off the preamble itself,
# same idea as decode_hcs301.py's auto-calibration -- no manual tuning
# needed.
PREAMBLE_EDGE_US = 760
HEADER_ZERO_US = 4512
HEADER_ONE_US = 4170
SHORT_US = 795
MID_US = 1138
LONG_US = 1482
TOLERANCE_US = 150

PREAMBLE_EDGES_EXPECTED = 11
PREAMBLE_EDGES_MIN_LOCK = 6
DATA_BITS = 65  # bits[1..65]; bits[0] comes from the header.

ANN_PREAMBLE, ANN_HEADER, ANN_BIT, ANN_HOPPING, ANN_SERIAL, ANN_BUTTON, \
    ANN_STATUS, ANN_FRAME, ANN_WARNING = range(9)


class Decoder(srd.Decoder):
    api_version = 3
    id = 'hcs301_lab'
    name = 'HCS301 lab'
    longname = 'HCS301-shaped lab frame (clear-text fields only)'
    desc = ('Decodes the HCS301-style pulse-distance frame used by this '
            'lab\'s STM32/ESP32 transmitters: preamble, header bit, '
            'hopping word (as transmitted), serial, button, VLOW, repeat. '
            'Does not decrypt the hopping word.')
    license = 'gplv2+'
    inputs = ['logic']
    outputs = []
    tags = ['Remote control']
    channels = (
        {'id': 'data', 'name': 'Data', 'desc': 'OOK data line (TX or RX, either polarity)'},
    )
    annotations = (
        ('preamble', 'Preamble'),
        ('header', 'Header bit'),
        ('bit', 'Data bit'),
        ('hopping', 'Hopping word'),
        ('serial', 'Serial'),
        ('button', 'Button'),
        ('status', 'VLOW / repeat'),
        ('frame', 'Frame'),
        ('warnings', 'Warnings'),
    )
    annotation_rows = (
        ('fields', 'Fields', (ANN_PREAMBLE, ANN_HEADER, ANN_BIT)),
        ('decoded', 'Decoded fields', (ANN_HOPPING, ANN_SERIAL, ANN_BUTTON, ANN_STATUS)),
        ('frame', 'Frame', (ANN_FRAME,)),
        ('warnings', 'Warnings', (ANN_WARNING,)),
    )

    def __init__(self):
        self.reset()

    def reset(self):
        self.samplerate = None
        self.state = 'SEEK'
        self.edge_start = 0
        self.preamble_durations = []
        self.preamble_first_start = 0
        self.scale = 1.0
        self.bits = []
        self.bit_starts = []
        self.frame_start = 0
        self.header_start = 0

    def start(self):
        self.out_ann = self.register(srd.OUTPUT_ANN)

    def metadata(self, key, value):
        if key == srd.SRD_CONF_SAMPLERATE:
            self.samplerate = value

    def us_to_samples(self, us):
        return int(self.samplerate * us / 1_000_000)

    def within(self, measured_us, center_us):
        tol = TOLERANCE_US * self.scale
        center = center_us * self.scale
        return (center - tol) <= measured_us <= (center + tol)

    def duration_us(self, samples):
        return samples * 1_000_000 / self.samplerate

    def putann(self, ss, es, index, texts):
        self.put(ss, es, self.out_ann, [index, texts])

    def abort_to_seek(self, warn_ss, warn_es, message):
        if message:
            self.putann(warn_ss, warn_es, ANN_WARNING, [message])
        self.state = 'SEEK'
        self.preamble_durations = []
        self.bits = []
        self.bit_starts = []

    def emit_frame(self, frame_end):
        def field(start, count):
            value = 0
            for i in range(count):
                value |= self.bits[start + i] << i
            return value

        hopping = field(0, 32)
        serial = field(32, 28)
        button = field(60, 4)
        vlow = self.bits[64]
        repeat = self.bits[65]

        self.putann(self.bit_starts[0], self.bit_starts[32], ANN_HOPPING,
                    ['Hopping: 0x%08X' % hopping, '0x%08X' % hopping])
        self.putann(self.bit_starts[32], self.bit_starts[60], ANN_SERIAL,
                    ['Serial: 0x%07X' % serial, '0x%07X' % serial])
        self.putann(self.bit_starts[60], self.bit_starts[64], ANN_BUTTON,
                    ['Button: 0x%X' % button, '0x%X' % button])
        self.putann(self.bit_starts[64], frame_end, ANN_STATUS,
                    ['VLOW=%d repeat=%d' % (vlow, repeat), 'V%d R%d' % (vlow, repeat)])
        self.putann(self.frame_start, frame_end, ANN_FRAME,
                    ['hopping=0x%08X serial=0x%07X button=0x%X repeat=%d (scale=%.4f)'
                     % (hopping, serial, button, repeat, self.scale),
                     '0x%08X/0x%07X/B%X' % (hopping, serial, button)])

    def decode(self):
        if not self.samplerate:
            raise Exception('This decoder needs a samplerate to work.')

        while True:
            self.wait({0: 'e'})
            edge_end = self.samplenum
            duration = self.duration_us(edge_end - self.edge_start)
            seg_start = self.edge_start
            self.edge_start = edge_end

            if self.state == 'SEEK':
                if 400 <= duration <= 1100:
                    self.preamble_durations = [duration]
                    self.preamble_first_start = seg_start
                    self.state = 'PREAMBLE'
                continue

            if self.state == 'PREAMBLE':
                avg = sum(self.preamble_durations) / len(self.preamble_durations)
                if duration <= avg * 2.0:
                    # Still looks like another preamble edge.
                    self.preamble_durations.append(duration)
                    if len(self.preamble_durations) > PREAMBLE_EDGES_EXPECTED:
                        # More than expected -- keep the most recent run.
                        self.preamble_durations.pop(0)
                    continue
                # A much longer edge: candidate header, if we've locked on
                # enough consistent preamble edges first.
                if len(self.preamble_durations) < PREAMBLE_EDGES_MIN_LOCK:
                    self.abort_to_seek(seg_start, edge_end, None)
                    # Re-seed: this edge might itself start a new preamble.
                    if 400 <= duration <= 1100:
                        self.preamble_durations = [duration]
                        self.preamble_first_start = seg_start
                        self.state = 'PREAMBLE'
                    continue
                self.scale = avg / PREAMBLE_EDGE_US
                self.putann(self.preamble_first_start, seg_start, ANN_PREAMBLE,
                            ['Preamble (%d edges, scale=%.4f)'
                             % (len(self.preamble_durations), self.scale), 'Preamble'])
                if self.within(duration, HEADER_ZERO_US):
                    bit0 = 0
                elif self.within(duration, HEADER_ONE_US):
                    bit0 = 1
                else:
                    self.abort_to_seek(seg_start, edge_end,
                                       'Expected header after preamble, got %dus' % duration)
                    continue
                self.header_start = seg_start
                self.frame_start = self.preamble_first_start
                self.bits = [bit0]
                self.bit_starts = [seg_start]
                self.putann(seg_start, edge_end, ANN_HEADER,
                            ['Header bit: %d' % bit0, 'H%d' % bit0])
                self.state = 'DATA'
                continue

            if self.state == 'DATA':
                prev_bit = self.bits[-1]
                if self.within(duration, SHORT_US):
                    if prev_bit != 0:
                        self.abort_to_seek(seg_start, edge_end,
                                           'SHORT cell after a 1 bit (invalid)')
                        continue
                    new_bit = 1
                elif self.within(duration, LONG_US):
                    if prev_bit != 1:
                        self.abort_to_seek(seg_start, edge_end,
                                           'LONG cell after a 0 bit (invalid)')
                        continue
                    new_bit = 0
                elif self.within(duration, MID_US):
                    new_bit = prev_bit
                else:
                    self.abort_to_seek(seg_start, edge_end,
                                       'Unrecognized data cell: %dus' % duration)
                    continue

                self.bits.append(new_bit)
                self.bit_starts.append(seg_start)
                self.putann(seg_start, edge_end, ANN_BIT, ['%d' % new_bit])

                if len(self.bits) == DATA_BITS + 1:  # header bit + 65 data bits
                    self.emit_frame(edge_end)
                    self.state = 'SEEK'
                    self.preamble_durations = []
                    self.bits = []
                    self.bit_starts = []
                continue
