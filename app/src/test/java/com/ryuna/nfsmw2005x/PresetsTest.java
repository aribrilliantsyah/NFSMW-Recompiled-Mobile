package com.ryuna.nfsmw2005x;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/** The launcher's Preset row is the game's: Presets.java against kPresets of src/common/settings_rows.cpp. */
public class PresetsTest {
    private static String rows() throws Exception {
        File f = new File("../src/common/settings_rows.cpp");
        if (!f.exists()) f = new File("src/common/settings_rows.cpp");   // run from the repo's root
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static String block(String src, String open) {
        int at = src.indexOf(open);
        assertTrue(open, at >= 0);
        return src.substring(at + open.length(), src.indexOf("};", at));
    }

    @Test
    public void fieldsAreTheGamesKeysInItsOrder() throws Exception {
        // PresetState's fields as mostwanted.toml names them (native/main.cpp, src/common/game_info.cpp)
        Map<String, String> key = new HashMap<>();
        key.put("reflections", Settings.K_REFLECT);
        key.put("aniso", Settings.K_ANISO);
        List<String> fields = new ArrayList<>();
        for (String decl : block(rows(), "struct PresetState {").replaceAll("//[^\n]*", "").split(";")) {
            decl = decl.trim().replaceFirst("^(uint32_t|bool)\\s+", "");
            if (decl.isEmpty()) continue;
            for (String n : decl.split(",")) fields.add(key.getOrDefault(n.trim(), n.trim()));
        }
        assertEquals(Arrays.asList(Presets.KEYS), fields);
    }

    @Test
    public void valuesAreTheGames() throws Exception {
        String table = block(rows(), "kPresets[kPresetCount] = {").replaceAll("//[^\n]*", "");
        Matcher m = Pattern.compile("\\{([^{}]*)\\}").matcher(table);
        List<Object[]> presets = new ArrayList<>();
        while (m.find()) {
            List<Object> vals = new ArrayList<>();
            for (String v : m.group(1).split(",")) {
                v = v.trim();
                if (v.equals("true") || v.equals("false")) vals.add(Boolean.valueOf(v));
                else if (v.equals("game::kAaOff")) vals.add(0);
                else if (v.equals("game::kAaFxaa")) vals.add(1);
                else if (v.equals("game::kAaSsaa")) vals.add(2);
                else vals.add(Integer.valueOf(v));
            }
            presets.add(vals.toArray());
        }
        assertEquals(Presets.VALUES.length, presets.size());
        for (int p = 0; p < presets.size(); p++)
            assertEquals(Presets.NAMES[p], Arrays.asList(Presets.VALUES[p]), Arrays.asList(presets.get(p)));
    }

    @Test
    public void applyThenDetectAndAHandChangeIsCustom() {
        for (int p = 0; p < Presets.VALUES.length; p++) {
            TomlSettings t = TomlSettings.load(new File("does-not-exist.toml"));
            Presets.apply(t, p);
            assertEquals(p, Presets.detect(t));
            t.put(Settings.K_FOG, !t.getBool(Settings.K_FOG, true));
            assertEquals(Presets.CUSTOM, Presets.detect(t));
        }
        assertEquals(Presets.CUSTOM, Presets.detect(TomlSettings.load(new File("does-not-exist.toml"))));
    }
}
