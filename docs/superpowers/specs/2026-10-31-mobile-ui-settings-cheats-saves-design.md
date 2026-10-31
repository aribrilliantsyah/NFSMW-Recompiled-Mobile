# Mobile UI, in-game settings, cheats and saves: design

Date: 2026-10-31. Branch `hle-merge` (Rangga's HLE engine with the nfsw-recompiled-mobile shell). Status: draft for review.

## 1. Goal

Make the port feel like a native mobile game:

- Settings can be changed while playing (in the game's own menus) as well as from the launcher. Both read and write
  the same file.
- Own touch controls: new SVG buttons, a MOBILE mode whose buttons follow the screen, and an XBOX mode with the whole pad.
- A CHEATS section.
- Multiple save slots with backup and restore.

**Constraint.** It must work like Rangga's MostWanted-Android (MIT, used as reference) but must not look like it. That means our
own grouping, names, icons and button art; nothing is copied from Rangga's assets.

**Success.**

- On the POCO M3 (Adreno 610) and in the Linux host:
  - every setting changes the game the same way from either place;
  - the touch layout matches the approved mockup;
  - cheats apply live;
  - a save slot survives backup → delete → restore.
- No regression in the Stage 1/2 features: tap/swipe in menus, typing on the game's keyboard, drag in the showcase, the
  HUD editor, widescreen, the yellow-tint toggle.

Out of scope: performance work, the minimap bug, new game features beyond the cheats below.

## 2. Settings: one file, two editors

- **Source of truth.** `mostwanted.toml` in the app's files directory, read by `native/main.cpp` (`app::Load`) into
  `Options` (`src/common/options.h`).
  - Today the launcher rewrites it at PLAY from `SharedPreferences` (`Settings.java`), and edits made elsewhere are lost.
  - **Change:** the launcher reads `mostwanted.toml` when it opens and writes it back. The native side saves `Options` to the
    same file when an in-game change is confirmed. `SharedPreferences` keep only shell-only state (game folder, active slot,
    overlay layout).
- **Live apply.** Every option in the in-game menu takes effect without a restart, except render resolution and
  anti-aliasing, which already rebuild targets in Rangga's backend. Options that truly need a restart are marked
  "applies next start" in both editors (language, audio output, shader preload).

### 2.1 Categories (both editors, same order and names)

| Category | Border | Rows |
|---|---|---|
| SCREEN | grey | Widescreen, Aspect ratio, Render resolution, Screen filter, Yellow filter |
| SPEED | grey | Preset (Saver / Balanced / Max), FPS cap, V-Sync, FPS counter, Counter position, CPU/GPU usage, Temperatures, Shader preloading |
| VISUALS | grey | Bloom, Fog, Motion blur, Car reflections, Road reflections, Shadows, Particles, Falling rain, Screen raindrops, Anti-aliasing, Anisotropic filtering |
| CONTROLS | grey | Control mode (MOBILE / XBOX), Steering (Stick / Arrows / Wheel / Tilt), Tilt sensitivity, Opacity, Size, Edit buttons, Edit HUD |
| SYSTEM | grey | Language, Audio output, Skip movies, Active save slot (read-only in game), About |
| CHEATS | **Most Wanted orange `#F28C1C`** | see §4 |

- A SPEED preset sets the SPEED and VISUALS rows at once. Changing any row afterwards shows the preset as "Custom".
  - **Saver:** 50% render, 30 fps, reflections off, motion blur off, particles off.
  - **Balanced:** 67%, 60 fps, reflections every 2 frames.
  - **Max:** 100%, unlimited, everything on.
- Labels are English. Indonesian stays a launcher-only translation (the game font may lack glyphs).

### 2.2 In-game menu

- **Mechanism.** The same one Rangga uses: our sections are injected into the game's OPTIONS screen and the pause menu as
  extra FEng categories, with their rows as the game's own toggle and slider widgets.
  - Rangga's `src/game/options_menu.cpp` (commit `b01625e`) was removed in Stage 1. It is the reference for the hooks:
    UIOptionsMain 0x104C70, Setup 0x128AB0, category ids from 0x20 up.
  - It is re-implemented with our 6 categories and our own icons. Its addresses are facts about the game and are reused;
    its layout, names and icon art are not.
- **Icons.** Six new category icons drawn in our icon style (24-grid, 2px stroke, round caps), rendered to the texture
  format the injected icons need. Rangga's `tools/option_icons.py` is replaced by our generator.
- **Auto-pause.** Opening any settings category or CHEATS during a race pauses the game.
  - From the pause menu the game is already paused.
  - From the overlay's ☰ during a race, the overlay first sends START (pause), waits for the pause menu (`FeMenuShown`),
    then opens the category.
  - Closing returns to the pause menu. The race resumes only when the player picks Resume.
  - Outside a race (front end), ☰ opens the category directly.

### 2.3 Launcher

- Six tabs with the same names and order, plus **SAVES** (§5). Tabs use the launcher's existing `UiKit` style.
- CHEATS gets the orange border and an orange tab marker.

## 3. Touch controls

All controls are SVG, rendered with `android.graphics.Path` by `TouchControllerView`. The PNG art from Rangga and the
native overlay in `native/touch.cpp` stay unused.

### 3.1 Visual style: Ring

- **Fill.** Dark translucent `rgba(12,13,16,.55)`.
- **Border.** One full border in the control's colour, plus a faint inner ring (same colour, 28% opacity).
- **Icon.** White line icon.
- **Colours.**
  - A green `#3FB950`
  - B red `#E5534B`
  - X blue `#4493F8`
  - Y yellow `#E3B341`
  - Shoulders, pedals and system grey `#9AA0A6`
  - CHEATS orange `#F28C1C`
- **Badge.** Face buttons and pedals carry a small badge on their top edge with the pad letter (A/B/X/Y, LT/RT).
- **Pressed.** Fill brightens, border thickens.

### 3.2 Icons (own drawings, 24-grid)

| Control | Pad | Icon |
|---|---|---|
| NOS | B | tilted nitrous bottle with lightning |
| E-BRAKE | A | handbrake lever |
| SPEEDBREAKER | X | stopwatch with slow-motion trails |
| LOOK BACK | Y | rear-view mirror with reflection streaks |
| CAMERA | Black | filled camcorder |
| GAS | RT | tall pedal shape, double chevron up |
| BRAKE | LT | shorter pedal shape, three horizontal lines |
| Gear lever | right stick | H-pattern |
| BACK / ☰ / START | Back / – / Start | arrow, hamburger, pause bars |
| D-pad | D-pad | cross with four separate arms and chevrons |
| Arrows | left stick ±X | round Ring buttons with chevrons |
| Wheel | left stick X | steering wheel turned by dragging round it |
| Menu actions | as prompted | ACCEPT ✓, BACK ←, RESTART ↻, STATISTICS bars, EXIT door, CANCEL ✕, ZOOM IN/OUT magnifier ± |

The approved mockup is the reference: `.work/nfsmw_buttons_mockup.html` in the main checkout.

### 3.3 MOBILE mode (was DRIVE)

**Default layout.** Fractions of the screen, from the user's DRIVE layout:

- **Top row.**
  - BACK, ☰ and START at top centre: BACK left of ☰, START right of it.
  - CAMERA in the top-right corner.
- **Left.** The left column shares one margin: 2.48% of the width from the left edge, the same as the pedals' distance
  from the right edge. The D-pad, stick, wheel and left arrow all put their left edge on it.
  - D-pad: 1.6u, y .41.
  - NOS (.219, .539), scale 1.2.
  - Steering, which depends on the steering option:
    - **Stick:** 2.4u, y .718.
    - **Arrows:** two round 1.9u buttons. They mirror the pedals: same gap between them, same distance from the screen edge,
      bottoms on the pedals' line.
    - **Wheel:** 2.7u, y .718.
    - **Tilt:** nothing on screen.
- **Right.**
  - Y (.862, .30), X (.939, .46), A (.805, .52), each 1.15u.
  - Edge-to-edge gaps of at least 0.69u (≈ 9 mm on a 6.5" phone) between A, X, Y and the pedals.
  - Pedals: GAS (.939, .751), BRAKE (.848, .781).
- **Middle.** Gear lever (.55, .79).
- **Bottom margin.** The bottom row sits about 10% of the screen height off the bottom edge.

**Follows the screen.**

- **During a race:** the race controls above.
- **When a FEng menu is shown** (`nativeMenuPills` / `FeMenuShown`):
  - The race controls hide.
  - Menu actions appear as icon-only Ring buttons in a row at bottom centre, bordered in the colour of the pad button they
    press. Each icon is picked from the prompt's label (§3.2); an unknown label falls back to the pad letter.
  - ZOOM IN/OUT magnifiers appear on the left only on screens that prompt the triggers.
  - Taps and swipes on the game's menu keep working (Stage 2).
- **Keyboard, world map, showcase:** as Stage 2: tap keys, drag for the right stick.

### 3.4 XBOX mode

- The whole pad, always, in the same Ring style:
  - LT/RT top corners;
  - BACK/☰/START top centre;
  - left stick and D-pad on the left;
  - A/B/X/Y diamond with Black (camera) and White above it, right stick below it, on the right.
- It does not change with menus. Menu taps and swipes still work where no control covers the screen.

### 3.5 Editor

- The existing EditorActivity moves, resizes and hides every control in both modes.
- Layout version goes to 6; saved layouts from version 5 are dropped once.
- The HUD editor is unchanged.

## 4. Cheats

| Cheat | Kind | Mechanism | Status |
|---|---|---|---|
| Infinite nitrous | toggle | byte 0x49A61C held at 1 | from Rangga, proven |
| Infinite speedbreaker | toggle | byte 0x4B9694 held at 1 | from Rangga, proven |
| No police | toggle | `[[0x494E40]+0x60]` held at 1, restored when off | from Rangga, proven |
| Unlock all | toggle | byte 0x493D00 held at 1, restored when off | from Rangga, proven |
| Lock cash at max | toggle | `[[0x48EDD4]+0x10]+0xB4` = 9,999,999 each present | from Rangga, proven |
| **Set cash** | number + APPLY | same address, written once with the entered value (0 to 9,999,999) | new, same address |
| **Bounty / heat** | number / level | address unknown | new, needs RE first |
| **Blacklist unlock** | pick rival | address unknown | new, needs RE first |

- Cheats apply live, through `ApplyCheats` at each present.
- Set cash is applied on confirm only and is not stored as a toggle.
- The two RE items are a separate task with their own report. They are added to the menu only when their address is
  proven in the binary and tested in a race. Until then they are not shown at all, not even greyed out.
- A small "CHEATS ON" tag shows in the launcher's slot list on slots last played with any cheat on. This is informational
  only.

## 5. Save slots

- **Layout.**
  - Slots live inside the game folder: `<game>/saves/<slot>/`.
  - Each slot is a full `hdd` directory, passed to `kernel::Boot` (`native/main.cpp:386`, today `<files>/hdd`).
  - The default slot is `Main`.
  - **Migration.** On first start of this build, an existing `<files>/hdd` (or the old LLE build's `game/UDATA`) is moved
    into `saves/Main` and is not deleted until the move has succeeded.
- **Active slot.**
  - It is chosen in the launcher only. The engine reads the save root once at boot.
  - PLAY passes the slot path to native through `mostwanted.toml` (`save_dir`).
  - The in-game SYSTEM category shows the slot name.
- **SAVES screen (launcher).**
  - Slot list with name, profile alias (read from the save if parseable, else omitted), last played date and size.
  - Actions:
    - **Select;**
    - **New** (empty);
    - **Duplicate;**
    - **Rename;**
    - **Delete** (with confirmation);
    - **Backup** to a `.zip` at a location picked with the system picker (SAF `ACTION_CREATE_DOCUMENT`), named
      `NFSMW-<slot>-<date>.zip`;
    - **Restore** a `.zip` (SAF `ACTION_OPEN_DOCUMENT`) into a new slot or over an existing one. Overwriting first writes an
      automatic backup zip of the old contents next to the saves.
  - A one-line warning: data under `Android/data` is removed when the app is uninstalled; back up first.
- **Safety.**
  - Zips are checked before restore (path traversal, size, expected layout).
  - Restore and delete are refused while the game process is running.

## 6. Components and boundaries

| Unit | Owns | Talks to |
|---|---|---|
| `Settings.java` → `TomlSettings` (new) | read/write `mostwanted.toml`; shell-only prefs stay in SharedPreferences | launcher tabs, GameActivity |
| `native/main.cpp` + `src/common/options.*` | load/save `Options`, `save_dir` | engine, in-game menu |
| `src/game/settings_menu.cpp` (new, replaces Rangga's options_menu) | FEng category injection, rows, auto-pause handshake | `Options`, `ApplyCheats`, overlay via JNI |
| `src/game/game.cpp` `ApplyCheats` | cheats incl. set-cash request | game memory |
| `TouchControllerView` + `ButtonArt` (new) | SVG paths, Ring style, MOBILE/XBOX layouts, menu-action row | JNI `nativeSetPad*`, `nativeMenuPills` (extended to return prompts and pad buttons) |
| `SaveSlots` (new, Java) | slot dirs, migration, zip backup/restore | LauncherActivity SAVES tab |

New JNI:

- `nativeMenuPrompts()`: the shown prompts with their labels and pad buttons. It replaces `nativeMenuPills`.
- `nativeOpenSettings(category)`: the ☰ shortcut, which runs pause first.

## 7. Error handling

- A broken or unknown `mostwanted.toml`: defaults are used, a backup copy `mostwanted.toml.bad` is kept, and the launcher
  shows a one-line notice.
- A slot dir that is missing at PLAY: fall back to `Main` and tell the user.
- A failed zip: an error with the reason, and nothing is changed.
- Cheat addresses not committed (e.g. no profile yet): the write is skipped silently, as Rangga does.

## 8. Testing

- **Linux host** (uinput touchscreen, `touchdrv.py`):
  - change each SCREEN/VISUALS/CHEATS row in game, then check a screenshot diff and the toml value;
  - auto-pause from ☰ during a race;
  - set cash, then check the career cash on screen.
- **Unit (Java):**
  - `TomlSettings` round-trip;
  - `SaveSlots` migration, duplicate, zip backup/restore and traversal rejection.
- **Phone (POCO M3):**
  - MOBILE race layout matches the mockup;
  - each of the 4 steering modes;
  - menu-action icons on STANDINGS (and ZOOM no longer covering the D-pad);
  - XBOX mode;
  - cheats in a race;
  - slot backup to Downloads → delete → restore → profile intact.
- **Regression:** the Stage 1/2 checks (tap/swipe/keyboard/showcase, HUD editor, widescreen, yellow filter).

## 9. Order of work

1. Settings file shared by both editors (`TomlSettings`, native save).
2. Save slots and SAVES tab.
3. Button art and the Ring style; MOBILE/XBOX layouts; menu-action row.
4. In-game categories and auto-pause.
5. CHEATS, including set cash.
6. Separately: RE for bounty/heat and blacklist; added only if proven.
