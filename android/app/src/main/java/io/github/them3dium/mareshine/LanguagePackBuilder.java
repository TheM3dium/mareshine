package io.github.them3dium.mareshine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static io.github.them3dium.mareshine.L.t;

// Builds the language packs from the player's own European disc (GMSP01) and
// North American disc (GMSE01), on the device: a Java port of chasem-dev's
// tools/make_language_packs.py (sms-hd-texture-extras), which the PC port's
// language packs come from. Writes <out>/<lang>/files/..., which the game
// reads with `language = <lang>` (platform/misc/language.cpp,
// platform/dvd/dvd.cpp). Nothing is downloaded: every byte comes from the
// two discs.
//
// The European disc keeps each language's 2D archives as <name>_<xx>.szs and
// its text and pictures as <name>_<xx>.bmg and timg/<xx>/ inside shared
// archives; the US game reads the plain names. A pack holds, for the US game:
//   files/data/<name>.szs                 the language's game_6, guide, select,
//                                         subtitle and endsave archives
//   files/data/option.szs                 the US archive with the language's
//                                         menus (text, title and score layouts,
//                                         word pictures with their boxes widened
//                                         to fit, the help line)
//   files/data/common.szs/2d/...          files put inside the US archive of
//   files/data/nintendo.szs/font/...      that name as it loads: the system
//   files/data/scene/<stage>.szs/map/...  text, the font, each stage's lines
// The text keeps the European words with each message's attributes from the
// US file (Europe moved the voice byte, and added one options help line to
// the memory card text).
final class LanguagePackBuilder {
    static final String[] LANG_CODES = {"fr", "de", "es", "it"};
    static final Map<String, String> LANGS = new LinkedHashMap<>(); // pack code -> disc suffix
    static {
        LANGS.put("fr", "fr");
        LANGS.put("de", "ge");
        LANGS.put("es", "sp");
        LANGS.put("it", "it");
    }
    static final String[] WHOLE = {"game_6", "guide", "select", "subtitle", "endsave"};
    static final String[] COMMON_TEXT = {"balloon", "infomess", "savemessage", "scenarioname", "stagename", "sys_message"};
    static final int LOADMESSAGE_PAL_ONLY = 28; // the options help line, which the US game has in option.blo

    interface Progress {
        void step(String what, double fraction);
        boolean cancelled();
    }

    static final class BuildException extends IOException {
        BuildException(String m) { super(m); }
    }

