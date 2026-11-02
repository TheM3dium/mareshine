package io.github.them3dium.mareshine;

import static io.github.them3dium.mareshine.L.t;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

// The ANGLE option (settings.txt SMS_ANGLE): the game's graphics on Vulkan,
// through the ANGLE that Android 15 and later carry in /system/lib64
// (platform/gx/src/gx_platform.cpp copies it into the app's storage and loads
// it). An alpha: offered once after a crash or freeze on a Mali GPU, whose
// GLES drivers give the most trouble, and switched off again by itself when
// the game had a problem with it on.
final class AngleOffer {
    private static final String PREFS = "angle_offer";
    private static final String SEEN = "problem_seen";  // the newest crash or freeze already looked at
    private static final String NEVER = "never";

    private AngleOffer() {}

    static boolean available() {
        return new File("/system/lib64/libEGL_angle.so").exists() && new File("/system/lib64/libGLESv2_angle.so").exists();
    }

    static boolean on(Context c) {
        return "1".equals(Settings.read(c).get("SMS_ANGLE"));
    }

    // After the launcher has shown what went wrong: a crash or freeze report
    // newer than the last one looked at, then either ANGLE is switched off
    // (it was on) or it is offered (a Mali GPU, ANGLE there, not refused).
    static void afterProblem(Activity a) {
        SharedPreferences p = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        File dir = CrashLog.dir(a);
        File[] files = dir.listFiles((d, n) -> (n.startsWith("crash") && n.endsWith(".log"))
            || ((n.startsWith("anr-") || n.startsWith("hang-")) && n.endsWith(".txt")));
        long newest = 0;
        if (files != null) for (File f : files) newest = Math.max(newest, f.lastModified());
        long seen = p.getLong(SEEN, -1);
        p.edit().putLong(SEEN, newest).apply();
        if (seen < 0 || newest <= seen) return;  // nothing new (or the first look, at old reports)
        String log = lastLog(dir);
        if (on(a) && log.contains("graphics driver: ANGLE (Vulkan)")) {
            Settings.put(a, "SMS_ANGLE", "0");
            new DlDialog(a).setTitle(t("ANGLE switched off"))
                .setMessage(t("The game had a problem while ANGLE (Vulkan) was on, so Mareshine went back to your "
                    + "device's own graphics driver. You can switch ANGLE on again under Settings, Video, Graphics driver."))
                .setPositiveButton(t("OK"), null)
                .show();
            return;
        }
        if (on(a) || p.getBoolean(NEVER, false) || !available() || !log.contains("renderer Mali")) return;
        new DlDialog(a).setTitle(t("Try ANGLE?"))
            .setMessage(t("Your device has a Mali GPU. Mareshine can draw the game through ANGLE (Vulkan) instead of "
                + "its own graphics driver. This is an untested alpha option: it may run better, the same or worse. "
                + "You can switch it off any time under Settings, Video, Graphics driver."))
            .setNegativeButton(t("Don't ask again"), (d, w) -> p.edit().putBoolean(NEVER, true).apply())
            .setNeutralButton(t("Not now"), null)
            .setPositiveButton(t("Try it"), (d, w) -> Settings.put(a, "SMS_ANGLE", "1"))
            .show();
    }

    // The end of the last session's game.log (the renderer line is near its start).
    private static String lastLog(File dir) {
        File f = new File(dir, "game.log");
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r")) {
            byte[] buf = new byte[(int) Math.min(r.length(), 262144)];
            r.readFully(buf);
            return new String(buf, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
