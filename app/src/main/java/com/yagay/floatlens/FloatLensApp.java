package com.yagay.floatlens;

import android.app.Activity;
import android.app.Application;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.Map;
import java.util.WeakHashMap;

/** Installs FloatLens' app-wide text menu and result-window lifecycle coordination. */
public final class FloatLensApp extends Application implements Application.ActivityLifecycleCallbacks {
    private final Map<Activity, View.OnLayoutChangeListener> resultCenterLocks = new WeakHashMap<>();

    @Override public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
    }

    @Override public void onActivityResumed(Activity activity) {
        install(activity);
        installResultCenterLock(activity);
        if (activity instanceof ResultActivity) {
            ResultReadyCoordinator.onResultActivityResumed(activity);
        }
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (decor != null) decor.post(() -> {
            install(activity);
            centerResultWindow(activity);
        });
    }

    private void install(Activity activity) {
        if (activity == null || activity.getWindow() == null) return;
        // UnifiedResultPanel already owns its TextSelectionSurface binding.
        if (activity instanceof ResultActivity) return;
        installRecursive(activity, activity.getWindow().getDecorView());
    }

    private void installResultCenterLock(Activity activity) {
        if (!(activity instanceof ResultActivity) || activity.getWindow() == null) return;
        View decor = activity.getWindow().getDecorView();
        if (decor == null) return;

        synchronized (resultCenterLocks) {
            if (resultCenterLocks.containsKey(activity)) {
                centerResultWindow(activity);
                return;
            }
            View.OnLayoutChangeListener listener = (v, left, top, right, bottom,
                    oldLeft, oldTop, oldRight, oldBottom) -> v.post(() -> centerResultWindow(activity));
            resultCenterLocks.put(activity, listener);
            decor.addOnLayoutChangeListener(listener);
        }
        centerResultWindow(activity);
    }

    private void centerResultWindow(Activity activity) {
        if (!(activity instanceof ResultActivity) || activity.getWindow() == null
                || activity.isFinishing() || activity.isDestroyed()) return;
        try {
            WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
            if (lp.gravity == Gravity.CENTER && lp.x == 0 && lp.y == 0) return;
            lp.gravity = Gravity.CENTER;
            lp.x = 0;
            lp.y = 0;
            activity.getWindow().setAttributes(lp);
            DiagnosticLog.i(activity, "RESULT_CENTER", "locked center "
                    + activity.getClass().getSimpleName()
                    + " size=" + lp.width + "x" + lp.height);
        } catch (Throwable t) {
            DiagnosticLog.i(activity, "RESULT_CENTER", "failed "
                    + activity.getClass().getSimpleName() + " " + t);
        }
    }

    private void installRecursive(Activity activity, View view) {
        if (view == null) return;
        if (view instanceof TextView tv && tv.isTextSelectable()) {
            final TextSelectionController[] ref = new TextSelectionController[1];
            TextSelectionController controller = new TextSelectionController(activity, tv, 0L,
                    new TextSelectionController.Observer() {
                        @Override public void onStarted() {
                            FloatActionMenu.dismiss();
                            FloatMenuAnchor.clear();
                        }

                        @Override public void onChanging() {
                            FloatActionMenu.dismiss();
                            FloatMenuAnchor.clear();
                        }

                        @Override public void onStable(String selectedText, Rect anchorOnScreen) {
                            if (selectedText == null || selectedText.isBlank()) return;
                            TextSelectionController current = ref[0];
                            FloatActionMenu.showTextAt(activity, selectedText.trim(),
                                    current == null ? null : current::selectAll, anchorOnScreen);
                        }
                    });
            ref[0] = controller;
            controller.install();
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                installRecursive(activity, group.getChildAt(i));
            }
        }
    }

    @Override public void onActivityPaused(Activity activity) {
        FloatActionMenu.dismiss();
        FloatMenuAnchor.clear();
    }

    @Override public void onActivityDestroyed(Activity activity) {
        FloatActionMenu.dismiss();
        FloatMenuAnchor.clear();
        synchronized (resultCenterLocks) {
            View.OnLayoutChangeListener listener = resultCenterLocks.remove(activity);
            if (listener != null && activity.getWindow() != null) {
                try { activity.getWindow().getDecorView().removeOnLayoutChangeListener(listener); }
                catch (Throwable ignored) { }
            }
        }
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) { }
}
