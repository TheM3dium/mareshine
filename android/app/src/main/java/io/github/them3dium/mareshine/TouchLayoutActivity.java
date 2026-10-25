package io.github.them3dium.mareshine;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.github.them3dium.mareshine.L.t;

// Touch layout editor: drag the on-screen buttons and sticks where you want
// them, change their size (the toolbar, or a two-finger pinch) and hide the
// ones you do not use. Saved as SMS_TOUCH_LAYOUT in settings.txt
// ("NAME=x,y,size,shown;..."), which platform/pad/touch_controller.cpp reads
// when the game starts. Positions are fractions of the screen, sizes
// fractions of its height, as in the game.
public class TouchLayoutActivity extends AppActivity {

    private static final class Elem {
        final String name, label;
        final int color;
        final boolean stick;
        final float dx, dy, dr; // defaults (touch_controller.cpp)
        float gx, gy, gr;       // the GameCube style's (kGcPlaces), else the same
        float x, y, r;
        boolean shown = true;

        Elem(String name, String label, int color, boolean stick, float x, float y, float r) {
            this.name = name;
            this.label = label;
            this.color = color;
            this.stick = stick;
            this.dx = x;
            this.dy = y;
            this.dr = r;
            gx = x;
            gy = y;
            gr = r;
            reset(false);
        }

        Elem gc(float x, float y, float r) {
            gx = x;
            gy = y;
            gr = r;
            return this;
        }

        void reset(boolean gamecube) {
            x = gamecube ? gx : dx;
            y = gamecube ? gy : dy;
            r = gamecube ? gr : dr;
            shown = true;
        }
    }

    private final List<Elem> mElems = new ArrayList<>();
    private Elem mSel;
    private EditorView mView;
    private TextView tvSel;
    private Button btnShow;
    private float mOpacity = 0.7f;
    // SMS_TOUCH_R_SLIDER: R+ is reached by sliding the finger from R out of a
    // circle of radius mSlide (a fraction of the screen height,
    // SMS_TOUCH_R_SLIDE), drawn dashed around R; drag its rim or use Size −/+
    // to change it. The R+ button itself is then not used
    private static final float SLIDE_DEFAULT = 0.06f;
    private boolean mSlider;
    private float mSlide = SLIDE_DEFAULT;
    private boolean mSlideSel; // the mark is selected
    // SMS_TOUCH_STYLE: the button style; each has its own layout
    // (SMS_TOUCH_LAYOUT, SMS_TOUCH_LAYOUT_GAMECUBE), switched in the toolbar
    private boolean mGc;
    private String mLayoutClassic, mLayoutGc;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        L.init(this);
        // the defaults of touch_controller.cpp
        mElems.add(new Elem("STICK", "STICK", 0xFFBFC9D4, true, 0.16f, 0.72f, 0.14f).gc(0.16f, 0.76f, 0.14f));
        mElems.add(new Elem("CSTICK", "C", 0xFFF1C40F, true, 0.61f, 0.76f, 0.11f));
        // and the GameCube style's places (kGcPlaces in touch.cpp)
        mElems.add(new Elem("A", "A", 0xFF2ECC71, false, 0.90f, 0.74f, 0.082f).gc(0.86f, 0.66f, 0.090f));
        mElems.add(new Elem("B", "B", 0xFFE74C3C, false, 0.80f, 0.82f, 0.072f).gc(0.775f, 0.76f, 0.060f));
        mElems.add(new Elem("X", "X", 0xFFF1C40F, false, 0.82f, 0.62f, 0.062f).gc(0.937f, 0.588f, 0.068f));
        mElems.add(new Elem("Y", "Y", 0xFF3498DB, false, 0.73f, 0.70f, 0.062f).gc(0.85f, 0.48f, 0.068f));
        mElems.add(new Elem("L", "L", 0xFF95A5A6, false, 0.08f, 0.16f, 0.072f).gc(0.12f, 0.366f, 0.075f));
        mElems.add(new Elem("R", "R", 0xFF2980B9, false, 0.91f, 0.52f, 0.072f).gc(0.877f, 0.366f, 0.075f));
        mElems.add(new Elem("RPLUS", "R+", 0xFF1F4E79, false, 0.82f, 0.44f, 0.062f).gc(0.907f, 0.246f, 0.075f));
        mElems.add(new Elem("Z", "Z", 0xFF9B59B6, false, 0.90f, 0.30f, 0.062f).gc(0.95f, 0.45f, 0.055f));
        mElems.add(new Elem("START", "START", 0xFFE67E22, false, 0.50f, 0.08f, 0.050f));
        mElems.add(new Elem("HUD", "HIDE", 0xFF7F8C8D, false, 0.40f, 0.08f, 0.045f)); // hides the other controls
        mElems.add(new Elem("MENU", "MENU", 0xFF455A64, false, 0.60f, 0.08f, 0.050f));
        mElems.add(new Elem("SAVE", "SAVE", 0xFF16A085, false, 0.74f, 0.08f, 0.042f)); // quick save
        mElems.add(new Elem("LOAD", "LOAD", 0xFFD35400, false, 0.83f, 0.08f, 0.042f)); // quick load (held)
        load();
        buildUi();
        hideSystemUi();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    // the whole display, as the game has it, so positions match
    @Override protected boolean keepClearOfSystemUi() { return false; }

