package io.github.them3dium.mareshine;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import static io.github.them3dium.mareshine.L.t;

// The mod manager's intro on the app's real screens (IntroActivity starts it
// after its first page): each step is the screen as it is, with the example
// mod (Mods.tourStart) among the installed ones, a layer over it that takes
// every touch and key (nothing on the screen can be pressed, Back does
// nothing), a moving picture drawn on that layer at the screen's own views (a
// finger, the pointer, the focus), and the step's text with Skip and Next. Next
// opens the next step's screen; Skip, Back on the first and Next on the last
// go back to IntroActivity (the result is forwarded along the steps).
final class Tour {
    static final String EXTRA = "tour";  // the step, as the screen knows it
    static final String SETTINGS = "settings", SEARCH = "search", INSTALLED = "installed", PAGE = "page",
        RATE = "rate";
    private static final String[] STEPS = {SETTINGS, SEARCH, INSTALLED, PAGE, RATE};

    interface Picture {
        void draw(Canvas c, Paint p, long ms);
    }

    private final AppActivity mA;
    private final List<View> mButtons = new ArrayList<>();
    private long mStart = SystemClock.uptimeMillis();
    private Picture mPicture, mOver;
    private View mDrawn;  // the layer's picture: what the rects are measured from
    static final int RESULT_BACK = Activity.RESULT_FIRST_USER + 40;  // Back on the first step
    static final int RESULT_DONE = Activity.RESULT_FIRST_USER + 41;  // Next on the last

    Tour(AppActivity a) { mA = a; }

    // the step this screen shows, or null (not in the tour)
    static String step(Activity a) { return a.getIntent().getStringExtra(EXTRA); }

    static Intent intent(Activity from, int index) {
        String step = STEPS[index];
        boolean page = step.equals(PAGE) || step.equals(RATE);
        Class<?> screen = step.equals(SETTINGS) ? SettingsActivity.class
            : page ? ModPageActivity.class : ModsActivity.class;
        Intent i = new Intent(from, screen).putExtra(EXTRA, step);
        if (page) i.putExtra(ModPageActivity.EXTRA_ID, Mods.TOUR_ID).putExtra(ModPageActivity.EXTRA_PAGE, "");
        return i;
    }

    static int count() { return STEPS.length; }

    private static int index(String step) {
        for (int i = 0; i < STEPS.length; i++) if (STEPS[i].equals(step)) return i;
        return STEPS.length - 1;
    }

    // Shows the step's layer, its text where `gravity` puts it (`width` of the
    // screen's, `lift` dp more off the bottom)
    void show(int gravity, float width, Picture picture) { show(gravity, width, 0, picture); }

    void show(int gravity, float width, float lift, Picture picture) {
        String step = step(mA);
        String title, text;
        switch (step) {
            case SETTINGS:
                title = t("Where to find them");
                text = t("Settings, MODS, Open mods. In the game, the quick menu has a MODS button too.");
                break;
            case SEARCH:
                title = t("Finding mods");
                text = t("In SEARCH MODS, tap a mod (or press A). The app downloads it and makes it from your disc. For "
                    + "some mods you pick the downloaded file instead.");
                break;
            case INSTALLED:
                title = t("Managing them");
                text = t("In INSTALLED, hold a mod (or hold A) to switch it on or off, move it or delete it.");
                break;
            case RATE:
                title = t("Rating mods");
                text = t("Tap the suns at the top (or press A on them) and give the mod one to five suns. Everyone sees "
                    + "the average of all players' ratings. You can change yours once an hour.");
                break;
            default:
                title = t("The mod's page");
                text = t("ON or OFF at the top, its buttons at the bottom: language, HD textures, website. With a "
                    + "controller, the right stick moves a pointer that A clicks, and the left stick moves between the "
                    + "buttons.");
                break;
        }
        show(title, text, false, gravity, width, lift, picture);
    }

    float dp(float v) { return v * mA.getResources().getDisplayMetrics().density; }

