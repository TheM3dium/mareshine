package io.github.them3dium.mareshine;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// A language pack (Settings → GAME → Game language) in a mod's own game, such
// as Super Mario Sunburn: the pack's files go in only where the mod left the
// North American disc's file as it was, so a text the mod changed keeps the
// mod's (English) wording. The files to leave out are worked out once per mod
// image and pack, compared with the player's own disc, and kept beside the
// mod (language-<code>.skip); the game reads them through SMS_LANGUAGE_SKIP
// (platform/dvd/dvd.cpp). Eclipse brings its own languages and changes
// nearly every file: no pack for it.
final class ModLanguage {
    private ModLanguage() {}

    static boolean applies(Mods.Mod m) { return m != null && !m.id.equals(EclipseMod.ID) && !m.id.startsWith(EclipseMod.ID + "-"); }

    // the game's chosen language when a pack for it is installed, else null
    static String packCode(Context ctx) {
        String code = Settings.read(ctx).getOrDefault("language", "en").trim().toLowerCase(Locale.ROOT);
        if (code.isEmpty() || code.equals("en")) return null;
        return new File(new File(DataDir.languagesDir(ctx), code), "files").isDirectory() ? code : null;
    }

    // The buttons a mod's own game (not Eclipse's) gets on its page, before its
    // own: Language (English, or an installed pack: the game language set in
    // Settings, which the button changes) and HD Textures (off, Mareshine's
    // pack, made for Sunshine, which the mod mostly keeps, or a pack of its own
    // imported into hd/textures).
    static final String LANGUAGE = "language", HD = "hd", HD_OFF = "off", HD_DEFAULT = "default", HD_OWN = "own";

    static void addOptions(Context ctx, Mods.Mod m) {
        if (m.image.isEmpty() || !applies(m)) return;
        List<Mods.Option> own = new ArrayList<>();
        List<String> codes = packCodes(ctx);
        if (!codes.isEmpty()) {
            Mods.Option o = new Mods.Option();
            o.type = "choice";
            o.id = LANGUAGE;
            o.label = L.t("Language");
            String cur = packCode(ctx);
            o.def = cur != null ? cur : "en";
            m.state.remove(LANGUAGE);  // not the mod's: Settings' game language
            o.values.add(value("en", "English " + L.t("(default)")));
            for (String c : codes) o.values.add(value(c, LanguagePackActivity.languageName(c)));
            own.add(o);
        }
        // the toggle it was before
        String was = m.state.get(HD);
        if ("1".equals(was)) m.state.put(HD, HD_DEFAULT);
        if ("0".equals(was)) m.state.put(HD, HD_OFF);
        Mods.Option hd = new Mods.Option();
        hd.type = "choice";
        hd.id = HD;
        hd.label = L.t("HD Textures");
        hd.def = HD_DEFAULT;
        hd.text = L.t("Mareshine's HD texture pack is made for Super Mario Sunshine and fits most of %s. Or import "
            + "another Dolphin texture pack (.zip) for it.", m.name);
        hd.values.add(value(HD_OFF, L.t("Off")));
        hd.values.add(value(HD_DEFAULT, L.t("Default pack")));
        hd.values.add(value(HD_OWN, L.t("Own pack")));
        own.add(hd);
        m.options.addAll(0, own);
    }

    private static Mods.Value value(String id, String label) {
        Mods.Value v = new Mods.Value();
        v.id = id;
        v.label = label;
        return v;
    }

    // the installed language packs
    static List<String> packCodes(Context ctx) {
        List<String> out = new ArrayList<>();
        File[] fs = DataDir.languagesDir(ctx).listFiles();
        if (fs != null) {
            Arrays.sort(fs);
            for (File f : fs)
                if (!f.getName().startsWith(".") && new File(f, "files").isDirectory()) out.add(f.getName());
        }
        return out;
    }

    // the pack the mod's game is played with: the game language's
    static String code(Context ctx, Mods.Mod m) { return applies(m) ? packCode(ctx) : null; }

    // its own HD texture pack (HD Textures → Own pack)
    static File texturesDir(Mods.Mod m) { return new File(new File(m.dir, HD), "textures"); }

    // its HD Textures button: Mareshine's pack (on unless turned off), its own instead when picked and there
    static String hd(Mods.Mod m) {
        if (!applies(m)) return HD_DEFAULT;
        String v = m.state != null ? m.state.get(HD) : null;
        if (HD_OFF.equals(v) || "0".equals(v)) return HD_OFF;
        if (HD_OWN.equals(v) && texturesDir(m).isDirectory()) return HD_OWN;
        return HD_DEFAULT;
    }

