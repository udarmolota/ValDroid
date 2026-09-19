#include <stdint.h>
#include <string.h>
#include <stdlib.h>

#include "dynarec_arm64_consts.h"
#include "debug.h"
#include "box64context.h"
#include "box64cpu.h"
#include "emu/x64emu_private.h"
#include "x64emu.h"
#include "box64stack.h"
#include "callback.h"
#include "emu/x64run_private.h"
#include "emu/x87emu_private.h"
#include "emu/x64primop.h"
#include "my_cpuid.h"
#include "freq.h"
#include "debug.h"
#include "custommem.h"
#include "dynarec_arm64_functions.h"
#include "emu/x64shaext.h"
#include "emu/x87emu_private.h"
#include "emu/x64compstrings.h"
#include "x64test.h"
#include "dynarec/dynarec_next.h"
#include "random.h"

static const int8_t mask_shift8[] = { -7, -6, -5, -4, -3, -2, -1, 0 };
static const int8_t mask_string8[] = { 7, 6, 5, 4, 3, 2, 1, 0 };
static const int8_t mask_string16[] = { 15, 14, 13, 12, 11, 10, 9, 8 };
static const float addsubps[4] = {-1.f, 1.f, -1.f, 1.f};
static const double addsubpd[2] = {-1., 1.};
static const float subaddps[4] = {1.f, -1.f, 1.f, -1.f};
static const double subaddpd[2] = {1., -1.};

