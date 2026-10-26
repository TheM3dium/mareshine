package io.github.them3dium.mareshine;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

// "Mareshine isn't responding" (an ANR) leaves no trace in game.log: the app
// hangs, and Android ends it. Android keeps the stack of every thread at that
// moment, and from Android 11 an app may read it back. At the next start the
// app writes each new one as logs/anr-<date>-<time>.txt (with logs on), which
// Settings → OTHER → LOGS → Share log sends along.
final class AnrLog {
    private static final String PREFS = "anr_log", SEEN = "seen";
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private AnrLog() {}

    // in the background: reading the traces can take a moment
    static void collect(Context ctx) {
        if (Build.VERSION.SDK_INT < 30) return;
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                run(app);
            } catch (Throwable ignored) {}
        }, "AnrLog").start();
    }

    private static void run(Context c) throws Exception {
        if (Build.VERSION.SDK_INT < 30 || !CrashLog.isEnabled(c)) return;
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return;
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long seen = p.getLong(SEEN, 0), newest = seen;
        List<ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(c.getPackageName(), 0, 10);
        File dir = CrashLog.dir(c);
        for (ApplicationExitInfo e : exits) {
            if (e.getReason() != ApplicationExitInfo.REASON_ANR || e.getTimestamp() <= seen) continue;
            newest = Math.max(newest, e.getTimestamp());
            dir.mkdirs();
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date(e.getTimestamp()));
            File out = new File(dir, "anr-" + stamp + ".txt");
            try (OutputStream o = new FileOutputStream(out)) {
                String head = "Mareshine " + CrashLog.BuildConfigInfo.version(c) + ", " + Build.MANUFACTURER + " "
                    + Build.MODEL + ", Android " + Build.VERSION.RELEASE + "\n"
                    + "process " + e.getProcessName() + ", pid " + e.getPid() + "\n"
                    + "reason: " + e.getDescription() + "\n\n";
                o.write(head.getBytes("UTF-8"));
                try (InputStream in = e.getTraceInputStream()) {
                    if (in == null) {
                        o.write("(Android kept no thread stacks for this one)\n".getBytes("UTF-8"));
                    } else {
                        byte[] buf = new byte[1 << 16];
                        int n, total = 0;
                        while ((n = in.read(buf)) > 0 && total < MAX_BYTES) {
                            o.write(buf, 0, n);
                            total += n;
                        }
                    }
                }
            }
        }
        if (newest != seen) p.edit().putLong(SEEN, newest).apply();
    }
}
