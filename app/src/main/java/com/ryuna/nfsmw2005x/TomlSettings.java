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
 * bools, integers or strings; every other entry (comments, unknown tables,
 * arrays, inline tables, multi-line strings, floats, dates) is kept verbatim,
 * so what the native side writes (native/main.cpp app::Save) survives the
 * launcher's edits and back. Checked: keys (bare or quoted, dotted), table and
 * array-of-tables headers, a key or table defined twice (by its full path),
 * strings and escapes, integers, floats, bools, dates and times, junk after a
 * value. Inside an array or inline table only the brackets and strings are
 * checked, not the values.
 */
final class TomlSettings {
    /** One line (or one entry over lines) of the file; path null for a comment, a blank or a table header. */
    private static final class Line {
        String text;
        final List<Object> scope;  // the table it is in, by decoded path; an array of tables' element an Integer
        final List<Object> path;   // an entry's full decoded path (scope + its key's segments), else null
        final String lead;         // the entry's text up to its value ("  key = "), kept when the value is replaced
        Object value;              // Boolean, Long, String, or null for a value of another type
        Line(String text, List<Object> scope, List<Object> path, String lead, Object value) {
            this.text = text;
            this.scope = scope;
            this.path = path;
            this.lead = lead;
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
            } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
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

    /** key a path, its segments joined by dots: "bloom", "padmap.a" (the entry a in table padmap, however the file
     *  spells it); v a Boolean, an integer or a String. */
    void put(String key, Object v) {
        Object value = v instanceof Number ? (Object) ((Number) v).longValue() : v;
        if (!(value instanceof Boolean || value instanceof Long || value instanceof String))
            throw new IllegalArgumentException("not a bool, integer or string: " + key);
        Line l = find(key);
        if (l != null) {
            l.text = l.lead + format(value);
            l.value = value;
            return;
        }
        List<Object> path = path(key);
        List<Object> table = path.subList(0, path.size() - 1);
        // in the table's [header]; else with the file's dotted keys for it at the top ("padmap.a = ..."); else a new
        // [header] (a top-level key: before the first header)
        boolean header = false, dotted = false;
        for (Line o : lines) {
            if (o.path == null && isHeader(o.text) && o.scope.equals(table)) header = true;
            if (o.path != null && o.scope.isEmpty() && o.path.size() > table.size()
                    && o.path.subList(0, table.size()).equals(table)) dotted = true;
        }
        List<Object> scope = table.isEmpty() || header || !dotted ? new ArrayList<>(table) : new ArrayList<>();
        String lead = keyText(path.subList(scope.size(), path.size())) + " = ";
        lines.add(insertAt(scope), new Line(lead + format(value), scope, path, lead, value));
    }

    /** Drops every line that is exactly text (an old comment). */
    void removeLine(String text) {
        lines.removeIf(l -> l.path == null && l.text.equals(text));
    }

    /** A change to the file f as it is now: under the lock the game takes as well (native/main.cpp's app::Save, a
     *  POSIX record lock on f's ".lock" beside it), f read again, changed, written. Two editors at once (the launcher
     *  or the overlay and the game) then each keep the other's keys. */
    static synchronized TomlSettings update(File f, java.util.function.Consumer<TomlSettings> change)
            throws IOException {
        // synchronized: a process holds a file's lock once (a second channel's lock() throws), the threads take turns
        File lock = new File(f.getParentFile(), f.getName() + ".lock");
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(lock, "rw");
             java.nio.channels.FileLock held = r.getChannel().lock()) {
            TomlSettings t = load(f);
            change.accept(t);
            t.save(f);
            return t;
        }
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

    /** The API's dotted key as a path. */
    private static List<Object> path(String key) {
        return new ArrayList<>(java.util.Arrays.asList((Object[]) key.split("\\.", -1)));
    }

    private Line find(String key) {
        List<Object> path = path(key);
        for (Line l : lines)
            if (path.equals(l.path)) return l;
        return null;
    }

    /** Segments as a TOML key: bare where they can be, else quoted. */
    private static String keyText(List<Object> segs) {
        StringBuilder b = new StringBuilder();
        for (Object o : segs) {
            String s = (String) o;
            if (b.length() > 0) b.append('.');
            boolean bare = !s.isEmpty();
            for (int i = 0; i < s.length() && bare; i++) bare = isBareKeyChar(s.charAt(i));
            b.append(bare ? s : format(s));
        }
        return b.toString();
    }

