#include "classifier.h"
#include "uart.h"
#include <string.h>
#include <math.h>

// Forward declarations
bool ev1527_try_decode(const uint32_t* pulses, uint16_t n, uint32_t* symbol, uint32_t* payload);
bool pt2262_try_decode(const uint32_t* pulses, uint16_t n, uint32_t* symbol, uint32_t* payload);
bool oregon2_try_classify(const uint32_t* pulses, uint16_t n, uint32_t* symbol);

static inline uint32_t absdiff(uint32_t a, uint32_t b) { return (a > b) ? (a - b) : (b - a); }
static inline bool approx(uint32_t a, uint32_t b, uint32_t tol) { return absdiff(a, b) <= tol; }

static void reset_frame(ProtocolCtx* ctx)
{
    ctx->count = 0;
    ctx->current = PROTO_UNKNOWN;
    ctx->symbol_us = 0;
}

// crude frame boundary: large gap indicates frame end
static bool is_frame_gap(uint32_t us) { return us > 5000; } // >5ms gap

void classifier_init(ProtocolCtx* ctx)
{
    memset(ctx, 0, sizeof(*ctx));
}

void classifier_feed(ProtocolCtx* ctx, uint32_t pulse_us)
{
    if (ctx->count >= (sizeof(ctx->pulses)/sizeof(ctx->pulses[0]))) {
        reset_frame(ctx);
        return;
    }

    if (is_frame_gap(pulse_us)) {
        // attempt classification on accumulated pulses
        if (ctx->count > 16) {
            uint32_t sym=0, payload=0;
            if (ev1527_try_decode(ctx->pulses, ctx->count, &sym, &payload)) {
                uart_printf("[PROTO:EV1527] SYMBOL≈%luus Payload:0x%06lX\r\n",
                            sym, payload & 0xFFFFFF);
            } else if (pt2262_try_decode(ctx->pulses, ctx->count, &sym, &payload)) {
                uart_printf("[PROTO:PT2262] SYMBOL≈%luus Payload:0x%08lX\r\n", sym, payload);
            } else if (oregon2_try_classify(ctx->pulses, ctx->count, &sym)) {
                uart_printf("[PROTO:OREGON2] SYMBOL≈%luus LEN=%u\r\n", sym, ctx->count);
            } else {
                // Unknown or rolling (heuristic: long preamble + inconsistent symbol ratios)
                uart_printf("[PROTO:UNKNOWN-ROLLING] LEN=%u\r\n", ctx->count);
            }
        }
        reset_frame(ctx);
        return;
    }

    ctx->pulses[ctx->count++] = pulse_us;
}

/* --------- EV1527 / PT2262 helpers ---------- */

static bool find_symbol(const uint32_t* p, uint16_t n, uint32_t* sym_out)
{
    // Estimate base symbol as GCD-like of small pulses (median of lower quartile)
    uint16_t m = n < 64 ? n : 64;
    uint32_t small[64], k=0;
    for (uint16_t i=0; i<m; ++i) {
        if (p[i] < 2000) small[k++] = p[i];
    }
    if (k < 8) return false;
    // simple insertion sort
    for (uint16_t i=1; i<k; ++i) {
        uint32_t v = small[i]; int j = i-1;
        while (j>=0 && small[j] > v) { small[j+1]=small[j]; j--; }
        small[j+1]=v;
    }
    uint32_t median = small[k/2];
    // Round symbol to nearest 50us
    uint32_t sym = ((median + 25) / 50) * 50;
    if (sym < 150 || sym > 1200) return false;
    *sym_out = sym;
    return true;
}

bool ev1527_try_decode(const uint32_t* p, uint16_t n, uint32_t* symbol, uint32_t* payload)
{
    uint32_t sym;
    if (!find_symbol(p, n, &sym)) return false;

    // EV1527: PWM bit: 0 = T high, 3T low; 1 = 3T high, T low; Sync is ~31T low gap.
    // We only see gaps here (edge-to-edge). Interpret pairs as high/low durations.
    // Heuristic: build bits from pairs with tolerance.
    const uint32_t tol = sym/3; // ~33%
    uint32_t bits = 0;
    int bitcnt = 0;

    // Find a large sync gap
    int start = -1;
    for (uint16_t i=0; i<n; ++i) {
        if (p[i] > sym*20) { start = i+1; break; }
    }
    if (start < 0) return false;
    // Decode next 24 bits (EV1527 typical)
    for (uint16_t i=start; i+1<n && bitcnt<24; i += 2) {
        uint32_t h = p[i], l = p[i+1];
        if (approx(h, sym, tol) && approx(l, 3*sym, sym)) {
            bits <<= 1; // 0
        } else if (approx(h, 3*sym, sym) && approx(l, sym, tol)) {
            bits = (bits<<1) | 1; // 1
        } else {
            return false;
        }
        bitcnt++;
    }
    if (bitcnt < 20) return false;

    *symbol = sym;
    *payload = bits;
    return true;
}

bool pt2262_try_decode(const uint32_t* p, uint16_t n, uint32_t* symbol, uint32_t* payload)
{
    // PT2262 is tri-state; here we approximate as EV1527-like but allow some jitter
    // Accept frames that decode as EV1527 but with length != 24 as PT2262-ish
    uint32_t sym, bits;
    const uint32_t tol = 0; (void)tol;
    if (!find_symbol(p, n, &sym)) return false;

    // Try decode 12/20 bits
    int candidates[] = {12, 20, 24};
    for (unsigned c=0; c<3; ++c) {
        int want = candidates[c];
        // naive reuse of ev-like decode
        bits = 0;
        int bitcnt = 0;
        int start = -1;
        for (uint16_t i=0; i<n; ++i) {
            if (p[i] > sym*20) { start = i+1; break; }
        }
        if (start < 0) continue;

        for (uint16_t i=start; i+1<n && bitcnt<want; i+=2) {
            uint32_t h = p[i], l = p[i+1];
            if (h < sym/2 || l < sym/2) { bitcnt=0; break; }
            bits = (bits<<1) | (h > l ? 1 : 0);
            bitcnt++;
        }
        if (bitcnt == want) {
            *symbol = sym;
            *payload = bits;
            return true;
        }
    }
    return false;
}

bool oregon2_try_classify(const uint32_t* p, uint16_t n, uint32_t* symbol)
{
    // Oregon v2: Manchester encoded, near-constant pulse spacing (half-bit)
    // Look for tight clustering around a base period ~ 488us or ~1024us
    if (n < 40) return false;
    // compute average of first 40
    uint64_t sum = 0;
    for (int i=0; i<40; ++i) sum += p[i];
    uint32_t avg = (uint32_t)(sum / 40);
    // Round to nearest 50us
    uint32_t sym_guess = ((avg + 25)/50)*50;
    if (sym_guess < 300 || sym_guess > 1200) return false;

    // Check that 80% of samples are within +/-30%
    int ok=0;
    for (int i=0; i<40; ++i) {
        uint32_t d = p[i] > sym_guess ? p[i]-sym_guess : sym_guess-p[i];
        if (d <= sym_guess/3) ok++;
    }
    if (ok < 32) return false;

    *symbol = sym_guess;
    return true;
}