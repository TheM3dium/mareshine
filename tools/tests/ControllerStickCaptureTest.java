package io.github.them3dium.mareshine;

public final class ControllerStickCaptureTest {
    private static int checks;
    private static void expect(String name, String expected, String actual) {
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError(name + ": " + actual);
        checks++;
    }
    public static void main(String[] args) {
        ControllerStickCapture c = new ControllerStickCapture();
        expect("opening navigation must not bind", null, c.update(1, 0, 0, 0));
        expect("neutral arms capture", null, c.update(0, 0, 0, 0));
        expect("small drift ignored", null, c.update(.1f, -.1f, .2f, 0));
        expect("left negative X selects left pair", "a0", c.update(-.9f, 0, 0, 0));
        c.reset();
        expect("reset waits for release", null, c.update(0, 0, 0, 1));
        expect("neutral again", null, c.update(0, 0, 0, 0));
        expect("right negative Y selects right pair", "a2", c.update(0, 0, 0, -.9f));
        expect("ambiguous simultaneous movement ignored", null, c.update(.9f, 0, .8f, 0));
        expect("invalid axis ignored", null, c.update(Float.NaN, 0, 0, 0));
        expect("dominant stick accepted", "a2", c.update(.3f, 0, 0, .9f));
        System.out.println(checks + " stick capture checks passed");
    }
}
