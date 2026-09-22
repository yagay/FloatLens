package com.yagay.floatlens;

import android.content.Context;
import android.provider.Settings;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;

import java.util.IdentityHashMap;
import java.util.Map;

/** Shared FloatLens WindowManager host for ordinary overlay surfaces. */
final class FlOverlayWindowHost {
    private final Context context;
    private final WindowManager appWindowManager;
    private final Map<View, Boolean> accessibilityHosted = new IdentityHashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private View lastView;

    FlOverlayWindowHost(Context c) {
        context = c.getApplicationContext();
        appWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    boolean add(View view, WindowManager.LayoutParams lp, String tag) {
        if (view == null || lp == null) return false;
        LensAccessibilityService a = LensAccessibilityService.get();
        if (a != null && a.addAccessibilityOverlay(view, lp)) {
            remember(view, true);
            bindToWorkflowScene(view, tag);
            DiagnosticLog.i(context, "FL_WINDOW", tag + " host=accessibility type=" + lp.type);
            return true;
        }
        return addApplication(view, lp, tag);
    }

    /**
     * Persistent controls prefer TYPE_APPLICATION_OVERLAY when permission is available.
     * Accessibility overlays are retained as a fallback only. This avoids coordinate-space drift
     * observed when AccessibilityService window metrics lag behind rapid display rotations.
     */
    boolean addStable(View view, WindowManager.LayoutParams lp, String tag) {
        if (view == null || lp == null) return false;
        if (Settings.canDrawOverlays(context)) {
            if (addApplication(view, lp, tag)) return true;
            DiagnosticLog.i(context, "FL_WINDOW",
                    tag + " stable application host failed; trying accessibility fallback");
        }
        LensAccessibilityService a = LensAccessibilityService.get();
        if (a != null && a.addAccessibilityOverlay(view, lp)) {
            remember(view, true);
            bindToWorkflowScene(view, tag);
            DiagnosticLog.i(context, "FL_WINDOW",
                    tag + " stable fallback host=accessibility type=" + lp.type);
            return true;
        }
        return false;
    }

    boolean addApplication(View view, WindowManager.LayoutParams lp, String tag) {
        if (view == null || lp == null) return false;
        try {
            lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            appWindowManager.addView(view, lp);
            remember(view, false);
            bindToWorkflowScene(view, tag);
            DiagnosticLog.i(context, "FL_WINDOW", tag + " host=application type=" + lp.type);
            return true;
        } catch (Throwable t) {
            accessibilityHosted.remove(view);
            DiagnosticLog.i(context, "FL_WINDOW", tag + " application add failed=" + t);
            return false;
        }
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
                bindToWorkflowScene(view, tag);
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
                    bindToWorkflowScene(view, tag);
                    added = true;
                    DiagnosticLog.i(context, "FL_WINDOW", tag + " migrate rollback host=accessibility type=" + lp.type);
                }
            }
        }

        if (added) view.setVisibility(visibility);
        return added;
    }

    boolean remove(View view, String tag) {
        if (view == null) return true;
        OverlaySceneManager.unbind(view);

        // A window that cannot be detached must never remain interactive as an orphan.
        try { view.setVisibility(View.INVISIBLE); } catch (Throwable ignored) { }

        boolean removed = removeFromCurrentHost(view, null, tag);
        if (removed) {
            forget(view);
            return true;
        }

        DiagnosticLog.i(context, "FL_WINDOW",
                tag + " remove deferred; orphan kept invisible for retry");
        mainHandler.postDelayed(() -> {
            if (!accessibilityHosted.containsKey(view) && !view.isAttachedToWindow()) {
                forget(view);
                return;
            }
            boolean retryRemoved = removeFromCurrentHost(view, null, tag + "_retry");
            if (retryRemoved || !view.isAttachedToWindow()) {
                forget(view);
            } else {
                DiagnosticLog.i(context, "FL_WINDOW",
                        tag + " retry remove still attached; keeping orphan invisible");
            }
        }, 160L);
        return false;
    }

    private void forget(View view) {
        accessibilityHosted.remove(view);
        if (lastView == view) lastView = null;
    }

    private boolean removeFromCurrentHost(View view, WindowManager.LayoutParams lp, String tag) {
        boolean known = accessibilityHosted.containsKey(view);
        boolean onAccessibility = known
                ? isAccessibilityHosted(view)
                : lp != null && lp.type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        try {
            boolean removed;
            if (onAccessibility) {
                LensAccessibilityService a = LensAccessibilityService.get();
                if (a != null) {
                    removed = a.removeAccessibilityOverlay(view);
                } else {
                    appWindowManager.removeViewImmediate(view);
                    removed = !view.isAttachedToWindow();
                }
            } else {
                appWindowManager.removeViewImmediate(view);
                removed = !view.isAttachedToWindow();
            }
            if (!removed) {
                DiagnosticLog.i(context, "FL_WINDOW",
                        tag + " remove did not detach view; host="
                                + (onAccessibility ? "accessibility" : "application"));
                return false;
            }
            accessibilityHosted.remove(view);
            return true;
        } catch (Throwable t) {
            DiagnosticLog.i(context, "FL_WINDOW", tag + " remove failed=" + t
                    + " host=" + (onAccessibility ? "accessibility" : "application")
                    + " attached=" + view.isAttachedToWindow());
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

    private void bindToWorkflowScene(View view, String tag) {
        if (view == null || !OverlaySceneManager.shouldBindWindow(tag)) return;
        OverlaySceneManager.bindCurrent(view, () -> remove(view,
                (tag == null ? "overlay" : tag) + "_workflow_scene"));
    }
}
