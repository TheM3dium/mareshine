package io.github.them3dium.mareshine;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.Locale;

import static io.github.them3dium.mareshine.L.t;

// Checks a disc image before the game loads it: the header (game, region,
// revision; the same rules as checkDisc in platform/gx/src/gx_launcher.cpp)
// and, once per file, the SHA-1 of the whole image against Redump's dump of
// the North American disc (redump.org/disc/1873, DL-DOL-GMSE-USA).
public final class DiscCheck {
    public static final long REDUMP_SIZE = 1459978240L;
    public static final String REDUMP_SHA1 = "8d094f2c5c112aba9660f0478b16c7f2caf63cbf";

    public static final class Result {
        public final boolean ok;      // the game can load it
        public final boolean plainIso; // a 1:1 dump, so its hash can be compared
        public final String summary;

        Result(boolean ok, boolean plainIso, String summary) {
            this.ok = ok;
            this.plainIso = plainIso;
            this.summary = summary;
        }
    }

    public interface Progress {
        void onProgress(long done, long total);
        boolean cancelled();
    }

    private DiscCheck() {}

    public static Result checkHeader(File file) {
        byte[] start = new byte[0x8000 + 0x440];
        int n;
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            n = readFully(f, start);
        } catch (IOException e) {
            return new Result(false, false, t("Cannot open this file: %s", e.getMessage()));
        }
        return checkStart(start, n, file.length());
    }

    // The same check on a file picked from another app, before it is copied.
    public static Result checkStream(InputStream in, long size) {
        byte[] start = new byte[0x8000 + 0x440];
        int n = 0;
        try {
            int r;
            while (n < start.length && (r = in.read(start, n, start.length - n)) > 0) n += r;
        } catch (IOException e) {
            return new Result(false, false, t("Cannot read this file: %s", e.getMessage()));
        }
        return checkStart(start, n, size);
    }

    // start: the first bytes of the file (up to 0x8440, enough for a CISO).
    private static Result checkStart(byte[] start, int len, long size) {
        byte[] h = new byte[0x440];
        String kind = "ISO image";
        boolean ciso = false;
        int n = Math.min(len, h.length);
        System.arraycopy(start, 0, h, 0, Math.max(0, n));
        if (n >= 4 && h[0] == 'C' && h[1] == 'I' && h[2] == 'S' && h[3] == 'O') {
            kind = "Dolphin CISO image";
            ciso = true;
            n = Math.max(0, Math.min(len - 0x8000, h.length));
            System.arraycopy(start, 0x8000, h, 0, n);
        }
        if (n >= 4 && ((h[0] == 'R' && h[1] == 'V' && h[2] == 'Z' && h[3] == 1)
                || (h[0] == 'W' && h[1] == 'I' && h[2] == 'A' && h[3] == 1)
                || ((h[0] & 0xFF) == 0xB1 && (h[1] & 0xFF) == 0x0B && (h[2] & 0xFF) == 0xC0 && h[3] == 1))) {
            String fmt = h[0] == 'R' ? "RVZ" : h[0] == 'W' ? "WIA" : "GCZ";
            return new Result(false, false, t("This file is a %s image (whatever its name says). "
                + "The game cannot read compressed images. In Dolphin on a PC, right-click the game, "
                + "choose Convert File and pick ISO, then use the new .iso file.", fmt));
        }
        long magic = ((h[0x1C] & 0xFFL) << 24) | ((h[0x1D] & 0xFFL) << 16) | ((h[0x1E] & 0xFFL) << 8) | (h[0x1F] & 0xFFL);
        if (n < 0x440 || magic != 0xC2339F3DL) {
            return new Result(false, false, t("This is not a GameCube disc image."));
        }
        boolean nkit = h[0x200] == 'N' && h[0x201] == 'K' && h[0x202] == 'I' && h[0x203] == 'T';
        if (nkit) kind = "NKit image";
        String id = new String(h, 0, 6, java.nio.charset.StandardCharsets.US_ASCII);
        if (!id.startsWith("GMS")) {
            return new Result(false, false, t("This is a different GameCube game (%s), not Super Mario Sunshine.", id));
        }
        if (!id.equals("GMSE01")) {
            char r = id.charAt(3);
            String msg = r == 'P' ? "This is the European release (%s). The port needs the North American release, GMSE01."
                : r == 'J' ? "This is the Japanese release (%s). The port needs the North American release, GMSE01."
                : "This is another release (%s). The port needs the North American release, GMSE01.";
            return new Result(false, false, t(msg, id));
        }
        if (h[7] != 0) {
            return new Result(false, false, t("This is revision %d. The port needs revision 0 of GMSE01.", (int) h[7]));
        }
        return new Result(true, !ciso && !nkit && size == REDUMP_SIZE,
            t("Super Mario Sunshine, North America (GMSE01, revision 0), %s, %.2f GB.", t(kind), size / 1e9));
    }

    private static int readFully(RandomAccessFile f, byte[] buf) throws IOException {
        int n = 0;
        while (n < buf.length) {
            int r = f.read(buf, n, buf.length - n);
            if (r < 0) break;
            n += r;
        }
        return n;
    }

    // The SHA-1 of the whole file as lowercase hex, or null when cancelled.
    public static String sha1(File file, Progress progress) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-1");
        } catch (Exception e) {
            throw new IOException("SHA-1 is not available");
        }
        long total = file.length(), done = 0;
        byte[] buf = new byte[1 << 20];
        try (InputStream in = new FileInputStream(file)) {
            int r;
            while ((r = in.read(buf)) > 0) {
                md.update(buf, 0, r);
                done += r;
                if (progress != null) {
                    if (progress.cancelled()) return null;
                    progress.onProgress(done, total);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    // For an image whose hash differs: compares every game file with the good
    // dump (assets/disc-files-gmse01.txt: path, size, SHA-1). A file that is
    // missing, lies past the end of the image, or has the original size but
    // other content is broken (a bad dump or a damaged copy); a file with
    // another size is taken as changed on purpose (a mod). Returns the broken
    // paths (empty when none), or null when cancelled.
    public static java.util.List<String> brokenFiles(Context ctx, File iso, Progress progress) throws IOException {
        java.util.List<String[]> good = new java.util.ArrayList<>();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                ctx.getAssets().open("disc-files-gmse01.txt"), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("#") || line.isEmpty()) continue;
                String[] p = line.split("\t");
                if (p.length == 3) good.add(p);
            }
        }
        java.util.List<String> broken = new java.util.ArrayList<>();
        java.util.Map<String, long[]> files = readFst(iso);
        if (files == null) {
            for (String[] g : good) broken.add(g[0]);
            return broken;
        }
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-1");
        } catch (Exception e) {
            throw new IOException("SHA-1 is not available");
        }
        long length = iso.length(), total = 0, done = 0;
        for (String[] g : good) {
            long[] e = files.get(g[0]);
            if (e != null && e[1] == Long.parseLong(g[1])) total += e[1];
        }
        byte[] buf = new byte[1 << 20];
        try (RandomAccessFile f = new RandomAccessFile(iso, "r")) {
            for (String[] g : good) {
                long[] e = files.get(g[0]);
                if (e == null || e[0] + e[1] > length) {
                    broken.add(g[0]);
                    continue;
                }
                if (e[1] != Long.parseLong(g[1])) continue; // changed on purpose
                md.reset();
                f.seek(e[0]);
                long left = e[1];
                while (left > 0) {
                    int r = f.read(buf, 0, (int) Math.min(buf.length, left));
                    if (r <= 0) break;
                    md.update(buf, 0, r);
                    left -= r;
                    done += r;
                    if (progress != null) {
                        if (progress.cancelled()) return null;
                        progress.onProgress(done, total);
                    }
                }
                StringBuilder sb = new StringBuilder();
                for (byte b : md.digest()) sb.append(String.format(Locale.ROOT, "%02x", b));
                if (left > 0 || !sb.toString().equals(g[2])) broken.add(g[0]);
            }
        }
        return broken;
    }

    // The disc's file table: path -> {offset, size}, or null when it is unreadable.
    private static java.util.Map<String, long[]> readFst(File iso) {
        try (RandomAccessFile f = new RandomAccessFile(iso, "r")) {
            f.seek(0x424);
            long off = f.readInt() & 0xFFFFFFFFL, size = f.readInt() & 0xFFFFFFFFL;
            if (size < 12 || size > (8 << 20) || off + size > f.length()) return null;
            byte[] d = new byte[(int) size];
            f.seek(off);
            f.readFully(d);
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(d); // big-endian
            int n = b.getInt(8);
            if (n < 1 || (long) n * 12 > size) return null;
            int strings = n * 12;
            java.util.Map<String, long[]> out = new java.util.HashMap<>();
            String[] dirs = new String[n];
            int[] dirEnd = new int[n];
            int depth = 0;
            for (int i = 1; i < n; i++) {
                while (depth > 0 && i >= dirEnd[depth - 1]) depth--;
                int word = b.getInt(i * 12);
                int nameOff = strings + (word & 0xFFFFFF);
                if (nameOff >= d.length) return null;
                int end = nameOff;
                while (end < d.length && d[end] != 0) end++;
                String name = new String(d, nameOff, end - nameOff, java.nio.charset.StandardCharsets.US_ASCII);
                String path = (depth > 0 ? dirs[depth - 1] : "") + name;
                long a = b.getInt(i * 12 + 4) & 0xFFFFFFFFL, c = b.getInt(i * 12 + 8) & 0xFFFFFFFFL;
                if ((word >>> 24) != 0) {
                    if (depth >= n) return null;
                    dirs[depth] = path + "/";
                    dirEnd[depth] = (int) Math.min(c, n);
                    depth++;
                } else {
                    out.put(path, new long[] {a, c});
                }
            }
            return out;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // Remembered file check: the broken paths joined by "\n" ("" for none).
    public static String cachedBroken(Context ctx, File f) {
        return ctx.getSharedPreferences("disc_check", Context.MODE_PRIVATE).getString("files|" + key(f), null);
    }

    public static void cacheBroken(Context ctx, File f, java.util.List<String> broken) {
        ctx.getSharedPreferences("disc_check", Context.MODE_PRIVATE).edit()
            .putString("files|" + key(f), String.join("\n", broken)).apply();
    }

    // Remembered hash results, so each file is hashed once (until it changes).
    private static String key(File f) {
        return f.getAbsolutePath() + "|" + f.length() + "|" + f.lastModified();
    }

    public static String cachedSha1(Context ctx, File f) {
        return ctx.getSharedPreferences("disc_check", Context.MODE_PRIVATE).getString(key(f), null);
    }

    public static void cacheSha1(Context ctx, File f, String sha1) {
        SharedPreferences p = ctx.getSharedPreferences("disc_check", Context.MODE_PRIVATE);
        p.edit().putString(key(f), sha1).apply();
    }
}
