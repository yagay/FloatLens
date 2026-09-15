package com.yagay.floatlens;

import android.graphics.Rect;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;

/** Pure screen-space text selection state used by CircleSelectOverlay/GoogleCircleInlineOverlay. */
final class CircleTextSelectionModel {
    private final ScreenBitmapTransform transform;
    private List<OcrDocument.CharUnit> chars = List.of();
    private int startIndex = -1;
    private int endIndex = -1;
    /** Non-contiguous rectangle selection before a handle is dragged. Always expanded to groups. */
    private List<Integer> explicitSelection = List.of();
    /**
     * Initial tap/scribble selection is semantic-group based. Once either selection handle is moved,
     * the endpoints become character-precise so a user can select only part of a word.
     */
    private boolean characterAdjustment;

    CircleTextSelectionModel(ScreenBitmapTransform transform) {
        if (transform == null) throw new IllegalArgumentException("transform required");
        this.transform = transform;
    }

    void setDocument(OcrDocument document) {
        if (document != null && !document.isScreenSpace()) {
            throw new IllegalArgumentException("Circle selection requires screen-space text");
        }
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
    boolean isCharacterAdjustment() { return characterAdjustment; }

    void clear() {
        startIndex = endIndex = -1;
        explicitSelection = List.of();
        characterAdjustment = false;
    }

    /** Select the complete semantic word/continuous-text group containing index. */
    void selectGroup(int index) {
        if (!validIndex(index)) return;
        explicitSelection = List.of();
        characterAdjustment = false;
        startIndex = groupStart(index);
        endIndex = groupEnd(index);
    }

    /** Legacy name retained for old callers; first selection is Google-style whole-group selection. */
    void selectSingle(int index) {
        selectGroup(index);
    }

    void selectAll() {
        if (chars.isEmpty()) return;
        explicitSelection = List.of();
        characterAdjustment = false;
        startIndex = 0;
        endIndex = chars.size() - 1;
    }

    /** Handle adjustment is deliberately character-precise, even inside the initially selected word. */
    void updateStart(int index) {
        if (!validIndex(index)) return;
        collapseExplicitToRange();
        characterAdjustment = true;
        startIndex = index;
    }

    /** Handle adjustment is deliberately character-precise, even inside the initially selected word. */
    void updateEnd(int index) {
        if (!validIndex(index)) return;
        collapseExplicitToRange();
        characterAdjustment = true;
        endIndex = index;
    }

    boolean hasSelection() {
        return !selectionIndices().isEmpty();
    }

    /** First selected character. It is also the first drawable segment start. */
    int low() {
        List<Integer> indexes = selectionIndices();
        return indexes.isEmpty() ? -1 : indexes.get(0);
    }

    /**
     * First character of the last selected visual segment. This lets the old overlay place the
     * right handle on the union box even when a word is only partially selected.
     */
    int high() {
        List<Integer> indexes = selectionIndices();
        if (indexes.isEmpty()) return -1;
        int position = indexes.size() - 1;
        int last = indexes.get(position);
        while (position > 0) {
            int previous = indexes.get(position - 1);
            int current = indexes.get(position);
            if (previous + 1 != current || !sameGroup(chars.get(previous), chars.get(last))) break;
            position--;
        }
        return indexes.get(position);
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

    /**
     * Compatibility surface for existing overlays: one drawable rectangle per selected semantic
     * segment. Before a handle is moved, a segment is the whole word/group. After a handle is moved,
     * the first/last segment can be only the selected letters inside that word.
     */
    RectF wordViewRect(int index, int viewWidth, int viewHeight) {
        if (!validIndex(index)) return new RectF();
        if (!characterAdjustment) {
            if (index != groupStart(index)) return new RectF();
            return groupViewRect(index, viewWidth, viewHeight);
        }

        List<Integer> selected = selectionIndices();
        int position = selected.indexOf(index);
        if (position < 0) return new RectF();
        if (position > 0) {
            int previous = selected.get(position - 1);
            if (previous + 1 == index && sameGroup(chars.get(previous), chars.get(index))) {
                return new RectF();
            }
        }

        Rect union = new Rect(chars.get(index).bounds());
        int previous = index;
        for (int i = position + 1; i < selected.size(); i++) {
            int next = selected.get(i);
            if (previous + 1 != next || !sameGroup(chars.get(index), chars.get(next))) break;
            union.union(chars.get(next).bounds());
            previous = next;
        }
        return transform.screenToView(union, viewWidth, viewHeight);
    }

    RectF groupViewRect(int index, int viewWidth, int viewHeight) {
        Rect screen = groupScreenBounds(index);
        return screen == null ? new RectF() : transform.screenToView(screen, viewWidth, viewHeight);
    }

    /** One visual rectangle per selected semantic segment, not one rectangle per character. */
    List<RectF> selectionGroupViewRects(int viewWidth, int viewHeight) {
        List<Integer> indexes = selectionIndices();
        if (indexes.isEmpty()) return List.of();
        ArrayList<RectF> out = new ArrayList<>();
        for (int position = 0; position < indexes.size();) {
            int first = indexes.get(position);
            if (!validIndex(first)) {
                position++;
                continue;
            }
            Rect union = new Rect(chars.get(first).bounds());
            int previous = first;
            int nextPosition = position + 1;
            while (nextPosition < indexes.size()) {
                int next = indexes.get(nextPosition);
                if (!validIndex(next) || previous + 1 != next
                        || !sameGroup(chars.get(first), chars.get(next))) break;
                union.union(chars.get(next).bounds());
                previous = next;
                nextPosition++;
            }
            out.add(transform.screenToView(union, viewWidth, viewHeight));
            position = nextPosition;
        }
        return List.copyOf(out);
    }

    int selectedGroupCount() {
        return selectionGroupViewRects(1, 1).size();
    }

    int findWordAt(float viewX, float viewY, int viewWidth, int viewHeight) {
        if (chars.isEmpty() || viewWidth <= 0 || viewHeight <= 0) return -1;
        int sx = transform.viewXToScreen(viewX, viewWidth);
        int sy = transform.viewYToScreen(viewY, viewHeight);
        int best = -1;
        long bestArea = Long.MAX_VALUE;
        for (int i = 0; i < chars.size(); i++) {
            Rect r = chars.get(i).bounds();
            if (!r.contains(sx, sy)) continue;
            long area = Math.max(1L, (long) r.width() * r.height());
            if (area < bestArea) { bestArea = area; best = i; }
        }
        return best;
    }

    /**
     * Exact character hit first; otherwise snap to the nearest character on the same row. Callers
     * decide whether that character should expand to its whole group (initial tap) or remain exact
     * (selection-handle adjustment).
     */
    int findSelectionWord(float viewX, float viewY, int viewWidth, int viewHeight,
                          float maxDistancePx) {
        int exact = findWordAt(viewX, viewY, viewWidth, viewHeight);
        if (exact >= 0) return exact;
        if (chars.isEmpty() || viewWidth <= 0 || viewHeight <= 0) return -1;

        float bestScore = Float.MAX_VALUE;
        int best = -1;
        for (int i = 0; i < chars.size(); i++) {
            RectF r = transform.screenToView(chars.get(i).bounds(), viewWidth, viewHeight);
            if (r.isEmpty()) continue;
            float dx = viewX < r.left ? r.left - viewX : viewX > r.right ? viewX - r.right : 0f;
            float dy = viewY < r.top ? r.top - viewY : viewY > r.bottom ? viewY - r.bottom : 0f;
            float rowGate = Math.max(r.height() * 1.35f,
                    Math.min(maxDistancePx * 0.48f, r.height() * 2.15f));
            if (dy > rowGate) continue;
            float score = dx * dx + dy * dy * 3.25f;
            if (score < bestScore) {
                bestScore = score;
                best = i;
            }
        }
        if (best < 0 || bestScore > maxDistancePx * maxDistancePx) return -1;
        return best;
    }

    /** Select semantic groups materially intersecting the absolute screen rectangle. */
    boolean selectIntersecting(Rect screenRect) {
        if (screenRect == null || screenRect.isEmpty() || chars.isEmpty()) return false;
        boolean[] selected = new boolean[chars.size()];
        boolean found = false;
        for (int i = 0; i < chars.size(); i++) {
            Rect r = chars.get(i).bounds();
            Rect intersection = new Rect();
            boolean intersects = intersection.setIntersect(r, screenRect);
            if (!intersects) continue;
            long overlap = (long) intersection.width() * intersection.height();
            long area = Math.max(1L, (long) r.width() * r.height());
            boolean centerInside = screenRect.contains(r.centerX(), r.centerY());
            if (!centerInside && overlap < area * 0.28f) continue;
            found = true;
            int lo = groupStart(i);
            int hi = groupEnd(i);
            for (int j = lo; j <= hi; j++) selected[j] = true;
        }
        if (!found) return false;

        ArrayList<Integer> hit = new ArrayList<>();
        for (int i = 0; i < selected.length; i++) if (selected[i]) hit.add(i);
        if (hit.isEmpty()) return false;
        explicitSelection = List.copyOf(hit);
        characterAdjustment = false;
        startIndex = hit.get(0);
        endIndex = hit.get(hit.size() - 1);
        return true;
    }

    /** Replace stale characters in a screen-space refined region. */
    void mergeRefinement(OcrDocument translatedPatch, Rect screenRect) {
        if (translatedPatch == null || !translatedPatch.isScreenSpace()
                || screenRect == null || screenRect.isEmpty()) return;
        ArrayList<OcrDocument.CharUnit> merged = new ArrayList<>();
        for (OcrDocument.CharUnit c : chars) {
            Rect r = c.bounds();
            if (!Rect.intersects(r, screenRect)) merged.add(c);
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

    Rect selectionScreenBounds() {
        List<Integer> indexes = selectionIndices();
        if (indexes.isEmpty()) return null;
        Rect union = null;
        for (int index : indexes) {
            if (!validIndex(index)) continue;
            Rect r = chars.get(index).bounds();
            if (union == null) union = new Rect(r); else union.union(r);
        }
        return union == null || union.isEmpty() ? null : union;
    }

    RectF selectionViewBounds(int viewWidth, int viewHeight) {
        Rect screen = selectionScreenBounds();
        return screen == null ? null : transform.screenToView(screen, viewWidth, viewHeight);
    }

    private Rect groupScreenBounds(int index) {
        if (!validIndex(index)) return null;
        int lo = groupStart(index);
        int hi = groupEnd(index);
        Rect out = null;
        for (int i = lo; i <= hi; i++) {
            Rect r = chars.get(i).bounds();
            if (out == null) out = new Rect(r); else out.union(r);
        }
        return out == null || out.isEmpty() ? null : out;
    }

    private int groupStart(int index) {
        if (!validIndex(index)) return index;
        OcrDocument.CharUnit target = chars.get(index);
        int i = index;
        while (i > 0 && sameGroup(chars.get(i - 1), target)) i--;
        return i;
    }

    private int groupEnd(int index) {
        if (!validIndex(index)) return index;
        OcrDocument.CharUnit target = chars.get(index);
        int i = index;
        while (i + 1 < chars.size() && sameGroup(chars.get(i + 1), target)) i++;
        return i;
    }

    private static boolean sameGroup(OcrDocument.CharUnit a, OcrDocument.CharUnit b) {
        return a != null && b != null && a.line() == b.line() && a.group() == b.group();
    }

    private void collapseExplicitToRange() {
        if (explicitSelection.isEmpty()) return;
        startIndex = explicitSelection.get(0);
        endIndex = explicitSelection.get(explicitSelection.size() - 1);
        explicitSelection = List.of();
    }

    private boolean validIndex(int index) { return index >= 0 && index < chars.size(); }

    /**
     * Normalize mixed View/OCR output into stable visual-line metadata while preserving the source
     * recognizer's semantic group boundaries. View snapshot groups come from actual whitespace in
     * the source CharSequence; PP-OCR groups come from actual recognized spaces; ML Kit groups map
     * to its Elements. Geometry is used only to order rows, never to invent a word break inside a
     * valid source group.
     */
    private List<OcrDocument.CharUnit> normalize(List<OcrDocument.CharUnit> input) {
        if (input == null || input.isEmpty()) return List.of();
        Rect workspace = transform.screenFrame();
        ArrayList<OcrDocument.CharUnit> sorted = new ArrayList<>();
        for (OcrDocument.CharUnit c : input) {
            if (c == null || c.text().isBlank() || c.bounds().isEmpty()) continue;
            Rect clipped = c.bounds();
            if (!workspace.isEmpty() && (!clipped.intersect(workspace) || clipped.isEmpty())) continue;
            sorted.add(new OcrDocument.CharUnit(c.text(), clipped, c.confidence(),
                    c.line(), c.group(), c.order()));
        }
        sorted.sort((a, b) -> {
            Rect ar = a.bounds(), br = b.bounds();
            int tolerance = Math.max(3, Math.min(Math.max(1, ar.height()), Math.max(1, br.height())) / 2);
            int dy = ar.centerY() - br.centerY();
            if (Math.abs(dy) > tolerance) return Integer.compare(ar.centerY(), br.centerY());
            return Integer.compare(ar.left, br.left);
        });

        ArrayList<OcrDocument.CharUnit> out = new ArrayList<>();
        int line = -1, group = -1, order = 0;
        Rect previous = null;
        int lineCenter = Integer.MIN_VALUE;
        int previousSourceLine = Integer.MIN_VALUE;
        int previousSourceGroup = Integer.MIN_VALUE;
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
                boolean semanticBreak = c.line() != previousSourceLine || c.group() != previousSourceGroup;
                if (semanticBreak) group++;
                lineCenter = (lineCenter + r.centerY()) / 2;
            }
            out.add(new OcrDocument.CharUnit(c.text(), r, c.confidence(), line, group, order++));
            previous = r;
            previousSourceLine = c.line();
            previousSourceGroup = c.group();
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
