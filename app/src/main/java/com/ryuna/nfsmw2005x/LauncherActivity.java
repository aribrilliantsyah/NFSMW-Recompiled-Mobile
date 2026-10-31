package com.ryuna.nfsmw2005x;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.View;
import android.widget.EditText;
import java.io.File;
import java.io.IOException;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Start screen, laid out like NFSMW-Recompiled-Mobile's SetupActivity: a
 * title, then dark cards -- Game (folder + PLAY), Display, Touch controls
 * (editor, layout, steering, tilt, vibration), Controller (mapping),
 * Graphics, Sound, Advanced (collapsed). Landscape: two columns of cards.
 */
public class LauncherActivity extends Activity {
    private static final int REQ_TREE = 1, REQ_ISO = 2, REQ_BACKUP = 3, REQ_RESTORE = 4;

    private SharedPreferences mPrefs;
    private TomlSettings mToml;
    private long mTomlStamp;
    private UiKit ui;
    private EditText mDir;
    private TextView mDirStatus, mPadStatus;
    private View mPlay;
    private android.widget.Button mIsoBtn, mIsoSmall;
    private TextView mPlayHint;
    private volatile boolean mCancelExtract;
    private LinearLayout mSlotList;
    // the slot (and the game folder it is in) whose backup the document picker is naming: kept over the launcher
    // being recreated while the picker is up
    private String mBackupSlot, mBackupGame;
    /** Slot work, one job at a time for every launcher instance; PLAY waits for none of it, it refuses. */
    private static final java.util.concurrent.ExecutorService SAVES =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "saves"));
    private static final java.util.concurrent.atomic.AtomicInteger sSaveJobs =
        new java.util.concurrent.atomic.AtomicInteger();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (b != null) {
            mBackupSlot = b.getString("backup_slot");
            mBackupGame = b.getString("backup_game");
        }
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        mPrefs = Settings.prefs(this);
        ui = new UiKit(this);
        loadNative();
        migrateSaves();             // a failure is shown; PLAY tries again and refuses until it succeeds
        setContentView(build());
        refreshDir();
        android.view.WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(android.view.WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("backup_slot", mBackupSlot);
        out.putString("backup_game", mBackupGame);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // back from the game, whose menu may have changed the settings file
        if (Settings.nativeFile(this).lastModified() != mTomlStamp) {
            loadNative();
            setContentView(build());
        }
        refreshDir();               // back from the permission screen
        refreshPads();
        refreshSaves();             // back from the game: dates, sizes, the cheats mark
    }

    // ---------------------------------------------------------------- layout

    private View build() {
        LinearLayout page = ui.column();
        page.setBackgroundColor(UiKit.BG);
        page.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.BOTTOM);
        TextView title = ui.title("Need for Speed: Most Wanted");
        title.setTextSize(26);
        head.addView(title);
        TextView ver = ui.summary("  nfsmw " + versionName() + " · Xbox static recompilation");
        ver.setPadding(0, 0, 0, ui.dp(4));
        head.addView(ver);
        page.addView(head);
        page.addView(ui.summary("You need your own copy of the game (the Xbox disc image, .iso). This app contains no game data."));
        page.addView(ui.gap(10));

        LinearLayout cols = new LinearLayout(this);
        LinearLayout left = ui.column(), right = ui.column();
        cols.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        cols.addView(ui.gap(10));
        cols.addView(right, new LinearLayout.LayoutParams(0, -2, 1f));
        page.addView(cols);

        left.addView(gameCard());
        left.addView(savesCard());
        left.addView(touchCard());
        left.addView(controllerCard());
        left.addView(gameplayCard());
        right.addView(displayCard());
        right.addView(graphicsCard());
        right.addView(soundCard());
        right.addView(advancedCard());

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(UiKit.BG);
        sv.setFillViewport(true);
        sv.addView(page);
        return sv;
    }

    private LinearLayout gameCard() {
        LinearLayout c = ui.card();
        c.addView(ui.section("GAME"));
        // No game yet: the ISO is the way in (extracted once into the app's
        // folder). With a game there, PLAY.
        mIsoBtn = ui.primary("INSTALL FROM ISO…", v -> pickIso());
        c.addView(mIsoBtn, new LinearLayout.LayoutParams(-1, -2));
        mPlayHint = ui.summary("Pick your NFS Most Wanted disc image (.iso, Xbox NTSC-U). It is extracted once "
                + "into the app's folder (~2.9 GB); afterwards the .iso can be deleted. "
                + "Extracted it on a PC already? Choose that folder below.");
        mPlayHint.setPadding(0, ui.dp(4), 0, ui.dp(10));
        c.addView(mPlayHint);
        c.addView(ui.text("Game folder (contains default.xbe)", 13, UiKit.DIM));
        mDir = new EditText(this);
        mDir.setSingleLine(true);
        mDir.setEllipsize(android.text.TextUtils.TruncateAt.START);
        mDir.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        mDir.setTextSize(13);
        mDir.setTextColor(UiKit.FG);
        mDir.setTypeface(Typeface.MONOSPACE);
        mDir.setBackground(ui.box(UiKit.PANEL, UiKit.LINE, 8));
        mDir.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8));
        mDir.setText(Settings.gameDir(this));
        mDir.setOnFocusChangeListener((v, f) -> { if (!f) saveDir(mDir.getText().toString()); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = ui.dp(4);
        c.addView(mDir, lp);
        mDirStatus = ui.summary("");
        mDirStatus.setPadding(0, ui.dp(4), 0, ui.dp(6));
        c.addView(mDirStatus);
        LinearLayout row = new LinearLayout(this);
        row.addView(ui.secondary("Choose folder…", v -> pickFolder()), new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(ui.gap(8));
        row.addView(ui.secondary("Use app folder", v -> {
            mDir.setText(Settings.defaultGameDir(this));
            saveDir(mDir.getText().toString());
        }), new LinearLayout.LayoutParams(0, -2, 1f));
        c.addView(row);
        // with a game installed: the ISO install stays here, small
        mIsoSmall = ui.secondary("Install again from ISO…", v -> pickIso());
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(-1, -2);
        il.topMargin = ui.dp(8);
        c.addView(mIsoSmall, il);
        mPlay = ui.primary("PLAY", v -> play());
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, -2);
        pl.topMargin = ui.dp(12);
        c.addView(mPlay, pl);
        return c;
    }

    // ---------------------------------------------------------------- saves

    private LinearLayout savesCard() {
        LinearLayout c = ui.card();
        c.addView(ui.section("SAVES"));
        c.addView(ui.summary("Save slots live in the game folder (saves/). Under Android/data they are removed with "
                + "the app: back up first."));
        mSlotList = ui.column();
        c.addView(mSlotList);
        LinearLayout row = new LinearLayout(this);
        row.addView(ui.secondary("New slot", v -> askName("New slot", "", n -> {
            SaveSlots slots = slots();
            job("Slot " + n.trim() + " created", () -> slots.create(n));
        })), new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(ui.gap(8));
        row.addView(ui.secondary("Restore backup…", v -> pickRestore()), new LinearLayout.LayoutParams(0, -2, 1f));
        c.addView(row);
        refreshSaves();
        return c;
    }

    private void refreshSaves() {
        if (mSlotList == null) return;
        mSlotList.removeAllViews();
        if (Settings.checkGameDir(Settings.gameDir(this)) != null) {
            mSlotList.addView(ui.summary("Choose the game folder first."));
            return;
        }
        String active = Settings.saveSlot(this);
        java.util.List<SaveSlots.Slot> slots = Settings.saveSlots(this).list();
        if (slots.isEmpty()) mSlotList.addView(ui.summary("No slots yet: PLAY makes Main."));
        java.text.DateFormat df = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,
                                                                           java.text.DateFormat.SHORT);
        for (SaveSlots.Slot s : slots) {
            boolean on = s.name.equals(active);
            StringBuilder sum = new StringBuilder();
            if (s.profile != null) sum.append(s.profile).append(" · ");
            sum.append(df.format(new java.util.Date(s.lastModified))).append(" · ")
               .append(String.format(java.util.Locale.US, "%,d KB", (s.sizeBytes + 1023) >> 10));
            LinearLayout r = ui.column();
            r.setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6));
            r.setBackground(ui.box(on ? UiKit.PANEL : UiKit.CARD, on ? UiKit.OK : UiKit.LINE, 8));
            LinearLayout top = new LinearLayout(this);
            top.addView(ui.text((on ? "● " : "") + s.name, 15, on ? UiKit.OK : UiKit.FG));
            if (s.cheatsUsed) {
                TextView tag = ui.text("  CHEATS ON", 11, 0xFFF28C1C);
                tag.setTypeface(Typeface.DEFAULT_BOLD);
                top.addView(tag);
            }
            r.addView(top);
            r.addView(ui.summary(sum.toString()));
            r.setOnClickListener(v -> slotActions(s.name));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = ui.dp(6);
            mSlotList.addView(r, lp);
        }
    }

    private void slotActions(String name) {
        String[] acts = {"Select", "Duplicate", "Rename", "Delete", "Back up…"};
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(name)
            .setItems(acts, (d, w) -> {
                switch (w) {
                    case 0:
                        mPrefs.edit().putString(Settings.K_SAVE_SLOT, name).apply();
                        refreshSaves();
                        break;
                    case 1:
                        askName("Copy of " + name, name + " copy", n -> {
                            SaveSlots slots = slots();
                            job("Copied into " + n.trim(), () -> slots.duplicate(name, n));
                        });
                        break;
                    case 2:
                        if (refuseWhileGame()) return;
                        askName("Rename " + name, name, n -> {
                            // the folder the slot is in, fixed now: the job may run after another is chosen
                            String game = Settings.gameDir(this);
                            SaveSlots slots = slots();
                            job("Renamed to " + n.trim(), () -> {
                                slots.rename(name, n);
                                if (game.equals(Settings.gameDir(this)) && name.equals(Settings.saveSlot(this)))
                                    mPrefs.edit().putString(Settings.K_SAVE_SLOT, n.trim()).apply();
                            });
                        });
                        break;
                    case 3:
                        if (refuseWhileGame()) return;
                        SaveSlots slots = slots();
                        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("Delete " + name + "?")
                            .setMessage("Its saves are gone for good unless you have a backup.")
                            .setPositiveButton("Delete", (d2, w2) -> job("Deleted " + name, () -> slots.delete(name)))
                            .setNegativeButton("Cancel", null)
                            .show();
                        break;
                    default:
                        pickBackup(name);
                }
            })
            .show();
    }

    private void askName(String title, String def, java.util.function.Consumer<String> ok) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(def);
        e.setSelectAllOnFocus(true);
        LinearLayout box = ui.column();
        box.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0);
        box.addView(e, new LinearLayout.LayoutParams(-1, -2));
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK", (d, w) -> ok.accept(e.getText().toString()))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private interface SaveJob { void run() throws IOException; }

    /** File work on the one saves thread, the list again and the outcome after. */
    private void job(String done, SaveJob j) {
        sSaveJobs.incrementAndGet();
        SAVES.execute(() -> {
            String err = null;
            try {
                j.run();
            } catch (IOException | RuntimeException e) {   // SAF's providers throw SecurityException and the like
                err = e.getMessage() != null ? e.getMessage() : e.toString();
            } finally {
                sSaveJobs.decrementAndGet();
            }
            final String msg = err;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                refreshSaves();
                if (msg != null) alert("Saves", msg);
                else Toast.makeText(this, done, Toast.LENGTH_SHORT).show();
            });
        });
    }

    /** The slots of the game folder, refusing the changes the running game must not see (asked again right before
     *  each one changes a slot: the game may have started since the button was pressed). */
    private SaveSlots slots() {
        return Settings.saveSlots(this).guard(() -> {
            if (gameRunning()) throw new IOException("The game is running: exit it (☰ > Exit) first.");
        });
    }

    private boolean gameRunning() {
        android.app.ActivityManager am = getSystemService(android.app.ActivityManager.class);
        java.util.List<android.app.ActivityManager.RunningAppProcessInfo> ps = am == null ? null
                : am.getRunningAppProcesses();
        if (ps != null)
            for (android.app.ActivityManager.RunningAppProcessInfo p : ps)
                if ((getPackageName() + ":game").equals(p.processName)) return true;
        return false;
    }

    /** The game reads and writes the slot while it runs: no restore, delete or rename under it. */
    private boolean refuseWhileGame() {
        if (!gameRunning()) return false;
        alert("The game is running", "Exit the game (☰ > Exit) first.");
        return true;
    }

    private void pickBackup(String slot) {
        mBackupSlot = slot;
        mBackupGame = Settings.gameDir(this);
        String day = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(new java.util.Date());
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_TITLE, "NFSMW-" + slot + "-" + day + ".zip");
        try {
            startActivityForResult(i, REQ_BACKUP);
        } catch (Exception e) {
            Toast.makeText(this, "No file picker on this phone.", Toast.LENGTH_LONG).show();
        }
    }

    private void pickRestore() {
        if (Settings.checkGameDir(Settings.gameDir(this)) != null) {
            Toast.makeText(this, "Choose the game folder first.", Toast.LENGTH_LONG).show();
            return;
        }
        if (refuseWhileGame()) return;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"application/zip", "application/x-zip-compressed",
                                                              "application/octet-stream"});
        try {
            startActivityForResult(i, REQ_RESTORE);
        } catch (Exception e) {
            Toast.makeText(this, "No file picker on this phone.", Toast.LENGTH_LONG).show();
        }
    }

    private void backup(Uri uri, String slot, String gameDir) {
        job("Backed up " + slot, () -> {
            try (java.io.OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new IOException("Cannot write the backup file");
                new SaveSlots(new File(gameDir)).backup(slot, out);
            }
        });
    }

    /** A picked zip: into the slot its name gives (NFSMW-<slot>-<date>.zip) or one the player names. */
    private void restore(Uri uri) {
        String file = null;
        try (android.database.Cursor c = getContentResolver().query(uri,
                new String[] {android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) file = c.getString(0);
        } catch (Exception ignored) { }
        String def = "Restored";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("NFSMW-(.+)-\\d{8}.*\\.zip")
                .matcher(file == null ? "" : file);
        if (m.matches()) def = m.group(1);
        askName("Restore into slot", def, n -> {
            SaveSlots slots = slots();
            String name = n.trim();
            Runnable go = () -> job("Restored into " + name, () -> {
                try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IOException("Cannot read the backup file");
                    slots.restore(in, name, true);
                }
            });
            if (!name.isEmpty() && slots.dir(name).exists()) {
                if (refuseWhileGame()) return;
                new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Replace " + name + "?")
                    .setMessage("Its current saves go into an auto-" + name + "-….zip in the saves folder first.")
                    .setPositiveButton("Replace", (d, w) -> go.run())
                    .setNegativeButton("Cancel", null)
                    .show();
            } else {
                go.run();
            }
        });
    }

    /** Once: the hard disk of the builds before slots (files/hdd, else game/UDATA) becomes Main. False (the reason
     *  shown) when it failed: the old saves stay where they were and the next start or PLAY tries again. */
    private boolean migrateSaves() {
        if (mPrefs.getBoolean(Settings.K_SAVES_MIGRATED, false)) return true;
        String game = Settings.gameDir(this);
        if (Settings.checkGameDir(game) != null) return true;
        if (sSaveJobs.get() > 0) return false;   // a job holds the slots; PLAY refuses then anyway
        try {
            Settings.saveSlots(this).migrateLegacy(new File(Settings.filesDir(this), "hdd"), new File(game, "UDATA"));
            mPrefs.edit().putBoolean(Settings.K_SAVES_MIGRATED, true).apply();
            return true;
        } catch (IOException e) {
            alert("Cannot move the saves into slots", e.getMessage() + "\n\nThe old saves are untouched.");
            return false;
        }
    }

    private LinearLayout touchCard() {
        LinearLayout c = ui.card();
        c.addView(ui.section("TOUCH CONTROLS"));
        c.addView(ui.summary("Move the touch buttons and change their size and opacity, for each layout. "
                + "In the game: the ⚙ next to HIDE/TOUCH opens the same editor."));
        c.addView(ui.gap(6));
        c.addView(ui.secondary("Edit touch controls", v -> startActivity(new Intent(this, EditorActivity.class))));
        c.addView(ui.gap(6));
        c.addView(ui.secondary("Edit HUD position", v -> startActivity(new Intent(this, HudEditorActivity.class))));
        c.addView(ui.toggle("On-screen controller", null, mPrefs.getBoolean(Settings.K_PAD, true),
                v -> pref(Settings.K_PAD, v)));
        c.addView(ui.segmented("Control mode", "MOBILE: steering, pedals, nitrous, e-brake, Speedbreaker; in the game's menus, its buttons. XBOX: the whole Xbox controller.",
                new String[] {"MOBILE", "XBOX"}, new int[] {Settings.MODE_MOBILE, Settings.MODE_XBOX},
                num(Settings.K_PAD_MODE, Settings.MODE_MOBILE), v -> put(Settings.K_PAD_MODE, v)));
        c.addView(ui.segmented("Steering (MOBILE)", "Stick: analog. Arrows: two big buttons. Wheel: drag round the wheel. Tilt: turn the phone like a wheel.",
                new String[] {"Stick", "Arrows", "Wheel", "Tilt"},
                new int[] {Settings.STEER_STICK, Settings.STEER_ARROWS, Settings.STEER_WHEEL, Settings.STEER_TILT},
                num(Settings.K_STEER, Settings.STEER_STICK), v -> put(Settings.K_STEER, v)));
        c.addView(ui.slider("Tilt sensitivity", "Further right, less tilt is needed to steer all the way.",
                1, 10, num(Settings.K_TILT_SENS, 5),
                s -> s + " · full lock at " + Math.round(Settings.tiltAngle(s)) + "°",
                v -> put(Settings.K_TILT_SENS, v)));
        c.addView(ui.toggle("Hide with a controller", "Touch controls go away while a physical controller is used; touch the screen to bring them back.",
                mPrefs.getBoolean(Settings.K_PAD_AUTOHIDE, true), v -> pref(Settings.K_PAD_AUTOHIDE, v)));
        c.addView(ui.toggle("Button feedback", "A short tick when a touch button is pressed.",
                mPrefs.getBoolean(Settings.K_HAPTIC, true), v -> pref(Settings.K_HAPTIC, v)));
        c.addView(ui.toggle("Vibration", "Crashes and takedowns rumble the controller; with none connected, the phone vibrates.",
                mPrefs.getBoolean(Settings.K_VIBRATE, true), v -> pref(Settings.K_VIBRATE, v)));
        c.addView(ui.secondary("Test vibration", v -> testVibration()));
        return c;
    }

    private LinearLayout controllerCard() {
        LinearLayout c = ui.card();
        c.addView(ui.section("CONTROLLER"));
        mPadStatus = ui.summary("");
        c.addView(mPadStatus);
        c.addView(ui.gap(6));
        c.addView(ui.secondary("Controller mapping", v -> startActivity(new Intent(this, GamepadMapActivity.class))));
        return c;
    }

    private LinearLayout gameplayCard() {
        LinearLayout c = ui.card();
        c.addView(ui.section("GAMEPLAY"));
        c.addView(ui.segmented("Language", "The recompilation's own text and prompts. Indonesian also translates the game's menus.",
                new String[] {"English", "Indonesia"}, new int[] {0, 1},
                "ind".equals(mToml.getString(Settings.K_LANGUAGE, "int")) ? 1 : 0,
                v -> put(Settings.K_LANGUAGE, v == 1 ? "ind" : "int")));
        c.addView(ui.toggle("Skip movies", "The intro and the game's videos end as they start (the engine's cutscenes still play).",
                flag(Settings.K_SKIP_MOVIES, false), v -> put(Settings.K_SKIP_MOVIES, v)));
        TextView head = ui.section("▸ CHEATS");
        c.addView(head);
        LinearLayout body = ui.column();
        body.setVisibility(View.GONE);
        body.addView(ui.toggle("Infinite nitrous", null, flag(Settings.K_CHEAT_NOS, false),
                v -> put(Settings.K_CHEAT_NOS, v)));
        body.addView(ui.toggle("Infinite speedbreaker", null, flag(Settings.K_CHEAT_SB, false),
                v -> put(Settings.K_CHEAT_SB, v)));
        body.addView(ui.toggle("Max cash", "Keeps the career's cash at 9,999,999; it ends up in the save.",
                flag(Settings.K_CHEAT_CASH, false), v -> put(Settings.K_CHEAT_CASH, v)));
        body.addView(ui.toggle("No police", "No new cops or pursuits; one already on goes on.",
                flag(Settings.K_CHEAT_COPS, false), v -> put(Settings.K_CHEAT_COPS, v)));
        body.addView(ui.toggle("Unlock all", "Every car, part and race.", flag(Settings.K_CHEAT_UNLOCK, false),
                v -> put(Settings.K_CHEAT_UNLOCK, v)));
        c.addView(body);
        head.setOnClickListener(v -> {
            boolean open = body.getVisibility() != View.VISIBLE;
            body.setVisibility(open ? View.VISIBLE : View.GONE);
            head.setText(open ? "▾ CHEATS" : "▸ CHEATS");
        });
        return c;
    }

    private LinearLayout displayCard() {
        LinearLayout c = ui.card();
        c.addView(ui.section("DISPLAY"));
        c.addView(ui.toggle("Widescreen", "On: the whole screen, the game's widescreen mode widened to the phone (Hor+). "
                + "Off: the Xbox's 4:3 picture with side bars.",
                flag(Settings.K_WIDESCREEN, true), v -> put(Settings.K_WIDESCREEN, v)));
        c.addView(ui.choice("Max frame rate", "60 is the console's speed for menus and films.",
                new String[] {"30", "60", "90", "120", "Refresh rate", "Unlimited"}, new int[] {30, 60, 90, 120, 1, 0},
                num(Settings.K_FPS_CAP, 60), v -> put(Settings.K_FPS_CAP, v)));
        c.addView(ui.toggle("Vertical sync", "Waits for the screen's refresh: no tearing, a little more latency.",
                flag(Settings.K_VSYNC, false), v -> put(Settings.K_VSYNC, v)));
        c.addView(ui.toggle("Show FPS", "Frames per second in a corner of the game.",
                flag(Settings.K_FPS, false), v -> put(Settings.K_FPS, v)));
        c.addView(ui.choice("FPS position", null,
                new String[] {"Top", "Top left", "Top right", "Bottom left", "Bottom", "Bottom right"},
                new int[] {0, 1, 2, 3, 4, 5}, num(Settings.K_FPS_POS, 2), v -> put(Settings.K_FPS_POS, v)));
        c.addView(ui.toggle("FPS: CPU / GPU load", null, flag(Settings.K_FPS_USAGE, true),
                v -> put(Settings.K_FPS_USAGE, v)));
        c.addView(ui.toggle("FPS: temperatures", null, flag(Settings.K_FPS_TEMPS, true),
                v -> put(Settings.K_FPS_TEMPS, v)));
        return c;
    }

    private LinearLayout graphicsCard() {
        LinearLayout c = ui.card();
        c.addView(ui.section("GRAPHICS"));
        c.addView(ui.slider("Render resolution", "Of the screen's height. 67% is about 720p on a 1080p phone.",
                20, 200, num(Settings.K_RENDER, 67), v -> v + " %", v -> put(Settings.K_RENDER, v)));
        c.addView(ui.choice("Anti-aliasing", "FXAA: a filter over the picture. SSAA: twice the height, much slower.",
                new String[] {"Off", "FXAA", "SSAA 2x"}, new int[] {0, 1, 2},
                num(Settings.K_AA, 0), v -> put(Settings.K_AA, v)));
        c.addView(ui.choice("Anisotropic filtering", "Sharper textures seen at an angle, like the road ahead.",
                new String[] {"Game default", "2x", "4x", "8x", "16x"}, new int[] {0, 2, 4, 8, 16},
                num(Settings.K_ANISO, 0), v -> put(Settings.K_ANISO, v)));
        c.addView(ui.toggle("Yellow filter", "The game's yellow colour grading. Off: neutral colours.",
                flag(Settings.K_YELLOW, false), v -> put(Settings.K_YELLOW, v)));
        c.addView(ui.choice("Car reflections", "How often the cars' reflection is drawn; about 3% of the GPU's frame.",
                new String[] {"Full", "Half", "Quarter", "Off"}, new int[] {0, 1, 2, 3},
                num(Settings.K_REFLECT, 0), v -> put(Settings.K_REFLECT, v)));
        c.addView(ui.toggle("Road reflections", "The wet road's reflection.",
                flag(Settings.K_ROAD_REFLECT, true), v -> put(Settings.K_ROAD_REFLECT, v)));
        c.addView(ui.toggle("Motion blur", "The blur by speed and the nitrous' radial blur.",
                flag(Settings.K_MOTION_BLUR, true), v -> put(Settings.K_MOTION_BLUR, v)));
        c.addView(ui.toggle("Bloom", null, flag(Settings.K_BLOOM, true), v -> put(Settings.K_BLOOM, v)));
        c.addView(ui.toggle("Fog", null, flag(Settings.K_FOG, true), v -> put(Settings.K_FOG, v)));
        c.addView(ui.toggle("Shadows", null, flag(Settings.K_SHADOWS, true), v -> put(Settings.K_SHADOWS, v)));
        c.addView(ui.toggle("Particle effects", "Tyre smoke, sparks, crash debris.",
                flag(Settings.K_PARTICLES, true), v -> put(Settings.K_PARTICLES, v)));
        c.addView(ui.toggle("Falling rain", null, flag(Settings.K_RAIN, true), v -> put(Settings.K_RAIN, v)));
        c.addView(ui.toggle("Screen raindrops", null, flag(Settings.K_RAIN_DROPS, true),
                v -> put(Settings.K_RAIN_DROPS, v)));
        c.addView(ui.choice("Screen filter", null,
                new String[] {"Off", "Sharpen", "Vivid", "Scanlines", "CRT", "HDR"}, new int[] {0, 1, 2, 3, 4, 5},
                num(Settings.K_FILTER, 0), v -> put(Settings.K_FILTER, v)));
        return c;
    }

    private LinearLayout soundCard() {
        LinearLayout c = ui.card();
        c.addView(ui.section("SOUND"));
        c.addView(ui.choice("Audio output", null, new String[] {"Automatic", "Stereo", "5.1 surround", "Mono"},
                new int[] {0, 1, 2, 3}, num(Settings.K_AUDIO_OUT, 0), v -> put(Settings.K_AUDIO_OUT, v)));
        return c;
    }

    private LinearLayout advancedCard() {
        LinearLayout c = ui.card();
        TextView head = ui.section("▸ ADVANCED");
        c.addView(head);
        LinearLayout body = ui.column();
        body.setVisibility(View.GONE);
        body.addView(ui.summary("To test what each thing costs on your phone. The defaults are the recommended ones."));
        body.addView(ui.toggle("Shader preloading", "Builds the shaders of earlier runs at the start: fewer stutters the first minutes.",
                flag(Settings.K_PRELOAD, false), v -> put(Settings.K_PRELOAD, v)));
        body.addView(ui.toggle("Performance log", "A line a second in Android/data/com.ryuna.nfsmw2005x/files/logs: fps, CPU and GPU frame times.",
                mPrefs.getBoolean(Settings.K_PERF_LOG, false), v -> pref(Settings.K_PERF_LOG, v)));
        c.addView(body);
        head.setOnClickListener(v -> {
            boolean open = body.getVisibility() != View.VISIBLE;
            body.setVisibility(open ? View.VISIBLE : View.GONE);
            head.setText(open ? "▾ ADVANCED" : "▸ ADVANCED");
        });
        return c;
    }

    // ---------------------------------------------------------------- actions

    /** The launcher's own state (not the game's). */
    private void pref(String k, boolean v) { mPrefs.edit().putBoolean(k, v).apply(); }

    private boolean flag(String k, boolean def) { return mToml.getBool(k, def); }

    private int num(String k, int def) { return (int) mToml.getInt(k, def); }

    /** A setting of the game's, into mostwanted.toml at once, so the game's menu starts from it. */
    private void put(String k, Object v) {
        mToml.put(k, v);
        try {
            mToml = Settings.updateNative(this, t -> t.put(k, v));  // the file's other keys as they are now
        } catch (IOException e) {
            Toast.makeText(this, "Cannot save settings: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        mTomlStamp = Settings.nativeFile(this).lastModified();
    }

    /** mostwanted.toml as it is now; a broken one leaves defaults and a .bad copy (Settings.nativeSettings). */
    private void loadNative() {
        mToml = Settings.nativeSettings(this);
        mTomlStamp = Settings.nativeFile(this).lastModified();
        if (mToml.recovered())
            Toast.makeText(this, "mostwanted.toml was unreadable: defaults used, the old file kept as mostwanted.toml.bad",
                           Toast.LENGTH_LONG).show();
    }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { return ""; }
    }

    private void refreshPads() {
        if (mPadStatus == null) return;
        StringBuilder sb = new StringBuilder();
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null || d.isVirtual()) continue;
            int s = d.getSources();
            if ((s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(d.getName());
            }
        }
        mPadStatus.setText(sb.length() == 0
                ? "No controller connected. USB and Bluetooth controllers work as player 1."
                : "Connected: " + sb + ". Works as player 1.");
    }

    private void testVibration() {
        Vibrator v;
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = (VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE);
            v = vm == null ? null : vm.getDefaultVibrator();
        } else {
            v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        }
        if (v == null || !v.hasVibrator()) {
            Toast.makeText(this, "This phone has no vibrator.", Toast.LENGTH_SHORT).show();
            return;
        }
        v.vibrate(VibrationEffect.createWaveform(new long[] {0, 120, 80, 260}, new int[] {0, 160, 0, 255}, -1));
    }

    private void saveDir(String d) {
        d = d.trim();
        while (d.length() > 1 && d.endsWith("/")) d = d.substring(0, d.length() - 1);
        // slot work queued or running belongs to the folder it was started in: no other folder until it is done
        if (sSaveJobs.get() > 0 && !d.equals(Settings.gameDir(this))) {
            Toast.makeText(this, "Wait for the save slot work to finish before changing the game folder.",
                           Toast.LENGTH_LONG).show();
            mDir.setText(Settings.gameDir(this));
            return;
        }
        mPrefs.edit().putString(Settings.K_GAME_DIR, d).apply();
        refreshDir();
        refreshSaves();
    }

    private boolean needsAllFiles(String dir) {
        String own = Settings.filesDir(this).getAbsolutePath();
        return !dir.startsWith(own) && !Environment.isExternalStorageManager();
    }

    private void refreshDir() {
        if (mDirStatus == null) return;
        String dir = Settings.gameDir(this);
        if (!mDir.hasFocus()) mDir.setText(dir);
        String err = Settings.checkGameDir(dir);
        if (err != null && needsAllFiles(dir))
            err = "needs “All files access” — tap Choose folder to grant it";
        if (err == null) {
            mDirStatus.setText("✓ default.xbe found");
            mDirStatus.setTextColor(UiKit.OK);
        } else {
            mDirStatus.setText("✗ " + err);
            mDirStatus.setTextColor(UiKit.ACCENT);
        }
        mPlay.setAlpha(err == null ? 1f : .5f);
        boolean have = err == null;
        mPlay.setVisibility(have ? View.VISIBLE : View.GONE);
        mIsoBtn.setVisibility(have ? View.GONE : View.VISIBLE);
        mPlayHint.setVisibility(have ? View.GONE : View.VISIBLE);
        mIsoSmall.setVisibility(have ? View.VISIBLE : View.GONE);
    }

    private void pickFolder() {
        if (!Environment.isExternalStorageManager()) {
            Toast.makeText(this, "Allow “All files access” so the game can read its folder, then come back.",
                           Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                         Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
            return;
        }
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE);
        } catch (Exception e) {
            Toast.makeText(this, "No folder picker; type the path instead.", Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------- ISO install

    private void pickIso() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*");
        try {
            startActivityForResult(i, REQ_ISO);
        } catch (Exception e) {
            Toast.makeText(this, "No file picker on this phone.", Toast.LENGTH_LONG).show();
        }
    }

    private static String fmtMB(long b) { return String.format(java.util.Locale.US, "%,d MB", b >> 20); }

    /** Checks the image, then extracts it into the app's game folder: game.tmp first, then the old disc's files
     *  replaced by the new ones, the saves (saves/, UDATA) left where they are. */
    private void installIso(Uri uri) {
        android.os.ParcelFileDescriptor pfd;
        try {
            pfd = getContentResolver().openFileDescriptor(uri, "r");
        } catch (Exception e) {
            alert("Cannot open the file", String.valueOf(e.getMessage()));
            return;
        }
        if (pfd == null) return;
        java.io.FileInputStream in = new java.io.FileInputStream(pfd.getFileDescriptor());
        java.nio.channels.FileChannel ch = in.getChannel();
        XisoExtractor x;
        int title;
        try {
            x = new XisoExtractor(ch);
            title = x.titleId();
        } catch (Exception e) {
            closeQuietly(pfd);
            alert("Not a usable disc image", e.getMessage()
                    + "\n\nPick the .iso of the Xbox disc (an \"XISO\" image, ~2.9 GB).");
            return;
        }
        if (x.find("default.xbe") == null || title != XisoExtractor.TITLE_ID) {
            closeQuietly(pfd);
            alert("Wrong game", title == 0 ? "This image has no default.xbe."
                    : String.format("This image is title %08X. nfsmw needs Need for Speed: Most Wanted, "
                    + "Xbox NTSC-U (title 4541007B).", title));
            return;
        }
        File root = Settings.filesDir(this);
        File game = new File(Settings.defaultGameDir(this)), tmp = new File(root, "game.tmp");
        deleteTree(tmp);
        long free = root.getUsableSpace(), need = x.totalBytes + (64L << 20);
        if (free < need) {
            closeQuietly(pfd);
            alert("Not enough space", "Extracting needs " + fmtMB(need) + "; " + fmtMB(free)
                    + " is free. Free some space and try again.");
            return;
        }

        // progress dialog
        LinearLayout box = ui.column();
        box.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(4));
        android.widget.ProgressBar bar = new android.widget.ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        TextView line = ui.summary("");
        TextView file = ui.summary("");
        file.setSingleLine(true);
        file.setEllipsize(android.text.TextUtils.TruncateAt.START);
        box.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        box.addView(line);
        box.addView(file);
        mCancelExtract = false;
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this,
                android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Installing NFS Most Wanted")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Cancel", null)
            .create();
        dlg.setOnShowListener(d -> dlg.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
                .setOnClickListener(v -> { mCancelExtract = true; line.setText("Cancelling…"); }));
        dlg.show();
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final long[] last = { 0 };
        final long t0 = android.os.SystemClock.elapsedRealtime();
        new Thread(() -> {
            String error = null;
            boolean cancelled = false;
            try {
                x.extract(tmp, (done, total, name) -> {
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now - last[0] >= 200 || done == total) {
                        last[0] = now;
                        double secs = Math.max(.001, (now - t0) / 1000.0);
                        String txt = fmtMB(done) + " / " + fmtMB(total) + "  ·  "
                                + String.format(java.util.Locale.US, "%.0f MB/s", (done >> 20) / secs);
                        runOnUiThread(() -> {
                            bar.setProgress((int) (done * 1000 / Math.max(1, total)));
                            if (!mCancelExtract) line.setText(txt);
                            file.setText(name);
                        });
                    }
                    return !mCancelExtract;
                });
                // the saves (slots, and the old LLE build's UDATA) never move: the old disc's files go and the new
                // ones are moved in around them, so a failure here cannot take a save with game.tmp
                java.util.Set<String> keep = new java.util.HashSet<>(java.util.Arrays.asList("saves", "UDATA"));
                if (!game.isDirectory() && !game.mkdirs()) throw new java.io.IOException("Cannot create " + game);
                File[] olds = game.listFiles();
                if (olds != null) for (File o : olds) if (!keep.contains(o.getName())) deleteTree(o);
                File[] news = tmp.listFiles();
                if (news == null) throw new java.io.IOException("Cannot read game.tmp");
                for (File n : news) {
                    if (keep.contains(n.getName())) continue;
                    if (!n.renameTo(new File(game, n.getName())))
                        throw new java.io.IOException("Cannot move " + n.getName() + " into game");
                }
                deleteTree(tmp);
            } catch (XisoExtractor.Cancelled c) {
                cancelled = true;
            } catch (Exception e) {
                error = e.getMessage();
            } finally {
                closeQuietly(pfd);
                if (error != null || cancelled) deleteTree(tmp);
            }
            final String err = error;
            final boolean canc = cancelled;
            final long secs = (android.os.SystemClock.elapsedRealtime() - t0) / 1000;
            runOnUiThread(() -> {
                getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                dlg.dismiss();
                if (canc) {
                    Toast.makeText(this, "Install cancelled.", Toast.LENGTH_SHORT).show();
                } else if (err != null) {
                    alert("Install failed", err);
                } else {
                    mDir.setText(game.getAbsolutePath());
                    saveDir(game.getAbsolutePath());
                    offerDeleteIso(uri, x.files.size(), secs);
                }
            });
        }, "iso-extract").start();
    }

    private void offerDeleteIso(Uri uri, int files, long secs) {
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Game installed")
            .setMessage(files + " files extracted in " + secs + " s. The .iso is no longer needed; "
                    + "delete it to get its space back?")
            .setPositiveButton("Delete .iso", (d, w) -> {
                boolean ok;
                try { ok = DocumentsContract.deleteDocument(getContentResolver(), uri); }
                catch (Exception e) { ok = false; }
                Toast.makeText(this, ok ? "The .iso was deleted." : "Could not delete it; remove it with a file manager.",
                        Toast.LENGTH_LONG).show();
            })
            .setNegativeButton("Keep it", null)
            .show();
    }

    private void alert(String title, String msg) {
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title).setMessage(msg).setPositiveButton("OK", null).show();
    }

    private static void closeQuietly(java.io.Closeable c) {
        try { c.close(); } catch (Exception ignored) { }
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.isDirectory() ? f.listFiles() : null;
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_ISO) {
            if (res == RESULT_OK && data != null && data.getData() != null) installIso(data.getData());
            return;
        }
        if (req == REQ_BACKUP || req == REQ_RESTORE) {
            if (res != RESULT_OK || data == null || data.getData() == null) return;
            if (req == REQ_RESTORE) {
                restore(data.getData());
            } else if (mBackupSlot == null || mBackupGame == null) {
                alert("Backup not made", "The launcher lost which slot to back up; pick Back up… again.");
            } else {
                backup(data.getData(), mBackupSlot, mBackupGame);
                mBackupSlot = mBackupGame = null;
            }
            return;
        }
        if (req != REQ_TREE || res != RESULT_OK || data == null || data.getData() == null) return;
        String path = treeToPath(data.getData());
        if (path == null) {
            Toast.makeText(this, "Pick a folder on the internal storage or SD card.", Toast.LENGTH_LONG).show();
            return;
        }
        mDir.setText(path);
        saveDir(path);
    }

    /** "primary:Games/NFSMW" -> /storage/emulated/0/Games/NFSMW;
     *  "1234-ABCD:x" -> /storage/1234-ABCD/x. */
    private static String treeToPath(Uri tree) {
        String id;
        try { id = DocumentsContract.getTreeDocumentId(tree); } catch (Exception e) { return null; }
        int c = id.indexOf(':');
        if (c < 0) return null;
        String vol = id.substring(0, c), rel = id.substring(c + 1);
        String base = "primary".equalsIgnoreCase(vol)
                ? Environment.getExternalStorageDirectory().getAbsolutePath()
                : "/storage/" + vol;
        return rel.isEmpty() ? base : base + "/" + rel;
    }

    private void play() {
        saveDir(mDir.getText().toString());
        String err = Settings.checkGameDir(Settings.gameDir(this));
        if (err != null) {
            Toast.makeText(this, err, Toast.LENGTH_LONG).show();
            return;
        }
        // a slot being copied, restored or deleted: the game would boot the half of it there is
        if (sSaveJobs.get() > 0) {
            Toast.makeText(this, "Wait for the save slot work to finish.", Toast.LENGTH_LONG).show();
            return;
        }
        // the old saves not in Main yet: playing would start a fresh Main over them
        if (!migrateSaves()) return;
        try {
            // the slot PLAY hands the game: the chosen one, or Main when that is gone
            SaveSlots slots = Settings.saveSlots(this);
            String slot = Settings.saveSlot(this);
            if (!slots.dir(slot).isDirectory()) {
                if (!slots.dir(SaveSlots.MAIN).isDirectory()) slots.create(SaveSlots.MAIN);
                if (!slot.equals(SaveSlots.MAIN))
                    Toast.makeText(this, "Slot " + slot + " is missing: playing Main.", Toast.LENGTH_LONG).show();
                mPrefs.edit().putString(Settings.K_SAVE_SLOT, SaveSlots.MAIN).commit();
            }
            Settings.writeNativeFiles(this);
        } catch (Exception e) {
            Toast.makeText(this, "Cannot save settings: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(new Intent(this, GameActivity.class));
    }
}
