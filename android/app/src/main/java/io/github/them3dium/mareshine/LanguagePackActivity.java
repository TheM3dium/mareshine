package io.github.them3dium.mareshine;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Locale;

import static io.github.them3dium.mareshine.L.t;

// Language packs from the player's own European disc (GMSP01): the European
// release has the game's text and menus in French, German, Spanish and
// Italian. The disc image is picked (and copied into iso/) or put into iso/
// by hand; LanguagePackBuilder turns it and the North American disc into
// packs in languages/; afterwards the European image can be deleted, the
// packs do not need it. Nothing is downloaded.
public class LanguagePackActivity extends AppActivity {
    private static final int REQUEST_PICK = 2001;
    private static final int TEXT = 0xFFF4F8FF, DIM = 0xFF8FB4D4;

    private TextView tvInfo, tvStatus;
    private ProgressBar bar;
    private Button btnPick, btnBuild, btnClose;
    private volatile boolean mCancel, mBusy;

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private Button button(String text, boolean main) {
        Button b = new Button(this);
        b.setText(t(text));
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        L.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0A1B30);
        ScrollView sv = new ScrollView(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.dl_panel);
        panel.setPadding(dp(32), dp(22), dp(32), dp(22));
        sv.addView(panel);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(760), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(sv, plp);

        TextView title = new TextView(this);
        title.setText(t("EXTRACT LANGUAGES FROM YOUR OWN EU .ISO"));
        title.setTextColor(0xFFFFC531);
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        title.setTextSize(22);
        panel.addView(title);

        tvInfo = new TextView(this);
        tvInfo.setTextColor(TEXT);
        tvInfo.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        tvInfo.setTextSize(16);
        tvInfo.setLineSpacing(0, 1.15f);
        tvInfo.setPadding(0, dp(10), 0, dp(10));
        tvInfo.setText(t("The North American game is English only. The European release also has French, German, "
            + "Spanish and Italian: pick your own European disc image with the button below, and the app makes the "
            + "languages from it. Nothing is downloaded.\n\n"
            + "It must be a plain .iso or .gcm file. You can also copy it into iso/pal/ in your data folder. When "
            + "the languages are made, you can delete it."));
        panel.addView(tvInfo);

