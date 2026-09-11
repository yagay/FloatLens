package com.yagay.floatlens;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.text.Selection;
import android.text.Spannable;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

/** Installs FloatLens' own selection menu on selectable text inside FloatLens activities. */
public final class FloatLensApp extends Application implements Application.ActivityLifecycleCallbacks {
    @Override public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
    }

    @Override public void onActivityResumed(Activity activity) {
        install(activity);
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (decor != null) decor.post(() -> install(activity));
    }

    private void install(Activity activity) {
        if (activity == null || activity.getWindow() == null) return;
        installRecursive(activity, activity.getWindow().getDecorView());
    }

    private void installRecursive(Activity activity, View view) {
        if (view == null) return;
        if (view instanceof TextView tv) {
            if (tv.isTextSelectable()) {
                tv.setCustomSelectionActionModeCallback(new FloatSelectionCallback(activity, tv));
            } else if (activity instanceof ResultTextActivity
                    && !(tv instanceof Button) && tv.isClickable()
                    && tv.getText() != null && !tv.getText().toString().isBlank()) {
                tv.setOnClickListener(v -> FloatActionMenu.showTextAt(
                        activity, tv.getText().toString(), null, FloatMenuAnchor.forView(tv)));
            }
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                installRecursive(activity, group.getChildAt(i));
            }
        }
    }

    private static final class FloatSelectionCallback implements ActionMode.Callback {
        private final Activity activity;
        private final TextView textView;
        private String lastValue = "";

        FloatSelectionCallback(Activity activity, TextView textView) {
            this.activity = activity;
            this.textView = textView;
        }

        @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            if (menu != null) menu.clear();
            showIfChanged(true);
            return true;
        }

        @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            if (menu != null) menu.clear();
            showIfChanged(false);
            return true;
        }

        @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            return true;
        }

        @Override public void onDestroyActionMode(ActionMode mode) {
            lastValue = "";
            FloatMenuAnchor.clear();
        }

        private void showIfChanged(boolean force) {
            String value = selectedText(textView);
            if (value.isEmpty()) return;
            if (!force && value.equals(lastValue)) return;
            lastValue = value;
            textView.post(() -> {
                String current = selectedText(textView);
                if (current.isEmpty()) return;
                FloatActionMenu.showTextAt(activity, current, () -> {
                    try {
                        CharSequence raw = textView.getText();
                        if (raw instanceof Spannable span && span.length() > 0) {
                            Selection.setSelection(span, 0, span.length());
                            textView.post(() -> {
                                String all = selectedText(textView);
                                if (!all.isEmpty()) {
                                    FloatActionMenu.showTextAt(activity, all, null,
                                            FloatMenuAnchor.forTextSelection(textView));
                                }
                            });
                        }
                    } catch (Throwable ignored) {}
                }, FloatMenuAnchor.forTextSelection(textView));
            });
        }
    }

    private static String selectedText(TextView tv) {
        if (tv == null || tv.getText() == null) return "";
        int a = tv.getSelectionStart();
        int b = tv.getSelectionEnd();
        if (a < 0 || b < 0 || a == b) return "";
        int lo = Math.max(0, Math.min(a, b));
        int hi = Math.min(tv.length(), Math.max(a, b));
        return lo < hi ? tv.getText().subSequence(lo, hi).toString().trim() : "";
    }

    @Override public void onActivityPaused(Activity activity) {
        FloatActionMenu.dismiss();
        FloatMenuAnchor.clear();
    }
    @Override public void onActivityDestroyed(Activity activity) {
        FloatActionMenu.dismiss();
        FloatMenuAnchor.clear();
    }
    @Override public void onActivityCreated(Activity activity, Bundle state) {}
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
}
