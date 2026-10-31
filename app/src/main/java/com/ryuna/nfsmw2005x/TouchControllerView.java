package com.ryuna.nfsmw2005x;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.input.InputManager;
import android.util.SparseArray;
import android.view.Display;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Touch controller over the game (GameActivity) or alone as its editor
 * (EditorActivity). Modelled on NFSMW-Recompiled-Mobile's
 * TouchControllerView: layouts with factory positions as fractions of the
 * screen, overridden per control by what the editor saved; a HIDE/TOUCH
 * button that fades after 4 s; the whole overlay hidden while a physical
 * pad is in use; an in-game editor behind the gear.
 *
 * Two layouts (Settings.MODE_*), in ButtonArt's Ring style as the mockup
 * (.work/nfsmw_buttons_mockup.html) has them:
 *   MOBILE  for driving: steering (stick, arrows, a wheel or tilting the
 *           phone), the D-pad and NOS on the left, the face buttons and the
 *           pedals on the right, the gear lever in the middle, BACK/☰/START
 *           and the camera on top. While a game menu is up the race
 *           controls give way to its prompts as icon buttons
 *           (GameActivity.nativeMenuPrompts).
 *   XBOX    the original Xbox controller, the same in races and menus.
 *
 * State goes to native code (GameActivity.nativeSetPad / nativeSetPadRight)
 * as controller port 0, merged with a physical pad if one is plugged in.
 */
public final class TouchControllerView extends View {
    // XBOX_GAMEPAD_* digital bits
    static final int UP = 0x01, DOWN = 0x02, LEFT = 0x04, RIGHT = 0x08;
    static final int START = 0x10, BACK = 0x20, L3 = 0x40, R3 = 0x80;
    // analog button indices (bit n of the face mask)
    static final int A = 0, B = 1, X = 2, Y = 3, BLACK = 4, WHITE = 5;

    private static final String PREFS = "nfsmw_touch";
    /** Bump when the factory layouts change. */
    private static final int LAYOUT_VERSION = 6;
    private static final boolean DEBUG = android.util.Log.isLoggable("nfsmw-pad", android.util.Log.DEBUG);
    private static final int MODES = 2;
    private static final String[] MODE_NAMES = {"XBOX", "MOBILE"};      // Settings.MODE_XBOX, MODE_MOBILE
    private static final long TOGGLE_MS = 4000;
    private static final float STEP = .1f, SCALE_MIN = .5f, SCALE_MAX = 2.2f, OPA_MIN = .15f;
    /** The wheel's lock each way, degrees of turning it: full lock on the left stick. */
    private static final float WHEEL_LOCK = 120f;
    /** How long a tap on a menu prompt, or a stick's or the gear lever's click, holds its pad button. */
    private static final long PULSE_MS = 120;
    /** A touch on a stick or the gear lever shorter than this, not moved, is its click. */
    private static final long CLICK_MS = 250;

    private static final String HELP = "Drag to move · SIZE: selected or all";
    private static final int ACCENT = Color.rgb(0x6F, 0x74, 0x32);
    private static final int UI_BG = Color.argb(190, 16, 17, 20);

    private static final int K_STICK = 0, K_ROUND = 1, K_RECT = 2, K_PEDAL = 3, K_DPAD = 4, K_ARROW = 5,
            K_WHEEL = 6, K_GEAR = 7;

    /** One on-screen control. */
    private final class Control {
        final String id;
        final ButtonArt.Icon icon;          // null: the letter alone
        final String badge;                 // the pad button's name on the icon's badge (MOBILE)
        final String letter;                // with no icon: the pad's letter, as the pad has it
        final int kind, digital, face, trigger, arrow;
        final float baseW, baseH;          // radius in u for round/stick/dpad/wheel/gear, else width and height
        int color = ButtonArt.COLOR_GREY;
        boolean right;                      // a stick: the right one
        int click;                          // a stick or the gear lever: the button a tap on it clicks (L3, R3)
        boolean menu;                       // the ☰: GameActivity's menu, no pad button
        boolean inMenus;                    // MOBILE: still shown while a game menu is up
        int visibleIn;                      // bit per mode
        final float[] fx = new float[MODES], fy = new float[MODES], fs = new float[MODES];
        final float[] px = new float[MODES], py = new float[MODES], ps = new float[MODES];
        final boolean[] own = new boolean[MODES];
        float cx, cy, scale = 1f;
        boolean down;
        float kx, ky;                       // stick or gear knob offset, -1..1
        boolean moved;                      // a stick or the gear lever: dragged since it came down, not a click
        long downAt;                        // uptime it came down: a click is short
        float angle, lastRaw;               // the wheel: turned clockwise, degrees; the finger's last angle round it
        int pointer = -1;

        Control(String id, ButtonArt.Icon icon, String badge, String letter, int kind, int digital, int face,
                int trigger, int arrow, float baseW, float baseH) {
            this.id = id; this.icon = icon; this.badge = badge; this.letter = letter; this.kind = kind;
            this.digital = digital; this.face = face; this.trigger = trigger; this.arrow = arrow;
            this.baseW = baseW; this.baseH = baseH;
        }

        /** Factory place in a mode, fractions of the screen. */
        Control at(int mode, float x, float y) {
            fx[mode] = x; fy[mode] = y; fs[mode] = 1f;
            visibleIn |= 1 << mode;
            return this;
        }

        boolean shown() { return (visibleIn & (1 << mode)) != 0; }
        float w() { return baseW * unit * scale; }
        float h() { return baseH * unit * scale; }
        boolean radial() {
            return kind == K_STICK || kind == K_ROUND || kind == K_DPAD || kind == K_WHEEL || kind == K_GEAR;
        }
        float reach() {
            if (kind == K_STICK) return w() * 1.35f;
            if (kind == K_DPAD) return w() * 1.05f;              // the cross spans 2w
            if (kind == K_WHEEL) return w();
            if (radial()) return w() + unit * .12f;
            return Math.max(w(), h()) * .6f + unit * .12f;
        }

