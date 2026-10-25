package io.github.them3dium.mareshine;

import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static io.github.them3dium.mareshine.L.t;

// A mod's page (Mods, docs/MODS.md): its preview picture fills the screen; ON/OFF
// at the top left, its suns in the middle, Back at the top right; its name and
// description in a panel (the text starts scrolling by itself after three
// seconds, until the player scrolls it); its buttons at the bottom, four at a
// time, centred, with Back and Next for more. A "page" button opens its
// settings as a list on a page of their own (EXTRA_PAGE).
public class ModPageActivity extends AppActivity {
    static final String EXTRA_ID = "mod", EXTRA_PAGE = "page";
    static final int PER_PAGE = 4;
    private static final int TEXT = 0xFFF4F8FF, DIM = 0xFF8FB4D4, GOLD = 0xFFFFC93C, ON = 0xFF46E06A;

    private Mods.Mod mMod;
    private List<Mods.Option> mOptions;  // this page's
    private String mPage;                // "" for the main page, else the page options' indexes ("2", "2/0")
    private int mFirst;                  // the first button shown
    private Button mOnOff, mBack;
    private LinearLayout mButtons;
    private final List<View> mButtonViews = new ArrayList<>();
    private LinearLayout mRows;          // a settings page's
    private final List<View> mRowViews = new ArrayList<>();
    private ScrollView mScroll;
    private TextView mScore;
    private ModViews.Suns mSuns;
    private View mRating;
    private TextView mTranslator;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mAutoOff;
    private float mAutoAt;

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override protected boolean keepClearOfSystemUi() { return false; }

