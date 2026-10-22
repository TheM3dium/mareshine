package io.github.them3dium.mareshine;

import java.util.HashMap;
import java.util.Map;

public final class StickBehaviorTest {
    private static int checks;
    private static void expect(String name, boolean ok) {
        if (!ok) throw new AssertionError(name);
        checks++;
    }
    public static void main(String[] args) {
        Map<String, String> settings = new HashMap<>();
        expect("camera X defaults on", StickBehavior.inverted(settings, "SMS_CAMERA_INVERT_X"));
        expect("camera Y defaults off", !StickBehavior.inverted(settings, "SMS_CAMERA_INVERT_Y"));
        expect("FLUDD X defaults off", !StickBehavior.inverted(settings, "SMS_FLUDD_INVERT_X"));
        expect("FLUDD Y defaults off", !StickBehavior.inverted(settings, "SMS_FLUDD_INVERT_Y"));
        expect("sticks default normal", !StickBehavior.swapped(settings));
        settings.put("SMS_PAD_MAP", "A=b2;STICK=a2 CSTICK=a0 MENU=b4");
        expect("old saved swap detected", StickBehavior.swapped(settings));
        StickBehavior.toggleSwap(settings);
        expect("old swap can be undone", !StickBehavior.swapped(settings)
            && settings.get("SMS_PAD_MAP").contains("STICK=a0 CSTICK=a2"));
        expect("other buttons preserved", settings.get("SMS_PAD_MAP").contains("A=b2")
            && settings.get("SMS_PAD_MAP").contains("MENU=b4"));
        StickBehavior.toggleSwap(settings);
        expect("swap can be enabled again", StickBehavior.swapped(settings)
            && settings.get("SMS_PAD_MAP").contains("STICK=a2 CSTICK=a0"));
        StickBehavior.toggleInversion(settings, "SMS_FLUDD_INVERT_X");
        expect("FLUDD X on writes native flip", "1".equals(settings.get("SMS_FLUDD_INVERT_X"))
            && StickBehavior.inverted(settings, "SMS_FLUDD_INVERT_X"));
        expect("FLUDD X leaves other axes alone", !StickBehavior.inverted(settings, "SMS_FLUDD_INVERT_Y")
            && StickBehavior.inverted(settings, "SMS_CAMERA_INVERT_X"));
        StickBehavior.toggleInversion(settings, "SMS_CAMERA_INVERT_X");
        expect("camera X off writes native flip", "1".equals(settings.get("SMS_CAMERA_INVERT_X"))
            && !StickBehavior.inverted(settings, "SMS_CAMERA_INVERT_X"));
        StickBehavior.toggleInversion(settings, "SMS_FLUDD_INVERT_X");
        expect("FLUDD X returns to default", !StickBehavior.inverted(settings, "SMS_FLUDD_INVERT_X"));
        System.out.println(checks + " stick behavior checks passed");
    }
}
