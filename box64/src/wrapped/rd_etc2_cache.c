// RimDroid: on-disk cache for ETC2 transcodes.
//
// Why: every launch re-encoded every texture from scratch — 12.7s of pure encoding per session on
// an Adreno 830 with Valheim, before counting the DXT decode that precedes half of it. The result
// is a pure function of the source bytes, so it can be computed once and read back afterwards.
//
// Design follows the cache Zomdroid ships in its NG-GL4ES and Mesa builds (content-addressed,
// 8-lane 64-bit hash, header-validated entries, temp+rename writes, LRU by mtime with a cap), but it
// is an independent implementation with its own file format: no other app can read this
// directory anyway, so byte compatibility would buy nothing.
//
// One deliberate difference: the caller may key an entry on the ORIGINAL compressed DXT bytes
// rather than on decoded pixels, so a hit skips the DXT decode as well as the ETC2 encode.
//
// Knobs: RIMDROID_ETC2_CACHE_DIR (set by the launcher; no dir = no cache), RIMDROID_ETC2_CACHE=0
// (encode every time), RIMDROID_ETC2_CACHE_MB (cap, default 1500).
// RimDroid-fork-only, never for upstream (box64's AGENTS.md forbids AI-authored PRs).
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <dirent.h>
#include <pthread.h>
#include <sys/stat.h>

#include "rd_etc2_cache.h"

// Bump whenever the ENCODER's output changes (rd_etc2.c), not only when this file format does:
// the version is folded into every key, so old entries stop matching instead of being served.
#define RD_E2C_VERSION 1u
#define RD_E2C_MAGIC   0x32454456u   /* "VDE2" little-endian */

typedef struct {
    uint32_t magic;
    uint32_t version;
    uint32_t etc2fmt;
    int32_t  w, h;
    uint32_t len;
} rd_e2c_header_t;

static pthread_mutex_t rd_e2c_mu = PTHREAD_MUTEX_INITIALIZER;
static char     rd_e2c_dir[512];
static int      rd_e2c_state = -1;         /* -1 unknown, 0 off, 1 on */
static long long rd_e2c_total = -1;        /* bytes on disk; -1 = not measured yet */
static uint64_t rd_e2c_hit_n = 0, rd_e2c_miss_n = 0, rd_e2c_evict_n = 0, rd_e2c_io_ns = 0;

static uint64_t rd_e2c_now_ns(void) {
    struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t);
    return (uint64_t)t.tv_sec * 1000000000ull + (uint64_t)t.tv_nsec;
}

int rd_etc2c_on(void) {
    if (rd_e2c_state >= 0) return rd_e2c_state;
    pthread_mutex_lock(&rd_e2c_mu);
    if (rd_e2c_state < 0) {
        const char* off = getenv("RIMDROID_ETC2_CACHE");
        const char* dir = getenv("RIMDROID_ETC2_CACHE_DIR");
        int on = !(off && off[0] == '0') && dir && dir[0] && strlen(dir) < sizeof(rd_e2c_dir) - 32;
        if (on) {
            strcpy(rd_e2c_dir, dir);
            if (mkdir(rd_e2c_dir, 0700) != 0 && errno != EEXIST) on = 0;
        }
        rd_e2c_state = on;
    }
    pthread_mutex_unlock(&rd_e2c_mu);
    return rd_e2c_state;
}

int rd_etc2c_worth(int32_t w, int32_t h) {
    return (int64_t)w * h > 64 * 64;
}

static long long rd_e2c_cap(void) {
    static long long cap = 0;
    if (!cap) {
        const char* e = getenv("RIMDROID_ETC2_CACHE_MB");
        long long mb = (e && e[0]) ? atoll(e) : 1500;
        if (mb < 64) mb = 64;
        cap = mb << 20;
    }
    return cap;
}

// ---- hash ------------------------------------------------------------------------------------
// Eight independent lanes: a single multiply chain is bound by the multiplier's latency, eight
// interleaved ones run at memory speed. memcpy for the loads because upload pointers carry no
// alignment promise; at -O2 it compiles to plain loads.
static uint64_t rd_e2c_fmix(uint64_t x) {
    x ^= x >> 33; x *= 0xff51afd7ed558ccdull;
    x ^= x >> 33; x *= 0xc4ceb9fe1a85ec53ull;
    x ^= x >> 33;
    return x;
}

