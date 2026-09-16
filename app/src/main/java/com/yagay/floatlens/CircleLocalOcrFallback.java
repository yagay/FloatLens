package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

import java.util.ArrayList;

/**
 * Gesture-scoped OCR fallback for small logos/icons that full-frame OCR may miss.
 *
 * <p>The fallback never forces PP-OCR. It runs the multi-scale recovery pipeline, and every pass
 * goes through {@link OcrEngine#recognizeDocument(Context, Bitmap, OcrEngine.DocumentCallback)} so
 * Auto / PP Medium / PP Small / ML Kit always follow the user's main OCR setting.</p>
 *
 * <p>{@link GoogleCircleTextResolver} already enlarged the tight pixel-only ROI once. The
 * multi-scale pipeline may inspect further scales/contrast variants; before returning, geometry is
 * mapped back into this input bitmap's coordinate space so the existing Circle screen transform
 * remains correct.</p>
 */
final class CircleLocalOcrFallback {
    private CircleLocalOcrFallback() {}

    static void recognize(Context context, Bitmap bitmap, OcrEngine.DocumentCallback callback) {
        if (context == null || bitmap == null || bitmap.isRecycled() || callback == null) return;
        Context app = context.getApplicationContext();

        DiagnosticLog.i(app, "G_CIRCLE_LOCAL_OCR",
                "start strategy=main_setting_multiscale"
                        + " bitmap=" + bitmap.getWidth() + "x" + bitmap.getHeight()
                        + " enginePolicy=follow_main_setting");

        CircleMultiScaleOcr.recognize(app, bitmap, new CircleMultiScaleOcr.Callback() {
            @Override public void onSuccess(OcrDocument document, float scaleX, float scaleY,
                                            String variant) {
                try {
                    OcrDocument mapped = mapToInput(document, bitmap.getWidth(), bitmap.getHeight(),
                            scaleX, scaleY, variant);
                    DiagnosticLog.i(app, "G_CIRCLE_LOCAL_OCR",
                            "multiscale success variant=" + variant
                                    + " engine=" + (mapped == null ? "none" : mapped.engine())
                                    + " chars=" + (mapped == null ? 0 : mapped.chars().size())
                                    + " scale=" + scaleX + "x" + scaleY);
                    if (mapped == null || mapped.fullText().isBlank() || mapped.chars().isEmpty()) {
                        callback.onFailure(new IllegalStateException("multi-scale OCR empty"));
                    } else {
                        callback.onSuccess(mapped);
                    }
                } catch (Throwable t) {
                    callback.onFailure(t);
                }
            }

            @Override public void onFailure(Throwable error) {
                DiagnosticLog.i(app, "G_CIRCLE_LOCAL_OCR",
                        "multiscale failed error=" + safe(error));
                callback.onFailure(error == null
                        ? new IllegalStateException("multi-scale OCR failed") : error);
            }
        });
    }

    private static OcrDocument mapToInput(OcrDocument source, int baseWidth, int baseHeight,
                                          float scaleX, float scaleY, String variant) {
        if (source == null) return null;
        float sx = Math.max(0.0001f, scaleX);
        float sy = Math.max(0.0001f, scaleY);
        ArrayList<OcrDocument.Line> lines = new ArrayList<>();
        int lineId = 0;
        int order = 0;

        for (OcrDocument.Line line : source.lines()) {
            if (line == null) continue;
            Rect lineBounds = downscale(line.bounds(), sx, sy, baseWidth, baseHeight);
            ArrayList<OcrDocument.CharUnit> chars = new ArrayList<>();
            for (OcrDocument.CharUnit c : line.chars()) {
                if (c == null || c.text().isBlank()) continue;
                Rect bounds = downscale(c.bounds(), sx, sy, baseWidth, baseHeight);
                if (bounds.isEmpty()) continue;
                chars.add(new OcrDocument.CharUnit(c.text(), bounds, c.confidence(),
                        lineId, c.group(), order++));
            }
            if (chars.isEmpty()) continue;
            if (lineBounds.isEmpty()) {
                lineBounds = new Rect(chars.get(0).bounds());
                for (int i = 1; i < chars.size(); i++) lineBounds.union(chars.get(i).bounds());
            }
            lines.add(new OcrDocument.Line(line.text(), lineBounds, line.confidence(), chars));
            lineId++;
        }

        String engine = "multiscale-" + (variant == null ? "unknown" : variant)
                + "-" + source.engine();
        return new OcrDocument(source.fullText(), source.blocks(), lines,
                engine, source.confidence(), source.score(),
                Math.max(1, baseWidth), Math.max(1, baseHeight));
    }

    private static Rect downscale(Rect source, float scaleX, float scaleY,
                                  int width, int height) {
        if (source == null || source.isEmpty()) return new Rect();
        Rect out = new Rect(
                Math.max(0, (int) Math.floor(source.left / scaleX)),
                Math.max(0, (int) Math.floor(source.top / scaleY)),
                Math.min(width, (int) Math.ceil(source.right / scaleX)),
                Math.min(height, (int) Math.ceil(source.bottom / scaleY)));
        if (out.right <= out.left || out.bottom <= out.top) return new Rect();
        return out;
    }

    private static String safe(Throwable error) {
        if (error == null) return "unknown";
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName() : message;
    }
}
