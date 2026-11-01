package com.ryuna.nfsmw2005x;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The launcher's settings and the files the native engine reads in the app's
 * external files directory (app::ExeDir):
 *
 *   mostwanted.toml  the engine's settings (native/main.cpp app::Load,
 *                    src/common/game_info.cpp): render, frame rate, graphics
 *                    switches, the touch layout's mode and steering, the game
 *                    folder ([game_dir]) and the controller mapping ([padmap],
 *                    native/input.cpp). The one place those settings live:
 *                    the launcher edits it in place (TomlSettings) and the
 *                    game's own menu saves into it (app::Save), each keeping
 *                    the other's keys.
 *
 * SharedPreferences ("nfsmw") keep the launcher's own state: the game folder,
 * the overlay's switches and the controller mapping, which go into the files
 * at PLAY. Touch layout positions live in their own preferences
 * ("nfsmw_touch"), written by the editor (TouchControllerView). The HUD
 * editor's keys (hud_*, fps_x, fps_y, fps_scale) are the settings file's.
 */
final class Settings {
    // Game
    static final String K_GAME_DIR = "game_dir";
    static final String K_SAVE_SLOT = "save_slot";        // the active slot's name (SaveSlots)
    static final String K_SAVES_MIGRATED = "saves_migrated";  // the hdd before slots went into Main once
    // Display
    static final String K_FPS = "fps_counter";
    static final String K_FPS_POS = "fps_position";   // native FpsPosition 0..5
    static final String K_FPS_USAGE = "fps_usage";
    static final String K_FPS_TEMPS = "fps_temps";
    static final String K_WIDESCREEN = "widescreen";
    static final String K_ASPECT = "aspect_ratio";    // hundredths (133 4:3), 0 the whole screen; common/aspect.h
    static final String K_FPS_CAP = "fps_cap";        // 0 unlimited, 1 refresh rate, else the cap
    static final String K_VSYNC = "vsync";
    // Graphics
    static final String K_RENDER = "render_percent";  // 20..200, of the screen's height
    static final String K_AA = "anti_aliasing";       // 0 off, 1 FXAA, 2 SSAA
    static final String K_ANISO = "anisotropic_filtering";  // 0 (game's), 2, 4, 8, 16
    static final String K_FILTER = "screen_filter";   // 0 off .. 5 HDR
    static final String K_YELLOW = "yellow_tint";
    static final String K_REFLECT = "car_reflections";  // 0 full, 1 half, 2 quarter, 3 off
    static final String K_ROAD_REFLECT = "road_reflections";
    static final String K_MOTION_BLUR = "motion_blur";
    static final String K_BLOOM = "bloom";
    static final String K_FOG = "fog";
    static final String K_SHADOWS = "shadows";
    static final String K_PARTICLES = "particles";
    static final String K_RAIN = "rain";
    static final String K_RAIN_DROPS = "rain_drops";
    static final String K_PRELOAD = "shader_preload";
    // Gameplay
    static final String K_SKIP_MOVIES = "skip_movies";
    static final String K_LANGUAGE = "language";      // "int" English, "ind" Indonesian
    static final String K_CHEAT_NOS = "cheat_nos";
    static final String K_CHEAT_SB = "cheat_speedbreaker";
    static final String K_CHEAT_CASH = "cheat_cash";
    static final String K_CHEAT_COPS = "cheat_no_police";
    static final String K_CHEAT_UNLOCK = "cheat_unlock_all";
    // the set cash request (native game_info.cpp's Load and AckCashRequest): the amount, 0..MAX_CASH, set once while the
    // flag is true; the game makes the flag false once it set that amount. Written by the launcher only
    static final String K_CHEAT_SET_CASH = "cheat_set_cash";
    static final String K_CHEAT_SET_CASH_PENDING = "cheat_set_cash_pending";
    static final int MAX_CASH = 9_999_999;  // native kMaxCash
    // Sound
    static final String K_AUDIO_OUT = "audio_output"; // 0 auto, 1 stereo, 2 5.1, 3 mono
    // Touch controls
    static final String K_PAD = "pad";
    static final String K_PAD_AUTOHIDE = "pad_autohide";
    static final String K_PAD_MODE = "pad_mode";      // 0 xbox, 1 mobile
    static final String K_STEER = "steer";            // steering mode: 0 stick, 1 arrows, 2 tilt, 3 wheel
    static final String K_TILT_SENS = "tilt_sens";    // 1..10
    static final String K_VIBRATE = "vibrate";
    static final String K_HAPTIC = "haptic";          // tick on press
    // the HUD editor's (HudEditorActivity, src/common/game_info.cpp LoadHud): per item hud_<id>_x / _y, FEng units (480
    // to the frame's height) from where the game puts it, and hud_<id>_shown; the FPS counter's middle in thousandths
    // of the screen (with fps_position FPS_CUSTOM) and its size in percent
    static final String K_FPS_X = "fps_x", K_FPS_Y = "fps_y", K_FPS_SCALE = "fps_scale";
    static final int FPS_CUSTOM = 6, FPS_SCALE_MIN = 50, FPS_SCALE_MAX = 200;
    // Physical pad
    static final String K_MAP_PREFIX = "map_";        // + xbox input name
    static final String K_DEADZONE = "deadzone";      // percent
    static final String K_INVERT_LY = "invert_ly";
    static final String K_INVERT_RY = "invert_ry";
    // Advanced
    static final String K_PERF_LOG = "perf_log";

