package io.github.them3dium.mareshine;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.widget.ImageView;

import java.io.InputStream;

/**
 * The launcher's logo; in the winter event its chain of lights is a light
 * organ that plays along with the Christmas music.
 *
 * <p>The winter logo ({@code mareshine_logo_winter}) shows the chain with its
 * lights off; {@code winter_logo_lights} is the same logo with them on, cut to
 * a soft disc around each bulb. Each bulb fades between the two, and on a
 * strong beat a glow of its colour blooms over it.
 *
 * <p>The bulbs come in five colours, one per band of the music as in an old
 * light organ: red the bass, then magenta, yellow, green, and cyan the
 * treble. Their levels are worked out beforehand from the track itself
 * ({@code res/raw/winter_lights_N} for each of {@link Winter#TRACKS}: 50
 * frames a second, a byte per band) and
 * read at the player's position, so it needs no audio capture (and no
 * permission). Without music (muted, or the menu left) all lights stay on.
 */
public class WinterLogoView extends ImageView {

    // Per bulb: x, y and radius in the winter logo (1100 px wide), and its band.
    // Found from the colours of the chain's own layer (20.11.2026).
    private static final float[] BULBS = {
        164.7f, 356.6f, 8.3f, 3,
        237.6f, 311.2f, 9.5f, 1,
        281.8f, 266.4f, 9.7f, 2,
        335.3f, 212.5f, 9.2f, 4,
        352.7f, 401.4f, 8.3f, 3,
        407.1f, 414.6f, 9.2f, 1,
        470.5f, 424.8f, 9.5f, 2,
        531.5f, 426.2f, 9.3f, 0,
        585.9f, 412.1f, 8.6f, 3,
        635.7f, 348.0f, 9.7f, 2,
        683.1f, 342.9f, 9.6f, 0,
        734.7f, 290.7f, 8.8f, 4,
        758.6f, 226.7f, 9.9f, 2,
        847.5f, 140.0f, 10.5f, 1,
        849.3f, 191.6f, 10.1f, 2,
        854.0f, 227.9f, 9.4f, 1,
        859.3f, 273.5f, 8.9f, 4,
        882.7f, 104.4f, 11.2f, 2,
        893.8f, 246.0f, 9.3f, 4,
        923.1f, 296.0f, 9.3f, 0,
        936.9f, 86.4f, 6.8f, 3,
        939.9f, 263.7f, 9.5f, 0,
        979.5f, 249.9f, 9.5f, 2,
        986.8f, 102.4f, 10.0f, 4,
        991.2f, 315.3f, 9.6f, 2,
        1015.8f, 137.1f, 10.2f, 0,
        1017.0f, 232.0f, 9.4f, 1,
        1027.8f, 186.2f, 9.9f, 2,
    };
    private static final int BANDS = 5, FPS = 50;
    private static final int[] GLOW_COLORS = {0xFFFF3B30, 0xFFFF4FD0, 0xFFFFC83A, 0xFF5CFF4A, 0xFF3AD8FF};
    /** The lit overlay reaches this many radii around a bulb (as it was cut). */
    private static final float LIT_REACH = 2.6f;
    /** The dimmest a bulb goes while the music plays. */
    private static final float FLOOR = 0.12f;

    private static final byte[][] sTracks = new byte[Winter.TRACKS.length][];
    private static final int[] LIGHT_TRACKS = {R.raw.winter_lights_1, R.raw.winter_lights_2, R.raw.winter_lights_3};
    private byte[] mTrack;
    private static Bitmap sLit, sGlow;

    private boolean mLights;
    private final float[] mShown = new float[BANDS];
    private long mLast;
    private final Paint mLitPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint mGlowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect mSrc = new Rect();
    private final RectF mDst = new RectF();
    private final Rect mGlowSrc = new Rect();
    private final PorterDuffColorFilter[] mTints = new PorterDuffColorFilter[BANDS];

    public WinterLogoView(Context c, AttributeSet a) {
        super(c, a);
        mGlowPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        java.util.Arrays.fill(mShown, 1f);
        for (int b = 0; b < BANDS; b++) mTints[b] = new PorterDuffColorFilter(GLOW_COLORS[b], PorterDuff.Mode.SRC_IN);
    }

    private boolean mChase;

    /** Without music the lights run along the chain instead of all being on (the winter update's intro). */
    void setChase(boolean chase) {
        mChase = chase;
        invalidate();
    }

    /** Shows the winter logo, with its lights on or off (or the normal logo). */
    void setWinter(boolean on, boolean lights) {
        setImageResource(on ? R.drawable.mareshine_logo_winter : R.drawable.mareshine_logo);
        mLights = on && lights && load();
        mLast = 0;
        invalidate();
    }

