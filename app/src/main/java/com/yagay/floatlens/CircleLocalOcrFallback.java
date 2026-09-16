package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;

/**
 * Gesture-scoped OCR fallback for image text/logos missed by the frozen full-frame OCR index.
 *
 * <p>The fallback never forces PP-OCR. Every pass runs through the main OCR engine setting. Small
 * ROIs use multiscale whole-image recognition; larger ROIs can additionally use overlapping tiles.
 * All variant/tile geometry is normalized back to the original local crop with
 * {@link CoordinateMapper}, using the OCR document's actual dimensions rather than assuming the
 * requested scale was achieved.</p>
 */
final class CircleLocalOcrFallback {
    private CircleLocalOcrFallback() {}

    static void recognize(Context context, Bitmap bitmap, OcrEngine.DocumentCallback callback) {
        if (context == null || bitmap == null || bitmap.isRecycled() || callback == null) return;
        Context app = context.getApplicationContext();

        DiagnosticLog.i(app, "G_CIRCLE_LOCAL_OCR",
                "start strategy=main_setting_multiscale_overlap_tiles"
                        + " bitmap=" + bitmap.getWidth() + "x" + bitmap.getHeight()
                        + " geometry=matrix_actual_document_size"
                        + " enginePolicy=follow_main_setting");

        CircleMultiScaleOcr.recognize(app, bitmap, new CircleMultiScaleOcr.Callback() {
            @Override public void onSuccess(OcrDocument document, float scaleX, float scaleY,
                                            String variant) {
                try {
                    OcrDocument mapped = mapToInput(document, bitmap.getWidth(), bitmap.getHeight(),
                            variant);
                    DiagnosticLog.i(app, "G_CIRCLE_LOCAL_OCR",
                            "enhanced success variant=" + variant
                                    + " engine=" + (mapped == null ? "none" : mapped.engine())
                                    + " chars=" + (mapped == null ? 0 : mapped.chars().size())
                                    + " reportedScale=" + scaleX + "x" + scaleY
                                    + " sourceSize=" + (document == null ? "none"
                                    : document.imageWidth() + "x" + document.imageHeight()));
                    if (mapped == null || mapped.fullText().isBlank() || mapped.chars().isEmpty()) {
                        callback.onFailure(new IllegalStateException("enhanced local OCR empty"));
                    } else {
                        callback.onSuccess(mapped);
                    }
                } catch (Throwable t) {
                    callback.onFailure(t);
                }
            }

            @Override public void onFailure(Throwable error) {
                DiagnosticLog.i(app, "G_CIRCLE_LOCAL_OCR",
                        "enhanced failed error=" + safe(error));
                callback.onFailure(error == null
                        ? new IllegalStateException("enhanced local OCR failed") : error);
            }
        });
    }

    private static OcrDocument mapToInput(OcrDocument source, int baseWidth, int baseHeight,
                                          String variant) {
        if (source == null) return null;
        int sourceWidth = Math.max(1, source.imageWidth());
        int sourceHeight = Math.max(1, source.imageHeight());
        CoordinateMapper mapper = new CoordinateMapper(
                new RectF(0f, 0f, sourceWidth, sourceHeight),
                new RectF(0f, 0f, Math.max(1, baseWidth), Math.max(1, baseHeight)));
        return mapper.mapDocument(source, false,
                Math.max(1, baseWidth), Math.max(1, baseHeight),
                "multiscale-" + (variant == null ? "unknown" : variant) + "-");
    }

    private static String safe(Throwable error) {
        if (error == null) return "unknown";
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName() : message;
    }
}