    // the stored numbers of the old FULL and DRIVE layouts, so a saved pad_mode keeps its meaning
    static final int MODE_XBOX = 0;
    static final int MODE_MOBILE = 1;
    static final int STEER_STICK = 0;
    static final int STEER_ARROWS = 1;
    static final int STEER_TILT = 2;
    static final int STEER_WHEEL = 3;

    /** The HUD items src/common/hud_layout.h moves, as the settings file's hud_<id> keys. */
    static final String[] HUD_IDS = {"speedometer", "minimap", "race_info", "ea_trax"};
    static final String[] HUD_LABELS = {"Speedometer", "Minimap", "Race info", "EA Trax"};
    /** Where the game draws them: middle x, y and half sizes in FEng's screen (origin the frame's middle, 480 high). */
    static final float[][] HUD_BOXES = {
        {307, 133, 80, 78}, {-307, 131, 68, 68}, {296, -136, 78, 64}, {-168, -183, 176, 32}};

    static String hudKey(int item, String part) { return "hud_" + HUD_IDS[item] + "_" + part; }

    static int hudX(TomlSettings t, int item) { return within(t.getInt(hudKey(item, "x"), 0), -600, 600, 0); }

    static int hudY(TomlSettings t, int item) { return within(t.getInt(hudKey(item, "y"), 0), -240, 240, 0); }

    static boolean hudShown(TomlSettings t, int item) { return t.getBool(hudKey(item, "shown"), true); }

    static int fpsScale(TomlSettings t) {
        return within(t.getInt(K_FPS_SCALE, 100), FPS_SCALE_MIN, FPS_SCALE_MAX, 100);
    }

    /** The HUD's defaults: every item where the game puts it and shown, the counter top centre at its size. */
    static void resetHud(TomlSettings t) {
        for (int i = 0; i < HUD_IDS.length; i++) {
            t.put(hudKey(i, "x"), 0);
            t.put(hudKey(i, "y"), 0);
            t.put(hudKey(i, "shown"), true);
        }
        t.put(K_FPS_POS, 0);
        t.put(K_FPS_X, 500);
        t.put(K_FPS_Y, 120);
        t.put(K_FPS_SCALE, 100);
    }

    /** Tilt angle (degrees) for full lock at sensitivity s (1..10). */
    static float tiltAngle(int s) {
        return 45f - (Math.max(1, Math.min(10, s)) - 1) * 3.5f;   // 45 .. 13.5
    }

