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
// under a random id of its own. On or off in Settings -> OTHER.
final class DeviceInfo {
    static final String KEY = "SMS_DEVICE_INFO";
    private static final String PREFS = "device_info", ASKED = "asked", SENT = "sent_version";
    private static final Pattern RENDERER = Pattern.compile("renderer ([^\\n(]+)");
    private static boolean tried;  // once per start of the app: an unreachable server is not asked again and again

    private DeviceInfo() {}

    // The launcher, each time it shows: the question once there is a game to play, else the sending.
    static void onLauncher(Activity a) {
        if (Ratings.URL.isEmpty()) return;
        SharedPreferences p = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean(ASKED, false)) {
            if (LauncherActivity.findIsoFile(a) != null) ask(a);
            return;
        }
        send(a.getApplicationContext());
    }

    private static boolean on(Context c) { return "1".equals(Settings.read(c).get(KEY)); }

    private static void ask(Activity a) {
        new DlDialog(a).setTitle(t("Send anonymous device info?"))
            .setMessage(t("To see which phones and graphics chips have problems, Mareshine can send anonymous info "
                + "about your device once after each install and update: phone model, graphics chip, Android and app "
                + "version, a few settings and the active mod. No names and no files, and your IP address is not "
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
        a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ASKED, true).apply();
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
