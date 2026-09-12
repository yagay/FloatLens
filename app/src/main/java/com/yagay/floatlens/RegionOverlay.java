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
        if (ocr) {
            FloatService f = FloatService.get();
            if (f != null) f.onCircleCaptureStarted();
        }
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        SelectView v = new SelectView(c, screen, ocr, wm);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                -1, -1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        wm.addView(v, lp);
    }

    static class SelectView extends View {
        final Bitmap source;
        final boolean ocr;
        final WindowManager wm;
        float sx, sy, ex, ey;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean selecting;
        final Path lasso = new Path();
        final List<PointF> points = new ArrayList<>();

        SelectView(Context c, Bitmap source, boolean ocr, WindowManager wm) {
            super(c);
            this.source = source;
            this.ocr = ocr;
            this.wm = wm;
            paint.setStrokeWidth(dp(2));
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(dp(16));
            textPaint.setShadowLayer(dp(3), 0, dp(1), Color.BLACK);
        }

        @Override protected void onDraw(Canvas canvas) {
            canvas.drawBitmap(source, null, new Rect(0, 0, getWidth(), getHeight()), paint);
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
                        FloatService f = FloatService.get();
                        if (f != null) f.onCircleFinished("selection_cancel");
                    }
                    close();
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
            Rect anchor = new Rect(
                    Math.round(selected.left) + origin[0],
                    Math.round(selected.top) + origin[1],
                    Math.round(selected.right) + origin[0],
                    Math.round(selected.bottom) + origin[1]);
            close();

            if (selected.width() < dp(8) || selected.height() < dp(8)) {
                if (ocr) {
                    FloatService f = FloatService.get();
                    if (f != null) f.onCircleFinished("selection_too_small");
                }
                return;
            }

            try {
                Bitmap crop = ocr
                        ? SelectionCropper.maskedCrop(source, points, viewWidth, viewHeight, dp(8))
                        : SelectionCropper.cropRect(source, selected, viewWidth, viewHeight);
                if (crop == null) throw new IllegalStateException("empty selection crop");
                if (ocr) {
                    FloatService f = FloatService.get();
                    if (f != null) f.onCircleRecognizeStarted();
                    OcrEngine.recognize(getContext(), crop, anchor);
                } else {
                    ScreenshotController.save(getContext(), crop);
                }
            } catch (Throwable t) {
                if (ocr) {
                    FloatService f = FloatService.get();
                    if (f != null) f.onCircleFinished("selection_error");
                }
                Toast.makeText(getContext(), "区域处理失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

        private void close() {
            try { wm.removeView(this); } catch (Throwable ignored) { }
        }

        private float dp(float v) {
            return v * getResources().getDisplayMetrics().density;
        }
    }

    private RegionOverlay() {}
}
