package io.github.them3dium.mareshine;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.them3dium.mareshine.TouchDraw.Elem;

import static io.github.them3dium.mareshine.L.t;

// Shows an intro (Intro): its pages one after another over a moving picture,
// with Skip at the bottom. A page either goes on (Next) or offers choices; a
// choice page is shown only when the intro asks (an update), and Skip goes
// straight to it.
public class IntroActivity extends AppActivity {
    static final String EXTRA_ID = "intro";

    // one page: its text, its picture (drawn every frame, ms since the page
    // came), and its choices (none: Next)
    private static final class Page {
        final String title, text;
        final Picture picture;
        final List<String> choices = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        boolean asks;  // a choice page: only when the intro asks

        Page(String title, String text, Picture picture) {
            this.title = title;
            this.text = text;
            this.picture = picture;
        }

        Page choice(String label, Runnable action) {
            choices.add(label);
            actions.add(action);
            asks = true;
            return this;
        }
    }

    private interface Picture {
        void draw(Canvas c, Paint p, float w, float h, long ms);
    }

    private String mId;
    private boolean mAsks;
    private final List<Page> mPages = new ArrayList<>();
    private int mIndex = -1;
    private long mPageStart;
    private PictureView mView;
    private LinearLayout mPanel, mButtons;
    private TextView mTitle, mText;
    private String mStyle, mSlider;  // the choice made (null: as before)

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        L.init(this);
        mId = getIntent().getStringExtra(EXTRA_ID);
        if (mId == null) { finish(); return; }
        mAsks = Intro.asks(this, mId);
        pages();
        for (int i = mPages.size() - 1; i >= 0; i--) if (mPages.get(i).asks && !mAsks) mPages.remove(i);
        if (mPages.isEmpty()) { finishIntro(); return; }
        buildUi();
        hideSystemUi();
        show(0);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    // the whole display, as the game has it, so the buttons are where they will be
    @Override protected boolean keepClearOfSystemUi() { return false; }

    private void hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    // back: as Skip
    @Override
    public void onBackPressed() { skip(); }

    // --- the intros' pages ----------------------------------------------------

    private void pages() {
        switch (mId) {
            case Intro.GAMECUBE_TOUCH: gamecubeTouch(); break;
            default: break;
        }
    }

    private void gamecubeTouch() {
        List<Elem> elems = TouchDraw.defaults();
        for (Elem e : elems) e.reset(true);
        mPages.add(new Page(t("New: the GameCube button style"),
            t("The touch buttons now have the shapes and colours of the GameCube controller, and sit where it has them."),
            (c, p, w, h, ms) -> drawLayout(c, p, elems, w, h, Math.min(1f, ms / 1400f), null)));
        mPages.add(new Page(t("R and R+ on one button"),
            t("Press R and swipe down: past the dashed circle R becomes R+, pressed all the way in."),
            (c, p, w, h, ms) -> drawRSlide(c, p, elems, w, h, ms)));
        Page ask = new Page(t("Switch to the new buttons?"),
            t("You are updating: your touch buttons stay as they were unless you switch."),
            (c, p, w, h, ms) -> drawLayout(c, p, elems, w, h, 1f, null));
        ask.choice(t("Yes"), () -> chooseTouch("gamecube", "1"))
            .choice(t("Yes, with a separate R+ button"), () -> chooseTouch("gamecube", "0"))
            .choice(t("No"), () -> chooseTouch(null, null));
        mPages.add(ask);
        Page done = new Page(t("All set"),
            t("You can change this at any time: Settings, Input, Touch (Button style, and the R+ slider under Customize layout)."),
            (c, p, w, h, ms) -> drawLayout(c, p, elems, w, h, mStyle == null ? 0.35f : 1f, null));
        done.asks = true;
        mPages.add(done);
    }

    // the choice: the style and the R+ slider (null: as they are)
    private void chooseTouch(String style, String slider) {
        mStyle = style;
        mSlider = slider;
        Map<String, String> m = Settings.read(this);
        if (style != null) m.put("SMS_TOUCH_STYLE", style);
        if (slider != null) m.put("SMS_TOUCH_R_SLIDER", slider);
        Settings.write(this, m);
        show(mIndex + 1);
    }

