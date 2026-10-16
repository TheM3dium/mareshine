package io.github.them3dium.mareshine;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

// The data folder ("Datenordner" / "Data folder") the player picks in the
// launcher. Everything the game writes or loads besides the disc image lives
// under it:
//   <data>/settings.txt   settings (SettingsActivity)
//   <data>/saves/         memory card saves and the shader cache (SMS_SAVE_DIR)
//   <data>/textures/      Dolphin-format texture packs (SMS_TEXTURE_PACKS)
//   <data>/iso/           optional: the disc image (was rom/)
// Without a choice it is the app's own folder (Android/data/<package>/files),
// as before.
public final class DataDir {
    private static final String PREFS = "sunshine";
    private static final String KEY_DIR = "data_dir";

    public static final String SETTINGS = "settings.txt";
    public static final String SAVES = "saves";
    public static final String TEXTURES = "textures";
    public static final String ROM = "iso";

    private DataDir() {}

    public static File defaultDir(Context ctx) {
        File d = ctx.getExternalFilesDir(null);
        return d != null ? d : ctx.getFilesDir();
    }

    public static boolean isCustom(Context ctx) {
        return prefs(ctx).getString(KEY_DIR, null) != null;
    }

    public static File get(Context ctx) {
        String p = prefs(ctx).getString(KEY_DIR, null);
        return p != null ? new File(p) : defaultDir(ctx);
    }

    public static void set(Context ctx, File dir) {
        prefs(ctx).edit().putString(KEY_DIR, dir.getAbsolutePath()).apply();
    }

    public static void reset(Context ctx) {
        prefs(ctx).edit().remove(KEY_DIR).apply();
    }

    public static File settingsFile(Context ctx) { return new File(get(ctx), SETTINGS); }
    public static File savesDir(Context ctx) { return new File(get(ctx), SAVES); }
    public static File texturesDir(Context ctx) { return new File(get(ctx), TEXTURES); }
    public static File romDir(Context ctx) { return new File(get(ctx), ROM); }
    public static File languagesDir(Context ctx) { return new File(get(ctx), "languages"); }
    // a European disc, only for building language packs
    public static File palDir(Context ctx) { return new File(romDir(ctx), "pal"); }

    // Creates the folder structure. Returns false when the folder cannot be
    // written (no "All files access", or a read-only volume).
    public static boolean ensureLayout(Context ctx) {
        File root = get(ctx);
        boolean ok = root.isDirectory() || root.mkdirs();
        // the disc image folder was called rom/ before
        File oldRom = new File(root, "rom"), iso = new File(root, ROM);
        if (oldRom.isDirectory() && !iso.exists()) oldRom.renameTo(iso);
        for (String sub : new String[] { SAVES, TEXTURES, ROM, ROM + "/pal", "logs", "languages" }) {
            File d = new File(root, sub);
            ok &= d.isDirectory() || d.mkdirs();
        }
        if (ok) {
            File readme = new File(root, "LEEME-README.txt");
            if (!readme.exists()) {
                try (OutputStream out = new FileOutputStream(readme)) {
                    out.write(("Super Mario Sunshine - Android data folder\n\n"
                        + "settings.txt  settings\n"
                        + "saves/        save games (memory card) and shader cache\n"
                        + "textures/     texture packs in Dolphin format, e.g. textures/GMSE01/...\n"
                        + "iso/          optional: put GMSE01.iso here\n"
                        + "logs/         game.log (this session), game.prev.log, crash-*.log\n"
                        + "languages/    language packs, e.g. languages/de/files/... (Settings > GAME > Language)\n").getBytes("UTF-8"));
                } catch (IOException ignored) {}
            }
        }
        if (ok) Settings.ensureDefaults(ctx);
        return ok && root.canWrite();
    }

    // Moves settings and saves from the previous folder into the new one, but
    // never overwrites anything already there.
    public static void migrate(File from, File to) {
        if (from == null || to == null || from.equals(to)) return;
        copyIfMissing(new File(from, SETTINGS), new File(to, SETTINGS));
        File oldSaves = new File(from, SAVES);
        if (!oldSaves.isDirectory()) oldSaves = new File(from, "card-a"); // layout before the data folder
        copyTreeIfMissing(oldSaves, new File(to, SAVES));
    }

    private static void copyTreeIfMissing(File from, File to) {
        File[] files = from.listFiles();
        if (files == null) return;
        to.mkdirs();
        for (File f : files) {
            File t = new File(to, f.getName());
            if (f.isDirectory()) copyTreeIfMissing(f, t);
            else copyIfMissing(f, t);
        }
    }

    private static void copyIfMissing(File from, File to) {
        if (!from.isFile() || to.exists()) return;
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (IOException ignored) {}
    }

    // A folder picked with ACTION_OPEN_DOCUMENT_TREE, as a file system path:
    // "primary:Games/Sunshine" is /storage/emulated/0/Games/Sunshine, and
    // "1234-ABCD:Sunshine" is /storage/1234-ABCD/Sunshine (an SD card).
    public static File fromTreeUri(Uri uri) {
        if (uri == null) return null;
        String id;
        try {
            id = DocumentsContract.getTreeDocumentId(uri);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (id == null) return null;
        int colon = id.indexOf(':');
        String volume = colon >= 0 ? id.substring(0, colon) : id;
        String rel = colon >= 0 ? id.substring(colon + 1) : "";
        File base;
        if ("primary".equalsIgnoreCase(volume)) {
            base = Environment.getExternalStorageDirectory();
        } else if ("home".equalsIgnoreCase(volume)) {
            base = new File(Environment.getExternalStorageDirectory(), "Documents");
        } else if (volume.startsWith("raw")) {
            return new File(rel.isEmpty() ? volume.substring(3) : rel);
        } else {
            base = new File("/storage/" + volume);
        }
        return rel.isEmpty() ? base : new File(base, rel);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
