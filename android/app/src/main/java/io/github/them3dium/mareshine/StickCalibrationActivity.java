package io.github.them3dium.mareshine;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;
import java.util.Map;

import static io.github.them3dium.mareshine.L.t;

// Stick calibration (beta): measures the resting centre and the reach of both
// sticks of a controller and saves them as SMS_CAL_LEFT / SMS_CAL_RIGHT
// ("cx,cy,minx,maxx,miny,maxy", raw -1..1); platform/pad corrects the sticks
// with them. The left stick is AXIS_X/Y; the right one AXIS_Z/RZ, or RX/RY on
// controllers without Z (the same axes SDL makes the right stick).
public class StickCalibrationActivity extends AppActivity {
    private static final int TEXT = 0xFFF4F8FF, DIM = 0xFF8FB4D4, SUN = 0xFFFFC531, SEA = 0xFF2FB8C9;

    private enum Phase { IDLE, CENTRE, RANGE, DONE }

    private Phase mPhase = Phase.IDLE;
    private final float[] mRaw = new float[4];          // lx, ly, rx, ry now
    private final double[] mSum = new double[4];
    private int mSamples;
    private final float[] mMin = new float[4], mMax = new float[4];
    private final float[] mCentre = new float[4];
    private boolean mSeenPad;

    private StickView mLeft, mRight;
    private TextView tvInfo, tvStatus;
    private Button btnStart;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        L.init(this);
        buildUi();
        showIdle();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private Typeface condensed(boolean bold) {
        return Typeface.create("sans-serif-condensed", bold ? Typeface.BOLD : Typeface.NORMAL);
    }

