package io.github.them3dium.mareshine;

import android.app.ActionBar;
import android.app.AlertDialog;
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
import java.util.ArrayList;
import java.util.List;

import static io.github.them3dium.mareshine.L.t;

public class SunshineActivity extends SDLActivity {
    private static final String TAG = "SunshineActivity";

    private static String[] splitArgs(String raw) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean escaped = false;

        for (int i = 0; i < raw.length(); ++i) {
            char c = raw.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\' && !inSingle) {
                escaped = true;
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                continue;
            }
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                continue;
            }
            if (!inSingle && !inDouble && Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    out.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out.toArray(new String[0]);
    }

    private String findDefaultDiscPath() {
        File rom = new File(DataDir.romDir(this), "GMSE01.iso");
        if (rom.exists() && rom.canRead() && rom.length() > 10 * 1024 * 1024) return rom.getAbsolutePath();

        File extFiles = getExternalFilesDir(null);
        if (extFiles != null) {
            File f = new File(extFiles, "GMSE01.iso");
            if (f.exists() && f.canRead() && f.length() > 10 * 1024 * 1024) return f.getAbsolutePath();
            f = new File(extFiles, "disc.iso");
            if (f.exists() && f.canRead() && f.length() > 10 * 1024 * 1024) return f.getAbsolutePath();
        }

        File intFiles = getFilesDir();
        if (intFiles != null) {
            File f = new File(intFiles, "GMSE01.iso");
            if (f.exists() && f.canRead() && f.length() > 10 * 1024 * 1024) return f.getAbsolutePath();
        }

        String[] candidates = new String[] {
            "/sdcard/Download/GMSE01.iso",
            "/sdcard/Download/Super Mario Sunshine (USA).iso",
            "/sdcard/Download/Super Mario Sunshine.iso",
            "/sdcard/sms/GMSE01.iso",
            "/sdcard/sms/Super Mario Sunshine.iso",
            Environment.getExternalStorageDirectory().getAbsolutePath() + "/Download/GMSE01.iso",
            Environment.getExternalStorageDirectory().getAbsolutePath() + "/Download/Super Mario Sunshine.iso",
            Environment.getExternalStorageDirectory().getAbsolutePath() + "/Download/Super Mario Sunshine (USA).iso",
        };

        for (String path : candidates) {
            File f = new File(path);
            if (f.exists() && f.canRead() && f.length() > 1024 * 1024) {
                return f.getAbsolutePath();
            }
        }

        return null;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        CrashLog.install(this);
        super.onCreate(savedInstanceState);

        String[] args = getArguments();
        if (args.length == 0) {
            mBrokenLibraries = true;
            AlertDialog.Builder dlgAlert = new AlertDialog.Builder(this);
            dlgAlert.setMessage(t("Game disc image not found (GMSE01.iso).\n\nPlease copy the game ISO to your data folder (iso/) or to the Downloads folder of your device:\n/sdcard/Download/GMSE01.iso"));
            dlgAlert.setTitle(t("Game not found"));
            dlgAlert.setPositiveButton(t("Exit"), new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int id) {
                    finish();
                }
            });
            dlgAlert.setCancelable(false);
            dlgAlert.create().show();
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
            nativeSetenv("SMS_TEXTURE_PACKS", hd ? DataDir.texturesDir(this).getAbsolutePath() : "0");
            // the cutscene frames the app ships (frames added by an update too)
            String frame = Settings.read(this).getOrDefault("SMS_CUTSCENE_FRAME", "0");
            if (!frame.equals("0") && !frame.isEmpty()) CutscenePack.installFrames(this);
            if (CrashLog.isEnabled(this)) {
                nativeSetenv("SMS_LOG_DIR", CrashLog.dir(this).getAbsolutePath());
                nativeSetenv("SMS_DEVICE_INFO", "app " + CrashLog.BuildConfigInfo.version(this)
                    + ", " + Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")"
                    + ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                    + ", disc " + String.join(" ", getArguments()));
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

    // The save state's message ("Saved to slot 1") for a moment at the top
    // of the game, in the shader notice's style.
    private TextView mStateNotice;
    private final Runnable mHideNotice = () -> { if (mStateNotice != null) mStateNotice.animate().alpha(0f).setDuration(300); };

    private void showStateNotice(String text) {
        if (text == null || mLayout == null) return;
        if (mStateNotice == null) {
            mStateNotice = new TextView(this);
            mStateNotice.setBackgroundResource(R.drawable.dl_panel);
            mStateNotice.setPadding(dp(16), dp(8), dp(16), dp(8));
            mStateNotice.setTextColor(getColor(R.color.dl_text));
            mStateNotice.setTextSize(15);
            mStateNotice.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL));
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
        return super.onUnhandledMessage(command, param);
    }

    // Back (TV remote, phone gesture) opens the in-game menu instead of
    // leaving the game.
    @Override
    public void onBackPressed() {
        openMenu();
    }

    private long mLastMenu;

    private void openMenu() {
        long now = System.currentTimeMillis();
        if (now - mLastMenu < 800) return;
        mLastMenu = now;
        startActivity(new Intent(this, GameMenuActivity.class));
    }

    @Override
    public void setOrientationBis(int w, int h, boolean resizable, String hint) {
        // Enforce sensor landscape regardless of window resizable flag
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }

    private void hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            View decorView = getWindow().getDecorView();
            decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN);
            ActionBar actionBar = getActionBar();
            if (actionBar != null) {
                actionBar.hide();
            }
        }
    }

    @Override
    protected String[] getLibraries() {
        return new String[] {
            "SDL2",
            "main"
        };
    }

    @Override
    protected String[] getArguments() {
        Intent intent = getIntent();
        if (intent != null) {
            String[] argv = intent.getStringArrayExtra("sms_argv");
            if (argv != null && argv.length > 0) {
                return argv;
            }

            String rawArgs = intent.getStringExtra("sms_args");
            if (rawArgs != null && !rawArgs.trim().isEmpty()) {
                return splitArgs(rawArgs.trim());
            }

            String discExtra = intent.getStringExtra("sms_disc");
            if (discExtra != null && !discExtra.trim().isEmpty()) {
                return new String[] { discExtra.trim() };
            }
        }

        String autoDisc = findDefaultDiscPath();
        if (autoDisc != null) {
            return new String[] { autoDisc };
        }

        return new String[0];
    }
}
