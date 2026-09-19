#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <errno.h>
#include <string.h>
#include <assert.h>
#include <unistd.h>   // RimDroid [RD-DELTA] capture: access() for the file trigger

#include "os.h"
#include "debug.h"
#include "box64context.h"
#include "custommem.h"
#include "box64cpu.h"
#include "emu/x64emu_private.h"
#include "x64emu.h"
#include "box64stack.h"
#include "callback.h"
#include "emu/x64run_private.h"
#include "x64trace.h"
#include "dynablock.h"
#include "dynablock_private.h"
#include "alternate.h"

#include "dynarec_native.h"
#include "dynarec_arch.h"
#include "dynarec_next.h"
#include "gdbjit.h"
#include "khash.h"

KHASH_MAP_INIT_INT64(table64, uint32_t)
KHASH_SET_INIT_INT64(nextset)

static kh_nextset_t* khnextset = NULL;

void printf_x64_instruction(dynarec_native_t* dyn, zydis_dec_t* dec, instruction_x64_t* inst, const char* name) {
    uint8_t *ip = (uint8_t*)inst->addr;
    if (ip[0] == 0xcc && IsBridgeSignature(ip[1], ip[2])) {
        uintptr_t a = *(uintptr_t*)(ip+3);
        if(a==0) {
            dynarec_log(LOG_NONE, "%s%p: Exit x64emu%s\n", (dyn->need_dump>1)?"\e[01;33m":"", (void*)ip, (dyn->need_dump>1)?"\e[m":"");
        } else {
            dynarec_log(LOG_NONE, "%s%p: Native call to %p%s\n", (dyn->need_dump>1)?"\e[01;33m":"", (void*)ip, (void*)a, (dyn->need_dump>1)?"\e[m":"");
        }
    } else {
        if(dec) {
            dynarec_log(LOG_NONE, "%s%p: %s", (dyn->need_dump > 1) ? "\e[01;33m" : "", ip, DecodeX64Trace(dec, inst->addr, 1));
        } else {
            dynarec_log(LOG_NONE, "%s%p: ", (dyn->need_dump>1)?"\e[01;33m":"", ip);
            for(int i=0; i<inst->size; ++i) {
                dynarec_log_prefix(0, LOG_NONE, "%02X ", ip[i]);
            }
            dynarec_log_prefix(0, LOG_NONE, " %s", name);
        }
        // print Call function name if possible
        if(ip[0]==0xE8 || ip[0]==0xE9) { // Call / Jmp
            uintptr_t nextaddr = (uintptr_t)ip + 5 + *((int32_t*)(ip+1));
            PrintFunctionAddr(nextaddr, "=> ");
        } else if(ip[0]==0xFF) {
            if(ip[1]==0x25) {
                uintptr_t nextaddr = (uintptr_t)ip + 6 + *((int32_t*)(ip+2));
                PrintFunctionAddr(nextaddr, "=> ");
            }
        }
        // end of line and colors
        dynarec_log_prefix(0, LOG_NONE, "%s\n", (dyn->need_dump>1)?"\e[m":"");
    }
}

void add_next(dynarec_native_t *dyn, uintptr_t addr) {
    if (!BOX64DRENV(dynarec_bigblock))
        return;
    int ret;
    kh_put(nextset, khnextset, addr, &ret);
    if(!ret)
        return;
    // add slots
    if(dyn->next_sz == dyn->next_cap) {
        printf_log(LOG_NONE, "Warning, overallocating next\n");
    }
    dyn->next[dyn->next_sz++] = addr;
}
uintptr_t get_closest_next(dynarec_native_t *dyn, uintptr_t addr) {
    // get closest, but no addresses before
    uintptr_t best = 0;
    int i = 0;
    while(i<dyn->next_sz) {
        uintptr_t next = dyn->next[i];
        if(!next || next<addr) {
            if(next) {
                khint_t k = kh_get(nextset, khnextset, next);
                if(k != kh_end(khnextset))
                    kh_del(nextset, khnextset, k);
            }
            dyn->next[i] = dyn->next[--dyn->next_sz];
            continue;
        }
        if((next<best) || !best)
            best = next;
        ++i;
    }
    return best;
}
void add_jump(dynarec_native_t *dyn, int ninst) {
    // add slots
    if(dyn->jmp_sz == dyn->jmp_cap) {
        printf_log(LOG_NONE, "Warning, overallocating jmps\n");
    }
    dyn->jmps[dyn->jmp_sz++] = ninst;
}
int get_first_jump(dynarec_native_t *dyn, int next) {
    if(next<0 || next>dyn->size)
        return -2;
    return get_first_jump_addr(dyn, dyn->insts[next].x64.addr);
}
int get_first_jump_addr(dynarec_native_t *dyn, uintptr_t next) {
    for(int i=0; i<dyn->jmp_sz; ++i)
        if(dyn->insts[dyn->jmps[i]].x64.jmp == next)
            return dyn->jmps[i];
    return -2;
}

#define PK(A) (*((uint8_t*)(addr+(A))))
int is_nops(dynarec_native_t *dyn, uintptr_t addr, int n)
{
    if(!n)
        return 1;
    if(PK(0)==0x90)
        return is_nops(dyn, addr+1, n-1);
    if(n>1 && PK(0)==0x66)  // if opcode start with 0x66, and there is more after, than is *can* be a NOP
        return is_nops(dyn, addr+1, n-1);
    if(n>1 && PK(0)==0xF3 && PK(1)==0x90)
        return is_nops(dyn, addr+2, n-2);
    if(n>2 && PK(0)==0x0f && PK(1)==0x1f && PK(2)==0x00)
        return is_nops(dyn, addr+3, n-3);
    if(n>2 && PK(0)==0x8d && PK(1)==0x76 && PK(2)==0x00)    // lea esi, [esi]
        return is_nops(dyn, addr+3, n-3);
    if(n>3 && PK(0)==0x0f && PK(1)==0x1f && PK(2)==0x40 && PK(3)==0x00)
        return is_nops(dyn, addr+4, n-4);
    if(n>3 && PK(0)==0x8d && PK(1)==0x74 && PK(2)==0x26 && PK(3)==0x00)
        return is_nops(dyn, addr+4, n-4);
    if(n>4 && PK(0)==0x0f && PK(1)==0x1f && PK(2)==0x44 && PK(3)==0x00 && PK(4)==0x00)
        return is_nops(dyn, addr+5, n-5);
    if(n>5 && PK(0)==0x8d && PK(1)==0xb6 && PK(2)==0x00 && PK(3)==0x00 && PK(4)==0x00 && PK(5)==0x00)
        return is_nops(dyn, addr+6, n-6);
    if(n>6 && PK(0)==0x0f && PK(1)==0x1f && PK(2)==0x80 && PK(3)==0x00 && PK(4)==0x00 && PK(5)==0x00 && PK(6)==0x00)
        return is_nops(dyn, addr+7, n-7);
    if(n>6 && PK(0)==0x8d && PK(1)==0xb4 && PK(2)==0x26 && PK(3)==0x00 && PK(4)==0x00 && PK(5)==0x00 && PK(6)==0x00) // lea esi, [esi+0]
        return is_nops(dyn, addr+7, n-7);
    if(n>7 && PK(0)==0x0f && PK(1)==0x1f && PK(2)==0x84 && PK(3)==0x00 && PK(4)==0x00 && PK(5)==0x00 && PK(6)==0x00 && PK(7)==0x00)
        return is_nops(dyn, addr+8, n-8);
    return 0;
}
#undef PK

void addInst(instsize_t* insts, size_t* size, int x64_size, int native_size)
{
    // x64 instruction is <16 bytes
    int toadd;
    if(x64_size>native_size)
        toadd = 1 + x64_size/15;
    else
        toadd = 1 + native_size/15;
    while(toadd) {
        if(x64_size>15)
            insts[*size].x64 = 15;
        else
            insts[*size].x64 = x64_size;
        x64_size -= insts[*size].x64;
        if(native_size>15)
            insts[*size].nat = 15;
        else
            insts[*size].nat = native_size;
        native_size -= insts[*size].nat;
        ++(*size);
        --toadd;
    }
}

static kh_table64_t* khtable64 = NULL;

int isTable64(dynarec_native_t *dyn, uint64_t val)
{
    if(!khtable64)
        return 0;
    if(kh_get(table64, khtable64, val)==kh_end(khtable64))
        return 0;
    return 1;
}
// add a value to table64 (if needed) and gives back the imm19 to use in LDR_literal
int Table64(dynarec_native_t *dyn, uint64_t val, int pass)
{
    if(!khtable64)
        khtable64 = kh_init(table64);
    // find the value if already present
    khint_t k = kh_get(table64, khtable64, val);
    uint32_t idx = 0;
    if(k!=kh_end(khtable64)) {
        idx = kh_value(khtable64, k);
    } else {
        idx = dyn->table64size++;
        if(pass==3) {
            if(idx < dyn->table64cap)
                dyn->table64[idx] = val;
            else
                printf_log(LOG_NONE, "Warning, table64 bigger than expected %d vs %d\n", idx, dyn->table64cap);
        }
        int ret;
        k = kh_put(table64, khtable64, val, &ret);
        kh_value(khtable64, k) = idx;
    }
    // calculate offset
    int delta = dyn->tablestart + idx*sizeof(uint64_t) - (uintptr_t)dyn->block;
    return delta;
}

void ResetTable64(dynarec_native_t* dyn)
{
    dyn->table64size = 0;
    if(khtable64) {
        kh_clear(table64, khtable64);
    }
}

static void recurse_mark_alive(dynarec_native_t* dyn, int i)
{
    if(dyn->insts[i].x64.alive)
        return;
    dyn->insts[i].x64.alive = 1;
    if(dyn->insts[i].x64.jmp && dyn->insts[i].x64.jmp_insts!=-1)
        recurse_mark_alive(dyn, dyn->insts[i].x64.jmp_insts);
    if(i<dyn->size-1 && dyn->insts[i].x64.has_next)
        recurse_mark_alive(dyn, i+1);
}

