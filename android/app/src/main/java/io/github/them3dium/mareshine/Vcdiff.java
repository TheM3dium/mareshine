package io.github.them3dium.mareshine;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.util.zip.Adler32;

// Applies a VCDIFF delta (RFC 3284), as xdelta3 writes it: a mod's patch onto
// the player's own disc image (Mods). The same decoder as
// tools/mods/vcdiff.py: the default code table, VCD_SOURCE and VCD_TARGET
// windows and xdelta3's per-window Adler-32; deltas with secondary compression
// or a custom code table are refused. The delta is read as a stream, so it can
// come straight out of its archive.
public final class Vcdiff {
    private Vcdiff() {}

    public static final class VcdiffException extends IOException {
        VcdiffException(String m) { super(m); }
    }

    public interface Progress {
        // the output written so far; false to stop
        boolean update(long written);
    }

    private static final int NOOP = 0, ADD = 1, RUN = 2, COPY = 3;
    private static final int S_NEAR = 4, S_SAME = 3;
    // RFC 3284 section 5.6: per code, type, size and mode of its two instructions
    private static final int[][] TABLE = new int[256][];

    static {
        int i = 0;
        TABLE[i++] = new int[] {RUN, 0, 0, NOOP, 0, 0};
        for (int s = 0; s < 18; s++) TABLE[i++] = new int[] {ADD, s, 0, NOOP, 0, 0};
        for (int mode = 0; mode < 9; mode++) {
            TABLE[i++] = new int[] {COPY, 0, mode, NOOP, 0, 0};
            for (int s = 4; s < 19; s++) TABLE[i++] = new int[] {COPY, s, mode, NOOP, 0, 0};
        }
        for (int mode = 0; mode < 6; mode++)
            for (int a = 1; a < 5; a++)
                for (int c = 4; c < 7; c++) TABLE[i++] = new int[] {ADD, a, 0, COPY, c, mode};
        for (int mode = 6; mode < 9; mode++)
            for (int a = 1; a < 5; a++) TABLE[i++] = new int[] {ADD, a, 0, COPY, 4, mode};
        for (int mode = 0; mode < 9; mode++) TABLE[i++] = new int[] {COPY, 4, mode, ADD, 1, 0};
        if (i != 256) throw new IllegalStateException("code table");
    }

