package com.yagay.floatlens;

import android.graphics.Rect;

import java.util.ArrayList;
import java.util.List;

/**
 * OCR passes for one frozen Circle frame plus one AKS-style global merged document.
 *
 * <p>The raw Full/TL/TR/BL/BR documents stay available for diagnostics, but text selection uses
 * the merged document. Merge happens only at complete ML Kit Element/group granularity: exact-text
 * spatial duplicates are removed, conflicting groups are kept, and characters are never fused
 * across OCR sources.</p>
 */
final class CircleOcrIndex {
    static final class Entry {
        final String source;
        final boolean fullFrame;
        final Rect coverage;
        final OcrDocument document;

        Entry(String source, boolean fullFrame, Rect coverage, OcrDocument document) {
            this.source = source == null ? "unknown" : source;
            this.fullFrame = fullFrame;
            this.coverage = coverage == null ? new Rect() : new Rect(coverage);
            this.document = document;
        }
    }

    private final List<Entry> entries;
    private final OcrDocument mergedDocument;

    CircleOcrIndex(List<Entry> value) {
        ArrayList<Entry> safe = new ArrayList<>();
        if (value != null) {
            for (Entry entry : value) {
                if (entry == null || !usable(entry.document)) continue;
                safe.add(new Entry(entry.source, entry.fullFrame, entry.coverage, entry.document));
            }
        }
        entries = List.copyOf(safe);
        mergedDocument = CircleAksOcrMerger.merge(entries);
    }

    List<Entry> entries() { return entries; }
    boolean isEmpty() { return entries.isEmpty(); }
    int passCount() { return entries.size(); }
    OcrDocument mergedDocument() { return usable(mergedDocument) ? mergedDocument : fullFrameDocument(); }

    int totalChars() {
        int count = 0;
        for (Entry entry : entries) count += entry.document.chars().size();
        return count;
    }

    int mergedChars() {
        OcrDocument document = mergedDocument();
        return document == null ? 0 : document.chars().size();
    }

    OcrDocument fullFrameDocument() {
        for (Entry entry : entries) {
            if (entry.fullFrame) return entry.document;
        }
        return entries.isEmpty() ? null : entries.get(0).document;
    }

    /** Convert every raw pass once into absolute SCREEN space, then rebuild the merged document. */
    CircleOcrIndex toScreen(ScreenBitmapTransform transform) {
        if (transform == null || entries.isEmpty()) return new CircleOcrIndex(List.of());
        ArrayList<Entry> mapped = new ArrayList<>();
        for (Entry entry : entries) {
            OcrDocument document = transform.documentBitmapToScreen(entry.document);
            if (!usableScreen(document)) continue;
            Rect coverage = transform.bitmapToScreen(entry.coverage);
            mapped.add(new Entry(entry.source, entry.fullFrame, coverage, document));
        }
        return new CircleOcrIndex(mapped);
    }

    private static boolean usable(OcrDocument document) {
        return document != null && !document.lines().isEmpty() && !document.chars().isEmpty();
    }

    private static boolean usableScreen(OcrDocument document) {
        return usable(document) && document.isScreenSpace();
    }
}
