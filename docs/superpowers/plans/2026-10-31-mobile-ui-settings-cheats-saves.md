# Mobile UI, in-game settings, cheats and saves Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the approved Ring-style SVG touch controls (MOBILE/XBOX), six settings categories editable both in-game and in the launcher, a CHEATS section with Set Cash, and multi-slot saves with zip backup/restore to the `hle-merge` port.

**Architecture:**
- **One settings file.** `mostwanted.toml` is the only store, read and written by native (`app::Load`/`app::Save`) and by the launcher (`TomlSettings`).
- **In-game settings.** Six FEng categories are injected into the game's OPTIONS and pause menus with the same hooks Rangga's removed `options_menu.cpp` used, re-implemented as `src/game/settings_menu.cpp` with our own category list, names and icons.
- **Touch controls.** They stay in the Java overlay, drawn from path data in a new `ButtonArt` class. Menu-action buttons come from a richer JNI (`nativeMenuPrompts`).
- **Saves.** Save slots are directories handed to `kernel::Boot`.

**Tech Stack:**
- C++20 (XboxXBE-Recomp runtime, toml++, SDL3, Vulkan).
- Java 17 Android app (minSdk 30, compileSdk 36), JUnit 4 for JVM tests.
- Python 3 for the icon generator.
- Linux host (`linux/`) for engine checks.

**Spec:** `docs/superpowers/specs/2026-10-31-mobile-ui-settings-cheats-saves-design.md`

## Global Constraints

- Package stays `com.ryuna.nfsmw2005x`; JNI symbols are `Java_com_ryuna_nfsmw2005x_GameActivity_*`.
- **Look.** Nothing is copied from Rangga's assets, names or category layout. His addresses (facts about the game) may be reused. Category names, in this order: SCREEN, SPEED, VISUALS, CONTROLS, SYSTEM, CHEATS.
- **Colours.**
  - A `#3FB950`
  - B `#E5534B`
  - X `#4493F8`
  - Y `#E3B341`
  - grey `#9AA0A6`
  - CHEATS orange `#F28C1C`
  - control fill `rgba(12,13,16,.55)`
  - inner ring: same colour, 28% opacity.
- **Icons.** 24-unit grid, 2-unit stroke, round caps and joins. Path data comes verbatim from `.work/nfsmw_buttons_mockup.html` in the main checkout (`const I = {...}`); that file is the visual reference.
- **MOBILE default layout.** Fractions of the screen; u = min(w/16, h/9).
  - Shared left margin: 0.0248·w.
  - D-pad 1.6u, y .41, left edge on the margin.
  - NOS: 1.5u at (.219, .539).
  - Steering:
    - Stick: 2.4u, y .718.
    - Wheel: 2.7u, y .718.
    - Arrows: round 1.9u, gap 0.021·w, left edge on the margin, bottom = h − 0.0391h − 0.06h.
    - Tilt: nothing on screen.
  - Y (.862, .30), X (.939, .46), A (.805, .52), each 1.15u.
  - GAS: 1.45u × 2.7u at (.939, .751).
  - BRAKE: 1.35u × 2.1u at (.848, .781).
  - Gear lever: 1u at (.55, .79).
  - BACK (.43, .07), ☰ (.50, .07), START (.57, .07): BACK and START 1.2u × 0.7u, ☰ 0.9u.
  - CAMERA: 0.9u at (.95, .07).
- **Save slots.** `<game_dir>/saves/<slot>/`, default slot `Main`. The native key is `save_dir`.
- **Cheat values.**
  - Set cash range 0–9,999,999.
  - Cash address `[[0x48EDD4]+0x10]+0xB4`.
  - Infinite NOS byte `0x49A61C`; infinite speedbreaker byte `0x4B9694`.
  - No police `[[0x494E40]+0x60]`; unlock all `0x493D00`.
- **No push.** Local commits on `hle-merge` only.

## Review Focus

1. A `mostwanted.toml` with unknown keys, wrong types or a parse error must not crash either side and must not wipe the user's other settings. Tests: `TomlSettingsTest.unknownKeysSurviveRoundTrip`, `TomlSettingsTest.brokenFileFallsBackAndKeepsCopy`.
2. A restore zip with `../` entries, absolute paths or a missing top-level layout must be refused with nothing changed. Test: `SaveSlotsTest.restoreRejectsTraversal`.
3. Slot names containing `/`, `..`, empty strings or duplicate names must be refused or normalised. Test: `SaveSlotsTest.slotNameRules`.
4. Set Cash before a profile exists, i.e. the cash address is not committed, must do nothing and must not crash. Test: Linux check in Task 6.
5. ☰ during a race followed by quick taps must never open a settings category while the race is running. Test: Linux check in Task 5.

