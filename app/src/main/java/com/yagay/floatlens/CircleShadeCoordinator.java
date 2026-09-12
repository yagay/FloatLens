package com.yagay.floatlens;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * OxygenOS/modern-Android compatibility bridge for Circle Select.
 *
 * A focusable TYPE_APPLICATION_OVERLAY can prevent the transient Activity fallback from collapsing
 * the notification shade. Circle Select already owns a frozen screenshot, so keep that bitmap in
 * memory, collapse the live shade first, then attach the interactive/focusable workspace.
 */
final class CircleShadeCoordinator {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long GLOBAL_RECHECK_MS = 120L;
    private static final long SHADOW_RECHECK_MS = 390L;

    interface Callback {
        void onReady(boolean collapsed);
    }

    static void prepare(Context context, boolean expandedAtCapture, Callback callback) {
        Context app = context.getApplicationContext();
        boolean liveExpanded = FvSystemPanelController.notificationShadeExpanded();
        if (!expandedAtCapture && !liveExpanded) {
            DiagnosticLog.i(app, "CIRCLE_SHADE", "pre-candidate skip expanded=false");
            callback.onReady(true);
            return;
        }

        LensAccessibilityService service = LensAccessibilityService.get();
        boolean global15 = false;
        if (service != null) {
            try {
                global15 = service.global(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
            } catch (Throwable t) {
                DiagnosticLog.i(app, "CIRCLE_SHADE", "global15 failed=" + t);
            }
        }
        DiagnosticLog.i(app, "CIRCLE_SHADE", "pre-candidate begin captureExpanded="
                + expandedAtCapture + " liveExpanded=" + liveExpanded + " global15=" + global15);

        // On the tested OxygenOS build action 15 is not exposed and returns false. Do not waste an
        // extra frame when Android already told us it could not issue the action; launch the FV-like
        // transient Activity immediately. If another ROM accepts action 15, give it one short frame.
        if (!global15) {
            launchShadow(app, callback);
        } else {
            MAIN.postDelayed(() -> {
                if (!FvSystemPanelController.notificationShadeExpanded()) {
                    DiagnosticLog.i(app, "CIRCLE_SHADE", "global15 collapsed before candidate");
                    callback.onReady(true);
                } else {
                    launchShadow(app, callback);
                }
            }, GLOBAL_RECHECK_MS);
        }
    }

    private static void launchShadow(Context app, Callback callback) {
        if (!FvSystemPanelController.notificationShadeExpanded()) {
            DiagnosticLog.i(app, "CIRCLE_SHADE", "already collapsed before shadow");
            callback.onReady(true);
            return;
        }

        boolean launched = ShadeDismissActivity.launch(app, "circle_pre_candidate");
        if (!launched) {
            DiagnosticLog.i(app, "CIRCLE_SHADE", "shadow launch failed; show frozen candidate anyway");
            callback.onReady(false);
            return;
        }

        MAIN.postDelayed(() -> {
            boolean expanded = FvSystemPanelController.notificationShadeExpanded();
            DiagnosticLog.i(app, "CIRCLE_SHADE", "shadow pre-candidate recheck expanded="
                    + expanded + " delayMs=" + SHADOW_RECHECK_MS);
            callback.onReady(!expanded);
        }, SHADOW_RECHECK_MS);
    }

    private CircleShadeCoordinator() {}
}
