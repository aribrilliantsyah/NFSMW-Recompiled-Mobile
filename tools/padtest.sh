#!/usr/bin/env bash
# Linux headless run with pad tracing: START pressed every 3 s from 20 s.
# Usage: tools/padtest.sh <outdir> [seconds]
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$1"; SECS="${2:-120}"
rm -rf "$OUT"; mkdir -p "$OUT/d"
S=""
for t in $(seq 20000 3000 $((SECS * 1000 - 5000))); do S="$S,$t:start:700"; done
S="${S#,}"
cd "$OUT"
NFSU2_GAME_DIR="$HERE/roms/game" RECOMP_VK_HEADLESS=1 RECOMP_QUIET=0 \
RECOMP_USB_TRACE=1 RECOMP_INPUT_DIAG=1 RECOMP_PAD_DIAG=1 RECOMP_GL_DUMP="$OUT/d/f,300" \
RECOMP_PAD_SCRIPT="$S" timeout -s KILL "$SECS" "$HERE/build/linux-vk/b3_recomp" > run.log 2>&1
echo "exit $?"
