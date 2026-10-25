package io.github.them3dium.mareshine;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

// The mod manager's pictures (ModsActivity, ModPageActivity): a mod's suns,
// its disc with its icon as the label, and its pictures loaded from its
// folder or a URL (cached in the app's cache).
final class ModViews {
    private ModViews() {}

    static final int GOLD = 0xFFFFC531, SUN_OFF = 0x55FFFFFF;

    // Five suns, filled up to the rating (halves and the like as part of a sun)
    static final class Suns extends View {
        private float mRating = -1;
        private final Paint mOn = new Paint(Paint.ANTI_ALIAS_FLAG), mOff = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mSun = new Path();

        Suns(Context c) {
            super(c);
            mOn.setColor(GOLD);
            mOff.setColor(SUN_OFF);
        }

        void setRating(float r) {
            mRating = r;
            invalidate();
        }

        @Override
        protected void onMeasure(int w, int h) {
            int height = MeasureSpec.getSize(h);
            setMeasuredDimension(Math.round(height * 5.4f), height);
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = getHeight(), step = s * 1.1f;
            for (int i = 0; i < 5; i++) {
                float x = i * step, fill = Math.max(0, Math.min(1, mRating - i));
                sun(mSun, x + s / 2, s / 2, s / 2);
                c.drawPath(mSun, mOff);
                if (fill > 0) {
                    c.save();
                    c.clipRect(x, 0, x + s * fill, s);
                    c.drawPath(mSun, mOn);
                    c.restore();
                }
            }
        }

        // a sun: a disc and eight rays
        private static void sun(Path p, float cx, float cy, float r) {
            p.reset();
            p.addCircle(cx, cy, r * 0.58f, Path.Direction.CW);
            for (int k = 0; k < 8; k++) {
                double a = k * Math.PI / 4, w = Math.PI / 9;
                p.moveTo(cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r);
                p.lineTo(cx + (float) Math.cos(a - w) * r * 0.62f, cy + (float) Math.sin(a - w) * r * 0.62f);
                p.lineTo(cx + (float) Math.cos(a + w) * r * 0.62f, cy + (float) Math.sin(a + w) * r * 0.62f);
                p.close();
            }
        }
    }

    // A disc with the mod's icon as its label (or its first letter)
    static final class Disc extends View {
        private Bitmap mLabel;
        private String mLetter = "";
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        Disc(Context c) { super(c); }

        void set(Bitmap label, String name) {
            mLabel = label;
            mLetter = name == null || name.isEmpty() ? "" : name.substring(0, 1).toUpperCase(Locale.ROOT);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight()), cx = getWidth() / 2f, cy = getHeight() / 2f, r = s / 2f;
            // the silver disc and its sheen
            mPaint.setShader(new RadialGradient(cx - r * 0.3f, cy - r * 0.3f, r * 1.4f,
                new int[] {0xFFF4F8FF, 0xFFB8C6D6, 0xFF7D8DA0}, null, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, mPaint);
            mPaint.setShader(null);
            // the label: the picture over the disc, short of its rim
            float lr = r * 0.9f;
            if (mLabel != null) {
                // a wide label (a disc's banner) shows whole across a dark label
                boolean wide = mLabel.getWidth() > mLabel.getHeight() * 1.4f;
                BitmapShader sh = new BitmapShader(mLabel, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                Matrix m = new Matrix();
                float sc = wide ? 1.5f * lr / mLabel.getWidth()
                    : Math.max(2 * lr / mLabel.getWidth(), 2 * lr / mLabel.getHeight());
                float my = wide ? cy - r * 0.52f : cy;  // a banner above the hole
                m.setScale(sc, sc);
                m.postTranslate(cx - mLabel.getWidth() * sc / 2, my - mLabel.getHeight() * sc / 2);
                sh.setLocalMatrix(m);
                mPaint.setShader(sh);
                if (wide) {
                    float hw = mLabel.getWidth() * sc / 2, hh = mLabel.getHeight() * sc / 2;
                    mPaint.setShader(null);
                    mPaint.setColor(0xFF0A1B30);
                    c.drawRoundRect(cx - hw - r * 0.04f, my - hh - r * 0.04f, cx + hw + r * 0.04f, my + hh + r * 0.04f,
                        r * 0.06f, r * 0.06f, mPaint);
                    mPaint.setShader(sh);
                    c.drawRect(cx - hw, my - hh, cx + hw, my + hh, mPaint);
                } else {
                    c.drawCircle(cx, cy, lr, mPaint);
                }
                mPaint.setShader(null);
            } else {
                mPaint.setColor(0xFF123A5C);
                c.drawCircle(cx, cy, lr, mPaint);
                mPaint.setColor(GOLD);
                // the letter above the hole
                mPaint.setTextSize(r * 0.55f);
                mPaint.setTextAlign(Paint.Align.CENTER);
                mPaint.setFakeBoldText(true);
                c.drawText(mLetter, cx, cy - r * 0.34f, mPaint);
            }
            // the hole and its ring
            mPaint.setColor(0xFFDCE4EE);
            c.drawCircle(cx, cy, r * 0.24f, mPaint);
            mPaint.setColor(0xFF0A1B30);
            c.drawCircle(cx, cy, r * 0.12f, mPaint);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(Math.max(1, r * 0.03f));
            mPaint.setColor(0x66000000);
            c.drawCircle(cx, cy, r - mPaint.getStrokeWidth() / 2, mPaint);
            mPaint.setStyle(Paint.Style.FILL);
        }
    }

    // --- pictures -----------------------------------------------------------------

    interface Got { void picture(Bitmap b); }

    private static final Handler sMain = new Handler(Looper.getMainLooper());

    // Loads a picture (a file, or an https URL cached in the app's cache),
    // scaled down to about maxSide, and hands it over on the main thread
    // (null when there is none).
    static void load(Context ctx, String where, int maxSide, Got got) {
        if (where == null || where.isEmpty()) {
            got.picture(null);
            return;
        }
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            Bitmap b = null;
            try {
                File f = where.startsWith("https://") ? cached(app, where) : new File(where);
                if (f != null && f.isFile()) b = decode(f, maxSide);
            } catch (Exception ignored) {
            }
            final Bitmap out = b;
            sMain.post(() -> got.picture(out));
        }, "mod-picture").start();
    }

    private static Bitmap decode(File f, int maxSide) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
    }

    private static File cached(Context ctx, String url) throws Exception {
        File dir = new File(ctx.getCacheDir(), "mod-pictures");
        dir.mkdirs();
        StringBuilder name = new StringBuilder();
        for (byte b : MessageDigest.getInstance("MD5").digest(url.getBytes("UTF-8")))
            name.append(String.format(Locale.ROOT, "%02x", b));
        File f = new File(dir, name.toString());
        if (f.isFile() && f.length() > 0) return f;
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "Mareshine");
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        File part = new File(dir, name + ".part");
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
            byte[] buf = new byte[1 << 15];
            long total = 0;
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                if ((total += n) > (16L << 20)) throw new java.io.IOException("too large");
            }
        } finally {
            c.disconnect();
        }
        return part.renameTo(f) ? f : null;
    }
}
