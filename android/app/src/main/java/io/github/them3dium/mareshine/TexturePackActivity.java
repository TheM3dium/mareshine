package io.github.them3dium.mareshine;

import android.app.Activity;
import android.content.Context;
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

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static io.github.them3dium.mareshine.L.t;

// Downloads and installs a texture pack into textures/ in the data folder, the
// way the PC port's launcher and tools/mods/get.py do:
//   1080p  qashto's UHD pack, GMS_1080.zip  -> textures/GMS
//   4K     qashto's UHD pack, GMS.7z        -> textures/GMS (from GMS/Textures/GMS)
//   extras chasem-dev's HD texture extras   -> textures/sms-hd-texture-extras
// A download resumes after an interruption; the old pack is replaced only once
// the new one is unpacked. textures/GMS/.sms-pack says which UHD pack it is.
public class TexturePackActivity extends AppActivity {
    public static final String EXTRA_JOB = "job";
    public static final String JOB_1080 = "1080", JOB_4K = "4k", JOB_EXTRAS = "extras";

    static final class Pack {
        final String url, file, md5, installDir, label;
        final long size, unpacked;

        Pack(String label, String url, String file, long size, long unpacked, String md5, String installDir) {
            this.label = label;
            this.url = url;
            this.file = file;
            this.size = size;
            this.unpacked = unpacked;
            this.md5 = md5;
            this.installDir = installDir;
        }
    }

    // pinned like the PC port (sizes and MD5s of the releases)
    static final Pack P1080 = new Pack("HD texture pack (1080p)",
        "https://github.com/qashto/Super_Mario_Sunshine_UHD_Texture_Pack/releases/download/2.1.1/GMS_1080.zip",
        "GMS_1080.zip", 155892294L, 684047357L, null, "GMS");
    static final Pack P4K = new Pack("Ultra HD texture pack (4K)",
        "https://github.com/qashto/Super_Mario_Sunshine_UHD_Texture_Pack/releases/download/2.1.1/GMS.7z",
        "GMS.7z", 986431331L, 3218563135L, "274985dba9214b857b3e5605d2ee49b2", "GMS");
    static final Pack EXTRAS = new Pack("HD texture extras v1.1.1",
        "https://github.com/chasem-dev/sms-hd-texture-extras/releases/download/v1.1.1/sms-hd-texture-extras-1.1.1.zip",
        "sms-hd-texture-extras-1.1.1.zip", 64196311L, 64158183L, "a658c43cce0751e585e991f8563e5f41",
        "sms-hd-texture-extras");

    static final String PACK_MARKER = ".sms-pack";    // in textures/GMS: "1080" or "4k"
    static final String EXTRAS_MARKER = ".release";   // in textures/sms-hd-texture-extras: the MD5

    // --- what is installed ----------------------------------------------------

