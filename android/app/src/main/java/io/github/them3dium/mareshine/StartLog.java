package io.github.them3dium.mareshine;

import android.content.Context;
import android.os.Process;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

// How far each process got in its start, and when: logs/start-<pid>.txt,
// a line per step with the milliseconds since Android started the process.
// A freeze report of that process (AnrLog) takes it along. Three Samsung
// phones on Android 17 froze before the game started with no stacks and no
// HangWatch note, so the freeze came before the app's own code ran or while
// the whole process stood still; these lines tell which.
final class StartLog {
    private static final int MAX_FILES = 6;

    private StartLog() {}

    private static File file(Context c, int pid) {
        return new File(CrashLog.dir(c), "start-" + pid + ".txt");
    }

    // A step of this process's start (logs on only).
    static synchronized void mark(Context c, String what) {
        try {
            if (!CrashLog.isEnabled(c)) return;
            File f = file(c, Process.myPid());
            f.getParentFile().mkdirs();
            long ms = SystemClock.uptimeMillis() - Process.getStartUptimeMillis();
            try (OutputStream o = new FileOutputStream(f, true)) {
                o.write((ms + " ms  " + what + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {}
    }

    // The steps of process `pid`, or null.
    static String of(Context c, int pid) {
        try {
            File f = file(c, pid);
            return f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : null;
        } catch (Throwable e) {
            return null;
        }
    }

    // Keeps the newest few.
    static void tidy(Context c) {
        try {
            File[] old = CrashLog.dir(c).listFiles((d, n) -> n.startsWith("start-") && n.endsWith(".txt"));
            if (old == null || old.length <= MAX_FILES) return;
            Arrays.sort(old, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (int i = MAX_FILES; i < old.length; i++) old[i].delete();
        } catch (Throwable ignored) {}
    }
}
