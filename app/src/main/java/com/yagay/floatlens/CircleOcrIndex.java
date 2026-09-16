package com.yagay.floatlens;

import android.graphics.Rect;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable collection of independent OCR passes for one frozen Circle frame.
 *
 * <p>Passes are deliberately never merged globally. Each document keeps the recognizer's own
 * line/group/character structure; gesture-time selection chooses one complete pass near the user's
 * actual target. This prevents a bad tile or a conflicting recognition from corrupting another
 * pass, and no recognized text is used to vote across OCR sources.</p>
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

    CircleOcrIndex(List<Entry> value) {
        ArrayList<Entry> safe = new ArrayList<>();
        if (value != null) {
            for (Entry entry : value) {
                if (entry == null || !usable(entry.document)) continue;
                safe.add(new Entry(entry.source, entry.fullFrame, entry.coverage, entry.document));
            }
        }
        entries = List.copyOf(safe);
    }

    List<Entry> entries() { return entries; }
    boolean isEmpty() { return entries.isEmpty(); }
    int passCount() { return entries.size(); }

    int totalChars() {
        int count = 0;
        for (Entry entry : entries) count += entry.document.chars().size();
        return count;
    }

    OcrDocument fullFrameDocument() {
        for (Entry entry : entries) {
            if (entry.fullFrame) return entry.document;
        }
        return entries.isEmpty() ? null : entries.get(0).document;
    }

    /** Convert every pass exactly once from full-bitmap coordinates into absolute SCREEN space. */
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
