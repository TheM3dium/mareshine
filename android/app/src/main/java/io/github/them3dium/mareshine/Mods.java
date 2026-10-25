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
//   "image": file   a whole disc image the mod plays instead of the player's
//                own (a mod too big for an overlay, such as Super Mario Eclipse,
//                patched from the player's disc by ModInstallActivity); the
//                last such mod switched on is played
//   "code": true    the mod needs code built into the app (SMS_CODE_MODS)
//   "icon", "preview"   pictures in its folder (or https URLs): its label on
//                the mod list's disc, and the picture its page is filled with
//   "rating": 4     its suns until players have rated it
//   "options": [...]   the buttons on its page (ModPageActivity): toggles and
//                choices that switch folders or disc images, settings pages
//                and links; docs/MODS.md has the format
// Their order and which are on is settings.txt's SMS_MODS ("id:1;id:0", first
// loaded first, so a later mod wins); mods not listed there are off, after
// the listed ones. Changes apply when the game starts.
public final class Mods {
    public static final String KEY = "SMS_MODS";
    // the mods' option values: "mod.option=value;…" (Mod.state)
    public static final String OPTIONS_KEY = "SMS_MOD_OPTIONS";
    // The mod manager's entries in Settings and the quick menu
    public static final boolean MENU = true;

    private Mods() {}

    // A button on a mod's page (docs/MODS.md).
    public static final class Option {
        public String type = "", id = "", label = "", url = "", folder = "", image = "", def = "", text = "";
        public final List<Value> values = new ArrayList<>();   // a choice's
        public final List<Option> options = new ArrayList<>(); // a page's

        public boolean isToggle() { return type.equals("toggle"); }
        public boolean isChoice() { return type.equals("choice"); }
        public boolean isPage() { return type.equals("page"); }
        public boolean isLink() { return type.equals("link"); }

        public Value value(String id) {
            for (Value v : values) if (v.id.equals(id)) return v;
            return null;
        }
    }

    // One of a choice's values: what it switches on
    public static final class Value {
        public String id = "", label = "", folder = "", image = "", translator = "";
    }

    public static final class Mod {
        public String id, name, version = "", author = "", description = "", image = "", icon = "", preview = "";
        public String update = "";  // a URL to look for a newer version at (ModUpdates)
        public float rating = -1;
        public int authorColor;  // 0: the page's own
        public List<String> requires = new ArrayList<>();
        public final List<Option> options = new ArrayList<>();
        public File dir;
        public boolean enabled, code;

        // a picture of the mod's: a file in its folder, or a URL
        public String picture(String name) {
            if (name.isEmpty() || name.startsWith("https://")) return name;
            File f = new File(dir, new File(name).getName());
            return f.isFile() ? f.getAbsolutePath() : "";
        }

        // every option, those on its settings pages too
        public List<Option> allOptions() {
            List<Option> out = new ArrayList<>();
            collect(options, out);
            return out;
        }

        private static void collect(List<Option> in, List<Option> out) {
            for (Option o : in) {
                out.add(o);
                collect(o.options, out);
            }
        }

        // the disc image it plays, if it has one and it is there: a choice's
        // value may pick another (Super Mario Eclipse's languages)
        public File image() {
            String name = image;
            for (Option o : allOptions()) {
                String pick = "";
                if (o.isChoice()) {
                    Value v = o.value(value(o));
                    if (v != null) pick = v.image;
                } else if (o.isToggle() && value(o).equals("1")) {
                    pick = o.image;
                }
                if (!pick.isEmpty() && new File(dir, pick).isFile()) name = pick;
            }
            if (name.isEmpty()) return null;
            File f = new File(dir, name);
            return f.isFile() ? f : null;
        }

        // its options' current values, by option id (Mods.list fills it)
        public Map<String, String> state;

        public String value(Option o) {
            String v = state != null ? state.get(o.id) : null;
            if (v != null) return v;
            return o.isToggle() ? (o.def.equals("1") || o.def.equals("true") ? "1" : "0") : o.def;
        }

