package io.github.them3dium.mareshine;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Process;
import android.view.Gravity;
import android.view.KeyEvent;
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
        findViewById(R.id.btnSaveStates).setOnClickListener(v ->
            startActivityForResult(new Intent(this, SaveStatesActivity.class), REQUEST_STATES));
        findViewById(R.id.btnAchievements).setOnClickListener(v ->
            startActivity(new Intent(this, AchievementsActivity.class)));
        findViewById(R.id.btnGraphics).setOnClickListener(v -> {
            mSettingsChanged = true;
            startActivity(new Intent(this, SettingsActivity.class).putExtra(SettingsActivity.EXTRA_IN_GAME, true));
        });
        findViewById(R.id.btnRestart).setOnClickListener(v -> leaveGame(true));
        findViewById(R.id.btnQuit).setOnClickListener(v -> leaveGame(false));
        findViewById(R.id.btnResume).requestFocus();
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
