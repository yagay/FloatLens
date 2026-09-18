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

    static final class Delivery {
        final ResultSession session;
        final ResultReadyCoordinator.Ticket readyTicket;
        final Runnable firstFrameCallback;
        Delivery(ResultSession session, ResultReadyCoordinator.Ticket readyTicket,
                 Runnable firstFrameCallback) {
            this.session = session;
            this.readyTicket = readyTicket;
            this.firstFrameCallback = firstFrameCallback;
        }
    }

    private static final class Pending {
        final ResultSession session;
        final ResultReadyCoordinator.Ticket readyTicket;
        final Runnable firstFrameCallback;
        final long createdAt;
        Pending(ResultSession session, ResultReadyCoordinator.Ticket readyTicket,
                Runnable firstFrameCallback) {
            this.session = session;
            this.readyTicket = readyTicket;
            this.firstFrameCallback = firstFrameCallback;
            createdAt = SystemClock.uptimeMillis();
        }
    }

    static boolean show(Context c, ResultSession session) {
        return showInternal(c, session, null, null);
    }

    static boolean showAfterFirstFrame(Context c, ResultSession session, Runnable callback) {
        return showInternal(c, session, null, callback);
    }

    private static boolean showInternal(Context c, ResultSession session,
                                        ResultReadyCoordinator.Ticket readyTicket,
                                        Runnable firstFrameCallback) {
        if (c == null || session == null) return false;
        Context app = c.getApplicationContext();
        cleanupExpired(app);
        long token = NEXT.getAndIncrement();
        PENDING.put(token, new Pending(session, readyTicket, firstFrameCallback));
        trimOverflow(app);
        MAIN.postDelayed(() -> expire(token, app), PENDING_TTL_MS);

        Intent intent = new Intent(app, ResultActivity.class)
                .putExtra(EXTRA_TOKEN, token)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            app.startActivity(intent);
            DiagnosticLog.i(app, "RESULT_CONTROLLER", "show token=" + token
                    + " mode=" + session.mode() + " origin=" + session.originMode()
                    + " ready=" + (readyTicket == null ? "none" : readyTicket.id));
            return true;
        } catch (Throwable t) {
            Pending removed = PENDING.remove(token);
            if (removed != null && removed.readyTicket != null) {
                ResultReadyCoordinator.cancel(removed.readyTicket, app, "activity_start_failed");
            }
            // Do not close the session here. show() returning false transfers ownership back to the
            // caller, which may save/reuse the same bitmap as its fallback path.
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
        boolean shown = showInternal(app, session, ticket, null);
        DiagnosticLog.i(app, "RESULT_CONTROLLER", "captured mode=" + session.mode()
                + " shown=" + shown + " reason=" + reason);
        if (!shown) ResultReadyCoordinator.cancel(ticket, app, reason + "_start_failed");
        return shown;
    }

    static Delivery take(long token) {
        if (token == 0L) return null;
        Pending pending = PENDING.remove(token);
        if (pending == null) return null;
        if (SystemClock.uptimeMillis() - pending.createdAt > PENDING_TTL_MS) {
            ResultReadyCoordinator.cancel(pending.readyTicket, null, "result_token_expired");
            close(pending.session);
            return null;
        }
        return new Delivery(pending.session, pending.readyTicket, pending.firstFrameCallback);
    }

    static void discard(long token) {
        if (token == 0L) return;
        Pending pending = PENDING.remove(token);
        if (pending != null) {
            ResultReadyCoordinator.cancel(pending.readyTicket, null, "result_discarded");
            close(pending.session);
        }
    }

    private static void expire(long token, Context app) {
        Pending pending = PENDING.get(token);
        if (pending == null) return;
        if (SystemClock.uptimeMillis() - pending.createdAt < PENDING_TTL_MS) return;
        if (PENDING.remove(token, pending)) {
            ResultReadyCoordinator.cancel(pending.readyTicket, app, "unconsumed_result_expired");
            close(pending.session);
            DiagnosticLog.i(app, "RESULT_CONTROLLER", "expire unconsumed token=" + token);
        }
    }

    private static void cleanupExpired(Context app) {
        long now = SystemClock.uptimeMillis();
        for (Map.Entry<Long, Pending> entry : PENDING.entrySet()) {
            Pending pending = entry.getValue();
            if (pending != null && now - pending.createdAt >= PENDING_TTL_MS
                    && PENDING.remove(entry.getKey(), pending)) {
                ResultReadyCoordinator.cancel(pending.readyTicket, app, "cleanup_expired_result");
                close(pending.session);
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
            ResultReadyCoordinator.cancel(oldest.readyTicket, app, "pending_result_trimmed");
            close(oldest.session);
            DiagnosticLog.i(app, "RESULT_CONTROLLER", "trim pending token=" + oldestToken);
        }
    }

    private static void close(ResultSession session) {
        if (session == null) return;
        try { session.close(); } catch (Throwable ignored) {}
    }

    private ResultController() {}
}
