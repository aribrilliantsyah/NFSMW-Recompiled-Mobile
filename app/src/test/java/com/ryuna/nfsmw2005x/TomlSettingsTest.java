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
            "x = 01\n",                               // leading zero
            "x = 1__0\n",                             // double underscore
            "x = 1_\n",                               // trailing underscore
            "x = 0x_\n",                              // hex with no digits
            "x = 0x_1\n",                             // underscore not between digits
            "x = 1.\n",                               // float with no fraction
            "x = 1e\n",                               // float with no exponent
            "x = 2026-13-01\n",                       // no 13th month
            "x = 9223372036854775808\n",              // beyond a 64-bit integer
            "fps _cap = 60\n",                        // blank inside a bare key
            "keys.up = \"W\"\n[keys]\nup = \"S\"\n",  // the same path by dotted key and by header
            "[keys]\nup = \"W\"\n[keys.up]\n",        // a value reopened as a table
            "[a]\n[a.b]\n[a]\n",                      // a table twice, sub-table between
            "a.b = 1\na.b.c = 2\n",                   // a value used as a table
            "x = 07:32\n",                            // a local time with no seconds
            "x = 1979-05-27T07:32Z\n",                // a date-time with no seconds
            "y = 2026-02-31\n",                       // no 31st of February
            "y = 2026-02-29\n",                       // 2026 is no leap year
            "y = 1900-02-29\n",                       // nor is 1900
            "y = 2026-04-31\n",                       // April has 30 days
        };
        for (String b : bad) {
            File f = tmp.newFile();
            Files.write(f.toPath(), b.getBytes(StandardCharsets.UTF_8));
            assertTrue(b, TomlSettings.load(f).recovered());
            assertTrue(b, new File(f.getParentFile(), f.getName() + ".bad").exists());
        }
    }

    @Test
    public void validEdgesAreKept() throws Exception {
        String[] good = {
            "[[items]]\n[items.meta]\nx = 1\n[[items]]\n[items.meta]\nx = 2\n",  // one sub-table per element
            "x = 0\ny = -0\nz = 1_000\nh = 0xDEAD_beef\no = 0o17\nb = 0b1010\n",
            "f = 1.5\ng = -0.0\nh = 1e10\ni = 6.02e+23\nj = inf\nk = -nan\nl = 1_0.0_1\n",
            "d = 1979-05-27\nt = 07:32:00\ndt = 1979-05-27T07:32:00.999-07:00\nsp = 1979-05-27 07:32:00Z\n",
            "x = 9223372036854775807\ny = -9223372036854775808\n",
            "a.b = 1\na.c = 2\n[x]\ny.z = 1\n",
            "\"quoted key\" = 1\n'lit' = 2\nsite.\"google.com\" = true\n",
            "[a.b]\n[a]\nx = 1\n",                     // a parent opened after its sub-table
            "y = 2024-02-29\nz = 2000-02-29\n",        // leap days
            "\"padmap.a\" = 1\npadmap.a = 2\n",        // two paths: one key with a dot, and a in padmap
        };
        for (String g : good) {
            File f = tmp.newFile();
            Files.write(f.toPath(), g.getBytes(StandardCharsets.UTF_8));
            TomlSettings t = TomlSettings.load(f);
            assertFalse(g, t.recovered());
            t.save(f);
            assertEquals(g, new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        }
    }

    @Test
    public void longIntsAndDottedKeys() throws Exception {
        File f = tmp.newFile();
        Files.write(f.toPath(), "big = 9223372036854775807\npadmap.a = \"x\"\n".getBytes(StandardCharsets.UTF_8));
        TomlSettings t = TomlSettings.load(f);
        assertEquals(Long.MAX_VALUE, t.getInt("big", 0));
        // a dotted key at the top is the same entry as padmap.a; a new padmap key joins it as a dotted key
        assertEquals("x", t.getString("padmap.a", ""));
        t.put("padmap.a", "y");
        t.put("padmap.b", "z");
        t.save(f);
        assertEquals("big = 9223372036854775807\npadmap.a = \"y\"\npadmap.b = \"z\"\n",
                     new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        assertFalse(TomlSettings.load(f).recovered());
    }

    @Test
    public void quotedDotIsOneSegment() throws Exception {
        File f = tmp.newFile();
        Files.write(f.toPath(), "\"padmap.a\" = \"literal\"\n\n[padmap]\na = \"nested\"\n".getBytes(StandardCharsets.UTF_8));
        TomlSettings t = TomlSettings.load(f);
        assertFalse(t.recovered());
        // the API's "padmap.a" is the nested path, never the one key whose name holds a dot
        assertEquals("nested", t.getString("padmap.a", ""));
        t.put("padmap.a", "changed");
        t.save(f);
        assertEquals("\"padmap.a\" = \"literal\"\n\n[padmap]\na = \"changed\"\n",
                     new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));

        // with only the quoted key there, put adds the nested one as a table entry, not over the quoted key
        File g = tmp.newFile();
        Files.write(g.toPath(), "\"padmap.a\" = \"literal\"\n".getBytes(StandardCharsets.UTF_8));
        TomlSettings u = TomlSettings.load(g);
        assertFalse(u.has("padmap.a"));
        u.put("padmap.a", "nested");
        u.save(g);
        assertEquals("\"padmap.a\" = \"literal\"\n\n[padmap]\na = \"nested\"\n",
                     new String(Files.readAllBytes(g.toPath()), StandardCharsets.UTF_8));
        assertFalse(TomlSettings.load(g).recovered());
    }

    @Test
    public void outOfRangeIntsFallBack() {
        int[] modes = {Settings.MODE_XBOX, Settings.MODE_MOBILE};
        assertEquals(Settings.MODE_MOBILE, Settings.oneOf(2, modes, Settings.MODE_MOBILE));
        assertEquals(Settings.MODE_MOBILE, Settings.oneOf(-1, modes, Settings.MODE_MOBILE));
        assertEquals(Settings.MODE_XBOX, Settings.oneOf(0, modes, Settings.MODE_MOBILE));
        int[] steers = {Settings.STEER_STICK, Settings.STEER_ARROWS, Settings.STEER_TILT, Settings.STEER_WHEEL};
        assertEquals(Settings.STEER_WHEEL, Settings.oneOf(3, steers, Settings.STEER_STICK));
        assertEquals(Settings.STEER_STICK, Settings.oneOf(4, steers, Settings.STEER_STICK));
        assertEquals(5, Settings.within(11, 1, 10, 5));
        assertEquals(5, Settings.within(0, 1, 10, 5));
        assertEquals(10, Settings.within(10, 1, 10, 5));
    }

    /** Two writers under the lock each re-read the file: neither loses the other's key. */
    @Test
    public void updatesUnderTheLockKeepEachOthersKeys() throws Exception {
        File f = new File(tmp.getRoot(), "mostwanted.toml");
        Files.write(f.toPath(), "save_dir = \"/s/Main\"\n".getBytes(StandardCharsets.UTF_8));
        int n = 20;
        Thread[] writers = new Thread[n];
        for (int i = 0; i < n; i++) {
            int k = i;
            writers[i] = new Thread(() -> {
                try {
                    TomlSettings.update(f, t -> t.put("key" + k, k));
                } catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        for (Thread w : writers) w.start();
        for (Thread w : writers) w.join();
        TomlSettings r = TomlSettings.load(f);
        for (int i = 0; i < n; i++) assertEquals(i, r.getInt("key" + i, -1));
        assertEquals("/s/Main", r.getString("save_dir", ""));
        assertTrue(new File(tmp.getRoot(), "mostwanted.toml.lock").exists());
    }

    /** The game's lock (native: fcntl F_SETLKW on the same .lock file, here python's lockf in another process) holds
     *  an update off until it goes: the two lock kinds are one. */
    @Test
    public void updateWaitsForTheNativeLock() throws Exception {
        File f = new File(tmp.getRoot(), "mostwanted.toml");
        File lock = new File(tmp.getRoot(), "mostwanted.toml.lock");
        Process holder;
        try {
            holder = new ProcessBuilder("python3", "-c",
                    "import fcntl,sys,time\nf=open(sys.argv[1],'a')\nfcntl.lockf(f,fcntl.LOCK_EX)\n"
                    + "print('held',flush=True)\ntime.sleep(1)", lock.getPath()).start();
        } catch (java.io.IOException e) {
            org.junit.Assume.assumeNoException(e);  // no python3 here
            return;
        }
        assertEquals("held", new java.io.BufferedReader(new java.io.InputStreamReader(holder.getInputStream())).readLine());
        long start = System.nanoTime();
        TomlSettings.update(f, t -> t.put("bloom", false));
        long waited = (System.nanoTime() - start) / 1_000_000;
        holder.waitFor();
        assertTrue("waited " + waited + " ms", waited >= 500);
        assertFalse(TomlSettings.load(f).getBool("bloom", true));
    }
}
