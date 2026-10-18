package io.github.them3dium.mareshine;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;

import static io.github.them3dium.mareshine.L.t;

// Installs the PC port's HD cutscene pack (CutscenePack): downloads each
// movie's patch, rebuilds the HD movie from the player's own disc and checks
// it, then deletes the patch, so at most one patch is on the device at a time.
// An interrupted install continues where it stopped (a part-downloaded patch
// resumes; finished movies are kept).
public class CutsceneActivity extends AppActivity {
    public static final String EXTRA_JOB = "job";
    public static final String JOB_INSTALL = "install";
    public static final String JOB_CONVERT = "convert";
    static final String MP4_SETTING = "SMS_HD_MOVIES_MP4"; // the MP4 folder, or 0

    static File mp4Dir(android.content.Context c) { return new File(CutscenePack.dir(c), "mp4"); }

    // the movies the game streams (all but the in-world portal animation)
    static boolean streamed(CutscenePack.Movie m) { return !m.stem().equalsIgnoreCase("EX128x144_q0"); }

    static int mp4Count(android.content.Context c) {
        try {
            int n = 0;
            for (CutscenePack.Movie m : CutscenePack.catalog(c))
                if (streamed(m) && new File(mp4Dir(c), m.stem() + ".mp4").isFile()) n++;
            return n;
        } catch (IOException e) {
            return 0;
        }
    }

    static int streamedCount(android.content.Context c) {
        try {
            int n = 0;
            for (CutscenePack.Movie m : CutscenePack.catalog(c)) if (streamed(m)) n++;
            return n;
        } catch (IOException e) {
            return 20;
        }
    }

