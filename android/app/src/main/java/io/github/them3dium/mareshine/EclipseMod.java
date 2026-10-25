package io.github.them3dium.mareshine;

import android.content.Context;
import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

// Super Mario Eclipse as one mod (mods/eclipse/): its languages are disc images
// side by side (Mods.ECLIPSE and its translations, each made from the
// player's disc by ModInstallActivity), picked by its Language button; its
// HD textures (a Dolphin pack the player downloads) go into hd/ and are
// switched by its HD Textures button. The app writes its mod.json from what
// is installed, and its disc label is the banner on the player's own image.
final class EclipseMod {
    private EclipseMod() {}

    static final String ID = "eclipse", LANGUAGE = "language", HD = "hd";
    static final String PREVIEW = "https://images.gamebanana.com/img/ss/mods/66c9f280640aa.jpg";
    static final String TEXTURES_PAGE = "https://github.com/iZePlayzYT/SuperMarioEclipse-TexturePacks/releases";

    static File dir(Context ctx) { return new File(Mods.dir(ctx), ID); }

    // the releases making its languages: English first, then its translations
    static List<Mods.Release> languages() {
        List<Mods.Release> out = new ArrayList<>();
        out.add(Mods.ECLIPSE);
        out.addAll(Mods.translations(ID));
        return out;
    }

    static String languageName(Mods.Release r) { return r.language.isEmpty() ? "English" : r.language; }

    static String valueId(Mods.Release r) { return r.base.isEmpty() ? "en" : r.id; }

    static boolean has(Context ctx, Mods.Release r) { return new File(dir(ctx), r.image).isFile(); }

