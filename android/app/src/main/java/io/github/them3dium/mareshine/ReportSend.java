package io.github.them3dium.mareshine;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Pattern;

import static io.github.them3dium.mareshine.L.t;

// Send to developer: the same logs Share log collects (LogShare), with file
// and folder names taken out, to the reports endpoint of the ratings worker
// (tools/ratings-worker), which mails them to the developer. Only when the
// player taps Send: from Settings → OTHER → LOGS, or offered once after a
// freeze or crash was found at the next start (offerAfterProblem).
final class ReportSend {
    private static final String PREFS = "report_send", OFFERED = "offered_until";
    private static final int MAX_CHARS = 500 * 1024;   // the worker takes 512 KB
    private static final int HEAD_CHARS = 48 * 1024;   // a longer log keeps its start (device, settings) and its end

    private ReportSend() {}

    // --- asking the player ---------------------------------------------------------

    static void ask(Activity a, String reason) {
        final EditText note = new EditText(a);
        note.setHint(t("What happened? (optional, e.g. \"opened the Sound tab\")"));
        note.setMinLines(2);
        new DlDialog(a).setTitle(t("Send to developer"))
            .setMessage(t("Sends the game's log and the latest crash and freeze reports to the developer, "
                + "with the names of your files and folders taken out. Nothing is sent without this button."))
            .setView(note)
            .setNegativeButton(t("Cancel"), null)
            .setNeutralButton(t("What is sent?"), (d, w) -> preview(a, reason, note.getText().toString()))
            .setPositiveButton(t("Send"), (d, w) -> send(a, reason, note.getText().toString()))
            .show();
    }

    private static void preview(Activity a, String reason, String note) {
        String text = build(a);
        if (text == null) {
            noLog(a);
            return;
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        android.widget.TextView tv = new android.widget.TextView(a);
        tv.setText(text.length() > 20000 ? text.substring(0, 20000) + "\n…" : text);
        tv.setTextSize(11);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        sv.addView(tv);
        new DlDialog(a).setTitle(t("What is sent?"))
            .setView(sv)
            .setNegativeButton(t("Cancel"), null)
            .setPositiveButton(t("Send"), (d, w) -> send(a, reason, note))
            .show();
    }

    // After a start: a freeze (AnrLog, HangWatch) or crash report newer than
    // the last one offered. Once per report; "Not now" does not ask again.
    static void offerAfterProblem(Activity a) {
        if (Ratings.URL.isEmpty() || !CrashLog.isEnabled(a)) return;
        File[] files = CrashLog.dir(a).listFiles((d, n) -> (n.startsWith("crash") && n.endsWith(".log"))
            || ((n.startsWith("anr-") || n.startsWith("hang-")) && n.endsWith(".txt")));
        if (files == null || files.length == 0) return;
        SharedPreferences p = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long offered = p.getLong(OFFERED, 0), newest = 0;
        File pick = null;
        for (File f : files) {
            if (f.lastModified() > newest) {
                newest = f.lastModified();
                pick = f;
            }
        }
        if (pick == null || newest <= offered) return;
        p.edit().putLong(OFFERED, newest).apply();
        if (offered == 0 && System.currentTimeMillis() - newest > 3L * 86400_000) return;  // an old one from before this version
        boolean crash = pick.getName().startsWith("crash");
        final String reason = crash ? "crash" : "freeze";
        new DlDialog(a).setTitle(crash ? t("⚠️ The game crashed last time") : t("⚠️ Mareshine froze last time"))
            .setMessage(t("A report about it was saved. Send it to the developer? It helps to fix this."))
            .setNegativeButton(t("Not now"), null)
            .setPositiveButton(t("Send…"), (d, w) -> ask(a, reason))
            .show();
    }

    // --- building and sending ------------------------------------------------------

    private static void noLog(Activity a) {
        Toast.makeText(a, t("There is no log yet. Turn on logs, play until the problem happens, then try again."),
            Toast.LENGTH_LONG).show();
    }

    // the logs as Share log puts them together, cleaned; null without a log
    private static String build(Context c) {
        try {
            File f = LogShare.collect(c);
            if (f == null) return null;
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            text = clean(c, text);
            if (text.length() > MAX_CHARS)
                text = text.substring(0, HEAD_CHARS) + "\n\n[report] (" + (text.length() - MAX_CHARS) / 1024
                    + " KB left out here)\n\n" + text.substring(text.length() - (MAX_CHARS - HEAD_CHARS));
            return text;
        } catch (Exception e) {
            return null;
        }
    }

    private static final Pattern PATH = Pattern.compile("(?:/storage|/sdcard|/mnt|/data/user|/data/data|/data/media)/[^\\s\"'`,;)\\]]*");
    // a disc file name: after a folder (may hold spaces and brackets), or alone
    private static final Pattern DISC = Pattern.compile(
        "(?<=/)[^/\\n\"'`]+?\\.(?i:nkit\\.iso|iso|gcm|ciso|rvz|gcz|wbfs|zip|7z|rar)\\b"
            + "|[^\\s/\"'`,;()\\[\\]]+\\.(?i:nkit\\.iso|iso|gcm|ciso|rvz|gcz|wbfs|zip|7z|rar)\\b");
    // names that say nothing about the player: a game id, the Redump name
    private static final Pattern GAME_ID = Pattern.compile(
        "[A-Z0-9]{6}\\.(?i:iso|gcm|ciso|rvz)"
            + "|Super Mario Sunshine(?: \\((?:USA|Europe|Japan|Korea|En,Fr,De,Es,It|Rev \\d)\\))*\\.(?i:iso|nkit\\.iso|gcm|ciso|rvz)");
    private static final Pattern URI = Pattern.compile("content://[^\\s\"'`,;)\\]]*");
    private static final Pattern MAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    // Takes out what could name the player: the data folder becomes <data>,
    // the app's own folders <app>, other paths <path>, disc image names (other
    // than a game id like GMSE01.iso or the Redump name) <disc>, content URIs <uri>, mail
    // addresses <mail>.
    static String clean(Context c, String s) {
        String[] own = {
            DataDir.get(c).getAbsolutePath(),
            c.getFilesDir().getAbsolutePath(), c.getCacheDir().getAbsolutePath(),
            c.getExternalFilesDir(null) != null ? c.getExternalFilesDir(null).getAbsolutePath() : null,
        };
        for (int i = 0; i < own.length; i++) {
            if (own[i] == null || own[i].length() < 4) continue;
            s = s.replace(own[i], i == 0 ? "<data>" : "<app>");
        }
        s = URI.matcher(s).replaceAll("<uri>");
        s = PATH.matcher(s).replaceAll("<path>");
        java.util.regex.Matcher m = DISC.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String name = m.group();
            m.appendReplacement(sb, GAME_ID.matcher(name).matches() ? java.util.regex.Matcher.quoteReplacement(name)
                : "<disc>." + name.substring(name.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT));
        }
        m.appendTail(sb);
        s = MAIL.matcher(sb.toString()).replaceAll("<mail>");
        return s;
    }

