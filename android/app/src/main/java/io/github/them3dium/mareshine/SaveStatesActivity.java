package io.github.them3dium.mareshine;

import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import static io.github.them3dium.mareshine.L.t;

// The save state slots over the paused game (from the in-game menu): five
// rows with their picture, stage, episode and time. A row offers to save into
// it, load it, make it the quick slot (the SAVE and LOAD buttons, the stick
// clicks) or delete it. Saving and loading go back into the game, which does
// them at its next frame, at the moment it was paused.
public class SaveStatesActivity extends AppActivity {
    private LinearLayout mList;
    private View mDelay;  // the Save/Load delay switch, above the slots

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
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(620), ViewGroup.LayoutParams.MATCH_PARENT);
        plp.gravity = Gravity.CENTER;
        plp.topMargin = plp.bottomMargin = dp(16);
        root.addView(panel, plp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(t("SAVE STATES"));
        title.setTextColor(0xFFFFC93C);
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        title.setTextSize(24);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(closeButton());
        panel.addView(head);
        closeOnOutsideTap(this, root, panel);

        TextView hint = new TextView(this);
        hint.setText(t("The quick slot (★) is the one the SAVE and LOAD buttons use. On a controller, hold the "
            + "hotkey (Select) and press R1 to save or L1 to load."));
        hint.setTextColor(getColor(R.color.dl_dim));
        hint.setTextSize(13);
        hint.setPadding(0, dp(2), 0, dp(8));
        panel.addView(hint);

        // the title screen or a stage still loading: say so here, instead of
        // the game's notice the player cannot see behind this menu
        if (!ready()) {
            TextView off = new TextView(this);
            off.setText(t("Save states work once you are in a level. Load your game first."));
            off.setTextColor(0xFFFFC93C);
            off.setTextSize(15);
            off.setPadding(0, 0, 0, dp(8));
            panel.addView(off);
        }

        // the touch buttons' safety: SAVE and LOAD act only once held
        TextView delay = new TextView(this);
        mDelay = delay;
        delay.setTextColor(getColor(R.color.dl_text));
        delay.setTextSize(16);
        delay.setPadding(dp(12), dp(10), dp(12), dp(10));
        delay.setBackgroundResource(R.drawable.dl_row);
        delay.setFocusable(true);
        Runnable label = () -> delay.setText(t("Save/Load delay") + ":  "
            + t(SaveStates.delay(this) ? "On" : "Off"));
        label.run();
        delay.setOnClickListener(v -> { SaveStates.setDelay(this, !SaveStates.delay(this)); label.run(); });
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.bottomMargin = dp(8);
        panel.addView(delay, dlp);

        ScrollView scroll = new ScrollView(this);
        mList = new LinearLayout(this);
        mList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(mList);
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));


        setContentView(root);
        fill();
    }

    private void fill() {
        mList.removeAllViews();
        int quick = SaveStates.quickSlot(this);
        for (int n = 1; n <= SaveStates.SLOTS; n++) {
            final SaveStates.Slot s = SaveStates.slot(n);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.dl_row);
            row.setPadding(dp(8), dp(6), dp(12), dp(6));
            row.setFocusable(true);
            row.setClickable(true);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = dp(6);

            ImageView pic = new ImageView(this);
            pic.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Bitmap b = s.used ? SaveStates.thumbnail(n) : null;
            if (b != null) pic.setImageBitmap(b);
            else pic.setBackgroundColor(0x33000000);
            row.addView(pic, new LinearLayout.LayoutParams(dp(112), dp(63)));

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(dp(12), 0, 0, 0);
            TextView head = new TextView(this);
            head.setText(t("Slot %d", n) + (n == quick ? "  ★" : ""));
            head.setTextColor(0xFFFFFFFF);
            head.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            head.setTextSize(17);
            texts.addView(head);
            TextView line = new TextView(this);
            line.setTextColor(getColor(R.color.dl_text));
            line.setTextSize(14);
            if (!s.used) {
                line.setText(t("Empty"));
            } else {
                String where = s.stage + (s.episode.isEmpty() ? "" : " · " + s.episode);
                line.setText(where.trim().isEmpty() ? s.when : where + "\n" + s.when
                    + (s.exact ? "" : "  (" + t("position only") + ")"));
            }
            texts.addView(line);
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            final int slot = n;
            row.setOnClickListener(v -> options(slot, s));
            mList.addView(row, rlp);
        }
    }

    // controller: AppActivity's scheme, straight down from the delay switch
    // through the slots (no tabs here)
    @Override protected boolean usesPadNav() { return true; }
    @Override protected boolean onPadTab(int dir, boolean shoulder) { return true; }

    @Override
    protected List<View> padItems() {
        List<View> v = new ArrayList<>();
        v.add(mDelay);
        for (int i = 0; i < mList.getChildCount(); i++) v.add(mList.getChildAt(i));
        return v;
    }

    private boolean ready() {
        try { return SaveStates.nativeReady() != 0; } catch (Throwable e) { return true; }
    }

    private void options(int n, SaveStates.Slot s) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        List<Boolean> dimmed = new ArrayList<>();
        // outside a level the game refuses both: they stay in the list, greyed
        // out, and say why when chosen (a missing entry only puzzles)
        final boolean ok = ready();
        Runnable why = () -> new DlDialog(this)
            .setTitle(t("Slot %d", n))
            .setMessage(t("Save states work once you are in a level. Load your game first."))
            .setPositiveButton(t("OK"), null)
            .show();
        labels.add(s.used ? t("Save here (replaces it)") : t("Save here"));
        dimmed.add(!ok);
        actions.add(!ok ? why : () -> {
            SaveStates.nativeSave(n);
            setResult(RESULT_OK);
            finish();
        });
        if (s.used) {
            labels.add(t("Load"));
            dimmed.add(!ok);
            actions.add(!ok ? why : () -> choice(t("Load slot %d?", n), t("Progress since you saved it is lost."),
                new String[] {t("Load")}, new Runnable[] {() -> {
                    if (SaveStates.nativeLoad(n) != 0) {
                        setResult(RESULT_OK);
                        finish();
                    }
                }}));
        }
        if (n != SaveStates.quickSlot(this)) {
            labels.add(t("Use as quick slot"));
            dimmed.add(false);
            actions.add(() -> { SaveStates.setQuickSlot(this, n); fill(); });
        }
        if (s.used) {
            labels.add(t("Delete"));
            dimmed.add(false);
            actions.add(() -> choice(t("Delete slot %d?", n), null,
                new String[] {t("Delete")}, new Runnable[] {() -> { SaveStates.nativeDelete(n); fill(); }}));
        }
        boolean[] dim = new boolean[dimmed.size()];
        for (int i = 0; i < dim.length; i++) dim[i] = dimmed.get(i);
        choice(t("Slot %d", n), null, labels.toArray(new String[0]), actions.toArray(new Runnable[0]), dim);
    }
}