---

### Task 1: Shared settings file (launcher reads and writes `mostwanted.toml`; native saves on change)

**Files:**
- Create: `app/src/main/java/com/ryuna/nfsmw2005x/TomlSettings.java`
- Create: `app/src/test/java/com/ryuna/nfsmw2005x/TomlSettingsTest.java`
- Modify: `app/build.gradle` (add `testImplementation 'junit:junit:4.13.2'`)
- Modify: `app/src/main/java/com/ryuna/nfsmw2005x/Settings.java:160-230` (`writeNativeFiles` writes through `TomlSettings` and no longer rebuilds the file from prefs)
- Modify: `native/main.cpp:189-220` (`Load` keeps a `.bad` copy on parse error; `Save` writes atomically through a temp file and rename)

**Interfaces:**
- Produces:
  - `final class TomlSettings`
  - `static TomlSettings load(File f)`
  - `boolean getBool(String key, boolean def)`
  - `long getInt(String key, long def)`
  - `String getString(String key, String def)`
  - `void put(String key, Object v)`
  - `void save(File f) throws IOException`
- Values are flat top-level keys plus the `[keys]` and `[padmap]` tables. Unknown keys and tables are kept verbatim.
- The file is the source for every key listed in `Settings.java:30-64`; SharedPreferences keep only `game_dir`, `pad*`, `steer`, `tilt_sens`, `vibrate`, `haptic`, `hud_*`, `map_*`, `deadzone`, `invert_*`, `perf_log` and `save_slot`.

- [ ] **Step 1: Write the failing tests.** In `TomlSettingsTest`:
  - `roundTripKeepsValues`: put `widescreen=false`, `render_percent=50`, `language="int"`; save, load, read back the same values.
  - `unknownKeysSurviveRoundTrip`: a file with `foo = 3` and `[keys]\nup = "W"`; after `put("bloom", false)` and save, both lines are still present.
  - `brokenFileFallsBackAndKeepsCopy`: file content `widescreen = = true`; `load` returns empty settings and `mostwanted.toml.bad` exists with the original bytes.
- [ ] **Step 2: Run the tests and see them fail.** Run `ANDROID_HOME=~/Documents/Repos/Android/SDK ./gradlew :app:testDebugUnitTest --tests '*TomlSettingsTest*'`. Expected: compile failure, TomlSettings not found.
- [ ] **Step 3: Implement `TomlSettings`.** A line-oriented TOML subset parser: keep the original lines, replace a `key = value` line in place, and append new keys before the first `[table]`. Values are bool, integer or quoted string. No new library.
- [ ] **Step 4: Run the tests and see them pass.** Same command as Step 2. Expected: 3 tests PASS.
- [ ] **Step 5: Switch `Settings.writeNativeFiles` and the launcher to TomlSettings.**
  - Every launcher toggle that sets a key in the native file now goes through `TomlSettings`.
  - `layout.toml` and `[padmap]` writing stay as they are.
- [ ] **Step 6: Native side.**
  - In `native/main.cpp`, `Load()` copies an unparsable file to `mostwanted.toml.bad` before returning.
  - `Save()` writes `mostwanted.toml.tmp` and renames it over the original.
- [ ] **Step 7: Build.** Run `scripts/build.sh --reuse-generated` and the Linux host build (`cmake --build linux/build`). Expected: both succeed.
- [ ] **Step 8: Commit.** `git commit -m "settings: one mostwanted.toml for the launcher and the game"`

### Task 2: Save slots and the SAVES tab

**Files:**
- Create: `app/src/main/java/com/ryuna/nfsmw2005x/SaveSlots.java`
- Create: `app/src/test/java/com/ryuna/nfsmw2005x/SaveSlotsTest.java`
- Modify: `app/src/main/java/com/ryuna/nfsmw2005x/LauncherActivity.java` (SAVES tab; PLAY writes `save_dir`)
- Modify: `native/main.cpp:386` (hdd root = `save_dir` key when set and existing, else `<files>/hdd`)

