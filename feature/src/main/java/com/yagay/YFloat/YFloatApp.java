package com.yagay.YFloat;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** Installs YFloat's app-wide text menu. Ordinary window/inset handling is owned by shared YUI. */
public final class YFloatApp extends Application implements Application.ActivityLifecycleCallbacks {
    @Override public void onCreate() {
        super.onCreate();
        YFloatSuiteRuntime.get(this);
    }

    @Override public void onActivityResumed(Activity activity) {
        ThemeSettings.applySystemBars(activity);
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
            Object existing = tv.getTag(R.id.yfloat_text_selection_controller);
            if (!(existing instanceof TextSelectionController)) {
                final TextSelectionController[] ref = new TextSelectionController[1];
                TextActionMenuController menu = new TextActionMenuController(activity);
                TextSelectionController controller = new TextSelectionController(activity, tv, 0L,
                        new TextSelectionController.Observer() {
                            @Override public void onStarted() {
                                menu.dismiss();
                            }

                            @Override public void onChanging() {
                                menu.dismiss();
                            }

                            @Override public void onStable(SelectionSnapshot snapshot) {
                                if (snapshot == null || snapshot.text().isBlank()) return;
                                TextSelectionController current = ref[0];
                                menu.show(snapshot, current == null ? null : current::selectAll);
                            }
                        });
                ref[0] = controller;
                controller.install();
                tv.setTag(R.id.yfloat_text_selection_controller, controller);
            }
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                installRecursive(activity, group.getChildAt(i));
            }
        }
    }

    @Override public void onActivityPaused(Activity activity) {
        FloatActionMenu.dismiss();
    }

    @Override public void onActivityDestroyed(Activity activity) {
        FloatActionMenu.dismiss();
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) {
        ThemeSettings.applySystemBars(activity);
    }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) { }
}
