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
import android.graphics.RectF;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Region screenshot selector; OCR mode uses a free-form lasso path. */
public final class RegionOverlay {
    public static void show(Context c, Bitmap screen, boolean ocr) {
        Context app = c.getApplicationContext();
        FloatSettings fs = new FloatSettings(app);
        Rect display = ScreenGeometry.displayBounds(app);
        if (display.isEmpty()) {
            if (screen != null && !screen.isRecycled()) screen.recycle();
            throw new IllegalStateException("display bounds unavailable");
        }
        Rect content = CaptureSystemBarsPolicy.captureBounds(
                app,
                fs.keepStatusBarInScreenshot(),
                CaptureSystemBarsPolicy.keepNavigationBar(app));

        FlOverlayWindowHost host = new FlOverlayWindowHost(app);
        SelectView view = new SelectView(app, screen, ocr, host);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                Math.max(1, content.width()), Math.max(1, content.height()),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = content.left - display.left;
        lp.y = content.top - display.top;
        if (!host.add(view, lp, "region_selector")) {
            if (screen != null && !screen.isRecycled()) screen.recycle();
            throw new IllegalStateException("unable to attach region selector");
        }
        DiagnosticLog.i(app, "REGION_SCREENSHOT", "overlay bounds=" + content.toShortString()
                + " keepStatusBar=" + fs.keepStatusBarInScreenshot()
                + " keepNavigationBar=" + CaptureSystemBarsPolicy.keepNavigationBar(app)
                + " accessibilityHost=" + host.isAccessibilityHosted());
    }

    static class SelectView extends View {
        final Bitmap source;
        final boolean ocr;
        final FlOverlayWindowHost host;
        float sx, sy, ex, ey;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean selecting;
        boolean closed;
        final Path lasso = new Path();
        final List<PointF> points = new ArrayList<>();

        SelectView(Context c, Bitmap source, boolean ocr, FlOverlayWindowHost host) {
            super(c);
            this.source = source;
            this.ocr = ocr;
            this.host = host;
            paint.setStrokeWidth(dp(2));
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(dp(16));
            textPaint.setShadowLayer(dp(3), 0, dp(1), Color.BLACK);
        }

        @Override protected void onDraw(Canvas canvas) {
            if (!source.isRecycled()) {
                canvas.drawBitmap(source, null, new Rect(0, 0, getWidth(), getHeight()), paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x77000000);
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            if (selecting) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(3));
                paint.setColor(Color.WHITE);
                if (ocr) canvas.drawPath(lasso, paint);
                else canvas.drawRect(rect(), paint);
            }
            canvas.drawText(ocr ? "圈选要识别的内容 · 松手开始 OCR" : "拖动选择截图区域",
                    dp(18), dp(38), textPaint);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (closed) return true;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    sx = ex = e.getX();
                    sy = ey = e.getY();
                    selecting = true;
                    points.clear();
                    lasso.reset();
                    lasso.moveTo(sx, sy);
                    points.add(new PointF(sx, sy));
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_MOVE -> {
                    ex = e.getX();
                    ey = e.getY();
                    if (ocr) {
                        lasso.lineTo(ex, ey);
                        points.add(new PointF(ex, ey));
                    }
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP -> {
                    ex = e.getX();
                    ey = e.getY();
                    if (ocr) {
                        lasso.lineTo(ex, ey);
                        lasso.close();
                        points.add(new PointF(ex, ey));
                    }
                    finishSelection();
                    return true;
                }
                case MotionEvent.ACTION_CANCEL -> {
                    if (ocr) {
                        FloatService service = FloatService.get();
                        if (service != null) service.onCircleFinished("selection_cancel");
                    }
                    close(true);
                    return true;
                }
            }
            return true;
        }

        private RectF rect() {
            return new RectF(Math.min(sx, ex), Math.min(sy, ey), Math.max(sx, ex), Math.max(sy, ey));
        }

        private RectF lassoBounds() {
            RectF r = new RectF();
            lasso.computeBounds(r, true);
            return r;
        }

        private void finishSelection() {
            RectF selected = ocr ? lassoBounds() : rect();
            int viewWidth = getWidth();
            int viewHeight = getHeight();
            int[] origin = new int[2];
            getLocationOnScreen(origin);
            Rect sourceBounds = new Rect(
                    Math.round(selected.left) + origin[0],
                    Math.round(selected.top) + origin[1],
                    Math.round(selected.right) + origin[0],
                    Math.round(selected.bottom) + origin[1]);

            if (selected.width() < dp(8) || selected.height() < dp(8)) {
                close(true);
                if (ocr) {
                    FloatService service = FloatService.get();
                    if (service != null) service.onCircleFinished("selection_too_small");
                }
                return;
            }

            Bitmap crop = null;
            try {
                crop = ocr
                        ? SelectionCropper.maskedCrop(source, points, viewWidth, viewHeight, dp(8))
                        : SelectionCropper.cropRect(source, selected, viewWidth, viewHeight);
                if (crop == null) throw new IllegalStateException("empty selection crop");
                close(true);
                if (ocr) {
                    OcrEngine.recognize(getContext(), crop, sourceBounds);
                } else {
                    boolean shown = ResultSurfaceRouter.showScreenshot(getContext(), crop, sourceBounds);
                    DiagnosticLog.i(getContext(), "REGION_SCREENSHOT",
                            "result shown=" + shown + " bounds=" + sourceBounds.toShortString());
                    if (!shown) {
                        DiagnosticLog.i(getContext(), "REGION_SCREENSHOT",
                                "result surface failed; save crop as fallback");
                        ScreenshotController.save(getContext(), crop);
                    }
                }
            } catch (Throwable t) {
                close(true);
                if (crop != null && !crop.isRecycled()) {
                    try { crop.recycle(); } catch (Throwable ignored) {}
                }
                if (ocr) {
                    FloatService service = FloatService.get();
                    if (service != null) service.onCircleFinished("selection_error");
                }
                Toast.makeText(getContext(), "区域处理失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

        private void close(boolean recycleSource) {
            if (closed) return;
            closed = true;
            host.remove(this, "region_selector");
            if (recycleSource && !source.isRecycled()) {
                try { source.recycle(); } catch (Throwable ignored) {}
            }
        }

        private float dp(float v) {
            return v * ScreenGeometry.density(getContext());
        }
    }

    private RegionOverlay() {}
}
