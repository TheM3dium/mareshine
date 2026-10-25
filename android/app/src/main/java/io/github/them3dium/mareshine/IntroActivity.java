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
        boolean back;  // a Back button, to the choice before

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
    private String mStyle, mSlider;          // the choice made (null: as before)
    private String mOrigStyle, mOrigSlider;  // the settings before it (null: not set)

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
        // faded in, softly
        View root = findViewById(android.R.id.content);
        root.setAlpha(0f);
        root.animate().alpha(1f).setStartDelay(150).setDuration(700).start();
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

    // back: to the choice from the page after it, else as Skip
    @Override
    public void onBackPressed() {
        if (mIndex >= 0 && mPages.get(mIndex).back) back();
        else skip();
    }

    // --- the intros' pages ----------------------------------------------------

    private void pages() {
        switch (mId) {
            case Intro.GAMECUBE_TOUCH: gamecubeTouch(); break;
            case Intro.EDITOR_FOLD: editorFold(); break;
            default: break;
        }
    }

    private void gamecubeTouch() {
        List<Elem> elems = TouchDraw.defaults();
        for (Elem e : elems) e.reset(true);
        // the buttons as they are now, shown when they stay
        Map<String, String> m = Settings.read(this);
        mOrigStyle = m.get("SMS_TOUCH_STYLE");
        mOrigSlider = m.get("SMS_TOUCH_R_SLIDER");
        boolean ownGc = !"classic".equals(mOrigStyle), ownSlider = !"0".equals(mOrigSlider);
        List<Elem> own = TouchDraw.defaults();
        TouchDraw.reset(own, ownGc, ownSlider);
        TouchDraw.applyLayout(own, m.get(ownGc ? "SMS_TOUCH_LAYOUT_GAMECUBE" : "SMS_TOUCH_LAYOUT"));
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
            (c, p, w, h, ms) -> {
                if (mStyle == null) drawOwn(c, p, own, ownGc, ownSlider, w, h, Math.min(1f, ms / 600f));
                else drawLayout(c, p, elems, w, h, 1f, null);
            });
        done.asks = true;
        done.back = true;
        mPages.add(done);
    }

    private void editorFold() {
        List<Elem> elems = TouchDraw.defaults();
        Map<String, String> m = Settings.read(this);
        boolean gc = !"classic".equals(m.get("SMS_TOUCH_STYLE"));
        TouchDraw.reset(elems, gc, !"0".equals(m.get("SMS_TOUCH_R_SLIDER")));
        mPages.add(new Page(t("Fold the layout editor's toolbar"),
            t("In Customize layout, tap ◀ under the button's name: the toolbar folds up out of the way. ▶ opens it again."),
            (c, p, w, h, ms) -> drawFold(c, p, elems, gc, w, h, ms)));
    }

    // the choice: the style and the R+ slider (null: as they were before the
    // intro, also after another choice and Back)
    private void chooseTouch(String style, String slider) {
        mStyle = style;
        mSlider = slider;
        Map<String, String> m = Settings.read(this);
        put(m, "SMS_TOUCH_STYLE", style != null ? style : mOrigStyle);
        put(m, "SMS_TOUCH_R_SLIDER", slider != null ? slider : mOrigSlider);
        Settings.write(this, m);
        show(mIndex + 1);
    }

    private static void put(Map<String, String> m, String key, String value) {
        if (value != null) m.put(key, value);
        else m.remove(key);
    }

    // --- pictures -------------------------------------------------------------

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    // The GameCube buttons (and the top row) as the game places them, faded in by fade; bright:
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
            if (e.name.equals("RPLUS") && !separate) continue;
            if (bright != null && e.name.equals(bright)) continue;
            Elem at = separate && e == r ? rp : separate && e == rp ? r : e;
            int a = Math.round(225 * fade * (bright != null ? 0.35f : 1f));
            // the top row (START, MENU, ...) as discs, as in the game
            if (!TouchDraw.drawGc(c, p, e, at.x * w, at.y * h, e.r * h, a, dp(6)))
                TouchDraw.drawClassic(c, p, e, at.x * w, at.y * h, e.r * h, a, dp(6), dp(1));
        }
    }

    // The buttons as they are (their style, places, sizes; hidden ones left out)
    private void drawOwn(Canvas c, Paint p, List<Elem> own, boolean gc, boolean slider, float w, float h, float fade) {
        TouchDraw.background(c, p, w, h);
        int a = Math.round(225 * fade);
        for (Elem e : own) {
            if (!e.shown || (slider && e.name.equals("RPLUS"))) continue;
            if (!gc || !TouchDraw.drawGc(c, p, e, e.x * w, e.y * h, e.r * h, a, dp(6)))
                TouchDraw.drawClassic(c, p, e, e.x * w, e.y * h, e.r * h, a, dp(6), dp(1));
        }
    }

    // The editor's toolbar folding up to its label and opening again, a finger
    // tapping its arrow
    private void drawFold(Canvas c, Paint p, List<Elem> elems, boolean gc, float w, float h, long ms) {
        TouchDraw.background(c, p, w, h);
        for (Elem e : elems) {
            if (e.name.equals("RPLUS") || (!gc && !e.shown)) continue;
            if (!gc || !TouchDraw.drawGc(c, p, e, e.x * w, e.y * h, e.r * h, 120, dp(6)))
                TouchDraw.drawClassic(c, p, e, e.x * w, e.y * h, e.r * h, 120, dp(6), dp(1));
        }
        float cycle = 4400, t = ms % (long) cycle / cycle;
        // 0 open; tap at .2; folds .25 to .35; tap at .62; opens .67 to .77
        float k = t < 0.25f ? 0 : t < 0.35f ? (t - 0.25f) / 0.1f : t < 0.67f ? 1 : t < 0.77f ? 1 - (t - 0.67f) / 0.1f : 0;
        k = k * k * (3 - 2 * k);  // eased
        boolean folded = t >= 0.3f && t < 0.72f;
        float x0 = w * 0.17f, y0 = h * 0.40f, fullW = w * 0.66f, smallW = dp(150);
        float pw = fullW + (smallW - fullW) * k, ph = dp(108) + (dp(70) - dp(108)) * k;
        android.graphics.RectF panel = new android.graphics.RectF(x0, y0, x0 + pw, y0 + ph);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xF00E2440);
        c.drawRoundRect(panel, dp(12), dp(12), p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(2));
        p.setColor(0xFF2FB8C9);
        c.drawRoundRect(panel, dp(12), dp(12), p);
        c.save();
        c.clipRect(panel);
        // the label and its arrow
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(dp(16));
        p.setColor(0xFFFFC531);
        c.drawText("✥ A  100%", x0 + dp(16), y0 + dp(28), p);
        android.graphics.RectF arrow = new android.graphics.RectF(x0 + dp(14), y0 + dp(38), x0 + dp(56), y0 + dp(60));
        p.setColor(0xFF143456);
        c.drawRoundRect(arrow, dp(8), dp(8), p);
        p.setColor(0xFFF4F8FF);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(dp(13));
        c.drawText(folded ? "▶" : "◀", arrow.centerX(), arrow.centerY() + dp(5), p);
        // the buttons and the opacity bar, folded away
        String[] tools = {"GCUBE", "SIZE −", "SIZE +", "HIDE", "R+ SLIDER", "RESET ALL", "CANCEL", "SAVE"};
        float bx = x0 + dp(170), bw = (fullW - dp(180)) / tools.length;
        for (int i = 0; i < tools.length; i++) {
            android.graphics.RectF b = new android.graphics.RectF(bx + i * bw + dp(3), y0 + dp(14), bx + (i + 1) * bw - dp(3), y0 + dp(46));
            p.setColor(i == tools.length - 1 ? 0xFFFFB020 : 0xFF143456);
            c.drawRoundRect(b, dp(8), dp(8), p);
            p.setColor(i == tools.length - 1 ? 0xFF0A1B30 : 0xFFF4F8FF);
            p.setTextSize(dp(11));
            c.drawText(tools[i], b.centerX(), b.centerY() + dp(4), p);
        }
        p.setColor(0xFFFFC531);
        c.drawRoundRect(new android.graphics.RectF(x0 + fullW * 0.35f, y0 + dp(70), x0 + fullW * 0.75f, y0 + dp(84)), dp(7), dp(7), p);
        c.restore();
        // the finger on the arrow
        float tap = t >= 0.12f && t < 0.3f ? (t - 0.12f) / 0.18f : t >= 0.54f && t < 0.72f ? (t - 0.54f) / 0.18f : -1;
        if (tap >= 0) {
            float a = (float) Math.sin(Math.PI * tap);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFF | (Math.round(110 * a) << 24));
            c.drawCircle(arrow.centerX(), arrow.centerY(), dp(20), p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            p.setColor(0xFFFFFF | (Math.round(230 * a) << 24));
            c.drawCircle(arrow.centerX(), arrow.centerY(), dp(20), p);
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
        plp.topMargin = Math.round(getResources().getDisplayMetrics().heightPixels * 0.17f);  // below the top row
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
            if (page.back) {
                Button back = button(t("Back"), false);
                back.setOnClickListener(v -> back());
                addButton(back);
            }
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

    // Back: to the choice before this page
    private void back() {
        for (int i = mIndex - 1; i >= 0; i--)
            if (!mPages.get(i).choices.isEmpty()) { show(i); return; }
    }

    // Skip: to the choice when there is one still to make, else done
    private void skip() {
        for (int i = mIndex + 1; i < mPages.size(); i++)
            if (!mPages.get(i).choices.isEmpty()) { show(i); return; }
        if (mIndex >= 0 && !mPages.get(mIndex).choices.isEmpty()) return;  // the choice itself: choose
        finishIntro();
    }

    // done: faded out, to the next intro (it fades itself in) or to the launcher
    private void finishIntro() {
        Intro.done(this, mId);
        String next = Intro.pending(this);
        View root = findViewById(android.R.id.content);
        if (mView == null || root == null) {
            if (next != null) startActivity(new android.content.Intent(this, IntroActivity.class).putExtra(EXTRA_ID, next));
            finish();
            overridePendingTransition(0, 0);
            return;
        }
        mButtons.setEnabled(false);
        root.animate().alpha(0f).setDuration(450).withEndAction(() -> {
            if (next != null) {
                startActivity(new android.content.Intent(this, IntroActivity.class).putExtra(EXTRA_ID, next));
                finish();
                overridePendingTransition(0, 0);
            } else {
                finish();
                overridePendingTransition(android.R.anim.fade_in, 0);
            }
        }).start();
    }
}
