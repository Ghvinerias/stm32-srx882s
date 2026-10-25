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
        static uint16_t pulses[LAB_TX_MAX_PULSES];
        uint8_t bits[LAB_CODEWORD_BITS];
        lab_tx_build_bits(bits, 2U, 0U, 0U, 0U); // hopping bit 1 set, bit 0 clear

        size_t n = lab_tx_build_waveform(2U, 0U, 0U, 1U, pulses, LAB_TX_MAX_PULSES);
        expect("one frame is 156 alternating H/L pulses", n == LAB_FRAME_PULSES);
        int preamble_ok = 1;
        for (unsigned i = 0; i < 11U; ++i) {
            preamble_ok &= pulses[2U * i] == LAB_TE_US && pulses[2U * i + 1U] == LAB_TE_US;
        }
        expect("preamble is 11 x (1 TE high, 1 TE low)", preamble_ok);
        expect("12th preamble high is 1 TE, header low is 10 TE",
               pulses[22] == LAB_TE_US && pulses[23] == 10U * LAB_TE_US);
        expect("bit 0 (=0) is 2 TE high + 1 TE low",
               pulses[24] == 2U * LAB_TE_US && pulses[25] == LAB_TE_US);
        expect("bit 1 (=1) is 1 TE high + 2 TE low",
               pulses[26] == LAB_TE_US && pulses[27] == 2U * LAB_TE_US);
        expect("frame ends with the idle-low guard",
               pulses[LAB_FRAME_PULSES - 1U] == LAB_FRAME_IDLE_LOW_US);

        uint16_t periods[LAB_FRAME_PERIODS];
        expect("frame reduces to 77 falling-edge periods",
               lab_tx_frame_periods(pulses, n, periods, LAB_FRAME_PERIODS) ==
                   LAB_FRAME_PERIODS);
        int pre = 1;
        for (unsigned i = 0; i < 11U; ++i) pre &= periods[i] == 2U * LAB_TE_US;
        expect("preamble periods are 2 TE (~790 us like the real remote)", pre);
        expect("header period is 12 TE for bit0=0",
               periods[11] == 12U * LAB_TE_US);
        uint8_t back[LAB_CODEWORD_BITS];
        expect("periods decode back to the same 66 bits",
               lab_tx_bits_from_periods(periods, LAB_FRAME_PERIODS, back) &&
                   memcmp(back, bits, LAB_CODEWORD_BITS) == 0);

        n = lab_tx_build_waveform(1U, 0U, 0U, 1U, pulses, LAB_TX_MAX_PULSES);
        lab_tx_frame_periods(pulses, n, periods, LAB_FRAME_PERIODS);
        expect("header period is 11 TE for bit0=1",
               periods[11] == 11U * LAB_TE_US);
    }

    {
        // Every kind of code word must round-trip through PWM -> periods -> bits.
        static uint16_t pulses[LAB_TX_MAX_PULSES];
        unsigned bad = 0, total = 0;
        uint32_t seed = 0xC0FFEE11U;
        for (unsigned i = 0; i < 4000U; ++i) {
            seed = seed * 1664525U + 1013904223U;
            uint32_t hopping = seed;
            seed = seed * 1664525U + 1013904223U;
            uint32_t serial = seed & 0x0FFFFFFFU;
            uint8_t button = (uint8_t)(seed >> 28);
            if (i == 0U) { hopping = 0U; serial = 0U; button = 0U; }
            if (i == 1U) { hopping = 0xFFFFFFFFU; serial = 0x0FFFFFFFU; button = 0xFU; }
            uint8_t frames = (uint8_t)(1U + i % 10U);
            size_t n = lab_tx_build_waveform(hopping, serial, button, frames,
                                             pulses, LAB_TX_MAX_PULSES);
            if (n != (size_t)frames * LAB_FRAME_PULSES) { ++bad; ++total; continue; }
            for (uint8_t f = 0; f < frames; ++f) {
                uint8_t want[LAB_CODEWORD_BITS], got[LAB_CODEWORD_BITS];
                uint16_t per[LAB_FRAME_PERIODS];
                lab_tx_build_bits(want, hopping, serial, button, f);
                ++total;
                if (lab_tx_frame_periods(pulses + (size_t)f * LAB_FRAME_PULSES,
                                         LAB_FRAME_PULSES, per,
                                         LAB_FRAME_PERIODS) != LAB_FRAME_PERIODS ||
                    !lab_tx_bits_from_periods(per, LAB_FRAME_PERIODS, got) ||
                    memcmp(got, want, LAB_CODEWORD_BITS) != 0) {
                    ++bad;
                }
            }
        }
        printf("  round-trip: %u/%u frames bad\n", bad, total);
        expect("4000 random code words round-trip through the PWM waveform", bad == 0U);

        size_t n = lab_tx_build_waveform(0U, 0U, 0U, LAB_TX_MAX_FRAMES, pulses,
                                         LAB_TX_MAX_PULSES);
        expect("10 frames fill LAB_TX_MAX_PULSES", n == LAB_TX_MAX_PULSES);
        expect("capacity too small yields 0",
               lab_tx_build_waveform(0U, 0U, 0U, 2U, pulses, LAB_FRAME_PULSES) == 0U);
        expect("frame_count 0 yields 0",
               lab_tx_build_waveform(0U, 0U, 0U, 0U, pulses, LAB_TX_MAX_PULSES) == 0U);
    }

    {
        // The measured stale capture (reduced form) must be replayable as PWM.
        const uint16_t *stale;
        size_t stale_count = lab_tx_stale_frame(&stale);
        uint8_t bits[LAB_CODEWORD_BITS];
        expect("stale capture decodes to a code word",
               lab_tx_bits_from_periods(stale, stale_count, bits));
        uint32_t hopping = 0, serial = 0;
        for (unsigned i = 0; i < 32U; ++i) hopping |= (uint32_t)bits[i] << i;
        for (unsigned i = 0; i < 28U; ++i) serial |= (uint32_t)bits[32U + i] << i;
        expect("stale capture is the known identity (hopping 0xBBA8D1CA serial 0xFB58C0C)",
               hopping == 0xBBA8D1CAU && serial == 0x0FB58C0CU);
    }

    return test_result();
}
