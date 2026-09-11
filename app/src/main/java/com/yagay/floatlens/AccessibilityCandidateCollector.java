package com.yagay.floatlens;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Strict FV-style candidate collector for FloatLens.
 *
 * User-visible candidates are deliberately narrow:
 *  1) TEXT: a node with its own visible text/contentDescription/hint/stateDescription;
 *  2) NON_TEXT: a node that is explicitly image/icon-like by class or resource id;
 *  3) ROOT: a near-fullscreen View used only as the final fallback when no TEXT/NON_TEXT target
 *     exists at the current point.
 *
 * Ordinary layout/container/clickable/focusable nodes remain traversal-only. This keeps useless
 * controls out of selection while restoring the fullscreen-View fallback present in FV-like flows.
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

            // Launcher implementations sometimes expose a richer node tree through the active root
            // when an overlay exists. It is collected as a second tree source and deduped below.
            AccessibilityNodeInfo active = null;
            try { active = service.getRootInActiveWindow(); } catch (Throwable ignored) {}
            if (active != null && !service.getPackageName().equals(nodePackage(active))) {
                collectNode(service, active, screen, 0, count, out);
            }
        } catch (Throwable t) {
            DiagnosticLog.i(service, "FV_TREE", "collect failed=" + t);
        }

        List<ScreenCandidate> filtered = CandidateGeometryFilter.filter(out, screen);
        DiagnosticLog.i(service, "FV_TREE", "text/image/root raw=" + out.size()
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
        if (image) {
            out.add(new ScreenCandidate(clipped, ScreenCandidate.Type.NON_TEXT,
                    ScreenCandidate.Source.ACCESSIBILITY,
                    ownText == null ? "" : ownText.toString().trim(), cls, id, pkg, depth, false,
                    safeClickable(n), safeEditable(n), safeFocusable(n), true));
        } else if (ownText != null) {
            out.add(new ScreenCandidate(clipped, ScreenCandidate.Type.TEXT,
                    ScreenCandidate.Source.ACCESSIBILITY, ownText.toString().trim(), cls, id, pkg,
                    depth, false, safeClickable(n), safeEditable(n), safeFocusable(n), false));
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

        // A real image/text target always keeps its normal type, even when it happens to fill the
        // screen. ROOT is only for otherwise-uninteresting near-fullscreen Views.
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
        } else if (fullscreen) {
            out.add(new ScreenCandidate(clipped, ScreenCandidate.Type.ROOT,
                    ScreenCandidate.Source.ACCESSIBILITY,
                    "", cls, id, pkg, depth, true,
                    safeClickable(n), safeEditable(n), safeFocusable(n), false));
        }

        // Every ordinary node remains traversal-only. ROOT above is the sole exception and is
        // deliberately deferred by ScreenSelectionModel until no TEXT/NON_TEXT candidate matches.
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
