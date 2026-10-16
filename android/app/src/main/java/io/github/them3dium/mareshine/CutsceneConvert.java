package io.github.them3dium.mareshine;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;

import static io.github.them3dium.mareshine.L.t;

// Turns an HD pack movie (THP) into an MP4 for the hardware video decoder
// (platform/thp/mp4_movies.cpp): H.264 Baseline, so frames come out in their
// own order, with exactly the THP's frames at its 29.97 fps. Video only: the
// game keeps playing the disc's THP for the sound and the timing.
//
// THP frames are JPEGs without byte stuffing (an 0xFF in the scan data is not
// followed by 0x00), so each is stuffed before Android's decoder reads it.
final class CutsceneConvert {
    private CutsceneConvert() {}

    interface Progress {
        void frame(int done, int total);
        boolean cancelled();
    }

    static int be32(byte[] d, int o) { return CutscenePack.be32(d, o); }

    // the JPEG with its scan data stuffed
    static byte[] stuff(byte[] j) throws IOException {
        int i = 2;
        int sos = -1;
        while (i + 4 <= j.length) {
            if ((j[i] & 0xFF) != 0xFF) throw new IOException("bad JPEG marker");
            int m = j[i + 1] & 0xFF;
            int len = (j[i + 2] & 0xFF) << 8 | (j[i + 3] & 0xFF);
            if (m == 0xDA) { sos = i + 2 + len; break; }
            i += 2 + len;
        }
        if (sos < 0) throw new IOException("JPEG without image data");
        int end = j.length - 2; // the EOI
        ByteArrayOutputStream o = new ByteArrayOutputStream(j.length + j.length / 64);
        o.write(j, 0, sos);
        for (int k = sos; k < end; k++) {
            o.write(j[k]);
            if ((j[k] & 0xFF) == 0xFF) o.write(0);
        }
        o.write(j, end, 2);
        return o.toByteArray();
    }

