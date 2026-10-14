/**
 * Manual function overrides and ICALL diagnostics
 *
 * This file provides:
 *   - recomp_lookup_manual()  : intercept specific Xbox VAs with hand-written code
 *   - recomp_icall_fail_log() : log when an indirect call target can't be resolved
 *   - ICALL trace ring buffer  : globals used by the RECOMP_ICALL macro
 *
 * The recomp pipeline generates an auto-dispatch table (recomp_lookup) that
 * resolves most function addresses. recomp_lookup_manual() is called FIRST,
 * giving you a chance to override any function with a custom implementation.
 *
 * Common reasons to add manual overrides:
 *   - Trace a function to understand call flow (wrap the generated version)
 *   - Fix a function the lifter translated incorrectly
 *   - Stub out a function that crashes (return early, set eax to a safe value)
 *   - Redirect a function to a native implementation (e.g., skip CRT init)
 *   - Intercept D3D/audio calls for custom rendering or sound
 */

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <stdlib.h>
#include <math.h>

/* The generated register model (g_eax/g_esp are thread-local there) and the
 * XBOX_PTR/MEM32 accessors; the register names below are its macros. */
#define RECOMP_GENERATED_CODE
#include "recomp_funcs.h"

/* ── ICALL trace ring buffer ───────────────────────────────── */

/*
 * These globals are written by the RECOMP_ICALL macro (defined in
 * recomp_types.h) every time an indirect call is dispatched. When a
 * crash occurs, the VEH handler or recomp_icall_fail_log() can dump
 * the last 16 call targets to help you trace what happened.
 *
 * The runtime owns them: xbox_kernel defines all three in
 * src/kernel/xbox_memory_layout.c, and recomp_types.h declares them extern.
 * Declare, do not define -- a definition here as well is a duplicate symbol,
 * and a project copied from this template failed to link on all three:
 *
 *   xbox_memory_layout.obj : error LNK2005: g_icall_count already defined
 *                            in recomp_manual.obj
 */
extern volatile uint32_t g_icall_trace[16];
extern volatile uint32_t g_icall_trace_idx;
extern volatile uint64_t g_icall_count;

typedef void (*recomp_func_t)(void);

/* ── Register state (defined in xbox_memory_layout.c) ──────── */

extern ptrdiff_t g_xbox_mem_offset;

/* ── NFSMW hooks ───────────────────────────────────────────
 *
 * Need for Speed: Most Wanted (Xbox NTSC-U, title 0x4541007B, XDK 5849,
 * NfsMWCollectorsRelease.exe = Black Edition). The libraries are the same
 * builds as Carbon's (stock D3D8, DSOUND flags 0x400B) and Burnout 3's
 * (DSOUND/CRT). Every address below was found in this XBE, not guessed:
 * tools/xdk_match.py (masked byte match against Burnout 3's XBE) for the
 * CRT and DSOUND bodies, tools/find_disp.py (the NV2A registers each D3D
 * handler touches) plus disassembly for D3D. See docs/research/nfsmw-re.md.
 *
 *   hook                 Burnout 3    Carbon       NFSMW
 *   memmove (x2)         0x2442A0     0x32C800     0x317170, 0x31A1C0
 *   D3D BlockOnTime      0x351770*    0x35A0C0     0x374020  (stdcall, ret 8)
 *   D3D device global    0x35FB48     0x368118     0x381DE8
 *   D3D vblank (ecx)     0x356DA0*    0x362050     0x37C6F0
 *   D3D PGRAPH (ecx)     0x357000     0x3625C0     0x37CC60
 *   DSOUND DSP post      0x2F7EC2     0x39406B     0x3B9139
 *   DSOUND EP watchdog   -            0x3947ED     0x3B98BB (from 0x3B9B9F)
 *   AC97 reset           0x2FE736     0x399A64     0x3BF8CC  (table 0x3C0518)
 *   DSOUND lock/unlock   0x2F3F70/92  0x391BB0/D2  0x3B65D0/0x3B65F2
 *   AC97 re-arm          0x2FE540     0x39986E     0x3BF6D6
 *   * = D3D8LTCG conventions (eax); NFSMW uses Carbon's (stdcall/thiscall).
 */

