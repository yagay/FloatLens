package com.yagay.floatlens;

import android.graphics.Rect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Unified Circle text index.
 *
 * Native View text remains authoritative for the normal full-frame merge, while an explicit user
 * ROI refinement is authoritative inside that requested region. Rectangle precedence math is kept
 * in the pure-Java CircleRoiOverridePolicy so the behavior is regression-testable off-device.
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
        if (patch == null || !patch.isScreenSpace() || empty(screenRegion)) return;
        Rect override = copy(screenRegion);
        ArrayList<OcrDocument.Line> lines = new ArrayList<>();
        if (ocrSupplement != null) {
            for (OcrDocument.Line line : ocrSupplement.lines()) {
                Rect bounds = line == null ? null : line.bounds();
                if (empty(bounds)) continue;
                if (!intersects(bounds, override)) lines.add(line);
            }
        }
        lines.addAll(patch.lines());
        ocrSupplement = documentFromLines(lines, patch.engine() + "+roi",
                Math.max(ocrSupplement == null ? 0f : ocrSupplement.confidence(), patch.confidence()),
                viewDocument.imageWidth(), viewDocument.imageHeight());
        addOverride(override);
    }

    OcrDocument current() { return mergeViewFirst(viewDocument, ocrSupplement, roiOverrides); }

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
            Rect bounds = viewLine == null ? null : viewLine.bounds();
            if (empty(bounds)) continue;
            if (!insideOverride(bounds, roiOverrides)) keptView.add(viewLine);
        }

        ArrayList<OcrDocument.Line> lines = new ArrayList<>(keptView);
        int keptOcr = 0;
        for (OcrDocument.Line ocrLine : ocr.lines()) {
            Rect bounds = ocrLine == null ? null : ocrLine.bounds();
            if (empty(bounds)) continue;
            boolean override = insideOverride(bounds, roiOverrides);
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
            if (intersects(old, region)) {
                region.left = Math.min(region.left, old.left);
                region.top = Math.min(region.top, old.top);
                region.right = Math.max(region.right, old.right);
                region.bottom = Math.max(region.bottom, old.bottom);
                roiOverrides.remove(i);
            }
        }
        roiOverrides.add(copy(region));
    }

    private static boolean insideOverride(Rect line, List<Rect> regions) {
        if (empty(line) || regions == null || regions.isEmpty()) return false;
        for (Rect region : regions) {
            if (empty(region)) continue;
            if (CircleRoiOverridePolicy.materiallyCovered(
                    line.left, line.top, line.right, line.bottom,
                    region.left, region.top, region.right, region.bottom)) return true;
        }
        return false;
    }

    private static boolean coveredByView(List<OcrDocument.Line> viewLines, OcrDocument.Line ocrLine) {
        Rect ocrBounds = ocrLine == null ? null : ocrLine.bounds();
        if (empty(ocrBounds)) return false;
        long ocrArea = Math.max(1L,
                (long) (ocrBounds.right - ocrBounds.left) * (ocrBounds.bottom - ocrBounds.top));
        int centerX = ocrBounds.left + (ocrBounds.right - ocrBounds.left) / 2;
        int centerY = ocrBounds.top + (ocrBounds.bottom - ocrBounds.top) / 2;
        for (OcrDocument.Line viewLine : viewLines) {
            Rect viewBounds = viewLine == null ? null : viewLine.bounds();
            if (empty(viewBounds)) continue;
            if (containsPoint(viewBounds, centerX, centerY)) return true;
            long overlap = overlapArea(viewBounds, ocrBounds);
            if (overlap * 100L >= ocrArea * 36L) return true;
        }
        return false;
    }

    private static boolean intersects(Rect a, Rect b) {
        if (empty(a) || empty(b)) return false;
        return CircleRoiOverridePolicy.intersectsOrContains(
                a.left, a.top, a.right, a.bottom,
                b.left, b.top, b.right, b.bottom);
    }

    private static boolean containsPoint(Rect r, int x, int y) {
        return !empty(r) && x >= r.left && x < r.right && y >= r.top && y < r.bottom;
    }

    private static long overlapArea(Rect a, Rect b) {
        if (!intersects(a, b)) return 0L;
        int left = Math.max(a.left, b.left);
        int top = Math.max(a.top, b.top);
        int right = Math.min(a.right, b.right);
        int bottom = Math.min(a.bottom, b.bottom);
        return Math.max(0L, (long) (right - left) * (bottom - top));
    }

    private static boolean empty(Rect r) {
        return r == null || r.right <= r.left || r.bottom <= r.top;
    }

    private static Rect copy(Rect r) {
        return r == null ? new Rect() : new Rect(r.left, r.top, r.right, r.bottom);
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
                Rect bounds = line == null ? null : line.bounds();
                if (line != null && !line.text().isBlank() && !empty(bounds)) sorted.add(line);
            }
        }
        sorted.sort(Comparator.comparingInt((OcrDocument.Line l) -> centerY(l.bounds()))
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

    private static int centerY(Rect r) {
        return empty(r) ? 0 : r.top + (r.bottom - r.top) / 2;
    }

    private static OcrDocument empty(String engine, int width, int height) {
        return OcrDocument.screenSpace("", List.of(), List.of(), engine, 0f, 0d,
                Math.max(1, width), Math.max(1, height));
    }
}
