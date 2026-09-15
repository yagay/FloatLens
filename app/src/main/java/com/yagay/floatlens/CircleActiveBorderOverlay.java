package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.RoundedCorner;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Non-interactive screen-edge indicator shown while Circle Select is active.
 *
 * The indicator is deliberately kept outside the frozen Circle Select bitmap. FloatLens capture
 * sessions can also acquire a short hide lease so a later screenshot taken while Circle Select is
 * still active never records the border.
 */
final class CircleActiveBorderOverlay {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static BorderView activeView;
    private static FlOverlayWindowHost activeHost;
    private static int captureHideLeases;
    private static long nextLeaseId;

    static synchronized void show(Context c) {
        Context app = c.getApplicationContext();
        removeLocked("replace");

        FlOverlayWindowHost host = new FlOverlayWindowHost(app);
        BorderView view = new BorderView(app);
        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        // The activation border belongs to the physical display edge, not the app content area.
        // Do not let status/navigation/cutout insets shrink this overlay.
        if (Build.VERSION.SDK_INT >= 30) {
            lp.setFitInsetsTypes(0);
            lp.setFitInsetsSides(0);
            lp.setFitInsetsIgnoringVisibility(true);
        }
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

        if (!host.add(view, lp, "circle_active_border")) {
            DiagnosticLog.i(app, "CIRCLE_BORDER", "overlay add failed");
            return;
        }
        activeHost = host;
        activeView = view;
        applyVisibilityLocked();
        DiagnosticLog.i(app, "CIRCLE_BORDER", "shown hideLeases=" + captureHideLeases);
    }

    static synchronized void hide(Context c, String reason) {
        Context app = c == null ? null : c.getApplicationContext();
        removeLocked(reason == null ? "hide" : reason);
        if (app != null) DiagnosticLog.i(app, "CIRCLE_BORDER", "hidden reason=" + safe(reason));
    }

    static synchronized CaptureLease acquireCaptureHidden(Context c, String reason) {
        Context app = c == null ? null : c.getApplicationContext();
        if (activeView == null) return new CaptureLease(0L, false, reason);
        long id = ++nextLeaseId;
        captureHideLeases++;
        applyVisibilityLocked();
        if (app != null) {
            DiagnosticLog.i(app, "CIRCLE_BORDER", "capture hide acquire id=" + id
                    + " count=" + captureHideLeases + " reason=" + safe(reason));
        }
        return new CaptureLease(id, true, reason);
    }

    private static synchronized void releaseCaptureHidden(Context c, CaptureLease lease) {
        if (lease == null || !lease.counted || !lease.released.compareAndSet(false, true)) return;
        captureHideLeases = Math.max(0, captureHideLeases - 1);
        applyVisibilityLocked();
        Context app = c == null ? null : c.getApplicationContext();
        if (app != null) {
            DiagnosticLog.i(app, "CIRCLE_BORDER", "capture hide release id=" + lease.id
                    + " count=" + captureHideLeases + " reason=" + safe(lease.reason));
        }
    }

    private static void applyVisibilityLocked() {
        BorderView view = activeView;
        if (view == null) return;
        int visibility = captureHideLeases > 0 ? View.INVISIBLE : View.VISIBLE;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            view.setVisibility(visibility);
        } else {
            MAIN.post(() -> {
                synchronized (CircleActiveBorderOverlay.class) {
                    if (activeView == view) view.setVisibility(captureHideLeases > 0
                            ? View.INVISIBLE : View.VISIBLE);
                }
            });
        }
    }

    private static void removeLocked(String reason) {
        BorderView view = activeView;
        FlOverlayWindowHost host = activeHost;
        activeView = null;
        activeHost = null;
        if (view != null && host != null) host.remove(view, "circle_active_border_" + safe(reason));
    }

    static final class CaptureLease {
        private final long id;
        private final boolean counted;
        private final String reason;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private CaptureLease(long id, boolean counted, String reason) {
            this.id = id;
            this.counted = counted;
            this.reason = reason;
        }

        boolean requiresSettle() { return counted; }

        void release(Context c) {
            CircleActiveBorderOverlay.releaseCaptureHidden(c, this);
        }
    }

    private static final class BorderView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path borderPath = new Path();
        private final RectF borderRect = new RectF();
        private final float stroke;
        private final float fallbackRadius;
        private float topLeftRadius;
        private float topRightRadius;
        private float bottomRightRadius;
        private float bottomLeftRadius;

        BorderView(Context c) {
            super(c);
            float density = getResources().getDisplayMetrics().density;
            stroke = Math.max(2f, 3f * density);
            fallbackRadius = 24f * density;
            paint.setColor(Color.rgb(66, 133, 244));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            setOnApplyWindowInsetsListener((v, insets) -> {
                updateRoundedCorners(insets);
                invalidate();
                return insets;
            });
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            requestApplyInsets();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (getWidth() <= 0 || getHeight() <= 0) return;

            WindowInsets rootInsets = getRootWindowInsets();
            if (rootInsets != null) updateRoundedCorners(rootInsets);

            float inset = stroke / 2f;
            borderRect.set(inset, inset,
                    Math.max(inset, getWidth() - inset),
                    Math.max(inset, getHeight() - inset));

            float tl = adjustedRadius(topLeftRadius, inset);
            float tr = adjustedRadius(topRightRadius, inset);
            float br = adjustedRadius(bottomRightRadius, inset);
            float bl = adjustedRadius(bottomLeftRadius, inset);
            float[] radii = new float[]{
                    tl, tl,
                    tr, tr,
                    br, br,
                    bl, bl
            };
            borderPath.reset();
            borderPath.addRoundRect(borderRect, radii, Path.Direction.CW);
            canvas.drawPath(borderPath, paint);
        }

        private void updateRoundedCorners(WindowInsets insets) {
            if (Build.VERSION.SDK_INT < 31 || insets == null) return;
            topLeftRadius = radius(insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT));
            topRightRadius = radius(insets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT));
            bottomRightRadius = radius(insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT));
            bottomLeftRadius = radius(insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT));
        }

        private float radius(RoundedCorner corner) {
            return corner == null || corner.getRadius() <= 0 ? fallbackRadius : corner.getRadius();
        }

        private float adjustedRadius(float radius, float inset) {
            float source = radius > 0 ? radius : fallbackRadius;
            return Math.max(0f, source - inset);
        }
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "capture" : value.replace(' ', '_');
    }

    private CircleActiveBorderOverlay() {}
}
