package com.ryuna.nfsmw2005x;

/**
 * The SPEED tab's Preset: kPresets of src/common/settings_rows.cpp, the game's own Preset row, key for key and value
 * for value (PresetsTest reads the C++ table). A preset is the whole state of the keys it covers (the FPS counter's
 * are the HUD tab's, which a preset leaves alone); the file as it is matches one exactly, or the row shows Custom.
 */
final class Presets {
    static final int SAVER = 0, BALANCED = 1, MAX = 2, CUSTOM = 3;
    static final String[] NAMES = {"Saver", "Balanced", "Max", "Custom"};

    /** PresetState's fields in order, as mostwanted.toml's keys. */
    static final String[] KEYS = {
        Settings.K_RENDER, Settings.K_FPS_CAP, Settings.K_VSYNC, Settings.K_PRELOAD, Settings.K_BLOOM, Settings.K_FOG,
        Settings.K_MOTION_BLUR, Settings.K_REFLECT, Settings.K_ROAD_REFLECT, Settings.K_SHADOWS, Settings.K_PARTICLES,
        Settings.K_RAIN, Settings.K_RAIN_DROPS, Settings.K_AA, Settings.K_ANISO,
    };

    /** Integers and Booleans, in KEYS' order. */
    static final Object[][] VALUES = {
        // Saver: half the resolution, 30 fps, no V-Sync, no reflections, motion blur or particles
        {50, 30, false, true, true, true, false, 3, false, true, false, true, true, 0, 0},
        // Balanced: two thirds, 60 fps, no V-Sync, the cars' reflections every 2nd frame
        {67, 60, false, true, true, true, true, 1, true, true, true, true, true, 0, 0},
        // Max: the full resolution, no cap, V-Sync, everything on, SSAA 2x, 16x anisotropic
        {100, 0, true, true, true, true, true, 0, true, true, true, true, true, 2, 16},
    };

    private Presets() { }

    static boolean covers(String key) {
        for (String k : KEYS) if (k.equals(key)) return true;
        return false;
    }

    /** The preset t is in, else CUSTOM; a key t lacks matches none. */
    static int detect(TomlSettings t) {
        for (int p = 0; p < VALUES.length; p++) {
            boolean same = true;
            for (int i = 0; i < KEYS.length && same; i++) {
                Object v = VALUES[p][i];
                same = v instanceof Boolean
                        ? t.has(KEYS[i]) && t.getBool(KEYS[i], !(Boolean) v) == (Boolean) v
                        : t.has(KEYS[i]) && t.getInt(KEYS[i], -1) == (Integer) v;
            }
            if (same) return p;
        }
        return CUSTOM;
    }

    static void apply(TomlSettings t, int p) {
        if (p < 0 || p >= VALUES.length) return;
        for (int i = 0; i < KEYS.length; i++) t.put(KEYS[i], VALUES[p][i]);
    }
}
