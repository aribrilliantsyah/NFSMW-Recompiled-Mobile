#!/usr/bin/env bash
# Linux headless run driven live: tools/livetest.sh <outdir> <seconds>
# Append steps to <outdir>/pad.txt while it runs ("start:600", "a:400",
# "down:200", "clear"); frames go to <outdir>/d/ every 120 frames.
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$1"; SECS="${2:-300}"
rm -rf "$OUT"; mkdir -p "$OUT/d"; : > "$OUT/pad.txt"
cd "$OUT"
NFSU2_GAME_DIR="$HERE/roms/game" RECOMP_VK_HEADLESS=1 RECOMP_FPS_LOG=1 \
RECOMP_PAD_DIAG=1 RECOMP_GL_DUMP="$OUT/d/f,120" RECOMP_PAD_LIVE="$OUT/pad.txt" \
timeout -s KILL "$SECS" "$HERE/build/linux-vk/b3_recomp" > run.log 2>&1
echo "exit $?"
