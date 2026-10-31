package com.ryuna.nfsmw2005x;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The touch controls' look ("Ring"): a dark fill in a border of the button's colour with a faint inner ring, a
 * white line icon on a 24-unit grid and a small badge naming the pad button on the top edge. The icons are the
 * mockup's SVG (.work/nfsmw_buttons_mockup.html, its `I` table), parsed by a small SVG-subset reader that writes
 * to a PathSink, so the parsing runs on the JVM without android.graphics.
 */
final class ButtonArt {
    private ButtonArt() { }

    enum Icon { NOS, EBRAKE, SPEEDBREAKER, LOOKBACK, CAMERA, GAS, BRAKE, GEAR, START, BACK, MENU, LEFT, RIGHT,
        ZOOM_IN, ZOOM_OUT, ACCEPT, RESTART, STATS, EXIT, CANCEL }

    // the pad's colours: A B X Y, grey for the pedals, shoulders and system buttons, White's white
    static final int COLOR_A = 0xFF3FB950, COLOR_B = 0xFFE5534B, COLOR_X = 0xFF4493F8, COLOR_Y = 0xFFE3B341,
            COLOR_GREY = 0xFF9AA0A6, COLOR_WHITE = 0xFFE8E8E8;
    /** drawDpad's pressedMask: the XBOX_GAMEPAD_DPAD_* bits. */
    static final int DPAD_UP = 0x01, DPAD_DOWN = 0x02, DPAD_LEFT = 0x04, DPAD_RIGHT = 0x08;

    private static final int FILL = 0x8C0C0D10;           // rgba(12,13,16,.55)
    private static final int BADGE_FILL = 0xFF15171B;
    private static final float INNER_ALPHA = .28f;

    // ------------------------------------------------------------------ icons

    /** The mockup's icon markup, verbatim; one entry per Icon. */
    static String iconSvg(Icon i) {
        switch (i) {
            case NOS: return "<g transform=\"rotate(32 12 12)\"><rect x=\"6.5\" y=\"6.5\" width=\"11\" height=\"16\" rx=\"3.5\"/><path d=\"M10 6.5V3.8h4v2.7M8.8 3.3h6.4\"/><path d=\"M13.4 9.6l-4 5.4h3l-1.9 5.1 4.3-5.9h-3z\" fill=\"currentColor\" stroke-width=\"1.2\"/></g>";
            case EBRAKE: return "<path d=\"M3.5 20.5h17\"/><circle cx=\"7.5\" cy=\"17\" r=\"2\"/><path d=\"M9 15.5l4.5-4.5\"/><path d=\"M13.5 11l5-5\" stroke-width=\"4.5\"/>";
            case SPEEDBREAKER: return "<circle cx=\"13\" cy=\"13.5\" r=\"6.5\"/><path d=\"M13 13.5l2.6-2.6M11 3.5h4M13 3.5v3.5\"/><path d=\"M2.5 10.5h3M2 13.5h2.5M2.5 16.5h3\"/>";
            case LOOKBACK: return "<rect x=\"2.5\" y=\"8\" width=\"19\" height=\"9.5\" rx=\"4.75\"/><path d=\"M12 8V4.5M9.5 4.5h5\"/><path d=\"M7 14.5l3-3.5M11 14.5l2-2.4\"/>";
            case CAMERA: return "<rect x=\"2.5\" y=\"7\" width=\"13\" height=\"10\" rx=\"2.2\" fill=\"currentColor\"/><path d=\"M16.5 10.6l5-3.1v9l-5-3.1z\" fill=\"currentColor\"/>";
            case GAS: return "<path d=\"M4 12.5l8-6.5 8 6.5M4 19.5l8-6.5 8 6.5\"/>";
            case BRAKE: return "<path d=\"M4.5 8h15M4.5 12.5h15M4.5 17h15\"/>";
            case GEAR: return "<circle cx=\"7\" cy=\"5\" r=\"1.6\"/><circle cx=\"12\" cy=\"5\" r=\"1.6\"/><circle cx=\"17\" cy=\"5\" r=\"1.6\"/><circle cx=\"7\" cy=\"19\" r=\"1.6\"/><circle cx=\"12\" cy=\"19\" r=\"1.6\"/><path d=\"M7 6.6v10.8M12 6.6v10.8M17 6.6V12H7\"/>";
            case START: return "<path d=\"M9 6v12M15 6v12\"/>";
            case BACK: return "<path d=\"M5 12h14M5 12l5-5M5 12l5 5\"/>";
            case MENU: return "<path d=\"M5 7h14M5 12h14M5 17h14\"/>";
            case LEFT: return "<path d=\"M15 5l-7 7 7 7\"/>";
            case RIGHT: return "<path d=\"M9 5l7 7-7 7\"/>";
            case ZOOM_IN: return "<circle cx=\"10.5\" cy=\"10.5\" r=\"6\"/><path d=\"M15 15l5 5M8 10.5h5M10.5 8v5\"/>";
            case ZOOM_OUT: return "<circle cx=\"10.5\" cy=\"10.5\" r=\"6\"/><path d=\"M15 15l5 5M8 10.5h5\"/>";
            case ACCEPT: return "<path d=\"M5 12.5l4.5 4.5L19 7\"/>";
            case RESTART: return "<path d=\"M5 12a7 7 0 1 0 2-5\"/><path d=\"M5 4v4h4\"/>";
            case STATS: return "<path d=\"M5 20V12M10 20V6M15 20v-9M20 20V9\"/>";
            case EXIT: return "<path d=\"M14 4h5v16h-5\"/><path d=\"M4 12h10M10 8l4 4-4 4\"/>";
            case CANCEL: return "<path d=\"M6 6l12 12M18 6L6 18\"/>";
        }
        throw new IllegalArgumentException(String.valueOf(i));
    }

