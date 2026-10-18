package io.github.them3dium.mareshine;

import android.app.AlertDialog;
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

        TextView title = new TextView(this);
        title.setText(t("SAVE STATES"));
        title.setTextColor(0xFFFFC93C);
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        title.setTextSize(24);
        panel.addView(title);

        TextView hint = new TextView(this);
        hint.setText(t("The quick slot (★) is the one the SAVE and LOAD buttons use. On a controller, hold the "
            + "hotkey (Select) and press R1 to save or L1 to load."));
        hint.setTextColor(getColor(R.color.dl_dim));
        hint.setTextSize(13);
        hint.setPadding(0, dp(2), 0, dp(8));
        panel.addView(hint);

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
        View first = null;
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
            if (first == null || n == quick) first = row;
        }
        if (first != null) first.requestFocus();
    }

    private void options(int n, SaveStates.Slot s) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add(s.used ? t("Save here (replaces it)") : t("Save here"));
        actions.add(() -> {
            SaveStates.nativeSave(n);
            setResult(RESULT_OK);
            finish();
        });
        if (s.used) {
            labels.add(t("Load"));
            actions.add(() -> new AlertDialog.Builder(this)
                .setTitle(t("Load slot %d?", n))
                .setMessage(t("Progress since you saved it is lost."))
                .setPositiveButton(t("Load"), (d, w) -> {
                    if (SaveStates.nativeLoad(n) != 0) {
                        setResult(RESULT_OK);
                        finish();
                    }
                })
                .setNegativeButton(t("Cancel"), null)
                .show());
        }
        if (n != SaveStates.quickSlot(this)) {
            labels.add(t("Use as quick slot"));
            actions.add(() -> { SaveStates.setQuickSlot(this, n); fill(); });
        }
        if (s.used) {
            labels.add(t("Delete"));
            actions.add(() -> new AlertDialog.Builder(this)
                .setTitle(t("Delete slot %d?", n))
                .setPositiveButton(t("Delete"), (d, w) -> { SaveStates.nativeDelete(n); fill(); })
                .setNegativeButton(t("Cancel"), null)
                .show());
        }
        new AlertDialog.Builder(this)
            .setTitle(t("Slot %d", n))
            .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
            .setNegativeButton(t("Cancel"), null)
            .show();
    }
}
