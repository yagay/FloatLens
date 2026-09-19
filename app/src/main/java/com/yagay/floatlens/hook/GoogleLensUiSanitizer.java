package com.yagay.floatlens.hook;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.lang.ref.WeakReference;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/**
 * Session-scoped Google Lens UI sanitizer.
 *
 * <p>This deliberately works at stable View boundaries instead of Google controller/protobuf
 * internals. It is attached only to the Activity that owns a FloatLens-marked CTS session and
 * runs immediately before drawing, so Google can freely update its internal state while FloatLens
 * prevents known post-selection chrome from ever becoming visible.</p>
 */
final class GoogleLensUiSanitizer {
    private static final String ACTION_MENU_VIEW =
            "com.google.android.libraries.lens.view.actionmenu.ActionMenuView";
    private static final String INFO_PANEL_VIEW =
            "com.google.android.libraries.lens.view.infopanel.InfoPanelView";
    private static final String OMNIBOX_VIEW =
            "com.google.android.libraries.lens.view.omnibox.OmniBoxView";
    private static final String MATERIAL_FLOATING_TOOLBAR =
            "com.google.android.material.floatingtoolbar.FloatingToolbarLayout";

    private static final String[] CHROME_ID_SUFFIXES = {
            ":id/lens_overlay_back_button",
            ":id/lens_overflow_menu_button",
            ":id/lens_overlay_history_button",
            ":id/lens_product_lockup_view"
    };

    private final BooleanSupplier enabled;
    private final BiConsumer<String, String> reporter;

    private WeakReference<Activity> activityRef = new WeakReference<>(null);
    private View root;
    private ViewTreeObserver.OnPreDrawListener listener;

    GoogleLensUiSanitizer(BooleanSupplier enabled, BiConsumer<String, String> reporter) {
        this.enabled = enabled;
        this.reporter = reporter;
    }

    void attach(Activity activity) {
        if (activity == null || activity.getWindow() == null) return;
        View nextRoot = activity.getWindow().getDecorView();
        if (nextRoot == null) return;
        if (root == nextRoot && listener != null) return;

        detach();
        activityRef = new WeakReference<>(activity);
        root = nextRoot;
        listener = () -> {
            sanitizeInternal("predraw");
            return true;
        };

        ViewTreeObserver observer = nextRoot.getViewTreeObserver();
        if (observer.isAlive()) observer.addOnPreDrawListener(listener);
        sanitizeNow();
    }

    void sanitizeNow() {
        Activity activity = activityRef.get();
        View target = root;
        if (activity == null || target == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            sanitizeInternal("immediate");
        } else {
            activity.runOnUiThread(() -> sanitizeInternal("ui_thread"));
        }
    }

    void detach() {
        View previousRoot = root;
        ViewTreeObserver.OnPreDrawListener previousListener = listener;
        root = null;
        listener = null;
        activityRef = new WeakReference<>(null);
        if (previousRoot == null || previousListener == null) return;
        try {
            ViewTreeObserver observer = previousRoot.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnPreDrawListener(previousListener);
        } catch (Throwable ignored) {
        }
    }

    private void sanitizeInternal(String phase) {
        if (enabled == null || !enabled.getAsBoolean()) return;
        View target = root;
        if (target == null) return;
        int hidden = sanitizeTree(target);
        if (hidden > 0 && reporter != null) {
            reporter.accept("GOOGLE_UI_SANITIZED",
                    "phase=" + phase + " hidden=" + hidden);
        }
    }

    private int sanitizeTree(View view) {
        if (view == null) return 0;
        if (shouldHide(view)) {
            return hide(view) ? 1 : 0;
        }

        int hidden = 0;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                hidden += sanitizeTree(group.getChildAt(i));
            }
        }
        return hidden;
    }

    private boolean shouldHide(View view) {
        String className = view.getClass().getName();
        if (ACTION_MENU_VIEW.equals(className)
                || INFO_PANEL_VIEW.equals(className)
                || OMNIBOX_VIEW.equals(className)
                || MATERIAL_FLOATING_TOOLBAR.equals(className)) {
            return true;
        }

        String id = resourceName(view);
        for (String suffix : CHROME_ID_SUFFIXES) {
            if (id.endsWith(suffix)) return true;
        }

        // Google has historically implemented the fixed "Select all" / "Listen all" controls
        // in an obfuscated Fragment. Match their semantic resource names instead of that Fragment
        // class so ordinary R8 class renaming does not bring the chips back.
        String lowerId = id.toLowerCase(java.util.Locale.ROOT);
        return lowerId.contains("select_all")
                || lowerId.contains("listen_all")
                || lowerId.contains("selection_chip")
                || lowerId.contains("selection_action_chip");
    }

    private boolean hide(View view) {
        boolean changed = view.getVisibility() != View.GONE
                || view.getAlpha() != 0f
                || view.isClickable();
        if (view.getVisibility() != View.GONE) view.setVisibility(View.GONE);
        if (view.getAlpha() != 0f) view.setAlpha(0f);
        if (view.isClickable()) view.setClickable(false);
        return changed;
    }

    private String resourceName(View view) {
        int id = view == null ? View.NO_ID : view.getId();
        if (id == View.NO_ID || id == 0) return "";
        try {
            return view.getResources().getResourceName(id);
        } catch (Throwable ignored) {
            return "";
        }
    }
}
