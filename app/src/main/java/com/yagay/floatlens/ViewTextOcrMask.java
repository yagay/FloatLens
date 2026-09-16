package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import java.util.HashSet;
import java.util.Set;

/**
 * Removes only reliably located native View text from a bitmap before visual OCR.
 *
 * <p>CircleViewTextSnapshot marks real Accessibility character-location geometry with confidence
 * 1.0 and synthetic/estimated geometry with 0.92. Only the former is eligible for masking. App
 * labels, content descriptions, semantic labels and whole View bounds are never used here.</p>
 */
final class ViewTextOcrMask {
    private static final float EXACT_GEOMETRY_CONFIDENCE = 0.999f;
    private static final float MASK_PAD_DP = 2f;
    private static final int MAX_BORDER_SAMPLES_PER_EDGE = 12;

    static int apply(Context context,
                     Bitmap mutableBitmap,
                     Rect fullBitmapRoi,
                     OcrDocument viewDocument,
                     ScreenBitmapTransform transform) {
        if (context == null || mutableBitmap == null || mutableBitmap.isRecycled()
                || !mutableBitmap.isMutable() || fullBitmapRoi == null || fullBitmapRoi.isEmpty()
                || viewDocument == null || !viewDocument.isScreenSpace() || transform == null) {
            return 0;
        }

        Rect fullFrame = new Rect(0, 0, transform.bitmapWidth(), transform.bitmapHeight());
        Rect roi = new Rect(fullBitmapRoi);
        if (!roi.intersect(fullFrame) || roi.isEmpty()) return 0;

        float screenPad = MASK_PAD_DP * context.getResources().getDisplayMetrics().density;
        int bitmapPad = Math.max(1, Math.round(transform.screenDistanceToBitmap(screenPad)));
        Canvas canvas = new Canvas(mutableBitmap);
        Paint paint = new Paint();
        paint.setStyle(Paint.Style.FILL);
        Set<String> seen = new HashSet<>();
        int masked = 0;

        for (OcrDocument.CharUnit unit : viewDocument.chars()) {
            if (unit == null || unit.text().isBlank()
                    || unit.confidence() < EXACT_GEOMETRY_CONFIDENCE
                    || unit.bounds().isEmpty()) {
                continue;
            }

            Rect full = transform.screenToBitmap(unit.bounds());
            if (full.isEmpty()) continue;
            full.inset(-bitmapPad, -bitmapPad);
            if (!full.intersect(roi) || full.isEmpty()) continue;

            Rect local = new Rect(full);
            local.offset(-roi.left, -roi.top);
            Rect localBounds = new Rect(0, 0, mutableBitmap.getWidth(), mutableBitmap.getHeight());
            if (!local.intersect(localBounds) || local.isEmpty()) continue;
            if (!seen.add(local.flattenToString())) continue;

            paint.setColor(sampleBorderColor(mutableBitmap, local));
            canvas.drawRect(local, paint);
            masked++;
        }
        return masked;
    }

    private static int sampleBorderColor(Bitmap bitmap, Rect rect) {
        long alpha = 0L;
        long red = 0L;
        long green = 0L;
        long blue = 0L;
        int count = 0;

        int top = rect.top - 1;
        int bottom = rect.bottom;
        int left = rect.left - 1;
        int right = rect.right;

        int xStep = Math.max(1, rect.width() / MAX_BORDER_SAMPLES_PER_EDGE);
        for (int x = rect.left; x < rect.right; x += xStep) {
            if (top >= 0) {
                int color = bitmap.getPixel(clamp(x, 0, bitmap.getWidth() - 1), top);
                alpha += Color.alpha(color); red += Color.red(color);
                green += Color.green(color); blue += Color.blue(color); count++;
            }
            if (bottom < bitmap.getHeight()) {
                int color = bitmap.getPixel(clamp(x, 0, bitmap.getWidth() - 1), bottom);
                alpha += Color.alpha(color); red += Color.red(color);
                green += Color.green(color); blue += Color.blue(color); count++;
            }
        }

        int yStep = Math.max(1, rect.height() / MAX_BORDER_SAMPLES_PER_EDGE);
        for (int y = rect.top; y < rect.bottom; y += yStep) {
            if (left >= 0) {
                int color = bitmap.getPixel(left, clamp(y, 0, bitmap.getHeight() - 1));
                alpha += Color.alpha(color); red += Color.red(color);
                green += Color.green(color); blue += Color.blue(color); count++;
            }
            if (right < bitmap.getWidth()) {
                int color = bitmap.getPixel(right, clamp(y, 0, bitmap.getHeight() - 1));
                alpha += Color.alpha(color); red += Color.red(color);
                green += Color.green(color); blue += Color.blue(color); count++;
            }
        }

        if (count <= 0) {
            int x = clamp(rect.centerX(), 0, bitmap.getWidth() - 1);
            int y = clamp(rect.centerY(), 0, bitmap.getHeight() - 1);
            return bitmap.getPixel(x, y);
        }
        return Color.argb(
                (int) (alpha / count),
                (int) (red / count),
                (int) (green / count),
                (int) (blue / count));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private ViewTextOcrMask() {}
}
