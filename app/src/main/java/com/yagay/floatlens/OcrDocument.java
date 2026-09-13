package com.yagay.floatlens;

import android.graphics.Rect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Engine-neutral OCR result shared by every FloatLens surface.
 *
 * Coordinates are always expressed in the source bitmap coordinate space. Consumers decide
 * whether they need full text, block text, line geometry or character geometry; recognition
 * engines never expose UI-specific result types.
 */
public final class OcrDocument {
    public enum Source { OCR, VIEW }

    public static final class CharUnit {
        private final String text;
        private final Rect bounds;
        private final float confidence;
        private final int line;
        private final int group;
        private final int order;
        private final Source source;

        public CharUnit(String text, Rect bounds, float confidence, int line, int group, int order) {
            this(text, bounds, confidence, line, group, order, Source.OCR);
        }

        public CharUnit(String text, Rect bounds, float confidence, int line, int group, int order,
                        Source source) {
            this.text = text == null ? "" : text;
            this.bounds = bounds == null ? new Rect() : new Rect(bounds);
            this.confidence = confidence;
            this.line = line;
            this.group = group;
            this.order = order;
            this.source = source == null ? Source.OCR : source;
        }

        public String text() { return text; }
        public Rect bounds() { return new Rect(bounds); }
        public float confidence() { return confidence; }
        public int line() { return line; }
        public int group() { return group; }
        public int order() { return order; }
        public Source source() { return source; }

        CharUnit translated(int dx, int dy, int newLine, int newOrder) {
            Rect r = new Rect(bounds);
            r.offset(dx, dy);
            return new CharUnit(text, r, confidence, newLine, group, newOrder, source);
        }
    }

    public static final class Line {
        private final String text;
        private final Rect bounds;
        private final float confidence;
        private final List<CharUnit> chars;
        private final Source source;

        public Line(String text, Rect bounds, float confidence, List<CharUnit> chars) {
            this(text, bounds, confidence, chars, Source.OCR);
        }

        public Line(String text, Rect bounds, float confidence, List<CharUnit> chars, Source source) {
            this.text = text == null ? "" : text;
            this.bounds = bounds == null ? new Rect() : new Rect(bounds);
            this.confidence = confidence;
            this.chars = chars == null ? List.of() : List.copyOf(chars);
            this.source = source == null ? Source.OCR : source;
        }

        public String text() { return text; }
        public Rect bounds() { return new Rect(bounds); }
        public float confidence() { return confidence; }
        public List<CharUnit> chars() { return chars; }
        public Source source() { return source; }
    }

    private final String fullText;
    private final List<String> blocks;
    private final List<Line> lines;
    private final String engine;
    private final float confidence;
    private final double score;
    private final int imageWidth;
    private final int imageHeight;

    public OcrDocument(String fullText, List<String> blocks, List<Line> lines,
                       String engine, float confidence, double score,
                       int imageWidth, int imageHeight) {
        this.fullText = fullText == null ? "" : fullText.trim();
        this.blocks = blocks == null ? List.of() : List.copyOf(blocks);
        this.lines = lines == null ? List.of() : List.copyOf(lines);
        this.engine = engine == null ? "unknown" : engine;
        this.confidence = confidence;
        this.score = score;
        this.imageWidth = Math.max(1, imageWidth);
        this.imageHeight = Math.max(1, imageHeight);
    }

    public String fullText() { return fullText; }
    public List<String> blocks() { return blocks; }
    public List<Line> lines() { return lines; }
    public String engine() { return engine; }
    public float confidence() { return confidence; }
    public double score() { return score; }
    public int imageWidth() { return imageWidth; }
    public int imageHeight() { return imageHeight; }
    public boolean isEmpty() { return fullText.isBlank() && chars().isEmpty(); }

    public List<CharUnit> chars() {
        if (lines.isEmpty()) return List.of();
        ArrayList<CharUnit> out = new ArrayList<>();
        for (Line line : lines) out.addAll(line.chars());
        return Collections.unmodifiableList(out);
    }

    /** Translate a crop-space result back into its parent screenshot coordinate space. */
    public OcrDocument translated(int dx, int dy, int parentWidth, int parentHeight) {
        ArrayList<Line> outLines = new ArrayList<>();
        int order = 0;
        for (int li = 0; li < lines.size(); li++) {
            Line line = lines.get(li);
            Rect lineBounds = line.bounds();
            lineBounds.offset(dx, dy);
            ArrayList<CharUnit> outChars = new ArrayList<>();
            for (CharUnit c : line.chars()) {
                outChars.add(c.translated(dx, dy, li, order++));
            }
            outLines.add(new Line(line.text(), lineBounds, line.confidence(), outChars, line.source()));
        }
        return new OcrDocument(fullText, blocks, outLines, engine, confidence, score,
                parentWidth, parentHeight);
    }
}