/* ── CRT memmove/memcpy (0x00317170, 0x0031A1C0) ─────────────
 *
 * Two copies of the MSVC CRT memmove, as in Carbon. Their tail and
 * backward-copy paths dispatch through UnwindDown-style vectors indexed with
 * a negative register, which the lifter cannot follow: every copy whose
 * length was not a multiple of 4 silently lost its tail. Replaced by the host
 * memmove. cdecl (dst, src, count), returns dst. */
static void crt_memmove(void)
{
    uint32_t dst = MEM32(esp + 4);
    uint32_t src = MEM32(esp + 8);
    uint32_t n   = MEM32(esp + 12);

    if (n)
        memmove((void *)XBOX_PTR(dst), (const void *)XBOX_PTR(src), n);
    eax = dst;
    esp += 4;   /* return address; cdecl, the caller pops the arguments */
}

void sub_00317170(void) { crt_memmove(); }
void sub_0031A1C0(void) { crt_memmove(); }

/* ── D3D fence wait, D3D_BlockOnTime (0x00374020) ────────────
 *
 * stdcall (fence, flags), ret 8. Device through the global at 0x00381DE8:
 *   +0x2C  write sequence, +0x30  pointer to the GPU semaphore
 * and the wait sleeps on the event at device + 0x1DCC (0x0037416E:
 * KeWaitForSingleObject until the PGRAPH handler takes the NOP(5) trap).
 * With the pushbuffer executor the real semaphore and event are handed to
 * it once and the original wait runs against them. */
#define NFSMW_D3D_DEVICE_PTR  0x00381DE8u
#define NFSMW_D3D_FENCE_EVENT 0x1DCCu
extern void nv2a_pb_set_semaphore_target(uint32_t guest_va);
extern void xbox_SetGpuFenceEvent(uint32_t guest_va);
extern void sub_00374020_gen(void);

void sub_00374020(void)
{
    static int exec = -1;
    uint32_t dev = MEM32(NFSMW_D3D_DEVICE_PTR);
    uint32_t sem = dev ? MEM32(dev + 0x30) : 0;

    if (exec < 0)
        exec = getenv("RECOMP_PB_EXEC") != NULL;
    if (exec) {
        static uint32_t registered;
        if (sem && sem != registered) {
            nv2a_pb_set_semaphore_target(sem);
            xbox_SetGpuFenceEvent(dev + NFSMW_D3D_FENCE_EVENT);
            fprintf(stderr, "[D3D] GPU semaphore at 0x%08X, fence event 0x%08X\n",
                    sem, dev + NFSMW_D3D_FENCE_EVENT);
            registered = sem;
        }
        sub_00374020_gen();
        return;
    }
    if (sem)
        MEM32(sem) = MEM32(dev + 0x2C);     /* "GPU" is always caught up */
    eax = 0;
    esp += 4 + 8;                           /* return address, 2 stdcall args */
}

/* ── D3D interrupt handlers at DISPATCH_LEVEL ────────────────
 *
 * The vblank handler (0x0037C6F0) and the PGRAPH handler (0x0037CC60) run
 * both from D3D's DPC (kernel timer thread here) and from its busy-waits on
 * the game thread. On the Xbox they never interleave; here they would, and a
 * flip whose target vblank was passed in between stays pending forever
 * (PersistDisplay hangs: NFSU2/Carbon's race-start hang). Both run at
 * DISPATCH_LEVEL. Same code as Carbon's, see examples/ and nfsc-sw. */
extern uint8_t xbox_KfRaiseIrql(uint8_t irql);
extern void xbox_KfLowerIrql(uint8_t irql);
extern uint8_t xbox_CurrentIrql(void);
extern int xbox_gil_suspend(void);
extern void xbox_gil_resume(int depth);
extern void xbox_Nv2aVblankTaken(void);
extern void nv2a_pb_flip_retired(unsigned n);

