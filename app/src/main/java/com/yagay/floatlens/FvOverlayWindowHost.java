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
