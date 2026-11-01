package com.ryuna.nfsmw2005x;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

/**
 * The HUD editor, laid out as the touch controls' editor: the game's speedometer, minimap, race information and EA
 * Trax banner, and the FPS counter, as boxes over the game's frame (the whole screen with widescreen on, the console's
 * 4:3 pillarboxed off). A box is picked by a tap and dragged; HIDE / SHOW hides or shows the picked one, − / + sizes
 * the FPS counter (the game's own HUD has one size: its pieces do not scale together). DONE writes the settings file
 * (Settings.updateNative), which the game reads again as it comes back in front (GameActivity.nativeReloadHud), so the
 * HUD moves without a restart; CANCEL (or back) leaves it as it was, RESET puts every box back where the game puts it.
 * Opened from the game (EXTRA_OVER_GAME) the window is see-through: the paused game shows behind the boxes.
 *
 * The game's items are offsets from where the game draws them, in FEng units (the frame 480 high, its origin the
 * middle: src/game/hud_layout.cpp); the counter is its middle in thousandths of the screen (src/common/menu.cpp).
 */
public class HudEditorActivity extends Activity {
    static final String EXTRA_OVER_GAME = "over_game";

    private static final int ITEMS = Settings.HUD_IDS.length;   // the game's; the FPS counter is index ITEMS
    private static final int FPS = ITEMS;

