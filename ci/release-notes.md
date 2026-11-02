<p align="center">
  <img src="https://raw.githubusercontent.com/{{REPO}}/main/docs/assets/appicon.png" width="96" alt="NFS Most Wanted">
</p>

<h1 align="center">NFS Most Wanted (Xbox) for Android {{VERSION}}</h1>

<p align="center">
  An unofficial native Android port of the original Xbox version of <b>Need for Speed: Most Wanted</b>,<br>
  statically recompiled with <a href="https://github.com/TGP482/XboxXBE-Recomp">XboxXBE-Recomp</a> and drawn with Vulkan.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white" alt="Android 10+">
  <img src="https://img.shields.io/badge/arch-arm64--v8a-blue" alt="arm64-v8a">
  <img src="https://img.shields.io/badge/Vulkan-1.1-AC162C?logo=vulkan&logoColor=white" alt="Vulkan 1.1">
  <img src="https://img.shields.io/badge/SDL-3-1D4F91" alt="SDL3">
</p>

> [!IMPORTANT]
> This release contains **no game assets**. You need the files from your own legally acquired copy of *Need for Speed: Most Wanted* (Xbox, Black Edition, USA release).

## 📦 Download

| File | Size | SHA-256 |
| --- | --- | --- |
| [`{{APK}}`](https://github.com/{{REPO}}/releases/download/{{TAG}}/{{APK}}) | {{SIZE}} | `{{SHA256}}` |

## 🚀 Installation

1. Install **`{{APK}}`** on your phone.
2. Open **NFS Most Wanted**, tap **Install from ISO…** and pick the Xbox disc image (`.iso`); it is extracted once into the app's folder. A folder with `default.xbe` and the disc's `NFS` folder works too (**Choose folder…**).
3. Tap **Play**.

> [!TIP]
> Settings, saves, the shader cache and the logs are in `Android/data/com.ryuna.nfsmw2005x/files`.

## ✨ Features

<p align="center">
  <img src="https://raw.githubusercontent.com/{{REPO}}/main/docs/screenshots/race.jpg" width="720" alt="A race with the MOBILE touch controls">
</p>

| | |
| --- | --- |
| 🖥️ **Graphics** | Widescreen on the phone's own shape, render resolution 25–200%, aspect ratio, FXAA / SSAA 2x, sharpen, vivid, scanline, CRT and HDR filters, yellow filter toggle, FPS cap and V-Sync, presets (Saver, Balanced, Max) |
| 👆 **Touch Controls** | MOBILE (stick, arrows, wheel or tilt steering, pedals, NOS, gear lever) or the whole Xbox pad, ring buttons in the pad's colours, menu prompts as icon buttons, a layout editor |
| 🧭 **HUD** | Edit HUD: move or hide the speedometer, minimap, race info, EA Trax and the FPS counter, applied without a restart |
| ⚙️ **Settings** | The same categories in the launcher and in the game's **Configuration** menu; the ☰ card pauses the race |
| 🧪 **Cheats & Saves** | Infinite nitrous and speedbreaker, no police, unlock all, set cash; save slots with zip backup and restore |
| 🎮 **Other Input** | SDL3 controllers with rumble and button mapping, keyboard |

Languages: English and Indonesian. Android's back button is the controller's Start in a race and B in the menus.

## 📱 Requirements

- **Android** 11 or newer, arm64
- **GPU** with a Vulkan 1.1 driver (Adreno 6xx+, Mali-G7x+, Xclipse)
- **4 GB** RAM, **4 GB** free storage

## 🙏 Credits

Built on [XboxXBE-Recomp](https://github.com/TGP482/XboxXBE-Recomp) by **TGP482** and the engine of [MostWanted-Android](https://github.com/RanggaCasper/MostWanted-Android) by **RanggaCasper** (with a Vulkan fix by **zetaxlinux**). Uses [SDL](https://github.com/libsdl-org/SDL), [glslang](https://github.com/KhronosGroup/glslang), [toml++](https://github.com/marzer/tomlplusplus) and [Dear ImGui](https://github.com/ocornut/imgui).

*Need for Speed* is a trademark of Electronic Arts Inc. This project is not affiliated with or endorsed by them.
