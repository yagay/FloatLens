package com.yagay.floatlens;

import android.app.Activity;
import android.view.View;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Keeps FloatLens' ordinary Activity content out from under status/navigation bars.
 *
 * Android 15+ enforces edge-to-edge for modern target SDKs, so user-facing pages must consume
 * system-bar insets explicitly. Overlay/capture surfaces intentionally use their own geometry and
 * are not routed through this helper.
 */
final class AppSystemBarInsets {
    static void install(Activity activity) {
        if (!isOrdinaryPage(activity) || activity.getWindow() == null) return;
        try {
            // Keep rendering edge-to-edge at the window level, then make the content root safe.
            // This is the supported model on Android 16+ where opting out of edge-to-edge is gone.
            WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);

            View content = activity.findViewById(android.R.id.content);
            if (content == null) return;
            ViewCompat.setOnApplyWindowInsetsListener(content, (view, windowInsets) -> {
                androidx.core.graphics.Insets bars = windowInsets.getInsets(
                        WindowInsetsCompat.Type.statusBars()
                                | WindowInsetsCompat.Type.navigationBars()
                                | WindowInsetsCompat.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return windowInsets;
            });
            ViewCompat.requestApplyInsets(content);
        } catch (Throwable t) {
            DiagnosticLog.i(activity, "APP_INSETS", "install failed=" + t);
        }
    }

    private static boolean isOrdinaryPage(Activity activity) {
        if (activity == null) return false;
        // These Activities are infrastructure/full-screen test hosts, not normal app pages.
        return !(activity instanceof SecureCaptureProbeActivity)
                && !(activity instanceof ShadeDismissActivity)
                && !(activity instanceof ResultActivity);
    }

    private AppSystemBarInsets() { }
}
