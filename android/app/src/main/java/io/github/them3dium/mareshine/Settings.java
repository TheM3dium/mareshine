package io.github.them3dium.mareshine;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// settings.txt in the data folder: `name = value` lines, read by the game at
// start (platform/port_runtime.cpp) and by the settings screens.
public final class Settings {
    // Conservative first-start profile: about 792p (528 lines x 1.5),
    // 16:9, 30 fps, original draw distance, MSAA off, 4x texture filtering.
    // Existing settings are preserved by ensureDefaults.
    public static final String[][] DEFAULTS = {
        {"render_scale", "1.5"},
        {"widescreen", "16:9"},
        {"widescreen_hud", "edges"},
        {"aspect", "keep"},
        {"frame_rate", "30"},
        {"draw_distance", "1.0"},
        {"msaa", "0"},
        {"anisotropic", "4"},
        {"fxaa", "0"},
        {"disable_shadows", "0"},
        {"disable_reflections", "0"},
        {"disable_water_anim", "0"},
        {"disable_shimmer", "0"},
        {"fast_peek", "1"},
        {"copy_writeback", "1"},
        {"shader_cache", "1"},
        {"SMS_GX_PERSISTENT_BUFFER", "auto"},
        {"SMS_GX_DETAILED_TIMERS", "0"},
        {"vsync", "0"},
    };

    private Settings() {}

    public static String def(String key, String fallback) {
        for (String[] kv : DEFAULTS) if (kv[0].equals(key)) return kv[1];
        return fallback;
    }

    public static Map<String, String> read(Context ctx) {
        return parse(DataDir.settingsFile(ctx));
    }

    // `name = value` per line; blank lines, lines starting with # and lines
    // without a name are skipped. A later line wins over an earlier one.
    static Map<String, String> parse(File f) {
        Map<String, String> m = new LinkedHashMap<>();
        if (!f.isFile()) return m;
        List<String> lines;
        try {
            lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return m;
        }
        for (String raw : lines) {
            String line = raw.trim();
            int eq = line.indexOf('=');
            if (line.startsWith("#") || eq <= 0) continue;
            m.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return m;
    }

    // Written beside it and renamed over it: the app's two processes and the
    // game read it at any time, and must never see it half written. Where a
    // rename does not go through, written in place as before.
    public static boolean write(Context ctx, Map<String, String> m) {
        File f = DataDir.settingsFile(ctx);
        File tmp = new File(f.getPath() + ".tmp");
        if (writeTo(tmp, m) && tmp.renameTo(f)) return true;
        tmp.delete();
        return writeTo(f, m);
    }

    static final String HEADER = "# Mareshine settings (the app writes this file; name = value per line)";

    private static boolean writeTo(File f, Map<String, String> m) {
        StringBuilder text = new StringBuilder(HEADER).append('\n');
        for (Map.Entry<String, String> e : m.entrySet())
            text.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        try {
            Files.write(f.toPath(), text.toString().getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static void put(Context ctx, String key, String value) {
        Map<String, String> m = read(ctx);
        m.put(key, value);
        write(ctx, m);
    }

    // A first start gets the defaults above, so the game does not fall back
    // to its own (native resolution, 4:3).
    public static void ensureDefaults(Context ctx) {
        if (DataDir.settingsFile(ctx).exists()) {
            upgrade(ctx);
            modernCameraXOnce(ctx);
            return;
        }
        Map<String, String> m = defaults(ctx);
        Intro.newInstall(m);
        write(ctx, m);
    }

    // The defaults, up to date (a new install, and Settings' Reset all): an
    // upgrade must not take them for an older version's settings (#21).
    public static Map<String, String> defaults(Context ctx) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String[] kv : DEFAULTS) m.put(kv[0], kv[1]);
        m.put(REV, Integer.toString(CURRENT_REV));
        if (Warp.available(ctx)) modernCameraX(m);
        return m;
    }

    // Test builds: the C-stick and the touch C-stick turn the view the way they
    // are pushed left and right, as in modern games (the game's own way is reversed).
    private static final String CAMERA_X_DONE = "SMS_LAUNCHER_DEV_CAMERA_X";

    private static void modernCameraX(Map<String, String> m) {
        m.put("SMS_CAMERA_INVERT_X", "1");
        m.put(CAMERA_X_DONE, "1");
    }

    // Once on a test device with older settings; changed by hand after that, it stays.
    private static void modernCameraXOnce(Context ctx) {
        if (!Warp.available(ctx)) return;
        Map<String, String> m = read(ctx);
        if (m.containsKey(CAMERA_X_DONE)) return;
        modernCameraX(m);
        write(ctx, m);
    }

    // Settings written by older versions, brought up to date once (the
    // revision is kept in settings.txt; the game ignores the key).
    private static final String REV = "SMS_LAUNCHER_SETTINGS_REV";
    private static final int CURRENT_REV = 3;

    private static void upgrade(Context ctx) {
        Map<String, String> m = read(ctx);
        int rev = 0;
        try { rev = Integer.parseInt(m.getOrDefault(REV, "0")); } catch (NumberFormatException ignored) {}
        if (rev >= CURRENT_REV) return;
        // 1.x always wrote copy_writeback = 0; 2.0 has it on, with a switch
        if (rev < 2 && "0".equals(m.get("copy_writeback"))) m.put("copy_writeback", "1");
        // 2.1 has the GameCube button style and the R+ slider by default: an
        // update keeps the buttons it had until its intro asks (Intro)
        if (rev < 3 && !"gamecube".equals(m.get("SMS_TOUCH_STYLE"))) {
            m.putIfAbsent("SMS_TOUCH_STYLE", "classic");
            m.putIfAbsent("SMS_TOUCH_R_SLIDER", "0");
            Intro.ask(m, Intro.GAMECUBE_TOUCH);
        }
        m.put(REV, Integer.toString(CURRENT_REV));
        write(ctx, m);
    }
}