    // Shows the layer: the text (where `gravity` puts it, `width` of the
    // screen's), Skip and Next (Got it on the last step), and the picture
    private void show(String title, String text, boolean last, int gravity, float width, float lift,
                      Picture picture) {
        mPicture = picture;
        // the screen under it keeps no focus: the controller stays on the layer's buttons
        ViewGroup content = mA.findViewById(android.R.id.content);
        for (int i = 0; i < content.getChildCount(); i++) {
            View v = content.getChildAt(i);
            if (v instanceof ViewGroup) ((ViewGroup) v).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            v.setFocusable(false);
        }
        FrameLayout layer = new FrameLayout(mA);
        View drawn = new View(mA) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(Canvas c) {
                if (mPicture != null) mPicture.draw(c, p, SystemClock.uptimeMillis() - mStart);
                postInvalidateOnAnimation();
            }

            // every touch stops here: the screen under it is only shown
            @Override
            public boolean onTouchEvent(MotionEvent e) { return true; }
        };
        mDrawn = drawn;
        layer.addView(drawn, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout panel = new LinearLayout(mA);
        panel.setOrientation(LinearLayout.VERTICAL);
        // solid under the app's panel: the screen's own buttons do not show through
        GradientDrawable under = new GradientDrawable();
        under.setColor(0xFF0A1B30);
        under.setCornerRadius(dp(12));
        panel.setBackground(new android.graphics.drawable.LayerDrawable(new Drawable[] {under,
            mA.getDrawable(R.drawable.dl_panel)}));
        int pad = Math.round(dp(16));
        panel.setPadding(pad, Math.round(dp(12)), pad, Math.round(dp(12)));
        panel.setClickable(true);
        mFirst = new Object[] {title, text, picture};
        TextView head = mHead = new TextView(mA);
        head.setText(title);
        head.setTextColor(0xFFFFC531);
        head.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        head.setTextSize(20);
        panel.addView(head);
        TextView body = mBody = new TextView(mA);
        body.setText(text);
        body.setTextColor(0xFFF4F8FF);
        body.setTextSize(15);
        body.setPadding(0, Math.round(dp(4)), 0, Math.round(dp(8)));
        panel.addView(body);
        LinearLayout buttons = new LinearLayout(mA);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        buttons.addView(button(t("Back"), false, this::back), buttonParams());
        if (!last) buttons.addView(button(t("Skip"), false, this::end), buttonParams());
        Button next = button(t(last ? "Got it" : "Next"), true, last ? this::end : this::next);
        mMain = next;
        buttons.addView(next, buttonParams());
        panel.addView(buttons);
        int w = Math.round(mA.getResources().getDisplayMetrics().widthPixels * width);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT, gravity);
        int m = Math.round(dp(18));
        plp.setMargins(m, m, m, m + Math.round(dp(lift)));
        layer.addView(panel, plp);
        // what is drawn over the text too (the pointer, which goes where it likes)
        View over = new View(mA) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(Canvas c) {
                if (mOver != null) {
                    int[] a = new int[2], b = new int[2];
                    getLocationInWindow(a);
                    mDrawn.getLocationInWindow(b);
                    c.translate(b[0] - a[0], b[1] - a[1]);  // in the picture's coordinates
                    mOver.draw(c, p, SystemClock.uptimeMillis() - mStart);
                }
                postInvalidateOnAnimation();
            }
        };
        over.setClickable(false);
        over.setFocusable(false);
        layer.addView(over, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        mA.addContentView(layer, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        layer.setAlpha(0f);
        layer.animate().alpha(1f).setDuration(400).start();
        next.post(next::requestFocus);
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Math.round(dp(6)), 0, 0, 0);
        return lp;
    }

    // as IntroActivity's: the orange one solid, the other see-through
    private Button button(String label, boolean main, Runnable action) {
        Button b = new Button(mA);
        b.setText(label);
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
            bg.setColor(0xFFFFA41C);
            b.setBackground(bg);
            b.setOnFocusChangeListener((v, has) -> bg.setStroke(has ? Math.round(dp(2)) : 0, 0xFFFFF3C4));
            b.setTextColor(0xFF0A1B30);
        } else {
            b.setBackgroundResource(R.drawable.dl_row);
            b.setTextColor(0xFFF4F8FF);
        }
        b.setOnClickListener(v -> action.run());
        mButtons.add(b);
        return b;
    }

    // the screen's controller entries while the layer is up: its buttons alone
    List<View> padItems() { return mButtons; }

    // drawn over the text as well (same clock as the picture)
    void over(Picture picture) { mOver = picture; }

    // where a controller starts: Next (Got it)
    View padDefault() { return mMain; }

    private View mMain;

    private boolean mLeaving;
    private TextView mHead, mBody;
    private Object[] mFirst, mThen;  // a step's two parts: title, text, picture
    private boolean mInThen;
    private Picture mBeforeNext;
    private long mBeforeNextMs;

    // what Next shows first, for `ms`, before the next step's screen (a tap
    // that opens it)
    void beforeNext(long ms, Picture picture) {
        mBeforeNextMs = ms;
        mBeforeNext = picture;
    }

    // A second part of the step, on the same screen: Next shows its text and
    // picture in place of the first's, Back the first's again
    void then(String title, String text, Picture picture) { mThen = new Object[] {title, text, picture}; }

    private void part(Object[] p) {
        mHead.setText((String) p[0]);
        mBody.setText((String) p[1]);
        mPicture = (Picture) p[2];
        mStart = SystemClock.uptimeMillis();
        View panel = (View) mHead.getParent();
        panel.setAlpha(0f);
        panel.animate().alpha(1f).setDuration(350).start();
    }

    // the next step's screen, over this one as it goes
    private void next() {
        if (mLeaving) return;
        if (mThen != null && !mInThen) {
            mInThen = true;
            part(mThen);
            return;
        }
        mLeaving = true;
        if (mBeforeNext != null) {
            mPicture = mBeforeNext;
            mStart = SystemClock.uptimeMillis();
            mA.getWindow().getDecorView().postDelayed(this::open, mBeforeNextMs);
            return;
        }
        open();
    }

    private void open() {
        if (index(step(mA)) == STEPS.length - 1) {  // the last: IntroActivity's page after the steps
            mA.setResult(RESULT_DONE);
            mA.finish();
            mA.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            return;
        }
        mA.startActivity(intent(mA, index(step(mA)) + 1).addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT));
        mA.finish();
        mA.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    // the step before (the first: back to IntroActivity's last page)
    private void back() {
        if (mLeaving) return;
        if (mInThen) {
            mInThen = false;
            part(mFirst);
            return;
        }
        mLeaving = true;
        int at = index(step(mA));
        if (at == 0) mA.setResult(RESULT_BACK);
        else mA.startActivity(intent(mA, at - 1).addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT));
        mA.finish();
        mA.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    // back to IntroActivity, which ends the intro
    private void end() {
        if (mLeaving) return;
        mLeaving = true;
        mA.finish();
        mA.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    // --- the picture's parts ----------------------------------------------------

    // where a view of the screen is, on the layer's picture (a screen may keep
    // its content clear of a cutout, the layer with it)
    RectF rectOf(View v) {
        if (v == null || !v.isShown() || mDrawn == null) return null;
        int[] at = new int[2], root = new int[2];
        v.getLocationInWindow(at);
        mDrawn.getLocationInWindow(root);
        return new RectF(at[0] - root[0], at[1] - root[1], at[0] - root[0] + v.getWidth(), at[1] - root[1] + v.getHeight());
    }

    // the first view of the screen showing this text (case aside), or null
    static View withText(View v, String text) {
        if (v instanceof TextView && ((TextView) v).getText().toString().trim().equalsIgnoreCase(text)) return v;
        if (v instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
                View f = withText(((ViewGroup) v).getChildAt(i), text);
                if (f != null) return f;
            }
        return null;
    }

    // the view itself, or the first one around it that can be pressed (a settings row)
    static View pressable(View v) {
        for (View x = v; x != null; x = x.getParent() instanceof View ? (View) x.getParent() : null)
            if (x.isClickable()) return x;
        return v;
    }

    // a button as the app draws it when focused (dl_row's own drawable)
    void focused(Canvas c, RectF r) { focused(c, r, 1f); }

    void focused(Canvas c, RectF r, float alpha) {
        Drawable d = mA.getDrawable(R.drawable.dl_row);
        if (d == null) return;
        d.setAlpha(Math.round(255 * alpha));
        d.setState(new int[] {android.R.attr.state_focused});
        d.setBounds(new Rect(Math.round(r.left), Math.round(r.top), Math.round(r.right), Math.round(r.bottom)));
        d.draw(c);
    }

    // a finger: a see-through white circle, pressed or not; a gold ring growing
    // round it while it holds (0 to 1)
    void finger(Canvas c, Paint p, float x, float y, float alpha, boolean down, float hold) {
        if (alpha <= 0) return;
        float fr = dp(down ? 20 : 24);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFFF | (Math.round(110 * alpha) << 24));
        c.drawCircle(x, y, fr, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(2));
        p.setColor(0xFFFFFF | (Math.round(230 * alpha) << 24));
        c.drawCircle(x, y, fr, p);
        if (hold > 0) {
            p.setStrokeWidth(dp(3));
            p.setColor(0xFFFFC531);
            c.drawArc(new RectF(x - fr - dp(8), y - fr - dp(8), x + fr + dp(8), y + fr + dp(8)), -90,
                360 * Math.min(1, hold), false, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    // the pointer the right stick moves, as AppActivity draws it: a round,
    // half-clear spot
    void pointer(Canvas c, Paint p, float x, float y) {
        float r = dp(14);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x73FFFFFF);
        c.drawCircle(x, y, r, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(r / 6);
        p.setColor(0x99000000);
        c.drawCircle(x, y, r, p);
        p.setStyle(Paint.Style.FILL);
    }

    // a click's ring, r from 0 to 1
    void click(Canvas c, Paint p, float x, float y, float r) {
        if (r < 0 || r > 1) return;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(2));
        p.setColor(0xFFFFFF | (Math.round(200 * (1 - r)) << 24));
        c.drawCircle(x, y, dp(8) + dp(18) * r, p);
        p.setStyle(Paint.Style.FILL);
    }

    // a short line on a dark pill, centred at x, y
    void label(Canvas c, Paint p, String s, float x, float y, int color) {
        p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        p.setTextSize(dp(15));
        p.setTextAlign(Paint.Align.CENTER);
        float w = p.measureText(s) / 2 + dp(12);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xCC0A1B30);
        c.drawRoundRect(new RectF(x - w, y - dp(15), x + w, y + dp(9)), dp(10), dp(10), p);
        p.setColor(color);
        c.drawText(s, x, y + dp(1), p);
    }

    static float loop(long ms, float cycle) { return ms % (long) cycle / cycle; }

    static float ease(float t, float a, float b) {
        float k = Math.max(0, Math.min(1, (t - a) / (b - a)));
        return k * k * (3 - 2 * k);
    }

    // a finger showing on the middle of r (no sliding: it is where it taps),
    // tapping it at `tap` (0 to 1 of the loop), going
    void tap(Canvas c, Paint p, RectF r, float t, float tap) {
        if (r == null) return;
        float a = t < tap - 0.25f ? 0 : t < tap - 0.15f ? (t - tap + 0.25f) / 0.1f : t < tap + 0.15f ? 1
            : Math.max(0, 1 - (t - tap - 0.15f) / 0.1f);
        finger(c, p, r.centerX(), r.centerY(), a, t >= tap && t < tap + 0.08f, 0);
        if (t >= tap && t < tap + 0.2f) click(c, p, r.centerX(), r.centerY(), (t - tap) / 0.2f);
    }
}
