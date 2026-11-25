package io.github.them3dium.mareshine;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.them3dium.mareshine.L.t;

// Anonymous info about the device, with the player's consent: asked once, then
// sent once after each install and update (to the ratings worker's /device),
// under a random id of its own. On or off in Settings -> OTHER. With the same
// consent the achievements unlocked are sent as they unlock (/achievement:
// which game and id, whether a cheat was on), so ones that never unlock stand
// out. The question was extended for that (CONSENT 2): a player who said yes
// to the first one is asked once more.
final class DeviceInfo {
    static final String KEY = "SMS_DEVICE_INFO";
    private static final String PREFS = "device_info", ASKED = "asked", SENT = "sent_version";
    // the question's version: 2 added the achievements
    private static final String CONSENT = "consent_version", PENDING = "achievements_pending";
    private static final int CONSENT_NOW = 2;
    private static final Pattern RENDERER = Pattern.compile("renderer ([^\\n(]+)");
    private static boolean tried;  // once per start of the app: an unreachable server is not asked again and again

    private DeviceInfo() {}

    // The launcher, each time it shows: the question once there is a game to play, else the sending.
    // true when it asked (a dialog is up)
    static boolean onLauncher(Activity a) {
        if (Ratings.URL.isEmpty()) return false;
        SharedPreferences p = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // asked before the question named the achievements: a yes is asked again, a no stays
        if (p.getBoolean(ASKED, false) && p.getInt(CONSENT, 1) < CONSENT_NOW && !on(a))
            p.edit().putInt(CONSENT, CONSENT_NOW).apply();
        if (!p.getBoolean(ASKED, false) || p.getInt(CONSENT, 1) < CONSENT_NOW) {
            if (LauncherActivity.findIsoFile(a) == null) return false;
            ask(a);
            return true;
        }
        send(a.getApplicationContext());
        flushAchievements(a.getApplicationContext());
        return false;
    }

    private static boolean on(Context c) { return "1".equals(Settings.read(c).get(KEY)); }

    private static void ask(Activity a) {
        new DlDialog(a).setTitle(t("Send anonymous device info?"))
            .setMessage(t("To see which devices and graphics chips have problems, Mareshine can send anonymous info "
                + "about your device once after each install and update: device model, graphics chip, Android and app "
                + "version, a few settings and the active mod. It also sends which achievement you unlock, so "
                + "achievements that do not work stand out. No names and no files, and your IP address is not "
                + "kept. You can change this at any time in Settings, under OTHER."))
            .setCancelable(false)
            .setNegativeButton(t("No"), (d, w) -> answer(a, false))
            .setNeutralButton(t("What is sent?"), (d, w) -> show(a))
            .setPositiveButton(t("Yes"), (d, w) -> answer(a, true))
            .show();
    }

    private static void show(Activity a) {
        String text;
        try {
            text = build(a).toString(2);
        } catch (Exception e) {
            text = "";
        }
        new DlDialog(a).setTitle(t("What is sent?")).setMessage(text)
            .setCancelable(false)
            .setPositiveButton(t("OK"), (d, w) -> ask(a))
            .show();
    }

