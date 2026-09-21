#ifndef RD_ETC2_CACHE_H
#define RD_ETC2_CACHE_H
// RimDroid: on-disk cache for ETC2 transcodes (see rd_etc2_cache.c). Own translation unit, -O2,
// for the same reason as the encoder: the hash runs over every uploaded texture, and at the Debug
// build's -O0 it would cost a noticeable fraction of what the cache saves.
// RimDroid-fork-only, never for upstream (box64's AGENTS.md forbids AI-authored PRs).
#include <stdint.h>
#include <stddef.h>

// Cache on at all: a directory was provided (RIMDROID_ETC2_CACHE_DIR) and RIMDROID_ETC2_CACHE is
// not "0". Cached after the first call.
int rd_etc2c_on(void);

// Whether a level this size is worth a disk round trip. Tiny levels encode in microseconds and an
// open+read costs more than that, so they never touch the disk.
int rd_etc2c_worth(int32_t w, int32_t h);

// Cache key for one upload. `src`/`n` are the bytes the ETC2 result is a pure function of — the
// RGBA pixels, or the ORIGINAL compressed DXT data (which lets a hit skip the DXT decode as well
// as the encode). `srcfmt` names what those bytes are, so equal bytes of different formats never
// collide. The storage version is folded into the seed: bump it whenever the encoder's output
// changes and every old entry simply stops matching.
uint64_t rd_etc2c_key(const void* src, size_t n, uint32_t srcfmt, uint32_t etc2fmt, int32_t w, int32_t h);

// Look up a key. On a hit returns the ETC2 blocks (a thread-local buffer, valid until the next call
// on the same thread) and writes their size; NULL on a miss. A file whose header does not match
// the request counts as a miss and is overwritten by the next put.
const void* rd_etc2c_get(uint64_t key, uint32_t etc2fmt, int32_t w, int32_t h, size_t* out_sz);

// Store an encode result. Written to a temp file and renamed into place, so a process killed
// mid-write never leaves a truncated entry behind. May trigger LRU eviction.
void rd_etc2c_put(uint64_t key, uint32_t etc2fmt, int32_t w, int32_t h, const void* data, size_t sz);

// Session counters, for the log lines the caller prints.
uint64_t rd_etc2c_hits(void);
uint64_t rd_etc2c_misses(void);
uint64_t rd_etc2c_evicted(void);
uint64_t rd_etc2c_io_ms(void);

#endif
