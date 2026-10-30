package com.ryuna.nfsmw2005x;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Every launcher setting, kept in SharedPreferences ("nfsmw"), and the files
 * the native engine reads at start in the app's external files directory
 * (app::ExeDir):
 *
 *   mostwanted.toml  the engine's settings (native/main.cpp app::Load,
 *                    src/common/game_info.cpp): render, frame rate, graphics
 *                    switches, the game folder ([game_dir]) and the
 *                    controller mapping ([padmap], native/input.cpp)
 *   layout.toml      [hud]: where the HUD's speedometer, minimap, race info
 *                    and EA Trax banner are moved (src/game/hud_layout.cpp)
 *
 * Both are rewritten at PLAY from the preferences; the engine does not write
 * them back. Touch layout positions live in their own preferences
 * ("nfsmw_touch"), written by the editor (TouchControllerView).
 */
final class Settings {
    // Game
    static final String K_GAME_DIR = "game_dir";
    // Display
    static final String K_FPS = "show_fps";
    static final String K_FPS_POS = "fps_position";   // native FpsPosition 0..5
    static final String K_FPS_USAGE = "fps_usage";
    static final String K_FPS_TEMPS = "fps_temps";
    static final String K_WIDESCREEN = "widescreen";
    static final String K_FPS_CAP = "fps_cap";        // 0 unlimited, 1 refresh rate, else the cap
    static final String K_VSYNC = "vsync";
    // Graphics
    static final String K_RENDER = "render_percent";  // 20..200, of the screen's height
    static final String K_AA = "anti_aliasing";       // 0 off, 1 FXAA, 2 SSAA
    static final String K_ANISO = "aniso2";           // 0 (game's), 2, 4, 8, 16
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
    // Sound
    static final String K_AUDIO_OUT = "audio_output"; // 0 auto, 1 stereo, 2 5.1, 3 mono
    // Touch controls
    static final String K_PAD = "pad";
    static final String K_PAD_AUTOHIDE = "pad_autohide";
    static final String K_PAD_MODE = "pad_mode";      // 0 full, 1 steering
    static final String K_STEER = "steer";            // steering mode: 0 stick, 1 arrows, 2 tilt
    static final String K_TILT_SENS = "tilt_sens";    // 1..10
    static final String K_VIBRATE = "vibrate";
    static final String K_HAPTIC = "haptic";          // tick on press
    // HUD positions, FEng units (480 to the frame's height) from where the game puts them
    static final String K_HUD_PREFIX = "hud_";        // + HUD_IDS[i] + "_x" / "_y"
    // Physical pad
    static final String K_MAP_PREFIX = "map_";        // + xbox input name
    static final String K_DEADZONE = "deadzone";      // percent
    static final String K_INVERT_LY = "invert_ly";
    static final String K_INVERT_RY = "invert_ry";
    // Advanced
    static final String K_PERF_LOG = "perf_log";

    static final int MODE_FULL = 0;
    static final int MODE_STEERING = 1;
    static final int STEER_STICK = 0;
    static final int STEER_ARROWS = 1;
    static final int STEER_TILT = 2;

    /** The HUD items src/common/hud_layout.h moves, as layout.toml's [hud] keys. */
    static final String[] HUD_IDS = {"speedometer", "minimap", "race_info", "ea_trax"};
    static final String[] HUD_LABELS = {"SPEEDOMETER", "MINIMAP", "RACE INFO", "EA TRAX"};
    /** kHudBoxes: middle x, y and half sizes in FEng's screen (origin the frame's middle, 480 high). */
    static final float[][] HUD_BOXES = {
        {307, 133, 80, 78}, {-307, 131, 68, 68}, {296, -136, 78, 64}, {-168, -183, 176, 32}};

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

    static float hud(SharedPreferences p, int item, int axis) {
        return p.getFloat(K_HUD_PREFIX + HUD_IDS[item] + (axis == 0 ? "_x" : "_y"), 0f);
    }

    /** A TOML basic string. */
    private static String str(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            if (ch == '"' || ch == '\\') b.append('\\');
            b.append(ch);
        }
        return b.append('"').toString();
    }

    private static void bool(StringBuilder b, SharedPreferences p, String key, boolean def) {
        b.append(key).append(" = ").append(p.getBoolean(key, def)).append('\n');
    }

    private static void num(StringBuilder b, String key, int v) {
        b.append(key).append(" = ").append(v).append('\n');
    }

    static void writeNativeFiles(Context c) throws IOException {
        SharedPreferences p = prefs(c);
        File dir = filesDir(c);

        StringBuilder t = new StringBuilder("# written by the launcher at PLAY (Settings.java); edits here are lost\n");
        t.append("defaults = 3\n");
        t.append("game_dir = ").append(str(gameDir(c))).append('\n');
        num(t, "render_percent", p.getInt(K_RENDER, 67));
        num(t, "fps_cap", p.getInt(K_FPS_CAP, 60));
        bool(t, p, K_VSYNC, false);
        num(t, "anisotropic_filtering", p.getInt(K_ANISO, 0));
        t.append("fps_counter = ").append(p.getBoolean(K_FPS, false)).append('\n');
        num(t, "fps_position", p.getInt(K_FPS_POS, 2));
        bool(t, p, K_FPS_USAGE, true);
        bool(t, p, K_FPS_TEMPS, true);
        bool(t, p, K_WIDESCREEN, true);
        num(t, "aspect_ratio", 0);
        num(t, "anti_aliasing", p.getInt(K_AA, 0));
        num(t, "screen_filter", p.getInt(K_FILTER, 0));
        bool(t, p, K_YELLOW, false);
        num(t, "car_reflections", p.getInt(K_REFLECT, 0));
        bool(t, p, K_ROAD_REFLECT, true);
        bool(t, p, K_MOTION_BLUR, true);
        bool(t, p, K_BLOOM, true);
        bool(t, p, K_FOG, true);
        bool(t, p, K_SHADOWS, true);
        bool(t, p, K_PARTICLES, true);
        bool(t, p, K_RAIN, true);
        bool(t, p, K_RAIN_DROPS, true);
        bool(t, p, K_PRELOAD, false);
        bool(t, p, K_SKIP_MOVIES, false);
        bool(t, p, K_CHEAT_NOS, false);
        bool(t, p, K_CHEAT_SB, false);
        bool(t, p, K_CHEAT_CASH, false);
        bool(t, p, K_CHEAT_COPS, false);
        bool(t, p, K_CHEAT_UNLOCK, false);
        num(t, "audio_output", p.getInt(K_AUDIO_OUT, 0));
        t.append("language = ").append(str(p.getString(K_LANGUAGE, "int"))).append('\n');
        t.append("\n[padmap]\n");
        for (int i = 0; i < XBOX_INPUTS.length; i++)
            t.append(XBOX_INPUTS[i]).append(" = ").append(str(mapping(p, i))).append('\n');
        num(t, "deadzone", p.getInt(K_DEADZONE, 8));
        bool(t, p, K_INVERT_LY, false);
        bool(t, p, K_INVERT_RY, false);
        write(new File(dir, "mostwanted.toml"), t.toString());

        StringBuilder l = new StringBuilder("# written by the launcher (HUD layout editor)\n[hud]\n");
        for (int i = 0; i < HUD_IDS.length; i++)
            l.append(String.format(Locale.US, "%s = { x = %.1f, y = %.1f }\n", HUD_IDS[i], hud(p, i, 0), hud(p, i, 1)));
        write(new File(dir, "layout.toml"), l.toString());

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
