package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;

import java.util.function.BooleanSupplier;

/**
 * Gesture-scoped OCR fallback and quality verifier for image text.
 *
 * <p>The fallback follows the user's main OCR engine setting and runs exactly three deterministic
 * representations of the same tight crop: adaptive upscale, grayscale/high-contrast and automatic
 * polarity binary. No app/icon/brand/language rule, local tiling, scale ladder or document merge is
 * used. The winning pass is always one complete recognizer document.</p>
 */
final class CircleLocalOcrFallback {
    private CircleLocalOcrFallback() {}

    static void recognize(Context context, Bitmap bitmap, OcrEngine.DocumentCallback callback) {
        recognize(context, bitmap, () -> false, callback);
    }

    static void recognize(Context context, Bitmap bitmap,
                          BooleanSupplier cancelled,
                          OcrEngine.DocumentCallback callback) {
        if (context == null || bitmap == null || bitmap.isRecycled() || callback == null) return;
        Context app = context.getApplicationContext();

        DiagnosticLog.i(app, "G_CIRCLE_LOCAL_OCR",
                "start strategy=three_variant_image_text"
                        + " bitmap=" + bitmap.getWidth() + "x" + bitmap.getHeight()
                        + " variants=upscale,contrast,adaptive-binary"
                        + " tiles=false merge=false scriptBias=false contentHints=false"
                        + " structure=single_complete_document"
                        + " geometry=normalized_once"
                        + " workspaceCancellation=true"
                        + " enginePolicy=follow_main_setting");

        CircleMultiScaleOcr.recognize(app, bitmap, cancelled, new CircleMultiScaleOcr.Callback() {
            @Override public void onSuccess(OcrDocument document, float scaleX, float scaleY,
                                            String variant) {
                try {
                    OcrDocument normalized = normalizeIfNeeded(document,
                            bitmap.getWidth(), bitmap.getHeight(), variant);
                    DiagnosticLog.i(app, "G_CIRCLE_LOCAL_OCR",
                            "verified success variant=" + variant
                                    + " engine=" + (normalized == null ? "none" : normalized.engine())
                                    + " chars=" + (normalized == null ? 0 : normalized.chars().size())
                                    + " reportedScale=" + scaleX + "x" + scaleY
                                    + " sourceSize=" + (document == null ? "none"
                                    : document.imageWidth() + "x" + document.imageHeight())
                                    + " remapped=" + (document != normalized));
                    if (normalized == null || normalized.fullText().isBlank()
                            || normalized.chars().isEmpty()) {
                        callback.onFailure(new IllegalStateException("local image OCR empty"));
                    } else {
                        callback.onSuccess(normalized);
                    }
                } catch (Throwable t) {
                    callback.onFailure(t);
                }
            }

            @Override public void onFailure(Throwable error) {
                DiagnosticLog.i(app, "G_CIRCLE_LOCAL_OCR",
                        "verified failed error=" + safe(error));
                callback.onFailure(error == null
                        ? new IllegalStateException("local image OCR failed") : error);
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
