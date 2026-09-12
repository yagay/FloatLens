package com.yagay.floatlens;

import android.graphics.Rect;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pure OcrDocument selection state used by CircleSelectOverlay. */
final class CircleTextSelectionModel {
    private final int imageWidth;
    private final int imageHeight;
    private List<OcrDocument.CharUnit> chars = List.of();
    private int startIndex = -1;
    private int endIndex = -1;
    /** Non-contiguous rectangle selection before a handle is dragged. */
    private List<Integer> explicitSelection = List.of();

    CircleTextSelectionModel(int imageWidth, int imageHeight) {
        this.imageWidth = Math.max(1, imageWidth);
        this.imageHeight = Math.max(1, imageHeight);
    }

    void setDocument(OcrDocument document) {
        chars = normalize(document == null ? List.of() : document.chars());
        clear();
    }

    void setChars(List<OcrDocument.CharUnit> value) {
        chars = normalize(value == null ? List.of() : value);
        clear();
    }

    List<OcrDocument.CharUnit> chars() { return chars; }
    int size() { return chars.size(); }
    boolean isEmpty() { return chars.isEmpty(); }
    int startIndex() { return startIndex; }
    int endIndex() { return endIndex; }

    void clear() {
        startIndex = endIndex = -1;
        explicitSelection = List.of();
    }

    void selectSingle(int index) {
        if (!validIndex(index)) return;
        explicitSelection = List.of();
        startIndex = endIndex = index;
    }

    void selectAll() {
        if (chars.isEmpty()) return;
        explicitSelection = List.of();
        startIndex = 0;
        endIndex = chars.size() - 1;
    }

    void updateStart(int index) {
        if (!validIndex(index)) return;
        collapseExplicitToRange();
        startIndex = index;
    }

    void updateEnd(int index) {
        if (!validIndex(index)) return;
        collapseExplicitToRange();
        endIndex = index;
    }

    boolean hasSelection() {
        return !selectionIndices().isEmpty();
    }

    int low() {
        List<Integer> indexes = selectionIndices();
        return indexes.isEmpty() ? -1 : indexes.get(0);
    }

    int high() {
        List<Integer> indexes = selectionIndices();
        return indexes.isEmpty() ? -1 : indexes.get(indexes.size() - 1);
    }

    List<Integer> selectionIndices() {
        if (!explicitSelection.isEmpty()) return explicitSelection;
        if (!validIndex(startIndex) || !validIndex(endIndex)) return List.of();
        int lo = Math.min(startIndex, endIndex);
        int hi = Math.max(startIndex, endIndex);
        ArrayList<Integer> out = new ArrayList<>(hi - lo + 1);
        for (int i = lo; i <= hi; i++) out.add(i);
        return out;
    }

    RectF wordViewRect(int index, int viewWidth, int viewHeight) {
        if (!validIndex(index)) return new RectF();
        return toViewRect(chars.get(index).bounds(), viewWidth, viewHeight);
    }

    int findWordAt(float viewX, float viewY, int viewWidth, int viewHeight) {
        if (chars.isEmpty() || viewWidth <= 0 || viewHeight <= 0) return -1;
        int bx = Math.round(viewX * imageWidth / (float) viewWidth);
        int by = Math.round(viewY * imageHeight / (float) viewHeight);
        int best = -1;
        long bestArea = Long.MAX_VALUE;
        for (int i = 0; i < chars.size(); i++) {
            Rect r = chars.get(i).bounds();
            if (!r.contains(bx, by)) continue;
            long area = Math.max(1L, (long) r.width() * r.height());
            if (area < bestArea) { bestArea = area; best = i; }
        }
        return best;
    }

    int findSelectionWord(float viewX, float viewY, int viewWidth, int viewHeight,
                          float maxDistancePx) {
        int exact = findWordAt(viewX, viewY, viewWidth, viewHeight);
        if (exact >= 0) return exact;
        if (chars.isEmpty() || viewWidth <= 0 || viewHeight <= 0) return -1;
        float bestScore = Float.MAX_VALUE;
        int best = -1;
        for (int i = 0; i < chars.size(); i++) {
            RectF r = wordViewRect(i, viewWidth, viewHeight);
            float dx = viewX < r.left ? r.left - viewX : viewX > r.right ? viewX - r.right : 0f;
            float dy = viewY < r.top ? r.top - viewY : viewY > r.bottom ? viewY - r.bottom : 0f;
            float score = dx * dx + dy * dy * 0.72f;
            if (score < bestScore) { bestScore = score; best = i; }
        }
        if (best < 0 || bestScore > maxDistancePx * maxDistancePx) return -1;
        return best;
    }