    static void start(android.app.Activity from, String id, String page, int request) {
        from.startActivityForResult(new Intent(from, ModPageActivity.class).putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_PAGE, page), request);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        L.init(this);
        mPage = getIntent().getStringExtra(EXTRA_PAGE);
        if (mPage == null) mPage = "";
        if (!load()) {
            finish();
            return;
        }
        buildUi();
    }

    // the mod and this page's options, fresh (another page may have changed them)
    private boolean load() {
        mMod = Mods.find(this, getIntent().getStringExtra(EXTRA_ID));
        if (mMod == null) return false;
        mOptions = mMod.options;
        Mods.Option page = null;
        if (!mPage.isEmpty())
            for (String part : mPage.split("/")) {
                int i;
                try {
                    i = Integer.parseInt(part);
                } catch (NumberFormatException e) {
                    return false;
                }
                if (i < 0 || i >= mOptions.size()) return false;
                page = mOptions.get(i);
                mOptions = page.options;
            }
        mPageOption = page;
        return true;
    }

    private Mods.Option mPageOption;

    private TextView text(String s, int color, float size, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextColor(color);
        v.setTextSize(size);
        v.setTypeface(Typeface.create("sans-serif-condensed", bold ? Typeface.BOLD : Typeface.NORMAL));
        return v;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        b.setTextSize(17);
        b.setTextColor(TEXT);
        b.setStateListAnimator(null);
        b.setBackgroundResource(R.drawable.dl_row);
        b.setPadding(dp(16), dp(6), dp(16), dp(6));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        return b;
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0A1B30);

        // the preview fills the screen, a little dimmed under the panel and buttons
        ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(preview, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        View dim = new View(this);
        dim.setBackgroundColor(0x55000000);
        root.addView(dim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        int w = getResources().getDisplayMetrics().widthPixels;
        ModViews.load(this, mMod.picture(mMod.preview), w, b -> {
            if (b != null) preview.setImageBitmap(b);
        });

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), dp(8), dp(12), dp(10));
        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        // the picture goes under the bars and the cutout; the rest keeps clear of them
        col.setOnApplyWindowInsetsListener((v, insets) -> {
            int[] safe = safeArea(this, insets, getResources().getDisplayMetrics().density);
            v.setPadding(safe[0] + dp(12), safe[1] + dp(8), safe[2] + dp(12), safe[3] + dp(10));
            return insets;
        });

        // top: ON/OFF, the suns, Back
        FrameLayout top = new FrameLayout(this);
        mOnOff = button("");
        mOnOff.setTextSize(24);
        mOnOff.setPadding(dp(22), dp(2), dp(22), dp(2));
        mOnOff.setOnClickListener(v -> {
            Mods.setEnabled(this, mMod.id, !mMod.enabled);
            changed();
        });
        top.addView(mOnOff, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48),
            Gravity.START | Gravity.CENTER_VERTICAL));
        LinearLayout rating = new LinearLayout(this);
        rating.setOrientation(LinearLayout.VERTICAL);
        rating.setGravity(Gravity.CENTER_HORIZONTAL);
        ModViews.Suns suns = new ModViews.Suns(this);
        mSuns = suns;
        rating.addView(suns, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)));
        mScore = text("", GOLD, 14, false);
        TextView score = mScore;
        // a tap on the suns: the player's own rating
        rating.setClickable(true);
        rating.setFocusable(true);
        // a dark pill under it keeps it readable over the picture; lit when focused
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0x99000000);
        pill.setCornerRadius(dp(12));
        rating.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[] {
            pill, getDrawable(R.drawable.dl_tab)}));
        rating.setPadding(dp(10), dp(2), dp(10), dp(2));
        rating.setContentDescription(t("Rate"));
        rating.setOnClickListener(v -> rate());
        mRating = rating;
        score.setShadowLayer(dp(3), 0, dp(1), 0xFF000000);
        LinearLayout.LayoutParams sclp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        sclp.gravity = Gravity.CENTER_HORIZONTAL;
        rating.addView(score, sclp);
        top.addView(rating, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        mBack = button(t("Back"));
        mBack.setTextSize(20);
        mBack.setPadding(dp(22), dp(2), dp(22), dp(2));
        mBack.setOnClickListener(v -> finish());
        top.addView(mBack, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48),
            Gravity.END | Gravity.CENTER_VERTICAL));
        col.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));

        // the panel: name and description, or a settings page's rows
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xB30A1B30);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), 0x99FFFFFF);
        panel.setBackground(bg);
        panel.setPadding(dp(24), dp(10), dp(24), dp(10));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        plp.setMargins(dp(40), dp(10), dp(40), dp(12));
        col.addView(panel, plp);
        String title = mPageOption != null ? mPageOption.label
            : mMod.name + (mMod.version.isEmpty() ? "" : " " + mMod.version);
        TextView head = text(title, 0xFFFFA41C, 26, false);
        panel.addView(head);
        mScroll = new ScrollView(this);
        panel.addView(mScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        if (mPageOption == null) {
            // "* " lists become bullets; a text with a list reads from the left
            String about = ("\n" + mMod.description).replaceAll("\n[*-] ", "\n• ").substring(1);
            TextView desc = text(about, TEXT, 19, false);
            desc.setGravity(about.contains("\n•") ? Gravity.START : Gravity.CENTER_HORIZONTAL);
            desc.setPadding(0, dp(6), 0, dp(6));
            mScroll.addView(desc);
            mScroll.setOnTouchListener((v, e) -> {
                if (e.getAction() == MotionEvent.ACTION_DOWN) stopAuto();
                return false;
            });
            mHandler.postDelayed(mAuto, 3000);
        } else {
            mRows = new LinearLayout(this);
            mRows.setOrientation(LinearLayout.VERTICAL);
            mScroll.addView(mRows);
        }

        // at the panel's foot: who translated the language picked (left), the mod's makers (right)
        if (mPageOption == null) {
            FrameLayout foot = new FrameLayout(this);
            mTranslator = text("", DIM, 15, true);
            foot.addView(mTranslator, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL));
            if (!mMod.author.isEmpty()) {
                TextView by = text(t("by %s", mMod.author), mMod.authorColor != 0 ? mMod.authorColor : DIM, 15, true);
                foot.addView(by, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL));
            }
            foot.setPadding(0, dp(4), 0, 0);
            panel.addView(foot);
        }

        // the buttons
        mButtons = new LinearLayout(this);
        mButtons.setOrientation(LinearLayout.HORIZONTAL);
        mButtons.setGravity(Gravity.CENTER);
        col.addView(mButtons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        setContentView(root);
        refresh();
        Ratings.refresh(this, this::showScore);
        mOnOff.requestFocus();
    }

    // --- the description's slow scroll ------------------------------------------

    private final Runnable mAuto = new Runnable() {
        @Override public void run() {
            if (mAutoOff || mScroll == null) return;
            View child = mScroll.getChildAt(0);
            int end = child == null ? 0 : Math.max(0, child.getHeight() - mScroll.getHeight());
            if (end == 0) return;  // it fits
            if (mScroll.getScrollY() >= end) {  // at the end: a pause, then from the top
                mHandler.postDelayed(() -> {
                    if (mAutoOff) return;
                    mScroll.smoothScrollTo(0, 0);
                    mAutoAt = 0;
                    mHandler.postDelayed(mAuto, 3000);
                }, 3000);
                return;
            }
            mAutoAt += dp(24) / 60f;  // 24 dp a second
            mScroll.scrollTo(0, Math.round(mAutoAt));
            mHandler.postDelayed(this, 16);
        }
    };

    // the player scrolls: it stays theirs while the page is open
    private void stopAuto() {
        mAutoOff = true;
        mHandler.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // --- state ---------------------------------------------------------------------

    // after a change made by a dialog (ModFlows)
    void reloadPage() { changed(); }

    private void changed() {
        setResult(RESULT_OK);
        if (!load()) {
            finish();
            return;
        }
        refresh();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        Uri uri = data != null ? data.getData() : null;
        if (request == ModFlows.PICK_TRANSLATION) {
            if (result == RESULT_OK && uri != null) ModFlows.picked(this, uri);
            return;
        }
        if (request == ModFlows.PICK_TEXTURES) {
            if (result == RESULT_OK && uri != null) importTextures(uri);
            return;
        }
        if (result == RESULT_OK) changed();
        else if (load()) refresh();
    }

    // back from installing a language (ModInstallActivity)
    @Override
    protected void onResume() {
        super.onResume();
        if (mOnOff != null && !mImporting && load()) refresh();
    }

    // Super Mario Eclipse's HD textures into hd/textures, then switched on
    private volatile boolean mImporting, mCancelImport;

    private void importTextures(Uri uri) {
        mImporting = true;
        mCancelImport = false;
        android.app.Dialog dlg = choice(t("Importing…"), t("Unpacking the textures. A large pack takes a few "
            + "minutes."), new String[0], new Runnable[0]);
        dlg.setOnDismissListener(d -> {
            if (mImporting) mCancelImport = true;  // closed while it runs: Cancel
        });
        File dest = ModFlows.texturesDir(this);
        new Thread(() -> {
            String error = null;
            int count = 0;
            try {
                dest.getParentFile().mkdirs();
                count = Mods.importTextures(this, uri, dest, new Mods.Progress() {
                    @Override public void step(long bytes) {}
                    @Override public boolean cancelled() { return mCancelImport; }
                });
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            final String err = error;
            final int n = count;
            runOnUiThread(() -> {
                mImporting = false;
                if (dlg.isShowing()) dlg.dismiss();
                if (mCancelImport) return;
                if (err != null) {
                    choice(t("Textures not imported"), err, new String[] {t("OK")}, new Runnable[] {null});
                    return;
                }
                load();
                for (Mods.Option o : mMod.allOptions()) if (o.id.equals(EclipseMod.HD)) Mods.setOption(this, mMod, o, "1");
                changed();
                choice(t("HD textures"), t("%d textures imported and switched on.", n), new String[] {t("OK")},
                    new Runnable[] {null});
            });
        }, "mod-textures").start();
    }

    // the suns' line: the rating, and the player's own
    private void showScore() {
        float shown = Ratings.shown(mMod);
        int votes = Ratings.votes(mMod.id);
        mSuns.setRating(shown);
        String s = shown >= 0 ? String.format(Locale.ROOT, "%.2f/5", shown) : t("Not rated yet");
        if (votes > 0) s += "  ·  " + t(votes == 1 ? "%d vote" : "%d votes", votes);
        int mine = Mods.myRating(this, mMod.id);
        if (mine > 0) s += "  ·  " + t("yours: %d", mine);
        mScore.setText(s);
    }

    private void rate() {
        int mine = Mods.myRating(this, mMod.id);
        long wait = Ratings.waitLeft(this, mMod.id);
        if (mine > 0 && wait > 0) {
            int min = (int) Math.max(1, (wait + 59_999) / 60_000);
            choice(t("Rate %s", mMod.name), t(min == 1 ? "You gave it %d suns. You can change your rating again in "
                    + "%d minute." : "You gave it %d suns. You can change your rating again in %d minutes.", mine, min),
                new String[] {t("OK")}, new Runnable[] {null});
            return;
        }
        String[] labels = new String[5];
        Runnable[] acts = new Runnable[5];
        for (int i = 0; i < 5; i++) {
            int suns = 5 - i;
            StringBuilder l = new StringBuilder();
            for (int k = 0; k < 5; k++) l.append(k < suns ? "☀" : "·");
            labels[i] = (suns == mine ? "✓  " : "") + l + "   " + suns;
            acts[i] = () -> {
                Ratings.rate(this, mMod.id, suns, this::showScore);
                showScore();
            };
        }
        choice(t("Rate %s", mMod.name), t("How many suns do you give it?"), labels, acts);
    }

    private void refresh() {
        showScore();
        mOnOff.setText(mMod.enabled ? "ON" : "OFF");
        mOnOff.setTextColor(mMod.enabled ? ON : DIM);
        mOnOff.setContentDescription(t(mMod.enabled ? "On" : "Off"));
        showButtons();
        if (mRows != null) showRows();
        if (mTranslator != null) {
            StringBuilder who = new StringBuilder();
            for (Mods.Option o : mMod.allOptions()) {
                Mods.Value v = o.isChoice() ? o.value(mMod.value(o)) : null;
                if (v != null && !v.translator.isEmpty())
                    who.append(who.length() > 0 ? "  ·  " : "").append(t("Translated by %s", v.translator));
            }
            mTranslator.setText(who);
        }
    }

    // a toggle's or choice's value as shown
    private String shown(Mods.Option o) {
        String v = mMod.value(o);
        if (o.isToggle()) return t(v.equals("1") ? "On" : "Off");
        Mods.Value val = o.value(v);
        return val != null ? val.label : v;
    }

    private void showButtons() {
        mButtons.removeAllViews();
        mButtonViews.clear();
        // a settings page lists its options as rows: no buttons but its links
        List<Mods.Option> opts = mPageOption == null ? mOptions : new ArrayList<>();
        int n = opts.size();
        if (mFirst >= n) mFirst = Math.max(0, (n - 1) / PER_PAGE * PER_PAGE);
        boolean paged = n > PER_PAGE;
        if (paged) {
            Button prev = button("‹ " + t("Back"));
            prev.setEnabled(mFirst > 0);
            prev.setAlpha(mFirst > 0 ? 1f : 0.4f);
            prev.setOnClickListener(v -> page(-1));
            addButton(prev);
        }
        for (int i = mFirst; i < Math.min(n, mFirst + PER_PAGE); i++) addButton(optionButton(opts.get(i), i));
        if (paged) {
            Button next = button(t("Next") + " ›");
            next.setEnabled(mFirst + PER_PAGE < n);
            next.setAlpha(mFirst + PER_PAGE < n ? 1f : 0.4f);
            next.setOnClickListener(v -> page(1));
            addButton(next);
        }
    }

    // four fit side by side; with Back and Next the six share the width
    private void addButton(Button b) {
        boolean paged = mOptions.size() > PER_PAGE && mPageOption == null;
        LinearLayout.LayoutParams lp = paged
            ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            : new LinearLayout.LayoutParams(dp(160), ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMargins(dp(5), 0, dp(5), 0);
        mButtons.addView(b, lp);
        mButtonViews.add(b);
    }

    private void page(int dir) {
        int n = mOptions.size();
        int to = mFirst + dir * PER_PAGE;
        if (to < 0 || to >= n) return;
        mFirst = to;
        showButtons();
        if (!mButtonViews.isEmpty()) mButtonViews.get(dir > 0 ? mButtonViews.size() - 1 : 0).requestFocus();
    }

    // a button: the label, and below it a toggle's or choice's value
    private Button optionButton(Mods.Option o, int index) {
        Button b = button(o.label);
        if (o.isToggle() || o.isChoice()) {
            String value = shown(o);
            boolean lit = o.isToggle() && mMod.value(o).equals("1");
            android.text.SpannableStringBuilder s = new android.text.SpannableStringBuilder(o.label + "\n" + value);
            s.setSpan(new android.text.style.RelativeSizeSpan(0.75f), 0, o.label.length(), 0);
            s.setSpan(new android.text.style.ForegroundColorSpan(DIM), 0, o.label.length(), 0);
            if (lit) s.setSpan(new android.text.style.ForegroundColorSpan(GOLD), o.label.length() + 1, s.length(), 0);
            b.setText(s);
            b.setLineSpacing(0, 0.9f);
        }
        b.setOnClickListener(v -> act(o, index));
        b.setOnLongClickListener(v -> longPress(o));
        return b;
    }

    private void act(Mods.Option o, int index) {
        // Super Mario Eclipse's languages and its HD textures, installed from here
        if (mMod.id.equals(EclipseMod.ID)) {
            if (o.id.equals(EclipseMod.LANGUAGE)) {
                ModFlows.languages(this);
                return;
            }
            if (o.id.equals(EclipseMod.HD) && !ModFlows.texturesDir(this).isDirectory()) {
                ModFlows.textures(this);
                return;
            }
        }
        if (o.isToggle()) {
            Mods.setOption(this, mMod, o, mMod.value(o).equals("1") ? "0" : "1");
            changed();
        } else if (o.isChoice()) {
            choose(o);
        } else if (o.isPage()) {
            start(this, mMod.id, mPage.isEmpty() ? String.valueOf(index) : mPage + "/" + index, 1);
        } else if (o.isLink() && !o.url.isEmpty()) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(o.url)));
            } catch (android.content.ActivityNotFoundException e) {
                choice(t("No browser"), o.url, new String[] {t("OK")}, new Runnable[] {null});
            }
        }
    }

    private void choose(Mods.Option o) {
        String[] labels = new String[o.values.size()];
        Runnable[] acts = new Runnable[o.values.size()];
        String cur = mMod.value(o);
        for (int i = 0; i < labels.length; i++) {
            Mods.Value v = o.values.get(i);
            labels[i] = (v.id.equals(cur) ? "✓  " : "") + v.label;
            acts[i] = () -> {
                Mods.setOption(this, mMod, o, v.id);
                changed();
            };
        }
        choice(o.label, o.text.isEmpty() ? null : o.text, labels, acts);
    }

    // a long press: Super Mario Eclipse's languages and HD textures to delete
    private boolean longPress(Mods.Option o) {
        if (!mMod.id.equals(EclipseMod.ID)) return false;
        if (o.id.equals(EclipseMod.LANGUAGE)) {
            List<Mods.Release> have = new ArrayList<>();
            for (Mods.Release r : EclipseMod.languages()) if (EclipseMod.has(this, r)) have.add(r);
            if (have.size() < 2) {
                choice(t("Delete a language"), t("This is the only language installed. Delete Super Mario Eclipse in "
                    + "the mod list (hold it there) to remove it."), new String[] {t("OK")}, new Runnable[] {null});
                return true;
            }
            String[] labels = new String[have.size()];
            Runnable[] acts = new Runnable[have.size()];
            for (int i = 0; i < have.size(); i++) {
                Mods.Release r = have.get(i);
                labels[i] = t("Delete %s (%.1f GB)", EclipseMod.languageName(r), r.imageSize / 1e9);
                acts[i] = () -> {
                    try {
                        EclipseMod.delete(this, r);
                    } catch (Exception ignored) {
                    }
                    changed();
                };
            }
            choice(t("Delete a language"), null, labels, acts);
            return true;
        }
        if (o.id.equals(EclipseMod.HD) && ModFlows.texturesDir(this).isDirectory()) {
            choice(t("HD textures"), null, new String[] {t("Import another pack"), t("Delete the HD textures")},
                new Runnable[] {() -> ModFlows.textures(this), () -> {
                    TexturePackActivity.remove(ModFlows.texturesDir(this).getParentFile());
                    Mods.setOption(this, mMod, o, "0");
                    changed();
                }});
            return true;
        }
        return false;
    }

    // a settings page: one row per option, its value at the right
    private void showRows() {
        mRows.removeAllViews();
        mRowViews.clear();
        for (int i = 0; i < mOptions.size(); i++) {
            final Mods.Option o = mOptions.get(i);
            final int index = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.dl_row);
            row.setPadding(dp(16), dp(8), dp(16), dp(8));
            row.setFocusable(true);
            row.setClickable(true);
            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.addView(text(o.label, TEXT, 18, true));
            if (!o.text.isEmpty()) info.addView(text(o.text, DIM, 14, false));
            row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            String value = o.isToggle() || o.isChoice() ? shown(o) : o.isPage() ? "›" : "↗";
            boolean lit = o.isToggle() && mMod.value(o).equals("1");
            TextView val = text(value, lit || o.isChoice() ? GOLD : DIM, 18, true);
            val.setPadding(dp(12), 0, 0, 0);
            row.addView(val);
            row.setOnClickListener(v -> act(o, index));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            mRows.addView(row, lp);
            mRowViews.add(row);
        }
    }

    // --- controller ------------------------------------------------------------------

    @Override protected boolean usesPadNav() { return true; }

    // L/R page through the buttons; the D-pad's left/right moves along them
    @Override
    protected boolean onPadTab(int dir, boolean shoulder) {
        if (!shoulder) return false;
        page(dir);
        return true;
    }

    @Override
    protected List<View> padItems() {
        List<View> items = new ArrayList<>();
        items.add(mOnOff);
        items.add(mRating);
        items.addAll(mRowViews);
        items.addAll(mButtonViews);
        items.add(mBack);
        return items;
    }
}
