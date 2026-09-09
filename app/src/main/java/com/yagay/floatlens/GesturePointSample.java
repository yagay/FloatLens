package com.yagay.floatlens;

/** Immutable sampled point from one floating-icon gesture session. Avoid Java record desugaring on Android. */
public final class GesturePointSample {
    private final float x;
    private final float y;
    private final long timeMs;

    public GesturePointSample(float x, float y, long timeMs) {
        this.x = x;
        this.y = y;
        this.timeMs = timeMs;
    }

    public float x() { return x; }
    public float y() { return y; }
    public long timeMs() { return timeMs; }

    @Override public String toString() {
        return "GesturePointSample{" + x + "," + y + ",t=" + timeMs + "}";
    }
}