        void use(int m) {
            cx = (own[m] ? px[m] : fx[m]) * width;
            cy = (own[m] ? py[m] : fy[m]) * height;
            scale = own[m] ? ps[m] : fs[m];
        }

        void keep(int m) {
            px[m] = cx / width; py[m] = cy / height; ps[m] = scale; own[m] = true;
        }

        boolean contains(float x, float y) {
            if (radial()) {
                float r = reach(), dx = x - cx, dy = y - cy;
                return dx * dx + dy * dy <= r * r;
            }
            float s = unit * .12f;
            return Math.abs(x - cx) <= w() * .5f + s && Math.abs(y - cy) <= h() * .5f + s;
        }
    }

    /** One of a game menu's prompts as a MOBILE button. */
    private static final class Prompt {
        final int pad;                      // PROMPT_PADS' index: 0 A, 1 B, 2 X, 3 Y, 4 LT, 5 RT
        final ButtonArt.Icon icon;          // null: the pad's letter
        float cx, cy, r;

        Prompt(int pad, ButtonArt.Icon icon) { this.pad = pad; this.icon = icon; }
    }
    /** nativeMenuPrompts' pad names, in native/touch.cpp's order. */
    private static final String[] PROMPT_PADS = {"A", "B", "X", "Y", "LT", "RT"};
    private static final int[] PROMPT_COLORS = {ButtonArt.COLOR_A, ButtonArt.COLOR_B, ButtonArt.COLOR_X,
            ButtonArt.COLOR_Y, ButtonArt.COLOR_GREY, ButtonArt.COLOR_GREY};

    private final List<Control> controls = new ArrayList<>();
    private final SparseArray<Control> pointers = new SparseArray<>();
    private final Set<Integer> taps = new HashSet<>();          // pointers that tapped a prompt or the ☰
    private final SharedPreferences touchPrefs, prefs;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint.FontMetrics fm = new Paint.FontMetrics();
    private final RectF r = new RectF();
    private final float density;
    private float width, height, unit;

    private int mode;
    private final int steer;                // Settings.STEER_*
    private final float[] opacity = new float[MODES];
    private boolean controlsVisible = true;
    private boolean physicalPad;
    private final boolean autoHide, haptic;
    private boolean toggleVisible = true;
    private final Runnable hideToggle = () -> { toggleVisible = false; invalidate(); };
    // the bar: HIDE/TOUCH, the editor, the mode; its ☰ only while the controls (and their ☰) are not drawn
    private final RectF toggleBtn = new RectF(), gearBtn = new RectF(), modeBtn = new RectF(), menuBtn = new RectF();
    /** The in-game menu (hamburger): set by GameActivity. */
    Runnable onMenu;

    // The game's own menus (native/touch.cpp): touches off the controls reach SDLSurface and are FEng's pointer,
    // swipes and the keyboard's keys. In MOBILE the race controls give way to the menu's prompts while one is up.
    private static final long MENU_POLL_MS = 250;
    private String[] lastPrompts;           // nativeMenuPrompts' last answer
    private List<Prompt> prompts;           // null: no game menu up, or XBOX, which keeps its layout
    private final Runnable menuPoll = new Runnable() {
        @Override public void run() {
            pollMenu();
            postDelayed(this, MENU_POLL_MS);
        }
    };

    // a tap's pad button, held PULSE_MS: a menu prompt, a stick's or the gear lever's click
    private int pulseDigital, pulseFace, pulseLt, pulseRt, pulsePad = -1;
    private final Runnable endPulse = () -> {
        pulseDigital = pulseFace = pulseLt = pulseRt = 0;
        pulsePad = -1;
        send();
        invalidate();
    };

    // editor
    private final boolean inGame;
    private final Runnable onEditorDone;     // EditorActivity: finish()
    private boolean editing;
    private Control picked;
    private int editPointer = -1;
    private float grabX, grabY;
    private final RectF sizeMinus = new RectF(), sizePlus = new RectF(), opaMinus = new RectF(),
            opaPlus = new RectF(), modeEdit = new RectF(), reset = new RectF(), cancel = new RectF(),
            done = new RectF(), helpBg = new RectF();
    private float sizeTextX, opaTextX;