static uint8_t d3d_isr_enter(void)
{
    uint8_t old = xbox_CurrentIrql();

    if (old < 2) {
        int d = xbox_gil_suspend();
        old = xbox_KfRaiseIrql(2);
        xbox_gil_resume(d);
    }
    return old;
}

static void d3d_isr_leave(uint8_t old)
{
    if (old < 2)
        xbox_KfLowerIrql(old);
}

/* Vblank handler, thiscall (ecx = block; +0x1BC retire index, +0x1C0
 * vblank count). */
extern void sub_0037C6F0_gen(void);

void sub_0037C6F0(void)
{
    uint32_t blk = ecx;
    uint8_t old = d3d_isr_enter();
    uint32_t head = MEM32(blk + 0x1BC);

    xbox_Nv2aVblankTaken();
    sub_0037C6F0_gen();
    nv2a_pb_flip_retired(MEM32(blk + 0x1BC) - head);
    d3d_isr_leave(old);
}

/* PGRAPH handler, thiscall. Takes the software-method traps (NOP(5) fence
 * event, flips) under the executor's trap lock. The game asks for a
 * 2-vblank presentation interval in races (30 fps); the flip NOP trap
 * carries it as (bits << 5) | 1 and it is rewritten to 1 vblank here, as in
 * Carbon/NFSU2 v0.5.9. NFSMW_FPS_CAP=30 keeps the title's interval. */
extern void sub_0037CC60_gen(void);
extern void nv2a_pb_trap_lock(void);
extern void nv2a_pb_trap_unlock(void);
extern void nv2a_pb_trap_taken(void);

static int fps_cap_30(void)
{
    static int v = -1;
    if (v < 0) {
        const char *e = getenv("NFSMW_FPS_CAP");
        v = e && atoi(e) == 30;
        fprintf(stderr, "[fps] cap: %s\n", v ? "30 (title)" : "60 (NFSMW_FPS_CAP=30 for 30)");
    }
    return v;
}

void sub_0037CC60(void)
{
    volatile uint32_t *nv = (volatile uint32_t *)XBOX_PTR(0xFD000000u);
    uint32_t blk = ecx;
    uint8_t old = d3d_isr_enter();
    uint32_t head = MEM32(blk + 0x1BC);
    uint32_t nsource;

    nv2a_pb_trap_lock();
    nsource = nv[0x400108 / 4];
    if (nsource && (nv[0x400704 / 4] & 0xFFFFu) == 0x0100u && !fps_cap_30()) {
        uint32_t data = nv[0x400708 / 4];
        if ((data & 0x1Fu) == 1u && ((data >> 5) & 7u) > 1u) {
            static int told;
            nv[0x400708 / 4] = (data & ~(7u << 5)) | (1u << 5);
            if (!told++)
                fprintf(stderr, "[fps] flip interval %u -> 1 vblank (uncapped)\n", (data >> 5) & 7u);
        }
    }
    sub_0037CC60_gen();
    nv2a_pb_flip_retired(MEM32(blk + 0x1BC) - head);
    if (nsource)
        nv2a_pb_trap_taken();
    nv2a_pb_trap_unlock();
    d3d_isr_leave(old);
}

/* ── DirectSound DSP command post (0x003B9139) ───────────────
 * Spins until the GP DSP zeroes the command word at scratch + 0x810; there
 * is no DSP, so the word is registered with the runtime's ack list. */
extern int xbox_ApuDspAckWord(uint32_t va);
extern void sub_003B9139_gen(void);

void sub_003B9139(void)
{
    uint32_t obj = MEM32(ecx + 8);
    uint32_t blk = obj ? MEM32(obj + 0x10) : 0;
    uint32_t scratch = blk ? MEM32(blk) : 0;

    if (scratch)
        xbox_ApuDspAckWord(scratch + 0x810);
    sub_003B9139_gen();
}

