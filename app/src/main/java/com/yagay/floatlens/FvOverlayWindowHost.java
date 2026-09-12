package com.yagay.floatlens;

import android.content.Context;
import android.view.View;
import android.view.WindowManager;

/**
 * Shared FV-style WindowManager host for selection/helper overlays.
 *
 * Use {@link #add(View, WindowManager.LayoutParams, String)} when the surface must sit above
 * SystemUI and should prefer TYPE_ACCESSIBILITY_OVERLAY. Use {@link #addApplication(View,
 * WindowManager.LayoutParams, String)} for native Android text editing/selection, whose framework
 * Editor, handles and magnifier require a normal focusable TYPE_APPLICATION_OVERLAY.
 */
final class FvOverlayWindowHost {
    private final Context context;
    private final WindowManager appWindowManager;
    private boolean accessibilityHosted;

    FvOverlayWindowHost(Context c) {
        context = c.getApplicationContext();
        appWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    boolean add(View view, WindowManager.LayoutParams lp, String tag) {
        if (view == null || lp == null) return false;
        LensAccessibilityService a = LensAccessibilityService.get();
        if (a != null && a.addAccessibilityOverlay(view, lp)) {
            accessibilityHosted = true;
            DiagnosticLog.i(context, "FV_WINDOW", tag + " host=accessibility type=" + lp.type);
            return true;
        }
        return addApplication(view, lp, tag);
    }

    boolean addApplication(View view, WindowManager.LayoutParams lp, String tag) {
        if (view == null || lp == null) return false;
        try {
            lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            appWindowManager.addView(view, lp);
            accessibilityHosted = false;
            DiagnosticLog.i(context, "FV_WINDOW", tag + " host=application type=" + lp.type);
            return true;
        } catch (Throwable t) {
            accessibilityHosted = false;
            DiagnosticLog.i(context, "FV_WINDOW", tag + " application add failed=" + t);
            return false;
        }
    }

    boolean update(View view, WindowManager.LayoutParams lp, String tag) {
        if (view == null || lp == null) return false;
        try {
            if (accessibilityHosted) {
                LensAccessibilityService a = LensAccessibilityService.get();
                if (a != null && a.updateAccessibilityOverlay(view, lp)) return true;
                DiagnosticLog.i(context, "FV_WINDOW", tag + " accessibility host unavailable during update");
                return false;
            }
            lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            appWindowManager.updateViewLayout(view, lp);
            return true;
        } catch (Throwable t) {
            DiagnosticLog.i(context, "FV_WINDOW", tag + " update failed=" + t);
            return false;
        }
    }

    /** Move the exact same View from AccessibilityService WindowManager to application overlay. */
    boolean migrateToApplication(View view, WindowManager.LayoutParams lp, String tag) {
        if (view == null || lp == null) return false;
        if (!accessibilityHosted) {
            try {
                lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
                appWindowManager.updateViewLayout(view, lp);
                DiagnosticLog.i(context, "FV_WINDOW", tag + " already application type=" + lp.type);
                return true;
            } catch (Throwable t) {
                DiagnosticLog.i(context, "FV_WINDOW", tag + " application update failed=" + t);
                return false;
            }
        }

        LensAccessibilityService a = LensAccessibilityService.get();
        if (a == null) {
            DiagnosticLog.i(context, "FV_WINDOW", tag + " migrate failed accessibility service missing");
            return false;
        }
        try {
            a.removeAccessibilityOverlay(view);
            accessibilityHosted = false;
        } catch (Throwable t) {
            DiagnosticLog.i(context, "FV_WINDOW", tag + " accessibility remove failed=" + t);
            return false;
        }

        try {
            lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            appWindowManager.addView(view, lp);
            DiagnosticLog.i(context, "FV_WINDOW", tag + " migrated host=application type=" + lp.type);
            return true;
        } catch (Throwable t) {
            DiagnosticLog.i(context, "FV_WINDOW", tag + " application add after migrate failed=" + t);
            try {
                if (a.addAccessibilityOverlay(view, lp)) {
                    accessibilityHosted = true;
                    DiagnosticLog.i(context, "FV_WINDOW", tag + " migrate rollback host=accessibility type=" + lp.type);
                }
            } catch (Throwable rollback) {
                DiagnosticLog.i(context, "FV_WINDOW", tag + " migrate rollback failed=" + rollback);
            }
            return false;
        }
    }

    void remove(View view, String tag) {
        if (view == null) return;
        if (accessibilityHosted) {
            LensAccessibilityService a = LensAccessibilityService.get();
            if (a != null) {
                try {
                    a.removeAccessibilityOverlay(view);
                    accessibilityHosted = false;
                    return;
                } catch (Throwable ignored) {}
            }
        }
        try { appWindowManager.removeView(view); }
        catch (Throwable ignored) {}
        accessibilityHosted = false;
    }

    boolean isAccessibilityHosted() { return accessibilityHosted; }
}
