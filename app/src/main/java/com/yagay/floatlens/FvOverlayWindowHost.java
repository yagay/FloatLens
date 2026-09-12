package com.yagay.floatlens;

import android.content.Context;
import android.view.View;
import android.view.WindowManager;

/**
 * Shared FV-style WindowManager host for selection/helper overlays.
 *
 * FV routes every helper window through the same addView helper and, whenever Accessibility is
 * available, rewrites the window type to 2032 (TYPE_ACCESSIBILITY_OVERLAY). FloatLens must do the
 * same for the whole selection window family, not only the floating owner icon, otherwise SystemUI
 * notification shade/quick settings can cover the probe, operation hint and selection frames.
 *
 * Text editing/selection is a special case: Android's native Editor ActionMode (selection handles
 * and magnifier) is reliable on a normal focusable TYPE_APPLICATION_OVERLAY, but not on an
 * accessibility overlay. Once SystemUI no longer needs to be covered, callers may migrate the same
 * attached View to the application WindowManager without rebuilding the visible surface.
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

        try {
            lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            appWindowManager.addView(view, lp);
            accessibilityHosted = false;
            DiagnosticLog.i(context, "FV_WINDOW", tag + " host=application type=" + lp.type);
            return true;
        } catch (Throwable t) {
            accessibilityHosted = false;
            DiagnosticLog.i(context, "FV_WINDOW", tag + " add failed=" + t);
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

    /**
     * Move the exact same View from AccessibilityService WindowManager to the application's normal
     * overlay WindowManager. This is used for native text selection, whose framework Editor owns the
     * Android handles and magnifier.
     */
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
            accessibilityHosted = false;
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
