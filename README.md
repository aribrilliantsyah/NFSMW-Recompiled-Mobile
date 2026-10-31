# Need for Speed: Most Wanted for Android
An unofficial native Android port of the original Xbox version of *Need for Speed: Most Wanted* (Black Edition), with touch controls, controller and keyboard support and extended graphics options.

The game's x86 code is statically recompiled ahead of time to C++ with [XboxXBE-Recomp](https://github.com/TGP482/XboxXBE-Recomp), then built for arm64. Drawing goes through Vulkan, and windowing, input and audio through SDL3. No emulator is involved. The engine (Vulkan renderer, Direct3D 8 and DirectSound on the host, the game's patches) comes from [MostWanted-Android](https://github.com/RanggaCasper/MostWanted-Android) by RanggaCasper; the Android shell (launcher, ISO installer, touch controls and their editors, controller mapping) from nfsw-recompiled-mobile.

> [!NOTE]
> This project does not include any game assets. You must provide the files from your own legally acquired copy of the game to install or build.

> [!WARNING]
> Work in progress. See [Status](#status).

## Gameplay

<p align="center">
  <a href="https://youtu.be/x5bczwTuyhw">
    <img src="https://img.youtube.com/vi/0ILO1_gulrg/maxresdefault.jpg" alt="Black Android gameplay on POCO F6" width="720">
  </a>
  <br>
  <sub>▶ Gameplay on Xiaomi Pad 7</sub>
</p>

## Installation
1. Install the APK.
2. Open **NFS Most Wanted** and tap **INSTALL FROM ISO…**: pick the Xbox disc image (`.iso`). It is extracted once into `Android/data/com.ryuna.nfsmw2005x/files/game` (about 2.9 GB); the `.iso` can be deleted afterwards. A folder extracted on a PC (`default.xbe` with the disc's `NFS` folder beside it) works too: **Choose folder…** (asks for all files access).
3. Tap **PLAY**.

The launcher writes the engine's settings (`mostwanted.toml`) and the HUD layout (`layout.toml`) in `Android/data/com.ryuna.nfsmw2005x/files` at each PLAY; the shader cache and the logs (`logs`) are there too. Saves are slots in the game folder (`game/saves/<slot>`, `Main` by default), managed in the launcher's SAVES card: back them up to a .zip before uninstalling, as Android removes `Android/data` with the app.

The supported disc is the USA Black Edition (`default.xbe` SHA-1 `f636a16af04171089f879a4bed646e185c734233`).

## Features
Every setting is in the launcher. In the game, the ☰ button (it shows with HIDE/TOUCH after a touch) resumes, opens the touch layout editor or goes back to the launcher. Android's back button is the controller's Start in a race (the pause menu) and B in the menus.

### Graphics
- **Widescreen**: on, the whole screen, the 3D view widened to the phone's shape (Hor+) and the menus and HUD laid out for it; off, the Xbox's 4:3 picture with side bars.
- **Render Resolution**: 20% to 200% of the screen's height, instead of the original 640x480.
- **V-Sync**, **Max Frame Rate** (30 to 120, the display's refresh rate or unlimited) and an **FPS Counter** with CPU / GPU load and temperatures.
- **Anisotropic Filtering**, **Anti-Aliasing** (FXAA, SSAA 2x) and **Screen Filters**.
- **Yellow filter** (the game's colour grading, off by default), car and road reflections, motion blur, bloom, fog, shadows, particles, rain and screen raindrops, shader preloading.

### Touch Controls
Two layouts, **Full** (the whole Xbox controller) and **Drive** (steering by stick, arrows or tilting the phone, gas and brake pedals, nitrous, e-brake, speedbreaker), each edited in the launcher or in the game: move, resize and fade the controls. **Edit HUD position** moves the speedometer, the minimap, the race information and the EA Trax banner. In the game's menus a tap on the highlighted item is A and a swipe steps through the list.

### Other Input
- **Controller**: any controller SDL3 supports, with rumble (the phone vibrates without one), each Xbox button remappable in the launcher. The touch controls hide while a controller is in use.
- **Keyboard**.

### General
- **Language**: English, or Indonesian (the recompilation's text and the game's menus translated).
- **Audio Output**: automatic, stereo, 5.1 surround or mono.
- **Skip movies** and **cheats** (infinite nitrous and speedbreaker, max cash, no police, unlock all).
- **Background Pause**: the game and its sound pause while the app is in the background.

## Status
Tested on a Xiaomi Pad 7 (Snapdragon 7+ Gen 3): boot, the menus, the career intro and races play, about 60 FPS in a race.

- `xbe_symbols` finds 165 of the 238 XDK functions the host knows and all 12 data symbols; the others are not linked into this image. Eleven more were resolved by hand in `config/game.toml`.
- Most Wanted runs many of its vertex programs from the GPU's program memory (`LoadVertexShaderProgram` and `SelectVertexShaderDirect`), pushes its vertex constants inline, sets its pixel combiners with render states and gives its own frame and depth buffers; the host handles each.
- Visibility tests (the sun's glare) are not counted yet: the glare stays off.
- Known: a story film (after the first race) has been seen to stop on its first picture; a rare crash in the game's own code has been seen twice.

Logs are in `Android/data/com.ryuna.nfsmw2005x/files/logs`. Please include the log when reporting a problem.

## System Requirements
- **Android** 11 or newer, arm64.
- **GPU** with a Vulkan 1.1 driver, for example Adreno 6xx or newer, Mali-G7x or newer, or Xclipse.
- **Memory**: 4 GB.
- **Storage**: 4 GB for the game files and saves.

## Building
On Linux: `scripts/build.sh` (on Windows, `build.bat`).

1. Put `default.xbe` in `assets/` (or the disc image in `assets/` or `iso/`; `python3` extracts the xbe from it), or pass `--xbe <file>`.
2. Install CMake, Ninja, Clang, Java 17 to 21 and the Android SDK with Platform 36 and NDK `28.2.13676358` (`ANDROID_HOME`, default `~/Documents/Repos/Android/SDK`).
3. Run:
   ```
   scripts/build.sh                    # debug APK: app/build/outputs/apk/debug/app-debug.apk
   scripts/build.sh --release          # release APK, debug key unless BLACK_KEYSTORE is set
   scripts/build.sh --reuse-generated  # keep generated/game, skip the recompile
   ```
   It builds the recompiler, recompiles the xbe into `generated/game`, builds `libmain.so` with the NDK into `out/build/android` and packages the APK with Gradle. The first build compiles the whole game for arm64 (10 to 90 minutes).

Install the APK with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

The Linux host for benchmarking (`linux/`) builds from the same tree; its commands are at the top of `linux/CMakeLists.txt`.

The SDK is vendored in `sdk/`, at the upstream commit in `sdk/UPSTREAM_COMMIT`, with one change: the console's language comes from `app::ConsoleLanguage()` (`sdk/runtime/kernel/xboxkrnl.cpp`).

## Credits
- [XboxXBE-Recomp](https://github.com/TGP482/XboxXBE-Recomp) by TGP482: the static recompiler and the portable runtime (Xbox kernel, XDK libraries and Direct3D 8) this port is built on.
- [Black-Android](https://github.com/RanggaCasper/Black-Android) and [ShaolinMonks-Android](https://github.com/RanggaCasper/ShaolinMonks-Android) by RanggaCasper: the Android host this port is built from.
- Zukiru: the HDR screen filter.
- [bagasromadon](https://github.com/bagasromadon): Button Design.
- [SDL](https://github.com/libsdl-org/SDL), [glslang](https://github.com/KhronosGroup/glslang), [toml++](https://github.com/marzer/tomlplusplus) and [Dear ImGui](https://github.com/ocornut/imgui).
- [xemu](https://github.com/xemu-project/xemu) and [Cxbx-Reloaded](https://github.com/Cxbx-Reloaded/Cxbx-Reloaded), which XboxXBE-Recomp used as references for Xbox hardware and API behavior.

*Need for Speed* is a trademark of Electronic Arts Inc. This project is not affiliated with or endorsed by them.
