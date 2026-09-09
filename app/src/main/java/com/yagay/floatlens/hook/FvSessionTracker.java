package com.yagay.floatlens.hook;

import android.view.MotionEvent;
import java.util.List;
import java.util.Locale;

/** Runtime-only summarizer for fooView FV touch sessions. */
final class FvSessionTracker {
    private static final Object LOCK = new Object();
    private static final String BUILD = "2.2.4-tracker-2";
    private static Session current;
    private static long seq;
    private static boolean buildLogged;

    static void onTouch(List<?> args) {
        if (!buildLogged) {
            buildLogged = true;
            HookLogTransport.log("TRACKER_BUILD", "build=" + BUILD);
        }
        MotionEvent e = null;
        for (Object o : args) if (o instanceof MotionEvent m) { e = m; break; }
        if (e == null) return;
        synchronized (LOCK) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                current = new Session(++seq, e);
                HookLogTransport.log("SESSION_START", String.format(Locale.US,
                        "id=%d raw=%.1f,%.1f t=%d", current.id, current.downX, current.downY, current.downAt));
                return;
            }
            if (current == null) return;
            current.sample(e);
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                current.ended = true;
                current.cancelled = a == MotionEvent.ACTION_CANCEL;
                current.upAt = e.getEventTime();
                final long id = current.id;
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> flushIf(id), 180);
            }
        }
    }

    static void onCode(int code, String source) {
        synchronized (LOCK) {
            HookLogTransport.log("FV_CODE", "source=" + source + " code=" + code + " label=" + label(code));
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
            case 0 -> "CIRCLE_TRACE_RELEASE_CLEANUP_CONFIRMED";
            case 1 -> "SIDE_SHORT_CONFIRMED";
            case 2 -> "SIDE_LONG_CONFIRMED";
            case 4 -> "UP_CONFIRMED";
            case 9 -> "CLICK_CONFIRMED";
            case 10 -> "DOWN_CONFIRMED";
            case 16 -> "LONG_HOLD_RELEASE_WITH_CIRCLE_ACTIVE_CONFIRMED";
            case 30 -> "OCR_CAPTURE_TRIGGER_CONFIRMED";
            case 6 -> "UNKNOWN_6";
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