    private TextView tvTitle, tvStatus;
    private ProgressBar bar;
    private Button btnCancel;
    private volatile boolean mCancel;
    private Thread mWorker;

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        L.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0A1B30);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.dl_panel);
        panel.setPadding(dp(32), dp(24), dp(32), dp(24));
        root.addView(panel, new FrameLayout.LayoutParams(dp(640), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        tvTitle = new TextView(this);
        tvTitle.setText(t("HD cutscenes").toUpperCase(Locale.getDefault()));
        tvTitle.setTextColor(0xFFFFC531);
        tvTitle.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        tvTitle.setTextSize(22);
        panel.addView(tvTitle);
        tvStatus = new TextView(this);
        tvStatus.setTextColor(0xFFF4F8FF);
        tvStatus.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        tvStatus.setTextSize(17);
        tvStatus.setPadding(0, dp(12), 0, dp(12));
        panel.addView(tvStatus);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFFFC531));
        panel.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)));
        btnCancel = new Button(this);
        btnCancel.setText(t("Cancel"));
        btnCancel.setTextColor(0xFFF4F8FF);
        btnCancel.setBackgroundResource(R.drawable.dl_row);
        btnCancel.setStateListAnimator(null);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.END;
        blp.topMargin = dp(16);
        panel.addView(btnCancel, blp);
        btnCancel.setOnClickListener(v -> {
            if (mWorker != null && mWorker.isAlive()) {
                mCancel = true;
                btnCancel.setEnabled(false);
                status(t("Cancelling… (the installation continues next time from where it stopped)"));
            } else {
                finish();
            }
        });
        setContentView(root);
        btnCancel.requestFocus();

        boolean convert = JOB_CONVERT.equals(getIntent().getStringExtra(EXTRA_JOB));
        if (convert) tvTitle.setText(t("Convert to MP4").toUpperCase(Locale.getDefault()));
        mWorker = new Thread(convert ? this::convert : this::install, "hd-cutscenes");
        mWorker.start();
    }

    @Override
    public void onBackPressed() { btnCancel.performClick(); }

    private void status(String s) { runOnUiThread(() -> tvStatus.setText(s)); }

    private void progress(double f) { runOnUiThread(() -> bar.setProgress((int) Math.round(Math.max(0, Math.min(1, f)) * 1000))); }

    private void done(boolean ok, String msg) {
        runOnUiThread(() -> {
            tvStatus.setText(msg);
            btnCancel.setEnabled(true);
            btnCancel.setText(t(ok ? "Done" : "Close"));
            if (ok) {
                bar.setProgress(1000);
                setResult(RESULT_OK);
            }
        });
    }

    static String gb(long n) { return String.format(Locale.ROOT, "%.2f GB", n / 1e9); }
    static String mb(long n) { return String.format(Locale.ROOT, "%.0f MB", n / 1e6); }

    // --- install --------------------------------------------------------------

    private void install() {
        try {
            List<CutscenePack.Movie> movies = CutscenePack.catalog(this);
            File iso = LauncherActivity.findIsoFile(this);
            if (iso == null || !DiscCheck.checkHeader(iso).ok) {
                done(false, t("The HD cutscenes are made from your own North American disc image (GMSE01). Set it first: Settings → GAME → Disc image."));
                return;
            }
            File dir = CutscenePack.dir(this), dl = CutscenePack.downloadDir(this);
            dir.mkdirs();
            dl.mkdirs();

            // what is still to do, and the room it needs
            long need = 0, biggest = 0, total = 0;
            for (CutscenePack.Movie m : movies) {
                total += m.patchBytes + m.targetBytes;
                if (CutscenePack.installed(this, m)) continue;
                File part = new File(dl, m.patchFile + ".part");
                need += m.targetBytes + Math.max(0, m.patchBytes - part.length() - new File(dl, m.patchFile).length());
                biggest = Math.max(biggest, m.patchBytes);
            }
            need += biggest + (200L << 20);
            if (dir.getUsableSpace() < need) {
                done(false, t("Not enough free space in %s: about %s is needed, %s is free.", dir.getAbsolutePath(), gb(need), gb(dir.getUsableSpace())));
                return;
            }

            long base = 0;
            int i = 0;
            try (LanguagePackBuilder.Disc disc = new LanguagePackBuilder.Disc(iso)) {
                for (CutscenePack.Movie m : movies) {
                    i++;
                    if (mCancel) { done(false, t("Cancelled. The installation continues next time from where it stopped.")); return; }
                    long weight = m.patchBytes + m.targetBytes;
                    if (CutscenePack.installed(this, m)) { base += weight; continue; }
                    final long b0 = base;
                    final double tot = total;
                    final String label = t("Movie %d of %d: %s", i, movies.size(), m.stem());
                    File patch = new File(dl, m.patchFile), part = new File(dl, m.patchFile + ".part");
                    if (!patch.isFile()) download(m, part, patch, label, b0, tot);
                    if (mCancel) { done(false, t("Cancelled. The installation continues next time from where it stopped.")); return; }
                    status(label + "\n" + t("Checking the download…"));
                    String sha = CutscenePack.sha256(patch, cancel());
                    if (!sha.equals(m.patchSha)) {
                        patch.delete();
                        done(false, t("The download of %s is damaged. It was deleted; please try again.", m.patchFile));
                        return;
                    }
                    status(label + "\n" + t("Building the HD movie from your disc…"));
                    CutscenePack.apply(disc, m, patch, new File(CutscenePack.thpDir(this), m.name()), new CutscenePack.Progress() {
                        long last;
                        @Override public void at(long d) {
                            long now = System.currentTimeMillis();
                            if (now - last < 250) return;
                            last = now;
                            progress((b0 + m.patchBytes + d) / tot);
                        }
                        @Override public boolean cancelled() { return mCancel; }
                    });
                    CutscenePack.markChecked(this, m);
                    patch.delete();
                    base += weight;
                    progress(base / tot);
                    android.util.Log.i("Sunshine", "HD cutscene installed: " + m.name());
                }
            }
            CutscenePack.writeMarker(this);
            TexturePackActivity.remove(dl);
            Settings.put(this, CutscenePack.SETTING, CutscenePack.dir(this).getAbsolutePath());
            done(true, t("HD cutscenes installed (%s). They are used the next time the game starts.", gb(CutscenePack.size(CutscenePack.thpDir(this)))));
        } catch (Exception e) {
            done(false, mCancel ? t("Cancelled. The installation continues next time from where it stopped.")
                : t("Failed: %s", e.getMessage()));
        }
    }

    // --- convert to MP4 ---------------------------------------------------------

    private void convert() {
        try {
            List<CutscenePack.Movie> movies = CutscenePack.catalog(this);
            long frames = 0, done = 0;
            for (CutscenePack.Movie m : movies) if (streamed(m)) frames += m.frames;
            File out = mp4Dir(this);
            out.mkdirs();
            if (out.getUsableSpace() < (2L << 30)) {
                done(false, t("Not enough free space in %s: about %s is needed, %s is free.", out.getAbsolutePath(), gb(2L << 30), gb(out.getUsableSpace())));
                return;
            }
            int i = 0, count = streamedCount(this);
            long started = System.currentTimeMillis();
            for (CutscenePack.Movie m : movies) {
                if (!streamed(m)) continue;
                i++;
                if (mCancel) { done(false, t("Cancelled. The conversion continues next time from where it stopped.")); return; }
                File mp4 = new File(out, m.stem() + ".mp4");
                if (mp4.isFile()) { done += m.frames; continue; }
                File thp = new File(CutscenePack.thpDir(this), m.name());
                if (!CutscenePack.installed(this, m)) {
                    done(false, t("%s of the HD pack is missing. Install the HD cutscenes first.", m.name()));
                    return;
                }
                final long base = done, all = frames;
                final String label = t("Movie %d of %d: %s", i, count, m.stem());
                CutsceneConvert.convert(thp, mp4, new CutsceneConvert.Progress() {
                    long last;
                    @Override public void frame(int d, int total) {
                        long now = System.currentTimeMillis();
                        if (now - last < 300) return;
                        last = now;
                        double f = (base + d) / (double) all;
                        long left = f > 0.01 ? (long) ((now - started) / f * (1 - f) / 60000) : -1;
                        status(label + "\n" + t("Frame %d of %d", d, total)
                            + (left >= 0 ? "  ·  " + t("about %d min left", left + 1) : ""));
                        progress(f);
                    }
                    @Override public boolean cancelled() { return mCancel; }
                });
                done += m.frames;
                android.util.Log.i("Sunshine", "HD cutscene converted: " + mp4.getName());
            }
            Settings.put(this, MP4_SETTING, out.getAbsolutePath());
            Settings.put(this, CutscenePack.SETTING, "0");
            done(true, t("The HD cutscenes are converted to MP4 (%s) and play from them now.",
                gb(CutscenePack.size(out))));
        } catch (Exception e) {
            done(false, mCancel ? t("Cancelled. The conversion continues next time from where it stopped.")
                : t("Failed: %s", e.getMessage()));
        }
    }

    private CutscenePack.Progress cancel() {
        return new CutscenePack.Progress() {
            @Override public void at(long d) {}
            @Override public boolean cancelled() { return mCancel; }
        };
    }

    private void download(CutscenePack.Movie m, File part, File patch, String label, long base, double total) throws IOException {
        String url = m.url;
        for (int redirects = 0; redirects < 8; redirects++) {
            long from = part.length();
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "sms-android cutscene installer");
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            if (from > 0) c.setRequestProperty("Range", "bytes=" + from + "-");
            int code = c.getResponseCode();
            if (code / 100 == 3) { // GitHub sends the file from another host
                url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            if (code == 416 && from == m.patchBytes) break; // already complete
            boolean append = code == 206;
            if (code != 200 && code != 206) throw new IOException("HTTP " + code);
            if (!append) from = 0;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part, append)) {
                byte[] buf = new byte[1 << 16];
                long got = from, last = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (mCancel) return;
                    out.write(buf, 0, n);
                    got += n;
                    long now = System.currentTimeMillis();
                    if (now - last > 250) {
                        last = now;
                        status(label + "\n" + t("Downloading… %s of %s", mb(got), mb(m.patchBytes)));
                        progress((base + got) / total);
                    }
                }
            } finally {
                c.disconnect();
            }
            break;
        }
        if (part.length() != m.patchBytes) throw new IOException("download incomplete (" + mb(part.length()) + " of " + mb(m.patchBytes) + ")");
        if (!part.renameTo(patch)) throw new IOException("cannot rename the download");
    }
}
