package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Insets;
import android.graphics.Rect;
import android.view.WindowInsets;
import android.view.WindowManager;

/** Low-level status/navigation inset calculation. Preference ownership lives in FloatSettings. */
final class CaptureSystemBarsPolicy {
    /** Compatibility alias for older callers; new code should use FloatSettings.K_KEEP_NAVIGATION_BAR. */
    static final String K_KEEP_NAVIGATION_BAR = FloatSettings.K_KEEP_NAVIGATION_BAR;

    static boolean keepNavigationBar(Context c) {
        return new FloatSettings(c).keepNavigationBarInScreenshot();
    }

    static Rect captureBounds(Context c, boolean keepStatusBar, boolean keepNavigationBar) {
        Rect display = ScreenGeometry.displayBounds(c);
        if (display.isEmpty()) return display;
        int excludedTypes = 0;
        if (!keepStatusBar) excludedTypes |= WindowInsets.Type.statusBars();
        if (!keepNavigationBar) excludedTypes |= WindowInsets.Type.navigationBars();
        if (excludedTypes == 0) return display;

        try {
            WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            var metrics = wm.getCurrentWindowMetrics();
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
