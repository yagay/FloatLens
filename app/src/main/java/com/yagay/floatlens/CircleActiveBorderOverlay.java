package com.yagay.floatlens;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
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
import android.view.animation.LinearInterpolator;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Non-interactive screen-edge indicator shown while Circle Select is active.
 *
 * The border is added only after the frozen Circle bitmap has been captured. It therefore remains a
 * live activation cue above the workspace without contaminating the image being selected/cropped.
 * The dashed stroke continuously advances around the physical display edge as a marching-ants cue.
 */
final class CircleActiveBorderOverlay {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static BorderView activeView;
    private static FlOverlayWindowHost activeHost;
    private static int captureHideLeases;
    private static long nextLeaseId;
    private static boolean circleActive;

    static synchronized void show(Context c) {
        if (c == null) return;
        Context app = c.getApplicationContext();
        circleActive = true;
        removeLocked("replace");

        FloatSettings settings = new FloatSettings(app);
        FloatSettingsDomains.Circle circle = FloatSettingsDomains.circle(settings);
        if (!circle.borderEnabled()) {
            DiagnosticLog.i(app, "CIRCLE_BORDER", "disabled by preference");
            return;
        }

        FlOverlayWindowHost host = new FlOverlayWindowHost(app);
        BorderView view = new BorderView(app,
                settings.circleBorderColor(), settings.circleBorderWidthDp());
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
        DiagnosticLog.i(app, "CIRCLE_BORDER", "shown mode=marching_ants hideLeases="
                + captureHideLeases
                + " color=0x" + Integer.toHexString(settings.circleBorderColor())
                + " widthDp=" + settings.circleBorderWidthDp());
    }

    static synchronized void hide(Context c, String reason) {
        circleActive = false;
        Context app = c == null ? null : c.getApplicationContext();
        removeLocked(reason == null ? "hide" : reason);
        if (app != null) DiagnosticLog.i(app, "CIRCLE_BORDER", "hidden reason=" + safe(reason));
    }