static uint64_t rd_e2c_hash(const uint8_t* p, size_t n, uint64_t seed) {
    enum { L = 8 };
    uint64_t lane[L];
    for (int l = 0; l < L; l++) lane[l] = seed + (uint64_t)(l + 1) * 0x9e3779b97f4a7c15ull;
    size_t blocks = n / (8 * L);
    for (size_t b = 0; b < blocks; b++) {
        const uint8_t* q = p + b * 8 * L;
        for (int l = 0; l < L; l++) {
            uint64_t k; memcpy(&k, q + l * 8, 8);
            lane[l] ^= k;
            lane[l] *= 0x9fb21c651e98df25ull;
            lane[l] ^= lane[l] >> 29;
        }
    }
    // Tail: whatever did not fill a whole 64-byte block, byte-folded into lane 0.
    for (size_t i = blocks * 8 * L; i < n; i++) {
        lane[0] ^= p[i];
        lane[0] *= 0x100000001b3ull;
    }
    uint64_t h = seed ^ (uint64_t)n;
    for (int l = 0; l < L; l++) h = rd_e2c_fmix(h ^ lane[l]);
    return h;
}

uint64_t rd_etc2c_key(const void* src, size_t n, uint32_t srcfmt, uint32_t etc2fmt, int32_t w, int32_t h) {
    uint64_t seed = rd_e2c_fmix(((uint64_t)RD_E2C_VERSION << 48) ^ ((uint64_t)srcfmt << 16) ^ etc2fmt);
    seed = rd_e2c_fmix(seed ^ ((uint64_t)(uint32_t)w << 32) ^ (uint32_t)h);
    return rd_e2c_hash((const uint8_t*)src, n, seed);
}

// ---- entries ---------------------------------------------------------------------------------
static size_t rd_e2c_expected(uint32_t etc2fmt, int32_t w, int32_t h) {
    size_t bs = (etc2fmt == 0x9278u || etc2fmt == 0x9279u) ? 16 : 8;
    return (size_t)((w + 3) / 4) * (size_t)((h + 3) / 4) * bs;
}

static void rd_e2c_path(char* out, size_t cap, uint64_t key) {
    snprintf(out, cap, "%s/%016llx.e2", rd_e2c_dir, (unsigned long long)key);
}

static __thread uint8_t* rd_e2c_buf = NULL;
static __thread size_t   rd_e2c_bufcap = 0;

const void* rd_etc2c_get(uint64_t key, uint32_t etc2fmt, int32_t w, int32_t h, size_t* out_sz) {
    if (!rd_etc2c_on()) return NULL;
    uint64_t t0 = rd_e2c_now_ns();
    char path[600];
    rd_e2c_path(path, sizeof(path), key);
    const void* result = NULL;
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        rd_e2c_header_t hd;
        size_t want = rd_e2c_expected(etc2fmt, w, h);
        if (read(fd, &hd, sizeof(hd)) == (ssize_t)sizeof(hd)
                && hd.magic == RD_E2C_MAGIC && hd.version == RD_E2C_VERSION
                && hd.etc2fmt == etc2fmt && hd.w == w && hd.h == h && hd.len == want) {
            if (rd_e2c_bufcap < want) {
                uint8_t* nb = (uint8_t*)realloc(rd_e2c_buf, want);
                if (nb) { rd_e2c_buf = nb; rd_e2c_bufcap = want; }
            }
            if (rd_e2c_bufcap >= want) {
                size_t got = 0;
                while (got < want) {
                    ssize_t r = read(fd, rd_e2c_buf + got, want - got);
                    if (r <= 0) break;
                    got += (size_t)r;
                }
                if (got == want) { result = rd_e2c_buf; *out_sz = want; }
            }
        }
        // LRU freshness — but not on every hit: a session hits thousands of files and each touch is
        // a metadata write. Once a day is plenty to keep a file in use from being evicted.
        if (result) {
            struct stat st;
            if (fstat(fd, &st) == 0 && time(NULL) - st.st_mtime > 86400) futimens(fd, NULL);
        }
        close(fd);
    }
    pthread_mutex_lock(&rd_e2c_mu);
    if (result) rd_e2c_hit_n++; else rd_e2c_miss_n++;
    rd_e2c_io_ns += rd_e2c_now_ns() - t0;
    pthread_mutex_unlock(&rd_e2c_mu);
    return result;
}

