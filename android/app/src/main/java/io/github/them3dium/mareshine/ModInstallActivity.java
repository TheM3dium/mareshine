package io.github.them3dium.mareshine;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

import static io.github.them3dium.mareshine.L.t;

// Installs a mod release (Mods.Release) from the player's own disc: checks the
// disc, downloads the release (it goes on where it stopped), checks it,
// applies its patch straight out of the archive (Vcdiff) into the mod's own
// disc image, checks that, and switches the mod on. Nothing of the game is
// downloaded: the patch only turns the player's disc into the mod's. A
// translation (Mods.Release.base) comes from a file the player downloaded
// and picked (EXTRA_URI): it is recognised by its size and checked the same way.
public class ModInstallActivity extends AppActivity {
    static final String EXTRA_ID = "release", EXTRA_URI = "uri";

    private TextView tvStatus;
    private ProgressBar bar;
    private Button btnCancel;
    private volatile boolean mCancel;
    private Thread mWorker;

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    static void start(Context ctx, Mods.Release r) {
        ctx.startActivity(new Intent(ctx, ModInstallActivity.class).putExtra(EXTRA_ID, r.id));
    }

    // a picked file: a translation, or a mod downloaded by hand (Mods.pickedOfSize)
    static void start(Context ctx, Mods.Release r, Uri picked) {
        ctx.startActivity(new Intent(ctx, ModInstallActivity.class).putExtra(EXTRA_ID, r.id)
            .putExtra(EXTRA_URI, picked.toString()));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        L.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Mods.Release r = Mods.release(getIntent().getStringExtra(EXTRA_ID));
        if (r == null) { finish(); return; }
        String picked = getIntent().getStringExtra(EXTRA_URI);
        Uri uri = picked != null ? Uri.parse(picked) : null;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0A1B30);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.dl_panel);
        panel.setPadding(dp(32), dp(24), dp(32), dp(24));
        root.addView(panel, new FrameLayout.LayoutParams(dp(640), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        TextView title = new TextView(this);
        title.setText((r.name + " " + r.version).toUpperCase(Locale.getDefault()));
        title.setTextColor(0xFFFFC531);
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        title.setTextSize(22);
        panel.addView(title);
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

        mWorker = new Thread(() -> run(r, uri), "mod-install");
        mWorker.start();
    }

    @Override
    public void onBackPressed() { btnCancel.performClick(); }

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
            }
        });
    }

    private static String gb(long n) { return String.format(Locale.ROOT, "%.2f GB", n / 1e9); }

    private static String mb(long n) { return String.format(Locale.ROOT, "%.0f MB", n / 1e6); }

    // --- the job ------------------------------------------------------------------

    private void run(Mods.Release r, Uri picked) {
        // Super Mario Eclipse's languages side by side in its folder (EclipseMod);
        // another mod in a folder of its own
        File mods = Mods.dir(this), dl = new File(mods, ".downloads");
        File dest = r.isEclipse() ? EclipseMod.dir(this) : new File(mods, r.id);
        File archive = new File(dl, r.file), part = new File(dl, r.file + ".part");
        File stage = new File(mods, "." + r.id + ".tmp");
        try {
            // the player's disc: the one the patch is made for
            File iso = LauncherActivity.findIsoFile(this);
            if (iso == null) {
                done(false, t("No disc image found. Pick your Super Mario Sunshine disc image in the launcher first."));
                return;
            }
            String md5 = md5(iso, t("Checking your disc image…"));
            if (md5 == null) { done(false, t("Cancelled.")); return; }
            if (!r.sourceMd5.equalsIgnoreCase(md5)) {
                done(false, t("%s is made for the North American disc (GMSE01, the unmodified 1:1 ISO), and yours is "
                    + "another (MD5 %s). Compressed or trimmed images (CISO, NKit, RVZ) do not work.", r.name, md5));
                return;
            }
            dl.mkdirs();
            // a game kept as files (ModGame): the image for a moment, and the files it changes
            long need = r.imageSize + (r.overlay ? r.imageSize / 3 : 0) + (picked != null || archive.length() == r.size ? 0 : r.size - part.length())
                + (200L << 20);
            if (mods.getUsableSpace() < need) {
                done(false, t("Not enough free space in %s: about %s is needed, %s is free.", mods.getAbsolutePath(),
                    gb(need), gb(mods.getUsableSpace())));
                return;
            }
            if (picked == null) {
                if (archive.length() != r.size) download(r, part, archive);
                if (mCancel) { done(false, t("Cancelled. The download continues next time from where it stopped.")); return; }
            }
            String got;
            try (InputStream in = open(picked, archive)) {
                got = md5(in, picked != null ? r.size : archive.length(), t("Checking the download…"));
            }
            if (got == null) { done(false, t("Cancelled.")); return; }
            if (!r.md5.equalsIgnoreCase(got)) {
                if (picked != null) {
                    done(false, t("This file is not %s %s (MD5 %s). Download it again from its page.", r.name,
                        r.version, got));
                    return;
                }
                archive.delete();
                done(false, t("The download is damaged (MD5 %s). It was deleted; please try again.", got));
                return;
            }

            // the patch, straight out of the archive, onto the player's disc
            TexturePackActivity.remove(stage);
            stage.mkdirs();
            File image = new File(stage, r.image);
            String result = null;
            try (ParcelFileDescriptor pfd = picked != null ? getContentResolver().openFileDescriptor(picked, "r") : null;
                 FileInputStream pin = pfd != null ? new FileInputStream(pfd.getFileDescriptor()) : null;
                 SevenZFile z = pin != null ? SevenZFile.builder().setSeekableByteChannel(pin.getChannel()).get()
                     : SevenZFile.builder().setFile(archive).get()) {
                for (SevenZArchiveEntry e; (e = z.getNextEntry()) != null; ) {
                    if (!e.getName().replace('\\', '/').equals(r.patch)) continue;
                    InputStream in = new InputStream() {
                        @Override public int read() throws IOException { return z.read(); }
                        @Override public int read(byte[] b, int off, int len) throws IOException { return z.read(b, off, len); }
                    };
                    long[] last = {0};
                    result = Vcdiff.apply(iso, new java.io.BufferedInputStream(in, 1 << 20), image, written -> {
                        long now = System.currentTimeMillis();
                        if (now - last[0] > 250) {
                            last[0] = now;
                            status(t("Making %s from your disc… %s of %s", r.name, mb(written), mb(r.imageSize))
                                + pct(written, r.imageSize));
                            progress(Math.min(1.0, (double) written / r.imageSize));
                        }
                        return !mCancel;
                    });
                    break;
                }
            }
            if (result == null) throw new IOException(t("The download has no %s.", r.patch));
            if (!r.resultMd5.equalsIgnoreCase(result)) {
                TexturePackActivity.remove(stage);
                done(false, t("The patched image is not the expected one (MD5 %s). Nothing was installed.", result));
                return;
            }

            // in place, described, picked and switched on
            if (r.overlay) {
                // only the files that differ from the player's disc, then the image goes
                File files = new File(stage, "files");
                long[] last = {0};
                ModGame.extract(iso, image, files, (done, total) -> {
                    long now = System.currentTimeMillis();
                    if (now - last[0] > 250) {
                        last[0] = now;
                        status(t("Keeping the files %s changes…", r.name) + pct(done, total));
                        progress((double) done / total);
                    }
                    return !mCancel;
                });
                status(t("Installing…"));
                dest.mkdirs();
                TexturePackActivity.remove(new File(dest, "files"));
                if (!files.renameTo(new File(dest, "files"))) throw new IOException("cannot move the files to " + dest);
                installed(r, dest, image, ModGame.discId(image));
                TexturePackActivity.remove(stage);
                if (picked == null) archive.delete();
            } else {
                status(t("Installing…"));
                dest.mkdirs();
                File target = new File(dest, r.image);
                target.delete();
                if (!image.renameTo(target)) throw new IOException("cannot move the image to " + dest);
                TexturePackActivity.remove(stage);
                if (picked == null) archive.delete();
                if (r.isEclipse()) EclipseMod.installed(this, r);
                else installed(r, dest, target, null);
            }
            String msg = t("%s is installed and switched on. Play starts it; switch it off in Mods to play Super Mario "
                + "Sunshine again.", r.name);
            if (picked != null) msg += "\n\n" + t("The downloaded file is not needed any more and can be deleted.");
            done(true, msg);
        } catch (Exception e) {
            TexturePackActivity.remove(stage);
            done(false, t(mCancel ? "Cancelled: %s" : "Failed: %s", e.getMessage()));
        }
    }

    private void download(Mods.Release r, File part, File archive) throws IOException {
        IOException last = null;
        for (String start : r.urls) {
            try {
                downloadFrom(start, r, part);
                if (mCancel) return;
                if (part.length() == r.size) {
                    if (!part.renameTo(archive)) throw new IOException("cannot rename the download");
                    return;
                }
                last = new IOException("download incomplete (" + mb(part.length()) + " of " + mb(r.size) + ")");
            } catch (IOException e) {
                last = e;  // the next source
            }
        }
        throw last != null ? last : new IOException("no download source");
    }

    private void downloadFrom(String url, Mods.Release r, File part) throws IOException {
        for (int redirects = 0; redirects < 8; redirects++) {
            long from = part.length();
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "Mareshine mod installer");
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            if (from > 0) c.setRequestProperty("Range", "bytes=" + from + "-");
            int code = c.getResponseCode();
            if (code / 100 == 3) {
                url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            if (code == 416 && from == r.size) return;  // already complete
            if (code != 200 && code != 206) throw new IOException("HTTP " + code);
            boolean append = code == 206;
            if (!append) from = 0;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part, append)) {
                byte[] buf = new byte[1 << 16];
                long got = from, last = 0;
                for (int n; (n = in.read(buf)) > 0; ) {
                    if (mCancel) return;
                    out.write(buf, 0, n);
                    got += n;
                    long now = System.currentTimeMillis();
                    if (now - last > 250) {
                        last = now;
                        status(t("Downloading %s… %s of %s", r.name, mb(got), mb(r.size)) + pct(got, r.size));
                        progress((double) got / r.size);
                    }
                }
            } finally {
                c.disconnect();
            }
            return;
        }
        throw new IOException("too many redirects");
    }

    // A mod of its own: its mod.json (its page, the mods it does not work
    // with, its disc's banner as its label), an older version's image gone, on.
    // discId: a game kept as files (ModGame), its image only read here.
    private void installed(Mods.Release r, File dir, File image, String discId) throws Exception {
        org.json.JSONObject o = new org.json.JSONObject().put("id", r.id).put("name", r.name)
            .put("version", r.version).put("author", r.author)
            .put("description", r.about.isEmpty() ? r.description : r.about)
            .put("code", r.code).put("page", r.page);
        if (discId != null) o.put("game", true).put("disc_id", discId);
        else o.put("image", r.image);
        if (r.rating >= 0) o.put("rating", r.rating);
        if (!r.preview.isEmpty()) o.put("preview", r.preview);
        if (r.incompatible.length > 0) o.put("incompatible", new org.json.JSONArray(java.util.Arrays.asList(r.incompatible)));
        o.put("options", new org.json.JSONArray().put(new org.json.JSONObject().put("type", "link")
            .put("label", "Website").put("url", r.page)));
        // its disc when the app ships one (Sunburn), else its disc's banner
        File disc = new File(dir, "disc.png"), icon = new File(dir, "icon.png");
        if (r.icon.startsWith("asset:")) {
            try (java.io.InputStream in = getAssets().open(r.icon.substring(6)); OutputStream out = new FileOutputStream(disc)) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            } catch (IOException ex) {
                disc.delete();
            }
        }
        if (!disc.isFile()) {
            android.graphics.Bitmap b = EclipseMod.banner(image);
            if (b != null) try (OutputStream out = new FileOutputStream(icon)) {
                b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            }
        }
        if (disc.isFile()) o.put("icon", "disc.png");
        else if (icon.isFile()) o.put("icon", "icon.png");
        File[] old = dir.listFiles();
        if (old != null)
            for (File f : old)
                if (f.getName().toLowerCase(Locale.ROOT).endsWith(".iso") && (discId != null || !f.getName().equals(r.image))) f.delete();
        try (OutputStream out = new FileOutputStream(new File(dir, "mod.json"))) {
            out.write(o.toString(2).getBytes("UTF-8"));
        }
        Mods.setEnabled(this, r.id, true);
    }

    private InputStream open(Uri picked, File archive) throws IOException {
        if (picked == null) return new FileInputStream(archive);
        InputStream in = getContentResolver().openInputStream(picked);
        if (in == null) throw new IOException("cannot open " + picked);
        return in;
    }

    // a file's MD5, with its progress shown; null when cancelled
    private String md5(File f, String what) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            return md5(in, f.length(), what);
        }
    }

    private String md5(InputStream in, long size, String what) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        long total = Math.max(1, size), done = 0, last = 0;
        byte[] buf = new byte[1 << 20];
        for (int n; (n = in.read(buf)) > 0; ) {
            if (mCancel) return null;
            md.update(buf, 0, n);
            done += n;
            long now = System.currentTimeMillis();
            if (now - last > 250) {
                last = now;
                status(what + pct(done, total));
                progress((double) done / total);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }
}
