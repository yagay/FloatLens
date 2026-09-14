package com.yagay.floatlens;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Single launch/update boundary for the one official ResultActivity host. */
final class ResultController {
    static final String EXTRA_TOKEN = "result_token";
    private static final long PENDING_TTL_MS = 15_000L;
    private static final int MAX_PENDING = 8;
    private static final AtomicLong NEXT = new AtomicLong(1L);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<Long, Pending> PENDING = new ConcurrentHashMap<>();

    private static final class Pending {
        final ResultSession session;
        final long createdAt;
        Pending(ResultSession session) {
            this.session = session;
            createdAt = SystemClock.uptimeMillis();
        }
    }

    static boolean show(Context c, ResultSession session) {
        if (c == null || session == null) return false;
        Context app = c.getApplicationContext();
        cleanupExpired(app);
        long token = NEXT.getAndIncrement();
        PENDING.put(token, new Pending(session));
        trimOverflow(app);
        MAIN.postDelayed(() -> expire(token, app), PENDING_TTL_MS);

        Intent intent = new Intent(app, ResultActivity.class)
                .putExtra(EXTRA_TOKEN, token)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            app.startActivity(intent);
            DiagnosticLog.i(app, "RESULT_CONTROLLER", "show token=" + token
                    + " mode=" + session.mode() + " origin=" + session.originMode());
            return true;
        } catch (Throwable t) {
            PENDING.remove(token);
            DiagnosticLog.i(app, "RESULT_CONTROLLER", "show failed token=" + token
                    + " error=" + ScreenCaptureBackend.safeMessage(t));
            return false;
        }
    }

    static boolean showCaptured(Context c, ResultSession session,
                                FlSystemPanelController.CaptureState shadeState, String reason) {
        if (c == null || session == null) return false;
        Context app = c.getApplicationContext();
        ResultReadyCoordinator.Ticket ticket = ResultReadyCoordinator.arm(app, shadeState, reason);
        boolean shown = show(app, session);
        DiagnosticLog.i(app, "RESULT_CONTROLLER", "captured mode=" + session.mode()
                + " shown=" + shown + " reason=" + reason);
        if (!shown) ResultReadyCoordinator.cancel(ticket, app, reason + "_start_failed");
        return shown;
    }

    static ResultSession take(long token) {
        if (token == 0L) return null;
        Pending pending = PENDING.remove(token);
        if (pending == null) return null;
        if (SystemClock.uptimeMillis() - pending.createdAt > PENDING_TTL_MS) return null;
        return pending.session;
    }

    static void discard(long token) { if (token != 0L) PENDING.remove(token); }

    private static void expire(long token, Context app) {
        Pending pending = PENDING.get(token);
        if (pending == null) return;
        if (SystemClock.uptimeMillis() - pending.createdAt < PENDING_TTL_MS) return;
        if (PENDING.remove(token, pending)) {
            DiagnosticLog.i(app, "RESULT_CONTROLLER", "expire unconsumed token=" + token);
        }
    }

    private static void cleanupExpired(Context app) {
        long now = SystemClock.uptimeMillis();
        for (Map.Entry<Long, Pending> entry : PENDING.entrySet()) {
            Pending pending = entry.getValue();
            if (pending != null && now - pending.createdAt >= PENDING_TTL_MS
                    && PENDING.remove(entry.getKey(), pending)) {
                DiagnosticLog.i(app, "RESULT_CONTROLLER", "cleanup expired token=" + entry.getKey());
            }
        }
    }

    private static void trimOverflow(Context app) {
        while (PENDING.size() > MAX_PENDING) {
            long oldestToken = 0L;
            Pending oldest = null;
            for (Map.Entry<Long, Pending> entry : PENDING.entrySet()) {
                Pending p = entry.getValue();
                if (p != null && (oldest == null || p.createdAt < oldest.createdAt)) {
                    oldest = p;
                    oldestToken = entry.getKey();
                }
            }
            if (oldest == null || oldestToken == 0L || !PENDING.remove(oldestToken, oldest)) break;
            DiagnosticLog.i(app, "RESULT_CONTROLLER", "trim pending token=" + oldestToken);
        }
    }

    private ResultController() {}
}