// ---- eviction --------------------------------------------------------------------------------
typedef struct { time_t mtime; long long size; char name[40]; } rd_e2c_ent_t;

static int rd_e2c_ent_cmp(const void* a, const void* b) {
    time_t x = ((const rd_e2c_ent_t*)a)->mtime, y = ((const rd_e2c_ent_t*)b)->mtime;
    return (x > y) - (x < y);
}

// Measure what is on disk and, if over the cap, delete oldest-first down to 15/16 of it — the
// headroom keeps the next few writes from each triggering a full rescan. Caller holds the mutex.
static void rd_e2c_scan_evict(void) {
    DIR* d = opendir(rd_e2c_dir);
    if (!d) { rd_e2c_total = 0; return; }
    size_t n = 0, cap = 256;
    rd_e2c_ent_t* v = (rd_e2c_ent_t*)malloc(cap * sizeof(*v));
    long long total = 0;
    struct dirent* e;
    while ((e = readdir(d)) != NULL) {
        size_t ln = strlen(e->d_name);
        if (ln < 4 || ln >= sizeof(v[0].name) || strcmp(e->d_name + ln - 3, ".e2") != 0) continue;
        struct stat st;
        if (fstatat(dirfd(d), e->d_name, &st, 0) != 0) continue;
        total += st.st_size;
        if (!v) continue;
        if (n == cap) {
            rd_e2c_ent_t* nv = (rd_e2c_ent_t*)realloc(v, cap * 2 * sizeof(*v));
            if (!nv) continue;
            v = nv; cap *= 2;
        }
        v[n].mtime = st.st_mtime; v[n].size = st.st_size;
        strcpy(v[n].name, e->d_name);
        n++;
    }
    if (v && total > rd_e2c_cap()) {
        qsort(v, n, sizeof(*v), rd_e2c_ent_cmp);
        long long target = rd_e2c_cap() / 16 * 15;
        for (size_t i = 0; i < n && total > target; i++) {
            if (unlinkat(dirfd(d), v[i].name, 0) == 0) { total -= v[i].size; rd_e2c_evict_n++; }
        }
    }
    closedir(d);
    free(v);
    rd_e2c_total = total;
}

void rd_etc2c_put(uint64_t key, uint32_t etc2fmt, int32_t w, int32_t h, const void* data, size_t sz) {
    if (!rd_etc2c_on() || !data || sz != rd_e2c_expected(etc2fmt, w, h)) return;
    uint64_t t0 = rd_e2c_now_ns();
    char path[600], tmp[640];
    rd_e2c_path(path, sizeof(path), key);
    snprintf(tmp, sizeof(tmp), "%s.tmp%d", path, (int)gettid());
    int fd = open(tmp, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    int ok = 0;
    if (fd >= 0) {
        rd_e2c_header_t hd = { RD_E2C_MAGIC, RD_E2C_VERSION, etc2fmt, w, h, (uint32_t)sz };
        ok = write(fd, &hd, sizeof(hd)) == (ssize_t)sizeof(hd);
        size_t put = 0;
        while (ok && put < sz) {
            ssize_t r = write(fd, (const uint8_t*)data + put, sz - put);
            if (r <= 0) { ok = 0; break; }
            put += (size_t)r;
        }
        if (close(fd) != 0) ok = 0;
        if (ok) ok = rename(tmp, path) == 0;
        if (!ok) unlink(tmp);
    }
    pthread_mutex_lock(&rd_e2c_mu);
    if (ok) {
        if (rd_e2c_total < 0) rd_e2c_scan_evict();          /* first write: measure what is there */
        else rd_e2c_total += (long long)(sz + sizeof(rd_e2c_header_t));
        if (rd_e2c_total > rd_e2c_cap()) rd_e2c_scan_evict();
    }
    rd_e2c_io_ns += rd_e2c_now_ns() - t0;
    pthread_mutex_unlock(&rd_e2c_mu);
}

uint64_t rd_etc2c_hits(void)    { return rd_e2c_hit_n; }
uint64_t rd_etc2c_misses(void)  { return rd_e2c_miss_n; }
uint64_t rd_etc2c_evicted(void) { return rd_e2c_evict_n; }
uint64_t rd_etc2c_io_ms(void)   { return rd_e2c_io_ns / 1000000u; }