#ifndef HAVE_TRACE
// [RD] op-hunt: zydis-free trace sink. Emitted (per-instruction) only for the env-selected range by
// dynarec_native_pass.c, so it logs guest registers as the Mono IMT resolver executes — at full
// dynarec speed, no interpreter, no stall. Called with emu in x0 (RIP already pinned to the insn addr).
void PrintTrace(x64emu_t* emu, uintptr_t ip, int b)
{
    (void)ip; (void)b;
    // RimDroid DISPATCH ENTRY-GUARD: at AnythingToStrip's entry (rd_guard_addr), dump the caller = the dispatch
    // call-site. R_RSP points at the return address (just pushed by the call); the call instruction is right
    // before it. Tells us whether 0x3C0 is baked into a JIT'd `call [reg+0x3C0]` (mechanism A) or it's an IMT
    // path (mechanism B). One-shot-ish (capped); returns early to skip the [RD-T] flood.
    {
        extern uintptr_t rd_guard_addr;
        if(rd_guard_addr && R_RIP==rd_guard_addr){
            #define RD_ING(p) ((((uintptr_t)(p))>=0x6000000000ULL&&((uintptr_t)(p))<0x8000000000ULL)||(((uintptr_t)(p))>=0x3f04000000ULL&&((uintptr_t)(p))<0x3f06000000ULL))
            static int rd_g_n=0;
            if(rd_g_n<24){ rd_g_n++;
                uintptr_t ret = R_RSP ? *(uintptr_t*)R_RSP : 0;
                int csok = (ret>=0x30000000ULL && ret<0x40000000ULL) || (ret>=0x3f04000000ULL && ret<0x3f06000000ULL);
                uint8_t* cs = csok ? (uint8_t*)(ret-8) : 0;
                int8_t disp = cs ? (int8_t)cs[7] : 0;          // ret-1 = the call's disp8 (FF 50 d8 form: call [rax+disp8])
                uintptr_t rax = R_RAX;
                uintptr_t objvt = RD_ING(R_RDI)? *(uintptr_t*)R_RDI : 0;   // object's vtable = *(this)
                uintptr_t target = rax + (intptr_t)disp;       // the call's effective address [rax+disp8]
                uintptr_t tval = RD_ING(target)? *(uintptr_t*)target : 0;  // what the slot holds (should be ExposeData)
                printf_log(LOG_NONE,"[RD-GUARD] rdi=%p objvt=%p rax=%p disp=%d -> target=%p *target=%p (target-objvt=%ld) bytes: %02x %02x %02x %02x %02x %02x %02x %02x\n",
                    (void*)R_RDI,(void*)objvt,(void*)rax,(int)disp,(void*)target,(void*)tval,(long)(target-objvt),
                    cs?cs[0]:0,cs?cs[1]:0,cs?cs[2]:0,cs?cs[3]:0,cs?cs[4]:0,cs?cs[5]:0,cs?cs[6]:0,cs?cs[7]:0);
            }
            // FIX ATTEMPT: the IMT slot [rax+disp] (= pawn_vt-0x58) holds a thunk that mis-dispatches ExposeData
            // to AnythingToStrip. Overwrite it with Pawn.ExposeData's real code so subsequent dispatches are
            // correct. (Single-target IMT slot — Pawn has no detectable conflict-thunk, so this is safe.) The pawn
            // that triggered THIS entry is already mis-dispatched; the rest of the save serializes correctly.
            {
                extern uintptr_t rd_expose_code;
                uintptr_t rax = R_RAX;
                uint8_t* cs2 = (uint8_t*)( (R_RSP?*(uintptr_t*)R_RSP:0) - 8 );
                int8_t disp = ((uintptr_t)cs2>0x1000) ? (int8_t)cs2[7] : 0;
                uintptr_t target = rax + (intptr_t)disp;
                if(rd_expose_code && RD_ING(target) && *(uintptr_t*)target != rd_expose_code){
                    static int rd_fx=0; if(rd_fx<8){ rd_fx++;
                        printf_log(LOG_NONE,"[RD-GUARD] FIX: IMT slot %p %p -> %p (ExposeData)\n",
                            (void*)target,(void*)*(uintptr_t*)target,(void*)rd_expose_code); }
                    *(uintptr_t*)target = rd_expose_code;
                }
            }
            #undef RD_ING
            return;
        }
    }
    // RimDroid ARG-TRACE: arm via env RIMDROID_TRACE_LO/HI = mono_class_interface_offset's ENTRY
    // (0x3f04174029-0x3f0417402a). At entry, SysV args: RDI=klass, RSI=target interface. When the klass is
    // Pawn, dump the TARGET interface (name + interface_id) + the caller return addr. Answers: is the WRONG
    // interface (IStrippable id=2273) passed in (→ caller bug), or IExposable (id=2269) being looked up but
    // still returning 112 (→ deeper)? One-shot-ish (capped). rd_pawn_klass is set once Pawn is located.
    {
        #define RD_IN(p) ((((uintptr_t)(p))>=0x6000000000ULL&&((uintptr_t)(p))<0x8000000000ULL)||(((uintptr_t)(p))>=0x3f04000000ULL&&((uintptr_t)(p))<0x3f06000000ULL))
        uintptr_t kl = R_RDI;
        uintptr_t kn = RD_IN(kl+0x40)? *(uintptr_t*)(kl+0x40):0;
        if(RD_IN(kn) && !strncmp((const char*)kn,"Pawn",5)){   // exact "Pawn\0"
            static int rd_arg_n=0;
            if(rd_arg_n<24){ rd_arg_n++;
                uintptr_t itf = R_RSI;
                uintptr_t inm = RD_IN(itf+0x40)? *(uintptr_t*)(itf+0x40):0;
                uint32_t  iid = RD_IN(itf+0x5c)? *(uint32_t*)(itf+0x5c):0;
                uintptr_t ret = RD_IN(R_RSP)? *(uintptr_t*)R_RSP:0;
                printf_log(LOG_NONE,"[RD-ARG] interface_offset(Pawn, itf=%p '%.20s' id=%u) ret=%p\n",
                    (void*)itf, RD_IN(inm)?(const char*)inm:"?", iid, (void*)ret);
            }
        }
        #undef RD_IN
    }
    // RimDroid save-bug level-1/2 PROBE (one-shot). Armed only on the Pawn ExposeData thunk's `cmp` (by
    // FillBlock64). RDI = this Pawn; r10 = IMT cookie (= ExposeData MonoMethod*). Compute the TRUE ExposeData
    // vtable cell via Mono offsets (read-only) and compare to the thunk's baked impl-slot:
    //   MonoObject+0 = MonoVTable ; MonoVTable+0 = MonoClass ; MonoVTable+0x40 = vtable[] cells
    //   MonoClass+0x64 = u16 iface count ; +0x68 = MonoClass** ifaces ; +0x70 = u16* iface_offsets
    //   MonoMethod+0x08 = klass (=IExposable) ; ExposeData slot = 0
    // Returns immediately (never the per-dispatch [RD-T] flood). No writes, no Mono calls.
    {
        extern uintptr_t rd_probe_list[]; extern int rd_probe_n;
        extern uintptr_t rd_probe_method; extern int rd_probe_done;
        { static int rd_pt_n=0; if(rd_probe_n>0 && rd_pt_n<30){ rd_pt_n++;
            printf_log(LOG_NONE, "[RD-PT] PrintTrace R_RIP=%p r10=%p method=%p n=%d\n",
                (void*)R_RIP,(void*)R_R10,(void*)rd_probe_method,rd_probe_n); } }
        int rd_in_list = 0;
        for(int i=0;i<rd_probe_n;i++) if(R_RIP==rd_probe_list[i]){ rd_in_list=1; break; }
        if(rd_in_list) {
            if(!rd_probe_done && R_R10==rd_probe_method) {
                #define RDIN(p) ((((uintptr_t)(p))>=0x7000000000ULL&&((uintptr_t)(p))<0x7400000000ULL)||(((uintptr_t)(p))>=0x3f04000000ULL&&((uintptr_t)(p))<0x3f06000000ULL))
                uintptr_t obj   = R_RDI;
                uintptr_t vt    = RDIN(obj)? *(uintptr_t*)obj : 0;
                uintptr_t klass = RDIN(vt)?  *(uintptr_t*)vt  : 0;
                uintptr_t kname = RDIN(klass)? *(uintptr_t*)(klass+0x40) : 0;
                int is_pawn = (RDIN(kname) && !strncmp((const char*)kname, "Pawn", 5));  // exact "Pawn\0"
                static int rd_fire_n = 0;
                if(rd_fire_n < 40) { rd_fire_n++;
                    printf_log(LOG_NONE, "[RD-PROBE] fire @%p klass=%p('%.20s') is_pawn=%d\n",
                        (void*)R_RIP,(void*)klass, RDIN(kname)?(const char*)kname:"?", is_pawn);
                }
                if(!is_pawn) {
                    // a DIFFERENT IExposable class sharing the ExposeData cookie (e.g. DefMap`2) — skip, stay
                    // armed; the Pawn thunk (also in the list) will fire on a Pawn dispatch later.
                    return;
                }
                rd_probe_done = 1;
                // impl-slot literal lives in this thunk's bytes: cmp@R_RIP, `49 BB <impl8>` at R_RIP+5, imm @ R_RIP+7
                uintptr_t implslot = *(uintptr_t*)(R_RIP + 7);
                uintptr_t itf = RDIN(rd_probe_method)? *(uintptr_t*)(rd_probe_method+0x08) : 0;
                int n=0, iface_off=-1; uintptr_t interfaces=0, offsets=0;
                if(RDIN(klass)) {
                    n          = *(uint16_t*)(klass+0x64);
                    interfaces = *(uintptr_t*)(klass+0x68);
                    offsets    = *(uintptr_t*)(klass+0x70);
                    if(RDIN(interfaces) && RDIN(offsets) && n>0 && n<8192)
                        for(int i=0;i<n;i++)
                            if(*(uintptr_t*)(interfaces+(uintptr_t)i*8)==itf){ iface_off=*(uint16_t*)(offsets+(uintptr_t)i*2); break; }
                }
                uintptr_t true_cell= (iface_off>=0 && RDIN(vt))? (vt + 0x40 + 8*(uintptr_t)iface_off) : 0;
                uintptr_t cur_impl = RDIN(implslot)? *(uintptr_t*)implslot : 0;
                printf_log(LOG_NONE, "[RD-PROBE] PAWN obj=%p vt=%p klass=%p('%.16s') iface_off=%d impl_slot=%p *impl=%p true_cell=%p delta=%ld\n",
                    (void*)obj,(void*)vt,(void*)klass,RDIN(kname)?(const char*)kname:"?",iface_off,
                    (void*)implslot,(void*)cur_impl,(void*)true_cell,(long)((intptr_t)implslot-(intptr_t)true_cell));
                // resolve real Pawn.ExposeData code: collect all JIT'd 'ExposeData' methods, pick the most-derived
                // override in pawn_klass's parent chain (klass->parent @ +0x28), write into true_cell = the FIX.
                uintptr_t domain = RDIN(vt)? *(uintptr_t*)(vt+0x10) : 0;
                uintptr_t cand_klass[64], cand_code[64]; int ncand=0;
                if(RDIN(domain)) {
                    uintptr_t hh = domain + 0xF0;
                    uint32_t  hsize   = *(uint32_t*)(hh+0x18);
                    uintptr_t buckets = *(uintptr_t*)(hh+0x20);
                    if(RDIN(buckets) && hsize>0 && hsize<0x100000)
                        for(uint32_t bk=0; bk<hsize && ncand<64; bk++) {
                            uintptr_t ji = *(uintptr_t*)(buckets + (uintptr_t)bk*8);
                            for(int g=0; RDIN(ji) && g<20000 && ncand<64; ji=*(uintptr_t*)(ji+0x08), g++) {
                                uintptr_t m = *(uintptr_t*)(ji+0x00); if(!RDIN(m)) continue;
                                uintptr_t nmp = *(uintptr_t*)(m+0x18); if(!RDIN(nmp)) continue;
                                if(!strncmp((const char*)nmp, "ExposeData", 11)) {
                                    cand_klass[ncand]=*(uintptr_t*)(m+0x08); cand_code[ncand]=*(uintptr_t*)(ji+0x10); ncand++;
                                }
                            }
                        }
                }
                uintptr_t expose_code=0, pick_klass=0;
                for(uintptr_t c=klass, d=0; RDIN(c) && d<24 && !expose_code; c=*(uintptr_t*)(c+0x28), d++) {
                    uintptr_t cn = RDIN(c)? *(uintptr_t*)(c+0x40):0;
                    printf_log(LOG_NONE, "[RD-PROBE] chain[%d] klass=%p '%.24s'\n", (int)d,(void*)c, RDIN(cn)?(const char*)cn:"?");
                    for(int i=0;i<ncand;i++) if(cand_klass[i]==c){ expose_code=cand_code[i]; pick_klass=c; break; }
                }
                uintptr_t pkname = RDIN(pick_klass)? *(uintptr_t*)(pick_klass+0x40):0;
                if(expose_code && expose_code!=cur_impl && RDIN(true_cell)) {
                    uintptr_t before = *(uintptr_t*)true_cell;
                    *(uintptr_t*)true_cell = expose_code;
                    printf_log(LOG_NONE, "[RD-PROBE] FIX applied: true_cell %p: %p -> %p (impl klass '%.24s')\n",
                        (void*)true_cell,(void*)before,(void*)expose_code, RDIN(pkname)?(const char*)pkname:"?");
                } else {
                    printf_log(LOG_NONE, "[RD-PROBE] NO FIX (expose_code=%p cur=%p ncand=%d) — candidates:\n",
                        (void*)expose_code,(void*)cur_impl,ncand);
                    for(int i=0;i<ncand && i<24;i++){ uintptr_t ckn=RDIN(cand_klass[i])?*(uintptr_t*)(cand_klass[i]+0x40):0;
                        printf_log(LOG_NONE, "[RD-PROBE]   cand[%d] klass=%p '%.24s' code=%p\n", i,(void*)cand_klass[i], RDIN(ckn)?(const char*)ckn:"?", (void*)cand_code[i]); }
                }
                #undef RDIN
            }
            return;  // listed thunk: do probe (one-shot once Pawn matched); skip the [RD-T] flood path
        }
    }
    // [RD] save-bug writer hunt. Two modes:
    //  - range mode (RIMDROID_TRACE_LO/HI set, RIMDROID_WATCH_ADDR unset): log every traced insn (default).
    //  - watch mode (RIMDROID_WATCH_ADDR set): log ONLY when some guest reg holds (watch_addr - watch_disp),
    //    i.e. this insn addresses the watched slot via [reg+disp]. Catches both the reader (RIP 0x...56c9d)
    //    and the WRITER (other RIP) of MonoClass+0x278 live, with no ring buffer / NRE hook. Distinguish by RIP.
    static int    rd_w_init = 0;
    static uintptr_t rd_w_addr = 0, rd_w_disp = 0x278;
    if(!rd_w_init){ rd_w_init = 1;
        char* a = getenv("RIMDROID_WATCH_ADDR"); char* d = getenv("RIMDROID_WATCH_DISP");
        if(a) rd_w_addr = (uintptr_t)strtoull(a, NULL, 0);
        if(d) rd_w_disp = (uintptr_t)strtoull(d, NULL, 0);
    }
    if(rd_w_addr){
        uintptr_t base = rd_w_addr - rd_w_disp;
        uintptr_t regs[16] = { R_RAX,R_RCX,R_RDX,R_RBX,R_RSP,R_RBP,R_RSI,R_RDI,
                               R_R8,R_R9,R_R10,R_R11,R_R12,R_R13,R_R14,R_R15 };
        int hit = 0;
        for(int i=0;i<16;i++) if(regs[i]==base){ hit=1; break; }
        if(!hit) return;
        // guest memory is mapped 1:1, so the slot's current value is directly readable.
        void* slotval = *(void**)rd_w_addr;
        printf_log(LOG_NONE, "[RD-W] rip=%p [%p]=%p rax=%p rcx=%p rdx=%p rbx=%p rsi=%p rdi=%p r8=%p r9=%p r10=%p r11=%p r12=%p r13=%p r14=%p r15=%p\n",
            (void*)R_RIP, (void*)rd_w_addr, slotval,
            (void*)R_RAX, (void*)R_RCX, (void*)R_RDX, (void*)R_RBX, (void*)R_RSI, (void*)R_RDI,
            (void*)R_R8, (void*)R_R9, (void*)R_R10, (void*)R_R11,
            (void*)R_R12, (void*)R_R13, (void*)R_R14, (void*)R_R15);
        return;
    }
    printf_log(LOG_NONE, "[RD-T] %p rax=%p rbx=%p rcx=%p rdx=%p rsi=%p rdi=%p r8=%p r9=%p r10=%p r11=%p r12=%p r13=%p r14=%p r15=%p\n",
        (void*)R_RIP, (void*)R_RAX, (void*)R_RBX, (void*)R_RCX, (void*)R_RDX,
        (void*)R_RSI, (void*)R_RDI, (void*)R_R8, (void*)R_R9, (void*)R_R10, (void*)R_R11,
        (void*)R_R12, (void*)R_R13, (void*)R_R14, (void*)R_R15);
}
#endif

