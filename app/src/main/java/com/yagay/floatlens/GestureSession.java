package com.yagay.floatlens;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One complete touch session. fooView also records a full point stream instead of only start/end. */
final class GestureSession {
    enum Phase { IDLE, DOWN, GESTURE, ICON_DRAG, FINISHING }
    final ArrayList<GesturePointSample> points = new ArrayList<>();
    Phase phase = Phase.IDLE;
    long downAt;
    float downX, downY, lastX, lastY;
    boolean longPressReady;
    boolean moved;
    boolean multiTouch;

    void begin(float x, float y, long t) {
        points.clear();
        phase = Phase.DOWN;
        downAt = t;
        downX = lastX = x;
        downY = lastY = y;
        longPressReady = false;
        moved = false;
        multiTouch = false;
        add(x, y, t);
    }

    void add(float x, float y, long t) {
        lastX = x; lastY = y;
        points.add(new GesturePointSample(x, y, t));
    }

    float dx() { return lastX - downX; }
    float dy() { return lastY - downY; }
    float distance() { return (float)Math.hypot(dx(), dy()); }
    long duration(long now) { return Math.max(0L, now - downAt); }
    List<GesturePointSample> snapshot() { return Collections.unmodifiableList(new ArrayList<>(points)); }
    void reset() { points.clear(); phase = Phase.IDLE; longPressReady = moved = multiTouch = false; }
}
