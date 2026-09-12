package com.yagay.floatlens;

import android.graphics.Rect;
import android.graphics.RectF;

import java.util.List;

/** Pure spatial-OCR selection state used by CircleSelectOverlay. */
final class CircleTextSelectionModel {
    private final int imageWidth;
    private final int imageHeight;
    private List<SpatialOcrEngine.Word> words = List.of();
    private int startIndex = -1;
    private int endIndex = -1;

    CircleTextSelectionModel(int imageWidth, int imageHeight) {
        this.imageWidth = Math.max(1, imageWidth);
        this.imageHeight = Math.max(1, imageHeight);
    }

    void setWords(List<SpatialOcrEngine.Word> value) {
        words = value == null ? List.of() : value;
        clear();
    }

    List<SpatialOcrEngine.Word> words() { return words; }
    int size() { return words.size(); }
    boolean isEmpty() { return words.isEmpty(); }
    int startIndex() { return startIndex; }
    int endIndex() { return endIndex; }

    void clear() { startIndex = endIndex = -1; }

    void selectSingle(int index) {
        if (!validIndex(index)) return;
        startIndex = endIndex = index;
    }

    void selectAll() {
        if (words.isEmpty()) return;
        startIndex = 0;
        endIndex = words.size() - 1;
    }

    void updateStart(int index) { if (validIndex(index)) startIndex = index; }
    void updateEnd(int index) { if (validIndex(index)) endIndex = index; }

    boolean hasSelection() {
        return validIndex(startIndex) && validIndex(endIndex);
    }

    int low() { return hasSelection() ? Math.min(startIndex, endIndex) : -1; }
    int high() { return hasSelection() ? Math.max(startIndex, endIndex) : -1; }

    RectF wordViewRect(int index, int viewWidth, int viewHeight) {
        if (!validIndex(index)) return new RectF();
        return toViewRect(words.get(index).bounds(), viewWidth, viewHeight);
    }

    int findWordAt(float viewX, float viewY, int viewWidth, int viewHeight) {
        if (words.isEmpty() || viewWidth <= 0 || viewHeight <= 0) return -1;
        int bx = Math.round(viewX * imageWidth / (float) viewWidth);
        int by = Math.round(viewY * imageHeight / (float) viewHeight);
        int best = -1;
        long bestArea = Long.MAX_VALUE;
        for (int i = 0; i < words.size(); i++) {
            Rect r = words.get(i).bounds();
            if (!r.contains(bx, by)) continue;
            long area = Math.max(1L, (long) r.width() * r.height());
            if (area < bestArea) {
                bestArea = area;
                best = i;
            }
        }
        return best;
    }

    int findSelectionWord(float viewX, float viewY, int viewWidth, int viewHeight,
                          float maxDistancePx) {
        int exact = findWordAt(viewX, viewY, viewWidth, viewHeight);
        if (exact >= 0) return exact;
        if (words.isEmpty() || viewWidth <= 0 || viewHeight <= 0) return -1;

        float bestScore = Float.MAX_VALUE;
        int best = -1;
        for (int i = 0; i < words.size(); i++) {
            RectF r = wordViewRect(i, viewWidth, viewHeight);
            float dx = 0f;
            if (viewX < r.left) dx = r.left - viewX;
            else if (viewX > r.right) dx = viewX - r.right;
            float dy = 0f;
            if (viewY < r.top) dy = r.top - viewY;
            else if (viewY > r.bottom) dy = viewY - r.bottom;
            float score = dx * dx + dy * dy * 0.72f;
            if (score < bestScore) {
                bestScore = score;
                best = i;
            }
        }
        if (best < 0 || bestScore > maxDistancePx * maxDistancePx) return -1;
        return best;
    }

    String selectedText() {
        if (!hasSelection()) return "";
        StringBuilder out = new StringBuilder();
        int previousLine = -1;
        int previousGroup = -1;
        String previous = "";
        for (int i = low(); i <= high() && i < words.size(); i++) {
            SpatialOcrEngine.Word w = words.get(i);
            String value = w.text();
            if (value.isBlank()) continue;
            if (out.length() > 0) {
                if (w.line() != previousLine) {
                    out.append('\n');
                } else if (w.group() != previousGroup && !noSpaceBetween(previous, value)) {
                    out.append(' ');
                }
            }
            out.append(value);
            previousLine = w.line();
            previousGroup = w.group();
            previous = value;
        }
        return out.toString().trim();
    }

    RectF selectionViewBounds(int viewWidth, int viewHeight) {
        if (!hasSelection()) return null;
        RectF union = null;
        for (int i = low(); i <= high() && i < words.size(); i++) {
            RectF r = wordViewRect(i, viewWidth, viewHeight);
            if (union == null) union = new RectF(r);
            else union.union(r);
        }
        return union == null || union.isEmpty() ? null : union;
    }

    private RectF toViewRect(Rect imageRect, int viewWidth, int viewHeight) {
        float sx = viewWidth / (float) imageWidth;
        float sy = viewHeight / (float) imageHeight;
        return new RectF(imageRect.left * sx, imageRect.top * sy,
                imageRect.right * sx, imageRect.bottom * sy);
    }

    private boolean validIndex(int index) {
        return index >= 0 && index < words.size();
    }

    private static boolean noSpaceBetween(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        int ac = a.codePointBefore(a.length());
        int bc = b.codePointAt(0);
        return isCjk(ac) && isCjk(bc);
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0x20000 && cp <= 0x2FA1F);
    }
}
