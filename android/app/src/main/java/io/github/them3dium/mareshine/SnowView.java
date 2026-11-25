package io.github.them3dium.mareshine;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import java.util.Random;

/**
 * Light snowfall over a menu screen (the winter event, {@link Winter}): a few
 * dozen flakes that drift down and sway a little. It only draws; touches and
 * the D-pad focus go through to the screen below. It runs only while it is on
 * screen, about as costly as one more drawn panel.
 *
 * <p>With a landing set ({@link #setLanding}, the launcher's winter logo) most
 * flakes that reach the picture's top edges, where its snow lies, stay there
 * and melt away after a while (two may lie on each other; one slides down
 * when the one below has melted); on a steep edge they fall on. A swipe over a
 * touch screen is a gust of wind ({@link #touch}): flakes near it are pushed
 * along, and settled ones blown off.
 */
final class SnowView extends View {
    private static final int FLAKES_PER_960DP = 60;
    private static final float LANDING_SHARE = 0.85f;  // of the flakes that reach a top edge
    private static final int MAX_SETTLED = 80;
    private static final float SETTLED_SECONDS_MIN = 10f, SETTLED_SECONDS_MAX = 20f;
    private static final float MELT_SECONDS = 2.5f;  // the last part of that: fading

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random mRandom = new Random();
    private final float mDp;
    // per flake: x, y, radius, fall speed, sway phase, sway speed, alpha; and the wind's push
    private float[] mX, mY, mR, mSpeed, mPhase, mSwaySpeed, mVx, mVy;
    private int[] mAlpha;
    private long mLast;

    // the landing: a view showing a picture, and per picture column the first
    // row from the top that is not transparent (-1: none)
    private View mLand;
    private int[] mTop;
    private int mPicW;
    private final int[] mLoc = new int[2], mOwnLoc = new int[2];

    // settled flakes: x, y, the edge under them (relative to the landing view, in its
    // pixels), fall speed, radius, alpha, age, life
    private final float[] mSx = new float[MAX_SETTLED], mSy = new float[MAX_SETTLED], mSr = new float[MAX_SETTLED];
    private final float[] mSground = new float[MAX_SETTLED], mSvy = new float[MAX_SETTLED];
    private final float[] mSage = new float[MAX_SETTLED], mSlife = new float[MAX_SETTLED];
    private final int[] mSalpha = new int[MAX_SETTLED];
    private int mSettled;

