package io.github.them3dium.mareshine;

// Capture a physical SDL stick pair without interpreting its direction as
// inversion. Wait for release so the stick used to open the row is not bound.
final class ControllerStickCapture {
    private boolean ready;

    void reset() { ready = false; }

    String update(float lx, float ly, float rx, float ry) {
        float left = Math.max(Math.abs(lx), Math.abs(ly));
        float right = Math.max(Math.abs(rx), Math.abs(ry));
        if (!Float.isFinite(left) || !Float.isFinite(right)) return null;
        if (!ready) {
            ready = left < 0.25f && right < 0.25f;
            return null;
        }
        if (Math.max(left, right) < 0.6f || Math.abs(left - right) < 0.2f) return null;
        return left > right ? "a0" : "a2";
    }
}
