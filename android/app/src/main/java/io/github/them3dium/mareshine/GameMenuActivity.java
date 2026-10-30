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
    private static final int REQUEST_MODS = 2;
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
        GameAudio.applyAll(this); // and the volumes, which apply at once
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
        findViewById(R.id.btnSound).setOnClickListener(v -> showSound());
        findViewById(R.id.btnMods).setVisibility(Mods.MENU ? View.VISIBLE : View.GONE);
        findViewById(R.id.btnMods).setOnClickListener(v -> startActivityForResult(
            new Intent(this, ModsActivity.class).putExtra(ModsActivity.EXTRA_IN_GAME, true), REQUEST_MODS));
        // test builds: straight to any episode (Warp.java)
        findViewById(R.id.btnWarp).setVisibility(Warp.available(this) ? View.VISIBLE : View.GONE);
        findViewById(R.id.btnWarp).setOnClickListener(v -> showWarpWorlds());
        findViewById(R.id.btnGraphics).setOnClickListener(v -> {
            mSettingsChanged = true;
            startActivity(new Intent(this, SettingsActivity.class).putExtra(SettingsActivity.EXTRA_IN_GAME, true));
        });
        findViewById(R.id.btnRestart).setOnClickListener(v -> leaveGame(true));
        findViewById(R.id.btnQuit).setOnClickListener(v -> toMainMenu());
        findViewById(R.id.btnResume).requestFocus();
        fitBar();
    }

    // Music and sound effect volumes, applied while the panel is open (the
    // mixer ramps to a new value, so dragging does not click) and kept.
    // WARP: the worlds, then a world's episodes; the game warps once the menu
    // closes (at the top of its next frame in a stage)
    private void showWarpWorlds() {
        java.util.List<Warp.Entry> all = Warp.list();
        if (all == null || all.isEmpty()) {
            android.widget.Toast.makeText(this, t("The level list is not ready yet. Try again in a moment."),
                android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.List<String> worlds = new java.util.ArrayList<>();
        for (Warp.Entry e : all) if (!worlds.contains(e.world)) worlds.add(e.world);
        java.util.List<String> items = new java.util.ArrayList<>(worlds);
        items.add(0, t("Enter numbers…"));
        new DlDialog(this).setTitle(t("WARP"))
            .setItems(items.toArray(new String[0]), (d, w) -> {
                if (w == 0) showWarpNumbers();
                else showWarpEpisodes(all, worlds.get(w - 1));
            })
            .show();
    }

    // area,episode typed in (as settings.txt's SMS_WARP takes them)
    private void showWarpNumbers() {
        android.widget.EditText field = new android.widget.EditText(this);
        field.setHint("2,0");
        field.setSingleLine(true);
        field.setInputType(android.text.InputType.TYPE_CLASS_PHONE); // digits and a comma
        new DlDialog(this).setTitle(t("WARP"))
            .setMessage(t("Area and episode, for example 2,0 for Bianco Hills' first episode."))
            .setView(field)
            .setNegativeButton(t("Cancel"), null)
            .setPositiveButton(t("Warp"), (d, w) -> {
                String[] f = field.getText().toString().trim().split("[,; ]+");
                Warp.Entry e = new Warp.Entry();
                try {
                    e.area = Integer.parseInt(f[0]);
                    e.episode = f.length > 1 ? Integer.parseInt(f[1]) : 0;
                } catch (NumberFormatException | ArrayIndexOutOfBoundsException ex) {
                    android.widget.Toast.makeText(this, t("Type two numbers, for example 2,0."),
                        android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!Warp.ready()) {
                    android.widget.Toast.makeText(this, t("Warps work in a level, once it has loaded."),
                        android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                Warp.go(e);
                finish();
            })
            .show();
    }

    private void showWarpEpisodes(java.util.List<Warp.Entry> all, String world) {
        java.util.List<Warp.Entry> eps = new java.util.ArrayList<>();
        java.util.List<String> names = new java.util.ArrayList<>();
        for (Warp.Entry e : all)
            if (e.world.equals(world)) {
                eps.add(e);
                names.add(e.name);
            }
        new DlDialog(this).setTitle(world)
            .setItems(names.toArray(new String[0]), (d, w) -> {
                if (!Warp.ready()) {
                    android.widget.Toast.makeText(this, t("Warps work in a level, once it has loaded."),
                        android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                Warp.go(eps.get(w));
                finish();
            })
            .show();
    }

    private void showSound() {
        final float d = getResources().getDisplayMetrics().density;
        final android.app.Dialog dlg = new android.app.Dialog(this);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.dl_panel);
        int pad = Math.round(20 * d);
        panel.setPadding(pad, Math.round(14 * d), pad, Math.round(18 * d));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(t("SOUND"));
        title.setTextColor(0xFFFFC93C);
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        title.setTextSize(22);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = closeButton();
        close.setOnClickListener(v -> dlg.dismiss());
        head.addView(close);
        panel.addView(head);
        String[][] rows = {{GameAudio.MUSIC, "Music"}, {GameAudio.SFX, "Sound effects"}};
        final java.util.List<android.widget.SeekBar> bars = new java.util.ArrayList<>();
        for (String[] r : rows) {
            final String key = r[0];
            final TextView label = new TextView(this);
            label.setTextColor(getColor(R.color.dl_text));
            label.setTextSize(16);
            label.setPadding(0, Math.round(10 * d), 0, Math.round(2 * d));
            final Runnable show = () -> {
                int v = GameAudio.volume(this, key);
                label.setText(t(r[1]) + ":  " + (v == 0 ? t("Off") : v + "%"));
            };
            show.run();
            panel.addView(label);
            android.widget.SeekBar bar = new android.widget.SeekBar(this);
            bar.setMax(10);  // steps of 10 %
            bar.setProgress(GameAudio.volume(this, key) / 10);
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFFFC93C));
            bar.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFFFC93C));
            bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF2A5A85));
            // a gold frame while chosen, as Settings' sliders (dl_row), so a
            // controller shows which one it moves
            bar.setBackgroundResource(R.drawable.dl_row);
            bar.setPadding(Math.round(16 * d), Math.round(14 * d), Math.round(16 * d), Math.round(14 * d));
            bar.setFocusable(true);
            bars.add(bar);
            bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(android.widget.SeekBar s, int p, boolean user) {
                    if (!user) return;
                    GameAudio.setVolume(GameMenuActivity.this, key, p * 10);
                    show.run();
                }
                @Override public void onStartTrackingTouch(android.widget.SeekBar s) {}
                @Override public void onStopTrackingTouch(android.widget.SeekBar s) {}
            });
            panel.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        dlg.setContentView(panel);
        dlg.setCanceledOnTouchOutside(true);
        android.view.Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            w.setLayout(Math.round(460 * d), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dlg.show();
        // with a controller (the menu was reached by it), the first slider is chosen
        if (getCurrentFocus() != null && !bars.isEmpty()) bars.get(0).requestFocus();
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

    // the entries in one row: smaller text and padding when they do not fit.
    // A Button keeps its 88dp minimum width however small its text gets, so on
    // a narrow phone (Pixel, German labels) one pass left the row too wide and
    // the last entry, MAIN MENU ✕, was squeezed under its neighbours: the
    // minimum goes, and it shrinks again until the row fits.
    private void shrinkToFit(android.widget.LinearLayout row) {
        if (mBarFitted) return;
        int avail = row.getWidth() - row.getPaddingLeft() - row.getPaddingRight();
        if (avail <= 0) return;
        mBarFitted = true;
        float scale = 1f;
        for (int pass = 0; pass < 4; pass++) {
            int total = barWidth(row);
            if (total <= avail || scale <= 0.35f) return;
            float f = Math.max(0.35f / scale, (float) avail / total * 0.98f);
            scale *= f;
            for (int i = 0; i < row.getChildCount(); i++) {
                View c = row.getChildAt(i);
                if (c instanceof TextView) {
                    TextView t = (TextView) c;
                    t.setMinWidth(0);
                    t.setMinimumWidth(0);
                    t.setSingleLine(true);
                    t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, t.getTextSize() * f);
                    t.setPadding(Math.round(t.getPaddingLeft() * f), t.getPaddingTop(),
                        Math.round(t.getPaddingRight() * f), t.getPaddingBottom());
                }
            }
        }
    }

    // the entries' natural width side by side, margins included
    private static int barWidth(android.widget.LinearLayout row) {
        int total = 0;
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            c.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            total += c.getMeasuredWidth();
            if (c.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams) {
                android.view.ViewGroup.MarginLayoutParams lp = (android.view.ViewGroup.MarginLayoutParams) c.getLayoutParams();
                total += lp.leftMargin + lp.rightMargin;
            }
        }
        return total;
    }

    // A state was saved or a load asked for: back into the game, which carries
    // it out at its next frame.
    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQUEST_STATES && result == RESULT_OK) finish();
        // the mods changed and the player chose to restart now
        if (request == REQUEST_MODS && result == ModsActivity.RESULT_RESTART) leaveGame(true);
    }

    // --- touch controls (live) -------------------------------------------

    private static final String[] TOUCH_KEYS = {
        "SMS_TOUCH_CONTROLS", "SMS_TOUCH_OPACITY", "SMS_TOUCH_AUTOHIDE", "SMS_TOUCH_LAYOUT", "SMS_TOUCH_STYLE", "SMS_TOUCH_LAYOUT_GAMECUBE"};
    // the controller's (platform/pad/pad.cpp reads them again on SMS_PAD_REV);
    // a key not set goes as "", which the game takes as its default
    private static final String[] PAD_KEYS = {
        "SMS_PAD_MAP", "SMS_R1_SOFT_SPRAY", "SMS_STICK_DEADZONE", "SMS_CSTICK_DEADZONE", "SMS_STICK_SENSITIVITY",
        "SMS_CSTICK_SENSITIVITY", "SMS_CAL_LEFT", "SMS_CAL_RIGHT", "SMS_FLUDD_INVERT_X", "SMS_FLUDD_INVERT_Y",
        "SMS_CAMERA_INVERT_X", "SMS_CAMERA_INVERT_Y"};
    private static int sTouchRev;

    // The game is paused while this menu shows, so its environment can be
    // changed safely: the touch settings from settings.txt, then SMS_TOUCH_REV
    // so platform/pad reads them again.
    private void pushTouchSettings() { pushLiveSettings(this); }

    // Also when the game comes back from the main menu (SunshineActivity): the
    // settings the game takes up while it runs.
    static void pushLiveSettings(android.content.Context ctx) {
        java.util.Map<String, String> m = Settings.read(ctx);
        try {
            for (String k : TOUCH_KEYS) {
                String v = m.getOrDefault(k, "");
                if (k.equals("SMS_TOUCH_CONTROLS") && v.isEmpty()) v = "auto";
                SDLActivity.nativeSetenv(k, v);
            }
            for (String k : new String[] {"SMS_VIBRATION_CONTROLLER", "SMS_VIBRATION_PHONE"})
                SDLActivity.nativeSetenv(k, m.getOrDefault(k, "80"));
            for (String k : PAD_KEYS)
                SDLActivity.nativeSetenv(k, m.getOrDefault(k, ""));
            String rev = Integer.toString(++sTouchRev) + "-" + System.nanoTime();
            SDLActivity.nativeSetenv("SMS_PAD_REV", rev);
            SDLActivity.nativeSetenv("SMS_TOUCH_REV", rev);
        } catch (Throwable ignored) {}
    }

    // a setting the running game takes up (pushLiveSettings), not only at its start
    static boolean liveSetting(String key) {
        for (String k : TOUCH_KEYS) if (k.equals(key)) return true;
        for (String k : PAD_KEYS) if (k.equals(key)) return true;
        return key.equals("SMS_VIBRATION_CONTROLLER") || key.equals("SMS_VIBRATION_PHONE");
    }

    // The settings the game started with (SunshineActivity writes them): what
    // was changed since, apart from what it takes up while it runs, waits for
    // a restart.
    static File startSettings(android.content.Context ctx) { return new File(ctx.getFilesDir(), "game-start-settings.txt"); }

    static void rememberStartSettings(android.content.Context ctx) {
        java.util.Properties p = new java.util.Properties();
        for (java.util.Map.Entry<String, String> e : Settings.read(ctx).entrySet()) p.setProperty(e.getKey(), e.getValue());
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(startSettings(ctx))) {
            p.store(out, null);
        } catch (IOException ignored) {}
    }

    static boolean restartPending(android.content.Context ctx) {
        java.util.Properties p = new java.util.Properties();
        try (java.io.FileInputStream in = new java.io.FileInputStream(startSettings(ctx))) {
            p.load(in);
        } catch (IOException e) {
            return false;
        }
        java.util.Map<String, String> now = Settings.read(ctx);
        java.util.Set<String> keys = new java.util.HashSet<>(now.keySet());
        keys.addAll(p.stringPropertyNames());
        for (String k : keys) {
            if (liveSetting(k)) continue;
            String a = now.get(k), b = p.getProperty(k);
            if (a == null ? b != null : !a.equals(b)) return true;
        }
        return false;
    }

    @Override
    protected void onPause() {
        pushTouchSettings();
        super.onPause();
    }

    // --- leaving the game -------------------------------------------------

    // The main menu, the game kept in the background: PLAY says RESUME there,
    // with RESTART below it (LauncherActivity).
    private void toMainMenu() {
        File log = new File(CrashLog.dir(this), "game.log");
        try (FileWriter w = new FileWriter(log, true)) {
            w.write("[log] main menu, the game kept in the background\n");
        } catch (IOException ignored) {}
        CrashLog.clearGameStarted(this);  // closed by Android in the background is no crash
        Intent i = new Intent(this, LauncherActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        i.putExtra(LauncherActivity.EXTRA_FROM_GAME, true);
        startActivity(i);
        finish();
    }

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

    // controller: AppActivity's scheme; the bar's entries are the tabs here
    @Override protected boolean usesPadNav() { return true; }

    private static final int[] BAR = {R.id.btnResume, R.id.btnSaveStates, R.id.btnAchievements, R.id.btnSync,
        R.id.btnSound, R.id.btnMods, R.id.btnWarp, R.id.btnGraphics, R.id.btnRestart, R.id.btnQuit};

    @Override
    protected boolean onPadTab(int dir, boolean shoulder) {
        java.util.List<View> bar = new java.util.ArrayList<>();
        for (int id : BAR) {
            View v = findViewById(id);
            if (v != null && v.getVisibility() == View.VISIBLE) bar.add(v);
        }
        if (bar.isEmpty()) return true;
        int at = bar.indexOf(getCurrentFocus());
        int next = at < 0 ? 0 : Math.max(0, Math.min(bar.size() - 1, at + dir));
        bar.get(next).requestFocusFromTouch();
        return true;
    }

    // up and down have nothing to move through in the bar
    @Override protected java.util.List<View> padItems() { return new java.util.ArrayList<>(); }

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