**Interfaces:**
- Consumes: `TomlSettings` (Task 1).
- Produces:
  - `final class SaveSlots`
  - `SaveSlots(File gameDir)`
  - `List<Slot> list()`, where `Slot` has `String name; long sizeBytes; long lastModified; String profile /* null if unknown */`
  - `File dir(String name)`
  - `void create(String name)`
  - `void duplicate(String from, String to)`
  - `void rename(String from, String to)`
  - `void delete(String name)`
  - `void backup(String name, OutputStream zip)`
  - `void restore(InputStream zip, String into, boolean overwrite)`
  - `boolean migrateLegacy(File filesHdd, File gameUdata)`
  - All failures throw `IOException` carrying a user-readable message.

- [ ] **Step 1: Write the failing tests.** In `SaveSlotsTest`, using a temp dir:
  - `migrateMovesHddIntoMain`
  - `duplicateCopiesTree`
  - `backupRestoreRoundTrip` (byte-equal files)
  - `restoreRejectsTraversal`: a zip with entry `../evil` throws, and the target slot is unchanged.
  - `restoreOverwriteMakesAutoBackup`: an `auto-<slot>-*.zip` appears in `saves/`.
  - `slotNameRules`: `""`, `"a/b"`, `".."` and an existing name each throw; `" Main "` is trimmed.
- [ ] **Step 2: Run the tests and see them fail.** Run `./gradlew :app:testDebugUnitTest --tests '*SaveSlotsTest*'`. Expected: compile failure.
- [ ] **Step 3: Implement `SaveSlots`.** Use `java.util.zip`. Zip entries are relative to the slot root. Before writing anything, reject entries whose normalised path leaves the root.
- [ ] **Step 4: Run the tests and see them pass.** Expected: 6 PASS.
- [ ] **Step 5: SAVES tab in `LauncherActivity`.**
  - The tab lists slots: name, profile if known, date, size, and a CHEATS ON tag when the slot's `cheats_used` marker file exists.
  - Actions: Select, New, Duplicate, Rename, Delete (with confirmation), Backup (`ACTION_CREATE_DOCUMENT`, default name `NFSMW-<slot>-<yyyyMMdd>.zip`) and Restore (`ACTION_OPEN_DOCUMENT`).
  - Restore and Delete are refused while `:game` runs (`ActivityManager` running processes).
  - One-line warning: data in Android/data is removed on uninstall.
  - On PLAY, call `migrateLegacy` once, then write `save_dir` = the active slot's dir.
  - If the active slot's dir is missing, select `Main` (create it if needed) and show a one-line notice before starting.
- [ ] **Step 6: Native side.** `native/main.cpp` passes `save_dir` to `kernel::Boot` when it is set and exists, and logs `save dir: <path>`.
- [ ] **Step 7: Verify.**
  - Linux host: run with `save_dir` pointing at a fresh dir and create an alias. Expected: files appear under that dir.
  - Phone: create slot "Cheat" and duplicate Main into it, play, back up to Downloads, delete, restore. Expected: the profile is intact.
- [ ] **Step 8: Commit.** `git commit -m "saves: slots in the game folder, backup and restore"`

### Task 3: ButtonArt and the Ring style

**Files:**
- Create: `app/src/main/java/com/ryuna/nfsmw2005x/ButtonArt.java`
- Create: `app/src/test/java/com/ryuna/nfsmw2005x/ButtonArtTest.java`
- Modify: `app/src/main/java/com/ryuna/nfsmw2005x/TouchControllerView.java` (drawing of every control goes through `ButtonArt`)

**Interfaces:**
- Produces:
  - `final class ButtonArt`
  - `enum Icon { NOS, EBRAKE, SPEEDBREAKER, LOOKBACK, CAMERA, GAS, BRAKE, GEAR, START, BACK, MENU, LEFT, RIGHT, ZOOM_IN, ZOOM_OUT, ACCEPT, RESTART, STATS, EXIT, CANCEL }`
  - `static void drawRound(Canvas c, float cx, float cy, float r, int color, Icon icon, String badge, boolean pressed, float opacity)`
  - `static void drawPedal(Canvas c, RectF box, int color, Icon icon, String badge, boolean pressed, float opacity)`
  - `static void drawPill(Canvas c, RectF box, int color, Icon icon, boolean pressed, float opacity)`
  - `static void drawStick(Canvas c, float cx, float cy, float r, float knobX, float knobY, float opacity)`
  - `static void drawWheel(Canvas c, float cx, float cy, float r, float angleDeg, float opacity)`
  - `static void drawDpad(Canvas c, float cx, float cy, float size, int pressedMask, float opacity)`
  - `static Path iconPath(Icon i)`: 24-grid path, cached.
  - `static Icon iconForPrompt(String label)`: null if unknown.

