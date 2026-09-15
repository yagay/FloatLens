package com.yagay.floatlens;

import android.graphics.Rect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Unified Circle text index.
 *
 * Native View text remains authoritative for the normal full-frame merge, while an explicit user
 * ROI refinement is authoritative inside that requested region. This prevents a stale/approximate
 * View rectangle from immediately suppressing the ML Kit patch that the user asked for.
 */
final class CircleTextIndex {
    private OcrDocument viewDocument;
    private OcrDocument ocrSupplement;
    private final ArrayList<Rect> roiOverrides = new ArrayList<>();
    private int lastGeometryRefinedChars;
    private int lastApproximateViewChars;

    CircleTextIndex(OcrDocument viewDocument, int screenWidth, int screenHeight) {
        this.viewDocument = requireScreenOrEmpty(viewDocument, "view-snapshot", screenWidth, screenHeight);
        this.ocrSupplement = empty("ocr-supplement", screenWidth, screenHeight);
    }

    OcrDocument viewDocument() { return viewDocument; }
    OcrDocument ocrSupplement() { return ocrSupplement; }
    int lastGeometryRefinedChars() { return lastGeometryRefinedChars; }
    int lastApproximateViewChars() { return lastApproximateViewChars; }

    void setFastOcr(OcrDocument document) {
        ocrSupplement = requireScreenOrEmpty(document, "ocr-supplement",
                viewDocument.imageWidth(), viewDocument.imageHeight());
        ViewTextGeometryRefiner.Result refined = ViewTextGeometryRefiner.refine(viewDocument, ocrSupplement);
        if (refined != null && refined.document() != null) viewDocument = refined.document();
        lastGeometryRefinedChars = refined == null ? 0 : refined.refinedChars();
        lastApproximateViewChars = refined == null ? 0 : refined.approximateChars();
    }

    void replaceOcrRegion(OcrDocument patch, Rect screenRegion) {
        if (patch == null || !patch.isScreenSpace()
                || screenRegion == null || screenRegion.isEmpty()) return;
        Rect override = new Rect(screenRegion);
        ArrayList<OcrDocument.Line> lines = new ArrayList<>();
        if (ocrSupplement != null) {
            for (OcrDocument.Line line : ocrSupplement.lines()) {
                if (line == null || line.bounds().isEmpty()) continue;
                if (!Rect.intersects(line.bounds(), override)) lines.add(line);
            }
        }
        lines.addAll(patch.lines());
        ocrSupplement = documentFromLines(lines, patch.engine() + "+roi",
                Math.max(ocrSupplement == null ? 0f : ocrSupplement.confidence(), patch.confidence()),
                viewDocument.imageWidth(), viewDocument.imageHeight());
        addOverride(override);
    }

    OcrDocument current() {
        return mergeViewFirst(viewDocument, ocrSupplement, roiOverrides);
    }

    static OcrDocument mergeViewFirst(OcrDocument view, OcrDocument ocr) {
        return mergeViewFirst(view, ocr, List.of());
    }

    private static OcrDocument mergeViewFirst(OcrDocument view, OcrDocument ocr,
                                              List<Rect> roiOverrides) {
        if (view == null || view.lines().isEmpty()) return ocr;
        if (ocr == null || ocr.lines().isEmpty()) return view;
        if (!view.isScreenSpace() || !ocr.isScreenSpace()) {
            throw new IllegalArgumentException("CircleTextIndex requires screen-space documents");
        }

        ArrayList<OcrDocument.Line> keptView = new ArrayList<>();
        for (OcrDocument.Line viewLine : view.lines()) {
            if (viewLine == null || viewLine.bounds().isEmpty()) continue;
            if (!insideOverride(viewLine.bounds(), roiOverrides)) keptView.add(viewLine);
        }

        ArrayList<OcrDocument.Line> lines = new ArrayList<>(keptView);
        int keptOcr = 0;
        for (OcrDocument.Line ocrLine : ocr.lines()) {
            if (ocrLine == null || ocrLine.bounds().isEmpty()) continue;
            boolean override = insideOverride(ocrLine.bounds(), roiOverrides);
            if (!override && coveredByView(keptView, ocrLine)) continue;
            lines.add(ocrLine);
            keptOcr++;
        }
        return documentFromLines(lines,
                keptOcr == 0 ? "view-snapshot" : "view-snapshot+mlkit",
                1f, view.imageWidth(), view.imageHeight());
    }