uintptr_t getConst(arm64_consts_t which)
{
    switch(which) {
        case const_none: dynarec_log(LOG_NONE, "Warning, const none used\n");
            return 0;
        case const_daa8: return (uintptr_t)daa8;
        case const_das8: return (uintptr_t)das8;
        case const_aaa16: return (uintptr_t)aaa16;
        case const_aas16: return (uintptr_t)aas16;
        case const_aam16: return (uintptr_t)aam16;
        case const_aad16: return (uintptr_t)aad16;
        case const_native_br: return (uintptr_t)native_br;
        case const_native_ud: return (uintptr_t)native_ud;
        case const_native_priv: return (uintptr_t)native_priv;
        case const_native_gpf: return (uintptr_t)native_gpf;
        case const_native_int3: return (uintptr_t)native_int3;
        case const_native_int: return (uintptr_t)native_int;
        case const_native_div0: return (uintptr_t)native_div0;
        case const_native_frstor16: return (uintptr_t)native_frstor16;
        case const_native_fsave16: return (uintptr_t)native_fsave16;
        case const_native_fsave: return (uintptr_t)native_fsave;
        case const_native_aesimc: return (uintptr_t)native_aesimc;
        case const_native_aesd: return (uintptr_t)native_aesd;
        case const_native_aesd_y: return (uintptr_t)native_aesd_y;
        case const_native_aesdlast: return (uintptr_t)native_aesdlast;
        case const_native_aesdlast_y: return (uintptr_t)native_aesdlast_y;
        case const_native_aese: return (uintptr_t)native_aese;
        case const_native_aese_y: return (uintptr_t)native_aese_y;
        case const_native_aeselast: return (uintptr_t)native_aeselast;
        case const_native_aeselast_y: return (uintptr_t)native_aeselast_y;
        case const_native_aeskeygenassist: return (uintptr_t)native_aeskeygenassist;
        case const_native_pclmul: return (uintptr_t)native_pclmul;
        case const_native_pclmul_x: return (uintptr_t)native_pclmul_x;
        case const_native_pclmul_y: return (uintptr_t)native_pclmul_y;
        case const_direct_f2xm1: return (uintptr_t)direct_f2xm1;
        case const_direct_fyl2x: return (uintptr_t)direct_fyl2x;
        case const_direct_fyl2xp1: return (uintptr_t)direct_fyl2xp1;
        case const_native_fxtract: return (uintptr_t)native_fxtract;
        case const_direct_ftan: return (uintptr_t)direct_ftan;
        case const_direct_fpatan: return (uintptr_t)direct_fpatan;
        case const_direct_fcos: return (uintptr_t)direct_fcos;
        case const_direct_fsin: return (uintptr_t)direct_fsin;
        case const_native_fsincos: return (uintptr_t)native_fsincos;
        case const_direct_fscale: return (uintptr_t)direct_fscale;
        case const_native_fld: return (uintptr_t)native_fld;
        case const_native_fstp: return (uintptr_t)native_fstp;
        case const_native_frstor: return (uintptr_t)native_frstor;
        case const_native_next: return (uintptr_t)native_next;
        case const_native_next_invalidate: return (uintptr_t)arm64_next_invalid;
        case const_native_crc32: return (uintptr_t)arm64_crc;
        case const_native_x31: return (uintptr_t)arm64_x31_hash;
        case const_int3: return (uintptr_t)EmuInt3;
        case const_x86syscall: return (uintptr_t)EmuX86Syscall;
        case const_x64syscall: return (uintptr_t)EmuX64Syscall;
        case const_x64syscall_linux: return (uintptr_t)EmuX64Syscall_linux;
        case const_rcl16: return (uintptr_t)rcl16;
        case const_rcl32: return (uintptr_t)rcl32;
        case const_rcl64: return (uintptr_t)rcl64;
        case const_rcr16: return (uintptr_t)rcr16;
        case const_rcr32: return (uintptr_t)rcr32;
        case const_rcr64: return (uintptr_t)rcr64;
        case const_div64: return (uintptr_t)div64;
        case const_idiv64: return (uintptr_t)idiv64;
        case const_random32: return (uintptr_t)get_random32;
        case const_random64: return (uintptr_t)get_random64;
        case const_readtsc: return (uintptr_t)ReadTSC;
        case const_helper_getcpu: return (uintptr_t)helper_getcpu;
        case const_cpuid: return (uintptr_t)my_cpuid;
        case const_getsegmentbase: return (uintptr_t)GetSegmentBaseEmu;
        case const_updateflags_arm64: return (uintptr_t)create_updateflags();
        case const_reset_fpu: return (uintptr_t)reset_fpu;
        case const_sha1msg2: return (uintptr_t)sha1msg2;
        case const_sha1rnds4: return (uintptr_t)sha1rnds4;
        case const_sha256msg1: return (uintptr_t)sha256msg1;
        case const_sha256msg2: return (uintptr_t)sha256msg2;
        case const_sha256rnds2: return (uintptr_t)sha256rnds2;
        case const_fpu_loadenv: return (uintptr_t)fpu_loadenv;
        case const_fpu_savenv: return (uintptr_t)fpu_savenv;
        case const_fpu_fxsave32: return (uintptr_t)fpu_fxsave32;
        case const_fpu_fxsave64: return (uintptr_t)fpu_fxsave64;
        case const_fpu_fxrstor32: return (uintptr_t)fpu_fxrstor32;
        case const_fpu_fxrstor64: return (uintptr_t)fpu_fxrstor64;
        case const_fpu_xsave: return (uintptr_t)fpu_xsave;
        case const_fpu_xrstor: return (uintptr_t)fpu_xrstor;
        case const_fpu_fbld: return (uintptr_t)fpu_fbld;
        case const_fpu_fbst: return (uintptr_t)fpu_fbst;
        case const_sse42_compare_string_explicit_len: return (uintptr_t)sse42_compare_string_explicit_len;
        case const_sse42_compare_string_implicit_len: return (uintptr_t)sse42_compare_string_implicit_len;
        case const_x64test_step: return (uintptr_t)x64test_step;
        case const_printtrace: return (uintptr_t)PrintTrace;
        case const_epilog: return (uintptr_t)native_epilog;
        case const_jmptbl32: return getJumpTable32();
        case const_jmptbl48: return getJumpTable48();
        case const_jmptbl64: return getJumpTable64();
        case const_context: return (uintptr_t)my_context;
        case const_8b_m7_m6_m5_m4_m3_m2_m1_0: return (uintptr_t)&mask_shift8;
        case const_8b_7_6_5_4_3_2_1_0: return (uintptr_t)&mask_string8;
        case const_8b_15_14_13_12_11_10_9_8: return (uintptr_t)&mask_string16;
        case const_4f_m1_1_m1_1: return (uintptr_t)&addsubps;
        case const_4f_1_m1_1_m1: return (uintptr_t)&subaddps;
        case const_2d_m1_1: return (uintptr_t)&addsubpd;
        case const_2d_1_m1: return (uintptr_t)&subaddpd;

        case const_last: dynarec_log(LOG_NONE, "Warning, const last used\n");
            return 0;
    }
    dynarec_log(LOG_NONE, "Warning, Unknown const %d used\n", which);
    return 0;
}