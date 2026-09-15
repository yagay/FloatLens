package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Immutable absolute-screen image regions exposed by the target app's Accessibility tree. */
final class CircleViewImageSnapshot {
    private static final int MAX_NODES = 4200;
    private static final int MAX_IMAGES = 240;

    private final List<Rect> images;

    private CircleViewImageSnapshot(List<Rect> images) {
        this.images = images == null ? List.of() : List.copyOf(images);
    }

    static CircleViewImageSnapshot empty() {
        return new CircleViewImageSnapshot(List.of());
    }

    static CircleViewImageSnapshot capture(Context context) {
        if (context == null) return empty();
        Context app = context.getApplicationContext();
        LensAccessibilityService service = LensAccessibilityService.get();
        if (service == null) return empty();
        Rect display = ScreenGeometry.displayBounds(app);
        ArrayList<Rect> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int[] visited = {0};

        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows != null && !windows.isEmpty()) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null || visited[0] >= MAX_NODES || out.size() >= MAX_IMAGES) break;
                    AccessibilityNodeInfo root = null;
                    try { root = window.getRoot(); } catch (Throwable ignored) {}
                    if (root == null || ownPackage(service, root)) continue;
                    collect(root, display, out, seen, visited, 0);
                }
            } else {
                AccessibilityNodeInfo root = null;
                try { root = service.getRootInActiveWindow(); } catch (Throwable ignored) {}
                if (root != null && !ownPackage(service, root)) {
                    collect(root, display, out, seen, visited, 0);
                }
            }
        } catch (Throwable t) {
            DiagnosticLog.i(service, "CIRCLE_VIEW_IMAGE", "capture failed=" + safe(t));
        }

        out.sort(Comparator.comparingLong(CircleViewImageSnapshot::area)
                .thenComparingInt(Rect::top)
                .thenComparingInt(Rect::left));
        DiagnosticLog.i(service, "CIRCLE_VIEW_IMAGE", "captured images=" + out.size()
                + " visited=" + visited[0] + " coordinateSpace=absolute_screen");
        return new CircleViewImageSnapshot(out);
    }

    int size() { return images.size(); }

    Rect findAt(int screenX, int screenY) {
        Rect best = null;
        long bestArea = Long.MAX_VALUE;
        for (Rect r : images) {
            if (r == null || r.isEmpty() || !r.contains(screenX, screenY)) continue;
            long area = area(r);
            if (area < bestArea) {
                bestArea = area;
                best = r;
            }
        }
        return best == null ? null : new Rect(best);
    }

    private static void collect(AccessibilityNodeInfo node, Rect display,
                                List<Rect> out, Set<String> seen,
                                int[] visited, int depth) {
        if (node == null || depth > 80 || visited[0]++ >= MAX_NODES || out.size() >= MAX_IMAGES) return;
        try { if (!node.isVisibleToUser()) return; } catch (Throwable ignored) {}

        Rect bounds = new Rect();
        try { node.getBoundsInScreen(bounds); } catch (Throwable ignored) { return; }
        if (bounds.isEmpty()) return;
        if (display != null && !display.isEmpty() && !bounds.intersect(display)) return;

        if (isImageNode(node)) add(out, seen, bounds);
        for (Rect enhanced : LsposedViewContentMetadata.imageBounds(node)) {
            Rect clipped = new Rect(enhanced);
            if (display == null || display.isEmpty() || clipped.intersect(display)) add(out, seen, clipped);
        }

        int count = Math.min(300, safeChildCount(node));
        for (int i = 0; i < count; i++) {
            if (visited[0] >= MAX_NODES || out.size() >= MAX_IMAGES) return;
            AccessibilityNodeInfo child = null;
            try { child = node.getChild(i); } catch (Throwable ignored) {}
            if (child != null) collect(child, display, out, seen, visited, depth + 1);
        }
    }

    private static boolean isImageNode(AccessibilityNodeInfo node) {
        try {
            CharSequence cls = node.getClassName();
            String name = cls == null ? "" : cls.toString();
            if (name.endsWith("ImageView") || name.contains("ImageView")) return true;
        } catch (Throwable ignored) {}
        int kind = LsposedViewContentMetadata.kind(node);
        return kind == LsposedViewContentMetadata.KIND_IMAGE
                || kind == LsposedViewContentMetadata.KIND_TEXT_IMAGE;
    }

    private static void add(List<Rect> out, Set<String> seen, Rect rect) {
        if (rect == null || rect.isEmpty()) return;
        String key = rect.flattenToString();
        if (seen.add(key)) out.add(new Rect(rect));
    }

    private static boolean ownPackage(LensAccessibilityService service, AccessibilityNodeInfo node) {
        try {
            CharSequence pkg = node.getPackageName();
            return service != null && pkg != null && service.getPackageName().contentEquals(pkg);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int safeChildCount(AccessibilityNodeInfo node) {
        try { return node.getChildCount(); } catch (Throwable ignored) { return 0; }
    }

    private static long area(Rect r) {
        return r == null || r.isEmpty() ? Long.MAX_VALUE
                : Math.max(1L, (long) r.width() * r.height());
    }

    private static String safe(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }

    private CircleViewImageSnapshot() {}
}