    /** The icon for a menu prompt's label (the game's or the overlay's, any case), null if none fits. */
    static Icon iconForPrompt(String label) {
        if (label == null) return null;
        switch (label.trim().toUpperCase(Locale.ROOT)) {
            case "ACCEPT": case "CONTINUE": case "OK": return Icon.ACCEPT;
            case "BACK": return Icon.BACK;
            case "RESTART": case "RESTART RACE": return Icon.RESTART;
            case "STATISTICS": case "STATS": case "GAME STATS": case "RACE STATS": return Icon.STATS;
            case "EXIT": case "QUIT": return Icon.EXIT;
            case "CANCEL": return Icon.CANCEL;
            case "ZOOM IN": return Icon.ZOOM_IN;
            case "ZOOM OUT": return Icon.ZOOM_OUT;
            default: return null;
        }
    }

    /** What the SVG reader writes: one begin per element (rect, circle, path), then its outline. */
    interface PathSink {
        /** fill: drawn filled as well as stroked; strokeWidth: the element's own, NaN for the icon's. */
        void begin(boolean fill, float strokeWidth);
        void moveTo(float x, float y);
        void lineTo(float x, float y);
        void cubicTo(float x1, float y1, float x2, float y2, float x, float y);
        void quadTo(float x1, float y1, float x, float y);
        void close();
    }

    private static final Pattern ATTR = Pattern.compile("([A-Za-z][\\w-]*)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern XFORM = Pattern.compile("(rotate|translate|scale)\\s*\\(([^)]*)\\)");
    private static final float KAPPA = .5522848f;          // quarter circle as one cubic

    /**
     * Reads the mockup's subset of SVG (g, rect, circle and path elements; transform rotate/translate/scale;
     * path commands M L H V C S Q T A Z, either case) into out. Returns the number of segments written.
     */
    static int parse(String svg, PathSink out) {
        Writer w = new Writer(out);
        List<float[]> stack = new ArrayList<>();
        float[] m = {1, 0, 0, 1, 0, 0};
        int i = 0, n = svg.length();
        while ((i = svg.indexOf('<', i)) >= 0) {
            int end = svg.indexOf('>', i);
            if (end < 0) throw new IllegalArgumentException("unclosed tag at " + i);
            String tag = svg.substring(i + 1, end).trim();
            i = end + 1;
            if (tag.startsWith("/")) {
                if (tag.substring(1).trim().equals("g") && !stack.isEmpty()) m = stack.remove(stack.size() - 1);
                continue;
            }
            boolean selfClosed = tag.endsWith("/");
            int sp = 0;
            while (sp < tag.length() && Character.isLetter(tag.charAt(sp))) sp++;
            String name = tag.substring(0, sp);
            String fill = null, sw = null, xf = null, d = null;
            float x = 0, y = 0, wd = 0, ht = 0, rx = 0, cx = 0, cy = 0, r = 0;
            Matcher a = ATTR.matcher(tag);
            while (a.find()) {
                String k = a.group(1), v = a.group(2);
                switch (k) {
                    case "fill": fill = v; break;
                    case "stroke-width": sw = v; break;
                    case "transform": xf = v; break;
                    case "d": d = v; break;
                    case "x": x = Float.parseFloat(v); break;
                    case "y": y = Float.parseFloat(v); break;
                    case "width": wd = Float.parseFloat(v); break;
                    case "height": ht = Float.parseFloat(v); break;
                    case "rx": rx = Float.parseFloat(v); break;
                    case "cx": cx = Float.parseFloat(v); break;
                    case "cy": cy = Float.parseFloat(v); break;
                    case "r": r = Float.parseFloat(v); break;
                    default: break;
                }
            }
            float[] em = xf == null ? m : mul(m, transform(xf));
            if (name.equals("g")) {
                if (!selfClosed) { stack.add(m); m = em; }
                continue;
            }
            boolean filled = fill != null && !fill.equals("none");
            float width = sw == null ? Float.NaN : Float.parseFloat(sw);
            switch (name) {
                case "path":
                    if (d == null) throw new IllegalArgumentException("path without d");
                    w.begin(em, filled, width);
                    path(d, w);
                    break;
                case "rect":
                    w.begin(em, filled, width);
                    rect(w, x, y, wd, ht, rx);
                    break;
                case "circle":
                    w.begin(em, filled, width);
                    ellipse(w, cx, cy, r, r);
                    break;
                default:
                    throw new IllegalArgumentException("unsupported element <" + name + ">");
            }
        }
        return w.segments;
    }

