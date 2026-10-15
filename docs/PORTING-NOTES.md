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
