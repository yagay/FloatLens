package com.yagay.floatlens;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.WindowManager;

/**
 * Pure-ish layout/persistence policy for FloatService icon windows.
 *
 * Gesture code supplies movement; this class only translates settings + display bounds into stable
 * icon WindowManager coordinates. Keeping this out of the Service makes edge hiding, orientation
 * persistence and mirrored-icon placement one reusable source of truth.
 */
final class FloatingIconLayoutPolicy {
    private final Context app;
    private final WindowManager wm;
    private FloatSettings settings;

    FloatingIconLayoutPolicy(Context c, FloatSettings settings) {
        app = c.getApplicationContext();
        wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        this.settings = settings;
    }

    void updateSettings(FloatSettings settings) {
        this.settings = settings;
    }

    int iconPx() {
        return Math.round(settings.sizeDp() * app.getResources().getDisplayMetrics().density);
    }

    int[] displaySize() {
        Rect b = wm.getCurrentWindowMetrics().getBounds();
        return new int[]{b.width(), b.height()};
    }

    WindowManager.LayoutParams createPrimary() {
        int px = iconPx();
        int[] wh = displaySize();
        int defaultX = wh[0] - px;
        int defaultY = wh[1] / 3;
        WindowManager.LayoutParams lp = baseLayout(px);
        lp.x = settings.prefs().getInt(settings.posXKey(),
                settings.prefs().getInt(FloatSettings.K_POS_X, defaultX));
        lp.y = settings.prefs().getInt(settings.posYKey(),
                settings.prefs().getInt(FloatSettings.K_POS_Y, defaultY));
        int side = settings.savedSide(lp.x + px / 2 < wh[0] / 2 ? 0 : 1);
        lp.x = side == 0 ? 0 : wh[0] - px;
        clamp(lp, false);
        return lp;
    }

    WindowManager.LayoutParams createMirror(WindowManager.LayoutParams primary) {
        if (primary == null) return null;
        WindowManager.LayoutParams lp = baseLayout(iconPx());
        int[] wh = displaySize();
        lp.x = isLeft(primary) ? wh[0] - lp.width : 0;
        lp.y = primary.y;
        clamp(lp, false);
        return lp;
    }

    WindowManager.LayoutParams baseLayout(int px) {
        int type = LensAccessibilityService.ready()
                ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                px, px, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        return lp;
    }

    boolean isLeft(WindowManager.LayoutParams lp) {
        if (lp == null) return true;
        int[] wh = displaySize();
        return lp.x + lp.width / 2 < wh[0] / 2;
    }

    void clamp(WindowManager.LayoutParams lp, boolean allowHidden) {
        if (lp == null) return;
        int[] wh = displaySize();
        int hidden = allowHidden ? Math.round(lp.width * settings.hiddenPercent() / 100f) : 0;
        lp.x = Math.max(-hidden, Math.min(lp.x, wh[0] - lp.width + hidden));
        lp.y = Math.max(0, Math.min(lp.y, wh[1] - lp.height));
    }

    void snap(WindowManager.LayoutParams lp) {
        if (lp == null) return;
        int[] wh = displaySize();
        lp.x = isLeft(lp) ? 0 : wh[0] - lp.width;
        clamp(lp, false);
        edgeHide(lp);
    }

    void edgeHide(WindowManager.LayoutParams lp) {
        if (lp == null) return;
        int hiddenPercent = settings.hiddenPercent();
        if (hiddenPercent <= 0) return;
        int[] wh = displaySize();
        boolean left = isLeft(lp);
        int hidden = Math.round(lp.width * hiddenPercent / 100f);
        lp.x = left ? -hidden : wh[0] - lp.width + hidden;
        clamp(lp, true);
        DiagnosticLog.i(app, "EDGE", "side=" + (left ? "L" : "R")
                + " visiblePct=" + settings.showPercentage()
                + " hiddenPx=" + hidden + " x=" + lp.x);
    }

    void syncMirror(WindowManager.LayoutParams primary, WindowManager.LayoutParams secondary) {
        if (primary == null || secondary == null) return;
        int[] wh = displaySize();
        secondary.x = isLeft(primary) ? wh[0] - secondary.width : 0;
        secondary.y = primary.y;
        edgeHide(secondary);
    }

    void persist(WindowManager.LayoutParams primary) {
        if (primary == null) return;
        boolean left = isLeft(primary);
        DiagnosticLog.i(app, "POSITION", "persist x=" + primary.x + " y=" + primary.y
                + " side=" + (left ? "L" : "R") + " landscape=" + settings.isLandscape());
        settings.saveSide(left);
        settings.prefs().edit()
                .putInt(settings.posXKey(), primary.x)
                .putInt(settings.posYKey(), primary.y)
                .apply();
    }
}
