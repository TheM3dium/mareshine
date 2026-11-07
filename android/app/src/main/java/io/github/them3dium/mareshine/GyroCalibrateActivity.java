package io.github.them3dium.mareshine;

import static io.github.them3dium.mareshine.L.t;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Calibrating the gyroscope, step by step: lay the phone flat, level it if it
// wobbles, hands off, a countdown, then the reading at rest. The result is
// SMS_GYRO_OFFSET in settings.txt (x,y,z in rad/s; platform/pad/pad.cpp takes it
// off every reading).
public class GyroCalibrateActivity extends AppActivity {
    private static final int STEP_FLAT = 0, STEP_LEVEL = 1, STEP_HANDS_OFF = 2, STEP_READY = 3,
        STEP_COUNTDOWN = 4, STEP_MEASURE = 5, STEP_DONE = 6, STEP_MOVED = 7;
    private static final String KEY = "SMS_GYRO_OFFSET";
    private static final int COUNTDOWN_SECONDS = 5, MEASURE_MS = 3000;
    private static final float MAX_GOOD_TILT = 15f;  // degrees from lying flat; only shown, it blocks nothing
    private static final float MAX_SPREAD = 0.05f;  // rad/s: more than this while it lies still means it moved
    private static final int BG = 0xFF071829, TEXT = 0xFFF4F8FF, MAIN = 0xFFFFA726, GOLD = 0xFFFFC531,
        TEAL = 0xFF2DB7C9, TABLE = 0xFF3A5A7A, BODY = 0xFFD6E0EA, DARK = 0xFF16324D, RED = 0xFFE5534B,
        GREEN = 0xFF3FBF7F, SHIM = 0xFFFFD54F, SKIN = 0xFFF2C9A1;