        tvStatus = new TextView(this);
        tvStatus.setTextColor(0xFFBFD98A);
        tvStatus.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        tvStatus.setTextSize(16);
        panel.addView(tvStatus);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFFFC531));
        bar.setVisibility(View.GONE);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16));
        barLp.topMargin = dp(8);
        panel.addView(bar, barLp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, dp(16), 0, 0);
        btnPick = button("Select European ISO", false);
        btnBuild = button("Build language packs", true);
        btnClose = button("Close", false);
        for (Button b : new Button[] {btnPick, btnBuild, btnClose}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(6), 0, dp(6), 0);
            buttons.addView(b, lp);
        }
        panel.addView(buttons);
        btnPick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQUEST_PICK);
        });
        btnBuild.setOnClickListener(v -> build());
        btnClose.setOnClickListener(v -> {
            if (mBusy) {
                mCancel = true;
                btnClose.setEnabled(false);
            } else {
                finish();
            }
        });
        setContentView(root);
        refresh();
    }

    @Override
    public void onBackPressed() { btnClose.performClick(); }

    // --- the discs -------------------------------------------------------------

    // iso/pal/ first (where picking puts it), then iso/ itself; any name, it
    // is recognised by its disc ID
    private File europeanDisc() {
        for (File dir : new File[] {DataDir.palDir(this), DataDir.romDir(this)}) {
            File[] fs = dir.listFiles();
            if (fs == null) continue;
            Arrays.sort(fs);
            for (File f : fs) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if ((n.endsWith(".iso") || n.endsWith(".gcm")) && f.length() > 10 * 1024 * 1024 && LauncherActivity.isEuropeanDisc(f))
                    return f;
            }
        }
        return null;
    }

    private void refresh() {
        File pal = europeanDisc(), us = LauncherActivity.findIsoFile(this);
        StringBuilder sb = new StringBuilder();
        sb.append(us != null && DiscCheck.checkHeader(us).ok ? "✓  " + t("North American disc: %s", us.getName())
            : "⚠  " + t("No North American disc (GMSE01) set: Settings → GAME → Disc image"));
        sb.append('\n');
        sb.append(pal != null ? "✓  " + t("European disc: %s", pal.getName()) : "•  " + t("No European disc (GMSP01) in iso/pal/ yet"));
        String have = installedList();
        if (!have.isEmpty()) sb.append("\n✓  ").append(t("Installed: %s", have));
        tvStatus.setText(sb.toString());
        btnBuild.setEnabled(pal != null && us != null && !mBusy);
        btnBuild.setAlpha(btnBuild.isEnabled() ? 1f : 0.45f);
        btnPick.setEnabled(!mBusy);
        (pal == null ? btnPick : btnBuild).requestFocus();
    }

    private String installedList() {
        StringBuilder sb = new StringBuilder();
        File[] fs = DataDir.languagesDir(this).listFiles();
        if (fs != null) {
            Arrays.sort(fs);
            for (File f : fs)
                if (new File(f, "files").isDirectory()) sb.append(sb.length() > 0 ? ", " : "").append(languageName(f.getName()));
        }
        return sb.toString();
    }

    static String languageName(String code) {
        switch (code) {
            case "en": return "English";
            case "de": return "Deutsch";
            case "fr": return "Français";
            case "es": return "Español";
            case "it": return "Italiano";
            case "pl": return "Polski";
            default: return code;
        }
    }

    // --- picking: copy into iso/ ----------------------------------------------------

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK && resultCode == RESULT_OK && data != null && data.getData() != null)
            copyIn(data.getData());
    }

    private void copyIn(Uri uri) {
        mBusy = true;
        mCancel = false;
        bar.setVisibility(View.VISIBLE);
        refresh();
        new Thread(() -> {
            long size = -1;
            try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int i = c.getColumnIndex(OpenableColumns.SIZE);
                    if (i >= 0) size = c.getLong(i);
                }
            } catch (Exception ignored) {}
            File dir = DataDir.palDir(this);
            dir.mkdirs();
            File dest = new File(dir, "GMSP01.iso"), tmp = new File(dir, "GMSP01.iso.tmp");
            String error = null;
            try {
                if (size > 0 && dir.getUsableSpace() < size + (64L << 20)) throw new Exception(t("not enough free space"));
                try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(tmp)) {
                    byte[] head = new byte[6];
                    int n = in.read(head);
                    String id = n == 6 ? new String(head, java.nio.charset.StandardCharsets.ISO_8859_1) : "";
                    if (!id.startsWith("GMSP")) throw new Exception(id.isEmpty()
                        ? t("this is not the European Super Mario Sunshine (GMSP01). It must be a plain .iso or .gcm.")
                        : t("this is not the European Super Mario Sunshine (GMSP01), it is %s. It must be a plain .iso or .gcm.",
                            id.replaceAll("[^ -~]", "?")));
                    out.write(head, 0, n);
                    long got = n, last = 0;
                    byte[] buf = new byte[1 << 17];
                    while ((n = in.read(buf)) > 0) {
                        if (mCancel) throw new Exception(t("cancelled"));
                        out.write(buf, 0, n);
                        got += n;
                        long now = System.currentTimeMillis();
                        if (now - last > 250) {
                            last = now;
                            final long g = got, s = size;
                            runOnUiThread(() -> {
                                tvStatus.setText(t("Copying the European disc… %.0f MB", g / 1e6));
                                if (s > 0) bar.setProgress((int) (g * 1000 / s));
                            });
                        }
                    }
                }
                if (dest.exists()) dest.delete();
                if (!tmp.renameTo(dest)) throw new Exception(t("cannot rename the copy"));
            } catch (Exception e) {
                tmp.delete();
                error = e.getMessage();
            }
            final String err = error;
            runOnUiThread(() -> {
                mBusy = false;
                bar.setVisibility(View.GONE);
                btnClose.setEnabled(true);
                refresh();
                if (err != null) message(t("European disc not copied"), err);
            });
        }, "pal-copy").start();
    }

    // --- building ------------------------------------------------------------------

    private void build() {
        File pal = europeanDisc(), us = LauncherActivity.findIsoFile(this);
        if (pal == null || us == null) return;
        String bad = LanguagePackBuilder.checkDiscs(pal, us);
        if (bad != null) {
            message(t("Cannot build the language packs"), bad);
            return;
        }
        mBusy = true;
        mCancel = false;
        bar.setVisibility(View.VISIBLE);
        bar.setProgress(0);
        refresh();
        new Thread(() -> {
            String error = null;
            try {
                LanguagePackBuilder.build(pal, us, DataDir.languagesDir(this),
                    Arrays.asList(LanguagePackBuilder.LANG_CODES), new LanguagePackBuilder.Progress() {
                        @Override public void step(String what, double f) {
                            runOnUiThread(() -> {
                                tvStatus.setText(t("Building: %s", what));
                                bar.setProgress((int) (f * 1000));
                            });
                        }
                        @Override public boolean cancelled() { return mCancel; }
                    });
                if (mCancel) error = t("Cancelled.");
            } catch (Throwable e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
                // the details for a bug report, in logs/
                try (java.io.PrintWriter w = new java.io.PrintWriter(new java.io.FileWriter(
                        new File(CrashLog.dir(this), "language-build.log")))) {
                    e.printStackTrace(w);
                } catch (Exception ignored) {}
            }
            final String err = error;
            runOnUiThread(() -> {
                mBusy = false;
                bar.setVisibility(View.GONE);
                btnClose.setEnabled(true);
                refresh();
                if (err != null) {
                    message(t("Building the language packs failed"), err + "\n\n" + t("Details: logs/language-build.log"));
                } else {
                    askDelete(pal);
                }
            });
        }, "language-packs").start();
    }

    private void askDelete(File pal) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(t("Language packs ready"))
            .setMessage(t("Français, Deutsch, Español and Italiano are installed. Choose one in Settings → GAME → "
                + "Language.\n\nThe packs do not need the European disc any more. Delete %s (%.2f GB) now?",
                pal.getName(), pal.length() / 1e9))
            .setPositiveButton(t("Delete it"), (d, w) -> {
                pal.delete();
                refresh();
            })
            .setNegativeButton(t("Keep it"), null)
            .show();
    }

    private void message(String title, String text) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title).setMessage(text).setPositiveButton(t("OK"), null).show();
    }
}
