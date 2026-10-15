#!/usr/bin/env bash
# Drive NFSMW on a phone from the main menu into a Quick Play race, hold
# GAS, log fps. Touch coordinates are the factory Drive layout on a
# 2460x1080 panel (Infinix X6815D). Usage:
#   scripts/android/race-bench.sh <serial> <outdir> [seconds]
# Expects the game already at the main menu with "Career" highlighted.
set -u
S="$1"; OUT="$2"; SECS="${3:-90}"
mkdir -p "$OUT"
# Factory Drive layout positions; right-side buttons follow the panel width.
W=$(adb -s "$S" shell wm size </dev/null | grep -o '[0-9]*x[0-9]*' | tail -1 | awk -Fx '{print ($1>$2)?$1:$2}')
A="$((W-127)) 512"; RIGHT="330 486"; GAS="$((W-138)) 876"
tap() { adb -s "$S" shell "input swipe $1 $1 ${2:-300}" </dev/null; }
hold() { adb -s "$S" shell "input swipe $1 $2 $1 $2 ${3:-300}" </dev/null; }
pos() { set -- $1; echo "$1 $2"; }
r=($RIGHT); a=($A); g=($GAS)
# Career -> Challenge Series -> Quick Race (one step at a time)
for i in 1 2; do hold ${r[0]} ${r[1]} 120; sleep 1.5; done
hold ${a[0]} ${a[1]} 300; sleep 4        # Quick Race -> Quick Play
hold ${a[0]} ${a[1]} 300; sleep 8        # Quick Play -> brief
hold ${a[0]} ${a[1]} 300                 # start race
sleep 30                                 # load + countdown
adb -s "$S" logcat -c
adb -s "$S" shell "input swipe ${g[0]} ${g[1]} ${g[0]} ${g[1]} $((SECS*1000)) & \
  sleep $((SECS/3)); screencap -p /sdcard/rb1.png; sleep $((SECS/3)); screencap -p /sdcard/rb2.png; wait" </dev/null
adb -s "$S" logcat -d -s nfsmw:V > "$OUT/race.log"
adb -s "$S" pull /sdcard/rb1.png "$OUT/race1.png" >/dev/null </dev/null
adb -s "$S" pull /sdcard/rb2.png "$OUT/race2.png" >/dev/null </dev/null
grep -o "\[fps\] [0-9.]*$" "$OUT/race.log" | awk '{print $2}' > "$OUT/fps.txt"
echo "samples: $(wc -l < "$OUT/fps.txt")"
