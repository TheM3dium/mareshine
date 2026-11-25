package io.github.them3dium.mareshine;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

/**
 * A soft glow along a panel's border that moves with the winter music, in
 * the border's own colour (the Settings window while the event is on), and
 * is gone while no music plays. Two parts:
 * <ul>
 * <li>{@link #border}: the border itself lights up with how much is going on
 *     in the music (a layer of the panel's background);
 * <li>{@link #around}: the glow spreads outward from it in waves that run
 *     round the window like an equalizer: the bass in a few big slow waves,
 *     the treble in many small quick ones (the screen's foreground, only
 *     outside the panel).
 * </ul>
 * Both follow the music calmly (eased), so it breathes rather than flickers.
 */
final class MusicGlowDrawable extends Drawable {

    private static final int GLOW = 0x2FB8C9, BRIGHT = 0xBFF6FF;
    private static final int BANDS = 5;
    /** How slowly the border follows the music (s). */
    private static final float EASE = 0.5f;
    /** How slowly the waves follow it (s): quicker, they are the moving part. */
    private static final float WAVE_EASE = 0.25f;
    /**
     * How close the glow's dots sit round the outline, as a share of their
     * smallest size: close enough to blend into one glow, with no stripes.
     */
    private static final float DOT_SPACING = 0.18f;
    // per band (bass .. treble): waves round the window, and how fast they run (rad/s)
    private static final int[] WAVES = {2, 3, 5, 8, 13};
    private static final float[] SPEED = {0.6f, -0.9f, 1.3f, -1.8f, 2.4f};
    private static final float[] WEIGHT = {1.0f, 0.8f, 0.6f, 0.45f, 0.35f};

    private final Resources mRes;
    private final float mDp, mRadius;
    private final boolean mAround;
    private final float mMarginH, mMarginV;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mBox = new RectF();
    private final float[] mBands = new float[BANDS], mShownBands = new float[BANDS];
    private float mShown;
    private long mLast;

    // the panel's outline: points evenly round it, their outward normals,
    // and where they are along it (0..1)
    private int mPoints;
    private float[] mPx, mPy, mNx, mNy, mS;
    private final Rect mOutlineFor = new Rect();

    private MusicGlowDrawable(Resources res, float radiusDp, boolean around, float marginHDp, float marginVDp) {
        mRes = res;
        mDp = res.getDisplayMetrics().density;
        mRadius = radiusDp * mDp;
        mAround = around;
        mMarginH = marginHDp * mDp;
        mMarginV = marginVDp * mDp;
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeJoin(Paint.Join.ROUND);
    }

    /** The panel's border, lit up with the music (a layer of the panel's own background). */
    static MusicGlowDrawable border(Resources res, float radiusDp) {
        return new MusicGlowDrawable(res, radiusDp, false, 0, 0);
    }

    /** The waves outward from a panel that sits these margins inside the screen (its foreground). */
    static MusicGlowDrawable around(Resources res, float radiusDp, float marginHDp, float marginVDp) {
        return new MusicGlowDrawable(res, radiusDp, true, marginHDp, marginVDp);
    }

    @Override
    public void draw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        float dt = mLast == 0 ? 0 : Math.min(0.1f, (now - mLast) / 1000f);
        mLast = now;
        boolean playing = WinterLogoView.musicBands(mRes, mBands);
        float energy = 0;
        for (int b = 0; b < BANDS; b++) energy += playing ? mBands[b] / BANDS : 0;
        float target = playing ? 0.45f + 0.55f * Math.max(0, Math.min(1, (energy - 0.3f) / 0.35f)) : 0;
        mShown += (target - mShown) * (1 - (float) Math.exp(-dt / EASE));
        float k = 1 - (float) Math.exp(-dt / WAVE_EASE);
        for (int b = 0; b < BANDS; b++) mShownBands[b] += ((playing ? mBands[b] : 0) - mShownBands[b]) * k;

        if (mShown > 0.005f) {
            if (mAround) drawWaves(canvas, now / 1000f);
            else drawBorder(canvas);
        }

