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
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Coordinate-preserving OCR used by Circle Select.
 *
 * This deliberately runs on the original frozen screenshot. The normal OcrEngine may preprocess,
 * resize and compare multiple passes for accuracy; that is excellent for final recognition but its
 * coordinates no longer map 1:1 to the screen. Circle Select needs stable hit boxes, so this layer
 * performs one original-image pass and keeps every ML Kit element bounding box.
 *
 * ML Kit does not promise that TextBlock iteration order is the same as visual reading order across
 * independent blocks. Circle Select represents a selection as one contiguous start/end index, so
 * every returned word must first be normalized into deterministic screen reading order. Otherwise a
 * handle dragged to the next visible row can still point at a non-contiguous index and appear stuck
 * on one row.
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
        ArrayList<LineCandidate> lines = new ArrayList<>();
        if (text == null) return new ArrayList<>();

        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect lineBox = line.getBoundingBox();
                ArrayList<ElementCandidate> elements = new ArrayList<>();
                Rect union = null;

                for (Text.Element element : line.getElements()) {
                    String value = element.getText() == null ? "" : element.getText().trim();
                    Rect box = element.getBoundingBox();
                    if (value.isEmpty() || box == null || box.isEmpty()) continue;
                    elements.add(new ElementCandidate(value, box));
                    if (union == null) union = new Rect(box);
                    else union.union(box);
                }

                // Some scripts/devices expose useful line text but no elements. Keep the complete
                // line as one selectable unit instead of making that row impossible to select.
                if (elements.isEmpty()) {
                    String value = line.getText() == null ? "" : line.getText().trim();
                    Rect box = lineBox;
                    if (!value.isEmpty() && box != null && !box.isEmpty()) {
                        elements.add(new ElementCandidate(value, box));
                        union = new Rect(box);
                    }
                }

                if (elements.isEmpty()) continue;
                elements.sort(Comparator
                        .comparingInt((ElementCandidate e) -> e.bounds.left)
                        .thenComparingInt(e -> e.bounds.top)
                        .thenComparingInt(e -> e.bounds.right));

                Rect stableLineBox = lineBox != null && !lineBox.isEmpty()
                        ? new Rect(lineBox)
                        : union == null ? new Rect() : new Rect(union);
                if (stableLineBox.isEmpty() && union != null) stableLineBox.set(union);
                lines.add(new LineCandidate(stableLineBox, elements));
            }
        }

        // Sort rows geometrically rather than trusting TextBlock iteration order. Use vertical centre
        // first so slightly different ascender/descender boxes from the same visual row do not swap.
        lines.sort((a, b) -> {
            int ah = Math.max(1, a.bounds.height());
            int bh = Math.max(1, b.bounds.height());
            int tolerance = Math.max(3, Math.min(ah, bh) / 2);
            int dy = a.bounds.centerY() - b.bounds.centerY();
            if (Math.abs(dy) > tolerance) return Integer.compare(a.bounds.centerY(), b.bounds.centerY());
            int top = Integer.compare(a.bounds.top, b.bounds.top);
            if (Math.abs(a.bounds.top - b.bounds.top) > tolerance) return top;
            return Integer.compare(a.bounds.left, b.bounds.left);
        });

        ArrayList<Word> out = new ArrayList<>();
        int order = 0;
        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            LineCandidate line = lines.get(lineIndex);
            for (ElementCandidate element : line.elements) {
                out.add(new Word(element.text, element.bounds, lineIndex, order++));
            }
        }
        return out;
    }

    private static final class LineCandidate {
        final Rect bounds;
        final List<ElementCandidate> elements;
        LineCandidate(Rect bounds, List<ElementCandidate> elements) {
            this.bounds = bounds == null ? new Rect() : new Rect(bounds);
            this.elements = elements;
        }
    }

    private static final class ElementCandidate {
        final String text;
        final Rect bounds;
        ElementCandidate(String text, Rect bounds) {
            this.text = text == null ? "" : text.trim();
            this.bounds = bounds == null ? new Rect() : new Rect(bounds);
        }
    }

    private static String safe(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }

    private SpatialOcrEngine() {}
}
