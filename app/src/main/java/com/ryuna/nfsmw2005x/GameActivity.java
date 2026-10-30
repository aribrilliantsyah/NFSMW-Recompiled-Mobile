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
    /** The game's menu's trigger prompts as zoom pill labels, {LT, RT} ("" not prompted), null with no menu up
     *  (native/activity.cpp). */
    static native String[] nativeMenuPills();
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

    /** The hamburger: resume, layout editor, or leave for the launcher. */
    private void showMenu() {
        String[] items = { "Resume", "Edit touch layout", "Exit to launcher" };
        android.app.AlertDialog d = new android.app.AlertDialog.Builder(this,
                android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("NFS Most Wanted")
            .setItems(items, (dlg, which) -> {
                if (which == 1 && mPad != null) mPad.openEditor();
                else if (which == 2) exitToLauncher();
            })
            .create();
        d.setOnDismissListener(x -> hideSystemBars());
        d.show();
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