static void sizePredecessors(dynarec_native_t* dyn)
{
    // compute total size of predecessor to allocate the array
    // mark alive...
    recurse_mark_alive(dyn, 0);
    // first compute the jumps
    int jmpto;
    for(int i=0; i<dyn->size; ++i) {
        if(dyn->insts[i].x64.alive && dyn->insts[i].x64.jmp && ((jmpto=dyn->insts[i].x64.jmp_insts)!=-1)) {
            dyn->insts[jmpto].pred_sz++;
        }
    }
    // remove "has_next" from orphan branch
    for(int i=0; i<dyn->size-1; ++i) {
        if(dyn->insts[i].x64.has_next && !dyn->insts[i+1].x64.alive)
            dyn->insts[i].x64.has_next = 0;
    }
    // second the "has_next"
    for(int i=0; i<dyn->size-1; ++i) {
        if(dyn->insts[i].x64.has_next) {
            dyn->insts[i+1].pred_sz++;
        }
    }
}
static void fillPredecessors(dynarec_native_t* dyn)
{
    // fill pred pointer
    int* p = dyn->predecessor;
    for(int i=0; i<dyn->size; ++i) {
        dyn->insts[i].pred = p;
        p += dyn->insts[i].pred_sz;
        dyn->insts[i].pred_sz=0;  // reset size, it's reused to actually fill pred[]
    }
    // fill pred
    for(int i=0; i<dyn->size; ++i) if(dyn->insts[i].x64.alive) {
        if((i!=dyn->size-1) && dyn->insts[i].x64.has_next)
            dyn->insts[i+1].pred[dyn->insts[i+1].pred_sz++] = i;
        if(dyn->insts[i].x64.jmp && (dyn->insts[i].x64.jmp_insts!=-1)) {
            int j = dyn->insts[i].x64.jmp_insts;
            dyn->insts[j].pred[dyn->insts[j].pred_sz++] = i;
        }
    }
}

// updateNeed for the current block. recursive function that goes backward
static int updateNeed(dynarec_native_t* dyn, int ninst, uint8_t need) {
    while (ninst>=0) {
        // need pending but instruction is only a subset: remove pend and use an X_ALL instead
        need |= dyn->insts[ninst].x64.need_after;
        if((need&X_PEND) && ((dyn->insts[ninst].x64.state_flags==SF_SUBSET) || (dyn->insts[ninst].x64.state_flags==SF_SET) || (dyn->insts[ninst].x64.state_flags==SF_SET_NODF))) {
            need &=~X_PEND;
            need |= X_ALL;
            STOP_NATIVE_FLAGS(dyn, ninst);
        }
        if((need&X_PEND) && dyn->insts[ninst].x64.state_flags==SF_SUBSET_PENDING) {
            need |= X_ALL&~(dyn->insts[ninst].x64.set_flags);
        }
        dyn->insts[ninst].x64.gen_flags = need&dyn->insts[ninst].x64.set_flags;
        if((need&X_PEND) && (dyn->insts[ninst].x64.state_flags&SF_PENDING))
            dyn->insts[ninst].x64.gen_flags |= X_PEND;
        dyn->insts[ninst].x64.need_after = need;
        need = dyn->insts[ninst].x64.need_after&~dyn->insts[ninst].x64.gen_flags;

        if(dyn->insts[ninst].x64.may_set)
            need |= dyn->insts[ninst].x64.gen_flags;    // forward the flags
        else if((need&X_PEND) && (dyn->insts[ninst].x64.set_flags&SF_PENDING))
            need &=~X_PEND;         // Consume X_PEND if relevant
        need |= dyn->insts[ninst].x64.use_flags;
        if(dyn->insts[ninst].x64.need_before == need)
            return ninst - 1;
        dyn->insts[ninst].x64.need_before = need;
        if(dyn->insts[ninst].x64.barrier&BARRIER_FLAGS) {
            need = need?X_PEND:0;
        }
        int ok = 0;
        for(int i=0; i<dyn->insts[ninst].pred_sz; ++i) {
            if(dyn->insts[ninst].pred[i] == ninst-1)
                ok = 1;
            else
                updateNeed(dyn, dyn->insts[ninst].pred[i], need);
        }
        --ninst;
        if(!ok)
            return ninst;
    }
    return ninst;
}

void* current_helper = NULL;
static int static_jmps[MAX_INSTS+2];
static uintptr_t static_next[MAX_INSTS+2];
static instruction_native_t static_insts[MAX_INSTS+2] = {0};
static callret_t static_callrets[MAX_INSTS+2] = {0};
static int static_preds[MAX_INSTS*2+2]; // for the worst case scenario were all instructions are conditional jumps
void* redundant_helper = NULL;
// TODO: ninst could be a uint16_t instead of an int, that could same some temp. memory

void ClearCache(void* start, size_t len)
{
#if defined(ARM64)
    // manually clear cache, I have issue with regular function on Ampere with kernel 6.12.4
    uintptr_t xstart = (uintptr_t)start;
    uintptr_t xend = (uintptr_t)start + len + 1;
    // Cache Type Info. Only grab the info once
    static uint64_t ctr_el0 = 0;
    if (ctr_el0 == 0)
        __asm __volatile("mrs %0, ctr_el0" : "=r"(ctr_el0));
    const int ctr_el0_idc = (ctr_el0>>28)&1;    // 0: datacache needs to be cleaned too, 1: no need
    const int ctr_el0_dic = (ctr_el0>>29)&1;    // 0: instruction cache needs to be cleaned, 1: no need
    const uintptr_t dcache_line_size = 4 << ((ctr_el0 >> 16) & 15);
    const uintptr_t icache_line_size = 4 << ((ctr_el0 >> 0) & 15);
    if (!ctr_el0_idc) {
        //purge each dcache line if no icache is defined...
        for (uint64_t addr=xstart&~(dcache_line_size-1); addr<xend; addr+=dcache_line_size)
            __asm __volatile("dc cvau, %0" ::"r"(addr));
    }
    __asm __volatile("dsb ish");
    if (!ctr_el0_dic) {
        // purge each icache line
        for (uint64_t addr=xstart&~(icache_line_size-1); addr<xend; addr+=icache_line_size)
            __asm __volatile("ic ivau, %0" ::"r"(addr));
        __asm __volatile("dsb ish");
    }
    __asm __volatile("isb sy");
#else
    __builtin___clear_cache(start, start+len+1);
#endif
}

NEW_JUMPBUFF(dynarec_jmpbuf);
int fillblock_active = 0;

void cancelFillBlock()
{
    LongJmp(GET_JUMPBUFF(dynarec_jmpbuf), 1);
}

void CancelBlock64(int need_lock)
{
    if(need_lock)
        mutex_lock(&my_context->mutex_dyndump);
    dynarec_native_t* helper = (dynarec_native_t*)current_helper;
    if(helper) {
        if(helper->dynablock && helper->dynablock->actual_block) {
            FreeDynarecMap((uintptr_t)helper->dynablock->actual_block);
            helper->dynablock->actual_block = NULL;
        }
    }
    fillblock_active = 0;
    current_helper = NULL;
    redundant_helper = NULL;
    if(need_lock)
        mutex_unlock(&my_context->mutex_dyndump);
}

uintptr_t native_pass0(dynarec_native_t* dyn, uintptr_t addr, int alternate, int is32bits, int inst_max);
uintptr_t native_pass1(dynarec_native_t* dyn, uintptr_t addr, int alternate, int is32bits, int inst_max);
uintptr_t native_pass2(dynarec_native_t* dyn, uintptr_t addr, int alternate, int is32bits, int inst_max);
uintptr_t native_pass3(dynarec_native_t* dyn, uintptr_t addr, int alternate, int is32bits, int inst_max);

dynablock_t* CreateEmptyBlock(uintptr_t addr, int is32bits, int is_new) {
    size_t sz = JMPNEXT_SIZE + sizeof(dynablock_t);
    void* actual_p = (void*)AllocDynarecMap(addr, sz, is_new);
    void* p = actual_p + sizeof(void*);
    if(actual_p==NULL) {
        dynarec_log(LOG_INFO, "AllocDynarecMap(%p, %zu) failed, canceling block\n", (void*)addr, sz);
        CancelBlock64(0);
        return NULL;
    }
    dynablock_t* block = (dynablock_t*)(actual_p+JMPNEXT_SIZE);
    memset(block, 0, sizeof(dynablock_t));
    // fill the block
    block->x64_addr = (void*)addr;
    block->x64_readaddr = addr;
    block->isize = 0;
    block->done = 0;
    block->size = sz;
    block->actual_block = actual_p;
    block->block = p;
    block->jmpnext = p;
    block->is32bits = is32bits;
    *(dynablock_t**)actual_p = block;
    *(void**)(p+JMPNEXT_SIZE-2*sizeof(void*)) = native_epilog;
    CreateJmpNext(block->jmpnext, p+JMPNEXT_SIZE-2*sizeof(void*));
    // all done...
    ClearCache(actual_p+sizeof(void*), JMPNEXT_SIZE-sizeof(void*));   // need to clear the cache before execution...
    return block;
}

// RimDroid [RD-DELTA] capture helpers (save-bug box64 fix). Bounds-checked guest-memory dumps (every read
// guarded by getProtection_fast) used ONE-SHOT to recover the Pawn ExposeData IMT conflict-thunk + its
// vtable neighbourhood, so the correct-slot DELTA can be computed offline. Inert unless armed by the
// /sdcard/Download/rd_capture trigger (see FillBlock64). printf_log(LOG_NONE) => always written to the log.
// readable? getProtection_fast under-reports Mono's GC/metadata heap (not box64-tracked), so accept plausible
// Mono ranges too: guest heap 0x70xx..0x74xx, libmono image 0x3f04xx..0x3f06xx.
static int rd_rd(uintptr_t p) {
    if(!p) return 0;
    if(getProtection_fast(p)&PROT_READ) return 1;
    // box64 doesn't track Mono/Unity's own data allocations (vtables, heap), so getProtection_fast
    // returns 0 for them — fall back to plausible-Mono-pointer ranges. The managed-heap/vtable
    // region's exact base is ASLR-dependent (Y700 ~0x72xx, Samsung ~0x74xx), so use a WIDE window
    // (0x60–0x80) rather than a tight one; every deref is structure-validated downstream.
    if((p>=0x6000000000ULL && p<0x8000000000ULL) || (p>=0x3f04000000ULL && p<0x3f06000000ULL)) return 1;
    return 0;
}

