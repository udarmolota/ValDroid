#ifndef RD_ETC2_H
#define RD_ETC2_H
// RimDroid: S3TC->ETC2 transcode encoder (see rd_etc2.c). Lives in its OWN translation unit so it
// can be compiled -O2 while the rest of box64's Android build stays at the safe optimization level
// — a full-search block encoder at -O0 turned the RimWorld 1.6 atlas bake into a 10-minute load
// that Android's app watchdog then SIGKILLed. RimDroid-fork-only, never for upstream.
#include <stdint.h>
#include <stddef.h>

// Encode tight RGBA8 (w*h*4) into ETC2. etc2fmt: 0x9274/0x9275 = (S)RGB8_ETC2 (8B/block),
// 0x9278/0x9279 = (S)RGBA8_ETC2_EAC (16B/block). Returns a reusable thread-local buffer (valid
// until the next call on the same thread) and writes the byte size to out_sz; NULL on OOM/oversize.
const void* rd_etc2_encode(uint32_t etc2fmt, int32_t w, int32_t h, const uint8_t* rgba, size_t* out_sz);

// Cumulative wall time spent encoding, ms — the field-log price tag for "is the encoder eating
// the fps": the caller prints it alongside the throttled ETC2 upload log lines.
uint64_t rd_etc2_total_ms(void);

#endif