    /** Xbox inputs that can be remapped, in display order (native/input.cpp's kInputs). */
    static final String[] XBOX_INPUTS = {
        "a", "b", "x", "y", "black", "white", "lt", "rt",
        "up", "down", "left", "right", "start", "back", "lthumb", "rthumb" };
    static final String[] XBOX_LABELS = {
        "A", "B", "X", "Y", "Black", "White", "Left trigger", "Right trigger",
        "D-pad up", "D-pad down", "D-pad left", "D-pad right", "Start", "Back",
        "Left stick click", "Right stick click" };
    /** Default SDL source for each (SDL_GetGamepadButtonFromString / AxisFromString names). */
    static final String[] SDL_DEFAULTS = {
        "a", "b", "x", "y", "leftshoulder", "rightshoulder", "lefttrigger", "righttrigger",
        "dpup", "dpdown", "dpleft", "dpright", "start", "back", "leftstick", "rightstick" };

    private Settings() { }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("nfsmw", Context.MODE_PRIVATE);
    }

    static File filesDir(Context c) {
        File d = c.getExternalFilesDir(null);
        return d != null ? d : c.getFilesDir();
    }

    static String defaultGameDir(Context c) {
        return new File(filesDir(c), "game").getAbsolutePath();
    }

    static String gameDir(Context c) {
        return prefs(c).getString(K_GAME_DIR, defaultGameDir(c));
    }

    static SaveSlots saveSlots(Context c) { return new SaveSlots(new File(gameDir(c))); }

    static String saveSlot(Context c) { return prefs(c).getString(K_SAVE_SLOT, SaveSlots.MAIN); }

    /** null when the folder holds a readable default.xbe, else the reason. */
    static String checkGameDir(String dir) {
        File xbe = new File(dir, "default.xbe");
        if (!new File(dir).isDirectory()) return "folder not found";
        if (!xbe.exists()) return "default.xbe not in this folder";
        if (!xbe.canRead()) return "default.xbe not readable (storage permission)";
        return null;
    }

    static String mapping(SharedPreferences p, int i) {
        return p.getString(K_MAP_PREFIX + XBOX_INPUTS[i], SDL_DEFAULTS[i]);
    }

    /** The HUD offsets the launcher kept in its preferences before they were the settings file's. */
    private static float legacyHud(SharedPreferences p, int item, int axis) {
        return p.getFloat("hud_" + HUD_IDS[item] + (axis == 0 ? "_x" : "_y"), 0f);
    }

    /** The settings file's keys the launcher shows: the key, the preference it lived in before the file was the
     *  place for it (taken over once), and the launcher's default. */
    private static final Object[][] NATIVE_KEYS = {
        {K_RENDER, K_RENDER, 67}, {K_FPS_CAP, K_FPS_CAP, 60}, {K_VSYNC, K_VSYNC, false},
        {K_ANISO, "aniso2", 0}, {K_FPS, "show_fps", false}, {K_FPS_POS, K_FPS_POS, 0},
        {K_FPS_USAGE, K_FPS_USAGE, true}, {K_FPS_TEMPS, K_FPS_TEMPS, true}, {K_WIDESCREEN, K_WIDESCREEN, true},
        {K_ASPECT, null, 0}, {K_AA, K_AA, 0}, {K_FILTER, K_FILTER, 0}, {K_YELLOW, K_YELLOW, false},
        {K_REFLECT, K_REFLECT, 0}, {K_ROAD_REFLECT, K_ROAD_REFLECT, true}, {K_MOTION_BLUR, K_MOTION_BLUR, true},
        {K_BLOOM, K_BLOOM, true}, {K_FOG, K_FOG, true}, {K_SHADOWS, K_SHADOWS, true},
        {K_PARTICLES, K_PARTICLES, true}, {K_RAIN, K_RAIN, true}, {K_RAIN_DROPS, K_RAIN_DROPS, true},
        {K_PRELOAD, K_PRELOAD, false}, {K_SKIP_MOVIES, K_SKIP_MOVIES, false}, {K_CHEAT_NOS, K_CHEAT_NOS, false},
        {K_CHEAT_SB, K_CHEAT_SB, false}, {K_CHEAT_CASH, K_CHEAT_CASH, false}, {K_CHEAT_COPS, K_CHEAT_COPS, false},
        {K_CHEAT_UNLOCK, K_CHEAT_UNLOCK, false}, {K_AUDIO_OUT, K_AUDIO_OUT, 0}, {K_LANGUAGE, K_LANGUAGE, "int"},
        {K_PAD_MODE, K_PAD_MODE, MODE_MOBILE}, {K_STEER, K_STEER, STEER_STICK}, {K_TILT_SENS, K_TILT_SENS, 5},
    };

    /** The header the launcher once wrote; the file is no longer rewritten at PLAY. */
    private static final String OLD_HEADER = "# written by the launcher at PLAY (Settings.java); edits here are lost";

    static File nativeFile(Context c) {
        return new File(filesDir(c), "mostwanted.toml");
    }

    /** mostwanted.toml with every key the launcher shows: a key it lacks is the old preference's value (once, from
     *  a launcher that kept them there) or the default. A broken file gives the defaults (and is kept as .bad). */
    static TomlSettings nativeSettings(Context c) {
        TomlSettings t = TomlSettings.load(nativeFile(c));
        fillNative(c, t);
        return t;
    }

    /** The keys the launcher shows that t lacks: the old preference's value or the default. */
    private static void fillNative(Context c, TomlSettings t) {
        SharedPreferences p = prefs(c);
        for (Object[] k : NATIVE_KEYS) {
            String key = (String) k[0], pref = (String) k[1];
            if (t.has(key)) continue;
            Object v = k[2];
            if (!t.recovered() && pref != null && p.contains(pref)) {
                if (v instanceof Boolean) v = p.getBoolean(pref, (Boolean) v);
                else if (v instanceof Integer) v = p.getInt(pref, (Integer) v);
                else v = p.getString(pref, (String) v);
            }
            t.put(key, v);
        }
        // the HUD: the old preferences' offsets taken over once (they went to layout.toml at PLAY)
        for (int i = 0; i < HUD_IDS.length; i++) {
            for (int a = 0; a < 2; a++) {
                String key = hudKey(i, a == 0 ? "x" : "y");
                if (!t.has(key)) t.put(key, t.recovered() ? 0 : Math.round(legacyHud(p, i, a)));
            }
            if (!t.has(hudKey(i, "shown"))) t.put(hudKey(i, "shown"), true);
        }
        if (!t.has(K_FPS_X)) t.put(K_FPS_X, 500);
        if (!t.has(K_FPS_Y)) t.put(K_FPS_Y, 120);
        if (!t.has(K_FPS_SCALE)) t.put(K_FPS_SCALE, 100);
    }

    /** The overlay's mode, steering and tilt from the settings file (the in-game overlay, the editor); the file is
     *  anyone's to edit, and the overlay indexes its per-mode arrays with these, so an unknown value is the default. */
    static int padMode(Context c) { return padMode(nativeSettings(c)); }

    static int padMode(TomlSettings t) {
        return oneOf(t.getInt(K_PAD_MODE, MODE_MOBILE), new int[] {MODE_XBOX, MODE_MOBILE}, MODE_MOBILE);
    }

    static boolean widescreen(Context c) { return nativeSettings(c).getBool(K_WIDESCREEN, true); }

    static int steer(Context c) { return steer(nativeSettings(c)); }

    static int steer(TomlSettings t) {
        return oneOf(t.getInt(K_STEER, STEER_STICK),
                     new int[] {STEER_STICK, STEER_ARROWS, STEER_TILT, STEER_WHEEL}, STEER_STICK);
    }

    static int tiltSens(Context c) { return tiltSens(nativeSettings(c)); }

    static int tiltSens(TomlSettings t) { return within(t.getInt(K_TILT_SENS, 5), 1, 10, 5); }

    // The overlay's steps, made on the file as it is now (inside updateNative's locked change): a value the launcher
    // or the game wrote since the overlay last read it is what the step starts from, never put back. Each returns the
    // value written.

    /** The other mode. */
    static int nextPadMode(TomlSettings t) {
        int m = padMode(t) == MODE_XBOX ? MODE_MOBILE : MODE_XBOX;
        t.put(K_PAD_MODE, m);
        return m;
    }

    static int stepSteer(TomlSettings t, int step) {
        int s = Math.floorMod(steer(t) + step, 4);
        t.put(K_STEER, s);
        return s;
    }

    static int stepTiltSens(TomlSettings t, int step) {
        int s = Math.max(1, Math.min(10, tiltSens(t) + step));
        t.put(K_TILT_SENS, s);
        return s;
    }

    static void putSteer(TomlSettings t, int s) {
        t.put(K_STEER, oneOf(s, new int[] {STEER_STICK, STEER_ARROWS, STEER_TILT, STEER_WHEEL}, STEER_STICK));
    }

    static void putPadMode(TomlSettings t, int m) {
        t.put(K_PAD_MODE, oneOf(m, new int[] {MODE_XBOX, MODE_MOBILE}, MODE_MOBILE));
    }

    /** A step of a mode's opacity (legacy: touchOpacity's) or size. */
    static int stepTouchLook(TomlSettings t, int mode, boolean opacity, int step, int legacy) {
        int pct = opacity ? stepTouchOpacity(touchOpacity(t, mode, legacy), step)
                          : stepTouchSize(touchSize(t, mode), step);
        t.put(opacity ? touchOpacityKey(mode) : touchSizeKey(mode), pct);
        return pct;
    }

    /** The editor's opacity edits of a mode, in order: +1 or -1 a step, 0 its RESET (the default). */
    static int replayOpacity(int base, java.util.List<Integer> edits) {
        int pct = base;
        for (int e : edits) pct = e == 0 ? TOUCH_OPACITY_DEF : stepTouchOpacity(pct, e);
        return pct;
    }

    /** The editor's DONE: each mode the editor changed the opacity of, its edits replayed on the file's value now;
     *  a mode with none is left as the file has it (another editor's change kept). legacy per mode as touchOpacity. */
    static void applyOpacityEdits(TomlSettings t, java.util.List<Integer>[] edits, int[] legacy) {
        for (int m = 0; m < edits.length; m++) {
            if (edits[m].isEmpty()) continue;
            t.put(touchOpacityKey(m), replayOpacity(touchOpacity(t, m, legacy[m]), edits[m]));
        }
    }

    // the overlay's look per mode (0 XBOX, 1 MOBILE), percent: the CONTROLS rows Opacity and Size, launcher and game.
    // In the settings file, not the touch preferences: those are cached per process and the game is its own process
    static final int TOUCH_OPACITY_DEF = 70, TOUCH_OPACITY_MIN = 15, TOUCH_SIZE_DEF = 100, TOUCH_SIZE_MIN = 50,
            TOUCH_SIZE_MAX = 220, TOUCH_STEP = 10;

    static String touchOpacityKey(int mode) { return "touch_opacity_" + mode; }

    /** Size: a factor over every control of the mode, on top of each one's own size from the editor. */
    static String touchSizeKey(int mode) { return "touch_size_" + mode; }

    /** The mode's opacity; legacy (the editor's old preference) while the file has none. */
    static int touchOpacity(TomlSettings t, int mode, int legacy) {
        int def = within(legacy, TOUCH_OPACITY_MIN, 100, TOUCH_OPACITY_DEF);
        return within(t.getInt(touchOpacityKey(mode), def), TOUCH_OPACITY_MIN, 100, def);
    }

    static int touchSize(TomlSettings t, int mode) {
        return within(t.getInt(touchSizeKey(mode), TOUCH_SIZE_DEF), TOUCH_SIZE_MIN, TOUCH_SIZE_MAX, TOUCH_SIZE_DEF);
    }

    /** A step of either: to the next tenth, kept in range. */
    static int stepTouchOpacity(int pct, int step) { return stepTenths(pct, step, TOUCH_OPACITY_MIN, 100); }

    static int stepTouchSize(int pct, int step) { return stepTenths(pct, step, TOUCH_SIZE_MIN, TOUCH_SIZE_MAX); }

    private static int stepTenths(int pct, int step, int min, int max) {
        int v = Math.round((pct + step * TOUCH_STEP) / (float) TOUCH_STEP) * TOUCH_STEP;
        return Math.max(min, Math.min(max, v));
    }

    /** v when it is one of known, else def. */
    static int oneOf(long v, int[] known, int def) {
        for (int k : known) if (k == v) return k;
        return def;
    }

    /** v when it is in min..max, else def. */
    static int within(long v, int min, int max, int def) {
        return v >= min && v <= max ? (int) v : def;
    }

    /** A Set cash entry (digits, "," "." spaces and "$" allowed as separators) as 0..MAX_CASH, or -1 when it is not
     *  one. */
    static int parseCash(String s) {
        if (s == null) return -1;
        String d = s.trim().replaceAll("[,.\\s$]", "");
        if (d.isEmpty() || d.length() > 7 || !d.matches("[0-9]+")) return -1;
        int v = Integer.parseInt(d);
        return v <= MAX_CASH ? v : -1;
    }

    /** The request in t: its amount, or -1 for none. */
    static int pendingCash(TomlSettings t) {
        if (!t.getBool(K_CHEAT_SET_CASH_PENDING, false)) return -1;
        return within(t.getInt(K_CHEAT_SET_CASH, -1), 0, MAX_CASH, -1);
    }

    /** t's request made value (0..MAX_CASH, 0 an amount too), or none for -1. */
    static void requestCash(TomlSettings t, int value) {
        if (value >= 0) t.put(K_CHEAT_SET_CASH, Math.min(value, MAX_CASH));
        t.put(K_CHEAT_SET_CASH_PENDING, value >= 0);
    }

    /** One key into the settings file, the rest of it as it is. */
    static void putNative(Context c, String key, Object v) throws IOException {
        updateNative(c, t -> t.put(key, v));
    }

    /** A change to the settings file as it is now, under the lock the game takes too (TomlSettings.update). */
    static TomlSettings updateNative(Context c, java.util.function.Consumer<TomlSettings> change) throws IOException {
        return TomlSettings.update(nativeFile(c), t -> {
            fillNative(c, t);
            change.accept(t);
        });
    }

    /** At PLAY: the settings file with the game folder, the active save slot's folder (save_dir) and the controller
     *  mapping from the preferences, every other key as the launcher and the game left it; perf from the
     *  preferences. */
    static void writeNativeFiles(Context c) throws IOException {
        SharedPreferences p = prefs(c);
        File dir = filesDir(c);

        String gameDir = gameDir(c), saveDir = saveSlots(c).dir(saveSlot(c)).getAbsolutePath();
        updateNative(c, t -> {
            t.removeLine(OLD_HEADER);
            if (!t.has("defaults")) t.put("defaults", 3);
            t.put("game_dir", gameDir);
            t.put("save_dir", saveDir);
            for (int i = 0; i < XBOX_INPUTS.length; i++) t.put("padmap." + XBOX_INPUTS[i], mapping(p, i));
            t.put("padmap." + K_DEADZONE, p.getInt(K_DEADZONE, 8));
            t.put("padmap." + K_INVERT_LY, p.getBoolean(K_INVERT_LY, false));
            t.put("padmap." + K_INVERT_RY, p.getBoolean(K_INVERT_RY, false));
        });

        // native/main.cpp: an empty file named perf turns on the engine's perf log lines (XBE_PERF)
        File perf = new File(dir, "perf");
        if (p.getBoolean(K_PERF_LOG, false)) write(perf, "");
        else if (perf.exists() && !perf.delete()) throw new IOException("cannot remove " + perf);
    }

    private static void write(File f, String s) throws IOException {
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }
}