    // the same full screen as the game, so positions match
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

    // --- settings.txt -------------------------------------------------------

    private void load() {
        Map<String, String> m = Settings.read(this);
        try {
            mOpacity = Math.max(0.05f, Math.min(1f, Float.parseFloat(m.getOrDefault("SMS_TOUCH_OPACITY", "70")) / 100f));
        } catch (NumberFormatException ignored) {}
        mSlider = !"0".equals(m.getOrDefault("SMS_TOUCH_R_SLIDER", "1"));
        try {
            mSlide = clamp(Float.parseFloat(m.getOrDefault("SMS_TOUCH_R_SLIDE", Float.toString(SLIDE_DEFAULT))), 0.02f, 0.4f);
        } catch (NumberFormatException ignored) {}
        mGc = !"classic".equals(m.get("SMS_TOUCH_STYLE"));  // GameCube unless Classic was chosen
        mLayoutClassic = m.get("SMS_TOUCH_LAYOUT");
        mLayoutGc = m.get("SMS_TOUCH_LAYOUT_GAMECUBE");
        apply(mGc ? mLayoutGc : mLayoutClassic);
    }

    // The style's defaults, then the layout over them
    private void apply(String layout) {
        resetAll();
        if (layout == null) return;
        for (String item : layout.split(";")) {
            int eq = item.indexOf('=');
            if (eq <= 0) continue;
            String name = item.substring(0, eq).trim();
            String[] v = item.substring(eq + 1).split(",");
            if (v.length < 3) continue;
            for (Elem e : mElems) {
                if (!e.name.equals(name)) continue;
                try {
                    e.x = clamp(Float.parseFloat(v[0]), 0, 1);
                    e.y = clamp(Float.parseFloat(v[1]), 0, 1);
                    e.r = clamp(Float.parseFloat(v[2]), 0.02f, 0.4f);
                    e.shown = v.length < 4 || !v[3].trim().equals("0");
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    // The style's defaults. Without the R+ slider the GameCube style has R+ in
    // R's place, by Z, and R above it, square to their flat side (touch.cpp too)
    private void resetAll() {
        for (Elem e : mElems) e.reset(mGc);
        if (mGc && !mSlider) swapR();
    }

    private void swapR() {
        Elem r = elem("R"), p = elem("RPLUS");
        float x = r.x, y = r.y;
        r.x = p.x;
        r.y = p.y;
        p.x = x;
        p.y = y;
    }

    private String layoutString() {
        StringBuilder sb = new StringBuilder();
        for (Elem e : mElems) {
            if (sb.length() > 0) sb.append(';');
            sb.append(String.format(Locale.ROOT, "%s=%.3f,%.3f,%.3f,%d", e.name, e.x, e.y, e.r, e.shown ? 1 : 0));
        }
        return sb.toString();
    }

    // Each style keeps its own layout; the one shown is saved, and its style chosen
    private void save() {
        if (mGc) mLayoutGc = layoutString();
        else mLayoutClassic = layoutString();
        Map<String, String> m = Settings.read(this);
        if (mLayoutClassic != null) m.put("SMS_TOUCH_LAYOUT", mLayoutClassic);
        if (mLayoutGc != null) m.put("SMS_TOUCH_LAYOUT_GAMECUBE", mLayoutGc);
        m.put("SMS_TOUCH_STYLE", mGc ? "gamecube" : "classic");
        m.put("SMS_TOUCH_OPACITY", Integer.toString(Math.round(mOpacity * 100)));
        m.put("SMS_TOUCH_R_SLIDER", mSlider ? "1" : "0");
        m.put("SMS_TOUCH_R_SLIDE", String.format(Locale.ROOT, "%.3f", mSlide));
        Settings.write(this, m);
        Toast.makeText(this, t("Touch layout saved. In the game it applies as soon as you go back to it."), Toast.LENGTH_LONG).show();
        finish();
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    // --- the GameCube style's shapes (GcShapes, from tools/touch-gamecube) ----

    private static final java.util.Map<Integer, Path> sGcPaths = new java.util.HashMap<>();

    private static int gcIndex(String name) {
        for (int i = 0; i < GcShapes.NAMES.length; i++) if (GcShapes.NAMES[i].equals(name)) return i;
        return -1;
    }

    // the shape an element has in the GameCube style, or -1 (drawn as before)
    private static int gcShape(String elem) {
        switch (elem) {
            case "STICK": return gcIndex("GATE");
            case "CSTICK": return gcIndex("CGATE");
            case "A": case "B": case "X": case "Y": case "Z": return gcIndex(elem);
            case "L": return gcIndex("TRIGGER_L");
            case "R": return gcIndex("TRIGGER_R");
            case "RPLUS": return gcIndex("TRIGGER_R");  // a second R
            default: return -1;
        }
    }

    private static int gcColor(String elem) {
        switch (elem) {
            case "A": return 0x2ECC71;
            case "B": return 0xE74C3C;
            case "Z": return 0x9B59B6;
            case "CSTICK": return 0xFFD21F;
            case "STICK": return 0xFFFFFF;
            default: return 0xD0D4DA;
        }
    }

    // the shape at (cx, cy), unit pixels for its [-1, 1]
    // L, R and R+ turned to fall as Z does (touch.cpp, kGcTilt), in degrees
    private static float gcTilt(String elem) {
        switch (elem) {
            case "L": return -(28.1f - 21.6f);
            case "R": return 28.1f - 21.4f;
            case "RPLUS": return 28.1f - 21.4f;  // R pressed in, in R's frame
            default: return 0;
        }
    }

    private static Path gcPath(int idx, float cx, float cy, float unit) { return gcPath(idx, cx, cy, unit, 0); }

    private static Path gcPath(int idx, float cx, float cy, float unit, float deg) {
        Path base = sGcPaths.get(idx);
        if (base == null) {
            base = new Path();
            base.setFillType(Path.FillType.EVEN_ODD);
            for (String ring : GcShapes.RINGS[idx].split(";")) {
                String[] pts = ring.trim().split(" ");
                for (int k = 0; k < pts.length; k++) {
                    String[] xy = pts[k].split(",");
                    float x = Float.parseFloat(xy[0]), y = Float.parseFloat(xy[1]);
                    if (k == 0) base.moveTo(x, y);
                    else base.lineTo(x, y);
                }
                base.close();
            }
            sGcPaths.put(idx, base);
        }
        Path out = new Path();
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(unit, unit);
        m.postRotate(deg);
        m.postTranslate(cx, cy);
        base.transform(m, out);
        return out;
    }

    // --- toolbar ------------------------------------------------------------

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private Button tool(String text, boolean main) {
        Button b = new Button(this);
        b.setText(t(text));
        b.setAllCaps(true);
        b.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        b.setTextSize(14);
        b.setStateListAnimator(null);
        b.setBackgroundResource(R.drawable.dl_row);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(14), dp(6), dp(14), dp(6));
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
        mView = new EditorView(this);
        root.addView(mView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundResource(R.drawable.dl_panel);
        bar.setPadding(dp(10), dp(6), dp(10), dp(6));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        blp.topMargin = Math.round(getResources().getDisplayMetrics().heightPixels * 0.27f); // between the top row and the sticks
        root.addView(bar, blp);

        tvSel = new TextView(this);
        tvSel.setTextColor(0xFFFFC531);
        tvSel.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        tvSel.setTextSize(16);
        tvSel.setPadding(dp(8), 0, dp(12), 0);
        tvSel.setMinWidth(dp(130));
        bar.addView(tvSel);

        Button smaller = tool("Size −", false);
        Button bigger = tool("Size +", false);
        btnShow = tool("Hide", false);
        Button slider = tool(mSlider ? "R+ slider: On" : "R+ slider: Off", false);
        Button style = tool(mGc ? "GCube" : "Classic", false);
        Button reset = tool("Reset all", false);
        Button cancel = tool("Cancel", false);
        Button save = tool("Save", true);
        for (Button b : new Button[] {style, smaller, bigger, btnShow, slider, reset, cancel, save}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(4), 0, dp(4), 0);
            bar.addView(b, lp);
        }
        smaller.setOnClickListener(v -> resize(1f / 1.1f));
        bigger.setOnClickListener(v -> resize(1.1f));
        btnShow.setOnClickListener(v -> {
            if (mSel == null || mSlideSel) return;
            mSel.shown = !mSel.shown;
            updateToolbar();
            mView.invalidate();
        });
        slider.setOnClickListener(v -> {  // R alone, sliding out of its circle to R+
            mSlider = !mSlider;
            slider.setText(t(mSlider ? "R+ slider: On" : "R+ slider: Off"));
            if (!mSlider && mSlideSel) {
                mSlideSel = false;
                mSel = elem("R");
            }
            if (mSlider && mSel != null && mSel.name.equals("RPLUS")) mSel = elem("R");
            if (mGc) swapR();  // R+ takes R's place by Z, or gives it back
            updateToolbar();
            mView.invalidate();
        });
        style.setOnClickListener(v -> {  // the other style, with its own layout
            if (mGc) mLayoutGc = layoutString();
            else mLayoutClassic = layoutString();
            mGc = !mGc;
            apply(mGc ? mLayoutGc : mLayoutClassic);
            style.setText(t(mGc ? "GCube" : "Classic"));
            updateToolbar();
            mView.invalidate();
        });
        reset.setOnClickListener(v -> {
            resetAll();
            mSlide = SLIDE_DEFAULT;
            updateToolbar();
            mView.invalidate();
        });
        cancel.setOnClickListener(v -> finish());
        save.setOnClickListener(v -> save());


        // opacity, shown live on the controls
        LinearLayout op = new LinearLayout(this);
        op.setOrientation(LinearLayout.HORIZONTAL);
        op.setGravity(Gravity.CENTER_VERTICAL);
        TextView opl = new TextView(this);
        opl.setTextColor(0xFFF4F8FF);
        opl.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        opl.setTextSize(14);
        opl.setPadding(dp(8), 0, dp(8), 0);
        opl.setMinWidth(dp(118));
        op.addView(opl);
        android.widget.SeekBar seek = new android.widget.SeekBar(this);
        seek.setMax(90); // 10..100 %
        seek.setProgress(Math.round(mOpacity * 100) - 10);
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFFFC531));
        seek.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFFFB020));
        seek.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF2A5A85));
        op.addView(seek, new LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.WRAP_CONTENT));
        Runnable showOp = () -> opl.setText(t("Opacity %d%%", Math.round(mOpacity * 100)));
        showOp.run();
        seek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar b, int p, boolean user) {
                mOpacity = (p + 10) / 100f;
                showOp.run();
                mView.invalidate();
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar b) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar b) {}
        });
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        ((ViewGroup) bar.getParent()).removeView(bar);
        col.setBackgroundResource(R.drawable.dl_panel);
        bar.setBackground(null);
        col.addView(bar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(op, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.setPadding(0, 0, 0, dp(6));
        root.addView(col, blp);
        tvSel.setOnTouchListener(dragToolbar(col));

        mSel = mElems.get(2); // A
        updateToolbar();
        setContentView(root);
    }

    // drag the toolbar out of the way by its label
    private View.OnTouchListener dragToolbar(View panel) {
        return new View.OnTouchListener() {
            float sx, sy, bx, by;
            @Override public boolean onTouch(View v, MotionEvent ev) {
                if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    sx = ev.getRawX(); sy = ev.getRawY(); bx = panel.getTranslationX(); by = panel.getTranslationY();
                } else if (ev.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    panel.setTranslationX(bx + ev.getRawX() - sx);
                    panel.setTranslationY(by + ev.getRawY() - sy);
                }
                return true;
            }
        };
    }

    private void resize(float f) {
        if (mSlideSel) {
            mSlide = clamp(mSlide * f, 0.02f, 0.4f);
            updateToolbar();
            mView.invalidate();
            return;
        }
        if (mSel == null) return;
        mSel.r = clamp(mSel.r * f, 0.02f, 0.4f);
        updateToolbar();
        mView.invalidate();
    }

    private void updateToolbar() {
        if (mSlideSel) {
            tvSel.setText("✥ R+ ◌  " + Math.round(mSlide / SLIDE_DEFAULT * 100) + "%");
            btnShow.setText(t("Hide"));
            return;
        }
        if (mSel == null) {
            tvSel.setText(t("Tap a control"));
            return;
        }
        tvSel.setText("✥ " + mSel.label + "  " + Math.round(mSel.r / mSel.dr * 100) + "%");
        btnShow.setText(t(mSel.shown ? "Hide" : "Show"));
    }

    // --- the editing surface -------------------------------------------------

    private Elem elem(String name) {
        for (Elem e : mElems) if (e.name.equals(name)) return e;
        return null;
    }

    // the R+ button has no use while R slides to R+
    private boolean unused(Elem e) { return mSlider && e.name.equals("RPLUS"); }

    private class EditorView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ScaleGestureDetector scale;
        private Elem drag;
        private boolean dragSlide;
        private float offX, offY;

        EditorView(Context c) {
            super(c);
            scale = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector d) {
                    if (mSlideSel) {
                        mSlide = clamp(mSlide * d.getScaleFactor(), 0.02f, 0.4f);
                        updateToolbar();
                        invalidate();
                    } else if (mSel != null) {
                        mSel.r = clamp(mSel.r * d.getScaleFactor(), 0.02f, 0.4f);
                        updateToolbar();
                        invalidate();
                    }
                    return true;
                }
            });
        }

        private Elem hit(float px, float py) {
            float w = getWidth(), h = getHeight();
            Elem best = null;
            float bestD = Float.MAX_VALUE;
            for (Elem e : mElems) {
                if (unused(e)) continue;
                float d = (float) Math.hypot(px - e.x * w, py - e.y * h);
                float reach = Math.max(e.r * h * 1.2f, dp(28));
                if (d <= reach && d < bestD) {
                    best = e;
                    bestD = d;
                }
            }
            return best;
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            scale.onTouchEvent(ev);
            float w = getWidth(), h = getHeight();
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    Elem r = elem("R");
                    if (mSlider && r != null && r.shown) {  // the R+ circle's rim first
                        float d = (float) Math.hypot(ev.getX() - r.x * w, ev.getY() - r.y * h);
                        if (Math.abs(d - mSlide * h) <= dp(18) && d > r.r * h * 0.6f) {
                            mSlideSel = true;
                            mSel = null;
                            dragSlide = true;
                            updateToolbar();
                            invalidate();
                            return true;
                        }
                    }
                    Elem e = hit(ev.getX(), ev.getY());
                    if (e != null) {
                        mSlideSel = false;
                        mSel = e;
                        drag = e;
                        offX = e.x * w - ev.getX();
                        offY = e.y * h - ev.getY();
                        updateToolbar();
                        invalidate();
                    }
                    return true;
                }
                case MotionEvent.ACTION_MOVE:
                    if (dragSlide && ev.getPointerCount() == 1 && !scale.isInProgress()) {
                        Elem r = elem("R");
                        if (r != null)
                            mSlide = clamp((float) Math.hypot(ev.getX() - r.x * w, ev.getY() - r.y * h) / h, 0.02f, 0.4f);
                        updateToolbar();
                        invalidate();
                    }
                    if (drag != null && ev.getPointerCount() == 1 && !scale.isInProgress()) {
                        drag.x = clamp((ev.getX() + offX) / w, 0, 1);
                        drag.y = clamp((ev.getY() + offY) / h, 0, 1);
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    drag = null; // two fingers: resize, not move
                    dragSlide = false;
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    drag = null;
                    dragSlide = false;
                    return true;
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            c.drawColor(0xFF0A1B30);
            // a light grid to line things up
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1);
            p.setColor(0x222FB8C9);
            for (int i = 1; i < 10; i++) c.drawLine(w * i / 10f, 0, w * i / 10f, h, p);
            for (int i = 1; i < 6; i++) c.drawLine(0, h * i / 6f, w, h * i / 6f, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x668FB4D4);
            p.setTextSize(dp(15));
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
            c.drawText(t("Drag the controls. Pinch or use Size −/+ to resize. Drag the toolbar by its label."),
                w / 2, h - dp(14), p);

            for (Elem e : mElems) {
                if (unused(e)) continue;
                // drawn as big as the game draws them (touch_controller.cpp: a
                // button's circle is 0.92 of its radius, a stick's 0.94)
                float cx = e.x * w, cy = e.y * h, r = e.r * h * (e.stick ? 0.94f : 0.92f);
                int a = e.shown ? Math.round(225 * Math.min(1f, mOpacity / 0.7f)) : 60;
                p.setPathEffect(e.shown ? null : new DashPathEffect(new float[] {dp(6), dp(5)}, 0));
                if (mGc && drawGc(c, p, e, cx, cy, e.r * h, a)) {
                    p.setPathEffect(null);
                } else if (e.stick) {
                    p.setStyle(Paint.Style.FILL);
                    p.setColor((e.color & 0x00FFFFFF) | ((a / 4) << 24));
                    c.drawCircle(cx, cy, r, p);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(dp(3));
                    p.setColor((e.color & 0x00FFFFFF) | (a << 24));
                    c.drawCircle(cx, cy, r, p);
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(cx, cy, r * 0.42f, p);
                } else {
                    p.setStyle(Paint.Style.FILL);
                    p.setColor((e.color & 0x00FFFFFF) | (a << 24));
                    c.drawCircle(cx, cy, r, p);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(dp(2));
                    p.setColor((0xFFFFFF) | (a << 24));
                    c.drawCircle(cx, cy, r, p);
                }
                p.setPathEffect(null);
                p.setStyle(Paint.Style.FILL);
                if (mGc && gcShape(e.name) >= 0) {
                    drawSelection(c, p, e, cx, cy, r);
                    continue;
                }
                p.setColor(e.shown ? 0xFFFFFFFF : 0x88FFFFFF);
                p.setTextSize(Math.max(dp(11), r * (e.label.length() > 2 ? 0.55f : 0.8f)));
                p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
                p.setTextAlign(Paint.Align.CENTER);
                float ty = cy - (p.descent() + p.ascent()) / 2;
                c.drawText(e.stick && e.name.equals("STICK") ? "" : e.label, cx, ty, p);
                drawSelection(c, p, e, cx, cy, r);
            }
            Elem rb = elem("R");
            if (mSlider && rb != null && rb.shown) drawSlideMark(c, rb, w, h);
        }

        private void drawSelection(Canvas c, Paint p, Elem e, float cx, float cy, float r) {
            if (e != mSel) return;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(3));
            p.setColor(0xFFFFC531);
            c.drawCircle(cx, cy, r + dp(6), p);
        }

        // The GameCube style as the game draws it (touch.cpp, makeGc): the
        // shape filled in the button's colour with its letter; a stick's gate
        // only as an outline. Size: the button's picture is 2r, the shape 0.94r.
        private boolean drawGc(Canvas c, Paint p, Elem e, float cx, float cy, float rPx, int alpha) {
            int idx = gcShape(e.name);
            if (idx < 0) return false;
            float unit = 0.94f * rPx;
            boolean stick = e.name.equals("STICK") || e.name.equals("CSTICK");
            int rgb = gcColor(e.name);
            p.setPathEffect(e.shown ? null : new DashPathEffect(new float[] {dp(6), dp(5)}, 0));
            if (stick) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x0A1420 | ((alpha * 35 / 100) << 24));
                c.drawPath(gcPath(idx, cx, cy, unit), p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(dp(2), 2 * rPx * 0.05f));
                p.setColor(rgb | (alpha << 24));
                c.drawPath(gcPath(idx, cx, cy, unit), p);
                // the knob in the middle, at rest
                int knob = gcIndex(e.name.equals("STICK") ? "KNOB" : "CKNOB");
                float ku = unit * (e.name.equals("STICK") ? 0.44f : 0.46f);
                p.setPathEffect(null);
                p.setStyle(Paint.Style.FILL);
                c.drawPath(gcPath(knob, cx, cy, ku), p);
                if (e.name.equals("CSTICK")) gcLabel(c, p, knob, "C", cx, cy, ku, 0.65f, alpha, 0);
                return true;
            }
            float deg = gcTilt(e.name);
            p.setStyle(Paint.Style.FILL);
            p.setColor(rgb | ((alpha * 92 / 100) << 24));
            c.drawPath(gcPath(idx, cx, cy, unit, deg), p);
            float scale = e.name.equals("A") || e.name.equals("B") ? 0.65f
                : e.name.equals("L") || e.name.equals("R") || e.name.equals("RPLUS") ? 0.75f : 1f;
            gcLabel(c, p, idx, e.label, cx, cy, unit, scale, alpha, deg);
            return true;
        }

        // The letter at the shape's widest place, about as large as touch.cpp draws it
        private void gcLabel(Canvas c, Paint p, int idx, String label, float cx, float cy, float unit, float scale, int alpha,
                             float deg) {
            float[] pole = GcShapes.POLES[idx];
            float gs = Math.min(pole[2] * unit * (label.length() > 1 ? 0.5f : 0.62f), unit / 0.94f * 0.6f) * scale;
            p.setPathEffect(null);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x14202C | (alpha << 24));
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(2 * gs / 0.72f);
            // the shape's widest place, turned with it (the letter stays upright)
            double a = Math.toRadians(deg);
            float px = (float) (pole[0] * Math.cos(a) - pole[1] * Math.sin(a));
            float py = (float) (pole[0] * Math.sin(a) + pole[1] * Math.cos(a));
            float x = cx + px * unit, y = cy + py * unit;
            c.drawText(label, x, y - (p.descent() + p.ascent()) / 2, p);
        }

        // Where R becomes R+: a dashed circle around R, labelled R+ at its top.
        private void drawSlideMark(Canvas c, Elem rb, float w, float h) {
            float cx = rb.x * w, cy = rb.y * h, rad = mSlide * h;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(mSlideSel ? 3 : 2));
            p.setColor(mSlideSel ? 0xFFFFC531 : 0xCCFFFFFF);
            p.setPathEffect(new DashPathEffect(new float[] {dp(6), dp(5)}, 0));
            c.drawCircle(cx, cy, rad, p);
            p.setPathEffect(null);
            p.setStyle(Paint.Style.FILL);
            p.setColor(mSlideSel ? 0xFFFFC531 : 0xDDFFFFFF);
            p.setTextSize(dp(14));
            p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText("R+", cx, cy - rad - dp(6), p);
        }
    }
}
