package io.github.them3dium.mareshine;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Where the game's disc image is looked for, in this order:
 * <ol>
 * <li>the data folder's iso/ folder, then the data folder itself: GMSE01.iso,
 *     else any disc image there (the one whose header the game takes first,
 *     else one that is not a European disc, which may lie there only for
 *     building language packs);</li>
 * <li>the app's own folders (where older versions kept the copied image);</li>
 * <li>a few public folders people put it in by hand.</li>
 * </ol>
 * Anything 10 MB or smaller is not a disc image (an empty or broken copy).
 */
final class DiscFinder {
    private DiscFinder() {}

    private static final long MIN_SIZE = 10L << 20;
    private static final String[] IMAGE_ENDINGS = {".iso", ".gcm", ".ciso", ".nkit.iso"};

    /** The disc image the game plays, or null when there is none. */
    static File find(Context ctx) {
        for (File dir : new File[] {DataDir.romDir(ctx), DataDir.get(ctx)}) {
            File named = new File(dir, "GMSE01.iso");
            if (usable(named)) return named;
            File inFolder = anyImageIn(dir);
            if (inFolder != null) return inFolder;
        }
        for (File f : fallbacks(ctx)) if (usable(f)) return f;
        return null;
    }

    /** True for a European Super Mario Sunshine (GMSP01): only used for building language packs. */
    static boolean isEuropean(File f) {
        byte[] id = new byte[6];
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            r.readFully(id);
        } catch (IOException e) {
            return false;
        }
        return new String(id, StandardCharsets.ISO_8859_1).startsWith("GMSP");
    }

    private static boolean usable(File f) {
        return f.isFile() && f.canRead() && f.length() > MIN_SIZE;
    }

    private static boolean looksLikeImage(File f) {
        String name = f.getName().toLowerCase(Locale.ROOT);
        for (String end : IMAGE_ENDINGS) if (name.endsWith(end)) return true;
        return false;
    }

    // In name order: the first the game accepts, else the first that is not European.
    private static File anyImageIn(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        Arrays.sort(files);
        File notEuropean = null;
        for (File f : files) {
            if (!looksLikeImage(f) || !usable(f)) continue;
            if (DiscCheck.checkHeader(f).ok) return f;
            if (notEuropean == null && !isEuropean(f)) notEuropean = f;
        }
        return notEuropean;
    }

    private static List<File> fallbacks(Context ctx) {
        List<File> out = new ArrayList<>();
        File ext = ctx.getExternalFilesDir(null);
        if (ext != null) {
            out.add(new File(ext, "GMSE01.iso"));
            out.add(new File(ext, "disc.iso"));
        }
        File internal = ctx.getFilesDir();
        if (internal != null) out.add(new File(internal, "GMSE01.iso"));

        String storage = Environment.getExternalStorageDirectory().getAbsolutePath();
        String[] usualNames = {"GMSE01.iso", "Super Mario Sunshine (USA).iso", "Super Mario Sunshine.iso"};
        for (String dir : new String[] {"/sdcard/Download", "/sdcard/sms", storage + "/Download"})
            for (String name : usualNames) out.add(new File(dir, name));
        return out;
    }
}
