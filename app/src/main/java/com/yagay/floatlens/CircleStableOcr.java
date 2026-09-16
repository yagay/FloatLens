package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.Set;

/**
 * Lazy ROI recognizer used by the Circle TextMap workflow.
 *
 * <p>Circle no longer follows the app's full-screen OCR engine setting. The background phase only
 * detects text geometry; when a gesture hits a TextMap paragraph (or detector fallback ROI), exactly
 * one ML Kit recognizer decodes that small screenshot crop and preserves its document geometry.</p>
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

    /** Circle-only ML Kit path: one complete ROI document, never Chinese/Latin character fusion. */
    static void recognizeMlKit(Context context, Bitmap bitmap, OcrEngine.DocumentCallback callback) {
        if (context == null || bitmap == null || bitmap.isRecycled() || callback == null) return;
        Context app = context.getApplicationContext();
        boolean chinese = preferredChinese(app);
        TextRecognizer recognizer = chinese ? ChineseHolder.INSTANCE : LatinHolder.INSTANCE;
        String engine = chinese ? "mlkit-circle-zh" : "mlkit-circle-latin";
        long started = android.os.SystemClock.uptimeMillis();
        DiagnosticLog.i(app, "G_CIRCLE_LAZY_RECOGNIZER",
                "start recognizer=" + (chinese ? "chinese" : "latin")
                        + " scope=roi policy=single_document");
        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener(text -> {
                        try {
                            OcrDocument document = MlKitTextCore.toDocument(text, engine,
                                    bitmap.getWidth(), bitmap.getHeight(), 0f, null);
                            if (document == null || document.fullText().isBlank()
                                    || document.chars().isEmpty()) {
                                callback.onFailure(new IllegalStateException("Circle lazy ML Kit empty"));
                                return;
                            }
                            DiagnosticLog.i(app, "G_CIRCLE_LAZY_RECOGNIZER",
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

    private CircleStableOcr() {}
}