    /** Maps points through the element's transform and counts the segments. */
    private static final class Writer {
        final PathSink out;
        float[] m;
        int segments;
        Writer(PathSink out) { this.out = out; }
        void begin(float[] m, boolean fill, float sw) {
            this.m = m;
            // a stroke width scales with the transform (only rotations here, so by its determinant's root)
            float s = (float) Math.sqrt(Math.abs(m[0] * m[3] - m[1] * m[2]));
            out.begin(fill, Float.isNaN(sw) ? sw : sw * s);
        }
        float tx(float x, float y) { return m[0] * x + m[2] * y + m[4]; }
        float ty(float x, float y) { return m[1] * x + m[3] * y + m[5]; }
        void moveTo(float x, float y) { out.moveTo(tx(x, y), ty(x, y)); }
        void lineTo(float x, float y) { out.lineTo(tx(x, y), ty(x, y)); segments++; }
        void cubicTo(float x1, float y1, float x2, float y2, float x, float y) {
            out.cubicTo(tx(x1, y1), ty(x1, y1), tx(x2, y2), ty(x2, y2), tx(x, y), ty(x, y));
            segments++;
        }
        void quadTo(float x1, float y1, float x, float y) {
            out.quadTo(tx(x1, y1), ty(x1, y1), tx(x, y), ty(x, y));
            segments++;
        }
        void close() { out.close(); }
    }

    private static float[] mul(float[] p, float[] q) {
        return new float[] {
            p[0] * q[0] + p[2] * q[1], p[1] * q[0] + p[3] * q[1],
            p[0] * q[2] + p[2] * q[3], p[1] * q[2] + p[3] * q[3],
            p[0] * q[4] + p[2] * q[5] + p[4], p[1] * q[4] + p[3] * q[5] + p[5],
        };
    }

    private static float[] transform(String s) {
        float[] m = {1, 0, 0, 1, 0, 0};
        Matcher t = XFORM.matcher(s);
        while (t.find()) {
            String[] a = t.group(2).trim().split("[\\s,]+");
            float[] v = new float[a.length];
            for (int k = 0; k < a.length; k++) v[k] = Float.parseFloat(a[k]);
            float[] q;
            switch (t.group(1)) {
                case "translate": q = new float[] {1, 0, 0, 1, v[0], v.length > 1 ? v[1] : 0}; break;
                case "scale": q = new float[] {v[0], 0, 0, v.length > 1 ? v[1] : v[0], 0, 0}; break;
                default: {
                    double rad = Math.toRadians(v[0]);
                    float c = (float) Math.cos(rad), sn = (float) Math.sin(rad);
                    float ox = v.length > 2 ? v[1] : 0, oy = v.length > 2 ? v[2] : 0;
                    q = new float[] {c, sn, -sn, c, ox - c * ox + sn * oy, oy - sn * ox - c * oy};
                }
            }
            m = mul(m, q);
        }
        return m;
    }

    private static void rect(Writer w, float x, float y, float wd, float ht, float rx) {
        rx = Math.min(rx, Math.min(wd, ht) / 2);
        if (rx <= 0) {
            w.moveTo(x, y); w.lineTo(x + wd, y); w.lineTo(x + wd, y + ht); w.lineTo(x, y + ht); w.close();
            return;
        }
        float k = rx * KAPPA, r = x + wd, b = y + ht;
        w.moveTo(x + rx, y);
        w.lineTo(r - rx, y);
        w.cubicTo(r - rx + k, y, r, y + rx - k, r, y + rx);
        w.lineTo(r, b - rx);
        w.cubicTo(r, b - rx + k, r - rx + k, b, r - rx, b);
        w.lineTo(x + rx, b);
        w.cubicTo(x + rx - k, b, x, b - rx + k, x, b - rx);
        w.lineTo(x, y + rx);
        w.cubicTo(x, y + rx - k, x + rx - k, y, x + rx, y);
        w.close();
    }

