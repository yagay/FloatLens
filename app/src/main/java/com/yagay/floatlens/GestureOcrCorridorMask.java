package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;

import java.util.List;

/**
 * Restricts gesture-local OCR input to a narrow corridor around the actual sampled stroke.
 *
 * <p>The local crop remains rectangular for efficiency, but pixels outside the real
 * SCRIBBLE/HIGHLIGHT path are replaced with a neutral color sampled from the crop border. TAP and
 * closed CIRCLE gestures are deliberately untouched. No semantic/app metadata participates.</p>
 */
final class GestureOcrCorridorMask {
    private static final float CORRIDOR_HALF_WIDTH_DP = 18f;
    private static final int MAX_BORDER_SAMPLES = 96;

    static final class Result {
        final boolean applied;
        final int pointCount;
        final float halfWidthBitmapPx;

        Result(boolean applied, int pointCount, float halfWidthBitmapPx) {
            this.applied = applied;
            this.pointCount = Math.max(0, pointCount);
            this.halfWidthBitmapPx = Math.max(0f, halfWidthBitmapPx);
        }
    }

    static Result apply(Context context,
                        Bitmap mutableCrop,
                        Rect fullBitmapRoi,
                        GoogleCircleSelection.Selection gesture,
                        ScreenBitmapTransform transform) {
        if (context == null || mutableCrop == null || mutableCrop.isRecycled()
                || !mutableCrop.isMutable() || fullBitmapRoi == null || fullBitmapRoi.isEmpty()
                || gesture == null || transform == null
                || gesture.kind == GoogleCircleSelection.Kind.TAP
                || gesture.kind == GoogleCircleSelection.Kind.CIRCLE) {
            return new Result(false, gesture == null ? 0 : gesture.points.size(), 0f);
        }

        List<PointF> points = gesture.points;
        if (points == null || points.size() < 2) {
            return new Result(false, points == null ? 0 : points.size(), 0f);
        }

        float screenHalfWidth = CORRIDOR_HALF_WIDTH_DP
                * context.getResources().getDisplayMetrics().density;
        float halfWidth = Math.max(1f, transform.screenDistanceToBitmap(screenHalfWidth));
        float strokeWidth = Math.max(2f, halfWidth * 2f);

        Bitmap mask = null;
        try {
            int width = mutableCrop.getWidth();
            int height = mutableCrop.getHeight();
            if (width <= 0 || height <= 0) return new Result(false, points.size(), halfWidth);

            mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas maskCanvas = new Canvas(mask);
            Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            strokePaint.setColor(Color.WHITE);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(strokeWidth);
            strokePaint.setStrokeCap(Paint.Cap.ROUND);
            strokePaint.setStrokeJoin(Paint.Join.ROUND);

            Path path = new Path();
            PointF first = points.get(0);
            path.moveTo(first.x - fullBitmapRoi.left, first.y - fullBitmapRoi.top);
            for (int i = 1; i < points.size(); i++) {
                PointF point = points.get(i);
                if (point == null) continue;
                path.lineTo(point.x - fullBitmapRoi.left, point.y - fullBitmapRoi.top);
            }
            maskCanvas.drawPath(path, strokePaint);

            int background = sampleBorderColor(mutableCrop);
            int[] pixels = new int[width * height];
            int[] maskPixels = new int[width * height];
            mutableCrop.getPixels(pixels, 0, width, 0, 0, width, height);
            mask.getPixels(maskPixels, 0, width, 0, 0, width, height);

            int bgA = Color.alpha(background);
            int bgR = Color.red(background);
            int bgG = Color.green(background);
            int bgB = Color.blue(background);
            for (int i = 0; i < pixels.length; i++) {
                int keep = Color.alpha(maskPixels[i]);
                if (keep >= 255) continue;
                if (keep <= 0) {
                    pixels[i] = background;
                    continue;
                }
                int src = pixels[i];
                int inv = 255 - keep;
                pixels[i] = Color.argb(
                        (Color.alpha(src) * keep + bgA * inv) / 255,
                        (Color.red(src) * keep + bgR * inv) / 255,
                        (Color.green(src) * keep + bgG * inv) / 255,
                        (Color.blue(src) * keep + bgB * inv) / 255);
            }
            mutableCrop.setPixels(pixels, 0, width, 0, 0, width, height);
            return new Result(true, points.size(), halfWidth);
        } catch (Throwable ignored) {
            return new Result(false, points.size(), halfWidth);
        } finally {
            if (mask != null && !mask.isRecycled()) mask.recycle();
        }
    }

    private static int sampleBorderColor(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= 0 || height <= 0) return Color.TRANSPARENT;

        long alpha = 0L, red = 0L, green = 0L, blue = 0L;
        int count = 0;
        int perimeter = Math.max(1, width * 2 + Math.max(0, height - 2) * 2);
        int step = Math.max(1, perimeter / MAX_BORDER_SAMPLES);
        for (int p = 0; p < perimeter; p += step) {
            int x;
            int y;
            if (p < width) {
                x = p;
                y = 0;
            } else if (p < width + Math.max(0, height - 2)) {
                x = width - 1;
                y = 1 + p - width;
            } else if (p < width * 2 + Math.max(0, height - 2)) {
                x = width - 1 - (p - width - Math.max(0, height - 2));
                y = height - 1;
            } else {
                x = 0;
                y = height - 2 - (p - width * 2 - Math.max(0, height - 2));
            }
            x = clamp(x, 0, width - 1);
            y = clamp(y, 0, height - 1);
            int color = bitmap.getPixel(x, y);
            alpha += Color.alpha(color);
            red += Color.red(color);
            green += Color.green(color);
            blue += Color.blue(color);
            count++;
        }
        if (count <= 0) return bitmap.getPixel(0, 0);
        return Color.argb((int) (alpha / count), (int) (red / count),
                (int) (green / count), (int) (blue / count));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private GestureOcrCorridorMask() {}
}
