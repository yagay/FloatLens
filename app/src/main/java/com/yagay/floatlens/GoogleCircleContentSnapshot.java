package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Frozen semantic snapshot captured before the Google-style overlay is installed.
 * Gestures select content; this snapshot resolves native text/image targets in SCREEN coordinates.
 */
final class GoogleCircleContentSnapshot {
    private static final int MAX_NODES = 4200;
    private static final int MAX_TEXTS = 520;
    private static final int MAX_IMAGES = 260;

    enum Kind { NONE, TEXT, IMAGE }

    static final class Target {
        final Kind kind;
        final Rect screenBounds;
        final String text;

        private Target(Kind kind, Rect bounds, String text) {
            this.kind = kind == null ? Kind.NONE : kind;
            this.screenBounds = bounds == null ? new Rect() : new Rect(bounds);
            this.text = text == null ? "" : text.trim();
        }

        static Target none(Rect bounds) { return new Target(Kind.NONE, bounds, ""); }
        static Target text(Rect bounds, String text) { return new Target(Kind.TEXT, bounds, text); }
        static Target image(Rect bounds) { return new Target(Kind.IMAGE, bounds, ""); }
        boolean hasText() { return kind == Kind.TEXT && !text.isBlank(); }
    }

    private static final class TextNode {
        final Rect bounds;
        final String text;
        final int depth;

        TextNode(Rect bounds, String text, int depth) {
            this.bounds = new Rect(bounds);
            this.text = text == null ? "" : text.trim();
            this.depth = depth;
        }
    }

    private final List<TextNode> texts;
    private final List<Rect> images;

    private GoogleCircleContentSnapshot(List<TextNode> texts, List<Rect> images) {
        this.texts = texts == null ? List.of() : List.copyOf(texts);
        this.images = copyRects(images);
    }

    static GoogleCircleContentSnapshot empty(Rect ignoredDisplayBounds) {
        return new GoogleCircleContentSnapshot(List.of(), List.of());
    }

