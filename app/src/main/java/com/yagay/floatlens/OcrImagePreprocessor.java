package com.yagay.floatlens;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;

import java.util.ArrayList;
import java.util.List;

/**
 * Prepares screen-capture crops for OCR. Small UI text benefits strongly from being presented to
 * ML Kit with enough pixels per glyph, while padding avoids clipping first/last characters.
 */
final class OcrImagePreprocessor {
    private static final int MAX_DIMENSION = 3200;
    private static final long MAX_PIXELS = 6_000_000L;

    record Variant(String name, Bitmap bitmap, boolean owned) {}

    static List<Variant> build(Bitmap source) {
        ArrayList<Variant> out = new ArrayList<>();
        if (source == null || source.isRecycled() || source.getWidth() <= 0 || source.getHeight() <= 0) {
            return out;
        }
        out.add(new Variant("original", source, false));

        float scale = chooseScale(source.getWidth(), source.getHeight());
        Bitmap enhanced = render(source, scale, false);
        if (enhanced != null) out.add(new Variant("enhanced", enhanced, true));

        Bitmap mono = render(source, scale, true);
        if (mono != null) out.add(new Variant("mono", mono, true));
        return out;
    }

    private static float chooseScale(int w, int h) {
        int shortSide = Math.min(w, h);
        float wanted;
        if (shortSide < 90) wanted = 3.2f;
        else if (shortSide < 160) wanted = 2.7f;
        else if (shortSide < 260) wanted = 2.15f;
        else if (shortSide < 420) wanted = 1.6f;
        else wanted = 1.25f;

        float byDimension = Math.min(MAX_DIMENSION / (float) Math.max(1, w),
                MAX_DIMENSION / (float) Math.max(1, h));
        float byPixels = (float) Math.sqrt(MAX_PIXELS / (double) Math.max(1L, (long) w * h));
        return Math.max(1f, Math.min(wanted, Math.min(byDimension, byPixels)));
    }

    private static Bitmap render(Bitmap source, float scale, boolean mono) {
        try {
            int sw = Math.max(1, Math.round(source.getWidth() * scale));
            int sh = Math.max(1, Math.round(source.getHeight() * scale));
            int pad = Math.max(12, Math.min(42, Math.round(14f * scale)));
            int outW = sw + pad * 2;
            int outH = sh + pad * 2;
            if ((long) outW * outH > MAX_PIXELS + 600_000L) return null;

            Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            int bg = estimateBackground(source);
            canvas.drawColor(bg);

            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
            ColorMatrix cm = new ColorMatrix();
            if (mono) {
                cm.setSaturation(0f);
                ColorMatrix contrast = contrast(1.38f);
                cm.postConcat(contrast);
            } else {
                cm.setSaturation(0.72f);
                ColorMatrix contrast = contrast(1.18f);
                cm.postConcat(contrast);
            }
            p.setColorFilter(new ColorMatrixColorFilter(cm));
            canvas.drawBitmap(source, null,
                    new android.graphics.Rect(pad, pad, pad + sw, pad + sh), p);
            return out;
        } catch (Throwable t) {
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
            for (int c : px) luma += 0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c);
            return luma / px.length >= 128f ? Color.WHITE : Color.BLACK;
        } catch (Throwable ignored) {
            return Color.WHITE;
        }
    }

    static void recycleOwned(List<Variant> variants) {
        if (variants == null) return;
        for (Variant v : variants) {
            if (v == null || !v.owned() || v.bitmap() == null || v.bitmap().isRecycled()) continue;
            try { v.bitmap().recycle(); } catch (Throwable ignored) {}
        }
    }

    private OcrImagePreprocessor() {}
}
