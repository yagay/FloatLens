package com.yagay.floatlens;

import android.app.Activity;
import android.app.Application;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** Installs FloatLens' app-wide text menu. Result popup geometry belongs only to the dialog host. */
public final class FloatLensApp extends Application implements Application.ActivityLifecycleCallbacks {
    @Override public void onCreate() {
        super.onCreate();
        try {
            CustomMenuActionStore.seedDictionaryOnce(this);
        } catch (Throwable t) {
            DiagnosticLog.i(this, "DICTIONARY", "seed action failed=" + t);
        }
        registerActivityLifecycleCallbacks(this);
    }

    @Override public void onActivityResumed(Activity activity) {
        install(activity);
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (decor != null) decor.post(() -> install(activity));
    }

    private void install(Activity activity) {
        if (activity == null || activity.getWindow() == null) return;
        // UnifiedResultPanel owns its TextSelectionSurface binding inside the dialog.
        if (activity instanceof ResultActivity) return;
        installRecursive(activity, activity.getWindow().getDecorView());
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
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) { }
}