- [ ] **Step 1: Write the failing tests.** These run as plain JVM tests because they touch no `android.graphics`.
  - `iconForPromptMapsKnownLabels`: "ACCEPT"/"CONTINUE"/"OK" → ACCEPT; "BACK" → BACK; "RESTART"/"RESTART RACE" → RESTART; "STATISTICS"/"STATS" → STATS; "EXIT"/"QUIT" → EXIT; "CANCEL" → CANCEL; "ZOOM IN" → ZOOM_IN; "ZOOM OUT" → ZOOM_OUT. Matching is case-insensitive; "xyz" → null.
  - `everyIconHasPathData`: for each `Icon`, `ButtonArt.iconSvg(i)` (the raw string) is non-empty and parses with the class's own SVG-subset path parser to at least one segment, without throwing.
- [ ] **Step 2: Run the tests and see them fail.** Run `./gradlew :app:testDebugUnitTest --tests '*ButtonArtTest*'`. Expected: compile failure.
- [ ] **Step 3: Implement `ButtonArt`.**
  - Icon path strings are copied from the mockup's `I` table.
  - A small parser handles the SVG path commands the mockup uses (M L H V C S Q A Z, plus `<rect>`/`<circle>` primitives converted at load), building `android.graphics.Path`. Parsing goes through an interface so the JVM test needs no Android.
  - Ring frame: fill, full border at 5% of the diameter, inner ring at 2.6× the border width inset, 28% opacity.
  - Pedal outline: points (.25w,0) (.75w,0) (w,.16h) (w,h) (0,h) (0,.16h), corner radius .16w.
  - Badge: circle radius .17·d on the top edge, dark fill `#15171B`, border in the control colour.
- [ ] **Step 4: Run the tests and see them pass.** Expected: 2 PASS.
- [ ] **Step 5: Use ButtonArt in `TouchControllerView`.** Draw every control through `ButtonArt`, removing the old Canvas circle and rectangle drawing and the text labels. Pressed state: fill alpha up 50%, border 1.4×.
- [ ] **Step 6: Verify.** Install on the phone and take a screenshot in a race. Expected: it matches the mockup's Ring race view (`.work/nfsmw_buttons_mockup.html`, stick steering).
- [ ] **Step 7: Commit.** `git commit -m "touch: Ring-style SVG controls (ButtonArt)"`

### Task 4: MOBILE / XBOX modes, four steering modes, menu-action row

**Files:**
- Modify: `app/src/main/java/com/ryuna/nfsmw2005x/TouchControllerView.java` (`build()` layouts, mode names, steering, menu row)
- Modify: `app/src/main/java/com/ryuna/nfsmw2005x/Settings.java` (`MODE_MOBILE=1`, `MODE_XBOX=0`, `STEER_WHEEL=3`)
- Modify: `native/activity.cpp:71-86` (replace `nativeMenuPills` with `nativeMenuPrompts`)
- Modify: `native/touch.cpp` (expose prompts as label plus pad button)
- Modify: `app/src/main/java/com/ryuna/nfsmw2005x/GameActivity.java` (native declaration)

**Interfaces:**
- Consumes: `ButtonArt` (Task 3).
- Produces:
  - JNI `static native String[] nativeMenuPrompts()`, returning pairs `[label0, pad0, label1, pad1, ...]`, where pad is one of `A B X Y LT RT BACK START`. It returns `null` when no FEng menu is shown.
  - Steering values: 0 stick, 1 arrows, 2 tilt, 3 wheel.
  - `LAYOUT_VERSION = 6`.

- [ ] **Step 1: Native side.**
  - `touch::CurrentMenuPrompts()` returns a vector of `{std::string label; int pad;}` from `game::CurrentFePrompts()`. The prompt index maps to the pad: 0 A, 1 B, 2 X, 3 Y, 4 LT, 5 RT.
  - `nativeMenuPrompts` builds the String array from it.
  - Remove `nativeMenuPills` and its Java caller.
- [ ] **Step 2: Rename and renumber the modes.**
  - Rename DRIVE to MOBILE and FULL to XBOX in names and UI.
  - Bump `LAYOUT_VERSION` to 6 so stored layouts from version 5 are cleared once.
- [ ] **Step 3: MOBILE layout.** Positions and sizes exactly as in Global Constraints. The steering choice shows the stick, the arrows, the wheel or nothing (tilt).
  - Wheel input: angle of the finger round the centre against the angle at touch-down, clamped to ±120°, mapped to stick X −1..1.
