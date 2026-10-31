package com.ryuna.nfsmw2005x;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TomlSettingsTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void roundTripKeepsValues() throws Exception {
        File f = new File(tmp.getRoot(), "mostwanted.toml");
        TomlSettings t = TomlSettings.load(f);
        t.put("widescreen", false);
        t.put("render_percent", 50);
        t.put("language", "int");
        t.save(f);

        TomlSettings r = TomlSettings.load(f);
        assertFalse(r.getBool("widescreen", true));
        assertEquals(50, r.getInt("render_percent", 0));
        assertEquals("int", r.getString("language", "ind"));
    }

    @Test
    public void unknownKeysSurviveRoundTrip() throws Exception {
        File f = new File(tmp.getRoot(), "mostwanted.toml");
        Files.write(f.toPath(), "foo = 3\n[keys]\nup = \"W\"\n".getBytes(StandardCharsets.UTF_8));
        TomlSettings t = TomlSettings.load(f);
        t.put("bloom", false);
        t.save(f);

        List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        assertTrue(lines.contains("foo = 3"));
        assertTrue(lines.contains("up = \"W\""));
        assertTrue(lines.contains("bloom = false"));
        // a new top-level key goes before the first table, so it stays top level
        assertTrue(lines.indexOf("bloom = false") < lines.indexOf("[keys]"));
        TomlSettings r = TomlSettings.load(f);
        assertEquals(3, r.getInt("foo", 0));
        assertEquals("W", r.getString("keys.up", ""));
    }

    @Test
    public void brokenFileFallsBackAndKeepsCopy() throws Exception {
        File f = new File(tmp.getRoot(), "mostwanted.toml");
        byte[] broken = "widescreen = = true\n".getBytes(StandardCharsets.UTF_8);
        Files.write(f.toPath(), broken);
        TomlSettings t = TomlSettings.load(f);

        assertTrue(t.recovered());
        assertTrue(t.getBool("widescreen", true));
        assertFalse(t.getBool("widescreen", false));
        File bad = new File(tmp.getRoot(), "mostwanted.toml.bad");
        assertTrue(bad.exists());
        assertArrayEquals(broken, Files.readAllBytes(bad.toPath()));
    }
}
