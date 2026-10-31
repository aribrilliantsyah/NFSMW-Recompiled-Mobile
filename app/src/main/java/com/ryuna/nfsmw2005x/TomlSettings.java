package com.ryuna.nfsmw2005x;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * mostwanted.toml as the launcher and the game share it: a line-oriented
 * TOML subset. Top-level keys and the keys of [table]s ("padmap.a") hold
 * bools, integers or strings; every other line (comments, unknown tables,
 * values of other types) is kept verbatim, so what the native side writes
 * (native/main.cpp app::Save) survives the launcher's edits and back.
 */
final class TomlSettings {
    /** One line of the file; key null for a comment, a blank or a table header. */
    private static final class Line {
        String text;
        final String table, key;
        Object value;     // Boolean, Long, String, or null for a value of another type
        Line(String text, String table, String key, Object value) {
            this.text = text;
            this.table = table;
            this.key = key;
            this.value = value;
        }
    }

    private final List<Line> lines = new ArrayList<>();
    private boolean recovered;

    private TomlSettings() { }

    /** The file's settings; none when it is missing. An unreadable file is
     *  copied to <name>.bad and empty settings come back (recovered()). */
    static TomlSettings load(File f) {
        TomlSettings t = new TomlSettings();
        if (!f.exists()) return t;
        try {
            byte[] bytes = Files.readAllBytes(f.toPath());
            try {
                t.parse(new String(bytes, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                t.lines.clear();
                t.recovered = true;
                Files.write(new File(f.getParentFile(), f.getName() + ".bad").toPath(), bytes);
            }
        } catch (IOException e) {
            t.lines.clear();
            t.recovered = true;
        }
        return t;
    }

    /** load found the file broken and fell back to defaults. */
    boolean recovered() { return recovered; }

    boolean has(String key) { return find(key) != null; }

    boolean getBool(String key, boolean def) {
        Line l = find(key);
        return l != null && l.value instanceof Boolean ? (Boolean) l.value : def;
    }

    long getInt(String key, long def) {
        Line l = find(key);
        return l != null && l.value instanceof Long ? (Long) l.value : def;
    }

    String getString(String key, String def) {
        Line l = find(key);
        return l != null && l.value instanceof String ? (String) l.value : def;
    }

    /** key, or table.key for a key in [table]; v a Boolean, an integer or a String. */
    void put(String key, Object v) {
        Object value = v instanceof Number ? (Object) ((Number) v).longValue() : v;
        if (!(value instanceof Boolean || value instanceof Long || value instanceof String))
            throw new IllegalArgumentException("not a bool, integer or string: " + key);
        int dot = key.indexOf('.');
        String table = dot < 0 ? "" : key.substring(0, dot), name = dot < 0 ? key : key.substring(dot + 1);
        String text = name + " = " + format(value);
        Line l = find(key);
        if (l != null) {
            l.text = text;
            l.value = value;
            return;
        }
        lines.add(insertAt(table), new Line(text, table, name, value));
    }

    /** Drops every line that is exactly text (an old comment). */
    void removeLine(String text) {
        lines.removeIf(l -> l.key == null && l.text.equals(text));
    }

    /** Written to a temporary file renamed over f, so a reader never sees half of it. */
    void save(File f) throws IOException {
        StringBuilder b = new StringBuilder();
        for (Line l : lines) b.append(l.text).append('\n');
        File tmp = new File(f.getParentFile(), f.getName() + ".java.tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(b.toString().getBytes(StandardCharsets.UTF_8));
        }
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // ------------------------------------------------------------------ inside

    private Line find(String key) {
        int dot = key.indexOf('.');
        String table = dot < 0 ? "" : key.substring(0, dot), name = dot < 0 ? key : key.substring(dot + 1);
        for (Line l : lines)
            if (l.key != null && l.table.equals(table) && l.key.equals(name)) return l;
        return null;
    }

    /** A new top-level key goes before the first table (and the blanks before it), a table's after its last key;
     *  a table not in the file yet is added at the end. */
    private int insertAt(String table) {
        int header = -1;
        for (int i = 0; i < lines.size() && header < 0; i++) {
            Line l = lines.get(i);
            if (l.key != null || !isHeader(l.text)) continue;
            if (table.isEmpty() || l.table.equals(table)) header = i;
        }
        if (table.isEmpty()) {
            int at = header < 0 ? lines.size() : header;
            while (at > 0 && lines.get(at - 1).text.trim().isEmpty()) at--;
            return at;
        }
        if (header < 0) {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).text.trim().isEmpty())
                lines.add(new Line("", "", null, null));
            lines.add(new Line("[" + table + "]", table, null, null));
            return lines.size();
        }
        int at = header + 1;
        for (int i = header + 1; i < lines.size() && lines.get(i).table.equals(table); i++)
            if (lines.get(i).key != null) at = i + 1;
        return at;
    }

    private static boolean isHeader(String text) {
        return text.trim().startsWith("[");
    }

    private void parse(String s) {
        String table = "";
        for (String raw : s.split("\r?\n", -1)) {
            lines.add(parseLine(raw, table));
            Line l = lines.get(lines.size() - 1);
            if (l.key == null && isHeader(raw)) table = l.table;
        }
        // split leaves one empty string after the last newline
        if (!lines.isEmpty() && lines.get(lines.size() - 1).text.isEmpty() && s.endsWith("\n"))
            lines.remove(lines.size() - 1);
    }

    private static Line parseLine(String raw, String table) {
        String t = raw.trim();
        if (t.isEmpty() || t.startsWith("#")) return new Line(raw, table, null, null);
        if (t.startsWith("[")) {
            boolean array = t.startsWith("[[");
            int end = t.indexOf(array ? "]]" : "]");
            if (end < 0) throw new IllegalArgumentException("unclosed table header: " + raw);
            String name = t.substring(array ? 2 : 1, end).trim();
            if (name.isEmpty()) throw new IllegalArgumentException("empty table name: " + raw);
            // keys under an array of tables are not addressable: "[[" cannot be a table name put() makes
            return new Line(raw, array ? "[[" + name : name, null, null);
        }
        int eq;
        String key;
        if (t.charAt(0) == '"' || t.charAt(0) == '\'') {
            int close = t.indexOf(t.charAt(0), 1);
            if (close < 0) throw new IllegalArgumentException("unclosed key: " + raw);
            key = t.substring(1, close);
            eq = t.indexOf('=', close + 1);
            if (eq < 0 || !t.substring(close + 1, eq).trim().isEmpty()) throw new IllegalArgumentException("no value: " + raw);
        } else {
            eq = t.indexOf('=');
            if (eq <= 0) throw new IllegalArgumentException("no key = value: " + raw);
            key = t.substring(0, eq).trim();
            if (!key.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("bad key: " + raw);
        }
        String v = t.substring(eq + 1).trim();
        if (v.isEmpty() || v.startsWith("=")) throw new IllegalArgumentException("no value: " + raw);
        return new Line(raw, table, key, value(v));
    }

    /** A bool, an integer or a one-line string; null for any other value (kept, not readable here). */
    private static Object value(String v) {
        char c = v.charAt(0);
        if (c == '"') return basic(v);
        if (c == '\'') {
            int close = v.indexOf('\'', 1);
            if (close < 0) throw new IllegalArgumentException("unclosed string: " + v);
            return v.startsWith("'''") ? null : v.substring(1, close);
        }
        String bare = v;
        int hash = bare.indexOf('#');
        if (hash >= 0) bare = bare.substring(0, hash).trim();
        if (bare.equals("true")) return Boolean.TRUE;
        if (bare.equals("false")) return Boolean.FALSE;
        if (bare.matches("[+-]?[0-9][0-9_]*")) {
            try { return Long.parseLong(bare.replace("_", "").replace("+", "")); }
            catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private static String basic(String v) {
        if (v.startsWith("\"\"\"")) return null;
        StringBuilder b = new StringBuilder();
        for (int i = 1; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (ch == '"') return b.toString();
            if (ch != '\\') { b.append(ch); continue; }
            if (++i >= v.length()) break;
            char e = v.charAt(i);
            switch (e) {
                case 'n': b.append('\n'); break;
                case 't': b.append('\t'); break;
                case 'r': b.append('\r'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'u': case 'U': {
                    int n = e == 'u' ? 4 : 8;
                    if (i + n >= v.length()) throw new IllegalArgumentException("bad escape: " + v);
                    b.appendCodePoint(Integer.parseInt(v.substring(i + 1, i + 1 + n), 16));
                    i += n;
                    break;
                }
                default: b.append(e);
            }
        }
        throw new IllegalArgumentException("unclosed string: " + v);
    }

    private static String format(Object v) {
        if (!(v instanceof String)) return v.toString();
        StringBuilder b = new StringBuilder("\"");
        for (char ch : ((String) v).toCharArray()) {
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch == '\n') b.append("\\n");
            else if (ch == '\t') b.append("\\t");
            else if (ch < 0x20) b.append(String.format("\\u%04X", (int) ch));
            else b.append(ch);
        }
        return b.append('"').toString();
    }
}
