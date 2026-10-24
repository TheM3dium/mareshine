package io.github.them3dium.mareshine;

import android.app.ActionBar;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

import org.libsdl.app.SDLActivity;

import java.io.File;

import static io.github.them3dium.mareshine.L.t;

public class SunshineActivity extends SDLActivity {
    // The disc image to play: the launcher's choice (sms_disc), else the one
    // the launcher would find in the data folder or Downloads.
    private String discPath() {
        Intent start = getIntent();
        String picked = start != null ? start.getStringExtra("sms_disc") : null;
        if (picked != null && !picked.trim().isEmpty()) return picked.trim();
        File found = LauncherActivity.findIsoFile(this);
        return found != null ? found.getAbsolutePath() : null;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        CrashLog.install(this);
        super.onCreate(savedInstanceState);
        mAchKnown = Achievements.unlockedIds(this); // what the banner does not announce again

        if (discPath() == null) {
            // nothing to play: say where the image goes, and close
            mBrokenLibraries = true;
            new DlDialog(this)
                .setTitle(t("No disc image"))
                .setMessage(t("Mareshine needs your own Super Mario Sunshine disc image (North American, GMSE01). "
                    + "Put it into iso/ in your data folder, or pick it in the launcher."))
                .setPositiveButton(t("Close"), (d, w) -> finish())
                .setCancelable(false)
                .show();
            return;
        }

        // The data folder (DataDir): the environment wins over settings.txt, so
        // saves and texture packs follow the folder chosen in the launcher.
        if (!mBrokenLibraries) {
            DataDir.ensureLayout(this);
            nativeSetenv("SMS_SETTINGS", DataDir.settingsFile(this).getAbsolutePath());
            nativeSetenv("SMS_SAVE_DIR", DataDir.savesDir(this).getAbsolutePath());
            nativeSetenv("SMS_LANGUAGES_DIR", DataDir.languagesDir(this).getAbsolutePath());
            // Settings → VIDEO → HD textures (on unless turned off)
            boolean hd = !"0".equals(Settings.read(this).getOrDefault("SMS_HD_TEXTURES", "1"));
            // the enabled mods' textures after the pack's, so a mod's win (Mods)
            StringBuilder packs = new StringBuilder(hd ? DataDir.texturesDir(this).getAbsolutePath() : "");
            for (String t : Mods.textureFolders(this)) packs.append(packs.length() > 0 ? ";" : "").append(t);
            nativeSetenv("SMS_TEXTURE_PACKS", packs.length() > 0 ? packs.toString() : "0");
            // and their disc files, after any set by hand in settings.txt's `mod`
            String mods = Mods.discFolders(this), own = Settings.read(this).getOrDefault("mod", "").trim();
            if (!mods.isEmpty()) nativeSetenv("SMS_MOD", own.isEmpty() ? mods : own + ";" + mods);
            // the cutscene frames the app ships (frames added by an update too)
            String frame = Settings.read(this).getOrDefault("SMS_CUTSCENE_FRAME", "0");
            if (!frame.equals("0") && !frame.isEmpty()) CutscenePack.installFrames(this);
            if (CrashLog.isEnabled(this)) {
                nativeSetenv("SMS_LOG_DIR", CrashLog.dir(this).getAbsolutePath());
                nativeSetenv("SMS_DEVICE_INFO", "app " + CrashLog.BuildConfigInfo.version(this)
                    + ", " + Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")"
                    + ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                    + ", disc " + discPath());
            }
        }

        // Lock to landscape (adapts to both landscape angles)
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        // Keep screen on while playing
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (!mBrokenLibraries && !"0".equals(Settings.read(this).getOrDefault(SHADER_POPUP_SETTING, "1")))
            addShaderPopup();

        hideSystemUI();
    }

    // --- the background shader compile (ShaderService) goes on during the
    // game: a small box at the bottom left says so (Settings → OTHER) ---------
    static final String SHADER_POPUP_SETTING = "SMS_SHADER_POPUP";

