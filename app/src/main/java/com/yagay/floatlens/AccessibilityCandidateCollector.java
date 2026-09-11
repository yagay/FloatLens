package com.yagay.floatlens;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * FV-style accessibility candidate collector.
 *
 * Candidate classes:
 *  1) TEXT: node with visible text/contentDescription/hint/stateDescription;
 *  2) NON_TEXT: explicit image/icon-like node;
 *  3) VIEW: ordinary View rectangle exposed by accessibility, especially nodes with a resource id,
 *     WebView/SurfaceView/TextureView/android.view.View, and whole-page Views;
 *  4) ROOT: last-resort near-fullscreen fallback when no better View identity is exposed.
 *
 * This matches FV more closely than the old text/image-only collector: an ordinary View does not
 * need text or image semantics to be selectable.
 */
public final class AccessibilityCandidateCollector {
    private AccessibilityCandidateCollector() {}

    public static List<ScreenCandidate> collect(LensAccessibilityService service) {
        ArrayList<ScreenCandidate> out = new ArrayList<>();
        if (service == null) return out;
        Rect screen = service.screenBounds();
        int[] count = {0};

        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows != null) {
                for (int wi = 0; wi < windows.size(); wi++) {
                    AccessibilityWindowInfo w = windows.get(wi);
                    if (w == null) continue;
                    AccessibilityNodeInfo root = null;
                    try { root = w.getRoot(); } catch (Throwable ignored) {}
                    if (root == null) continue;
                    String pkg = nodePackage(root);
                    if (service.getPackageName().equals(pkg)) continue;
                    collectNode(service, root, screen, 0, count, out);
                    if (count[0] > 6000) break;
                }
            }

