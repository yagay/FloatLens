package com.yagay.floatlens;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewTreeObserver;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Bridges each captured result dialog's first frame back to its own capture state. */
final class ResultReadyCoordinator {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicLong NEXT_ID = new AtomicLong(1L);
    private static final long EXPIRE_MS = 4_000L;

    /**
     * Self-contained ticket: no global single-pending slot. Rapid captured results can therefore be
     * in flight at the same time without replacing each other's notification-shade state.
     */
    static final class Ticket {
        final long id;
        final android.content.Context app;
        final FlSystemPanelController.CaptureState state;
        final String reason;
        final long armedAt;
        final AtomicBoolean consumed = new AtomicBoolean(false);
        final AtomicBoolean attached = new AtomicBoolean(false);

        Ticket(long id, android.content.Context app,
               FlSystemPanelController.CaptureState state, String reason) {
            this.id = id;
            this.app = app == null ? null : app.getApplicationContext();
            this.state = state;
            this.reason = reason == null ? "result_dialog_shown" : reason;
            this.armedAt = SystemClock.uptimeMillis();
        }
    }

    static Ticket arm(Activity activity, FlSystemPanelController.CaptureState state, String reason) {
        return armInternal(activity == null ? null : activity.getApplicationContext(), state, reason);
    }

    static Ticket arm(android.content.Context context,
                      FlSystemPanelController.CaptureState state, String reason) {
        return armInternal(context == null ? null : context.getApplicationContext(), state, reason);
    }

    private static Ticket armInternal(android.content.Context app,
                                      FlSystemPanelController.CaptureState state, String reason) {
        if (state == null) return null;
        Ticket ticket = new Ticket(NEXT_ID.getAndIncrement(), app, state, reason);
        if (app != null) DiagnosticLog.i(app, "RESULT_READY", "arm id=" + ticket.id
                + " reason=" + ticket.reason);
        MAIN.postDelayed(() -> expire(ticket, app), EXPIRE_MS);
        return ticket;
    }

    static void cancel(Ticket ticket, android.content.Context context, String reason) {
        if (ticket == null || !ticket.consumed.compareAndSet(false, true)) return;
        android.content.Context app = context == null ? ticket.app : context.getApplicationContext();
        if (app != null) {
            DiagnosticLog.i(app, "RESULT_READY",
                    "cancel id=" + ticket.id + " reason=" + reason + " panelResolved=true");
            FlSystemPanelController.onResultReady(
                    app, ticket.state, reason == null ? ticket.reason : reason);
        }
    }

    interface FirstFrameCallback {
        void onFirstFrame(String stage);
    }

    static void afterFirstVisibleFrame(View root, FirstFrameCallback callback) {
        if (root == null || callback == null) return;
        root.post(() -> {
            ViewTreeObserver observer = root.getViewTreeObserver();
            if (!observer.isAlive()) {
                root.postOnAnimation(() -> callback.onFirstFrame("dialog_observer_dead"));
                return;
            }
            ViewTreeObserver.OnPreDrawListener listener = new ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    try {
                        ViewTreeObserver current = root.getViewTreeObserver();
                        if (current.isAlive()) current.removeOnPreDrawListener(this);
                    } catch (Throwable ignored) { }
                    root.postOnAnimation(() -> callback.onFirstFrame("dialog_first_frame"));
                    return true;
                }
            };
            observer.addOnPreDrawListener(listener);
            root.invalidate();
        });
    }

    static void onResultDialogReady(ResultActivity activity, View root, Ticket ticket) {
        if (activity == null || root == null || ticket == null || ticket.consumed.get()) return;
        if (!ticket.attached.compareAndSet(false, true)) return;

        DiagnosticLog.i(activity, "RESULT_READY", "dialog attached id=" + ticket.id
                + " reason=" + ticket.reason);
        afterFirstVisibleFrame(root, stage -> deliver(activity, ticket, stage));
    }

    private static void deliver(Activity activity, Ticket ticket, String stage) {
        if (!ticket.consumed.compareAndSet(false, true)) return;
        long elapsed = Math.max(0L, SystemClock.uptimeMillis() - ticket.armedAt);
        DiagnosticLog.i(activity, "RESULT_READY", "deliver id=" + ticket.id
                + " stage=" + stage + " elapsedMs=" + elapsed + " reason=" + ticket.reason);
        FlSystemPanelController.onResultReady(activity, ticket.state, ticket.reason);
    }

    private static void expire(Ticket ticket, android.content.Context app) {
        if (ticket == null || !ticket.consumed.compareAndSet(false, true)) return;
        android.content.Context target = app == null ? ticket.app : app.getApplicationContext();
        if (target != null) {
            DiagnosticLog.i(target, "RESULT_READY",
                    "expire id=" + ticket.id + " reason=" + ticket.reason
                            + " panelResolved=true");
            FlSystemPanelController.onResultReady(
                    target, ticket.state, ticket.reason + "_timeout");
        }
    }

    private ResultReadyCoordinator() { }
}