// The save-bug FIX (locate Pawn, rd_imt_fix) runs by DEFAULT. The heavy/noisy DIAGNOSTICS (good-phase probe,
// AnythingToStrip slot scan + entry-guard, thunk dumps, verbose logs) stay behind env RIMDROID_SAVEDIAG — they do
// expensive jit_code_hash passes that made good-phase save-LOADS crawl. rd_diag_on() gates only the diagnostics.
static int rd_diag_on(void) { static int v=-1; if(v<0) v = getenv("RIMDROID_SAVEDIAG")?1:0; return v; }

// RimDroid save-bug level-1/2 PROBE state. Set when the Pawn ExposeData conflict-thunk is detected at compile
// (FillBlock64); consumed by the trace-emit in dynarec_native_pass.c and by PrintTrace in
// dynarec_arm64_consts.c. PrintTrace fires once at the thunk's `cmp` (R_RIP==rd_probe_cmp_ip), where RDI = the
// `this` Pawn, computes the TRUE ExposeData vtable cell via Mono offsets (read-only) and logs level-1 vs level-2.
// The ExposeData IMT cookie is the INTERFACE method, shared by ALL classes implementing IExposable — so the
// first conflict-thunk with an "ExposeData" key is usually NOT Pawn's (seen: DefMap`2). So we arm a LIST of all
// ExposeData thunks; PrintTrace filters at execution by the dispatched object's klass name == "Pawn".
uintptr_t rd_probe_list[64]; int rd_probe_n = 0;
uintptr_t rd_probe_method = 0;
int rd_probe_done = 0;
// RimDroid PAWN SAVE FIX (2026-06-25, AI-consensus): Pawn's ExposeData has NO detectable IMT conflict-thunk — its
// mis-dispatch is a corrupted PLAIN vtable cell (pawn_vt+0x40+8*interface_offset(Pawn,IExposable) holds
// AnythingToStrip's code). Find Pawn read-only WITHOUT a dispatch: scan jit_code_hash for any method whose
// klass->name=="Pawn" -> pawn_klass; get its live MonoVTable via runtime_info (verified offsets: MonoClass+0xC8 =
// MonoClassRuntimeInfo; ri+0x00 = u16 max_domain; pawn_vt = *(ri + 0x8 + domain_id*8); MonoDomain+0xBC = domain_id),
// validate *(vt+0)==pawn_klass; compute the IExposable cell; write Pawn's real ExposeData JIT code (parent chain).
// One-shot per process. No Mono calls. Triggered from rd_savefix_repair once a domain is known.
static int rd_pawn_done = 0;
// Pawn fix state: located once (cell/klass/domain/initial-trampoline-value), then a cheap per-FillBlock tick
// applies the fix the moment the cell actually changes (gets dispatched/corrupted) AND ExposeData is compiled.
static uintptr_t rd_pawn_cell = 0, rd_pawn_tramp = 0, rd_pawn_domain = 0;
uintptr_t rd_pawn_klass = 0;       // non-static: PrintTrace (arm64_consts.c) externs it to filter the arg-trace to Pawn
uintptr_t rd_guard_addr = 0;       // non-static: AnythingToStrip entry — pass.c emits PrintTrace there, which dumps the dispatch call-site
uintptr_t rd_expose_code = 0;      // non-static: Pawn.ExposeData compiled code — the guard writes it into the mis-built IMT slot
static uintptr_t rd_pawn_vt = 0;   // Pawn's MonoVTable (for the AnythingToStrip slot scan)

// Find a method's compiled code in domain->jit_code_hash by name, walking the Pawn parent chain.
// Read-only; returns 0 if not (yet) compiled. (name match uses strncmp of `len`.)
static uintptr_t rd_find_code_by_name(const char* name, int len) {
    if(!rd_pawn_domain || !rd_pawn_klass) return 0;
    uintptr_t hh = rd_pawn_domain + 0xF0;
    if(!rd_rd(hh+0x20)) return 0;
    uint32_t  hsize   = *(uint32_t*)(hh+0x18);
    uintptr_t buckets = *(uintptr_t*)(hh+0x20);
    if(!rd_rd(buckets) || hsize==0 || hsize>0x100000) return 0;
    for(uintptr_t c=rd_pawn_klass, d=0; rd_rd(c) && d<24; c=*(uintptr_t*)(c+0x28), d++)
        for(uint32_t bk=0; bk<hsize; bk++){
            uintptr_t ji=*(uintptr_t*)(buckets+(uintptr_t)bk*8);
            for(int g=0; rd_rd(ji) && g<20000; ji=*(uintptr_t*)(ji+0x08), g++){
                uintptr_t m=*(uintptr_t*)(ji+0x00); if(!rd_rd(m)) continue;
                if(*(uintptr_t*)(m+0x08)!=c) continue;
                uintptr_t nmp=*(uintptr_t*)(m+0x18); if(!rd_rd(nmp)) continue;
                if(!strncmp((const char*)nmp,name,(size_t)len)) return *(uintptr_t*)(ji+0x10);
            }
        }
    return 0;
}

// Reverse-lookup: given a compiled code pointer, name the method ("Klass.Method") by scanning the WHOLE
// domain jit_code_hash for code_start==code. Read-only, one-shot use. Writes "?.?" if not found.
static void rd_name_of_code(uintptr_t code, char* out, int outlen) {
    if(outlen>0){ out[0]='?'; out[1]=0; }
    if(!rd_pawn_domain || !code) return;
    uintptr_t hh = rd_pawn_domain + 0xF0;
    if(!rd_rd(hh+0x20)) return;
    uint32_t  hsize   = *(uint32_t*)(hh+0x18);
    uintptr_t buckets = *(uintptr_t*)(hh+0x20);
    if(!rd_rd(buckets) || hsize==0 || hsize>0x100000) return;
    for(uint32_t bk=0; bk<hsize; bk++){
        uintptr_t ji=*(uintptr_t*)(buckets+(uintptr_t)bk*8);
        for(int g=0; rd_rd(ji) && g<20000; ji=*(uintptr_t*)(ji+0x08), g++){
            if(*(uintptr_t*)(ji+0x10)!=code) continue;
            uintptr_t m=*(uintptr_t*)(ji+0x00); if(!rd_rd(m)) return;
            uintptr_t nmp=*(uintptr_t*)(m+0x18);
            uintptr_t kl=*(uintptr_t*)(m+0x08);
            uintptr_t kn= rd_rd(kl+0x40)? *(uintptr_t*)(kl+0x40):0;
            snprintf(out,(size_t)outlen,"%.24s.%.24s", rd_rd(kn)?(const char*)kn:"?", rd_rd(nmp)?(const char*)nmp:"?");
            return;
        }
    }
}

// Sonnet's cheapest decisive probe: scan Pawn's MonoVTable for the slot that holds AnythingToStrip's
// compiled code — THAT is Pawn's real IMT ExposeData dispatch slot (the mis-dispatch target), wherever
// it lives (negative IMT region vt[-N] or positive vtable). Logs the offset (compare to the plain cell
// vt+0x40+8*14 = vt+0xB0: same => that IS the dispatch slot; different => plain slot is vestigial and
// the real one is elsewhere). Capped expensive jit-search; cheap window scan thereafter.
static int rd_strip_done = 0; static uintptr_t rd_strip_code = 0;
static void rd_pawn_scan_strip(void) {
    if(rd_strip_done || !rd_pawn_vt) return;
    if(!rd_strip_code) {
        // Only run the (expensive) jit_code_hash search once the Pawn cell has actually been dispatched
        // (cur != trampoline/NULL) — AnythingToStrip is compiled by then, so we don't burn early passes.
        if(!rd_rd(rd_pawn_cell)) return;
        uintptr_t cur = *(uintptr_t*)rd_pawn_cell;
        if(cur == rd_pawn_tramp || cur == 0) return;        // not dispatched yet
        rd_strip_code = rd_find_code_by_name("AnythingToStrip", 15);
        if(!rd_strip_code) return;                          // not compiled yet
        uintptr_t expose = rd_find_code_by_name("ExposeData", 11);   // 0 if it never compiled
        rd_expose_code = expose;     // hand it to the guard so it can repair the IMT slot to real ExposeData
        char nm[80]; rd_name_of_code(cur, nm, sizeof(nm));
        printf_log(LOG_NONE,"[RD-PAWNSCAN] strip=%p expose=%p plain_cell=%p ('%s'), pawn_vt=%p\n",
                   (void*)rd_strip_code,(void*)expose,(void*)cur,nm,(void*)rd_pawn_vt);
        // ARM the dispatch entry-guard: invalidate AnythingToStrip's block so it RECOMPILES with a PrintTrace
        // at its entry (pass.c keys on rd_guard_addr). On the NEXT mis-dispatch, PrintTrace dumps the caller
        // (return addr + call-site bytes) → ground truth: is offset 0x3C0 baked into the JIT call, or an IMT path?
        rd_guard_addr = rd_strip_code;
        cleanDBFromAddressRange(rd_strip_code, 0x40, 1);
        printf_log(LOG_NONE,"[RD-GUARD] armed AnythingToStrip @%p (block invalidated, will trace caller)\n",(void*)rd_strip_code);
    }
    int found=0;
    // (a) DIRECT: a slot in a WIDE vtable window holds AnythingToStrip's code.
    for(intptr_t off=-0x400; off<=0x800; off+=8){
        uintptr_t slot = rd_pawn_vt + (uintptr_t)off;
        if(!rd_rd(slot)) continue;
        if(*(uintptr_t*)slot == rd_strip_code){
            printf_log(LOG_NONE,"[RD-PAWNSCAN] DIRECT hit: vt%+ld (= %p) holds AnythingToStrip\n",(long)off,(void*)slot);
            found++;
        }
    }
    // (b) INDIRECT: a vtable field points to an array (a separate IMT table?) that holds AnythingToStrip's
    // code — Unity Mono may keep the IMT as a separate allocation rather than inline in the vtable.
    for(intptr_t off=-0x200; off<=0x200; off+=8){
        uintptr_t fld = rd_pawn_vt + (uintptr_t)off; if(!rd_rd(fld)) continue;
        uintptr_t arr = *(uintptr_t*)fld;            if(!rd_rd(arr)) continue;
        for(int i=-24;i<=32;i++){
            uintptr_t e = arr + (intptr_t)i*8; if(!rd_rd(e)) continue;
            if(*(uintptr_t*)e == rd_strip_code){
                printf_log(LOG_NONE,"[RD-PAWNSCAN] INDIRECT hit: vt%+ld -> arr[%d] holds AnythingToStrip\n",(long)off,i);
                found++;
            }
        }
    }
    rd_strip_done=1;   // one comprehensive pass once strip code is known
}

