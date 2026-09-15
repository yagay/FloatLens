package com.yagay.floatlens;

import android.graphics.Rect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** View text is authoritative; OCR is retained only where View text exposes no coverage. */
final class CircleTextIndex {
    private final OcrDocument viewDocument;
    private OcrDocument ocrSupplement;

    CircleTextIndex(OcrDocument viewDocument, int width, int height) {
        this.viewDocument = viewDocument == null
                ? empty("view-snapshot", width, height) : viewDocument;
        this.ocrSupplement = empty("ocr-supplement", width, height);
    }

    OcrDocument viewDocument() { return viewDocument; }
    OcrDocument ocrSupplement() { return ocrSupplement; }

    void setFastOcr(OcrDocument document) {
        ocrSupplement = document == null
                ? empty("ocr-supplement", viewDocument.imageWidth(), viewDocument.imageHeight())
                : document;
    }

    void replaceOcrRegion(OcrDocument patch, Rect region) {
        if (patch == null || region == null || region.isEmpty()) return;
        ArrayList<OcrDocument.Line> lines = new ArrayList<>();
        if (ocrSupplement != null) {
            for (OcrDocument.Line line : ocrSupplement.lines()) {
                if (line == null || line.bounds().isEmpty()) continue;
                if (!Rect.intersects(line.bounds(), region)) lines.add(line);
            }
        }
        lines.addAll(patch.lines());
        ocrSupplement = documentFromLines(lines, patch.engine() + "+roi",
                Math.max(ocrSupplement == null ? 0f : ocrSupplement.confidence(), patch.confidence()),
                patch.imageWidth(), patch.imageHeight());
    }

    OcrDocument current() {
        return mergeViewFirst(viewDocument, ocrSupplement);
    }

    static OcrDocument mergeViewFirst(OcrDocument view, OcrDocument ocr) {
        if (view == null || view.lines().isEmpty()) return ocr;
        if (ocr == null || ocr.lines().isEmpty()) return view;

        ArrayList<OcrDocument.Line> lines = new ArrayList<>(view.lines());
        int keptOcr = 0;
        for (OcrDocument.Line ocrLine : ocr.lines()) {
            if (ocrLine == null || ocrLine.bounds().isEmpty()) continue;
            if (coveredByView(view.lines(), ocrLine)) continue;
            lines.add(ocrLine);
            keptOcr++;
        }
        return documentFromLines(lines,
                keptOcr == 0 ? "view-snapshot" : "view-snapshot+ocr-blindspots",
                1f, view.imageWidth(), view.imageHeight());
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
            float ocrCoverage = overlapArea / (float) ocrArea;
            if (ocrCoverage >= 0.36f) return true;
        }
        return false;
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
        return new OcrDocument(full.toString(), blocks, sorted, engine,
                confidence, score, Math.max(1, width), Math.max(1, height));
    }

    private static OcrDocument empty(String engine, int width, int height) {
        return new OcrDocument("", List.of(), List.of(), engine, 0f, 0d,
                Math.max(1, width), Math.max(1, height));
    }
}
