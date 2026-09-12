package com.yagay.floatlens;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * Cleans the live notification shade underneath the frozen Circle Select accessibility overlay.
 *
 * The visible Circle workspace is already frozen and lives above SystemUI, so shade cleanup must
 * never block opening the workspace. On ROMs that do not expose GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE
 * (the tested OxygenOS build does not), use GLOBAL_ACTION_BACK only while the accessibility window
 * probe still proves the shade is expanded. At most two BACK actions are issued, which also handles
 * a two-level Quick Settings -> notification shade collapse without leaking BACK to the app below.
 */
final class CircleShadeCoordinator {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long DISMISS_RECHECK_MS = 90L;
    private static final long BACK_RECHECK_MS = 140L;
    private static final int MAX_BACK_ATTEMPTS = 2;

    interface Callback {
        void onReady(boolean collapsed);
    }

    static void prepare(Context context, boolean expandedAtCapture, Callback callback) {
        Context app = context.getApplicationContext();
        boolean liveExpanded = FvSystemPanelController.notificationShadeExpanded();
        if (!expandedAtCapture && !liveExpanded) {
            DiagnosticLog.i(app, "CIRCLE_SHADE", "background cleanup skip expanded=false");
            callback.onReady(true);
            return;
        }

        LensAccessibilityService service = LensAccessibilityService.get();
        if (service == null) {
            DiagnosticLog.i(app, "CIRCLE_SHADE", "background cleanup no accessibility service");
            callback.onReady(false);
            return;
        }

        boolean dismiss15 = false;
        try {
            dismiss15 = service.global(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
        } catch (Throwable t) {
            DiagnosticLog.i(app, "CIRCLE_SHADE", "global15 failed=" + t);
        }
        DiagnosticLog.i(app, "CIRCLE_SHADE", "background cleanup begin captureExpanded="
                + expandedAtCapture + " liveExpanded=" + liveExpanded + " global15=" + dismiss15);

        MAIN.postDelayed(() -> checkThenBack(app, service, callback, 0), DISMISS_RECHECK_MS);
    }

    private static void checkThenBack(Context app, LensAccessibilityService service,
                                      Callback callback, int completedBackAttempts) {
        boolean expanded = FvSystemPanelController.notificationShadeExpanded();
        DiagnosticLog.i(app, "CIRCLE_SHADE", "cleanup recheck expanded=" + expanded
                + " completedBackAttempts=" + completedBackAttempts);
        if (!expanded) {
            callback.onReady(true);
            return;
        }
        if (completedBackAttempts >= MAX_BACK_ATTEMPTS) {
            DiagnosticLog.i(app, "CIRCLE_SHADE", "cleanup exhausted shade still expanded");
            callback.onReady(false);
            return;
        }

        boolean back = false;
        try {
            // Important: this is only issued after a fresh TYPE_SYSTEM probe proved shade=true.
            back = service.global(AccessibilityService.GLOBAL_ACTION_BACK);
        } catch (Throwable t) {
            DiagnosticLog.i(app, "CIRCLE_SHADE", "global back failed=" + t);
        }
        int attempt = completedBackAttempts + 1;
        DiagnosticLog.i(app, "CIRCLE_SHADE", "global BACK attempt=" + attempt
                + " issued=" + back);

        MAIN.postDelayed(() -> checkThenBack(app, service, callback, attempt), BACK_RECHECK_MS);
    }

    private CircleShadeCoordinator() {}
}