    // Writes mods/eclipse/mod.json for the languages that are there, and its
    // label when it has none yet. false when no language is installed.
    static boolean write(Context ctx) throws Exception {
        File d = dir(ctx);
        Mods.Release first = null;
        JSONArray values = new JSONArray();
        StringBuilder credits = new StringBuilder();
        for (Mods.Release r : languages()) {
            if (!has(ctx, r)) continue;
            if (first == null) first = r;
            values.put(new JSONObject().put("id", valueId(r)).put("label", languageName(r)).put("image", r.image));
            if (!r.credits.isEmpty()) credits.append("\n").append(languageName(r)).append(": ").append(r.credits);
        }
        if (first == null) return false;
        Mods.Release e = Mods.ECLIPSE;
        JSONArray options = new JSONArray()
            .put(new JSONObject().put("type", "choice").put("id", LANGUAGE).put("label", "Language").put("values", values))
            .put(new JSONObject().put("type", "toggle").put("id", HD).put("label", "HD Textures").put("folder", HD))
            .put(new JSONObject().put("type", "link").put("label", "Website").put("url", e.page));
        JSONObject o = new JSONObject().put("id", ID).put("name", e.name).put("version", e.version)
            .put("author", e.author).put("description", e.description + (credits.length() > 0
                ? "\n\nTranslations:" + credits : "")).put("image", first.image).put("code", e.code)
            .put("page", e.page).put("rating", e.rating).put("preview", PREVIEW).put("options", options);
        File icon = new File(d, "icon.png");
        if (!icon.isFile()) {
            Bitmap b = banner(new File(d, first.image));
            if (b != null) try (OutputStream out = new FileOutputStream(icon)) {
                b.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
        }
        if (icon.isFile()) o.put("icon", "icon.png");
        try (OutputStream out = new FileOutputStream(new File(d, "mod.json"))) {
            out.write(o.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        return true;
    }

    // Moves a language installed as a mod of its own (before Eclipse was one
    // mod: mods/eclipse-de/) into mods/eclipse/.
    static void migrate(Context ctx) {
        boolean moved = false;
        Mods.Release on = null;  // a language that was switched on: picked, and Eclipse on
        String mods = ";" + Settings.read(ctx).getOrDefault(Mods.KEY, "");
        for (Mods.Release r : Mods.translations(ID)) {
            File old = new File(Mods.dir(ctx), r.id), img = new File(old, r.image);
            if (!img.isFile()) continue;
            if (mods.contains(";" + r.id + ":1")) on = r;
            dir(ctx).mkdirs();
            if (img.renameTo(new File(dir(ctx), r.image))) {
                TexturePackActivity.remove(old);
                moved = true;
            }
        }
        // a mod.json from before (no buttons): written anew
        File json = new File(dir(ctx), "mod.json");
        if (!moved && (!json.isFile() || Mods.read(dir(ctx)) == null || !Mods.read(dir(ctx)).options.isEmpty())) return;
        try {
            write(ctx);
        } catch (Exception ignored) {
        }
        if (on != null) {
            try {
                installed(ctx, on);
            } catch (Exception ignored) {
            }
        }
        Mods.save(ctx, Mods.list(ctx));  // without the old entry
    }

    // Puts a newly made language in place and picks it.
    static void installed(Context ctx, Mods.Release r) throws Exception {
        write(ctx);
        Mods.Mod m = Mods.find(ctx, ID);
        if (m == null) return;
        for (Mods.Option o : m.options)
            if (o.id.equals(LANGUAGE)) Mods.setOption(ctx, m, o, valueId(r));
        Mods.setEnabled(ctx, ID, true);
    }

    // Deletes one language's image (not the last: deleting the mod does that).
    static void delete(Context ctx, Mods.Release r) throws Exception {
        new File(dir(ctx), r.image).delete();
        write(ctx);
    }

    // --- the banner (opening.bnr): 96 x 32, RGB5A3 in 4 x 4 tiles ------------------

    static Bitmap banner(File iso) {
        try (RandomAccessFile f = new RandomAccessFile(iso, "r")) {
            f.seek(0x424);
            long fstOff = f.readInt() & 0xFFFFFFFFL, fstSize = f.readInt() & 0xFFFFFFFFL;
            if (fstSize <= 0 || fstSize > (8 << 20)) return null;
            byte[] fst = new byte[(int) fstSize];
            f.seek(fstOff);
            f.readFully(fst);
            int n = be32(fst, 8), strings = n * 12;
            for (int i = 1; i < n; i++) {
                int w0 = be32(fst, i * 12);
                if ((w0 >>> 24) != 0) continue;
                int s = strings + (w0 & 0xFFFFFF), e = s;
                while (e < fst.length && fst[e] != 0) e++;
                if (!new String(fst, s, e - s, StandardCharsets.ISO_8859_1).equalsIgnoreCase("opening.bnr")) continue;
                byte[] px = new byte[96 * 32 * 2];
                f.seek((be32(fst, i * 12 + 4) & 0xFFFFFFFFL) + 0x20);
                f.readFully(px);
                int[] argb = new int[96 * 32];
                int o = 0;
                for (int ty = 0; ty < 32; ty += 4)
                    for (int tx = 0; tx < 96; tx += 4)
                        for (int y = 0; y < 4; y++)
                            for (int x = 0; x < 4; x++, o += 2)
                                argb[(ty + y) * 96 + tx + x] = rgb5a3(((px[o] & 0xFF) << 8) | (px[o + 1] & 0xFF));
                return Bitmap.createBitmap(argb, 96, 32, Bitmap.Config.ARGB_8888);
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private static int be32(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    private static int rgb5a3(int v) {
        if ((v & 0x8000) != 0) {
            int r = (v >> 10) & 31, g = (v >> 5) & 31, b = v & 31;
            return 0xFF000000 | (r * 255 / 31) << 16 | (g * 255 / 31) << 8 | (b * 255 / 31);
        }
        int a = (v >> 12) & 7, r = (v >> 8) & 15, g = (v >> 4) & 15, b = v & 15;
        return (a * 255 / 7) << 24 | (r * 17) << 16 | (g * 17) << 8 | (b * 17);
    }
}