- [ ] **Step 4: XBOX layout.**
  - LT/RT in the top corners, BACK/☰/START top centre.
  - Left stick (2u) at (.10, .44) and D-pad (2.1u) at (.10, .80).
  - A/B/X/Y diamond around (.90, .44) with step 0.05·w horizontally and 0.11·h vertically.
  - CAMERA (Black) and White above the diamond, right stick (1.9u) at (.90, .80).
  - The layout never changes with menus.
- [ ] **Step 5: MOBILE menu row.**
  - Poll `nativeMenuPrompts` every 250 ms; the existing `menuPoll` is reused.
  - When the result is non-null, hide the race controls and draw one round 1.15u button per prompt in a centred row at y .86, spaced 0.08·w, coloured by its pad and drawn with `iconForPrompt(label)`. An unknown label falls back to the pad letter as the badge and the pad letter as text.
  - LT/RT prompts become ZOOM_OUT/ZOOM_IN at (.045, .62)/(.045, .76).
  - A tap presses that pad button for 120 ms.
  - When the result is null, the race controls come back.
- [ ] **Step 6: Verify.**
  - Phone: race in each of the four steering modes, and check that STANDINGS shows icon buttons and that no ZOOM covers the D-pad.
  - XBOX mode in a race and in the menu.
  - Linux host: the `touchdrv.py` swipe and keyboard checks from `.work/hle-merge-evidence/README.md` still pass.
- [ ] **Step 7: Commit.** `git commit -m "touch: MOBILE and XBOX modes, wheel steering, menu action icons"`

### Task 5: In-game settings categories and auto-pause

**Files:**
- Create: `src/game/settings_menu.cpp`, the FEng injection. The reference is `git show rangga/main:src/game/options_menu.cpp`: the addresses and hook points may be reused, the sections, names and icon art may not.
- Create: `src/common/settings_rows.h` and `src/common/settings_rows.cpp`, the six categories' rows, values, stepping and apply.
- Create: `tools/category_icons.py`, which writes `src/game/category_icons.inc` from our 24-grid icon paths (64×64 ARGB).
- Modify: `CMakeLists.txt` and `linux/CMakeLists.txt` (new sources)
- Modify: `native/activity.cpp` (JNI `nativeOpenSettings(int category)`)
- Modify: `app/src/main/java/com/ryuna/nfsmw2005x/TouchControllerView.java` (☰ during a race calls `nativeOpenSettings(-1)`, which opens the category list)

**Interfaces:**
- Consumes: `Options` (`src/common/options.h`), `app::Save()` (Task 1).
- Produces:
  - `namespace settings { enum Category { kScreen, kSpeed, kVisuals, kControls, kSystem, kCheats, kCount }; struct Row { Kind kind; int id; const char* label; }; const std::vector<Row>& Rows(Category); std::string Value(const Row&, int step = 0); void Activate(const Row&); const char* Name(Category); uint32_t Color(Category); }`
  - `Kind` is one of `Toggle`, `Choice`, `Number` and `Action`.
  - `Value(row, step)` applies the step to `Options`, calls `app::Save()` and returns the new display string.
  - JNI `nativeOpenSettings(int category)`, where −1 means the list.

- [ ] **Step 1: `settings_rows.cpp`.**
  - Rows follow the spec §2.1 table: names, order, ranges, and the preset behaviour (Saver/Balanced/Max set the SPEED and VISUALS rows; any manual change shows "Custom").
  - Rows that apply only after a restart show the suffix " (next start)".
  - Every row's value comes from `Options` or `app::Get()`.
- [ ] **Step 2: Category icons.** `tools/category_icons.py` renders 6 icons (screen = monitor, speed = gauge, visuals = sparkle, controls = gamepad, system = cog, cheats = bolt in orange) from 24-grid paths to `category_icons.inc` (`kIconCount = 6`, 64×64). All are new drawings.
- [ ] **Step 3: `settings_menu.cpp`.**
  - Inject a "SETTINGS" icon beside the game's OPTIONS in the main and pause menus, using the menu-setup hooks at `0x104C0F` and `0x105A5B`.
  - That icon opens a UIOptionsMain holding our six category icons, using category ids `0x20 + Category`.
  - Each category is filled with toggle widgets whose Act/Draw call `settings::Value`/`settings::Activate`.
  - The CHEATS category draws its row titles and highlight in `#F28C1C`.