    // --- pictures -------------------------------------------------------------

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    // The GameCube buttons as the game places them, faded in by fade; bright:
    // all but that one dimmed (the caller draws it). With the slider (the
    // default) there is no R+ button; chosen without it, R+ is in R's place
    // and R above it (touch.cpp)
    private void drawLayout(Canvas c, Paint p, List<Elem> elems, float w, float h, float fade, String bright) {
        TouchDraw.background(c, p, w, h);
        boolean separate = "0".equals(mSlider);
        Elem r = null, rp = null;
        for (Elem e : elems) {
            if (e.name.equals("R")) r = e;
            if (e.name.equals("RPLUS")) rp = e;
        }
        for (Elem e : elems) {
            if (TouchDraw.gcShape(e.name) < 0 || (e.name.equals("RPLUS") && !separate)) continue;
            if (bright != null && e.name.equals(bright)) continue;
            Elem at = separate && e == r ? rp : separate && e == rp ? r : e;
            int a = Math.round(225 * fade * (bright != null ? 0.35f : 1f));
            TouchDraw.drawGc(c, p, e, at.x * w, at.y * h, e.r * h, a, dp(6));
        }
    }

    // R marked by a ring; a finger presses it and swipes down, and past the
    // slider's circle R is drawn pressed in, as R+
    private void drawRSlide(Canvas c, Paint p, List<Elem> elems, float w, float h, long ms) {
        drawLayout(c, p, elems, w, h, 1f, "R");
        Elem r = null;
        for (Elem e : elems) if (e.name.equals("R")) r = e;
        if (r == null) return;
        float cx = r.x * w, cy = r.y * h, rPx = r.r * h, slide = 0.06f * h;
        float cycle = 2800, t = ms % (long) cycle / cycle;
        // the finger: comes (0 to .15), presses (.15 to .3), swipes (.3 to .55), holds, goes (.85 to 1)
        float fy = cy + (t < 0.3f ? 0 : Math.min(1f, (t - 0.3f) / 0.25f)) * slide * 1.7f;
        boolean down = t >= 0.15f && t < 0.85f, plus = down && fy - cy > slide;
        float fingerA = t < 0.15f ? t / 0.15f : t > 0.85f ? (1 - t) / 0.15f : 1;
        // the slider's circle
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(2));
        p.setColor(0xCCFFFFFF);
        p.setPathEffect(new DashPathEffect(new float[] {dp(6), dp(5)}, 0));
        c.drawCircle(cx, cy, slide, p);
        p.setPathEffect(null);
        // R, pressed: lighter; past the circle pressed in, as R+
        int idx = TouchDraw.gcIndex(plus ? "TRIGGER_RPLUS" : "TRIGGER_R");
        int rgb = down ? 0xF0F2F5 : TouchDraw.gcColor("R");
        TouchDraw.drawGcShape(c, p, idx, plus ? "R+" : "R", cx, cy, 0.94f * rPx, TouchDraw.gcTilt("R"), rgb, 235,
            TouchDraw.gcLetterScale("R"));
        // the ring marking R, breathing
        float pulse = (float) (0.5 + 0.5 * Math.sin(ms / 260.0));
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(3));
        p.setColor(0xFFFFC531 & 0x00FFFFFF | (Math.round(150 + 105 * pulse) << 24));
        c.drawCircle(cx, cy, rPx * 1.25f + dp(4) * pulse, p);
        // the finger
        if (fingerA > 0) {
            float fr = dp(down ? 20 : 24);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFF | (Math.round(110 * fingerA) << 24));
            c.drawCircle(cx, fy, fr, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            p.setColor(0xFFFFFF | (Math.round(230 * fingerA) << 24));
            c.drawCircle(cx, fy, fr, p);
        }
    }

    private class PictureView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        PictureView(Context c) { super(c); }

        @Override
        protected void onDraw(Canvas c) {
            if (mIndex < 0 || mIndex >= mPages.size()) return;
            mPages.get(mIndex).picture.draw(c, p, getWidth(), getHeight(), SystemClock.uptimeMillis() - mPageStart);
            postInvalidateOnAnimation();
        }
    }

    // --- the page around it ---------------------------------------------------

    private Button button(String text, boolean main) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(true);
        b.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        b.setTextSize(15);
        b.setStateListAnimator(null);
        b.setBackgroundResource(R.drawable.dl_row);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(Math.round(dp(18)), Math.round(dp(8)), Math.round(dp(18)), Math.round(dp(8)));
        if (main) {
            b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFFB020));
            b.setTextColor(0xFF0A1B30);
        } else {
            b.setTextColor(0xFFF4F8FF);
        }
        return b;
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        mView = new PictureView(this);
        root.addView(mView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // the text, at the top between the shoulder buttons
        mPanel = new LinearLayout(this);
        mPanel.setOrientation(LinearLayout.VERTICAL);
        mPanel.setBackgroundResource(R.drawable.dl_panel);
        int pad = Math.round(dp(16));
        mPanel.setPadding(pad, Math.round(dp(12)), pad, Math.round(dp(14)));
        mTitle = new TextView(this);
        mTitle.setTextColor(0xFFFFC531);
        mTitle.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        mTitle.setTextSize(22);
        mPanel.addView(mTitle);
        mText = new TextView(this);
        mText.setTextColor(0xFFF4F8FF);
        mText.setTextSize(16);
        mText.setPadding(0, Math.round(dp(6)), 0, 0);
        mPanel.addView(mText);
        int panelW = Math.round(getResources().getDisplayMetrics().widthPixels * 0.5f);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(panelW, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        plp.topMargin = Math.round(getResources().getDisplayMetrics().heightPixels * 0.05f);
        root.addView(mPanel, plp);

        // the buttons, at the bottom in the middle
        mButtons = new LinearLayout(this);
        mButtons.setOrientation(LinearLayout.HORIZONTAL);
        mButtons.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        blp.bottomMargin = Math.round(dp(14));
        root.addView(mButtons, blp);
        setContentView(root);
    }

    private void addButton(Button b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Math.round(dp(6)), 0, Math.round(dp(6)), 0);
        mButtons.addView(b, lp);
    }

    private void show(int index) {
        if (index >= mPages.size()) { finishIntro(); return; }
        mIndex = index;
        mPageStart = SystemClock.uptimeMillis();
        Page page = mPages.get(index);
        mTitle.setText(page.title);
        mText.setText(page.text);
        mPanel.setAlpha(0f);
        mPanel.animate().alpha(1f).setDuration(350).start();
        mButtons.removeAllViews();
        Button first;
        if (!page.choices.isEmpty()) {
            first = null;
            for (int i = 0; i < page.choices.size(); i++) {
                Button b = button(page.choices.get(i), i == 0);
                Runnable action = page.actions.get(i);
                b.setOnClickListener(v -> action.run());
                addButton(b);
                if (first == null) first = b;
            }
        } else {
            boolean last = index == mPages.size() - 1;
            if (!last) {
                Button skip = button(t("Skip"), false);
                skip.setOnClickListener(v -> skip());
                addButton(skip);
            }
            first = button(t(last ? "Got it" : "Next"), true);
            first.setOnClickListener(v -> show(mIndex + 1));
            addButton(first);
        }
        first.requestFocus();
        mView.invalidate();
    }

    // Skip: to the choice when there is one still to make, else done
    private void skip() {
        for (int i = mIndex + 1; i < mPages.size(); i++)
            if (!mPages.get(i).choices.isEmpty()) { show(i); return; }
        if (mIndex >= 0 && !mPages.get(mIndex).choices.isEmpty()) return;  // the choice itself: choose
        finishIntro();
    }

    private void finishIntro() {
        Intro.done(this, mId);
        finish();
    }
}
