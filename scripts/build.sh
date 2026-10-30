#!/usr/bin/env bash
# The Linux build of the Android APK (build.bat's on Windows):
#   1. the recompiler (tools/CMakeLists.txt) -> out/build/linux-tools/{xbe_recomp,xbe_symbols}
#   2. generated/game from assets/default.xbe (taken out of assets/*.iso or iso/*.iso first when missing)
#   3. libmain.so and libSDL3.so with the NDK's CMake toolchain -> out/build/android
#   4. the APK with Gradle, packaging those libraries -> app/build/outputs/apk/<variant>/
#
#   scripts/build.sh [--release] [--reuse-generated] [--xbe <default.xbe>] [--skip-apk]
#     --release           assembleRelease (debug key unless BLACK_KEYSTORE is set), else assembleDebug
#     --reuse-generated   keep generated/game when it is there (the recompile is skipped)
#     --xbe FILE          the xbe to recompile, copied to assets/default.xbe
#     --skip-apk          stop after libmain.so
# Environment: ANDROID_HOME (default ~/Documents/Repos/Android/SDK), JOBS (nproc), GRADLE_ARGS (more for Gradle).
# The native code is always Release: the recompiled code is far too slow unoptimized. The first build compiles 128
# partitions of recompiled code (10 to 90 minutes).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-$HOME/Documents/Repos/Android/SDK}}"
export ANDROID_HOME
NDK_VERSION=28.2.13676358
NDK="$ANDROID_HOME/ndk/$NDK_VERSION"
JOBS="${JOBS:-$(nproc)}"
TOOLS="$ROOT/out/build/linux-tools"
NATIVE="$ROOT/out/build/android"
LIBS="$ROOT/out/build/android-libs/arm64-v8a"
SHA1=f636a16af04171089f879a4bed646e185c734233

VARIANT=debug REUSE=0 XBE_IN="" SKIP_APK=0
while [ $# -gt 0 ]; do
    case "$1" in
        --release) VARIANT=release ;;
        --reuse-generated) REUSE=1 ;;
        --xbe) XBE_IN="$2"; shift ;;
        --skip-apk) SKIP_APK=1 ;;
        -h|--help) sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown option $1" >&2; exit 2 ;;
    esac
    shift
done

say() { printf '\n[%s] %s\n' "$1" "$2"; }
die() { echo "error: $*" >&2; exit 1; }

say 1/5 "Tools"
for t in cmake ninja clang clang++ git java; do command -v "$t" >/dev/null || die "$t is missing"; done
[ -f "$NDK/build/cmake/android.toolchain.cmake" ] || die "no NDK $NDK_VERSION in $ANDROID_HOME/ndk"
[ -d "$ANDROID_HOME/platforms" ] || die "no Android platforms in $ANDROID_HOME"

say 2/5 "default.xbe"
mkdir -p assets
if [ -n "$XBE_IN" ]; then cp "$XBE_IN" assets/default.xbe; fi
if [ ! -f assets/default.xbe ]; then
    iso=$(ls assets/*.iso iso/*.iso 2>/dev/null | head -n1 || true)
    [ -n "$iso" ] || die "put default.xbe (or the disc image) in assets/, or pass --xbe"
    command -v python3 >/dev/null || die "python3 is needed to take default.xbe out of $iso"
    python3 -I tools/xdvdfs.py "$iso" default.xbe assets/default.xbe
fi
sha=$(sha1sum assets/default.xbe | cut -d' ' -f1)
[ "$sha" = "$SHA1" ] || die "assets/default.xbe is not the supported image (sha1 $sha)"

say 3/5 "Recompiler and generated/game"
if [ "$REUSE" = 1 ] && [ -f generated/game/x86_func_mapping.cpp ]; then
    echo "      generated/game kept (--reuse-generated)"
else
    cmake -S tools -B "$TOOLS" -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++ >/dev/null
    cmake --build "$TOOLS" -j "$JOBS" >/dev/null
    SDK="$(head -n1 "$TOOLS/sdk_dir.txt")"
    grep -q '^\[symbols\]' config/game.toml || "$TOOLS/xbe_symbols" assets/default.xbe "$SDK/xdk" --out config/game.toml
    "$TOOLS/xbe_recomp" config/game.toml --xdk "$SDK/xdk" >/dev/null
fi

say 4/5 "libmain.so (NDK $NDK_VERSION, arm64-v8a)"
# SDL3's checkout, which CMake builds and whose Java side goes into the APK (app/build.gradle clones it too)
[ -f third_party/SDL/CMakeLists.txt ] ||
    git clone -q --depth 1 --branch release-3.4.16 https://github.com/libsdl-org/SDL.git third_party/SDL
cmake -S . -B "$NATIVE" -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-30 -DANDROID_STL=c++_shared -DXBE_IMAGE="$ROOT/assets/default.xbe" >/dev/null
cmake --build "$NATIVE" --target main -j "$JOBS"
mkdir -p "$LIBS"
STRIP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
"$STRIP" --strip-unneeded -o "$LIBS/libmain.so" "$NATIVE/libmain.so"
"$STRIP" --strip-unneeded -o "$LIBS/libSDL3.so" "$NATIVE/SDL/libSDL3.so"
cp "$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "$LIBS/"
echo "      $LIBS: $(ls "$LIBS" | tr '\n' ' ')"
[ "$SKIP_APK" = 1 ] && exit 0

say 5/5 "APK ($VARIANT)"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
task=assemble${VARIANT^}
apk="app/build/outputs/apk/$VARIANT/app-$VARIANT.apk"
# packaged afresh: Gradle's incremental packaging appends a changed libmain.so to the old APK, which then doubles
rm -f "$apk"
# shellcheck disable=SC2086
./gradlew --no-daemon "$task" -PnativeLibs="$(dirname "$LIBS")" ${GRADLE_ARGS:-}
[ -f "$apk" ] || die "no $apk"
echo
echo "  $apk ($(du -h "$apk" | cut -f1))"
echo "  install: adb install -r $apk"
