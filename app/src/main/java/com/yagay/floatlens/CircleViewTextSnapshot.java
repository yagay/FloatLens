package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Parcelable;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable View-text snapshot captured before Circle Select installs its overlay.
 *
 * All geometry is kept in absolute screen coordinates. Screenshot bounds and bitmap resolution are
 * deliberately not part of this class, so changing screenshot policy can never move View text.
 */
final class CircleViewTextSnapshot {
    private static final int MAX_NODES = 4200;
    private static final int MAX_TEXT_NODES = 480;
    private static final int MAX_CHARACTER_REQUEST = 20000;

    private final Rect displayBounds;
    private final List<TextNode> nodes;
    private final int exactGeometryNodes;

    private CircleViewTextSnapshot(Rect displayBounds, List<TextNode> nodes, int exactGeometryNodes) {
        this.displayBounds = displayBounds == null ? new Rect() : new Rect(displayBounds);
        this.nodes = nodes == null ? List.of() : List.copyOf(nodes);
        this.exactGeometryNodes = Math.max(0, exactGeometryNodes);
    }

    static CircleViewTextSnapshot empty(Rect displayBounds) {
        return new CircleViewTextSnapshot(displayBounds, List.of(), 0);
    }

    static CircleViewTextSnapshot capture(Context context) {
        Context app = context.getApplicationContext();
        LensAccessibilityService service = LensAccessibilityService.get();
        Rect display = safeDisplayBounds(app, service);
        if (service == null) return empty(display);

        ArrayList<TextNode> out = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        int[] visited = {0};
        int[] exact = {0};

        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows != null && !windows.isEmpty()) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null || visited[0] >= MAX_NODES || out.size() >= MAX_TEXT_NODES) break;
                    AccessibilityNodeInfo root = null;
                    try { root = window.getRoot(); } catch (Throwable ignored) {}
                    if (root == null || ownPackage(service, root)) continue;
                    collect(root, display, out, seen, visited, exact, 0);
                }
            } else {
                AccessibilityNodeInfo active = null;
                try { active = service.getRootInActiveWindow(); } catch (Throwable ignored) {}
                if (active != null && !ownPackage(service, active)) {
                    collect(active, display, out, seen, visited, exact, 0);
                }
            }
        } catch (Throwable t) {
            DiagnosticLog.i(service, "CIRCLE_VIEW_SNAPSHOT", "capture failed=" + safe(t));
        }

        List<TextNode> pruned = pruneDuplicateContainers(out);
        int keptExact = 0;
        for (TextNode node : pruned) if (!node.characters.isEmpty()) keptExact++;
        DiagnosticLog.i(service, "CIRCLE_VIEW_SNAPSHOT",
                "captured nodes=" + pruned.size()
                        + " raw=" + out.size()
                        + " exactGeometry=" + keptExact
                        + " exactRaw=" + exact[0]
                        + " visited=" + visited[0]
                        + " display=" + display.toShortString()
                        + " coordinateSpace=absolute_screen");
        return new CircleViewTextSnapshot(display, pruned, keptExact);
    }

    boolean isEmpty() { return nodes.isEmpty(); }
    int nodeCount() { return nodes.size(); }
    int exactGeometryNodeCount() { return exactGeometryNodes; }
    Rect displayBounds() { return new Rect(displayBounds); }

    OcrDocument toScreenDocument() {
        int width = Math.max(1, displayBounds.width());
        int height = Math.max(1, displayBounds.height());
        if (nodes.isEmpty() || displayBounds.isEmpty()) {
            return new OcrDocument("", List.of(), List.of(), "view-snapshot", 1f, 0d, width, height);
        }

        ArrayList<OcrDocument.Line> lines = new ArrayList<>();
        int lineId = 0;
        int group = 0;
        int order = 0;
        for (TextNode node : nodes) {
            if (node == null || node.text.isBlank() || node.bounds.isEmpty()) continue;

            if (!node.characters.isEmpty()) {
                ArrayList<CharacterBox> charsInNode = new ArrayList<>(node.characters);
                charsInNode.sort(Comparator.comparingInt((CharacterBox c) -> c.bounds.centerY())
                        .thenComparingInt(c -> c.bounds.left));
                for (ArrayList<CharacterBox> row : splitRows(charsInNode)) {
                    if (row.isEmpty()) continue;
                    Rect rowBounds = null;
                    StringBuilder rowText = new StringBuilder();
                    ArrayList<OcrDocument.CharUnit> chars = new ArrayList<>();
                    int rowGroup = group++;
                    for (CharacterBox c : row) {
                        if (rowBounds == null) rowBounds = new Rect(c.bounds); else rowBounds.union(c.bounds);
                        rowText.append(c.text);
                        chars.add(new OcrDocument.CharUnit(c.text, c.bounds, 1f,
                                lineId, rowGroup, order++));
                    }
                    if (rowBounds != null && !rowBounds.isEmpty() && !chars.isEmpty()) {
                        lines.add(new OcrDocument.Line(rowText.toString(), rowBounds, 1f, chars));
                        lineId++;
                    }
                }
                continue;
            }

            String[] rows = node.text.split("\\R", -1);
            int nonEmpty = 0;
            for (String row : rows) if (!row.trim().isEmpty()) nonEmpty++;
            if (nonEmpty == 0) continue;
            int rowIndex = 0;
            for (String raw : rows) {
                String row = raw.trim();
                if (row.isEmpty()) continue;
                int top = node.bounds.top + node.bounds.height() * rowIndex / nonEmpty;
                int bottom = node.bounds.top + node.bounds.height() * (rowIndex + 1) / nonEmpty;
                Rect lineBounds = new Rect(node.bounds.left, top, node.bounds.right,
                        Math.max(top + 1, bottom));
                ArrayList<OcrDocument.CharUnit> chars = new ArrayList<>();
                int visible = countVisible(row);
                int visibleIndex = 0;
                int rowGroup = group++;
                for (int cp : row.codePoints().toArray()) {
                    if (Character.isWhitespace(cp)) continue;
                    int left = lineBounds.left + lineBounds.width() * visibleIndex / Math.max(1, visible);
                    int right = lineBounds.left + lineBounds.width() * (visibleIndex + 1) / Math.max(1, visible);
                    chars.add(new OcrDocument.CharUnit(new String(Character.toChars(cp)),
                            new Rect(left, lineBounds.top, Math.max(left + 1, right), lineBounds.bottom),
                            0.92f, lineId, rowGroup, order++));
                    visibleIndex++;
                }
                if (!chars.isEmpty()) {
                    lines.add(new OcrDocument.Line(row, lineBounds, 0.92f, chars));
                    lineId++;
                }
                rowIndex++;
            }
        }

        lines.sort(Comparator.comparingInt((OcrDocument.Line l) -> l.bounds().centerY())
                .thenComparingInt(l -> l.bounds().left));
        ArrayList<String> blocks = new ArrayList<>();
        StringBuilder full = new StringBuilder();
        for (OcrDocument.Line line : lines) {
            String text = line.text().trim();
            if (text.isEmpty()) continue;
            if (full.length() > 0) full.append('\n');
            full.append(text);
            blocks.add(text);
        }
        double score = lines.size() * 8d;
        for (OcrDocument.Line line : lines) score += line.chars().size() * 2d;
        return new OcrDocument(full.toString(), blocks, lines, "view-snapshot",
                1f, score, width, height);
    }

    private static Rect safeDisplayBounds(Context app, LensAccessibilityService service) {
        try {
            Rect display = CircleSelectFrame.displayBounds(app);
            if (display != null && !display.isEmpty()) return display;
        } catch (Throwable ignored) {}
        try {
            Rect display = service == null ? null : service.screenBounds();
            if (display != null && !display.isEmpty()) return new Rect(display);
        } catch (Throwable ignored) {}
        return new Rect();
    }

    private static void collect(AccessibilityNodeInfo node,
                                Rect display,
                                List<TextNode> out,
                                Set<String> seen,
                                int[] visited,
                                int[] exact,
                                int depth) {
        if (node == null || depth > 80 || visited[0]++ >= MAX_NODES || out.size() >= MAX_TEXT_NODES) return;
        try { if (!node.isVisibleToUser()) return; } catch (Throwable ignored) {}

        Rect bounds = new Rect();
        try { node.getBoundsInScreen(bounds); } catch (Throwable ignored) { return; }
        if (bounds.isEmpty()) return;
        Rect clipped = new Rect(bounds);
        if (display != null && !display.isEmpty() && !clipped.intersect(display)) return;

        CharSequence nativeText = safeText(node);
        CharSequence semanticText = firstNonBlank(nativeText,
                safeContentDescription(node), safeHint(node), safeStateDescription(node));
        if (semanticText != null) {
            String text = semanticText.toString().trim();
            if (!text.isEmpty()) {
                String key = clipped.flattenToString() + "\u0000" + text;
                if (seen.add(key)) {
                    List<CharacterBox> characters = nativeText == null || nativeText.toString().isBlank()
                            ? List.of() : requestCharacterBoxes(node, nativeText.toString(), display);
                    if (!characters.isEmpty()) exact[0]++;
                    out.add(new TextNode(clipped, text, characters, depth));
                }
            }
        }

        int children = Math.min(300, safeChildCount(node));
        for (int i = 0; i < children; i++) {
            if (visited[0] >= MAX_NODES || out.size() >= MAX_TEXT_NODES) return;
            AccessibilityNodeInfo child = null;
            try { child = node.getChild(i); } catch (Throwable ignored) {}
            if (child != null) collect(child, display, out, seen, visited, exact, depth + 1);
        }
    }

    private static List<CharacterBox> requestCharacterBoxes(AccessibilityNodeInfo node,
                                                             String text,
                                                             Rect display) {
        if (node == null || text == null || text.isEmpty()) return List.of();
        try {
            List<String> available = node.getAvailableExtraData();
            if (available == null
                    || !available.contains(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)) {
                return List.of();
            }
            int length = Math.min(text.length(), MAX_CHARACTER_REQUEST);
            if (length <= 0) return List.of();
            Bundle args = new Bundle();
            args.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0);
            args.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, length);
            if (!node.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, args)) {
                return List.of();
            }
            Parcelable[] raw = node.getExtras().getParcelableArray(
                    AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY);
            if (raw == null || raw.length == 0) return List.of();

            ArrayList<CharacterBox> out = new ArrayList<>();
            for (int offset = 0; offset < length;) {
                int cp = text.codePointAt(offset);
                int cpLength = Character.charCount(cp);
                RectF union = null;
                for (int j = 0; j < cpLength && offset + j < raw.length; j++) {
                    Parcelable value = raw[offset + j];
                    if (!(value instanceof RectF rect) || rect.isEmpty()) continue;
                    if (union == null) union = new RectF(rect); else union.union(rect);
                }
                if (!Character.isWhitespace(cp) && union != null && !union.isEmpty()) {
                    Rect r = new Rect((int) Math.floor(union.left), (int) Math.floor(union.top),
                            (int) Math.ceil(union.right), (int) Math.ceil(union.bottom));
                    if (display == null || display.isEmpty() || r.intersect(display)) {
                        if (!r.isEmpty()) out.add(new CharacterBox(new String(Character.toChars(cp)), r));
                    }
                }
                offset += cpLength;
            }
            return out;
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    private static List<TextNode> pruneDuplicateContainers(List<TextNode> input) {
        if (input == null || input.size() < 2) return input == null ? List.of() : List.copyOf(input);
        ArrayList<TextNode> sorted = new ArrayList<>(input);
        sorted.sort(Comparator.comparingLong(CircleViewTextSnapshot::area)
                .thenComparing((TextNode n) -> -n.depth));
        ArrayList<TextNode> kept = new ArrayList<>();
        for (TextNode candidate : sorted) {
            boolean duplicate = false;
            String compact = compact(candidate.text);
            for (TextNode existing : kept) {
                if (!compact.equals(compact(existing.text))) continue;
                if (containsWithTolerance(candidate.bounds, existing.bounds)
                        || containsWithTolerance(existing.bounds, candidate.bounds)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) kept.add(candidate);
        }
        kept.sort(Comparator.comparingInt((TextNode n) -> n.bounds.centerY())
                .thenComparingInt(n -> n.bounds.left));
        return List.copyOf(kept);
    }

    private static ArrayList<ArrayList<CharacterBox>> splitRows(List<CharacterBox> chars) {
        ArrayList<ArrayList<CharacterBox>> rows = new ArrayList<>();
        for (CharacterBox c : chars) {
            ArrayList<CharacterBox> target = null;
            for (ArrayList<CharacterBox> row : rows) {
                if (row.isEmpty()) continue;
                Rect sample = row.get(0).bounds;
                int tolerance = Math.max(3, Math.min(sample.height(), c.bounds.height()) / 2);
                if (Math.abs(sample.centerY() - c.bounds.centerY()) <= tolerance) {
                    target = row;
                    break;
                }
            }
            if (target == null) {
                target = new ArrayList<>();
                rows.add(target);
            }
            target.add(c);
        }
        rows.sort(Comparator.comparingInt(row -> row.get(0).bounds.centerY()));
        for (ArrayList<CharacterBox> row : rows) row.sort(Comparator.comparingInt(c -> c.bounds.left));
        return rows;
    }

    private static long area(TextNode n) {
        return n == null || n.bounds == null ? Long.MAX_VALUE
                : Math.max(1L, (long) n.bounds.width() * n.bounds.height());
    }

    private static boolean containsWithTolerance(Rect outer, Rect inner) {
        if (outer == null || inner == null || outer.isEmpty() || inner.isEmpty()) return false;
        int tolerance = 3;
        return outer.left <= inner.left + tolerance
                && outer.top <= inner.top + tolerance
                && outer.right >= inner.right - tolerance
                && outer.bottom >= inner.bottom - tolerance;
    }

    private static String compact(String value) {
        if (value == null) return "";
        return value.replaceAll("\\s+", "").trim();
    }

    private static int countVisible(String value) {
        int count = 0;
        if (value != null) {
            for (int cp : value.codePoints().toArray()) if (!Character.isWhitespace(cp)) count++;
        }
        return count;
    }

    private static boolean ownPackage(LensAccessibilityService service, AccessibilityNodeInfo node) {
        return service != null && service.getPackageName().equals(safePackage(node));
    }

    private static CharSequence firstNonBlank(CharSequence... values) {
        if (values == null) return null;
        for (CharSequence value : values) {
            if (value != null && !value.toString().trim().isEmpty()) return value;
        }
        return null;
    }

    private static CharSequence safeText(AccessibilityNodeInfo n) { try { return n.getText(); } catch (Throwable t) { return null; } }
    private static CharSequence safeContentDescription(AccessibilityNodeInfo n) { try { return n.getContentDescription(); } catch (Throwable t) { return null; } }
    private static CharSequence safeHint(AccessibilityNodeInfo n) { try { return n.getHintText(); } catch (Throwable t) { return null; } }
    private static CharSequence safeStateDescription(AccessibilityNodeInfo n) { try { return n.getStateDescription(); } catch (Throwable t) { return null; } }
    private static String safePackage(AccessibilityNodeInfo n) { try { return n.getPackageName() == null ? "" : n.getPackageName().toString(); } catch (Throwable t) { return ""; } }
    private static int safeChildCount(AccessibilityNodeInfo n) { try { return n.getChildCount(); } catch (Throwable t) { return 0; } }
    private static String safe(Throwable t) { if (t == null) return "unknown"; String m = t.getMessage(); return m == null || m.isBlank() ? t.getClass().getSimpleName() : m; }

    private static final class TextNode {
        final Rect bounds;
        final String text;
        final List<CharacterBox> characters;
        final int depth;

        TextNode(Rect bounds, String text, List<CharacterBox> characters, int depth) {
            this.bounds = bounds == null ? new Rect() : new Rect(bounds);
            this.text = text == null ? "" : text;
            this.characters = characters == null ? List.of() : List.copyOf(characters);
            this.depth = depth;
        }
    }

    private record CharacterBox(String text, Rect bounds) {
        CharacterBox {
            text = text == null ? "" : text;
            bounds = bounds == null ? new Rect() : new Rect(bounds);
        }
    }
}
