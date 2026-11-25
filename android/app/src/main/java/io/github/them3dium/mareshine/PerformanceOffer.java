package io.github.them3dium.mareshine;

import static io.github.them3dium.mareshine.L.t;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.nio.file.Files;

// The performance mode (settings.txt SMS_ANDROID_PERFORMANCE_MODE): the game
// on the fastest CPU cores (platform/android/performance.cpp) and the GL
// thread on (platform/gx/src/gx_glthread.cpp). It helps phones with only two
// fast cores, such as the moto g15 (Helio G81: render scale 1.5 went from 21
// to 30 FPS in Delfino Plaza's full view), so the launcher offers it there
// once; elsewhere it stays off until switched on in the settings.
final class PerformanceOffer {
    static final String KEY = "SMS_ANDROID_PERFORMANCE_MODE";
    private static final String PREFS = "performance_offer";
    private static final String ASKED = "asked";

    private PerformanceOffer() {}

    static boolean on(Context c) {
        return "1".equals(Settings.read(c).get(KEY));
    }

    // Once per installation, on a device whose fastest tier is one or two of
    // at least four cores, clearly faster than the rest.
    static void offer(Activity a) {
        SharedPreferences p = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (p.getBoolean(ASKED, false)) return;
        if (on(a) || !fewFastCores()) {
            p.edit().putBoolean(ASKED, true).apply();
            return;
        }
        p.edit().putBoolean(ASKED, true).apply();
        new DlDialog(a).setTitle(t("Turn on the performance mode?"))
            .setMessage(t("Your device has only two fast CPU cores. The performance mode keeps the game on them and "
                + "gives the graphics driver a core of its own; on devices like yours this can raise the frame rate a lot. "
                + "You can switch it off any time under Settings, Video, Performance mode."))
            .setNegativeButton(t("No thanks"), null)
            .setPositiveButton(t("Turn on"), (d, w) -> Settings.put(a, KEY, "1"))
            .show();
    }

    static boolean fewFastCores() {
        for (String node : new String[] {"cpu_capacity", "cpufreq/cpuinfo_max_freq"}) {
            long[] w = weights(node);
            if (w == null) continue;
            long max = 0;
            for (long v : w) max = Math.max(max, v);
            int fast = 0;
            boolean gap = true;
            for (long v : w) {
                if (v == max) fast++;
                else if (v * 10 > max * 8) gap = false;  // another core within 80%: no clear fast tier
            }
            return w.length >= 4 && fast <= 2 && gap;
        }
        return false;
    }

    // One value per CPU from one sysfs node, or null if any is missing.
    private static long[] weights(String node) {
        int n = Runtime.getRuntime().availableProcessors();
        File[] cpus = new File("/sys/devices/system/cpu").listFiles((d, name) -> name.matches("cpu\\d+"));
        if (cpus != null) n = Math.max(n, cpus.length);
        if (n <= 0) return null;
        long[] w = new long[n];
        for (int i = 0; i < n; i++) {
            try {
                w[i] = Long.parseLong(new String(Files.readAllBytes(
                    new File("/sys/devices/system/cpu/cpu" + i + "/" + node).toPath())).trim());
            } catch (Exception e) {
                return null;
            }
            if (w[i] <= 0) return null;
        }
        return w;
    }
}
