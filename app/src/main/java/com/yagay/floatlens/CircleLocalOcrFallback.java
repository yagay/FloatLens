package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;

/**
 * Gesture-scoped OCR fallback for image text/logos missed by the frozen multi-pass OCR index.
 *
 * <p>The fallback never forces PP-OCR. Every pass follows the main OCR engine setting. The complete
 * local ROI is tried at multiple scales and normalized back to the original crop inside
 * {@link CircleMultiScaleOcr}. Overlap tiles are recovery-only when every complete-ROI pass is
 * empty, and tile results are never globally merged.</p>
 */
final class CircleLocalOcrFallback {
    private CircleLocalOcrFallback() {}

    static void recognize(Context context, Bitmap bitmap, OcrEngine.DocumentCallback callback) {
        if (context == null || bitmap == null || bitmap.isRecycled() || callback == null) return;
        Context app = context.getApplicationContext();

        DiagnosticLog.i(app, "G_CIRCLE_LOCAL_OCR",
                "start strategy=main_setting_multiscale_consensus"
                        + " bitmap=" + bitmap.getWidth() + "x" + bitmap.getHeight()
                        + " tileRecovery=full_empty_only"
                        + " tileMerge=false"
                        + " geometry=normalized_once"
                        + " enginePolicy=follow_main_setting");

        CircleMultiScaleOcr.recognize(app, bitmap, new CircleMultiScaleOcr.Callback() {
            @Override public void onSuccess(OcrDocument document, float scaleX, float scaleY,
                                            String variant) {
                try {
                    OcrDocument normalized = normalizeIfNeeded(document,
                            bitmap.getWidth(), bitmap.getHeight(), variant);
                    DiagnosticLog.i(app, "G_CIRCLE_LOCAL_OCR",
                            "enhanced success variant=" + variant
                                    + " engine=" + (normalized == null ? "none" : normalized.engine())
                                    + " chars=" + (normalized == null ? 0 : normalized.chars().size())
                                    + " reportedScale=" + scaleX + "x" + scaleY
                                    + " sourceSize=" + (document == null ? "none"
                                    : document.imageWidth() + "x" + document.imageHeight())
                                    + " remapped=" + (document != normalized));
                    if (normalized == null || normalized.fullText().isBlank()
                            || normalized.chars().isEmpty()) {
                        callback.onFailure(new IllegalStateException("enhanced local OCR empty"));
                    } else {
                        callback.onSuccess(normalized);
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

    private static OcrDocument normalizeIfNeeded(OcrDocument source,
                                                 int baseWidth, int baseHeight,
                                                 String variant) {
        if (source == null) return null;
        int width = Math.max(1, baseWidth);
        int height = Math.max(1, baseHeight);
        if (source.isBitmapSpace()
                && source.imageWidth() == width && source.imageHeight() == height) {
            return source;
        }

        CoordinateMapper mapper = new CoordinateMapper(
                new RectF(0f, 0f, Math.max(1, source.imageWidth()),
                        Math.max(1, source.imageHeight())),
                new RectF(0f, 0f, width, height));
        return mapper.mapDocument(source, false, width, height,
                "fallback-normalize-" + (variant == null ? "unknown" : variant) + "-");
    }

    private static String safe(Throwable error) {
        if (error == null) return "unknown";
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName() : message;
    }
}
