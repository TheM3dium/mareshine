package io.github.them3dium.mareshine;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;

import static io.github.them3dium.mareshine.L.t;

// Updates from the project's GitHub releases: the latest release's tag is
// compared with this version; a newer one is offered with its notes, its APK
// is downloaded, checked (size, same signing key) and handed to Android's
// package installer, which asks the player to confirm.
//
// The automatic check runs once each time the app starts and can be
// turned off (Settings -> OTHER -> UPDATES, settings.txt SMS_UPDATE_CHECK).
final class Updates {
    static final String REPO = "TheM3dium/mareshine";
    static final String SETTING = "SMS_UPDATE_CHECK";
    private static final String PREFS = "updates";

    private Updates() {}

    static final class Release {
        String tag, name, notes, page, apkUrl;
        long apkSize;
    }

    interface Result { void done(Release newer, String error); }

    static boolean autoEnabled(Context c) {
        return !"0".equals(Settings.read(c).getOrDefault(SETTING, "1"));
    }

    // From the launcher: each time the app starts (once per start, not each
    // time the launcher shows again), unless turned off; a newer release that
    // was not ignored goes to `found` (the launcher's "new update" box). The
    // last one found is kept, so the box is back when the launcher returns.
    private static boolean sChecked;

    static void autoCheck(Activity a, java.util.function.Consumer<Release> found) {
        if (!autoEnabled(a)) return;
        SharedPreferences p = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Release known = found(a);
        if (known != null) found.accept(known);
        if (sChecked) return;
        sChecked = true;
        check(a, (r, err) -> {
            if (err != null) { sChecked = false; return; } // offline: try again the next time
            remember(a, r);
            if (r == null || r.tag.equals(p.getString("skip", ""))) return;
            if (!a.isFinishing() && !a.isDestroyed()) found.accept(r);
        });
    }

    private static Release sFound;

    // the newer release last found, kept until it is installed or ignored, so
    // the settings can say "New update!" after the app restarts too
    private static void remember(Context c, Release r) {
        sFound = r;
        SharedPreferences.Editor e = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if (r == null) e.remove("found");
        else {
            try {
                e.putString("found", new JSONObject().put("tag", r.tag).put("name", r.name).put("notes", r.notes)
                    .put("page", r.page).put("apk", r.apkUrl).put("size", r.apkSize).toString());
            } catch (Exception ignored) {}
        }
        e.apply();
    }

    // a newer release found earlier and not ignored, or null
    static Release found(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Release r = sFound;
        if (r == null) {
            try {
                JSONObject j = new JSONObject(p.getString("found", ""));
                r = new Release();
                r.tag = j.getString("tag"); r.name = j.optString("name", r.tag); r.notes = j.optString("notes", "");
                r.page = j.optString("page"); r.apkUrl = j.optString("apk"); r.apkSize = j.optLong("size", -1);
            } catch (Exception e) {
                return null;
            }
        }
        if (r.tag.equals(p.getString("skip", "")) || compare(r.tag, CrashLog.BuildConfigInfo.version(c)) <= 0) return null;
        return r;
    }

    static String version(Release r) { return r.tag.replaceFirst("^[vV]", ""); }

    // From the settings: always asks GitHub and says what it found.
    static void manualCheck(Activity a) {
        Toast.makeText(a, t("Checking for updates…"), Toast.LENGTH_SHORT).show();
        check(a, (r, err) -> {
            if (err == null) remember(a, r);
            if (a.isFinishing() || a.isDestroyed()) return;
            if (err != null) {
                Toast.makeText(a, t("Could not check for updates: %s", err), Toast.LENGTH_LONG).show();
            } else if (r == null) {
                Toast.makeText(a, t("Mareshine is up to date (%s).", CrashLog.BuildConfigInfo.version(a)),
                    Toast.LENGTH_LONG).show();
            } else {
                ask(a, r, null);
            }
        });
    }

