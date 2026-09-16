package com.yagay.floatlens;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.Set;

/**
 * Stable complete-document OCR adapter used by Circle.
 *
 * <p>When ML Kit is requested explicitly, exactly one recognizer processes the whole input and its
 * document geometry is preserved intact. Chinese/Latin recognizer outputs are never fused at the
 * character level. The generic recognize() entry still respects the app's main OCR setting for
 * callers outside the AKS-style Circle pre-index.</p>
 */
final class CircleStableOcr {
    private static final class ChineseHolder {
        static final TextRecognizer INSTANCE = TextRecognition.getClient(
                new ChineseTextRecognizerOptions.Builder().build());
    }

    private static final class LatinHolder {
        static final TextRecognizer INSTANCE = TextRecognition.getClient(
                TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    static void recognize(Context context, Bitmap bitmap, OcrEngine.DocumentCallback callback) {
        if (context == null || bitmap == null || bitmap.isRecycled() || callback == null) return;
        Context app = context.getApplicationContext();
        int mode = readMode(app);
        boolean smallReady = OcrModelManager.isReady(app, OcrModelManager.SMALL);
        boolean mediumReady = OcrModelManager.isReady(app, OcrModelManager.MEDIUM);

        boolean directMlKit = mode == 3 || (mode == 0 && !smallReady && !mediumReady);
        DiagnosticLog.i(app, "G_CIRCLE_STABLE_OCR",
                "start mode=" + mode
                        + " directMlKit=" + directMlKit
                        + " small=" + smallReady
                        + " medium=" + mediumReady
                        + " policy=single_complete_document");

        if (directMlKit) {
            recognizeMlKit(app, bitmap, callback);
            return;
        }

        OcrEngine.recognizeDocument(app, bitmap, new OcrEngine.DocumentCallback() {
            @Override public void onSuccess(OcrDocument document) {
                if (document != null && document.engine() != null
                        && document.engine().startsWith("mlkit-fused")) {
                    DiagnosticLog.i(app, "G_CIRCLE_STABLE_OCR",
                            "replace fused ML Kit fallback with single complete document");
                    recognizeMlKit(app, bitmap, callback);
                } else {
                    callback.onSuccess(document);
                }
            }

            @Override public void onFailure(Throwable error) {
                callback.onFailure(error);
            }
        });
    }

    /** Circle-only ML Kit path: one complete recognizer document, never Chinese/Latin char fusion. */
    static void recognizeMlKit(Context context, Bitmap bitmap, OcrEngine.DocumentCallback callback) {
        if (context == null || bitmap == null || bitmap.isRecycled() || callback == null) return;
        Context app = context.getApplicationContext();
        boolean chinese = preferredChinese(app);
        TextRecognizer recognizer = chinese ? ChineseHolder.INSTANCE : LatinHolder.INSTANCE;
        String engine = chinese ? "mlkit-circle-zh" : "mlkit-circle-latin";
        long started = android.os.SystemClock.uptimeMillis();
        DiagnosticLog.i(app, "G_CIRCLE_STABLE_OCR",
                "mlkit_only=true recognizer=" + (chinese ? "chinese" : "latin")
                        + " policy=single_complete_document");
        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener(text -> {
                        try {
                            OcrDocument document = MlKitTextCore.toDocument(text, engine,
                                    bitmap.getWidth(), bitmap.getHeight(), 0f, null);
                            if (document == null || document.fullText().isBlank()
                                    || document.chars().isEmpty()) {
                                callback.onFailure(new IllegalStateException("Circle ML Kit empty"));
                                return;
                            }
                            DiagnosticLog.i(app, "G_CIRCLE_STABLE_OCR",
                                    "success engine=" + engine
                                            + " chars=" + document.chars().size()
                                            + " lines=" + document.lines().size()
                                            + " elapsedMs="
                                            + (android.os.SystemClock.uptimeMillis() - started));
                            callback.onSuccess(document);
                        } catch (Throwable t) {
                            callback.onFailure(t);
                        }
                    })
                    .addOnFailureListener(callback::onFailure);
        } catch (Throwable t) {
            callback.onFailure(t);
        }
    }

    private static boolean preferredChinese(Context app) {
        Set<String> languages = OcrLanguages.get(app);
        boolean chinese = OcrLanguages.chineseEnabled(languages);
        boolean english = OcrLanguages.englishEnabled(languages);
        if (!chinese && !english) return true;
        return chinese;
    }

    private static int readMode(Context app) {
        try {
            SharedPreferences p = app.getSharedPreferences(FloatSettings.PREF, Context.MODE_PRIVATE);
            Object raw = p.getAll().get(FloatSettings.K_OCR_ENGINE);
            if (raw instanceof Number n) return Math.max(0, Math.min(3, n.intValue()));
            if (raw instanceof String s) {
                try { return Math.max(0, Math.min(3, Integer.parseInt(s.trim()))); }
                catch (Throwable ignored) { return 0; }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private CircleStableOcr() {}
}
