# Porting notes (NFSMW)

Working notes for this port. CLAUDE.md is still the Burnout 3 one (copied
from burnout3-sw; Burnout's findings mostly apply to the runtime here too).

## Origin

- Shell, renderer, Vulkan 1.1 fallback for Mali-G68 r32 / Adreno 610:
  burnout3-sw tag v0.3-mali-adreno-ok, unchanged.
- Hook layout: antoxa2584x/nfsc-sw (Carbon = MW engine, same stock D3D8).
- Look (icon, olive #6F7432, touch roles): victorgbd/NFSMW-Recompiled-Mobile
  (Xbox 360 port). Clones in `.work/ref/`.
- Burnout 3's hooks and CLAUDE.md: `examples/burnout3/`.

## Rules

- Never commit `roms/` or `.work/gen/`. Commit every change right away.
- Touch layout mechanics stay identical to b3x; only labels and which face
  button is big in Drive change (B = NOS).

## Layout

| Where | What |
|---|---|
| `.work/venv`, `.work/android-deps` | symlinks to burnout3-sw's |
| `scripts/android/nfsmw.sh` | build/install/run/log (was b3x.sh) |
| `tools/regen.sh` | lift (`LIFT_ONLY=1` after editing recomp_manual.c) |
| `tools/xdk_match.py`, `tools/find_disp.py` | hook finding, see docs/research/nfsmw-re.md |

## Findings

- Entry 0x2B3916; 28,397 functions after disasm (4,194 thiscall).
- Hooks: see the header of src/recomp_manual.c.

## Next

Linux Vulkan build and first run (ICALL/STUB triage, seeds), then the
phones (the user tests on device).

## START did nothing at the title (fixed 2026-10-15)

The pad worked end to end: USB reports reached the game and XInputGetState
(0x3C2094, `NFSMW_XINPUT_DIAG=1`) returned buttons 0x0010 with rc 0. The
title never acted because sub_002BF64E, the input event queue's get-next,
had been lifted as an empty stub (`recomp_stubs_unresolved.c`): nothing
reached through a tail jump only. Seeding it (config/seed_functions.json)
fixed START on Linux and on the phone. Check `recomp_stubs_unresolved.c`
for stubs near any code path that "reads input but ignores it".

## Uneven race frame rate on the Mali-G68 (2026-10-17)

Found from the Mali race log of the 43.2 fps benchmark
(`.work/bench/mali/race.log`, 90 s, no phone attached since):

| What the log shows | Cause | Fix |
|---|---|---|
| 50,000 of 57,712 lines are `[NV2A] DMA_PUT` / `PCRTC_START`, ~800 a second, from the executor's thread (tid 31820 writes every line) | `s_nv2a_trace` was armed by `RECOMP_PB_EXEC` too, so the per-kickoff trace ran in every game. Each line is a write into the logcat pipe; the executor (which records every Vulkan draw) blocks when the log thread falls behind | the lines and the framebuffer checksum need `RECOMP_NV2A_TRACE` (xbox_memory_layout.c `s_nv2a_verbose`) |
| `[KERNEL] summary` every 2 s, `[READ]` per file read, `[GPU]` reports | `RECOMP_QUIET` was Switch-only | Android sets `RECOMP_QUIET=1` too (main.c); `RECOMP_QUIET=0` in nfsmw_env.txt brings them back |
| fps 55 -> 35 -> 55 with `[hitch]` frames of 50-110 ms and *0 programs, 0 textures* | threads not pinned (`xbox_guest_pin` is a no-op without `RECOMP_GUEST_ONE_CORE`): the guest thread and the executor land on the A55 cluster; schedutil raises clocks only after a busy stretch | guest + executor kept on the fastest cluster (`xbox_fast_cores_pin`, cpuinfo_max_freq; MT6877 = cpu6-7 A78), `RECOMP_BIG_CORES=0` off; ADPF performance hint session (android_main.c) reports each frame against 16.7 ms, `NFSMW_PERF_HINT=0` off |
| game and executor take turns | the frame function ends with BlockOnFence on its own fence (below) | frame lag on by default |
| ~16 render passes a race frame | each began with an ALL_COMMANDS -> ALL_COMMANDS barrier: on a tiler every pass's binning waited for the previous pass's last fragment | `barrier_pass()`: fragment/attachment/transfer stages only (no vertex program samples a texture); `RECOMP_VK_BARRIER=all` restores the old one |

### Frame function (RE, lifted C in `.work/gen/recomp_0019.c`)

sub_000D8720 is NFSMW's per-frame render (called from the main loop). Its
end:

```
0x0D896F  call 0x374610   ; KickOff ([0x381DE8] -> sub_00373E40)
0x0D8974  call 0x377A70   ; returns 0
0x0D8979  call 0x374790   ; InsertFence -> eax
...
0x0D8B4F  push eax
          call 0x374600   ; BlockOnFence(fence)  -> BlockOnTime(fence, 4)
0x0D8B55  push ebx
          call 0x3768B0   ; Swap(0)
```

BlockOnFence (0x374600) is `push 4; push [esp+4]; call 0x374020; ret 4`,
so in the BlockOnTime hook (0x374020) the return address is 0x37460C and
the frame's return address sits at esp+12 = 0x0D8B55. The hook swaps the
fence for the previous frame's (`NFSMW_FRAME_LAG=0` keeps the title's),
exactly as Carbon's sub_0010B240 / NFSU2's 0x000AEDDB. The other two
BlockOnFence callers (0x0C8D7E, 0x0C8DF8) are the loading/movie screens and
are left alone.

Not measured on the phone yet (no device attached): run
`scripts/android/race-bench.sh` against the 43.2 fps / p10 33.4 baseline.

## Drive layout: Xbox face order (2026-10-17)

The Drive diamond had B (NOS) at the bottom and A (E-BRAKE) on the right:
swapped against the Xbox pad. Now A bottom, B right (big), X left, Y top;
`LAYOUT_VERSION` 4 clears saved positions. race-bench.sh's A tap moved
with it (W-225, 616 on a 1080-high panel).

## Races never finished: AllHumanPlayersDone unresolved (fixed 2026-10-17)

User report: at the finish line the race does not end, the car keeps
driving round. Phone log (`nfsmw_log.txt`, 10:01 session): one
`[ICALL] Failed to resolve VA 0x0018D100` during the race. 0x18D100 is
pushed in 0x199DFA together with the string 0x3DCD08
`"AllHumanPlayersDone"`: the race script's natives table (registered via
sub_00196080, next to AllRacersDone 0x187D80 and IsLANGame 0x188080). Its
body starts straight after the `ret` of sub_0018D090 with no padding, so
disasm never split it off and no function existed for the dispatch. It is
the check that ends the race once every human crossed the line; failing,
the race goes on. Seeded (config/seed_functions.json), full regen. A scan
of all 27 natives pushed to 0x196080/0x1966C0 found no other missing one.

## START after minutes in a race / minimap gone (open, 2026-10-17)

Reported on the phone with the build that had frame lag on by default and
the guest/DPC/ISR threads all pinned to the two big cores. Not reproduced
on Linux: an 8-minute Quick Race (frame lag on, START every minute) opened
the pause menu every time and the minimap stayed. The phone log of that
session shows no error near it. As precautions the frame lag is opt-in
again (`NFSMW_FRAME_LAG=1`), the narrow pass barrier is opt-in
(`RECOMP_VK_BARRIER=pass`), and only the frame-loop thread and the
executor are pinned. Next phone report decides which of them it was.