    private final int[] mX = new int[ITEMS], mY = new int[ITEMS];
    private final boolean[] mShown = new boolean[ITEMS + 1];
    private int mFpsX, mFpsY, mFpsScale, mFpsPos;
    private boolean mFpsUsage, mFpsTemps, mOverGame;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        mOverGame = getIntent().getBooleanExtra(EXTRA_OVER_GAME, false);
        load(Settings.nativeSettings(this));
        setContentView(new Board());
        fullScreen();
    }

    private void load(TomlSettings t) {
        for (int i = 0; i < ITEMS; i++) {
            mX[i] = Settings.hudX(t, i);
            mY[i] = Settings.hudY(t, i);
            mShown[i] = Settings.hudShown(t, i);
        }
        mShown[FPS] = t.getBool(Settings.K_FPS, false);
        mFpsPos = (int) t.getInt(Settings.K_FPS_POS, 0);
        mFpsX = Settings.within(t.getInt(Settings.K_FPS_X, 500), 0, 1000, 500);
        mFpsY = Settings.within(t.getInt(Settings.K_FPS_Y, 120), 0, 1000, 120);
        mFpsScale = Settings.fpsScale(t);
        mFpsUsage = t.getBool(Settings.K_FPS_USAGE, true);
        mFpsTemps = t.getBool(Settings.K_FPS_TEMPS, true);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) fullScreen();
    }

    private void fullScreen() {
        Window w = getWindow();
        w.setDecorFitsSystemWindows(false);
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        w.setAttributes(lp);
        WindowInsetsController c = w.getDecorView().getWindowInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    private void save() {
        try {
            Settings.updateNative(this, t -> {
                for (int i = 0; i < ITEMS; i++) {
                    t.put(Settings.hudKey(i, "x"), mX[i]);
                    t.put(Settings.hudKey(i, "y"), mY[i]);
                    t.put(Settings.hudKey(i, "shown"), mShown[i]);
                }
                t.put(Settings.K_FPS, mShown[FPS]);
                t.put(Settings.K_FPS_POS, mFpsPos);
                t.put(Settings.K_FPS_X, mFpsX);
                t.put(Settings.K_FPS_Y, mFpsY);
                t.put(Settings.K_FPS_SCALE, mFpsScale);
            });
        } catch (java.io.IOException e) {
            android.util.Log.w("nfsmw", "cannot save the HUD layout: " + e.getMessage());
        }
        finish();
    }

    private void reset() {
        TomlSettings t = Settings.nativeSettings(this);
        Settings.resetHud(t);
        load(t);
        mShown[FPS] = t.getBool(Settings.K_FPS, false);   // RESET puts places back; the counter stays on or off
    }

    private final class Board extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG),
                ghost = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF minus = new RectF(), plus = new RectF(), hide = new RectF(), resetBtn = new RectF(),
                cancel = new RectF(), done = new RectF(), help = new RectF();
        // the frame on the screen and FEng's width for it (src/game/fe_mouse.cpp: 480 x aspect with the console's
        // widescreen flag, from 3:2, else the 640 wide layout stretched)
        private float fx, fy, fw, fh, wide;
        private int picked = -1, drag = -1;
        private float downX, downY, fromX, fromY;

        Board() {
            super(HudEditorActivity.this);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp(2));
            ghost.setStyle(Paint.Style.STROKE);
            ghost.setStrokeWidth(dp(1.5f));
            ghost.setPathEffect(new DashPathEffect(new float[] {dp(6), dp(4)}, 0));
            text.setColor(Color.WHITE);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(Typeface.DEFAULT_BOLD);
        }

        private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            TomlSettings t = Settings.nativeSettings(getContext());
            float screen = (float) w / h;
            int code = (int) t.getInt(Settings.K_ASPECT, 0);
            float aspect = !t.getBool(Settings.K_WIDESCREEN, true) ? 4f / 3f
                    : code > 0 ? code / 100f : Math.max(screen, 4f / 3f);
            fw = Math.min(w, h * aspect);
            fh = fw / aspect;
            fx = (w - fw) / 2;
            fy = (h - fh) / 2;
            wide = aspect >= 1.5f ? 480f * aspect : 640f;
            // the bar, in the middle of the screen (the game's HUD sits at the edges and corners): − + (the counter's
            // size), HIDE/SHOW, RESET, CANCEL, DONE, and the hint under it. Hidden while a box is dragged
            float bh = dp(36), gap = dp(8), sq = bh, bw = dp(84), top = h / 2f - bh;
            float total = 2 * sq + 4 * bw + 5 * gap, x = w / 2f - total / 2;
            minus.set(x, top, x + sq, top + bh); x += sq + gap;
            plus.set(x, top, x + sq, top + bh); x += sq + gap;
            hide.set(x, top, x + bw, top + bh); x += bw + gap;
            resetBtn.set(x, top, x + bw, top + bh); x += bw + gap;
            cancel.set(x, top, x + bw, top + bh); x += bw + gap;
            done.set(x, top, x + bw, top + bh);
            help.set(w / 2f - dp(240), top + bh + dp(8), w / 2f + dp(240), top + bh + dp(32));
        }

        /** The FPS counter's pill on the screen, as src/common/menu.cpp lays it out. */
        private RectF fpsBox() {
            float w = getWidth(), h = getHeight();
            float font = 13f * Math.max(1f, h / 540f) * mFpsScale / 100f;
            float ph = font * 2.1f;
            float pw = font * (5.5f + (mFpsUsage || mFpsTemps ? 15f : 0f) + (mFpsTemps ? 3.8f : 0f));
            float margin = h * .03f, x, y;
            if (mFpsPos == Settings.FPS_CUSTOM) {
                x = clamp(mFpsX / 1000f * w - pw / 2, 0, Math.max(0, w - pw));
                y = clamp(mFpsY / 1000f * h - ph / 2, 0, Math.max(0, h - ph));
            } else {
                boolean bottom = mFpsPos == 3 || mFpsPos == 4 || mFpsPos == 5;
                y = bottom ? h - margin - ph : margin;
                if (mFpsPos == 0) y = h * .07f + .35f * Math.min(w / 16f, h / 9f) + margin * .5f;
                x = mFpsPos == 1 || mFpsPos == 3 ? margin
                        : mFpsPos == 2 || mFpsPos == 5 ? w - margin - pw : (w - pw) / 2;
            }
            return new RectF(x, y, x + pw, y + ph);
        }

        private RectF box(int i) {
            if (i == FPS) return fpsBox();
            float[] b = Settings.HUD_BOXES[i];
            float cx = fx + (.5f + (b[0] + mX[i]) / wide) * fw, cy = fy + (.5f + (b[1] + mY[i]) / 480f) * fh;
            float hw = b[2] / wide * fw, hh = b[3] / 480f * fh;
            return new RectF(cx - hw, cy - hh, cx + hw, cy + hh);
        }

        private String label(int i) { return i == FPS ? "FPS counter" : Settings.HUD_LABELS[i]; }

        @Override
        protected void onDraw(Canvas c) {
            if (mOverGame) {
                c.drawColor(0x66000000);   // the paused game behind, dimmed
            } else {
                c.drawColor(Color.BLACK);
                fill.setColor(0xFF1B1D22);
                c.drawRect(fx, fy, fx + fw, fy + fh, fill);
            }
            stroke.setColor(0x33FFFFFF);
            stroke.setStrokeWidth(dp(1));
            c.drawRect(fx, fy, fx + fw, fy + fh, stroke);   // the game's frame
            stroke.setStrokeWidth(dp(2));
            text.setTextSize(dp(12));
            for (int i = 0; i <= ITEMS; i++) {
                RectF r = box(i);
                boolean on = i == picked;
                if (mShown[i]) {
                    fill.setColor(on ? 0x996F7432 : 0x663A3D45);
                    c.drawRoundRect(r, dp(6), dp(6), fill);
                    stroke.setColor(on ? UiKit.ACCENT : 0xCCFFFFFF);
                    c.drawRoundRect(r, dp(6), dp(6), stroke);
                    text.setColor(Color.WHITE);
                } else {
                    // hidden: a dashed outline, so it can be picked and shown again
                    ghost.setColor(on ? UiKit.ACCENT : 0x88FFFFFF);
                    c.drawRoundRect(r, dp(6), dp(6), ghost);
                    text.setColor(0x88FFFFFF);
                }
                String s = label(i) + (mShown[i] ? "" : " (hidden)");
                if (i == FPS) s += " " + mFpsScale + "%";
                c.drawText(s, r.centerX(), r.centerY() + dp(4), text);
            }
            if (drag >= 0) return;   // the bar out of the way while a box moves
            boolean fps = picked == FPS;
            button(c, minus, "\u2212", fps);
            button(c, plus, "+", fps);
            button(c, hide, picked >= 0 && !mShown[picked] ? "Show" : "Hide", picked >= 0);
            button(c, resetBtn, "Reset", true);
            button(c, cancel, "Cancel", true);
            button(c, done, "Done", true);
            fill.setColor(0xCC101114);
            c.drawRoundRect(help, dp(6), dp(6), fill);
            text.setColor(0xFFBDBDBD);
            text.setTextSize(dp(11));
            c.drawText(picked < 0 ? "Tap an item to pick it, drag to move it"
                            : picked == FPS ? "Drag to move, \u2212 + to resize, Hide to turn the counter off"
                            : "Drag to move, Hide to take it off the screen",
                    help.centerX(), help.centerY() + dp(4), text);
        }

        private void button(Canvas c, RectF r, String label, boolean enabled) {
            fill.setColor(0xE0101114);
            c.drawRoundRect(r, dp(8), dp(8), fill);
            stroke.setColor(enabled ? UiKit.ACCENT : 0x55FFFFFF);
            c.drawRoundRect(r, dp(8), dp(8), stroke);
            text.setColor(enabled ? Color.WHITE : 0x66FFFFFF);
            text.setTextSize(dp(label.length() == 1 ? 18 : 12));
            c.drawText(label, r.centerX(), r.centerY() + dp(label.length() == 1 ? 6 : 4), text);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (resetBtn.contains(x, y)) { reset(); picked = -1; invalidate(); return true; }
                    if (cancel.contains(x, y)) { finish(); return true; }
                    if (done.contains(x, y)) { save(); return true; }
                    if (picked == FPS && (minus.contains(x, y) || plus.contains(x, y))) {
                        int step = minus.contains(x, y) ? -10 : 10;
                        mFpsScale = Math.max(Settings.FPS_SCALE_MIN, Math.min(Settings.FPS_SCALE_MAX, mFpsScale + step));
                        invalidate();
                        return true;
                    }
                    if (hide.contains(x, y)) {
                        if (picked >= 0) mShown[picked] = !mShown[picked];
                        invalidate();
                        return true;
                    }
                    if (minus.contains(x, y) || plus.contains(x, y) || help.contains(x, y)) return true;
                    drag = -1;
                    // the counter first: it is drawn last, over the others
                    for (int i = ITEMS; i >= 0 && drag < 0; i--) if (box(i).contains(x, y)) drag = i;
                    picked = drag;
                    if (drag == FPS) {
                        RectF r = fpsBox();
                        fromX = r.centerX();
                        fromY = r.centerY();
                    } else if (drag >= 0) {
                        fromX = mX[drag];
                        fromY = mY[drag];
                    }
                    downX = x;
                    downY = y;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (drag < 0) return true;
                    if (drag == FPS) {
                        RectF r = fpsBox();
                        float w = getWidth(), h = getHeight();
                        float cx = clamp(fromX + x - downX, r.width() / 2, w - r.width() / 2);
                        float cy = clamp(fromY + y - downY, r.height() / 2, h - r.height() / 2);
                        mFpsPos = Settings.FPS_CUSTOM;
                        mFpsX = Math.round(cx / w * 1000);
                        mFpsY = Math.round(cy / h * 1000);
                    } else {
                        float[] b = Settings.HUD_BOXES[drag];
                        // the box kept in the frame
                        float limX = wide / 2 - b[2], limY = 240f - b[3];
                        mX[drag] = Math.round(clamp(b[0] + fromX + (x - downX) / fw * wide, -limX, limX) - b[0]);
                        mY[drag] = Math.round(clamp(b[1] + fromY + (y - downY) / fh * 480f, -limY, limY) - b[1]);
                    }
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    drag = -1;
                    invalidate();
                    return true;
                default:
                    return true;
            }
        }
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