/* ── DirectSound EP watchdog reset (0x003B98BB) ──────────────
 * sub_003B9AFB ends by checking the EP DSP heartbeat (*[0x003C0B70] =
 * 0xFE85A018 must read 0xCCCCCC) and otherwise resets the APU. The APU
 * model reads DSP memory as 0, so it would reset every frame (Carbon: the
 * intro froze). Only the watchdog's call (return 0x003B9B9F) is skipped. */
extern void sub_003B98BB_gen(void);

void sub_003B98BB(void)
{
    if (MEM32(esp) == 0x003B9B9Fu) {
        esp += 4;
        return;
    }
    sub_003B98BB_gen();
}

/* ── DirectSound AC'97 channel reset (0x003BF8CC) ────────────
 * Sets RR in the NABM control byte and spins on a hoisted copy of it
 * (never re-read). Same function with the reset completing at once. */
extern void sub_003B65D0(void);
extern void sub_003B65F2(void);
extern void sub_003BF6D6(void);

void sub_003BF8CC(void)
{
    uint32_t ebp_saved = g_ebp, frame, chan, nabm;

    PUSH32(esp, ebp_saved);
    frame = esp;
    g_ebp = frame;
    g_seh_ebp = frame;
    esp -= 8;
    MEM32(frame - 4) = 0;
    PUSH32(esp, esi);
    esi = ecx;
    chan = esi;

    ecx = frame - 8;
    PUSH32(esp, 0x003BF8E0u); RECOMP_ABI_CALL(0x003B65D0u, sub_003B65D0);

    nabm = MEM32(MEM32(chan) * 4 + 0x003C0518u);
    MEM8(nabm + 0xFEC0010Bu) = 2;
    MEM8(nabm + 0xFEC0010Bu) &= (uint8_t)~2u;   /* reset complete */
    eax = nabm;
    SET_LO8(ecx, 0);

    MEM32(nabm + 0xFEC00100u) = MEM32(chan + 0x1C);
    if (MEM32(chan) == 1)
        MEM32(0xFEC0017Cu) = MEM32(chan + 0x28);

    PUSH32(esp, 1);
    PUSH32(esp, 1);
    ecx = chan;
    PUSH32(esp, MEM8(chan + 0x24));
    PUSH32(esp, MEM8(chan + 0x25));
    eax = MEM8(chan + 0x25);
    g_ebp = frame;
    g_seh_ebp = frame;
    PUSH32(esp, 0x003BF933u); RECOMP_ABI_CALL(0x003BF6D6u, sub_003BF6D6);

    ecx = frame - 8;
    g_ebp = frame;
    g_seh_ebp = frame;
    PUSH32(esp, 0x003BF93Bu); RECOMP_ABI_CALL(0x003B65F2u, sub_003B65F2);

    POP32(esp, esi);
    esp = frame;
    POP32(esp, ebp_saved);
    g_ebp = ebp_saved;
    esp += 4;
}

/* ── XInputGetState (0x003C2094) diagnostic ──────────────────
 * stdcall (handle, PXINPUT_STATE), ret 8. Copies the last report from the
 * device object (+0x14) into the caller's state; returns 0 or 0x48F
 * (DEVICE_NOT_CONNECTED). NFSMW_XINPUT_DIAG=1 logs each change of
 * (result, buttons) and who asked, so "pad pressed, game does nothing"
 * splits into "game never calls it" / "gets 0x48F" / "gets the press". */
extern void sub_003C2094_gen(void);

