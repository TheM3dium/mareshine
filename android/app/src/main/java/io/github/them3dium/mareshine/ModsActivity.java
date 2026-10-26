package io.github.them3dium.mareshine;

import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import static io.github.them3dium.mareshine.L.t;

// The mod manager (Mods): the installed mod packages in their load order,
// each switched on or off, moved up or down or deleted, and new ones
// imported from a .zip. Mods are loaded when the game starts: opened from
// the quick menu (EXTRA_IN_GAME), closing after a change offers to restart
// the game (RESULT_RESTART); declining keeps the changes for the next start.
public class ModsActivity extends AppActivity {
    public static final String EXTRA_IN_GAME = "in_game";
    // opened to look for updates at once (Settings → MODS, LauncherActivity's daily look)
    public static final String EXTRA_UPDATES = "updates";
    // Settings → MODS
    public static final String AUTO_UPDATES_KEY = "SMS_MODS_AUTO_UPDATES", UNTESTED_KEY = "SMS_MODS_UNTESTED";
    public static final int RESULT_RESTART = RESULT_FIRST_USER;
    private static final int REQUEST_IMPORT = 1, REQUEST_PAGE = 3;
    private static final int TEXT = 0xFFF4F8FF, DIM = 0xFF8FB4D4, GOLD = 0xFFFFC93C, WARN = 0xFFFFA25C;

    private List<Mods.Mod> mMods = new ArrayList<>();
    private final List<View> mRows = new ArrayList<>();
    private LinearLayout mList;
    private TextView mStatus, mTexWarn;
    private ProgressBar mBar;
    private Button mImport, mClose;
    private boolean mChanged, mInGame;
    private String mStart;