    private static void check(Context c, Result result) {
        Handler main = new Handler(Looper.getMainLooper());
        String current = CrashLog.BuildConfigInfo.version(c);
        new Thread(() -> {
            Release newer = null;
            String error = null;
            try {
                Release r = latest();
                // a beta ("2.1.0-beta.1") is not offered: this is the stable version a beta goes back to
                if (r != null && !r.tag.contains("-") && compare(r.tag, current) > 0 && r.apkUrl != null) newer = r;
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
            final Release n = newer;
            final String err = error;
            main.post(() -> result.done(n, err));
        }, "UpdateCheck").start();
    }

    private static Release latest() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(
            "https://api.github.com/repos/" + REPO + "/releases/latest").openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "Mareshine updater");
        try {
            if (c.getResponseCode() == 404) return null; // no release yet
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            StringBuilder sb = new StringBuilder();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
            }
            JSONObject j = new JSONObject(sb.toString());
            Release r = new Release();
            r.tag = j.optString("tag_name", "");
            r.name = j.optString("name", r.tag);
            r.notes = j.optString("body", "");
            r.page = j.optString("html_url", "https://github.com/" + REPO + "/releases");
            JSONArray assets = j.optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject as = assets.getJSONObject(i);
                if (as.optString("name").toLowerCase().endsWith(".apk")) {
                    r.apkUrl = as.optString("browser_download_url");
                    r.apkSize = as.optLong("size", -1);
                    break;
                }
            }
            return r;
        } finally {
            c.disconnect();
        }
    }

    // "v1.2.0" against "1.1.0": numbers part by part, missing parts are 0
    static int compare(String a, String b) {
        String[] x = a.replaceFirst("^[vV]", "").split("[^0-9]+"), y = b.replaceFirst("^[vV]", "").split("[^0-9]+");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length && !x[i].isEmpty() ? Integer.parseInt(x[i]) : 0;
            int q = i < y.length && !y[i].isEmpty() ? Integer.parseInt(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    // The release notes' Markdown as plain text: links as their words, no
    // heading marks or emphasis, wrapped lines joined into paragraphs.
    static String plainNotes(String md) {
        String t = md.replace("\r", "")
            .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
            .replaceAll("(?m)^#+\\s*", "")
            .replaceAll("\\*\\*|__|`|(?<![A-Za-z0-9])\\*|\\*(?![A-Za-z0-9])", "");
        StringBuilder out = new StringBuilder();
        for (String para : t.split("\n\\s*\n")) {
            String p = para.trim();
            if (p.isEmpty()) continue;
            // lists keep their lines, other paragraphs are one line
            if (!p.matches("(?s)^[-•].*")) p = p.replaceAll("\\s*\n\\s*", " ");
            if (out.length() > 0) out.append("\n\n");
            out.append(p);
        }
        return out.toString();
    }

    // "Do you want to update?": Yes downloads and installs, Ignore skips this
    // version (no more box for it) and runs `ignored`.
    static void ask(Activity a, Release r, Runnable ignored) {
        String notes = plainNotes(r.notes);
        if (notes.length() > 1200) notes = notes.substring(0, 1200) + "…";
        new DlDialog(a)
            .setTitle(t("Update to version %s?", version(r)))
            .setMessage(t("You have version %s.", CrashLog.BuildConfigInfo.version(a)) + (notes.isEmpty() ? "" : "\n\n" + notes))
            .setPositiveButton(t("Yes"), (d, w) -> download(a, r))
            .setNegativeButton(t("Ignore"), (d, w) -> {
                a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("skip", r.tag).apply();
                if (ignored != null) ignored.run();
            })
            .show();
    }

    private static void download(Activity a, Release r) {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            new DlDialog(a)
                .setTitle(t("Allow installing updates"))
                .setMessage(t("Android needs your permission for Mareshine to install its updates. Allow it on the "
                    + "next screen, then come back and choose Yes again."))
                .setPositiveButton(t("Open settings"), (d, w) -> a.startActivity(new Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + a.getPackageName()))))
                .setNegativeButton(t("Cancel"), null)
                .show();
            return;
        }
        ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        TextView label = new TextView(a);
        label.setText(t("Downloading %s…", r.name));
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * a.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(label);
        box.addView(bar);
        final boolean[] cancelled = {false};
        android.app.Dialog dialog = new DlDialog(a)
            .setTitle(t("Update"))
            .setView(box)
            .setNegativeButton(t("Cancel"), (d, w) -> cancelled[0] = true)
            .setCancelable(false)
            .show();
        Handler main = new Handler(Looper.getMainLooper());
        File dir = new File(a.getCacheDir(), "update");
        dir.mkdirs();
        File apk = new File(dir, "Mareshine.apk");
        new Thread(() -> {
            String error = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(r.apkUrl).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", "Mareshine updater");
                long total = r.apkSize > 0 ? r.apkSize : c.getContentLengthLong();
                long got = 0;
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[1 << 16];
                    int n, last = -1;
                    while ((n = in.read(buf)) > 0) {
                        if (cancelled[0]) throw new Exception("cancelled");
                        out.write(buf, 0, n);
                        got += n;
                        int pct = total > 0 ? (int) (got * 100 / total) : 0;
                        if (pct != last) { last = pct; final int p = pct; main.post(() -> bar.setProgress(p)); }
                    }
                } finally {
                    c.disconnect();
                }
                if (r.apkSize > 0 && got != r.apkSize) throw new Exception(t("the download is incomplete"));
                if (!sameSigner(a, apk)) throw new Exception(t("the update is not signed with this app's key"));
            } catch (Exception e) {
                error = e.getMessage();
            }
            final String err = error;
            main.post(() -> {
                if (dialog.isShowing()) dialog.dismiss();
                if (cancelled[0]) { apk.delete(); return; }
                if (err != null) {
                    apk.delete();
                    new DlDialog(a)
                        .setTitle(t("Update failed"))
                        .setMessage(t("The update could not be installed: %s\n\nYou can also download it from %s",
                            err, r.page))
                        .setPositiveButton(t("OK"), null)
                        .show();
                } else {
                    install(a, apk);
                }
            });
        }, "UpdateDownload").start();
    }

    // the downloaded APK is signed with the same key as this app, so Android
    // would accept it as an update (a debug build cannot take a release)
    @SuppressWarnings("deprecation")
    private static boolean sameSigner(Context c, File apk) {
        try {
            PackageManager pm = c.getPackageManager();
            int flag = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo mine = pm.getPackageInfo(c.getPackageName(), flag);
            PackageInfo theirs = pm.getPackageArchiveInfo(apk.getPath(), flag);
            if (theirs == null || !c.getPackageName().equals(theirs.packageName)) return false;
            Signature[] x, y;
            if (Build.VERSION.SDK_INT >= 28) {
                if (mine.signingInfo == null || theirs.signingInfo == null) return false;
                x = mine.signingInfo.getApkContentsSigners();
                y = theirs.signingInfo.getApkContentsSigners();
            } else {
                x = mine.signatures;
                y = theirs.signatures;
            }
            return x != null && y != null && Arrays.equals(x, y);
        } catch (Exception e) {
            return false;
        }
    }

    private static void install(Activity a, File apk) {
        try {
            PackageInstaller pi = a.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(a.getPackageName());
            int id = pi.createSession(params);
            try (PackageInstaller.Session s = pi.openSession(id)) {
                try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("mareshine.apk", 0, apk.length())) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    s.fsync(out);
                }
                Intent done = new Intent(a, InstallResult.class);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                s.commit(PendingIntent.getBroadcast(a, id, done, flags).getIntentSender());
            }
        } catch (Exception e) {
            Toast.makeText(a, t("The update could not be installed: %s", e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    // The installer's answer: Android's confirmation screen when it needs the
    // player's yes, a message when it failed.
    public static final class InstallResult extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    c.startActivity(confirm);
                }
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Toast.makeText(c, t("The update could not be installed: %s", msg != null ? msg : "?"),
                    Toast.LENGTH_LONG).show();
            }
        }
    }
}
