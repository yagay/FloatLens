package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Coordinate-preserving OCR used by Circle Select.
 *
 * This deliberately runs on the original frozen screenshot. The normal OcrEngine may preprocess,
 * resize and compare multiple passes for accuracy; that is excellent for final recognition but its
 * coordinates no longer map 1:1 to the screen. Circle Select needs stable hit boxes, so this layer
 * performs one original-image pass and keeps every ML Kit element bounding box.
 */
public final class SpatialOcrEngine {
    public interface Callback {
        void onSuccess(List<Word> words);
        void onFailure(Throwable error);
    }

    public static final class Word {
        private final String text;
        private final Rect bounds;
        private final int line;
        private final int order;

        Word(String text, Rect bounds, int line, int order) {
            this.text = text == null ? "" : text.trim();
            this.bounds = bounds == null ? new Rect() : new Rect(bounds);
            this.line = line;
            this.order = order;
        }

        public String text() { return text; }
        public Rect bounds() { return new Rect(bounds); }
        public int line() { return line; }
        public int order() { return order; }
    }

    public static void recognize(Context c, Bitmap source, Callback callback) {
        Context app = c.getApplicationContext();
        if (callback == null) return;
        if (source == null || source.isRecycled() || source.getWidth() <= 0 || source.getHeight() <= 0) {
            callback.onFailure(new IllegalArgumentException("invalid bitmap"));
            return;
        }

        Set<String> languages = OcrLanguages.get(app);
        boolean chinese = OcrLanguages.chineseEnabled(languages);
        boolean english = OcrLanguages.englishEnabled(languages);
        if (!chinese && !english) chinese = true;

        TextRecognizer recognizer;
        try {
            recognizer = chinese
                    ? TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build())
                    : TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        } catch (Throwable t) {
            callback.onFailure(t);
            return;
        }

        DiagnosticLog.i(app, "SPATIAL_OCR", "start image=" + source.getWidth() + "x" + source.getHeight()
                + " engine=" + (chinese ? "mlkit_zh" : "mlkit_latin"));
        TextRecognizer finalRecognizer = recognizer;
        recognizer.process(InputImage.fromBitmap(source, 0))
                .addOnSuccessListener(text -> {
                    try {
                        ArrayList<Word> out = parse(text);
                        DiagnosticLog.i(app, "SPATIAL_OCR", "ready words=" + out.size());
                        callback.onSuccess(Collections.unmodifiableList(out));
                    } catch (Throwable t) {
                        callback.onFailure(t);
                    } finally {
                        try { finalRecognizer.close(); } catch (Throwable ignored) {}
                    }
                })
                .addOnFailureListener(error -> {
                    try { finalRecognizer.close(); } catch (Throwable ignored) {}
                    DiagnosticLog.i(app, "SPATIAL_OCR", "failed=" + safe(error));
                    callback.onFailure(error);
                });
    }

    private static ArrayList<Word> parse(Text text) {
        ArrayList<Word> out = new ArrayList<>();
        if (text == null) return out;
        int lineIndex = 0;
        int order = 0;
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                int before = out.size();
                for (Text.Element element : line.getElements()) {
                    String value = element.getText() == null ? "" : element.getText().trim();
                    Rect box = element.getBoundingBox();
                    if (!value.isEmpty() && box != null && !box.isEmpty()) {
                        out.add(new Word(value, box, lineIndex, order++));
                    }
                }
                // Some scripts/devices expose a useful line box but no elements. Keep the line as
                // one selectable unit instead of silently making that text impossible to touch.
                if (out.size() == before) {
                    String value = line.getText() == null ? "" : line.getText().trim();
                    Rect box = line.getBoundingBox();
                    if (!value.isEmpty() && box != null && !box.isEmpty()) {
                        out.add(new Word(value, box, lineIndex, order++));
                    }
                }
                lineIndex++;
            }
        }
        return out;
    }

    private static String safe(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }

    private SpatialOcrEngine() {}
}
