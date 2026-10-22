package io.github.them3dium.mareshine;

import java.util.Map;

// Keep the UI convention separate from flags that reverse the game's input.
final class StickBehavior {
    static boolean inverted(Map<String, String> settings, String key) {
        boolean flip = !"0".equals(settings.getOrDefault(key, "0"));
        return key.equals("SMS_CAMERA_INVERT_X") ? !flip : flip;
    }

    static void toggleInversion(Map<String, String> settings, String key) {
        boolean flip = !"0".equals(settings.getOrDefault(key, "0"));
        settings.put(key, flip ? "0" : "1");
    }

    static boolean swapped(Map<String, String> settings) {
        for (String token : settings.getOrDefault("SMS_PAD_MAP", "").trim().split("[\\s;]+")) {
            if (token.regionMatches(true, 0, "STICK=", 0, 6)) return token.substring(6).equals("a2");
        }
        return false;
    }

    private static void bind(Map<String, String> settings, String key, String value) {
        StringBuilder map = new StringBuilder();
        for (String token : settings.getOrDefault("SMS_PAD_MAP", "").trim().split("[\\s;]+")) {
            if (token.isEmpty() || token.regionMatches(true, 0, key + "=", 0, key.length() + 1)) continue;
            if (map.length() > 0) map.append(' ');
            map.append(token);
        }
        if (map.length() > 0) map.append(' ');
        settings.put("SMS_PAD_MAP", map.append(key).append('=').append(value).toString());
    }

    static void toggleSwap(Map<String, String> settings) {
        boolean swap = !swapped(settings);
        bind(settings, "STICK", swap ? "a2" : "a0");
        bind(settings, "CSTICK", swap ? "a0" : "a2");
    }
}