    static File skipFile(Mods.Mod m, String code) { return new File(m.dir, "language-" + code + ".skip"); }

    // what the cache is valid for: the mod's image, the pack and the player's disc
    private static String key(File image, File pack, File us) {
        return "v3 " + image.length() + "-" + image.lastModified() + " " + pack.lastModified() + " " + us.length() + "-" + us.lastModified();
    }

    // The pack's texts merged into the mod's own files where the mod changed
    // them, as a disc file mod (files/) given after the pack (SMS_MOD).
    static File mergedDir(Mods.Mod m, String code) { return new File(m.dir, "language-" + code); }

    // The skip list, made now if it is missing or out of date (seconds: the
    // stages' archives of both discs are unpacked); null when it cannot be made.
    // A file the mod changed is left out of the pack, and made again from the
    // mod's own (mergedDir): a message the mod kept, or took from elsewhere in
    // the game, gets the pack's text; one with only a name changed in it gets
    // that change in the pack's text; only what is new stays English. In a
    // whole archive (the options screen) the mod's changed pictures go into
    // the pack's.
    // the skip list made already for this mod image, pack and disc
    static boolean ready(Context ctx, Mods.Mod m, String code, File usDisc) {
        File image = m.image(), files = new File(new File(DataDir.languagesDir(ctx), code), "files"), out = skipFile(m, code);
        if (image == null || usDisc == null || !out.isFile()) return false;
        try {
            return new String(read(out), StandardCharsets.UTF_8).split("\n", 2)[0].equals("# " + key(image, files, usDisc));
        } catch (IOException e) {
            return false;
        }
    }

    static File prepare(Context ctx, Mods.Mod m, String code, File usDisc) {
        File image = m.image(), files = new File(new File(DataDir.languagesDir(ctx), code), "files");
        if (image == null || usDisc == null || !usDisc.isFile() || !files.isDirectory()) return null;
        File out = skipFile(m, code), merged = new File(mergedDir(m, code), "files");
        String key = key(image, files, usDisc);
        try {
            if (out.isFile()) {
                String head = new String(java.nio.file.Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8).split("\n", 2)[0];
                if (head.equals("# " + key)) return out;
            }
            deleteTree(mergedDir(m, code));
            List<String> rel = new ArrayList<>();
            list(files, "", rel);
            List<String> skip = new ArrayList<>();
            // by disc file, one archive pair in memory at a time
            Map<String, List<String>> byDisc = new java.util.LinkedHashMap<>();
            for (String r : rel) {
                int cut = archiveEnd(r);
                String disc = "/" + (cut < 0 ? r : r.substring(0, cut));
                List<String> l = byDisc.get(disc);
                if (l == null) byDisc.put(disc, l = new ArrayList<>());
                l.add(r);
            }
            // every message of the North American disc in the pack's files: where it is
            Map<String, String[]> where = new java.util.HashMap<>();
            int total = 0, english = 0;
            try (LanguagePackBuilder.Disc us = new LanguagePackBuilder.Disc(usDisc);
                 LanguagePackBuilder.Disc mod = new LanguagePackBuilder.Disc(image)) {
                for (Map.Entry<String, List<String>> e : byDisc.entrySet()) {
                    String disc = e.getKey();
                    if (!us.has(disc)) continue;
                    Map<String, LanguagePackBuilder.Ent> ia = null;
                    for (String r : e.getValue()) {
                        int cut = archiveEnd(r);
                        if (cut < 0 || !r.toLowerCase(Locale.ROOT).endsWith(".bmg")) continue;
                        if (ia == null) ia = open(us.read(disc));
                        LanguagePackBuilder.Ent x = ia.get(("/" + r.substring(cut + 1)).toLowerCase(Locale.ROOT));
                        if (x == null) continue;
                        List<byte[]> texts = texts(x.data);
                        total += texts.size();
                        for (int i = 0; i < texts.size(); i++)
                            where.putIfAbsent(latin(texts.get(i)), new String[] {r, Integer.toString(i)});
                    }
                }
                for (Map.Entry<String, List<String>> e : byDisc.entrySet()) {
                    String disc = e.getKey();
                    if (!us.has(disc) || !mod.has(disc)) { skip.addAll(e.getValue()); continue; }
                    byte[] a = us.read(disc), b = mod.read(disc);
                    if (Arrays.equals(md5(a), md5(b))) continue;  // the mod left it as it was
                    Map<String, LanguagePackBuilder.Ent> ia = null, ib = null;
                    for (String r : e.getValue()) {
                        int cut = archiveEnd(r);
                        if (cut < 0) {  // a whole archive: the pack's, with the mod's changed files in it
                            skip.add(r);
                            if (ia == null) { ia = open(a); ib = open(b); }
                            LanguagePackBuilder.Rarc pack = new LanguagePackBuilder.Rarc(unpack(read(new File(files, r))));
                            for (Map.Entry<String, LanguagePackBuilder.Ent> f : ib.entrySet()) {
                                LanguagePackBuilder.Ent was = ia.get(f.getKey());
                                if (f.getValue().data != null && (was == null || !Arrays.equals(was.data, f.getValue().data)))
                                    pack.put(f.getKey(), f.getValue().data);
                            }
                            write(new File(merged, r), LanguagePackBuilder.yaz0Store(pack.build()));
                            continue;
                        }
                        if (ia == null) { ia = open(a); ib = open(b); }
                        String inner = ("/" + r.substring(cut + 1)).toLowerCase(Locale.ROOT);
                        LanguagePackBuilder.Ent x = ia.get(inner), y = ib.get(inner);
                        if (x != null && y != null && Arrays.equals(x.data, y.data)) continue;
                        skip.add(r);
                        if (y == null || !r.toLowerCase(Locale.ROOT).endsWith(".bmg")) continue;
                        int[] left = new int[1];
                        byte[] bmg = mergeBmg(x == null ? null : x.data, y.data, read(new File(files, r)), files, where, left);
                        english += left[0];
                        if (bmg != null) write(new File(merged, r), bmg);
                        else english += texts(y.data).size();
                    }
                }
            }
            int pct = total > 0 ? Math.max(0, Math.min(100, (int) Math.floor(100.0 * (total - english) / total))) : 100;
            StringBuilder sb = new StringBuilder("# ").append(key).append('\n').append("# percent ").append(pct).append('\n');
            for (String s2 : skip) sb.append(s2).append('\n');
            try (OutputStream o = new FileOutputStream(out)) { o.write(sb.toString().getBytes(StandardCharsets.UTF_8)); }
            return out;
        } catch (Exception | OutOfMemoryError e) {
            out.delete();
            deleteTree(mergedDir(m, code));
            return null;
        }
    }