- [ ] **Step 4: Auto-pause for ☰.**
  - `nativeOpenSettings(c)` sets a pending request.
  - On the game thread at the next present, if a race is running and no FEng menu is shown, post START (pause) and keep the request.
  - Once `FeMenuShown()` is true on the pause menu, open SETTINGS (and category c if c ≥ 0).
  - Outside a race, open directly.
  - Closing returns to the pause menu.
- [ ] **Step 5: Verify on the Linux host** (touchdrv/pad script).
  - From the main menu: SETTINGS → SCREEN → toggle Yellow filter. Expected: the screenshot changes and `mostwanted.toml` has `yellow_tint = true`.
  - In a race, ☰ then five quick taps. Expected: the race is paused before any category shows; the screenshot shows the pause background.
  - Resume. Expected: the race continues.
- [ ] **Step 6: Commit.** `git commit -m "settings: six categories in the game's options and pause menus, auto-pause"`

### Task 6: CHEATS (incl. Set Cash) in both editors

**Files:**
- Modify: `src/common/options.h` (add `uint32_t cheat_set_cash = 0;` as a pending value, not persisted)
- Modify: `src/game/game.cpp:101-150` (`ApplyCheats` applies `cheat_set_cash` once and clears it)
- Modify: `src/common/settings_rows.cpp` (CHEATS rows: the five toggles plus a Set Cash number row with APPLY)
- Modify: `app/src/main/java/com/ryuna/nfsmw2005x/LauncherActivity.java` (CHEATS tab with an orange border; a Set Cash field writes `cheat_set_cash` into the toml and native applies it at the first present that has a profile)

**Interfaces:**
- Consumes: `settings::Row` with Kind `Number` (Task 5), `TomlSettings` (Task 1).
- Produces: toml key `cheat_set_cash` (0 = none). Native clears it in the file after applying it.

- [ ] **Step 1: Native side.** In `ApplyCheats`, when `cheat_set_cash > 0` and the profile cash address is committed, write the value, set `cheat_set_cash = 0`, call `app::Save()`, and touch the `cheats_used` marker file in the save dir.
  - Every other cheat that is on also touches the marker once per boot.
- [ ] **Step 2: In-game CHEATS rows.**
  - The five toggles: Infinite nitrous, Infinite speedbreaker, No police, Unlock all, Lock cash at max.
  - "Set cash", a number stepped by 10,000 (hold for ×10), range 0–9,999,999.
  - "Apply cash", an action.
- [ ] **Step 3: Launcher CHEATS tab.** The same rows, with a numeric field for Set Cash, inside an orange (`#F28C1C`) bordered card.
- [ ] **Step 4: Verify on the Linux host.**
  - Career profile: Set cash 1,234,567 then Apply. Expected: the career cash shows 1,234,567 on screen and the toml's `cheat_set_cash` is back to 0.
  - With no profile (fresh slot): Apply. Expected: nothing changes, no crash, and the value stays pending until a profile loads.
- [ ] **Step 5: Verify on the phone.** Infinite NOS in a race; No police in free roam.
- [ ] **Step 6: Commit.** `git commit -m "cheats: CHEATS category in game and launcher, set cash"`

### Task 7: Launcher tabs regrouped and final check

**Files:**
- Modify: `app/src/main/java/com/ryuna/nfsmw2005x/LauncherActivity.java` (tabs SCREEN, SPEED, VISUALS, CONTROLS, SYSTEM, CHEATS, SAVES with the rows in the spec's order; the existing toggles move, none are duplicated)
- Modify: `README.md`, `CLAUDE.md` (features, saves location, build)

- [ ] **Step 1: Regroup the launcher's rows.** Move the existing rows into the seven tabs in the spec's order. CONTROLS gets MOBILE/XBOX, four steering options, Edit buttons and Edit HUD.
- [ ] **Step 2: Consistency check.** For every key, change it in the launcher, PLAY and confirm the in-game row shows the same value. Change it in game, quit and confirm the launcher shows it. Sample: widescreen, render %, yellow filter, motion blur, steering, cheat_nos.
- [ ] **Step 3: Regression on the phone.**
  - Tap/swipe menus, typing an alias, showcase drag, HUD editor, widescreen and yellow toggles.
  - A race in MOBILE (each steering) and in XBOX.
- [ ] **Step 4: Docs.** Write the README sections: Controls, Settings, Cheats, Saves.
- [ ] **Step 5: Commit.** `git commit -m "launcher: tabs as the in-game categories; docs"`
