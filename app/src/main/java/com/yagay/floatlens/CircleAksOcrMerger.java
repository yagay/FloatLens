package com.yagay.floatlens;

import android.graphics.Rect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AKS-style global merge for Circle OCR passes.
 *
 * <p>Each ML Kit Element/group is treated as AKS treats a Tesseract word. Passes are considered in
 * reverse source order (later regional passes before earlier/full), exact text plus heavy spatial
 * overlap removes duplicates, conflicting text is deliberately kept, and accepted groups are then
 * rebuilt into visual rows. Characters are never mixed between OCR groups.</p>
 */
final class CircleAksOcrMerger {
    private static final float DUPLICATE_OVERLAP = 0.70f;
    private static final float SAME_ROW_CENTER_FACTOR = 0.60f;

    private static final class WordCandidate {
        final String source;
        final String text;
        final Rect bounds;
        final List<OcrDocument.CharUnit> chars;
        final float confidence;
        final int priority;

        WordCandidate(String source, String text, Rect bounds,
                      List<OcrDocument.CharUnit> chars, float confidence, int priority) {
            this.source = source == null ? "unknown" : source;
            this.text = text == null ? "" : text;
            this.bounds = bounds == null ? new Rect() : new Rect(bounds);
            this.chars = chars == null ? List.of() : List.copyOf(chars);
            this.confidence = confidence;
            this.priority = priority;
        }
    }

    static OcrDocument merge(List<CircleOcrIndex.Entry> entries) {
        if (entries == null || entries.isEmpty()) return null;

        ArrayList<WordCandidate> all = new ArrayList<>();
        OcrDocument template = null;
        for (int sourceIndex = 0; sourceIndex < entries.size(); sourceIndex++) {
            CircleOcrIndex.Entry entry = entries.get(sourceIndex);
            if (entry == null || entry.document == null || entry.document.chars().isEmpty()) continue;
            if (template == null) template = entry.document;
            collectGroups(all, entry, sourceIndex);
        }
        if (template == null || all.isEmpty()) return null;

        // AKS gives later pass indices priority (BR > BL > TR > TL > Full).
        all.sort((a, b) -> Integer.compare(b.priority, a.priority));
        ArrayList<WordCandidate> accepted = new ArrayList<>();
        for (WordCandidate candidate : all) {
            if (candidate.text.isBlank() || candidate.bounds.isEmpty()) continue;
            boolean duplicate = false;
            for (WordCandidate existing : accepted) {
                if (!sameText(existing.text, candidate.text)) continue;
                if (duplicateOverlap(candidate.bounds, existing.bounds) > DUPLICATE_OVERLAP) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) accepted.add(candidate);
        }
        if (accepted.isEmpty()) return null;

        // AKS first sorts accepted words visually, then groups consecutive words into lines using
        // center-Y distance relative to the previous word's height.
        accepted.sort(Comparator
                .comparingInt((WordCandidate w) -> w.bounds.top)
                .thenComparingInt(w -> w.bounds.left));

        ArrayList<ArrayList<WordCandidate>> rows = new ArrayList<>();
        ArrayList<WordCandidate> current = null;
        WordCandidate previous = null;
        for (WordCandidate candidate : accepted) {
            boolean sameRow = previous != null
                    && Math.abs(candidate.bounds.centerY() - previous.bounds.centerY())
                    < Math.max(1f, previous.bounds.height() * SAME_ROW_CENTER_FACTOR);
            if (current == null || !sameRow) {
                current = new ArrayList<>();
                rows.add(current);
            }
            current.add(candidate);
            previous = candidate;
        }

        ArrayList<OcrDocument.Line> lines = new ArrayList<>();
        ArrayList<String> blocks = new ArrayList<>();
        StringBuilder full = new StringBuilder();
        int lineId = 0;
        int groupId = 0;
        int order = 0;
        float confidenceSum = 0f;
        int confidenceCount = 0;

        for (ArrayList<WordCandidate> row : rows) {
            if (row.isEmpty()) continue;
            row.sort(Comparator.comparingInt(w -> w.bounds.left));
            ArrayList<OcrDocument.CharUnit> chars = new ArrayList<>();
            Rect lineBounds = null;
            StringBuilder lineText = new StringBuilder();
            WordCandidate previousWord = null;
            float lineConfidenceSum = 0f;
            int lineConfidenceCount = 0;

            for (WordCandidate word : row) {
                if (word.text.isBlank() || word.bounds.isEmpty() || word.chars.isEmpty()) continue;
                if (previousWord != null && shouldInsertSpace(previousWord.text, word.text)) {
                    lineText.append(' ');
                }
                lineText.append(word.text);

                int outputGroup = groupId++;
                for (OcrDocument.CharUnit source : word.chars) {
                    if (source == null || source.text().isBlank() || source.bounds().isEmpty()) continue;
                    Rect bounds = source.bounds();
                    chars.add(new OcrDocument.CharUnit(source.text(), bounds, source.confidence(),
                            lineId, outputGroup, order++));
                    if (lineBounds == null) lineBounds = new Rect(bounds); else lineBounds.union(bounds);
                    confidenceSum += source.confidence();
                    confidenceCount++;
                    lineConfidenceSum += source.confidence();
                    lineConfidenceCount++;
                }
                previousWord = word;
            }

            String text = lineText.toString().trim();
            if (chars.isEmpty() || lineBounds == null || lineBounds.isEmpty() || text.isEmpty()) continue;
            float lineConfidence = lineConfidenceCount == 0 ? 0f
                    : lineConfidenceSum / lineConfidenceCount;
            lines.add(new OcrDocument.Line(text, lineBounds, lineConfidence, chars));
            blocks.add(text);
            if (full.length() > 0) full.append('\n');
            full.append(text);
            lineId++;
        }

        if (lines.isEmpty()) return null;
        float confidence = confidenceCount == 0 ? 0f : confidenceSum / confidenceCount;
        double score = lines.size() * 5d;
        for (OcrDocument.Line line : lines) score += line.chars().size();
        String engine = "aks-merged-mlkit";
        if (template.isScreenSpace()) {
            return OcrDocument.screenSpace(full.toString(), blocks, lines, engine,
                    confidence, score, template.imageWidth(), template.imageHeight());
        }
        return new OcrDocument(full.toString(), blocks, lines, engine,
                confidence, score, template.imageWidth(), template.imageHeight());
    }

