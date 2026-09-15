package com.yagay.floatlens;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Single normal-mode Accessibility candidate collector.
 *
 * <p>Visible text, semantic labels, image detection and generic View classification come from
 * {@link AccessibilityNodeSemantics}. Direct selection and the explicit View picker both consume
 * this collector. MOVE never traverses the live tree; it only hit-tests a prepared snapshot.</p>
 */
public final class AccessibilityCandidateCollector {
    private AccessibilityCandidateCollector() {}

    public static List<ScreenCandidate> collect(LensAccessibilityService service) {
        ArrayList<ScreenCandidate> out = new ArrayList<>();
        if (service == null || cancelled()) return out;
        Rect screen = service.screenBounds();
        int[] count = {0};
        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    if (cancelled()) break;
                    AccessibilityNodeInfo root = root(window);
                    if (root == null || ownPackage(service, root)) continue;
                    collectNode(service, root, screen, 0, count, out);
                    if (count[0] > 6000 || cancelled()) break;
                }
            }
            if (!cancelled()) {
                AccessibilityNodeInfo active = activeRoot(service);
                if (active != null && !ownPackage(service, active)) {
                    collectNode(service, active, screen, 0, count, out);
                }
            }
        } catch (Throwable t) {
            if (!cancelled()) DiagnosticLog.i(service, "FL_TREE", "collect failed=" + t);
        }
        if (cancelled()) {
            DiagnosticLog.i(service, "FL_TREE", "collect cancelled nodes=" + count[0]
                    + " partial=" + out.size());
            return new ArrayList<>();
        }
        List<ScreenCandidate> filtered = CandidateGeometryFilter.filter(out, screen);
        DiagnosticLog.i(service, "FL_TREE", "visibleText/image/view/root raw=" + out.size()
                + " filtered=" + filtered.size());
        return filtered;
    }

    public static List<ScreenCandidate> collectAtPoint(LensAccessibilityService service,
                                                        float x, float y) {
        ArrayList<ScreenCandidate> out = new ArrayList<>();
        if (service == null || cancelled()) return out;
        Rect screen = service.screenBounds();
        int px = Math.round(x), py = Math.round(y);
        int[] count = {0};
        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    if (cancelled()) break;
                    if (window == null) continue;
                    Rect wr = new Rect();
                    try { window.getBoundsInScreen(wr); } catch (Throwable ignored) {}
                    if (!wr.isEmpty() && !wr.contains(px, py)) continue;
                    AccessibilityNodeInfo root = root(window);
                    if (root == null || ownPackage(service, root)) continue;
                    collectNodeAtPoint(service, root, screen, px, py, 0, count, out);
                    if (count[0] > 2200 || cancelled()) break;
                }
            }
            if (!cancelled()) {
                AccessibilityNodeInfo active = activeRoot(service);
                if (active != null && !ownPackage(service, active)) {
                    collectNodeAtPoint(service, active, screen, px, py, 0, count, out);
                }
            }
        } catch (Throwable t) {
            if (!cancelled()) DiagnosticLog.i(service, "FL_TREE", "point collect failed=" + t);
        }
        if (cancelled()) return new ArrayList<>();
        return CandidateGeometryFilter.filter(out, screen);
    }

    private static void collectNodeAtPoint(LensAccessibilityService service, AccessibilityNodeInfo node,
                                           Rect screen, int px, int py, int depth, int[] count,
                                           List<ScreenCandidate> out) {
        if (cancelled() || node == null || depth > 80 || count[0]++ > 2200) return;
        try { if (!node.isVisibleToUser()) return; } catch (Throwable ignored) {}
        Rect bounds = AccessibilityNodeSemantics.clippedBounds(node, screen);
        if (bounds.isEmpty() || !bounds.contains(px, py)) return;
        addCandidate(service, node, bounds, screen, depth, out);
        int children = Math.min(300, AccessibilityNodeSemantics.childCount(node));
        for (int i = 0; i < children; i++) {
            if (cancelled()) return;
            AccessibilityNodeInfo child = child(node, i);
            if (child != null) collectNodeAtPoint(service, child, screen, px, py,
                    depth + 1, count, out);
        }
    }

    private static void collectNode(LensAccessibilityService service, AccessibilityNodeInfo node,
                                    Rect screen, int depth, int[] count, List<ScreenCandidate> out) {
        if (cancelled() || node == null || depth > 80 || count[0]++ > 6500) return;
        try { if (!node.isVisibleToUser()) return; } catch (Throwable ignored) {}
        Rect bounds = AccessibilityNodeSemantics.clippedBounds(node, screen);
        if (bounds.isEmpty()) return;
        addCandidate(service, node, bounds, screen, depth, out);
        int children = Math.min(300, AccessibilityNodeSemantics.childCount(node));
        for (int i = 0; i < children; i++) {
            if (cancelled()) return;
            AccessibilityNodeInfo child = child(node, i);
            if (child != null) collectNode(service, child, screen, depth + 1, count, out);
        }
    }

    private static void addCandidate(LensAccessibilityService service, AccessibilityNodeInfo node,
                                     Rect bounds, Rect screen, int depth,
                                     List<ScreenCandidate> out) {
        String visibleText = AccessibilityNodeSemantics.visibleText(node);
        String semanticLabel = AccessibilityNodeSemantics.semanticLabel(node);
        boolean fullscreen = AccessibilityNodeSemantics.isFullscreenLike(bounds, screen);
        boolean image = AccessibilityNodeSemantics.isImage(service, node, bounds);
        boolean genericView = AccessibilityNodeSemantics.isGenericView(
                service, node, bounds, fullscreen);

        ScreenCandidate.Type type;
        boolean iconLike = false;
        if (!visibleText.isEmpty()) type = ScreenCandidate.Type.TEXT;
        else if (image) { type = ScreenCandidate.Type.NON_TEXT; iconLike = true; }
        else if (genericView) type = ScreenCandidate.Type.VIEW;
        else if (fullscreen) type = ScreenCandidate.Type.ROOT;
        else return;

        out.add(new ScreenCandidate(bounds, type, ScreenCandidate.Source.ACCESSIBILITY,
                visibleText, semanticLabel,
                AccessibilityNodeSemantics.className(node),
                AccessibilityNodeSemantics.viewId(node),
                AccessibilityNodeSemantics.packageName(node),
                depth, fullscreen,
                AccessibilityNodeSemantics.clickable(node),
                AccessibilityNodeSemantics.editable(node),
                AccessibilityNodeSemantics.focusable(node), iconLike));
    }

    private static boolean cancelled() { return Thread.currentThread().isInterrupted(); }

    private static AccessibilityNodeInfo root(AccessibilityWindowInfo window) {
        if (window == null) return null;
        try { return window.getRoot(); } catch (Throwable ignored) { return null; }
    }

    private static AccessibilityNodeInfo activeRoot(LensAccessibilityService service) {
        try { return service == null ? null : service.getRootInActiveWindow(); }
        catch (Throwable ignored) { return null; }
    }

    private static AccessibilityNodeInfo child(AccessibilityNodeInfo node, int index) {
        try { return node == null ? null : node.getChild(index); }
        catch (Throwable ignored) { return null; }
    }

    private static boolean ownPackage(LensAccessibilityService service, AccessibilityNodeInfo node) {
        return service != null && service.getPackageName().equals(
                AccessibilityNodeSemantics.packageName(node));
    }
}
