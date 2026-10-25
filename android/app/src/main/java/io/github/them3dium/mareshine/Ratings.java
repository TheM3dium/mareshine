package io.github.them3dium.mareshine;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

// Players' ratings of mods (tools/ratings-worker): every mod's votes from
// the rating service, cached, and the player's own vote sent to it. A mod
// shows the players' average once it has votes, else its start value
// (mod.json's "rating"). An install is a random id made here; nothing else
// about the player is sent. The player's vote is kept in settings.txt
// (Mods.myRating) and sent again until the service has it.
final class Ratings {
    private Ratings() {}

    // the deployed worker (tools/ratings-worker/README.md); "" while there is none
    static final String URL = "https://mareshine-ratings.them3dium.workers.dev";

    private static final Handler sMain = new Handler(Looper.getMainLooper());
    private static final Map<String, float[]> sVotes = new HashMap<>();  // mod -> {sum, count}
    private static long sFetched;

    // the suns to show: the players' average, else the start value (-1: none)
    static float shown(Mods.Mod m) {
        float[] v;
        synchronized (sVotes) {
            v = sVotes.get(m.id);
        }
        return v != null && v[1] > 0 ? v[0] / v[1] : m.rating;
    }

    static int votes(String id) {
        synchronized (sVotes) {
            float[] v = sVotes.get(id);
            return v != null ? (int) v[1] : 0;
        }
    }

    // Fetches every mod's votes (at most every five minutes) and runs done on
    // the main thread when they changed.
    static void refresh(Context ctx, Runnable done) {
        if (URL.isEmpty() || System.currentTimeMillis() - sFetched < 5 * 60_000) return;
        sFetched = System.currentTimeMillis();
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                JSONObject all = new JSONObject(request("GET", URL + "/ratings", null));
                synchronized (sVotes) {
                    sVotes.clear();
                    for (java.util.Iterator<String> it = all.keys(); it.hasNext(); ) {
                        String id = it.next();
                        JSONObject a = all.getJSONObject(id);
                        sVotes.put(id, new float[] {(float) a.optDouble("sum"), (float) a.optDouble("count")});
                    }
                }
                sendPending(app);
                if (done != null) sMain.post(done);
            } catch (Exception e) {
                sFetched = 0;  // try again next time
            }
        }, "mod-ratings").start();
    }

    // a vote can be changed once an hour (the service holds to that too)
    static final long WAIT_MS = 60 * 60_000;

    // milliseconds until the player may rate this mod again (0: now)
    static long waitLeft(Context ctx, String mod) {
        File f = new File(ctx.getFilesDir(), "ratings_time_" + mod);
        if (!f.isFile()) return 0;
        return Math.max(0, f.lastModified() + WAIT_MS - System.currentTimeMillis());
    }

    // The player's vote: kept, and sent (again later if it does not get through)
    static void rate(Context ctx, String mod, int suns, Runnable done) {
        if (Mods.myRating(ctx, mod) > 0 && waitLeft(ctx, mod) > 0) return;  // the page says how long
        stamp(ctx, mod);
        Mods.setMyRating(ctx, mod, suns);
        setSent(ctx, mod, false);
        if (URL.isEmpty()) return;
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            if (send(app, mod, suns) && done != null) sMain.post(done);
        }, "mod-rate").start();
    }

    private static boolean send(Context ctx, String mod, int suns) {
        try {
            JSONObject body = new JSONObject().put("mod", mod).put("install", install(ctx)).put("suns", suns);
            JSONObject a = new JSONObject(request("POST", URL + "/rate", body.toString()));
            synchronized (sVotes) {
                sVotes.put(mod, new float[] {(float) a.optDouble("sum"), (float) a.optDouble("count")});
            }
            setSent(ctx, mod, true);
            stamp(ctx, mod);  // a vote sent later starts the hour too
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void stamp(Context ctx, String mod) {
        try {
            File f = new File(ctx.getFilesDir(), "ratings_time_" + mod);
            f.createNewFile();
            f.setLastModified(System.currentTimeMillis());
        } catch (Exception ignored) {
        }
    }

    // votes made while the service could not be reached
    private static void sendPending(Context ctx) {
        for (String e : Settings.read(ctx).getOrDefault(Mods.RATINGS_KEY, "").split(";")) {
            int eq = e.indexOf('=');
            if (eq <= 0) continue;
            String mod = e.substring(0, eq);
            if (isSent(ctx, mod)) continue;
            send(ctx, mod, Mods.myRating(ctx, mod));
        }
    }

    // --- the install's id and which votes were sent (the app's own files) -----------

    private static String install(Context ctx) throws Exception {
        File f = new File(ctx.getFilesDir(), "ratings_install");
        if (f.isFile()) {
            String id = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
            if (id.matches("[0-9a-f]{32}")) return id;
        }
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        Files.write(f.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        return sb.toString();
    }

    private static File sentFile(Context ctx, String mod) { return new File(ctx.getFilesDir(), "ratings_sent_" + mod); }

    private static boolean isSent(Context ctx, String mod) { return sentFile(ctx, mod).isFile(); }

    private static void setSent(Context ctx, String mod, boolean sent) {
        File f = sentFile(ctx, mod);
        try {
            if (sent) f.createNewFile();
            else f.delete();
        } catch (Exception ignored) {
        }
    }

    private static String request(String method, String url, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "Mareshine");
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        try (InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream()) {
            if (c.getResponseCode() >= 400) throw new java.io.IOException("HTTP " + c.getResponseCode());
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 14];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
