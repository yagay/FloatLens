package com.yagay.floatlens;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Clean-room equivalent of FV's notification/system-panel close path.
 *
 * Reverse-engineered FV behaviour:
 *  - FooAccessibilityService.B0() identifies an expanded SystemUI TYPE_SYSTEM window;
 *  - m5/w2.n() first broadcasts ACTION_CLOSE_SYSTEM_DIALOGS;
 *  - its callback then calls FooAccessibilityService.H();
 *  - H() is exactly performGlobalAction(15), i.e. GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE.
 *
 * Callers snapshot the expanded state before capture and invoke dismiss only after the bitmap or
 * frozen result UI is ready. That preserves the notification shade in the captured image.
 *
 * Modern OxygenOS can reject both original FV routes: CLOSE_SYSTEM_DIALOGS requires a privileged
 * permission and performGlobalAction(15) may return false. FloatLens therefore preserves FV's two
 * routes first, then uses a root-only `cmd statusbar collapse` fallback when both fail.
 */
public final class FvSystemPanelController {
    private static final ExecutorService ROOT_IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FloatLens-shade-collapse");
        t.setDaemon(true);
        return t;
    });

    private FvSystemPanelController() {}

    /** FV B0()-style SystemUI window test, with the cached environment only as a fallback. */
    public static boolean notificationShadeExpanded() {
        LensAccessibilityService service = LensAccessibilityService.get();
        if (service == null) return false;

        try {
            WindowManager wm = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
            int screenHeight = Math.max(1, wm.getCurrentWindowMetrics().getBounds().height());
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null || window.getType() != AccessibilityWindowInfo.TYPE_SYSTEM) continue;
                    AccessibilityNodeInfo root = null;
                    try { root = window.getRoot(); } catch (Throwable ignored) {}
                    if (root == null || root.getPackageName() == null
                            || !"com.android.systemui".contentEquals(root.getPackageName())) continue;
                    Rect bounds = new Rect();
                    try { window.getBoundsInScreen(bounds); } catch (Throwable ignored) {}
                    if (!bounds.isEmpty() && bounds.height() >= screenHeight / 2) {
                        DiagnosticLog.i(service, "FV_SHADE", "expanded via TYPE_SYSTEM bounds="
                                + bounds.toShortString());
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            DiagnosticLog.i(service, "FV_SHADE", "window check failed=" + t);
        }

        try {
            EnvironmentState env = service.environment();
            return env != null && env.notificationExpanded();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Mirrors m5/w2.n() + FooAccessibilityService.H(). Only executes when the shade was known to be
     * expanded before capture; this must be called after the captured/frozen result is ready.
     */
    public static void dismissAfterCapture(Context context, boolean wasExpanded, String reason) {
        if (!wasExpanded) return;
        Context app = context.getApplicationContext();
        app.getMainExecutor().execute(() -> {
            boolean broadcast = false;
            try {
                app.sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
                broadcast = true;
            } catch (Throwable t) {
                DiagnosticLog.i(app, "FV_SHADE", "CLOSE_SYSTEM_DIALOGS failed=" + t);
            }

            boolean global = false;
            LensAccessibilityService service = LensAccessibilityService.get();
            if (service != null) {
                try {
                    global = service.global(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
                } catch (Throwable t) {
                    DiagnosticLog.i(app, "FV_SHADE", "dismiss global action failed=" + t);
                }
            }

            DiagnosticLog.i(app, "FV_SHADE", "dismiss after capture reason=" + reason
                    + " broadcast=" + broadcast + " global15=" + global);

            // OxygenOS 16 on rooted devices can reject both original FV mechanisms. Do not replace
            // FV's path: use root only as a fallback after both failed.
            if (!broadcast && !global) {
                collapseWithRoot(app, reason);
            }
        });
    }

    private static void collapseWithRoot(Context app, String reason) {
        ROOT_IO.execute(() -> {
            int code = -1;
            String error = "";
            try {
                Process p = new ProcessBuilder("su", "-c", "cmd statusbar collapse")
                        .redirectErrorStream(true)
                        .start();
                code = p.waitFor();
            } catch (Throwable t) {
                error = String.valueOf(t);
            }
            final int exitCode = code;
            final String failure = error;
            app.getMainExecutor().execute(() -> DiagnosticLog.i(app, "FV_SHADE",
                    "root collapse reason=" + reason + " exit=" + exitCode
                            + (failure.isEmpty() ? "" : " error=" + failure)));
        });
    }
}
