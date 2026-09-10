package com.yagay.floatlens;

import android.content.Context;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.WindowManager;

/**
 * Converts the original floating-icon touch stream into FV's selection probe point.
 *
 * fooView 1.6.4 does not use rawX/rawY or the floating icon centre directly for View selection.
 * Runtime capture of FooViewService.v3() shows a separate probe coordinate with four important
 * behaviours:
 *
 * 1) X normally tracks the floating icon's left edge minus about 25dp.
 * 2) Near the right edge FV progressively adds the distance into an edge compensation zone. This
 *    makes the probe accelerate (roughly 2:1) instead of becoming unreachable at the screen edge.
 * 3) Y normally sits about 60dp above the finger (10dp lead - 20dp helper inset - 50dp span).
 * 4) Near the bottom edge FV switches to a mirrored/accelerated formula so the probe can continue
 *    toward the bottom rather than stopping one icon radius early.
 *
 * The touch offset is captured while FloatIconView is still the small overlay. Once the same View
 * is expanded to MATCH_PARENT, local MotionEvent x/y change coordinate spaces, but raw x/y remain
 * stable, so every later update is reconstructed from raw coordinates plus that original offset.
 */
public final class SelectionPointTransformer {
    private static final float FV_EDGE_LEAD_DP = 10f;
    private static final float FV_X_PROBE_OFFSET_DP = 25f;
    private static final float FV_EDGE_SPAN_DP = 50f;
    private static final float FV_Y_HELPER_INSET_DP = 20f;
    private static final long LOG_INTERVAL_MS = 80L;

    private final Context context;
    private final float iconWidth;
    private final float iconHeight;
    private final float density;

    private float touchOffsetX;
    private float touchOffsetY;
    private boolean initialized;
    private long lastLogAt;

    public SelectionPointTransformer(Context c, float iconWidth, float iconHeight) {
        context = c.getApplicationContext();
        this.iconWidth = Math.max(1f, iconWidth);
        this.iconHeight = Math.max(1f, iconHeight);
        density = Math.max(0.1f, context.getResources().getDisplayMetrics().density);
    }

    public void begin(MotionEvent e) {
        if (e == null) return;
        touchOffsetX = e.getX();
        touchOffsetY = e.getY();
        initialized = true;

        Rect screen = screenBounds();
        DiagnosticLog.i(context, "FV_PROBE", "BEGIN raw="
                + Math.round(e.getRawX()) + "," + Math.round(e.getRawY())
                + " local=" + Math.round(touchOffsetX) + "," + Math.round(touchOffsetY)
                + " icon=" + Math.round(iconWidth) + "x" + Math.round(iconHeight)
                + " density=" + String.format(java.util.Locale.US, "%.3f", density)
                + " screen=" + screen);
    }

    public PointF transform(MotionEvent e) {
        if (e == null) return new PointF();
        if (!initialized) begin(e);
        return transformRaw(e.getRawX(), e.getRawY());
    }

    /** Reconstruct the small icon bounds from the same raw stream and original in-icon offset. */
    public RectF iconBoundsForRaw(float rawX, float rawY) {
        ensureInitializedFallback();
        float left = rawX - touchOffsetX;
        float top = rawY - touchOffsetY;
        return new RectF(left, top, left + iconWidth, top + iconHeight);
    }

    /** Use after FloatIconView has been expanded to full screen. */
    public PointF transformRaw(float rawX, float rawY) {
        ensureInitializedFallback();

        final Rect screen = screenBounds();
        final float lead = dp(FV_EDGE_LEAD_DP);
        final float edgeSpan = dp(FV_EDGE_SPAN_DP);
        final float helperInset = dp(FV_Y_HELPER_INSET_DP);

        // FV normal X path: current icon-left reconstructed from the original finger offset, then
        // move the selection probe 25dp to the left of that icon edge.
        final float iconLeft = rawX - touchOffsetX;
        float x = iconLeft - dp(FV_X_PROBE_OFFSET_DP);
        boolean rightCompensation = false;
        float rightThreshold = Float.NaN;

        // Runtime samples on a 1272px-wide device match:
        //   baseX = iconLeft - 25dp
        //   if rawX + 10dp enters the right edge zone:
        //       baseX += (rawX + 10dp - threshold)
        // This is why FV's probe continues moving at the right edge instead of feeling clamped.
        if (!screen.isEmpty()) {
            final float edgeX = rawX + lead;
            rightThreshold = screen.right - iconWidth - edgeSpan - lead;
            if (edgeX > rightThreshold) {
                x += edgeX - rightThreshold;
                rightCompensation = true;
            }
        }

        // FV normal Y path observed from v3(): rawY + 10dp - 20dp - 50dp.
        final float edgeY = rawY + lead;
        float y = edgeY - helperInset - edgeSpan;
        boolean bottomCompensation = false;
        float bottomThreshold = Float.NaN;

        if (!screen.isEmpty()) {
            bottomThreshold = screen.bottom - helperInset - edgeSpan;
            if (edgeY > bottomThreshold) {
                // FV bottom-edge branch: y = 2 * (rawY + 10dp) - screenHeight.
                y = 2f * edgeY - screen.bottom;
                bottomCompensation = true;
            }

            // FV allows the helper/probe to go outside the left/right edge, but the bottom branch
            // is bounded by the display height. Do not clamp X and do not clamp Y at the top: both
            // behaviours are visible in the captured APK runtime data.
            if (y > screen.bottom) y = screen.bottom;
        }

        maybeLog(rawX, rawY, iconLeft, x, y,
                rightCompensation, bottomCompensation, rightThreshold, bottomThreshold, screen);
        return new PointF(x, y);
    }

    private void ensureInitializedFallback() {
        if (initialized) return;
        // Compatibility fallback only. Normal selection calls begin() on ACTION_DOWN while the icon
        // is still a small window.
        touchOffsetX = iconWidth / 2f;
        touchOffsetY = iconHeight / 2f;
        initialized = true;
    }

    private void maybeLog(float rawX, float rawY, float iconLeft, float x, float y,
                          boolean rightCompensation, boolean bottomCompensation,
                          float rightThreshold, float bottomThreshold, Rect screen) {
        long now = SystemClock.uptimeMillis();
        if (now - lastLogAt < LOG_INTERVAL_MS && !rightCompensation && !bottomCompensation) return;
        lastLogAt = now;
        DiagnosticLog.i(context, "FV_PROBE", "raw=" + Math.round(rawX) + "," + Math.round(rawY)
                + " iconLeft=" + Math.round(iconLeft)
                + " probe=" + Math.round(x) + "," + Math.round(y)
                + " edgeR=" + rightCompensation + " edgeB=" + bottomCompensation
                + " thresholdR=" + (Float.isNaN(rightThreshold) ? "n/a" : Math.round(rightThreshold))
                + " thresholdB=" + (Float.isNaN(bottomThreshold) ? "n/a" : Math.round(bottomThreshold))
                + " screen=" + screen);
    }

    private float dp(float value) {
        return value * density;
    }

    private Rect screenBounds() {
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            return new Rect(wm.getCurrentWindowMetrics().getBounds());
        } catch (Throwable t) {
            return new Rect();
        }
    }
}
