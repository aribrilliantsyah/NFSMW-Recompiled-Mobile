package com.ryuna.nfsmw2005x;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
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
 * Where the game's HUD goes: the speedometer, the minimap, the race
 * information and the EA Trax banner, dragged over the game's frame as the
 * renderer shows it (the whole screen with widescreen on, 4:3 pillarboxed
 * off). Saved as offsets from where the game puts them, in FEng units (the
 * frame 480 high, its origin the middle), which Settings writes to
 * layout.toml's [hud] at PLAY and src/game/hud_layout.cpp moves the HUD by.
 * DONE saves, CANCEL (or back) leaves them as they were, RESET puts them back.
 */
public class HudEditorActivity extends Activity {
    private SharedPreferences mPrefs;
    private final float[][] mPos = new float[Settings.HUD_IDS.length][2];

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        mPrefs = Settings.prefs(this);
        for (int i = 0; i < mPos.length; i++) {
            mPos[i][0] = Settings.hud(mPrefs, i, 0);
            mPos[i][1] = Settings.hud(mPrefs, i, 1);
        }
        setContentView(new Board());
        fullScreen();
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
        SharedPreferences.Editor e = mPrefs.edit();
        for (int i = 0; i < mPos.length; i++) {
            e.putFloat(Settings.K_HUD_PREFIX + Settings.HUD_IDS[i] + "_x", mPos[i][0]);
            e.putFloat(Settings.K_HUD_PREFIX + Settings.HUD_IDS[i] + "_y", mPos[i][1]);
        }
        e.apply();
        finish();
    }

    private final class Board extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG),
                text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF reset = new RectF(), cancel = new RectF(), done = new RectF();
        // the frame on the screen and FEng's width for it (src/game/fe_mouse.cpp: 480 x aspect with the
        // console's widescreen flag, from 3:2, else the 640 wide layout stretched)
        private float fx, fy, fw, fh, wide;
        private int drag = -1;
        private float downX, downY, fromX, fromY;

        Board() {
            super(HudEditorActivity.this);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp(2));
            text.setColor(Color.WHITE);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(Typeface.DEFAULT_BOLD);
            text.setTextSize(dp(12));
        }

        private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            float aspect = mPrefs.getBoolean(Settings.K_WIDESCREEN, true) ? Math.max((float) w / h, 4f / 3f) : 4f / 3f;
            fw = Math.min(w, h * aspect);
            fh = fw / aspect;
            fx = (w - fw) / 2;
            fy = (h - fh) / 2;
            wide = aspect >= 1.5f ? 480f * aspect : 640f;
            float bw = dp(96), bh = dp(36), gap = dp(10), top = dp(12), left = w / 2f - (3 * bw + 2 * gap) / 2;
            reset.set(left, top, left + bw, top + bh);
            cancel.set(left + bw + gap, top, left + 2 * bw + gap, top + bh);
            done.set(left + 2 * (bw + gap), top, left + 3 * bw + 2 * gap, top + bh);
        }

        private RectF box(int i) {
            float[] b = Settings.HUD_BOXES[i];
            float cx = fx + (.5f + (b[0] + mPos[i][0]) / wide) * fw, cy = fy + (.5f + (b[1] + mPos[i][1]) / 480f) * fh;
            float hw = b[2] / wide * fw, hh = b[3] / 480f * fh;
            return new RectF(cx - hw, cy - hh, cx + hw, cy + hh);
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawColor(Color.BLACK);
            fill.setColor(0xFF1B1D22);
            c.drawRect(fx, fy, fx + fw, fy + fh, fill);
            text.setColor(0x33FFFFFF);
            text.setTextSize(dp(28));
            c.drawText("GAME", fx + fw / 2, fy + fh / 2, text);
            text.setTextSize(dp(12));
            for (int i = 0; i < mPos.length; i++) {
                RectF r = box(i);
                fill.setColor(i == drag ? 0x996F7432 : 0x553A3D45);
                c.drawRoundRect(r, dp(6), dp(6), fill);
                stroke.setColor(i == drag ? UiKit.ACCENT : 0xCCFFFFFF);
                c.drawRoundRect(r, dp(6), dp(6), stroke);
                text.setColor(Color.WHITE);
                c.drawText(Settings.HUD_LABELS[i], r.centerX(), r.centerY() + dp(4), text);
            }
            button(c, reset, "RESET");
            button(c, cancel, "CANCEL");
            button(c, done, "DONE");
            text.setColor(0xFF9A9A9A);
            c.drawText("Drag the HUD's boxes where they go", getWidth() / 2f, reset.bottom + dp(20), text);
        }

        private void button(Canvas c, RectF r, String label) {
            fill.setColor(0xE0101114);
            c.drawRoundRect(r, dp(8), dp(8), fill);
            stroke.setColor(UiKit.ACCENT);
            c.drawRoundRect(r, dp(8), dp(8), stroke);
            text.setColor(Color.WHITE);
            c.drawText(label, r.centerX(), r.centerY() + dp(4), text);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (reset.contains(x, y)) {
                        for (float[] p : mPos) p[0] = p[1] = 0;
                        invalidate();
                        return true;
                    }
                    if (cancel.contains(x, y)) { finish(); return true; }
                    if (done.contains(x, y)) { save(); return true; }
                    drag = -1;
                    for (int i = mPos.length - 1; i >= 0 && drag < 0; i--) if (box(i).contains(x, y)) drag = i;
                    if (drag >= 0) {
                        downX = x; downY = y;
                        fromX = mPos[drag][0]; fromY = mPos[drag][1];
                    }
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (drag < 0) return true;
                    float[] b = Settings.HUD_BOXES[drag];
                    // the box kept in the frame
                    float limX = wide / 2 - b[2], limY = 240f - b[3];
                    mPos[drag][0] = clamp(b[0] + fromX + (x - downX) / fw * wide, -limX, limX) - b[0];
                    mPos[drag][1] = clamp(b[1] + fromY + (y - downY) / fh * 480f, -limY, limY) - b[1];
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
