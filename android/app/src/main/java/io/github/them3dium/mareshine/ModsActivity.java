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
    public static final int RESULT_RESTART = RESULT_FIRST_USER;
    private static final int REQUEST_IMPORT = 1, REQUEST_TRANSLATION = 2;
    private static final int TEXT = 0xFFF4F8FF, DIM = 0xFF8FB4D4, GOLD = 0xFFFFC93C, WARN = 0xFFFFA25C;

    private List<Mods.Mod> mMods = new ArrayList<>();
    private final List<View> mRows = new ArrayList<>();
    private LinearLayout mList;
    private TextView mStatus, mTexWarn;
    private ProgressBar mBar;
    private Button mImport, mClose;
    private boolean mChanged, mInGame;
    private volatile boolean mBusy, mCancel;

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        L.init(this);
        mInGame = getIntent().getBooleanExtra(EXTRA_IN_GAME, false);
        buildUi();
        reload();
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
        // the app's panel: title and ✕ on top, the mods scroll, Import / Close below
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundResource(R.drawable.dl_scrim_strong);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.dl_panel);
        panel.setPadding(dp(28), dp(14), dp(28), dp(14));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        plp.setMargins(dp(16), dp(12), dp(16), dp(12));
        root.addView(panel, plp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text(t("MODS"), GOLD, 24, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = closeButton();
        close.setOnClickListener(v -> leave());
        head.addView(close);
        panel.addView(head);

        TextView hint = text(t("Mods change the game's files and textures. They load in this order, top first: a mod "
            + "lower down wins over one above it. Tap a mod to switch it on or off, move it or delete it. "
            + "Changes apply when the game starts."), DIM, 15, false);
        hint.setPadding(0, dp(4), 0, dp(8));
        panel.addView(hint);
        // several texture packs at once (Settings' own and mods'): a warning
        mTexWarn = text("", WARN, 15, true);
        mTexWarn.setPadding(0, 0, 0, dp(8));
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

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, dp(10), 0, 0);
        mImport = button("Import mod", true);
        mClose = button("Close", false);
        for (Button b : new Button[] {mImport, mClose}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(6), 0, dp(6), 0);
            buttons.addView(b, lp);
        }
        panel.addView(buttons);
        mImport.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQUEST_IMPORT);
        });
        mClose.setOnClickListener(v -> leave());
        setContentView(root);
    }

    private void reload() {
        mMods = Mods.list(this);
        showList();
    }

    // back from installing one (ModInstallActivity)
    @Override
    protected void onResume() {
        super.onResume();
        if (!mBusy && mList != null) {
            boolean before = mMods.size() != Mods.list(this).size();
            reload();
            if (before) mChanged = true;
        }
    }

    private void showList() {
        int packs = Mods.textureFolders(this).size();
        if (!"0".equals(Settings.read(this).getOrDefault("SMS_HD_TEXTURES", "1"))) {
            String[] own = DataDir.texturesDir(this).list();
            if (own != null && own.length > 0) packs++;
        }
        mTexWarn.setText("⚠  " + t(SettingsActivity.TEXTURE_MIX));
        mTexWarn.setVisibility(packs > 1 ? View.VISIBLE : View.GONE);
        mList.removeAllViews();
        mRows.clear();
        if (mMods.isEmpty()) {
            TextView none = text(t("No mods installed yet. Import a mod package (.zip with a mod.json) with the button "
                + "below, or put a mod's folder into mods/ in your data folder."), TEXT, 16, false);
            none.setPadding(0, dp(16), 0, dp(16));
            mList.addView(none);
        }
        for (int i = 0; i < mMods.size(); i++) {
            final Mods.Mod m = mMods.get(i);
            final int index = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.dl_row);
            row.setPadding(dp(18), dp(10), dp(18), dp(10));
            row.setFocusable(true);
            row.setClickable(true);
            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            String title = (i + 1) + ".  " + m.name + (m.version.isEmpty() ? "" : "  " + m.version);
            info.addView(text(title, TEXT, 18, true));
            StringBuilder sub = new StringBuilder();
            if (!m.author.isEmpty()) sub.append(t("by %s", m.author));
            if (!m.description.isEmpty()) sub.append(sub.length() > 0 ? "  ·  " : "").append(m.description);
            if (m.image() != null) sub.append(sub.length() > 0 ? "  ·  " : "").append(t("its own game"));
            if (m.files() != null) sub.append(sub.length() > 0 ? "  ·  " : "").append(t("game files"));
            if (m.textures() != null) sub.append(sub.length() > 0 ? "  ·  " : "").append(t("textures"));
            if (sub.length() > 0) info.addView(text(sub.toString(), DIM, 14, false));
            List<String> missing = m.enabled ? Mods.missing(m, mMods) : new ArrayList<>();
            if (!missing.isEmpty())
                info.addView(text("⚠  " + t("Needs %s, switched on and above it.", String.join(", ", missing)), WARN, 14, true));
            row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView state = text(t(m.enabled ? "On" : "Off"), m.enabled ? GOLD : DIM, 18, true);
            state.setPadding(dp(12), 0, 0, 0);
            row.addView(state);
            row.setOnClickListener(v -> actions(index));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            mList.addView(row, lp);
            mRows.add(row);
        }
        // mods to download, made from the player's own disc
        List<Mods.Release> more = Mods.available(this);
        if (!more.isEmpty()) {
            TextView head = text(t("TO DOWNLOAD"), GOLD, 16, true);
            head.setPadding(0, dp(18), 0, dp(2));
            mList.addView(head);
        }
        for (Mods.Release r : more) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.dl_row);
            row.setPadding(dp(18), dp(10), dp(18), dp(10));
            row.setFocusable(true);
            row.setClickable(true);
            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.addView(text(r.name + "  " + r.version, TEXT, 18, true));
            info.addView(text(t("by %s", r.author) + "  ·  " + t(r.description), DIM, 14, false));
            row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView get = text("⬇  " + t("%.1f GB", r.size / 1e9), GOLD, 17, true);
            get.setPadding(dp(12), 0, 0, 0);
            row.addView(get);
            row.setOnClickListener(v -> offer(r));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            mList.addView(row, lp);
            mRows.add(row);
        }
    }

    private void offer(Mods.Release r) {
        List<Mods.Release> tr = Mods.translations(r.id);
        choice(t("Install %s?", r.name),
            t("%s is downloaded (%.1f GB) and made from your own Super Mario Sunshine disc image (North American, "
                + "GMSE01): the download holds only the changes, nothing of the game. It then needs about %.1f GB "
                + "and is switched on: Play starts it, and switching it off here plays Super Mario Sunshine again.",
                r.name, r.size / 1e9, r.imageSize / 1e9),
            tr.isEmpty() ? new String[] {t("Install")} : new String[] {t("Install"), t("Install a translation…")},
            new Runnable[] {() -> ModInstallActivity.start(this, r), () -> translations(r.name, tr)});
    }

    // Tapping a mod: on/off, up, down, delete, and its translations
    private void actions(int i) {
        final Mods.Mod m = mMods.get(i);
        List<Mods.Release> tr = Mods.translations(m.id);
        List<String> labels = new ArrayList<>(java.util.Arrays.asList(t(m.enabled ? "Switch off" : "Switch on"),
            t("Move up"), t("Move down"), t("Delete")));
        Runnable[] base = {
            () -> {
                m.enabled = !m.enabled;
                // one game is played: a mod with its own disc image switches the others off
                if (m.enabled && m.image() != null)
                    for (Mods.Mod o : mMods) if (o != m && o.image() != null) o.enabled = false;
                changed(i);
            },
            () -> move(i, -1),
            () -> move(i, +1),
            () -> confirmDelete(i),
        };
        List<Runnable> acts = new ArrayList<>(java.util.Arrays.asList(base));
        if (!tr.isEmpty()) {
            labels.add(t("Install a translation…"));
            acts.add(() -> translations(m.name, tr));
        }
        String page = Mods.texturePage(m.id);
        if (page != null) {
            labels.add(t("HD textures…"));
            acts.add(() -> textures(m.name, page));
        }
        boolean[] dim = {false, i == 0, i == mMods.size() - 1, false};
        choice(m.name, null, labels.toArray(new String[0]), acts.toArray(new Runnable[0]), dim);
    }

    // A mod's HD texture pack from its makers' page: downloaded there, then
    // imported like a mod (Mods.importZip takes Dolphin texture packs)
    private void textures(String name, String page) {
        choice(t("HD textures"), t("An HD texture pack for %s is made by its own team. Download the texture pack's "
                + ".zip from its page, then import it here: it becomes a mod of its own, switched on. The packs are "
                + "large (several GB, and unpacked about twice that), and big textures need a lot of the device's "
                + "memory.", name),
            new String[] {t("Open download page"), t("Import downloaded file")},
            new Runnable[] {() -> openPage(page), () -> mImport.performClick()});
    }

    private void openPage(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (android.content.ActivityNotFoundException e) {
            choice(t("No browser"), url, new String[] {t("OK")}, new Runnable[] {null});
        }
    }

    // A mod's translations come from their own pages: the player downloads one
    // there and picks the file, and it is made from their disc like the mod
    // (ModInstallActivity).
    private void translations(String name, List<Mods.Release> tr) {
        StringBuilder msg = new StringBuilder(t("Translations of %s are made by their own teams. Download one from its "
            + "page (the .7z file, do not unpack it), then pick the downloaded file here. It is made from your own disc "
            + "like %s and needs about %.1f GB.", name, name, tr.get(0).imageSize / 1e9));
        for (Mods.Release r : tr) msg.append("\n\n").append(r.language).append(": ").append(t("by %s", r.credits))
            .append(", ").append(r.version);
        List<String> labels = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        for (Mods.Release r : tr) {
            labels.add(tr.size() == 1 ? t("Open download page") : t("Download page: %s", r.language));
            acts.add(() -> openPage(r.page));
        }
        labels.add(t("Pick downloaded file"));
        acts.add(() -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQUEST_TRANSLATION);
        });
        choice(t("Install a translation"), msg.toString(), labels.toArray(new String[0]), acts.toArray(new Runnable[0]));
    }

    // the picked file: which translation it is, by its size
    private void installTranslation(Uri uri) {
        long size = -1;
        try (android.database.Cursor c = getContentResolver().query(uri,
                new String[] {android.provider.OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) size = c.getLong(0);
        } catch (Exception ignored) {
        }
        Mods.Release r = Mods.translationOfSize(size);
        if (r == null) {
            choice(t("Not a known translation"), t("This file is none of the translations listed. Pick the .7z file "
                + "as downloaded from the translation's page, not unpacked."), new String[] {t("OK")},
                new Runnable[] {null});
            return;
        }
        ModInstallActivity.start(this, r, uri);
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
        showList();
        if (focus >= 0 && focus < mRows.size()) mRows.get(focus).requestFocus();
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

    // --- importing ---------------------------------------------------------------

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQUEST_IMPORT && result == RESULT_OK && data != null && data.getData() != null)
            importMod(data.getData());
        if (request == REQUEST_TRANSLATION && result == RESULT_OK && data != null && data.getData() != null)
            installTranslation(data.getData());
    }

    private void importMod(Uri uri) {
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
                        runOnUiThread(() -> mStatus.setText(t("Importing… %.0f MB", bytes / 1e6)));
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
                mStatus.setVisibility(View.GONE);
                if (err != null) {
                    choice(t("Mod not imported"), err, new String[] {t("OK")}, new Runnable[] {null});
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
        if (!mChanged) {
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

    @Override
    protected List<View> padItems() {
        List<View> items = new ArrayList<>(mRows);
        items.add(mImport);
        items.add(mClose);
        return items;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && getCurrentFocus() == null && !isTouch(this)) padFocusFirst();
    }
}
