package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Immutable absolute-screen image regions from the normal Accessibility tree only. */
final class CircleViewImageSnapshot {
    private static final int MAX_NODES = 4200;
    private static final int MAX_IMAGES = 240;

    private final List<Rect> images;

    private CircleViewImageSnapshot(List<Rect> images) {
        this.images = images == null ? List.of() : List.copyOf(images);
    }

    static CircleViewImageSnapshot empty() { return new CircleViewImageSnapshot(List.of()); }

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
                .thenComparingInt(r -> r.top)
                .thenComparingInt(r -> r.left));
        DiagnosticLog.i(service, "CIRCLE_VIEW_IMAGE", "captured native images=" + out.size()
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

        int count = Math.min(300, safeChildCount(node));
        for (int i = 0; i < count; i++) {
            if (visited[0] >= MAX_NODES || out.size() >= MAX_IMAGES) return;
            AccessibilityNodeInfo child = null;
            try { child = node.getChild(i); } catch (Throwable ignored) {}
            if (child != null) collect(child, display, out, seen, visited, depth + 1);
        }
    }

    private static boolean isImageNode(AccessibilityNodeInfo node) {
        String cls = "";
        String id = "";
        try { if (node.getClassName() != null) cls = node.getClassName().toString(); }
        catch (Throwable ignored) {}
        try { if (node.getViewIdResourceName() != null) id = node.getViewIdResourceName(); }
        catch (Throwable ignored) {}
        String c = cls.toLowerCase(Locale.ROOT);
        String v = id.toLowerCase(Locale.ROOT);
        return c.contains("imageview") || c.contains("imagebutton") || c.contains("iconview")
                || c.endsWith(".image")
                || containsToken(v, "icon") || containsToken(v, "image")
                || containsToken(v, "avatar") || containsToken(v, "thumbnail")
                || containsToken(v, "photo") || containsToken(v, "picture");
    }

    private static boolean containsToken(String value, String token) {
        if (value == null || value.isEmpty()) return false;
        return value.contains("/" + token)
                || value.contains("_" + token)
                || value.contains(token + "_")
                || value.endsWith(token)
                || value.contains(token);
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
        } catch (Throwable ignored) { return false; }
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
}