    // Writes out = delta applied to source, and returns the output's MD5 (hex).
    public static String apply(File source, InputStream delta, File out, Progress progress) throws IOException {
        MessageDigest md5;
        try {
            md5 = MessageDigest.getInstance("MD5");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        try (RandomAccessFile sf = new RandomAccessFile(source, "r");
             RandomAccessFile of = new RandomAccessFile(out, "rw")) {
            of.setLength(0);
            FileChannel sc = sf.getChannel();
            long srcLen = sc.size();
            if (u8(delta) != 0xD6 || u8(delta) != 0xC3 || u8(delta) != 0xC4 || u8(delta) != 0x00)
                throw new VcdiffException("not a VCDIFF delta");
            int hdr = u8(delta);
            if ((hdr & 1) != 0) throw new VcdiffException("secondary compression is not supported");
            if ((hdr & 2) != 0) throw new VcdiffException("custom code tables are not supported");
            if ((hdr & 4) != 0) skip(delta, varint(delta));  // application header
            long written = 0;
            while (true) {
                int win = delta.read();
                if (win < 0) break;
                byte[] seg = new byte[0];
                int slen = 0;
                if ((win & 3) != 0) {
                    slen = (int) varint(delta);
                    long spos = varint(delta);
                    seg = new byte[slen];
                    if ((win & 1) != 0) {
                        if (spos + slen > srcLen) throw new VcdiffException("source segment past the end of the source");
                        MappedByteBuffer m = sc.map(FileChannel.MapMode.READ_ONLY, spos, slen);
                        m.get(seg);
                    } else {
                        of.seek(spos);
                        of.readFully(seg);
                        of.seek(written);
                    }
                }
                varint(delta);  // length of the delta encoding
                int tlen = (int) varint(delta);
                if (u8(delta) != 0) throw new VcdiffException("compressed window sections are not supported");
                int dlen = (int) varint(delta), ilen = (int) varint(delta), alen = (int) varint(delta);
                long cksum = -1;
                if ((win & 4) != 0) cksum = ((long) u8(delta) << 24) | (u8(delta) << 16) | (u8(delta) << 8) | u8(delta);
                byte[] data = read(delta, dlen), inst = read(delta, ilen), addr = read(delta, alen);
                byte[] target = new byte[tlen];
                decodeWindow(target, seg, slen, data, inst, addr);
                if (cksum >= 0) {
                    Adler32 a = new Adler32();
                    a.update(target, 0, tlen);
                    if (a.getValue() != cksum)
                        throw new VcdiffException("checksum mismatch in the window at output offset " + written);
                }
                of.write(target);
                md5.update(target);
                written += tlen;
                if (progress != null && !progress.update(written)) throw new VcdiffException("stopped");
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md5.digest()) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }

    // One window's instructions, filling target
    private static void decodeWindow(byte[] target, byte[] seg, int slen, byte[] data, byte[] inst, byte[] addr)
            throws VcdiffException {
        long[] near = new long[S_NEAR];
        long[] same = new long[S_SAME * 256];
        int nextSlot = 0;
        int tpos = 0, dpos = 0;
        int[] ipos = {0}, apos = {0};
        try {
            while (ipos[0] < inst.length) {
                int[] code = TABLE[inst[ipos[0]++] & 0xFF];
                for (int k = 0; k < 2; k++) {
                    int typ = code[k * 3], size = code[k * 3 + 1], mode = code[k * 3 + 2];
                    if (typ == NOOP) continue;
                    if (size == 0) size = (int) varint(inst, ipos);
                    if (typ == ADD) {
                        System.arraycopy(data, dpos, target, tpos, size);
                        dpos += size;
                        tpos += size;
                    } else if (typ == RUN) {
                        java.util.Arrays.fill(target, tpos, tpos + size, data[dpos]);
                        dpos += 1;
                        tpos += size;
                    } else {
                        long here = (long) slen + tpos, a;
                        if (mode == 0) a = varint(addr, apos);
                        else if (mode == 1) a = here - varint(addr, apos);
                        else if (mode < 2 + S_NEAR) a = near[mode - 2] + varint(addr, apos);
                        else a = same[(mode - 2 - S_NEAR) * 256 + (addr[apos[0]++] & 0xFF)];
                        near[nextSlot] = a;
                        nextSlot = (nextSlot + 1) % S_NEAR;
                        same[(int) (a % (S_SAME * 256))] = a;
                        if (a >= here) throw new VcdiffException("copy address past the data decoded so far");
                        int n = size;
                        if (a < slen) {  // from the source segment, maybe running into the target
                            int c = (int) Math.min(n, slen - a);
                            System.arraycopy(seg, (int) a, target, tpos, c);
                            tpos += c;
                            n -= c;
                            a = slen;
                        }
                        int ta = (int) (a - slen);
                        while (n > 0) {  // overlapping: repeats the pattern [ta, tpos)
                            int c = Math.min(n, tpos - ta);
                            System.arraycopy(target, ta, target, tpos, c);
                            tpos += c;
                            ta += c;
                            n -= c;
                        }
                    }
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new VcdiffException("window sections do not add up");
        }
        if (tpos != target.length || dpos != data.length || apos[0] != addr.length)
            throw new VcdiffException("window sections do not add up");
    }

    private static long varint(byte[] buf, int[] pos) {
        long v = 0;
        while (true) {
            int b = buf[pos[0]++] & 0xFF;
            v = (v << 7) | (b & 0x7F);
            if ((b & 0x80) == 0) return v;
        }
    }

    private static long varint(InputStream in) throws IOException {
        long v = 0;
        while (true) {
            int b = u8(in);
            v = (v << 7) | (b & 0x7F);
            if ((b & 0x80) == 0) return v;
        }
    }

    private static int u8(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new VcdiffException("truncated delta");
        return b;
    }

    private static byte[] read(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) throw new VcdiffException("truncated delta");
            off += r;
        }
        return b;
    }

    private static void skip(InputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                if (in.read() < 0) throw new EOFException();
                s = 1;
            }
            n -= s;
        }
    }
}
