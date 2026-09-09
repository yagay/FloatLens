package com.yagay.floatlens.hook;

import android.os.SystemClock;
import android.view.MotionEvent;
import java.util.List;
import java.util.Locale;

/**
 * Runtime-only summarizer for fooView FV touch sessions.
 * Confirmed codes are based on captured fooView 1.6.4 runtime traces.
 */
final class FvSessionTracker {
    private static final Object LOCK = new Object();
    private static Session current;
    private static long seq;

    static void onTouch(List<?> args) {
        MotionEvent e = null;
        for (Object o : args) if (o instanceof MotionEvent m) { e = m; break; }
        if (e == null) return;
        synchronized (LOCK) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                current = new Session(++seq, e);
                return;
            }
            if (current == null) return;
            current.sample(e);
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                current.ended = true;
                current.cancelled = a == MotionEvent.ACTION_CANCEL;
                current.upAt = e.getEventTime();
                // Wait briefly for c3.b(code)/Y1(code), which normally follow ACTION_UP.
                final long id = current.id;
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> flushIf(id), 180);
            }
        }
    }

    static void onCode(int code, String source) {
        synchronized (LOCK) {
            if (current == null) return;
            if ("b".equals(source)) current.bCode = code;
            if ("Y1".equals(source)) current.y1Code = code;
            if (current.ended && current.bCode != null && current.y1Code != null) flushLocked(current.id);
        }
    }

    private static void flushIf(long id) {
        synchronized (LOCK) { flushLocked(id); }
    }

    private static void flushLocked(long id) {
        if (current == null || current.id != id || current.summarySent) return;
        Session s = current;
        s.summarySent = true;
        long dur = Math.max(0, (s.upAt > 0 ? s.upAt : s.lastAt) - s.downAt);
        double dx = s.lastX - s.downX, dy = s.lastY - s.downY;
        double direct = Math.hypot(dx, dy);
        Integer code = s.bCode != null ? s.bCode : s.y1Code;
        String label = code == null ? "UNKNOWN" : label(code);
        HookLogTransport.log("SESSION_SUMMARY", String.format(Locale.US,
                "id=%d duration=%dms dx=%.1f dy=%.1f direct=%.1f path=%.1f points=%d code=%s y1=%s label=%s cancelled=%s",
                s.id, dur, dx, dy, direct, s.path, s.points,
                String.valueOf(s.bCode), String.valueOf(s.y1Code), label, s.cancelled));
        current = null;
    }

    static String label(int code) {
        return switch (code) {
            case 1 -> "SIDE_SHORT_CONFIRMED";
            case 2 -> "SIDE_LONG_CONFIRMED";
            case 4 -> "UP_CONFIRMED";
            case 9 -> "CLICK_CONFIRMED";
            case 10 -> "DOWN_CONFIRMED";
            case 30 -> "RECOGNIZE_PATH_CONFIRMED";
            case 6 -> "UNKNOWN_6";
            case 16 -> "UNKNOWN_16";
            default -> "UNKNOWN_" + code;
        };
    }

    private static final class Session {
        final long id;
        final long downAt;
        final float downX, downY;
        float lastX, lastY;
        long lastAt, upAt;
        double path;
        int points = 1;
        Integer bCode, y1Code;
        boolean ended, cancelled, summarySent;

        Session(long id, MotionEvent e) {
            this.id = id;
            downAt = e.getEventTime();
            downX = lastX = e.getRawX();
            downY = lastY = e.getRawY();
            lastAt = downAt;
        }
        void sample(MotionEvent e) {
            float x = e.getRawX(), y = e.getRawY();
            path += Math.hypot(x - lastX, y - lastY);
            lastX = x; lastY = y; lastAt = e.getEventTime(); points++;
        }
    }

    private FvSessionTracker() {}
}