    // ------------------------------------------------------------- bytes
    static int u8(byte[] d, int o) { return d[o] & 0xFF; }
    static int u16(byte[] d, int o) { return (d[o] & 0xFF) << 8 | (d[o + 1] & 0xFF); }
    static int s16(byte[] d, int o) { return (short) u16(d, o); }
    static int u32(byte[] d, int o) { return (d[o] & 0xFF) << 24 | (d[o + 1] & 0xFF) << 16 | (d[o + 2] & 0xFF) << 8 | (d[o + 3] & 0xFF); }
    static void p16(byte[] d, int o, int v) { d[o] = (byte) (v >> 8); d[o + 1] = (byte) v; }
    static void p32(byte[] d, int o, int v) { d[o] = (byte) (v >>> 24); d[o + 1] = (byte) (v >>> 16); d[o + 2] = (byte) (v >>> 8); d[o + 3] = (byte) v; }
    static byte[] sub(byte[] d, int a, int b) { return Arrays.copyOfRange(d, a, b); }
    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }
    static byte[] zeros(int n) { return new byte[Math.max(0, n)]; }
    static int pad32(int n) { return (-n) & 31; }
    static String latin1(byte[] d, int a, int b) { return new String(d, a, b - a, StandardCharsets.ISO_8859_1); }
    static byte[] bytes(String s) { return s.getBytes(StandardCharsets.ISO_8859_1); }
    static int indexOf(byte[] d, byte[] what, int from) {
        outer:
        for (int i = Math.max(0, from); i <= d.length - what.length; i++) {
            for (int k = 0; k < what.length; k++) if (d[i + k] != what[k]) continue outer;
            return i;
        }
        return -1;
    }
    static int indexOfZero(byte[] d, int from) {
        for (int i = from; i < d.length; i++) if (d[i] == 0) return i;
        throw new IllegalStateException("no terminator");
    }
    static byte[] be16(int v) { return new byte[] {(byte) (v >> 8), (byte) v}; }
    static byte[] be32(int v) { return new byte[] {(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v}; }

    // ------------------------------------------------------------- disc
    static final class Disc implements AutoCloseable {
        final RandomAccessFile f;
        final String id;
        final Map<String, long[]> files = new TreeMap<>();

        Disc(File path) throws IOException {
            f = new RandomAccessFile(path, "r");
            byte[] h = new byte[0x440];
            f.readFully(h);
            if ((h[0] == 'C' && h[1] == 'I' && h[2] == 'S' && h[3] == 'O') || (h[0] == 'R' && h[1] == 'V' && h[2] == 'Z'))
                throw new BuildException(path.getName() + " is a compressed image; it needs to be a plain .iso or .gcm");
            id = latin1(h, 0, 6);
            long fo = u32(h, 0x424) & 0xFFFFFFFFL, fs = u32(h, 0x428) & 0xFFFFFFFFL;
            byte[] fst = new byte[(int) fs];
            f.seek(fo);
            f.readFully(fst);
            int n = u32(fst, 8);
            int strt = n * 12;
            walk(fst, strt, 1, n, "/");
        }

        private int walk(byte[] fst, int strt, int i, int end, String path) {
            while (i < end) {
                int w0 = u32(fst, i * 12), a = u32(fst, i * 12 + 4), b = u32(fst, i * 12 + 8);
                int no = strt + (w0 & 0xFFFFFF);
                String nm = latin1(fst, no, indexOfZero(fst, no));
                if ((w0 >>> 24) != 0) {
                    walk(fst, strt, i + 1, b, path + nm + "/");
                    i = b;
                } else {
                    files.put(path + nm, new long[] {a & 0xFFFFFFFFL, b & 0xFFFFFFFFL});
                    i++;
                }
            }
            return i;
        }

        boolean has(String path) { return files.containsKey(path); }

        byte[] read(String path) throws IOException {
            long[] e = files.get(path);
            if (e == null) throw new BuildException("the disc has no " + path);
            byte[] out = new byte[(int) e[1]];
            f.seek(e[0]);
            f.readFully(out);
            return out;
        }

        @Override public void close() throws IOException { f.close(); }
    }

    // ------------------------------------------------------------- Yaz0
    static byte[] yaz0Decode(byte[] d) {
        if (d.length < 16 || d[0] != 'Y' || d[1] != 'a' || d[2] != 'z' || d[3] != '0') return d;
        int n = u32(d, 4);
        byte[] o = new byte[n];
        int op = 0, i = 16;
        while (op < n) {
            int c = d[i++] & 0xFF;
            for (int b = 0; b < 8 && op < n; b++) {
                if ((c & (0x80 >> b)) != 0) {
                    o[op++] = d[i++];
                } else {
                    int b1 = d[i] & 0xFF, b2 = d[i + 1] & 0xFF;
                    i += 2;
                    int dist = ((b1 & 15) << 8 | b2) + 1;
                    int ln;
                    if ((b1 >> 4) != 0) ln = (b1 >> 4) + 2;
                    else ln = (d[i++] & 0xFF) + 0x12;
                    for (int k = 0; k < ln && op < n; k++, op++) o[op] = o[op - dist];
                }
            }
        }
        return o;
    }

    /** A valid Yaz0 stream holding `raw` as literals only. */
    static byte[] yaz0Store(byte[] raw) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length + raw.length / 8 + 32);
        out.write('Y'); out.write('a'); out.write('z'); out.write('0');
        out.write(be32(raw.length), 0, 4);
        out.write(zeros(8), 0, 8);
        for (int i = 0; i < raw.length; i += 8) {
            out.write(0xFF);
            out.write(raw, i, Math.min(8, raw.length - i));
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------- RARC
    static final class Ent {
        String name;
        int flags, hash;
        boolean dir;     // a folder (flags 0x0200); the root's ".." points at -1
        int node = -1;   // folders
        byte[] data;     // files
    }

    static final class Node {
        byte[] type;
        String name;
        int hash;
        List<Ent> ents = new ArrayList<>();
    }

    /** An archive as folders of named entries, which can be changed and written. */
    static final class Rarc {
        final List<Node> nodes = new ArrayList<>();
        final int sync;

        Rarc(byte[] d) {
            int nn = u32(d, 0x20), no = u32(d, 0x24) + 0x20, fo = u32(d, 0x2C) + 0x20, so = u32(d, 0x34) + 0x20;
            sync = u8(d, 0x3A);
            int doff = 0x20 + u32(d, 0x0C);
            for (int i = 0; i < nn; i++) {
                int b = no + i * 16;
                Node nd = new Node();
                nd.type = sub(d, b, b + 4);
                nd.name = name(d, so, u32(d, b + 4));
                nd.hash = u16(d, b + 8);
                int cnt = u16(d, b + 10), first = u32(d, b + 12);
                for (int k = first; k < first + cnt; k++) {
                    int e = fo + k * 20;
                    Ent ent = new Ent();
                    ent.hash = u16(d, e + 2);
                    ent.flags = u16(d, e + 4);
                    ent.name = name(d, so, u16(d, e + 6));
                    int off = u32(d, e + 8), size = u32(d, e + 12);
                    if ((ent.flags & 0x0200) != 0) { ent.dir = true; ent.node = off; }
                    else ent.data = sub(d, doff + off, doff + off + size);
                    nd.ents.add(ent);
                }
                nodes.add(nd);
            }
        }

        private static String name(byte[] d, int so, int o) { return latin1(d, so + o, indexOfZero(d, so + o)); }

        Map<String, Ent> files() {
            Map<String, Ent> out = new LinkedHashMap<>();
            walk(0, "", out);
            return out;
        }

        private void walk(int node, String path, Map<String, Ent> out) {
            for (Ent e : nodes.get(node).ents) {
                if (e.name.equals(".") || e.name.equals("..")) continue;
                if (e.dir) walk(e.node, path + "/" + e.name, out);
                else out.put((path + "/" + e.name).toLowerCase(Locale.ROOT), e);
            }
        }

        byte[] get(String path) throws BuildException {
            Ent e = files().get(path.toLowerCase(Locale.ROOT));
            if (e == null) throw new BuildException("no " + path + " in the archive");
            return e.data;
        }

        boolean has(String path) { return files().containsKey(path.toLowerCase(Locale.ROOT)); }

        /** Replace a file, or add it to its (existing) folder. */
        void put(String path, byte[] data) throws BuildException {
            Map<String, Ent> f = files();
            Ent have = f.get(path.toLowerCase(Locale.ROOT));
            if (have != null) {
                have.data = data;
                return;
            }
            String[] parts = Arrays.stream(path.toLowerCase(Locale.ROOT).split("/")).filter(p -> !p.isEmpty()).toArray(String[]::new);
            int node = 0;
            for (int i = 0; i < parts.length - 1; i++) {
                int next = -1;
                for (Ent e : nodes.get(node).ents)
                    if (e.dir && e.name.toLowerCase(Locale.ROOT).equals(parts[i])) { next = e.node; break; }
                if (next < 0) throw new BuildException("no folder for " + path);
                node = next;
            }
            String leaf = path.substring(path.lastIndexOf('/') + 1);
            List<Ent> ents = nodes.get(node).ents;
            int h = 0;
            for (byte c : bytes(parts[parts.length - 1])) h = (h * 3 + (c & 0xFF)) & 0xFFFF;
            Ent like = null;
            for (Ent e : ents) if (e.data != null) { like = e; break; } // same flags as its neighbours
            if (like == null) throw new BuildException("no file next to " + path);
            int at = ents.size();
            for (int i = 0; i < ents.size(); i++) if (ents.get(i).name.equals(".") || ents.get(i).name.equals("..")) { at = i; break; }
            Ent n = new Ent();
            n.name = leaf;
            n.flags = like.flags;
            n.hash = h;
            n.data = data;
            ents.add(at, n);
        }

        byte[] build() {
            ByteArrayOutputStream strings = new ByteArrayOutputStream();
            strings.write(bytes(".\0..\0"), 0, 5);
            Map<String, Integer> offs = new HashMap<>();
            offs.put(".", 0);
            offs.put("..", 2);
            ByteArrayOutputStream nodesB = new ByteArrayOutputStream(), ents = new ByteArrayOutputStream(), data = new ByteArrayOutputStream();
            int index = 0;
            for (Node nd : nodes) {
                nodesB.write(nd.type, 0, 4);
                nodesB.write(be32(soff(nd.name, offs, strings)), 0, 4);
                nodesB.write(be16(nd.hash), 0, 2);
                nodesB.write(be16(nd.ents.size()), 0, 2);
                nodesB.write(be32(index), 0, 4);
                for (Ent e : nd.ents) {
                    if (e.dir) {
                        ents.write(be16(0xFFFF), 0, 2);
                        ents.write(be16(e.hash), 0, 2);
                        ents.write(be16(e.flags), 0, 2);
                        ents.write(be16(soff(e.name, offs, strings)), 0, 2);
                        ents.write(be32(e.node), 0, 4);
                        ents.write(be32(0x10), 0, 4);
                        ents.write(be32(0), 0, 4);
                    } else {
                        data.write(zeros(pad32(data.size())), 0, pad32(data.size()));
                        int fid = sync != 0 ? index : 0;
                        ents.write(be16(fid), 0, 2);
                        ents.write(be16(e.hash), 0, 2);
                        ents.write(be16(e.flags), 0, 2);
                        ents.write(be16(soff(e.name, offs, strings)), 0, 2);
                        ents.write(be32(data.size()), 0, 4);
                        ents.write(be32(e.data.length), 0, 4);
                        ents.write(be32(0), 0, 4);
                        data.write(e.data, 0, e.data.length);
                    }
                    index++;
                }
            }
            data.write(zeros(pad32(data.size())), 0, pad32(data.size()));
            byte[] nb = padded(nodesB.toByteArray()), eb = padded(ents.toByteArray()), sb = padded(strings.toByteArray());
            int nodeOff = 0x20, entOff = nodeOff + nb.length, strOff = entOff + eb.length;
            int nfiles = index;
            byte[] info = cat(be32(nodes.size()), be32(nodeOff), be32(nfiles), be32(entOff), be32(sb.length), be32(strOff),
                be16(nfiles), new byte[] {(byte) sync, 0}, zeros(4));
            byte[] body = cat(info, nb, eb, sb);
            byte[] dat = data.toByteArray();
            byte[] head = cat(bytes("RARC"), be32(0x20 + body.length + dat.length), be32(0x20), be32(body.length),
                be32(dat.length), be32(dat.length), be32(0), be32(0));
            return cat(head, body, dat);
        }

        private static int soff(String s, Map<String, Integer> offs, ByteArrayOutputStream strings) {
            Integer o = offs.get(s);
            if (o == null) {
                o = strings.size();
                offs.put(s, o);
                byte[] b = bytes(s);
                strings.write(b, 0, b.length);
                strings.write(0);
            }
            return o;
        }

        private static byte[] padded(byte[] b) { return cat(b, zeros(pad32(b.length))); }
    }

    // ------------------------------------------------------------- BMG
    static final class Bmg {
        byte[] head;
        List<byte[][]> secs = new ArrayList<>(); // {tag, payload}
    }

    static Bmg bmgSections(byte[] d) throws BuildException {
        if (!latin1(d, 0, 8).equals("MESGbmg1")) throw new BuildException("not a BMG file");
        int n = u32(d, 0x0C);
        int o = 0x20;
        Bmg b = new Bmg();
        b.head = sub(d, 0, 0x20);
        for (int i = 0; i < n; i++) {
            int size = u32(d, o + 4);
            b.secs.add(new byte[][] {sub(d, o, o + 4), sub(d, o + 8, o + size)});
            o += size;
        }
        return b;
    }

    static byte[] bmgBuild(byte[] head, List<byte[][]> sections) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[][] s : sections) {
            byte[] sec = cat(s[0], zeros(4), s[1]);
            sec = cat(sec, zeros(pad32(sec.length)));
            p32(sec, 4, sec.length);
            body.write(sec, 0, sec.length);
        }
        byte[] h = head.clone();
        p32(h, 0x08, 0x20 + body.size());
        p32(h, 0x0C, sections.size());
        return cat(h, body.toByteArray());
    }

    private static byte[] section(Bmg b, String tag) throws BuildException {
        for (byte[][] s : b.secs) if (latin1(s[0], 0, 4).equals(tag)) return s[1];
        throw new BuildException("no " + tag + " section");
    }

    static List<byte[]> bmgEntries(byte[] d, int[] esOut) throws BuildException {
        byte[] p = section(bmgSections(d), "INF1");
        int n = u16(p, 0), es = u16(p, 2);
        esOut[0] = es;
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(sub(p, 8 + i * es, 8 + (i + 1) * es));
        return out;
    }

    static byte[] bmgText(byte[] d, int i) throws BuildException {
        Bmg b = bmgSections(d);
        int[] es = new int[1];
        List<byte[]> ents = bmgEntries(d, es);
        byte[] dat = section(b, "DAT1");
        int o = u32(ents.get(i), 0);
        return sub(dat, o, indexOfZero(dat, o));
    }

    /** The European text with the US message attributes. */
    static byte[] convertBmg(byte[] pal, byte[] us, String name, int drop) throws BuildException {
        Bmg b = bmgSections(pal);
        int ii = -1;
        for (int k = 0; k < b.secs.size(); k++) if (latin1(b.secs.get(k)[0], 0, 4).equals("INF1")) { ii = k; break; }
        int[] es = new int[1], ues = new int[1];
        List<byte[]> ents = bmgEntries(pal, es);
        if (drop >= 0) ents.remove(drop);
        List<byte[]> uents = bmgEntries(us, ues);
        if (ents.size() != uents.size() || es[0] != ues[0])
            throw new BuildException(name + ": " + ents.size() + " entries of " + es[0] + " bytes, the US file "
                + uents.size() + " of " + ues[0]);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int k = 0; k < ents.size(); k++) {
            byte[] p = ents.get(k), u = uents.get(k);
            byte[] pt = sub(p, 4, p.length), ut = sub(u, 4, u.length);
            boolean same = Arrays.equals(pt, ut);
            boolean moved = es[0] == 12 && Arrays.equals(cat(sub(p, 4, 8), sub(p, 10, 12), zeros(2)), ut);
            if (!same && !moved) throw new BuildException(name + ": message attributes fit no known layout");
            out.write(p, 0, 4);
            out.write(ut, 0, ut.length);
        }
        byte[] old = b.secs.get(ii)[1];
        b.secs.get(ii)[1] = cat(be16(ents.size()), be16(es[0]), sub(old, 4, 8), out.toByteArray());
        return bmgBuild(b.head, b.secs);
    }

    // ------------------------------------------------------------- BLO
    static List<int[]> bloChunks(byte[] d) { // {offset, size}, tag at offset
        List<int[]> out = new ArrayList<>();
        int o = 0x20;
        while (o + 8 <= d.length) {
            int s = u32(d, o + 4);
            if (s == 0) break;
            out.add(new int[] {o, s});
            if (latin1(d, o, o + 4).equals("EXT1")) break;
            o += s;
        }
        return out;
    }

    static boolean paneTag(String t) { return t.equals("PAN1") || t.equals("PIC1") || t.equals("WIN1") || t.equals("TBX1"); }

    static String tagOf(byte[] d, int o) {
        int a = o + 12;
        while (a < o + 16 && d[a] == 0) a++;
        return latin1(d, a, o + 16);
    }

    static final class Pane {
        int off, x, y, w, h;
        String kind, tex;
    }

    static boolean wordChar(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '.';
    }

    // re.search(rb'\x02([\x01-\x7f])([\w.]+?\.bti)', chunk): the first match,
    // then its length byte has to agree
    static String picTexture(byte[] d, int a, int b) {
        for (int i = a; i + 2 < b; i++) {
            if (d[i] != 2) continue;
            int len = d[i + 1] & 0xFF;
            if (len < 1 || len > 0x7F) continue;
            int s = i + 2, k = s;
            while (k < b && wordChar(d[k] & 0xFF)) {
                k++;
                if (k - s >= 5 && d[k - 4] == '.' && d[k - 3] == 'b' && d[k - 2] == 't' && d[k - 1] == 'i') {
                    return len == k - s ? latin1(d, s, k) : "";
                }
            }
        }
        return "";
    }

    static LinkedHashMap<String, Pane> bloPanes(byte[] d) {
        LinkedHashMap<String, Pane> out = new LinkedHashMap<>();
        for (int[] c : bloChunks(d)) {
            int o = c[0], s = c[1];
            String t = latin1(d, o, o + 4);
            if (!paneTag(t)) continue;
            Pane p = new Pane();
            p.off = o;
            p.kind = t;
            p.x = s16(d, o + 16);
            p.y = s16(d, o + 18);
            p.w = s16(d, o + 20);
            p.h = s16(d, o + 22);
            p.tex = t.equals("PIC1") ? picTexture(d, o + 24, o + s) : "";
            out.put(tagOf(d, o), p);
        }
        return out;
    }

    static Map<String, List<String>> bloChildren(byte[] d) {
        Map<String, List<String>> kids = new HashMap<>();
        List<String> stack = new ArrayList<>();
        String last = null;
        for (int[] c : bloChunks(d)) {
            String t = latin1(d, c[0], c[0] + 4);
            if (t.equals("BGN1")) stack.add(last);
            else if (t.equals("END1")) { if (!stack.isEmpty()) stack.remove(stack.size() - 1); }
            else if (paneTag(t)) {
                last = tagOf(d, c[0]);
                if (!stack.isEmpty() && stack.get(stack.size() - 1) != null)
                    kids.computeIfAbsent(stack.get(stack.size() - 1), k -> new ArrayList<>()).add(last);
            }
        }
        return kids;
    }

    /** A copy of layout `d` with text box `tag`'s string replaced. */
    static byte[] bloSetText(byte[] d, String tag, byte[] text) throws BuildException {
        byte[] font = bytes("standard_font.bfn");
        for (int[] c : bloChunks(d)) {
            int o = c[0], s = c[1];
            if (!latin1(d, o, o + 4).equals("TBX1") || !tagOf(d, o).equals(tag)) continue;
            byte[] body = sub(d, o + 8, o + s);
            int p = indexOf(body, font, 0);
            int sl = p + font.length + 8 + 1 + 8;
            int ln = u16(body, sl);
            byte[] nw = cat(sub(body, 0, sl), be16(text.length), text, sub(body, sl + 2 + ln, body.length));
            nw = cat(nw, zeros((-(nw.length + 8)) & 3));
            byte[] chunk = cat(bytes("TBX1"), be32(nw.length + 8), nw);
            byte[] out = cat(sub(d, 0, o), chunk, sub(d, o + s, d.length));
            p32(out, 8, out.length);
            return out;
        }
        throw new BuildException("no text box " + tag);
    }

    static byte[] bloSetRect(byte[] d, int off, int x, int y, int w, int h) {
        byte[] o = d.clone();
        p16(o, off + 16, x);
        p16(o, off + 18, y);
        p16(o, off + 20, w);
        p16(o, off + 22, h);
        return o;
    }

    static int[] btiSize(byte[] d) { return new int[] {u16(d, 2), u16(d, 4)}; }

    static int pyRound(double v) { return (int) Math.rint(v); } // Python's round(): half to even
    static int floorDiv(int a, int b) { return Math.floorDiv(a, b); }

    // ------------------------------------------------------------- option.szs
    static byte[] helpIcon(byte[] sym) {
        return cat(bytes("\u001bFX[24]\u001bFY[24]\u001bCC[64FF64]\u001bGC[64FF64]"), sym,
            bytes("\u001bFX[18]\u001bCC[FFFFFF]\u001bGC[FFFFFF]"));
    }

    static byte[] replaceFirst(byte[] d, byte[] what, byte[] with) {
        int i = indexOf(d, what, 0);
        return i < 0 ? d : cat(sub(d, 0, i), with, sub(d, i + what.length, d.length));
    }

    static byte[] rstripSpaces(byte[] d) {
        int n = d.length;
        while (n > 0 && d[n - 1] == ' ') n--;
        return sub(d, 0, n);
    }

    /** The US option.szs with language `lx`'s menus. */
    static byte[] buildOption(byte[] usOption, byte[] palOption, String lx) throws BuildException {
        Rarc a = new Rarc(usOption);
        Rarc p = new Rarc(palOption);
        Map<String, Ent> pf = p.files();
        Map<String, byte[]> langImgs = new TreeMap<>(), usImgs = new HashMap<>();
        for (Map.Entry<String, Ent> e : pf.entrySet())
            if (e.getKey().startsWith("/timg/" + lx + "/")) langImgs.put(e.getKey().substring(e.getKey().lastIndexOf('/') + 1), e.getValue().data);
        for (Map.Entry<String, Ent> e : a.files().entrySet())
            if (e.getKey().startsWith("/timg/")) usImgs.put(e.getKey().substring(e.getKey().lastIndexOf('/') + 1), e.getValue().data);

        // text: the memory card messages, and option.blo's help line
        Ent lmEnt = pf.get("/loadmessage_" + lx + ".bmg");
        if (lmEnt == null) throw new BuildException("no loadmessage_" + lx + ".bmg on the European disc");
        byte[] lmPal = lmEnt.data;
        a.put("/loadmessage.bmg", convertBmg(lmPal, a.get("/loadmessage.bmg"), "loadmessage", LOADMESSAGE_PAL_ONLY));
        byte[] help = rstripSpaces(bmgText(lmPal, LOADMESSAGE_PAL_ONLY));
        help = replaceFirst(help, bytes("*"), helpIcon(bytes("*")));
        help = replaceFirst(help, bytes("@"), helpIcon(bytes("@")));

        // the title and score screens: the language's own layouts, with every
        // picture they show from the European disc
        for (String blo : new String[] {"title_1", "load_score"}) {
            String k = "/scrn/" + blo + "_" + lx + ".blo";
            Ent ke = pf.get(k);
            if (ke == null) throw new BuildException("no " + k + " on the European disc");
            a.put("/scrn/" + blo + ".blo", ke.data);
            for (Pane v : bloPanes(ke.data).values()) {
                String n = v.tex.toLowerCase(Locale.ROOT);
                if (n.isEmpty()) continue;
                byte[] img = langImgs.get(n);
                if (img == null && pf.containsKey("/timg/" + n)) img = pf.get("/timg/" + n).data;
                if (img != null) a.put("/timg/" + n, img);
            }
        }

        // the word pictures of the US file select and options screens
        for (Map.Entry<String, byte[]> e : langImgs.entrySet())
            if (usImgs.containsKey(e.getKey())) a.put("/timg/" + e.getKey(), e.getValue());

        // load.blo and option.blo stay the US layouts: each word's box is
        // resized as its picture is, about the box's centre (children kept in
        // place); the file menus' windows widen to fit their widest word
        for (String blo : new String[] {"load", "option"}) {
            byte[] d = a.get("/scrn/" + blo + ".blo");
            Map<String, List<String>> kids = bloChildren(d);
            Map<String, String> parent = new HashMap<>();
            for (Map.Entry<String, List<String>> e : kids.entrySet()) for (String c : e.getValue()) parent.put(c, e.getKey());

            List<Map.Entry<String, Pane>> panes = new ArrayList<>(bloPanes(d).entrySet());
            for (Map.Entry<String, Pane> pe : panes) {
                String tag = pe.getKey();
                Pane pa = pe.getValue();
                String tex = pa.tex.toLowerCase(Locale.ROOT);
                if (pa.kind.equals("PIC1") && langImgs.containsKey(tex) && usImgs.containsKey(tex)) {
                    int[] us = btiSize(usImgs.get(tex)), ln = btiSize(langImgs.get(tex));
                    int nw = pyRound((double) pa.w * ln[0] / us[0]), nh = pyRound((double) pa.h * ln[1] / us[1]);
                    int dx = -floorDiv(nw - pa.w, 2);
                    int sx = left(d, tag, parent) + dx; // kept inside the screen's 600 units
                    dx += Math.max(0, 6 - sx) - Math.max(0, sx + nw - 594);
                    d = move(d, tag, dx, -floorDiv(nh - pa.h, 2), nw, nh, kids);
                }
            }
            if (blo.equals("load")) {
                for (String win : new String[] {"w_4", "w_5", "w_6"}) {
                    Map<String, Pane> ps = bloPanes(d);
                    Pane wp = ps.get(win);
                    int margin = 26; // the US menus': (150 - 97) / 2
                    int widest = 0;
                    for (String k : kids.getOrDefault(win, new ArrayList<>())) widest = Math.max(widest, ps.get(k).w);
                    int nw = Math.max(wp.w, widest + 2 * margin);
                    int nx = Math.min(Math.max(wp.x - floorDiv(nw - wp.w, 2), 24), 600 - 24 - nw);
                    d = bloSetRect(d, wp.off, nx, wp.y, nw, wp.h);
                    int cw = s16(d, wp.off + 33); // its inner fill grows with it
                    p16(d, wp.off + 33, cw + nw - wp.w);
                    for (String k : kids.getOrDefault(win, new ArrayList<>())) {
                        Pane kp = bloPanes(d).get(k);
                        d = bloSetRect(d, kp.off, floorDiv(nw - kp.w, 2), kp.y, kp.w, kp.h);
                    }
                }
            }
            if (blo.equals("option"))
                for (String tag : new String[] {"m_0a", "m_0b"}) d = bloSetText(d, tag, help);
            a.put("/scrn/" + blo + ".blo", d);
        }
        return a.build();
    }

    private static int left(byte[] d, String tag, Map<String, String> parent) { // its x on the screen
        int x = 0;
        Map<String, Pane> ps = bloPanes(d);
        while (tag != null && ps.containsKey(tag)) {
            x += ps.get(tag).x;
            tag = parent.get(tag);
        }
        return x;
    }

    private static byte[] move(byte[] d, String tag, int dx, int dy, int w, int h, Map<String, List<String>> kids) {
        Pane p = bloPanes(d).get(tag);
        d = bloSetRect(d, p.off, p.x + dx, p.y + dy, w, h);
        for (String k : kids.getOrDefault(tag, new ArrayList<>())) { // children are placed relative to it
            Pane kp = bloPanes(d).get(k);
            d = bloSetRect(d, kp.off, kp.x - dx, kp.y - dy, kp.w, kp.h);
        }
        return d;
    }

    // ------------------------------------------------------------- packs
    private static void put(File out, String lang, String rel, byte[] blob) throws IOException {
        File f = new File(out, lang + "/files/" + rel);
        f.getParentFile().mkdirs();
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(blob);
        }
    }

    /** Checks the two discs; returns null when they fit, else what is wrong. */
    static String checkDiscs(File pal, File us) {
        try (Disc p = new Disc(pal); Disc u = new Disc(us)) {
            if (!p.id.startsWith("GMSP")) return t("%s is %s, not the European Super Mario Sunshine (GMSP01)", pal.getName(), p.id);
            if (!u.id.startsWith("GMSE")) return t("%s is %s, not the North American disc (GMSE01)", us.getName(), u.id);
            return null;
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    /** Builds the packs for `langs` into `out` (out/<lang>/files/...). */
    static void build(File palIso, File usIso, File out, List<String> langs, Progress prog) throws IOException {
        try (Disc pal = new Disc(palIso); Disc us = new Disc(usIso)) {
            if (!pal.id.startsWith("GMSP") || !us.id.startsWith("GMSE"))
                throw new BuildException("need the European (GMSP01) and North American (GMSE01) discs, got " + pal.id + " and " + us.id);
            File stage = new File(out, ".building");
            Settings_rm(stage);
            stage.mkdirs();

            prog.step(t("Reading the menus…"), 0.02);
            Rarc usCommon = new Rarc(yaz0Decode(us.read("/data/common.szs")));
            byte[] usOption = yaz0Decode(us.read("/data/option.szs"));
            byte[] palOption = yaz0Decode(pal.read("/data/option.szs"));
            byte[] palFont = new Rarc(yaz0Decode(pal.read("/data/nintendo.szs"))).get("/font/standard_fontex.bfn");
            int li = 0;
            for (String lang : langs) {
                if (prog.cancelled()) return;
                String lx = LANGS.get(lang);
                prog.step(t("Menus and text: %s", lang.toUpperCase(Locale.ROOT)), 0.05 + 0.25 * li / langs.size());
                for (String n : WHOLE) {
                    byte[] src = pal.read("/data/" + n + "_" + lx + ".szs");
                    if ((n.equals("game_6") || n.equals("guide")) && u32(src, 4) > 0x64000)
                        throw new BuildException(n + "_" + lx + " unpacks to more than the game's 0x64000-byte buffer");
                    put(stage, lang, "data/" + n + ".szs", src);
                }
                Rarc cmn2d = new Rarc(yaz0Decode(pal.read("/data/cmn2d_" + lx + ".szs")));
                for (String n : COMMON_TEXT)
                    put(stage, lang, "data/common.szs/2d/" + n + ".bmg",
                        convertBmg(cmn2d.get("/" + n + ".bmg"), usCommon.get("/2d/" + n + ".bmg"), n, -1));
                put(stage, lang, "data/common.szs/2d/wipe_gameover.bti", cmn2d.get("/wipe_gameover.bti"));
                put(stage, lang, "data/nintendo.szs/font/standard_fontex.bfn", palFont);
                put(stage, lang, "data/option.szs", yaz0Store(buildOption(usOption, palOption, lx)));
                li++;
            }

            List<String> scenes = new ArrayList<>();
            for (String p : us.files.keySet()) if (p.startsWith("/data/scene/") && p.endsWith(".szs")) scenes.add(p);
            for (int i = 0; i < scenes.size(); i++) {
                if (prog.cancelled()) return;
                String p = scenes.get(i);
                prog.step(t("Stages: %d of %d", i + 1, scenes.size()), 0.3 + 0.68 * i / scenes.size());
                Rarc u = new Rarc(yaz0Decode(us.read(p)));
                if (!u.has("/map/message.bmg")) continue;
                Rarc pv = new Rarc(yaz0Decode(pal.read(p)));
                for (String lang : langs) {
                    byte[] msg = convertBmg(pv.get("/map/message_" + LANGS.get(lang) + ".bmg"), u.get("/map/message.bmg"), p, -1);
                    put(stage, lang, "data/scene/" + p.substring(p.lastIndexOf('/') + 1) + "/map/message.bmg", msg);
                }
            }

            // swap each finished language into place
            for (String lang : langs) {
                File done = new File(out, lang);
                Settings_rm(done);
                if (!new File(stage, lang).renameTo(done)) throw new BuildException("cannot move the " + lang + " pack into place");
                try (FileOutputStream o = new FileOutputStream(new File(done, "source.txt"))) {
                    o.write(("Built on this device from your own discs: " + pal.id + " and " + us.id + ".\n").getBytes(StandardCharsets.UTF_8));
                }
            }
            Settings_rm(stage);
            prog.step(t("Done"), 1.0);
        }
    }

    static void Settings_rm(File f) {
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) for (File c : fs) Settings_rm(c);
        }
        f.delete();
    }
}
