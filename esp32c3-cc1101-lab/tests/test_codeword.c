// Host-side tests for the codeword bit layout and waveform timing, mirroring
// firmware/src/lab_tx.c's send_bits_once()/lab_tx_send_codeword().
#include "src/lab_tx.h"

#include "test_util.h"

static void build_and_check(const char *label, uint32_t hopping,
                            uint32_t serial, uint8_t button, uint8_t frame,
                            const uint8_t expected[LAB_CODEWORD_BITS]) {
    uint8_t bits[LAB_CODEWORD_BITS];
    lab_tx_build_bits(bits, hopping, serial, button, frame);
    expect(label, memcmp(bits, expected, LAB_CODEWORD_BITS) == 0);
}

int main(void) {
    // ---- bit layout ----
    {
        uint8_t expected[LAB_CODEWORD_BITS];
        memset(expected, 0, sizeof(expected));
        expected[0] = 1U; // hopping LSB first
        build_and_check("hopping bit 0 lands at bit 0", 0x00000001U, 0U, 0U,
                        0U, expected);

        memset(expected, 0, sizeof(expected));
        expected[31] = 1U; // hopping MSB
        build_and_check("hopping bit 31 lands at bit 31", 0x80000000U, 0U, 0U,
                        0U, expected);

        memset(expected, 0, sizeof(expected));
        expected[32] = 1U;
        expected[59] = 1U;
        build_and_check("serial occupies bits 32..59, LSB first",
                        0U, 0x08000001U, 0U, 0U, expected);

        memset(expected, 0, sizeof(expected));
        expected[32] = 1U; // serial bit 28 must be masked away
        build_and_check("serial is masked to 28 bits", 0U, 0x10000001U, 0U, 0U,
                        expected);

        memset(expected, 0, sizeof(expected));
        expected[60] = 0U;
        expected[61] = 1U;
        expected[62] = 0U;
        expected[63] = 1U;
        build_and_check("button 0xA occupies bits 60..63", 0U, 0U, 0xAU, 0U,
                        expected);

        memset(expected, 0, sizeof(expected));
        expected[61] = 1U; // button 0x1A masked to 0xA
        expected[63] = 1U;
        build_and_check("button is masked to 4 bits", 0U, 0U, 0x1AU, 0U,
                        expected);

        memset(expected, 0, sizeof(expected));
        expected[64] = 0U;
        expected[65] = 0U;
        build_and_check("VLOW and repeat are 0 on frame 0", 0U, 0U, 0U, 0U,
                        expected);

        memset(expected, 0, sizeof(expected));
        expected[65] = 1U;
        build_and_check("repeat is 1 on every later frame", 0U, 0U, 0U, 1U,
                        expected);
    }

    // ---- waveform shape ----
    {
        const uint16_t *stale;
        size_t stale_count = lab_tx_stale_frame(&stale);
        expect("measured stale frame is 77 intervals", stale_count == 77U);
        expect("measured stale frame starts with 785us",
               stale_count > 0 && stale[0] == 785U);
        expect("measured stale frame ends with 1140us",
               stale_count > 0 && stale[76] == 1140U);
    }

    {
        uint16_t pulses[LAB_TX_MAX_PULSES];
        uint8_t bits[LAB_CODEWORD_BITS];

        // hopping=2 -> bits[0]=0, bits[1]=1: header ZERO, then SHORT.
        size_t n = lab_tx_build_waveform(2U, 0U, 0U, 1U, pulses,
                                         LAB_TX_MAX_PULSES);
        expect("one frame is 77 intervals", n == 77U);
        int preamble_ok = 1;
        for (size_t i = 0; i < LAB_PREAMBLE_INTERVALS; ++i) {
            if (pulses[i] != LAB_PREAMBLE_EDGE_US) {
                preamble_ok = 0;
            }
        }
        expect("11 preamble intervals of 760us", preamble_ok);
        expect("header is 4512us when bit 0 is 0",
               pulses[11] == LAB_HEADER_BIT_ZERO_US);
        expect("0->1 data cell is 795us", pulses[12] == LAB_DATA_SHORT_US);

        // hopping=1 -> bits[0]=1, bits[1]=0: header ONE, then LONG.
        n = lab_tx_build_waveform(1U, 0U, 0U, 1U, pulses,
                                  LAB_TX_MAX_PULSES);
        expect("header is 4170us when bit 0 is 1",
               n == 77U && pulses[11] == LAB_HEADER_BIT_ONE_US);
        expect("1->0 data cell is 1482us", pulses[12] == LAB_DATA_LONG_US);

        // hopping=0 -> 0->0: MID.
        n = lab_tx_build_waveform(0U, 0U, 0U, 1U, pulses,
                                  LAB_TX_MAX_PULSES);
        expect("equal adjacent bits use the 1138us MID cell",
               n == 77U && pulses[12] == LAB_DATA_MID_US);

        // The repeat bit changes only the last (bit64 -> bit65) cell.
        uint16_t frame0[LAB_TX_MAX_PULSES];
        uint16_t frame1[LAB_TX_MAX_PULSES];
        size_t n0 = lab_tx_build_waveform(0U, 0U, 0U, 1U, frame0,
                                          LAB_TX_MAX_PULSES);
        size_t n1 = lab_tx_build_waveform(0U, 0U, 0U, 2U, frame1,
                                          LAB_TX_MAX_PULSES);
        expect("two frames are 77 + gap + 77 intervals",
               n1 == 155U && frame1[77] == LAB_INTER_FRAME_GAP_US);
        expect("frame 0 ends VLOW=0 -> repeat=0 with a MID cell",
               n0 == 77U && frame0[76] == LAB_DATA_MID_US);
        expect("frame 1 ends VLOW=0 -> repeat=1 with a SHORT cell",
               n1 == 155U && frame1[154] == LAB_DATA_SHORT_US);

        n = lab_tx_build_waveform(0U, 0U, 0U, LAB_TX_MAX_FRAMES, pulses,
                                  LAB_TX_MAX_PULSES);
        expect("ten frames fit the maximum waveform exactly",
               n == LAB_TX_MAX_PULSES);

        n = lab_tx_build_waveform(0U, 0U, 0U, 1U, pulses, 76U);
        expect("a too-small output buffer is rejected", n == 0U);

        // Reconstruct the 66 bits from the emitted intervals and compare to
        // the layout helper, proving the waveform really carries them.
        uint32_t hopping = 0xBBA8D1CAU;
        uint32_t serial = 0x0FB58C0CU;
        lab_tx_build_bits(bits, hopping, serial, 0x02U, 0U);
        n = lab_tx_build_waveform(hopping, serial, 0x02U, 1U, pulses,
                                  LAB_TX_MAX_PULSES);
        uint8_t decoded[LAB_CODEWORD_BITS];
        int ok = n == 77U;
        for (size_t i = 0; ok && i < LAB_PREAMBLE_INTERVALS; ++i) {
            ok = pulses[i] == LAB_PREAMBLE_EDGE_US;
        }
        if (ok) {
            decoded[0] = pulses[11] == LAB_HEADER_BIT_ONE_US ? 1U : 0U;
            ok = pulses[11] == LAB_HEADER_BIT_ONE_US ||
                 pulses[11] == LAB_HEADER_BIT_ZERO_US;
        }
        for (size_t i = 0; ok && i < 65U; ++i) {
            uint16_t d = pulses[12U + i];
            if (d == LAB_DATA_MID_US) {
                decoded[i + 1U] = decoded[i];
            } else if (d == LAB_DATA_SHORT_US && decoded[i] == 0U) {
                decoded[i + 1U] = 1U;
            } else if (d == LAB_DATA_LONG_US && decoded[i] == 1U) {
                decoded[i + 1U] = 0U;
            } else {
                ok = 0;
            }
        }
        expect("waveform decodes back to the same 66 bits",
               ok && memcmp(decoded, bits, LAB_CODEWORD_BITS) == 0);
    }

    return test_result();
}
