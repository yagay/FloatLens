package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;

/**
 * Circle OCR adapter.
 *
 * <p>Circle recognition follows the same OCR engine setting as normal OCR. The selected mode is
 * resolved by {@link OcrEngine}: Auto, PP-OCRv6 Medium, PP-OCRv6 Small, or ML Kit. Circle still owns
 * the frozen-screen cache and gesture-scoped selection; only recognition-engine choice is delegated
 * to the shared OCR pipeline.</p>
 */
final class CircleStableOcr {
    /**
     * Legacy method name retained for source compatibility. Recognition is no longer fixed to ML Kit;
     * it delegates to the OCR engine selected in Settings -> Screenshot & OCR.
     */
    static void recognizeMlKit(Context context, Bitmap bitmap, OcrEngine.DocumentCallback callback) {
        recognizeConfigured(context, bitmap, callback);
    }

    static void recognizeConfigured(Context context, Bitmap bitmap,
                                    OcrEngine.DocumentCallback callback) {
        if (callback == null) return;
        if (context == null || bitmap == null || bitmap.isRecycled()
                || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            callback.onFailure(new IllegalArgumentException("invalid Circle OCR bitmap"));
            return;
        }

        Context app = context.getApplicationContext();
        int mode = new FloatSettings(app).ocrEngineMode();
        long started = android.os.SystemClock.uptimeMillis();
        DiagnosticLog.i(app, "G_CIRCLE_OCR_ENGINE",
                "start mode=" + mode
                        + " selected=" + modeLabel(mode)
                        + " source=settings"
                        + " dispatcher=OcrEngine.recognizeDocument"
                        + " bitmap=" + bitmap.getWidth() + "x" + bitmap.getHeight());

        OcrEngine.recognizeDocument(app, bitmap, new OcrEngine.DocumentCallback() {
            @Override public void onSuccess(OcrDocument document) {
                if (document == null || document.fullText().isBlank() || document.chars().isEmpty()) {
                    callback.onFailure(new IllegalStateException("configured Circle OCR empty"));
                    return;
                }
                DiagnosticLog.i(app, "G_CIRCLE_OCR_ENGINE",
                        "success mode=" + mode
                                + " selected=" + modeLabel(mode)
                                + " actualEngine=" + document.engine()
                                + " chars=" + document.chars().size()
                                + " lines=" + document.lines().size()
                                + " elapsedMs="
                                + (android.os.SystemClock.uptimeMillis() - started));
                callback.onSuccess(document);
            }

            @Override public void onFailure(Throwable error) {
                DiagnosticLog.i(app, "G_CIRCLE_OCR_ENGINE",
                        "failed mode=" + mode
                                + " selected=" + modeLabel(mode)
                                + " error=" + safe(error)
                                + " elapsedMs="
                                + (android.os.SystemClock.uptimeMillis() - started));
                callback.onFailure(error == null
                        ? new IllegalStateException("configured Circle OCR failed") : error);
            }
        });
    }

    private static String modeLabel(int mode) {
        return switch (mode) {
            case 1 -> "ppocr_medium";
            case 2 -> "ppocr_small";
            case 3 -> "mlkit";
            default -> "auto";
        };
    }

    private static String safe(Throwable error) {
        if (error == null) return "unknown";
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName() : message;
    }

    private CircleStableOcr() {}
}