    @SuppressWarnings("unchecked")
    static void convert(File thp, File mp4, Progress p) throws Exception {
        try (RandomAccessFile f = new RandomAccessFile(thp, "r")) {
            byte[] h = new byte[0x40];
            f.readFully(h);
            if (h[0] != 'T' || h[1] != 'H' || h[2] != 'P') throw new IOException(thp.getName() + " is not a THP movie");
            final int frames = be32(h, 0x14);
            int comp = be32(h, 0x20);
            byte[] ch = new byte[4];
            f.seek(comp);
            f.readFully(ch);
            final int k = be32(ch, 0);
            byte[] wh = new byte[8];
            f.seek(comp + 20);
            f.readFully(wh);
            final int w = be32(wh, 0), hgt = be32(wh, 4);
            if (w <= 0 || hgt <= 0 || w > 2048 || hgt > 2048 ) throw new IOException("unexpected THP size");

            // the JPEGs, read ahead in order and decoded on three threads
            final ArrayBlockingQueue<Object> queue = new ArrayBlockingQueue<>(6);
            final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(3);
            final long first = be32(h, 0x28) & 0xFFFFFFFFL, firstSize = be32(h, 0x18) & 0xFFFFFFFFL;
            Thread reader = new Thread(() -> {
                try (RandomAccessFile rf = new RandomAccessFile(thp, "r")) {
                    long off = first, size = firstSize;
                    byte[] fh = new byte[8 + 4 * k];
                    for (int i = 0; i < frames; i++) {
                        if (p.cancelled()) break;
                        rf.seek(off);
                        rf.readFully(fh);
                        int n = be32(fh, 8);
                        byte[] jpeg = new byte[n];
                        rf.readFully(jpeg);
                        final int index = i;
                        queue.put(pool.submit(() -> {
                            byte[] fixed = stuff(jpeg);
                            BitmapFactory.Options opt = new BitmapFactory.Options();
                            opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
                            Bitmap b = BitmapFactory.decodeByteArray(fixed, 0, fixed.length, opt);
                            if (b == null) throw new IOException("frame " + index + " of " + thp.getName() + " cannot be read");
                            return b;
                        }));
                        long next = be32(fh, 0) & 0xFFFFFFFFL;
                        off += size;
                        size = next;
                    }
                } catch (Exception e) {
                    try { queue.put(e); } catch (InterruptedException ignored) {}
                }
            }, "thp-reader");

            MediaFormat fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, hgt);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, (int) Math.min(12_000_000L, (long) w * hgt * 4));
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            fmt.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            fmt.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41);
            MediaCodec enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Surface in = enc.createInputSurface();
            enc.start();
            File tmp = new File(mp4.getPath() + ".part");
            tmp.delete();
            mp4.getParentFile().mkdirs();
            MediaMuxer mux = new MediaMuxer(tmp.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int[] track = {-1};
            int[] written = {0};
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            reader.start();
            boolean ok = false;
            GlInput gl = null;
            try {
                for (int i = 0; i < frames; i++) {
                    if (p.cancelled()) throw new IOException(t("Cancelled."));
                    Object x = queue.take();
                    if (x instanceof Exception) throw (Exception) x;
                    Bitmap b;
                    try {
                        b = ((java.util.concurrent.Future<Bitmap>) x).get();
                    } catch (java.util.concurrent.ExecutionException e) {
                        throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
                    }
                    if (gl == null) gl = new GlInput(in, w, hgt);
                    gl.draw(b, i * 1001000000L / 30); // frame i at 30000/1001 fps, in ns
                    b.recycle();
                    drain(enc, mux, track, written, info, false);
                    p.frame(i + 1, frames);
                }
                enc.signalEndOfInputStream();
                drain(enc, mux, track, written, info, true);
                ok = true;
            } finally {
                reader.interrupt();
                try { reader.join(2000); } catch (InterruptedException ignored) {}
                pool.shutdownNow();
                if (gl != null) gl.release();
                in.release();
                try { enc.stop(); } catch (Exception ignored) {}
                enc.release();
                try { if (track[0] >= 0) mux.stop(); } catch (Exception ignored) {}
                mux.release();
                if (!ok) tmp.delete();
            }
            if (written[0] != frames) {
                tmp.delete();
                throw new IOException(t("%s: the encoder gave %d frames instead of %d.", mp4.getName(), written[0], frames));
            }
            if (mp4.exists()) mp4.delete();
            if (!tmp.renameTo(mp4)) throw new IOException("cannot move " + mp4.getName() + " into place");
        }
    }

    // The frames go to the encoder through OpenGL ES with their own
    // presentation times (a Canvas post stamps them with the clock, and the
    // encoder drops one that comes too close to the one before).
    static final class GlInput {
        private final EGLDisplay dpy;
        private final EGLContext ctx;
        private final EGLSurface surf;
        private final int prog, tex, aPos, aUv;
        private final java.nio.FloatBuffer quad;

        GlInput(Surface s, int w, int h) throws IOException {
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] v = new int[2];
            if (!EGL14.eglInitialize(dpy, v, 0, v, 1)) throw new IOException("no EGL display");
            int[] attr = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, 0x3142 /* EGL_RECORDABLE_ANDROID */, 1, EGL14.EGL_NONE};
            EGLConfig[] cfg = new EGLConfig[1];
            int[] n = new int[1];
            if (!EGL14.eglChooseConfig(dpy, attr, 0, cfg, 0, 1, n, 0) || n[0] == 0) throw new IOException("no EGL config for the encoder");
            ctx = EGL14.eglCreateContext(dpy, cfg[0], EGL14.EGL_NO_CONTEXT, new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            surf = EGL14.eglCreateWindowSurface(dpy, cfg[0], s, new int[] {EGL14.EGL_NONE}, 0);
            if (ctx == EGL14.EGL_NO_CONTEXT || surf == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(dpy, surf, surf, ctx))
                throw new IOException("cannot draw to the encoder");
            GLES20.glViewport(0, 0, w, h);
            prog = program("attribute vec2 p; attribute vec2 t; varying vec2 v; void main() { v = t; gl_Position = vec4(p, 0.0, 1.0); }",
                "precision mediump float; varying vec2 v; uniform sampler2D s; void main() { gl_FragColor = texture2D(s, v); }");
            aPos = GLES20.glGetAttribLocation(prog, "p");
            aUv = GLES20.glGetAttribLocation(prog, "t");
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            tex = t[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            // the bitmap's top row at the top of the frame
            float[] q = {-1, -1, 0, 1,  1, -1, 1, 1,  -1, 1, 0, 0,  1, 1, 1, 0};
            quad = java.nio.ByteBuffer.allocateDirect(q.length * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(q).position(0);
        }

        private static int program(String vs, String fs) throws IOException {
            int p = GLES20.glCreateProgram();
            for (int i = 0; i < 2; i++) {
                int sh = GLES20.glCreateShader(i == 0 ? GLES20.GL_VERTEX_SHADER : GLES20.GL_FRAGMENT_SHADER);
                GLES20.glShaderSource(sh, i == 0 ? vs : fs);
                GLES20.glCompileShader(sh);
                GLES20.glAttachShader(p, sh);
            }
            GLES20.glLinkProgram(p);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) throw new IOException("encoder shader: " + GLES20.glGetProgramInfoLog(p));
            return p;
        }

        void draw(Bitmap b, long ptsNs) {
            GLES20.glUseProgram(prog);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
            quad.position(0);
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad);
            GLES20.glEnableVertexAttribArray(aPos);
            quad.position(2);
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, quad);
            GLES20.glEnableVertexAttribArray(aUv);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            EGLExt.eglPresentationTimeANDROID(dpy, surf, ptsNs);
            EGL14.eglSwapBuffers(dpy, surf);
        }

        void release() {
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(dpy, surf);
            EGL14.eglDestroyContext(dpy, ctx);
            EGL14.eglTerminate(dpy);
        }
    }

    // writes what the encoder has made; at the end, until its last frame
    private static void drain(MediaCodec enc, MediaMuxer mux, int[] track, int[] written,
                              MediaCodec.BufferInfo info, boolean end) throws IOException {
        long deadline = System.currentTimeMillis() + (end ? 10000 : 0);
        while (true) {
            int idx = enc.dequeueOutputBuffer(info, end ? 10000 : 0);
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!end || System.currentTimeMillis() > deadline) return;
                continue;
            }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track[0] = mux.addTrack(enc.getOutputFormat());
                mux.start();
                continue;
            }
            if (idx < 0) continue;
            ByteBuffer buf = enc.getOutputBuffer(idx);
            boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
            if (!config && info.size > 0 && buf != null) {
                if (track[0] < 0) throw new IOException("the encoder gave no format");
                // frame n of the movie at 30000/1001 fps
                info.presentationTimeUs = written[0] * 1001000L / 30;
                buf.position(info.offset);
                buf.limit(info.offset + info.size);
                mux.writeSampleData(track[0], buf, info);
                written[0]++;
            }
            enc.releaseOutputBuffer(idx, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
        }
    }
}