void sub_003C2094(void)
{
    static int on = -1;
    uint32_t ret = MEM32(esp), h = MEM32(esp + 4), st = MEM32(esp + 8);

    if (on < 0) {
        const char *e = getenv("NFSMW_XINPUT_DIAG");
        on = e && *e == '1';
    }
    sub_003C2094_gen();
    if (on) {
        static uint32_t last_rc = 0xFFFFFFFFu, last_b = 0xFFFFFFFFu, n;
        uint32_t b = st ? (MEM16(st + 4) | ((uint32_t)MEM8(st + 6) << 16)) : 0;
        n++;
        if (eax != last_rc || b != last_b || (n % 600) == 0) {
            fprintf(stderr, "[xinput] #%u from %08X h=%08X rc=%X pkt=%u buttons=%04X a=%02X\n",
                    n, ret, h, eax, st ? MEM32(st) : 0, st ? MEM16(st + 4) : 0,
                    st ? MEM8(st + 6) : 0);
            last_rc = eax;
            last_b = b;
        }
    }
}

/* ── Picture options (nfsmw_options.txt) ─────────────────────
 * Written by the launcher (Graphics card): scale=100/150/200/250, fxaa=0/1,
 * aniso=1..16. Loaded at boot into the Vulkan renderer. In-game rows (as
 * Carbon's Options -> Gameplay) are not wired yet. Without scale= the
 * renderer keeps RECOMP_GL_SCALE. */
#ifdef NFSU2_VULKAN
extern volatile int nv2a_vk_scale_pct;
extern volatile int nv2a_vk_fxaa;
extern volatile int nv2a_vk_aniso;
#endif

void game_options_load(void)
{
    char line[128];
    FILE *f = fopen("nfsmw_options.txt", "r");
    int scale = 0, fxaa = 0, aniso = 4;

    if (f) {
        while (fgets(line, sizeof line, f)) {
            if (!strncmp(line, "fxaa=", 5))
                fxaa = line[5] != '0';
            else if (!strncmp(line, "aniso=", 6))
                aniso = atoi(line + 6);
            else if (!strncmp(line, "scale=", 6))
                scale = atoi(line + 6);
        }
        fclose(f);
    }
    if (aniso < 1) aniso = 1;
    if (aniso > 16) aniso = 16;
#ifdef NFSU2_VULKAN
    if (scale >= 50 && scale <= 400)
        nv2a_vk_scale_pct = scale;
    nv2a_vk_fxaa = fxaa;
    nv2a_vk_aniso = aniso;
#endif
    fprintf(stderr, "[options] %s: scale %d%%, fxaa %s, anisotropic %dx\n",
            f ? "nfsmw_options.txt" : "defaults", scale ? scale : 100,
            fxaa ? "on" : "off", aniso);
}

/* ── Manual function overrides ─────────────────────────────── */

/*
 * Return a function pointer to override the given Xbox VA, or NULL
 * to fall through to the auto-generated dispatch table.
 *
 * This is called on every indirect call (RECOMP_ICALL) and every
 * direct call through the dispatch table, so keep it fast. A chain
 * of if-statements on uint32_t compiles to a simple comparison
 * sequence; for large override tables, consider a sorted array
 * with binary search.
 *
 * Examples of common override patterns:
 *
 *   // Trace wrapper: log entry/exit around the generated function
 *   extern void sub_00012345(void);
 *   static void traced_sub_00012345(void) {
 *       fprintf(stderr, "[TRACE] sub_00012345 entered, eax=0x%08X\n", g_eax);
 *       sub_00012345();
 *       fprintf(stderr, "[TRACE] sub_00012345 returned, eax=0x%08X\n", g_eax);
 *   }
 *
 *   // Stub: skip a function entirely (return 0 in eax)
 *   static void stub_00067890(void) {
 *       g_eax = 0;
 *   }
 *
 *   // Fix: replace a broken lifted function with correct C
 *   static void fixed_sub_000ABCDE(void) {
 *       // Read arguments from stack/registers per calling convention
 *       uint32_t arg1 = g_ecx;
 *       uint32_t arg2 = MEM32(g_esp + 4);
 *       // ... correct implementation ...
 *       g_eax = result;
 *   }
 */
