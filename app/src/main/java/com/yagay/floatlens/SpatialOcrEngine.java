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
 * This deliberately runs on the original frozen screenshot. Circle Select needs stable 1:1 hit
 * boxes, so this layer keeps ML Kit geometry and normalizes it into deterministic screen reading
 * order.
 *
 * Selection granularity is symbol-first: when ML Kit exposes per-symbol boxes, each character/
 * symbol becomes an independently selectable unit. If symbol geometry is unavailable, the parser
 * safely falls back to the element box, then finally to the line box.
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
        private final int group;
        private final int order;

        Word(String text, Rect bounds, int line, int group, int order) {
            this.text = text == null ? "" : text.trim();
            this.bounds = bounds == null ? new Rect() : new Rect(bounds);
            this.line = line;
            this.group = group;
            this.order = order;
        }

        public String text() { return text; }
        public Rect bounds() { return new Rect(bounds); }
        public int line() { return line; }
        public int group() { return group; }
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
                        int symbolUnits = 0;
                        for (Word w : out) if (w.text().codePointCount(0, w.text().length()) <= 1) symbolUnits++;
                        DiagnosticLog.i(app, "SPATIAL_OCR", "ready units=" + out.size()
                                + " symbolLike=" + symbolUnits);
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

        int nextGroup = 0;
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect lineBox = line.getBoundingBox();
                ArrayList<UnitCandidate> units = new ArrayList<>();
                Rect union = null;

                for (Text.Element element : line.getElements()) {
                    String elementText = element.getText() == null ? "" : element.getText().trim();
                    Rect elementBox = element.getBoundingBox();
                    if (elementText.isEmpty() || elementBox == null || elementBox.isEmpty()) continue;

                    int group = nextGroup++;
                    ArrayList<UnitCandidate> symbols = validSymbols(element, group);
                    if (!symbols.isEmpty()) {
                        for (UnitCandidate symbol : symbols) {
                            units.add(symbol);
                            if (union == null) union = new Rect(symbol.bounds);
                            else union.union(symbol.bounds);
                        }
                    } else {
                        units.add(new UnitCandidate(elementText, elementBox, group));
                        if (union == null) union = new Rect(elementBox);
                        else union.union(elementBox);
                    }
                }

                // Some scripts/devices expose useful line text but no elements. Keep the complete
                // line as one selectable unit rather than making the row impossible to select.
                if (units.isEmpty()) {
                    String value = line.getText() == null ? "" : line.getText().trim();
                    Rect box = lineBox;
                    if (!value.isEmpty() && box != null && !box.isEmpty()) {
                        units.add(new UnitCandidate(value, box, nextGroup++));
                        union = new Rect(box);
                    }
                }

                if (units.isEmpty()) continue;
                units.sort(Comparator
                        .comparingInt((UnitCandidate e) -> e.bounds.left)
                        .thenComparingInt(e -> e.bounds.top)
                        .thenComparingInt(e -> e.bounds.right));

                Rect stableLineBox = lineBox != null && !lineBox.isEmpty()
                        ? new Rect(lineBox)
                        : union == null ? new Rect() : new Rect(union);
                if (stableLineBox.isEmpty() && union != null) stableLineBox.set(union);
                lines.add(new LineCandidate(stableLineBox, units));
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
            for (UnitCandidate unit : line.units) {
                out.add(new Word(unit.text, unit.bounds, lineIndex, unit.group, order++));
            }
        }
        return out;
    }

    /**
     * Prefer ML Kit's Symbol geometry only when it is complete enough to preserve the element text.
     * Falling back to the element avoids silently dropping characters on recognizers that expose a
     * partial Symbol list.
     */
    private static ArrayList<UnitCandidate> validSymbols(Text.Element element, int group) {
        ArrayList<UnitCandidate> out = new ArrayList<>();
        List<Text.Symbol> symbols;
        try { symbols = element.getSymbols(); }
        catch (Throwable ignored) { return out; }
        if (symbols == null || symbols.isEmpty()) return out;

        StringBuilder combined = new StringBuilder();
        for (Text.Symbol symbol : symbols) {
            if (symbol == null) return new ArrayList<>();
            String value = symbol.getText() == null ? "" : symbol.getText().trim();
            Rect box = symbol.getBoundingBox();
            if (value.isEmpty() || box == null || box.isEmpty()) return new ArrayList<>();
            combined.append(value);
            out.add(new UnitCandidate(value, box, group));
        }

        String elementText = element.getText() == null ? "" : element.getText().replace(" ", "").trim();
        String symbolText = combined.toString().replace(" ", "").trim();
        if (out.isEmpty() || !elementText.equals(symbolText)) return new ArrayList<>();
        return out;
    }

    private static final class LineCandidate {
        final Rect bounds;
        final List<UnitCandidate> units;
        LineCandidate(Rect bounds, List<UnitCandidate> units) {
            this.bounds = bounds == null ? new Rect() : new Rect(bounds);
            this.units = units;
        }
    }

    private static final class UnitCandidate {
        final String text;
        final Rect bounds;
        final int group;
        UnitCandidate(String text, Rect bounds, int group) {
            this.text = text == null ? "" : text.trim();
            this.bounds = bounds == null ? new Rect() : new Rect(bounds);
            this.group = group;
        }
    }

    private static String safe(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }

    private SpatialOcrEngine() {}
}