    private View mShaderBox;
    private TextView mShaderText;
    private BroadcastReceiver mShaderProgress;

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void addShaderPopup() {
        if (mLayout == null) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackgroundResource(R.drawable.dl_panel);
        box.setPadding(dp(10), dp(6), dp(14), dp(6));
        box.setAlpha(0.85f);
        box.setVisibility(View.GONE);
        ProgressBar spin = new ProgressBar(this);
        spin.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.dl_gold)));
        box.addView(spin, new LinearLayout.LayoutParams(dp(16), dp(16)));
        mShaderText = new TextView(this);
        mShaderText.setTextColor(getColor(R.color.dl_text));
        mShaderText.setTextSize(13);
        mShaderText.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tp.leftMargin = dp(8);
        box.addView(mShaderText, tp);
        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
        lp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        lp.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
        lp.leftMargin = dp(12);
        lp.bottomMargin = dp(8);
        mLayout.addView(box, lp);
        mShaderBox = box;
        mShaderProgress = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                int done = i.getIntExtra(ShaderService.EXTRA_DONE, 0), total = i.getIntExtra(ShaderService.EXTRA_TOTAL, 0);
                if (total <= 0 || done >= total) { mShaderBox.setVisibility(View.GONE); return; }
                mShaderText.setText(t("Compiling shaders: %d of %d", done, total));
                mShaderBox.setVisibility(View.VISIBLE);
            }
        };
        IntentFilter f = new IntentFilter(ShaderService.ACTION_PROGRESS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(mShaderProgress, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(mShaderProgress, f);
    }

    @Override
    protected void onDestroy() {
        if (mShaderProgress != null) {
            try { unregisterReceiver(mShaderProgress); } catch (IllegalArgumentException ignored) {}
            mShaderProgress = null;
        }
        super.onDestroy();
    }

    // platform/pad's port_open_menu (SDL_AndroidSendMessage)
    private static final int COMMAND_OPEN_MENU = 0x8001;
    // platform/savestate/savestate_android.cpp: a save state message (code << 8 | slot)
    private static final int COMMAND_SAVESTATE = 0x8002;

    // the MovieDirector patch (zzzz-movieskip-01): 1 = START, 2 = A pressed once
    // in a movie not seen yet, 0 = skipped
    private static final int COMMAND_MOVIE_SKIP = 0x8003;

    // platform/achievements: param = how many were just unlocked (the names
    // are in unlocked.json, which the game has written by then)
    private static final int COMMAND_ACHIEVEMENTS = 0x8004;

    // platform/pad's PADControlMotor: the phone's vibration, param its
    // strength 1..255, 0 stops it
    private static final int COMMAND_RUMBLE = 0x8005;

    // platform/pad/rumble_game.cpp: rumble turned on or off in the game's own
    // options; the app's settings follow (param: the controller's percent,
    // and the phone's << 8)
    private static final int COMMAND_RUMBLE_OPTION = 0x8006;

    // platform/pad/touch.cpp: a touch button's feedback, param 1
    // for a light tick, 2 for a click (the R slider reaching R+)
    private static final int COMMAND_HAPTIC = 0x8007;

    // platform/pad/pad.cpp: a hint in the notice line, param 1 = the MENU
    // button (Select) was tapped once, the menu wants two taps
    private static final int COMMAND_HINT = 0x8008;

    // platform/port_runtime.cpp: subtitles turned on (1) or off (0) in the
    // game's own options; the app's "Subtitles in cutscenes" follows
    private static final int COMMAND_SUBTITLES = 0x8009;

    // "Achievement unlocked" with its name, one after another, at the bottom
    private TextView mAchNotice;
    private java.util.Set<String> mAchKnown;
    // the banners to show: the title, and whether it is a secret one (its own sound)
    private final java.util.ArrayDeque<String[]> mAchQueue = new java.util.ArrayDeque<>();
    private final Runnable mAchNext = this::showNextAchievement;

    private void achievementsUnlocked() {
        java.util.Set<String> now = Achievements.unlockedIds(this);
        if (mAchKnown == null) mAchKnown = new java.util.HashSet<>();
        java.util.List<String> fresh = new java.util.ArrayList<>();
        for (String id : now) if (!mAchKnown.contains(id)) fresh.add(id);
        mAchKnown = now;
        if (fresh.isEmpty()) return;
        java.util.Map<String, Achievements.Def> defs = new java.util.HashMap<>();
        for (Achievements.Category k : Achievements.load(this))
            for (Achievements.Def d : k.items) defs.put(d.id, d);
        if (fresh.size() > 3) { // a save file's progress at once: one line for all
            boolean secret = false;
            for (String id : fresh) secret |= defs.containsKey(id) && defs.get(id).secret;
            mAchQueue.add(new String[] {t("%d achievements unlocked", fresh.size()), secret ? "1" : ""});
        } else {
            for (String id : fresh) {
                Achievements.Def d = defs.get(id);
                if (d != null) mAchQueue.add(new String[] {d.title, d.secret ? "1" : ""});
            }
        }
        if (mAchNotice == null || mAchNotice.getAlpha() == 0f) showNextAchievement();
    }

    private void showNextAchievement() {
        if (mLayout == null) return;
        String[] next = mAchQueue.poll();
        if (next == null) {
            if (mAchNotice != null) mAchNotice.animate().alpha(0f).setDuration(300);
            return;
        }
        if (mAchNotice == null) {
            mAchNotice = noticeView();
            mAchNotice.setGravity(android.view.Gravity.CENTER);
            RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
            lp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
            lp.addRule(RelativeLayout.CENTER_HORIZONTAL);
            lp.bottomMargin = getResources().getDisplayMetrics().heightPixels / 10;
            mLayout.addView(mAchNotice, lp);
        }
        String title = next[0];
        boolean secret = !next[1].isEmpty();
        android.text.SpannableString text = new android.text.SpannableString((secret ? "✨  " : "🏆  ")
            + t(secret ? "Secret achievement unlocked" : "Achievement unlocked") + "\n" + title);
        int split = text.toString().indexOf('\n');
        text.setSpan(new android.text.style.ForegroundColorSpan(0xFFFFC93C), 0, split, 0);
        text.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), split + 1, text.length(), 0);
        mAchNotice.setText(text);
        mAchNotice.animate().cancel();
        mAchNotice.setAlpha(1f);
        mAchNotice.removeCallbacks(mAchNext);
        mAchNotice.postDelayed(mAchNext, secret ? 4500 : 3500); // the sound is the game's (ach_game.cpp)
    }

    // The save state's message ("Saved to slot 1") for a moment at the top
    // of the game, in the shader notice's style.
    private TextView mStateNotice;
    private final Runnable mHideNotice = () -> { if (mStateNotice != null) mStateNotice.animate().alpha(0f).setDuration(300); };

    private void showStateNotice(String text) {
        if (text == null || mLayout == null) return;
        if (mStateNotice == null) {
            mStateNotice = noticeView();
            RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
            lp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
            lp.addRule(RelativeLayout.CENTER_HORIZONTAL);
            lp.topMargin = dp(104); // below the game's HUD and the top touch buttons
            mLayout.addView(mStateNotice, lp);
        }
        mStateNotice.setText(text);
        mStateNotice.animate().cancel();
        mStateNotice.setAlpha(1f);
        mStateNotice.removeCallbacks(mHideNotice);
        mStateNotice.postDelayed(mHideNotice, 2500);
    }

    // "Press START again to skip", small at the bottom middle of a movie, as long as the
    // game takes the second press (3 seconds).
    private TextView mSkipNotice;
    private final Runnable mHideSkip = () -> { if (mSkipNotice != null) mSkipNotice.animate().alpha(0f).setDuration(200); };

    private void showSkipNotice(int button) {
        boolean show = button != 0;
        if (mLayout == null) return;
        if (mSkipNotice == null) {
            if (!show) return;
            mSkipNotice = noticeView();
            mSkipNotice.setTextSize(12);
            mSkipNotice.setPadding(dp(10), dp(4), dp(10), dp(4));
            RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
            lp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
            lp.addRule(RelativeLayout.CENTER_HORIZONTAL);
            // above the subtitles, well below the middle of the picture
            lp.bottomMargin = getResources().getDisplayMetrics().heightPixels / 5;
            mLayout.addView(mSkipNotice, lp);
        }
        mSkipNotice.removeCallbacks(mHideSkip);
        mSkipNotice.animate().cancel();
        if (!show) {
            mHideSkip.run();
            return;
        }
        mSkipNotice.setText(button == 2 ? t("Press A again to skip") : t("Press START again to skip"));
        mSkipNotice.setAlpha(1f);
        mSkipNotice.postDelayed(mHideSkip, 3000);
    }

    private TextView noticeView() {
        TextView v = new TextView(this);
        v.setBackgroundResource(R.drawable.dl_panel);
        v.setPadding(dp(16), dp(8), dp(16), dp(8));
        v.setTextColor(getColor(R.color.dl_text));
        v.setTextSize(15);
        v.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL));
        return v;
    }

    @Override
    protected boolean onUnhandledMessage(int command, Object param) {
        if (command == COMMAND_OPEN_MENU) {
            openMenu();
            return true;
        }
        if (command == COMMAND_SAVESTATE && param instanceof Integer) {
            int v = (Integer) param;
            showStateNotice(SaveStates.message(v >> 8, v & 0xFF));
            return true;
        }
        if (command == COMMAND_ACHIEVEMENTS) {
            achievementsUnlocked();
            return true;
        }
        if (command == COMMAND_RUMBLE && param instanceof Integer) {
            rumble((Integer) param);
            return true;
        }
        if (command == COMMAND_HAPTIC && param instanceof Integer) {
            haptic((Integer) param);
            return true;
        }
        if (command == COMMAND_RUMBLE_OPTION && param instanceof Integer) {
            int v = (Integer) param;
            java.util.Map<String, String> m = Settings.read(this);
            m.put("SMS_VIBRATION_CONTROLLER", Integer.toString(v & 0xFF));
            m.put("SMS_VIBRATION_PHONE", Integer.toString((v >> 8) & 0xFF));
            Settings.write(this, m);
            return true;
        }
        if (command == COMMAND_MOVIE_SKIP && param instanceof Integer) {
            showSkipNotice((Integer) param);
            return true;
        }
        if (command == COMMAND_SUBTITLES && param instanceof Integer) {
            java.util.Map<String, String> m = Settings.read(this);
            m.put("SMS_SUBTITLES", (Integer) param != 0 ? "1" : "0");
            Settings.write(this, m);
            return true;
        }
        if (command == COMMAND_HINT && param instanceof Integer) {
            if ((Integer) param == 1) showStateNotice(t("Press SELECT twice to open the menu"));
            return true;
        }
        return super.onUnhandledMessage(command, param);
    }

    // Back (TV remote, phone gesture) opens the in-game menu instead of
    // leaving the game.
    @Override
    public void onBackPressed() {
        openMenu();
    }

    private long mLastMenu;

    // Back on a remote or a keyboard opens the quick menu (SDL would take it
    // as a key for the game). Not from a game controller: some send Back for B.
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (e.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK) {
            int src = e.getSource();
            boolean pad = (src & android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD
                || (src & android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK;
            if (!pad) {
                if (e.getAction() == android.view.KeyEvent.ACTION_UP) openMenu();
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private boolean mRumbling; // the game's rumble runs: touch feedback waits

    private void rumble(int strength) {
        android.os.Vibrator v = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null || !v.hasVibrator()) return;
        mRumbling = strength > 0;
        if (strength <= 0) {
            v.cancel();
            return;
        }
        // platform/pad renews it while it lasts and stops it itself
        if (Build.VERSION.SDK_INT >= 26)
            v.vibrate(android.os.VibrationEffect.createOneShot(5000,
                v.hasAmplitudeControl() ? Math.max(1, Math.min(255, strength)) : android.os.VibrationEffect.DEFAULT_AMPLITUDE));
        else
            v.vibrate(5000);
    }

    // A short tick or click for the touch buttons, left out while the game
    // rumbles (it would cut that rumble short).
    private void haptic(int kind) {
        if (mRumbling) return;
        android.os.Vibrator v = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null || !v.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= 29)
            v.vibrate(android.os.VibrationEffect.createPredefined(kind == 2
                ? android.os.VibrationEffect.EFFECT_CLICK : android.os.VibrationEffect.EFFECT_TICK));
        else if (Build.VERSION.SDK_INT >= 26)
            v.vibrate(android.os.VibrationEffect.createOneShot(kind == 2 ? 20 : 10,
                v.hasAmplitudeControl() ? (kind == 2 ? 200 : 90) : android.os.VibrationEffect.DEFAULT_AMPLITUDE));
        else
            v.vibrate(kind == 2 ? 20 : 10);
    }

    @Override
    protected void onPause() {
        super.onPause();
        rumble(0);
    }

    // Touch: three fingers swiped down from the top of the screen open the
    // quick menu (as in Dusklight); elsewhere three fingers are left to the game.
    private float mSwipeY = -1;
    private boolean mSwipeDone;

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        int a = e.getActionMasked();
        int n = e.getPointerCount();
        if (n >= 3) {
            float y = 0;
            for (int i = 0; i < n; i++) y += e.getY(i);
            y /= n;
            if (a == android.view.MotionEvent.ACTION_POINTER_DOWN && n == 3) {
                mSwipeY = y < getWindow().getDecorView().getHeight() / 4f ? y : -1;
                mSwipeDone = false;
            } else if (a == android.view.MotionEvent.ACTION_MOVE && mSwipeY >= 0 && !mSwipeDone && y - mSwipeY > dp(90)) {
                mSwipeDone = true;
                openMenu();
            }
        }
        if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) mSwipeY = -1;
        return super.dispatchTouchEvent(e);
    }

    // How to open the quick menu, when the game starts and when the menu
    // closes: with a controller connected its button, otherwise on a
    // touchscreen the three-finger swipe (as Dusklight does).
    // Not again within 8 seconds of the last time it was due: opening and
    // closing the menu again and again keeps it away.
    private static long sMenuHintAt = -1;

    private void showMenuHint() {
        long now = android.os.SystemClock.elapsedRealtime();
        boolean recent = sMenuHintAt >= 0 && now - sMenuHintAt < 8000;
        sMenuHintAt = now;
        if (recent) return;
        String text = null;
        if (controllerConnected()) text = t("Quick menu: tap Select on the controller");
        else if (getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN))
            text = t("Quick menu: swipe down from the top with three fingers");
        final String hint = text;
        if (hint != null && mLayout != null) mLayout.postDelayed(() -> showStateNotice(hint), 1500);
    }

    private static boolean controllerConnected() {
        for (int id : android.view.InputDevice.getDeviceIds()) {
            android.view.InputDevice d = android.view.InputDevice.getDevice(id);
            if (d == null || d.isVirtual()) continue;
            int src = d.getSources();
            if ((src & android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD
                || (src & android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK)
                return true;
        }
        return false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
        showMenuHint();
    }

    private void openMenu() {
        long now = System.currentTimeMillis();
        if (now - mLastMenu < 800) return;
        mLastMenu = now;
        startActivity(new Intent(this, GameMenuActivity.class));
    }

    // SDL asks for an orientation from the window's size: the game is always landscape.
    @Override
    public void setOrientationBis(int w, int h, boolean resizable, String hint) {
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }

    // As AppActivity.hideSystemBars: the whole screen, cutout included, with
    // the bars hidden (a swipe shows them for a moment), so the game's
    // surface never shrinks under the status bar.
    private void hideSystemUI() {
        android.view.Window w = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController bars = w.getInsetsController();
            if (bars == null) return;
            bars.hide(WindowInsets.Type.systemBars());
            bars.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            return;
        }
        int flags = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        w.getDecorView().setSystemUiVisibility(flags);
        if (getActionBar() != null) getActionBar().hide();
    }

    // SDL, then the loader, which places the game (libsms.so) where its 32-bit
    // pointer fields work (android/bootstrap.cpp, docs/ANDROID.md)
    @Override
    protected String[] getLibraries() {
        return new String[] {"SDL2", "smsbootstrap"};
    }

    // The loader's arguments: the data folder (its working directory), where
    // the game's library is, and the disc image.
    @Override
    protected String[] getArguments() {
        String disc = discPath();
        if (disc == null) return new String[0];
        return new String[] {
            "--data", DataDir.get(this).getAbsolutePath(),
            "--libraries", getApplicationInfo().nativeLibraryDir,
            "--disc", disc,
        };
    }
}
