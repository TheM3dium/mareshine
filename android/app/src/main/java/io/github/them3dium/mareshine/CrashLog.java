package io.github.them3dium.mareshine;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import static io.github.them3dium.mareshine.L.t;

// Logs and crash reports in <data folder>/logs (switch in Advanced Settings):
//   game.log, game.prev.log         the game's log, this and the last session
//                                   (written by port_log, SMS_LOG_DIR)
//   crash-<date>-<time>.log         game.log at the moment the game crashed
//   crash-java-<date>-<time>.log    an uncaught Java exception in the app
public final class CrashLog {
    private static final String PREFS = "sunshine";
    private static final String KEY_ENABLED = "write_logs";
    private static boolean sInstalled;

    private CrashLog() {}

    public static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply();
    }

    // Remembers when the game was started, so the launcher can explain what
    // went wrong once the player is back (the game process may have exited).
    // A file, not SharedPreferences: the game runs in its own process (":game")
    // and the launcher's in-memory preferences would not see its changes.
    private static File sessionFile(Context ctx) {
        return new File(dir(ctx), ".session");
    }

    public static void markGameStarted(Context ctx) {
        File f = sessionFile(ctx);
        f.getParentFile().mkdirs();
        try (FileWriter w = new FileWriter(f)) {
            w.write(Long.toString(System.currentTimeMillis()));
        } catch (Exception ignored) {}
    }

    // The player left through the menu: nothing to report.
    public static void clearGameStarted(Context ctx) {
        sessionFile(ctx).delete();
    }

    // What went wrong in the last game session, or null when nothing did (or
    // it was already reported). Looks for a crash report written since the
    // game started, then for known errors at the end of game.log.
    public static String takeLastSessionProblem(Context ctx) {
        File session = sessionFile(ctx);
        if (!session.exists()) return null;
        long started;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(session))) {
            started = Long.parseLong(r.readLine().trim());
        } catch (Exception e) {
            started = session.lastModified();
        }
        session.delete();
        File d = dir(ctx);
        File newest = null;
        File[] files = d.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().startsWith("crash-") && f.lastModified() >= started - 2000
                        && (newest == null || f.lastModified() > newest.lastModified())) {
                    newest = f;
                }
            }
        }
        if (newest != null) {
            String lines = grep(newest, "fatal signal", "[OSPanic]", "pc sym:", "Exception", "Error", "Caused by");
            return t("The game crashed.") + "\n\n" + (lines.isEmpty() ? "" : lines + "\n")
                + t("Crash report:") + "\n" + newest.getAbsolutePath();
        }
        File log = new File(d, "game.log");
        if (log.lastModified() >= started - 2000) {
            String lines = grep(log, "is not a usable GameCube disc image", "out of memory for MEM1", "SAVE FAILED",
                "images are not supported", "[OSPanic]");
            if (!lines.isEmpty()) {
                return t("The game reported a problem:") + "\n\n" + lines + "\n\n" + t("Full log:") + "\n" + log.getAbsolutePath();
            }
        }
        return null;
    }

    // Up to 6 lines from the last 64 KiB of a file that contain one of the needles.
    private static String grep(File f, String... needles) {
        StringBuilder out = new StringBuilder();
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            long len = raf.length(), from = Math.max(0, len - 65536);
            byte[] buf = new byte[(int) (len - from)];
            raf.seek(from);
            raf.readFully(buf);
            int count = 0;
            for (String line : new String(buf, java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                for (String n : needles) {
                    if (line.contains(n)) {
                        out.append(line.trim()).append('\n');
                        count++;
                        break;
                    }
                }
                if (count >= 6) break;
            }
        } catch (Exception ignored) {}
        return out.toString().trim();
    }

    public static File dir(Context ctx) {
        return new File(DataDir.get(ctx), "logs");
    }

    public static void install(Context ctx) {
        if (sInstalled) return;
        sInstalled = true;
        final Context app = ctx.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            try {
                if (isEnabled(app)) write(app, thread, e);
            } catch (Throwable ignored) {}
            if (previous != null) previous.uncaughtException(thread, e);
        });
    }

    private static void write(Context ctx, Thread thread, Throwable e) throws Exception {
        File d = dir(ctx);
        d.mkdirs();
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        try (PrintWriter pw = new PrintWriter(new FileWriter(new File(d, "crash-java-" + stamp + ".log")))) {
            pw.println("Mareshine (Super Mario Sunshine for Android) crash report");
            pw.println("Time:    " + new Date());
            pw.println("App:     " + BuildConfigInfo.version(ctx));
            pw.println("Device:  " + Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")");
            pw.println("Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            pw.println("Thread:  " + thread.getName());
            pw.println();
            e.printStackTrace(pw);
        }
    }

    // The app's version without depending on the generated BuildConfig.
    static final class BuildConfigInfo {
        static String version(Context ctx) {
            try {
                return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "?";
            }
        }
    }
}
