package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Insets;
import android.graphics.Rect;
import android.view.WindowInsets;
import android.view.WindowManager;

/** Shared status/navigation-bar capture policy for screenshots and Circle Select. */
final class CaptureSystemBarsPolicy {
    /** New setting: keep the live/captured system navigation-bar area. Defaults off. */
    static final String K_KEEP_NAVIGATION_BAR = "screen_capture_keep_navigation_bar_v1";

    static boolean keepNavigationBar(Context c) {
        return c.getApplicationContext()
                .getSharedPreferences(FloatSettings.PREF, Context.MODE_PRIVATE)
                .getBoolean(K_KEEP_NAVIGATION_BAR, false);
    }

    static Rect captureBounds(Context c, boolean keepStatusBar, boolean keepNavigationBar) {
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        var metrics = wm.getCurrentWindowMetrics();
        Rect display = new Rect(metrics.getBounds());
        int excludedTypes = 0;
        if (!keepStatusBar) excludedTypes |= WindowInsets.Type.statusBars();
        if (!keepNavigationBar) excludedTypes |= WindowInsets.Type.navigationBars();
        if (excludedTypes == 0) return display;

        try {
            // Use currently visible insets. Hidden immersive bars should not remove app pixels.
            Insets excluded = metrics.getWindowInsets().getInsets(excludedTypes);
            Rect content = new Rect(
                    display.left + Math.max(0, excluded.left),
                    display.top + Math.max(0, excluded.top),
                    display.right - Math.max(0, excluded.right),
                    display.bottom - Math.max(0, excluded.bottom));
            if (!content.isEmpty()) return content;
        } catch (Throwable ignored) {
            // OEM fallback: keep the complete frame rather than failing capture.
        }
        return display;
    }

    private CaptureSystemBarsPolicy() {}
}
