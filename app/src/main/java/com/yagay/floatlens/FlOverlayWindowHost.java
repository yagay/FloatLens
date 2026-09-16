package com.yagay.floatlens;

import android.content.Context;
import android.view.View;
import android.view.WindowManager;

import java.util.IdentityHashMap;
import java.util.Map;

/** Shared FloatLens WindowManager host for ordinary overlay surfaces. */
final class FlOverlayWindowHost {
    /**
     * A text action menu is often attached from the same ACTION_UP that finishes a text selection.
     * Some overlay/window combinations can route that release into the newly attached first menu
     * item. The first item is Copy, so guard the new menu briefly until the selection touch is fully
     * released. The menu is still drawn immediately; only input is delayed.
     */
    private static final long FLOAT_ACTION_MENU_INPUT_GUARD_MS = 120L;

    private final Context context;
    private final WindowManager appWindowManager;
    private final Map<View, Boolean> accessibilityHosted = new IdentityHashMap<>();
    private View lastView;

    FlOverlayWindowHost(Context c) {
        context = c.getApplicationContext();
        appWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    boolean add(View view, WindowManager.LayoutParams lp, String tag) {
        if (view == null || lp == null) return false;
        boolean menuInputGuard = prepareInputGuard(lp, tag);
        LensAccessibilityService a = LensAccessibilityService.get();
        if (a != null && a.addAccessibilityOverlay(view, lp)) {
            remember(view, true);
            DiagnosticLog.i(context, "FL_WINDOW", tag + " host=accessibility type=" + lp.type);
            if (menuInputGuard) armInputGuardRelease(view, lp, tag);
            return true;
        }
        return addApplicationPrepared(view, lp, tag, menuInputGuard);
    }

    boolean addApplication(View view, WindowManager.LayoutParams lp, String tag) {
        if (view == null || lp == null) return false;
        boolean menuInputGuard = prepareInputGuard(lp, tag);
        return addApplicationPrepared(view, lp, tag, menuInputGuard);
    }

    private boolean addApplicationPrepared(View view, WindowManager.LayoutParams lp, String tag,
                                           boolean menuInputGuard) {
        try {
            lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            appWindowManager.addView(view, lp);
            remember(view, false);
            DiagnosticLog.i(context, "FL_WINDOW", tag + " host=application type=" + lp.type);
            if (menuInputGuard) armInputGuardRelease(view, lp, tag);
            return true;
        } catch (Throwable t) {
            accessibilityHosted.remove(view);
            DiagnosticLog.i(context, "FL_WINDOW", tag + " application add failed=" + t);
            return false;
        }
    }

    private boolean prepareInputGuard(WindowManager.LayoutParams lp, String tag) {
        if (!"float_action_menu".equals(tag)) return false;
        if ((lp.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0) return false;
        lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        DiagnosticLog.i(context, "FLOAT_ACTION_MENU",
                "input guard armed ms=" + FLOAT_ACTION_MENU_INPUT_GUARD_MS
                        + " reason=selection_release menuOnly=true clipboardWrite=false");
        return true;
    }

    private void armInputGuardRelease(View view, WindowManager.LayoutParams lp, String tag) {
        view.postDelayed(() -> {
            if (!accessibilityHosted.containsKey(view)) return;
            lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            boolean updated = update(view, lp, tag + "_input_ready");
            DiagnosticLog.i(context, "FLOAT_ACTION_MENU",
                    "input guard released updated=" + updated
                            + " menuOnly=true clipboardWrite=false");
        }, FLOAT_ACTION_MENU_INPUT_GUARD_MS);
    }

    boolean update(View view, WindowManager.LayoutParams lp, String tag) {
        if (view == null || lp == null) return false;
        try {
            if (isAccessibilityHosted(view)) {
                LensAccessibilityService a = LensAccessibilityService.get();
                if (a != null && a.updateAccessibilityOverlay(view, lp)) return true;
                DiagnosticLog.i(context, "FL_WINDOW", tag + " accessibility host unavailable during update");
                return false;
            }
            lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            appWindowManager.updateViewLayout(view, lp);
            return true;
        } catch (Throwable t) {
            DiagnosticLog.i(context, "FL_WINDOW", tag + " update failed=" + t);
            return false;
        }
    }

    boolean migrateToApplication(View view, WindowManager.LayoutParams lp, String tag) {
        return migrate(view, lp, false, tag);
    }

    boolean migrateToAccessibility(View view, WindowManager.LayoutParams lp, String tag) {
        return migrate(view, lp, true, tag);
    }

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
                DiagnosticLog.i(context, "FL_WINDOW", tag + " migrated host=accessibility type=" + lp.type);
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
                    DiagnosticLog.i(context, "FL_WINDOW", tag + " migrate rollback host=accessibility type=" + lp.type);
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
                if (a != null) a.removeAccessibilityOverlay(view);
                else appWindowManager.removeView(view);
            } else {
                appWindowManager.removeView(view);
            }
            accessibilityHosted.remove(view);
            return true;
        } catch (Throwable t) {
            DiagnosticLog.i(context, "FL_WINDOW", tag + " remove failed=" + t);
            accessibilityHosted.remove(view);
            return false;
        }
    }

    boolean isAccessibilityHosted(View view) {
        return Boolean.TRUE.equals(accessibilityHosted.get(view));
    }

    boolean isAccessibilityHosted() {
        return lastView != null && isAccessibilityHosted(lastView);
    }

    private void remember(View view, boolean onAccessibility) {
        accessibilityHosted.put(view, onAccessibility);
        lastView = view;
    }
}