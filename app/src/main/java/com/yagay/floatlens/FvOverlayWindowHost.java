package com.yagay.floatlens;

import android.content.Context;
import android.view.View;
import android.view.WindowManager;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Shared FV-style WindowManager host.
 *
 * A single instance can manage multiple Views. Host ownership is tracked per View rather than by a
 * single global boolean, which lets FloatService, result windows and selection helpers all reuse the
 * same add/update/remove/migrate implementation without guessing from LayoutParams.type.
 */
final class FvOverlayWindowHost {
    private final Context context;
    private final WindowManager appWindowManager;
    private final Map<View, Boolean> accessibilityHosted = new IdentityHashMap<>();
    private View lastView;

    FvOverlayWindowHost(Context c) {
        context = c.getApplicationContext();
        appWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    boolean add(View view, WindowManager.LayoutParams lp, String tag) {
        if (view == null || lp == null) return false;
        LensAccessibilityService a = LensAccessibilityService.get();
        if (a != null && a.addAccessibilityOverlay(view, lp)) {
            remember(view, true);
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
            remember(view, false);
            DiagnosticLog.i(context, "FV_WINDOW", tag + " host=application type=" + lp.type);
            return true;
        } catch (Throwable t) {
            accessibilityHosted.remove(view);
            DiagnosticLog.i(context, "FV_WINDOW", tag + " application add failed=" + t);
            return false;
        }
    }

    boolean update(View view, WindowManager.LayoutParams lp, String tag) {
        if (view == null || lp == null) return false;
        try {
            if (isAccessibilityHosted(view)) {
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

    boolean migrateToApplication(View view, WindowManager.LayoutParams lp, String tag) {
        return migrate(view, lp, false, tag);
    }

    boolean migrateToAccessibility(View view, WindowManager.LayoutParams lp, String tag) {
        return migrate(view, lp, true, tag);
    }

    /** Rehost the exact same View while preserving its LayoutParams and visibility. */
    boolean migrate(View view, WindowManager.LayoutParams lp, boolean useAccessibility, String tag) {
        if (view == null || lp == null) return false;
        boolean currentAccessibility = isAccessibilityHosted(view);
        if (accessibilityHosted.containsKey(view) && currentAccessibility == useAccessibility) {
            return update(view, lp, tag + "_same_host");
        }

        int visibility = view.getVisibility();
        if (!removeFromCurrentHost(view, lp, tag + "_migrate_remove")) return false;

        boolean added;
        if (useAccessibility) {
            LensAccessibilityService a = LensAccessibilityService.get();
            added = a != null && a.addAccessibilityOverlay(view, lp);
            if (added) {
                remember(view, true);
                view.setVisibility(visibility);
                DiagnosticLog.i(context, "FV_WINDOW", tag + " migrated host=accessibility type=" + lp.type);
                return true;
            }
            added = addApplication(view, lp, tag + "_fallback_application");
        } else {
            added = addApplication(view, lp, tag);
            if (!added) {
                LensAccessibilityService a = LensAccessibilityService.get();
                if (a != null && a.addAccessibilityOverlay(view, lp)) {
                    remember(view, true);
                    added = true;
                    DiagnosticLog.i(context, "FV_WINDOW", tag + " migrate rollback host=accessibility type=" + lp.type);
                }
            }
        }

        if (added) view.setVisibility(visibility);
        return added;
    }

    void remove(View view, String tag) {
        if (view == null) return;
        removeFromCurrentHost(view, null, tag);
        accessibilityHosted.remove(view);
        if (lastView == view) lastView = null;
    }

    private boolean removeFromCurrentHost(View view, WindowManager.LayoutParams lp, String tag) {
        boolean known = accessibilityHosted.containsKey(view);
        boolean onAccessibility = known
                ? isAccessibilityHosted(view)
                : lp != null && lp.type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        try {
            if (onAccessibility) {
                LensAccessibilityService a = LensAccessibilityService.get();
                if (a != null) {
                    a.removeAccessibilityOverlay(view);
                } else {
                    appWindowManager.removeView(view);
                }
            } else {
                appWindowManager.removeView(view);
            }
            accessibilityHosted.remove(view);
            return true;
        } catch (Throwable t) {
            DiagnosticLog.i(context, "FV_WINDOW", tag + " remove failed=" + t);
            accessibilityHosted.remove(view);
            return false;
        }
    }

    boolean isAccessibilityHosted(View view) {
        return Boolean.TRUE.equals(accessibilityHosted.get(view));
    }

    /** Convenience for single-view callers such as FloatingResultWindow. */
    boolean isAccessibilityHosted() {
        return lastView != null && isAccessibilityHosted(lastView);
    }

    private void remember(View view, boolean onAccessibility) {
        accessibilityHosted.put(view, onAccessibility);
        lastView = view;
    }
}