        // the folders its switched-on options add (each with files/ and textures/)
        public List<File> optionFolders() {
            List<File> out = new ArrayList<>();
            for (Option o : allOptions()) {
                String f = "";
                if (o.isToggle() && value(o).equals("1")) f = o.folder;
                if (o.isChoice()) {
                    Value v = o.value(value(o));
                    if (v != null) f = v.folder;
                }
                if (!f.isEmpty()) {
                    File d = new File(dir, f);
                    if (d.isDirectory()) out.add(d);
                }
            }
            return out;
        }

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
        loadCatalog(ctx);
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
        Map<String, Map<String, String>> state = options(ctx);
        for (Mod m : out) {
            m.state = state.get(m.id);
            if (m.state == null) m.state = new LinkedHashMap<>();
        }
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

    // Switches a mod on or off; one game is played, so a mod with its own disc
    // image switches the others with one off.
    public static void setEnabled(Context ctx, String id, boolean on) {
        List<Mod> all = list(ctx);
        Mod mod = null;
        for (Mod m : all) if (m.id.equals(id)) mod = m;
        if (mod == null) return;
        mod.enabled = on;
        if (on && !mod.image.isEmpty())
            for (Mod o : all) if (o != mod && !o.image.isEmpty()) o.enabled = false;
        save(ctx, all);
    }

    public static Mod find(Context ctx, String id) {
        for (Mod m : list(ctx)) if (m.id.equals(id)) return m;
        return null;
    }

    // The player's own rating of a mod (1 to 5 suns; 0: none), kept in
    // settings.txt until it can be sent (the rating service)
    public static final String RATINGS_KEY = "SMS_MOD_RATINGS";

