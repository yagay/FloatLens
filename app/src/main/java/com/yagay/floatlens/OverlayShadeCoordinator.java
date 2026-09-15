package com.yagay.floatlens;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * Thin frozen-overlay adapter over the single {@link FlSystemPanelController} implementation.
 * Circle Select no longer owns a second notification-shade dismissal algorithm.
 */
final class OverlayShadeCoordinator {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long RESULT_RECHECK_MS = 700L;

    interface Callback { void onComplete(boolean collapsed); }

    static void cleanup(Context context, boolean expandedAtCapture, String owner, Callback callback) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        String tag = owner == null || owner.isBlank() ? "overlay" : owner;
        Callback done = callback == null ? collapsed -> {} : callback;

        boolean liveExpanded = FlSystemPanelController.notificationShadeExpanded();
        if (!expandedAtCapture && !liveExpanded) {
            DiagnosticLog.i(app, "OVERLAY_SHADE", "skip owner=" + tag + " expanded=false");
            done.onComplete(true);
            return;
        }

        DiagnosticLog.i(app, "OVERLAY_SHADE", "delegate owner=" + tag
                + " captureExpanded=" + expandedAtCapture
                + " liveExpanded=" + liveExpanded);
        FlSystemPanelController.dismissAfterCapture(
                app, expandedAtCapture || liveExpanded, "overlay_" + tag);

        MAIN.postDelayed(() -> {
            boolean collapsed = !FlSystemPanelController.notificationShadeExpanded();
            DiagnosticLog.i(app, "OVERLAY_SHADE", "delegated recheck owner=" + tag
                    + " collapsed=" + collapsed + " delayMs=" + RESULT_RECHECK_MS);
            done.onComplete(collapsed);
        }, RESULT_RECHECK_MS);
    }

    private OverlayShadeCoordinator() {}
}
