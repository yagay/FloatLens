package com.yagay.floatlens;

import android.graphics.PointF;
import android.graphics.Rect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Immutable bitmap-space text-layout map built from detection boxes only.
 *
 * <p>No recognized text lives here. Detector boxes are normalized into visual lines and then
 * stitched into paragraph-like reading-flow groups using geometry (row alignment, line gap,
 * indentation and column separation). Paragraphs are structural hints only: gesture targeting
 * returns the touched line(s) plus a small amount of locally continuous reading context instead of
 * blindly returning an entire paragraph. Only that local context ROI is sent to OCR.</p>
 */
final class CircleTextMap {
    static final class Target {
        final Rect bounds;
        final List<Integer> paragraphIds;
        final int lineCount;

        Target(Rect bounds, List<Integer> paragraphIds, int lineCount) {
            this.bounds = bounds == null ? new Rect() : new Rect(bounds);
            this.paragraphIds = paragraphIds == null ? List.of() : List.copyOf(paragraphIds);
            this.lineCount = lineCount;
        }

        String idsForLog() {
            return paragraphIds.toString();
        }
    }

    private static final class Paragraph {
        final int id;
        final ArrayList<Rect> lines = new ArrayList<>();
        final Rect bounds = new Rect();

        Paragraph(int id, Rect first) {
            this.id = id;
            add(first);
        }

        void add(Rect line) {
            Rect copy = new Rect(line);
            lines.add(copy);
            if (bounds.isEmpty()) bounds.set(copy); else bounds.union(copy);
        }

        Rect lastLine() {
            return lines.get(lines.size() - 1);
        }
    }

    private static final class ParagraphHit {
        final Paragraph paragraph;
        final ArrayList<Integer> lineIndexes = new ArrayList<>();

        ParagraphHit(Paragraph paragraph) {
            this.paragraph = paragraph;
        }

        void add(int index) {
            if (!lineIndexes.contains(index)) lineIndexes.add(index);
        }
    }

    private static final int LOCAL_CONTEXT_NEIGHBORS = 2;

    private final int width;
    private final int height;
    private final List<Paragraph> paragraphs;
    private final int lineCount;

