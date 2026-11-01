package com.ryuna.nfsmw2005x;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLControllerManager;

/**
 * The game. SDL3's SDLActivity creates the surface, the audio device and the
 * input plumbing, loads libSDL3.so and libmain.so and runs SDL_main
 * (native/main.cpp) on its own thread. On top: TouchControllerView (the
 * touch pad and its in-game editor), whose state goes to controller port 0
 * through nativeSetPad / nativeSetPadRight (native/activity.cpp). Runs in its
 * own process (":game"), which ends with it.
 */
public class GameActivity extends SDLActivity {
    static native void nativeSetPad(int buttons, int face, int lt, int rt, int lx, int ly);
    static native void nativeSetPadRight(int rx, int ry);
    /** The game menu's button prompts as { icon, pad, label, ... }: icon a ButtonArt.Icon name chosen by the string's
     *  hash ("" when unknown), pad one of A B X Y LT RT, label the game's text; null with no menu up
     *  (native/activity.cpp). */
    static native String[] nativeMenuPrompts();
    /** The game is in a race (free roam too): the race controls' state in MOBILE (native/activity.cpp). */
    static native boolean nativeRacing();
    /** The overlay's ☰: the game's settings, -1 their categories, else one; in a race it pauses first
     *  (src/game/settings_menu.cpp). */
    static native void nativeOpenSettings(int category);
    /** The ☰ card: true as it opens (a race with no menu up pauses), false on its Resume; that START only if the
     *  card's paused the race (src/game/settings_menu.cpp). */
    static native void nativeCardPause(boolean pause);
    // A controller some phones' game services pass on as a virtual device (MIUI's took a DualShock 4 on a Redmi
    // Note 8 Pro): SDL sees no controller in it and drops its input. Its buttons and sticks go to port 0 here.
    private static native void nativeLooseKey(int keyCode, boolean down);
    private static native void nativeLooseAxes(float lx, float ly, float rx, float ry, float lt, float rt, float hx,
                                               float hy);
    private static native void nativeLooseRelease();