    // what the game starts with: the mods, their order, options and versions, the game language
    private String state() {
        java.util.Map<String, String> s = Settings.read(this);
        StringBuilder sb = new StringBuilder().append(s.get(Mods.KEY)).append('|').append(s.get(Mods.OPTIONS_KEY))
            .append('|').append(s.get("language"));
        for (Mods.Mod m : Mods.list(this)) sb.append('|').append(m.id).append(' ').append(m.version);
        return sb.toString();
    }
    private volatile boolean mBusy, mCancel;

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        L.init(this);
        mInGame = getIntent().getBooleanExtra(EXTRA_IN_GAME, false);
        EclipseMod.migrate(this);  // its languages as mods of their own before
        mStart = state();
        mShowUntested = "1".equals(Settings.read(this).getOrDefault(UNTESTED_KEY, "0"));
        buildUi();
        reload();
        if (getIntent().getBooleanExtra(EXTRA_UPDATES, false) && savedInstanceState == null)
            mList.post(() -> checkUpdates(null));
        Ratings.refresh(this, () -> { if (!mBusy) showList(); });
    }

    private TextView text(String s, int color, int size, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextColor(color);
        v.setTextSize(size);
        v.setTypeface(Typeface.create("sans-serif-condensed", bold ? Typeface.BOLD : Typeface.NORMAL));
        return v;
    }

    private Button button(String label, boolean main) {
        Button b = new Button(this);
        b.setText(t(label));
        b.setAllCaps(true);
        b.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        b.setTextSize(15);
        b.setStateListAnimator(null);
        b.setBackgroundResource(R.drawable.dl_row);
        b.setPadding(dp(18), dp(10), dp(18), dp(10));
        if (main) {
            b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFFB020));
            b.setTextColor(0xFF0A1B30);
        } else {
            b.setTextColor(TEXT);
        }
        return b;
    }

    private void buildUi() {
        // the app's panel: title, the two tabs and ✕ on top, the mods scroll,
        // paging and the buttons below
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundResource(R.drawable.dl_scrim_strong);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.dl_panel);
        panel.setPadding(dp(28), dp(10), dp(28), dp(12));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        plp.setMargins(dp(16), dp(12), dp(16), dp(12));
        root.addView(panel, plp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text(t("MODS"), GOLD, 24, true));
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER);
        for (int i = 0; i < 2; i++) {
            final int tab = i;
            TextView tv = text(t(i == 0 ? "INSTALLED" : "SEARCH MODS"), TEXT, 18, true);
            tv.setPadding(dp(18), dp(8), dp(18), dp(8));
            tv.setBackgroundResource(R.drawable.dl_tab);
            tv.setFocusable(true);
            tv.setOnClickListener(v -> showTab(tab));
            tabs.addView(tv);
            mTabs[i] = tv;
        }
        head.addView(tabs, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = closeButton();
        close.setOnClickListener(v -> leave());
        head.addView(close);
        panel.addView(head);

        mHint = text("", DIM, 14, false);
        mHint.setPadding(0, dp(2), 0, dp(6));
        panel.addView(mHint);
        // several texture packs at once (Settings' own and mods'): a warning
        mTexWarn = text("", WARN, 14, true);
        mTexWarn.setPadding(0, 0, 0, dp(6));
        mTexWarn.setVisibility(View.GONE);
        panel.addView(mTexWarn);

        ScrollView scroll = new ScrollView(this);
        mList = new LinearLayout(this);
        mList.setOrientation(LinearLayout.VERTICAL);
        mList.setPadding(0, 0, dp(6), dp(8));
        scroll.addView(mList);
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        mStatus = text("", 0xFFBFD98A, 15, true);
        mStatus.setVisibility(View.GONE);
        panel.addView(mStatus);
        mBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mBar.setIndeterminate(true);
        mBar.setVisibility(View.GONE);
        panel.addView(mBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12)));

        // paging at the left, the buttons at the right
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setPadding(0, dp(8), 0, 0);
        mUpdate = button("Install updates", false);
        mPrev = button("‹ " + t("Back"), false);
        mPageText = text("", DIM, 15, true);
        mPageText.setPadding(dp(8), 0, dp(8), 0);
        mNext = button(t("Next") + " ›", false);
        mPerPage = button("", false);
        mUntested = button("", false);
        mImport = button("Import mod", true);
        mClose = button("Close", false);
        for (View b : new View[] {mUpdate, mPrev, mPageText, mNext}) buttons.addView(b, buttonParams());
        buttons.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        for (View b : new View[] {mPerPage, mUntested, mImport, mClose}) buttons.addView(b, buttonParams());
        panel.addView(buttons);
        mUpdate.setOnClickListener(v -> checkUpdates(null));
        mPrev.setOnClickListener(v -> page(-1));
        mNext.setOnClickListener(v -> page(1));
        mPerPage.setOnClickListener(v -> perPage());
        mUntested.setOnClickListener(v -> {
            mShowUntested = !mShowUntested;
            java.util.Map<String, String> st = Settings.read(this);
            st.put(UNTESTED_KEY, mShowUntested ? "1" : "0");
            Settings.write(this, st);
            mPage = 0;
            showList();
        });
        mImport.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQUEST_IMPORT);
        });
        mClose.setOnClickListener(v -> leave());
        setContentView(root);
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), 0, dp(4), 0);
        return lp;
    }

    private void reload() {
        mMods = Mods.list(this);
        showList();
    }

    // back from a mod's page or from installing one (ModInstallActivity)
    @Override
    protected void onResume() {
        super.onResume();
        // an update came back (its install or picker closed): the next one
        if (mUpdateFlow && mUpdatePaused) {
            mUpdatePaused = false;
            if (mUpdateHold) mUpdateHold = false;  // what was picked starts its own install first
            else if (!mBusy) mList.post(this::nextUpdate);
        }
        if (!mBusy && mList != null) {
            boolean before = mMods.size() != Mods.list(this).size();
            reload();
            if (before) mChanged = true;
        }
    }

    // --- the tabs and their pages --------------------------------------------------

    private static final int[] PER_PAGE = {5, 8, 10, 15};
    private static final String PER_PAGE_KEY = "SMS_MODS_PER_PAGE";
    private final TextView[] mTabs = new TextView[2];
    private int mTab, mPage;
    private boolean mShowUntested;
    private TextView mHint, mPageText;
    private Button mPrev, mNext, mPerPage, mUntested, mUpdate;

    private int perPageCount() {
        try {
            int n = Integer.parseInt(Settings.read(this).getOrDefault(PER_PAGE_KEY, "8"));
            for (int p : PER_PAGE) if (p == n) return n;
        } catch (NumberFormatException ignored) {
        }
        return 8;
    }

    private void perPage() {
        int cur = perPageCount(), next = PER_PAGE[0];
        for (int i = 0; i < PER_PAGE.length; i++) if (PER_PAGE[i] == cur) next = PER_PAGE[(i + 1) % PER_PAGE.length];
        java.util.Map<String, String> s = Settings.read(this);
        s.put(PER_PAGE_KEY, String.valueOf(next));
        Settings.write(this, s);
        mPage = 0;
        showList();
        mPerPage.requestFocus();
    }

    private void showTab(int tab) {
        mTab = tab;
        mPage = 0;
        showList();
    }

    private void page(int dir) {
        mPage += dir;
        showList();
        (dir < 0 ? mPrev : mNext).requestFocus();
    }

    // the catalog's mods for the Search tab: tested ones, or all
    private List<Mods.Release> catalog() {
        List<Mods.Release> out = new ArrayList<>();
        for (Mods.Release r : Mods.catalog(this)) if (r.tested || mShowUntested) out.add(r);
        return out;
    }

    private void showList() {
        int packs = Mods.textureFolders(this).size();
        if (!"0".equals(Settings.read(this).getOrDefault("SMS_HD_TEXTURES", "1"))) {
            String[] own = DataDir.texturesDir(this).list();
            if (own != null && own.length > 0) packs++;
        }
        mTexWarn.setText("⚠  " + t(SettingsActivity.TEXTURE_MIX));
        mTexWarn.setVisibility(packs > 1 && mTab == 0 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < 2; i++) mTabs[i].setTextColor(i == mTab ? GOLD : DIM);
        mHint.setText(mTab == 0
            ? t("Tap a mod for its page, hold it to switch it on or off, move or delete it. They load top first: a "
                + "mod lower down wins over one above it. Changes apply when the game starts.")
            : t("Mods made from your own disc: tap one to install it."));
        mList.removeAllViews();
        mRows.clear();
        int per = perPageCount();
        int count = mTab == 0 ? mMods.size() : catalog().size();
        int pages = Math.max(1, (count + per - 1) / per);
        mPage = Math.max(0, Math.min(mPage, pages - 1));
        int from = mPage * per, to = Math.min(count, from + per);
        if (mTab == 0) {
            if (mMods.isEmpty()) {
                TextView none = text(t("No mods installed yet. Find one in Search Mods, import a mod package (.zip) "
                    + "with the button below, or put a mod's folder into mods/ in your data folder."), TEXT, 16, false);
                none.setPadding(0, dp(16), 0, dp(16));
                mList.addView(none);
            }
            for (int i = from; i < to; i++) addModRow(i);
        } else {
            List<Mods.Release> list = catalog();
            if (list.isEmpty()) {
                TextView none = text(t("Nothing to find here yet."), TEXT, 16, false);
                none.setPadding(0, dp(16), 0, dp(16));
                mList.addView(none);
            }
            for (int i = from; i < to; i++) addReleaseRow(list.get(i));
        }
        boolean paged = pages > 1;
        mPrev.setVisibility(paged ? View.VISIBLE : View.GONE);
        mNext.setVisibility(paged ? View.VISIBLE : View.GONE);
        mPageText.setVisibility(paged ? View.VISIBLE : View.GONE);
        mPrev.setEnabled(mPage > 0);
        mPrev.setAlpha(mPage > 0 ? 1f : 0.4f);
        mNext.setEnabled(mPage < pages - 1);
        mNext.setAlpha(mPage < pages - 1 ? 1f : 0.4f);
        mPageText.setText((mPage + 1) + " / " + pages);
        mPerPage.setText(t("Per page: %d", per));
        mUntested.setText(t(mShowUntested ? "Hide untested mods" : "Show untested mods"));
        mUntested.setVisibility(mTab == 1 ? View.VISIBLE : View.GONE);
        mImport.setVisibility(mTab == 0 ? View.VISIBLE : View.GONE);
        mUpdate.setVisibility(mTab == 0 && !mMods.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // a row: the mod's disc, its name and suns, and at the right its state
    private LinearLayout row(String icon, String title, String sub, float rating, String state,
                             int stateColor) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.dl_row);
        row.setPadding(dp(12), dp(6), dp(18), dp(6));
        row.setFocusable(true);
        row.setClickable(true);
        row.setLongClickable(true);
        ModViews.Disc disc = new ModViews.Disc(this);
        disc.set(null, title);
        ModViews.load(this, icon, dp(96), b -> disc.set(b, title));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(52), dp(52));
        dlp.rightMargin = dp(14);
        row.addView(disc, dlp);
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.addView(text(title, TEXT, 18, true));
        if (rating >= 0) {
            ModViews.Suns suns = new ModViews.Suns(this);
            suns.setRating(rating);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(16));
            slp.leftMargin = dp(12);
            line.addView(suns, slp);
        }
        info.addView(line);
        if (!sub.isEmpty()) {
            TextView s = text(sub, DIM, 14, false);
            s.setMaxLines(2);
            s.setEllipsize(android.text.TextUtils.TruncateAt.END);
            info.addView(s);
        }
        row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView st = text(state, stateColor, 18, true);
        st.setPadding(dp(12), 0, 0, 0);
        row.addView(st);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        mList.addView(row, lp);
        mRows.add(row);
        return row;
    }

    private void addModRow(int i) {
        final Mods.Mod m = mMods.get(i);
        StringBuilder sub = new StringBuilder();
        if (!m.author.isEmpty()) sub.append(t("by %s", m.author));
        if (m.isGame()) sub.append(sub.length() > 0 ? "  ·  " : "").append(t("its own game"));
        if (m.files() != null) sub.append(sub.length() > 0 ? "  ·  " : "").append(t("game files"));
        if (m.textures() != null) sub.append(sub.length() > 0 ? "  ·  " : "").append(t("textures"));
        List<String> missing = m.enabled ? Mods.missing(m, mMods) : new ArrayList<>();
        if (!missing.isEmpty())
            sub.append("\n⚠  ").append(t("Needs %s, switched on and above it.", String.join(", ", missing)));
        String title = (i + 1) + ".  " + m.name + (m.version.isEmpty() ? "" : "  " + m.version);
        LinearLayout row = row(m.picture(m.icon), title, sub.toString(), Ratings.shown(m),
            t(m.enabled ? "On" : "Off"), m.enabled ? GOLD : DIM);
        row.setOnClickListener(v -> ModPageActivity.start(this, m.id, "", REQUEST_PAGE));
        row.setOnLongClickListener(v -> {
            actions(i);
            return true;
        });
    }

    private void addReleaseRow(Mods.Release r) {
        boolean have = Mods.installed(this, r);
        String sub = t("by %s", r.author) + "  ·  " + t(r.description) + (r.tested ? "" : "  ·  " + t("untested"));
        Mods.Mod m = have ? Mods.find(this, r.id) : null;
        float suns = m != null ? Ratings.shown(m) : Ratings.shown(r.id, r.rating);
        LinearLayout row = row(m != null ? m.picture(m.icon) : r.icon, r.name + "  " + r.version, sub, suns,
            have ? t("Installed") : "⬇  " + t("%.1f GB", r.size / 1e9), have ? DIM : GOLD);
        row.setOnClickListener(v -> {
            if (have) {
                ModPageActivity.start(this, r.id, "", REQUEST_PAGE);
            } else {
                offer(r);
            }
        });
    }

    // Super Mario Eclipse asks for its language first (ModFlows)
    private void offer(Mods.Release r) {
        if (r.id.equals(EclipseMod.ID)) {
            ModFlows.languages(this);
            return;
        }
        if (r.urls.length == 0) {
            ModFlows.manual(this, r);
            return;
        }
        choice(t("Install %s?", r.name),
            t("%s is downloaded (%.1f GB) and made from your own Super Mario Sunshine disc image (North American, "
                + "GMSE01): the download holds only the changes, nothing of the game. It then needs about %.1f GB "
                + "and is switched on: Play starts it, and switching it off here plays Super Mario Sunshine again.",
                r.name, r.size / 1e9, r.imageSize / 1e9),
            new String[] {t("Install")}, new Runnable[] {() -> ModInstallActivity.start(this, r)});
    }

    // Holding a mod: on/off, its toggles and choices, up, down, delete
    private void actions(int i) {
        final Mods.Mod m = mMods.get(i);
        List<String> labels = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        List<Boolean> dim = new ArrayList<>();
        labels.add(t(m.enabled ? "Switch off" : "Switch on"));
        acts.add(() -> ModFlows.setEnabled(this, m, !m.enabled, () -> {
            mMods = Mods.list(this);
            changed(i);
        }));
        dim.add(false);
        for (Mods.Option o : m.options) {
            if (!o.isToggle() && !o.isChoice()) continue;
            String value = o.isToggle() ? t(m.value(o).equals("1") ? "On" : "Off")
                : o.value(m.value(o)) != null ? o.value(m.value(o)).label : m.value(o);
            labels.add(o.label + ": " + value);
            acts.add(() -> option(m, o, i));
            dim.add(false);
        }
        labels.add(t("Check for updates"));
        acts.add(() -> checkUpdates(m.id));
        dim.add(false);
        labels.add(t("Move up"));
        acts.add(() -> move(i, -1));
        dim.add(i == 0);
        labels.add(t("Move down"));
        acts.add(() -> move(i, +1));
        dim.add(i == mMods.size() - 1);
        labels.add(t("Delete"));
        acts.add(() -> confirmDelete(i));
        dim.add(false);
        boolean[] d = new boolean[dim.size()];
        for (int k = 0; k < d.length; k++) d[k] = dim.get(k);
        choice(m.name, null, labels.toArray(new String[0]), acts.toArray(new Runnable[0]), d);
    }

    // a toggle or choice from the quick menu (Eclipse's as on its page)
    private void option(Mods.Mod m, Mods.Option o, int i) {
        if (m.id.equals(EclipseMod.ID) && o.id.equals(EclipseMod.LANGUAGE)) {
            ModFlows.languages(this);
            return;
        }
        if (m.id.equals(EclipseMod.ID) && o.id.equals(EclipseMod.HD) && !ModFlows.texturesDir(this).isDirectory()) {
            ModFlows.textures(this);
            return;
        }
        if (m.id.equals(EclipseMod.ID) && o.id.equals(EclipseMod.HD)) {
            ModFlows.eclipseHd(this, m, o, () -> {
                mMods = Mods.list(this);
                changed(i);
            });
            return;
        }
        if (o.isToggle()) {
            Mods.setOption(this, m, o, m.value(o).equals("1") ? "0" : "1");
            mMods = Mods.list(this);
            changed(i);
            return;
        }
        String[] labels = new String[o.values.size()];
        Runnable[] acts = new Runnable[labels.length];
        for (int k = 0; k < labels.length; k++) {
            Mods.Value v = o.values.get(k);
            labels[k] = (v.id.equals(m.value(o)) ? "✓  " : "") + v.label;
            acts[k] = () -> {
                Mods.setOption(this, m, o, v.id);
                mMods = Mods.list(this);
                changed(i);
            };
        }
        choice(o.label, null, labels, acts);
    }

    private void move(int i, int dir) {
        int j = i + dir;
        if (j < 0 || j >= mMods.size()) return;
        Mods.Mod m = mMods.remove(i);
        mMods.add(j, m);
        changed(j);
    }

    private void changed(int focus) {
        mChanged = true;
        Mods.save(this, mMods);
        int per = perPageCount();
        if (focus >= 0) mPage = focus / per;
        showList();
        int at = focus - mPage * per;
        if (at >= 0 && at < mRows.size()) mRows.get(at).requestFocus();
    }

    private void confirmDelete(int i) {
        final Mods.Mod m = mMods.get(i);
        choice(t("Delete %s?", m.name), t("Its folder is removed from mods/ in your data folder."),
            new String[] {t("Delete")}, new Runnable[] {() -> {
                Mods.delete(m);
                mMods.remove(m);
                changed(Math.min(i, mMods.size() - 1));
            }});
    }

    // --- updates -------------------------------------------------------------------
    // Install updates (or one mod's, from its quick menu): every part asked on
    // its own, then the ones agreed to installed one after the other (each
    // comes back here: onResume starts the next).

    private final List<ModUpdates.Update> mUpdateQueue = new ArrayList<>();
    private boolean mUpdateFlow, mUpdatePaused, mUpdateHold;

    @Override
    protected void onPause() {
        super.onPause();
        if (mUpdateFlow) mUpdatePaused = true;
    }

    private void checkUpdates(String onlyMod) {
        if (mBusy) return;
        mBusy = true;
        mStatus.setVisibility(View.VISIBLE);
        mStatus.setText(t("Checking for updates…"));
        mBar.setVisibility(View.VISIBLE);
        new Thread(() -> {
            List<ModUpdates.Update> found;
            try {
                found = ModUpdates.check(this, onlyMod);
            } catch (Exception e) {
                found = null;
            }
            final List<ModUpdates.Update> list = found;
            runOnUiThread(() -> {
                mBusy = false;
                mBar.setVisibility(View.GONE);
                mStatus.setVisibility(View.GONE);
                reload();
                if (list == null) {
                    choice(t("Updates"), t("Could not look for updates. Are you online?"), new String[] {t("OK")},
                        new Runnable[] {null});
                } else if (list.isEmpty()) {
                    choice(t("Updates"), t(onlyMod == null ? "All mods are up to date." : "This mod is up to date."),
                        new String[] {t("OK")}, new Runnable[] {null});
                } else {
                    mUpdateQueue.clear();
                    ask(list, 0);
                }
            });
        }, "mod-updates").start();
    }

    // one question per part: "Update Super Mario Eclipse? v1.1.0 -> v1.2.0"
    private void ask(List<ModUpdates.Update> list, int i) {
        if (i >= list.size()) {
            nextUpdate();
            return;
        }
        ModUpdates.Update u = list.get(i);
        String msg = t("%s → %s", u.from, u.to) + (list.size() > 1 ? "\n" + t("Update %d of %d", i + 1, list.size()) : "");
        choice(t("Update %s?", u.title), msg, new String[] {t("Update"), t("Skip")}, new Runnable[] {
            () -> { mUpdateQueue.add(u); ask(list, i + 1); },
            () -> ask(list, i + 1)});
    }

    private void nextUpdate() {
        mUpdateHold = false;
        if (mUpdateQueue.isEmpty()) {
            mUpdateFlow = false;
            return;
        }
        mUpdateFlow = true;
        ModUpdates.Update u = mUpdateQueue.remove(0);
        mChanged = true;
        u.install.accept(this);
    }

    // an update installed here (an import): the next one
    private void afterUpdate() {
        if (mUpdateFlow) nextUpdate();
    }

    // a mod's own update: its zip downloaded and imported like one picked
    void downloadAndImport(String url, String name) {
        mBusy = true;
        mStatus.setVisibility(View.VISIBLE);
        mStatus.setText(t("Downloading %s…", name));
        mBar.setVisibility(View.VISIBLE);
        new Thread(() -> {
            Uri got = null;
            String err = null;
            try {
                got = ModUpdates.download(this, url);
            } catch (Exception e) {
                err = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            final Uri file = got;
            final String error = err;
            runOnUiThread(() -> {
                mBusy = false;
                if (file != null) {
                    importMod(file);
                } else {
                    mBar.setVisibility(View.GONE);
                    mStatus.setVisibility(View.GONE);
                    choice(t("Update failed"), error, new String[] {t("OK")}, new Runnable[] {this::afterUpdate});
                }
            });
        }, "mod-update-download").start();
    }

    // --- importing ---------------------------------------------------------------

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQUEST_IMPORT && result == RESULT_OK && data != null && data.getData() != null)
            importMod(data.getData());
        boolean picked = result == RESULT_OK && data != null && data.getData() != null;
        // a picked file starts its own install: the next update waits for that
        if ((request == ModFlows.PICK_TRANSLATION || request == ModFlows.PICK_TEXTURES) && picked) mUpdateHold = mUpdateFlow;
        if (request == ModFlows.PICK_TRANSLATION && picked) ModFlows.picked(this, data.getData());
        if (request == ModFlows.PICK_TEXTURES && picked) importEclipseTextures(data.getData());
        if (request == REQUEST_PAGE && result == RESULT_OK) mChanged = true;
    }

    // Super Mario Eclipse's HD texture pack (from its quick menu) into its hd/textures
    private void importEclipseTextures(Uri uri) {
        long total = Mods.sizeOf(this, uri);
        mBusy = true;
        mCancel = false;
        mStatus.setVisibility(View.VISIBLE);
        mStatus.setText(t("Importing…"));
        mBar.setVisibility(View.VISIBLE);
        new Thread(() -> {
            String error = null;
            try {
                ModFlows.texturesDir(this).getParentFile().mkdirs();
                Mods.importTextures(this, uri, ModFlows.texturesDir(this), new Mods.Progress() {
                    long last;
                    @Override public void step(long bytes) {
                        long now = System.currentTimeMillis();
                        if (now - last < 250) return;
                        last = now;
                        runOnUiThread(() -> {
                            mStatus.setText(t("Importing…") + "  " + Mods.progressText(bytes, total));
                            if (total > 0) {
                                mBar.setIndeterminate(false);
                                mBar.setMax(1000);
                                mBar.setProgress((int) (bytes * 1000 / total));
                            }
                        });
                    }
                    @Override public boolean cancelled() { return mCancel; }
                });
                Mods.Mod m = Mods.find(this, EclipseMod.ID);
                if (m != null)
                    for (Mods.Option o : m.options) if (o.id.equals(EclipseMod.HD)) Mods.setOption(this, m, o, "1");
                EclipseMod.setVersion(this, EclipseMod.HD, ModUpdates.versionIn(Mods.displayName(this, uri)));
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            final String err = error;
            runOnUiThread(() -> {
                mBusy = false;
                mBar.setVisibility(View.GONE);
                mBar.setIndeterminate(true);
                mStatus.setVisibility(View.GONE);
                if (err != null) {
                    choice(t("Textures not imported"), err, new String[] {t("OK")}, new Runnable[] {this::afterUpdate});
                    return;
                }
                mChanged = true;
                reload();
                afterUpdate();
            });
        }, "mod-textures").start();
    }

    private void importMod(Uri uri) {
        long total = Mods.sizeOf(this, uri);  // the unpacked bytes come near it for a texture pack
        mBusy = true;
        mCancel = false;
        mImport.setEnabled(false);
        mStatus.setVisibility(View.VISIBLE);
        mStatus.setText(t("Importing…"));
        mBar.setVisibility(View.VISIBLE);
        new Thread(() -> {
            String error = null;
            Mods.Mod got = null;
            try {
                got = Mods.importZip(this, uri, new Mods.Progress() {
                    long last;
                    @Override public void step(long bytes) {
                        long now = System.currentTimeMillis();
                        if (now - last < 250) return;
                        last = now;
                        runOnUiThread(() -> {
                            mStatus.setText(t("Importing… %.0f MB", bytes / 1e6) + pct(bytes, total));
                            if (total > 0) {
                                mBar.setIndeterminate(false);
                                mBar.setMax(1000);
                                mBar.setProgress((int) Math.min(1000, bytes * 1000 / total));
                            }
                        });
                    }
                    @Override public boolean cancelled() { return mCancel; }
                });
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            final String err = error;
            final Mods.Mod mod = got;
            runOnUiThread(() -> {
                mBusy = false;
                mImport.setEnabled(true);
                mBar.setVisibility(View.GONE);
                mBar.setIndeterminate(true);
                mStatus.setVisibility(View.GONE);
                if (err != null) {
                    choice(t("Mod not imported"), err, new String[] {t("OK")}, new Runnable[] {this::afterUpdate});
                    return;
                }
                // a new mod is switched on at the end; an updated one keeps its place and state
                List<Mods.Mod> now = Mods.list(this);
                for (Mods.Mod x : now)
                    if (x.id.equals(mod.id) && !Settings.read(this).getOrDefault(Mods.KEY, "").contains(mod.id + ":"))
                        x.enabled = true;
                mMods = now;
                int at = 0;
                for (int k = 0; k < mMods.size(); k++) if (mMods.get(k).id.equals(mod.id)) at = k;
                changed(at);
                afterUpdate();
            });
        }, "mod-import").start();
    }

    // --- leaving -------------------------------------------------------------------

    @Override
    public void onBackPressed() { leave(); }

    // After a change in the game: restart now, or keep the changes for the next start.
    private void leave() {
        if (mBusy) {
            mCancel = true;
            return;
        }
        if (!mChanged || state().equals(mStart)) {  // switched back as it was: nothing to say
            finish();
            return;
        }
        if (!mInGame) {
            choice(t("Mods changed"), t("They apply the next time the game starts."), new String[] {t("OK")},
                new Runnable[] {this::finish});
            return;
        }
        choice(t("Please restart"), t("Mods load when the game starts. Restart the game now? Unsaved progress is "
                + "lost. Otherwise the changes apply at the next start."),
            new String[] {t("Restart now"), t("Later")},
            new Runnable[] {() -> { setResult(RESULT_RESTART); finish(); }, this::finish});
    }

    // --- controller ------------------------------------------------------------------

    @Override protected boolean usesPadNav() { return true; }
    @Override protected boolean usesPadCursor() { return true; }

    @Override
    protected List<View> padItems() {
        List<View> items = new ArrayList<>(mRows);
        for (View b : new View[] {mUpdate, mPrev, mNext, mPerPage, mUntested, mImport, mClose})
            if (b.getVisibility() == View.VISIBLE && b.isEnabled()) items.add(b);
        return items;
    }

    // L/R and the D-pad's left/right switch between the tabs
    @Override
    protected boolean onPadTab(int dir, boolean shoulder) {
        if (!shoulder && !mRows.contains(getCurrentFocus())) return false;  // along the buttons
        int tab = Math.max(0, Math.min(1, mTab + dir));
        if (tab != mTab) showTab(tab);
        if (!mRows.isEmpty()) mRows.get(0).requestFocus();
        return true;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && getCurrentFocus() == null && !isTouch(this)) padFocusFirst();
    }
}