// Re-scan domain->jit_code_hash for Pawn's real ExposeData code (most-derived in parent chain) and write it into
// the cell. Called when the cell has changed from its initial trampoline (= dispatched/corrupted). One-shot.
static void rd_pawn_apply_fix(void) {
    if(rd_pawn_done || !rd_pawn_cell || !rd_rd(rd_pawn_cell)) return;
    uintptr_t cur = *(uintptr_t*)rd_pawn_cell;
    if(cur == rd_pawn_tramp) return;                 // not yet dispatched/corrupted — wait
    uintptr_t hh = rd_pawn_domain + 0xF0;
    if(!rd_rd(hh+0x20)) return;
    uint32_t  hsize   = *(uint32_t*)(hh+0x18);
    uintptr_t buckets = *(uintptr_t*)(hh+0x20);
    if(!rd_rd(buckets) || hsize==0 || hsize>0x100000) return;
    uintptr_t code=0, pk=0;
    for(uintptr_t c=rd_pawn_klass, d=0; rd_rd(c) && d<24 && !code; c=*(uintptr_t*)(c+0x28), d++)
        for(uint32_t bk=0; bk<hsize && !code; bk++){
            uintptr_t ji=*(uintptr_t*)(buckets+(uintptr_t)bk*8);
            for(int g=0; rd_rd(ji) && g<20000 && !code; ji=*(uintptr_t*)(ji+0x08), g++){
                uintptr_t m=*(uintptr_t*)(ji+0x00); if(!rd_rd(m)) continue;
                if(*(uintptr_t*)(m+0x08)!=c) continue;
                uintptr_t nmp=*(uintptr_t*)(m+0x18); if(!rd_rd(nmp)) continue;
                if(!strncmp((const char*)nmp,"ExposeData",11)){ code=*(uintptr_t*)(ji+0x10); pk=c; }
            }
        }
    if(code && code!=cur){
        *(uintptr_t*)rd_pawn_cell = code;
        rd_pawn_done = 1;
        printf_log(LOG_NONE, "[RD-PAWNFIX] APPLIED: cell %p %p -> %p (changed from tramp %p)\n",
                   (void*)rd_pawn_cell,(void*)cur,(void*)code,(void*)rd_pawn_tramp);
    } else if(code==cur) {
        rd_pawn_done = 1;  // already correct
    }
}
// PROACTIVE IMT-slot fix (the real fix — runs BEFORE any pawn dispatches, so NO pawn is lost). The dispatch
// `call [pawn_vt - 0x58]` goes through a box64-mis-built IMT slot whose thunk targets AnythingToStrip's vtable
// cell (pawn_vt+0x3C0) instead of ExposeData's (pawn_vt+0xB0). Scan the 19 IMT slots (pawn_vt[-19..-1]); the one
// whose thunk embeds the literal `pawn_vt+0x3C0` is the broken ExposeData slot — overwrite it to point straight
// at Pawn.ExposeData's compiled code. One-shot. (Confirmed fix point: the guard-write already took the save from
// 1 to 14 colonists; doing it here, before the first dispatch, saves the last one too.)
static int rd_imt_done = 0;
static void rd_imt_fix(void) {
    if(rd_imt_done || !rd_pawn_vt) return;
    // THROTTLE: the rd_find_code_by_name() below is a full jit_code_hash scan. Pawn is located during gameplay
    // but ExposeData only compiles around save time, so without this we'd run that scan on EVERY FillBlock for
    // the whole session → the game crawled. Only attempt every 256th tick while waiting; once fixed, done.
    static unsigned rd_imt_tick = 0;
    if((rd_imt_tick++ & 0xFF) != 0) return;
    uintptr_t expose = rd_find_code_by_name("ExposeData", 11);
    if(!expose) return;                                   // ExposeData not compiled yet — wait
    uintptr_t bad_cell = rd_pawn_vt + 0x3C0;              // AnythingToStrip's vtable cell (DIRECT-hit confirmed)
    for(int s=1; s<=19; s++){
        uintptr_t slotaddr = rd_pawn_vt - (uintptr_t)s*8; // IMT region = negative offsets before the vtable
        if(!rd_rd(slotaddr)) continue;
        uintptr_t thunk = *(uintptr_t*)slotaddr;
        if(!rd_rd(thunk)) continue;
        for(int o=0; o<0x60; o++){                        // the thunk embeds `mov r11, <bad_cell>` (49 BB <imm8>)
            if(!rd_rd(thunk+o+8)) break;
            if(*(uintptr_t*)(thunk+o) == bad_cell){
                printf_log(LOG_NONE,"[RD-IMTFIX] slot @%p (vt-0x%x) thunk %p refs bad_cell %p -> ExposeData %p\n",
                    (void*)slotaddr,s*8,(void*)thunk,(void*)bad_cell,(void*)expose);
                *(uintptr_t*)slotaddr = expose;           // dispatch now goes straight to ExposeData
                rd_imt_done = 1;
                return;
            }
        }
    }
}
// Cheap per-FillBlock tick: once located, watch the cell and fix as soon as it changes.
static void rd_pawn_tick(void) {
    if(!rd_pawn_cell) return;
    rd_imt_fix();           // THE FIX — always on. Proactively repair the mis-built IMT slot (before any pawn dispatches)
    if(!rd_diag_on()) return;   // everything below is diagnostics (slot scan, entry-guard, cell watch)
    rd_pawn_scan_strip();   // locate Pawn's REAL IMT dispatch slot (holds AnythingToStrip) — runs even after the plain-cell fix
    if(rd_pawn_done) return;
    static int tlog=0; static uintptr_t last=0;
    if(rd_rd(rd_pawn_cell)){
        uintptr_t cur=*(uintptr_t*)rd_pawn_cell;
        if(cur!=last && tlog<40){ tlog++; last=cur;
            printf_log(LOG_NONE,"[RD-PAWNFIX] tick *cell=%p (tramp=%p)\n",(void*)cur,(void*)rd_pawn_tramp); }
    }
    rd_pawn_apply_fix();
}
// RimDroid TEST toggle (env RIMDROID_NO_SAVEFIX=1): disable the entire save-fix machinery (FillBlock thunk
// detector + rd_savefix_repair code-rewrites + rd_imt_fix). Used to confirm whether the save-fix's per-block
// code rewriting is what causes the reduced-render flicker on loaded saves. Default OFF (the fix stays on).
static int rd_savefix_off(void) {
    static int v = -1;
    if(v < 0) v = getenv("RIMDROID_NO_SAVEFIX") ? 1 : 0;
    return v;
}
static void rd_repair_pawn(uintptr_t domain, uintptr_t expose_itf) {
    if(rd_pawn_done || rd_pawn_cell) return;   // already located (tick handles fixing) or done
    if(!rd_rd(domain) || !rd_rd(expose_itf+0x08)) return;
    uintptr_t itf_klass = *(uintptr_t*)(expose_itf+0x08);
    uintptr_t hh = domain + 0xF0;
    if(!rd_rd(hh+0x20)) return;
    uint32_t  hsize   = *(uint32_t*)(hh+0x18);
    uintptr_t buckets = *(uintptr_t*)(hh+0x20);
    if(!rd_rd(buckets) || hsize==0 || hsize>0x100000) return;
    // find pawn_klass = klass of any JIT'd method whose klass->name == "Pawn"
    uintptr_t pawn_klass = 0;
    for(uint32_t bk=0; bk<hsize && !pawn_klass; bk++){
        uintptr_t ji=*(uintptr_t*)(buckets+(uintptr_t)bk*8);
        for(int g=0; rd_rd(ji) && g<20000 && !pawn_klass; ji=*(uintptr_t*)(ji+0x08), g++){
            uintptr_t m=*(uintptr_t*)(ji+0x00); if(!rd_rd(m)) continue;
            uintptr_t k=*(uintptr_t*)(m+0x08); if(!rd_rd(k)||!rd_rd(k+0x40)) continue;
            uintptr_t kn=*(uintptr_t*)(k+0x40); if(!rd_rd(kn)) continue;
            if(!strncmp((const char*)kn,"Pawn",5)) pawn_klass=k;
        }
    }
    if(!pawn_klass){ printf_log(LOG_NONE,"[RD-PAWNFIX] Pawn klass not in jit hash yet\n"); return; }
    // (2) Pawn live MonoVTable via runtime_info
    uintptr_t ri = rd_rd(pawn_klass+0xc8)? *(uintptr_t*)(pawn_klass+0xc8):0;
    if(!rd_rd(ri)){ printf_log(LOG_NONE,"[RD-PAWNFIX] no runtime_info (klass=%p)\n",(void*)pawn_klass); return; }
    int domain_id  = rd_rd(domain+0xbc)? *(int*)(domain+0xbc):0;
    int max_domain = *(uint16_t*)ri;
    if(domain_id<0 || domain_id>max_domain){ printf_log(LOG_NONE,"[RD-PAWNFIX] domain_id %d > max %d\n",domain_id,max_domain); return; }
    uintptr_t vtp = ri + 0x8 + (uintptr_t)domain_id*8;
    uintptr_t pawn_vt = rd_rd(vtp)? *(uintptr_t*)vtp:0;
    if(!rd_rd(pawn_vt) || *(uintptr_t*)pawn_vt != pawn_klass){ printf_log(LOG_NONE,"[RD-PAWNFIX] vt validate fail vt=%p\n",(void*)pawn_vt); return; }
    // (3) interface_offset(Pawn, IExposable)
    int nn=*(uint16_t*)(pawn_klass+0x64);
    uintptr_t ifaces=*(uintptr_t*)(pawn_klass+0x68), offs=*(uintptr_t*)(pawn_klass+0x70);
    if(!rd_rd(ifaces)||!rd_rd(offs)||nn<=0||nn>=8192){ printf_log(LOG_NONE,"[RD-PAWNFIX] iface scan fail\n"); return; }
    int ioff=-1;
    for(int i=0;i<nn;i++) if(*(uintptr_t*)(ifaces+(uintptr_t)i*8)==itf_klass){ ioff=*(uint16_t*)(offs+(uintptr_t)i*2); break; }
    if(ioff<0){ printf_log(LOG_NONE,"[RD-PAWNFIX] Pawn doesn't implement IExposable?\n"); return; }
    uintptr_t cell = pawn_vt + 0x40 + 8*(uintptr_t)ioff;
    // LOCATED — store state; the per-FillBlock tick fixes the cell the moment it changes (gets corrupted) and
    // ExposeData is compiled. (At locate time the cell is usually still the uncompiled trampoline.)
    rd_pawn_cell = cell; rd_pawn_klass = pawn_klass; rd_pawn_domain = domain; rd_pawn_vt = pawn_vt;
    rd_pawn_tramp = rd_rd(cell)? *(uintptr_t*)cell : 0;
    printf_log(LOG_NONE,"[RD-PAWNFIX] LOCATED pawn_klass=%p vt=%p ioff=%d cell=%p *cell(tramp)=%p\n",
        (void*)pawn_klass,(void*)pawn_vt,ioff,(void*)cell,(void*)rd_pawn_tramp);
    // followup7 leading hypothesis: mono_class_interface_offset bsearch's ifaces[] assumes sort by interface_id
    // (MonoClass+0x5C). If box64 miscompiled the SORT at class setup, a mis-sorted array makes a CORRECT bsearch
    // return the wrong interface → interface_offset(Pawn,IExposable) resolves to IStrippable's 112 instead of 14.
    // Read-only check: log each interface as Name=interface_id + whether the id sequence is monotonic.
    {
        int ni = *(uint16_t*)(pawn_klass+0x64);
        uintptr_t ifc = rd_rd(pawn_klass+0x68)? *(uintptr_t*)(pawn_klass+0x68):0;
        if(rd_rd(ifc) && ni>0 && ni<8192){
            int sorted=1; uint32_t prev=0; char buf[480]; int bp=0;
            for(int i=0;i<ni && i<28 && bp<440;i++){
                uintptr_t e=*(uintptr_t*)(ifc+(uintptr_t)i*8); if(!rd_rd(e+0x5c)) continue;
                uint32_t id=*(uint32_t*)(e+0x5c);
                uintptr_t nm = rd_rd(e+0x40)? *(uintptr_t*)(e+0x40):0;
                if(i>0 && id<prev) sorted=0; prev=id;
                bp += snprintf(buf+bp,(size_t)(sizeof(buf)-bp),"%.14s=%u ", rd_rd(nm)?(const char*)nm:"?", id);
            }
            printf_log(LOG_NONE,"[RD-IFACES] Pawn n=%d SORTED=%d : %s\n",ni,sorted,buf);
        }
    }
    rd_pawn_apply_fix();   // fix immediately if it's already corrupted
}

