// RimDroid: S3TC->ETC2 transcode encoder. Split out of wrappedsdl2.c (2026-08-12) so THIS file can
// be compiled -O2 (see CMakeLists: per-file COMPILE_OPTIONS) while box64's Android build keeps its
// safe optimization level elsewhere — the encoder is pure arithmetic with no dynarec/emulation
// interplay, so fast codegen is risk-free here. At -O0 the full-search encoder turned the RimWorld
// 1.6 atlas bake into a 10-minute 100%-CPU load that Android then SIGKILLed as unresponsive.
//
// Two algorithmic cuts on top of the compiler (vs the first full-search version):
//  - uniform blocks (flat colors / atlas padding — a huge share of RimWorld atlases) take a fast
//    path: one quantization, no table search (max channel error 2, invisible);
//  - the ETC1 table search tries only the 3 tables bracketing the subblock's actual max deviation
//    instead of all 8 (the modifier tables are monotonic, far-off tables can't win).
//
// Formats: ETC1-subset color (individual/differential modes; no T/H/planar — quality plenty for
// sprites) + EAC alpha. Same bytes-per-block as the DXT source: memory parity restored on GPUs
// without S3TC (Mali & friends). RimDroid-fork-only, never for upstream (box64 AGENTS.md).
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "rd_etc2.h"

// Cumulative wall time spent inside rd_etc2_encode, in milliseconds. The Tecno report ("fps
// halved with ETC2") needs a NUMBER, not a theory: the caller logs this every N textures, so a
// field log states the price outright — and doubles as the build fingerprint (version strings
// don't change between test builds; the watchdog taught us that lesson).
static uint64_t rd_etc2_total_ns = 0;
uint64_t rd_etc2_total_ms(void) { return rd_etc2_total_ns / 1000000u; }

static const int rd_etc1_mod[8][2] = {{2,8},{5,17},{9,29},{13,42},{18,60},{24,80},{33,106},{47,183}};
static const int8_t rd_eac_mod[16][8] = {
    {-3,-6,-9,-15,2,5,8,14},{-3,-7,-10,-13,2,6,9,12},{-2,-5,-8,-13,1,4,7,12},{-2,-4,-6,-13,1,3,5,12},
    {-3,-6,-8,-12,2,5,7,11},{-3,-7,-9,-11,2,6,8,10},{-4,-7,-8,-11,3,6,7,10},{-3,-5,-8,-11,2,4,7,10},
    {-2,-6,-8,-10,1,5,7,9},{-2,-5,-8,-10,1,4,7,9},{-2,-4,-8,-10,1,3,7,9},{-2,-5,-7,-10,1,4,6,9},
    {-3,-4,-7,-10,2,3,6,9},{-1,-2,-3,-10,0,1,2,9},{-4,-6,-8,-9,3,5,7,8},{-3,-5,-7,-9,2,4,6,8}};

