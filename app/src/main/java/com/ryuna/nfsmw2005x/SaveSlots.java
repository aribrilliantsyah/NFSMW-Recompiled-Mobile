package com.ryuna.nfsmw2005x;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Save slots: <game folder>/saves/<slot>/, each a whole hard disk as kernel::Boot mounts it (data_dir: E/ C/ X/ Y/ Z/
 * for the partitions, display.bin and launch.bin beside them; the game's saves in E/UDATA/<title>/<save>/). PLAY hands
 * the active one to native as save_dir. Backups are zips of a slot, entries relative to its root.
 * Every failure is an IOException whose message can be shown to the player as it is.
 */
final class SaveSlots {
    static final String MAIN = "Main";
    /** A slot's mark that a cheat was on while it was played (written by native ApplyCheats). */
    static final String CHEATS_MARK = "cheats_used";
    /** A restore stops past this much unpacked data: a save is a few hundred KB, a zip bomb is not. */
    static final long MAX_UNPACKED = 512L << 20;

    /** What a backup may hold at its top: the partitions and the files Boot writes beside them. */
    private static final String[] TOP_DIRS = {"E", "C", "X", "Y", "Z"};
    private static final String[] TOP_FILES = {"display.bin", "launch.bin", CHEATS_MARK};

    static final class Slot {
        String name;
        long sizeBytes;
        long lastModified;
        /** The save's own name (SaveMeta.xbx), null if none is found. */
        String profile;
        boolean cheatsUsed;
    }

    private final File root;

    SaveSlots(File gameDir) { root = new File(gameDir, "saves"); }

    File root() { return root; }

    File dir(String name) { return new File(root, name.trim()); }

    /** Slots by name; dot names are this class's work dirs, files are the backups beside them. */
    List<Slot> list() {
        List<Slot> out = new ArrayList<>();
        File[] kids = root.listFiles();
        if (kids == null) return out;
        for (File d : kids) {
            if (!d.isDirectory() || d.getName().startsWith(".")) continue;
            Slot s = new Slot();
            s.name = d.getName();
            long[] st = new long[2];
            stat(d, st);
            s.sizeBytes = st[0];
            s.lastModified = st[1] != 0 ? st[1] : d.lastModified();
            s.profile = profile(d);
            s.cheatsUsed = new File(d, CHEATS_MARK).exists();
            out.add(s);
        }
        out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    void create(String name) throws IOException {
        File d = dir(checkNew(name));
        if (!d.mkdirs()) throw new IOException("Cannot create the slot folder " + d);
    }

    void duplicate(String from, String to) throws IOException {
        File src = existing(from), dst = dir(checkNew(to));
        File tmp = work("copy-" + dst.getName());
        try {
            copyTree(src, tmp);
            move(tmp, dst);
        } finally {
            deleteTree(tmp);
        }
    }

    void rename(String from, String to) throws IOException {
        File src = existing(from), dst = dir(checkNew(to));
        if (!src.renameTo(dst)) throw new IOException("Cannot rename " + src.getName() + " to " + dst.getName());
    }

    void delete(String name) throws IOException {
        File d = existing(name);
        deleteTree(d);
        if (d.exists()) throw new IOException("Could not delete all of " + d.getName());
    }

    /** The slot as a zip into out (finished, not closed: out is the caller's). */
    void backup(String name, OutputStream out) throws IOException {
        File d = existing(name);
        ZipOutputStream z = new ZipOutputStream(out);
        zipTree(d, "", z);
        z.finish();
        z.flush();
    }

    /**
     * A backup into a slot: a new one, or over the one named (overwrite), whose contents first go to
     * saves/auto-<slot>-<time>.zip. The zip is copied aside and every entry checked before anything is unpacked
     * (no path leaving the slot, the layout of a slot, at most MAX_UNPACKED), then unpacked beside the slot and
     * swapped in only once it all succeeded: a bad zip leaves everything as it was.
     */
    void restore(InputStream in, String into, boolean overwrite) throws IOException {
        String name = checkName(into);
        File dst = dir(name);
        boolean replace = dst.exists();
        if (replace && !overwrite) throw new IOException("A slot named " + name + " already exists");
        File zipCopy = work("restore-" + name + ".zip"), tmp = work("restore-" + name);
        try {
            copyCapped(in, zipCopy);
            try (ZipFile zip = new ZipFile(zipCopy)) {
                check(zip, tmp);
                unpack(zip, tmp);
            }
            if (replace) {
                autoBackup(name);
                File old = work("old-" + name);
                if (!dst.renameTo(old)) throw new IOException("Cannot move the old " + name + " aside");
                if (!tmp.renameTo(dst)) {
                    old.renameTo(dst);
                    throw new IOException("Cannot put the restored " + name + " in place");
                }
                deleteTree(old);
            } else {
                move(tmp, dst);
            }
        } finally {
            zipCopy.delete();
            deleteTree(tmp);
        }
    }

    /**
     * Once, at PLAY: the hard disk of the builds before slots (<files>/hdd, else the old LLE build's game/UDATA, the
     * E: partition's UDATA) becomes Main. Copied, and the source deleted only once the copy is whole. False when Main
     * already holds something or there is nothing to move.
     */
    boolean migrateLegacy(File filesHdd, File gameUdata) throws IOException {
        File main = dir(MAIN);
        if (hasContent(main)) return false;
        File src;
        String under;
        if (hasContent(filesHdd)) {
            src = filesHdd;
            under = "";
        } else if (hasContent(gameUdata)) {
            src = gameUdata;
            under = "E/UDATA";
        } else {
            return false;
        }
        File tmp = work("migrate");
        try {
            copyTree(src, under.isEmpty() ? tmp : new File(tmp, under));
            if (main.exists() && !main.delete()) throw new IOException("Cannot replace the empty " + MAIN);
            move(tmp, main);
        } finally {
            deleteTree(tmp);
        }
        deleteTree(src);
        return true;
    }

    // ---------------------------------------------------------------- names

    /** The trimmed name of a slot that may be made: not empty, one plain folder name, not taken. */
    private String checkNew(String name) throws IOException {
        String n = checkName(name);
        if (dir(n).exists()) throw new IOException("A slot named " + n + " already exists");
        return n;
    }

    private static String checkName(String name) throws IOException {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) throw new IOException("The slot needs a name");
        if (n.length() > 40) throw new IOException("Slot names are at most 40 characters");
        // dot names are the work dirs (and . / ..), a separator would make it a path
        if (n.startsWith(".") || n.contains("/") || n.contains("\\") || n.indexOf('\0') >= 0)
            throw new IOException("A slot name cannot start with a dot or contain / or \\");
        return n;
    }

    private File existing(String name) throws IOException {
        File d = dir(checkName(name));
        if (!d.isDirectory()) throw new IOException("No slot named " + name.trim());
        return d;
    }

    /** A work dir or file in saves/, gone from an earlier run that died. */
    private File work(String what) throws IOException {
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create " + root);
        File f = new File(root, "." + what);
        deleteTree(f);
        return f;
    }

    // ---------------------------------------------------------------- zips

    /** Every entry: a path inside the slot, under the slot's top-level names, sizes within the cap; and some save. */
    private static void check(ZipFile zip, File into) throws IOException {
        String base = into.getCanonicalPath() + File.separator;
        boolean save = false;
        long total = 0;
        for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
            ZipEntry z = e.nextElement();
            String n = z.getName();
            if (n.isEmpty() || n.startsWith("/") || n.contains("\\") || n.contains("\0")
                    || (n.length() > 1 && n.charAt(1) == ':'))
                throw new IOException("Not a save backup: bad entry " + n);
            for (String part : n.split("/"))
                if (part.equals("..") || part.equals("."))
                    throw new IOException("Not a save backup: entry " + n + " leaves the slot");
            if (!new File(into, n).getCanonicalPath().startsWith(base))
                throw new IOException("Not a save backup: entry " + n + " leaves the slot");
            String top = n.contains("/") ? n.substring(0, n.indexOf('/')) : n;
            boolean dirTop = n.contains("/") && oneOf(top, TOP_DIRS), fileTop = !n.contains("/") && oneOf(top, TOP_FILES);
            if (!dirTop && !fileTop) throw new IOException("Not a save backup: unexpected " + n);
            if (n.startsWith("E/UDATA/") || n.startsWith("E/TDATA/")) save = true;
            if (z.getSize() > 0) total += z.getSize();
            if (total > MAX_UNPACKED) throw new IOException("The backup unpacks to more than " + (MAX_UNPACKED >> 20) + " MB");
        }
        if (!save) throw new IOException("Not a save backup: no E/UDATA or E/TDATA in it");
    }

    /** Into a fresh dir, counting what is written: the sizes a zip declares may lie. */
    private static void unpack(ZipFile zip, File into) throws IOException {
        if (!into.mkdirs()) throw new IOException("Cannot create " + into);
        long total = 0;
        byte[] buf = new byte[64 << 10];
        for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
            ZipEntry z = e.nextElement();
            File f = new File(into, z.getName());
            if (z.isDirectory()) {
                if (!f.isDirectory() && !f.mkdirs()) throw new IOException("Cannot create " + f);
                continue;
            }
            File p = f.getParentFile();
            if (!p.isDirectory() && !p.mkdirs()) throw new IOException("Cannot create " + p);
            try (InputStream in = zip.getInputStream(z); OutputStream out = new FileOutputStream(f)) {
                for (int r; (r = in.read(buf)) > 0; ) {
                    total += r;
                    if (total > MAX_UNPACKED)
                        throw new IOException("The backup unpacks to more than " + (MAX_UNPACKED >> 20) + " MB");
                    out.write(buf, 0, r);
                }
            }
            if (z.getTime() > 0) f.setLastModified(z.getTime());
        }
    }

    private static void zipTree(File dir, String prefix, ZipOutputStream z) throws IOException {
        File[] kids = dir.listFiles();
        if (kids == null) throw new IOException("Cannot read " + dir);
        byte[] buf = new byte[64 << 10];
        for (File k : kids) {
            String n = prefix + k.getName();
            if (k.isDirectory()) {
                // directories too: the game made E/TDATA/<title> and the like, empty ones included
                ZipEntry e = new ZipEntry(n + "/");
                e.setTime(k.lastModified());
                z.putNextEntry(e);
                z.closeEntry();
                zipTree(k, n + "/", z);
            } else {
                ZipEntry e = new ZipEntry(n);
                e.setTime(k.lastModified());
                z.putNextEntry(e);
                try (InputStream in = new FileInputStream(k)) {
                    for (int r; (r = in.read(buf)) > 0; ) z.write(buf, 0, r);
                }
                z.closeEntry();
            }
        }
    }

    private void autoBackup(String name) throws IOException {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File f = new File(root, "auto-" + name + "-" + stamp + ".zip");
        for (int i = 2; f.exists(); i++) f = new File(root, "auto-" + name + "-" + stamp + "-" + i + ".zip");
        try (OutputStream out = new FileOutputStream(f)) {
            backup(name, out);
        } catch (IOException e) {
            f.delete();
            throw new IOException("Cannot back up the old " + name + " first: " + e.getMessage());
        }
    }

    private static void copyCapped(InputStream in, File to) throws IOException {
        long total = 0;
        byte[] buf = new byte[64 << 10];
        try (OutputStream out = new FileOutputStream(to)) {
            for (int r; (r = in.read(buf)) > 0; ) {
                total += r;
                if (total > MAX_UNPACKED) throw new IOException("The file is larger than " + (MAX_UNPACKED >> 20) + " MB");
                out.write(buf, 0, r);
            }
        }
    }

    // ---------------------------------------------------------------- files

    /** The names in E/UDATA/<title>/<save>/SaveMeta.xbx (UTF-16, "Name=..." lines, as the XDK writes it). */
    private static String profile(File slot) {
        File[] titles = new File(slot, "E/UDATA").listFiles(File::isDirectory);
        if (titles == null) return null;
        List<String> names = new ArrayList<>();
        for (File t : titles) {
            File[] saves = t.listFiles(File::isDirectory);
            if (saves == null) continue;
            for (File s : saves) {
                String n = metaName(new File(s, "SaveMeta.xbx"));
                if (n != null && !names.contains(n)) names.add(n);
            }
        }
        return names.isEmpty() ? null : String.join(", ", names);
    }

    private static String metaName(File meta) {
        if (!meta.isFile() || meta.length() > 4096) return null;
        try {
            byte[] b = Files.readAllBytes(meta.toPath());
            String s = new String(b, StandardCharsets.UTF_16);  // BOM FF FE: little endian
            for (String line : s.split("\r?\n")) {
                line = line.trim();
                if (line.regionMatches(true, 0, "Name=", 0, 5) && line.length() > 5) return line.substring(5).trim();
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private static boolean oneOf(String s, String[] set) {
        for (String x : set) if (x.equals(s)) return true;
        return false;
    }

    private static boolean hasContent(File d) {
        String[] k = d == null ? null : d.list();
        return k != null && k.length > 0;
    }

    /** {bytes, newest file's time} under d. */
    private static void stat(File d, long[] st) {
        File[] kids = d.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) {
                stat(k, st);
            } else {
                st[0] += k.length();
                st[1] = Math.max(st[1], k.lastModified());
            }
        }
    }

    private static void move(File from, File to) throws IOException {
        if (!from.renameTo(to)) throw new IOException("Cannot move " + from.getName() + " to " + to);
    }

    private static void copyTree(File src, File dst) throws IOException {
        if (!dst.isDirectory() && !dst.mkdirs()) throw new IOException("Cannot create " + dst);
        File[] kids = src.listFiles();
        if (kids == null) throw new IOException("Cannot read " + src);
        for (File k : kids) {
            File t = new File(dst, k.getName());
            if (k.isDirectory()) {
                copyTree(k, t);
            } else {
                Files.copy(k.toPath(), t.toPath());
                t.setLastModified(k.lastModified());
            }
        }
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.isDirectory() ? f.listFiles() : null;
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