    public static int myRating(Context ctx, String id) {
        for (String e : Settings.read(ctx).getOrDefault(RATINGS_KEY, "").split(";")) {
            int eq = e.indexOf('=');
            if (eq > 0 && e.substring(0, eq).equals(id)) {
                try {
                    return Math.max(0, Math.min(5, Integer.parseInt(e.substring(eq + 1))));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0;
    }

    public static void setMyRating(Context ctx, String id, int suns) {
        StringBuilder sb = new StringBuilder();
        for (String e : Settings.read(ctx).getOrDefault(RATINGS_KEY, "").split(";")) {
            int eq = e.indexOf('=');
            if (eq <= 0 || e.substring(0, eq).equals(id)) continue;
            sb.append(sb.length() > 0 ? ";" : "").append(e);
        }
        if (suns > 0) sb.append(sb.length() > 0 ? ";" : "").append(id).append('=').append(suns);
        Map<String, String> s = Settings.read(ctx);
        s.put(RATINGS_KEY, sb.toString());
        Settings.write(ctx, s);
    }

    // every mod's option values, by mod id
    static Map<String, Map<String, String>> options(Context ctx) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (String entry : Settings.read(ctx).getOrDefault(OPTIONS_KEY, "").split(";")) {
            int dot = entry.indexOf('.'), eq = entry.indexOf('=');
            if (dot <= 0 || eq <= dot + 1) continue;
            Map<String, String> m = out.get(entry.substring(0, dot));
            if (m == null) out.put(entry.substring(0, dot), m = new LinkedHashMap<>());
            m.put(entry.substring(dot + 1, eq), entry.substring(eq + 1));
        }
        return out;
    }

    // Sets one option's value (ids and values are cleanId'd: no ';' '=' in them)
    public static void setOption(Context ctx, Mod mod, Option o, String value) {
        mod.state.put(o.id, cleanId(value));
        Map<String, Map<String, String>> all = options(ctx);
        all.put(mod.id, mod.state);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Map<String, String>> m : all.entrySet())
            for (Map.Entry<String, String> e : m.getValue().entrySet()) {
                if (sb.length() > 0) sb.append(';');
                sb.append(m.getKey()).append('.').append(e.getKey()).append('=').append(e.getValue());
            }
        Map<String, String> s = Settings.read(ctx);
        s.put(OPTIONS_KEY, sb.toString());
        Settings.write(ctx, s);
    }

    // SMS_MOD_OPTIONS for the game: the switched-on mods' option values, for
    // code built into the app that reads them
    public static String optionsForGame(Context ctx) {
        StringBuilder sb = new StringBuilder();
        for (Mod m : list(ctx)) {
            if (!m.enabled) continue;
            for (Option o : m.allOptions()) {
                if (o.id.isEmpty() || !(o.isToggle() || o.isChoice())) continue;
                if (sb.length() > 0) sb.append(';');
                sb.append(m.id).append('.').append(o.id).append('=').append(m.value(o));
            }
        }
        return sb.toString();
    }

    // a text in the app's language when the mod has it ("description_de"), else as it is
    static String loc(JSONObject o, String key, String def) {
        String lang = L.loaded();
        String own = lang.isEmpty() ? "" : o.optString(key + "_" + lang, "");
        return own.isEmpty() ? o.optString(key, def) : own;
    }

    static List<Option> parseOptions(JSONArray a) {
        List<Option> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject j = a.optJSONObject(i);
            if (j == null) continue;
            Option o = new Option();
            o.type = j.optString("type", "toggle");
            o.id = cleanId(j.optString("id", ""));
            o.label = loc(j, "label", o.id);
            o.text = loc(j, "text", "");
            o.url = j.optString("url", "");
            o.folder = cleanPath(j.optString("folder", ""));
            o.image = new File(j.optString("image", "")).getName();
            Object def = j.opt("default");
            o.def = def instanceof Boolean ? ((Boolean) def ? "1" : "0") : def != null ? cleanId(def.toString()) : "";
            JSONArray vals = j.optJSONArray("values");
            if (vals != null)
                for (int k = 0; k < vals.length(); k++) {
                    JSONObject vj = vals.optJSONObject(k);
                    Value v = new Value();
                    if (vj == null) {
                        v.id = cleanId(vals.optString(k));
                        v.label = vals.optString(k);
                    } else {
                        v.id = cleanId(vj.optString("id", ""));
                        v.label = loc(vj, "label", v.id);
                        v.folder = cleanPath(vj.optString("folder", ""));
                        v.image = new File(vj.optString("image", "")).getName();
                        v.translator = vj.optString("translator", "");
                    }
                    if (!v.id.isEmpty()) o.values.add(v);
                }
            if (o.isChoice() && o.def.isEmpty() && !o.values.isEmpty()) o.def = o.values.get(0).id;
            o.options.addAll(parseOptions(j.optJSONArray("options")));
            if ((o.isToggle() || o.isChoice()) && o.id.isEmpty()) continue;  // nothing to keep its value under
            out.add(o);
        }
        return out;
    }