            AccessibilityNodeInfo active = null;
            try { active = service.getRootInActiveWindow(); } catch (Throwable ignored) {}
            if (active != null && !service.getPackageName().equals(nodePackage(active))) {
                collectNode(service, active, screen, 0, count, out);
            }
        } catch (Throwable t) {
            DiagnosticLog.i(service, "FV_TREE", "collect failed=" + t);
        }

        List<ScreenCandidate> filtered = CandidateGeometryFilter.filter(out, screen);
        DiagnosticLog.i(service, "FV_TREE", "text/image/view/root raw=" + out.size()
                + " filtered=" + filtered.size());
        return filtered;
    }

    public static List<ScreenCandidate> collectAtPoint(LensAccessibilityService service, float x, float y) {
        ArrayList<ScreenCandidate> out = new ArrayList<>();
        if (service == null) return out;
        Rect screen = service.screenBounds();
        int px = Math.round(x), py = Math.round(y);
        int[] count = {0};
        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows != null) {
                for (int wi = 0; wi < windows.size(); wi++) {
                    AccessibilityWindowInfo w = windows.get(wi);
                    if (w == null) continue;
                    Rect wr = new Rect();
                    try { w.getBoundsInScreen(wr); } catch (Throwable ignored) {}
                    if (!wr.isEmpty() && !wr.contains(px, py)) continue;
                    AccessibilityNodeInfo root = null;
                    try { root = w.getRoot(); } catch (Throwable ignored) {}
                    if (root == null || service.getPackageName().equals(nodePackage(root))) continue;
                    collectNodeAtPoint(service, root, screen, px, py, 0, count, out);
                    if (count[0] > 2200) break;
                }
            }
            AccessibilityNodeInfo active = null;
            try { active = service.getRootInActiveWindow(); } catch (Throwable ignored) {}
            if (active != null && !service.getPackageName().equals(nodePackage(active))) {
                collectNodeAtPoint(service, active, screen, px, py, 0, count, out);
            }
        } catch (Throwable t) {
            DiagnosticLog.i(service, "FV_TREE", "point collect failed=" + t);
        }
        return CandidateGeometryFilter.filter(out, screen);
    }

    private static void collectNodeAtPoint(LensAccessibilityService service, AccessibilityNodeInfo n,
                                           Rect screen, int px, int py, int depth, int[] count,
                                           List<ScreenCandidate> out) {
        if (n == null || depth > 80 || count[0]++ > 2200) return;
        try { if (!n.isVisibleToUser()) return; } catch (Throwable ignored) {}
        Rect r = new Rect();
        try { n.getBoundsInScreen(r); } catch (Throwable t) { return; }
        if (r.isEmpty()) return;
        Rect clipped = new Rect(r);
        if (screen != null && !screen.isEmpty() && !clipped.intersect(screen)) return;
        if (!clipped.contains(px, py)) return;

        String cls = safeClass(n);
        String id = safeId(n);
        String pkg = nodePackage(n);
        CharSequence ownText = firstNonBlank(
                safeText(n), safeContentDescription(n), safeHint(n), safeStateDescription(n));
        boolean image = isImageCandidate(service, n, clipped, cls, id);
        boolean fullscreen = isFullscreenLike(clipped, screen);
        boolean genericView = isGenericViewCandidate(service, clipped, cls, id, fullscreen);

        if (image) {
            out.add(new ScreenCandidate(clipped, ScreenCandidate.Type.NON_TEXT,
                    ScreenCandidate.Source.ACCESSIBILITY,
                    ownText == null ? "" : ownText.toString().trim(), cls, id, pkg, depth, false,
                    safeClickable(n), safeEditable(n), safeFocusable(n), true));
        } else if (ownText != null) {
            out.add(new ScreenCandidate(clipped, ScreenCandidate.Type.TEXT,
                    ScreenCandidate.Source.ACCESSIBILITY, ownText.toString().trim(), cls, id, pkg,
                    depth, false, safeClickable(n), safeEditable(n), safeFocusable(n), false));
        } else if (genericView) {
            out.add(new ScreenCandidate(clipped, ScreenCandidate.Type.VIEW,
                    ScreenCandidate.Source.ACCESSIBILITY, "", cls, id, pkg, depth, fullscreen,
                    safeClickable(n), safeEditable(n), safeFocusable(n), false));
        } else if (fullscreen) {
            out.add(new ScreenCandidate(clipped, ScreenCandidate.Type.ROOT,
                    ScreenCandidate.Source.ACCESSIBILITY, "", cls, id, pkg, depth, true,
                    safeClickable(n), safeEditable(n), safeFocusable(n), false));
        }

        int children = Math.min(300, safeChildCount(n));
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo child = null;
            try { child = n.getChild(i); } catch (Throwable ignored) {}
            if (child != null) collectNodeAtPoint(service, child, screen, px, py,
                    depth + 1, count, out);
        }
    }

    private static void collectNode(LensAccessibilityService service, AccessibilityNodeInfo n,
                                    Rect screen, int depth, int[] count, List<ScreenCandidate> out) {
        if (n == null || depth > 80 || count[0]++ > 6500) return;
        try { if (!n.isVisibleToUser()) return; } catch (Throwable ignored) {}

        Rect r = new Rect();
        try { n.getBoundsInScreen(r); } catch (Throwable t) { return; }
        if (r.isEmpty()) return;
        Rect clipped = new Rect(r);
        if (screen != null && !screen.isEmpty() && !clipped.intersect(screen)) return;

        String cls = safeClass(n);
        String id = safeId(n);
        String pkg = nodePackage(n);
        CharSequence ownText = firstNonBlank(
                safeText(n), safeContentDescription(n), safeHint(n), safeStateDescription(n));

        boolean image = isImageCandidate(service, n, clipped, cls, id);
        boolean fullscreen = isFullscreenLike(clipped, screen);
        boolean genericView = isGenericViewCandidate(service, clipped, cls, id, fullscreen);

        if (image) {
            out.add(new ScreenCandidate(clipped, ScreenCandidate.Type.NON_TEXT,
                    ScreenCandidate.Source.ACCESSIBILITY,
                    ownText == null ? "" : ownText.toString().trim(),
                    cls, id, pkg, depth, false,
                    safeClickable(n), safeEditable(n), safeFocusable(n), true));
        } else if (ownText != null) {
            out.add(new ScreenCandidate(clipped, ScreenCandidate.Type.TEXT,
                    ScreenCandidate.Source.ACCESSIBILITY,
                    ownText.toString().trim(), cls, id, pkg, depth, false,
                    safeClickable(n), safeEditable(n), safeFocusable(n), false));
        } else if (genericView) {
            out.add(new ScreenCandidate(clipped, ScreenCandidate.Type.VIEW,
                    ScreenCandidate.Source.ACCESSIBILITY,
                    "", cls, id, pkg, depth, fullscreen,
                    safeClickable(n), safeEditable(n), safeFocusable(n), false));
        } else if (fullscreen) {
            out.add(new ScreenCandidate(clipped, ScreenCandidate.Type.ROOT,
                    ScreenCandidate.Source.ACCESSIBILITY,
                    "", cls, id, pkg, depth, true,
                    safeClickable(n), safeEditable(n), safeFocusable(n), false));
        }

        int children = Math.min(300, safeChildCount(n));
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo child = null;
            try { child = n.getChild(i); } catch (Throwable ignored) {}
            if (child != null) collectNode(service, child, screen, depth + 1, count, out);
        }
    }

    private static boolean isImageCandidate(LensAccessibilityService service,
                                            AccessibilityNodeInfo n,
                                            Rect r,
                                            String cls,
                                            String id) {
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

    /** FV keeps ordinary View rectangles instead of requiring text/image semantics. */
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
    private static String safeClass(AccessibilityNodeInfo n) { try { return n.getClassName() == null ? "" : n.getClassName().toString(); } catch (Throwable t) { return ""; } }
    private static String safeId(AccessibilityNodeInfo n) { try { return n.getViewIdResourceName() == null ? "" : n.getViewIdResourceName(); } catch (Throwable t) { return ""; } }
    private static boolean safeEditable(AccessibilityNodeInfo n) { try { return n.isEditable(); } catch (Throwable t) { return false; } }
    private static boolean safeClickable(AccessibilityNodeInfo n) { try { return n.isClickable() || n.isLongClickable(); } catch (Throwable t) { return false; } }
    private static boolean safeFocusable(AccessibilityNodeInfo n) { try { return n.isFocusable(); } catch (Throwable t) { return false; } }
    private static int safeChildCount(AccessibilityNodeInfo n) { try { return n.getChildCount(); } catch (Throwable t) { return 0; } }
}
