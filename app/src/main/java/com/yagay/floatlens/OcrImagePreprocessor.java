package com.yagay.floatlens;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;

/** Low-memory, on-demand OCR image preparation with reversible source geometry. */
final class OcrImagePreprocessor {
    static final int MODE_ORIGINAL = 0;
    static final int MODE_ENHANCED = 1;
    static final int MODE_MONO = 2;
    static final int MODE_INVERTED = 3;
    static final int MODE_BINARY = 4;

    // A modern full-screen phone screenshot can exceed 3 MP before any scaling. The previous
    // 3 MP ceiling silently skipped every non-original full-screen rescue pass on such devices.
    private static final int MAX_DIMENSION = 3600;
    private static final long MAX_PIXELS = 6_000_000L;

    static final class Prepared {
        final String name;
        final Bitmap bitmap;
        final boolean owned;
        final float scale;
        final int padX;
        final int padY;
        final int sourceWidth;
        final int sourceHeight;

        Prepared(String name, Bitmap bitmap, boolean owned,
                 float scale, int padX, int padY, int sourceWidth, int sourceHeight) {
            this.name = name;
            this.bitmap = bitmap;
            this.owned = owned;
            this.scale = Math.max(0.0001f, scale);
            this.padX = Math.max(0, padX);
            this.padY = Math.max(0, padY);
            this.sourceWidth = Math.max(1, sourceWidth);
            this.sourceHeight = Math.max(1, sourceHeight);
        }

        /** Map an OCR rectangle from the prepared image back to the original source bitmap. */
        Rect toSourceRect(Rect preparedRect) {
            if (preparedRect == null || preparedRect.isEmpty()) return new Rect();
            int left = clamp(Math.round((preparedRect.left - padX) / scale), 0, sourceWidth - 1);
            int top = clamp(Math.round((preparedRect.top - padY) / scale), 0, sourceHeight - 1);
            int right = clamp(Math.round((preparedRect.right - padX) / scale), left + 1, sourceWidth);
            int bottom = clamp(Math.round((preparedRect.bottom - padY) / scale), top + 1, sourceHeight);
            return new Rect(left, top, right, bottom);
        }
    }

    static Prepared prepare(Bitmap source, int mode) {
        if (source == null || source.isRecycled() || source.getWidth() <= 0 || source.getHeight() <= 0) {
            return null;
        }
        if (mode == MODE_ORIGINAL) {
            return new Prepared("original", source, false, 1f, 0, 0,
                    source.getWidth(), source.getHeight());
        }

        float scale = chooseScale(source.getWidth(), source.getHeight());
        if (mode == MODE_BINARY) return renderBinary(source, scale);
        return renderColor(source, scale, mode);
    }

    static String modeName(int mode) {
        if (mode == MODE_MONO) return "MONO";
        if (mode == MODE_ENHANCED) return "ENHANCED";
        if (mode == MODE_INVERTED) return "INVERTED";
        if (mode == MODE_BINARY) return "BINARY";
        return "ORIGINAL";
    }

    private static float chooseScale(int w, int h) {
        int shortSide = Math.min(w, h);
        float wanted;
        if (shortSide < 90) wanted = 3.0f;
        else if (shortSide < 160) wanted = 2.5f;
        else if (shortSide < 260) wanted = 2.0f;
        else if (shortSide < 420) wanted = 1.65f;
        else wanted = 1.2f;

        float byDimension = Math.min(MAX_DIMENSION / (float) Math.max(1, w),
                MAX_DIMENSION / (float) Math.max(1, h));
        float byPixels = (float) Math.sqrt(MAX_PIXELS / (double) Math.max(1L, (long) w * h));
        return Math.max(1f, Math.min(wanted, Math.min(byDimension, byPixels)));
    }

    private static Prepared renderColor(Bitmap source, float scale, int mode) {
        Bitmap out = null;
        try {
            int sw = Math.max(1, Math.round(source.getWidth() * scale));
            int sh = Math.max(1, Math.round(source.getHeight() * scale));
            int pad = Math.max(10, Math.min(36, Math.round(12f * scale)));
            int outW = sw + pad * 2;
            int outH = sh + pad * 2;
            if ((long) outW * outH > MAX_PIXELS + 500_000L) return null;

            out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            canvas.drawColor(mode == MODE_INVERTED ? Color.WHITE : estimateBackground(source));

            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
            ColorMatrix cm = new ColorMatrix();
            String name;
            if (mode == MODE_MONO) {
                name = "mono";
                cm.setSaturation(0f);
                cm.postConcat(contrast(1.32f));
            } else if (mode == MODE_INVERTED) {
                name = "inverted";
                cm.setSaturation(0f);
                cm.postConcat(contrast(1.18f));
                cm.postConcat(invert());
            } else {
                name = "enhanced";
                cm.setSaturation(0.82f);
                cm.postConcat(contrast(1.16f));
            }
            p.setColorFilter(new ColorMatrixColorFilter(cm));
            canvas.drawBitmap(source, null, new Rect(pad, pad, pad + sw, pad + sh), p);
            return new Prepared(name, out, true,
                    scale, pad, pad, source.getWidth(), source.getHeight());
        } catch (Throwable t) {
            if (out != null && !out.isRecycled()) {
                try { out.recycle(); } catch (Throwable ignored) {}
            }
            return null;
        }
    }

