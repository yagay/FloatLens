package com.yagay.floatlens;

import android.content.Context;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Single top-level identity/state owner for user-visible FloatLens work.
 *
 * <p>Subsystems may keep their own short-lived request counters (for example OCR latest-wins), but
 * those counters are children of exactly one current workflow session.</p>
 */
final class WorkflowSessionManager {
    enum Type { RECOGNITION, CIRCLE, OCR, GOOGLE_CTS, SCREENSHOT, VIEW }
    enum Phase {
        CREATED, CAPTURING, SELECTING, RECOGNIZING, RESULT_PENDING, RESULT_VISIBLE,
        FINISHED, CANCELLED, FAILED
    }

    static final class Session {
        private final long id;
        private final Type type;
        private final String externalKey;
        private volatile Phase phase;
        private volatile String reason;

        private Session(long id, Type type, String externalKey, Phase phase, String reason) {
            this.id = id;
            this.type = type;
            this.externalKey = externalKey == null ? "" : externalKey;
            this.phase = phase;
            this.reason = reason == null ? "" : reason;
        }

        long id() { return id; }
        Type type() { return type; }
        Phase phase() { return phase; }
        String externalKey() { return externalKey; }
        String reason() { return reason; }
        boolean current() { return WorkflowSessionManager.isCurrent(this); }
        boolean terminal() {
            return phase == Phase.FINISHED || phase == Phase.CANCELLED || phase == Phase.FAILED;
        }
    }

    private static final Object LOCK = new Object();
    private static final AtomicLong NEXT = new AtomicLong(1L);
    private static Session active;

    static Session begin(Context context, Type type, String reason) {
        return beginExternal(context, type, "", reason);
    }

    static Session beginExternal(Context context, Type type, String externalKey, String reason) {
        Session previous;
        Session next;
        synchronized (LOCK) {
            previous = active;
            if (previous != null && !previous.terminal()) {
                previous.phase = Phase.CANCELLED;
                previous.reason = "replaced:" + safe(reason);
            }
            next = new Session(NEXT.getAndIncrement(),
                    type == null ? Type.RECOGNITION : type,
                    externalKey, Phase.CREATED, safe(reason));
            active = next;
        }
        if (previous != null) {
            int released = OverlaySceneManager.close(previous.id, "workflow_replaced");
            log(context, "overlay scene close id=" + previous.id + " released=" + released
                    + " reason=workflow_replaced");
        }
        OverlaySceneManager.open(next.id);
        log(context, "begin " + describe(next)
                + (previous == null ? "" : " replaced=" + previous.id));
        return next;
    }

    /** Reuse the current workflow when one exists; otherwise start the supplied fallback type. */
    static Session ensureCurrent(Context context, Type fallbackType, String reason) {
        synchronized (LOCK) {
            if (active != null && !active.terminal()) return active;
        }
        return begin(context, fallbackType, reason);
    }

    static Session current() {
        synchronized (LOCK) { return active; }
    }

    static long currentId() {
        Session session = current();
        return session == null ? 0L : session.id;
    }

    /** True while a FloatLens-owned Google CTS flow is still being captured/selected/handed off. */
    static boolean googleCtsInFlight() {
        synchronized (LOCK) {
            if (active == null || active.terminal() || active.type != Type.GOOGLE_CTS) {
                return false;
            }
            return switch (active.phase) {
                case CREATED, CAPTURING, SELECTING, RESULT_PENDING -> true;
                default -> false;
            };
        }
    }

    static boolean isCurrent(Session session) {
        if (session == null) return false;
        synchronized (LOCK) {
            return active == session && !session.terminal();
        }
    }

    static boolean isCurrent(long id) {
        if (id <= 0L) return false;
        synchronized (LOCK) {
            return active != null && active.id == id && !active.terminal();
        }
    }

    static boolean matchesExternal(String externalKey) {
        if (externalKey == null || externalKey.isBlank()) return false;
        synchronized (LOCK) {
            return active != null && !active.terminal()
                    && externalKey.equals(active.externalKey);
        }
    }

    static void transition(Context context, Session session, Phase next, String reason) {
        if (session == null || next == null) return;
        Phase old;
        synchronized (LOCK) {
            if (active != session || session.terminal()) return;
            old = session.phase;
            session.phase = next;
            session.reason = safe(reason);
        }
        if (old != next) log(context, "transition id=" + session.id
                + " " + old + "->" + next + " reason=" + safe(reason));
    }

    static void transitionCurrent(Context context, Phase next, String reason) {
        Session session = ensureCurrent(context, Type.RECOGNITION, reason);
        transition(context, session, next, reason);
    }

    static void finish(Context context, Session session, String reason) {
        end(context, session, Phase.FINISHED, reason);
    }

    static void fail(Context context, Session session, String reason) {
        end(context, session, Phase.FAILED, reason);
    }

    static void cancel(Context context, Session session, String reason) {
        end(context, session, Phase.CANCELLED, reason);
    }

    static void finishCurrent(Context context, String reason) {
        Session session = current();
        finish(context, session, reason);
    }

    static void finishExternal(Context context, String externalKey, String reason) {
        Session session;
        synchronized (LOCK) {
            session = active != null && externalKey != null
                    && externalKey.equals(active.externalKey) ? active : null;
        }
        finish(context, session, reason);
    }

    private static void end(Context context, Session session, Phase terminal, String reason) {
        if (session == null) return;
        Phase old;
        synchronized (LOCK) {
            if (active != session || session.terminal()) return;
            old = session.phase;
            session.phase = terminal;
            session.reason = safe(reason);
            active = null;
        }
        int released = OverlaySceneManager.close(session.id, safe(reason));
        log(context, "end id=" + session.id + " " + old + "->" + terminal
                + " reason=" + safe(reason) + " overlayReleased=" + released);
    }

    private static String describe(Session s) {
        return "id=" + s.id + " type=" + s.type
                + (s.externalKey.isBlank() ? "" : " external=" + shortKey(s.externalKey))
                + " reason=" + s.reason;
    }

    private static String shortKey(String value) {
        if (value == null || value.isBlank()) return "none";
        return value.substring(0, Math.min(8, value.length()));
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private static void log(Context context, String message) {
        if (context != null) DiagnosticLog.i(context.getApplicationContext(), "WORKFLOW", message);
    }

    private WorkflowSessionManager() {}
}
