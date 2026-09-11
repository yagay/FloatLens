package com.yagay.floatlens;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;

/** Low-memory, on-demand OCR image preparation for screen-capture crops. */
final class OcrImagePreprocessor {
    static final int MODE_ORIGINAL = 0;
    static final int MODE_ENHANCED = 1;
    static final int MODE_MONO = 2;

    private static final int MAX_DIMENSION = 2400;
    private static final long MAX_PIXELS = 3_000_000L;

    static final class Prepared {
        final String name;
        final Bitmap bitmap;
        final boolean owned;

        Prepared(String name, Bitmap bitmap, boolean owned) {
            this.name = name;
            this.bitmap = bitmap;
            this.owned = owned;
        }
    }

    static Prepared prepare(Bitmap source, int mode) {
        if (source == null || source.isRecycled() || source.getWidth() <= 0 || source.getHeight() <= 0) {
            return null;
        }
        if (mode == MODE_ORIGINAL) return new Prepared("original", source, false);

        float scale = chooseScale(source.getWidth(), source.getHeight());
        Bitmap rendered = render(source, scale, mode == MODE_MONO);
        if (rendered == null) return null;
        return new Prepared(mode == MODE_MONO ? "mono" : "enhanced", rendered, true);
    }

    static String modeName(int mode) {
        if (mode == MODE_MONO) return "MONO";
        if (mode == MODE_ENHANCED) return "ENHANCED";
        return "ORIGINAL";
    }

    private static float chooseScale(int w, int h) {
        int shortSide = Math.min(w, h);
        float wanted;
        if (shortSide < 90) wanted = 3.0f;
        else if (shortSide < 160) wanted = 2.5f;
        else if (shortSide < 260) wanted = 2.0f;
        else if (shortSide < 420) wanted = 1.55f;
        else wanted = 1.2f;

        float byDimension = Math.min(MAX_DIMENSION / (float) Math.max(1, w),
                MAX_DIMENSION / (float) Math.max(1, h));
        float byPixels = (float) Math.sqrt(MAX_PIXELS / (double) Math.max(1L, (long) w * h));
        return Math.max(1f, Math.min(wanted, Math.min(byDimension, byPixels)));
    }

    private static Bitmap render(Bitmap source, float scale, boolean mono) {
        Bitmap out = null;
        try {
            int sw = Math.max(1, Math.round(source.getWidth() * scale));
            int sh = Math.max(1, Math.round(source.getHeight() * scale));
            int pad = Math.max(8, Math.min(28, Math.round(10f * scale)));
            int outW = sw + pad * 2;
            int outH = sh + pad * 2;
            if ((long) outW * outH > MAX_PIXELS + 250_000L) return null;

            out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            canvas.drawColor(estimateBackground(source));

            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
            ColorMatrix cm = new ColorMatrix();
            if (mono) {
                cm.setSaturation(0f);
                cm.postConcat(contrast(1.28f));
            } else {
                cm.setSaturation(0.82f);
                cm.postConcat(contrast(1.14f));
            }
            p.setColorFilter(new ColorMatrixColorFilter(cm));
            canvas.drawBitmap(source, null, new Rect(pad, pad, pad + sw, pad + sh), p);
            return out;
        } catch (Throwable t) {
            if (out != null && !out.isRecycled()) {
                try { out.recycle(); } catch (Throwable ignored) {}
            }
            return null;
        }
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

    private static int estimateBackground(Bitmap b) {
        try {
            int w = b.getWidth(), h = b.getHeight();
            int[] px = {
                    b.getPixel(0, 0), b.getPixel(Math.max(0, w - 1), 0),
                    b.getPixel(0, Math.max(0, h - 1)),
                    b.getPixel(Math.max(0, w - 1), Math.max(0, h - 1))
            };
            float luma = 0f;
            for (int c : px) {
                luma += 0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c);
            }
            return luma / px.length >= 128f ? Color.WHITE : Color.BLACK;
        } catch (Throwable ignored) {
            return Color.WHITE;
        }
    }

    static void recycle(Prepared prepared) {
        if (prepared == null || !prepared.owned || prepared.bitmap == null
                || prepared.bitmap.isRecycled()) return;
        try { prepared.bitmap.recycle(); } catch (Throwable ignored) {}
    }

    private OcrImagePreprocessor() {}
}