    private static void send(Activity a, String reason, String note) {
        final Context app = a.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        Toast.makeText(a, t("Sending…"), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String text = build(app), result;
            boolean ok = false;
            if (text == null) {
                result = t("There is no log yet. Turn on logs, play until the problem happens, then try again.");
            } else {
                try {
                    JSONObject body = new JSONObject()
                        .put("version", CrashLog.BuildConfigInfo.version(app))
                        .put("device", Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")")
                        .put("android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")")
                        .put("reason", reason)
                        .put("note", clean(app, note.trim()))
                        .put("log", text);
                    String id = new JSONObject(post(Ratings.URL + "/report", body.toString())).optString("id");
                    ok = true;
                    result = t("Thank you! The report was sent.") + "\n\n" + t("Report code:") + " " + id + "\n\n"
                        + t("If you opened an issue on GitHub, you can add this code to it.");
                } catch (java.io.IOException e) {
                    result = e.getMessage() != null && e.getMessage().contains("429")
                        ? t("Too many reports in a short time. Please try again in an hour.")
                        : t("The report could not be sent. Check the internet connection and try again.");
                } catch (Exception e) {
                    result = t("The report could not be sent. Check the internet connection and try again.");
                }
            }
            final String msg = result;
            final boolean sent = ok;
            main.post(() -> {
                if (a.isFinishing() || a.isDestroyed()) {
                    Toast.makeText(app, msg, Toast.LENGTH_LONG).show();
                    return;
                }
                new DlDialog(a).setTitle(sent ? t("Report sent") : t("Send to developer"))
                    .setMessage(msg)
                    .setPositiveButton(t("OK"), null)
                    .show();
            });
        }, "report-send").start();
    }

    private static String post(String url, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "Mareshine");
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = c.getOutputStream()) {
            out.write(bytes);
        }
        int code = c.getResponseCode();
        try (InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream()) {
            if (code >= 400) throw new java.io.IOException("HTTP " + code);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 14];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
