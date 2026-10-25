package io.github.them3dium.mareshine;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.them3dium.mareshine.L.t;

// All settings on one screen, in the style of Dusklight's: tabs at the top, a
// list of rows on the left (label, value) and the focused row's description
// on the right. Values change with D-pad left/right or a tap and are saved to
// settings.txt at once; the game reads them when it starts.
//
// GAME-tab actions (disc image, verify, data folder) are done by the launcher:
// this screen returns EXTRA_ACTION. Opened from the in-game menu
// (EXTRA_IN_GAME) those rows are disabled.
public class SettingsActivity extends AppActivity {
    public static final String EXTRA_IN_GAME = "in_game";
    public static final String EXTRA_ACTION = "action";
    public static final String ACTION_PICK_ISO = "pick_iso";
    public static final String ACTION_VERIFY_ISO = "verify_iso";
    public static final String ACTION_DATA_DIR = "data_dir";

    private static final String PREFS = "sunshine";
    public static final String PREF_AUTOSTART = "autostart";

    private static final int GOLD = 0xFFFFC531, TEXT = 0xFFF4F8FF, DIM = 0xFF8FB4D4;

    // --- a row --------------------------------------------------------------

    private abstract class Row {
        final String label, desc;
        View view;
        TextView valueView;
        boolean enabled = true;

        Row(String label, String desc) {
            this.label = t(label);
            this.desc = t(desc);
        }

        String value() { return ""; }
        void step(int dir) {}           // left/right
        void click() { step(1); }       // tap / OK
        boolean hasArrow() { return false; }
    }

    // A setting from settings.txt with a fixed list of values.
    private class Choice extends Row {
        final String key;
        String[] values, labels;
        final String def;

        Choice(String key, String label, String desc, String def, String[] values, String[] labels) {
            super(label, desc);
            this.key = key;
            this.def = def;
            this.values = values;
            this.labels = labels;
            for (int i = 0; i < labels.length; i++) labels[i] = t(labels[i]);
        }

        void reload() {} // lists that change (connected controllers)

        int index() {
            reload();
            String v = mSettings.getOrDefault(key, def);
            for (int i = 0; i < values.length; i++) if (values[i].equals(v)) return i;
            for (int i = 0; i < values.length; i++) if (values[i].equals(def)) return i;
            return 0;
        }

        @Override String value() {
            int i = index(); // reloads the lists first
            return labels[i];
        }

        @Override void step(int dir) {
            int i = (index() + dir + values.length) % values.length;
            set(i);
        }

        void set(int i) {
            mSettings.put(key, values[i]);
            saved();
        }

        // OK / tap: the choices open in the panel on the right
        @Override void click() { openOptions(this); }

        // actions listed under the choices in the panel (the game language's
        // extra entries); none by default
        String[] extras() { return null; }
        void extra(int i) {}
    }

    // On/off settings; `inverted` for the disable_* keys ("Shadows: On" = 0).
    private class Toggle extends Choice {
        Toggle(String key, String label, String desc, boolean defOn, boolean inverted) {
            super(key, label, desc, (defOn ^ inverted) ? "1" : "0",
                new String[] {"0", "1"}, inverted ? new String[] {"On", "Off"} : new String[] {"Off", "On"});
        }

        @Override void click() { step(1); } // on/off flips at once
    }

    // A percentage from settings.txt: left/right steps on the row; OK opens a
    // slider and a number field on the right for any value in [min, max].
    private class Slider extends Row {
        final String key;
        final int min, max, stepSize, def;

        Slider(String key, String label, String desc, int def, int min, int max, int stepSize) {
            super(label, desc);
            this.key = key;
            this.def = def;
            this.min = min;
            this.max = max;
            this.stepSize = stepSize;
        }

        int get() {
            try {
                return clamp(Math.round(Float.parseFloat(mSettings.getOrDefault(key, Integer.toString(def)))));
            } catch (NumberFormatException e) {
                return def;
            }
        }

        int clamp(int v) { return Math.max(min, Math.min(max, v)); }

        void set(int v) {
            mSettings.put(key, Integer.toString(clamp(v)));
            saved();
        }

        @Override String value() { return get() + "%" + (get() == def ? t(" (default)") : ""); }
        // past either end it wraps round: left from the lowest goes to the highest
        @Override void step(int dir) {
            int v = get();
            if (dir < 0 && v <= min) set(max);
            else if (dir > 0 && v >= max) set(min);
            else {  // to the next whole step (94 goes to 100 or 90)
                int base = Math.floorDiv(v - min, stepSize) * stepSize + min;
                set(dir > 0 ? base + stepSize : (base == v ? v - stepSize : base));
            }
        }
        @Override void click() { openSlider(this); }
    }

    private class Action extends Row {
        final Runnable run;
        final java.util.function.Supplier<String> val;

        Action(String label, String desc, java.util.function.Supplier<String> val, Runnable run) {
            super(label, desc);
            this.val = val;
            this.run = run;
        }

        @Override String value() { return val != null ? val.get() : ""; }
        @Override void click() { run.run(); }
        @Override void step(int dir) {}
        @Override boolean hasArrow() { return val == null; }
    }

    // --- state --------------------------------------------------------------

