package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Same-touch-session Circle screenshot capture used by the floating icon.
 * The icon remains the MotionEvent owner; this full-screen frozen layer stays NOT_TOUCHABLE.
 */
public final class CircleLiveController {
    private static Session active;

    public static synchronized boolean start(Context c, float x, float y) {
        if (active != null) active.cancel("restart");
        active = new Session(c.getApplicationContext(), x, y);
        active.start();
        return true;
    }

    public static synchronized boolean active() { return active != null; }
    public static synchronized void move(float x, float y) { if (active != null) active.move(x, y); }
    public static synchronized void finish(float x, float y) {
        Session s = active; active = null; if (s != null) s.finish(x, y, false);
    }
    public static synchronized void cancel(String reason) {
        Session s = active; active = null; if (s != null) s.cancel(reason == null ? "cancel" : reason);
    }

    private static final class Session {
        private final Context context;
        private final WindowManager wm;
        private final FloatSettings settings;
        private final FlSystemPanelController.CaptureState shadeState;
        private final List<PointF> points = new ArrayList<>();
        private LiveView overlay;
        private Bitmap screenshot;
        private boolean ended, cancelled, processed;

        Session(Context c, float x, float y) {
            context = c;
            wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            settings = new FloatSettings(c);
            shadeState = FlSystemPanelController.beginCapture(c, "circle_live");
            points.add(new PointF(x, y));
        }

        void start() {
            FloatService service = FloatService.get();
            if (service != null) service.onCircleCaptureStarted();
            DiagnosticLog.i(context, "CIRCLE_LIVE", "ENTER code=" + GestureCode.ENTER_CIRCLE
                    + " x=" + Math.round(points.get(0).x) + " y=" + Math.round(points.get(0).y)
                    + " shadeExpanded=" + shadeState.expandedAtCapture());
            // Shared backend owns Accessibility -> Root fallback. Do not hide the icon here because
            // replacing the touch owner can terminate the current MotionEvent stream.
            ScreenCaptureBackend.capture(context, settings, this::onScreenshot, this::onCaptureFailure);
        }

        void move(float x, float y) {
            if (ended) return;
            PointF last = points.get(points.size() - 1);
            if (Math.abs(last.x - x) < 0.5f && Math.abs(last.y - y) < 0.5f) return;
            points.add(new PointF(x, y));
            if (overlay != null) overlay.setPoints(points);
        }

        void finish(float x, float y, boolean wasCancelled) {
            if (ended) return;
            ended = true;
            cancelled = wasCancelled;
            points.add(new PointF(x, y));
            DiagnosticLog.i(context, "CIRCLE_LIVE", "RELEASE code=" + GestureCode.CIRCLE_FINISH
                    + " points=" + points.size() + " cancelled=" + cancelled);
            if (overlay != null) overlay.setPoints(points);
            maybeProcess();
        }

        void cancel(String reason) {
            if (ended && processed) return;
            ended = true;
            cancelled = true;
            closeOverlay();
            FloatService f = FloatService.get();
            if (f != null) f.onCircleFinished(reason);
            DiagnosticLog.i(context, "CIRCLE_LIVE", "CANCEL reason=" + reason);
        }

        private void onScreenshot(Bitmap bitmap) {
            if (cancelled) {
                if (bitmap != null) bitmap.recycle();
                return;
            }
            screenshot = bitmap;
            showOverlay();
            if (ended && overlay != null) {
                overlay.postDelayed(() -> {
                    if (cancelled) return;
                    onCandidateReady();
                    maybeProcess();
                }, 16L);
                return;
            }
            onCandidateReady();
            maybeProcess();
        }

        private void onCandidateReady() {
            FlSystemPanelController.onResultReady(context, shadeState, "circle_live_candidate_shown");
        }

        private void onCaptureFailure(Throwable error) {
            closeOverlay();
            FloatService f = FloatService.get();
            if (f != null) f.onCircleFinished("capture_failed");
            String message = ScreenCaptureBackend.safeMessage(error);
            Toast.makeText(context, "圈选截图失败: " + message, Toast.LENGTH_LONG).show();
            DiagnosticLog.i(context, "CIRCLE_LIVE", "capture failed=" + message);
            processed = true;
        }

