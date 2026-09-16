package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;

/**
 * Gesture-scoped OCR fallback for small logos/icons that full-frame OCR may miss.
 *
 * <p>This lane deliberately prefers PP-OCR regardless of the normal OCR engine setting because it
 * only runs after the frozen full-frame index misses the current gesture. The input is already a
 * small, tightly cropped bitmap, so the extra PP latency is bounded. If no PP model is installed or
 * PP fails/returns no text, normal document OCR is used as the final fallback.</p>
 */
final class CircleLocalOcrFallback {
    private CircleLocalOcrFallback() {}

    static void recognize(Context context, Bitmap bitmap, OcrEngine.DocumentCallback callback) {
        if (context == null || bitmap == null || bitmap.isRecycled() || callback == null) return;
        Context app = context.getApplicationContext();
        boolean medium = OcrModelManager.isReady(app, OcrModelManager.MEDIUM);
        boolean small = OcrModelManager.isReady(app, OcrModelManager.SMALL);

        DiagnosticLog.i(app, "G_CIRCLE_LOCAL_OCR",
                "start strategy=pp_first"
                        + " medium=" + medium
                        + " small=" + small
                        + " bitmap=" + bitmap.getWidth() + "x" + bitmap.getHeight());

        if (medium) {
            runPaddle(app, bitmap, OcrModelManager.MEDIUM, small, callback);
        } else if (small) {
            runPaddle(app, bitmap, OcrModelManager.SMALL, false, callback);
        } else {
            DiagnosticLog.i(app, "G_CIRCLE_LOCAL_OCR",
                    "pp unavailable fallback=normal_document_ocr");
            OcrEngine.recognizeDocument(app, bitmap, callback);
        }
    }

    private static void runPaddle(Context app, Bitmap bitmap, int model,
                                  boolean trySmallAfterFailure,
                                  OcrEngine.DocumentCallback callback) {
        long started = android.os.SystemClock.uptimeMillis();
        PaddleOcrBridge.recognize(app, bitmap, model, new PaddleOcrBridge.Callback() {
            @Override public void onSuccess(OcrDocument document, long totalMs, int lineCount) {
                boolean usable = document != null
                        && !document.fullText().isBlank()
                        && !document.chars().isEmpty();
                DiagnosticLog.i(app, "G_CIRCLE_LOCAL_OCR",
                        "pp done model=" + model
                                + " usable=" + usable
                                + " chars=" + (document == null ? 0 : document.chars().size())
                                + " lines=" + lineCount
                                + " confidence=" + (document == null ? 0f : document.confidence())
                                + " totalMs=" + totalMs);
                if (usable) {
                    callback.onSuccess(document);
                    return;
                }
                fallbackAfterPaddle(app, bitmap, model, trySmallAfterFailure, callback,
                        new IllegalStateException("PP-OCR local result empty"));
            }

            @Override public void onFailure(String message) {
                DiagnosticLog.i(app, "G_CIRCLE_LOCAL_OCR",
                        "pp failed model=" + model
                                + " elapsedMs=" + (android.os.SystemClock.uptimeMillis() - started)
                                + " error=" + message);
                fallbackAfterPaddle(app, bitmap, model, trySmallAfterFailure, callback,
                        new IllegalStateException(message == null ? "PP-OCR local failure" : message));
            }
        });
    }

    private static void fallbackAfterPaddle(Context app, Bitmap bitmap, int previousModel,
                                            boolean trySmall,
                                            OcrEngine.DocumentCallback callback,
                                            Throwable previousError) {
        if (trySmall && previousModel != OcrModelManager.SMALL
                && OcrModelManager.isReady(app, OcrModelManager.SMALL)) {
            DiagnosticLog.i(app, "G_CIRCLE_LOCAL_OCR",
                    "fallback medium->small");
            runPaddle(app, bitmap, OcrModelManager.SMALL, false, callback);
            return;
        }
        DiagnosticLog.i(app, "G_CIRCLE_LOCAL_OCR",
                "fallback pp->normal_document_ocr previous=" + safe(previousError));
        OcrEngine.recognizeDocument(app, bitmap, callback);
    }

    private static String safe(Throwable error) {
        if (error == null) return "unknown";
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName() : message;
    }
}