    /** Select only character boxes materially intersecting the rectangle. */
    boolean selectIntersecting(Rect imageRect) {
        if (imageRect == null || imageRect.isEmpty() || chars.isEmpty()) return false;
        ArrayList<Integer> hit = new ArrayList<>();
        for (int i = 0; i < chars.size(); i++) {
            Rect r = chars.get(i).bounds();
            Rect intersection = new Rect();
            boolean intersects = intersection.setIntersect(r, imageRect);
            if (!intersects) continue;
            long overlap = (long) intersection.width() * intersection.height();
            long area = Math.max(1L, (long) r.width() * r.height());
            boolean centerInside = imageRect.contains(r.centerX(), r.centerY());
            if (centerInside || overlap >= area * 0.28f) hit.add(i);
        }
        if (hit.isEmpty()) return false;
        explicitSelection = List.copyOf(hit);
        startIndex = hit.get(0);
        endIndex = hit.get(hit.size() - 1);
        return true;
    }

    /** Replace stale characters in a refined region, then restore deterministic reading order. */
    void mergeRefinement(OcrDocument translatedPatch, Rect imageRect) {
        if (translatedPatch == null || imageRect == null || imageRect.isEmpty()) return;
        ArrayList<OcrDocument.CharUnit> merged = new ArrayList<>();
        for (OcrDocument.CharUnit c : chars) {
            Rect r = c.bounds();
            if (!Rect.intersects(r, imageRect)) merged.add(c);
        }
        merged.addAll(translatedPatch.chars());
        chars = normalize(merged);
        clear();
    }

    String selectedText() {
        List<Integer> indexes = selectionIndices();
        if (indexes.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        int previousLine = -1;
        int previousGroup = -1;
        String previous = "";
        for (int index : indexes) {
            if (!validIndex(index)) continue;
            OcrDocument.CharUnit c = chars.get(index);
            String value = c.text();
            if (value.isBlank()) continue;
            if (out.length() > 0) {
                if (c.line() != previousLine) out.append('\n');
                else if (c.group() != previousGroup && !noSpaceBetween(previous, value)) out.append(' ');
            }
            out.append(value);
            previousLine = c.line();
            previousGroup = c.group();
            previous = value;
        }
        return out.toString().trim();
    }

    RectF selectionViewBounds(int viewWidth, int viewHeight) {
        List<Integer> indexes = selectionIndices();
        if (indexes.isEmpty()) return null;
        RectF union = null;
        for (int index : indexes) {
            RectF r = wordViewRect(index, viewWidth, viewHeight);
            if (union == null) union = new RectF(r); else union.union(r);
        }
        return union == null || union.isEmpty() ? null : union;
    }

    private void collapseExplicitToRange() {
        if (explicitSelection.isEmpty()) return;
        startIndex = explicitSelection.get(0);
        endIndex = explicitSelection.get(explicitSelection.size() - 1);
        explicitSelection = List.of();
    }

    private RectF toViewRect(Rect imageRect, int viewWidth, int viewHeight) {
        float sx = viewWidth / (float) imageWidth;
        float sy = viewHeight / (float) imageHeight;
        return new RectF(imageRect.left * sx, imageRect.top * sy,
                imageRect.right * sx, imageRect.bottom * sy);
    }

    private boolean validIndex(int index) { return index >= 0 && index < chars.size(); }

    /** Normalize mixed engine/refinement output into stable line/group/order metadata. */
    private List<OcrDocument.CharUnit> normalize(List<OcrDocument.CharUnit> input) {
        if (input == null || input.isEmpty()) return List.of();
        ArrayList<OcrDocument.CharUnit> sorted = new ArrayList<>();
        for (OcrDocument.CharUnit c : input) {
            if (c != null && !c.text().isBlank() && !c.bounds().isEmpty()) sorted.add(c);
        }
        sorted.sort((a, b) -> {
            Rect ar = a.bounds(), br = b.bounds();
            int tolerance = Math.max(3, Math.min(Math.max(1, ar.height()), Math.max(1, br.height())) / 2);
            int dy = ar.centerY() - br.centerY();
            if (Math.abs(dy) > tolerance) return Integer.compare(ar.centerY(), br.centerY());
            return Integer.compare(ar.left, br.left);
        });

        ArrayList<OcrDocument.CharUnit> out = new ArrayList<>();
        int line = -1, group = 0, order = 0;
        Rect previous = null;
        int lineCenter = Integer.MIN_VALUE;
        for (OcrDocument.CharUnit c : sorted) {
            Rect r = c.bounds();
            int tolerance = previous == null ? 0
                    : Math.max(3, Math.min(Math.max(1, previous.height()), Math.max(1, r.height())) / 2);
            boolean newLine = previous == null || Math.abs(r.centerY() - lineCenter) > tolerance;
            if (newLine) {
                line++;
                group++;
                lineCenter = r.centerY();
            } else {
                int gap = r.left - previous.right;
                float threshold = Math.max(2f, Math.min(previous.height(), r.height()) * 0.32f);
                if (gap > threshold) group++;
                lineCenter = (lineCenter + r.centerY()) / 2;
            }
            out.add(new OcrDocument.CharUnit(c.text(), r, c.confidence(), line, group, order++));
            previous = r;
        }
        return List.copyOf(out);
    }

    private static boolean noSpaceBetween(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        int ac = a.codePointBefore(a.length());
        int bc = b.codePointAt(0);
        return isCjk(ac) && isCjk(bc);
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x3400 && cp <= 0x4DBF) || (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0x20000 && cp <= 0x2FA1F);
    }
}
