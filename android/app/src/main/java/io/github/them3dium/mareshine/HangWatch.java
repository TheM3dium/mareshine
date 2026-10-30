package io.github.them3dium.mareshine;

import android.content.Context;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Android does not keep the stacks of every ANR ("Mareshine isn't
// responding"): on some devices (Retroid, #13) AnrLog finds none. So each of
// the app's processes asks its main thread every half second whether it is
// still there; when it has not answered for 3 s (Android gives up after 5),
// what every thread is doing goes to logs/hang-<date>-<time>.txt (with logs
// on), which Share log and Send to developer take along.
final class HangWatch {
    private static final long STEP_MS = 500, HANG_MS = 3000;
    private static final long FROZEN_MS = 1500;  // a step this late: the process itself was stopped
    private static final int MAX_FILES = 5, MAX_BYTES = 256 * 1024;

    private static volatile long sAnswered;

    private HangWatch() {}

    static void start(Context ctx) {
        final Context app = ctx.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        final Runnable answer = () -> sAnswered = SystemClock.uptimeMillis();
        Thread t = new Thread(() -> {
            long asked = SystemClock.uptimeMillis();
            sAnswered = asked;
            boolean written = false;
            main.post(answer);
            while (true) {
                long before = SystemClock.uptimeMillis();
                try {
                    Thread.sleep(STEP_MS);
                } catch (InterruptedException e) {
                    return;
                }
                long now = SystemClock.uptimeMillis();
                // Android froze the whole process (in the background): not a hang
                if (now - before > STEP_MS + FROZEN_MS || Debug.isDebuggerConnected()) {
                    asked = now;
                    sAnswered = now;
                    main.post(answer);
                    continue;
                }
                if (sAnswered >= asked) {  // answered: ask again
                    written = false;
                    asked = now;
                    main.post(answer);
                    continue;
                }
                if (!written && now - asked >= HANG_MS) {
                    written = true;  // once per hang
                    try {
                        if (CrashLog.isEnabled(app)) write(app, now - asked);
                    } catch (Throwable ignored) {}
                }
            }
        }, "HangWatch");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void write(Context c, long late) throws Exception {
        File dir = CrashLog.dir(c);
        dir.mkdirs();
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        String process = Build.VERSION.SDK_INT >= 28 ? android.app.Application.getProcessName() : "?";
        StringBuilder sb = new StringBuilder();
        sb.append("Mareshine ").append(CrashLog.BuildConfigInfo.version(c)).append(", ").append(Build.MANUFACTURER)
            .append(' ').append(Build.MODEL).append(", Android ").append(Build.VERSION.RELEASE).append('\n')
            .append("process ").append(process).append(", pid ").append(android.os.Process.myPid()).append('\n')
            .append("the main thread has not answered for ").append(late).append(" ms\n");
        Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
        Thread mainThread = Looper.getMainLooper().getThread();
        List<Thread> threads = new ArrayList<>(all.keySet());
        threads.sort((a, b) -> a == mainThread ? -1 : b == mainThread ? 1 : a.getName().compareTo(b.getName()));
        for (Thread th : threads) {
            if (sb.length() > MAX_BYTES) break;
            sb.append("\n\"").append(th.getName()).append("\" ").append(th.getState())
                .append(th == mainThread ? " (main)" : "").append('\n');
            StackTraceElement[] st = all.get(th);
            if (st != null) for (StackTraceElement e : st) sb.append("  at ").append(e).append('\n');
        }
        try (OutputStream o = new FileOutputStream(new File(dir, "hang-" + stamp + ".txt"))) {
            o.write(sb.toString().getBytes("UTF-8"));
        }
        // the newest few
        File[] old = dir.listFiles((d, n) -> n.startsWith("hang-") && n.endsWith(".txt"));
        if (old != null && old.length > MAX_FILES) {
            Arrays.sort(old, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (int i = MAX_FILES; i < old.length; i++) old[i].delete();
        }
    }
}
