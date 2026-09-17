package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognizer;

/**
 * Circle OCR adapter for the hybrid frozen-screen workflow.
 *
 * <p>The complete frozen frame is indexed with ML Kit only. Every TAP / HIGHLIGHT / SCRIBBLE then
 * runs PP-OCR on a small gesture-local bitmap. The resolver compares the two gesture-scoped results
 * and keeps the ML selection when they agree, otherwise the PP result wins. When hybrid mode is
 * disabled, {@link #recognizeConfigured(Context, Bitmap, OcrEngine.DocumentCallback)} preserves the
 * previous Settings-selected OCR behavior.</p>
 */
final class CircleStableOcr {
    static void recognizeFullScreenMlKit(Context context, Bitmap bitmap,
                                         OcrEngine.DocumentCallback callback) {
        if (!valid(context, bitmap, callback)) return;
        Context app = context.getApplicationContext();
        long started = android.os.SystemClock.uptimeMillis();
        TextRecognizer recognizer;
        try {
            recognizer = MlKitTextCore.createPreferredRecognizer(app);
        } catch (Throwable t) {
            callback.onFailure(t);
            return;
        }

        String engine = MlKitTextCore.preferredEngine("mlkit-circle-full", app);
        DiagnosticLog.i(app, "G_CIRCLE_ML_INDEX",
                "start engine=" + engine
                        + " bitmap=" + bitmap.getWidth() + "x" + bitmap.getHeight()
                        + " role=full_screen_index");
        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener(text -> {
                        try {
                            OcrDocument document = MlKitTextCore.toDocument(text, engine,
                                    bitmap.getWidth(), bitmap.getHeight(), 0f, null);
                            if (document == null || document.fullText().isBlank()
                                    || document.chars().isEmpty()) {
                                callback.onFailure(new IllegalStateException("ML Kit full-screen OCR empty"));
                                return;
                            }
                            DiagnosticLog.i(app, "G_CIRCLE_ML_INDEX",
                                    "success engine=" + document.engine()
                                            + " chars=" + document.chars().size()
                                            + " lines=" + document.lines().size()
                                            + " elapsedMs="
                                            + (android.os.SystemClock.uptimeMillis() - started));
                            callback.onSuccess(document);
                        } catch (Throwable t) {
                            callback.onFailure(t);
                        } finally {
                            try { recognizer.close(); } catch (Throwable ignored) {}
                        }
                    })
                    .addOnFailureListener(error -> {
                        try { recognizer.close(); } catch (Throwable ignored) {}
                        DiagnosticLog.i(app, "G_CIRCLE_ML_INDEX",
                                "failed error=" + safe(error)
                                        + " elapsedMs="
                                        + (android.os.SystemClock.uptimeMillis() - started));
                        callback.onFailure(error);
                    });
        } catch (Throwable t) {
            try { recognizer.close(); } catch (Throwable ignored) {}
            callback.onFailure(t);
        }
    }

    static void recognizePaddleRegion(Context context, Bitmap bitmap,
                                      OcrEngine.DocumentCallback callback) {
        if (!valid(context, bitmap, callback)) return;
        Context app = context.getApplicationContext();
        int mode = new FloatSettings(app).ocrEngineMode();
        int model = chooseLocalPaddleModel(app, mode);
        if (model == 0) {
            callback.onFailure(new IllegalStateException("No PP-OCR model available for Circle region"));
            return;
        }
        boolean autoEscalate = mode == 0 || mode == 3;
        runPaddle(app, bitmap, callback, model, autoEscalate,
                model == OcrModelManager.SMALL && OcrModelManager.isReady(app, OcrModelManager.MEDIUM));
    }

    private static void runPaddle(Context app, Bitmap bitmap, OcrEngine.DocumentCallback callback,
                                  int model, boolean autoEscalate, boolean mediumAvailable) {
        long started = android.os.SystemClock.uptimeMillis();
        DiagnosticLog.i(app, "G_CIRCLE_PP_REGION",
                "start model=" + model
                        + " modelName=" + OcrModelManager.displayName(model)
                        + " bitmap=" + bitmap.getWidth() + "x" + bitmap.getHeight()
                        + " role=gesture_verifier"
                        + " autoEscalate=" + autoEscalate);
        PaddleOcrBridge.recognize(app, bitmap, model, new PaddleOcrBridge.Callback() {
            @Override public void onSuccess(OcrDocument raw, long totalMs, int lineCount) {
                if (raw == null || raw.fullText().isBlank() || raw.chars().isEmpty()) {
                    if (autoEscalate && model == OcrModelManager.SMALL && mediumAvailable) {
                        DiagnosticLog.i(app, "G_CIRCLE_PP_REGION",
                                "small empty -> medium retry");
                        runPaddle(app, bitmap, callback, OcrModelManager.MEDIUM, false, false);
                        return;
                    }
                    callback.onFailure(new IllegalStateException("PP-OCR region empty"));
                    return;
                }
                DiagnosticLog.i(app, "G_CIRCLE_PP_REGION",
                        "success model=" + model
                                + " engine=" + raw.engine()
                                + " chars=" + raw.chars().size()
                                + " lines=" + lineCount
                                + " elapsedMs="
                                + (android.os.SystemClock.uptimeMillis() - started));
                callback.onSuccess(raw);
            }

            @Override public void onFailure(String message) {
                if (autoEscalate && model == OcrModelManager.SMALL && mediumAvailable) {
                    DiagnosticLog.i(app, "G_CIRCLE_PP_REGION",
                            "small failed -> medium retry error=" + message);
                    runPaddle(app, bitmap, callback, OcrModelManager.MEDIUM, false, false);
                    return;
                }
                callback.onFailure(new IllegalStateException(
                        message == null || message.isBlank() ? "PP-OCR region failed" : message));
            }
        });
    }

    private static int chooseLocalPaddleModel(Context app, int mode) {
        if (mode == 1) {
            return OcrModelManager.isReady(app, OcrModelManager.MEDIUM)
                    ? OcrModelManager.MEDIUM : 0;
        }
        if (mode == 2) {
            return OcrModelManager.isReady(app, OcrModelManager.SMALL)
                    ? OcrModelManager.SMALL : 0;
        }
        if (OcrModelManager.isReady(app, OcrModelManager.SMALL)) return OcrModelManager.SMALL;
        if (OcrModelManager.isReady(app, OcrModelManager.MEDIUM)) return OcrModelManager.MEDIUM;
        return 0;
    }

    static void recognizeConfigured(Context context, Bitmap bitmap,
                                    OcrEngine.DocumentCallback callback) {
        if (!valid(context, bitmap, callback)) return;
        OcrEngine.recognizeDocument(context.getApplicationContext(), bitmap, callback);
    }

    static void recognizeMlKit(Context context, Bitmap bitmap, OcrEngine.DocumentCallback callback) {
        recognizeFullScreenMlKit(context, bitmap, callback);
    }

    private static boolean valid(Context context, Bitmap bitmap,
                                 OcrEngine.DocumentCallback callback) {
        if (callback == null) return false;
        if (context == null || bitmap == null || bitmap.isRecycled()
                || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            callback.onFailure(new IllegalArgumentException("invalid Circle OCR bitmap"));
            return false;
        }
        return true;
    }

    private static String safe(Throwable error) {
        if (error == null) return "unknown";
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName() : message;
    }

    private CircleStableOcr() {}
}