    /** A new top-level entry goes before the first table header (and the blanks before it), a table's after its last
     *  entry; a table not in the file yet is added at the end. */
    private int insertAt(List<Object> table) {
        int header = -1;
        for (int i = 0; i < lines.size() && header < 0; i++) {
            Line l = lines.get(i);
            if (l.path != null || !isHeader(l.text)) continue;
            if (table.isEmpty() || l.scope.equals(table)) header = i;
        }
        if (table.isEmpty()) {
            int at = header < 0 ? lines.size() : header;
            while (at > 0 && lines.get(at - 1).text.trim().isEmpty()) at--;
            return at;
        }
        if (header < 0) {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).text.trim().isEmpty())
                lines.add(new Line("", new ArrayList<>(), null, null, null));
            lines.add(new Line("[" + keyText(table) + "]", table, null, null, null));
            return lines.size();
        }
        int at = header + 1;
        for (int i = header + 1; i < lines.size() && lines.get(i).scope.equals(table); i++)
            if (lines.get(i).path != null) at = i + 1;
        return at;
    }

    private static boolean isHeader(String text) {
        return text.trim().startsWith("[");
    }

    /** Builds the lines, an entry whose value spans lines (an array, a multi-line string) as one, and turns down a
     *  file outside the subset the class comment names. */
    private void parse(String s) {
        // every path defined so far, by decoded segments (an array of tables' element an Integer after its name):
        // what it is (a table by header 'h', by dotted key 'd' or as a header's parent 'p', a value 'v', an array of
        // tables 'a')
        java.util.Map<List<Object>, Character> defined = new java.util.HashMap<>();
        java.util.Map<List<Object>, Integer> elements = new java.util.HashMap<>();   // an array of tables: its count
        List<Object> scope = new ArrayList<>();     // the current table
        int p = 0, n = s.length();
        while (p < n) {
            int eol = eol(s, p);
            String raw = s.substring(p, eol);
            String t = raw.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                lines.add(new Line(stripCr(raw), scope, null, null, null));
                p = next(s, eol);
                continue;
            }
            if (t.startsWith("[")) {
                int q = p;
                while (s.charAt(q) == ' ' || s.charAt(q) == '\t') q++;
                boolean array = s.startsWith("[[", q);
                List<String> name = new ArrayList<>();
                q = key(s, q + (array ? 2 : 1), eol, name);
                if (!s.startsWith(array ? "]]" : "]", q)) throw new IllegalArgumentException("bad table header: " + raw);
                q += array ? 2 : 1;
                String rest = s.substring(q, eol).trim();
                if (!(rest.isEmpty() || rest.startsWith("#"))) throw new IllegalArgumentException("bad table header: " + raw);
                // the parents: tables (made here if new), an array of tables standing for its last element
                List<Object> path = new ArrayList<>();
                for (int i = 0; i < name.size() - 1; i++) path = step(defined, elements, path, name.get(i), 'p', raw);
                List<Object> full = with(path, name.get(name.size() - 1));
                Character was = defined.get(full);
                if (array) {
                    if (was != null && was != 'a') throw new IllegalArgumentException("not an array of tables: " + raw);
                    defined.put(full, 'a');
                    scope = with(full, elements.merge(full, 1, Integer::sum) - 1);
                } else {
                    // a table only its sub-tables' headers made may be opened once; any other is defined twice
                    if (was != null && was != 'p') throw new IllegalArgumentException("table twice: " + raw);
                    defined.put(full, 'h');
                    scope = full;
                }
                lines.add(new Line(stripCr(raw), scope, null, null, null));
                p = next(s, eol);
                continue;
            }
            // key = value, the value maybe running over lines
            List<String> name = new ArrayList<>();
            int q = key(s, p, eol, name);
            if (q >= eol || s.charAt(q) != '=') throw new IllegalArgumentException("no key = value: " + raw);
            q++;
            while (q < eol && (s.charAt(q) == ' ' || s.charAt(q) == '\t')) q++;
            if (q >= eol) throw new IllegalArgumentException("no value: " + raw);
            List<Object> path = scope;
            for (int i = 0; i < name.size() - 1; i++) path = step(defined, elements, path, name.get(i), 'd', raw);
            List<Object> full = with(path, name.get(name.size() - 1));
            if (defined.containsKey(full)) throw new IllegalArgumentException("defined twice: " + raw);
            defined.put(full, 'v');
            Object[] value = new Object[1];
            int end = value(s, q, value);
            // after the value: blanks, a comment, the line's end
            while (end < n && (s.charAt(end) == ' ' || s.charAt(end) == '\t')) end++;
            int lineEnd = eol(s, end);
            String rest = s.substring(end, lineEnd).trim();
            if (!rest.isEmpty() && !rest.startsWith("#")) throw new IllegalArgumentException("junk after the value: " + raw);
            lines.add(new Line(stripCr(s.substring(p, lineEnd)), scope, full, s.substring(p, q), value[0]));
            p = next(s, lineEnd);
        }
    }

    private static List<Object> with(List<Object> path, Object seg) {
        List<Object> l = new ArrayList<>(path);
        l.add(seg);
        return l;
    }

    /** Into the table seg under path: made if new (as kind 'p', a header's parent, or 'd', by a dotted key); an
     *  array of tables leads to its last element. A value there, or a header's table reopened by a dotted key, is
     *  an error. */
    private static List<Object> step(java.util.Map<List<Object>, Character> defined,
                                     java.util.Map<List<Object>, Integer> elements, List<Object> path, String seg,
                                     char kind, String raw) {
        List<Object> full = with(path, seg);
        Character was = defined.get(full);
        if (was == null) {
            defined.put(full, kind);
            return full;
        }
        if (was == 'v') throw new IllegalArgumentException("not a table: " + raw);
        if (was == 'a') {
            if (kind == 'd') throw new IllegalArgumentException("an array of tables in a dotted key: " + raw);
            return with(full, elements.get(full) - 1);
        }
        if (kind == 'd' && (was == 'h' || was == 'p')) throw new IllegalArgumentException("table reopened: " + raw);
        return full;
    }

    /** A key at s[p] (blanks before it, a dotted one's segments bare or quoted) into segs; its end, past the
     *  blanks after it. */
    private static int key(String s, int p, int eol, List<String> segs) {
        int q = p;
        while (true) {
            while (q < eol && (s.charAt(q) == ' ' || s.charAt(q) == '\t')) q++;
            if (q >= eol) throw new IllegalArgumentException("no key at " + p);
            char c = s.charAt(q);
            if (c == '"') {
                StringBuilder b = new StringBuilder();
                q = basic(s, q, b);
                segs.add(b.toString());
            } else if (c == '\'') {
                int close = s.indexOf('\'', q + 1);
                if (close < 0 || close > eol) throw new IllegalArgumentException("unclosed key at " + q);
                segs.add(s.substring(q + 1, close));
                q = close + 1;
            } else {
                int k = q;
                while (k < eol && isBareKeyChar(s.charAt(k))) k++;
                if (k == q) throw new IllegalArgumentException("bad key at " + q);
                segs.add(s.substring(q, k));
                q = k;
            }
            while (q < eol && (s.charAt(q) == ' ' || s.charAt(q) == '\t')) q++;
            if (q < eol && s.charAt(q) == '.') { q++; continue; }
            return q;
        }
    }

    private static boolean isBareKeyChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
    }

    private static int eol(String s, int p) {
        int e = s.indexOf('\n', p);
        return e < 0 ? s.length() : e;
    }

    private static int next(String s, int eol) { return Math.min(s.length(), eol + 1); }

    private static String stripCr(String s) { return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s; }

    // TOML's numbers: no leading zeros, an underscore only between two digits
    private static final String DEC = "(0|[1-9](_?[0-9])*)", DIGITS = "[0-9](_?[0-9])*";
    private static final java.util.regex.Pattern INT = java.util.regex.Pattern.compile("[+-]?" + DEC);
    private static final java.util.regex.Pattern RADIX = java.util.regex.Pattern.compile(
        "0x[0-9A-Fa-f](_?[0-9A-Fa-f])*|0o[0-7](_?[0-7])*|0b[01](_?[01])*");
    private static final java.util.regex.Pattern FLOAT = java.util.regex.Pattern.compile(
        "[+-]?(inf|nan)|[+-]?" + DEC + "(\\." + DIGITS + "([eE][+-]?" + DIGITS + ")?|[eE][+-]?" + DIGITS + ")");
    // a local time needs its seconds (TOML 1.0, as toml++ reads it); a date is checked against the calendar after
    private static final String TIME = "([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](\\.[0-9]+)?";
    private static final java.util.regex.Pattern DATE = java.util.regex.Pattern.compile(
        "([0-9]{4})-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])([Tt ]" + TIME
        + "([Zz]|[+-]([01][0-9]|2[0-3]):[0-5][0-9])?)?");
    private static final java.util.regex.Pattern LOCAL_TIME = java.util.regex.Pattern.compile(TIME);

    private static boolean isDate(String bare) {
        if (LOCAL_TIME.matcher(bare).matches()) return true;
        java.util.regex.Matcher m = DATE.matcher(bare);
        if (!m.matches()) return false;
        int year = Integer.parseInt(m.group(1)), month = Integer.parseInt(m.group(2)), day = Integer.parseInt(m.group(3));
        boolean leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
        int days = month == 2 ? (leap ? 29 : 28) : month == 4 || month == 6 || month == 9 || month == 11 ? 30 : 31;
        return day <= days;
    }

    /** The value at s[p]: its end, and in out[0] a Boolean, Long or String, or null for a value kept as it is
     *  (an array, an inline table, a multi-line string, a float, a date). */
    private static int value(String s, int p, Object[] out) {
        char c = s.charAt(p);
        if (s.startsWith("\"\"\"", p) || s.startsWith("'''", p)) return multiline(s, p);
        if (c == '"') {
            StringBuilder b = new StringBuilder();
            int e = basic(s, p, b);
            out[0] = b.toString();
            return e;
        }
        if (c == '\'') {
            int close = s.indexOf('\'', p + 1);
            if (close < 0 || close > eol(s, p)) throw new IllegalArgumentException("unclosed string at " + p);
            out[0] = s.substring(p + 1, close);
            return close + 1;
        }
        if (c == '[' || c == '{') return nested(s, p);
        int e = p;
        while (e < s.length() && " \t\r\n,#]}".indexOf(s.charAt(e)) < 0) e++;
        String bare = s.substring(p, e);
        // a date and its time, apart
        if (bare.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") && e + 1 < s.length() && s.charAt(e) == ' '
                && Character.isDigit(s.charAt(e + 1))) {
            int f = e + 1;
            while (f < s.length() && " \t\r\n,#]}".indexOf(s.charAt(f)) < 0) f++;
            bare = s.substring(p, f);
            e = f;
        }
        if (bare.equals("true")) out[0] = Boolean.TRUE;
        else if (bare.equals("false")) out[0] = Boolean.FALSE;
        else if (INT.matcher(bare).matches()) out[0] = integer(bare.replace("_", "").replace("+", ""), 10);
        else if (RADIX.matcher(bare).matches()) {
            char r = bare.charAt(1);
            out[0] = integer(bare.substring(2).replace("_", ""), r == 'x' ? 16 : r == 'o' ? 8 : 2);
        } else if (!FLOAT.matcher(bare).matches() && !isDate(bare))
            throw new IllegalArgumentException("not a value: " + bare);
        return e;
    }

    /** A 64-bit integer, as TOML's are; one beyond it is an error, as in toml++. */
    private static long integer(String digits, int radix) {
        try {
            return Long.parseLong(digits, radix);
        } catch (NumberFormatException x) {
            throw new IllegalArgumentException("integer out of range: " + digits);
        }
    }

    /** """...""" or '''...''', which may hold up to two more quotes before the closing three. */
    private static int multiline(String s, int p) {
        char q = s.charAt(p);
        int i = p + 3;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (q == '"' && ch == '\\') { i += 2; continue; }
            if (s.startsWith(q == '"' ? "\"\"\"" : "'''", i)) {
                int e = i + 3;
                for (int k = 0; k < 2 && e < s.length() && s.charAt(e) == q; k++) e++;
                return e;
            }
            i++;
        }
        throw new IllegalArgumentException("unclosed multi-line string at " + p);
    }

    /** An array or inline table, brackets balanced, strings and comments in it skipped. */
    private static int nested(String s, int p) {
        int depth = 0;
        int i = p;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (s.startsWith("\"\"\"", i) || s.startsWith("'''", i)) { i = multiline(s, i); continue; }
            if (ch == '"') { i = basic(s, i, new StringBuilder()); continue; }
            if (ch == '\'') {
                int close = s.indexOf('\'', i + 1);
                if (close < 0 || close > eol(s, i)) throw new IllegalArgumentException("unclosed string at " + i);
                i = close + 1;
                continue;
            }
            if (ch == '#') { i = eol(s, i); continue; }
            if (ch == '[' || ch == '{') depth++;
            else if (ch == ']' || ch == '}') {
                if (--depth == 0) return i + 1;
                if (depth < 0) break;
            }
            i++;
        }
        throw new IllegalArgumentException("unclosed array or table at " + p);
    }

    /** A one-line basic string at s[p] into b; its end. */
    private static int basic(String s, int p, StringBuilder b) {
        for (int i = p + 1; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"') return i + 1;
            if (ch == '\n') break;
            if (ch != '\\') { b.append(ch); continue; }
            if (++i >= s.length()) break;
            char e = s.charAt(i);
            switch (e) {
                case 'n': b.append('\n'); break;
                case 't': b.append('\t'); break;
                case 'r': b.append('\r'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case '"': case '\\': b.append(e); break;
                case 'u': case 'U': {
                    int len = e == 'u' ? 4 : 8;
                    if (i + len >= s.length()) throw new IllegalArgumentException("bad escape at " + i);
                    try { b.appendCodePoint(Integer.parseInt(s.substring(i + 1, i + 1 + len), 16)); }
                    catch (IllegalArgumentException x) { throw new IllegalArgumentException("bad escape at " + i); }
                    i += len;
                    break;
                }
                default: throw new IllegalArgumentException("bad escape at " + i);
            }
        }
        throw new IllegalArgumentException("unclosed string at " + p);
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
