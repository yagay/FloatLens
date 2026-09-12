package com.yagay.floatlens;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * Background notification-shade cleanup shared by frozen accessibility-overlay surfaces.
 *
 * The visible result/workspace is already attached above SystemUI, so cleanup never blocks showing
 * it. GLOBAL_ACTION_BACK is issued only after a fresh accessibility probe still proves the shade is
 * expanded, and at most twice to handle Quick Settings -> notification shade -> collapsed.
 */
final class OverlayShadeCoordinator {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long DISMISS_RECHECK_MS = 90L;
    private static final long BACK_RECHECK_MS = 140L;
    private static final int MAX_BACK_ATTEMPTS = 2;

    interface Callback {
        void onComplete(boolean collapsed);
    }

    static void cleanup(Context context, boolean expandedAtCapture, String owner, Callback callback) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        String tag = owner == null || owner.isBlank() ? "overlay" : owner;
        Callback done = callback == null ? collapsed -> {} : callback;

        boolean liveExpanded = FvSystemPanelController.notificationShadeExpanded();
        if (!expandedAtCapture && !liveExpanded) {
            DiagnosticLog.i(app, "OVERLAY_SHADE", "skip owner=" + tag + " expanded=false");
            done.onComplete(true);
            return;
        }

        LensAccessibilityService service = LensAccessibilityService.get();
        if (service == null) {
            DiagnosticLog.i(app, "OVERLAY_SHADE", "no accessibility service owner=" + tag);
            done.onComplete(false);
            return;
        }

        boolean dismiss15 = false;
        try {
            dismiss15 = service.global(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
        } catch (Throwable t) {
            DiagnosticLog.i(app, "OVERLAY_SHADE", "global15 failed owner=" + tag + " error=" + t);
        }
        DiagnosticLog.i(app, "OVERLAY_SHADE", "begin owner=" + tag
                + " captureExpanded=" + expandedAtCapture
                + " liveExpanded=" + liveExpanded
                + " global15=" + dismiss15);

        MAIN.postDelayed(() -> checkThenBack(app, service, tag, done, 0), DISMISS_RECHECK_MS);
    }

    private static void checkThenBack(Context app, LensAccessibilityService service, String owner,
                                      Callback callback, int completedBackAttempts) {
        boolean expanded = FvSystemPanelController.notificationShadeExpanded();
        DiagnosticLog.i(app, "OVERLAY_SHADE", "recheck owner=" + owner
                + " expanded=" + expanded
                + " completedBackAttempts=" + completedBackAttempts);
        if (!expanded) {
            callback.onComplete(true);
            return;
        }
        if (completedBackAttempts >= MAX_BACK_ATTEMPTS) {
            DiagnosticLog.i(app, "OVERLAY_SHADE", "exhausted owner=" + owner
                    + " shade still expanded");
            callback.onComplete(false);
            return;
        }

        boolean back = false;
        try {
            // Safe because a fresh TYPE_SYSTEM probe immediately above proved shade=true.
            back = service.global(AccessibilityService.GLOBAL_ACTION_BACK);
        } catch (Throwable t) {
            DiagnosticLog.i(app, "OVERLAY_SHADE", "global back failed owner=" + owner
                    + " error=" + t);
        }
        int attempt = completedBackAttempts + 1;
        DiagnosticLog.i(app, "OVERLAY_SHADE", "BACK owner=" + owner
                + " attempt=" + attempt + " issued=" + back);

        MAIN.postDelayed(() -> checkThenBack(app, service, owner, callback, attempt),
                BACK_RECHECK_MS);
    }

    private OverlayShadeCoordinator() {}
}
