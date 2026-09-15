package com.yagay.floatlens;

import android.graphics.Rect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Engine-neutral OCR/text geometry shared by FloatLens surfaces.
 *
 * Recognition engines produce BITMAP-space documents. Higher-level coordinators may normalize a
 * document once into absolute SCREEN space before mixing it with Accessibility/View geometry.
 */
public final class OcrDocument {
    public enum CoordinateSpace { BITMAP, SCREEN }

    public static final class CharUnit {
        private final String text;
        private final Rect bounds;
        private final float confidence;
        private final int line;
        private final int group;
        private final int order;

        public CharUnit(String text, Rect bounds, float confidence, int line, int group, int order) {
            this.text = text == null ? "" : text;
            this.bounds = bounds == null ? new Rect() : new Rect(bounds);
            this.confidence = confidence;
            this.line = line;
            this.group = group;
            this.order = order;
        }

        public String text() { return text; }
        public Rect bounds() { return new Rect(bounds); }
        public float confidence() { return confidence; }
        public int line() { return line; }
        public int group() { return group; }
        public int order() { return order; }

        CharUnit translated(int dx, int dy, int newLine, int newOrder) {
            Rect r = new Rect(bounds);
            r.offset(dx, dy);
            return new CharUnit(text, r, confidence, newLine, group, newOrder);
        }
    }

    public static final class Line {
        private final String text;
        private final Rect bounds;
        private final float confidence;
        private final List<CharUnit> chars;

        public Line(String text, Rect bounds, float confidence, List<CharUnit> chars) {
            this.text = text == null ? "" : text;
            this.bounds = bounds == null ? new Rect() : new Rect(bounds);
            this.confidence = confidence;
            this.chars = chars == null ? List.of() : List.copyOf(chars);
        }

        public String text() { return text; }
        public Rect bounds() { return new Rect(bounds); }
        public float confidence() { return confidence; }
        public List<CharUnit> chars() { return chars; }
    }

    private final String fullText;
    private final List<String> blocks;
    private final List<Line> lines;
    private final String engine;
    private final float confidence;
    private final double score;
    private final int imageWidth;
    private final int imageHeight;
    private final CoordinateSpace coordinateSpace;

    public OcrDocument(String fullText, List<String> blocks, List<Line> lines,
                       String engine, float confidence, double score,
                       int imageWidth, int imageHeight) {
        this(fullText, blocks, lines, engine, confidence, score,
                imageWidth, imageHeight, CoordinateSpace.BITMAP);
    }

    public OcrDocument(String fullText, List<String> blocks, List<Line> lines,
                       String engine, float confidence, double score,
                       int imageWidth, int imageHeight, CoordinateSpace coordinateSpace) {
        this.fullText = fullText == null ? "" : fullText.trim();
        this.blocks = blocks == null ? List.of() : List.copyOf(blocks);
        this.lines = lines == null ? List.of() : List.copyOf(lines);
        this.engine = engine == null ? "unknown" : engine;
        this.confidence = confidence;
        this.score = score;
        this.imageWidth = Math.max(1, imageWidth);
        this.imageHeight = Math.max(1, imageHeight);
        this.coordinateSpace = coordinateSpace == null ? CoordinateSpace.BITMAP : coordinateSpace;
    }

    public static OcrDocument screenSpace(String fullText, List<String> blocks, List<Line> lines,
                                          String engine, float confidence, double score,
                                          int screenWidth, int screenHeight) {
        return new OcrDocument(fullText, blocks, lines, engine, confidence, score,
                screenWidth, screenHeight, CoordinateSpace.SCREEN);
    }

    public String fullText() { return fullText; }
    public List<String> blocks() { return blocks; }
    public List<Line> lines() { return lines; }
    public String engine() { return engine; }
    public float confidence() { return confidence; }
    public double score() { return score; }
    public int imageWidth() { return imageWidth; }
    public int imageHeight() { return imageHeight; }
    public CoordinateSpace coordinateSpace() { return coordinateSpace; }
    public boolean isBitmapSpace() { return coordinateSpace == CoordinateSpace.BITMAP; }
    public boolean isScreenSpace() { return coordinateSpace == CoordinateSpace.SCREEN; }
    public boolean isEmpty() { return fullText.isBlank() && chars().isEmpty(); }

    public List<CharUnit> chars() {
        if (lines.isEmpty()) return List.of();
        ArrayList<CharUnit> out = new ArrayList<>();
        for (Line line : lines) out.addAll(line.chars());
        return Collections.unmodifiableList(out);
    }

    /** Translate within the current coordinate space, preserving its coordinate-space identity. */
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
            outLines.add(new Line(line.text(), lineBounds, line.confidence(), outChars));
        }
        return new OcrDocument(fullText, blocks, outLines, engine, confidence, score,
                parentWidth, parentHeight, coordinateSpace);
    }
}