static inline int rd_clamp255(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

/* Encode one 4x4 RGB block (px[16][3], col-major p = x*4+y) into ETC1-subset bits. */
static void rd_etc1_encode_block(const uint8_t px[16][3], uint8_t out[8]) {
    // Fast path: a uniform block needs no search — both subblocks share the quantized base,
    // every pixel takes the smallest modifier (+2 from table 0; max error 2 per channel).
    int uniform = 1;
    for (int p = 1; p < 16 && uniform; p++)
        uniform = px[p][0] == px[0][0] && px[p][1] == px[0][1] && px[p][2] == px[0][2];
    if (uniform) {
        int b5[3];
        for (int k = 0; k < 3; k++) b5[k] = (px[0][k] * 31 + 127) / 255;
        out[0] = (uint8_t)(b5[0] << 3);          /* delta 0 */
        out[1] = (uint8_t)(b5[1] << 3);
        out[2] = (uint8_t)(b5[2] << 3);
        out[3] = 2;                              /* tables 0/0, diff mode, no flip */
        out[4] = 0; out[5] = 0;                  /* all indices 00 = +a (+2) */
        out[6] = 0; out[7] = 0;
        return;
    }
    int best_err = 0x7fffffff;
    for (int flip = 0; flip < 2; flip++) {
        int avg[2][3] = {{0,0,0},{0,0,0}};
        for (int p = 0; p < 16; p++) {
            int sub = flip ? ((p & 3) >> 1) : (p >> 3);
            for (int k = 0; k < 3; k++) avg[sub][k] += px[p][k];
        }
        for (int s = 0; s < 2; s++) for (int k = 0; k < 3; k++) avg[s][k] = (avg[s][k] + 4) / 8;
        int b5[2][3], diff_ok = 1;
        for (int k = 0; k < 3; k++) {
            b5[0][k] = (avg[0][k] * 31 + 127) / 255;
            b5[1][k] = (avg[1][k] * 31 + 127) / 255;
            int d = b5[1][k] - b5[0][k];
            if (d < -4 || d > 3) diff_ok = 0;
        }
        int base[2][3];
        if (diff_ok) {
            for (int s = 0; s < 2; s++) for (int k = 0; k < 3; k++)
                base[s][k] = (b5[s][k] << 3) | (b5[s][k] >> 2);
        } else {
            for (int s = 0; s < 2; s++) for (int k = 0; k < 3; k++) {
                int q = (avg[s][k] * 15 + 127) / 255;
                base[s][k] = (q << 4) | q;
            }
        }
        int tbl[2] = {0,0}; uint32_t idx[2] = {0,0}; int err_total = 0;
        for (int s = 0; s < 2; s++) {
            // Table preselect: the tables are sorted by magnitude; only the ones bracketing the
            // subblock's max per-channel deviation from base can win. Try [t-1, t, t+1].
            int maxdev = 0;
            for (int p = 0; p < 16; p++) {
                int sub = flip ? ((p & 3) >> 1) : (p >> 3);
                if (sub != s) continue;
                for (int k = 0; k < 3; k++) {
                    int d = px[p][k] - base[s][k];
                    if (d < 0) d = -d;
                    if (d > maxdev) maxdev = d;
                }
            }
            int t_pick = 7;
            for (int t = 0; t < 8; t++) if (rd_etc1_mod[t][1] >= maxdev) { t_pick = t; break; }
            int t_lo = t_pick > 0 ? t_pick - 1 : 0;
            int t_hi = t_pick < 7 ? t_pick + 1 : 7;
            int best_sub = 0x7fffffff, best_t = t_lo; uint32_t best_idx = 0;
            for (int t = t_lo; t <= t_hi; t++) {
                int e_sum = 0; uint32_t ind = 0;
                for (int p = 0; p < 16; p++) {
                    int sub = flip ? ((p & 3) >> 1) : (p >> 3);
                    if (sub != s) continue;
                    int be = 0x7fffffff, bi = 0;
                    for (int m = 0; m < 4; m++) {
                        int mod = (m & 1) ? rd_etc1_mod[t][1] : rd_etc1_mod[t][0];
                        if (m & 2) mod = -mod;
                        int e = 0;
                        for (int k = 0; k < 3; k++) {
                            int d = px[p][k] - rd_clamp255(base[s][k] + mod);
                            e += d * d;
                        }
                        if (e < be) { be = e; bi = ((m & 2) ? 2 : 0) | (m & 1); }
                    }
                    e_sum += be;
                    ind |= (uint32_t)bi << (2 * p);
                }
                if (e_sum < best_sub) { best_sub = e_sum; best_t = t; best_idx = ind; }
            }
            tbl[s] = best_t; idx[s] = best_idx; err_total += best_sub;
        }
        if (err_total < best_err) {
            best_err = err_total;
            uint8_t o[8] = {0};
            if (diff_ok) {
                o[0] = (uint8_t)((b5[0][0] << 3) | ((b5[1][0] - b5[0][0]) & 7));
                o[1] = (uint8_t)((b5[0][1] << 3) | ((b5[1][1] - b5[0][1]) & 7));
                o[2] = (uint8_t)((b5[0][2] << 3) | ((b5[1][2] - b5[0][2]) & 7));
            } else {
                o[0] = (uint8_t)(((base[0][0] >> 4) << 4) | (base[1][0] >> 4));
                o[1] = (uint8_t)(((base[0][1] >> 4) << 4) | (base[1][1] >> 4));
                o[2] = (uint8_t)(((base[0][2] >> 4) << 4) | (base[1][2] >> 4));
            }
            o[3] = (uint8_t)((tbl[0] << 5) | (tbl[1] << 2) | (diff_ok ? 2 : 0) | flip);
            uint16_t msb = 0, lsb = 0;
            uint32_t all = idx[0] | idx[1];
            for (int p = 0; p < 16; p++) {
                uint32_t bi = (all >> (2 * p)) & 3;
                if (bi & 2) msb |= (uint16_t)(1u << p);
                if (bi & 1) lsb |= (uint16_t)(1u << p);
            }
            o[4] = (uint8_t)(msb >> 8); o[5] = (uint8_t)(msb & 0xff);
            o[6] = (uint8_t)(lsb >> 8); o[7] = (uint8_t)(lsb & 0xff);
            memcpy(out, o, 8);
        }
    }
}

/* Encode one 4x4 alpha block (a[16], col-major) into EAC bits. Bounded search. */
static void rd_eac_encode_block(const uint8_t a[16], uint8_t out[8]) {
    int amin = 255, amax = 0;
    for (int p = 0; p < 16; p++) { if (a[p] < amin) amin = a[p]; if (a[p] > amax) amax = a[p]; }
    if (amin == amax) {   // flat alpha (fully opaque sprites dominate) — exact, no search
        out[0] = (uint8_t)amin;
        out[1] = (uint8_t)((1 << 4) | 13);       /* mult 1, table 13 has a 0 modifier at index 4 */
        uint64_t bits = 0;
        for (int p = 0; p < 16; p++) bits |= (uint64_t)4 << (45 - 3 * p);
        for (int i = 0; i < 6; i++) out[2 + i] = (uint8_t)(bits >> (40 - 8 * i));
        return;
    }
    int best_err = 0x7fffffff;
    for (int t = 0; t < 16; t++) {
        int span = rd_eac_mod[t][7] - rd_eac_mod[t][3];
        int m0 = span ? (amax - amin + span / 2) / span : 1;
        for (int dm = -1; dm <= 1; dm++) {
            int mult = m0 + dm; if (mult < 1) mult = 1; if (mult > 15) mult = 15;
            int b0 = (amin + amax) / 2 - mult * (rd_eac_mod[t][3] + rd_eac_mod[t][7]) / 2;
            for (int db = -1; db <= 1; db++) {
                int base = rd_clamp255(b0 + db);
                int e_sum = 0; uint8_t sel[16];
                for (int p = 0; p < 16; p++) {
                    int be = 0x7fffffff, bs = 0;
                    for (int s = 0; s < 8; s++) {
                        int v = rd_clamp255(base + mult * rd_eac_mod[t][s]);
                        int d = a[p] - v, e = d * d;
                        if (e < be) { be = e; bs = s; }
                    }
                    e_sum += be; sel[p] = (uint8_t)bs;
                }
                if (e_sum < best_err) {
                    best_err = e_sum;
                    out[0] = (uint8_t)base;
                    out[1] = (uint8_t)((mult << 4) | t);
                    uint64_t bits = 0;
                    for (int p = 0; p < 16; p++) bits |= (uint64_t)sel[p] << (45 - 3 * p);
                    for (int i = 0; i < 6; i++) out[2 + i] = (uint8_t)(bits >> (40 - 8 * i));
                }
            }
        }
    }
}

const void* rd_etc2_encode(uint32_t etc2fmt, int32_t w, int32_t h, const uint8_t* rgba, size_t* out_sz) {
    struct timespec t0, t1;
    clock_gettime(CLOCK_MONOTONIC, &t0);
    static _Thread_local uint8_t* ebuf = NULL; static _Thread_local size_t ecap = 0;
    const int alpha = (etc2fmt == 0x9278u || etc2fmt == 0x9279u);
    const int bs = alpha ? 16 : 8;
    const int bw = (w + 3) / 4, bh = (h + 3) / 4;
    size_t need = (size_t)bw * bh * bs;
    if (!need || need > (128u << 20)) return NULL;
    if (ecap < need) { void* nb = realloc(ebuf, need); if (!nb) return NULL; ebuf = (uint8_t*)nb; ecap = need; }
    for (int by = 0; by < bh; by++) for (int bx = 0; bx < bw; bx++) {
        uint8_t px[16][3], al[16];
        for (int p = 0; p < 16; p++) {                       /* col-major p = x*4+y, edge clamp */
            int x = bx * 4 + (p >> 2), y = by * 4 + (p & 3);
            if (x >= w) x = w - 1;
            if (y >= h) y = h - 1;
            const uint8_t* s = rgba + ((size_t)y * w + x) * 4;
            px[p][0] = s[0]; px[p][1] = s[1]; px[p][2] = s[2]; al[p] = s[3];
        }
        uint8_t* o = ebuf + ((size_t)by * bw + bx) * bs;
        if (alpha) { rd_eac_encode_block(al, o); rd_etc1_encode_block(px, o + 8); }
        else rd_etc1_encode_block(px, o);
    }
    *out_sz = need;
    clock_gettime(CLOCK_MONOTONIC, &t1);
    rd_etc2_total_ns += (uint64_t)(t1.tv_sec - t0.tv_sec) * 1000000000u + (uint64_t)(t1.tv_nsec - t0.tv_nsec);
    return ebuf;
}
