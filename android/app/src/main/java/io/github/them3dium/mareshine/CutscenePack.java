package io.github.them3dium.mareshine;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static io.github.them3dium.mareshine.L.t;

// The PC port's HD cutscene pack (docs/HD-CUTSCENES.md): its 21 movies,
// AI-upscaled by 3, are published as SMP1 patches (tools/media/movie_patch.py)
// that hold only the enhanced video frames (JPEGs). Applied to the movie on
// the player's own North American disc, which gives the audio, timeline and
// metadata, a patch rebuilds the HD THP, checked against the catalog's
// SHA-256 (tools/media/cutscene-release.json, copied into the APK's assets).
//
// The pack lives in cutscenes/ in the data folder: files/data/<movie>.thp,
// with sms-hd-cutscenes-v1.complete once all 21 are there; the game uses it
// when settings.txt has hd_cutscenes = <that folder> (platform/thp).
final class CutscenePack {
    static final String MARKER = "sms-hd-cutscenes-v1.complete";
    static final String SETTING = "hd_cutscenes";

    static final class Movie {
        String discPath, patchFile, url, sourceSha, targetSha, patchSha;
        long targetBytes, patchBytes;
        int width, height, frames;

        String name() { return discPath.substring(discPath.lastIndexOf('/') + 1); } // "openingA.thp"
        String stem() { return name().substring(0, name().length() - 4); }
    }

    private CutscenePack() {}

    static File dir(Context c) { return new File(DataDir.get(c), "cutscenes"); }
    static File thpDir(Context c) { return new File(dir(c), "files/data"); }
    static File downloadDir(Context c) { return new File(dir(c), ".downloads"); }