recomp_func_t recomp_lookup_manual(uint32_t xbox_va)
{
    /*
     * TODO: Add your overrides here. Examples:
     *
     * if (xbox_va == 0x00012345) return traced_sub_00012345;
     * if (xbox_va == 0x00067890) return stub_00067890;
     * if (xbox_va == 0x000ABCDE) return fixed_sub_000ABCDE;
     */

    (void)xbox_va;
    return (recomp_func_t)0;
}

/* ── ICALL failure logging ─────────────────────────────────── */

/*
 * Called when RECOMP_ICALL cannot resolve a target address.
 * This usually means one of:
 *   - A vtable dispatch to an address not in the dispatch table
 *   - A function pointer loaded from uninitialized or corrupt memory
 *   - A kernel thunk address that the bridge doesn't handle
 *
 * During early bring-up you will see many of these. Most are harmless
 * (the ICALL macro pops the dummy return address and continues).
 * Focus on the ones that cause crashes or incorrect behavior.
 */
void recomp_icall_fail_log(uint32_t va)
{
    fprintf(stderr, "[ICALL] Failed to resolve VA 0x%08X (total calls: %llu)\n",
            va, (unsigned long long)g_icall_count);

    /* Dump last 16 call targets from the ring buffer */
    fprintf(stderr, "  Recent ICALL targets:\n");
    for (int i = 0; i < 16; i++) {
        int idx = (g_icall_trace_idx - 16 + i) & 15;
        if (g_icall_trace[idx])
            fprintf(stderr, "    [%2d] 0x%08X\n", i, g_icall_trace[idx]);
    }
    fflush(stderr);
}

/* An indirect call whose target is not code: a null or wild function pointer.
 *
 * Skipping these is right -- calling a data address is worse -- but skipping
 * them *silently* is not. They almost always arrive inside a loop, so the
 * symptom is a hang with no output rather than a diagnosable null vtable call.
 *
 * Rate-limited per address: a spin can produce millions of these, and the
 * useful information is which addresses occur, not how often.
 */
void recomp_icall_not_code_log(uint32_t va)
{
    enum { SLOTS = 16 };
    static uint32_t seen[SLOTS];
    static uint64_t hits[SLOTS];
    static int count;
    int i;

    for (i = 0; i < count; i++)
        if (seen[i] == va)
            break;
    if (i == count) {
        if (count == SLOTS)
            return;
        seen[count] = va;
        hits[count] = 0;
        count++;
    }
    hits[i]++;
    /* Report at 1, 10, 100, 1000 ... rather than once. A single line says a
     * wild pointer was skipped; the progression says it is being skipped in a
     * loop, which is the difference between a curiosity and the reason the
     * title is hung. */
    {
        uint64_t n = hits[i];
        while (n >= 10 && n % 10 == 0)
            n /= 10;
        if (n != 1)
            return;
    }
    fprintf(stderr, "[ICALL] target 0x%08X is not code -- skipped %llu time(s) "
                    "(null or wild function pointer, at call #%llu)\n",
            va, (unsigned long long)hits[i],
            (unsigned long long)g_icall_count);
    fflush(stderr);
}

/* ── Untranslated instructions ───────────────────────────────────────────
 *
 * The lifter emits RECOMP_UNIMPL(text, va) at every instruction it has no
 * translation for, in place of the bare comment it used to leave. The
 * instruction is still a no-op; this only stops the omission being silent.
 * RECOMP_UNIMPL_TRAP=1 aborts at the first hit, at the guest address of the
 * cause rather than wherever the damage surfaces. */
#include <stdlib.h>

void recomp_unimpl(const char *text, uint32_t va)
{
    static int printed;
    const char *trap = getenv("RECOMP_UNIMPL_TRAP");
    int stop = trap && *trap && *trap != '0';

    if (printed < 50 || stop) {
        printed++;
        fprintf(stderr,
                "[UNIMPL] untranslated instruction REACHED: `%s` at 0x%08X"
                " (a no-op; set RECOMP_UNIMPL_TRAP=1 to stop here)\n",
                text, va);
        fflush(stderr);
    }
    if (stop) abort();
}