    private void addOverride(Rect region) {
        for (int i = roiOverrides.size() - 1; i >= 0; i--) {
            Rect old = roiOverrides.get(i);
            if (Rect.intersects(old, region) || old.contains(region) || region.contains(old)) {
                region.union(old);
                roiOverrides.remove(i);
            }
        }
        roiOverrides.add(new Rect(region));
    }

    private static boolean insideOverride(Rect line, List<Rect> regions) {
        if (line == null || line.isEmpty() || regions == null || regions.isEmpty()) return false;
        long lineArea = Math.max(1L, (long) line.width() * line.height());
        for (Rect region : regions) {
            if (region == null || region.isEmpty()) continue;
            if (region.contains(line.centerX(), line.centerY())) return true;
            Rect overlap = new Rect();
            if (!overlap.setIntersect(region, line)) continue;
            long overlapArea = Math.max(0L, (long) overlap.width() * overlap.height());
            if (overlapArea >= lineArea * 28L / 100L) return true;
        }
        return false;
    }

    private static boolean coveredByView(List<OcrDocument.Line> viewLines, OcrDocument.Line ocrLine) {
        Rect ocrBounds = ocrLine.bounds();
        long ocrArea = Math.max(1L, (long) ocrBounds.width() * ocrBounds.height());
        for (OcrDocument.Line viewLine : viewLines) {
            if (viewLine == null || viewLine.bounds().isEmpty()) continue;
            Rect viewBounds = viewLine.bounds();
            if (viewBounds.contains(ocrBounds.centerX(), ocrBounds.centerY())) return true;
            Rect overlap = new Rect();
            if (!overlap.setIntersect(viewBounds, ocrBounds)) continue;
            long overlapArea = Math.max(0L, (long) overlap.width() * overlap.height());
            if (overlapArea / (float) ocrArea >= 0.36f) return true;
        }
        return false;
    }

    private static OcrDocument requireScreenOrEmpty(OcrDocument document, String engine,
                                                    int width, int height) {
        if (document == null) return empty(engine, width, height);
        if (!document.isScreenSpace()) {
            throw new IllegalArgumentException(engine + " must be normalized to screen coordinates");
        }
        return document;
    }

    private static OcrDocument documentFromLines(List<OcrDocument.Line> source, String engine,
                                                  float confidence, int width, int height) {
        ArrayList<OcrDocument.Line> sorted = new ArrayList<>();
        if (source != null) {
            for (OcrDocument.Line line : source) {
                if (line != null && !line.text().isBlank() && !line.bounds().isEmpty()) sorted.add(line);
            }
        }
        sorted.sort(Comparator.comparingInt((OcrDocument.Line l) -> l.bounds().centerY())
                .thenComparingInt(l -> l.bounds().left));
        ArrayList<String> blocks = new ArrayList<>();
        StringBuilder full = new StringBuilder();
        for (OcrDocument.Line line : sorted) {
            String text = line.text().trim();
            if (text.isEmpty()) continue;
            if (full.length() > 0) full.append('\n');
            full.append(text);
            blocks.add(text);
        }
        double score = sorted.size() * 5d;
        for (OcrDocument.Line line : sorted) score += line.chars().size();
        return OcrDocument.screenSpace(full.toString(), blocks, sorted, engine,
                confidence, score, Math.max(1, width), Math.max(1, height));
    }

    private static OcrDocument empty(String engine, int width, int height) {
        return OcrDocument.screenSpace("", List.of(), List.of(), engine, 0f, 0d,
                Math.max(1, width), Math.max(1, height));
    }
}
