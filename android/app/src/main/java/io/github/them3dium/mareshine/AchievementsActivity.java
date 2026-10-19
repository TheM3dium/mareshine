package io.github.them3dium.mareshine;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

import static io.github.them3dium.mareshine.L.t;

// The achievements (Achievements.java): every category with its achievements,
// unlocked ones bright with their date, the others dim with their progress; a
// secret one shows as ??? until it is unlocked (or only its title). Two tabs:
// ALL, and UNLOCKED with only the ones unlocked, in the same order. From
// Settings → ACHIEVEMENTS (which also turns them on or off) and from the quick
// menu in the game.
public class AchievementsActivity extends AppActivity {
    private LinearLayout mList;
    private TextView mSwitch;
    private ScrollView mScroll;
    private final TextView[] mTabs = new TextView[2];
    private final View[] mTabBars = new View[2];
    private static int sTab; // kept while the app runs

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        L.init(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundResource(R.drawable.dl_scrim_strong);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.dl_panel);
        panel.setPadding(dp(20), dp(14), dp(20), dp(14));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(660), ViewGroup.LayoutParams.MATCH_PARENT);
        plp.gravity = Gravity.CENTER;
        plp.topMargin = plp.bottomMargin = dp(16);
        root.addView(panel, plp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(t("ACHIEVEMENTS"));
        title.setTextColor(0xFFFFC93C);
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        title.setTextSize(24);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        panel.addView(head);

        // the tabs, as in Settings (L1 / R1 switch them too)
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            TextView tv = new TextView(this);
            tv.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            tv.setTextSize(18);
            tv.setPadding(dp(14), dp(8), dp(14), dp(6));
            tv.setFocusable(true);
            tv.setBackgroundResource(R.drawable.dl_tab);
            tv.setOnClickListener(v -> showTab(idx));
            tv.setOnFocusChangeListener((v, has) -> { if (has && sTab != idx) showTab(idx); });
            cell.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            View bar = new View(this);
            cell.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));
            tabs.addView(cell);
            mTabs[i] = tv;
            mTabBars[i] = bar;
        }
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(6);
        tlp.bottomMargin = dp(4);
        panel.addView(tabs, tlp);

        // turned off in Settings: say so (the switch is only there)
        mSwitch = new TextView(this);
        mSwitch.setTextSize(14);
        mSwitch.setTextColor(getColor(R.color.dl_dim));
        mSwitch.setPadding(0, dp(4), 0, dp(6));
        mSwitch.setText(t("Achievements are off (Settings → ACHIEVEMENTS): nothing is tracked or unlocked."));
        mSwitch.setVisibility(Achievements.enabled(this) ? View.GONE : View.VISIBLE);
        panel.addView(mSwitch);

        mScroll = new ScrollView(this);
        mList = new LinearLayout(this);
        mList.setOrientation(LinearLayout.VERTICAL);
        mScroll.addView(mList);
        panel.addView(mScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        showTab(sTab);
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent ev) {
        if (ev.getAction() == android.view.KeyEvent.ACTION_DOWN
            && (ev.getKeyCode() == android.view.KeyEvent.KEYCODE_BUTTON_L1
                || ev.getKeyCode() == android.view.KeyEvent.KEYCODE_BUTTON_R1)) {
            showTab(1 - sTab);
            mTabs[sTab].requestFocus();
            return true;
        }
        return super.dispatchKeyEvent(ev);
    }

    private void showTab(int idx) {
        sTab = idx;
        for (int i = 0; i < 2; i++) {
            mTabs[i].setTextColor(i == idx ? 0xFFFFFFFF : 0xFF8FB4D4);
            mTabBars[i].setBackgroundColor(i == idx ? 0xFFFFC531 : 0x00000000);
        }
        fill();
        mScroll.scrollTo(0, 0);
    }

    private void fill() {
        mList.removeAllViews();
        List<Achievements.Category> cats = Achievements.load(this);
        int[] n = Achievements.count(cats);
        mTabs[0].setText(t("ALL") + "  " + n[1]);
        mTabs[1].setText(t("UNLOCKED") + "  " + n[0] + " / " + n[1]);
        final boolean onlyUnlocked = sTab == 1;
        if (onlyUnlocked && n[0] == 0) {
            TextView none = new TextView(this);
            none.setText(t("No achievements yet"));
            none.setTextColor(getColor(R.color.dl_dim));
            none.setTextSize(16);
            none.setGravity(Gravity.CENTER);
            none.setPadding(0, dp(40), 0, dp(40));
            mList.addView(none);
            return;
        }
        java.text.DateFormat df = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM);
        for (Achievements.Category k : cats) {
            if (k.items.isEmpty()) continue;
            int got = 0;
            for (Achievements.Def d : k.items) if (d.unlocked) got++;
            if (onlyUnlocked && got == 0) continue;
            TextView h = new TextView(this);
            h.setText(k.icon + "  " + k.title + "   " + got + "/" + k.items.size());
            h.setTextColor(0xFFFFC93C);
            h.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            h.setTextSize(17);
            h.setPadding(0, dp(10), 0, dp(4));
            mList.addView(h);
            for (Achievements.Def d : k.items)
                if (d.unlocked || !onlyUnlocked) mList.addView(row(k, d, df));
        }
    }

    private View row(Achievements.Category k, Achievements.Def d, java.text.DateFormat df) {
        boolean hidden = d.secret && !d.unlocked;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.dl_row);
        row.setPadding(dp(10), dp(6), dp(12), dp(6));
        row.setFocusable(true); // for scrolling with a controller or remote
        row.setAlpha(d.unlocked ? 1f : 0.55f);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(4);
        row.setLayoutParams(rlp);

        TextView icon = new TextView(this);
        icon.setText(d.unlocked ? "🏆" : hidden ? "❔" : "🔒");
        icon.setTextSize(22);
        icon.setGravity(Gravity.CENTER);
        row.addView(icon, new LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(8), 0, 0, 0);
        TextView name = new TextView(this);
        name.setText(hidden && !d.titleShown ? "???" : d.title);
        name.setTextColor(0xFFFFFFFF);
        name.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        name.setTextSize(16);
        texts.addView(name);
        TextView desc = new TextView(this);
        desc.setText(hidden ? "???" : d.description);
        desc.setTextColor(getColor(R.color.dl_text));
        desc.setTextSize(13);
        texts.addView(desc);
        if (!d.unlocked && !hidden && d.target > 1) {
            LinearLayout pr = new LinearLayout(this);
            pr.setOrientation(LinearLayout.HORIZONTAL);
            pr.setGravity(Gravity.CENTER_VERTICAL);
            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(d.target);
            bar.setProgress(Math.min(d.progress, d.target));
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFFFC93C));
            pr.addView(bar, new LinearLayout.LayoutParams(0, dp(6), 1f));
            TextView num = new TextView(this);
            int done = Math.min(d.progress, d.target);
            num.setText(d.minutes ? "  " + done / 60 + " / " + d.target / 60 + " h" : "  " + done + " / " + d.target);
            num.setTextColor(getColor(R.color.dl_dim));
            num.setTextSize(12);
            pr.addView(num);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.topMargin = dp(3);
            texts.addView(pr, plp);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (d.unlocked && d.at > 0) {
            TextView when = new TextView(this);
            when.setText(df.format(new java.util.Date(d.at * 1000)));
            when.setTextColor(getColor(R.color.dl_dim));
            when.setTextSize(12);
            row.addView(when);
        }
        return row;
    }
}