    SnowView(Context c) {
        super(c);
        mDp = c.getResources().getDisplayMetrics().density;
        mPaint.setColor(0xFFFFFFFF);
        mShadow.setColor(0xFF1E4F8C);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Flakes may settle on the top edges of the picture `view` shows (scaled to its width); null: none. */
    void setLanding(View view, Bitmap picture) {
        if (view == mLand) return;
        mLand = view;
        mSettled = 0;
        mTop = picture == null ? null : topEdges(picture);
        mPicW = picture == null ? 0 : picture.getWidth();
    }

    private static int[] topEdges(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int[] top = new int[w];
        for (int x = 0; x < w; x++) {
            top[x] = -1;
            for (int y = 0; y < h; y++) {
                if ((px[y * w + x] >>> 24) > 160) { top[x] = y; break; }
            }
        }
        return top;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        int n = Math.max(20, Math.round(FLAKES_PER_960DP * w / (960f * mDp)));
        mX = new float[n]; mY = new float[n]; mR = new float[n]; mSpeed = new float[n];
        mPhase = new float[n]; mSwaySpeed = new float[n]; mAlpha = new int[n];
        mVx = new float[n]; mVy = new float[n];
        for (int i = 0; i < n; i++) place(i, w, h, true);
    }

    // A flake: anywhere on the screen at the start, later from just above the top.
    private void place(int i, int w, int h, boolean anywhere) {
        float depth = mRandom.nextFloat();  // 0 far: small, slow and faint; 1 near
        mR[i] = (1.2f + 2.6f * depth) * mDp;
        mSpeed[i] = (18f + 42f * depth) * mDp;  // per second
        mAlpha[i] = Math.round(110 + 120 * depth);
        mPhase[i] = mRandom.nextFloat() * 6.2832f;
        mSwaySpeed[i] = 0.4f + mRandom.nextFloat() * 0.8f;
        mX[i] = mRandom.nextFloat() * w;
        mVx[i] = 0;
        mVy[i] = 0;
        mY[i] = anywhere ? mRandom.nextFloat() * h : -mR[i] * 2;
    }

    // --- wind: a swipe over a touch screen pushes the flakes near it -------

    private static final float GUST_RADIUS_DP = 80f;
    private float mGustX, mGustY, mGustVx, mGustVy;  // the last swipe step, in px and px/s
    private boolean mGust;
    private float mTouchX, mTouchY;
    private long mTouchT;

    /** The screen's touches, seen on their way to the views (AppActivity); nothing is taken from them. */
    void touch(android.view.MotionEvent e) {
        getLocationInWindow(mOwnLoc);
        float x = e.getX() - mOwnLoc[0], y = e.getY() - mOwnLoc[1];
        long t = e.getEventTime();
        switch (e.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                mTouchX = x; mTouchY = y; mTouchT = t;
                break;
            case android.view.MotionEvent.ACTION_MOVE: {
                float dtt = Math.max(0.008f, (t - mTouchT) / 1000f);
                mGustVx = (x - mTouchX) / dtt;
                mGustVy = (y - mTouchY) / dtt;
                mGustX = x; mGustY = y;
                mGust = true;
                mTouchX = x; mTouchY = y; mTouchT = t;
                break;
            }
            default:
                break;
        }
    }

    // How much of the last swipe step reaches a point: 1 at the finger, 0 at the radius.
    private float gustAt(float x, float y) {
        float dx = x - mGustX, dy = y - mGustY, r = GUST_RADIUS_DP * mDp;
        float d2 = dx * dx + dy * dy;
        return d2 >= r * r ? 0f : 1f - (float) Math.sqrt(d2) / r;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (mX == null || w == 0) return;
        long now = System.nanoTime();
        float dt = mLast == 0 ? 0 : Math.min(0.1f, (now - mLast) / 1e9f);
        mLast = now;

        // where the landing is now (it can move: the launcher shifts its logo)
        boolean landing = mTop != null && mLand != null && mLand.isShown() && mLand.getWidth() > 0;
        float lx = 0, ly = 0, scale = 1;
        if (landing) {
            mLand.getLocationInWindow(mLoc);
            getLocationInWindow(mOwnLoc);
            lx = mLoc[0] - mOwnLoc[0];
            ly = mLoc[1] - mOwnLoc[1];
            scale = mLand.getWidth() / (float) mPicW;
        }

        boolean gust = mGust;
        mGust = false;
        float maxPush = 600f * mDp, damp = (float) Math.exp(-1.8f * dt);
        for (int i = 0; i < mX.length; i++) {
            if (gust) {
                float g = gustAt(mX[i], mY[i]) * 0.5f;
                mVx[i] = Math.max(-maxPush, Math.min(maxPush, mVx[i] + mGustVx * g));
                mVy[i] = Math.max(-maxPush, Math.min(maxPush, mVy[i] + mGustVy * g));
            }
            mVx[i] *= damp;
            mVy[i] *= damp;
            mPhase[i] += mSwaySpeed[i] * dt;
            float before = mY[i];
            mX[i] += mVx[i] * dt;
            if (mX[i] < -10 * mDp) mX[i] += w + 20 * mDp;
            else if (mX[i] > w + 10 * mDp) mX[i] -= w + 20 * mDp;
            mY[i] += (mSpeed[i] + mVy[i]) * dt;
            float x = mX[i] + (float) Math.sin(mPhase[i]) * 6f * mDp;
            if (landing && settles(i, x, before, lx, ly, scale)) {
                place(i, w, h, false);
                continue;
            }
            if (mY[i] - mR[i] > h || mY[i] < -h * 0.5f) { place(i, w, h, false); continue; }
            mPaint.setAlpha(mAlpha[i]);
            canvas.drawCircle(x, mY[i], mR[i], mPaint);
        }

        if (landing) {
            if (gust) blowOff(lx, ly, w);
            settle(dt);
            drawSettled(canvas, dt, lx, ly);
        }
        postInvalidateOnAnimation();
    }

    // A falling flake that crossed a top edge of the landing this frame, on a
    // part flat enough to hold it: it stays there (most of them). It may land
    // on a flake that lies on the edge itself, not higher: two at most.
    private boolean settles(int i, float x, float before, float lx, float ly, float scale) {
        int col = Math.round((x - lx) / scale);
        if (col < 3 || col >= mTop.length - 3 || mTop[col] < 0) return false;
        float ground = mTop[col] * scale;  // in the landing view
        float edge = ly + ground;
        boolean onFlake = false;
        for (int k = 0; k < mSettled; k++) {
            if (Math.abs(lx + mSx[k] - x) >= (mSr[k] + mR[i]) * 0.7f) continue;
            float top = ly + mSy[k] - mSr[k] * 0.6f;
            if (top < edge) {
                if (mSy[k] < mSground[k] - mSr[k]) return false;  // that one lies on another: no third
                edge = top;
                onFlake = true;
            }
        }
        if (!(before < edge && mY[i] >= edge)) return false;
        int left = mTop[col - 3], right = mTop[col + 3];
        if (!onFlake && (left < 0 || right < 0 || Math.abs(right - left) * scale > 4f * mDp)) return false;  // steep: it slides off
        if (mRandom.nextFloat() >= LANDING_SHARE) return false;
        if (mSettled == MAX_SETTLED) return false;
        int k = mSettled++;
        mSx[k] = x - lx;
        mSground[k] = ground;
        mSy[k] = edge - ly - mR[i] * 0.4f;
        mSvy[k] = 0;
        mSr[k] = mR[i];
        mSalpha[k] = 255;
        mSage[k] = 0;
        mSlife[k] = SETTLED_SECONDS_MIN + mRandom.nextFloat() * (SETTLED_SECONDS_MAX - SETTLED_SECONDS_MIN);
        return true;
    }

    // The current radius of a settled flake: it shrinks while it melts.
    private float radius(int k) {
        float melt = Math.min(1f, (mSlife[k] - mSage[k]) / MELT_SECONDS);
        return mSr[k] * (0.6f + 0.4f * Math.max(0f, melt));
    }

    // A little gravity: each settled flake rests on the edge or on a flake
    // below it; when that one melts away, it slides down after it.
    private void settle(float dt) {
        float g = 900f * mDp;
        for (int k = 0; k < mSettled; k++) {
            float r = radius(k);
            float rest = mSground[k] - r * 0.4f;
            for (int j = 0; j < mSettled; j++) {
                if (j == k || mSy[j] <= mSy[k]) continue;  // only one lower down holds it
                float rj = radius(j);
                if (Math.abs(mSx[j] - mSx[k]) >= (rj + r) * 0.7f) continue;
                rest = Math.min(rest, mSy[j] - (rj + r) * 0.6f);
            }
            if (mSy[k] < rest - 0.1f) {
                mSvy[k] += g * dt;
                mSy[k] = Math.min(rest, mSy[k] + mSvy[k] * dt);
            } else {
                mSy[k] = rest;
                mSvy[k] = 0;
            }
        }
    }

    // A quick swipe near settled flakes blows them off: each goes on as a
    // falling flake (one that was about to start over at the top) with the wind.
    private void blowOff(float lx, float ly, int w) {
        float strong = 250f * mDp;
        if (mGustVx * mGustVx + mGustVy * mGustVy < strong * strong) return;
        for (int k = 0; k < mSettled; ) {
            float x = lx + mSx[k], y = ly + mSy[k];
            float g = gustAt(x, y);
            if (g <= 0f) { k++; continue; }
            int i = highestFalling();
            mX[i] = x; mY[i] = y; mR[i] = mSr[k];
            mVx[i] = mGustVx * g * 0.6f;
            mVy[i] = mGustVy * g * 0.6f - 40f * mDp;
            removeSettled(k);
        }
    }

    private int highestFalling() {
        int best = 0;
        for (int i = 1; i < mY.length; i++) if (mY[i] < mY[best]) best = i;
        return best;
    }

    private void removeSettled(int k) {
        int last = --mSettled;
        mSx[k] = mSx[last]; mSy[k] = mSy[last]; mSr[k] = mSr[last]; mSground[k] = mSground[last];
        mSvy[k] = mSvy[last]; mSalpha[k] = mSalpha[last]; mSage[k] = mSage[last]; mSlife[k] = mSlife[last];
    }

    // The settled flakes: they melt (fade and shrink a little) at the end of their life.
    private void drawSettled(Canvas canvas, float dt, float lx, float ly) {
        for (int k = 0; k < mSettled; ) {
            mSage[k] += dt;
            float left = mSlife[k] - mSage[k];
            if (left <= 0) { removeSettled(k); continue; }
            float melt = Math.min(1f, left / MELT_SECONDS);
            float r = radius(k);
            // a faint blue shadow under it sets it off from the logo's own snow
            mShadow.setAlpha(Math.round(90 * melt));
            canvas.drawCircle(lx + mSx[k], ly + mSy[k] + 0.7f * mDp, r * 1.15f, mShadow);
            mPaint.setAlpha(Math.round(mSalpha[k] * melt));
            canvas.drawCircle(lx + mSx[k], ly + mSy[k], r, mPaint);
            k++;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mLast = 0;
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        mLast = 0;  // no jump after a pause
        if (visibility == VISIBLE) postInvalidateOnAnimation();
    }
}