    /** Applies changed preferences without requiring Circle Select to restart. */
    static synchronized void refreshStyle(Context c) {
        if (c == null) return;
        Context app = c.getApplicationContext();
        FloatSettings settings = new FloatSettings(app);
        FloatSettingsDomains.Circle circle = FloatSettingsDomains.circle(settings);
        if (!circle.borderEnabled()) {
            removeLocked("preference_disabled");
            return;
        }
        if (circleActive && activeView == null) {
            show(app);
            return;
        }
        BorderView view = activeView;
        if (view != null) {
            view.applyStyle(settings.circleBorderColor(), settings.circleBorderWidthDp());
            DiagnosticLog.i(app, "CIRCLE_BORDER", "style refreshed color=0x"
                    + Integer.toHexString(settings.circleBorderColor())
                    + " widthDp=" + settings.circleBorderWidthDp());
        }
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
                    if (activeView == view) {
                        view.setVisibility(captureHideLeases > 0 ? View.INVISIBLE : View.VISIBLE);
                    }
                }
            });
        }
    }

    private static void removeLocked(String reason) {
        BorderView view = activeView;
        FlOverlayWindowHost host = activeHost;
        activeView = null;
        activeHost = null;
        if (view != null) view.stopAnimation();
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
        private static final long MARCH_DURATION_MS = 720L;

        private final Context context;
        private final WindowManager windowManager;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path borderPath = new Path();
        private final RectF borderRect = new RectF();
        private final float density;
        private final float minFallbackRadius;
        private final float maxFallbackRadius;
        private ValueAnimator animator;
        private float stroke;
        private float dashLength;
        private float dashGap;
        private float dashPhase;
        private float topLeftRadius;
        private float topRightRadius;
        private float bottomRightRadius;
        private float bottomLeftRadius;
        private int geometryWidth = -1;
        private int geometryHeight = -1;
        private int lastLoggedWidth = -1;
        private int lastLoggedHeight = -1;
        private int lastLoggedTl = -1;
        private int lastLoggedTr = -1;
        private int lastLoggedBr = -1;
        private int lastLoggedBl = -1;

        BorderView(Context c, int color, int widthDp) {
            super(c);
            context = c.getApplicationContext();
            windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            density = getResources().getDisplayMetrics().density;
            minFallbackRadius = 16f * density;
            maxFallbackRadius = 32f * density;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            applyStyle(color, widthDp);
            setOnApplyWindowInsetsListener((v, insets) -> {
                refreshGeometry(insets, "insets");
                return insets;
            });
        }

        void applyStyle(int color, int widthDp) {
            stroke = Math.max(1f, Math.max(1, Math.min(8, widthDp)) * density);
            dashLength = Math.max(6f * density, stroke * 3.2f);
            dashGap = Math.max(4f * density, stroke * 2.2f);
            paint.setColor(color);
            paint.setStrokeWidth(stroke);
            updateDashEffect();
            invalidate();
        }

        private void updateDashEffect() {
            paint.setPathEffect(new DashPathEffect(
                    new float[]{Math.max(1f, dashLength), Math.max(1f, dashGap)}, dashPhase));
        }

        private void startAnimation() {
            if (animator != null && animator.isStarted()) return;
            stopAnimation();
            float cycle = Math.max(2f, dashLength + dashGap);
            animator = ValueAnimator.ofFloat(0f, cycle);
            animator.setDuration(MARCH_DURATION_MS);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setRepeatMode(ValueAnimator.RESTART);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(a -> {
                dashPhase = (float) a.getAnimatedValue();
                updateDashEffect();
                invalidate();
            });
            animator.start();
        }

        void stopAnimation() {
            ValueAnimator running = animator;
            animator = null;
            if (running != null) {
                running.removeAllUpdateListeners();
                running.cancel();
            }
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            requestApplyInsets();
            post(() -> refreshGeometry(getRootWindowInsets(), "attached"));
            startAnimation();
        }

        @Override protected void onDetachedFromWindow() {
            stopAnimation();
            super.onDetachedFromWindow();
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (w != oldw || h != oldh) {
                refreshGeometry(getRootWindowInsets(), "size");
                requestApplyInsets();
            }
        }

        @Override protected void onConfigurationChanged(Configuration newConfig) {
            super.onConfigurationChanged(newConfig);
            post(() -> {
                requestApplyInsets();
                refreshGeometry(getRootWindowInsets(), "configuration");
            });
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (getWidth() <= 0 || getHeight() <= 0) return;

            if (geometryWidth != getWidth() || geometryHeight != getHeight()) {
                refreshGeometry(getRootWindowInsets(), "draw_guard");
            }

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

        private void refreshGeometry(WindowInsets rootInsets, String reason) {
            int width = getWidth();
            int height = getHeight();
            if (width <= 0 || height <= 0) {
                try {
                    android.graphics.Rect bounds = windowManager.getCurrentWindowMetrics().getBounds();
                    width = Math.max(0, bounds.width());
                    height = Math.max(0, bounds.height());
                } catch (Throwable ignored) { }
            }
            geometryWidth = width;
            geometryHeight = height;

            WindowInsets insets = rootInsets;
            if (insets == null) {
                try { insets = windowManager.getCurrentWindowMetrics().getWindowInsets(); }
                catch (Throwable ignored) { }
            }

            float fallback = adaptiveFallbackRadius(width, height);
            if (Build.VERSION.SDK_INT >= 31 && insets != null) {
                topLeftRadius = radius(insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT), fallback);
                topRightRadius = radius(insets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT), fallback);
                bottomRightRadius = radius(insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT), fallback);
                bottomLeftRadius = radius(insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT), fallback);
            } else {
                topLeftRadius = fallback;
                topRightRadius = fallback;
                bottomRightRadius = fallback;
                bottomLeftRadius = fallback;
            }
            logGeometryIfChanged(reason);
            invalidate();
        }

        private float adaptiveFallbackRadius(int width, int height) {
            int shortEdge = Math.min(width, height);
            if (shortEdge <= 0) return minFallbackRadius;
            float proportional = shortEdge * 0.035f;
            return Math.max(minFallbackRadius, Math.min(maxFallbackRadius, proportional));
        }

        private float radius(RoundedCorner corner, float fallback) {
            return corner == null || corner.getRadius() <= 0 ? fallback : corner.getRadius();
        }

        private float adjustedRadius(float radius, float inset) {
            return Math.max(0f, radius - inset);
        }

        private void logGeometryIfChanged(String reason) {
            int tl = Math.round(topLeftRadius);
            int tr = Math.round(topRightRadius);
            int br = Math.round(bottomRightRadius);
            int bl = Math.round(bottomLeftRadius);
            if (geometryWidth == lastLoggedWidth && geometryHeight == lastLoggedHeight
                    && tl == lastLoggedTl && tr == lastLoggedTr
                    && br == lastLoggedBr && bl == lastLoggedBl) return;
            lastLoggedWidth = geometryWidth;
            lastLoggedHeight = geometryHeight;
            lastLoggedTl = tl;
            lastLoggedTr = tr;
            lastLoggedBr = br;
            lastLoggedBl = bl;
            DiagnosticLog.i(context, "CIRCLE_BORDER", "geometry reason=" + safe(reason)
                    + " size=" + geometryWidth + "x" + geometryHeight
                    + " radii=" + tl + "," + tr + "," + br + "," + bl
                    + " animated=true");
        }
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "capture" : value.replace(' ', '_');
    }

    private CircleActiveBorderOverlay() {}
}