    private boolean load() {
        try {
            if (sLit == null) sLit = BitmapFactory.decodeResource(getResources(), R.drawable.winter_logo_lights);
            if (sGlow == null) sGlow = BitmapFactory.decodeResource(getResources(), R.drawable.winter_glow);
            loadTracks(getResources());
            mGlowSrc.set(0, 0, sGlow.getWidth(), sGlow.getHeight());
            return sLit != null;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!mLights || getDrawable() == null) return;

        // the music's levels now (all on without music), eased a little so a
        // stop or start of the music fades rather than jumps
        long now = SystemClock.uptimeMillis();
        float dt = mLast == 0 ? 1f : Math.min(0.1f, (now - mLast) / 1000f);
        mLast = now;
        int pos = Winter.musicPosition();
        mTrack = sTracks[Winter.musicTrack() % sTracks.length];
        boolean playing = pos >= 0 && mTrack != null;
        boolean settling = false;
        for (int b = 0; b < BANDS; b++) {
            float target = playing ? FLOOR + (1 - FLOOR) * level(pos, b)
                : mChase ? FLOOR + (1 - FLOOR) * (0.5f + 0.5f * (float) Math.sin(now / 260.0 - b * 2 * Math.PI / BANDS)) : 1f;
            // the track has its own fade; this only smooths music on / off
            float k = playing ? 1f : 1 - (float) Math.exp(-dt * 4);
            mShown[b] += (target - mShown[b]) * k;
            if (Math.abs(target - mShown[b]) > 0.004f) settling = true;
        }

        int save = canvas.save();
        canvas.translate(getPaddingLeft(), getPaddingTop());
        canvas.concat(getImageMatrix());
        float sx = sLit.getWidth() / (float) getDrawable().getIntrinsicWidth();
        float sy = sLit.getHeight() / (float) getDrawable().getIntrinsicHeight();
        for (int i = 0; i < BULBS.length; i += 4) {
            float x = BULBS[i], y = BULBS[i + 1], r = BULBS[i + 2];
            int band = (int) BULBS[i + 3];
            float l = mShown[band];
            // the bulb itself: from off to on
            float reach = r * LIT_REACH;
            mDst.set(x - reach, y - reach, x + reach, y + reach);
            mSrc.set((int) (mDst.left * sx), (int) (mDst.top * sy), (int) Math.ceil(mDst.right * sx), (int) Math.ceil(mDst.bottom * sy));
            mDst.set(mSrc.left / sx, mSrc.top / sy, mSrc.right / sx, mSrc.bottom / sy);
            mLitPaint.setAlpha(Math.round(l * 255));
            canvas.drawBitmap(sLit, mSrc, mDst, mLitPaint);
            // a strong beat: a glow of its colour on top
            if (playing && l > 0.6f) {
                float g = (l - 0.6f) / 0.4f;
                float gr = r * 4.2f;
                mDst.set(x - gr, y - gr, x + gr, y + gr);
                mGlowPaint.setColorFilter(mTints[band]);
                mGlowPaint.setAlpha(Math.round(g * g * 150));
                canvas.drawBitmap(sGlow, mGlowSrc, mDst, mGlowPaint);
            }
        }
        canvas.restoreToCount(save);

        if (playing || settling || mChase) postInvalidateOnAnimation();
        else mLast = 0;
    }

    private static void loadTracks(android.content.res.Resources res) throws java.io.IOException {
        for (int t = 0; t < sTracks.length; t++) {
            if (sTracks[t] != null) continue;
            try (InputStream in = res.openRawResource(LIGHT_TRACKS[t])) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(90_000);
                byte[] b = new byte[16384];
                for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
                sTracks[t] = out.toByteArray();
            }
        }
    }

    /**
     * How much is going on in the music now (0..1: all bands' levels, averaged),
     * or -1 while it does not play. For other things that move with it.
     */
    static float musicEnergy(android.content.res.Resources res) {
        int pos = Winter.musicPosition();
        if (pos < 0) return -1;
        try {
            loadTracks(res);
        } catch (java.io.IOException e) {
            return -1;
        }
        byte[] track = sTracks[Winter.musicTrack() % sTracks.length];
        if (track == null) return -1;
        float sum = 0;
        for (int b = 0; b < BANDS; b++) sum += level(track, pos, b);
        return sum / BANDS;
    }

    /**
     * The music's five bands now (bass .. treble, 0..1) into {@code out};
     * false while it does not play.
     */
    static boolean musicBands(android.content.res.Resources res, float[] out) {
        int pos = Winter.musicPosition();
        if (pos < 0) return false;
        try {
            loadTracks(res);
        } catch (java.io.IOException e) {
            return false;
        }
        byte[] track = sTracks[Winter.musicTrack() % sTracks.length];
        if (track == null) return false;
        for (int b = 0; b < BANDS && b < out.length; b++) out[b] = level(track, pos, b);
        return true;
    }

    private float level(int ms, int band) { return level(mTrack, ms, band); }

    /** A band's level (0..1) at a position of a track, between its frames. */
    private static float level(byte[] track, int ms, int band) {
        int frames = track.length / BANDS;
        if (frames == 0) return 1f;
        float f = (ms * FPS / 1000f) % frames;
        int i = (int) f, j = (i + 1) % frames;
        float t = f - i;
        int a = track[i * BANDS + band] & 0xFF, b = track[j * BANDS + band] & 0xFF;
        return (a + (b - a) * t) / 255f;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mLast = 0;
        invalidate();
    }
}
