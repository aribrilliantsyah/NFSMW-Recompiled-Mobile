# Need for Speed: Most Wanted for Android
An unofficial native Android port of the original Xbox version of *Need for Speed: Most Wanted* (Black Edition), with touch controls, controller and keyboard support and extended graphics options.

The game's x86 code is statically recompiled ahead of time to C++ with [XboxXBE-Recomp](https://github.com/TGP482/XboxXBE-Recomp), then built for arm64. Drawing goes through Vulkan, and windowing, input and audio through SDL3. No emulator is involved. The engine (Vulkan renderer, Direct3D 8 and DirectSound on the host, the game's patches) comes from [MostWanted-Android](https://github.com/RanggaCasper/MostWanted-Android) by RanggaCasper; the Android shell (launcher, ISO installer, touch controls and their editors, controller mapping) from nfsw-recompiled-mobile.

> [!NOTE]
> This project does not include the game's data. You must provide the files from your own legally acquired copy of the game to install or build. The one exception is `ci/default.xbe.enc`, the game's executable encrypted for the release workflow, which only builds with the repository's secret key.

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

The settings (`mostwanted.toml`, shared by the launcher and the game) and the HUD layout (`layout.toml`, written at each PLAY) are in `Android/data/com.ryuna.nfsmw2005x/files`; the shader cache and the logs (`logs`) are there too. Saves: see [Saves](#saves).

The supported disc is the USA Black Edition (`default.xbe` SHA-1 `f636a16af04171089f879a4bed646e185c734233`).

## Features
Settings are in the launcher's tabs and in the game: **SETTINGS**, beside OPTIONS in the main menu and the pause menu, has the same categories with the same rows, names and order, and a change in one shows in the other. In the game, the ☰ button (it shows with HIDE/TOUCH after a touch) resumes, opens the touch layout editor or goes back to the launcher. Android's back button is the controller's Start in a race (the pause menu) and B in the menus. **Background Pause**: the game and its sound pause while the app is in the background.

## Controls
Launcher tab **CONTROLS**, and the game's CONTROLS category.
- **Control mode**: **MOBILE** (steering, gas and brake pedals, nitrous, e-brake, speedbreaker, a gear lever; in the game's menus, their buttons as icons) or **XBOX** (the whole Xbox controller).
- **Steering** (MOBILE): Stick, Arrows, Wheel (drag round it) or Tilt (turn the phone), with **Tilt sensitivity**.
- **Opacity** and **Size** of the current layout's buttons (Size scales every button over each one's own size from the editor), kept in `mostwanted.toml` (`touch_opacity_<mode>`, `touch_size_<mode>`) and shared by the launcher and the game.
- **Edit buttons**: move, resize and fade each control (also the ⚙ next to HIDE/TOUCH in the game). **Edit HUD**: moves the speedometer, the minimap, the race information and the EA Trax banner.
- In the game's menus a tap on the highlighted item is A and a swipe steps through the list; the keyboard takes taps.
- Launcher only: on-screen controller, hide with a controller, button feedback, vibration (with a test).
- **Controller**: any controller SDL3 supports, with rumble (the phone vibrates without one), each Xbox button remappable (**Controller mapping**). The touch controls hide while a controller is in use. **Keyboard** too.

## Settings
| Tab | Rows |
|---|---|
| SCREEN | Widescreen (Hor+ on the phone's shape; off, the Xbox's 4:3 with side bars), Aspect ratio, Render resolution (25% to 200% of the screen's height), Screen filter (Sharpen, Vivid, Scanlines, CRT, HDR), Yellow filter (the game's colour grading, off by default) |
| SPEED | Preset (Saver, Balanced, Max), FPS cap (30 to 360, the display's refresh rate or unlimited), V-Sync, FPS counter, Counter position, CPU/GPU usage, Temperatures, Shader preloading; in the launcher, Performance log |
| VISUALS | Bloom, Fog, Motion blur, Car reflections (full, every 2nd or 4th frame, off), Road reflections, Shadows, Particles, Falling rain, Screen raindrops, Anti-aliasing (FXAA, SSAA 2x), Anisotropic filtering |
| SYSTEM | Language (English, or Indonesian: the recompilation's text and the game's menus translated), Audio output (automatic, mono, stereo, 5.1), Skip movies, Active save slot, About |

A **Preset** sets the SPEED and VISUALS rows and the render resolution at once: **Saver** 50%, 30 fps, no reflections, motion blur or particles; **Balanced** 67%, 60 fps, the cars' reflections every 2nd frame; **Max** 100%, no cap, V-Sync, everything on, SSAA 2x and 16x anisotropic. A row changed afterwards shows the preset as Custom. A change in the launcher reaches the game at PLAY. In the game most rows apply at once; of the rows marked *(next start)*, Language takes effect at the next start, Audio output switches at once, and Shader preloading turned on starts preloading once during this run (turned off, it stops only at the next start).

## Cheats
Launcher tab **CHEATS** (orange), and the game's CHEATS category: infinite nitrous, infinite speedbreaker, no police (no new cops or pursuits; one already on goes on), unlock all, lock cash at max (9,999,999, kept in the save), and **Set cash** (0 to 9,999,999): set once when a career profile is loaded; in the launcher only while the game is not running, with Cancel. A save slot played with a cheat on is tagged **CHEATS ON** in SAVES.

## Saves
Saves are slots in the game folder, `game/saves/<slot>` (`Main` by default; under `Android/data/com.ryuna.nfsmw2005x/files` for the ISO install). The launcher's **SAVES** tab makes, selects, duplicates, renames and deletes slots, backs one up to a .zip and restores one from it; PLAY plays the selected slot, which the game's SYSTEM shows as Active save slot. Back up before uninstalling: Android removes `Android/data` with the app. Saves from builds before slots move into `Main` once.

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