        private void showOverlay() {
            if (overlay != null || screenshot == null || cancelled) return;
            overlay = new LiveView(context, screenshot, points);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    -1, -1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            try {
                wm.addView(overlay, lp);
            } catch (Throwable t) {
                overlay = null;
                DiagnosticLog.i(context, "CIRCLE_LIVE", "overlay add failed=" + t);
            }
        }

        private void maybeProcess() {
            if (processed || !ended || screenshot == null) return;
            processed = true;
            closeOverlay();
            if (cancelled) return;

            Rect display = wm.getCurrentWindowMetrics().getBounds();
            Bitmap masked = SelectionCropper.maskedCrop(screenshot, points,
                    Math.max(1, display.width()), Math.max(1, display.height()), dp(8));
            if (masked == null) {
                FloatService f = FloatService.get();
                if (f != null) f.onCircleFinished("selection_too_small");
                return;
            }

            Rect anchor = selectionBounds(display);
            DiagnosticLog.i(context, "CIRCLE_LIVE", "masked screenshot=" + masked.getWidth() + "x"
                    + masked.getHeight() + " sourcePoints=" + points.size()
                    + " anchor=" + anchor.toShortString());

            boolean shown = ResultSurfaceRouter.showCapturedScreenshot(
                    context, masked, anchor, shadeState);
            if (!shown) {
                DiagnosticLog.i(context, "CIRCLE_LIVE", "result surface failed -> save fallback");
                ScreenshotController.save(context, masked);
                FlSystemPanelController.onResultReady(context, shadeState, "circle_live_saved_fallback");
            }
            FloatService f = FloatService.get();
            if (f != null) f.onCircleFinished("circle_screenshot_ready");
        }

        private Rect selectionBounds(Rect display) {
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (PointF p : points) {
                if (p == null) continue;
                minX = Math.min(minX, p.x);
                minY = Math.min(minY, p.y);
                maxX = Math.max(maxX, p.x);
                maxY = Math.max(maxY, p.y);
            }
            if (minX == Float.MAX_VALUE) return new Rect(display);
            int left = Math.max(display.left, Math.min(display.right - 1, Math.round(minX)));
            int top = Math.max(display.top, Math.min(display.bottom - 1, Math.round(minY)));
            int right = Math.max(left + 1, Math.min(display.right, Math.round(maxX)));
            int bottom = Math.max(top + 1, Math.min(display.bottom, Math.round(maxY)));
            return new Rect(left, top, right, bottom);
        }

        private void closeOverlay() {
            if (overlay != null) {
                try { wm.removeView(overlay); } catch (Throwable ignored) { }
            }
            overlay = null;
        }

        private float dp(float value) {
            return value * context.getResources().getDisplayMetrics().density;
        }
    }

    private static final class LiveView extends View {
        private final Bitmap screenshot;
        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private List<PointF> points;

        LiveView(Context c, Bitmap bitmap, List<PointF> initial) {
            super(c);
            screenshot = bitmap;
            points = new ArrayList<>(initial);
            shade.setColor(0x66000000);
            shade.setStyle(Paint.Style.FILL);
            line.setColor(Color.WHITE);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(dp(3));
            text.setColor(Color.WHITE);
            text.setTextSize(dp(15));
            text.setShadowLayer(dp(3), 0, dp(1), Color.BLACK);
        }

        void setPoints(List<PointF> value) {
            points = new ArrayList<>(value);
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawBitmap(screenshot, null, new Rect(0, 0, getWidth(), getHeight()), bitmapPaint);
            canvas.drawRect(0, 0, getWidth(), getHeight(), shade);
            if (points != null && !points.isEmpty()) {
                Path path = new Path();
                boolean first = true;
                for (PointF p : points) {
                    if (first) {
                        path.moveTo(p.x, p.y);
                        first = false;
                    } else {
                        path.lineTo(p.x, p.y);
                    }
                }
                canvas.drawPath(path, line);
            }
            canvas.drawText("圈选截图 · 松手生成截图", dp(18), dp(38), text);
        }

        private float dp(float value) {
            return value * getResources().getDisplayMetrics().density;
        }
    }

    private CircleLiveController() {}
}
