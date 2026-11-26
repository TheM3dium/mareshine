package io.github.them3dium.mareshine;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// The winter cutscene frame is not in the APK (6.5 MB): assets/winter-frame.json
// lists its images (url, SHA-256, size), published as release assets. Turning
// the winter event on fetches them into cutscenes/winter/ (CutscenePack.frameFor
// takes them from there); only files whose SHA-256 matches are kept. Offline,
// the normal frame stays, and the next launcher start in the event tries again.
final class WinterFrame {
    static final class Item {
        String name, url, sha256;
        long size;
    }

    private static volatile boolean sRunning;

    private WinterFrame() {}

    static File dir(Context c) { return new File(CutscenePack.dir(c), "winter"); }

    static List<Item> list(Context c) {
        List<Item> out = new ArrayList<>();
        try (InputStream in = c.getAssets().open("winter-frame.json")) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 14];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            JSONArray a = new JSONObject(b.toString("UTF-8")).getJSONArray("files");
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Item it = new Item();
                it.name = o.getString("name");
                it.url = base(c, o.getString("url"));
                it.sha256 = o.getString("sha256").toLowerCase(Locale.ROOT);
                it.size = o.optLong("size");
                if (it.name.matches("overlay[A-Za-z0-9_]*\\.png") && it.sha256.length() == 64) out.add(it);
            }
        } catch (Exception ignored) {
            // a build without the list
        }
        return out;
    }

    // Test builds: SMS_PLUGIN_BASE in settings.txt serves the files from
    // elsewhere (a test server or a file:// folder before they are published),
    // as for the mods' plugins; the SHA-256 still decides.
    static String base(Context c, String url) {
        if (!CrashLog.BuildConfigInfo.version(c).contains("dev")) return url;
        String b = Settings.read(c).getOrDefault("SMS_PLUGIN_BASE", "").trim();
        if (b.isEmpty()) return url;
        return (b.endsWith("/") ? b : b + "/") + url.substring(url.lastIndexOf('/') + 1);
    }

    /** True when every listed image is in place (size checked; the SHA-256 was checked on download). */
    static boolean complete(Context c) {
        List<Item> l = list(c);
        if (l.isEmpty()) return false;
        for (Item it : l) {
            File f = new File(dir(c), it.name);
            if (!f.isFile() || (it.size > 0 && f.length() != it.size)) return false;
        }
        return true;
    }

    /** In the background, once at a time: fetch what is missing, when the event is on or the frame style is Winter. */
    static void fetchIfNeeded(Context c) {
        Context app = c.getApplicationContext();
        boolean wanted = Winter.on(Settings.read(app)) || CutscenePack.frameStyle(app).equals("winter");
        if (sRunning || !wanted || complete(app)) return;
        sRunning = true;
        new Thread(() -> {
            try {
                fetch(app);
            } finally {
                sRunning = false;
            }
        }, "winter-frame").start();
    }

    /** Blocking. True when all images are in place afterwards. */
    static boolean fetch(Context c) {
        File d = dir(c);
        if (!d.isDirectory() && !d.mkdirs()) return false;
        boolean all = true;
        for (Item it : list(c)) {
            File dst = new File(d, it.name);
            if (it.sha256.equals(sha256(dst))) continue;
            File part = new File(d, it.name + ".part");
            try {
                download(it.url, part, it.size);
                if (!it.sha256.equals(sha256(part))) throw new IOException("SHA-256 differs");
                dst.delete();
                if (!part.renameTo(dst)) throw new IOException("cannot move to " + dst);
                android.util.Log.i("Mareshine", "winter frame: " + it.name + " fetched");
            } catch (IOException e) {
                android.util.Log.i("Mareshine", "winter frame: " + it.name + " not fetched (" + e.getMessage() + ")");
                part.delete();
                all = false;
            }
        }
        return all;
    }

    static void download(String url, File to, long size) throws IOException {
        long max = size > 0 ? size : 16L << 20;
        for (int redirects = 0; redirects < 8; redirects++) {
            java.net.URLConnection uc = new URL(url).openConnection();
            uc.setConnectTimeout(15000);
            uc.setReadTimeout(20000);
            HttpURLConnection h = uc instanceof HttpURLConnection ? (HttpURLConnection) uc : null;
            try {
                if (h != null) {
                    h.setInstanceFollowRedirects(false);
                    h.setRequestProperty("User-Agent", "Mareshine");
                    int code = h.getResponseCode();
                    if (code / 100 == 3) {
                        url = new URL(new URL(url), h.getHeaderField("Location")).toString();
                        continue;
                    }
                    if (code != 200) throw new IOException("HTTP " + code);
                }
                try (InputStream in = uc.getInputStream(); OutputStream out = new FileOutputStream(to)) {
                    byte[] buf = new byte[1 << 16];
                    long got = 0;
                    for (int n; (n = in.read(buf)) > 0; ) {
                        got += n;
                        if (got > max) throw new IOException("larger than expected");
                        out.write(buf, 0, n);
                    }
                }
                return;
            } finally {
                if (h != null) h.disconnect();
            }
        }
        throw new IOException("too many redirects");
    }

    static String sha256(File f) {
        if (f == null || !f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return CutscenePack.hex(md.digest());
        } catch (Exception e) {
            return null;
        }
    }
}