    // tilt steering
    private final SensorManager sensors;
    private final Sensor gravity;
    private final boolean accelOnly;
    private final float[] lowPass = new float[2];
    private final float fullLockSin;
    private boolean listening;
    private float tilt;                      // -1..1
    private final SensorEventListener tiltListener = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent e) {
            float gx = e.values[0], gy = e.values[1];
            if (accelOnly) {
                lowPass[0] += (gx - lowPass[0]) * .2f;
                lowPass[1] += (gy - lowPass[1]) * .2f;
                gx = lowPass[0]; gy = lowPass[1];
            }
            Display d = getDisplay();
            int rot = d == null ? Surface.ROTATION_90 : d.getRotation();
            float sx;
            switch (rot) {
                case Surface.ROTATION_90: sx = gy; break;
                case Surface.ROTATION_180: sx = -gx; break;
                case Surface.ROTATION_270: sx = -gy; break;
                default: sx = gx; break;
            }
            float t = clamp(sx / (SensorManager.GRAVITY_EARTH * fullLockSin), -1f, 1f);
            float dz = .04f;
            t = Math.abs(t) < dz ? 0f : Math.signum(t) * (Math.abs(t) - dz) / (1f - dz);
            if (Math.abs(t - tilt) > .005f) {
                tilt = t;
                if (tiltActive()) send();
            }
        }
        @Override public void onAccuracyChanged(Sensor s, int a) { }
    };

    private final InputManager inputManager;
    private final InputManager.InputDeviceListener padListener = new InputManager.InputDeviceListener() {
        @Override public void onInputDeviceAdded(int id) { checkPads(); }
        @Override public void onInputDeviceRemoved(int id) { checkPads(); }
        @Override public void onInputDeviceChanged(int id) { checkPads(); }
    };

    /** In the game. */
    public TouchControllerView(Context c) {
        this(c, null);
    }

    /** With onDone: the editor (EditorActivity), no native calls. */
    public TouchControllerView(Context c, Runnable onDone) {
        super(c);
        onEditorDone = onDone;
        inGame = onDone == null;
        editing = !inGame;
        density = getResources().getDisplayMetrics().density;
        prefs = Settings.prefs(c);
        touchPrefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // saved positions are offsets from one set of factory defaults; when
        // the defaults change, start again from the new ones
        if (touchPrefs.getInt("layout_version", 0) != LAYOUT_VERSION)
            touchPrefs.edit().clear().putInt("layout_version", LAYOUT_VERSION).apply();
        mode = Settings.padMode(c);
        steer = Settings.steer(c);
        autoHide = prefs.getBoolean(Settings.K_PAD_AUTOHIDE, true);
        haptic = prefs.getBoolean(Settings.K_HAPTIC, true);
        controlsVisible = !inGame || prefs.getBoolean(Settings.K_PAD, true);
        for (int m = 0; m < MODES; m++) opacity[m] = touchPrefs.getFloat("m" + m + "_opacity", .7f);
        fullLockSin = (float) Math.sin(Math.toRadians(Settings.tiltAngle(Settings.tiltSens(c))));
        sensors = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        Sensor g = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_GRAVITY);
        accelOnly = g == null && sensors != null;
        gravity = g != null ? g : sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        inputManager = (InputManager) c.getSystemService(Context.INPUT_SERVICE);

        setWillNotDraw(false);
        setFocusable(false);
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(1.6f));
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(dp(2.5f));
        frame.setColor(ACCENT);
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.DEFAULT_BOLD);
    }

    // ------------------------------------------------------------------ setup

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (inGame) {
            if (inputManager != null) inputManager.registerInputDeviceListener(padListener, getHandler());
            checkPads();
            showToggle();
            post(menuPoll);
        }
        updateTilt();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (inputManager != null) inputManager.unregisterInputDeviceListener(padListener);
        removeCallbacks(hideToggle);
        removeCallbacks(menuPoll);
        removeCallbacks(endPulse);
        stopTilt();
        super.onDetachedFromWindow();
    }

    /** GameActivity: pause/resume the tilt sensor with the activity. */
    void onPauseResume(boolean resumed) {
        if (resumed) updateTilt(); else stopTilt();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        width = w; height = h;
        unit = Math.min(w / 16f, h / 9f);
        build();
    }

    private void build() {
        controls.clear();
        final int XB = Settings.MODE_XBOX, MO = Settings.MODE_MOBILE;
        final float u = unit, w = width, h = height;

        // ---------------- MOBILE: the mockup's race layout ----------------
        // One left margin for the left column, the pedals' distance from the right edge: the D-pad, stick, wheel and
        // left arrow put their left edge on it.
        final float margin = .0248f * w;
        top(add(pill("back", ButtonArt.Icon.BACK, BACK)).at(MO, .43f, .07f));
        top(menu(add(round("menu", ButtonArt.Icon.MENU, null, null, 0, -1, 0, .9f, ButtonArt.COLOR_GREY)))).at(MO, .50f, .07f);
        top(add(pill("start", ButtonArt.Icon.START, START)).at(MO, .57f, .07f));
        // Black is the camera; its badge an empty disc, as Black has no letter
        add(round("black", ButtonArt.Icon.CAMERA, " ", null, 0, BLACK, 0, .9f, ButtonArt.COLOR_GREY)).at(MO, .95f, .07f);
        Control dpad = add(new Control("dpad", null, null, null, K_DPAD, 0, -1, 0, 0, .8f, 0));
        place(dpad, MO, margin + .8f * u, .41f * h);
        add(round("nos", ButtonArt.Icon.NOS, "B", null, 0, B, 0, 1.5f, ButtonArt.COLOR_B)).at(MO, .219f, .539f);
        if (steer == Settings.STEER_STICK) {
            Control ls = add(new Control("ls", null, null, null, K_STICK, 0, -1, 0, 0, 1.2f, 0));
            place(ls, MO, margin + 1.2f * u, .718f * h);
        } else if (steer == Settings.STEER_ARROWS) {
            // the arrows mirror the pedals: the same gap between them, the same distance from the edge, bottoms on
            // the pedals' line
            float d = 1.9f * u, lx = margin + d / 2, rx = lx + d + .021f * w, y = h - .0391f * h - .06f * h - d / 2;
            place(add(new Control("steer_l", ButtonArt.Icon.LEFT, null, null, K_ARROW, 0, -1, 0, -1, 1.9f, 1.9f)), MO, lx, y);
            place(add(new Control("steer_r", ButtonArt.Icon.RIGHT, null, null, K_ARROW, 0, -1, 0, 1, 1.9f, 1.9f)), MO, rx, y);
        } else if (steer == Settings.STEER_WHEEL) {
            Control wheel = add(new Control("wheel", null, null, null, K_WHEEL, 0, -1, 0, 0, 1.35f, 0));
            place(wheel, MO, margin + 1.35f * u, .718f * h);
        }                                                   // tilt: nothing on the screen
        add(round("y", ButtonArt.Icon.LOOKBACK, "Y", null, 0, Y, 0, 1.15f, ButtonArt.COLOR_Y)).at(MO, .862f, .30f);
        add(round("x", ButtonArt.Icon.SPEEDBREAKER, "X", null, 0, X, 0, 1.15f, ButtonArt.COLOR_X)).at(MO, .939f, .46f);
        add(round("a", ButtonArt.Icon.EBRAKE, "A", null, 0, A, 0, 1.15f, ButtonArt.COLOR_A)).at(MO, .805f, .52f);
        add(new Control("gas", ButtonArt.Icon.GAS, "RT", null, K_PEDAL, 0, -1, 2, 0, 1.45f, 2.7f)).at(MO, .939f, .751f);
        add(new Control("brake", ButtonArt.Icon.BRAKE, "LT", null, K_PEDAL, 0, -1, 1, 0, 1.35f, 2.1f)).at(MO, .848f, .781f);
        // the gear lever is the right stick (the spec's "Gear lever | right stick"): a drag is that stick, a tap
        // clicks it (R3); the DRIVE layout had no gear to keep the behaviour of
        Control gear = add(new Control("gear", ButtonArt.Icon.GEAR, null, null, K_GEAR, 0, -1, 0, 0, .5f, 0));
        gear.right = true;
        gear.click = R3;
        gear.at(MO, .55f, .79f);

        // ---------------- XBOX: the whole pad, the mockup's layout ----------------
        // ids of their own: the editor keeps each control's place per mode under its id
        add(round("x_lt", null, null, "LT", 0, -1, 1, 1.1f, ButtonArt.COLOR_GREY)).at(XB, .06f, .09f);
        add(round("x_rt", null, null, "RT", 0, -1, 2, 1.1f, ButtonArt.COLOR_GREY)).at(XB, .94f, .09f);
        add(pill("x_back", ButtonArt.Icon.BACK, BACK)).at(XB, .43f, .07f);
        menu(add(round("x_menu", ButtonArt.Icon.MENU, null, null, 0, -1, 0, .9f, ButtonArt.COLOR_GREY))).at(XB, .50f, .07f);
        add(pill("x_start", ButtonArt.Icon.START, START)).at(XB, .57f, .07f);
        Control ls = add(new Control("x_ls", null, null, null, K_STICK, 0, -1, 0, 0, 1f, 0));
        ls.click = L3;
        ls.at(XB, .10f, .44f);
        add(new Control("x_dpad", null, null, null, K_DPAD, 0, -1, 0, 0, 1.05f, 0)).at(XB, .10f, .80f);
        float cxR = .90f, cyR = .44f, dx = .05f, dy = .11f;
        add(round("x_a", null, null, "A", 0, A, 0, 1f, ButtonArt.COLOR_A)).at(XB, cxR, cyR + dy);
        add(round("x_b", null, null, "B", 0, B, 0, 1f, ButtonArt.COLOR_B)).at(XB, cxR + dx, cyR);
        add(round("x_x", null, null, "X", 0, X, 0, 1f, ButtonArt.COLOR_X)).at(XB, cxR - dx, cyR);
        add(round("x_y", null, null, "Y", 0, Y, 0, 1f, ButtonArt.COLOR_Y)).at(XB, cxR, cyR - dy);
        add(round("x_black", ButtonArt.Icon.CAMERA, null, null, 0, BLACK, 0, .75f, ButtonArt.COLOR_GREY)).at(XB, cxR - dx, cyR - 2 * dy);
        add(round("x_white", null, null, "W", 0, WHITE, 0, .75f, ButtonArt.COLOR_WHITE)).at(XB, cxR + dx, cyR - 2 * dy);
        Control rs = add(new Control("x_rs", null, null, null, K_STICK, 0, -1, 0, 0, .95f, 0));
        rs.right = true;
        rs.click = R3;
        rs.at(XB, cxR, .80f);

        for (Control c : controls) load(c);
        useMode(mode);
        layoutUi();
        prompts = lastPrompts == null ? null : layoutPrompts(lastPrompts);
        send();
        invalidate();
    }

    /** A round button diameter u across. */
    private Control round(String id, ButtonArt.Icon icon, String badge, String letter, int digital, int face,
                          int trigger, float diameter, int color) {
        Control c = new Control(id, icon, badge, letter, K_ROUND, digital, face, trigger, 0, diameter / 2, 0);
        c.color = color;
        return c;
    }

    /** BACK and START: small rounded rectangles. */
    private Control pill(String id, ButtonArt.Icon icon, int digital) {
        return new Control(id, icon, null, null, K_RECT, digital, -1, 0, 0, 1.2f, .7f);
    }

    private Control menu(Control c) { c.menu = true; return c; }

    /** MOBILE's top row stays over the game's menus too. */
    private Control top(Control c) { c.inMenus = true; return c; }

    /** Factory position in pixels for a mode (stored as fractions). */
    private void place(Control c, int mode, float px, float py) {
        c.at(mode, px / width, py / height);
    }

    private Control add(Control c) {
        controls.add(c);
        return c;
    }

    private void load(Control c) {
        for (int m = 0; m < MODES; m++) {
            String k = "m" + m + "_" + c.id;
            if (!touchPrefs.contains(k + "_x")) continue;
            c.px[m] = touchPrefs.getFloat(k + "_x", 0f);
            c.py[m] = touchPrefs.getFloat(k + "_y", 0f);
            c.ps[m] = touchPrefs.getFloat(k + "_s", 1f);
            c.own[m] = true;
        }
    }

    private void useMode(int m) {
        for (Control c : controls) c.use(m);
        invalidate();
    }

    /** Drawn and touchable now: in its mode, and in MOBILE over a game menu only the top row. */
    private boolean visible(Control c) {
        return c.shown() && (editing || prompts == null || c.inMenus);
    }

    private void layoutUi() {
        // the bar, top left: the top centre is BACK/☰/START's, and the left column starts below it
        text.setTextSize(dp(11));
        float h = Math.max(dp(26), unit * .42f);
        float top = dp(6), gap = dp(8);
        float tw = Math.max(dp(64), text.measureText("TOUCH") + dp(24));
        float mw = text.measureText("MOBILE") + dp(22);
        float x0 = width * .12f;
        toggleBtn.set(x0, top, x0 + tw, top + h);
        gearBtn.set(toggleBtn.right + gap, top, toggleBtn.right + gap + h, top + h);
        modeBtn.set(gearBtn.right + gap, top, gearBtn.right + gap + mw, top + h);
        menuBtn.set(modeBtn.right + gap, top, modeBtn.right + gap + h, top + h);

        // editor bar: [-] SIZE [+]   [-] OPACITY [+]  /  [MODE] [RESET] [CANCEL] [DONE]
        text.setTextSize(dp(12));
        float side = Math.max(dp(34), unit * .6f);
        gap = dp(10);
        float sw = text.measureText("SIZE 220 %") + dp(16);
        float ow = text.measureText("OPACITY 100 %") + dp(16);
        float row = side * 4 + sw + ow + gap * 7;
        float x = width * .5f - row * .5f, y = height * .25f;
        sizeMinus.set(x, y, x + side, y + side); x += side + gap;
        sizeTextX = x + sw * .5f; x += sw + gap;
        sizePlus.set(x, y, x + side, y + side); x += side + gap * 3;
        opaMinus.set(x, y, x + side, y + side); x += side + gap;
        opaTextX = x + ow * .5f; x += ow + gap;
        opaPlus.set(x, y, x + side, y + side);
        float mw2 = text.measureText("LAYOUT: MOBILE") + dp(24);
        float rw = text.measureText("RESET") + dp(26), cw = text.measureText("CANCEL") + dp(26),
              dw = text.measureText("DONE") + dp(26);
        row = mw2 + rw + cw + dw + gap * 3;
        x = width * .5f - row * .5f; y += side + gap;
        modeEdit.set(x, y, x + mw2, y + side); x += mw2 + gap;
        reset.set(x, y, x + rw, y + side); x += rw + gap;
        cancel.set(x, y, x + cw, y + side); x += cw + gap;
        done.set(x, y, x + dw, y + side);
        text.setTextSize(dp(11));
        float hw = text.measureText(HELP) * .5f + dp(12);
        y += side + gap;
        helpBg.set(width * .5f - hw, y, width * .5f + hw, y + dp(24));
    }

    // ---------------------------------------------------------------- drawing

    private boolean padShown() { return controlsVisible && !physicalPad; }

    @Override
    protected void onDraw(Canvas canvas) {
        if (editing) {
            for (Control c : controls) if (c.shown()) drawControl(canvas, c);
            if (picked != null) {
                float rr = (picked.radial() ? picked.reach() : Math.max(picked.w(), picked.h()) * .5f) + dp(6);
                canvas.drawRect(picked.cx - rr, picked.cy - rr, picked.cx + rr, picked.cy + rr, frame);
            }
            drawEditor(canvas);
            return;
        }
        if (padShown()) {
            for (Control c : controls) if (visible(c)) drawControl(canvas, c);
            if (prompts != null) for (Prompt p : prompts) drawPrompt(canvas, p);
        }
        if (toggleVisible) {
            uiButton(canvas, toggleBtn, controlsVisible ? "HIDE" : "TOUCH", dp(11));
            uiButton(canvas, gearBtn, "\u2699", dp(15));
            uiButton(canvas, modeBtn, MODE_NAMES[mode], dp(11));
            if (!padShown()) uiButton(canvas, menuBtn, "\u2630", dp(15));
        }
    }

    private void drawControl(Canvas canvas, Control c) {
        float op = opacity[mode];
        switch (c.kind) {
            case K_STICK: {
                float rad = c.w();
                ButtonArt.drawStick(canvas, c.cx, c.cy, rad, c.cx + c.kx * rad * .6f, c.cy + c.ky * rad * .6f, op);
                break;
            }
            case K_ROUND:
                // XBOX is the pad itself: its letters, no badges; MOBILE names the action, the pad's button on the badge
                if (c.icon == null)
                    ButtonArt.drawRound(canvas, c.cx, c.cy, c.w(), c.color, null, c.letter, c.down, op);
                else
                    ButtonArt.drawRound(canvas, c.cx, c.cy, c.w(), c.color, c.icon,
                                        mode == Settings.MODE_XBOX ? null : c.badge, c.down, op);
                break;
            case K_GEAR:
                // the lever's knob follows the finger a little
                ButtonArt.drawRound(canvas, c.cx + c.kx * c.w() * .5f, c.cy + c.ky * c.w() * .5f, c.w(), c.color,
                                    c.icon, null, c.down || pulseDigital == c.click && c.click != 0, op);
                break;
            case K_WHEEL:
                ButtonArt.drawWheel(canvas, c.cx, c.cy, c.w(), c.angle, op);
                break;
            case K_ARROW:
                ButtonArt.drawRound(canvas, c.cx, c.cy, Math.min(c.w(), c.h()) * .5f, c.color, c.icon, null, c.down, op);
                break;
            case K_RECT: case K_PEDAL: {
                r.set(c.cx - c.w() * .5f, c.cy - c.h() * .5f, c.cx + c.w() * .5f, c.cy + c.h() * .5f);
                if (c.kind == K_PEDAL) ButtonArt.drawPedal(canvas, r, c.color, c.icon, c.badge, c.down, op);
                else ButtonArt.drawPill(canvas, r, c.color, c.icon, c.down, op);
                break;
            }
            case K_DPAD:
                // the cross spans 2w, as reach() and the arms' hit test have it
                ButtonArt.drawDpad(canvas, c.cx, c.cy, c.w() * 2f, c.down ? currentDpad : 0, op);
                break;
        }
    }

    /** A menu prompt: its action's icon in a ring of its pad button's colour; no icon fits, the pad's letter. */
    private void drawPrompt(Canvas canvas, Prompt p) {
        float op = opacity[mode];
        boolean on = pulsePad == p.pad;
        int color = PROMPT_COLORS[p.pad];
        if (p.icon != null)
            ButtonArt.drawRound(canvas, p.cx, p.cy, p.r, color, p.icon, null, on, op);
        else
            ButtonArt.drawRoundLetter(canvas, p.cx, p.cy, p.r, color, PROMPT_PADS[p.pad], PROMPT_PADS[p.pad], on, op);
    }

    private int currentDpad;

    private void uiButton(Canvas canvas, RectF b, String s, float size) {
        fill.setColor(UI_BG);
        canvas.drawRoundRect(b, dp(7), dp(7), fill);
        stroke.setColor(ACCENT);
        canvas.drawRoundRect(b, dp(7), dp(7), stroke);
        text.setTextSize(size);
        text.getFontMetrics(fm);
        canvas.drawText(s, b.centerX(), b.centerY() - (fm.ascent + fm.descent) * .5f, text);
    }

    private void drawEditor(Canvas canvas) {
        uiButton(canvas, sizeMinus, "\u2212", dp(18));
        uiButton(canvas, sizePlus, "+", dp(18));
        uiButton(canvas, opaMinus, "\u2212", dp(18));
        uiButton(canvas, opaPlus, "+", dp(18));
        String st = picked == null ? "SIZE (ALL)" : "SIZE " + Math.round(picked.scale * 100) + " %";
        pill(canvas, st, sizeTextX, sizeMinus.centerY());
        pill(canvas, "OPACITY " + Math.round(opacity[mode] * 100) + " %", opaTextX, opaMinus.centerY());
        uiButton(canvas, modeEdit, "LAYOUT: " + MODE_NAMES[mode], dp(12));
        uiButton(canvas, reset, "RESET", dp(12));
        uiButton(canvas, cancel, "CANCEL", dp(12));
        uiButton(canvas, done, "DONE", dp(12));
        fill.setColor(UI_BG);
        canvas.drawRoundRect(helpBg, dp(6), dp(6), fill);
        text.setTextSize(dp(11));
        text.getFontMetrics(fm);
        canvas.drawText(HELP,
                helpBg.centerX(), helpBg.centerY() - (fm.ascent + fm.descent) * .5f, text);
    }

    private void pill(Canvas canvas, String s, float x, float y) {
        text.setTextSize(dp(12));
        float half = text.measureText(s) * .5f + dp(8);
        fill.setColor(UI_BG);
        canvas.drawRoundRect(x - half, y - dp(13), x + half, y + dp(13), dp(7), dp(7), fill);
        text.getFontMetrics(fm);
        canvas.drawText(s, x, y - (fm.ascent + fm.descent) * .5f, text);
    }

    // ------------------------------------------------------------ game menus

    /** The game's menu prompts, from native: in MOBILE they replace the race controls while a menu is up. */
    private void pollMenu() {
        String[] p = null;
        if (mode == Settings.MODE_MOBILE) {
            try { p = GameActivity.nativeMenuPrompts(); } catch (UnsatisfiedLinkError e) { return; }
        }
        if (Arrays.equals(p, lastPrompts)) return;
        boolean was = prompts != null;
        lastPrompts = p;
        prompts = p == null ? null : layoutPrompts(p);
        if (prompts != null && !was) releaseHidden();
        send();
        invalidate();
    }

    /** nativeMenuPrompts' { label, pad, ... } as buttons: the triggers' zoom on the left, the rest a centred row. */
    private List<Prompt> layoutPrompts(String[] p) {
        List<Prompt> out = new ArrayList<>(), row = new ArrayList<>();
        for (int i = 0; i + 1 < p.length; i += 2) {
            int pad = Arrays.asList(PROMPT_PADS).indexOf(p[i + 1]);
            if (pad < 0) continue;
            if (pad >= 4) {                 // the triggers zoom wherever they are prompted (the showcase, the map)
                Prompt z = new Prompt(pad, pad == 4 ? ButtonArt.Icon.ZOOM_OUT : ButtonArt.Icon.ZOOM_IN);
                z.cx = .045f * width;
                z.cy = (pad == 4 ? .62f : .76f) * height;
                z.r = .5f * unit;
                out.add(z);
            } else {
                row.add(new Prompt(pad, ButtonArt.iconForPrompt(p[i])));
            }
        }
        for (int i = 0; i < row.size(); i++) {
            Prompt b = row.get(i);
            b.cx = (.5f + (i - (row.size() - 1) / 2f) * .08f) * width;
            b.cy = .86f * height;
            b.r = 1.15f / 2 * unit;
            out.add(b);
        }
        return out;
    }

    /** The race controls a menu covers let go of what they held. */
    private void releaseHidden() {
        for (Control c : controls) {
            if (visible(c) || c.pointer < 0) continue;
            pointers.remove(c.pointer);
            release(c);
        }
    }

    /** A tap's pad button: held PULSE_MS, then let go. pad: PROMPT_PADS' index, or -1 with digital alone. */
    private void pulse(int pad, int digital) {
        pulseDigital = digital;
        pulseFace = pad >= 0 && pad < 4 ? 1 << pad : 0;
        pulseLt = pad == 4 ? 255 : 0;
        pulseRt = pad == 5 ? 255 : 0;
        pulsePad = pad;
        removeCallbacks(endPulse);
        postDelayed(endPulse, PULSE_MS);
        send();
        invalidate();
    }

    // ------------------------------------------------------------------ touch

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (editing) return editTouch(e);
        int action = e.getActionMasked(), idx = e.getActionIndex(), id = e.getPointerId(idx);
        float x = e.getX(idx), y = e.getY(idx);

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            boolean barMenu = !padShown() && menuBtn.contains(x, y);
            if (toggleVisible) {
                if (gearBtn.contains(x, y)) { openEditor(); return true; }
                if (toggleBtn.contains(x, y)) {
                    controlsVisible = !controlsVisible;
                    if (!controlsVisible) releaseAll();
                    showToggle();
                    return true;
                }
                if (modeBtn.contains(x, y)) { switchMode(); showToggle(); return true; }
                if (barMenu) { releaseAll(); if (onMenu != null) onMenu.run(); return true; }
            }
            if (toggleBtn.contains(x, y) || gearBtn.contains(x, y) || modeBtn.contains(x, y) || barMenu
                    || (physicalPad && autoHide)) {
                if (physicalPad && autoHide) { physicalPad = false; controlsVisible = true; }
                showToggle();
                return true;
            }
            if (!controlsVisible) return false;
            if (prompts != null) {
                for (Prompt p : prompts) {
                    float dx = x - p.cx, dy = y - p.cy, rr = p.r + unit * .12f;
                    if (dx * dx + dy * dy > rr * rr) continue;
                    taps.add(id);
                    pulse(p.pad, 0);
                    if (haptic) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    return true;
                }
            }
            for (int i = controls.size() - 1; i >= 0; i--) {
                Control c = controls.get(i);
                if (c.pointer >= 0 || !visible(c) || !c.contains(x, y)) continue;
                if (haptic) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                if (c.menu) {
                    taps.add(id);
                    releaseAll();
                    if (onMenu != null) onMenu.run();
                    return true;
                }
                c.pointer = id;
                pointers.put(id, c);
                press(c, x, y);
                send();
                invalidate();
                return true;
            }
            // not ours: Android dispatches this pointer to the next view under it, SDLSurface, and the game's menus
            // take it (native/touch.cpp); in a race nothing does
            return false;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            boolean any = false;
            for (int i = 0; i < e.getPointerCount(); i++) {
                Control c = pointers.get(e.getPointerId(i));
                if (c != null) { move(c, e.getX(i), e.getY(i)); any = true; }
            }
            if (any) { send(); invalidate(); }
            return any || !taps.isEmpty();
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            if (taps.remove(id)) return true;
            Control c = pointers.get(id);
            if (c == null) return false;
            // a short touch that did not move is the stick's click; a thumb resting on it and lifted is not
            boolean click = c.click != 0 && !c.moved && android.os.SystemClock.uptimeMillis() - c.downAt < CLICK_MS;
            release(c);
            pointers.remove(id);
            if (click) pulse(-1, c.click);
            send();
            invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) { releaseAll(); return true; }
        return false;
    }

    private void press(Control c, float x, float y) {
        c.down = true;
        c.moved = false;
        c.downAt = android.os.SystemClock.uptimeMillis();
        if (c.kind == K_WHEEL) c.lastRaw = (float) Math.toDegrees(Math.atan2(y - c.cy, x - c.cx));
        move(c, x, y);
    }

    private void move(Control c, float x, float y) {
        if (c.kind == K_STICK || c.kind == K_GEAR) {
            float rad = c.w() * (c.kind == K_GEAR ? 1f : .6f), dx = (x - c.cx) / rad, dy = (y - c.cy) / rad;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d > 1f) { dx /= d; dy /= d; }
            if (d > .35f) c.moved = true;
            c.kx = dx; c.ky = dy;
        } else if (c.kind == K_WHEEL) {
            // turned by the finger's angle round the hub, from where it came down; near the hub the angle is noise
            float dx = x - c.cx, dy = y - c.cy;
            if (dx * dx + dy * dy < (c.w() * .2f) * (c.w() * .2f)) return;
            float raw = (float) Math.toDegrees(Math.atan2(dy, dx));          // clockwise on the screen
            float d = raw - c.lastRaw;
            if (d > 180) d -= 360; else if (d < -180) d += 360;
            c.lastRaw = raw;
            c.angle = clamp(c.angle + d, -WHEEL_LOCK, WHEEL_LOCK);
        } else if (c.kind == K_DPAD) {
            float dx = x - c.cx, dy = y - c.cy, dead = c.w() * .2f;
            int bits = 0;
            if (Math.abs(dx) > dead || Math.abs(dy) > dead) {
                double ang = Math.toDegrees(Math.atan2(-dy, dx));        // 0 right, 90 up
                if (ang < 0) ang += 360;
                if (ang < 67.5 || ang >= 292.5) bits |= RIGHT;
                if (ang >= 22.5 && ang < 157.5) bits |= UP;
                if (ang >= 112.5 && ang < 247.5) bits |= LEFT;
                if (ang >= 202.5 && ang < 337.5) bits |= DOWN;
            }
            currentDpad = bits;
        }
    }

    private void release(Control c) {
        c.down = false;
        c.pointer = -1;
        c.kx = c.ky = 0;
        c.angle = 0;
        if (c.kind == K_DPAD) currentDpad = 0;
    }

    private void releaseAll() {
        for (Control c : controls) release(c);
        pointers.clear();
        taps.clear();
        send();
        invalidate();
    }

    private void switchMode() {
        releaseAll();
        mode = (mode + 1) % MODES;
        saveMode();
        useMode(mode);
        pollMenu();
        updateTilt();
        send();
    }

    /** The layout's mode is mostwanted.toml's pad_mode, the game's own menu's too. */
    private void saveMode() {
        try {
            Settings.putNative(getContext(), Settings.K_PAD_MODE, mode);
        } catch (java.io.IOException e) {
            android.util.Log.w("nfsmw", "cannot save pad_mode: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------- to native

    private int lastB = -1, lastF = -1, lastLt = -1, lastRt = -1, lastLx, lastLy, lastRx = -1, lastRy = -1;

    private void send() {
        if (!inGame) return;
        int buttons = 0, face = 0, lt = 0, rt = 0, lx = 0, ly = 0, rx = 0, ry = 0;
        if (padShown()) {
            for (Control c : controls) {
                if (!c.down || !visible(c) || c.menu) continue;
                buttons |= c.digital;
                if (c.face >= 0) face |= 1 << c.face;
                if (c.trigger == 1) lt = 255;
                if (c.trigger == 2) rt = 255;
                if (c.kind == K_DPAD) buttons |= currentDpad;
                if (c.kind == K_ARROW) lx = c.arrow * 32767;
                if (c.kind == K_WHEEL) lx = Math.round(c.angle / WHEEL_LOCK * 32767);
                if (c.kind == K_STICK || (c.kind == K_GEAR && c.moved)) {
                    int vx = axis(c.kx), vy = axis(-c.ky);
                    if (c.right) { rx = vx; ry = vy; } else { lx = vx; ly = vy; }
                }
            }
            // tilt steers races only: over a menu the left stick would move its highlight
            if (tiltActive() && prompts == null) lx = Math.round(tilt * 32767);
            buttons |= pulseDigital;
            face |= pulseFace;
            lt = Math.max(lt, pulseLt);
            rt = Math.max(rt, pulseRt);
        }
        if (buttons != lastB || face != lastF || lt != lastLt || rt != lastRt || lx != lastLx || ly != lastLy) {
            lastB = buttons; lastF = face; lastLt = lt; lastRt = rt; lastLx = lx; lastLy = ly;
            if (DEBUG) android.util.Log.d("nfsmw-pad", "b=" + buttons + " f=" + face + " lt=" + lt + " rt=" + rt + " lx=" + lx + " ly=" + ly);
            try { GameActivity.nativeSetPad(buttons, face, lt, rt, lx, ly); } catch (UnsatisfiedLinkError ignored) { }
        }
        if (rx != lastRx || ry != lastRy) {
            lastRx = rx; lastRy = ry;
            try { GameActivity.nativeSetPadRight(rx, ry); } catch (UnsatisfiedLinkError ignored) { }
        }
    }

    private static int axis(float v) {
        if (Math.abs(v) < .1f) return 0;
        float s = Math.signum(v) * (Math.abs(v) - .1f) / .9f;
        return Math.round(clamp(s, -1f, 1f) * 32767);
    }

    // ------------------------------------------------------------ pads, tilt

    private void checkPads() {
        boolean pad = false;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null || d.isVirtual()) continue;
            int s = d.getSources();
            if ((s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) pad = true;
        }
        boolean hide = pad && autoHide;
        if (hide != physicalPad) {
            physicalPad = hide;
            if (hide) releaseAll();
            showToggle();
            updateTilt();
            send();
            invalidate();
        }
    }

    /** GameActivity: any key/motion from a physical pad hides the overlay. */
    void onPhysicalInput() {
        if (autoHide && !physicalPad) {
            physicalPad = true;
            releaseAll();
            invalidate();
        }
    }

    private boolean tiltActive() {
        return inGame && mode == Settings.MODE_MOBILE && steer == Settings.STEER_TILT
                && controlsVisible && !physicalPad && !editing;
    }

    private void updateTilt() {
        boolean want = tiltActive() && gravity != null;
        if (want && !listening) {
            listening = sensors.registerListener(tiltListener, gravity, SensorManager.SENSOR_DELAY_GAME);
        } else if (!want) {
            stopTilt();
        }
    }

    private void stopTilt() {
        if (listening) sensors.unregisterListener(tiltListener);
        listening = false;
        tilt = 0;
    }

    private void showToggle() {
        toggleVisible = true;
        removeCallbacks(hideToggle);
        postDelayed(hideToggle, TOGGLE_MS);
        invalidate();
    }

    // ----------------------------------------------------------------- editor

    void openEditor() {
        releaseAll();
        removeCallbacks(hideToggle);
        toggleVisible = false;
        editing = true;
        picked = null;
        editPointer = -1;
        stopTilt();
        invalidate();
    }

    private boolean editTouch(MotionEvent e) {
        int action = e.getActionMasked(), idx = e.getActionIndex();
        float x = e.getX(idx), y = e.getY(idx);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (editPointer != -1) return true;
                if (sizeMinus.contains(x, y)) resize(-STEP);
                else if (sizePlus.contains(x, y)) resize(STEP);
                else if (opaMinus.contains(x, y)) opacity[mode] = clamp(round1(opacity[mode] - STEP), OPA_MIN, 1f);
                else if (opaPlus.contains(x, y)) opacity[mode] = clamp(round1(opacity[mode] + STEP), OPA_MIN, 1f);
                else if (modeEdit.contains(x, y)) {
                    for (Control c : controls) if (c.shown()) c.keep(mode);
                    mode = (mode + 1) % MODES;
                    picked = null;
                    useMode(mode);
                } else if (reset.contains(x, y)) {
                    for (Control c : controls) { c.own[mode] = false; c.use(mode); }
                    opacity[mode] = .7f;
                    picked = null;
                } else if (cancel.contains(x, y)) finishEditor(false);
                else if (done.contains(x, y)) finishEditor(true);
                else {
                    picked = null;
                    for (int i = controls.size() - 1; i >= 0; i--) {
                        Control c = controls.get(i);
                        if (c.shown() && c.contains(x, y)) { picked = c; break; }
                    }
                    if (picked != null) {
                        editPointer = e.getPointerId(idx);
                        grabX = x - picked.cx;
                        grabY = y - picked.cy;
                    }
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE: {
                int i = editPointer == -1 ? -1 : e.findPointerIndex(editPointer);
                if (i < 0 || picked == null) return true;
                picked.cx = clamp(e.getX(i) - grabX, 0, width);
                picked.cy = clamp(e.getY(i) - grabY, 0, height);
                picked.keep(mode);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if (e.getPointerId(idx) == editPointer) editPointer = -1;
                return true;
            default:
                editPointer = -1;
                return true;
        }
    }

    private void resize(float step) {
        if (picked != null) {
            picked.scale = clamp(round1(picked.scale + step), SCALE_MIN, SCALE_MAX);
            picked.keep(mode);
        } else {
            for (Control c : controls) {
                if (!c.shown()) continue;
                c.scale = clamp(round1(c.scale + step), SCALE_MIN, SCALE_MAX);
                c.keep(mode);
            }
        }
    }

    private void finishEditor(boolean save) {
        if (save) {
            for (Control c : controls) if (c.shown()) c.keep(mode);
            SharedPreferences.Editor ed = touchPrefs.edit();
            for (int m = 0; m < MODES; m++) {
                ed.putFloat("m" + m + "_opacity", opacity[m]);
                for (Control c : controls) {
                    String k = "m" + m + "_" + c.id;
                    if (c.own[m]) {
                        ed.putFloat(k + "_x", c.px[m]).putFloat(k + "_y", c.py[m]).putFloat(k + "_s", c.ps[m]);
                    } else {
                        ed.remove(k + "_x").remove(k + "_y").remove(k + "_s");
                    }
                }
            }
            ed.apply();
            saveMode();
        }
        if (!inGame) {
            onEditorDone.run();
            return;
        }
        editing = false;
        picked = null;
        if (!save) {                          // back to what is saved
            for (int m = 0; m < MODES; m++) opacity[m] = touchPrefs.getFloat("m" + m + "_opacity", .7f);
            for (Control c : controls) { java.util.Arrays.fill(c.own, false); load(c); }
        }
        useMode(mode);
        updateTilt();
        showToggle();
    }

    /** Back in the game while the in-game editor is open: cancel it. */
    boolean onBack() {
        if (editing && inGame) { finishEditor(false); return true; }
        return false;
    }

    // ------------------------------------------------------------------ utils

    private float dp(float v) { return v * density; }

    private static float round1(float v) { return Math.round(v * 10f) / 10f; }

    private static float clamp(float v, float lo, float hi) { return v < lo ? lo : v > hi ? hi : v; }
}
