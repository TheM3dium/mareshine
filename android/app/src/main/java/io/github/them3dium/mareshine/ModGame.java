package io.github.them3dium.mareshine;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

// A mod's own game kept as the disc files it changes (Super Mario Sunburn):
// patched from the player's disc as a whole image, then only the files that
// differ from the player's disc are kept in files/ and the image goes (1.4 GB
// less). The game plays the player's disc with them over it (SMS_GAME_MOD,
// under the language pack) and the mod's game code and maker (SMS_DISC_ID), so
// its saves stay its own. Its mod.json says "game": true and "disc_id".
final class ModGame {
    private ModGame() {}

    interface Progress {
        // false: stop
        boolean step(long done, long total);
    }

    // what the game never reads: the modders' tools, sources and backups on the
    // disc, the Kuribo modules (their code is built into the app), its symbol
    // map and banner
    static boolean keep(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        for (String skip : new String[] {"/audiores/__backup/", "/audiores/audio_modding/", "/audiores/tool/",
                "/audiores/_readme", "/kuribo!/"})
            if (p.startsWith(skip)) return false;
        for (String ext : new String[] {".map", ".bnr", ".bat", ".txt", ".exe", ".dll", ".pdb", ".pdf", ".config"})
            if (p.endsWith(ext)) return false;
        return true;
    }

    // The files of `modded` that are not the same on `us` into `files`; the bytes kept
    static long extract(File us, File modded, File files, Progress progress) throws IOException {
        long kept = 0, done = 0, total = 0;
        try (LanguagePackBuilder.Disc a = new LanguagePackBuilder.Disc(us);
             LanguagePackBuilder.Disc b = new LanguagePackBuilder.Disc(modded)) {
            for (long[] e : b.files.values()) total += e[1];
            byte[] x = new byte[1 << 20], y = new byte[1 << 20];
            for (Map.Entry<String, long[]> e : b.files.entrySet()) {
                String path = e.getKey();
                long[] at = e.getValue(), was = a.files.get(path);
                if (keep(path) && (was == null || !same(a.f, was, b.f, at, x, y))) {
                    File out = new File(files, path.substring(1));
                    out.getParentFile().mkdirs();
                    try (OutputStream o = new FileOutputStream(out)) {
                        b.f.seek(at[0]);
                        for (long left = at[1]; left > 0; ) {
                            int n = (int) Math.min(x.length, left);
                            b.f.readFully(x, 0, n);
                            o.write(x, 0, n);
                            left -= n;
                        }
                    }
                    kept += at[1];
                }
                done += at[1];
                if (progress != null && !progress.step(done, total)) throw new IOException("cancelled");
            }
        }
        return kept;
    }

    private static boolean same(RandomAccessFile fa, long[] a, RandomAccessFile fb, long[] b, byte[] x, byte[] y)
            throws IOException {
        if (a[1] != b[1]) return false;
        fa.seek(a[0]);
        fb.seek(b[0]);
        for (long left = a[1]; left > 0; ) {
            int n = (int) Math.min(x.length, left);
            fa.readFully(x, 0, n);
            fb.readFully(y, 0, n);
            if (!java.nio.ByteBuffer.wrap(x, 0, n).equals(java.nio.ByteBuffer.wrap(y, 0, n))) return false;
            left -= n;
        }
        return true;
    }

    // its game code and maker ("GMSE03")
    static String discId(File image) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(image, "r")) {
            byte[] h = new byte[6];
            f.readFully(h);
            return new String(h, StandardCharsets.ISO_8859_1);
        }
    }

    // An installed mod with a whole image that the app keeps as files now
    // (Release.overlay): its files out of the image, its mod.json changed, the
    // image deleted. Once, at the next start; false when it could not be done.
    static boolean convert(Context ctx, Mods.Mod m, File us, Progress progress) {
        File image = m.image(), files = new File(m.dir, "files"), tmp = new File(m.dir, "files.tmp");
        if (image == null || us == null || !us.isFile()) return false;
        try {
            TexturePackActivity.remove(tmp);
            extract(us, image, tmp, progress);
            String id = discId(image);
            File json = new File(m.dir, "mod.json");
            JSONObject o = new JSONObject(new String(java.nio.file.Files.readAllBytes(json.toPath()), StandardCharsets.UTF_8));
            o.remove("image");
            o.put("game", true).put("disc_id", id);
            TexturePackActivity.remove(files);
            if (!tmp.renameTo(files)) throw new IOException("cannot rename " + tmp);
            try (OutputStream out = new FileOutputStream(json)) {
                out.write(o.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            image.delete();
            return true;
        } catch (Exception e) {
            TexturePackActivity.remove(tmp);
            return false;
        }
    }

    // an installed mod to convert: a whole image the app would keep as files
    static boolean toConvert(Mods.Mod m) {
        if (m == null || m.image() == null) return false;
        Mods.Release r = Mods.release(m.id);
        return r != null && r.overlay;
    }
}