    // The mod's message file with the pack's texts; left[0]: messages left English.
    private static byte[] mergeBmg(byte[] us, byte[] mod, byte[] pack, File files, Map<String, String[]> where, int[] left) {
        try {
            List<byte[]> u = us == null ? new ArrayList<>() : texts(us), mt = texts(mod), pt = texts(pack);
            Map<String, List<byte[]>> others = new java.util.HashMap<>();
            List<byte[]> outTexts = new ArrayList<>();
            for (int i = 0; i < mt.size(); i++) {
                byte[] t = mt.get(i), pick = null;
                if (i < u.size() && i < pt.size() && Arrays.equals(t, u.get(i))) pick = pt.get(i);
                if (pick == null) {
                    String[] w = where.get(latin(t));
                    if (w != null) {
                        List<byte[]> o = others.get(w[0]);
                        if (o == null) others.put(w[0], o = texts(read(new File(files, w[0]))));
                        int k = Integer.parseInt(w[1]);
                        if (k < o.size()) pick = o.get(k);
                    }
                }
                if (pick == null && i < u.size() && i < pt.size()) pick = renamed(u.get(i), t, pt.get(i));
                if (pick == null) { pick = t; left[0]++; }
                outTexts.add(pick);
            }
            return withTexts(mod, outTexts);
        } catch (Exception e) {
            return null;
        }
    }

    // a message the mod changed only by one piece (a name): the same change in the translation
    private static byte[] renamed(byte[] us, byte[] mod, byte[] tr) {
        int a = 0;
        while (a < us.length && a < mod.length && us[a] == mod[a]) a++;
        int b = 0;
        while (b < us.length - a && b < mod.length - a && us[us.length - 1 - b] == mod[mod.length - 1 - b]) b++;
        // back to a word's start, so "Sunshine" -> "Sunburn!" is found as a whole
        while (a > 0 && Character.isLetter((char) (us[a - 1] & 0xFF))) a--;
        String old = latin(Arrays.copyOfRange(us, a, us.length - b)), now = latin(Arrays.copyOfRange(mod, a, mod.length - b));
        if (old.length() < 3 || old.indexOf('\n') >= 0) return null;
        String t = latin(tr);
        int at = t.indexOf(old);
        // the game's name changed, and the translation does not name it ("Dieses Spiel"): it fits as it is
        if (at < 0 && old.contains("Sunshine") && !t.contains("Sunshine")) return tr;
        if (at < 0 || t.indexOf(old, at + 1) >= 0) return null;
        return (t.substring(0, at) + now + t.substring(at + old.length())).getBytes(StandardCharsets.ISO_8859_1);
    }