    private int mStep;
    private long mStepStart;
    private TextView mTitle, mText;
    private LinearLayout mButtons;
    private Button mMain;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private SensorManager mSensors;
    private SensorEventListener mListener;
    private boolean mMeasuring;
    private final float[] mSum = new float[3], mMin = new float[3], mMax = new float[3];
    private int mCount;
    // what the screen shows while it runs: the gyroscope now, how far it lately swung, the tilt
    private static final int RECENT = 30;  // about half a second
    private final float[][] mRecent = new float[RECENT][3];
    private int mRecentAt, mRecentCount;
    private final float[] mGyroNow = new float[3];
    private float mTilt = Float.NaN;
    private boolean mHaveGyro;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        L.init(this);
        mSensors = (SensorManager) getSystemService(SENSOR_SERVICE);
        buildUi();
        show(STEP_FLAT);
    }

    @Override
    protected void onResume() {
        super.onResume();
        startSensors();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // away from the screen in the middle of it: this reading counts for nothing
        if (mStep == STEP_COUNTDOWN || mStep == STEP_MEASURE) {
            stopMeasuring();
            show(STEP_READY);
        }
        stopSensors();
    }

    @Override
    protected void onDestroy() {
        stopMeasuring();
        stopSensors();
        super.onDestroy();
    }

    @Override protected boolean usesPadNav() { return true; }

    @Override
    protected List<View> padItems() {
        List<View> items = new ArrayList<>();
        for (int i = 0; i < mButtons.getChildCount(); i++) items.add(mButtons.getChildAt(i));
        return items;
    }

    @Override protected View padDefault() { return mMain; }

    @Override
    public void onBackPressed() {
        if (mStep > STEP_FLAT && mStep <= STEP_READY) show(mStep - 1);
        else if (mStep == STEP_COUNTDOWN || mStep == STEP_MEASURE) { stopMeasuring(); show(STEP_READY); }
        else finish();
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    // --- the steps ------------------------------------------------------------

    private void show(int step) {
        mStep = step;
        mStepStart = SystemClock.uptimeMillis();
        mButtons.removeAllViews();
        mMain = null;
        switch (step) {
            case STEP_FLAT:
                text(t("Lay your phone flat"), t("Put your phone on a surface that is as flat as you can find, such as a table."));
                addButton(t("Cancel"), false, this::finish);
                if (Settings.read(this).containsKey(KEY)) addButton(t("Reset"), false, this::reset);
                addButton(t("Next"), true, () -> show(STEP_LEVEL));
                break;
            case STEP_LEVEL:
                text(t("Does it wobble?"), t("If a camera or a handheld's grip keeps it from lying flat, rest it on something steady under its middle, such as a cup, until it stops wobbling."));
                addButton(t("Back"), false, () -> show(STEP_FLAT));
                addButton(t("Next"), true, () -> show(STEP_HANDS_OFF));
                break;
            case STEP_HANDS_OFF:
                text(t("Hands off"), t("Don't touch your phone while it calibrates, and unplug any cable so that nothing can move it."));
                addButton(t("Back"), false, () -> show(STEP_LEVEL));
                addButton(t("Next"), true, () -> show(STEP_READY));
                break;
            case STEP_READY:
                text(t("Ready?"), t("After you press the button you have 5 seconds to let go of your phone."));
                addButton(t("Back"), false, () -> show(STEP_HANDS_OFF));
                addButton(t("Calibrate now"), true, () -> show(STEP_COUNTDOWN));
                break;
            case STEP_COUNTDOWN:
                text(t("Let go of your phone"), t("Calibration starts in a moment."));
                addButton(t("Cancel"), false, () -> show(STEP_READY));
                mHandler.postDelayed(this::startMeasuring, COUNTDOWN_SECONDS * 1000L);
                break;
            case STEP_MEASURE:
                text(t("Please wait"), t("Calibrating. Don't move your phone."));
                break;
            case STEP_DONE:
                text(t("Gyro calibrated."), t("You can pick up your phone again."));
                addButton(t("Done"), true, this::finish);
                break;
            default:
                text(t("Your phone moved"), t("Lay it down, make sure it doesn't wobble, and try again."));
                addButton(t("Close"), false, this::finish);
                addButton(t("Try again"), true, () -> show(STEP_READY));
                break;
        }
        if (step != STEP_COUNTDOWN) mHandler.removeCallbacksAndMessages(null);
        if (mMain != null) mMain.requestFocus();
    }

    private void text(String title, String text) {
        mTitle.setText(title);
        mText.setText(text);
    }

    private void reset() {
        Map<String, String> m = Settings.read(this);
        m.remove(KEY);
        Settings.write(this, m);
        finish();
    }

    private void startSensors() {
        if (mSensors == null || mListener != null) return;
        Sensor gyro = mSensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        Sensor accel = mSensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mListener = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent e) {
                if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                    float[] v = e.values;
                    float len = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
                    // 0 degrees: lying flat, screen or back down
                    if (len > 0.1f) mTilt = (float) Math.toDegrees(Math.acos(Math.min(1f, Math.abs(v[2]) / len)));
                    return;
                }
                mHaveGyro = true;
                for (int i = 0; i < 3; i++) {
                    mGyroNow[i] = e.values[i];
                    mRecent[mRecentAt][i] = e.values[i];
                    if (mMeasuring) {
                        mSum[i] += e.values[i];
                        mMin[i] = Math.min(mMin[i], e.values[i]);
                        mMax[i] = Math.max(mMax[i], e.values[i]);
                    }
                }
                if (mMeasuring) mCount++;
                mRecentAt = (mRecentAt + 1) % RECENT;
                mRecentCount = Math.min(mRecentCount + 1, RECENT);
            }
            @Override public void onAccuracyChanged(Sensor s, int a) {}
        };
        if (gyro != null) mSensors.registerListener(mListener, gyro, SensorManager.SENSOR_DELAY_GAME);
        if (accel != null) mSensors.registerListener(mListener, accel, SensorManager.SENSOR_DELAY_GAME);
    }

    private void stopSensors() {
        if (mListener != null && mSensors != null) mSensors.unregisterListener(mListener);
        mListener = null;
    }

    // lately, did any axis swing more than a phone lying still does?
    private boolean moving() {
        for (int a = 0; a < 3; a++) {
            float min = 9, max = -9;
            for (int i = 0; i < mRecentCount; i++) {
                min = Math.min(min, mRecent[i][a]);
                max = Math.max(max, mRecent[i][a]);
            }
            if (mRecentCount > 0 && max - min >= MAX_SPREAD) return true;
        }
        return false;
    }

    private void startMeasuring() {
        if (mSensors == null || mSensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE) == null) { finish(); return; }
        for (int i = 0; i < 3; i++) { mSum[i] = 0; mMin[i] = 9; mMax[i] = -9; }
        mCount = 0;
        mMeasuring = true;
        show(STEP_MEASURE);
        mHandler.postDelayed(this::finishMeasuring, MEASURE_MS);
    }

    private void stopMeasuring() {
        mHandler.removeCallbacksAndMessages(null);
        mMeasuring = false;
    }

    private void finishMeasuring() {
        stopMeasuring();
        boolean still = mCount >= 10;
        for (int i = 0; i < 3; i++) still &= mMax[i] - mMin[i] < MAX_SPREAD;
        if (!still) { show(STEP_MOVED); return; }
        Settings.put(this, KEY, String.format(Locale.ROOT, "%.5f,%.5f,%.5f",
            mSum[0] / mCount, mSum[1] / mCount, mSum[2] / mCount));
        show(STEP_DONE);
    }

    // --- the screen -----------------------------------------------------------

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.addView(new PictureView(), new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.dl_panel);
        int pad = Math.round(dp(16));
        panel.setPadding(pad, Math.round(dp(10)), pad, Math.round(dp(12)));
        mTitle = new TextView(this);
        mTitle.setTextColor(GOLD);
        mTitle.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        mTitle.setTextSize(22);
        panel.addView(mTitle);
        mText = new TextView(this);
        mText.setTextColor(TEXT);
        mText.setTextSize(16);
        mText.setPadding(0, Math.round(dp(4)), 0, 0);
        panel.addView(mText);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
            Math.round(getResources().getDisplayMetrics().widthPixels * 0.62f),
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        plp.topMargin = Math.round(dp(18));
        root.addView(panel, plp);

        mButtons = new LinearLayout(this);
        mButtons.setOrientation(LinearLayout.HORIZONTAL);
        mButtons.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        blp.bottomMargin = Math.round(dp(14));
        root.addView(mButtons, blp);
        setContentView(root);
    }

    private void addButton(String text, boolean main, Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(true);
        b.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        b.setTextSize(15);
        b.setStateListAnimator(null);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(Math.round(dp(18)), Math.round(dp(8)), Math.round(dp(18)), Math.round(dp(8)));
        if (main) {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(10));
            bg.setColor(MAIN);
            b.setBackground(bg);
            b.setOnFocusChangeListener((v, has) -> bg.setStroke(has ? Math.round(dp(2)) : 0, 0xFFFFF3C4));
            b.setTextColor(0xFF0A1B30);
            mMain = b;
        } else {
            b.setBackgroundResource(R.drawable.dl_row);
            b.setTextColor(TEXT);
        }
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(Math.round(dp(6)));
        lp.setMarginEnd(Math.round(dp(6)));
        mButtons.addView(b, lp);
    }

    // --- the pictures: a phone seen from its side, lying on a table -----------

    private class PictureView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Path path = new Path();

        PictureView() { super(GyroCalibrateActivity.this); }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            long ms = SystemClock.uptimeMillis() - mStepStart;
            float table = h * 0.79f;
            switch (mStep) {
                case STEP_FLAT: flat(c, w, table, ms); break;
                case STEP_LEVEL: level(c, w, table, ms); break;
                case STEP_HANDS_OFF: handsOff(c, w, table, ms); break;
                case STEP_READY: ready(c, w, table, ms); break;
                case STEP_COUNTDOWN: countdown(c, w, h, ms); break;
                case STEP_MEASURE: measuring(c, w, table, ms); break;
                case STEP_DONE: result(c, w, h, true); break;
                default: result(c, w, h, false); break;
            }
            if (mStep != STEP_DONE && mStep != STEP_MOVED) readout(c, w, h);
            postInvalidateOnAnimation();
        }

        // the sensor as it reads now, so that a wobble shows
        private void readout(Canvas c, float w, float h) {
            if (!mHaveGyro) return;
            float y = h * 0.31f;
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(dp(13));
            p.setColor(0xFFB9C8D8);
            p.setTypeface(Typeface.MONOSPACE);
            c.drawText(String.format(Locale.ROOT, "X %+.3f   Y %+.3f   Z %+.3f", mGyroNow[0], mGyroNow[1], mGyroNow[2]), w / 2, y, p);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextSize(dp(15));
            boolean moves = moving();
            p.setColor(moves ? RED : GREEN);
            c.drawText(moves ? t("Moving") : t("Still"), w / 2, y + dp(20), p);
            if (!Float.isNaN(mTilt)) {
                boolean good = mTilt <= MAX_GOOD_TILT;
                p.setColor(good ? GREEN : RED);
                c.drawText(String.format(Locale.ROOT, "%s: %s (%.0f°)", t("Angle"), good ? t("Good") : t("Bad"), mTilt),
                    w / 2, y + dp(39), p);
                p.setTypeface(Typeface.DEFAULT);
                p.setTextSize(dp(12));
                p.setColor(MAIN);
                c.drawText(t("(Take this info with a pinch of salt: if your phone really lies flat, you're good to go.)"), w / 2, y + dp(56), p);
            }
            p.setTypeface(Typeface.DEFAULT);
        }

        private void table(Canvas c, float x0, float x1, float y) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(TABLE);
            r.set(x0, y, x1, y + dp(6));
            c.drawRoundRect(r, dp(3), dp(3), p);
        }

        // centred on cx, its underside at bottom, tilted about its middle; the
        // screen faces up. bump: how far the camera at its left end sticks out.
        private void phone(Canvas c, float cx, float bottom, float len, float tall, float bump, float deg) {
            c.save();
            c.rotate(deg, cx, bottom);
            p.setStyle(Paint.Style.FILL);
            if (bump > 0) {
                p.setColor(BODY);
                r.set(cx - len * 0.44f, bottom - dp(3), cx - len * 0.44f + len * 0.24f, bottom + bump);
                c.drawRoundRect(r, dp(3), dp(3), p);
            }
            p.setColor(BODY);
            r.set(cx - len / 2, bottom - tall, cx + len / 2, bottom);
            c.drawRoundRect(r, dp(4), dp(4), p);
            p.setColor(TEAL);
            r.set(cx - len / 2 + dp(4), bottom - tall, cx + len / 2 - dp(4), bottom - tall + dp(3));
            c.drawRoundRect(r, dp(1.5f), dp(1.5f), p);
            c.restore();
        }

        private void flat(Canvas c, float w, float table, long ms) {
            table(c, w * 0.22f, w * 0.78f, table);
            float t = Math.min((ms % 3200) / 1400f, 1f);
            float ease = 1 - (1 - t) * (1 - t);
            phone(c, w / 2, table - dp(90) * (1 - ease), dp(220), dp(14), 0, -8 * (1 - ease));
        }

        private float eased(float loop, float from, float to) {
            float t = Math.max(0f, Math.min((loop - from) / (to - from), 1f));
            return t * t * (3 - 2 * t);
        }

        // wobbling on its camera; then it rises, a cup comes under its middle and it
        // settles on the cup, in that order so that nothing passes through it
        private void wobbling(Canvas c, float cx, float table, float halfTable, float len, float tall, float bump, long ms) {
            table(c, cx - halfTable, cx + halfTable, table);
            float loop = ms % 7000;
            float rise = eased(loop, 2600, 3200);
            float slide = eased(loop, 3200, 4000);
            float settle = eased(loop, 4000, 4400);
            float cupW = dp(62), cupH = dp(44), clearance = dp(12);
            if (slide > 0) {
                float cupX = cx + dp(200) * (1 - slide);
                int alpha = Math.round(255 * Math.min(slide * 4f, 1f));
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(6));
                p.setColor(0xFFE0B23A);
                p.setAlpha(alpha);
                r.set(cupX + cupW / 2 - dp(9), table - cupH * 0.8f, cupX + cupW / 2 + dp(13), table - cupH * 0.26f);
                c.drawOval(r, p);
                p.setStyle(Paint.Style.FILL);
                p.setColor(SHIM);
                p.setAlpha(alpha);
                r.set(cupX - cupW / 2, table - cupH, cupX + cupW / 2, table);
                c.drawRoundRect(r, dp(6), dp(6), p);
                p.setAlpha(255);
            }
            float wobble = (float) Math.sin(loop / 180.0);
            float tilt = (float) Math.toDegrees(Math.atan2(bump, len * 0.56f));
            float deg = tilt * (0.55f + 0.45f * wobble) * (1 - rise);
            // from resting on its camera, up above the cup, then down onto it
            float up = table - bump + (bump - cupH - clearance) * rise;
            float bottom = up + clearance * settle;
            phone(c, cx, bottom, len, tall, bump, deg);
        }

        private void level(Canvas c, float w, float table, long ms) {
            wobbling(c, w / 2, table, w * 0.28f, dp(220), dp(14), dp(11), ms);
        }

        private void noEntry(Canvas c, float cx, float cy, float rad) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(4));
            p.setColor(RED);
            c.drawCircle(cx, cy, rad, p);
            float d = rad * 0.7071f;
            c.drawLine(cx - d, cy - d, cx + d, cy + d, p);
            p.setStyle(Paint.Style.FILL);
        }

        private void handsOff(Canvas c, float w, float table, long ms) {
            table(c, w * 0.15f, w * 0.85f, table);
            float cx = w * 0.45f, len = dp(220), tall = dp(14);
            // the USB-C cable, along the table: its silver end starts hidden inside the
            // phone (drawn over it) and comes out as the cable is pulled
            float endX = cx + len / 2, plugY = table - tall / 2;
            float pull = (ms % 3000) / 3000f;
            pull = pull < 0.15f ? 0f : Math.min((pull - 0.15f) / 0.45f, 1f);
            float metalX = endX - dp(15) + dp(78) * pull * (2 - pull);
            float bodyX = metalX + dp(14);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(5));
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(0xFF8497AA);
            path.reset();
            path.moveTo(bodyX + dp(26), plugY);
            path.cubicTo(bodyX + dp(52), plugY, bodyX + dp(52), table - dp(2), bodyX + dp(88), table - dp(2));
            path.lineTo(w * 0.84f, table - dp(2));
            c.drawPath(path, p);
            p.setStrokeCap(Paint.Cap.BUTT);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFC4CDD6);                                     // the silver connector
            r.set(metalX, plugY - dp(3), metalX + dp(15), plugY + dp(3));
            c.drawRoundRect(r, dp(1.5f), dp(1.5f), p);
            p.setColor(0xFF7E8A96);                                     // its opening
            r.set(metalX + dp(1.5f), plugY - dp(0.75f), metalX + dp(9), plugY + dp(0.75f));
            c.drawRect(r, p);
            p.setColor(0xFF2B3E52);                                     // the plug's grip
            r.set(bodyX, plugY - dp(7), bodyX + dp(28), plugY + dp(7));
            c.drawRoundRect(r, dp(4), dp(4), p);
            phone(c, cx, table, len, tall, 0, 0);
            // the phone's socket, once the plug has left it
            if (metalX > endX) {
                p.setColor(DARK);
                r.set(endX - dp(3), plugY - dp(3), endX, plugY + dp(3));
                c.drawRect(r, p);
            }
            // a hand comes down to poke it with its index finger, and is told not to
            float loop = (ms % 3000) / 3000f;
            float down = loop < 0.4f ? loop / 0.4f : loop < 0.8f ? 1f : 1f - (loop - 0.8f) / 0.2f;
            float fx = cx - dp(40), tip = table - tall - dp(4) - dp(26) * (1 - down);
            pointingHand(c, fx, tip);
            noEntry(c, fx + dp(11), tip - dp(50), dp(62));
        }

        // A hand drawn as an outline, its index finger down with the tip at (x, tip).
        // Worked out in a box about 987 wide and 1280 tall, finger up, then flipped.
        private void pointingHand(Canvas c, float x, float tip) {
            final float scale = dp(105) / 1280f;
            c.save();
            c.translate(x, tip);
            c.scale(scale, -scale);
            c.translate(-352, -11);
            path.reset();
            path.moveTo(366, 1268);
            path.lineTo(366, 1185);
            path.cubicTo(366, 1145, 345, 1100, 310, 1075);
            path.cubicTo(205, 1003, 143, 890, 143, 770);
            path.lineTo(143, 737);
            path.cubicTo(143, 695, 115, 658, 72, 645);
            path.cubicTo(35, 633, 12, 603, 12, 570);
            path.cubicTo(12, 525, 50, 490, 100, 485);
            path.cubicTo(170, 480, 235, 515, 263, 570);
            path.lineTo(263, 92);
            path.cubicTo(263, 45, 303, 11, 352, 11);
            path.cubicTo(401, 11, 441, 45, 441, 92);
            path.lineTo(441, 345);
            path.cubicTo(441, 298, 481, 264, 530, 264);
            path.cubicTo(579, 264, 620, 298, 620, 345);
            path.lineTo(620, 375);
            path.cubicTo(620, 328, 660, 295, 708, 295);
            path.cubicTo(757, 295, 797, 328, 797, 375);
            path.lineTo(797, 445);
            path.cubicTo(797, 398, 837, 364, 886, 364);
            path.cubicTo(935, 364, 975, 398, 975, 445);
            path.lineTo(975, 825);
            path.cubicTo(975, 895, 952, 960, 910, 1015);
            path.cubicTo(872, 1062, 851, 1120, 851, 1185);
            path.lineTo(851, 1268);
            p.setStyle(Paint.Style.FILL);
            p.setColor(SKIN);
            c.drawPath(path, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(24);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(0xFF7A5C45);
            c.drawPath(path, p);
            c.drawLine(441, 345, 441, 600, p);
            c.drawLine(620, 375, 620, 600, p);
            c.drawLine(797, 445, 797, 600, p);
            path.reset();
            path.moveTo(263, 570);
            path.lineTo(263, 675);
            path.cubicTo(263, 730, 233, 770, 227, 835);
            c.drawPath(path, p);
            p.setStrokeCap(Paint.Cap.BUTT);
            p.setStrokeJoin(Paint.Join.MITER);
            p.setStyle(Paint.Style.FILL);
            c.restore();
        }

        private void ready(Canvas c, float w, float table, long ms) {
            table(c, w * 0.22f, w * 0.78f, table);
            phone(c, w / 2, table, dp(220), dp(14), 0, 0);
            float pulse = (ms % 1500) / 1500f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(3));
            p.setColor(Color.argb(Math.round(200 * (1 - pulse)), 0x2D, 0xB7, 0xC9));
            c.drawCircle(w / 2, table - dp(7), dp(30) + dp(60) * pulse, p);
            p.setStyle(Paint.Style.FILL);
        }

        private void countdown(Canvas c, float w, float h, long ms) {
            int left = Math.max(1, COUNTDOWN_SECONDS - (int) (ms / 1000));
            float part = (ms % 1000) / 1000f;
            float cy = h * 0.6f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(6));
            p.setColor(DARK);
            float rad = dp(62);
            c.drawCircle(w / 2, cy, rad, p);
            p.setColor(GOLD);
            r.set(w / 2 - rad, cy - rad, w / 2 + rad, cy + rad);
            c.drawArc(r, -90, 360 * (1 - part), false, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(TEXT);
            p.setTextSize(dp(64));
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            c.drawText(Integer.toString(left), w / 2, cy + dp(23), p);
            p.setTypeface(Typeface.DEFAULT);
        }

        private void measuring(Canvas c, float w, float table, long ms) {
            table(c, w * 0.22f, w * 0.78f, table);
            phone(c, w / 2, table, dp(220), dp(14), 0, 0);
            float cy = table - dp(62), rad = dp(30);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(5));
            p.setColor(DARK);
            c.drawCircle(w / 2, cy, rad, p);
            p.setColor(GOLD);
            r.set(w / 2 - rad, cy - rad, w / 2 + rad, cy + rad);
            c.drawArc(r, -90, 360f * Math.min(1f, ms / (float) MEASURE_MS), false, p);
            p.setStyle(Paint.Style.FILL);
        }

        private void result(Canvas c, float w, float h, boolean good) {
            float cy = h * 0.6f, rad = dp(52);
            p.setStyle(Paint.Style.FILL);
            p.setColor(good ? GREEN : RED);
            c.drawCircle(w / 2, cy, rad, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(dp(9));
            p.setColor(TEXT);
            if (good) {
                path.reset();
                path.moveTo(w / 2 - rad * 0.45f, cy + rad * 0.02f);
                path.lineTo(w / 2 - rad * 0.1f, cy + rad * 0.38f);
                path.lineTo(w / 2 + rad * 0.48f, cy - rad * 0.32f);
                c.drawPath(path, p);
            } else {
                float d = rad * 0.38f;
                c.drawLine(w / 2 - d, cy - d, w / 2 + d, cy + d, p);
                c.drawLine(w / 2 + d, cy - d, w / 2 - d, cy + d, p);
            }
            p.setStrokeCap(Paint.Cap.BUTT);
            p.setStyle(Paint.Style.FILL);
        }
    }
}
