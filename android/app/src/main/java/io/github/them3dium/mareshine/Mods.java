package io.github.them3dium.mareshine;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

// Mod packages in <data>/mods/<id>/, in the format of Dusklight's (CC0):
//   mod.json     {"id": ..., "name", "version", "author", "description", "requires": [ids]}
//                (only "id" is needed; a folder without one is a mod named after it)
//   files/ or overlay/   disc files: each takes the place of the disc's file at the
//                same path (or goes inside the archive named like its folder), or
//                is added (platform/dvd/dvd.cpp, SMS_MOD)
//   textures/    Dolphin-format textures (SMS_TEXTURE_PACKS)
// Their order and which are on is settings.txt's SMS_MODS ("id:1;id:0", first
// loaded first, so a later mod wins); mods not listed there are off, after
// the listed ones. Changes apply when the game starts.
public final class Mods {
    public static final String KEY = "SMS_MODS";
    // The mod manager's entries in Settings and the quick menu: off until it
    // ships with patch import and Super Mario Eclipse (installed mods still load).
    public static final boolean MENU = false;

    private Mods() {}

    public static final class Mod {
        public String id, name, version = "", author = "", description = "";
        public List<String> requires = new ArrayList<>();
        public File dir;
        public boolean enabled;

        public File files() {
            for (String n : new String[] {"files", "overlay"}) {
                File f = new File(dir, n);
                if (f.isDirectory()) return f;
            }
            return null;
        }

        public File textures() {
            File f = new File(dir, "textures");
            return f.isDirectory() ? f : null;
        }
    }

    public static File dir(Context ctx) { return new File(DataDir.get(ctx), "mods"); }

    // The installed mods, in their order.
    public static List<Mod> list(Context ctx) {
        Map<String, Mod> found = new LinkedHashMap<>();
        File[] dirs = dir(ctx).listFiles();
        if (dirs != null) {
            Arrays.sort(dirs);
            for (File d : dirs) {
                if (!d.isDirectory() || d.getName().startsWith(".") || d.getName().endsWith(".tmp")) continue;
                Mod m = read(d);
                if (m != null && !found.containsKey(m.id)) found.put(m.id, m);
            }
        }
        List<Mod> out = new ArrayList<>();
        for (String entry : Settings.read(ctx).getOrDefault(KEY, "").split(";")) {
            int colon = entry.lastIndexOf(':');
            String id = colon > 0 ? entry.substring(0, colon) : entry;
            Mod m = found.remove(id);
            if (m == null) continue;
            m.enabled = colon > 0 && entry.substring(colon + 1).equals("1");
            out.add(m);
        }
        out.addAll(found.values()); // new ones: off, at the end
        return out;
    }

    public static void save(Context ctx, List<Mod> mods) {
        StringBuilder sb = new StringBuilder();
        for (Mod m : mods) {
            if (sb.length() > 0) sb.append(';');
            sb.append(m.id).append(':').append(m.enabled ? '1' : '0');
        }
        Map<String, String> s = Settings.read(ctx);
        s.put(KEY, sb.toString());
        Settings.write(ctx, s);
    }

    // A mod's folder: its mod.json, or the folder's name when it has none but
    // has disc files or textures.
    static Mod read(File d) {
        Mod m = new Mod();
        m.dir = d;
        m.id = d.getName();
        File json = new File(d, "mod.json");
        if (json.isFile()) {
            try {
                JSONObject o = new JSONObject(readText(json));
                m.id = cleanId(o.optString("id", d.getName()));
                m.name = o.optString("name", "");
                m.version = o.optString("version", "");
                m.author = o.optString("author", "");
                m.description = o.optString("description", "");
                JSONArray req = o.optJSONArray("requires");
                if (req != null)
                    for (int i = 0; i < req.length(); i++) m.requires.add(cleanId(req.optString(i)));
            } catch (Exception e) {
                return null;
            }
        } else if (m.files() == null && m.textures() == null) {
            return null;
        }
        if (m.id.isEmpty()) return null;
        if (m.name == null || m.name.isEmpty()) m.name = m.id;
        return m;
    }

    // ids are folder names: letters, digits, dot, dash and underscore
    static String cleanId(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-")
            .replaceAll("^[.-]+", "");
    }

