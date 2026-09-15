package com.yagay.floatlens;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Single normal-mode Accessibility candidate collector.
 *
 * <p>Visible text means {@link AccessibilityNodeInfo#getText()} only. contentDescription, hint and
 * stateDescription remain semantic labels for non-text Views and never become selectable screen
 * text. Direct selection and the explicit View picker both consume this collector.</p>
 *
 * <p>The collector is interruption-aware because the Direct engine cancels stale tree snapshots.
 * MOVE never traverses the live Accessibility tree; it only hit-tests a prepared snapshot.</p>
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
                    if (window == null) continue;
                    AccessibilityNodeInfo root = null;
                    try { root = window.getRoot(); } catch (Throwable ignored) {}
                    if (root == null || service.getPackageName().equals(nodePackage(root))) continue;
                    collectNode(service, root, screen, 0, count, out);
                    if (count[0] > 6000 || cancelled()) break;
                }
            }

            if (!cancelled()) {
                AccessibilityNodeInfo active = null;
                try { active = service.getRootInActiveWindow(); } catch (Throwable ignored) {}
                if (active != null && !service.getPackageName().equals(nodePackage(active))) {
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
                    AccessibilityNodeInfo root = null;
                    try { root = window.getRoot(); } catch (Throwable ignored) {}
                    if (root == null || service.getPackageName().equals(nodePackage(root))) continue;
                    collectNodeAtPoint(service, root, screen, px, py, 0, count, out);
                    if (count[0] > 2200 || cancelled()) break;
                }
            }
            if (!cancelled()) {
                AccessibilityNodeInfo active = null;
                try { active = service.getRootInActiveWindow(); } catch (Throwable ignored) {}
                if (active != null && !service.getPackageName().equals(nodePackage(active))) {
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
        Rect bounds = clippedBounds(node, screen);
        if (bounds.isEmpty() || !bounds.contains(px, py)) return;
        addCandidate(service, node, bounds, screen, depth, out);

        int children = Math.min(300, safeChildCount(node));
        for (int i = 0; i < children; i++) {
            if (cancelled()) return;
            AccessibilityNodeInfo child = null;
            try { child = node.getChild(i); } catch (Throwable ignored) {}
            if (child != null) collectNodeAtPoint(service, child, screen, px, py,
                    depth + 1, count, out);
        }
    }

    private static void collectNode(LensAccessibilityService service, AccessibilityNodeInfo node,
                                    Rect screen, int depth, int[] count, List<ScreenCandidate> out) {
        if (cancelled() || node == null || depth > 80 || count[0]++ > 6500) return;
        try { if (!node.isVisibleToUser()) return; } catch (Throwable ignored) {}
        Rect bounds = clippedBounds(node, screen);
        if (bounds.isEmpty()) return;
        addCandidate(service, node, bounds, screen, depth, out);

        int children = Math.min(300, safeChildCount(node));
        for (int i = 0; i < children; i++) {
            if (cancelled()) return;
            AccessibilityNodeInfo child = null;
            try { child = node.getChild(i); } catch (Throwable ignored) {}
            if (child != null) collectNode(service, child, screen, depth + 1, count, out);
        }
    }

    private static void addCandidate(LensAccessibilityService service, AccessibilityNodeInfo node,
                                     Rect bounds, Rect screen, int depth,
                                     List<ScreenCandidate> out) {
        String cls = safeClass(node);
        String id = safeId(node);
        String pkg = nodePackage(node);
        CharSequence visible = nonBlank(safeText(node));
        CharSequence semantic = firstNonBlank(
                safeContentDescription(node), safeHint(node), safeStateDescription(node));
        String visibleText = visible == null ? "" : visible.toString().trim();
        String semanticLabel = semantic == null ? "" : semantic.toString().trim();
        boolean image = isImageCandidate(service, bounds, cls, id);
        boolean fullscreen = isFullscreenLike(bounds, screen);
        boolean genericView = isGenericViewCandidate(service, bounds, cls, id, fullscreen);

        ScreenCandidate.Type type;
        boolean iconLike = false;
        if (!visibleText.isEmpty()) {
            type = ScreenCandidate.Type.TEXT;
        } else if (image) {
            type = ScreenCandidate.Type.NON_TEXT;
            iconLike = true;
        } else if (genericView) {
            type = ScreenCandidate.Type.VIEW;
        } else if (fullscreen) {
            type = ScreenCandidate.Type.ROOT;
        } else {
            return;
        }

        out.add(new ScreenCandidate(bounds, type, ScreenCandidate.Source.ACCESSIBILITY,
                visibleText, semanticLabel, cls, id, pkg, depth, fullscreen,
                safeClickable(node), safeEditable(node), safeFocusable(node), iconLike));
    }

    private static Rect clippedBounds(AccessibilityNodeInfo node, Rect screen) {
        Rect bounds = new Rect();
        try { node.getBoundsInScreen(bounds); } catch (Throwable t) { return new Rect(); }
        if (bounds.isEmpty()) return new Rect();
        if (screen != null && !screen.isEmpty() && !bounds.intersect(screen)) return new Rect();
        return bounds;
    }

    private static boolean cancelled() { return Thread.currentThread().isInterrupted(); }

    private static boolean isImageCandidate(LensAccessibilityService service,
                                            Rect r, String cls, String id) {
        if (r == null || r.isEmpty()) return false;
        float density = service.getResources().getDisplayMetrics().density;
        int min = Math.round(12f * density);
        if (r.width() < min || r.height() < min) return false;

        String c = cls == null ? "" : cls.toLowerCase(Locale.ROOT);
        String v = id == null ? "" : id.toLowerCase(Locale.ROOT);
        boolean imageClass = c.equals("android.widget.imageview")
                || c.equals("android.widget.image")
                || c.contains("imageview")
                || c.contains("imagebutton")
                || c.contains("iconview")
                || c.endsWith(".image");
        boolean imageId = containsToken(v, "icon")
                || containsToken(v, "image")
                || containsToken(v, "avatar")
                || containsToken(v, "thumbnail")
                || containsToken(v, "thumb")
                || containsToken(v, "photo")
                || containsToken(v, "picture");
        return imageClass || imageId;
    }

    private static boolean isGenericViewCandidate(LensAccessibilityService service,
                                                   Rect r, String cls, String id,
                                                   boolean fullscreen) {
        if (r == null || r.isEmpty()) return false;
        float density = service.getResources().getDisplayMetrics().density;
        int min = Math.max(1, Math.round(20f * density));
        if (r.width() < min || r.height() < min) return false;
        if (id != null && !id.isBlank()) return true;
        if (fullscreen) return true;
        String c = cls == null ? "" : cls.toLowerCase(Locale.ROOT);
        return c.equals("android.view.view")
                || c.contains("webview")
                || c.contains("surfaceview")
                || c.contains("textureview");
    }

    private static boolean isFullscreenLike(Rect r, Rect screen) {
        if (r == null || r.isEmpty() || screen == null || screen.isEmpty()) return false;
        long area = (long) r.width() * r.height();
        long screenArea = (long) screen.width() * screen.height();
        if (screenArea <= 0) return false;
        return area >= screenArea * 88L / 100L
                || (r.width() >= screen.width() * 94L / 100L
                && r.height() >= screen.height() * 90L / 100L);
    }

    private static boolean containsToken(String value, String token) {
        if (value == null || value.isEmpty()) return false;
        return value.contains("/" + token)
                || value.contains("_" + token)
                || value.contains(token + "_")
                || value.endsWith(token)
                || value.contains(token);
    }

    private static String nodePackage(AccessibilityNodeInfo n) {
        try { return n != null && n.getPackageName() != null ? n.getPackageName().toString() : ""; }
        catch (Throwable t) { return ""; }
    }

    private static CharSequence nonBlank(CharSequence value) {
        return value == null || value.toString().trim().isEmpty() ? null : value;
    }

    private static CharSequence firstNonBlank(CharSequence... values) {
        if (values == null) return null;
        for (CharSequence value : values) {
            CharSequence present = nonBlank(value);
            if (present != null) return present;
        }
        return null;
    }

    private static CharSequence safeText(AccessibilityNodeInfo n) { try { return n.getText(); } catch (Throwable t) { return null; } }
    private static CharSequence safeContentDescription(AccessibilityNodeInfo n) { try { return n.getContentDescription(); } catch (Throwable t) { return null; } }
    private static CharSequence safeHint(AccessibilityNodeInfo n) { try { return n.getHintText(); } catch (Throwable t) { return null; } }
    private static CharSequence safeStateDescription(AccessibilityNodeInfo n) { try { return n.getStateDescription(); } catch (Throwable t) { return null; } }
    private static String safeClass(AccessibilityNodeInfo n) { try { return n.getClassName() == null ? "" : n.getClassName().toString(); } catch (Throwable t) { return ""; } }
    private static String safeId(AccessibilityNodeInfo n) { try { return n.getViewIdResourceName() == null ? "" : n.getViewIdResourceName(); } catch (Throwable t) { return ""; } }
    private static boolean safeEditable(AccessibilityNodeInfo n) { try { return n.isEditable(); } catch (Throwable t) { return false; } }
    private static boolean safeClickable(AccessibilityNodeInfo n) { try { return n.isClickable() || n.isLongClickable(); } catch (Throwable t) { return false; } }
    private static boolean safeFocusable(AccessibilityNodeInfo n) { try { return n.isFocusable(); } catch (Throwable t) { return false; } }
    private static int safeChildCount(AccessibilityNodeInfo n) { try { return n.getChildCount(); } catch (Throwable t) { return 0; } }
}