    private static void ellipse(Writer w, float cx, float cy, float rx, float ry) {
        float kx = rx * KAPPA, ky = ry * KAPPA;
        w.moveTo(cx + rx, cy);
        w.cubicTo(cx + rx, cy + ky, cx + kx, cy + ry, cx, cy + ry);
        w.cubicTo(cx - kx, cy + ry, cx - rx, cy + ky, cx - rx, cy);
        w.cubicTo(cx - rx, cy - ky, cx - kx, cy - ry, cx, cy - ry);
        w.cubicTo(cx + kx, cy - ry, cx + rx, cy - ky, cx + rx, cy);
        w.close();
    }

    /** Path data's numbers: "4.3-5.9" and "1.5.5" are two each; arc flags are one digit. */
    private static final class Numbers {
        final String s;
        int i;
        Numbers(String s) { this.s = s; }
        void skip() {
            while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == ',' || s.charAt(i) == '\t'
                    || s.charAt(i) == '\n' || s.charAt(i) == '\r')) i++;
        }
        boolean hasNumber() {
            skip();
            if (i >= s.length()) return false;
            char c = s.charAt(i);
            return c == '-' || c == '+' || c == '.' || (c >= '0' && c <= '9');
        }
        float next() {
            skip();
            int st = i;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            boolean dot = false, digits = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') { digits = true; i++; }
                else if (c == '.' && !dot) { dot = true; i++; }
                else break;
            }
            if (digits && i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                int e = i + 1;
                if (e < s.length() && (s.charAt(e) == '-' || s.charAt(e) == '+')) e++;
                if (e < s.length() && Character.isDigit(s.charAt(e))) {
                    i = e;
                    while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
                }
            }
            if (!digits) throw new IllegalArgumentException("number expected at " + st + " in \"" + s + "\"");
            return Float.parseFloat(s.substring(st, i));
        }
        boolean flag() {
            skip();
            char c = i < s.length() ? s.charAt(i) : '?';
            if (c != '0' && c != '1') throw new IllegalArgumentException("arc flag expected at " + i);
            i++;
            return c == '1';
        }
    }

    private static void path(String d, Writer w) {
        Numbers p = new Numbers(d);
        float x = 0, y = 0, sx = 0, sy = 0, lcx = 0, lcy = 0;   // pen, subpath start, last control point
        char prev = ' ';
        char cmd = ' ';
        while (true) {
            p.skip();
            if (p.i >= d.length()) break;
            char c = d.charAt(p.i);
            if (Character.isLetter(c)) { cmd = c; p.i++; }
            else if (cmd == ' ') throw new IllegalArgumentException("path data must start with a command: " + d);
            boolean rel = Character.isLowerCase(cmd);
            float ox = rel ? x : 0, oy = rel ? y : 0;
            switch (Character.toUpperCase(cmd)) {
                case 'M':
                    x = ox + p.next(); y = oy + p.next();
                    sx = x; sy = y;
                    w.moveTo(x, y);
                    cmd = rel ? 'l' : 'L';                // further pairs are lines
                    break;
                case 'L':
                    x = ox + p.next(); y = oy + p.next();
                    w.lineTo(x, y);
                    break;
                case 'H':
                    x = ox + p.next();
                    w.lineTo(x, y);
                    break;
                case 'V':
                    y = oy + p.next();
                    w.lineTo(x, y);
                    break;
                case 'C': {
                    float x1 = ox + p.next(), y1 = oy + p.next(), x2 = ox + p.next(), y2 = oy + p.next();
                    x = ox + p.next(); y = oy + p.next();
                    w.cubicTo(x1, y1, x2, y2, x, y);
                    lcx = x2; lcy = y2;
                    break;
                }
                case 'S': {
                    boolean after = "CcSs".indexOf(prev) >= 0;
                    float x1 = after ? 2 * x - lcx : x, y1 = after ? 2 * y - lcy : y;
                    float x2 = ox + p.next(), y2 = oy + p.next();
                    x = ox + p.next(); y = oy + p.next();
                    w.cubicTo(x1, y1, x2, y2, x, y);
                    lcx = x2; lcy = y2;
                    break;
                }
                case 'Q': {
                    float x1 = ox + p.next(), y1 = oy + p.next();
                    x = ox + p.next(); y = oy + p.next();
                    w.quadTo(x1, y1, x, y);
                    lcx = x1; lcy = y1;
                    break;
                }
                case 'T': {
                    boolean after = "QqTt".indexOf(prev) >= 0;
                    float x1 = after ? 2 * x - lcx : x, y1 = after ? 2 * y - lcy : y;
                    x = ox + p.next(); y = oy + p.next();
                    w.quadTo(x1, y1, x, y);
                    lcx = x1; lcy = y1;
                    break;
                }
                case 'A': {
                    float rx = p.next(), ry = p.next(), rot = p.next();
                    boolean large = p.flag(), sweep = p.flag();
                    float ex = ox + p.next(), ey = oy + p.next();
                    arc(w, x, y, rx, ry, rot, large, sweep, ex, ey);
                    x = ex; y = ey;
                    break;
                }
                case 'Z':
                    w.close();
                    x = sx; y = sy;
                    break;
                default:
                    throw new IllegalArgumentException("unsupported path command " + cmd);
            }
            prev = cmd;
            if (Character.toUpperCase(cmd) == 'Z') { cmd = ' '; if (p.hasNumber()) throw new IllegalArgumentException("numbers after Z"); }
            else if (!p.hasNumber()) {
                p.skip();
                if (p.i < d.length() && !Character.isLetter(d.charAt(p.i)))
                    throw new IllegalArgumentException("bad path data at " + p.i + ": " + d);
            }
        }
    }

    /** SVG's endpoint arc as cubics, a quarter turn at most each (SVG 1.1 appendix F.6). */
    private static void arc(Writer w, float x0, float y0, float rx, float ry, float rotDeg, boolean large,
                            boolean sweep, float x, float y) {
        if (x0 == x && y0 == y) return;
        if (rx == 0 || ry == 0) { w.lineTo(x, y); return; }
        double phi = Math.toRadians(rotDeg), cos = Math.cos(phi), sin = Math.sin(phi);
        double dx2 = (x0 - x) / 2.0, dy2 = (y0 - y) / 2.0;
        double x1p = cos * dx2 + sin * dy2, y1p = -sin * dx2 + cos * dy2;
        double arx = Math.abs(rx), ary = Math.abs(ry);
        double lambda = x1p * x1p / (arx * arx) + y1p * y1p / (ary * ary);
        if (lambda > 1) { double s = Math.sqrt(lambda); arx *= s; ary *= s; }
        double num = arx * arx * ary * ary - arx * arx * y1p * y1p - ary * ary * x1p * x1p;
        double den = arx * arx * y1p * y1p + ary * ary * x1p * x1p;
        double coef = (large == sweep ? -1 : 1) * Math.sqrt(Math.max(0, num / den));
        double cxp = coef * arx * y1p / ary, cyp = -coef * ary * x1p / arx;
        double cx = cos * cxp - sin * cyp + (x0 + x) / 2.0, cy = sin * cxp + cos * cyp + (y0 + y) / 2.0;
        double ux = (x1p - cxp) / arx, uy = (y1p - cyp) / ary, vx = (-x1p - cxp) / arx, vy = (-y1p - cyp) / ary;
        double th = Math.atan2(uy, ux);
        double dth = Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy);
        if (!sweep && dth > 0) dth -= 2 * Math.PI;
        if (sweep && dth < 0) dth += 2 * Math.PI;
        int segs = (int) Math.ceil(Math.abs(dth) / (Math.PI / 2) - 1e-9);
        double delta = dth / segs, t = 4.0 / 3.0 * Math.tan(delta / 4);
        for (int k = 0; k < segs; k++) {
            double a1 = th + k * delta, a2 = a1 + delta;
            double c1 = Math.cos(a1), s1 = Math.sin(a1), c2 = Math.cos(a2), s2 = Math.sin(a2);
            double p1x = c1 - t * s1, p1y = s1 + t * c1, p2x = c2 + t * s2, p2y = s2 - t * c2;
            float ex = k == segs - 1 ? x : (float) (cx + arx * c2 * cos - ary * s2 * sin);
            float ey = k == segs - 1 ? y : (float) (cy + arx * c2 * sin + ary * s2 * cos);
            w.cubicTo((float) (cx + arx * p1x * cos - ary * p1y * sin), (float) (cy + arx * p1x * sin + ary * p1y * cos),
                    (float) (cx + arx * p2x * cos - ary * p2y * sin), (float) (cy + arx * p2x * sin + ary * p2y * cos),
                    ex, ey);
        }
    }

    // --------------------------------------------------------- android paths

    /** An icon's elements as Paths on the 24 grid. */
    private static final class Glyph {
        final List<Path> paths = new ArrayList<>();
        final List<Boolean> filled = new ArrayList<>();
        final List<Float> widths = new ArrayList<>();
        final Path all = new Path();
    }

    private static final EnumMap<Icon, Glyph> GLYPHS = new EnumMap<>(Icon.class);

    private static Glyph glyph(Icon icon) {
        Glyph g = GLYPHS.get(icon);
        if (g != null) return g;
        final Glyph ng = new Glyph();
        parse(iconSvg(icon), new PathSink() {
            Path p;
            @Override public void begin(boolean fill, float strokeWidth) {
                p = new Path();
                ng.paths.add(p); ng.filled.add(fill); ng.widths.add(strokeWidth);
            }
            @Override public void moveTo(float x, float y) { p.moveTo(x, y); }
            @Override public void lineTo(float x, float y) { p.lineTo(x, y); }
            @Override public void cubicTo(float x1, float y1, float x2, float y2, float x, float y) { p.cubicTo(x1, y1, x2, y2, x, y); }
            @Override public void quadTo(float x1, float y1, float x, float y) { p.quadTo(x1, y1, x, y); }
            @Override public void close() { p.close(); }
        });
        for (Path p : ng.paths) ng.all.addPath(p);
        GLYPHS.put(icon, ng);
        return ng;
    }

    /** The icon's outline on the 24-unit grid (every element), cached. */
    static Path iconPath(Icon i) {
        return glyph(i).all;
    }

    // ---------------------------------------------------------------- drawing

    /** Paints and scratch shapes, made on first draw (the JVM tests never get here). */
    private static final class P {
        static final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        static final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        static final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        static final Paint.FontMetrics fm = new Paint.FontMetrics();
        static final Path path = new Path();
        static final RectF rect = new RectF();
        static {
            fill.setStyle(Paint.Style.FILL);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        }
    }

    /** color's own alpha times a (a may pass 1: a pressed fill), the product clamped to 0..255. */
    static int scaledAlpha(int color, float a) {
        return Math.max(0, Math.min(255, Math.round(((color >>> 24) & 0xFF) * a)));
    }

    private static int alpha(int color, float a) {
        return (scaledAlpha(color, a) << 24) | (color & 0xFFFFFF);
    }

    /** The icons and badge letters stay a little clearer than the frames at low opacity. */
    private static float ink(float opacity) {
        return Math.min(1f, opacity + .15f);
    }

    private static void stroke(int color, float width) {
        P.stroke.setColor(color);
        P.stroke.setStrokeWidth(width);
    }

    private static void fill(int color) {
        P.fill.setColor(color);
    }

    /** The control's fill alpha: pressed half as opaque again, past the opacity setting if need be. */
    static int bodyAlpha(boolean pressed, float opacity) {
        return scaledAlpha(FILL, (pressed ? 1.5f : 1f) * opacity);
    }

    private static int body(boolean pressed, float opacity) {
        return (bodyAlpha(pressed, opacity) << 24) | (FILL & 0xFFFFFF);
    }

    /** icon, size px square, centred on (cx, cy), lines baseWidth grid units wide. */
    private static void icon(Canvas c, Icon icon, float cx, float cy, float size, float baseWidth, float opacity) {
        if (icon == null) return;
        Glyph g = glyph(icon);
        int white = alpha(0xFFFFFFFF, ink(opacity));
        c.save();
        c.translate(cx - size / 2, cy - size / 2);
        c.scale(size / 24f, size / 24f);
        for (int k = 0; k < g.paths.size(); k++) {
            Path p = g.paths.get(k);
            if (g.filled.get(k)) { fill(white); c.drawPath(p, P.fill); }
            float w = g.widths.get(k);
            stroke(white, Float.isNaN(w) ? baseWidth : w);
            c.drawPath(p, P.stroke);
        }
        c.restore();
    }

    private static void text(Canvas c, String s, float x, float y, float size, float opacity) {
        P.text.setTextSize(size);
        P.text.setColor(alpha(0xFFFFFFFF, ink(opacity)));
        P.text.getFontMetrics(P.fm);
        c.drawText(s, x, y - (P.fm.ascent + P.fm.descent) * .5f, P.text);
    }

    /** The pad button's letter in a dark disc on the control's top edge; a blank badge is the disc alone. */
    private static void badge(Canvas c, float x, float y, float r, int color, String s, float opacity) {
        if (s == null || s.isEmpty()) return;
        fill(alpha(BADGE_FILL, opacity));
        c.drawCircle(x, y, r, P.fill);
        stroke(alpha(color, opacity), r * .18f);
        c.drawCircle(x, y, r, P.stroke);
        if (!s.trim().isEmpty()) text(c, s, x, y + r * .06f, r * 1.1f, opacity);
    }

    /**
     * A round button: the ring, its icon and its badge. With no icon the badge is the label instead, in the
     * middle (the pad letters of the XBOX layout).
     */
    static void drawRound(Canvas c, float cx, float cy, float r, int color, Icon icon, String badge,
                          boolean pressed, float opacity) {
        float d = 2 * r, sw = d * .05f * (pressed ? 1.4f : 1f), sw0 = d * .05f;
        fill(body(pressed, opacity));
        c.drawCircle(cx, cy, r - sw0, P.fill);
        stroke(alpha(color, opacity), sw);
        c.drawCircle(cx, cy, r - sw0, P.stroke);
        stroke(alpha(color, INNER_ALPHA * opacity), sw0 * .6f);
        c.drawCircle(cx, cy, r - sw0 * 2.6f, P.stroke);
        if (icon != null) {
            icon(c, icon, cx, cy, d * .5f, 2f, opacity);
            badge(c, cx, cy - r + d * .03f, d * .17f, color, badge, opacity);
        } else if (badge != null && !badge.trim().isEmpty()) {
            text(c, badge, cx, cy + d * .04f, d * .36f, opacity);
        }
    }

    /** A round button with a letter in the middle and a badge on its top edge (a menu prompt no icon fits). */
    static void drawRoundLetter(Canvas c, float cx, float cy, float r, int color, String letter, String badge,
                                boolean pressed, float opacity) {
        drawRound(c, cx, cy, r, color, null, letter, pressed, opacity);
        float d = 2 * r;
        badge(c, cx, cy - r + d * .03f, d * .17f, color, badge, opacity);
    }

    /** Builds the mockup's roundPoly: straight sides, each corner a quadratic of up to r along both sides. */
    private static void roundPoly(Path p, float[] pts, float r) {
        p.reset();
        int n = pts.length / 2;
        for (int k = 0; k < n; k++) {
            float px = pts[2 * k], py = pts[2 * k + 1];
            int a = (k - 1 + n) % n, b = (k + 1) % n;
            float v1x = pts[2 * a] - px, v1y = pts[2 * a + 1] - py, v2x = pts[2 * b] - px, v2y = pts[2 * b + 1] - py;
            float l1 = (float) Math.hypot(v1x, v1y), l2 = (float) Math.hypot(v2x, v2y);
            float rr = Math.min(r, Math.min(l1 / 2, l2 / 2));
            float qx = px + v1x / l1 * rr, qy = py + v1y / l1 * rr, ex = px + v2x / l2 * rr, ey = py + v2y / l2 * rr;
            if (k == 0) p.moveTo(qx, qy); else p.lineTo(qx, qy);
            p.quadTo(px, py, ex, ey);
        }
        p.close();
    }

    /** A pedal: a tall shield with its shoulders slanted in, the icon low in it, the trigger's badge on top. */
    static void drawPedal(Canvas c, RectF box, int color, Icon icon, String badge, boolean pressed, float opacity) {
        float w = box.width(), h = box.height(), l = box.left, t = box.top;
        float sw = w * .045f, q = sw / 2, r = w * .16f, in = sw * 2.4f;
        roundPoly(P.path, new float[] {
            l + w * .25f, t + q, l + w * .75f, t + q, l + w - q, t + h * .16f,
            l + w - q, t + h - q, l + q, t + h - q, l + q, t + h * .16f}, r);
        fill(body(pressed, opacity));
        c.drawPath(P.path, P.fill);
        stroke(alpha(color, opacity), sw * (pressed ? 1.4f : 1f));
        c.drawPath(P.path, P.stroke);
        roundPoly(P.path, new float[] {
            l + w * .3f, t + in, l + w * .7f, t + in, l + w - in, t + h * .18f,
            l + w - in, t + h - in, l + in, t + h - in, l + in, t + h * .18f}, r * .8f);
        stroke(alpha(color, INNER_ALPHA * opacity), sw * .6f);
        c.drawPath(P.path, P.stroke);
        icon(c, icon, l + w / 2, t + h * .57f, w * .66f, 2.8f, opacity);
        badge(c, l + w / 2, t + q, w * .15f, color, badge, opacity);
    }

    /** A small rounded rectangle (BACK, START, the shoulders): border, no inner ring, the icon in the middle. */
    static void drawPill(Canvas c, RectF box, int color, Icon icon, boolean pressed, float opacity) {
        float m = Math.min(box.width(), box.height()), sw = m * .05f, rr = m * .22f;
        P.rect.set(box.left + sw / 2, box.top + sw / 2, box.right - sw / 2, box.bottom - sw / 2);
        fill(body(pressed, opacity));
        c.drawRoundRect(P.rect, rr, rr, P.fill);
        stroke(alpha(color, opacity), sw * (pressed ? 1.4f : 1f));
        c.drawRoundRect(P.rect, rr, rr, P.stroke);
        icon(c, icon, box.centerX(), box.centerY(), m * .5f, 2f, opacity);
    }

    /** A stick: a faint well in a grey border, the knob (centred on knobX, knobY) a light disc in white. */
    static void drawStick(Canvas c, float cx, float cy, float r, float knobX, float knobY, float opacity) {
        float d = 2 * r, sw = d * .028f;
        fill(alpha(0x590C0D10, opacity));                 // rgba(12,13,16,.35)
        c.drawCircle(cx, cy, r - sw / 2, P.fill);
        stroke(alpha(COLOR_GREY, opacity), sw);
        c.drawCircle(cx, cy, r - sw / 2, P.stroke);
        fill(alpha(0x2EFFFFFF, opacity));                 // white 18%
        c.drawCircle(knobX, knobY, d * .2f, P.fill);
        stroke(alpha(0xFFFFFFFF, ink(opacity)), d * .024f);
        c.drawCircle(knobX, knobY, d * .2f, P.stroke);
    }

    /** A steering wheel turned angleDeg clockwise: grey rim, white hub and spokes, a yellow mark at the top. */
    static void drawWheel(Canvas c, float cx, float cy, float r, float angleDeg, float opacity) {
        float sw = 2 * r * .06f, rr = r - sw / 2;
        fill(alpha(0x400C0D10, opacity));                 // rgba(12,13,16,.25)
        c.drawCircle(cx, cy, rr, P.fill);
        stroke(alpha(COLOR_GREY, opacity), sw);
        c.drawCircle(cx, cy, rr, P.stroke);
        c.save();
        c.rotate(angleDeg, cx, cy);
        int white = alpha(0xFFFFFFFF, ink(opacity));
        stroke(white, sw * .75f);
        c.drawCircle(cx, cy, rr * .22f, P.stroke);
        Path p = P.path;
        p.reset();
        p.moveTo(cx - rr, cy - rr * .12f); p.lineTo(cx - rr * .22f, cy);
        p.moveTo(cx + rr, cy - rr * .12f); p.lineTo(cx + rr * .22f, cy);
        p.moveTo(cx, cy + rr * .22f); p.lineTo(cx, cy + rr);
        c.drawPath(p, P.stroke);
        stroke(alpha(COLOR_Y, ink(opacity)), sw);
        c.drawLine(cx - rr * .18f, cy - rr + sw * .4f, cx + rr * .18f, cy - rr + sw * .4f, P.stroke);
        c.restore();
    }

    /** A D-pad cross size px across: four arms, each its own press with a chevron; pressedMask: DPAD_* bits. */
    static void drawDpad(Canvas c, float cx, float cy, float size, int pressedMask, float opacity) {
        float a = size * .34f, sw = size * .035f, r = a * .28f, L = size / 2 - sw;
        fill(body(false, opacity));
        c.drawRect(cx - a / 2, cy - a / 2, cx + a / 2, cy + a / 2, P.fill);
        // up, right, down, left: the arm drawn upwards, turned
        int[] bits = {DPAD_UP, DPAD_RIGHT, DPAD_DOWN, DPAD_LEFT};
        Path p = P.path;
        for (int k = 0; k < 4; k++) {
            boolean on = (pressedMask & bits[k]) != 0;
            c.save();
            c.rotate(90 * k, cx, cy);
            p.reset();
            p.moveTo(cx - a / 2, cy - a / 2);
            p.lineTo(cx - a / 2, cy - L + r);
            p.quadTo(cx - a / 2, cy - L, cx - a / 2 + r, cy - L);
            p.lineTo(cx + a / 2 - r, cy - L);
            p.quadTo(cx + a / 2, cy - L, cx + a / 2, cy - L + r);
            p.lineTo(cx + a / 2, cy - a / 2);
            fill(body(on, opacity));
            c.drawPath(p, P.fill);
            stroke(alpha(COLOR_GREY, opacity), sw * (on ? 1.4f : 1f));
            c.drawPath(p, P.stroke);
            p.reset();
            p.moveTo(cx - a * .22f, cy - L + a * .62f);
            p.lineTo(cx, cy - L + a * .36f);
            p.lineTo(cx + a * .22f, cy - L + a * .62f);
            stroke(alpha(0xFFFFFFFF, ink(opacity)), sw * 1.1f);
            c.drawPath(p, P.stroke);
            c.restore();
        }
        stroke(alpha(COLOR_GREY, .5f * opacity), sw * .8f);
        c.drawCircle(cx, cy, a * .16f, P.stroke);
    }
}