    private final Map<String, String> mSettings = new LinkedHashMap<>();
    private final List<String> mTabs = new ArrayList<>();
    private final List<TextView> mTabViews = new ArrayList<>();
    private final List<View> mTabBars = new ArrayList<>();
    private int mTab = 0;
    private boolean mInGame;
    // opened from the main menu while the game still runs in the background
    // (left with Home): what changes here applies once the game restarts
    private boolean mGameInBackground, mBackgroundNoted;
    private LinearLayout mList;
    private TextView mDesc;
    private LinearLayout mOptList;
    private ScrollView mRightScroll;
    private TextView mScrollDown;
    private boolean mScrollHintSeen;
    private Panel mReturnPanel;
    private int mReturnIndex;
    private View[] mPanelItems;
    private Row mOpen; // the row whose choices (or slider) are shown on the right
    private final List<Row> mRows = new ArrayList<>();
    private View mExactField;     // the open slider's exact-value field, and its controller picker
    private Runnable mExactPick;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mInGame = getIntent().getBooleanExtra(EXTRA_IN_GAME, false);
        mGameInBackground = !mInGame && gameProcessAlive();
        DataDir.ensureLayout(this);
        mSettings.putAll(Settings.read(this));
        L.init(this);
        buildFrame();
        showTab(savedInstanceState != null ? savedInstanceState.getInt("tab", 0) : 0);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", mTab);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // the button mapping screen writes settings.txt too
        mSettings.clear();
        mSettings.putAll(Settings.read(this));
        refreshValues();
        // back from making language packs: the open list shows the new ones
        if (mOpen instanceof Choice) openOptions((Choice) mOpen);
    }

    // the slider changed last: dragging it or stepping it on counts once
    private String mLastSlider;

    private void saved() {
        // the hidden "Fine-Tuner": 120 settings (as many as there are shines) changed in this menu;
        // a slider counts once until something else is changed
        int changed = 0;
        boolean any = false;
        Map<String, String> before = Settings.read(this);
        for (Map.Entry<String, String> e : mSettings.entrySet()) {
            if (e.getValue().equals(before.get(e.getKey()))) continue;
            if (!GameMenuActivity.liveSetting(e.getKey())) any = true;  // touch and controller apply at once
            if (!isSlider(e.getKey())) {
                changed++;
                mLastSlider = null;
            } else if (!e.getKey().equals(mLastSlider)) {
                changed++;
                mLastSlider = e.getKey();
            }
        }
        Settings.write(this, mSettings);
        refreshValues();
        if (any && mGameInBackground && !mBackgroundNoted) {
            mBackgroundNoted = true;
            android.widget.Toast.makeText(this, t("Takes effect after the game restarts."), android.widget.Toast.LENGTH_LONG).show();
        }
        if (changed > 0 && Achievements.addProgress(this, "sms.fun.fine_tuner", changed, 120))
            android.widget.Toast.makeText(this, "✨  " + t("Secret achievement unlocked") + ": "
                + Achievements.title(this, "sms.fun.fine_tuner"), android.widget.Toast.LENGTH_LONG).show();
    }

    private boolean isSlider(String key) {
        if (mOpen instanceof Slider && ((Slider) mOpen).key.equals(key)) return true;
        for (Row r : mRows)
            if (r instanceof Slider && ((Slider) r).key.equals(key)) return true;
        return false;
    }

    private Context ctx() { return this; }

    private boolean gameProcessAlive() {
        android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        java.util.List<android.app.ActivityManager.RunningAppProcessInfo> ps = am == null ? null : am.getRunningAppProcesses();
        if (ps != null) for (android.app.ActivityManager.RunningAppProcessInfo p : ps)
            if (p.processName != null && p.processName.endsWith(":game")) return true;
        return false;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private Typeface condensed(boolean bold) {
        return Typeface.create("sans-serif-condensed", bold ? Typeface.BOLD : Typeface.NORMAL);
    }

    // --- frame: panel, tabs, list, description ----------------------------

    private void buildFrame() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0A1B30);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.dl_panel);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        plp.setMargins(dp(24), dp(20), dp(24), dp(20));
        root.addView(panel, plp);

        // tabs
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.setPadding(dp(16), 0, dp(8), 0);
        mTabs.add("GAME");
        mTabs.add("VIDEO");
        mTabs.add("INPUT");
        mTabs.add("CHEATS");
        if (Mods.MENU) mTabs.add("MODS");
        mTabs.add("ACHIEVEMENTS");
        mTabs.add("OTHER");
        for (int i = 0; i < mTabs.size(); i++) {
            final int idx = i;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            TextView t = new TextView(this);
            t.setText(t(mTabs.get(i)));
            t.setTypeface(condensed(true));
            t.setTextSize(20);
            t.setPadding(dp(20), dp(14), dp(20), dp(10));
            // tapped, or L/R and the D-pad's left/right (onPadTab); not a focus
            // stop, so up from the first entry cannot land on (and open) a tab
            t.setFocusable(false);
            t.setOnClickListener(v -> showTab(idx));
            t.setBackgroundResource(R.drawable.dl_tab);
            cell.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            View bar = new View(this);
            cell.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));
            tabs.addView(cell, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            mTabViews.add(t);
            mTabBars.add(bar);
        }
        View spacer = new View(this);
        tabs.addView(spacer, new LinearLayout.LayoutParams(0, dp(1), 1f));
        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(TEXT);
        close.setTextSize(24);
        close.setPadding(dp(18), dp(8), dp(18), dp(8));
        close.setFocusable(false);  // B closes with a controller
        close.setBackgroundResource(R.drawable.dl_tab);
        close.setOnClickListener(v -> finish());
        tabs.addView(close, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(tabs, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View line = new View(this);
        line.setBackgroundColor(0xFF2FB8C9);
        panel.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // body
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        panel.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        ScrollView scroll = new ScrollView(this);
        mList = new LinearLayout(this);
        mList.setOrientation(LinearLayout.VERTICAL);
        mList.setPadding(dp(20), dp(10), dp(16), dp(20));
        scroll.addView(mList);
        body.addView(scroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        View vline = new View(this);
        vline.setBackgroundColor(0xFF2FB8C9);
        body.addView(vline, new LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout rightFrame = new LinearLayout(this);
        rightFrame.setOrientation(LinearLayout.VERTICAL);
        ScrollView right = new ScrollView(this);
        mRightScroll = right;
        // Repeated controller focus moves must not race an unfinished scroll animation.
        right.setSmoothScrollingEnabled(false);
        LinearLayout rightCol = new LinearLayout(this);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        rightCol.setPadding(dp(28), dp(24), dp(28), dp(24));
        right.addView(rightCol);
        mDesc = new TextView(this);
        mDesc.setTextColor(TEXT);
        mDesc.setTypeface(condensed(false));
        mDesc.setTextSize(19);
        mDesc.setLineSpacing(0, 1.2f);
        rightCol.addView(mDesc);
        mOptList = new LinearLayout(this);
        mOptList.setOrientation(LinearLayout.VERTICAL);
        mOptList.setPadding(0, dp(18), 0, 0);
        mOptList.setVisibility(View.GONE);
        rightCol.addView(mOptList);
        // A first-scroll hint sits outside the viewport; the native scrollbar shows progress.
        mScrollDown = scrollArrow("▼");
        rightFrame.addView(right, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        rightFrame.addView(mScrollDown, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(18)));
        right.setVerticalScrollBarEnabled(true);
        right.setScrollbarFadingEnabled(true);
        right.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
        right.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
            if (y > oldY) mScrollHintSeen = true;
            updateScrollArrows();
        });
        rightCol.addOnLayoutChangeListener((v, l, t, rr, b, ol, ot, orr, ob) -> updateScrollArrows());
        right.addOnLayoutChangeListener((v, l, t, rr, b, ol, ot, orr, ob) -> updateScrollArrows());
        body.addView(rightFrame, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        setContentView(root);
    }

    private TextView scrollArrow(String symbol) {
        TextView arrow = new TextView(this);
        arrow.setText(symbol);
        arrow.setTextColor(GOLD);
        arrow.setTextSize(14);
        arrow.setGravity(Gravity.CENTER);
        arrow.setFocusable(false);
        arrow.setClickable(false);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        arrow.setVisibility(View.INVISIBLE);
        return arrow;
    }

    private void showDescription(CharSequence text) {
        mScrollHintSeen = false;
        mDesc.setText(text);
        mRightScroll.scrollTo(0, 0);
        mRightScroll.post(this::updateScrollArrows);
    }

    private void updateScrollArrows() {
        if (mRightScroll == null || mScrollDown == null) return;
        boolean hint = !mScrollHintSeen && mRightScroll.canScrollVertically(1);
        if (hint && mScrollDown.getVisibility() != View.VISIBLE) {
            android.view.animation.AlphaAnimation pulse = new android.view.animation.AlphaAnimation(0.35f, 1f);
            pulse.setDuration(700);
            pulse.setRepeatMode(android.view.animation.Animation.REVERSE);
            pulse.setRepeatCount(android.view.animation.Animation.INFINITE);
            mScrollDown.setVisibility(View.VISIBLE);
            mScrollDown.startAnimation(pulse);
        } else if (!hint) {
            mScrollDown.clearAnimation();
            mScrollDown.setVisibility(View.INVISIBLE);
        }
    }

    private void showTab(int idx) {
        mTab = idx;
        for (int i = 0; i < mTabViews.size(); i++) {
            boolean on = i == idx;
            mTabViews.get(i).setTextColor(on ? 0xFFFFFFFF : 0xFF8FB4D4);
            mTabBars.get(i).setBackgroundColor(on ? GOLD : 0x00000000);
        }
        if (mOpen != null) closeOptions(false);
        mList.removeAllViews();
        mRows.clear();
        showDescription("");
        switch (mTabs.get(idx)) {
            case "GAME": buildGame(); break;
            case "VIDEO": buildVideo(); break;
            case "INPUT": buildInput(); break;
            case "CHEATS": buildCheats(); break;
            case "MODS": buildMods(); break;
            case "ACHIEVEMENTS": buildAchievements(); break;
            default: buildInterface(); break;
        }
    }

    private void section(String title) {
        TextView t = new TextView(this);
        t.setText(t(title));
        t.setTextColor(0xFF5ED3DD); // turquoise, like the sea around Isle Delfino
        t.setTypeface(condensed(true));
        t.setTextSize(20);
        t.setPadding(0, dp(16), 0, dp(6));
        mList.addView(t);
    }

    // long words (German compounds) break at syllables, not at any letter
    private void hyphenate(TextView v) {
        v.setTextLocale(new java.util.Locale(L.current(this)));
        v.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NORMAL);
        v.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY);
    }

    private void add(Row r) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setBackgroundResource(R.drawable.dl_row);
        v.setPadding(dp(18), dp(12), dp(18), dp(12));
        v.setFocusable(true);
        v.setClickable(true);
        TextView l = new TextView(this);
        l.setText(r.label);
        l.setTextColor(TEXT);
        l.setTypeface(condensed(true));
        l.setTextSize(18);
        l.setAllCaps(true);
        hyphenate(l);
        v.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));
        TextView val = new TextView(this);
        val.setTextColor(TEXT);
        val.setTypeface(condensed(false));
        val.setTextSize(18);
        val.setGravity(Gravity.END);
        val.setMaxLines(1);
        val.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        v.addView(val, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        mList.addView(v, lp);
        r.view = v;
        r.valueView = val;
        v.setOnClickListener(x -> {
            showDescription(r.desc); // on a touchscreen a row is not focused: tapping it shows what it does
            if (r.enabled) r.click();
        });
        v.setOnFocusChangeListener((x, has) -> {
            if (!has) return;
            if (mOpen != null) closeOptions(false);
            showDescription(r.desc);
        });
        v.setOnKeyListener((x, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN || !r.enabled) return false;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) { r.step(-1); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { r.step(1); return true; }
            return false;
        });
        mRows.add(r);
        refreshRow(r);
    }

    // --- choices in the right panel ------------------------------------------

    private void openOptions(Choice c) {
        closeOptions(false);
        c.reload();
        mOpen = c;
        showDescription(c.desc);
        mOptList.removeAllViews();
        int cur = c.index();
        View focus = null;
        for (int i = 0; i < c.values.length; i++) {
            final int idx = i;
            TextView o = new TextView(this);
            o.setText((i == cur ? "●  " : "     ") + t(c.labels[i]));
            o.setTextColor(i == cur ? 0xFFFFC531 : TEXT);
            o.setTypeface(condensed(i == cur));
            o.setTextSize(18);
            o.setBackgroundResource(R.drawable.dl_row);
            o.setPadding(dp(18), dp(12), dp(18), dp(12));
            o.setFocusable(true);
            o.setClickable(true);
            o.setOnClickListener(v -> {
                c.set(idx);
                closeOptions(true);
            });
            o.setOnKeyListener((v, code, ev) -> {
                if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_BACK
                        || code == KeyEvent.KEYCODE_BUTTON_B || code == KeyEvent.KEYCODE_ESCAPE) {
                    closeOptions(true);
                    return true;
                }
                return false;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            mOptList.addView(o, lp);
            if (i == cur) focus = o;
        }
        String[] extras = c.extras();
        for (int i = 0; extras != null && i < extras.length; i++) {
            final int idx = i;
            TextView o = new TextView(this);
            o.setText("›  " + extras[i]);
            o.setTextColor(DIM);
            o.setTypeface(condensed(false));
            o.setTextSize(17);
            o.setBackgroundResource(R.drawable.dl_row);
            o.setPadding(dp(18), dp(12), dp(18), dp(12));
            o.setFocusable(true);
            o.setClickable(true);
            o.setOnClickListener(v -> {
                c.extra(idx);
                if (mOpen == c) openOptions(c); // its label may have changed (an on/off entry)
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(i == 0 ? 18 : 6);
            mOptList.addView(o, lp);
        }
        mOptList.setVisibility(View.VISIBLE);
        if (focus != null) focus.requestFocus();
    }

    private void openSlider(Slider sl) {
        closeOptions(false);
        mOpen = sl;
        updateScrollArrows();
        mRightScroll.scrollTo(0, 0);
        showDescription(sl.desc);
        mOptList.removeAllViews();

        TextView big = new TextView(this);
        big.setTextColor(0xFFFFC531);
        big.setTypeface(condensed(true));
        big.setTextSize(34);
        big.setGravity(Gravity.CENTER);
        mOptList.addView(big);

        android.widget.SeekBar bar = new android.widget.SeekBar(this);
        // in whole steps (10 % for most); the exact field takes any value
        bar.setMax((sl.max - sl.min) / sl.stepSize);
        bar.setProgress(Math.round((sl.get() - sl.min) / (float) sl.stepSize));
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFFFC531));
        bar.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFFFB020));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF2A5A85));
        bar.setKeyProgressIncrement(1);
        bar.setPadding(dp(16), dp(14), dp(16), dp(14));
        bar.setBackgroundResource(R.drawable.dl_row);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(10);
        mOptList.addView(bar, blp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView lbl = new TextView(this);
        lbl.setText(t("Exact value"));
        lbl.setTextColor(TEXT);
        lbl.setTypeface(condensed(true));
        lbl.setTextSize(17);
        row.addView(lbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        android.widget.EditText field = new android.widget.EditText(this);
        field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        field.setSelectAllOnFocus(true);
        field.setTextColor(TEXT);
        field.setTypeface(condensed(false));
        field.setTextSize(20);
        field.setGravity(Gravity.END);
        field.setEms(4);
        field.setBackgroundResource(R.drawable.dl_row);
        field.setPadding(dp(14), dp(8), dp(14), dp(8));
        row.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView pct = new TextView(this);
        pct.setText(" %");
        pct.setTextColor(TEXT);
        pct.setTextSize(20);
        row.addView(pct);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(10);
        mOptList.addView(row, rlp);

        TextView hint = new TextView(this);
        hint.setText(t("%d%% to %d%%. Default %d%%.", sl.min, sl.max, sl.def));
        hint.setTextColor(DIM);
        hint.setTypeface(condensed(false));
        hint.setTextSize(14);
        hint.setPadding(0, dp(8), 0, 0);
        mOptList.addView(hint);

        final boolean[] updating = {false};
        Runnable show = () -> {
            updating[0] = true;
            int v = sl.get();
            big.setText(v + "%");
            int p = Math.round((v - sl.min) / (float) sl.stepSize);
            if (bar.getProgress() != p) bar.setProgress(p);
            if (!field.hasFocus()) field.setText(Integer.toString(v));
            updating[0] = false;
        };
        show.run();
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar b, int p, boolean user) {
                if (updating[0]) return;
                sl.set(sl.min + p * sl.stepSize);
                show.run();
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar b) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar b) {}
        });
        Runnable apply = () -> {
            try {
                sl.set(Integer.parseInt(field.getText().toString().trim()));
            } catch (NumberFormatException ignored) {}
            field.setText(Integer.toString(sl.get()));
            show.run();
        };
        field.setOnEditorActionListener((v, action, ev) -> {
            apply.run();
            android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
            bar.requestFocus();
            return true;
        });
        field.setOnFocusChangeListener((v, has) -> { if (!has) apply.run(); });
        View.OnKeyListener back = (v, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_B || code == KeyEvent.KEYCODE_ESCAPE) {
                closeOptions(true);
                return true;
            }
            return false;
        };
        // the D-pad steps like the row does, wrapping round past either end
        bar.setOnKeyListener((v, code, ev) -> {
            if (back.onKey(v, code, ev)) return true;
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            int dir = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
            if (dir == 0) return false;
            sl.step(dir);
            show.run();
            return true;
        });
        mExactField = field;
        mExactPick = () -> pickExact(sl, show, bar, field);
        mOptList.setVisibility(View.VISIBLE);
        bar.requestFocus();
    }

    // The exact value with a controller (its keyboard cannot be worked with
    // one): the number, left/right by 1, up/down by 10, A takes it, B not.
    private void pickExact(Slider sl, Runnable show, View back, android.widget.EditText field) {
        final float d = getResources().getDisplayMetrics().density;
        final int[] v = {sl.get()};
        final android.app.Dialog dlg = new android.app.Dialog(this);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.dl_panel);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(dp(24), dp(14), dp(24), dp(16));
        TextView title = new TextView(this);
        title.setText(t("Exact value"));
        title.setTextColor(GOLD);
        title.setTypeface(condensed(true));
        title.setTextSize(22);
        title.setSingleLine(true);
        panel.addView(title);
        TextView num = new TextView(this);
        num.setTextColor(0xFFFFFFFF);
        num.setTypeface(condensed(true));
        num.setTextSize(48);
        num.setGravity(Gravity.CENTER);
        num.setBackgroundResource(R.drawable.dl_row);
        num.setPadding(dp(32), dp(6), dp(32), dp(6));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = nlp.bottomMargin = dp(10);
        panel.addView(num, nlp);
        TextView hint = new TextView(this);
        hint.setText(t("◀ ▶  ±1     ▲ ▼  ±10     A  OK     B  Cancel"));
        hint.setTextColor(DIM);
        hint.setTextSize(15);
        panel.addView(hint);
        // three digits, so the number keeps its width (and the panel its size)
        Runnable draw = () -> num.setText(String.format(java.util.Locale.ROOT, "%03d %%", v[0]));
        draw.run();
        dlg.setContentView(panel);
        dlg.setOnKeyListener((x, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return code != KeyEvent.KEYCODE_BACK;
            int step = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1
                : code == KeyEvent.KEYCODE_DPAD_DOWN ? -10 : code == KeyEvent.KEYCODE_DPAD_UP ? 10 : 0;
            if (step != 0) {
                // past either end it wraps round, as the row does
                v[0] = v[0] + step > sl.max ? (v[0] == sl.max ? sl.min : sl.max)
                     : v[0] + step < sl.min ? (v[0] == sl.min ? sl.max : sl.min) : v[0] + step;
                draw.run();
                return true;
            }
            if (code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_DPAD_CENTER
                    || code == KeyEvent.KEYCODE_ENTER) {
                sl.set(v[0]);
                // the field too: it keeps its text while focused, and would
                // set its old value again when the focus leaves it
                field.setText(Integer.toString(sl.get()));
                show.run();
                dlg.dismiss();
                back.requestFocus();
                return true;
            }
            if (code == KeyEvent.KEYCODE_BUTTON_B || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
                dlg.dismiss();
                back.requestFocus();
                return true;
            }
            return false;
        });
        android.view.Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            w.setLayout(dp(380), ViewGroup.LayoutParams.WRAP_CONTENT);  // one size, whatever the number
        }
        dlg.show();
    }

    @Override
    protected boolean onPadA(View focused) {
        if (focused != null && focused == mExactField && mExactPick != null) {
            mExactPick.run();
            return true;
        }
        return false;
    }

    private void closeOptions(boolean refocusRow) {
        Panel back = mReturnPanel;
        int backIndex = mReturnIndex;
        mReturnPanel = null;
        mExactField = null;
        mExactPick = null;
        Row c = mOpen;
        mOpen = null;
        mOptList.removeAllViews();
        mOptList.setVisibility(View.GONE);
        if (refocusRow && back != null) { openPanel(back, backIndex); return; }
        if (refocusRow && c != null && c.view != null) c.view.requestFocusFromTouch();
        mRightScroll.post(this::updateScrollArrows);
    }

    @Override
    public void onBackPressed() {
        if (mOpen != null) closeOptions(true);
        else super.onBackPressed();
    }

    private void refreshRow(Row r) {
        String v = r.value();
        r.valueView.setText(r.hasArrow() ? "›" : t(v));
        r.view.setAlpha(r.enabled ? 1f : 0.45f);
    }

    private void refreshValues() {
        for (Row r : mRows) refreshRow(r);
        if (mOpen != null && mPanel != null && mOpen == mPanel.row) updatePanel();
    }

    // controller: AppActivity's scheme, tabs like Dusklight
    @Override protected boolean usesPadNav() { return true; }

    @Override
    protected boolean onPadTab(int dir, boolean shoulder) {
        if (mOpen != null) {
            if (!shoulder) return false;  // the open choices or slider take left/right
            closeOptions(false);
        }
        showTab((mTab + dir + mTabs.size()) % mTabs.size());
        padFocusFirst();
        return true;
    }

    @Override
    protected List<View> padItems() {
        if (mOpen != null) {
            if (mPanel != null && mOpen == mPanel.row && mPanel.orderedNavigation && mPanelItems != null)
                return java.util.Arrays.asList(mPanelItems);
            return null; // sliders and other choices use Android's own order
        }
        List<View> v = new ArrayList<>();
        for (Row r : mRows) v.add(r.view);
        return v;
    }

    // --- GAME ---------------------------------------------------------------

    private void finishWith(String action) {
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_ACTION, action));
        finish();
    }

    private void buildGame() {
        section("DISC");
        String inGameNote = "\n\n" + t("Quit to the main menu to change this.");
        Row disc = new Action("Disc image",
            t("The Super Mario Sunshine disc image the game starts from (USA, GMSE01, revision 0). "
                + "A picked file is checked first and copied into iso/ in your data folder.") + (mInGame ? inGameNote : ""),
            () -> {
                File iso = LauncherActivity.findIsoFile(ctx());
                return iso != null ? iso.getName() : "None";
            },
            () -> finishWith(ACTION_PICK_ISO));
        disc.enabled = !mInGame;
        add(disc);
        Row verify = new Action("Verify disc",
            t("Checks that the disc image is a good, complete copy of the game. Takes a moment; the result is "
                + "remembered.") + (mInGame ? inGameNote : ""),
            () -> {
                File iso = LauncherActivity.findIsoFile(ctx());
                if (iso == null) return "—";
                String sha = DiscCheck.cachedSha1(ctx(), iso);
                return sha == null ? "Not checked" : sha.equals(DiscCheck.REDUMP_SHA1) ? "✓ Verified" : "⚠ Differs";
            },
            () -> finishWith(ACTION_VERIFY_ISO));
        verify.enabled = !mInGame;
        add(verify);

        section("LANGUAGE");
        // the game's language, with the language packs' maker and the cutscene
        // subtitles (one setting with the game's own option) under its choices
        add(new Choice("language", "Game language",
            "The language of the game's text and menus. English is built in; French, German, Spanish and Italian "
                + "can be made from your own European disc image (below the languages).\n\nSubtitles in cutscenes "
                + "is the same setting as the subtitle option in the game's own options.\n\nThe language applies "
                + "the next time the game starts.",
            "en", new String[] {"en"}, new String[] {"English"}) {
            @Override void reload() {
                List<String> v = new ArrayList<>(), l = new ArrayList<>();
                v.add("en");
                l.add("English");
                java.io.File[] fs = DataDir.languagesDir(ctx()).listFiles();
                if (fs != null) {
                    java.util.Arrays.sort(fs);
                    for (java.io.File f : fs)
                        if (new java.io.File(f, "files").isDirectory() && !f.getName().startsWith(".")) {
                            v.add(f.getName());
                            l.add(LanguagePackActivity.languageName(f.getName()));
                        }
                }
                String cur = mSettings.getOrDefault(key, "en");
                if (!v.contains(cur)) {
                    v.add(cur);
                    l.add(t("%s (not installed)", LanguagePackActivity.languageName(cur)));
                }
                values = v.toArray(new String[0]);
                labels = l.toArray(new String[0]);
            }

            boolean subtitles() { return !"0".equals(mSettings.getOrDefault("SMS_SUBTITLES", "1")); }

            @Override String[] extras() {
                java.io.File pal = LanguagePackActivity.europeanDisc(ctx());
                List<String> e = new ArrayList<>();
                e.add(values.length > 1 ? t("Install the languages again from your own EU .iso file")
                                        : t("Extract languages from your own EU .iso file"));
                e.add(t("Subtitles in cutscenes") + ": " + t(subtitles() ? "On" : "Off"));
                if (pal != null) e.add(t("Delete the European disc image (%.1f GB)", pal.length() / 1e9));
                return e.toArray(new String[0]);
            }

            @Override void extra(int i) {
                if (i == 2) {
                    java.io.File pal = LanguagePackActivity.europeanDisc(ctx());
                    if (pal == null) return;
                    dialog().setTitle(t("Delete the European disc image?"))
                        .setMessage(t("The language packs do not need it any more. Delete %s?", pal.getName()))
                        .setPositiveButton(t("Delete it"), (d, w) -> {
                            String err = LanguagePackActivity.deleteDisc(pal);
                            if (err != null)
                                dialog().setTitle(t("The European disc could not be deleted")).setMessage(err)
                                    .setPositiveButton(t("OK"), null).show();
                            refreshValues();
                        })
                        .setNegativeButton(t("Cancel"), null).show();
                    return;
                }
                if (i == 0) {
                    if (mInGame)
                        android.widget.Toast.makeText(ctx(), t("Only in the main menu, not while the game runs."),
                            android.widget.Toast.LENGTH_SHORT).show();
                    else
                        startActivity(new Intent(SettingsActivity.this, LanguagePackActivity.class));
                } else {
                    mSettings.put("SMS_SUBTITLES", subtitles() ? "0" : "1");
                    saved();
                }
            }
        });

        // the mixer's music and sound effect gains (platform/audio, decomp-patches/audio-05)
        section("SOUND");
        add(new Volume("SMS_MUSIC_VOLUME", "Music",
            "How loud the music is. 0% turns it off."));
        add(new Volume("SMS_SFX_VOLUME", "Sound effects",
            "How loud sound effects and voices are. 0% turns them off."));

        section("DATA");
        Row data = new Action("Data folder",
            t("Where your settings, saves, disc image, texture packs and logs are kept. They are copied over "
                + "when you change it.") + (mInGame ? inGameNote : ""),
            () -> {
                File d = DataDir.get(ctx());
                return DataDir.isCustom(ctx()) ? d.getName() : "App folder";
            },
            () -> finishWith(ACTION_DATA_DIR));
        data.enabled = !mInGame;
        add(data);

        section("STARTUP");
        add(new Row("Start game automatically",
            "When a working disc image is set, opening the app starts the game straight away, without this "
                + "menu.") {
            boolean on() { return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_AUTOSTART, false); }
            @Override String value() { return on() ? "On" : "Off"; }
            @Override void step(int dir) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_AUTOSTART, !on()).apply();
                refreshValues();
            }
        });
    }

    // --- VIDEO --------------------------------------------------------------

    private static final String[][] PRESETS = {
        // name, render_scale, frame_rate, widescreen, widescreen_hud, draw_distance, msaa, anisotropic, fxaa, shadows/reflections/water/shimmer disabled
        {"Recommended (HD)", "1.5", "30", "16:9", "edges", "1.0", "0", "4", "0", "0", "0", "0", "0"},
        {"Low", "0.5", "30", "16:9", "centre", "0.7", "0", "0", "0", "1", "1", "1", "1"},
        {"Medium", "1.0", "30", "16:9", "edges", "1.0", "0", "2", "0", "0", "0", "0", "1"},
        {"High", "1.5", "30", "16:9", "edges", "1.5", "2", "4", "1", "0", "0", "0", "0"},
        {"Ultra", "4.0", "60", "16:9", "edges", "3.0", "8", "16", "1", "0", "0", "0", "0"},
    };
    private static final String[] PRESET_KEYS = {"render_scale", "frame_rate", "widescreen", "widescreen_hud",
        "draw_distance", "msaa", "anisotropic", "fxaa", "disable_shadows", "disable_reflections",
        "disable_water_anim", "disable_shimmer"};

    private int currentPreset() {
        for (int p = 0; p < PRESETS.length; p++) {
            boolean all = true;
            for (int k = 0; k < PRESET_KEYS.length && all; k++)
                all = PRESETS[p][k + 1].equals(mSettings.getOrDefault(PRESET_KEYS[k], Settings.def(PRESET_KEYS[k], "")));
            if (all) return p;
        }
        return -1;
    }

    private String screenRatio() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = Math.max(dm.widthPixels, dm.heightPixels), h = Math.min(dm.widthPixels, dm.heightPixels);
        int a = w, b = h;
        while (b != 0) { int t = a % b; a = b; b = t; }
        return a > 0 ? (w / a) + ":" + (h / a) : "16:9";
    }

    // the screen's shape as players know it: the nearest of the usual ones
    // (2142x960 is exactly 357:160, about 20:9)
    private static String roughRatio(String exact) {
        String[] known = {"4:3", "16:10", "16:9", "18:9", "19.5:9", "20:9", "21:9", "32:9"};
        float r = ratio(exact);
        String best = exact;
        float diff = Float.MAX_VALUE;
        for (String k : known) {
            float d = Math.abs(ratio(k) - r);
            if (d < diff) { diff = d; best = k; }
        }
        return best.equals(exact) ? exact : "≈ " + best;
    }

    private static float ratio(String s) {
        String[] p = s.split(":");
        try { return Float.parseFloat(p[0]) / Float.parseFloat(p[1]); } catch (Exception e) { return 0; }
    }

    private void buildVideo() {
        section("PROFILE");
        add(new Row("Profile", "") {
            @Override String value() { int p = currentPreset(); return p < 0 ? "Custom" : PRESETS[p][0]; }
            @Override void step(int dir) {
                int p = currentPreset();
                int n = p < 0 ? 0 : (p + dir + PRESETS.length) % PRESETS.length;
                for (int k = 0; k < PRESET_KEYS.length; k++) mSettings.put(PRESET_KEYS[k], PRESETS[n][k + 1]);
                saved();
            }
        });

        section("RESOLUTION");
        add(new Choice("render_scale", "Internal resolution",
            "How sharp the game is drawn. 2× is about Full HD (1080p). Higher looks sharper but needs a faster "
                + "device.",
            Settings.def("render_scale", "1.5"),
            new String[] {"0.5", "0.75", "1.0", "1.5", "2.0", "3.0", "4.0"},
            new String[] {"0.5× (264p)", "0.75× (396p)", "1× (528p, GameCube)", "1.5× (792p, ~720p)",
                "2× (1056p, ~1080p)", "3× (1584p)", "4× (2112p, ~4K)"}));
        add(new Choice("frame_rate", "Frame rate",
            "30 FPS is the game's own. 60 FPS is smoother in levels but needs a fast device; 120 FPS also needs "
                + "a 120 Hz screen. The game runs at the same speed either way; menus and videos stay at 30.",
            "30", new String[] {"30", "60", "120"}, new String[] {"30 FPS", "60 FPS", "120 FPS"}));

        section("PICTURE");
        String auto = screenRatio(), rough = roughRatio(auto);
        List<String> wv = new ArrayList<>(java.util.Arrays.asList("16:9", "16:10", "20:9", "21:9", "off"));
        List<String> wl = new ArrayList<>(java.util.Arrays.asList("16:9", "16:10", "20:9", "21:9", "Off (4:3)"));
        if (!wv.contains(auto)) { wv.add(1, auto); wl.add(1, t("This screen (%s)", rough)); }
        add(new Choice("widescreen", "Widescreen",
            t("The game camera shows a wider view; nothing is stretched. Choose your screen's shape "
                + "(this screen is %s). Cutscene videos stay 4:3.", rough),
            "16:9", wv.toArray(new String[0]), wl.toArray(new String[0])));
        add(new Choice("widescreen_hud", "HUD position",
            "Where coins, the water gauge and lives are shown in widescreen.",
            "edges", new String[] {"edges", "centre"}, new String[] {"Screen edges", "4:3 area"}));

        section("QUALITY");
        add(new Choice("draw_distance", "Draw distance",
            "How far away objects are still drawn. 1.0 is the original; 1.5 shows clearly more.",
            Settings.def("draw_distance", "1.0"), new String[] {"0.7", "1.0", "1.5", "2.0", "3.0", "5.0"},
            new String[] {"0.7×", "1× (original)", "1.5×", "2×", "3×", "5×"}));
        add(new Choice("msaa", "Anti-aliasing (MSAA)",
            "Smooths jagged edges. Higher values need more GPU power.",
            Settings.def("msaa", "0"), new String[] {"0", "2", "4", "8"}, new String[] {"Off", "2×", "4×", "8×"}));
        add(new Choice("anisotropic", "Texture filtering",
            "Keeps the ground and walls sharp at an angle.",
            "4", new String[] {"0", "2", "4", "8", "16"}, new String[] {"Off", "2×", "4×", "8×", "16×"}));
        add(new Toggle("fxaa", "FXAA",
            "A soft extra edge filter. Usually not needed with MSAA.", false, false));

        section("TEXTURE PACK AND CUTSCENES");
        Row[] pack = new Row[1];
        pack[0] = new Action("Texture pack settings", t(TEXTURE_INTRO) + "\n\n" + t(TEXTURE_MORE) + (Mods.MENU ? "\n\n" + t(TEXTURE_MIX) : ""),
            () -> !texturesOn() ? t("Off") : t(packLabel()) + (TexturePackActivity.extrasInstalled(ctx()) ? t(" + extras") : ""),
            () -> openPanel(texturePanel(pack[0])));
        add(pack[0]);
        Row[] cut = new Row[1];
        cut[0] = new Action("Cutscene settings", t(CUTSCENE_INTRO) + "\n\n" + t(CUTSCENE_MORE),
            () -> t(cutsceneModeLabel()),
            () -> openPanel(cutscenePanel(cut[0])));
        add(cut[0]);

        section("EFFECTS");
        add(new Toggle("disable_shadows", "Shadows", "Character and object shadows. Off is faster.", true, true));
        add(new Toggle("disable_reflections", "Reflections", "Reflections on water and shiny surfaces.", true, true));
        add(new Toggle("disable_water_anim", "Water animation", "The animated water surface.", true, true));
        add(new Toggle("disable_shimmer", "Heat shimmer", "The shimmer and glow effects.", true, true));
        add(new Toggle("copy_writeback", "Copy write-back",
            "Copies what the game draws into its own textures back to the game's memory, as on the GameCube. Turning it off can raise FPS on weaker devices; the goop map is always copied, so cleaning goop still works. Restart the game after changing this.",
            true, false));
        section("RESET");
        add(new Action("Reset video settings", "Sets the VIDEO tab back to Recommended (HD).", () -> "", () -> {
            for (int k = 0; k < PRESET_KEYS.length; k++) mSettings.put(PRESET_KEYS[k], PRESETS[0][k + 1]);
            mSettings.put("aspect", "keep");
            saved();
            android.widget.Toast.makeText(this, t("Video settings reset to Recommended (HD)."), android.widget.Toast.LENGTH_SHORT).show();
        }));
    }

    // --- texture packs (TexturePackActivity does the work) -------------------

    private static final String TEXTURE_INTRO = "Sharper textures from a texture pack. Restart the game after changing packs.";
    // several texture sources at once (this pack and mods with textures)
    static final String TEXTURE_MIX = "Texture packs from mods load after this one and win where both replace the same "
        + "texture. Several packs at once can clash: textures that do not match, or more video memory and longer "
        + "loading. If something looks wrong, switch the other packs off.";

    private static final String TEXTURE_MORE =
        "Download the Super Mario Sunshine UHD Texture Pack by qashto and razius, in HD (1080p) or Ultra HD "
            + "(4K). Or use your own pack: copy it into textures/ in your data folder (a Dolphin pack for the North "
            + "American version).\n\n"
            + "The HD extras add HD text and menus for the other languages. They also work with your own pack, "
            + "but replace some of its textures.";

    private boolean texturesOn() { return !"0".equals(mSettings.getOrDefault("SMS_HD_TEXTURES", "1")); }

    private String packLabel() {
        String p = TexturePackActivity.installedPack(this);
        return p == null ? "No pack" : p.equals("1080") ? "HD 1080p" : p.equals("4k") ? "Ultra HD 4K" : "Your own pack";
    }

    // A row's options in the right panel (the texture pack's, the cutscenes'):
    // a short intro, rows with a value each, and the longer text below them.
    // The values change in place, so the focus stays where it is.
    private static final class Panel {
        Row row;
        String intro, more;
        String note; // a second paragraph after `more`, translated apart
        String[] labels;
        String[] sections;
        java.util.function.Supplier<String>[] values;
        java.util.function.BooleanSupplier[] gold; // the value is shown gold (switched on)
        Runnable[] acts;
        boolean orderedNavigation;
    }

    private Panel mPanel;
    private TextView[] mPanelValues;

    @SuppressWarnings("unchecked")
    private Panel texturePanel(Row row) {
        Panel p = new Panel();
        p.row = row;
        p.intro = TEXTURE_INTRO;
        p.more = TEXTURE_MORE;
        if (Mods.MENU) p.note = TEXTURE_MIX;
        p.labels = new String[] {"Texture pack", "Download texture pack", "Download HD extras"};
        p.values = new java.util.function.Supplier[] {
            () -> texturesOn() ? "On" : "Off",
            this::packLabel,
            () -> TexturePackActivity.extrasInstalled(this) ? "Installed" : "Not installed"};
        p.gold = new java.util.function.BooleanSupplier[] {this::texturesOn, () -> false, () -> false};
        p.acts = new Runnable[] {
            () -> {
                boolean now = !texturesOn();
                mSettings.put("SMS_HD_TEXTURES", now ? "1" : "0");
                saved();
                if (now) afterTexturesOn();
            },
            this::choosePack,
            this::chooseExtras};
        return p;
    }

    private void openPanel(Panel p) { openPanel(p, 0); }

    private void openPanel(Panel p, int focusIndex) {
        closeOptions(false);
        mOpen = p.row;
        mPanel = p;
        showDescription(t(p.intro));
        mOptList.removeAllViews();
        mPanelValues = new TextView[p.labels.length];
        mPanelItems = new View[p.labels.length];
        View first = null;
        for (int i = 0; i < p.labels.length; i++) {
            final int idx = i;
            if (p.sections != null && p.sections[i] != null) {
                TextView heading = new TextView(this);
                heading.setText(t(p.sections[i]));
                heading.setTextColor(0xFF5ED3DD);
                heading.setTypeface(condensed(true));
                heading.setTextSize(20);
                heading.setPadding(0, dp(18), 0, dp(6));
                heading.setFocusable(false);
                mOptList.addView(heading);
            }
            LinearLayout o = new LinearLayout(this);
            o.setOrientation(LinearLayout.HORIZONTAL);
            o.setGravity(Gravity.CENTER_VERTICAL);
            o.setBackgroundResource(R.drawable.dl_row);
            o.setPadding(dp(18), dp(12), dp(18), dp(12));
            o.setFocusable(true);
            o.setClickable(true);
            TextView l = new TextView(this);
            l.setText(t(p.labels[i]));
            l.setTextColor(TEXT);
            l.setTypeface(condensed(true));
            l.setTextSize(18);
            l.setAllCaps(true);
            hyphenate(l);
            o.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));
            TextView v = new TextView(this);
            mPanelValues[i] = v;
            v.setTextColor(TEXT);
            v.setTypeface(condensed(false));
            v.setTextSize(18);
            v.setGravity(Gravity.END);
            o.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            mPanelItems[i] = o;
            if (p.orderedNavigation) o.setOnFocusChangeListener((item, focused) -> {
                if (focused) item.post(() -> item.requestRectangleOnScreen(
                    new android.graphics.Rect(0, 0, item.getWidth(), item.getHeight()), true));
            });
            o.setOnClickListener(x -> p.acts[idx].run());
            o.setOnKeyListener((x, code, ev) -> {
                if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_BACK
                        || code == KeyEvent.KEYCODE_BUTTON_B || code == KeyEvent.KEYCODE_ESCAPE) {
                    closeOptions(true);
                    return true;
                }
                return false;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            mOptList.addView(o, lp);
            if (i == focusIndex) first = o;
        }
        TextView more = new TextView(this);
        more.setText(t(p.more) + (p.note != null ? "\n\n" + t(p.note) : ""));
        more.setTextColor(DIM);
        more.setTypeface(condensed(false));
        more.setTextSize(17);
        more.setLineSpacing(0, 1.2f);
        more.setPadding(0, dp(16), 0, 0);
        mOptList.addView(more);
        updatePanel();
        mOptList.setVisibility(View.VISIBLE);
        // Joystick motion does not leave Android touch mode, so a controller
        // forces the focus; after a tap it must not, or the next tap re-enters
        // touch mode, the focus jumps to a list row and that closes the panel.
        if (first != null) {
            if (first.isInTouchMode()) first.requestFocus();
            else first.requestFocusFromTouch();
        }
        mRightScroll.post(this::updateScrollArrows);
    }

    private void updatePanel() {
        for (int i = 0; i < mPanelValues.length; i++) {
            boolean g = mPanel.gold[i].getAsBoolean();
            mPanelValues[i].setText(t(mPanel.values[i].get()));
            mPanelValues[i].setTextColor(g ? GOLD : TEXT);
        }
    }

    // --- HD cutscenes (CutsceneActivity does the work) -------------------------

    private static final String CUTSCENE_INTRO =
        "The game's movies in HD. Restart the game after changing this.";
    private static final String CUTSCENE_MORE =
        "The game's 21 movies, upscaled with AI by chasem-dev. The app downloads 5.7 GB and builds the movies "
            + "from your own disc image; they take about 5.8 GB in your data folder.\n\n"
            + "If the HD movies stutter on this device, Convert to MP4 makes lighter videos of them (about 1 GB, "
            + "10–20 minutes); the large original files can be deleted after that.\n\n"
            + "Frame around cutscenes fills the black bars beside the movies with an image. You can replace it with "
            + "your own (cutscenes/overlay.png in your data folder, best 1920×1080).\n\n"
            + "Wide movies across the screen shows the wide movies larger, across the whole widescreen.";

    private boolean cutscenesOn() {
        String v = mSettings.getOrDefault(CutscenePack.SETTING, "0");
        return !v.equals("0") && !v.isEmpty() && CutscenePack.complete(this);
    }

    private boolean mp4On() {
        String v = mSettings.getOrDefault(CutsceneActivity.MP4_SETTING, "0");
        return !v.equals("0") && !v.isEmpty() && mp4Ready();
    }

    private boolean mp4Ready() { return CutsceneActivity.mp4Count(this) == CutsceneActivity.streamedCount(this); }

    private String cutsceneModeLabel() { return mp4On() ? "HD (MP4)" : cutscenesOn() ? "HD (THP)" : "Original"; }

    // Original -> HD (THP) -> HD (MP4) -> Original, as far as installed
    private void nextCutsceneMode() {
        if (!CutscenePack.complete(this) && !mp4Ready()) { askCutscenes(); return; }
        String mode = mp4On() ? "mp4" : cutscenesOn() ? "thp" : "off";
        String next;
        if (mode.equals("off")) next = CutscenePack.complete(this) ? "thp" : "mp4";
        else if (mode.equals("thp")) next = mp4Ready() ? "mp4" : "off";
        else next = "off";
        mSettings.put(CutscenePack.SETTING, next.equals("thp") ? CutscenePack.dir(this).getAbsolutePath() : "0");
        mSettings.put(CutsceneActivity.MP4_SETTING, next.equals("mp4") ? CutsceneActivity.mp4Dir(this).getAbsolutePath() : "0");
        saved();
        restartNote();
    }

    // In the game's menu: a setting the game reads when it starts
    private void restartNote() {
        if (mInGame)
            android.widget.Toast.makeText(this, t("Takes effect after the game restarts."), android.widget.Toast.LENGTH_LONG).show();
    }

    private String cutsceneState() {
        int n = CutscenePack.installedCount(this);
        if (CutscenePack.complete(this)) return "Installed";
        return n == 0 ? "Not installed" : t("%d of 21", n);
    }

    private String mp4State() {
        int n = CutsceneActivity.mp4Count(this);
        if (mp4Ready()) return "Converted";
        return n == 0 ? "Not converted" : t("%d of %d", n, CutsceneActivity.streamedCount(this));
    }

    @SuppressWarnings("unchecked")
    private Panel cutscenePanel(Row row) {
        Panel p = new Panel();
        p.row = row;
        p.intro = CUTSCENE_INTRO;
        p.more = CUTSCENE_MORE;
        p.labels = new String[] {"HD cutscenes", "Download HD cutscenes", "Convert to MP4 (for weaker devices)",
            "Frame around cutscenes", "Wide movies across the screen"};
        p.values = new java.util.function.Supplier[] {this::cutsceneModeLabel, this::cutsceneState, this::mp4State,
            () -> frameOn() ? "On" : "Off", () -> fillOn() ? "On" : "Off"};
        p.gold = new java.util.function.BooleanSupplier[] {() -> mp4On() || cutscenesOn(), () -> false, () -> false,
            this::frameOn, this::fillOn};
        p.acts = new Runnable[] {this::nextCutsceneMode, this::chooseCutscenes, this::chooseMp4, this::toggleFrame,
            () -> { mSettings.put(FILL_SETTING, fillOn() ? "0" : "1"); saved(); }};
        return p;
    }

    // --- the frame around cutscenes (SMS_CUTSCENE_FRAME, gx_render.cpp) -------

    static final String FRAME_SETTING = "SMS_CUTSCENE_FRAME";
    static final String FILL_SETTING = "SMS_MOVIE_FILL"; // wide (2:1) movies across the widened screen; on by default

    private boolean fillOn() { return !"0".equals(mSettings.getOrDefault(FILL_SETTING, "1")); }

    private java.io.File frameFile() { return new java.io.File(CutscenePack.dir(this), "overlay.png"); }

    private boolean frameOn() {
        String v = mSettings.getOrDefault(FRAME_SETTING, "0");
        return !v.equals("0") && !v.isEmpty();
    }

    private boolean ensureFrameFile() {
        CutscenePack.installFrames(this);
        return frameFile().isFile();
    }

    private void toggleFrame() {
        if (frameOn()) {
            mSettings.put(FRAME_SETTING, "0");
            saved();
            return;
        }
        if (!ensureFrameFile()) {
            android.widget.Toast.makeText(this, t("Put a PNG image at %s first.", frameFile().getAbsolutePath()),
                android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        mSettings.put(FRAME_SETTING, frameFile().getAbsolutePath());
        saved();
    }

    private void chooseMp4() {
        if (mInGame && mp4Ready() && !mp4On()) {  // switching to the converted MP4s needs no main menu
            mSettings.put(CutscenePack.SETTING, "0");
            mSettings.put(CutsceneActivity.MP4_SETTING, CutsceneActivity.mp4Dir(this).getAbsolutePath());
            saved();
            restartNote();
            return;
        }
        if (mInGame) {
            android.widget.Toast.makeText(this, t("Quit to the main menu to change this."), android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        if (!mp4Ready()) {
            if (!CutscenePack.complete(this)) {
                android.widget.Toast.makeText(this, t("Install the HD cutscenes first."), android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            dialog().setTitle(t("Convert the HD cutscenes to MP4?"))
                .setMessage(t("Makes H.264 videos of the 20 HD movies with this device's video encoder, about 1 GB. "
                    + "It takes 10–20 minutes; keep the app open. The game then plays them with the hardware video "
                    + "decoder, which is much lighter than the HD pack's THPs."))
                .setPositiveButton(t("Convert"), (d, w) -> startActivity(new Intent(this, CutsceneActivity.class)
                    .putExtra(CutsceneActivity.EXTRA_JOB, CutsceneActivity.JOB_CONVERT)))
                .setNegativeButton(t("Not now"), null).show();
            return;
        }
        boolean thps = CutscenePack.complete(this);
        String[] items = thps ? new String[] {t("Delete the HD pack's THPs (keep the MP4s)"), t("Remove the MP4s")}
                              : new String[] {t("Remove the MP4s")};
        dialog().setTitle(t("Convert to MP4 (for weaker devices)"))
            .setItems(items, (d, w) -> {
                if (thps && w == 0) {
                    dialog().setTitle(t("Delete the HD pack's THPs?"))
                        .setMessage(t("Frees %s. The HD cutscenes keep playing from the MP4s; HD (THP) is no longer available until you install the pack again.",
                            CutsceneActivity.gb(CutscenePack.size(CutscenePack.thpDir(this)))))
                        .setPositiveButton(t("Delete"), (d2, w2) -> {
                            TexturePackActivity.remove(new java.io.File(CutscenePack.dir(this), "files"));
                            TexturePackActivity.remove(new java.io.File(CutscenePack.dir(this), ".checked"));
                            new java.io.File(CutscenePack.dir(this), CutscenePack.MARKER).delete();
                            mSettings.put(CutscenePack.SETTING, "0");
                            mSettings.put(CutsceneActivity.MP4_SETTING, CutsceneActivity.mp4Dir(this).getAbsolutePath());
                            saved();
                        })
                        .setNegativeButton(t("Cancel"), null).show();
                } else {
                    TexturePackActivity.remove(CutsceneActivity.mp4Dir(this));
                    mSettings.put(CutsceneActivity.MP4_SETTING, "0");
                    saved();
                }
            })
            .setNegativeButton(t("Cancel"), null).show();
    }

    private void askCutscenes() {
        if (mInGame) {
            android.widget.Toast.makeText(this, t("Quit to the main menu to change this."), android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        dialog().setTitle(t("Install the HD cutscenes?"))
            .setMessage(t("Downloads 5.7 GB of patches and builds the 21 HD movies from your own disc image. "
                + "Needs about 6.8 GB free while installing and 5.8 GB afterwards. It can take a while; an "
                + "interrupted installation continues where it stopped."))
            .setPositiveButton(t("Install"), (d, w) -> startActivity(new Intent(this, CutsceneActivity.class)))
            .setNegativeButton(t("Not now"), null)
            .show();
    }

    private void chooseCutscenes() {
        if (!CutscenePack.complete(this) && CutscenePack.installedCount(this) == 0) { askCutscenes(); return; }
        String[] items = CutscenePack.complete(this)
            ? new String[] {t("Remove the HD cutscenes")}
            : new String[] {t("Continue the installation"), t("Remove the HD cutscenes")};
        dialog().setTitle(t("HD cutscenes"))
            .setItems(items, (d, w) -> {
                if (items.length == 2 && w == 0) { askCutscenes(); return; }
                dialog().setTitle(t("Remove the HD cutscenes?"))
                    .setMessage(t("Deletes cutscenes/ from your data folder (%s). The game plays its original movies again.",
                        CutsceneActivity.gb(CutscenePack.size(CutscenePack.dir(this)))))
                    .setPositiveButton(t("Remove"), (d2, w2) -> {
                        TexturePackActivity.remove(CutscenePack.dir(this));
                        mSettings.put(CutscenePack.SETTING, "0");
                        mSettings.put(CutsceneActivity.MP4_SETTING, "0");
                        saved();
                    })
                    .setNegativeButton(t("Cancel"), null).show();
            })
            .setNegativeButton(t("Cancel"), null).show();
    }

    private DlDialog dialog() {
        return new DlDialog(this);
    }

    private void startPackJob(String job) {
        startActivity(new Intent(this, TexturePackActivity.class).putExtra(TexturePackActivity.EXTRA_JOB, job));
    }

    private void choosePack() {
        String cur = TexturePackActivity.installedPack(this);
        List<String> items = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        if ("1080".equals(cur) || "4k".equals(cur)) {
            items.add(t("Install again"));
            acts.add(() -> startPackJob("4k".equals(cur) ? TexturePackActivity.JOB_4K : TexturePackActivity.JOB_1080));
        }
        items.add(t("Download HD, 1080p  (156 MB, recommended)"));
        acts.add(() -> startPackJob(TexturePackActivity.JOB_1080));
        items.add(t("Download Ultra HD, 4K  (986 MB, 3.2 GB on the device)"));
        acts.add(() -> startPackJob(TexturePackActivity.JOB_4K));
        if ("1080".equals(cur) || "4k".equals(cur)) {
            items.add(t("Remove the installed pack"));
            acts.add(() -> dialog().setTitle(t("Remove the texture pack?"))
                .setMessage(t("Deletes textures/GMS from your data folder. Your own packs and the extras stay."))
                .setPositiveButton(t("Remove"), (d, w) -> {
                    TexturePackActivity.remove(new java.io.File(DataDir.texturesDir(this), "GMS"));
                    refreshValues();
                })
                .setNegativeButton(t("Cancel"), null).show());
        }
        dialog().setTitle(t("Texture pack: HD or Ultra HD"))
            .setItems(items.toArray(new String[0]), (d, w) -> acts.get(w).run())
            .setNegativeButton(t("Cancel"), null)
            .show();
    }

    private void chooseExtras() {
        if (TexturePackActivity.extrasInstalled(this)) {
            dialog().setTitle(t("HD extras"))
                .setItems(new String[] {t("Install again"), t("Remove")}, (d, w) -> {
                    if (w == 0) startPackJob(TexturePackActivity.JOB_EXTRAS);
                    else {
                        TexturePackActivity.remove(new java.io.File(DataDir.texturesDir(this), TexturePackActivity.EXTRAS.installDir));
                        refreshValues();
                    }
                })
                .setNegativeButton(t("Cancel"), null).show();
        } else {
            askExtras(t("Install the HD extras?"));
        }
    }

    private void askExtras(String title) {
        dialog().setTitle(title)
            .setMessage(t("208 extra HD textures the default texture pack does not cover, 64 MB.\n\n"
                + "Caution: if you use your own texture pack, the extras can replace some of its textures."))
            .setPositiveButton(t("Install"), (d, w) -> startPackJob(TexturePackActivity.JOB_EXTRAS))
            .setNegativeButton(t("Not now"), null)
            .show();
    }

    // HD textures just turned on: offer what is missing
    private void afterTexturesOn() {
        String pack = TexturePackActivity.installedPack(this);
        if (pack == null) {
            dialog().setTitle(t("No texture pack installed"))
                .setMessage(t("Download the texture pack now (HD 1080p or Ultra HD 4K)? You can also add your own pack to textures/ "
                    + "in your data folder instead."))
                .setPositiveButton(t("Choose a pack"), (d, w) -> choosePack())
                .setNegativeButton(t("Later"), null)
                .show();
        } else if (!TexturePackActivity.extrasInstalled(this)) {
            askExtras(t("The HD extras seem not to be installed. Install them?"));
        }
    }

    // --- INPUT --------------------------------------------------------------

    private static boolean isGamepad(InputDevice d) {
        if (d == null || d.isVirtual()) return false;
        int s = d.getSources();
        return (s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
            || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    @SuppressWarnings("unchecked")
    private Panel stickBehaviorPanel(Row row) {
        Panel p = new Panel();
        p.row = row;
        p.orderedNavigation = true;
        p.intro = "Stick assignment, inversion, dead zones and sensitivity.";
        p.more = "Camera X inversion is on by default. FLUDD inversion is off by default. Restart the game after changing these settings.";
        String[] keys = {"SMS_CAMERA_INVERT_X", "SMS_CAMERA_INVERT_Y", "SMS_FLUDD_INVERT_X", "SMS_FLUDD_INVERT_Y"};
        p.labels = new String[] {"Swap sticks", "Left stick: dead zone", "Left stick: sensitivity",
            "C-stick: dead zone", "C-stick: sensitivity", "C-stick: horizontal (X)", "C-stick: vertical (Y)",
            "FLUDD aiming: horizontal (X)", "FLUDD aiming: vertical (Y)", "Calibrate sticks"};
        p.sections = new String[] {"Stick assignment", "Left stick", null, "C-stick", null, null, null, "FLUDD aiming", null, "Calibration"};
        p.values = new java.util.function.Supplier[10];
        p.gold = new java.util.function.BooleanSupplier[10];
        p.acts = new Runnable[10];
        p.gold[0] = () -> StickBehavior.swapped(mSettings);
        p.values[0] = () -> p.gold[0].getAsBoolean() ? "On" : "Off";
        p.acts[0] = () -> { StickBehavior.toggleSwap(mSettings); saved(); };
        Slider[] sliders = {
            new Slider("SMS_STICK_DEADZONE", "Left stick: dead zone",
                "How far the stick can move before Mario reacts, in percent of the full throw. Raise it if Mario "
                    + "walks on his own (a worn or drifting stick); lower it for finer control.", 8, 0, 60, 1),
            new Slider("SMS_STICK_SENSITIVITY", "Left stick: sensitivity",
                "Above 100% Mario reaches full speed with less stick movement; below 100% the stick needs to go "
                    + "further. Useful when a controller's sticks do not reach the rim.", 100, 25, 300, 5),
            new Slider("SMS_CSTICK_DEADZONE", "C-stick: dead zone",
                "How far the camera stick can move before the camera turns, in percent of the full throw.", 8, 0, 60, 1),
            new Slider("SMS_CSTICK_SENSITIVITY", "C-stick: sensitivity",
                "How fast the camera stick reaches full camera speed.", 100, 25, 300, 5)
        };
        for (int i = 0; i < sliders.length; i++) {
            final Slider slider = sliders[i];
            final int index = i + 1;
            p.values[index] = slider::value;
            p.gold[index] = () -> false;
            p.acts[index] = () -> {
                openSlider(slider);
                mReturnPanel = p;
                mReturnIndex = index;
            };
        }
        for (int i = 0; i < keys.length; i++) {
            final String key = keys[i];
            final int index = i + 5;
            p.gold[index] = () -> StickBehavior.inverted(mSettings, key);
            p.values[index] = () -> p.gold[index].getAsBoolean() ? "On" : "Off";
            p.acts[index] = () -> { StickBehavior.toggleInversion(mSettings, key); saved(); };
        }
        p.values[9] = () -> (mSettings.containsKey("SMS_CAL_LEFT") || mSettings.containsKey("SMS_CAL_RIGHT"))
            ? "Calibrated" : "Not calibrated";
        p.gold[9] = () -> false;
        p.acts[9] = () -> startActivity(new Intent(this, StickCalibrationActivity.class));
        return p;
    }

    // TOUCH's finer settings, in the panel on the right
    @SuppressWarnings("unchecked")
    private Panel touchBehaviorPanel(Row row) {
        Panel p = new Panel();
        p.row = row;
        p.orderedNavigation = true;
        p.intro = "Hiding when idle, the swipe camera's inversion and button feedback.";
        p.more = "Swiping on the free part of the screen turns the camera, with an inversion of its own apart from "
            + "the C-stick's. Button feedback pauses while the game itself vibrates and stays off when the "
            + "smartphone's vibration is set to 0%.";
        p.labels = new String[] {"Hide when idle", "Invert horizontal (X)", "Invert vertical (Y)", "Button feedback"};
        p.sections = new String[] {"Display", "Swipe camera", null, "Feedback"};
        p.values = new java.util.function.Supplier[4];
        p.gold = new java.util.function.BooleanSupplier[4];
        p.acts = new Runnable[4];
        Choice hide = new Choice("SMS_TOUCH_AUTOHIDE", "Hide when idle", "", "0",
            new String[] {"0", "3", "5", "10"}, new String[] {"Never", "After 3 s", "After 5 s", "After 10 s"});
        p.values[0] = hide::value;
        p.gold[0] = () -> hide.index() != 0;
        p.acts[0] = () -> hide.step(1);
        String[] keys = {"SMS_SWIPE_INVERT_X", "SMS_SWIPE_INVERT_Y", "SMS_TOUCH_HAPTIC"};
        String[] defs = {"1", "0", "0"};
        for (int i = 0; i < keys.length; i++) {
            final String key = keys[i], def = defs[i];
            final int index = i + 1;
            p.gold[index] = () -> !"0".equals(mSettings.getOrDefault(key, def));
            p.values[index] = () -> p.gold[index].getAsBoolean() ? "On" : "Off";
            p.acts[index] = () -> {
                mSettings.put(key, p.gold[index].getAsBoolean() ? "0" : "1");
                saved();
            };
        }
        return p;
    }

    private void buildInput() {
        section("CONTROLLER");
        add(new Choice("SMS_PAD_DEVICE", "Controller",
            "Which game controller plays. \"Any controller\" uses every connected one; pick one to ignore the "
                + "others (for example a TV remote). Pair Bluetooth controllers in the Android settings.",
            "", new String[] {""}, new String[] {"Any controller"}) {
            @Override void reload() {
                List<String> names = new ArrayList<>();
                List<String> labels = new ArrayList<>();
                names.add("");
                labels.add("Any controller");
                for (int id : InputDevice.getDeviceIds()) {
                    InputDevice d = InputDevice.getDevice(id);
                    if (isGamepad(d) && !names.contains(d.getName())) {
                        names.add(d.getName());
                        labels.add(d.getName());
                    }
                }
                String cur = mSettings.getOrDefault(key, "");
                if (!names.contains(cur)) {
                    names.add(cur);
                    labels.add(t("%s (not connected)", cur));
                }
                values = names.toArray(new String[0]);
                this.labels = labels.toArray(new String[0]);
            }
        });
        add(new Action("Configure buttons", "Choose which controller button does what: press a row, then the button.",
            null, () -> startActivity(new Intent(this, ControllerSettingsActivity.class))));
        add(new Row("Stick Behavior", "Stick assignment, inversion, dead zones and sensitivity.") {
            @Override void click() { openPanel(stickBehaviorPanel(this)); }
            @Override boolean hasArrow() { return true; }
        });

        section("TOUCH");
        add(new Choice("SMS_TOUCH_STYLE", "Button style",
            "How the on-screen buttons look: Classic discs with letters, or GameCube outlines in the controller's shapes and colours.",
            "gamecube", new String[] {"classic", "gamecube"}, new String[] {"Classic", "GameCube"}));
        add(new Choice("SMS_TOUCH_CONTROLS", "Touch controls",
            "The on-screen buttons. Auto hides them while a controller is connected.",
            "auto", new String[] {"auto", "on", "off"}, new String[] {"Auto", "On", "Off"}) {
            @Override int index() {
                String v = mSettings.getOrDefault(key, def);
                return v.equals("on") || v.equals("1") ? 1 : v.equals("off") || v.equals("0") ? 2 : 0;
            }
        });
        add(new Row("Touch behavior", "Hiding when idle, the swipe camera's inversion and button feedback.") {
            @Override void click() { openPanel(touchBehaviorPanel(this)); }
            @Override boolean hasArrow() { return true; }
        });
        add(new Action("Customize layout",
            "Move, resize and hide the on-screen buttons and sticks, and switch on the R+ slider (one R button: "
                + "slide out of its circle for R+). Drag with a finger or the mouse; pinch or use Size −/+ to resize.",
            () -> TouchDraw.isDefault(mSettings) ? "Default" : "Custom",
            () -> startActivity(new Intent(this, TouchLayoutActivity.class))));

        // both follow the game's own rumble option (platform/pad/rumble_game.cpp)
        section("VIBRATION");
        add(new Vibration("SMS_VIBRATION_CONTROLLER", "Controller",
            "How strongly the controller vibrates. 0% turns it off."));
        android.os.Vibrator vib = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vib != null && vib.hasVibrator())
            add(new Vibration("SMS_VIBRATION_PHONE", "Smartphone",
                "How strongly the smartphone vibrates, also with a controller. 0% turns it off."));
    }

    // A volume in percent (platform/audio/dsp_mixer.cpp); 0 is off.
    private class Volume extends Slider {
        Volume(String key, String label, String desc) { super(key, label, desc, 100, 0, 100, 10); }

        @Override String value() { return get() == 0 ? t("Off") : super.value(); }
    }

    // A vibration's strength in percent (platform/pad); 0 is off.
    private class Vibration extends Slider {
        Vibration(String key, String label, String desc) { super(key, label, desc, 80, 0, 100, 10); }

        @Override String value() { return get() == 0 ? t("Off") : super.value(); }
    }

    // --- OTHER --------------------------------------------------------------

    static final String DISCLAIMER =
        "This app is an unofficial, non-commercial fan project. It is not affiliated with, endorsed, sponsored "
            + "or approved by Nintendo Co., Ltd. or any of its subsidiaries.\n\n"
            + "Super Mario Sunshine, Mario, Nintendo and Nintendo GameCube are trademarks of Nintendo. All rights to "
            + "Super Mario Sunshine, including its characters, names, graphics, sound, music and program code, "
            + "belong to Nintendo. Their names are used here only to say which game this app runs.\n\n"
            + "No game data is included with this app: no graphics, models, textures, sound, music, videos or text "
            + "of the game are shipped. Everything is read at runtime from a disc image that you provide. Only use a "
            + "disc image that you made yourself from a copy of the game that you legally own. This app does not "
            + "provide, link to or help to obtain copies of the game.\n\n"
            + "The program is based on a community decompilation of the game (doldecomp/sms) and its PC and Android "
            + "ports. It is provided \"as is\", without warranty of any kind. Use it at your own risk; the "
            + "contributors are not liable for any damage or loss, including lost save data.\n\n"
            + "The optional HD cutscenes are not part of this app: they are downloaded from the PC port's release on "
            + "GitHub (chasem-dev/sms-pc-port), and that download may stop being available at any time.";

    private void showDisclaimer() {
        TextView t = new TextView(this);
        t.setText(t(DISCLAIMER));
        t.setTextColor(TEXT);
        t.setTypeface(condensed(false));
        t.setTextSize(17);
        t.setLineSpacing(0, 1.2f);
        t.setPadding(dp(24), dp(16), dp(24), dp(8));
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        new DlDialog(this)
            .setTitle(t("Disclaimer"))
            .setView(sv)
            .setPositiveButton(t("OK"), null)
            .show();
    }

    // each language in its own name; English, the default, says so (for support)
    private static String[] appLanguageNames() {
        String[] n = L.NAMES.clone();
        n[0] = t(n[0]);
        n[1] = n[1] + " (" + t("Default") + ")";
        return n;
    }

    // the new language shows at once: this screen is built again, on the same tab
    private void switchLanguage(String code) {
        if (code.equals(L.chosen(this))) { closeOptions(true); return; }
        L.choose(this, code);
        recreate();
    }

    private void confirmResetAll() {
        new DlDialog(this)
            .setTitle(t("Reset all settings?"))
            .setMessage(t("Every setting goes back to its default. Your disc image, data folder and save games are kept."))
            .setPositiveButton(t("Reset"), (d, w) -> {
                mSettings.clear();
                for (String[] kv : Settings.DEFAULTS) mSettings.put(kv[0], kv[1]);
                saved();
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(PREF_AUTOSTART).apply();
                CrashLog.setEnabled(this, true);
                refreshValues();
                android.widget.Toast.makeText(this, t("All settings reset to their defaults."), android.widget.Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton(t("Cancel"), null)
            .show();
    }

    // --- cheats: the PC port's (its F9 menu), read when the game starts
    // (platform/misc/port_cheats.cpp, decomp-patches/port-03 to port-08) ----

    private static final String CHEAT_NOTE = "\n\nTakes effect the next time the game starts.";

    private void buildCheats() {
        section("PLAYER");
        add(new Toggle("SMS_CHEAT_GOD_MODE", "God mode",
            "Mario takes no damage and his health stays full." + CHEAT_NOTE, false, false));
        add(new Toggle("SMS_CHEAT_INFINITE_LIVES", "Infinite lives",
            "Losing a life does not lower the lives counter." + CHEAT_NOTE, false, false));
        add(new Toggle("SMS_CHEAT_INFINITE_FLUDD", "Infinite FLUDD",
            "FLUDD uses no water. Hover, Rocket and Turbo nozzles stay on while R is held." + CHEAT_NOTE, false, false));
        section("MOVEMENT");
        add(new Choice("SMS_CHEAT_MOVEMENT_SPEED", "Movement speed",
            "Mario runs, swims and hovers faster." + CHEAT_NOTE, "1",
            new String[] {"1", "2", "4"}, new String[] {"Off", "2x", "4x"}));
        add(new Choice("SMS_CHEAT_JUMP_HEIGHT", "Jump height",
            "Normal, double and triple jumps go higher." + CHEAT_NOTE, "1",
            new String[] {"1", "2", "4", "6"}, new String[] {"Off", "2x", "4x", "6x"}));
        add(new Toggle("SMS_CHEAT_MOON_JUMP", "Moon jump",
            "Holding A in the air lifts Mario, so he can fly up anywhere." + CHEAT_NOTE, false, false));
        section("ITEMS");
        add(new Choice("SMS_CHEAT_COIN_MULTIPLIER", "Coin multiplier",
            "Each yellow coin counts several times. Blue and red coins are not changed." + CHEAT_NOTE, "1",
            new String[] {"1", "2", "5", "10"}, new String[] {"Off", "2x", "5x", "10x"}));
    }

    private static final int REQUEST_SYNC_CREATE = 41, REQUEST_SYNC_OPEN = 42;

    private void chooseSyncFile() {
        List<String> items = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        items.add(t("Create a new sync file"));
        acts.add(() -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/zip");
            i.putExtra(Intent.EXTRA_TITLE, "mareshine-sync.zip");
            startSyncPicker(i, REQUEST_SYNC_CREATE);
        });
        items.add(t("Use an existing sync file"));
        acts.add(() -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startSyncPicker(i, REQUEST_SYNC_OPEN);
        });
        if (Sync.configured(this)) {
            items.add(t("Stop syncing"));
            acts.add(() -> { Sync.forget(this); refreshValues(); });
        }
        dialog().setTitle(t("Sync file"))
            .setItems(items.toArray(new String[0]), (d, w) -> acts.get(w).run())
            .setNegativeButton(t("Cancel"), null).show();
    }

    private void startSyncPicker(Intent i, int request) {
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, request);
        } catch (android.content.ActivityNotFoundException e) {
            android.widget.Toast.makeText(this, t("This device has no file picker."), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if ((request == REQUEST_SYNC_CREATE || request == REQUEST_SYNC_OPEN) && result == RESULT_OK
                && data != null && data.getData() != null) {
            Sync.setUri(this, data.getData());
            refreshValues();
            android.widget.Toast.makeText(this, t("Syncing…"), android.widget.Toast.LENGTH_SHORT).show();
            Sync.run(this, (msg, changed) -> {
                if (msg != null) android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show();
                refreshValues();
            });
        }
    }

    // The mod manager (Mods, ModsActivity) and its settings; tidying up its files
    private void buildMods() {
        section("MODS");
        add(new Action("Open mods",
            "Mod packages that change the game's files and textures, and games made from your disc such as Super "
                + "Mario Eclipse: switch them on or off, set their order, find and import new ones. Changes apply when "
                + "the game starts.",
            () -> {
                int on = 0, all = 0;
                for (Mods.Mod m : Mods.list(ctx())) {
                    all++;
                    if (m.enabled) on++;
                }
                return all == 0 ? t("None") : t("%d of %d on", on, all);
            },
            () -> startActivity(new Intent(this, ModsActivity.class))) {
            @Override boolean hasArrow() { return true; }
        });
        add(new Action("Check for mod updates",
            "Looks for newer versions of your mods (each part of Super Mario Eclipse on its own) and asks which to "
                + "install.",
            null, () -> startActivity(new Intent(this, ModsActivity.class).putExtra(ModsActivity.EXTRA_UPDATES, true))));
        add(new Toggle(ModsActivity.AUTO_UPDATES_KEY, "Look for updates automatically",
            "Once a day, when the app starts, it looks for mod updates and tells you only when there are some.",
            true, false));
        add(new Choice("SMS_MODS_PER_PAGE", "Mods per page", "How many mods the mod list shows at a time.", "8",
            new String[] {"5", "8", "10", "15"}, new String[] {"5", "8", "10", "15"}));
        add(new Toggle(ModsActivity.UNTESTED_KEY, "Show untested mods",
            "Search Mods also lists mods we have not checked yet. They may not work.", false, false));

        section("TIDY UP");
        add(new Action("Switch all mods off",
            "Plays Super Mario Sunshine as it is, without uninstalling anything. Switch them on again in the mod "
                + "list.",
            () -> {
                int on = 0;
                for (Mods.Mod m : Mods.list(ctx())) if (m.enabled) on++;
                return on == 0 ? t("All off") : t("%d on", on);
            },
            () -> {
                List<Mods.Mod> all = Mods.list(ctx());
                for (Mods.Mod m : all) m.enabled = false;
                Mods.save(ctx(), all);
                mSettings.putAll(Settings.read(this));
                showTab(mTab);
            }));
        add(new Action("Delete leftover downloads",
            "Downloads that were stopped half way and the pictures of mod pages kept for later. Nothing installed "
                + "is touched.",
            later(() -> mb(Mods.leftovers(ctx()))),
            () -> {
                Mods.deleteLeftovers(ctx());
                showTab(mTab);
            }));
        add(new Action("Uninstall all mods",
            "Deletes every mod and its files from mods/ in your data folder, Super Mario Eclipse's games among them. "
                + "Your saves stay.",
            later(() -> mb(Mods.size(Mods.dir(ctx())))),
            () -> choice(t("Uninstall all mods?"), t("Every mod is deleted (%s). This cannot be undone.",
                    mb(Mods.size(Mods.dir(ctx())))),
                new String[] {t("Uninstall all")}, new Runnable[] {() -> {
                    Mods.uninstallAll(ctx());
                    mSettings.remove(Mods.KEY);
                    mSettings.remove(Mods.OPTIONS_KEY);
                    mSettings.putAll(Settings.read(this));
                    showTab(mTab);
                }})));
    }

    // a row's value that takes a while (sizes of many files): "…" until it is
    // worked out off the main thread, then shown
    private java.util.function.Supplier<String> later(java.util.function.Supplier<String> slow) {
        final String[] got = {null};
        final boolean[] started = {false};
        return () -> {
            if (got[0] != null) return got[0];
            if (!started[0]) {
                started[0] = true;
                new Thread(() -> {
                    String v = slow.get();
                    runOnUiThread(() -> {
                        got[0] = v;
                        refreshValues();
                    });
                }, "settings-value").start();
            }
            return "…";
        };
    }

    private static String mb(long bytes) {
        return bytes >= 1e9 ? String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1e9)
            : String.format(java.util.Locale.ROOT, "%.0f MB", bytes / 1e6);
    }

    private void buildAchievements() {
        section("ACHIEVEMENTS");
        add(new Toggle(Achievements.SETTING, "Achievements",
            "If you have already done something in an older save, you get the matching achievements as soon as "
                + "you load it.\n\nOff: nothing is tracked or unlocked; what you have unlocked stays.\n\n"
                + "Applies the next time the game starts.",
            true, false));
        add(new Action("Show achievements",
            "All achievements, what you have unlocked and how far you are. Secret ones show as ??? until unlocked. "
                + "In the game they are in the quick menu too.",
            () -> {
                int[] n = Achievements.count(Achievements.load(ctx()));
                return n[0] + " / " + n[1];
            },
            () -> startActivity(new Intent(ctx(), AchievementsActivity.class))));
    }

    private void buildInterface() {
        section("APP LANGUAGE");
        add(new Choice("", "App language",
            "The language of this app's menus and settings. System follows the device's language. The game's own "
                + "text is set separately under GAME → Language.",
            L.SYSTEM, L.CODES.clone(), appLanguageNames()) {
            @Override int index() {
                String v = L.chosen(ctx());
                for (int i = 0; i < values.length; i++) if (values[i].equals(v)) return i;
                return 0;
            }
            @Override void set(int i) { switchLanguage(values[i]); }
        });

        section("NOTICES");
        add(new Toggle(SunshineActivity.SHADER_POPUP_SETTING, "Shader compiling in game",
            "While the app compiles shaders in the background, a small box at the bottom left of the game shows how far it is. Until it is done, places you visit for the first time can stutter briefly.", true, false));

        section("SYNC");
        String syncNote = "\n\n" + t("Quit to the main menu to sync.");
        Row syncFile = new Action("Sync file",
            t("Keeps your game saves, save states and achievements the same on several devices, through one file "
                + "in Google Drive, Dropbox or any other storage your device offers. Create it on the first device, "
                + "then choose the same file on the others (or use the Wi-Fi sync below).\n\nMareshine syncs when "
                + "it starts and when you leave the game. Anything it replaces is kept in sync-backups/ in your "
                + "data folder.")
                + (mInGame ? syncNote : ""),
            () -> {
                String n = Sync.fileName(ctx());
                return n == null ? t("Off") : n;
            },
            this::chooseSyncFile);
        syncFile.enabled = !mInGame;
        add(syncFile);
        Row wifi = new Action("Sync with a device in the same Wi-Fi",
            t("Without a cloud: open this on both devices, choose the other one and enter the code it shows. "
                + "Works on TVs too.") + (mInGame ? syncNote : ""),
            null, () -> startActivity(new Intent(this, WifiSyncActivity.class)));
        wifi.enabled = !mInGame;
        add(wifi);
        add(new Toggle(Sync.SETTING_STATES, "Sync save states",
            "Save states can be large, which makes each sync take longer. On another device a save state loads "
                + "the place it was saved at.", true, false));
        Row syncNow = new Action("Sync now",
            t("Syncs at once and says what changed.") + (mInGame ? syncNote : ""),
            () -> {
                long l = Sync.lastSync(ctx());
                return l == 0 ? "" : java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,
                    java.text.DateFormat.SHORT).format(new java.util.Date(l));
            },
            () -> {
                if (!Sync.configured(ctx())) { chooseSyncFile(); return; }
                android.widget.Toast.makeText(this, t("Syncing…"), android.widget.Toast.LENGTH_SHORT).show();
                Sync.run(this, (msg, changed) -> {
                    if (msg != null) android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show();
                    refreshValues();
                });
            });
        syncNow.enabled = !mInGame;
        add(syncNow);

        section("UPDATES");
        add(new Toggle(Updates.SETTING, "Check for updates automatically",
            "Each time the app starts, Mareshine looks for a newer version and offers it. Nothing is installed "
                + "without your OK.", true, false));
        add(new Action("Check for updates now",
            "Looks for a newer version now. It is downloaded and installed only after you confirm it.",
            () -> Updates.found(ctx()) != null
                ? t("New Update!") + "  (" + Updates.version(Updates.found(ctx())) + ")"
                : CrashLog.BuildConfigInfo.version(ctx()),
            () -> {
                Updates.Release r = Updates.found(this);
                if (r != null) Updates.ask(this, r, this::refreshValues);
                else Updates.manualCheck(this);
            }));

        section("PERFORMANCE");
        add(new Toggle("SMS_OVERLAY", "Performance overlay",
            "Shows the frame rate and rendering counters. Detailed timing is optional. Applies the next time the game starts.",
            false, false));
        add(new Toggle("SMS_GX_DETAILED_TIMERS", "Detailed performance timings",
            "Measures rendering phases for diagnostics. Can reduce FPS. Restart the game after changing this.",
            false, false));
        add(new Choice("SMS_GX_PERSISTENT_BUFFER", "Persistent GPU buffer (test)",
            "Automatic enables it on supported Adreno GPUs. Restart the game after changing this.",
            Settings.def("SMS_GX_PERSISTENT_BUFFER", "auto"),
            new String[] {"auto", "0", "1"}, new String[] {"Automatic (Adreno)", "Off", "On"}));

        section("LOGS");
        add(new Row("Logs and crash reports",
            "Writes the game's log and, when it crashes, a crash report to logs/ in your data folder. "
                + "Useful when something goes wrong.") {
            @Override String value() { return CrashLog.isEnabled(ctx()) ? "On" : "Off"; }
            @Override void step(int dir) { CrashLog.setEnabled(ctx(), !CrashLog.isEnabled(ctx())); refreshValues(); }
        });
        section("RESET");
        add(new Action("Reset all settings",
            "Sets every setting back to its default: video (Recommended (HD)), controller, button mapping, "
                + "stick calibration, touch layout, and starting the game automatically.\n\n"
                + "Your disc image, data folder and save games are kept.",
            () -> "", this::confirmResetAll));

        section("ABOUT");
        add(new Action("Version", "Mareshine, a Super Mario Sunshine port for Android.",
            () -> CrashLog.BuildConfigInfo.version(ctx()), () -> {}));

        section("CREDITS");
        add(new Action("Android improvements",
            "Data folder, disc check, settings and menus, controller and stick options, touch layout editor, "
                + "crash logs and Android fixes.",
            () -> "TheM3dium", () -> {}));
        add(new Action("Android port",
            "The original Android port of the game (sms-android).",
            () -> "jbcgames", () -> {}));
        add(new Action("PC port",
            "The native PC port this Android version is built on (sms-pc-port), and the English decompilation "
                + "fork it uses.",
            () -> "chasem-dev", () -> {}));
        add(new Action("Decompilation",
            "The Super Mario Sunshine decompilation project (doldecomp/sms) that made the port possible.",
            () -> "doldecomp", () -> {}));
        add(new Action("GameCube button shapes",
            "The shapes of the GameCube touch button style, traced from m-overlay's controller pictures (MIT license).",
            () -> "Bkacjios", () -> {}));
        add(new Action("Libraries",
            "SDL2 (input, audio, window), stb (images), Dear ImGui and bcdec (graphics layer).",
            () -> "SDL2, stb, ImGui", () -> {}));

        section("LEGAL");
        add(new Action("Disclaimer",
            "Unofficial fan project, not affiliated with Nintendo. Super Mario Sunshine and all rights to it "
                + "belong to Nintendo. No game data is included; you need your own copy of the game.\n\n"
                + "Press OK or tap to read the full disclaimer.",
            null, this::showDisclaimer));
    }
}