    private static void collectGroups(List<WordCandidate> out, CircleOcrIndex.Entry entry,
                                      int priority) {
        for (OcrDocument.Line line : entry.document.lines()) {
            if (line == null || line.chars().isEmpty()) continue;
            Map<Integer, ArrayList<OcrDocument.CharUnit>> groups = new LinkedHashMap<>();
            for (OcrDocument.CharUnit unit : line.chars()) {
                if (unit == null || unit.text().isBlank() || unit.bounds().isEmpty()) continue;
                groups.computeIfAbsent(unit.group(), ignored -> new ArrayList<>()).add(unit);
            }
            for (ArrayList<OcrDocument.CharUnit> chars : groups.values()) {
                if (chars.isEmpty()) continue;
                chars.sort(Comparator.comparingInt(c -> c.bounds().left));
                StringBuilder text = new StringBuilder();
                Rect bounds = null;
                float confidence = 0f;
                for (OcrDocument.CharUnit unit : chars) {
                    text.append(unit.text());
                    Rect r = unit.bounds();
                    if (bounds == null) bounds = new Rect(r); else bounds.union(r);
                    confidence += unit.confidence();
                }
                if (bounds == null || bounds.isEmpty() || text.length() == 0) continue;
                out.add(new WordCandidate(entry.source, text.toString(), bounds, chars,
                        confidence / chars.size(), priority));
            }
        }
    }

    private static float duplicateOverlap(Rect candidate, Rect existing) {
        Rect intersection = new Rect();
        if (!intersection.setIntersect(candidate, existing)) return 0f;
        long candidateArea = Math.max(1L, (long) candidate.width() * candidate.height());
        long overlap = Math.max(0L, (long) intersection.width() * intersection.height());
        return overlap / (float) candidateArea;
    }

    private static boolean sameText(String a, String b) {
        if (a == null || b == null) return false;
        return a.trim().toLowerCase(Locale.ROOT).equals(b.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean shouldInsertSpace(String left, String right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) return false;
        int a = left.codePointBefore(left.length());
        int b = right.codePointAt(0);
        return !(isCjk(a) && isCjk(b));
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x3400 && cp <= 0x4DBF) || (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0x20000 && cp <= 0x2FA1F);
    }

    private CircleAksOcrMerger() {}
}