    // a BMG file's messages, as bytes (control codes, 0x1A + length, included)
    private static List<byte[]> texts(byte[] d) throws IOException {
        LanguagePackBuilder.Bmg b = LanguagePackBuilder.bmgSections(d);
        byte[] dat = null;
        for (byte[][] s2 : b.secs) if (LanguagePackBuilder.latin1(s2[0], 0, 4).equals("DAT1")) dat = s2[1];
        if (dat == null) throw new IOException("no DAT1");
        int[] es = new int[1];
        List<byte[]> out = new ArrayList<>();
        for (byte[] e : LanguagePackBuilder.bmgEntries(d, es)) {
            int o = LanguagePackBuilder.u32(e, 0), j = o;
            while (j < dat.length && dat[j] != 0) j += dat[j] == 0x1A && j + 1 < dat.length ? Math.max(2, dat[j + 1] & 0xFF) : 1;
            out.add(Arrays.copyOfRange(dat, o, Math.min(j, dat.length)));
        }
        return out;
    }

    // the BMG file with these messages, its attributes as they were
    private static byte[] withTexts(byte[] d, List<byte[]> texts) throws IOException {
        LanguagePackBuilder.Bmg b = LanguagePackBuilder.bmgSections(d);
        int[] es = new int[1];
        List<byte[]> ents = LanguagePackBuilder.bmgEntries(d, es);
        java.io.ByteArrayOutputStream dat = new java.io.ByteArrayOutputStream(), inf = new java.io.ByteArrayOutputStream();
        dat.write(0);
        for (int i = 0; i < ents.size(); i++) {
            byte[] e = ents.get(i).clone();
            LanguagePackBuilder.p32(e, 0, dat.size());
            inf.write(e, 0, e.length);
            byte[] t = texts.get(i);
            dat.write(t, 0, t.length);
            dat.write(0);
        }
        for (byte[][] s2 : b.secs) {
            String tag = LanguagePackBuilder.latin1(s2[0], 0, 4);
            if (tag.equals("INF1")) s2[1] = LanguagePackBuilder.cat(LanguagePackBuilder.sub(s2[1], 0, 8), inf.toByteArray());
            else if (tag.equals("DAT1")) s2[1] = dat.toByteArray();
        }
        return LanguagePackBuilder.bmgBuild(b.head, b.secs);
    }

    private static String latin(byte[] b) { return new String(b, StandardCharsets.ISO_8859_1); }

    private static byte[] unpack(byte[] d) {
        return d.length > 4 && d[0] == 'Y' && d[1] == 'a' && d[2] == 'z' && d[3] == '0' ? LanguagePackBuilder.yaz0Decode(d) : d;
    }

    private static byte[] read(File f) throws IOException { return java.nio.file.Files.readAllBytes(f.toPath()); }

    private static void write(File f, byte[] d) throws IOException {
        f.getParentFile().mkdirs();
        try (OutputStream o = new FileOutputStream(f)) { o.write(d); }
    }

    private static void deleteTree(File f) {
        File[] cs = f.listFiles();
        if (cs != null) for (File c : cs) deleteTree(c);
        f.delete();
    }

    // how much of the mod's text is in the pack's language, in percent; -1 when unknown
    static int percent(File skip) {
        try {
            for (String l : new String(read(skip), StandardCharsets.UTF_8).split("\n"))
                if (l.startsWith("# percent ")) return Integer.parseInt(l.substring(10).trim());
        } catch (IOException | NumberFormatException ignored) {
        }
        return -1;
    }

    // where a disc archive's name ends in a pack path (data/scene/bianco0.szs/map/message.bmg), else -1
    private static int archiveEnd(String r) {
        String low = r.toLowerCase(Locale.ROOT);
        for (String ext : new String[] {".szs/", ".arc/"}) {
            int i = low.indexOf(ext);
            if (i >= 0) return i + ext.length() - 1;
        }
        return -1;
    }

    private static Map<String, LanguagePackBuilder.Ent> open(byte[] d) { return new LanguagePackBuilder.Rarc(unpack(d)).files(); }

    private static void list(File dir, String prefix, List<String> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        Arrays.sort(fs);
        for (File f : fs) {
            String r = prefix.isEmpty() ? f.getName() : prefix + "/" + f.getName();
            if (f.isDirectory()) list(f, r, out);
            else out.add(r);
        }
    }

    private static byte[] md5(byte[] d) throws Exception { return MessageDigest.getInstance("MD5").digest(d); }
}
