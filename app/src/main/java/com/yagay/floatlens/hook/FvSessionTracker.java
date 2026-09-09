package com.yagay.floatlens.hook;

import android.view.MotionEvent;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/** Runtime-only summarizer for fooView FV touch sessions with nested Circle support. */
final class FvSessionTracker {
    private static final Object LOCK = new Object();
    private static final String BUILD = "2.2.6-tracker-1";
    private static final Deque<Session> sessions = new ArrayDeque<>();
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
                Long parent = sessions.peekLast() != null ? sessions.peekLast().id : null;
                Session s = new Session(++seq, parent, e);
                sessions.addLast(s);
                HookLogTransport.log("SESSION_START", String.format(Locale.US,
                        "id=%d parent=%s depth=%d raw=%.1f,%.1f t=%d",
                        s.id, String.valueOf(parent), sessions.size(), s.downX, s.downY, s.downAt));
                return;
            }
            Session s = sessions.peekLast();
            if (s == null) return;
            s.sample(e);
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                s.ended = true;
                s.cancelled = a == MotionEvent.ACTION_CANCEL;
                s.upAt = e.getEventTime();
                final long id = s.id;
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> flushIf(id), 220);
            }
        }
    }

    static void onCode(int code, String source) {
        synchronized (LOCK) {
            HookLogTransport.log("FV_CODE", "source=" + source + " code=" + code + " label=" + label(code));
            Session s = sessions.peekLast();
            if (s == null) return;
            if ("b".equals(source)) s.bCode = code;
            if ("Y1".equals(source)) s.y1Code = code;
            if (code == 16) s.enteredCircle = true;
            if (code == 30) s.recognitionTriggered = true;
            if (s.ended && s.bCode != null && s.y1Code != null) flushLocked(s.id);
        }
    }

    static void onActionLayer(String method, List<?> args) {
        HookLogTransport.log("ACTION_LAYER", "method=" + method + " args=" + HookFmt.args(args) + " stack=" + HookFmt.stack(7));
    }

    static void onDownstream(String owner, String method, List<?> args) {
        HookLogTransport.log("ACTION_DOWNSTREAM", "owner=" + owner + " method=" + method + " args=" + HookFmt.args(args) + " stack=" + HookFmt.stack(8));
    }

    private static void flushIf(long id) {
        synchronized (LOCK) { flushLocked(id); }
    }

    private static void flushLocked(long id) {
        Session target = null;
        for (Session s : sessions) if (s.id == id) { target = s; break; }
        if (target == null || target.summarySent) return;
        Session s = target;
        s.summarySent = true;
        long dur = Math.max(0, (s.upAt > 0 ? s.upAt : s.lastAt) - s.downAt);
        double dx = s.lastX - s.downX, dy = s.lastY - s.downY;
        double direct = Math.hypot(dx, dy);
        Integer code = s.bCode != null ? s.bCode : s.y1Code;
        String label = code == null ? "UNKNOWN" : label(code);
        HookLogTransport.log("SESSION_SUMMARY", String.format(Locale.US,
                "id=%d parent=%s depth=%d duration=%dms dx=%.1f dy=%.1f direct=%.1f path=%.1f points=%d code=%s y1=%s label=%s circle=%s recognize=%s cancelled=%s",
                s.id, String.valueOf(s.parentId), depthOf(s), dur, dx, dy, direct, s.path, s.points,
                String.valueOf(s.bCode), String.valueOf(s.y1Code), label, s.enteredCircle, s.recognitionTriggered, s.cancelled));
        sessions.remove(s);
    }

    private static int depthOf(Session s) {
        int d = 1;
        Long p = s.parentId;
        while (p != null) {
            Session found = null;
            for (Session x : sessions) if (x.id == p) { found = x; break; }
            if (found == null) break;
            d++; p = found.parentId;
        }
        return d;
    }

    static String label(int code) {
        return switch (code) {
            case 0 -> "CIRCLE_RELEASE_FINISH_CONFIRMED";
            case 1 -> "SIDE_SHORT_CONFIRMED";
            case 2 -> "SIDE_LONG_CONFIRMED";
            case 4 -> "UP_CONFIRMED";
            case 9 -> "TAP_DISPATCH_CONFIRMED";
            case 10 -> "DOWN_CONFIRMED";
            case 16 -> "ENTER_CIRCLE_MODE_CONFIRMED";
            case 30 -> "GESTURE_RECOGNIZE_PATH_CONFIRMED";
            case 6 -> "UNKNOWN_6";
            default -> "UNKNOWN_" + code;
        };
    }

    private static final class Session {
        final long id;
        final Long parentId;
        final long downAt;
        final float downX, downY;
        float lastX, lastY;
        long lastAt, upAt;
        double path;
        int points = 1;
        Integer bCode, y1Code;
        boolean ended, cancelled, summarySent, enteredCircle, recognitionTriggered;

        Session(long id, Long parentId, MotionEvent e) {
            this.id = id;
            this.parentId = parentId;
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