    private static GameActivity sSelf;
    private static boolean sVibrate;
    private TouchControllerView mPad;

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL3", "main" };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        super.onCreate(savedInstanceState);
        if (mBrokenLibraries || mLayout == null) return;     // SDL failed to start (shows its own dialog)
        sSelf = this;
        hideSystemBars();
        SharedPreferences p = Settings.prefs(this);
        sVibrate = p.getBoolean(Settings.K_VIBRATE, true);
        mPad = new TouchControllerView(this);
        mPad.onMenu = this::showMenu;
        mPad.onSettings = this::showMenu;  // the race's ☰: the touch panel, whose Game settings opens the game's
        mLayout.addView(mPad, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                         ViewGroup.LayoutParams.MATCH_PARENT));
        nativeSetPad(0, 0, 0, 0, 0, 0);
    }

    /** Full screen under the cutout; bars come back on a swipe. */
    private void hideSystemBars() {
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

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
        else if (!mBrokenLibraries) nativeLooseRelease();  // its ups go elsewhere while the game is not in front
    }

    /** SDL asks for an orientation from the window size and hint; the game
     *  is landscape only, whatever it asks. */
    @Override
    public void setOrientationBis(int w, int h, boolean resizable, String hint) {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    @Override
    protected void onPause() {
        if (mPad != null) mPad.onPauseResume(false);
        rumbleNow(0, 0);
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mPad != null) mPad.onPauseResume(true);
    }

    /** The ☰: a floating card over a dimmed game (the touch controls' settings: mode, MOBILE's steering, tilt,
     *  opacity, size, the editors; then the game's settings, resume and leaving for the launcher). Opening it pauses a
     *  race with no menu up (nativeCardPause, one START); Resume, a tap on the scrim or Back unpause only if the card
     *  paused it. Every change goes to mostwanted.toml under its lock (TouchControllerView.settingsRow), as the
     *  launcher's do. */
    private void showMenu() {
        if (mPad == null) return;
        UiKit ui = new UiKit(this);
        // the card: the touch rows scroll when the screen is short; GAME (Resume...) stays at its foot, always in view
        android.widget.LinearLayout card = ui.column();
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        android.widget.LinearLayout col = ui.column();
        col.setPadding(ui.dp(18), ui.dp(14), ui.dp(18), 0);
        scroll.addView(col);
        card.addView(scroll, new android.widget.LinearLayout.LayoutParams(-1, 0, 1f));
        android.app.AlertDialog[] dlg = new android.app.AlertDialog[1];
        boolean[] resume = {true};  // the card closing hands the game back (Resume, scrim, Back); false: it goes on
        Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            col.removeAllViews();
            TomlSettings t = Settings.nativeSettings(this);
            int mode = Settings.padMode(t), steer = Settings.steer(t);
            col.addView(ui.section("Touch controls"));
            col.addView(ui.segmented("Layout", null, new String[] {"MOBILE", "XBOX"},
                    new int[] {Settings.MODE_MOBILE, Settings.MODE_XBOX}, mode, v -> {
                        mPad.setControl(f -> Settings.putPadMode(f, v));
                        fill[0].run();
                    }));
            if (mode == Settings.MODE_MOBILE) {
                col.addView(ui.segmented("Steering", null, new String[] {"Stick", "Arrows", "Wheel", "Tilt"},
                        new int[] {Settings.STEER_STICK, Settings.STEER_ARROWS, Settings.STEER_WHEEL,
                                   Settings.STEER_TILT}, steer, v -> {
                            mPad.setControl(f -> Settings.putSteer(f, v));
                            fill[0].run();
                        }));
                if (steer == Settings.STEER_TILT)
                    col.addView(ui.stepper("Tilt sensitivity", null, s -> mPad.settingsRow(2, s)));
            }
            col.addView(ui.stepper("Opacity", null, s -> mPad.settingsRow(3, s)));
            col.addView(ui.stepper("Size", null, s -> mPad.settingsRow(4, s)));
            android.widget.LinearLayout edits = new android.widget.LinearLayout(this);
            edits.setPadding(0, ui.dp(6), 0, ui.dp(10));
            android.widget.LinearLayout.LayoutParams half = new android.widget.LinearLayout.LayoutParams(0, -2, 1f);
            edits.addView(ui.secondary("Edit buttons", v -> { resume[0] = false; dlg[0].dismiss(); mPad.openEditor(); }), half);
            android.widget.LinearLayout.LayoutParams half2 = new android.widget.LinearLayout.LayoutParams(0, -2, 1f);
            half2.leftMargin = ui.dp(8);
            edits.addView(ui.secondary("Edit HUD (next start)", v -> {
                resume[0] = false;
                dlg[0].dismiss();
                startActivity(new Intent(this, HudEditorActivity.class));
            }), half2);
            col.addView(edits);
        };
        fill[0].run();
        android.widget.LinearLayout foot = ui.column();
        foot.setPadding(ui.dp(18), ui.dp(4), ui.dp(18), ui.dp(14));
        foot.addView(ui.section("Game"));
        android.widget.LinearLayout game = new android.widget.LinearLayout(this);
        game.addView(ui.secondary("Resume", v -> dlg[0].dismiss()),
                new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
        android.widget.LinearLayout.LayoutParams gp = new android.widget.LinearLayout.LayoutParams(0, -2, 1f);
        gp.leftMargin = ui.dp(8);
        game.addView(ui.secondary("Game settings", v -> {
            resume[0] = false;
            dlg[0].dismiss();
            nativeOpenSettings(-1);
        }), gp);
        android.widget.LinearLayout.LayoutParams gp2 = new android.widget.LinearLayout.LayoutParams(0, -2, 1f);
        gp2.leftMargin = ui.dp(8);
        game.addView(ui.secondary("Exit to launcher", v -> {
            resume[0] = false;
            dlg[0].dismiss();
            exitToLauncher();
        }), gp2);
        foot.addView(game);
        card.addView(foot);
        dlg[0] = new android.app.AlertDialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar) {
            // the ☰'s own touch goes on into the card (it opens on its down): its up is no tap on the scrim
            private boolean armed;
            @Override public boolean onTouchEvent(android.view.MotionEvent e) {
                if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) armed = true;
                return armed ? super.onTouchEvent(e) : true;
            }
        };
        dlg[0].setView(card);
        dlg[0].setCanceledOnTouchOutside(true);  // the scrim: a tap outside the card is Resume
        dlg[0].setOnShowListener(x -> {
            Window w = dlg[0].getWindow();
            if (w != null) {
                // the card: rounded, centred, as tall as its rows up to 88% of the screen (then its rows scroll)
                w.setBackgroundDrawable(ui.box(UiKit.CARD, UiKit.LINE, 20));
                w.setGravity(android.view.Gravity.CENTER);
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                w.setDimAmount(.6f);
                android.util.DisplayMetrics m = getResources().getDisplayMetrics();
                int width = Math.min(m.widthPixels * 3 / 5, ui.dp(560));
                card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                w.setLayout(width, Math.min(card.getMeasuredHeight(), m.heightPixels * 88 / 100));
            }
        });
        dlg[0].setOnDismissListener(x -> {
            if (resume[0]) nativeCardPause(false);
            hideSystemBars();
        });
        nativeCardPause(true);
        dlg[0].show();
    }

    private void exitToLauncher() {
        Intent i = new Intent(this, LauncherActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(i);
        finish();          // onDestroy ends the :game process
    }

    @Override
    public void onBackPressed() {
        if (mPad != null && mPad.onBack()) return;
        super.onBackPressed();
    }

    private static boolean loose(InputEvent e, int sources) {
        return !mBrokenLibraries && (e.getSource() & sources) != 0
                && !SDLControllerManager.isDeviceSDLJoystick(e.getDeviceId());
    }

    // A physical pad in use hides the touch overlay; one SDL does not know goes to port 0 itself.
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (mPad != null && isPad(e.getSource())) mPad.onPhysicalInput();
        boolean pad = KeyEvent.isGamepadButton(e.getKeyCode()) || (e.getKeyCode() >= KeyEvent.KEYCODE_DPAD_UP
                && e.getKeyCode() <= KeyEvent.KEYCODE_DPAD_RIGHT);
        if (pad && loose(e, InputDevice.SOURCE_GAMEPAD | InputDevice.SOURCE_JOYSTICK)) {
            if (e.getRepeatCount() == 0) nativeLooseKey(e.getKeyCode(), e.getAction() == KeyEvent.ACTION_DOWN);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (mPad != null && isPad(e.getSource())) mPad.onPhysicalInput();
        if (loose(e, InputDevice.SOURCE_JOYSTICK) && e.getActionMasked() == MotionEvent.ACTION_MOVE) {
            // the right stick on Z and RZ, the triggers on LTRIGGER and RTRIGGER or BRAKE and GAS, as Android has them
            nativeLooseAxes(e.getAxisValue(MotionEvent.AXIS_X), e.getAxisValue(MotionEvent.AXIS_Y),
                    e.getAxisValue(MotionEvent.AXIS_Z), e.getAxisValue(MotionEvent.AXIS_RZ),
                    Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE)),
                    Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS)),
                    e.getAxisValue(MotionEvent.AXIS_HAT_X), e.getAxisValue(MotionEvent.AXIS_HAT_Y));
            return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }

    private static boolean isPad(int src) {
        return (src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
            || (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    @Override
    protected void onDestroy() {
        rumbleNow(0, 0);
        sSelf = null;
        boolean finishing = isFinishing();
        super.onDestroy();
        // The recompiled game cannot start twice in one process; it runs in
        // its own (":game"), which goes with the activity, so Play from the
        // launcher always gets a fresh one.
        if (finishing)
            android.os.Process.killProcess(android.os.Process.myPid());
    }

    /** From native (native/stats.cpp), the performance overlay's battery temperature in tenths of a degree, -1 unknown. */
    @SuppressWarnings("unused")
    public int batteryTemperature() {
        Intent i = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return i != null ? i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) : -1;
    }

    // ------------------------------------------------------- in-game settings

    /** From native (native/activity.cpp ShellRow), the game thread: the game's CONTROLS rows, which are the
     *  overlay's (TouchControllerView.settingsRow; 5 its editor, 6 the HUD editor, opened on a step). Runs on the UI
     *  thread, waited for a second at most; null when it did not answer by then (the row shows its last value; a change
     *  not begun yet is cancelled, one begun is made, and native never makes it itself on Android). */
    @SuppressWarnings("unused")
    static String settingsRow(int row, int step) {
        GameActivity a = sSelf;
        if (a == null || a.mPad == null) return null;
        if (row >= 5) {
            if (step != 0) a.runOnUiThread(() -> {
                if (row == 5) a.mPad.openEditor();
                else a.startActivity(new Intent(a, HudEditorActivity.class));
            });
            return "";
        }
        java.util.concurrent.FutureTask<String> t =
            new java.util.concurrent.FutureTask<>(() -> a.mPad.settingsRow(row, step));
        a.runOnUiThread(t);
        try {
            return t.get(1, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            t.cancel(false);
            android.util.Log.w("nfsmw", "settings row " + row + ": the overlay did not answer: " + e);
            return null;
        }
    }

    // ------------------------------------------------------------- rumble

    /** From native (native/activity.cpp), the game thread: the game's rumble,
     *  0..65535 per motor, when no physical pad takes it. */
    @SuppressWarnings("unused")
    static void rumble(int left, int right) {
        if (!sVibrate) return;
        GameActivity a = sSelf;
        if (a != null) a.rumbleNow(left, right);
    }

    private void rumbleNow(int left, int right) {
        Vibrator v;
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            v = vm == null ? null : vm.getDefaultVibrator();
        } else {
            v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }
        if (v == null || !v.hasVibrator()) return;
        int amp = Math.max(left, right) >> 8;          // 0..255
        if (amp < 8) { v.cancel(); return; }
        // A second at that strength; the game sends a new level when it
        // changes, and zero to stop.
        v.vibrate(VibrationEffect.createOneShot(1000, Math.min(255, Math.max(1, amp))));
    }
}
