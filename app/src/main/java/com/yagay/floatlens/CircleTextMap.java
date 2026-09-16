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
 * indentation and column separation). Gestures hit this map first; only the resulting paragraph ROI
 * is sent to OCR.</p>
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
        Paragraph best = null;
        long bestDistance = Long.MAX_VALUE;
        for (Paragraph paragraph : paragraphs) {
            long paragraphDistance = Long.MAX_VALUE;
            for (Rect line : paragraph.lines) {
                long distance = distanceSquared(point.x, point.y, line);
                paragraphDistance = Math.min(paragraphDistance, distance);
            }
            if (paragraphDistance <= (long) pad * pad && paragraphDistance < bestDistance) {
                best = paragraph;
                bestDistance = paragraphDistance;
            }
        }
        return best == null ? null : toTarget(List.of(best));
    }

    private Target strokeTarget(GoogleCircleSelection.Selection gesture, int pad) {
        ArrayList<Paragraph> hit = new ArrayList<>();
        Rect gestureRect = GoogleCircleSelection.exactRectAndClamp(gesture.bounds, width, height);
        List<PointF> points = gesture.points;

        for (Paragraph paragraph : paragraphs) {
            boolean touched = false;
            for (Rect line : paragraph.lines) {
                Rect expanded = expanded(line, pad, width, height);
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
                if (!touched && !gestureRect.isEmpty() && Rect.intersects(expanded, gestureRect)) {
                    float overlap = overlapCoverage(gestureRect, expanded);
                    float reverse = overlapCoverage(expanded, gestureRect);
                    touched = Math.max(overlap, reverse) >= 0.08f;
                }
                if (touched) break;
            }
            if (touched) hit.add(paragraph);
        }

        if (hit.isEmpty()) return null;
        hit.sort(Comparator
                .comparingInt((Paragraph p) -> p.bounds.top)
                .thenComparingInt(p -> p.bounds.left));
        return toTarget(hit);
    }

    private static Target toTarget(List<Paragraph> hit) {
        Rect bounds = new Rect();
        ArrayList<Integer> ids = new ArrayList<>();
        int lines = 0;
        for (Paragraph paragraph : hit) {
            if (bounds.isEmpty()) bounds.set(paragraph.bounds); else bounds.union(paragraph.bounds);
            ids.add(paragraph.id);
            lines += paragraph.lines.size();
        }
        return bounds.isEmpty() ? null : new Target(bounds, ids, lines);
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

                // If two lines have no horizontal overlap, do not bridge distant columns merely
                // because their Y positions are close.
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
