package io.github.them3dium.mareshine;

import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// The touch controls as the app shows them (the layout editor, the intros):
// their default places and the GameCube style's shapes, drawn as the game
// draws them (platform/pad/touch.cpp).
final class TouchDraw {
    private TouchDraw() {}

    static final class Elem {
        final String name, label;
        final int color;
        final boolean stick;
        final float dx, dy, dr; // defaults (touch.cpp, kDefs)
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

    // the defaults of touch.cpp, and the GameCube style's places (kGcPlaces)
    static List<Elem> defaults() {
        List<Elem> l = new ArrayList<>();
        l.add(new Elem("STICK", "STICK", 0xFFBFC9D4, true, 0.16f, 0.72f, 0.14f).gc(0.16f, 0.76f, 0.14f));
        l.add(new Elem("CSTICK", "C", 0xFFF1C40F, true, 0.61f, 0.76f, 0.11f));
        l.add(new Elem("A", "A", 0xFF2ECC71, false, 0.90f, 0.74f, 0.082f).gc(0.86f, 0.66f, 0.090f));
        l.add(new Elem("B", "B", 0xFFE74C3C, false, 0.80f, 0.82f, 0.072f).gc(0.775f, 0.76f, 0.060f));
        l.add(new Elem("X", "X", 0xFFF1C40F, false, 0.82f, 0.62f, 0.062f).gc(0.937f, 0.588f, 0.068f));
        l.add(new Elem("Y", "Y", 0xFF3498DB, false, 0.73f, 0.70f, 0.062f).gc(0.85f, 0.48f, 0.068f));
        l.add(new Elem("L", "L", 0xFF95A5A6, false, 0.08f, 0.16f, 0.072f).gc(0.12f, 0.366f, 0.075f));
        l.add(new Elem("R", "R", 0xFF2980B9, false, 0.91f, 0.52f, 0.072f).gc(0.877f, 0.366f, 0.075f));
        l.add(new Elem("RPLUS", "R+", 0xFF1F4E79, false, 0.82f, 0.44f, 0.062f).gc(0.899f, 0.275f, 0.075f));
        l.add(new Elem("Z", "Z", 0xFF9B59B6, false, 0.90f, 0.30f, 0.062f).gc(0.95f, 0.45f, 0.055f));
        l.add(new Elem("START", "START", 0xFFE67E22, false, 0.50f, 0.08f, 0.050f));
        l.add(new Elem("HUD", "HIDE", 0xFF7F8C8D, false, 0.40f, 0.08f, 0.045f)); // hides the other controls
        l.add(new Elem("MENU", "MENU", 0xFF455A64, false, 0.60f, 0.08f, 0.050f));
        l.add(new Elem("SAVE", "SAVE", 0xFF16A085, false, 0.74f, 0.08f, 0.042f)); // quick save
        l.add(new Elem("LOAD", "LOAD", 0xFFD35400, false, 0.83f, 0.08f, 0.042f)); // quick load (held)
        return l;
    }

    // The editor's background: dark blue with a light grid to line things up
    static void background(Canvas c, Paint p, float w, float h) {
        c.drawColor(0xFF0A1B30);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1);
        p.setColor(0x222FB8C9);
        for (int i = 1; i < 10; i++) c.drawLine(w * i / 10f, 0, w * i / 10f, h, p);
        for (int i = 1; i < 6; i++) c.drawLine(0, h * i / 6f, w, h * i / 6f, p);
    }

    // --- the GameCube style's shapes (GcShapes, from tools/touch-gamecube) ----

    private static final Map<Integer, Path> sGcPaths = new HashMap<>();

    static int gcIndex(String name) {
        for (int i = 0; i < GcShapes.NAMES.length; i++) if (GcShapes.NAMES[i].equals(name)) return i;
        return -1;
    }

    // the shape an element has in the GameCube style, or -1 (drawn as before)
    static int gcShape(String elem) {
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

    static int gcColor(String elem) {
        switch (elem) {
            case "A": return 0x2ECC71;
            case "B": return 0xE74C3C;
            case "Z": return 0x9B59B6;
            case "CSTICK": return 0xFFD21F;
            case "STICK": return 0xFFFFFF;
            default: return 0xD0D4DA;
        }
    }

    // L, R and R+ turned to fall as Z does (touch.cpp, kGcTilt), in degrees
    static float gcTilt(String elem) {
        switch (elem) {
            case "L": return -(28.1f - 21.6f);
            case "R": return 28.1f - 21.4f;
            case "RPLUS": return 28.1f - 21.4f;  // R pressed in, in R's frame
            default: return 0;
        }
    }

    static float gcLetterScale(String elem) {
        return elem.equals("A") || elem.equals("B") ? 0.65f
            : elem.equals("L") || elem.equals("R") || elem.equals("RPLUS") ? 0.75f : 1f;
    }

    // the shape at (cx, cy), unit pixels for its [-1, 1], turned by deg
    static Path gcPath(int idx, float cx, float cy, float unit, float deg) {
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

    // The GameCube style as the game draws it (touch.cpp, makeGc): the shape
    // filled in the button's colour with its letter; a stick's gate only as an
    // outline. Size: the button's picture is 2r, the shape 0.94r. A hidden
    // element is dashed (dash in pixels).
    static boolean drawGc(Canvas c, Paint p, Elem e, float cx, float cy, float rPx, int alpha, float dash) {
        int idx = gcShape(e.name);
        if (idx < 0) return false;
        float unit = 0.94f * rPx;
        boolean stick = e.name.equals("STICK") || e.name.equals("CSTICK");
        int rgb = gcColor(e.name);
        p.setPathEffect(e.shown ? null : new DashPathEffect(new float[] {dash, dash * 5 / 6}, 0));
        if (stick) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x0A1420 | ((alpha * 35 / 100) << 24));
            c.drawPath(gcPath(idx, cx, cy, unit, 0), p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(dash / 3, 2 * rPx * 0.05f));
            p.setColor(rgb | (alpha << 24));
            c.drawPath(gcPath(idx, cx, cy, unit, 0), p);
            // the knob in the middle, at rest
            int knob = gcIndex(e.name.equals("STICK") ? "KNOB" : "CKNOB");
            float ku = unit * (e.name.equals("STICK") ? 0.44f : 0.46f);
            p.setPathEffect(null);
            p.setStyle(Paint.Style.FILL);
            c.drawPath(gcPath(knob, cx, cy, ku, 0), p);
            if (e.name.equals("CSTICK")) gcLabel(c, p, knob, "C", cx, cy, ku, 0.65f, alpha, 0);
            return true;
        }
        drawGcShape(c, p, idx, e.label, cx, cy, unit, gcTilt(e.name), rgb, alpha, gcLetterScale(e.name));
        return true;
    }

    // one filled shape with its letter
    static void drawGcShape(Canvas c, Paint p, int idx, String label, float cx, float cy, float unit, float deg, int rgb,
                            int alpha, float letterScale) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(rgb | ((alpha * 92 / 100) << 24));
        c.drawPath(gcPath(idx, cx, cy, unit, deg), p);
        gcLabel(c, p, idx, label, cx, cy, unit, letterScale, alpha, deg);
    }

    // The letter at the shape's widest place, about as large as touch.cpp draws it
    static void gcLabel(Canvas c, Paint p, int idx, String label, float cx, float cy, float unit, float scale, int alpha,
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
}
