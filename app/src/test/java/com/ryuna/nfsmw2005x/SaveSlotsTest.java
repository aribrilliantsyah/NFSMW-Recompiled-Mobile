package com.ryuna.nfsmw2005x;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SaveSlotsTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static void write(File f, String s) throws IOException {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] read(File f) throws IOException { return Files.readAllBytes(f.toPath()); }

    /** A slot as kernel::Boot leaves it: E/UDATA/<title>/<save>/..., display.bin at the root. */
    private static void fillSlot(File root) throws IOException {
        write(new File(root, "E/UDATA/4541007b/TitleMeta.xbx"), "title");
        write(new File(root, "E/UDATA/4541007b/004100520049/ARI"), "career bytes");
        new File(root, "E/TDATA/4541007b").mkdirs();
        write(new File(root, "display.bin"), "pixels");
    }

    @Test
    public void migrateMovesHddIntoMain() throws Exception {
        File files = tmp.newFolder("files"), game = tmp.newFolder("game");
        File hdd = new File(files, "hdd");
        fillSlot(hdd);
        SaveSlots s = new SaveSlots(game);

        assertTrue(s.migrateLegacy(hdd, new File(game, "UDATA")));
        File main = s.dir("Main");
        assertEquals("career bytes", new String(read(new File(main, "E/UDATA/4541007b/004100520049/ARI")),
                                               StandardCharsets.UTF_8));
        assertTrue(new File(main, "E/TDATA/4541007b").isDirectory());
        assertFalse(hdd.exists());
        // Main has content now: a second call is a no-op
        fillSlot(hdd);
        assertFalse(s.migrateLegacy(hdd, new File(game, "UDATA")));
        assertTrue(hdd.exists());
    }

    @Test
    public void duplicateCopiesTree() throws Exception {
        SaveSlots s = new SaveSlots(tmp.newFolder("game"));
        s.create("Main");
        fillSlot(s.dir("Main"));
        s.duplicate("Main", "Cheat");
        assertArrayEquals(read(new File(s.dir("Main"), "E/UDATA/4541007b/004100520049/ARI")),
                          read(new File(s.dir("Cheat"), "E/UDATA/4541007b/004100520049/ARI")));
        assertTrue(new File(s.dir("Cheat"), "E/TDATA/4541007b").isDirectory());
        assertEquals(2, s.list().size());
    }

    @Test
    public void backupRestoreRoundTrip() throws Exception {
        SaveSlots s = new SaveSlots(tmp.newFolder("game"));
        s.create("Main");
        fillSlot(s.dir("Main"));
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        s.backup("Main", zip);

        s.restore(new ByteArrayInputStream(zip.toByteArray()), "Copy", false);
        for (String p : new String[] {"E/UDATA/4541007b/TitleMeta.xbx", "E/UDATA/4541007b/004100520049/ARI",
                                      "display.bin"})
            assertArrayEquals(p, read(new File(s.dir("Main"), p)), read(new File(s.dir("Copy"), p)));
        assertTrue(new File(s.dir("Copy"), "E/TDATA/4541007b").isDirectory());
    }

    @Test
    public void restoreRejectsTraversal() throws Exception {
        File game = tmp.newFolder("game");
        SaveSlots s = new SaveSlots(game);
        s.create("Main");
        fillSlot(s.dir("Main"));
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(zip)) {
            z.putNextEntry(new ZipEntry("E/UDATA/4541007b/x"));
            z.write(1);
            z.putNextEntry(new ZipEntry("../evil"));
            z.write(2);
        }
        try {
            s.restore(new ByteArrayInputStream(zip.toByteArray()), "Main", true);
            fail("a zip leaving the slot was restored");
        } catch (IOException expected) { }
        assertFalse(new File(game, "saves/evil").exists());
        assertFalse(new File(s.dir("Main"), "E/UDATA/4541007b/x").exists());
        assertEquals("career bytes", new String(read(new File(s.dir("Main"), "E/UDATA/4541007b/004100520049/ARI")),
                                               StandardCharsets.UTF_8));
        assertEquals(1, s.list().size());
    }

    @Test
    public void restoreOverwriteMakesAutoBackup() throws Exception {
        File game = tmp.newFolder("game");
        SaveSlots s = new SaveSlots(game);
        s.create("Main");
        fillSlot(s.dir("Main"));
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        s.backup("Main", zip);
        write(new File(s.dir("Main"), "E/UDATA/4541007b/004100520049/ARI"), "newer");

        s.restore(new ByteArrayInputStream(zip.toByteArray()), "Main", true);
        File[] auto = new File(game, "saves").listFiles((d, n) -> n.startsWith("auto-Main-") && n.endsWith(".zip"));
        assertEquals(1, auto.length);
        assertEquals("career bytes", new String(read(new File(s.dir("Main"), "E/UDATA/4541007b/004100520049/ARI")),
                                               StandardCharsets.UTF_8));
        // the automatic backup holds the contents that were overwritten
        s.restore(Files.newInputStream(auto[0].toPath()), "Old", false);
        assertEquals("newer", new String(read(new File(s.dir("Old"), "E/UDATA/4541007b/004100520049/ARI")),
                                        StandardCharsets.UTF_8));
    }

    @Test
    public void slotNameRules() throws Exception {
        SaveSlots s = new SaveSlots(tmp.newFolder("game"));
        s.create("Main");
        for (String bad : new String[] {"", "a/b", "..", "Main"}) {
            try {
                s.create(bad);
                fail("accepted slot name \"" + bad + "\"");
            } catch (IOException expected) { }
        }
        s.create(" Cheat ");
        assertTrue(s.dir("Cheat").isDirectory());
        try {
            s.create(" Main ");
            fail("\" Main \" is Main once trimmed");
        } catch (IOException expected) { }
    }
}