    /** "1080", "4k", "custom" (files there, not ours) or null. */
    static String installedPack(Context ctx) {
        File gms = new File(DataDir.texturesDir(ctx), "GMS");
        File marker = new File(gms, PACK_MARKER);
        if (marker.isFile()) {
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(marker))) {
                String v = r.readLine();
                if (v != null) return v.trim();
            } catch (IOException ignored) {}
        }
        return hasFiles(DataDir.texturesDir(ctx), "sms-hd-texture-extras") ? "custom" : null;
    }

    static boolean extrasInstalled(Context ctx) {
        return new File(new File(DataDir.texturesDir(ctx), EXTRAS.installDir), EXTRAS_MARKER).isFile();
    }

    // any texture under textures/ outside the given folder (a pack the player copied in)
    private static boolean hasFiles(File dir, String except) {
        File[] fs = dir.listFiles();
        if (fs == null) return false;
        for (File f : fs) {
            if (f.getName().startsWith(".") || f.getName().equals(except)) continue;
            if (f.isFile() && (f.getName().endsWith(".png") || f.getName().endsWith(".dds"))) return true;
            if (f.isDirectory() && hasFiles(f, null)) return true;
        }
        return false;
    }

    static void remove(File f) {
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) for (File c : fs) remove(c);
        }
        f.delete();
    }

    // --- the screen -------------------------------------------------------------

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
        String job = getIntent().getStringExtra(EXTRA_JOB);
        Pack pack = JOB_4K.equals(job) ? P4K : JOB_EXTRAS.equals(job) ? EXTRAS : P1080;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0A1B30);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.dl_panel);
        panel.setPadding(dp(32), dp(24), dp(32), dp(24));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(640), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(panel, plp);
        tvTitle = new TextView(this);
        tvTitle.setText(t(pack.label).toUpperCase(Locale.getDefault()));
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
                status(t("Cancelling… (the download continues next time from where it stopped)"));
            } else {
                finish();
            }
        });
        setContentView(root);
        btnCancel.requestFocus();

        mWorker = new Thread(() -> run(pack), "texture-pack");
        mWorker.start();
    }

    @Override
    public void onBackPressed() {
        btnCancel.performClick();
    }

    private void status(String s) { runOnUiThread(() -> tvStatus.setText(s)); }

    private void progress(double f) { runOnUiThread(() -> bar.setProgress((int) Math.round(f * 1000))); }

    private void done(boolean ok, String msg) {
        runOnUiThread(() -> {
            tvStatus.setText(msg);
            btnCancel.setEnabled(true);
            btnCancel.setText(t(ok ? "Done" : "Close"));
            if (ok) {
                bar.setProgress(1000);
                setResult(RESULT_OK);
                // installed: the message shows a moment, then back on its own
                tvStatus.postDelayed(() -> { if (!isFinishing()) finish(); }, 1500);
            }
        });
    }

    // --- the job ------------------------------------------------------------------

    private static String gb(long n) { return String.format(Locale.ROOT, "%.2f GB", n / 1e9); }
    private static String mb(long n) { return String.format(Locale.ROOT, "%.0f MB", n / 1e6); }

    private void run(Pack pack) {
        File textures = DataDir.texturesDir(this);
        File dl = new File(textures, ".downloads");
        dl.mkdirs();
        File archive = new File(dl, pack.file), part = new File(dl, pack.file + ".part");
        try {
            long have = archive.length() + part.length();
            long need = pack.unpacked + (archive.length() == pack.size ? 0 : pack.size - have) + (200L << 20);
            if (textures.getUsableSpace() < need) {
                done(false, t("Not enough free space in %s: about %s is needed, %s is free.", textures.getAbsolutePath(), gb(need), gb(textures.getUsableSpace())));
                return;
            }
            if (archive.length() != pack.size) download(pack, part, archive);
            if (mCancel) { done(false, t("Cancelled. The download continues next time from where it stopped.")); return; }

            if (pack.md5 != null) {
                status(t("Checking the download…"));
                String md5 = md5(archive);
                if (!pack.md5.equalsIgnoreCase(md5)) {
                    archive.delete();
                    done(false, t("The download is damaged (MD5 %s). It was deleted; please try again.", md5));
                    return;
                }
            }

            File stage = new File(dl, "stage-" + pack.installDir);
            remove(stage);
            stage.mkdirs();
            if (pack == P4K) unpack7z(archive, stage, "GMS/Textures/GMS/");
            else if (pack == EXTRAS) unzip(archive, stage, pack.unpacked);
            else unzip(archive, stage, pack.unpacked);
            if (mCancel) { remove(stage); done(false, t("Cancelled.")); return; }

            // swap it in
            File target = new File(textures, pack.installDir);
            File staged = pack == EXTRAS ? stage : stage;
            status(t("Installing…"));
            remove(target);
            if (!staged.renameTo(target)) throw new IOException("cannot move the pack to " + target);
            try (FileWriter w = new FileWriter(new File(target, pack == EXTRAS ? EXTRAS_MARKER : PACK_MARKER))) {
                w.write(pack == EXTRAS ? pack.md5 : (pack == P4K ? "4k" : "1080"));
            }
            archive.delete();
            // the pack is meant to be used: turn HD textures on
            Settings.put(this, "SMS_HD_TEXTURES", "1");
            done(true, t("%s installed (%s).\n\nIt is used the next time the game starts.", t(pack.label), gb(pack.unpacked)));
        } catch (Exception e) {
            done(false, t(mCancel ? "Cancelled: %s" : "Failed: %s", e.getMessage()));
        }
    }

    private void download(Pack pack, File part, File archive) throws IOException {
        String url = pack.url;
        for (int redirects = 0; redirects < 8; redirects++) {
            long from = part.length();
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "sms-android texture installer");
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            if (from > 0) c.setRequestProperty("Range", "bytes=" + from + "-");
            int code = c.getResponseCode();
            if (code / 100 == 3) { // GitHub sends the file from another host
                url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            if (code == 416 && from == pack.size) break; // already complete
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
                        status(t("Downloading… %s of %s", mb(got), mb(pack.size)) + pct(got, pack.size));
                        progress((double) got / pack.size);
                    }
                }
            } finally {
                c.disconnect();
            }
            break;
        }
        if (part.length() != pack.size) throw new IOException("download incomplete (" + mb(part.length()) + " of " + mb(pack.size) + ")");
        if (!part.renameTo(archive)) throw new IOException("cannot rename the download");
    }

    private static String md5(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    private void unzip(File archive, File dest, long total) throws IOException {
        long done = 0, last = 0;
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(archive))) {
            ZipEntry e;
            byte[] buf = new byte[1 << 16];
            while ((e = zin.getNextEntry()) != null) {
                if (mCancel) return;
                File out = safeChild(dest, e.getName());
                if (e.isDirectory()) { out.mkdirs(); continue; }
                out.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(out)) {
                    int n;
                    while ((n = zin.read(buf)) > 0) {
                        o.write(buf, 0, n);
                        done += n;
                    }
                }
                long now = System.currentTimeMillis();
                if (now - last > 250) {
                    last = now;
                    status(t("Unpacking… %s of %s", mb(done), mb(total)) + pct(done, total));
                    progress((double) done / total);
                }
            }
        }
    }

    // 7z (LZMA2) with Apache Commons Compress: only the entries under `prefix`,
    // without it
    private void unpack7z(File archive, File dest, String prefix) throws IOException {
        long done = 0, last = 0, total = P4K.unpacked;
        try (SevenZFile z = SevenZFile.builder().setFile(archive).get()) {
            SevenZArchiveEntry e;
            byte[] buf = new byte[1 << 16];
            while ((e = z.getNextEntry()) != null) {
                if (mCancel) return;
                String name = e.getName().replace('\\', '/');
                if (!name.startsWith(prefix) || name.length() == prefix.length()) continue;
                File out = safeChild(dest, name.substring(prefix.length()));
                if (e.isDirectory()) { out.mkdirs(); continue; }
                out.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(out)) {
                    int n;
                    while ((n = z.read(buf)) > 0) {
                        o.write(buf, 0, n);
                        done += n;
                        long now = System.currentTimeMillis();
                        if (now - last > 250) {
                            last = now;
                            status(t("Unpacking… %s of %s (this takes a while)", mb(done), mb(total)) + pct(done, total));
                            progress((double) done / total);
                        }
                    }
                }
            }
        }
    }

    // no "../" escapes out of the target folder
    private static File safeChild(File dir, String name) throws IOException {
        File f = new File(dir, name);
        if (!f.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator) && !f.getCanonicalPath().equals(dir.getCanonicalPath()))
            throw new IOException("bad path in archive: " + name);
        return f;
    }
}