// RimDroid SAVE FIX (2026-06-24, AI-consensus): class-agnostic compile-time repair of a corrupted IMT ExposeData
// vtable cell. box64 mis-builds the IMT conflict-thunk so the ExposeData vtable cell holds AnythingToStrip's code
// (a pointer INTO the thunk's own region, ~thunk+0x30) instead of the class's real ExposeData code -> objects
// (esp. Verse.Pawn) serialize empty. Given the corrupt cell (impl_slot) + the ExposeData INTERFACE method, this
// reverse-finds the owning MonoVTable (validated by the interface_offset formula: vt+0x40+8*interface_offset ==
// impl_slot), then writes the class's real ExposeData JIT code (jit_code_hash, most-derived class in the parent
// chain). Read-only except the single cell write; no Mono calls. Offsets verified vs _libmono.so: MonoVTable
// +0=klass,+0x10=domain,+0x40=method cells; MonoClass +0x28=parent,+0x40=name,+0x64=ifaceN,+0x68=ifaces,
// +0x70=iface_offsets; MonoMethod +0x08=klass,+0x18=name; MonoDomain+0xF0=jit_code_hash{size@0x18,buckets@0x20};
// MonoJitInfo{method@0,next@0x08,code_start@0x10}.
static void rd_savefix_repair(uintptr_t implslot, uintptr_t expose_itf) {
    uintptr_t itf_klass = rd_rd(expose_itf+0x08) ? *(uintptr_t*)(expose_itf+0x08) : 0;
    if(!rd_rd(itf_klass)) return;
    for(int delta=0x40; delta <= 0x40 + 8*2048; delta += 8) {
        uintptr_t vt = implslot - (uintptr_t)delta;
        if(!rd_rd(vt) || !rd_rd(vt+0x10) || !rd_rd(vt+0x70)) continue;
        uintptr_t klass  = *(uintptr_t*)vt;
        uintptr_t domain = *(uintptr_t*)(vt+0x10);
        if(!rd_rd(klass) || !rd_rd(domain) || !rd_rd(klass+0x70)) continue;
        int nn = *(uint16_t*)(klass+0x64);
        uintptr_t ifaces = *(uintptr_t*)(klass+0x68);
        uintptr_t offs   = *(uintptr_t*)(klass+0x70);
        if(!rd_rd(ifaces) || !rd_rd(offs) || nn<=0 || nn>=8192) continue;
        int ioff = -1;
        for(int i=0;i<nn;i++) if(*(uintptr_t*)(ifaces+(uintptr_t)i*8)==itf_klass){ ioff=*(uint16_t*)(offs+(uintptr_t)i*2); break; }
        if(ioff<0) continue;
        if(vt + 0x40 + 8*(uintptr_t)ioff != implslot) continue;   // STRONG validation: this IS the owner vtable
        // Pawn has no detectable conflict-thunk; repair its plain vtable cell directly (one-shot) now that we have a domain.
        rd_repair_pawn(domain, expose_itf);
        uintptr_t hh = domain + 0xF0;
        if(!rd_rd(hh+0x20)) return;
        uint32_t  hsize   = *(uint32_t*)(hh+0x18);
        uintptr_t buckets = *(uintptr_t*)(hh+0x20);
        if(!rd_rd(buckets) || hsize==0 || hsize>0x100000) return;
        static uintptr_t cand_k[1024], cand_c[1024]; int nc=0;
        for(uint32_t bk=0; bk<hsize && nc<1024; bk++){
            uintptr_t ji = *(uintptr_t*)(buckets+(uintptr_t)bk*8);
            for(int g=0; rd_rd(ji) && g<20000 && nc<1024; ji=*(uintptr_t*)(ji+0x08), g++){
                uintptr_t m=*(uintptr_t*)(ji+0x00); if(!rd_rd(m)) continue;
                uintptr_t nmp=*(uintptr_t*)(m+0x18); if(!rd_rd(nmp)) continue;
                if(!strncmp((const char*)nmp,"ExposeData",11)){ cand_k[nc]=*(uintptr_t*)(m+0x08); cand_c[nc]=*(uintptr_t*)(ji+0x10); nc++; }
            }
        }
        uintptr_t code=0, pk=0;
        for(uintptr_t c=klass, d=0; rd_rd(c) && d<24 && !code; c=*(uintptr_t*)(c+0x28), d++)
            for(int i=0;i<nc;i++) if(cand_k[i]==c){ code=cand_c[i]; pk=c; break; }
        uintptr_t kn  = rd_rd(klass+0x40)? *(uintptr_t*)(klass+0x40):0;
        uintptr_t pkn = rd_rd(pk+0x40)?    *(uintptr_t*)(pk+0x40):0;
        if(code && code != *(uintptr_t*)implslot) {
            *(uintptr_t*)implslot = code;
            printf_log(LOG_NONE, "[RD-SAVEFIX] %.20s.ExposeData cell %p -> %p (impl '%.20s')\n",
                rd_rd(kn)?(const char*)kn:"?", (void*)implslot, (void*)code, rd_rd(pkn)?(const char*)pkn:"?");
        } else {
            printf_log(LOG_NONE, "[RD-SAVEFIX] %.20s: code not found (nc=%d), cell %p left\n",
                rd_rd(kn)?(const char*)kn:"?", nc, (void*)implslot);
        }
        return;
    }
    printf_log(LOG_NONE, "[RD-SAVEFIX] reverse-find FAILED for implslot %p (no owner vtable in scan range)\n", (void*)implslot);
}

