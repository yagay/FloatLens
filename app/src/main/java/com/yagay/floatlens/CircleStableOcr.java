package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognizer;

/**
 * Circle OCR adapter with independently selectable text engines.
 *
 * <p>ML Kit is the canonical geometry/line/group owner for the full-screen index. When a PP model
 * is selected for full-screen text, PP still supplies the recognized text while a single ML pass
 * supplies the visual structure used by Circle selection.</p>
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
        recognizePaddleFullWithMlGeometry(app, bitmap, model, callback);
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

    private static void recognizePaddleFullWithMlGeometry(Context app,
                                                           Bitmap bitmap,
                                                           int model,
                                                           OcrEngine.DocumentCallback callback) {
        long started = android.os.SystemClock.uptimeMillis();
        recognizePaddle(app, bitmap, model, "full_screen_index", new OcrEngine.DocumentCallback() {
            @Override public void onSuccess(OcrDocument ppDocument) {
                recognizeFullScreenMlKit(app, bitmap, new OcrEngine.DocumentCallback() {
                    @Override public void onSuccess(OcrDocument mlDocument) {
                        OcrDocument canonical = CircleMlGeometryCanonicalizer.canonicalize(
                                app, ppDocument, mlDocument);
                        DiagnosticLog.i(app, "G_CIRCLE_FULL_CANONICAL",
                                "textEngine=" + ppDocument.engine()
                                        + " geometryEngine=" + mlDocument.engine()
                                        + " finalEngine=" + canonical.engine()
                                        + " chars=" + canonical.chars().size()
                                        + " lines=" + canonical.lines().size()
                                        + " elapsedMs="
                                        + (android.os.SystemClock.uptimeMillis() - started)
                                        + " selectionLogic=mlkit");
                        callback.onSuccess(canonical);
                    }

                    @Override public void onFailure(Throwable error) {
                        DiagnosticLog.i(app, "G_CIRCLE_FULL_CANONICAL",
                                "mlGeometryFailed=true fallback=pp"
                                        + " textEngine=" + ppDocument.engine()
                                        + " error=" + safe(error));
                        callback.onSuccess(ppDocument);
                    }
                });
            }

            @Override public void onFailure(Throwable error) {
                callback.onFailure(error);
            }
        });
    }

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

    private static void recognizePaddle(Context app, Bitmap bitmap, int model, String role,
                                        OcrEngine.DocumentCallback callback) {
        long started = android.os.SystemClock.uptimeMillis();
        DiagnosticLog.i(app, role.equals("gesture_correction")
                        ? "G_CIRCLE_PP_REGION" : "G_CIRCLE_PP_INDEX",
                "start model=" + model
                        + " modelName=" + OcrModelManager.displayName(model)
                        + " bitmap=" + bitmap.getWidth() + "x" + bitmap.getHeight()
                        + " role=" + role);
        PaddleOcrBridge.recognize(app, bitmap, model, new PaddleOcrBridge.Callback() {
            @Override public void onSuccess(OcrDocument raw, long totalMs, int lineCount) {
                if (raw == null || raw.fullText().isBlank() || raw.chars().isEmpty()) {
                    callback.onFailure(new IllegalStateException("PP-OCR empty"));
                    return;
                }
                DiagnosticLog.i(app, role.equals("gesture_correction")
                                ? "G_CIRCLE_PP_REGION" : "G_CIRCLE_PP_INDEX",
                        "success model=" + model
                                + " engine=" + raw.engine()
                                + " chars=" + raw.chars().size()
                                + " lines=" + lineCount
                                + " elapsedMs="
                                + (android.os.SystemClock.uptimeMillis() - started));
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

    /** Normal/legacy OCR path retained for non-Circle callers. */
    static void recognizeConfigured(Context context, Bitmap bitmap,
                                    OcrEngine.DocumentCallback callback) {
        if (!valid(context, bitmap, callback)) return;
        OcrEngine.recognizeDocument(context.getApplicationContext(), bitmap, callback);
    }

    static void recognizePaddleRegion(Context context, Bitmap bitmap,
                                      OcrEngine.DocumentCallback callback) {
        recognizeCorrectionSelected(context, bitmap, callback);
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
