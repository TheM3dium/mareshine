package io.github.them3dium.mareshine;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.util.Random;

/**
 * The winter music's timeline, under its buttons on the launcher: a thin
 * white line that fills as the track plays, with a soft glowing head that
 * now and then lets off a tiny star.
 *
 * <p>It can be used: a touch or a drag jumps to that point (on release), and
 * with the D-pad (focused) left / right go 10 s back / ahead. Focused or held,
 * the head turns gold like the other switches.
 */
public class WinterTimeline extends View {

    private static final int STEP_MS = 10_000;
    private static final int MAX_STARS = 10;

    private final float mDp;
    private final Paint mLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHead = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mStarPath = new Path();
    private final Random mRandom = new Random();
    private final int mGold;

    // stars: position, drift and age (s); age < 0 is a free slot
    private final float[] mSx = new float[MAX_STARS], mSy = new float[MAX_STARS];
    private final float[] mVx = new float[MAX_STARS], mVy = new float[MAX_STARS];
    private final float[] mAge = new float[MAX_STARS], mLife = new float[MAX_STARS], mSize = new float[MAX_STARS];
    private float mNextStar;
    private long mLast;

    private boolean mDragging;
    private float mDragFraction;
    private float mGlowRadius;

    public WinterTimeline(Context c, AttributeSet a) {
        super(c, a);
        mDp = getResources().getDisplayMetrics().density;
        mGold = c.getColor(R.color.dl_gold);
        mLine.setStrokeCap(Paint.Cap.ROUND);
        mLine.setStrokeWidth(2.5f * mDp);
        mStar.setColor(0xFFFFFFFF);
        java.util.Arrays.fill(mAge, -1);
        setFocusable(true);
        setClickable(true);
    }

    private float fraction() {
        if (mDragging) return mDragFraction;
        int len = Winter.musicLength();
        return len > 0 ? Math.min(1f, Winter.musicTime() / (float) len) : 0f;
    }

    private float left() { return getPaddingLeft() + 6 * mDp; }

    private float right() { return getWidth() - getPaddingRight() - 6 * mDp; }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        float dt = mLast == 0 ? 0 : Math.min(0.1f, (now - mLast) / 1000f);
        mLast = now;
        boolean playing = Winter.musicPosition() >= 0;
        float l = left(), r = right(), y = getHeight() / 2f;
        float x = l + (r - l) * fraction();
        boolean lit = isFocused() || mDragging;

        // the line: dim to the end, white up to the head
        mLine.setColor(0x40FFFFFF);
        canvas.drawLine(l, y, r, y, mLine);
        mLine.setColor(lit ? mGold : 0xE6FFFFFF);
        if (x > l) canvas.drawLine(l, y, x, y, mLine);

        // the stars the head lets off while the music plays
        if (playing && !mDragging) {
            mNextStar -= dt;
            if (mNextStar <= 0) {
                spawn(x, y);
                mNextStar = 0.25f + mRandom.nextFloat() * 0.45f;
            }
        }
        boolean stars = false;
        for (int i = 0; i < MAX_STARS; i++) {
            if (mAge[i] < 0) continue;
            mAge[i] += dt;
            if (mAge[i] >= mLife[i]) { mAge[i] = -1; continue; }
            stars = true;
            mSx[i] += mVx[i] * dt;
            mSy[i] += mVy[i] * dt;
            float t = mAge[i] / mLife[i];
            // in quickly, then out; it shrinks a little as it fades
            float a = t < 0.2f ? t / 0.2f : 1 - (t - 0.2f) / 0.8f;
            mStar.setAlpha(Math.round(a * 220));
            drawStar(canvas, mSx[i], mSy[i], mSize[i] * (1 - 0.4f * t));
        }

        // the head: a soft glow and a small dot
        float glow = 9 * mDp;
        if (mGlowRadius != glow) {
            mGlowRadius = glow;
            mGlow.setShader(new RadialGradient(0, 0, glow, 0x99FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        }
        canvas.save();
        canvas.translate(x, y);
        canvas.drawCircle(0, 0, glow, mGlow);
        canvas.restore();
        mHead.setColor(lit ? mGold : 0xFFFFFFFF);
        canvas.drawCircle(x, y, (lit ? 5f : 4f) * mDp, mHead);

        if (isShown() && (playing || stars)) postInvalidateDelayed(33);
        else mLast = 0;
    }

    private void spawn(float x, float y) {
        for (int i = 0; i < MAX_STARS; i++) {
            if (mAge[i] >= 0) continue;
            mSx[i] = x + (mRandom.nextFloat() - 0.5f) * 4 * mDp;
            mSy[i] = y + (mRandom.nextFloat() - 0.5f) * 4 * mDp;
            // they drift back along the line and a little up or down
            mVx[i] = -(4 + mRandom.nextFloat() * 8) * mDp;
            mVy[i] = (mRandom.nextFloat() - 0.5f) * 14 * mDp;
            mSize[i] = (1.6f + mRandom.nextFloat() * 1.6f) * mDp;
            mLife[i] = 0.8f + mRandom.nextFloat() * 0.8f;
            mAge[i] = 0;
            return;
        }
    }

    /** A small four-pointed sparkle. */
    private void drawStar(Canvas c, float x, float y, float s) {
        float w = s * 0.28f;
        mStarPath.reset();
        mStarPath.moveTo(x, y - s);
        mStarPath.quadTo(x + w, y - w, x + s, y);
        mStarPath.quadTo(x + w, y + w, x, y + s);
        mStarPath.quadTo(x - w, y + w, x - s, y);
        mStarPath.quadTo(x - w, y - w, x, y - s);
        c.drawPath(mStarPath, mStar);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return false;
        float l = left(), r = right();
        float f = Math.max(0, Math.min(1, (e.getX() - l) / Math.max(1, r - l)));
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDragging = true;
                mDragFraction = f;
                getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                mDragFraction = f;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                mDragging = false;
                Winter.seekTo(getContext(), Math.round(f * Winter.musicLength()));
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                mDragging = false;
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (isEnabled() && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            int step = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -STEP_MS : STEP_MS;
            Winter.seekTo(getContext(), Winter.musicTime() + step);
            invalidate();
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, android.graphics.Rect previous) {
        super.onFocusChanged(focused, direction, previous);
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mLast = 0;
        invalidate();
    }
}