dynablock_t* FillBlock64(uintptr_t addr, int is32bits, int inst_max, int is_new, int noalt) {
    /*
        A Block must have this layout:

        0x0000..0x0007  : dynablock_t* : self
        0x0008..8+4*n   : actual Native instructions, (n is the total number)
        A ..    A+8*n   : Table64: n 64bits values
        B ..    B+7     : dynablock_t* : self (as part of JmpNext, that simulate another block)
        B+8 ..  B+8+m   : Native code for jmpnext (or jmp epilog in case of empty block), m depends on arch
        B+J-8.. B+J-1   : jmpnext (or jmp_epilog) address. jumpnext is used when the block needs testing
                           (J = JMPNEXT_SIZE, varies by architecture)
        B+J ..  B+J+sz  : instsize (compressed array with each instruction length on x64 and native side)
        C ..    C+sz    : arch: arch specific info (likes flags info) per inst (can be absent)

    */
    const uint32_t req_prot = (box64_pagesize==4096)?(PROT_EXEC|PROT_READ):PROT_READ;
    uintptr_t old_addr = addr;
    #ifdef HAVE_ALTJUMP
    uintptr_t altjump = noalt?0:getAlternateJump((void*)addr, is32bits);
    if(altjump) {
        dynarec_log(LOG_INFO, "Building a Dynablock for %p with an alternate content at %p\n", (void*)addr, (void*)altjump);
        addr = altjump;
    }
    #else
    uintptr_t altjump = 0;
    #endif 
    if(addr>=BOX64ENV(nodynarec_start) && addr<BOX64ENV(nodynarec_end)) {
        dynarec_log(LOG_INFO, "Create empty block in no-dynarec zone\n");
        return BOX64ENV(nodynarec_delay)?NULL:CreateEmptyBlock(old_addr, is32bits, is_new);
    }
    int is_inhotpage = checkInHotPage(addr);
    if(is_inhotpage && !BOX64ENV(dynarec_dirty)) {
        dynarec_log(LOG_DEBUG, "Not creating dynablock at %p as in a HotPage\n", (void*)addr);
        return NULL;
    }
#ifndef _WIN32
    if((getProtection_fast(addr)&req_prot)!=req_prot) {// cannot be run, get out of the Dynarec
        dynarec_log(LOG_DEBUG, "Not creating dynablock at %p because EXEC protection is missing\n", (void*)addr);
        return NULL;
    }
#endif
    // RimDroid SAVE FIX (always-on, compile-time, class-agnostic). When an IMT ExposeData conflict-thunk compiles
    // and its vtable cell is corrupted (the cell points INTO the thunk's own region = AnythingToStrip's code, the
    // deterministic box64 mis-build signature), repair the cell to the class's real ExposeData code. No trigger,
    // no runtime trace, no Mono calls. Per-block cost = a couple of byte compares; the heavy repair runs only on
    // the rare corrupted-conflict-thunk match (capped). Catches Pawn's thunk whenever it compiles.
    // The save-bug FIX runs by DEFAULT (cheap): the thunk detector locates Pawn from a corrupt container thunk,
    // then rd_pawn_tick()->rd_imt_fix() repairs the mis-built IMT slot. Only the heavy/noisy DIAGNOSTICS
    // (firstkey logs, byte dumps, good-phase probe) are gated behind rd_diag_on() (env RIMDROID_SAVEDIAG) —
    // those did the expensive passes that made good-phase save-LOADS crawl.
    if(!rd_savefix_off()) {
        rd_pawn_tick();   // THE FIX (rd_imt_fix); its own diagnostics are gated inside
        static int rd_sf_n = 0, rd_sf_seen = 0;
        if(rd_sf_n < 256 && (getProtection_fast(addr)&PROT_READ) && (getProtection_fast(addr+12)&PROT_READ)) {
            uint8_t* b = (uint8_t*)addr;
            // conflict thunk = starts with `49 BB <key8> 4D 3B D3` (mov r11,key1 ; cmp r10,r11)
            if(b[0]==0x49 && b[1]==0xbb && b[10]==0x4d && b[11]==0x3b && b[12]==0xd3) {
                // diagnostic: log this thunk's FIRST key name (AnythingToStrip-first => it's Pawn's thunk)
                uintptr_t k0 = *(uintptr_t*)(addr+2);
                if(rd_diag_on() && rd_sf_seen < 80 && rd_rd(k0) && rd_rd(k0+0x18)) {
                    uintptr_t n0 = *(uintptr_t*)(k0+0x18);
                    if(rd_rd(n0)) { rd_sf_seen++;
                        printf_log(LOG_NONE, "[RD-SAVEFIX] thunk @%p firstkey='%.24s'\n", (void*)addr, (const char*)n0); }
                    // One-shot raw byte dump of the FIRST ExposeData thunk, so we can read its real
                    // entry encoding (the strict pattern below misses these — different thunk shape).
                    static int rd_dumped = 0;
                    if(!rd_dumped && rd_rd(n0) && !strncmp((const char*)n0,"ExposeData",11)
                       && (getProtection_fast(addr+0x50)&PROT_READ)) {
                        rd_dumped = 1;
                        char hex[0x50*3+1]; int hp=0;
                        for(int q=0;q<0x50;q++) hp += snprintf(hex+hp, (size_t)(sizeof(hex)-hp), "%02x ", b[q]);
                        printf_log(LOG_NONE, "[RD-SAVEFIX] DUMP @%p (0x50): %s\n", (void*)addr, hex);
                    }
                }
                // scan EVERY entry (handles multi-collision thunks where ExposeData is NOT the first key):
                // each entry = `49 BB <key> 4D 3B D3 75 ?? 49 BB <impl> 41 FF 23`; cmp at offset p.
                for(int p=10; p<0x100 && rd_sf_n<256; p++) {
                    if(!(getProtection_fast(addr+p+17)&PROT_READ)) break;
                    if(b[p]==0x4d && b[p+1]==0x3b && b[p+2]==0xd3 && b[p+3]==0x75
                       && b[p+5]==0x49 && b[p+6]==0xbb && b[p+15]==0x41 && b[p+16]==0xff && b[p+17]==0x23) {
                        uintptr_t key  = *(uintptr_t*)(addr+p-8);
                        uintptr_t impl = *(uintptr_t*)(addr+p+7);
                        if(rd_rd(key) && rd_rd(key+0x18)) {
                            uintptr_t namep = *(uintptr_t*)(key+0x18);
                            if(rd_rd(namep) && !strncmp((const char*)namep, "ExposeData", 11) && rd_rd(impl)) {
                                uintptr_t v = *(uintptr_t*)impl;
                                if(v >= addr && v < addr + 0x100) {   // cell points back into the thunk = corrupted
                                    rd_sf_n++;
                                    if(rd_diag_on())
                                        printf_log(LOG_NONE, "[RD-SAVEFIX] detect corrupt @%p entry+%d key=%p impl=%p *impl=%p\n",
                                                   (void*)addr, p, (void*)key, (void*)impl, (void*)v);
                                    rd_savefix_repair(impl, key);   // FIX path: repairs the container cell AND locates Pawn (→ rd_imt_fix)
                                } else if(rd_diag_on()) {
                                    // GOOD-PHASE PROBE (diagnostics only): cell NOT corrupt, but locate Pawn anyway so
                                    // the scan/guard can study a working dispatch. Expensive (this was the good-phase
                                    // save-LOAD slowdown) → only when RIMDROID_SAVEDIAG is set. Not needed for the fix.
                                    static int rd_gp_probe_n = 0;
                                    if(!rd_pawn_cell && !rd_pawn_done && rd_gp_probe_n < 64) {
                                        rd_gp_probe_n++;
                                        rd_savefix_repair(impl, key);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if(current_helper) {
        if(current_helper==redundant_helper) {
            dynarec_log(LOG_INFO, "%04d|Warning: previous FillBlock did not cleaned up correctly (helper=%p, x64addr=%p, db=%p)\n", GetTID(), current_helper, (void*)((dynarec_native_t*)current_helper)->start, ((dynarec_native_t*)current_helper)->dynablock);
            return NULL;
        }
        dynarec_log(LOG_INFO, "Warning: some static area curruption appeared (current=%p, redundant=%p)\n", current_helper, redundant_helper);
    }
    // protect the 1st page
    protectDB(addr, 1);
    // init the helper
    dynarec_native_t helper = {0};
    dynarec_native_t* dyn = &helper;
    if(!khnextset)
        khnextset = kh_init(nextset);
    kh_clear(nextset, khnextset);
#ifdef GDBJIT
    helper.gdbjit_block = box_calloc(1, sizeof(gdbjit_block_t));
#endif
    redundant_helper = current_helper = &helper;
    helper.dynablock = NULL;
    helper.start = addr;
    uintptr_t start = addr;
    helper.cap = MAX_INSTS;
    helper.insts = static_insts;
    helper.jmps = static_jmps;
    helper.jmp_cap = MAX_INSTS;
    helper.next = static_next;
    helper.next_cap = MAX_INSTS;
    helper.table64 = NULL;
    helper.env = GetCurEnvByAddr(addr);
    if(getProtection(addr)&PROT_NEVERCLEAN) {
        helper.always_test = 1;
    }
    ResetTable64(&helper);
    helper.table64cap = 0;
    helper.end = addr + SizeFileMapped(addr);
    if(helper.end == helper.start)  // that means there is no mmap with a file associated to the memory
        helper.end = (uintptr_t)~0LL;
    helper.need_reloc = IsAddrNeedReloc(addr);
    size_t native_size = 0;
    size_t insts_rsize = 0;
    size_t arch_size = 0;
    size_t callret_size = 0;
    size_t sep_size = 0;
    size_t reloc_size = 0;
    size_t sz = 0;
    size_t dynablock_align = 0;
    size_t oldnativesize = 0;
    size_t oldinstsize = 0;
    uintptr_t end = 0;
    void* actual_p = NULL;
    void* p = NULL;
    void* next = NULL;
    void* instsize = NULL;
    void* tablestart = NULL;
    void* arch = NULL;
    void* callrets = NULL;
    void* seps = NULL;
    dynablock_t* block = NULL;
    #define BUILD_INIT 0
    #define BUILD_PASS0 1
    #define BUILD_PASS1 2
    #define BUILD_PASS2 3
    #define BUILD_PASS3 4
    #define BUILD_POST  50
    #define BUILD_DONE 100
    #define BUILD_ABORT_NULL    200
    #define BUILD_ABORT_EMPTY   201
    int state = BUILD_INIT;
    while(state!=BUILD_DONE) switch(state) {
        case BUILD_INIT:
            state = BUILD_PASS0;
            if (SigSetJmp(GET_JUMPBUFF(dynarec_jmpbuf), 1)) {
                if(state==BUILD_PASS0 && helper.size>1) {
                    end = helper.insts[helper.size].x64.addr;
                    --helper.size;
                    printf_log(LOG_INFO, "FillBlock at %p triggered a segfault, truncating at %d\n", (void*)addr, helper.size);
                    state = BUILD_PASS1;
                } else {
                    printf_log(LOG_INFO, "FillBlock at %p triggered a segfault (state=%d, size=%d), canceling\n", (void*)addr, state, helper.size);
                    state = BUILD_ABORT_EMPTY;
                }
            } else
                fillblock_active = 1;
            break;
        case BUILD_ABORT_NULL:
            CancelBlock64(0);
            return NULL;
        case BUILD_ABORT_EMPTY:
            CancelBlock64(0);
            return CreateEmptyBlock(old_addr, is32bits, is_new);
        case BUILD_PASS0:
            // pass 0, addresses, x64 jump addresses, overall size of the block
            end = native_pass0(&helper, addr, altjump?1:0, is32bits, inst_max);
            if(helper.abort) {
                if(helper.size<2) {
                    if(dyn->need_dump || BOX64ENV(dynarec_log))dynarec_log(LOG_NONE, "Abort dynablock on pass0\n");
                    state = BUILD_ABORT_EMPTY;
                    continue;
                }
                if(dyn->need_dump || BOX64ENV(dynarec_log))dynarec_log(LOG_NONE, "Dynablock shorten on pass0 at ninst=%d\n", helper.size);
                --helper.size;
                helper.abort = 0;
            }
            // basic checks
            if(!helper.size) {
                dynarec_log(LOG_INFO, "Warning, null-sized dynarec block (%p)\n", (void*)addr);
                state = BUILD_ABORT_EMPTY;
                continue;
            }
            // RimDroid (gated by BOX64_RD_HOTPAGE_HARDEN): the stock check below covers only the FIRST
            // page — pass0 reads instruction bytes from TAIL pages that are not write-protected yet, so
            // a concurrent Mono JIT patch there is invisible, and the torn instruction BOUNDARIES are
            // never re-verified (passes 1-3 re-read final bytes at torn offsets → internally-consistent
            // garbage that passes every hash check → the X7 pawn-save corruption). Check the WHOLE
            // range: if any part is unprotected (never-protected tail OR a write-trap fired during
            // pass0), protect the full range and PUNT (no block) — the interpreter runs this code for
            // now and the next compile attempt decodes fully-guarded, stable bytes.
            if(!is_inhotpage && BOX64ENV(rd_hotpage_harden) && !isprotectedDB(addr, end-addr)) {
                dynarec_log(LOG_INFO, "Block %p-%p not fully protected after pass0 (tail page or concurrent write), protect & punt\n", (void*)addr, (void*)end);
                protectDB(addr, end-addr);
                state = BUILD_ABORT_NULL;
                continue;
            }
            if(!is_inhotpage && !isprotectedDB(addr, 1)) {
                dynarec_log(LOG_INFO, "Warning, write on current page on pass0, aborting dynablock creation (%p)\n", (void*)addr);
                state = BUILD_ABORT_NULL;
                continue;
            }
            state = BUILD_PASS1;
            //fallthru
        case BUILD_PASS1:
            if(BOX64ENV(dynarec_x87double)==2) {
                helper.need_x87check = 1;
            }
            // protect the block of it goes over the 1st page
            if(!is_inhotpage)
                if((addr&~(box64_pagesize-1))!=(end&~(box64_pagesize-1))) // need to protect some other pages too
                    protectDB(addr, end-addr);  //end is 1byte after actual end
            // compute hash signature
            uint32_t hash = X31_hash_code((void*)addr, end-addr);
            // calculate barriers
            for(int ii=0; ii<helper.jmp_sz; ++ii) {
                int i = helper.jmps[ii];
                uintptr_t j = helper.insts[i].x64.jmp;
                helper.insts[i].x64.jmp_insts = -1;
                #ifndef ARCH_NOP
                if(j<start || j>=end || j==helper.insts[i].x64.addr)
                #else
                if(j<start || j>=end)
                #endif
                {
                    helper.insts[i].x64.need_after |= X_PEND;
                    if(helper.insts[i].barrier_maybe) {
                        helper.insts[i].x64.barrier|=BARRIER_FLOAT;
                        helper.insts[i].barrier_maybe = 0;
                    }
                } else {
                    // find jump address instruction
                    int k=-1;
                    int search = ((j>=helper.insts[0].x64.addr) && j<helper.insts[0].x64.addr+helper.isize)?1:0;
                    int imin = 0;
                    int imax = helper.size-1;
                    int i2 = helper.size/2;
                    // dichotomy search
                    while(search) {
                        if(helper.insts[i2].x64.addr == j) {
                            k = i2;
                            search = 0;
                        } else if(helper.insts[i2].x64.addr>j) {
                            imax = i2;
                            i2 = (imax+imin)/2;
                        } else {
                            imin = i2;
                            i2 = (imax+imin)/2;
                        }
                        if(search && (imax-imin)<2) {
                            search = 0;
                            if(helper.insts[imin].x64.addr==j)
                                k = imin;
                            else if(helper.insts[imax].x64.addr==j)
                                k = imax;
                        }
                    }
                    /*for(int i2=0; i2<helper.size && k==-1; ++i2) {
                        if(helper.insts[i2].x64.addr==j)
                            k=i2;
                    }*/
                    if(k!=-1) {
                        // special case, loop on itself with some nop in between
                        if(k<i && !helper.insts[i].x64.has_next && is_nops(&helper, helper.insts[k].x64.addr, helper.insts[i].x64.addr-helper.insts[k].x64.addr)) {
                            #ifndef ARCH_NOP
                            helper.always_test = 1;
                            k = -1;
                            #else
                            helper.insts[k].x64.self_loop = 1;
                            #endif
                        }
                        helper.insts[i].x64.jmp_insts = k;
                        helper.insts[i].barrier_maybe = 0;
                    } else {
                        helper.insts[i].x64.need_after |= X_PEND;
                        if(helper.insts[i].barrier_maybe) {
                            helper.insts[i].x64.barrier|=BARRIER_FLOAT;
                            helper.insts[i].barrier_maybe = 0;
                        }
                    }
                }
            }
            // fill predecessors with the jump address
            sizePredecessors(&helper);
            helper.predecessor = static_preds;
            fillPredecessors(&helper);

            PREUPDATE_SPECIFICS(&helper);

            int pos = helper.size-1;
            while (pos>=0)
                pos = updateNeed(&helper, pos, 0);
            // remove fpu stuff on non-executed code
            for(int i=1; i<helper.size-1; ++i)
                if(!helper.insts[i].pred_sz) {
                    int ii = i;
                    while(ii<helper.size && !helper.insts[ii].pred_sz) {
                        fpu_reset_ninst(&helper, ii);
                        RAZ_SPECIFIC(&helper, ii);
                        ++ii;
                    }
                    i = ii;
                }
            // remove trailling dead code
            while(helper.size && !helper.insts[helper.size-1].x64.alive) {
                helper.isize-=helper.insts[helper.size-1].x64.size;
                --helper.size;
            }
            if(!helper.size) {
                // NULL block after removing dead code, how is that possible?
                dynarec_log(LOG_INFO, "Warning, null-sized dynarec block after trimming dead code (%p)\n", (void*)addr);
                CancelBlock64(0);
                return CreateEmptyBlock(old_addr, is32bits, is_new);
            }
            UPDATE_SPECIFICS(&helper);
            // check for still valid close loop
            for(int ii=0; ii<helper.jmp_sz && !helper.always_test; ++ii) {
                int i = helper.jmps[ii];
                if(helper.insts[i].x64.alive && (helper.insts[i].x64.jmp==helper.insts[i].x64.addr)) {
                    #ifndef ARCH_NOP
                    helper.always_test = 1;
                    #else
                    helper.insts[i].x64.self_loop = 1;
                    #endif
                }
            }
            // no need for next anymore
            helper.next_sz = helper.next_cap = 0;
            helper.next = NULL;
            ResetTable64(&helper);
            helper.reloc_size = 0;
            // pass 1, float optimizations, first pass for flags
            native_pass1(&helper, addr, altjump?1:0, is32bits, inst_max);
            if(helper.abort) {
                if(dyn->need_dump || BOX64ENV(dynarec_log))dynarec_log(LOG_NONE, "Abort dynablock on pass1\n");
                state = BUILD_ABORT_NULL;
                continue;
            }
            state = BUILD_PASS2;
            //fallthrough
        case BUILD_PASS2:
            if(BOX64ENV(dynarec_x87double)==2) {
                if(helper.need_x87check==1)
                    helper.need_x87check = 0;
            }
            POSTUPDATE_SPECIFICS(&helper);
            ResetTable64(&helper);
            helper.reloc_size = 0;
            // pass 2, instruction size
            helper.callrets = static_callrets;
            native_pass2(&helper, addr, altjump?1:0, is32bits, inst_max);
            if(helper.abort) {
                if(dyn->need_dump || BOX64ENV(dynarec_log))dynarec_log(LOG_NONE, "Abort dynablock on pass2\n");
                state = BUILD_ABORT_NULL;
                continue;
            }
            state = BUILD_PASS3;
            //fallthrough
        case BUILD_PASS3:
            // keep size of instructions for signal handling
            native_size = (helper.native_size+7)&~7;   // round the size...
            // check if size is overlimit
            if((inst_max==MAX_INSTS) && (native_size>MAXBLOCK_SIZE)) {
                int imax = 0;
                size_t max_size = 0;
                while((max_size<MAXBLOCK_SIZE) && (imax<helper.size)) {
                    max_size += helper.insts[imax].size;
                    ++imax;
                }
                if(!imax) return NULL; //that should never happens
                --imax;
                if(dyn->need_dump || BOX64ENV(dynarec_log))dynarec_log(LOG_NONE, "Dynablock oversized, with %zu (max=%zd), recomputing cutting at %d from %d\n", native_size, MAXBLOCK_SIZE, imax, helper.size);
                CancelBlock64(0);
                return FillBlock64(old_addr, is32bits, imax, is_new, noalt);
            }
            insts_rsize = (helper.insts_size+2)*sizeof(instsize_t);
            insts_rsize = (insts_rsize+7)&~7;   // round the size...
            arch_size = ARCH_SIZE(&helper);
            callret_size = helper.callret_size*sizeof(callret_t);
            sep_size = helper.sep_size*sizeof(sep_t);
            reloc_size = helper.reloc_size*sizeof(uint32_t);
            // ok, now allocate mapped memory, with executable flag on
            sz = sizeof(void*) + native_size + helper.table64size*sizeof(uint64_t) + JMPNEXT_SIZE + insts_rsize + arch_size + callret_size + sep_size;
            dynablock_align = (sz&7)?(8 -(sz&7)):0;    // align dynablock
            sz += dynablock_align + sizeof(dynablock_t) + reloc_size;
            //           dynablock_t*     block (arm insts)            table64               jmpnext code       instsize     arch         callrets         sep  dynablock           relocs
            actual_p = (void*)AllocDynarecMap(old_addr, sz, is_new);
            if(actual_p==NULL) {
                dynarec_log(LOG_INFO, "AllocDynarecMap(%p, %zu) failed, canceling block\n", (void*)addr, sz);
                state = BUILD_ABORT_NULL;
                continue;
            }
            p = (void*)(((uintptr_t)actual_p) + sizeof(void*));
            tablestart = p + native_size;
            next = tablestart + helper.table64size*sizeof(uint64_t);
            instsize = next + JMPNEXT_SIZE;
            arch = instsize + insts_rsize;
            callrets = arch + arch_size;
            seps = callrets + callret_size;
            helper.block = p;
            block = (dynablock_t*)(seps+sep_size+dynablock_align);
            memset(block, 0, sizeof(dynablock_t));
            void* relocs = helper.need_reloc?(block+1):NULL;
            // fill the block
            block->x64_addr = (void*)addr;
            block->x64_readaddr = addr;
            block->isize = 0;
            block->actual_block = actual_p;
            helper.relocs = relocs;
            block->relocs = relocs;
            block->table64size = helper.table64size;
            helper.native_start = (uintptr_t)p;
            helper.tablestart = (uintptr_t)tablestart;
            helper.jmp_next = (uintptr_t)next+sizeof(void*);
            helper.instsize = (instsize_t*)instsize;
            *(dynablock_t**)actual_p = block;
            helper.table64cap = helper.table64size;
            helper.table64 = (uint64_t*)helper.tablestart;
            helper.callrets = (callret_t*)callrets;
            helper.sep = (sep_t*)seps;
            block->prefixsize = helper.prefixsize;
            block->table64 = helper.table64;
            helper.dynablock = block;
            if(callret_size)
                memcpy(helper.callrets, static_callrets, helper.callret_size*sizeof(callret_t));
            helper.callret_size = 0;
            helper.sep_size = 0;
            // pass 3, emit (log emit native opcode)
            if(dyn->need_dump) {
                dynarec_log(LOG_NONE, "%s%04d|Emitting %zu bytes for %u %s bytes (native=%zu, table64=%zu, instsize=%zu, arch=%zu, callrets=%zu, entry=%p)", (dyn->need_dump>1)?"\e[01;36m":"", GetTID(), helper.native_size, helper.isize, is32bits?"x86":"x64", native_size, helper.table64size*sizeof(uint64_t), insts_rsize, arch_size, callret_size, helper.block);
                PrintFunctionAddr(helper.start, " => ");
                dynarec_log_prefix(0, LOG_NONE, "%s\n", (dyn->need_dump>1)?"\e[m":"");
            }
            if (BOX64ENV(dynarec_gdbjit) && (!BOX64ENV(dynarec_gdbjit_end) || (addr >= BOX64ENV(dynarec_gdbjit_start) && addr < BOX64ENV(dynarec_gdbjit_end)))) {
                GdbJITNewBlock(helper.gdbjit_block, (GDB_CORE_ADDR)block->actual_block, (GDB_CORE_ADDR)block->actual_block + native_size, helper.start);
            }
            int oldtable64size = helper.table64size;
            oldnativesize = helper.native_size;
            oldinstsize = helper.insts_size;
            int oldsize= helper.size;
            helper.native_size = 0;
            ResetTable64(&helper); // reset table64 (but not the cap)
            helper.insts_size = 0;  // reset
            helper.reloc_size = 0;
            native_pass3(&helper, addr, altjump?1:0, is32bits, inst_max);
            if(helper.abort) {
                if(dyn->need_dump || BOX64ENV(dynarec_log))dynarec_log(LOG_NONE, "Abort dynablock on pass3\n");
                state = BUILD_ABORT_NULL;
                continue;
            }
            state = BUILD_POST;
            //fallthrough
        case BUILD_POST:
            // no need for jmps anymore
            helper.jmp_sz = helper.jmp_cap = 0;
            helper.jmps = NULL;
            // keep size of instructions for signal handling
            block->instsize = instsize;
            helper.table64 = NULL;
            helper.instsize = NULL;
            helper.predecessor = NULL;
            block->size = sz;
            block->isize = helper.size;
            block->block = p;
            block->jmpnext = next+sizeof(void*);
            #ifdef ARCH_CRC_INLINE
            block->always_test = 0;
            block->autocrc = helper.always_test?1:0;
            #else
            block->always_test = helper.always_test;
            block->autocrc = 0;
            #endif
            block->dirty = block->always_test;
            block->is32bits = is32bits;
            block->relocsize = helper.reloc_size*sizeof(uint32_t);
            if(arch_size) {
                block->arch_size = arch_size;
                block->arch = ARCH_FILL(&helper, arch, arch_size);
                if(!block->arch) block->arch_size = 0;
            } else {
                block->arch = NULL;
                block->arch_size = arch_size;
            }
            block->callret_size = helper.callret_size;
            block->callrets = helper.callrets;
            block->sep_size = helper.sep_size;
            block->sep = helper.sep;
            block->native_size = native_size;
            *(dynablock_t**)next = block;
            for(int i=0; i<helper.sep_size; ++i) {
                // setup the dynablock reference for secondary entry points
                void* p = (block->block + helper.sep[i].nat_offs - sizeof(void*));
                *(dynablock_t**)p = block;
            }
            *(void**)(next+JMPNEXT_SIZE-sizeof(void*)) = native_next;
            CreateJmpNext(block->jmpnext, next+JMPNEXT_SIZE-sizeof(void*));
            ClearCache(block->jmpnext, JMPNEXT_SIZE-sizeof(void*));
            //block->x64_addr = (void*)start;
            block->x64_size = end-start;
            // all done...
            if (BOX64ENV(dynarec_gdbjit) && (!BOX64ENV(dynarec_gdbjit_end) || (addr >= BOX64ENV(dynarec_gdbjit_start) && addr < BOX64ENV(dynarec_gdbjit_end)))) {
                if (BOX64ENV(dynarec_gdbjit) != 3) GdbJITBlockReady(helper.gdbjit_block);
                GdbJITBlockCleanup(helper.gdbjit_block);
                #ifdef GDBJIT
                block->gdbjit_block = helper.gdbjit_block;
                #endif
            }
            ClearCache(actual_p+sizeof(void*), native_size);   // need to clear the cache before execution...
            block->hash = X31_hash_code((void*)block->x64_readaddr, block->x64_size);
            // Check if something changed, to abort if it is
            if((helper.abort || (block->hash != hash))) {
                dynarec_log(LOG_DEBUG, "Warning, a block changed while being processed hash(%p:%ld)=%x/%x\n", block->x64_readaddr, block->x64_size, block->hash, hash);
                state = BUILD_ABORT_NULL;
                continue;
            }
            if((oldnativesize!=helper.native_size) || (oldtable64size<helper.table64size)) {
                printf_log(LOG_NONE, "Warning, size difference in block between pass2 (%zu, %d) & pass3 (%zu, %d)!\n", oldnativesize+oldtable64size*8, oldsize, helper.native_size+helper.table64size*8, helper.size);
                uint8_t *dump = (uint8_t*)helper.start;
                printf_log(LOG_NONE, "Dump of %d x64 opcodes:\n", helper.size);
                for(int i=0; i<helper.size; ++i) {
                    printf_log(LOG_NONE, "%s%p:", (helper.insts[i].size2!=helper.insts[i].size)?"=====> ":"", dump);
                    for(; dump<(uint8_t*)helper.insts[i+1].x64.addr; ++dump)
                        printf_log_prefix(0, LOG_NONE, " %02X", *dump);
                    printf_log_prefix(0, LOG_NONE, "\t%d -> %d", helper.insts[i].size2, helper.insts[i].size);
                    if(helper.insts[i].ymm0_pass2 || helper.insts[i].ymm0_pass3)
                        printf_log_prefix(0, LOG_NONE, "\t %04x -> %04x", helper.insts[i].ymm0_pass2, helper.insts[i].ymm0_pass3);
                    printf_log_prefix(0, LOG_NONE, "\n");
                }
                printf_log(LOG_NONE, "Table64 \t%d -> %d\n", oldtable64size*8, helper.table64size*8);
                printf_log(LOG_NONE, " ------------\n");
                state = BUILD_ABORT_NULL;
                continue;
            }
            state = BUILD_DONE;
    }
    fillblock_active = 0;   // disable the use of the LongJump if Segfault/Sigbus
    // ok, free the helper now
    ResetTable64(&helper);
    //dynaFree(helper.insts);
    helper.insts = NULL;
    if(insts_rsize/sizeof(instsize_t)<helper.insts_size) {
        printf_log(LOG_NONE, "Warning, insts_size difference in block between pass2 (%zu) and pass3 (%zu), allocated: %zu\n", oldinstsize, helper.insts_size, insts_rsize/sizeof(instsize_t));
    }
    if(!is_inhotpage && !isprotectedDB(addr, end-addr)) {
        dynarec_log(LOG_INFO, "Warning, block unprotected while being processed %p:%ld, marking as need_test\n", block->x64_addr, block->x64_size);
        block->dirty = 1;
        //protectDB(addr, end-addr);
    }
#ifdef ARCH_CRC_INLINE
    if(is_inhotpage && !block->autocrc)
        block->always_test = 2;
#else
    if(is_inhotpage)
        block->always_test = 2;
#endif
    if(block->always_test) {
        dynarec_log(LOG_INFO, "Note: block marked as always dirty %p:%ld\n", block->x64_addr, block->x64_size);
        #ifdef ARCH_NOP
        // mark callrets to trigger SIGILL to check clean state
        if(block->callret_size) {
            for(int i=0; i<block->callret_size; ++i)
                *(uint32_t*)(block->block+block->callrets[i].offs) = ARCH_UDF;
            ClearCache(block->block, block->size);
        }
        #endif
    }
    if(altjump) block->x64_addr = (void*)old_addr; // set the not-alt addr if a shadow jump was used
    redundant_helper = current_helper = NULL;
    //block->done = 1;
    return block;
}