        if (playing || mShown > 0.005f) invalidateSelf();
        else {
            // no music: look again in a while (it can be turned on from here)
            mLast = 0;
            unscheduleSelf(mCheck);
            scheduleSelf(mCheck, SystemClock.uptimeMillis() + 500);
        }
    }

    private final Runnable mCheck = this::invalidateSelf;

    private void drawBorder(Canvas canvas) {
        mBox.set(getBounds());
        mBox.inset(1.25f * mDp, 1.25f * mDp);
        mPaint.setStrokeWidth(2.5f * mDp);
        mPaint.setColor(0xFF000000 | BRIGHT);
        mPaint.setAlpha(Math.round(mShown * 255));
        canvas.drawRoundRect(mBox, mRadius, mRadius, mPaint);
    }

    private void drawWaves(Canvas canvas, float t) {
        Rect b = getBounds();
        if (!b.equals(mOutlineFor)) outline(b);
        if (mDot == null) mDot = dot();
        // how far it reaches at each point: a calm base, plus the bands' waves
        // running round (kept inside the margin, so it never ends in a hard line)
        float room = Math.max(0, Math.min(mMarginH, mMarginV) - 2 * mDp);
        int save = canvas.save();
        mPanel.set(b.left + mMarginH, b.top + mMarginV, b.right - mMarginH, b.bottom - mMarginV);
        mClip.reset();
        mClip.addRoundRect(mPanel, mRadius, mRadius, Path.Direction.CW);
        canvas.clipOutPath(mClip);  // only outside the panel
        for (int p = 0; p < mPoints; p++) {
            float w = 0;
            for (int band = 0; band < BANDS; band++) {
                float phase = (float) (2 * Math.PI) * WAVES[band] * mS[p] - SPEED[band] * t;
                w += WEIGHT[band] * mShownBands[band] * (0.5f + 0.5f * (float) Math.sin(phase));
            }
            // a soft dot on the border, as big as the glow reaches there
            float wn = Math.min(1f, w / 1.6f);
            float reach = room * (0.5f + 0.5f * wn);
            mDotPaint.setAlpha(Math.round(mShown * 120 * (0.35f + 0.65f * wn)));
            mDst.set(mPx[p] - reach, mPy[p] - reach, mPx[p] + reach, mPy[p] + reach);
            canvas.drawBitmap(mDot, null, mDst, mDotPaint);
        }
        canvas.restoreToCount(save);
    }

    private Bitmap mDot;
    private final Paint mDotPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF mDst = new RectF(), mPanel = new RectF();
    private final Path mClip = new Path();

    /** A soft round dot in the glow's colour, fading out to its edge. */
    private static Bitmap dot() {
        int n = 64;
        Bitmap bm = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new android.graphics.RadialGradient(n / 2f, n / 2f, n / 2f,
            new int[] {0xD0000000 | GLOW, 0x60000000 | GLOW, 0x00000000 | GLOW}, new float[] {0f, 0.45f, 1f},
            android.graphics.Shader.TileMode.CLAMP));
        new Canvas(bm).drawCircle(n / 2f, n / 2f, n / 2f, p);
        return bm;
    }

    /** Points evenly round the panel's rounded outline, with their outward normals. */
    private void outline(Rect b) {
        mOutlineFor.set(b);
        float l = b.left + mMarginH, t = b.top + mMarginV, r = b.right - mMarginH, bo = b.bottom - mMarginV;
        float rad = Math.min(mRadius, Math.min(r - l, bo - t) / 2);
        float w = r - l - 2 * rad, h = bo - t - 2 * rad, arc = (float) (Math.PI / 2) * rad;
        float total = 2 * w + 2 * h + 4 * arc;
        // as many dots as it takes (the smallest is half the room)
        float room = Math.max(1, Math.min(mMarginH, mMarginV) - 2 * mDp);
        mPoints = Math.max(120, Math.min(1500, (int) Math.ceil(total / (room * DOT_SPACING))));
        mPx = new float[mPoints]; mPy = new float[mPoints];
        mNx = new float[mPoints]; mNy = new float[mPoints]; mS = new float[mPoints];
        for (int p = 0; p < mPoints; p++) {
            float s = p / (float) mPoints, d = s * total;
            mS[p] = s;
            // top, top right corner, right, bottom right, bottom, bottom left, left, top left
            float[] seg = {w, arc, h, arc, w, arc, h, arc};
            int k = 0;
            while (k < 7 && d > seg[k]) { d -= seg[k]; k++; }
            float x, y, nx, ny;
            switch (k) {
                case 0: x = l + rad + d; y = t; nx = 0; ny = -1; break;
                case 2: x = r; y = t + rad + d; nx = 1; ny = 0; break;
                case 4: x = r - rad - d; y = bo; nx = 0; ny = 1; break;
                case 6: x = l; y = bo - rad - d; nx = -1; ny = 0; break;
                default: {
                    // corners: centre and start angle, going clockwise
                    float cx = k == 1 || k == 3 ? r - rad : l + rad;
                    float cy = k == 1 || k == 7 ? t + rad : bo - rad;
                    double a0 = k == 1 ? -Math.PI / 2 : k == 3 ? 0 : k == 5 ? Math.PI / 2 : Math.PI;
                    double a = a0 + (rad > 0 ? d / rad : 0);
                    nx = (float) Math.cos(a); ny = (float) Math.sin(a);
                    x = cx + nx * rad; y = cy + ny * rad;
                }
            }
            mPx[p] = x; mPy[p] = y; mNx[p] = nx; mNy[p] = ny;
        }
    }

    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter cf) { mPaint.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