    // The ids an enabled mod needs that are missing or off.
    public static List<String> missing(Mod m, List<Mod> all) {
        List<String> out = new ArrayList<>();
        for (String r : m.requires) {
            boolean ok = false;
            for (Mod o : all) if (o.id.equals(r) && o.enabled && all.indexOf(o) < all.indexOf(m)) ok = true;
            if (!ok) out.add(r);
        }
        return out;
    }

    public static boolean delete(Mod m) { return deleteTree(m.dir); }

    private static boolean deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        return f.delete();
    }

    // --- for the game (SunshineActivity) ----------------------------------------

    // SMS_MOD: the enabled mods' folders (dvd.cpp loads <folder>/files or /overlay), in order
    public static String discFolders(Context ctx) {
        StringBuilder sb = new StringBuilder();
        for (Mod m : list(ctx)) {
            if (!m.enabled || m.files() == null) continue;
            if (sb.length() > 0) sb.append(';');
            sb.append(m.dir.getAbsolutePath());
        }
        return sb.toString();
    }

    // the enabled mods' texture folders, in order (a later one wins)
    public static List<String> textureFolders(Context ctx) {
        List<String> out = new ArrayList<>();
        for (Mod m : list(ctx))
            if (m.enabled && m.textures() != null) out.add(m.textures().getAbsolutePath());
        return out;
    }

    // --- importing ---------------------------------------------------------------

    public interface Progress {
        void step(long bytes);
        boolean cancelled();
    }

    // Unpacks a .zip mod package into mods/<id>/ (replacing an older version of
    // the same mod) and returns it. Its mod.json may be at the top or in one
    // folder (a zip made of the mod's folder).
    public static Mod importZip(Context ctx, Uri uri, Progress progress) throws IOException {
        String prefix = findPrefix(ctx, uri);
        if (prefix == null) throw new IOException(L.t("This is not a mod package: there is no mod.json in it."));
        File root = dir(ctx);
        root.mkdirs();
        File tmp = new File(root, "import.tmp");
        deleteTree(tmp);
        tmp.mkdirs();
        long done = 0;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri); ZipInputStream zip = new ZipInputStream(in)) {
            byte[] buf = new byte[1 << 16];
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (progress.cancelled()) throw new IOException(L.t("Cancelled."));
                String name = e.getName().replace('\\', '/');
                if (!name.startsWith(prefix)) continue;
                name = name.substring(prefix.length());
                if (name.isEmpty()) continue;
                File out = new File(tmp, name);
                // nothing may land outside the mod's folder ("../" in a name)
                if (!out.getCanonicalPath().startsWith(tmp.getCanonicalPath() + File.separator))
                    throw new IOException(L.t("The package has a file outside its folder: %s", e.getName()));
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                out.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(out)) {
                    for (int n; (n = zip.read(buf)) > 0; ) {
                        o.write(buf, 0, n);
                        done += n;
                        progress.step(done);
                    }
                }
            }
        } catch (IOException e) {
            deleteTree(tmp);
            throw e;
        }
        Mod m = read(tmp);
        if (m == null) {
            deleteTree(tmp);
            throw new IOException(L.t("The package's mod.json cannot be read, or it has no id."));
        }
        File dest = new File(root, m.id);
        deleteTree(dest);
        if (!tmp.renameTo(dest)) {
            deleteTree(tmp);
            throw new IOException(L.t("Cannot move the mod into its folder."));
        }
        m.dir = dest;
        return m;
    }

    // "" when mod.json is at the zip's top, "folder/" when it is one folder down
    private static String findPrefix(Context ctx, Uri uri) throws IOException {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri); ZipInputStream zip = new ZipInputStream(in)) {
            String best = null;
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                String name = e.getName().replace('\\', '/');
                if (!name.endsWith("mod.json")) continue;
                String p = name.substring(0, name.length() - "mod.json".length());
                if (p.isEmpty() || (p.indexOf('/') == p.length() - 1 && p.length() > 1))
                    if (best == null || p.length() < best.length()) best = p;
            }
            return best;
        } catch (IllegalArgumentException e) {
            throw new IOException(L.t("This is not a .zip file."));
        }
    }

    private static String readText(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 1 << 20)];
            int n = 0;
            for (int r; n < b.length && (r = in.read(b, n, b.length - n)) > 0; ) n += r;
            return new String(b, 0, n, StandardCharsets.UTF_8);
        }
    }
}