    private Button button(String text, boolean main) {
        Button b = new Button(this);
        b.setText(t(text));
        b.setAllCaps(true);
        b.setTypeface(condensed(true));
        b.setTextSize(16);
        b.setStateListAnimator(null);
        b.setBackgroundResource(R.drawable.dl_row);
        if (main) {
            b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFFB020));
            b.setTextColor(0xFF0A1B30);
        } else {
            b.setTextColor(TEXT);
        }
        b.setPadding(dp(20), dp(10), dp(20), dp(10));
        return b;
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0A1B30);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.dl_panel);
        panel.setPadding(dp(28), dp(20), dp(28), dp(20));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        plp.setMargins(dp(24), dp(20), dp(24), dp(20));
        root.addView(panel, plp);

        TextView title = new TextView(this);
        title.setText(t("CALIBRATE STICKS  (BETA)"));
        title.setTextColor(SUN);
        title.setTypeface(condensed(true));
        title.setTextSize(24);
        panel.addView(title);

        tvInfo = new TextView(this);
        tvInfo.setTextColor(TEXT);
        tvInfo.setTypeface(condensed(false));
        tvInfo.setTextSize(17);
        tvInfo.setPadding(0, dp(8), 0, dp(8));
        panel.addView(tvInfo);

        LinearLayout sticks = new LinearLayout(this);
        sticks.setOrientation(LinearLayout.HORIZONTAL);
        sticks.setGravity(Gravity.CENTER);
        mLeft = new StickView(this, "LEFT STICK (move)");
        mRight = new StickView(this, "RIGHT STICK (camera)");
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        sticks.addView(mLeft, slp);
        sticks.addView(mRight, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        panel.addView(sticks, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        tvStatus = new TextView(this);
        tvStatus.setTextColor(DIM);
        tvStatus.setTypeface(condensed(false));
        tvStatus.setTextSize(14);
        tvStatus.setGravity(Gravity.CENTER);
        panel.addView(tvStatus);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        buttons.setPadding(0, dp(10), 0, 0);
        btnStart = button("Start", true);
        Button reset = button("Reset calibration", false);
        Button close = button("Close", false);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(dp(8), 0, dp(8), 0);
        buttons.addView(btnStart, blp);
        buttons.addView(reset, new LinearLayout.LayoutParams(blp));
        buttons.addView(close, new LinearLayout.LayoutParams(blp));
        panel.addView(buttons);

        btnStart.setOnClickListener(v -> next());
        reset.setOnClickListener(v -> {
            Map<String, String> m = Settings.read(this);
            m.remove("SMS_CAL_LEFT");
            m.remove("SMS_CAL_RIGHT");
            Settings.write(this, m);
            mPhase = Phase.IDLE;
            showIdle();
            tvStatus.setText(t("Calibration removed. The sticks are used as the controller reports them."));
        });
        close.setOnClickListener(v -> finish());
        setContentView(root);
        btnStart.requestFocus();
    }

    // --- steps --------------------------------------------------------------

    private void showIdle() {
        boolean saved = Settings.read(this).containsKey("SMS_CAL_LEFT");
        tvInfo.setText(t("Move both sticks to check that they react below. Then press START (or A) and follow the steps:\n"
            + "1. Let go of both sticks.   2. Rotate both sticks slowly along the edge a few times.   3. Press A or DONE.")
            + (saved ? "\n\n" + t("This controller is calibrated already; calibrating again replaces it.") : ""));
        btnStart.setText(t("Start"));
    }

    private void next() {
        switch (mPhase) {
            case IDLE:
            case DONE:
                startCentre();
                break;
            case CENTRE:
                break; // counting down
            case RANGE:
                finishRange();
                break;
        }
    }

    private void startCentre() {
        if (!mSeenPad) {
            tvStatus.setText(t("No stick input yet: connect a controller and move a stick first."));
            return;
        }
        mPhase = Phase.CENTRE;
        mSamples = 0;
        java.util.Arrays.fill(mSum, 0);
        btnStart.setText(t("Wait…"));
        mHandler.post(mSampler);
        countdown(3);
    }

    // Resting sticks may send no events at all: sample the last position on a
    // timer for the centre.
    private final Runnable mSampler = new Runnable() {
        @Override public void run() {
            if (mPhase != Phase.CENTRE) return;
            for (int i = 0; i < 4; i++) mSum[i] += mRaw[i];
            mSamples++;
            mHandler.postDelayed(this, 30);
        }
    };

    private void countdown(int n) {
        if (mPhase != Phase.CENTRE) return;
        if (n > 0) {
            tvInfo.setText(t("Step 1 of 2: let go of both sticks and do not touch them… %d", n));
            mHandler.postDelayed(() -> countdown(n - 1), 1000);
            return;
        }
        if (mSamples == 0) {
            tvStatus.setText(t("No stick data arrived. Move a stick and try again."));
            mPhase = Phase.IDLE;
            showIdle();
            return;
        }
        for (int i = 0; i < 4; i++) {
            mCentre[i] = (float) (mSum[i] / mSamples);
            mMin[i] = mCentre[i];
            mMax[i] = mCentre[i];
        }
        mPhase = Phase.RANGE;
        tvInfo.setText(t("Step 2 of 2: rotate BOTH sticks slowly along the edge, all the way round, 3 times. "
            + "Then press A or DONE."));
        btnStart.setText(t("Done"));
        btnStart.requestFocus();
    }

    private void finishRange() {
        boolean okL = mMax[0] - mMin[0] > 0.6f && mMax[1] - mMin[1] > 0.6f;
        boolean okR = mMax[2] - mMin[2] > 0.6f && mMax[3] - mMin[3] > 0.6f;
        if (!okL && !okR) {
            tvStatus.setText(t("The sticks did not move far enough. Rotate them along the edge and press DONE again."));
            return;
        }
        Map<String, String> m = Settings.read(this);
        if (okL) m.put("SMS_CAL_LEFT", fmt(0)); else m.remove("SMS_CAL_LEFT");
        if (okR) m.put("SMS_CAL_RIGHT", fmt(2)); else m.remove("SMS_CAL_RIGHT");
        Settings.write(this, m);
        mPhase = Phase.DONE;
        tvInfo.setText(t("Saved. The yellow dot shows the stick as the game will see it. "
            + "Restart the game for it to take effect (pause menu → Restart game)."));
        tvStatus.setText(t(okL ? "Left stick calibrated." : "Left stick skipped (not moved).") + " "
            + t(okR ? "Right stick calibrated." : "Right stick skipped (not moved)."));
        btnStart.setText(t("Again"));
    }

    private String fmt(int a) {
        return String.format(Locale.ROOT, "%.4f,%.4f,%.4f,%.4f,%.4f,%.4f",
            mCentre[a], mCentre[a + 1], mMin[a], mMax[a], mMin[a + 1], mMax[a + 1]);
    }

    // --- input --------------------------------------------------------------

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if ((ev.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK
                || ev.getAction() != MotionEvent.ACTION_MOVE) {
            return super.dispatchGenericMotionEvent(ev);
        }
        InputDevice d = ev.getDevice();
        boolean hasZ = d != null && d.getMotionRange(MotionEvent.AXIS_Z) != null
            && d.getMotionRange(MotionEvent.AXIS_RZ) != null;
        mRaw[0] = ev.getAxisValue(MotionEvent.AXIS_X);
        mRaw[1] = ev.getAxisValue(MotionEvent.AXIS_Y);
        mRaw[2] = ev.getAxisValue(hasZ ? MotionEvent.AXIS_Z : MotionEvent.AXIS_RX);
        mRaw[3] = ev.getAxisValue(hasZ ? MotionEvent.AXIS_RZ : MotionEvent.AXIS_RY);
        mSeenPad = true;
        if (mPhase == Phase.RANGE) {
            for (int i = 0; i < 4; i++) {
                mMin[i] = Math.min(mMin[i], mRaw[i]);
                mMax[i] = Math.max(mMax[i], mRaw[i]);
            }
        }
        mLeft.update(0);
        mRight.update(2);
        tvStatus.setText(String.format(Locale.ROOT, "left %+.2f %+.2f    right %+.2f %+.2f",
            mRaw[0], mRaw[1], mRaw[2], mRaw[3]));
        return true; // the sticks must not move the focus around
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0
                && (ev.getKeyCode() == KeyEvent.KEYCODE_BUTTON_A || ev.getKeyCode() == KeyEvent.KEYCODE_BUTTON_START)) {
            next();
            return true;
        }
        return super.dispatchKeyEvent(ev);
    }

    // --- a stick picture ----------------------------------------------------

    private class StickView extends View {
        private final String label;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int base;

        StickView(Context c, String label) {
            super(c);
            this.label = t(label);
        }

        void update(int base) {
            this.base = base;
            invalidate();
        }

        private float cal(float v, int i) {
            if (mPhase != Phase.DONE && mPhase != Phase.RANGE) return v;
            float c = mCentre[i], mn = mMin[i], mx = mMax[i];
            float r = v >= c ? (v - c) / Math.max(mx - c, 0.05f) : (v - c) / Math.max(c - mn, 0.05f);
            return Math.max(-1, Math.min(1, r));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float r = Math.min(w, h - dp(40)) * 0.42f, cx = w / 2, cy = (h - dp(30)) / 2 + dp(4);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x6612385E);
            canvas.drawCircle(cx, cy, r, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            p.setColor(SEA);
            canvas.drawCircle(cx, cy, r, p);
            p.setStrokeWidth(dp(1));
            p.setColor(0x552FB8C9);
            canvas.drawLine(cx - r, cy, cx + r, cy, p);
            canvas.drawLine(cx, cy - r, cx, cy + r, p);
            // the measured reach
            if (mPhase == Phase.RANGE || mPhase == Phase.DONE) {
                p.setColor(0xAAFFB020);
                canvas.drawRect(cx + mMin[base] * r, cy + mMin[base + 1] * r, cx + mMax[base] * r, cy + mMax[base + 1] * r, p);
            }
            // raw position (white) and corrected (sun yellow)
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xCCFFFFFF);
            canvas.drawCircle(cx + mRaw[base] * r, cy + mRaw[base + 1] * r, dp(7), p);
            if (mPhase == Phase.DONE || mPhase == Phase.RANGE) {
                p.setColor(SUN);
                canvas.drawCircle(cx + cal(mRaw[base], base) * r, cy + cal(mRaw[base + 1], base + 1) * r, dp(9), p);
            }
            p.setColor(TEXT);
            p.setTextSize(dp(16));
            p.setTypeface(condensed(true));
            p.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(label, cx, h - dp(8), p);
        }
    }
}
