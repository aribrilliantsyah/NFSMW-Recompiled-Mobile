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

    @Test
    public void multiLineValuesKeptVerbatim() throws Exception {
        File f = new File(tmp.getRoot(), "mostwanted.toml");
        String body = "foo = [\n  1,\n  2, # two\n]\nnote = \"\"\"\nline \"one\"\n\"\"\"\n"
                + "box = { x = 1.5, y = -2.0 }\nwhen = 1979-05-27T07:32:00Z\nratio = 1.25e3\nbloom = true\n"
                + "\n[keys]\nup = \"W\"\n";
        Files.write(f.toPath(), body.getBytes(StandardCharsets.UTF_8));
        TomlSettings t = TomlSettings.load(f);
        assertFalse(t.recovered());
        assertTrue(t.getBool("bloom", false));
        assertTrue(t.has("foo"));
        t.put("bloom", false);
        t.save(f);
        String out = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        assertEquals(body.replace("bloom = true", "bloom = false"), out);
    }

    @Test
    public void malformedFilesAreBroken() throws Exception {
        String[] bad = {
            "bloom = true\nbloom = false\n",          // a key twice
            "[keys]\nup = \"W\"\n[keys]\n",           // a table twice
            "foo = @\n",                              // not a value
            "language = \"int\" junk\n",              // junk after the value
            "language = \"int\n",                     // string left open
            "foo = [1, 2\n",                          // array left open
            "note = \"\"\"never closed\n",            // multi-line string left open
            "x = \"\\q\"\n",                          // bad escape
        };
        for (String b : bad) {
            File f = tmp.newFile();
            Files.write(f.toPath(), b.getBytes(StandardCharsets.UTF_8));
            assertTrue(b, TomlSettings.load(f).recovered());
            assertTrue(b, new File(f.getParentFile(), f.getName() + ".bad").exists());
        }
    }

    @Test
    public void outOfRangeIntsFallBack() {
        int[] modes = {Settings.MODE_FULL, Settings.MODE_STEERING};
        assertEquals(Settings.MODE_STEERING, Settings.oneOf(2, modes, Settings.MODE_STEERING));
        assertEquals(Settings.MODE_STEERING, Settings.oneOf(-1, modes, Settings.MODE_STEERING));
        assertEquals(Settings.MODE_FULL, Settings.oneOf(0, modes, Settings.MODE_STEERING));
        assertEquals(5, Settings.within(11, 1, 10, 5));
        assertEquals(5, Settings.within(0, 1, 10, 5));
        assertEquals(10, Settings.within(10, 1, 10, 5));
    }
}