    /**
     * Convert high-contrast logo/UI text to the OCR-friendly black-on-white form. The threshold is
     * chosen from the source histogram and the polarity comes from the border luminance, so white
     * text on red/dark cards becomes black text on white without losing source-coordinate mapping.
     */
    private static Prepared renderBinary(Bitmap source, float scale) {
        Bitmap out = null;
        try {
            int sw = Math.max(1, Math.round(source.getWidth() * scale));
            int sh = Math.max(1, Math.round(source.getHeight() * scale));
            int pad = Math.max(12, Math.min(40, Math.round(14f * scale)));
            int outW = sw + pad * 2;
            int outH = sh + pad * 2;
            if ((long) outW * outH > MAX_PIXELS + 500_000L) return null;

            out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            canvas.drawColor(Color.WHITE);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
            canvas.drawBitmap(source, null, new Rect(pad, pad, pad + sw, pad + sh), p);

            int threshold = otsuThreshold(source);
            boolean darkBackground = estimateBorderLuma(source) < threshold;
            int[] row = new int[sw];
            for (int y = 0; y < sh; y++) {
                out.getPixels(row, 0, sw, pad, pad + y, sw, 1);
                for (int x = 0; x < sw; x++) {
                    int luma = luma(row[x]);
                    boolean foreground = darkBackground ? luma > threshold : luma < threshold;
                    row[x] = foreground ? Color.BLACK : Color.WHITE;
                }
                out.setPixels(row, 0, sw, pad, pad + y, sw, 1);
            }
            return new Prepared("binary", out, true,
                    scale, pad, pad, source.getWidth(), source.getHeight());
        } catch (Throwable t) {
            if (out != null && !out.isRecycled()) {
                try { out.recycle(); } catch (Throwable ignored) {}
            }
            return null;
        }
    }

    private static int otsuThreshold(Bitmap source) {
        try {
            int[] hist = new int[256];
            int w = source.getWidth(), h = source.getHeight();
            long pixels = Math.max(1L, (long) w * h);
            int stride = Math.max(1, (int) Math.sqrt(pixels / 180_000d));
            long total = 0;
            long sum = 0;
            for (int y = 0; y < h; y += stride) {
                for (int x = 0; x < w; x += stride) {
                    int v = luma(source.getPixel(x, y));
                    hist[v]++;
                    total++;
                    sum += v;
                }
            }
            if (total <= 0) return 160;
            long backgroundWeight = 0;
            long backgroundSum = 0;
            double bestVariance = -1d;
            int best = 160;
            for (int t = 0; t < 256; t++) {
                backgroundWeight += hist[t];
                if (backgroundWeight == 0) continue;
                long foregroundWeight = total - backgroundWeight;
                if (foregroundWeight == 0) break;
                backgroundSum += (long) t * hist[t];
                double meanB = backgroundSum / (double) backgroundWeight;
                double meanF = (sum - backgroundSum) / (double) foregroundWeight;
                double diff = meanB - meanF;
                double between = backgroundWeight * (double) foregroundWeight * diff * diff;
                if (between > bestVariance) {
                    bestVariance = between;
                    best = t;
                }
            }
            return Math.max(72, Math.min(220, best));
        } catch (Throwable ignored) {
            return 160;
        }
    }

    private static int estimateBorderLuma(Bitmap b) {
        try {
            int w = b.getWidth(), h = b.getHeight();
            int insetX = Math.max(0, w / 20);
            int insetY = Math.max(0, h / 20);
            int[] px = {
                    b.getPixel(insetX, insetY),
                    b.getPixel(Math.max(0, w - 1 - insetX), insetY),
                    b.getPixel(insetX, Math.max(0, h - 1 - insetY)),
                    b.getPixel(Math.max(0, w - 1 - insetX), Math.max(0, h - 1 - insetY)),
                    b.getPixel(w / 2, insetY),
                    b.getPixel(w / 2, Math.max(0, h - 1 - insetY))
            };
            long sum = 0;
            for (int c : px) sum += luma(c);
            return (int) (sum / px.length);
        } catch (Throwable ignored) {
            return 128;
        }
    }

    private static int luma(int c) {
        return Math.round(0.2126f * Color.red(c)
                + 0.7152f * Color.green(c)
                + 0.0722f * Color.blue(c));
    }

    private static ColorMatrix contrast(float amount) {
        float translate = 128f * (1f - amount);
        return new ColorMatrix(new float[]{
                amount, 0, 0, 0, translate,
                0, amount, 0, 0, translate,
                0, 0, amount, 0, translate,
                0, 0, 0, 1, 0
        });
    }

    private static ColorMatrix invert() {
        return new ColorMatrix(new float[]{
                -1, 0, 0, 0, 255,
                0, -1, 0, 0, 255,
                0, 0, -1, 0, 255,
                0, 0, 0, 1, 0
        });
    }

    private static int estimateBackground(Bitmap b) {
        try {
            return estimateBorderLuma(b) >= 128 ? Color.WHITE : Color.BLACK;
        } catch (Throwable ignored) {
            return Color.WHITE;
        }
    }

    static void recycle(Prepared prepared) {
        if (prepared == null || !prepared.owned || prepared.bitmap == null
                || prepared.bitmap.isRecycled()) return;
        try { prepared.bitmap.recycle(); } catch (Throwable ignored) {}
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private OcrImagePreprocessor() {}
}