    private CircleTextMap(int width, int height, List<Paragraph> paragraphs, int lineCount) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.paragraphs = List.copyOf(paragraphs);
        this.lineCount = lineCount;
    }

    static CircleTextMap build(List<Rect> detected, int width, int height) {
        if (width <= 0 || height <= 0) return new CircleTextMap(1, 1, List.of(), 0);
        ArrayList<Rect> cleaned = cleanBoxes(detected, width, height);
        ArrayList<Rect> lines = mergeIntoLines(cleaned, width);
        ArrayList<Paragraph> paragraphs = stitchParagraphs(lines, width);
        return new CircleTextMap(width, height, paragraphs, lines.size());
    }

    int paragraphCount() {
        return paragraphs.size();
    }

    int lineCount() {
        return lineCount;
    }

    boolean isEmpty() {
        return paragraphs.isEmpty();
    }

    Target targetFor(GoogleCircleSelection.Selection gesture, int hitPaddingPx) {
        if (gesture == null || paragraphs.isEmpty()) return null;
        int pad = Math.max(1, hitPaddingPx);
        if (gesture.kind == GoogleCircleSelection.Kind.TAP) {
            return tapTarget(gesture.focus, pad);
        }
        return strokeTarget(gesture, pad);
    }

    private Target tapTarget(PointF point, int pad) {
        if (point == null) return null;
        Paragraph bestParagraph = null;
        int bestLine = -1;
        long bestDistance = Long.MAX_VALUE;
        for (Paragraph paragraph : paragraphs) {
            for (int i = 0; i < paragraph.lines.size(); i++) {
                Rect line = paragraph.lines.get(i);
                long distance = distanceSquared(point.x, point.y, line);
                if (distance <= (long) pad * pad && distance < bestDistance) {
                    bestParagraph = paragraph;
                    bestLine = i;
                    bestDistance = distance;
                }
            }
        }
        if (bestParagraph == null || bestLine < 0) return null;
        ParagraphHit hit = new ParagraphHit(bestParagraph);
        hit.add(bestLine);
        return toLocalTarget(List.of(hit));
    }

    private Target strokeTarget(GoogleCircleSelection.Selection gesture, int pad) {
        ArrayList<ParagraphHit> hits = new ArrayList<>();
        Rect gestureRect = GoogleCircleSelection.exactRectAndClamp(gesture.bounds, width, height);
        List<PointF> points = gesture.points;

        for (Paragraph paragraph : paragraphs) {
            ParagraphHit paragraphHit = null;
            for (int lineIndex = 0; lineIndex < paragraph.lines.size(); lineIndex++) {
                Rect line = paragraph.lines.get(lineIndex);
                Rect expanded = expanded(line, pad, width, height);
                boolean touched = false;
                if (points != null && !points.isEmpty()) {
                    for (PointF point : points) {
                        if (point != null && expanded.contains(Math.round(point.x), Math.round(point.y))) {
                            touched = true;
                            break;
                        }
                    }
                    if (!touched && points.size() > 1) {
                        for (int i = 1; i < points.size(); i++) {
                            PointF a = points.get(i - 1);
                            PointF b = points.get(i);
                            if (a != null && b != null && segmentEnvelopeIntersects(a, b, expanded, pad)) {
                                touched = true;
                                break;
                            }
                        }
                    }
                }
                if (!touched && (points == null || points.isEmpty())
                        && !gestureRect.isEmpty() && Rect.intersects(expanded, gestureRect)) {
                    float overlap = overlapCoverage(gestureRect, expanded);
                    float reverse = overlapCoverage(expanded, gestureRect);
                    touched = Math.max(overlap, reverse) >= 0.08f;
                }
                if (touched) {
                    if (paragraphHit == null) paragraphHit = new ParagraphHit(paragraph);
                    paragraphHit.add(lineIndex);
                }
            }
            if (paragraphHit != null && !paragraphHit.lineIndexes.isEmpty()) hits.add(paragraphHit);
        }

        if (hits.isEmpty()) return null;
        hits.sort(Comparator
                .comparingInt((ParagraphHit hit) -> hit.paragraph.bounds.top)
                .thenComparingInt(hit -> hit.paragraph.bounds.left));
        return toLocalTarget(hits);
    }

    /**
     * Build OCR context around actual touched line(s). Paragraph membership is only a continuity
     * hint. Up to two compatible neighbour lines on either side are retained so selection handles
     * have useful nearby context without turning a small gesture into a near-full-screen OCR crop.
     */
    private Target toLocalTarget(List<ParagraphHit> hits) {
        Rect bounds = new Rect();
        ArrayList<Integer> ids = new ArrayList<>();
        int selectedLineCount = 0;

        for (ParagraphHit hit : hits) {
            Paragraph paragraph = hit.paragraph;
            if (paragraph == null || paragraph.lines.isEmpty() || hit.lineIndexes.isEmpty()) continue;
            hit.lineIndexes.sort(Integer::compareTo);
            boolean[] include = new boolean[paragraph.lines.size()];
            int first = paragraph.lines.size();
            int last = -1;
            for (int index : hit.lineIndexes) {
                if (index < 0 || index >= paragraph.lines.size()) continue;
                include[index] = true;
                first = Math.min(first, index);
                last = Math.max(last, index);
            }
            if (last < 0) continue;

            int cursor = first;
            for (int n = 0; n < LOCAL_CONTEXT_NEIGHBORS && cursor > 0; n++) {
                int candidate = cursor - 1;
                if (!localFlowCompatible(paragraph.lines.get(candidate),
                        paragraph.lines.get(cursor), width)) break;
                include[candidate] = true;
                cursor = candidate;
            }

            cursor = last;
            for (int n = 0; n < LOCAL_CONTEXT_NEIGHBORS
                    && cursor + 1 < paragraph.lines.size(); n++) {
                int candidate = cursor + 1;
                if (!localFlowCompatible(paragraph.lines.get(cursor),
                        paragraph.lines.get(candidate), width)) break;
                include[candidate] = true;
                cursor = candidate;
            }

            boolean paragraphUsed = false;
            for (int i = 0; i < include.length; i++) {
                if (!include[i]) continue;
                Rect line = paragraph.lines.get(i);
                if (bounds.isEmpty()) bounds.set(line); else bounds.union(line);
                selectedLineCount++;
                paragraphUsed = true;
            }
            if (paragraphUsed && !ids.contains(paragraph.id)) ids.add(paragraph.id);
        }

        return bounds.isEmpty() ? null : new Target(bounds, ids, selectedLineCount);
    }

    private static boolean localFlowCompatible(Rect upper, Rect lower, int screenWidth) {
        if (upper == null || lower == null || upper.isEmpty() || lower.isEmpty()) return false;
        int maxHeight = Math.max(upper.height(), lower.height());
        int minHeight = Math.max(1, Math.min(upper.height(), lower.height()));
        float heightRatio = maxHeight / (float) minHeight;
        if (heightRatio > 1.80f) return false;

        int gap = Math.max(0, lower.top - upper.bottom);
        if (gap > Math.max(10, Math.round(maxHeight * 1.15f))) return false;

        float xOverlap = horizontalOverlapCoverage(upper, lower);
        int leftDelta = Math.abs(upper.left - lower.left);
        int alignLimit = Math.max(Math.round(maxHeight * 1.35f),
                Math.round(screenWidth * 0.030f));
        if (xOverlap < 0.24f && leftDelta > alignLimit) return false;

        int wide = Math.max(upper.width(), lower.width());
        int narrow = Math.max(1, Math.min(upper.width(), lower.width()));
        if (wide > narrow * 3 && xOverlap < 0.55f) return false;

        if (xOverlap <= 0f) {
            int centerDelta = Math.abs(upper.centerX() - lower.centerX());
            if (centerDelta > Math.max(maxHeight * 4, Math.round(screenWidth * 0.16f))) {
                return false;
            }
        }
        return true;
    }

    private static ArrayList<Rect> cleanBoxes(List<Rect> source, int width, int height) {
        ArrayList<Rect> out = new ArrayList<>();
        if (source == null) return out;
        for (Rect raw : source) {
            if (raw == null) continue;
            Rect box = clamp(raw, width, height);
            if (box.width() < 2 || box.height() < 2) continue;
            boolean duplicate = false;
            for (int i = out.size() - 1; i >= 0; i--) {
                Rect existing = out.get(i);
                float mutual = Math.min(overlapCoverage(box, existing), overlapCoverage(existing, box));
                if (mutual >= 0.82f) {
                    duplicate = true;
                    if (area(box) < area(existing)) out.set(i, box);
                    break;
                }
            }
            if (!duplicate) out.add(box);
        }
        out.sort(Comparator
                .comparingInt((Rect r) -> r.centerY())
                .thenComparingInt(r -> r.left));
        return out;
    }

    /** Merge nearby DBNet fragments that visually belong to one horizontal text line. */
    private static ArrayList<Rect> mergeIntoLines(List<Rect> boxes, int screenWidth) {
        ArrayList<Rect> lines = new ArrayList<>();
        for (Rect box : boxes) {
            int bestIndex = -1;
            int bestGap = Integer.MAX_VALUE;
            for (int i = lines.size() - 1; i >= 0; i--) {
                Rect line = lines.get(i);
                if (line.top > box.bottom || box.top > line.bottom) {
                    int verticalDistance = Math.max(line.top - box.bottom, box.top - line.bottom);
                    if (verticalDistance > Math.max(line.height(), box.height())) continue;
                }
                float vertical = verticalOverlapCoverage(line, box);
                if (vertical < 0.58f) continue;
                int gap = horizontalGap(line, box);
                int maxGap = Math.max(8, Math.round(Math.max(line.height(), box.height()) * 2.2f));
                maxGap = Math.min(maxGap, Math.max(12, Math.round(screenWidth * 0.055f)));
                if (gap > maxGap) continue;
                if (gap < bestGap) {
                    bestGap = gap;
                    bestIndex = i;
                }
            }
            if (bestIndex >= 0) {
                Rect merged = new Rect(lines.get(bestIndex));
                merged.union(box);
                lines.set(bestIndex, merged);
            } else {
                lines.add(new Rect(box));
            }
        }
        lines.sort(Comparator
                .comparingInt((Rect r) -> r.top)
                .thenComparingInt(r -> r.left));
        return lines;
    }

    /** Geometry-only paragraph stitching; no OCR text or semantic labels are required. */
    private static ArrayList<Paragraph> stitchParagraphs(List<Rect> lines, int screenWidth) {
        ArrayList<Paragraph> paragraphs = new ArrayList<>();
        int nextId = 0;
        for (Rect line : lines) {
            Paragraph best = null;
            float bestScore = Float.MAX_VALUE;
            for (Paragraph paragraph : paragraphs) {
                Rect previous = paragraph.lastLine();
                if (line.top + Math.max(2, line.height() / 3) < previous.top) continue;
                int gap = Math.max(0, line.top - previous.bottom);
                int referenceHeight = Math.max(previous.height(), line.height());
                int maxGap = Math.max(10, Math.round(referenceHeight * 1.45f));
                if (gap > maxGap) continue;

                float xOverlap = horizontalOverlapCoverage(previous, line);
                int leftDelta = Math.abs(previous.left - line.left);
                int alignLimit = Math.max(Math.round(referenceHeight * 1.8f),
                        Math.round(screenWidth * 0.035f));
                boolean aligned = xOverlap >= 0.20f || leftDelta <= alignLimit;
                if (!aligned) continue;

                if (xOverlap <= 0f) {
                    int centerDelta = Math.abs(previous.centerX() - line.centerX());
                    if (centerDelta > Math.max(referenceHeight * 5, Math.round(screenWidth * 0.22f))) {
                        continue;
                    }
                }

                float score = gap + leftDelta * 0.20f - xOverlap * referenceHeight;
                if (score < bestScore) {
                    bestScore = score;
                    best = paragraph;
                }
            }
            if (best == null) paragraphs.add(new Paragraph(nextId++, line));
            else best.add(line);
        }
        paragraphs.sort(Comparator
                .comparingInt((Paragraph p) -> p.bounds.top)
                .thenComparingInt(p -> p.bounds.left));
        return paragraphs;
    }

    private static boolean segmentEnvelopeIntersects(PointF a, PointF b, Rect target, int pad) {
        int left = Math.round(Math.min(a.x, b.x)) - pad;
        int top = Math.round(Math.min(a.y, b.y)) - pad;
        int right = Math.round(Math.max(a.x, b.x)) + pad + 1;
        int bottom = Math.round(Math.max(a.y, b.y)) + pad + 1;
        return Rect.intersects(new Rect(left, top, right, bottom), target);
    }

    private static long distanceSquared(float x, float y, Rect rect) {
        float dx = x < rect.left ? rect.left - x : x > rect.right ? x - rect.right : 0f;
        float dy = y < rect.top ? rect.top - y : y > rect.bottom ? y - rect.bottom : 0f;
        long ix = Math.round(dx);
        long iy = Math.round(dy);
        return ix * ix + iy * iy;
    }

    private static int horizontalGap(Rect a, Rect b) {
        if (a.right < b.left) return b.left - a.right;
        if (b.right < a.left) return a.left - b.right;
        return 0;
    }

    private static float verticalOverlapCoverage(Rect a, Rect b) {
        int overlap = Math.max(0, Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top));
        int base = Math.max(1, Math.min(a.height(), b.height()));
        return overlap / (float) base;
    }

    private static float horizontalOverlapCoverage(Rect a, Rect b) {
        int overlap = Math.max(0, Math.min(a.right, b.right) - Math.max(a.left, b.left));
        int base = Math.max(1, Math.min(a.width(), b.width()));
        return overlap / (float) base;
    }

    private static float overlapCoverage(Rect target, Rect cover) {
        if (target == null || cover == null || target.isEmpty() || cover.isEmpty()) return 0f;
        int left = Math.max(target.left, cover.left);
        int top = Math.max(target.top, cover.top);
        int right = Math.min(target.right, cover.right);
        int bottom = Math.min(target.bottom, cover.bottom);
        if (right <= left || bottom <= top) return 0f;
        long intersection = (long) (right - left) * (bottom - top);
        long targetArea = area(target);
        return targetArea <= 0L ? 0f : Math.min(1f, intersection / (float) targetArea);
    }

    private static Rect expanded(Rect source, int pad, int width, int height) {
        return clamp(new Rect(source.left - pad, source.top - pad,
                source.right + pad, source.bottom + pad), width, height);
    }

    private static Rect clamp(Rect source, int width, int height) {
        if (source == null || width <= 0 || height <= 0) return new Rect();
        int left = Math.max(0, Math.min(width - 1, source.left));
        int top = Math.max(0, Math.min(height - 1, source.top));
        int right = Math.max(left + 1, Math.min(width, source.right));
        int bottom = Math.max(top + 1, Math.min(height, source.bottom));
        return new Rect(left, top, right, bottom);
    }

    private static long area(Rect rect) {
        return rect == null ? 0L : (long) Math.max(0, rect.width()) * Math.max(0, rect.height());
    }
}