    static GoogleCircleContentSnapshot capture(Context context) {
        if (context == null) return empty(new Rect());
        Context app = context.getApplicationContext();
        LensAccessibilityService service = LensAccessibilityService.get();
        Rect display = ScreenGeometry.displayBounds(app);
        if (service == null || display.isEmpty() || interrupted()) return empty(display);

        ArrayList<TextNode> textOut = new ArrayList<>();
        ArrayList<Rect> imageOut = new ArrayList<>();
        Set<String> textSeen = new HashSet<>();
        Set<String> imageSeen = new HashSet<>();
        int[] visited = {0};

        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows != null && !windows.isEmpty()) {
                for (AccessibilityWindowInfo window : windows) {
                    if (interrupted() || window == null || visited[0] >= MAX_NODES) break;
                    AccessibilityNodeInfo root = null;
                    try { root = window.getRoot(); } catch (Throwable ignored) {}
                    if (root == null || ownPackage(service, root)) continue;
                    collect(service, root, display, textOut, imageOut,
                            textSeen, imageSeen, visited, 0);
                }
            } else if (!interrupted()) {
                AccessibilityNodeInfo root = null;
                try { root = service.getRootInActiveWindow(); } catch (Throwable ignored) {}
                if (root != null && !ownPackage(service, root)) {
                    collect(service, root, display, textOut, imageOut,
                            textSeen, imageSeen, visited, 0);
                }
            }
        } catch (Throwable t) {
            DiagnosticLog.i(app, "G_CIRCLE_CONTENT", "snapshot failed=" + safe(t));
        }

        if (interrupted()) return empty(display);
        List<TextNode> pruned = pruneTextContainers(textOut);
        imageOut.sort(Comparator.comparingLong(GoogleCircleContentSnapshot::area)
                .thenComparingInt(r -> r.top).thenComparingInt(r -> r.left));
        DiagnosticLog.i(app, "G_CIRCLE_CONTENT", "snapshot text=" + pruned.size()
                + " images=" + imageOut.size() + " visited=" + visited[0]
                + " coordinateSpace=absolute_screen");
        return new GoogleCircleContentSnapshot(pruned, imageOut);
    }

    int textCount() { return texts.size(); }
    int imageCount() { return images.size(); }

    Target resolve(GoogleCircleCapture.Frame frame, GoogleCircleSelection.Selection selection) {
        if (frame == null || selection == null) return Target.none(new Rect());
        Rect selected = frame.bitmapRectToScreen(toBitmapRect(selection.bounds,
                frame.bitmap.getWidth(), frame.bitmap.getHeight()));
        Point focus = bitmapPointToScreen(frame, selection.focus.x, selection.focus.y);
        if (selected.isEmpty()) selected.set(focus.x, focus.y, focus.x + 1, focus.y + 1);

        if (selection.kind == GoogleCircleSelection.Kind.TAP) {
            TextNode exactText = smallestTextAt(focus.x, focus.y);
            if (exactText != null) return Target.text(exactText.bounds, exactText.text);
            Rect exactImage = smallestImageAt(focus.x, focus.y);
            if (exactImage != null) return Target.image(exactImage);
            Target nearby = selectText(selected, true);
            if (nearby.hasText()) return nearby;
            Rect image = bestImage(selected, focus, 0.16d);
            return image == null ? Target.none(selected) : Target.image(image);
        }

        if (selection.kind == GoogleCircleSelection.Kind.HIGHLIGHT) {
            Target text = selectText(selected, false);
            if (text.hasText()) return text;
            Rect image = bestImage(selected, focus, 0.18d);
            return image == null ? Target.none(selected) : Target.image(image);
        }

        Target text = selectText(selected, false);
        Rect image = bestImage(selected, focus, 0.24d);
        if (text.hasText() && image == null) return text;
        if (!text.hasText() && image != null) return Target.image(image);
        if (!text.hasText()) return Target.none(selected);

        TextNode focusedText = smallestTextAt(focus.x, focus.y);
        if (focusedText != null && coverage(focusedText.bounds, selected) >= 0.20d) return text;
        double imageCoverage = overlapArea(image, selected) / (double) Math.max(1L, area(selected));
        double textCoverage = overlapArea(text.screenBounds, selected)
                / (double) Math.max(1L, area(selected));
        return imageCoverage > Math.max(0.42d, textCoverage * 1.35d)
                ? Target.image(image) : text;
    }

    private Target selectText(Rect selected, boolean loose) {
        ArrayList<TextNode> hits = new ArrayList<>();
        for (TextNode node : texts) {
            long overlap = overlapArea(node.bounds, selected);
            if (overlap <= 0) continue;
            boolean centerInside = selected.contains(node.bounds.centerX(), node.bounds.centerY());
            double nodeRatio = overlap / (double) Math.max(1L, area(node.bounds));
            double selectedRatio = overlap / (double) Math.max(1L, area(selected));
            if (centerInside || nodeRatio >= (loose ? 0.12d : 0.26d)
                    || selectedRatio >= (loose ? 0.10d : 0.20d)) hits.add(node);
        }
        if (hits.isEmpty()) return Target.none(selected);
        hits.sort(Comparator.comparingInt((TextNode n) -> n.bounds.centerY())
                .thenComparingInt(n -> n.bounds.left));

        StringBuilder value = new StringBuilder();
        Rect union = null;
        HashSet<String> emitted = new HashSet<>();
        for (TextNode hit : hits) {
            String key = compact(hit.text);
            if (key.isEmpty() || !emitted.add(key)) continue;
            if (value.length() > 0) value.append('\n');
            value.append(hit.text);
            if (union == null) union = new Rect(hit.bounds); else union.union(hit.bounds);
        }
        return value.length() == 0 ? Target.none(selected) : Target.text(union, value.toString());
    }

    private TextNode smallestTextAt(int x, int y) {
        TextNode best = null;
        long bestArea = Long.MAX_VALUE;
        for (TextNode node : texts) {
            if (!node.bounds.contains(x, y)) continue;
            long candidateArea = area(node.bounds);
            if (candidateArea < bestArea) { bestArea = candidateArea; best = node; }
        }
        return best;
    }

    private Rect smallestImageAt(int x, int y) {
        Rect best = null;
        long bestArea = Long.MAX_VALUE;
        for (Rect image : images) {
            if (!image.contains(x, y)) continue;
            long candidateArea = area(image);
            if (candidateArea < bestArea) { bestArea = candidateArea; best = image; }
        }
        return best == null ? null : new Rect(best);
    }

    private Rect bestImage(Rect selected, Point focus, double minimumScore) {
        Rect best = null;
        double bestScore = minimumScore;
        for (Rect image : images) {
            long overlap = overlapArea(image, selected);
            if (overlap <= 0) continue;
            double selectedRatio = overlap / (double) Math.max(1L, area(selected));
            double imageRatio = overlap / (double) Math.max(1L, area(image));
            double score = Math.max(selectedRatio, imageRatio * 0.78d);
            if (focus != null && image.contains(focus.x, focus.y)) score += 0.18d;
            if (score > bestScore) { bestScore = score; best = image; }
        }
        return best == null ? null : new Rect(best);
    }

    private static void collect(LensAccessibilityService service, AccessibilityNodeInfo node,
                                Rect display, List<TextNode> texts, List<Rect> images,
                                Set<String> textSeen, Set<String> imageSeen,
                                int[] visited, int depth) {
        if (node == null || depth > 80 || interrupted() || visited[0]++ >= MAX_NODES) return;
        try { if (!node.isVisibleToUser()) return; } catch (Throwable ignored) {}
        Rect bounds = AccessibilityNodeSemantics.clippedBounds(node, display);
        if (bounds.isEmpty()) return;

        if (texts.size() < MAX_TEXTS) {
            String text = AccessibilityNodeSemantics.visibleText(node);
            if (!text.isBlank()) {
                String key = bounds.flattenToString() + '\u0000' + compact(text);
                if (textSeen.add(key)) texts.add(new TextNode(bounds, text, depth));
            }
        }
        if (images.size() < MAX_IMAGES && AccessibilityNodeSemantics.isImage(service, node, bounds)) {
            String key = bounds.flattenToString();
            if (imageSeen.add(key)) images.add(new Rect(bounds));
        }

        int count = Math.min(300, AccessibilityNodeSemantics.childCount(node));
        for (int i = 0; i < count; i++) {
            if (interrupted() || visited[0] >= MAX_NODES) return;
            AccessibilityNodeInfo child = null;
            try { child = node.getChild(i); } catch (Throwable ignored) {}
            if (child != null) collect(service, child, display, texts, images,
                    textSeen, imageSeen, visited, depth + 1);
        }
    }

    private static List<TextNode> pruneTextContainers(List<TextNode> input) {
        if (input == null || input.isEmpty()) return List.of();
        ArrayList<TextNode> sorted = new ArrayList<>(input);
        sorted.sort(Comparator.comparingLong((TextNode n) -> area(n.bounds))
                .thenComparing((TextNode n) -> -n.depth));
        ArrayList<TextNode> kept = new ArrayList<>();
        for (TextNode candidate : sorted) {
            String candidateText = compact(candidate.text);
            if (candidateText.isEmpty()) continue;
            boolean duplicate = false;
            for (TextNode existing : kept) {
                if (!candidateText.equals(compact(existing.text))) continue;
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

    private static Rect toBitmapRect(RectF r, int width, int height) {
        int left = Math.max(0, Math.min(width - 1, (int) Math.floor(r.left)));
        int top = Math.max(0, Math.min(height - 1, (int) Math.floor(r.top)));
        int right = Math.max(left + 1, Math.min(width, (int) Math.ceil(r.right)));
        int bottom = Math.max(top + 1, Math.min(height, (int) Math.ceil(r.bottom)));
        return new Rect(left, top, right, bottom);
    }

    private static Point bitmapPointToScreen(GoogleCircleCapture.Frame frame, float x, float y) {
        float sx = frame.screenBounds.width() / (float) Math.max(1, frame.bitmap.getWidth());
        float sy = frame.screenBounds.height() / (float) Math.max(1, frame.bitmap.getHeight());
        return new Point(frame.screenBounds.left + Math.round(x * sx),
                frame.screenBounds.top + Math.round(y * sy));
    }

    private static double coverage(Rect value, Rect selected) {
        return overlapArea(value, selected) / (double) Math.max(1L, area(value));
    }

    private static boolean containsWithTolerance(Rect outer, Rect inner) {
        if (outer == null || inner == null || outer.isEmpty() || inner.isEmpty()) return false;
        int tolerance = 3;
        return outer.left <= inner.left + tolerance && outer.top <= inner.top + tolerance
                && outer.right >= inner.right - tolerance && outer.bottom >= inner.bottom - tolerance;
    }

    private static long overlapArea(Rect a, Rect b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty() || !Rect.intersects(a, b)) return 0L;
        int left = Math.max(a.left, b.left);
        int top = Math.max(a.top, b.top);
        int right = Math.min(a.right, b.right);
        int bottom = Math.min(a.bottom, b.bottom);
        return Math.max(0L, (long) (right - left) * (bottom - top));
    }

    private static long area(Rect r) {
        return r == null || r.isEmpty() ? 0L : Math.max(1L, (long) r.width() * r.height());
    }

    private static String compact(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static boolean ownPackage(LensAccessibilityService service, AccessibilityNodeInfo node) {
        return service != null && service.getPackageName().equals(
                AccessibilityNodeSemantics.packageName(node));
    }

    private static List<Rect> copyRects(List<Rect> source) {
        if (source == null || source.isEmpty()) return List.of();
        ArrayList<Rect> out = new ArrayList<>();
        for (Rect rect : source) if (rect != null && !rect.isEmpty()) out.add(new Rect(rect));
        return List.copyOf(out);
    }

    private static boolean interrupted() { return Thread.currentThread().isInterrupted(); }

    private static String safe(Throwable t) {
        if (t == null) return "unknown";
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }
}
