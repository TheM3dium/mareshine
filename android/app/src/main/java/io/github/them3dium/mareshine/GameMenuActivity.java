package io.github.them3dium.mareshine;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Process;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import static io.github.them3dium.mareshine.L.t;

// The quick menu, opened over the game (Esc, Back, Select tapped, Select +
// Start, the controller's Home button, three fingers swiped down or the MENU
// touch button): a bar at the top, as in Dusklight. It runs in the game's
// process (":game"); while it is shown SDLActivity is paused, and so is the
// game. Graphics and controller changes apply when the game restarts.
public class GameMenuActivity extends AppActivity {
    private TextView tvNote;
    private boolean mSettingsChanged;
    private static final int REQUEST_STATES = 1;
    private String mLang;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        L.init(this); // Settings (another process) may have changed the app language
        if (!L.current(this).equals(mLang)) buildUi();
        if (mSettingsChanged) {
            tvNote.setText(t("Changes apply after \"Restart game\"."));
        }
    }

    private void buildUi() {
        L.init(this);
        mLang = L.current(this);
        setContentView(R.layout.activity_game_menu);
        L.translate(findViewById(android.R.id.content));
        tvNote = findViewById(R.id.tvNote);
        findViewById(R.id.btnResume).setOnClickListener(v -> finish());
        findViewById(R.id.dim).setOnClickListener(v -> finish());
        findViewById(R.id.btnSaveStates).setOnClickListener(v ->
            startActivityForResult(new Intent(this, SaveStatesActivity.class), REQUEST_STATES));
        findViewById(R.id.btnAchievements).setOnClickListener(v ->
            startActivity(new Intent(this, AchievementsActivity.class)));
        // only with a sync file (the Wi-Fi sync needs the main menu on both devices)
        View sync = findViewById(R.id.btnSync);
        sync.setVisibility(Sync.configured(this) ? View.VISIBLE : View.GONE);
        sync.setOnClickListener(v -> {
            android.widget.Toast.makeText(this, t("Syncing…"), android.widget.Toast.LENGTH_SHORT).show();
            Sync.run(this, true, (msg, changed) -> {
                if (msg != null) android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show();
            });
        });
        findViewById(R.id.btnGraphics).setOnClickListener(v -> {
            mSettingsChanged = true;
            startActivity(new Intent(this, SettingsActivity.class).putExtra(SettingsActivity.EXTRA_IN_GAME, true));
        });
        findViewById(R.id.btnRestart).setOnClickListener(v -> leaveGame(true));
        findViewById(R.id.btnQuit).setOnClickListener(v -> leaveGame(false));
        findViewById(R.id.btnResume).requestFocus();
        fitBar();
    }

    // The bar and the dimming cover the whole screen, up to the edges; only
    // the entries and the note keep clear of a camera cutout and rounded
    // corners (AppActivity would inset the whole screen).
    @Override
    protected boolean keepClearOfSystemUi() { return false; }

    private boolean mBarFitted; // shrunk once per layout (insets come more than once)

    private void fitBar() {
        mBarFitted = false;
        final android.widget.LinearLayout row = findViewById(R.id.barRow);
        final TextView note = findViewById(R.id.tvNote);
        final float density = getResources().getDisplayMetrics().density;
        final int pad = Math.round(16 * density);
        row.setOnApplyWindowInsetsListener((v, insets) -> {
            int[] safe = AppActivity.safeArea(this, insets, density);
            v.setPadding(safe[0] + pad, safe[1], safe[2] + pad, 0);
            android.view.ViewGroup.MarginLayoutParams lp = (android.view.ViewGroup.MarginLayoutParams) note.getLayoutParams();
            lp.leftMargin = safe[0] + Math.round(32 * density);
            lp.bottomMargin = safe[3] + Math.round(24 * density);
            note.setLayoutParams(lp);
            v.post(() -> shrinkToFit(row));
            return insets;
        });
        row.requestApplyInsets();
    }

    // the entries in one row: smaller text and padding when they do not fit
    private void shrinkToFit(android.widget.LinearLayout row) {
        if (mBarFitted) return;
        int avail = row.getWidth() - row.getPaddingLeft() - row.getPaddingRight();
        if (avail <= 0) return;
        int total = 0;
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            c.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            total += c.getMeasuredWidth();
        }
        mBarFitted = true;
        if (total <= avail) return;
        float f = Math.max(0.45f, (float) avail / total * 0.98f);
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            if (c instanceof TextView) {
                TextView t = (TextView) c;
                t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, t.getTextSize() * f);
                t.setPadding(Math.round(t.getPaddingLeft() * f), t.getPaddingTop(), Math.round(t.getPaddingRight() * f),
                    t.getPaddingBottom());
            }
        }
    }

    // A state was saved or a load asked for: back into the game, which carries
    // it out at its next frame.
    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQUEST_STATES && result == RESULT_OK) finish();
    }

    // --- touch controls (live) -------------------------------------------

    private static final String[] TOUCH_KEYS = {
        "SMS_TOUCH_CONTROLS", "SMS_TOUCH_OPACITY", "SMS_TOUCH_AUTOHIDE", "SMS_TOUCH_LAYOUT"};
    private static int sTouchRev;

    // The game is paused while this menu shows, so its environment can be
    // changed safely: the touch settings from settings.txt, then SMS_TOUCH_REV
    // so platform/pad reads them again.
    private void pushTouchSettings() {
        java.util.Map<String, String> m = Settings.read(this);
        try {
            for (String k : TOUCH_KEYS) {
                String v = m.getOrDefault(k, "");
                if (k.equals("SMS_TOUCH_CONTROLS") && v.isEmpty()) v = "auto";
                SDLActivity.nativeSetenv(k, v);
            }
            for (String k : new String[] {"SMS_VIBRATION_CONTROLLER", "SMS_VIBRATION_PHONE"})
                SDLActivity.nativeSetenv(k, m.getOrDefault(k, "80"));
            SDLActivity.nativeSetenv("SMS_TOUCH_REV", Integer.toString(++sTouchRev) + "-" + System.nanoTime());
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onPause() {
        pushTouchSettings();
        super.onPause();
    }

    // --- leaving the game -------------------------------------------------

    // Restarting or quitting ends the game's process (the game cannot be shut
    // down cleanly from outside); saves are written synchronously, so nothing
    // already saved is lost.
    private void leaveGame(boolean restart) {
        File log = new File(CrashLog.dir(this), "game.log");
        try (FileWriter w = new FileWriter(log, true)) {
            w.write(restart ? "[log] game restarted from the menu\n" : "[log] game quit from the menu\n");
        } catch (IOException ignored) {}
        CrashLog.clearGameStarted(this);
        Intent i = new Intent(this, LauncherActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (restart) i.putExtra(LauncherActivity.EXTRA_AUTOSTART, true);
        else i.putExtra(LauncherActivity.EXTRA_FROM_GAME, true);
        startActivity(i);
        finishAffinity();
        Process.killProcess(Process.myPid());
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // the same buttons that opened the menu close it again
        if (keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_BUTTON_MODE
                || keyCode == KeyEvent.KEYCODE_BUTTON_START) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
