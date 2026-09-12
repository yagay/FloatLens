package com.yagay.floatlens;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewTreeObserver;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Bridges ResultActivity lifecycle back to the FV-style capture state machine.
 *
 * FV closes the live notification shade only after its frozen/candidate result surface is actually
 * shown. Calling startActivity() is too early: it only schedules an Activity launch. This coordinator
 * arms the next ResultActivity and delivers onResultReady() after its first real draw boundary.
 */
final class ResultReadyCoordinator {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicLong NEXT_ID = new AtomicLong(1L);
    private static final Object LOCK = new Object();
    private static final long EXPIRE_MS = 4_000L;

    private static Pending pending;

    static final class Ticket {
        final long id;
        Ticket(long id) { this.id = id; }
    }

    private static final class Pending {
        final Ticket ticket;
        final FvSystemPanelController.CaptureState state;
        final String reason;
        final long armedAt;
        boolean attached;

        Pending(Ticket ticket, FvSystemPanelController.CaptureState state, String reason) {
            this.ticket = ticket;
            this.state = state;
            this.reason = reason == null ? "result_activity_shown" : reason;
            this.armedAt = SystemClock.uptimeMillis();
        }
    }

    static Ticket arm(Activity activity, FvSystemPanelController.CaptureState state, String reason) {
        return armInternal(activity == null ? null : activity.getApplicationContext(), state, reason);
    }

    static Ticket arm(android.content.Context context,
                      FvSystemPanelController.CaptureState state, String reason) {
        return armInternal(context == null ? null : context.getApplicationContext(), state, reason);
    }

    private static Ticket armInternal(android.content.Context app,
                                      FvSystemPanelController.CaptureState state, String reason) {
        if (state == null) return null;
        Ticket ticket = new Ticket(NEXT_ID.getAndIncrement());
        Pending next = new Pending(ticket, state, reason);
        Pending replaced;
        synchronized (LOCK) {
            replaced = pending;
            pending = next;
        }
        if (app != null) {
            DiagnosticLog.i(app, "RESULT_READY", "arm id=" + ticket.id
                    + " reason=" + next.reason
                    + (replaced == null ? "" : " replaced=" + replaced.ticket.id));
        }
        MAIN.postDelayed(() -> expire(ticket, app), EXPIRE_MS);
        return ticket;
    }

    static void cancel(Ticket ticket, android.content.Context context, String reason) {
        if (ticket == null) return;
        boolean removed = false;
        synchronized (LOCK) {
            if (pending != null && pending.ticket.id == ticket.id) {
                pending = null;
                removed = true;
            }
        }
        if (removed && context != null) {
            DiagnosticLog.i(context.getApplicationContext(), "RESULT_READY",
                    "cancel id=" + ticket.id + " reason=" + reason);
        }
    }

    static void onResultActivityResumed(Activity activity) {
        if (!(activity instanceof ResultActivity) || activity.getWindow() == null) return;

        Pending selected;
        synchronized (LOCK) {
            selected = pending;
            if (selected == null || selected.attached) return;
            selected.attached = true;
        }

        final Pending target = selected;
        View decor = activity.getWindow().getDecorView();
        if (decor == null) {
            deliver(activity, target, "no_decor");
            return;
        }

        DiagnosticLog.i(activity, "RESULT_READY", "resumed id=" + target.ticket.id
                + " reason=" + target.reason);

        ViewTreeObserver observer = decor.getViewTreeObserver();
        if (!observer.isAlive()) {
            decor.post(() -> deliver(activity, target, "observer_dead"));
            return;
        }

        ViewTreeObserver.OnPreDrawListener listener = new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                try {
                    ViewTreeObserver current = decor.getViewTreeObserver();
                    if (current.isAlive()) current.removeOnPreDrawListener(this);
                } catch (Throwable ignored) {}

                // Run on the following frame. At this point the ResultActivity window has been
                // attached and its first content frame has crossed the draw boundary.
                decor.postOnAnimation(() -> deliver(activity, target, "first_frame"));
                return true;
            }
        };
        observer.addOnPreDrawListener(listener);
        decor.invalidate();
    }

    private static void deliver(Activity activity, Pending target, String stage) {
        boolean accepted = false;
        synchronized (LOCK) {
            if (pending == target) {
                pending = null;
                accepted = true;
            }
        }
        if (!accepted) return;

        long elapsed = Math.max(0L, SystemClock.uptimeMillis() - target.armedAt);
        DiagnosticLog.i(activity, "RESULT_READY", "deliver id=" + target.ticket.id
                + " stage=" + stage + " elapsedMs=" + elapsed
                + " reason=" + target.reason);
        FvSystemPanelController.onResultReady(activity, target.state, target.reason);
    }

    private static void expire(Ticket ticket, android.content.Context app) {
        Pending expired = null;
        synchronized (LOCK) {
            if (pending != null && pending.ticket.id == ticket.id) {
                expired = pending;
                pending = null;
            }
        }
        if (expired != null && app != null) {
            DiagnosticLog.i(app, "RESULT_READY", "expire id=" + ticket.id
                    + " reason=" + expired.reason);
        }
    }

    private ResultReadyCoordinator() {}
}
