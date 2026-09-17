package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognizer;

/**
 * Circle OCR adapter with independently selectable recognition engines.
 *
 * <p>Normal Circle mode is deliberately OCR-only: it never traverses Accessibility Views for
 * text, never merges View text, and never masks View bounds. Root/LSPosed View extraction can be
 * added later as a separate enhanced path without affecting this stable normal-mode pipeline.</p>
 */
final class CircleStableOcr {
    static void recognizeFullScreenSelected(Context context, Bitmap bitmap,
                                            OcrEngine.DocumentCallback callback) {
        if (!valid(context, bitmap, callback)) return;
        Context app = context.getApplicationContext();
        int mode = new FloatSettings(app).circleFullOcrEngine();
        if (mode == 0) {
            recognizeFullScreenMlKit(app, bitmap, callback);
            return;
        }
        int model = modelForCircleMode(mode);
        if (!OcrModelManager.isReady(app, model)) {
            DiagnosticLog.i(app, "G_CIRCLE_FULL_ENGINE",
                    "selected=" + modeLabel(mode) + " modelMissing=true fallback=mlkit");
            recognizeFullScreenMlKit(app, bitmap, callback);
            return;
        }
        recognizePaddle(app, bitmap, model, "full_screen_index", callback);
    }

    static void recognizeCorrectionSelected(Context context, Bitmap bitmap,
                                            OcrEngine.DocumentCallback callback) {
        if (!valid(context, bitmap, callback)) return;
        Context app = context.getApplicationContext();
        int mode = new FloatSettings(app).circleCorrectionEngine();
        if (mode == 0) {
            callback.onFailure(new IllegalStateException("Circle correction disabled"));
            return;
        }
        int model = modelForCircleMode(mode);
        if (!OcrModelManager.isReady(app, model)) {
            callback.onFailure(new IllegalStateException(
                    OcrModelManager.displayName(model) + " not downloaded"));
            return;
        }
        recognizePaddle(app, bitmap, model, "gesture_correction", callback);
    }

    /** Normal mode: complete screenshot -> ML Kit only. No Accessibility/View text path. */
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
                        + " role=full_screen_index"
                        + " mode=normal_ocr_only"
                        + " viewText=false");
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
                                            + " mode=normal_ocr_only"
                                            + " viewText=false"
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
                                        + " mode=normal_ocr_only"
                                        + " viewText=false"
                                        + " elapsedMs="
                                        + (android.os.SystemClock.uptimeMillis() - started));
                        callback.onFailure(error);
                    });
        } catch (Throwable t) {
            try { recognizer.close(); } catch (Throwable ignored) {}
            callback.onFailure(t);
        }
    }

    private static void recognizePaddle(Context app, Bitmap bitmap, int model, String role,
                                        OcrEngine.DocumentCallback callback) {
        long started = android.os.SystemClock.uptimeMillis();
        DiagnosticLog.i(app, role.equals("gesture_correction")
                        ? "G_CIRCLE_PP_REGION" : "G_CIRCLE_PP_INDEX",
                "start model=" + model
                        + " modelName=" + OcrModelManager.displayName(model)
                        + " bitmap=" + bitmap.getWidth() + "x" + bitmap.getHeight()
                        + " role=" + role
                        + " postLogic=ml_semantics_no_ml_runtime");
        PaddleOcrBridge.recognize(app, bitmap, model, new PaddleOcrBridge.Callback() {
            @Override public void onSuccess(OcrDocument raw, long totalMs, int lineCount) {
                if (raw == null || raw.fullText().isBlank() || raw.chars().isEmpty()) {
                    callback.onFailure(new IllegalStateException("PP-OCR empty"));
                    return;
                }
                // PaddleOcrBridge already normalizes PP output through OcrCanonicalGeometry.
                DiagnosticLog.i(app, role.equals("gesture_correction")
                                ? "G_CIRCLE_PP_REGION" : "G_CIRCLE_PP_INDEX",
                        "success model=" + model
                                + " engine=" + raw.engine()
                                + " chars=" + raw.chars().size()
                                + " lines=" + lineCount
                                + " elapsedMs="
                                + (android.os.SystemClock.uptimeMillis() - started)
                                + " semantics=ml_line_element_symbol");
                callback.onSuccess(raw);
            }

            @Override public void onFailure(String message) {
                callback.onFailure(new IllegalStateException(
                        message == null || message.isBlank() ? "PP-OCR failed" : message));
            }
        });
    }

    private static int modelForCircleMode(int mode) {
        return switch (mode) {
            case 1 -> OcrModelManager.TINY;
            case 2 -> OcrModelManager.SMALL;
            case 3 -> OcrModelManager.MEDIUM;
            default -> throw new IllegalArgumentException("not a PP Circle mode=" + mode);
        };
    }

    static String fullModeLabel(Context context) {
        return modeLabel(new FloatSettings(context.getApplicationContext()).circleFullOcrEngine());
    }

    static String correctionModeLabel(Context context) {
        int mode = new FloatSettings(context.getApplicationContext()).circleCorrectionEngine();
        return mode == 0 ? "off" : modeLabel(mode);
    }

    private static String modeLabel(int mode) {
        return switch (mode) {
            case 1 -> "ppocr_tiny";
            case 2 -> "ppocr_small";
            case 3 -> "ppocr_medium";
            default -> "mlkit";
        };
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