    // a folder inside the mod's: no "..", no leading "/"
    static String cleanPath(String p) {
        if (p == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String part : p.replace('\\', '/').split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(part);
        }
        return sb.toString();
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
                m.name = loc(o, "name", "");
                m.version = o.optString("version", "");
                m.author = o.optString("author", "");
                m.description = loc(o, "description", "");
                m.image = new File(o.optString("image", "")).getName();  // a file in its folder
                m.code = o.optBoolean("code", false);
                m.icon = o.optString("icon", "");
                m.preview = o.optString("preview", "");
                m.update = o.optString("update", "");
                m.rating = (float) o.optDouble("rating", -1);
                try {
                    String c = o.optString("author_color", "");
                    if (!c.isEmpty()) m.authorColor = 0xFF000000 | android.graphics.Color.parseColor(c);
                } catch (IllegalArgumentException ignored) {
                }
                m.options.addAll(parseOptions(o.optJSONArray("options")));
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

    // The enabled mod whose disc image is played (the last one), or null
    public static Mod activeImage(Context ctx) {
        Mod out = null;
        for (Mod m : list(ctx)) if (m.enabled && m.image() != null) out = m;
        return out;
    }

    // whether this build of the app has the code mods built in (Gradle's
    // -Pmareshine.eclipse=ON; docs/ECLIPSE.md)
    public static boolean codeBuiltIn(Context ctx) { return ctx.getResources().getBoolean(R.bool.code_mods); }

    // --- mods to download: patched from the player's own disc -------------------

    public static final class Release {
        public final String id, name, version, author, description, page;
        public final String[] urls;               // the first that works
        public final String file, md5, patch;     // the archive, its MD5, the delta in it
        public final long size, imageSize;        // the archive, the patched image
        public final String sourceMd5, resultMd5, image;
        public final boolean code;
        // a translation: the mod it translates and its language; picked by
        // the player from its download page (no urls), until its makers allow
        // the app to download it
        public String base = "", language = "", credits = "";
        // for the Search tab: checked by us, its suns until players rate it, its disc label (a URL)
        public boolean tested = true;
        public float rating = -1;
        public String icon = "";

        Release(String id, String name, String version, String author, String description, String page, String[] urls,
                String file, long size, String md5, String patch, String sourceMd5, String resultMd5, long imageSize,
                String image, boolean code) {
            this.id = id; this.name = name; this.version = version; this.author = author; this.description = description;
            this.page = page; this.urls = urls; this.file = file; this.size = size; this.md5 = md5; this.patch = patch;
            this.sourceMd5 = sourceMd5; this.resultMd5 = resultMd5; this.imageSize = imageSize; this.image = image;
            this.code = code;
        }

        // an entry of the online catalog (the keys as the fields; tools/ratings-worker/catalog.json)
        static Release from(JSONObject o) {
            String id = cleanId(o.optString("id", ""));
            JSONArray u = o.optJSONArray("urls");
            String[] urls = new String[u != null ? u.length() : 0];
            for (int i = 0; i < urls.length; i++) urls[i] = u.optString(i);
            Release r = new Release(id, o.optString("name", id), o.optString("version", ""),
                o.optString("author", ""), o.optString("description", ""), o.optString("page", ""), urls,
                new File(o.optString("file", "")).getName(), o.optLong("size", 0), o.optString("md5", ""),
                o.optString("patch", ""), o.optString("sourceMd5", ""), o.optString("resultMd5", ""),
                o.optLong("imageSize", 0), new File(o.optString("image", "")).getName(), o.optBoolean("code", false));
            if (id.isEmpty() || r.image.isEmpty() || r.size <= 0 || r.md5.length() != 32) return null;
            r.base = cleanId(o.optString("base", ""));
            r.language = o.optString("language", "");
            r.credits = o.optString("credits", "");
            r.tested = o.optBoolean("tested", true);
            r.rating = (float) o.optDouble("rating", -1);
            r.icon = o.optString("icon", "");
            return r;
        }
    }

    // Super Mario Eclipse v1.1.0 as tools/mods/get.py installs it: the
    // GameBanana release (mirrored for SMS Launcher; GameBanana the fallback),
    // its xdelta patch applied to the North American disc (GMSE01, rev 0)
    public static final Release ECLIPSE = new Release("eclipse", "Super Mario Eclipse", "v1.1.0", "Eclipse Team",
        "New stages, characters and challenges: a whole new adventure.", "https://gamebanana.com/mods/536309",
        new String[] {"https://sms-eclipse.tekrantgaming.com/files/super_mario_eclipse_v110.7z",
            "https://gamebanana.com/dl/1729332"},
        "super_mario_eclipse_v110.7z", 892257115L, "37c1805ab88b2a3bb2a96bcdf6884433", "patches/v1.1.0.xdelta",
        "0c6d2edae9fdf40dfc410ff1623e4119", "caa546309e0443f7b47632b040a250d7", 1459978240L,
        "Super Mario Eclipse v1.1.0.iso", true);

    // Super Mario Eclipse in German, by the German Translation Team: the same
    // kind of patch onto the North American disc
    public static final Release ECLIPSE_DE = new Release("eclipse-de", "Super Mario Eclipse (Deutsch)", "v1.1.0",
        "Eclipse Team", "New stages, characters and challenges: a whole new adventure.",
        "https://gamebanana.com/mods/666102", new String[0], "super_mario_eclipse_v110_de_e0349.7z", 904477530L,
        "394a610f2e568e281129f281947eef2d", "patches/v1.1.0 DE.xdelta", "0c6d2edae9fdf40dfc410ff1623e4119",
        "620ee2e30428ee38148b5041306b0b71", 1459978240L, "Super Mario Eclipse v1.1.0 DE.iso", true);

    static {
        ECLIPSE.rating = 4;
        ECLIPSE_DE.base = "eclipse";
        ECLIPSE_DE.language = "Deutsch";
        ECLIPSE_DE.credits = "German Translation Team";
    }

    // The releases the app offers: those built in, replaced or added to by the
    // online catalog (ModUpdates fetches it into catalog.json in the app's files)
    private static final List<Release> RELEASES = new ArrayList<>(Arrays.asList(ECLIPSE, ECLIPSE_DE));
    private static long sCatalogTime = -1;

    static File catalogFile(Context ctx) { return new File(ctx.getFilesDir(), "catalog.json"); }

    static synchronized void loadCatalog(Context ctx) {
        File f = catalogFile(ctx);
        long t = f.isFile() ? f.lastModified() : 0;
        if (t == sCatalogTime) return;
        sCatalogTime = t;
        List<Release> all = new ArrayList<>(Arrays.asList(ECLIPSE, ECLIPSE_DE));
        if (t != 0) {
            try {
                JSONArray a = new JSONObject(readText(f)).optJSONArray("releases");
                for (int i = 0; a != null && i < a.length(); i++) {
                    Release r = Release.from(a.getJSONObject(i));
                    if (r == null) continue;
                    for (int k = all.size() - 1; k >= 0; k--) if (all.get(k).id.equals(r.id)) all.remove(k);
                    all.add(r);
                }
            } catch (Exception ignored) {  // a damaged file: the built-in ones
            }
        }
        RELEASES.clear();
        RELEASES.addAll(all);
    }

    public static synchronized Release release(String id) {
        for (Release r : RELEASES) if (r.id.equals(id)) return r;
        return null;
    }

    // the Search tab's mods (translations come with their mod)
    public static List<Release> catalog(Context ctx) {
        loadCatalog(ctx);
        List<Release> out = new ArrayList<>();
        synchronized (Mods.class) {
            for (Release r : RELEASES) {
                if (!r.base.isEmpty() || (r.code && !codeBuiltIn(ctx))) continue;
                out.add(r);
            }
        }
        return out;
    }

    public static boolean installed(Context ctx, Release r) {
        Mod m = find(ctx, r.id);
        return m != null && m.image() != null;
    }

    // the translations of a mod
    public static List<Release> translations(String base) {
        List<Release> out = new ArrayList<>();
        synchronized (Mods.class) {
            for (Release r : RELEASES) if (r.base.equals(base)) out.add(r);
        }
        return out;
    }

    // the translation a picked file of this size is, or null
    public static Release translationOfSize(long size) {
        synchronized (Mods.class) {
            for (Release r : RELEASES) if (!r.base.isEmpty() && r.size == size) return r;
        }
        return null;
    }

    // SMS_MOD: the enabled mods' folders (dvd.cpp loads <folder>/files or /overlay), in order
    public static String discFolders(Context ctx) {
        StringBuilder sb = new StringBuilder();
        for (Mod m : list(ctx)) {
            if (!m.enabled) continue;
            List<File> dirs = new ArrayList<>();
            if (m.files() != null) dirs.add(m.dir);
            for (File f : m.optionFolders())
                if (new File(f, "files").isDirectory() || new File(f, "overlay").isDirectory()) dirs.add(f);
            for (File d : dirs) {
                if (sb.length() > 0) sb.append(';');
                sb.append(d.getAbsolutePath());
            }
        }
        return sb.toString();
    }

    // the enabled mods' texture folders, in order (a later one wins)
    public static List<String> textureFolders(Context ctx) {
        List<String> out = new ArrayList<>();
        for (Mod m : list(ctx)) {
            if (!m.enabled) continue;
            if (m.textures() != null) out.add(m.textures().getAbsolutePath());
            for (File f : m.optionFolders())
                if (new File(f, "textures").isDirectory()) out.add(new File(f, "textures").getAbsolutePath());
        }
        return out;
    }

    // --- importing ---------------------------------------------------------------

    public interface Progress {
        void step(long bytes);
        boolean cancelled();
    }

    // Unpacks a .zip mod package into mods/<id>/ (replacing an older version of
    // the same mod) and returns it. Its mod.json may be at the top or in one
    // folder (a zip made of the mod's folder). A Dolphin texture pack (a zip of
    // tex1_ PNGs or DDSs, such as Super Mario Eclipse's HD textures) becomes a
    // mod of its own with them in textures/, named after the zip.
    public static Mod importZip(Context ctx, Uri uri, Progress progress) throws IOException {
        String prefix = findPrefix(ctx, uri);
        boolean pack = prefix == null && isTexturePack(ctx, uri);
        if (pack) prefix = "";
        else if (prefix == null) throw new IOException(L.t("This is not a mod package: there is no mod.json in it."));
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
                if (pack) {
                    if (e.isDirectory() || !isTexture(name)) continue;
                    name = "textures/" + name;
                }
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
        if (pack) {
            String zipName = displayName(ctx, uri).replaceAll("(?i)\\.zip$", "");
            JSONObject o = new JSONObject();
            try {
                o.put("id", cleanId(zipName)).put("name", zipName.replace('_', ' '));
            } catch (org.json.JSONException ignored) {
            }
            try (OutputStream out = new FileOutputStream(new File(tmp, "mod.json"))) {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            }
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

    // Unpacks a Dolphin texture pack's textures into dest/ (replacing what is
    // there), for a mod's own HD textures (Super Mario Eclipse's hd/textures).
    // Returns how many there were.
    public static int importTextures(Context ctx, Uri uri, File dest, Progress progress) throws IOException {
        File tmp = new File(dest.getParentFile(), dest.getName() + ".tmp");
        deleteTree(tmp);
        tmp.mkdirs();
        int count = 0;
        long done = 0;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri); ZipInputStream zip = new ZipInputStream(in)) {
            byte[] buf = new byte[1 << 16];
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (progress.cancelled()) throw new IOException(L.t("Cancelled."));
                String name = e.getName().replace('\\', '/');
                if (e.isDirectory() || !isTexture(name)) continue;
                File out = new File(tmp, cleanPath(name));
                if (!out.getCanonicalPath().startsWith(tmp.getCanonicalPath() + File.separator)) continue;
                out.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(out)) {
                    for (int n; (n = zip.read(buf)) > 0; ) {
                        o.write(buf, 0, n);
                        done += n;
                        progress.step(done);
                    }
                }
                count++;
            }
        } catch (IOException | IllegalArgumentException e) {
            deleteTree(tmp);
            throw e instanceof IOException ? (IOException) e : new IOException(L.t("This is not a .zip file."));
        }
        if (count == 0) {
            deleteTree(tmp);
            throw new IOException(L.t("There are no Dolphin textures (tex1_….png or .dds) in this file."));
        }
        deleteTree(dest);
        if (!tmp.renameTo(dest)) {
            deleteTree(tmp);
            throw new IOException(L.t("Cannot move the textures into their folder."));
        }
        return count;
    }

    // a Dolphin texture's file name: tex1_….png or .dds
    private static boolean isTexture(String path) {
        String n = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return n.startsWith("tex1_") && (n.endsWith(".png") || n.endsWith(".dds"));
    }

    // whether the zip holds Dolphin textures (found among its first entries)
    private static boolean isTexturePack(Context ctx, Uri uri) throws IOException {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri); ZipInputStream zip = new ZipInputStream(in)) {
            int seen = 0;
            for (ZipEntry e; (e = zip.getNextEntry()) != null && seen < 200; seen++)
                if (!e.isDirectory() && isTexture(e.getName().replace('\\', '/'))) return true;
            return false;
        } catch (IllegalArgumentException e) {
            throw new IOException(L.t("This is not a .zip file."));
        }
    }

    static String displayName(Context ctx, Uri uri) {
        try (android.database.Cursor c = ctx.getContentResolver().query(uri,
                new String[] {android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last != null ? last.substring(last.lastIndexOf('/') + 1) : "textures";
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