    private static void answer(Activity a, boolean yes) {
        a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ASKED, true)
            .putInt(CONSENT, CONSENT_NOW).apply();
        Settings.put(a, KEY, yes ? "1" : "0");
        if (yes) send(a.getApplicationContext());
    }

    // In the background; not again for this version once the server has it.
    private static void send(Context c) {
        if (tried || !on(c)) return;
        String version = CrashLog.BuildConfigInfo.version(c);
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (version.equals(p.getString(SENT, null))) return;
        tried = true;
        new Thread(() -> {
            try {
                HttpURLConnection h = (HttpURLConnection) new URL(Ratings.URL + "/device").openConnection();
                h.setRequestMethod("POST");
                h.setConnectTimeout(10000);
                h.setReadTimeout(30000);
                h.setRequestProperty("User-Agent", "Mareshine");
                h.setRequestProperty("Content-Type", "application/json");
                h.setDoOutput(true);
                byte[] bytes = build(c).toString().getBytes(StandardCharsets.UTF_8);
                h.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream out = h.getOutputStream()) {
                    out.write(bytes);
                }
                if (h.getResponseCode() == 200) p.edit().putString(SENT, version).apply();
                h.disconnect();
            } catch (Exception ignored) {
                // the next start of the app tries again
            }
        }, "device-info").start();
    }

    // --- achievements --------------------------------------------------------------

    private static boolean achTried; // a failed send waits for the next unlock or start of the app

    // Achievements just unlocked (SunshineActivity): kept until the server has them.
    static void queueAchievements(Context c, java.util.List<String> ids) {
        if (Ratings.URL.isEmpty() || !on(c) || c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(CONSENT, 1)
            < CONSENT_NOW) return;
        JSONObject state = Achievements.state(c);
        JSONObject un = state == null ? null : state.optJSONObject("unlocked");
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        java.util.Set<String> pending = new java.util.HashSet<>(p.getStringSet(PENDING, new java.util.HashSet<>()));
        for (String id : ids) {
            JSONObject e = un == null ? null : un.optJSONObject(id);
            boolean cheats = e != null && e.optBoolean("cheats", false);
            if (pending.size() < 500) pending.add(id + (cheats ? "|cheats" : ""));
        }
        p.edit().putStringSet(PENDING, pending).apply();
        achTried = false;
        flushAchievements(c.getApplicationContext());
    }

    private static synchronized void flushAchievements(Context c) {
        if (achTried || !on(c)) return;
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        java.util.Set<String> pending = new java.util.HashSet<>(p.getStringSet(PENDING, new java.util.HashSet<>()));
        if (pending.isEmpty()) return;
        achTried = true;
        new Thread(() -> {
            try {
                org.json.JSONArray items = new org.json.JSONArray();
                for (String e : pending) {
                    String[] parts = e.split("\\|");
                    items.put(new JSONObject().put("id", parts[0]).put("cheats", parts.length > 1));
                }
                JSONObject body = new JSONObject().put("install", install(c))
                    .put("version", CrashLog.BuildConfigInfo.version(c)).put("unlocked", items);
                HttpURLConnection h = (HttpURLConnection) new URL(Ratings.URL + "/achievement").openConnection();
                h.setRequestMethod("POST");
                h.setConnectTimeout(10000);
                h.setReadTimeout(30000);
                h.setRequestProperty("User-Agent", "Mareshine");
                h.setRequestProperty("Content-Type", "application/json");
                h.setDoOutput(true);
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                h.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream out = h.getOutputStream()) {
                    out.write(bytes);
                }
                if (h.getResponseCode() == 200) {
                    synchronized (DeviceInfo.class) {
                        java.util.Set<String> now = new java.util.HashSet<>(
                            p.getStringSet(PENDING, new java.util.HashSet<>()));
                        now.removeAll(pending);
                        p.edit().putStringSet(PENDING, now).apply();
                    }
                    achTried = false;
                }
                h.disconnect();
            } catch (Exception ignored) {
                // kept: sent with the next unlock or start of the app
            }
        }, "achievements-info").start();
    }

    private static JSONObject build(Context c) throws Exception {
        Map<String, String> s = Settings.read(c);
        Mods.Mod game = Mods.activeGame(c);
        JSONObject settings = new JSONObject();
        for (String key : new String[] {"render_scale", "frame_rate", "widescreen", "msaa"})
            settings.put(key, s.containsKey(key) ? s.get(key) : "");
        return new JSONObject()
            .put("install", install(c))
            .put("version", CrashLog.BuildConfigInfo.version(c))
            .put("device", Build.MANUFACTURER + " " + Build.MODEL)
            .put("android", Build.VERSION.SDK_INT)
            .put("chip", Build.VERSION.SDK_INT >= 31 ? Build.SOC_MODEL : Build.HARDWARE)
            .put("gpu", gpu(c))
            .put("mod", game != null ? game.id : "")
            .put("settings", settings);
    }

    // The graphics chip as the last game's log names it (none before the first game).
    private static String gpu(Context c) {
        File f = new File(CrashLog.dir(c), "game.log");
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r")) {
            byte[] buf = new byte[(int) Math.min(r.length(), 65536)];
            r.readFully(buf);
            Matcher m = RENDERER.matcher(new String(buf, StandardCharsets.UTF_8));
            return m.find() ? m.group(1).trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    // A random id of its own, not the ratings'.
    private static String install(Context c) throws Exception {
        File f = new File(c.getFilesDir(), "device_install");
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
}
