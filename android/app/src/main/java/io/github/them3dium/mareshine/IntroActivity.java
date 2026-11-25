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
    static final String EXTRA_BEFORE = "intro_before";  // the intro this one came right after

    // one page: its text, its picture (drawn every frame, ms since the page
    // came), and its choices (none: Next)
    private static final class Page {
        final String title, text;
        final Picture picture;
        final List<String> choices = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        boolean asks;  // a choice page: only when the intro asks
        boolean back;  // a Back button, to the choice before
        String link;   // a button in the middle of the picture, and what it does
        Runnable linkAction;
        String next;   // the orange button's own word (null: Next, or Got it on the last page)
        boolean logo;  // the winter logo with its lights as the picture
        boolean middle; // the text in the middle of the screen, see-through (the fireworks are above it)

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
    private Button mLink;
    private TextView mTitle, mText;
    private Button mMain;      // the page's orange button, lit now and then
    private final Runnable mPulse = this::pulse;
    private String mStyle, mSlider;          // the choice made (null: as before)
    private String mOrigStyle, mOrigSlider;  // the settings before it (null: not set)

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        L.init(this);
        mId = getIntent().getStringExtra(EXTRA_ID);
        if (mId == null) { finish(); return; }
        mAsks = Intro.asks(this, mId) || Intro.WELCOME.equals(mId) || Intro.WINTER_UPDATE.equals(mId);
        // the winter event's music plays behind these two, once it is on
        mMusic = Intro.WINTER_UPDATE.equals(mId) || Intro.isNewYear(mId);
        pages();
        for (int i = mPages.size() - 1; i >= 0; i--) if (mPages.get(i).asks && !mAsks) mPages.remove(i);
        if (mPages.isEmpty()) { finishIntro(); return; }
        if (getIntent().getStringExtra(EXTRA_BEFORE) != null && mPages.get(0).choices.isEmpty()) mPages.get(0).back = true;
        buildUi();
        hideSystemUi();
        show(0);
        // faded in, softly
        View root = findViewById(android.R.id.content);
        root.setAlpha(0f);
        root.animate().alpha(1f).setStartDelay(150).setDuration(700).start();
    }

    private boolean mMusic;
    private SnowView mIntroSnow;

    @Override
    protected void onResume() {
        super.onResume();
        if (mMusic) {
            Winter.screenShown(this);
            updateIntroSnow();
        }
    }

    // It snows over these intros too while the event's snow is on (AppActivity
    // leaves it out on screens laid out like the game's, as this one is).
    private void updateIntroSnow() {
        boolean snow = mMusic && Winter.snow(Settings.read(this));
        if (snow && mIntroSnow == null) {
            mIntroSnow = new SnowView(this);
            ((ViewGroup) getWindow().getDecorView()).addView(mIntroSnow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else if (!snow && mIntroSnow != null) {
            ((ViewGroup) mIntroSnow.getParent()).removeView(mIntroSnow);
            mIntroSnow = null;
        }
    }

    // a swipe is a gust of wind for the snow
    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (mIntroSnow != null) mIntroSnow.touch(ev);
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mMusic) Winter.screenHidden();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    // a controller moves along the buttons and presses them (AppActivity's scheme)
    @Override protected boolean usesPadNav() { return true; }

    @Override
    protected List<View> padItems() {
        List<View> items = new ArrayList<>();
        if (mLink != null && mLink.getVisibility() == View.VISIBLE) items.add(mLink);
        if (mButtons != null) for (int i = 0; i < mButtons.getChildCount(); i++) items.add(mButtons.getChildAt(i));
        return items;
    }

    // a controller starts on the orange button (Next, Got it, a choice's first)
    @Override protected View padDefault() { return mMain; }

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
            case Intro.MOD_MANAGER: modManager(); break;
            case Intro.SKIP_MOVIES: skipMovies(); break;
            case Intro.GYRO_AIMING: gyroAiming(); break;
            case Intro.SWAP_AB: swapAb(); break;
            case Intro.TOUR_AGAIN: tourAgain(); break;
            case Intro.WELCOME: welcome(); break;
            case Intro.WINTER_UPDATE: winterUpdate(); break;
            default:
                if (Intro.isNewYear(mId)) newYear();
                break;
        }
    }

    // --- the mod manager ------------------------------------------------------

    private android.graphics.Bitmap mEclipseDisc, mSunburnDisc;  // loaded once
    private int mTourAfter = -1;  // the page Tour's steps on the real screens follow (-1: none)
    private String[] mTourSteps = Tour.MODS;
    private boolean mTouring;
    private static final int REQUEST_TOUR = 1;

    private android.graphics.Bitmap asset(String path) {
        try (java.io.InputStream in = getAssets().open(path)) {
            return android.graphics.BitmapFactory.decodeStream(in);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private void modManager() {
        mEclipseDisc = asset(EclipseMod.DISC_ASSET);
        mSunburnDisc = asset("mods/sunburn-disc.png");
        mPages.add(new Page(t("New: Mods"),
            t("Super Mario Sunburn (SMSB) and Super Mario Eclipse (SME) now run in Mareshine, made from your own "
                + "disc. SMSB in every language of the app, SME in English and German for now (more to come)."),
            this::drawDiscs));
        Page menu = new Page(t("The mod menu"),
            t("Everything about mods is in the mod menu: install mods from SEARCH MODS, or make your own and import "
                + "it. The next pages show you around, on the app's own screens."),
            this::drawPreviews);
        menu.back = true;
        mPages.add(menu);
        // then the app's own screens, with an example mod (Tour)
        mTourAfter = mPages.size() - 1;
        Page own = new Page(t("Make your own mods"),
            t("Change stages, textures or music and share it as a mod. The guide on GitHub shows how, step by step."),
            (c, p, w, h, ms) -> TouchDraw.background(c, p, w, h));
        own.link = t("Open the guide");
        own.linkAction = () -> choice(t("Open GitHub?"), t("The guide to making mods opens in your browser."),
            new String[] {t("Open")}, new Runnable[] {() -> {
                try {
                    startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(ModsActivity.GUIDE)));
                } catch (android.content.ActivityNotFoundException e) {
                    choice(t("No browser"), ModsActivity.GUIDE, new String[] {t("OK")}, new Runnable[] {null});
                }
            }});
        own.back = true;  // to the steps' last
        mPages.add(own);
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

    private void skipMovies() {
        mPages.add(new Page(t("New: skip the movies"),
            t("Seen them all? Settings, VIDEO, Cutscene settings, Skip movies: the game's movies end at once. Your disc and your HD cutscenes stay as they are, and you can switch it back at any time."),
            this::drawSkip));
    }

    // two triangles and a bar: the sign for skipping ahead
    private void drawSkip(Canvas c, Paint p, float w, float h, long ms) {
        float s = Math.min(w, h) * 0.22f, x = w / 2f - s * 1.1f, y = h * 0.62f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFF4C430);
        for (int i = 0; i < 2; i++) {
            android.graphics.Path t = new android.graphics.Path();
            t.moveTo(x + i * s, y - s * 0.6f);
            t.lineTo(x + i * s + s, y);
            t.lineTo(x + i * s, y + s * 0.6f);
            t.close();
            c.drawPath(t, p);
        }
        c.drawRect(x + 2 * s + s * 0.1f, y - s * 0.6f, x + 2 * s + s * 0.3f, y + s * 0.6f, p);
    }

    private void gyroAiming() {
        mPages.add(new Page(t("New: gyro aiming"), t("Turn your phone to aim FLUDD with R+ or to look around with Y. Your stick still works as well."), this::drawGyroPhone));
        mTourAfter = 0;
        mTourSteps = Tour.GYRO;
    }

    private void welcome() {
        Page page = new Page(t("Welcome to Mareshine!"), t("Mareshine can show you its features since version 2.1 in a short tour. Do you want to see all of it, only the newest features, or skip it? You can watch the tour at any time under OTHER, Tour."), this::drawLogo);
        page.choice(t("Complete tour"), () -> chooseTours(true, false));
        page.choice(t("Newest features"), () -> chooseTours(false, true));
        page.choice(t("Skip for now"), () -> chooseTours(false, false));
        mPages.add(page);
    }

    private void chooseTours(boolean all, boolean newest) {
        Intro.choose(this, all, newest);
        finishIntro();
    }

    private void tourAgain() {
        mPages.add(new Page(t("Missed a tour?"), t("You can watch every tour again at any time."), this::drawLogo));
        mTourAfter = 0;
        mTourSteps = Tour.AGAIN;
    }

    // --- the winter update (2.2) ----------------------------------------------

    private WinterLogoView mWinterLogo;
    private android.graphics.Bitmap mTfDisc;

    private static final String WINTER_TEXT = "Until 6 January winter moves into Mareshine, and it gets Christmassy. "
        + "During the winter event snow falls, the Mareshine logo and parts of the menu light up, and three pieces of "
        + "music made exclusively for the event play, inspired by the instruments of Super Mario Sunshine. All of it can be "
        + "switched off as you like.";

    private void winterUpdate() {
        // the event's question first, so the music plays only for those who want it
        if (Winter.needsAsking(this)) {
            Page ask = new Page(t("❄️ It is getting frosty in Mareshine"),
                t(WINTER_TEXT) + "\n\n" + t("Turn it on?"),
                (c, p, w, h, ms) -> { });
            ask.logo = true;
            ask.choice(t("Turn on"), () -> answerWinter(true)).choice(t("No, thanks"), () -> answerWinter(false));
            mPages.add(ask);
        } else if (Winter.inSeason(this)) {
            // already answered (a test build, or the question came before this intro): the event all the same
            Page event = new Page(t("❄️ It is getting frosty in Mareshine"),
                t(WINTER_TEXT),
                (c, p, w, h, ms) -> { });
            event.logo = true;
            mPages.add(event);
        }
        mEclipseDisc = asset(EclipseMod.DISC_ASSET);
        mSunburnDisc = asset("mods/sunburn-disc.png");
        mTfDisc = asset("mods/tropicalfreeze-disc.png");
        if (Mods.MENU && mTfDisc != null) {
            Page gift = new Page(t("It stays frosty"),
                t("We have a present for you. Open it!"),
                (c, p, w, h, ms) -> drawGift(c, p, w, h, ms, false));
            gift.next = t("Open");
            gift.back = !mPages.isEmpty() && mPages.get(0).choices.isEmpty();
            mPages.add(gift);
            Page tf = new Page("Super Mario Tropical Freeze!",
                t("The mod by Super Mario Elements now runs in Mareshine: Isle Delfino frozen over, with icy "
                    + "floors, fog and new music. You find it in the mod menu under SEARCH MODS."),
                (c, p, w, h, ms) -> drawGift(c, p, w, h, ms, true));
            tf.back = true;
            mPages.add(tf);
            Page ach = new Page(t("Achievements for the mods"),
                t("Super Mario Sunburn, Super Mario Eclipse and Super Mario Tropical Freeze now have achievements of their own."),
                this::drawTrophy);
            ach.back = true;
            mPages.add(ach);
        }
        if (AppActivity.isTouch(this)) {
            Page stick = new Page(t("A more precise touch stick"),
                t("The touch stick is more precise now: walking slowly and running fast now flow into each other. The "
                    + "stick you hold also grows, which gives your thumb more room.\n\nAt players' "
                    + "request: the stick's centre can now be fixed instead of following your thumb. "
                    + "Settings → Input → Touch behavior → Fixed stick centre."),
                this::drawStick);
            stick.back = !mPages.isEmpty();
            mPages.add(stick);
        }
        Page speed = new Page(t("More speed on weaker devices"),
            t("With Mareshine 2.2.0 we have looked after weaker devices. They are often weak on a single core but mostly "
                + "have several CPU cores, and the performance mode uses exactly those for Mareshine. On a Moto G15 "
                + "this gives nearly steady 30 FPS at 1.5 times the resolution, instead of about 20.\n\nMareshine "
                + "suggests the performance mode by itself on devices it suits. Otherwise turn it on under "
                + "Settings → Video → Performance mode."),
            this::drawGauge);
        speed.back = !mPages.isEmpty();
        mPages.add(speed);
        Page fixes = new Page(t("Steadier than before"),
            t("Mali-G52 devices with an outdated driver version crashed together with ANGLE. This is a bug on older "
                + "Android versions, which Mareshine now gets round with an exclusive workaround. Other crashes and "
                + "bugs are fixed, and the freeze report no longer comes up by mistake.\n\nOur test farm has grown, "
                + "and more devices will follow, to give you the best possible performance on every device."),
            this::drawCheck);
        fixes.back = !mPages.isEmpty();
        mPages.add(fixes);
    }

    private void answerWinter(boolean on) {
        Winter.answer(this, on);
        Winter.updateMusic(this);
        updateIntroSnow();
        show(mIndex + 1);
    }

    private static final int GIFT_RED = 0xFFD42A2A, GIFT_RED_LIT = 0xFFE64545, GOLD = 0xFFFFC531, GOLD_DARK = 0xFFDD9A12;

    // A red present with a golden ribbon and bow. Closed, it shakes now and
    // then; opening, the lid flies off, a golden beam rises and the mod's disc
    // comes up in it.
    private void drawGift(Canvas c, Paint p, float w, float h, long ms, boolean open) {
        float top = areaTop(h), bottom = areaBottom(h), room = bottom - top;
        float bh = room * 0.56f, bw = bh * 1.2f, cx = w / 2f, by = bottom - dp(4);
        float bodyTop = by - bh * 0.74f;
        // closed: a shake every now and then; opening: a stronger one first
        float shake;
        if (open) {
            shake = ms < 450 ? (float) Math.sin(ms / 450.0 * 8 * Math.PI) * 6f * (1 - ms / 450f) : 0f;
        } else {
            float t = (ms % 2400) / 2400f;
            shake = t < 0.25f ? (float) Math.sin(t / 0.25 * 6 * Math.PI) * 5f * (1 - t / 0.25f) : 0f;
        }
        float lid = open ? Math.max(0f, Math.min(1f, (ms - 450) / 650f)) : 0f;  // the lid's way off
        float beam = open ? Math.max(0f, Math.min(1f, (ms - 500) / 700f)) : 0f;
        float rise = open ? Math.max(0f, Math.min(1f, (ms - 850) / 1100f)) : 0f;

        if (beam > 0) {
            // the beam: narrow at the box, wide at the top, breathing a little
            float spread = bw * (1.5f + 0.06f * (float) Math.sin(ms / 700.0));
            android.graphics.Path b = new android.graphics.Path();
            b.moveTo(cx - bw * 0.42f, bodyTop);
            b.lineTo(cx - spread, 0);
            b.lineTo(cx + spread, 0);
            b.lineTo(cx + bw * 0.42f, bodyTop);
            b.close();
            p.setStyle(Paint.Style.FILL);
            p.setShader(new android.graphics.LinearGradient(0, bodyTop, 0, 0,
                0xFFD95A | (Math.round(235 * beam) << 24), 0x00FFD95A, android.graphics.Shader.TileMode.CLAMP));
            c.drawPath(b, p);
            // its bright core
            android.graphics.Path core = new android.graphics.Path();
            core.moveTo(cx - bw * 0.2f, bodyTop);
            core.lineTo(cx - spread * 0.45f, 0);
            core.lineTo(cx + spread * 0.45f, 0);
            core.lineTo(cx + bw * 0.2f, bodyTop);
            core.close();
            p.setShader(new android.graphics.LinearGradient(0, bodyTop, 0, 0,
                0xFFF6C8 | (Math.round(170 * beam) << 24), 0x00FFF6C8, android.graphics.Shader.TileMode.CLAMP));
            c.drawPath(core, p);
            p.setShader(null);
        }
        if (rise > 0 && mTfDisc != null) {
            // the disc, up out of the box, then floating and turning
            float r = Math.min(room * 0.25f, dp(64));
            float restY = top + r + dp(2), fromY = bodyTop + r * 0.2f;
            float e = backOut(rise), y = fromY + (restY - fromY) * e;
            if (rise >= 1f) y += (float) Math.sin((ms - 1950) / 800.0) * dp(4);
            float size = r * (0.35f + 0.65f * Math.min(1f, e));
            // rays behind it
            p.setColor(0xFFD34A | (Math.round(110 * rise) << 24));
            android.graphics.Path ray = new android.graphics.Path();
            for (int i = 0; i < 12; i++) {
                double a0 = Math.toRadians(ms * 0.02 + i * 30), a1 = Math.toRadians(ms * 0.02 + i * 30 + 12);
                ray.moveTo(cx, y);
                ray.lineTo(cx + (float) Math.cos(a0) * size * 2.1f, y + (float) Math.sin(a0) * size * 2.1f);
                ray.lineTo(cx + (float) Math.cos(a1) * size * 2.1f, y + (float) Math.sin(a1) * size * 2.1f);
                ray.close();
            }
            c.drawPath(ray, p);
            p.setShader(new android.graphics.RadialGradient(cx, y, size * 1.7f,
                0xFFE27A | (Math.round(220 * rise) << 24), 0x00FFE27A, android.graphics.Shader.TileMode.CLAMP));
            c.drawCircle(cx, y, size * 1.7f, p);
            p.setShader(null);
            disc(c, p, mTfDisc, cx, y, size, ms * 0.03f, 255);
            // sparks going up the beam
            for (int i = 0; i < 14; i++) {
                float sp = ((ms / 2600f) + i * 0.173f) % 1f;
                float sx = cx + bw * (((i * 0.37f) % 1f) - 0.5f) * (0.8f + sp * 1.6f);
                float sy = bodyTop - sp * (bodyTop - top + dp(30));
                p.setColor(0xFFFFFF | (Math.round(200 * rise * (float) Math.sin(sp * Math.PI)) << 24));
                c.drawCircle(sx, sy, dp(1.4f + (i % 3) * 0.7f), p);
            }
        }

        c.save();
        c.rotate(shake, cx, by);
        // the box
        p.setStyle(Paint.Style.FILL);
        p.setColor(GIFT_RED);
        android.graphics.RectF body = new android.graphics.RectF(cx - bw / 2, bodyTop, cx + bw / 2, by);
        c.drawRoundRect(body, dp(5), dp(5), p);
        p.setColor(0x26000000);  // its right side in a little shade
        c.drawRect(cx + bw * 0.22f, bodyTop, cx + bw / 2, by, p);
        p.setColor(0x33000000);  // and under the lid
        c.drawRect(cx - bw / 2, bodyTop, cx + bw / 2, bodyTop + bh * 0.07f, p);
        ribbon(c, p, cx - bw * 0.085f, bodyTop, cx + bw * 0.085f, by);
        // the lid, with the bow: flying off to the upper right when it opens
        float lx = lid * lid * bw * 0.9f, ly = -(float) Math.sin(lid * Math.PI * 0.75) * bh * 1.5f;
        int alpha = Math.round(255 * (1 - Math.max(0f, (lid - 0.55f) / 0.45f)));
        if (alpha > 0) {
            int layer = alpha < 255 ? c.saveLayerAlpha(0, 0, w, h, alpha) : c.save();
            c.translate(lx, ly);
            c.rotate(lid * 70f, cx, bodyTop);
            float lt = bodyTop - bh * 0.2f, lb = bodyTop + bh * 0.05f;
            p.setColor(GIFT_RED_LIT);
            c.drawRoundRect(new android.graphics.RectF(cx - bw * 0.56f, lt, cx + bw * 0.56f, lb), dp(5), dp(5), p);
            ribbon(c, p, cx - bw * 0.085f, lt, cx + bw * 0.085f, lb);
            bow(c, p, cx, lt, bw, bh);
            c.restoreToCount(layer);
        }
        c.restore();

        if (!open) {
            // a few glints around it
            for (int i = 0; i < 6; i++) {
                float t = ((ms / 1800f) + i * 0.31f) % 1f;
                double a = 0.15 + i * (Math.PI - 0.3) / 5;
                float gx = cx + (float) Math.cos(a) * bw * 0.85f, gy = bodyTop + bh * 0.2f - (float) Math.sin(a) * bh * 0.75f;
                star(c, p, gx, gy, dp(7) * (float) Math.sin(t * Math.PI), GOLD);
            }
        }
    }

    // a golden band between two edges, lighter along its middle
    private void ribbon(Canvas c, Paint p, float l, float t, float r, float b) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(GOLD);
        c.drawRect(l, t, r, b, p);
        p.setColor(0x66FFFFFF);
        c.drawRect(l + (r - l) * 0.38f, t, l + (r - l) * 0.56f, b, p);
    }

    // the bow on the lid: two loops, two ends and the knot
    private void bow(Canvas c, Paint p, float cx, float y, float bw, float bh) {
        float lw = bw * 0.26f, lh = bh * 0.2f;
        for (int side = -1; side <= 1; side += 2) {
            c.save();
            c.rotate(side * -22f, cx, y);
            android.graphics.RectF loop = new android.graphics.RectF(side > 0 ? cx : cx - lw, y - lh, side > 0 ? cx + lw : cx, y);
            p.setStyle(Paint.Style.FILL);
            p.setColor(GOLD_DARK);
            c.drawOval(loop, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(bw * 0.05f);
            p.setColor(GOLD);
            c.drawOval(loop, p);
            c.restore();
            // an end, hanging down over the lid
            p.setStyle(Paint.Style.FILL);
            p.setColor(GOLD);
            android.graphics.Path end = new android.graphics.Path();
            end.moveTo(cx, y - lh * 0.2f);
            end.lineTo(cx + side * bw * 0.2f, y + bh * 0.1f);
            end.lineTo(cx + side * bw * 0.11f, y + bh * 0.13f);
            end.close();
            c.drawPath(end, p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(GOLD);
        c.drawCircle(cx, y - lh * 0.3f, bw * 0.06f, p);
    }

    // a four-pointed glint
    private void star(Canvas c, Paint p, float x, float y, float r, int color) {
        if (r <= 0.5f) return;
        android.graphics.Path s = new android.graphics.Path();
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            float d = i % 2 == 0 ? r : r * 0.3f;
            float px = x + (float) Math.cos(a) * d, py = y + (float) Math.sin(a) * d;
            if (i == 0) s.moveTo(px, py);
            else s.lineTo(px, py);
        }
        s.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawPath(s, p);
    }

    // a golden cup, the three mods' discs going round it
    private void drawTrophy(Canvas c, Paint p, float w, float h, long ms) {
        float top = areaTop(h), bottom = areaBottom(h), room = bottom - top, cx = w / 2f;
        float ch = room * 0.72f, cw = ch * 0.62f, y0 = top + (room - ch) / 2 + (float) Math.sin(ms / 900.0) * dp(3);
        float r = Math.min(room * 0.2f, dp(50));
        android.graphics.Bitmap[] discs = {mSunburnDisc, mTfDisc, mEclipseDisc};
        // those at the back of their round first, behind the cup
        for (int i = 0; i < 3; i++) orbit(c, p, discs[i], i, cx, top + room * 0.52f, cw + r * 1.5f, room * 0.13f, r, ms, false);
        drawCup(c, p, cx, y0, cw, ch, ms);
        for (int i = 0; i < 3; i++) orbit(c, p, discs[i], i, cx, top + room * 0.52f, cw + r * 1.5f, room * 0.13f, r, ms, true);
    }

    private void orbit(Canvas c, Paint p, android.graphics.Bitmap b, int i, float cx, float cy, float a, float b2,
            float r, long ms, boolean front) {
        double ang = ms * 0.0007 + i * 2 * Math.PI / 3;
        if ((Math.sin(ang) >= 0) != front) return;
        float depth = 0.85f + 0.15f * (float) Math.sin(ang);  // a little smaller at the back
        disc(c, p, b, cx + (float) Math.cos(ang) * a, cy + (float) Math.sin(ang) * b2, r * depth, ms * 0.03f, 255);
    }

    private void drawCup(Canvas c, Paint p, float cx, float y0, float cw, float ch, long ms) {
        // the handles
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(cw * 0.1f);
        p.setColor(GOLD_DARK);
        c.drawOval(new android.graphics.RectF(cx - cw * 0.78f, y0 + ch * 0.06f, cx - cw * 0.2f, y0 + ch * 0.42f), p);
        c.drawOval(new android.graphics.RectF(cx + cw * 0.2f, y0 + ch * 0.06f, cx + cw * 0.78f, y0 + ch * 0.42f), p);
        // the bowl, the stem and the foot
        p.setStyle(Paint.Style.FILL);
        p.setColor(GOLD);
        android.graphics.Path bowl = new android.graphics.Path();
        bowl.moveTo(cx - cw * 0.5f, y0);
        bowl.lineTo(cx + cw * 0.5f, y0);
        bowl.cubicTo(cx + cw * 0.5f, y0 + ch * 0.5f, cx + cw * 0.18f, y0 + ch * 0.6f, cx, y0 + ch * 0.6f);
        bowl.cubicTo(cx - cw * 0.18f, y0 + ch * 0.6f, cx - cw * 0.5f, y0 + ch * 0.5f, cx - cw * 0.5f, y0);
        bowl.close();
        c.drawPath(bowl, p);
        c.drawRect(cx - cw * 0.07f, y0 + ch * 0.58f, cx + cw * 0.07f, y0 + ch * 0.82f, p);
        p.setColor(GOLD_DARK);
        c.drawRoundRect(new android.graphics.RectF(cx - cw * 0.36f, y0 + ch * 0.82f, cx + cw * 0.36f, y0 + ch), dp(4), dp(4), p);
        p.setColor(0x55FFFFFF);
        c.drawRect(cx - cw * 0.34f, y0 + ch * 0.06f, cx - cw * 0.22f, y0 + ch * 0.36f, p);
        // a glint on its rim, now and then
        float t = (ms % 2600) / 2600f;
        star(c, p, cx + cw * 0.34f, y0 + ch * 0.08f, dp(11) * (t < 0.4f ? (float) Math.sin(t / 0.4 * Math.PI) : 0f), 0xFFFFFFFF);
    }

    // a touch stick, held: its knob going from a small push to the rim and round
    private void drawStick(Canvas c, Paint p, float w, float h, long ms) {
        float top = areaTop(h), bottom = areaBottom(h), cx = w / 2f, cy = (top + bottom) / 2;
        float r = Math.min((bottom - top) * 0.42f, dp(92));
        float loop = (ms % 5200) / 5200f;
        // how far out: a little, then more and more, then back
        float out = loop < 0.25f ? 0.18f : loop < 0.7f ? 0.18f + 0.82f * ((loop - 0.25f) / 0.45f) : 1f - 0.82f * ((loop - 0.7f) / 0.3f);
        double a = ms / 620.0;
        float kx = cx + (float) Math.cos(a) * r * 0.62f * out, ky = cy + (float) Math.sin(a) * r * 0.62f * out;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xCC0A1420);
        c.drawCircle(cx, cy, r, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(4));
        p.setColor(0xFFD6DCE4);
        c.drawCircle(cx, cy, r, p);
        // the speed it gives, as an arc round it: from a walk to a run
        p.setStrokeWidth(dp(5));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(out > 0.85f ? 0xFF46E06A : 0xFFFFC531);
        c.drawArc(new android.graphics.RectF(cx - r - dp(12), cy - r - dp(12), cx + r + dp(12), cy + r + dp(12)),
            -90, 360 * out, false, p);
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFC9CFD6);
        c.drawCircle(kx, ky, r * 0.42f, p);
    }

    // a speed gauge, its needle going up and staying there, trembling a little
    private void drawGauge(Canvas c, Paint p, float w, float h, long ms) {
        float top = areaTop(h), bottom = areaBottom(h), cx = w / 2f;
        float r = Math.min((bottom - top) * 0.62f, dp(120)), cy = bottom - dp(6);
        android.graphics.RectF arc = new android.graphics.RectF(cx - r, cy - r, cx + r, cy + r);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(r * 0.12f);
        p.setColor(0xFF1E3A5C);
        c.drawArc(arc, 180, 180, false, p);
        float up = Math.max(0f, Math.min(1f, (ms - 300) / 1400f));
        up = up * up * (3 - 2 * up);
        float at = 0.38f + 0.5f * up + (up >= 1f ? 0.012f * (float) Math.sin(ms / 90.0) : 0f);  // 0 left, 1 right
        // the part reached: from orange to green as it goes up
        p.setShader(new android.graphics.SweepGradient(cx, cy, new int[] {0xFFFFA41C, 0xFFFFA41C, 0xFFFFC531, 0xFF46E06A},
            new float[] {0f, 0.5f, 0.72f, 1f}));
        c.drawArc(arc, 180, 180 * at, false, p);
        p.setShader(null);
        // the needle
        double a = Math.PI * (1 - at);
        p.setStrokeWidth(r * 0.05f);
        p.setColor(TEXT);
        c.drawLine(cx, cy, cx + (float) Math.cos(a) * r * 0.78f, cy - (float) Math.sin(a) * r * 0.78f, p);
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy, r * 0.09f, p);
    }

    // a check mark in a ring, drawn in
    private void drawCheck(Canvas c, Paint p, float w, float h, long ms) {
        float top = areaTop(h), bottom = areaBottom(h), cx = w / 2f, cy = (top + bottom) / 2;
        float r = Math.min((bottom - top) * 0.36f, dp(80));
        float ring = Math.max(0f, Math.min(1f, ms / 600f)), mark = Math.max(0f, Math.min(1f, (ms - 500) / 450f));
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(r * 0.14f);
        p.setColor(0xFF46E06A);
        c.drawArc(new android.graphics.RectF(cx - r, cy - r, cx + r, cy + r), -90, 360 * ring, false, p);
        if (mark > 0) {
            float[] x = {cx - r * 0.45f, cx - r * 0.1f, cx + r * 0.5f}, y = {cy + r * 0.02f, cy + r * 0.38f, cy - r * 0.36f};
            float a = Math.min(1f, mark / 0.4f), b = Math.max(0f, (mark - 0.4f) / 0.6f);
            android.graphics.Path m = new android.graphics.Path();
            m.moveTo(x[0], y[0]);
            m.lineTo(x[0] + (x[1] - x[0]) * a, y[0] + (y[1] - y[0]) * a);
            if (b > 0) m.lineTo(x[1] + (x[2] - x[1]) * b, y[1] + (y[2] - y[1]) * b);
            c.drawPath(m, p);
        }
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setStrokeJoin(Paint.Join.MITER);
        p.setStyle(Paint.Style.FILL);
    }

    // --- a happy new year -----------------------------------------------------

    private void newYear() {
        String year = mId.substring(mId.lastIndexOf('_') + 1);
        mPages.add(new Page(t("Happy New Year!"),
            t("TheM3dium wishes all of you a happy new year. Thank you for using Mareshine!"),
            (c, p, w, h, ms) -> drawFireworks(c, p, w, h, ms, year)));
        mPages.get(mPages.size() - 1).middle = true;
        // soft sounds for the rockets, for those who have the event's music on
        if (Winter.music(Settings.read(this))) fireworkSounds();
    }

    // The rockets' two sounds: a whoosh for the start (res/raw/firework_start,
    // "Cartoon Rocket Whoosh" by Universfield, from Pixabay, shortened and
    // softened) and a soft pop with a crackle for the burst, made here.
    private android.media.SoundPool mFx;
    private int mFxStart, mFxPop;
    private volatile boolean mFxReady;
    private final int[] mFxCycle = new int[ROCKETS];
    private final boolean[] mFxPopped = new boolean[ROCKETS];
    private boolean mFxSeen;

    private void fireworkSounds() {
        mFx = new android.media.SoundPool.Builder().setMaxStreams(6)
            .setAudioAttributes(new android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_GAME)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .build();
        final android.media.SoundPool pool = mFx;
        final int[] loaded = {0};
        pool.setOnLoadCompleteListener((sp, id, status) -> { if (status == 0 && ++loaded[0] == 2) mFxReady = true; });
        new Thread(() -> {
            try {
                java.io.File pop = new java.io.File(getCacheDir(), "firework-pop.wav");
                wav(pop, pop());
                mFxStart = pool.load(this, R.raw.firework_start, 1);
                mFxPop = pool.load(pop.getAbsolutePath(), 1);
            } catch (Exception ignored) {}  // then the fireworks are silent
        }, "FireworkSounds").start();
    }

    private static final int FX_RATE = 22050;

    private static short[] pop() {
        int n = FX_RATE * 90 / 100;
        short[] out = new short[n];
        java.util.Random r = new java.util.Random(11);
        double low = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) FX_RATE;
            // the burst: a dull thump under noise that dies away fast
            low += (r.nextDouble() * 2 - 1 - low) * (0.05 + 0.5 * Math.exp(-t * 18));
            double v = low * Math.exp(-t * 9) * 0.9 + Math.sin(2 * Math.PI * 85 * t) * Math.exp(-t * 16) * 0.5;
            // then the crackle: single ticks, fewer and softer
            if (t > 0.12 && r.nextDouble() < 0.012 * Math.exp(-(t - 0.12) * 3)) v += (r.nextDouble() * 2 - 1) * 0.35 * Math.exp(-(t - 0.12) * 4);
            out[i] = (short) (32767 * Math.max(-1, Math.min(1, v)));
        }
        return out;
    }

    private static void wav(java.io.File f, short[] pcm) throws java.io.IOException {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(44 + pcm.length * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes("US-ASCII")).putInt(36 + pcm.length * 2).put("WAVEfmt ".getBytes("US-ASCII"))
            .putInt(16).putShort((short) 1).putShort((short) 1).putInt(FX_RATE).putInt(FX_RATE * 2)
            .putShort((short) 2).putShort((short) 16).put("data".getBytes("US-ASCII")).putInt(pcm.length * 2);
        for (short v : pcm) b.putShort(v);
        try (java.io.OutputStream o = new java.io.FileOutputStream(f)) {
            o.write(b.array());
        }
    }

    // a rocket's sound: softer and at a slightly different pitch each time (the
    // whoosh only ever lower), left or right as it is on the screen
    private void fx(int id, float x, int seed, float volume, float lowest, float highest) {
        if (!mFxReady || mFx == null) return;
        float pan = Math.max(0f, Math.min(1f, x)), v = volume * (0.7f + 0.3f * ((seed >>> 5 & 255) / 255f));
        mFx.play(id, v * (1 - pan * 0.6f), v * (0.4f + pan * 0.6f), 1, 0, lowest + (highest - lowest) * ((seed >>> 9 & 255) / 255f));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mFx != null) { mFx.release(); mFx = null; }
    }

    private static final int ROCKETS = 7;
    private static final int[] FIREWORKS = {0xFFFFC531, 0xFFFF3D8B, 0xFF2FB8C9, 0xFF46E06A, 0xFFFF7A1C, 0xFFB98CFF, 0xFFF4F8FF};

    // rockets going up and bursting all over the night sky, the year below
    private void drawFireworks(Canvas c, Paint p, float w, float h, long ms, String year) {
        c.drawColor(0xFF050D1C);
        final int rockets = ROCKETS;
        final float period = 2800f;
        for (int k = 0; k < rockets; k++) {
            float at = ms + k * period / rockets + (k % 2) * 310;
            int n = (int) (at / period);
            float t = (at % period) / period;
            int seed = (k * 73856093) ^ (n * 19349663);
            float x = w * (0.1f + 0.8f * (((seed >>> 3) & 1023) / 1023f));
            float y = h * (0.1f + 0.36f * (((seed >>> 13) & 1023) / 1023f));  // the upper half, above the text
            int color = FIREWORKS[((seed >>> 23) & 0x7FFFFFFF) % FIREWORKS.length];
            float size = dp(70) + dp(60) * (((seed >>> 7) & 255) / 255f);
            // its sounds: when it starts and when it bursts (not for those already up on the first frame)
            if (mFxSeen && n != mFxCycle[k]) {
                mFxPopped[k] = false;
                fx(mFxStart, x / w, seed, 0.176f, 0.7f, 1f);
            }
            if (mFxSeen && t >= 0.22f && !mFxPopped[k] && n == mFxCycle[k]) fx(mFxPop, x / w, seed, 0.119f, 0.85f, 1.15f);
            if (t >= 0.22f || !mFxSeen) mFxPopped[k] = t >= 0.22f;
            mFxCycle[k] = n;
            p.setStyle(Paint.Style.FILL);
            if (t < 0.22f) {
                // the rocket, on its way up
                float u = t / 0.22f, e = 1 - (1 - u) * (1 - u), ry = h + (y - h) * e;
                p.setColor(0xFFF3B0 | (200 << 24));
                c.drawCircle(x, ry, dp(2.2f), p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(1.6f));
                p.setColor(0xFFF3B0 | (90 << 24));
                c.drawLine(x, ry, x, ry + dp(26) * (1 - u * 0.6f), p);
                p.setStyle(Paint.Style.FILL);
                continue;
            }
            float u = (t - 0.22f) / 0.78f;
            float out = 1 - (1 - u) * (1 - u) * (1 - u), fall = dp(46) * u * u;
            int alpha = Math.round(255 * (float) Math.pow(1 - u, 0.7));
            if (alpha <= 2) continue;
            // the flash of the burst
            if (u < 0.12f) {
                p.setColor(0xFFFFFF | (Math.round(150 * (1 - u / 0.12f)) << 24));
                c.drawCircle(x, y, size * 0.35f * (u / 0.12f + 0.3f), p);
            }
            for (int i = 0; i < 40; i++) {
                double a = i * (2 * Math.PI / 40) + (seed & 63) * 0.05;
                float d = size * out * (i % 2 == 0 ? 1f : 0.62f);
                float px = x + (float) Math.cos(a) * d, py = y + (float) Math.sin(a) * d + fall;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(1.3f));
                p.setColor(color & 0x00FFFFFF | ((alpha / 2) << 24));
                c.drawLine(x + (float) Math.cos(a) * d * 0.8f, y + (float) Math.sin(a) * d * 0.8f + fall, px, py, p);
                p.setStyle(Paint.Style.FILL);
                // twinkling towards the end
                int tw = u > 0.55f && ((i + ms / 90) % 3 == 0) ? alpha / 3 : alpha;
                p.setColor(color & 0x00FFFFFF | ((tw / 5) << 24));
                c.drawCircle(px, py, dp(7), p);  // its glow
                p.setColor(color & 0x00FFFFFF | (tw << 24));
                c.drawCircle(px, py, dp(3), p);
            }
        }
        mFxSeen = true;
        // the year, golden, glowing a little
        float ty = areaBottom(h) - dp(10), size = Math.min(dp(84), (areaBottom(h) - areaTop(h)) * 0.5f);
        float glow = 0.75f + 0.25f * (float) Math.sin(ms / 600.0);
        p.setShadowLayer(dp(18) * glow, 0, 0, 0xFFFFA41C);
        text(c, p, year, w / 2f, ty, size, GOLD, Paint.Align.CENTER);
        p.clearShadowLayer();
    }

    private android.graphics.Bitmap mLogo;

    private void drawLogo(Canvas c, Paint p, float w, float h, long ms) {
        if (mLogo == null) mLogo = android.graphics.BitmapFactory.decodeResource(getResources(), R.drawable.mareshine_logo);
        if (mLogo == null) return;
        float bw = dp(230), bh = bw * mLogo.getHeight() / mLogo.getWidth(), cy = h * 0.67f;
        cy += (float) Math.sin(ms / 900.0) * dp(4);
        c.drawBitmap(mLogo, null, new android.graphics.RectF(w / 2 - bw / 2, cy - bh / 2, w / 2 + bw / 2, cy + bh / 2), p);
    }

    private void swapAb() {
        mPages.add(new Page(t("New: swap A and B"), t("Would you rather confirm with B and go back with A, as on a Nintendo controller? The game's own buttons stay as they are."), this::drawSwapAb));
        mTourAfter = 0;
        mTourSteps = Tour.AB;
    }

    // a phone seen from the front, turning from side to side, its aim going along
    private void drawGyroPhone(Canvas c, Paint p, float w, float h, long ms) {
        float cx = w / 2f, cy = h * 0.7f, pw = dp(190), ph = dp(88);
        float swing = (float) Math.sin(ms / 900.0);
        c.save();
        c.translate(cx, cy);
        c.scale(1f - 0.16f * Math.abs(swing), 1f);
        c.rotate(swing * 4f);
        android.graphics.RectF r = new android.graphics.RectF(-pw / 2, -ph / 2, pw / 2, ph / 2);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFD6E0EA);
        c.drawRoundRect(r, dp(14), dp(14), p);
        r.inset(dp(7), dp(7));
        p.setColor(0xFF10304C);
        c.drawRoundRect(r, dp(8), dp(8), p);
        float ax = swing * pw * 0.3f;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(3));
        p.setColor(0xFFFFC531);
        c.drawCircle(ax, 0, dp(13), p);
        c.drawLine(ax - dp(20), 0, ax - dp(8), 0, p);
        c.drawLine(ax + dp(8), 0, ax + dp(20), 0, p);
        c.drawLine(ax, -dp(20), ax, -dp(8), p);
        c.drawLine(ax, dp(8), ax, dp(20), p);
        p.setStyle(Paint.Style.FILL);
        c.restore();
    }

    // A and B changing places
    private void drawSwapAb(Canvas c, Paint p, float w, float h, long ms) {
        float loop = (ms % 3600) / 3600f;
        float t = loop < 0.3f ? 0f : loop < 0.5f ? (loop - 0.3f) / 0.2f : loop < 0.8f ? 1f : 1f - (loop - 0.8f) / 0.2f;
        t = t * t * (3 - 2 * t);
        float cy = h * 0.72f, gap = dp(70), rad = dp(34);
        float lift = (float) Math.sin(t * Math.PI) * dp(26);
        float ax = w / 2f + gap * (2 * t - 1), bx = w / 2f - gap * (2 * t - 1);
        button(c, p, ax, cy - lift, rad, 0xFF2FB56A, "A");
        button(c, p, bx, cy + lift, rad, 0xFFE5534B, "B");
    }

    private void button(Canvas c, Paint p, float x, float y, float rad, int color, String label) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawCircle(x, y, rad, p);
        text(c, p, label, x, y + rad * 0.42f, rad * 1.2f, 0xFFFFFFFF, Paint.Align.CENTER);
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

    // --- the mod manager's first page ----------------------------------------

    private static final int TEXT = 0xFFF4F8FF;

    // the room for a picture: between the text panel and the buttons
    private float areaTop(float h) {
        return Math.max(h * 0.42f, (mPanel != null ? mPanel.getBottom() : 0) + dp(14));
    }

    private float areaBottom(float h) {
        return (mButtons != null && mButtons.getTop() > 0 ? mButtons.getTop() : h * 0.88f) - dp(12);
    }

    private void text(Canvas c, Paint p, String s, float x, float y, float size, int color, Paint.Align align) {
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        p.setTextAlign(align);
        p.setTextSize(size);
        p.setColor(color);
        c.drawText(s, x, y, p);
    }

    // a mod's disc picture, turned by `deg`
    private void disc(Canvas c, Paint p, android.graphics.Bitmap b, float cx, float cy, float r, float deg) {
        disc(c, p, b, cx, cy, r, deg, 255);
    }

    private void disc(Canvas c, Paint p, android.graphics.Bitmap b, float cx, float cy, float r, float deg, int alpha) {
        if (b == null) return;
        c.save();
        c.rotate(deg, cx, cy);
        p.setFilterBitmap(true);
        p.setAlpha(alpha);
        c.drawBitmap(b, null, new android.graphics.RectF(cx - r, cy - r, cx + r, cy + r), p);
        c.restore();
    }

    // Page 1: the two games' discs fly in from the sides, turning, and land with
    // a flash and confetti (mods are something to celebrate); then they turn
    // side by side
    private static final long START = 800;          // after the page has faded in (onCreate)
    private static final long LAND = START + 1100;  // when the discs are in place

    private void drawDiscs(Canvas c, Paint p, float w, float h, long ms) {
        TouchDraw.background(c, p, w, h);
        float top = areaTop(h), bot = areaBottom(h) - dp(26);  // the names below the discs
        float r = Math.min((bot - top) / 2, w * 0.13f), y = (top + bot) / 2, deg = ms * 0.045f;
        float in = Math.max(0f, Math.min(1f, (ms - START) / (float) (LAND - START))), k = in <= 0 ? 0 : backOut(in);
        float spin = (1 - k) * 540;  // the extra turns while flying
        float ex = w * 0.36f - (1 - k) * w * 0.6f, sx = w * 0.64f + (1 - k) * w * 0.6f;
        // the flash: a golden ring growing from between them, and rays
        float t = (ms - LAND) / 900f;
        if (t > 0 && t < 1) {
            float cx = w / 2, rr = r * (0.6f + 2.4f * t);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(10) * (1 - t));
            p.setColor(0xFFFFC531 & 0x00FFFFFF | (Math.round(220 * (1 - t)) << 24));
            c.drawCircle(cx, y, rr, p);
            p.setStyle(Paint.Style.FILL);
            p.setShader(new android.graphics.RadialGradient(cx, y, r * 2.6f,
                0xFFFFE9A0 & 0x00FFFFFF | (Math.round(150 * (1 - t)) << 24), 0x00FFE9A0,
                android.graphics.Shader.TileMode.CLAMP));
            c.drawCircle(cx, y, r * 2.6f, p);
            p.setShader(null);
        }
        disc(c, p, mEclipseDisc, ex, y + (float) Math.sin(ms / 700.0) * dp(4), r, deg + spin, 255);
        disc(c, p, mSunburnDisc, sx, y + (float) Math.sin(ms / 700.0 + 1.6) * dp(4), r, -deg - spin, 255);
        int name = Math.round(255 * Math.max(0, Math.min(1, (ms - LAND) / 500f)));
        text(c, p, "Super Mario Eclipse", w * 0.36f, y + r + dp(22), dp(15), TEXT & 0x00FFFFFF | (name << 24), Paint.Align.CENTER);
        text(c, p, "Super Mario Sunburn", w * 0.64f, y + r + dp(22), dp(15), TEXT & 0x00FFFFFF | (name << 24), Paint.Align.CENTER);
        confetti(c, p, w / 2, y, (ms - LAND) / 1000f, h);
    }

    // overshooting a little, then settling (0 to 1)
    private static float backOut(float x) {
        float s = 0.3f, u = x - 1;  // small: the discs must not meet in the middle
        return 1 + u * u * ((s + 1) * u + s);
    }

    private static final int[] CONFETTI = {0xFFFFC531, 0xFFFFA41C, 0xFFFF3D8B, 0xFF2FB8C9, 0xFF46E06A, 0xFFF4F8FF};

    // pieces shot up from (x, y) `t` seconds ago, falling and turning, gone after a while
    private void confetti(Canvas c, Paint p, float x, float y, float t, float h) {
        if (t < 0 || t > 3f) return;
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 130; i++) {
            double a = Math.toRadians(-90 + ((i * 137.5) % 160) - 80);  // upwards, fanned out
            float v = dp(380 + (i * 53) % 420), g = dp(900);
            float px = x + (float) Math.cos(a) * v * t * (1 + (i % 5) * 0.25f);
            float py = y + (float) Math.sin(a) * v * t + g * t * t / 2;
            if (py > h + dp(20)) continue;
            float fade = Math.max(0, Math.min(1, (3f - t) / 0.8f));
            p.setColor(CONFETTI[i % CONFETTI.length] & 0x00FFFFFF | (Math.round(255 * fade) << 24));
            c.save();
            c.rotate(t * (200 + i * 37 % 400) * (i % 2 == 0 ? 1 : -1), px, py);
            float cw = dp(5 + i % 5), chh = dp(9 + i % 6) * Math.abs((float) Math.cos(t * 6 + i));
            c.drawRect(px - cw / 2, py - chh / 2, px + cw / 2, py + chh / 2, p);
            c.restore();
        }
    }

    // Page 2: each game's disc on a card of its colours: a glow, slow rays
    // turning behind it, the disc turning and zooming a little (only the app's
    // own pictures: the games' logos are not ours to show)
    private void drawPreviews(Canvas c, Paint p, float w, float h, long ms) {
        TouchDraw.background(c, p, w, h);
        float top = areaTop(h), bot = areaBottom(h) - dp(52);  // the names and "and more" below
        float ch = Math.min(bot - top, w * 0.38f * 9 / 16), cw = ch * 16 / 9, y = (top + bot) / 2;
        float gap = dp(24), fade = Math.min(1f, ms / 900f);
        int alpha = Math.round(255 * fade);
        float[] cx = {w / 2 - gap / 2 - cw / 2, w / 2 + gap / 2 + cw / 2};
        android.graphics.Bitmap[] discs = {mEclipseDisc, mSunburnDisc};
        int[][] colours = {{0xFF2A0B4A, 0xFF7A1450, 0xFFFF3D8B}, {0xFF5A1206, 0xFFC2410C, 0xFFFFB02E}};
        String[] names = {"Super Mario Eclipse", "Super Mario Sunburn"};
        for (int k = 0; k < 2; k++) {
            android.graphics.RectF r = new android.graphics.RectF(cx[k] - cw / 2, y - ch / 2, cx[k] + cw / 2, y + ch / 2);
            int[] col = colours[k];
            c.save();
            android.graphics.Path clip = new android.graphics.Path();
            clip.addRoundRect(r, dp(12), dp(12), android.graphics.Path.Direction.CW);
            c.clipPath(clip);
            // the card: dark at the edge, its colour towards the middle
            p.setStyle(Paint.Style.FILL);
            p.setShader(new android.graphics.LinearGradient(r.left, r.top, r.right, r.bottom, col[0], col[1],
                android.graphics.Shader.TileMode.CLAMP));
            p.setAlpha(alpha);
            c.drawRect(r, p);
            // rays, turning slowly the other way to the disc
            float rays = (k == 0 ? -1 : 1) * ms * 0.006f, len = cw;
            p.setShader(null);
            p.setColor(col[2] & 0x00FFFFFF | (Math.round(38 * fade) << 24));
            android.graphics.Path ray = new android.graphics.Path();
            for (int i = 0; i < 12; i++) {
                double a0 = Math.toRadians(rays + i * 30), a1 = Math.toRadians(rays + i * 30 + 12);
                ray.moveTo(r.centerX(), r.centerY());
                ray.lineTo(r.centerX() + (float) Math.cos(a0) * len, r.centerY() + (float) Math.sin(a0) * len);
                ray.lineTo(r.centerX() + (float) Math.cos(a1) * len, r.centerY() + (float) Math.sin(a1) * len);
                ray.close();
            }
            c.drawPath(ray, p);
            // a glow behind the disc, breathing
            float glow = ch * (0.55f + 0.04f * (float) Math.sin(ms / 900.0 + k));
            p.setShader(new android.graphics.RadialGradient(r.centerX(), r.centerY(), glow,
                col[2] & 0x00FFFFFF | (Math.round(150 * fade) << 24), col[2] & 0x00FFFFFF,
                android.graphics.Shader.TileMode.CLAMP));
            c.drawCircle(r.centerX(), r.centerY(), glow, p);
            p.setShader(null);
            // small sparks drifting up
            for (int i = 0; i < 10; i++) {
                float sx = r.left + r.width() * ((i * 0.137f + k * 0.31f) % 1f);
                float sp = ((ms / 5200f) + i * 0.173f) % 1f;
                float sy = r.bottom - sp * r.height();
                p.setColor(0xFFFFFF | (Math.round(150 * fade * (float) Math.sin(sp * Math.PI)) << 24));
                c.drawCircle(sx, sy, dp(1.6f + (i % 3)), p);
            }
            p.setAlpha(255);
            // the disc, turning, zooming a little
            float zoom = 1f + 0.06f * (float) Math.sin(ms / 2600.0 + k * 1.7);
            disc(c, p, discs[k], r.centerX(), r.centerY(), ch * 0.36f * zoom, ms * (k == 0 ? 0.03f : -0.03f), alpha);
            c.restore();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            p.setColor(0xFF2FB8C9 & 0x00FFFFFF | (alpha << 24));
            c.drawRoundRect(r, dp(12), dp(12), p);
            p.setStyle(Paint.Style.FILL);
            text(c, p, names[k], cx[k], r.bottom + dp(22), dp(15), TEXT, Paint.Align.CENTER);
        }
        text(c, p, t("and more (soon)"), w / 2, y + ch / 2 + dp(48), dp(15), 0xFFFFC531, Paint.Align.CENTER);
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
            // solid orange (dl_row is see-through), a light rim while focused
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(dp(10));
            bg.setColor(MAIN);
            b.setBackground(bg);
            b.setOnFocusChangeListener((v, has) -> bg.setStroke(has ? Math.round(dp(2)) : 0, 0xFFFFF3C4));
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

        // the winter logo, its lights running along the chain or dancing to the music
        mWinterLogo = new WinterLogoView(this, null);
        mWinterLogo.setWinter(true, true);
        mWinterLogo.setChase(true);
        mWinterLogo.setAdjustViewBounds(true);
        mWinterLogo.setVisibility(View.GONE);
        root.addView(mWinterLogo, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            Math.round(getResources().getDisplayMetrics().heightPixels * 0.4f), Gravity.CENTER_HORIZONTAL | Gravity.TOP));

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

        // a page's button in the middle of its picture
        mLink = button("", true);
        mLink.setTextSize(20);
        mLink.setVisibility(View.GONE);
        mLink.setOnClickListener(v -> {
            Page page = mPages.get(mIndex);
            if (page.linkAction != null) page.linkAction.run();
        });
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        llp.topMargin = Math.round(getResources().getDisplayMetrics().heightPixels * 0.62f);
        root.addView(mLink, llp);

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
        mLink.setText(page.link != null ? page.link : "");
        mLink.setVisibility(page.link != null ? View.VISIBLE : View.GONE);
        mWinterLogo.setVisibility(page.logo ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams panel = (FrameLayout.LayoutParams) mPanel.getLayoutParams();
        panel.topMargin = Math.round(getResources().getDisplayMetrics().heightPixels * (page.middle ? 0.4f : 0.17f));
        mPanel.setLayoutParams(panel);
        mPanel.getBackground().mutate().setAlpha(page.middle ? 150 : 255);
        if (page.logo) mPanel.post(() -> {  // between the text and the buttons, once they are laid out
            float h = mView.getHeight(), top = areaTop(h), bottom = areaBottom(h);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mWinterLogo.getLayoutParams();
            lp.topMargin = Math.round(top);
            lp.height = Math.max(1, Math.round(bottom - top));
            mWinterLogo.setLayoutParams(lp);
        });
        mPanel.setAlpha(0f);
        // the mods' first page: the text after the discs have landed
        long wait = index == 0 && Intro.MOD_MANAGER.equals(mId) ? LAND + 300 : 0;
        mPanel.animate().alpha(1f).setStartDelay(wait).setDuration(350).start();
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
            boolean last = index == mPages.size() - 1 && index != mTourAfter;
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
            first = button(page.next != null ? page.next : t(last ? "Got it" : "Next"), true);
            first.setOnClickListener(v -> {
                if (mIndex == mTourAfter) startTour();
                else show(mIndex + 1);
            });
            addButton(first);
        }
        first.requestFocus();
        mView.invalidate();
        // easily missed among the moving pictures: after a few seconds the orange
        // button (Next, Got it, a choice's first) lights up, now and then
        mButtons.removeCallbacks(mPulse);
        mMain = first;
        mButtons.postDelayed(mPulse, 3000);
    }

    private static final int MAIN = 0xFFFFA41C, MAIN_LIT = 0xFFFFE45C;

    // the main button grows a little and lights up, and settles again
    private void pulse() {
        Button b = mMain;
        if (b == null || !b.isAttachedToWindow()) return;
        android.graphics.drawable.GradientDrawable bg = (android.graphics.drawable.GradientDrawable) b.getBackground();
        android.animation.ValueAnimator glow = android.animation.ValueAnimator.ofArgb(MAIN, MAIN_LIT, MAIN);
        glow.setDuration(700);
        glow.addUpdateListener(a -> bg.setColor((int) a.getAnimatedValue()));
        glow.start();
        b.animate().scaleX(1.12f).scaleY(1.12f).setDuration(300).withEndAction(() ->
            b.animate().scaleX(1f).scaleY(1f).setDuration(400).start()).start();
        mButtons.postDelayed(mPulse, 4000);
    }

    // Back: to the choice before this page, else the page before
    private void back() {
        String before = getIntent().getStringExtra(EXTRA_BEFORE);
        if (mIndex == 0 && before != null) {  // the first page: the intro before this one
            startActivity(new android.content.Intent(this, IntroActivity.class).putExtra(EXTRA_ID, before));
            finish();
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            return;
        }
        if (mTourAfter >= 0 && mIndex == mTourAfter + 1) {  // the page after the steps: their last again
            startTour(mTourSteps.length - 1);
            return;
        }
        for (int i = mIndex - 1; i >= 0; i--)
            if (!mPages.get(i).choices.isEmpty()) { show(i); return; }
        if (mIndex > 0) show(mIndex - 1);  // no choice before it: the page before
    }

    // Skip: to the choice when there is one still to make, else done
    private void skip() {
        for (int i = mIndex + 1; i < mPages.size(); i++)
            if (!mPages.get(i).choices.isEmpty()) { show(i); return; }
        if (mIndex >= 0 && !mPages.get(mIndex).choices.isEmpty()) return;  // the choice itself: choose
        finishIntro();
    }

    // the steps on the real screens; they come back here when skipped or done
    private void startTour() { startTour(0); }

    private void startTour(int step) {
        if (mTouring) return;
        mTouring = true;
        if (mTourSteps == Tour.MODS) Mods.tourStart(this);
        startActivityForResult(Tour.intent(this, mTourSteps, step), REQUEST_TOUR);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    protected void onActivityResult(int request, int result, android.content.Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQUEST_TOUR) return;
        if (mTourSteps == Tour.MODS) Mods.tourEnd(this);
        mTouring = false;
        if (result == Tour.RESULT_BACK) {  // Back on its first step: the page before the steps
            if (mIndex != mTourAfter) show(mTourAfter);
            return;
        }
        if (result == Tour.RESULT_DONE) {  // Next on its last: the page after it
            show(mTourAfter + 1);
            return;
        }
        finishIntro();
    }

    // done: faded out, to the next intro (it fades itself in) or to the launcher
    private void finishIntro() {
        Intro.done(this, mId);
        String next = Intro.pending(this);
        View root = findViewById(android.R.id.content);
        if (mView == null || root == null) {
            if (next != null) startActivity(new android.content.Intent(this, IntroActivity.class).putExtra(EXTRA_ID, next).putExtra(EXTRA_BEFORE, mId));
            finish();
            overridePendingTransition(0, 0);
            return;
        }
        mButtons.setEnabled(false);
        root.animate().alpha(0f).setDuration(450).withEndAction(() -> {
            if (next != null) {
                startActivity(new android.content.Intent(this, IntroActivity.class).putExtra(EXTRA_ID, next).putExtra(EXTRA_BEFORE, mId));
                finish();
                overridePendingTransition(0, 0);
            } else {
                finish();
                overridePendingTransition(android.R.anim.fade_in, 0);
            }
        }).start();
    }
}
