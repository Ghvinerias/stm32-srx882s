// Regression for the real-remote receive failure: the ESP's both-edge RMT
// captures must be reduced to the STM32 reference representation so the
// existing decoders work.  Frames below were recorded on the bench; the STM32
// decoded the same presses (serial 0x0FB3EC77, hopping changes per press).
#include "src/lab_capture.h"

#include "data/remote1_frames.h"
#include "test_util.h"

// Same rules as tools/decode_hcs301.py (scale 1.0, tolerance 10%).
static int decode(const capture_frame_t *f, uint32_t *hop, uint32_t *ser,
                  unsigned *btn, double scale) {
    if (f->pulse_count != 77U) {
        return 0;
    }
    double zero = 4512 * scale, one = 4170 * scale;
    double h = f->pulses[11];
    double dz = h > zero ? h - zero : zero - h, d1 = h > one ? h - one : one - h;
    if (!(dz <= zero * 0.1 || d1 <= one * 0.1)) {
        return 0;
    }
    int bits[66];
    bits[0] = dz <= d1 ? 0 : 1;
    for (int i = 0; i < 65; ++i) {
        double d = f->pulses[12 + i];
        double s = 795 * scale, m = 1138 * scale, l = 1482 * scale;
        double es = d > s ? d - s : s - d, em = d > m ? d - m : m - d,
               el = d > l ? d - l : l - d;
        if (es <= em && es <= el) {
            if (bits[i] != 0) return 0;
            bits[i + 1] = 1;
        } else if (el <= em && el <= es) {
            if (bits[i] != 1) return 0;
            bits[i + 1] = 0;
        } else {
            bits[i + 1] = bits[i];
        }
    }
    *hop = *ser = 0;
    *btn = 0;
    for (int i = 0; i < 32; ++i) *hop |= (uint32_t)bits[i] << i;
    for (int i = 0; i < 28; ++i) *ser |= (uint32_t)bits[32 + i] << i;
    for (int i = 0; i < 4; ++i) *btn |= (unsigned)bits[60 + i] << i;
    return 1;
}

static void load(capture_frame_t *f, const esp_rec_t *r) {
    memset(f, 0, sizeof(*f));
    f->pulse_count = (uint16_t)r->count;
    for (unsigned i = 0; i < r->count; ++i) f->pulses[i] = r->pulses[i];
}

int main(void) {
    for (unsigned i = 0; i < sizeof(esp_good) / sizeof(esp_good[0]); ++i) {
        capture_frame_t f;
        load(&f, &esp_good[i]);
        int reduced = lab_capture_reduce_hcs301(&f);
        uint32_t hop = 0, ser = 0;
        unsigned btn = 0;
        int ok = reduced && decode(&f, &hop, &ser, &btn, 1.04);
        char label[96];
        snprintf(label, sizeof(label), "%s reduces to 77 periods and decodes", esp_good[i].name);
        expect(label, ok);
        snprintf(label, sizeof(label), "%s serial 0x0FB3EC77 button 2", esp_good[i].name);
        expect(label, ok && ser == 0x0FB3EC77U && btn == 2U);
        snprintf(label, sizeof(label), "%s hopping matches the STM32 decode (0x%08X)",
                 esp_good[i].name, esp_good[i].hopping);
        expect(label, ok && hop == esp_good[i].hopping);
    }
    for (unsigned i = 0; i < sizeof(esp_partial) / sizeof(esp_partial[0]); ++i) {
        capture_frame_t f;
        load(&f, &esp_partial[i]);
        uint16_t before = f.pulse_count;
        int reduced = lab_capture_reduce_hcs301(&f);
        uint32_t hop, ser;
        unsigned btn;
        char label[96];
        snprintf(label, sizeof(label), "%s (partial/noise) is not reduced into garbage",
                 esp_partial[i].name);
        // either left untouched, or reduced to something that decodes with the
        // known serial -- never a bogus decode
        expect(label, (!reduced && f.pulse_count == before) ||
                          (reduced && decode(&f, &hop, &ser, &btn, 1.04) &&
                           ser == 0x0FB3EC77U));
    }
    return test_result();
}