    static List<Movie> catalog(Context c) throws IOException {
        try (InputStream in = c.getAssets().open("cutscene-release.json")) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 14];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            JSONArray a = new JSONObject(b.toString("UTF-8")).getJSONArray("movies");
            List<Movie> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Movie m = new Movie();
                m.discPath = o.getString("disc_path");
                m.patchFile = o.getString("patch_file");
                m.url = o.getString("url");
                m.sourceSha = o.getString("source_sha256");
                m.targetSha = o.getString("target_sha256");
                m.patchSha = o.getString("patch_sha256");
                m.targetBytes = o.getLong("target_bytes");
                m.patchBytes = o.getLong("patch_bytes");
                m.width = o.getInt("width");
                m.height = o.getInt("height");
                m.frames = o.getInt("frames");
                out.add(m);
            }
            return out;
        } catch (org.json.JSONException e) {
            throw new IOException("bad cutscene catalog: " + e.getMessage());
        }
    }

    static long totalTarget(List<Movie> ms) { long n = 0; for (Movie m : ms) n += m.targetBytes; return n; }
    static long totalPatch(List<Movie> ms) { long n = 0; for (Movie m : ms) n += m.patchBytes; return n; }

    // a movie is in place when it has the catalog's size and was checked once
    // (cutscenes/.checked/<movie> holds its SHA-256)
    static boolean installed(Context c, Movie m) {
        File f = new File(thpDir(c), m.name());
        if (f.length() != m.targetBytes) return false;
        File ok = new File(new File(dir(c), ".checked"), m.stem());
        try {
            return m.targetSha.equals(new String(java.nio.file.Files.readAllBytes(ok.toPath()), StandardCharsets.UTF_8).trim());
        } catch (IOException e) {
            return false;
        }
    }

    static void markChecked(Context c, Movie m) throws IOException {
        File d = new File(dir(c), ".checked");
        d.mkdirs();
        try (FileOutputStream o = new FileOutputStream(new File(d, m.stem()))) {
            o.write(m.targetSha.getBytes(StandardCharsets.UTF_8));
        }
    }

    static boolean complete(Context c) { return new File(dir(c), MARKER).isFile(); }

    static int installedCount(Context c) {
        try {
            int n = 0;
            for (Movie m : catalog(c)) if (installed(c, m)) n++;
            return n;
        } catch (IOException e) {
            return 0;
        }
    }

    static void writeMarker(Context c) throws IOException {
        try (FileOutputStream o = new FileOutputStream(new File(dir(c), MARKER))) {
            o.write("ai-hd-v1\n".getBytes(StandardCharsets.UTF_8));
        }
    }

    // The app's own cutscene frames as cutscenes/overlay.png (assets/
    // cutscene_frame.png) and overlay_<movie>.png (assets/cutscene_frame_<movie>
    // .png), each unless there is one already: the player's own stay.
    static void installFrames(Context c) {
        File dir = dir(c);
        dir.mkdirs();
        try {
            String[] names = c.getAssets().list("");
            if (names == null) return;
            for (String n : names) {
                if (!n.startsWith("cutscene_frame") || !n.endsWith(".png")) continue;
                File f = new File(dir, n.equals("cutscene_frame.png") ? "overlay.png"
                    : "overlay_" + n.substring("cutscene_frame_".length()));
                if (f.isFile()) continue;
                try (InputStream in = c.getAssets().open(n); OutputStream out = new FileOutputStream(f)) {
                    byte[] buf = new byte[1 << 16];
                    int k;
                    while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
                } catch (IOException e) {
                    f.delete();
                }
            }
        } catch (IOException ignored) {}
    }

    static long size(File f) {
        if (f.isFile()) return f.length();
        long n = 0;
        File[] fs = f.listFiles();
        if (fs != null) for (File x : fs) n += size(x);
        return n;
    }

    // --- SHA-256 --------------------------------------------------------------

    interface Progress {
        void at(long done);
        boolean cancelled();
    }

    static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    static MessageDigest sha() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(File f, Progress p) throws IOException {
        MessageDigest md = sha();
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[1 << 20];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                if (p != null && p.cancelled()) throw new IOException(t("Cancelled."));
                md.update(buf, 0, n);
                done += n;
                if (p != null) p.at(done);
            }
        }
        return hex(md.digest());
    }

    // --- the original movie on the disc ----------------------------------------

    static int be32(byte[] d, int o) {
        return (d[o] & 0xFF) << 24 | (d[o + 1] & 0xFF) << 16 | (d[o + 2] & 0xFF) << 8 | (d[o + 3] & 0xFF);
    }

    static void put32(byte[] d, int o, long v) {
        d[o] = (byte) (v >>> 24);
        d[o + 1] = (byte) (v >>> 16);
        d[o + 2] = (byte) (v >>> 8);
        d[o + 3] = (byte) v;
    }

    // A THP inside the disc image, read in place.
    static final class Source {
        final RandomAccessFile f;
        final long base, length;
        byte[] prefix;        // the header up to the first frame
        int comp, frames, tracks = 1;
        int[] kinds;
        int width, height;

        Source(RandomAccessFile f, long base, long length) throws IOException {
            this.f = f;
            this.base = base;
            this.length = length;
            byte[] h = read(0, 0x40);
            if (h[0] != 'T' || h[1] != 'H' || h[2] != 'P' || h[3] != 0) throw new IOException("not a THP movie");
            int version = be32(h, 4);
            if (version != 0x10000 && version != 0x11000) throw new IOException("unsupported THP version");
            frames = be32(h, 0x14);
            comp = be32(h, 0x20);
            int first = be32(h, 0x28);
            if (first <= 0 || first > 0x10000) throw new IOException("bad THP header");
            prefix = read(0, first);
            int count = be32(prefix, comp);
            if (count < 1 || count > 16) throw new IOException("bad THP component count");
            kinds = new int[count];
            int info = comp + 20;
            for (int i = 0; i < count; i++) {
                kinds[i] = prefix[comp + 4 + i] & 0xFF;
                if (kinds[i] == 0) {
                    width = be32(prefix, info);
                    height = be32(prefix, info + 4);
                    info += version == 0x11000 ? 12 : 8;
                } else if (kinds[i] == 1) {
                    if (version == 0x11000) tracks = be32(prefix, info + 12);
                    info += version == 0x11000 ? 16 : 12;
                } else {
                    throw new IOException("unsupported THP component");
                }
            }
            if (kinds[0] != 0) throw new IOException("expected a video-first THP");
            for (int i = 1; i < count; i++) if (kinds[i] == 0) throw new IOException("expected one video component");
            if (be32(prefix, 0x24) != 0) throw new IOException("expected a THP without a frame index");
        }

        byte[] read(long at, int n) throws IOException {
            if (at < 0 || at + n > length) throw new IOException("truncated THP");
            byte[] b = new byte[n];
            f.seek(base + at);
            f.readFully(b);
            return b;
        }

        String sha256(Progress p) throws IOException {
            MessageDigest md = sha();
            byte[] buf = new byte[1 << 20];
            f.seek(base);
            long left = length, done = 0;
            while (left > 0) {
                if (p != null && p.cancelled()) throw new IOException(t("Cancelled."));
                int n = (int) Math.min(buf.length, left);
                f.readFully(buf, 0, n);
                md.update(buf, 0, n);
                left -= n;
                done += n;
                if (p != null) p.at(done);
            }
            return hex(md.digest());
        }
    }

    // finds data/<name> on the disc, whatever its case ("pakkun.thp")
    static long[] findOnDisc(LanguagePackBuilder.Disc disc, String discPath) {
        String want = "/" + discPath;
        long[] e = disc.files.get(want);
        if (e != null) return e;
        for (java.util.Map.Entry<String, long[]> x : disc.files.entrySet())
            if (x.getKey().equalsIgnoreCase(want)) return x.getValue();
        return null;
    }

    // --- applying a patch (movie_patch.py apply) ---------------------------------

    // Rebuilds the HD movie into `out` from the disc's movie and the patch.
    static void apply(LanguagePackBuilder.Disc disc, Movie m, File patch, File out, Progress p) throws IOException {
        long[] e = findOnDisc(disc, m.discPath);
        if (e == null) throw new IOException(t("The disc has no %s.", m.discPath));
        Source s = new Source(disc.f, e[0], e[1]);
        if (!m.sourceSha.equals(s.sha256(null)))
            throw new IOException(t("%s on your disc is not the original movie this patch needs.", m.name()));
        int k = s.kinds.length;

        // pass 1: the frames' sizes in the new movie
        long[] jpegAt = new long[s.frames];
        int[] jpegLen = new int[s.frames], frameSize = new int[s.frames];
        try (RandomAccessFile pf = new RandomAccessFile(patch, "r")) {
            byte[] magic = new byte[8];
            pf.readFully(magic);
            if (magic[0] != 'S' || magic[1] != 'M' || magic[2] != 'P' || magic[3] != '1') throw new IOException("not an SMP1 movie patch");
            int hl = be32(magic, 4);
            if (hl <= 0 || hl > 65536) throw new IOException("bad movie patch header");
            byte[] hj = new byte[hl];
            pf.readFully(hj);
            try {
                JSONObject h = new JSONObject(new String(hj, StandardCharsets.UTF_8));
                if (h.getInt("schema") != 1 || h.getInt("frames") != s.frames || !m.sourceSha.equals(h.getString("source_sha256")))
                    throw new IOException("the patch does not belong to this movie");
                if ((long) h.getInt("width") * s.height != (long) h.getInt("height") * s.width)
                    throw new IOException("the patch changes the movie's aspect ratio");
            } catch (org.json.JSONException x) {
                throw new IOException("bad movie patch header");
            }
            long at = 8 + hl;
            long fo = be32(s.prefix, 0x28) & 0xFFFFFFFFL, fsz = be32(s.prefix, 0x18) & 0xFFFFFFFFL;
            byte[] four = new byte[4];
            for (int i = 0; i < s.frames; i++) {
                byte[] fh = s.read(fo, 8 + 4 * k);
                long audio = 0;
                for (int c = 1; c < k; c++) audio += (be32(fh, 8 + 4 * c) & 0xFFFFFFFFL) * s.tracks;
                pf.seek(at);
                pf.readFully(four);
                int n = be32(four, 0);
                if (n < 4 || n > (32 << 20)) throw new IOException("bad frame in the movie patch");
                jpegAt[i] = at + 4;
                jpegLen[i] = n;
                frameSize[i] = (int) ((8 + 4L * k + n + audio + 31) & ~31L);
                at += 4 + n;
                long next = be32(fh, 0) & 0xFFFFFFFFL;
                fo += fsz;
                fsz = next;
            }
            if (at != pf.length()) throw new IOException("unexpected data after the patch's frames");

            byte[] prefix = s.prefix.clone();
            int max = 0;
            long sum = 0;
            for (int x : frameSize) { max = Math.max(max, x); sum += x; }
            put32(prefix, s.comp + 20, m.width);
            put32(prefix, s.comp + 24, m.height);
            put32(prefix, 8, max);
            put32(prefix, 0x18, frameSize[0]);
            put32(prefix, 0x1c, sum);
            put32(prefix, 0x2c, prefix.length + sum - frameSize[s.frames - 1]);
            if (prefix.length + sum != m.targetBytes) throw new IOException("the rebuilt movie would have the wrong size");

            // pass 2: write it
            out.getParentFile().mkdirs();
            File tmp = new File(out.getPath() + ".part");
            MessageDigest md = sha();
            try (OutputStream o = new BufferedOutputStream(new FileOutputStream(tmp), 1 << 20)) {
                o.write(prefix);
                md.update(prefix);
                long done = prefix.length;
                fo = be32(s.prefix, 0x28) & 0xFFFFFFFFL;
                fsz = be32(s.prefix, 0x18) & 0xFFFFFFFFL;
                for (int i = 0; i < s.frames; i++) {
                    if (p != null && p.cancelled()) throw new IOException(t("Cancelled."));
                    byte[] fh = s.read(fo, 8 + 4 * k);
                    int audio = 0;
                    for (int c = 1; c < k; c++) audio += be32(fh, 8 + 4 * c) * s.tracks;
                    // the frame's audio packets follow its video in the original
                    byte[] packets = s.read(fo + 8 + 4L * k + (be32(fh, 8) & 0xFFFFFFFFL), audio);
                    byte[] head = new byte[8 + 4 * k];
                    put32(head, 0, frameSize[(i + 1) % s.frames]);
                    put32(head, 4, frameSize[(i - 1 + s.frames) % s.frames]);
                    put32(head, 8, jpegLen[i]);
                    System.arraycopy(fh, 12, head, 12, 4 * (k - 1));
                    byte[] jpeg = new byte[jpegLen[i]];
                    pf.seek(jpegAt[i]);
                    pf.readFully(jpeg);
                    if ((jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) throw new IOException("bad JPEG in the movie patch");
                    byte[] pad = new byte[frameSize[i] - head.length - jpeg.length - packets.length];
                    for (byte[] b : new byte[][] {head, jpeg, packets, pad}) {
                        o.write(b);
                        md.update(b);
                    }
                    done += frameSize[i];
                    if (p != null) p.at(done);
                    long next = be32(fh, 0) & 0xFFFFFFFFL;
                    fo += fsz;
                    fsz = next;
                }
            }
            if (!m.targetSha.equals(hex(md.digest()))) {
                tmp.delete();
                throw new IOException(t("%s failed its final check.", m.name()));
            }
            if (out.exists()) out.delete();
            if (!tmp.renameTo(out)) throw new IOException("cannot move " + m.name() + " into place");
        }
    }
}
