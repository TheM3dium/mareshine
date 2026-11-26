package io.github.them3dium.mareshine;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// The winter event's music is not in the APK (9 MB): assets/winter-music.json
// lists its tracks (url, SHA-256, size), published as release assets. Turning
// the winter event on (its question, the intro or Settings) fetches them into
// the app's own folder; only files whose SHA-256 matches are kept. Until all are
// there the event runs without music, and the music starts by itself once they
// are. Offline, the next launcher start in the event tries again.
final class WinterMusic {
    private static volatile boolean sRunning;
    private static final Handler sMain = new Handler(Looper.getMainLooper());

    private WinterMusic() {}

    // in the app's own folder on the shared storage (always the app's to write, whatever data folder
    // is chosen, and Android removes it with the app); inside the app only if that is not there
    static File dir(Context c) {
        File ext = c.getExternalFilesDir(null);
        return new File(ext != null ? ext : c.getFilesDir(), "winter-music");
    }

    /** The file of track {@code number} (1, 2, 3: Winter.TRACKS). */
    static File track(Context c, int number) { return new File(dir(c), "winter_music_" + number + ".ogg"); }

    static List<WinterFrame.Item> list(Context c) {
        List<WinterFrame.Item> out = new ArrayList<>();
        try (InputStream in = c.getAssets().open("winter-music.json")) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 14];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            JSONArray a = new JSONObject(b.toString("UTF-8")).getJSONArray("files");
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                WinterFrame.Item it = new WinterFrame.Item();
                it.name = o.getString("name");
                it.url = WinterFrame.base(c, o.getString("url"));
                it.sha256 = o.getString("sha256").toLowerCase(Locale.ROOT);
                it.size = o.optLong("size");
                if (it.name.matches("winter_music_[0-9]+\\.ogg") && it.sha256.length() == 64) out.add(it);
            }
        } catch (Exception ignored) {
            // a build without the list
        }
        return out;
    }

    /** True when every listed track is in place (size checked; the SHA-256 was checked on download). */
    static boolean complete(Context c) {
        List<WinterFrame.Item> l = list(c);
        if (l.isEmpty()) return false;
        for (WinterFrame.Item it : l) {
            File f = new File(dir(c), it.name);
            if (!f.isFile() || (it.size > 0 && f.length() != it.size)) return false;
        }
        return true;
    }

    /** In the background, once at a time: fetch what is missing while the event is on; then the music starts. */
    static void fetchIfNeeded(Context c) {
        Context app = c.getApplicationContext();
        if (sRunning || !Winter.on(Settings.read(app)) || complete(app)) return;
        sRunning = true;
        new Thread(() -> {
            boolean ok = false;
            try {
                ok = fetch(app);
            } finally {
                sRunning = false;
            }
            if (ok) sMain.post(() -> Winter.updateMusic(app));
        }, "winter-music").start();
    }

    /** Blocking. True when all tracks are in place afterwards. */
    static boolean fetch(Context c) {
        File d = dir(c);
        if (!d.isDirectory() && !d.mkdirs()) return false;
        boolean all = true;
        for (WinterFrame.Item it : list(c)) {
            File dst = new File(d, it.name);
            if (it.sha256.equals(WinterFrame.sha256(dst))) continue;
            File part = new File(d, it.name + ".part");
            try {
                WinterFrame.download(it.url, part, it.size);
                if (!it.sha256.equals(WinterFrame.sha256(part))) throw new IOException("SHA-256 differs");
                dst.delete();
                if (!part.renameTo(dst)) throw new IOException("cannot move to " + dst);
                android.util.Log.i("Mareshine", "winter music: " + it.name + " fetched");
            } catch (IOException e) {
                android.util.Log.i("Mareshine", "winter music: " + it.name + " not fetched (" + e.getMessage() + ")");
                part.delete();
                all = false;
            }
        }
        return all;
    }
}
