package com.yagay.floatlens;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Clean-room collector modelled from the observable FV s0/R pipeline.
 *
 * It walks the complete visible Accessibility tree but only emits actual selection candidates:
 * Text/Edit, NonText, and near-screen Root fallbacks. Plain layout/container nodes are traversed but
 * are not themselves selectable, preventing deep empty wrappers from stealing the pointer.
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
        DiagnosticLog.i(service, "FV_TREE", "raw=" + out.size() + " filtered=" + filtered.size());
        return filtered;
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

        CharSequence ownText = firstNonBlank(
                safeText(n), safeContentDescription(n), safeHint(n), safeStateDescription(n));
        boolean editable = safeEditable(n);
        boolean clickable = safeClickable(n);
        boolean focusable = safeFocusable(n);
        String cls = safeClass(n);
        String id = safeId(n);
        String pkg = nodePackage(n);
        boolean fullscreen = isFullscreenLike(clipped, screen);
        boolean explicitImage = isExplicitImageClass(cls);
        boolean nonText = !editable && isNonTextCandidate(service, n, clipped, ownText, explicitImage);

        ScreenCandidate.Type type = null;
        if (fullscreen) type = ScreenCandidate.Type.ROOT;
        else if (editable || ownText != null) type = ScreenCandidate.Type.TEXT;
        else if (nonText) type = ScreenCandidate.Type.NON_TEXT;

        if (type != null) {
            String text = ownText == null ? "" : ownText.toString().trim();
            out.add(new ScreenCandidate(clipped, type, ScreenCandidate.Source.ACCESSIBILITY,
                    text, cls, id, pkg, depth, fullscreen,
                    clickable, editable, focusable, explicitImage || nonText));
        }

        int children = Math.min(300, safeChildCount(n));
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo child = null;
            try { child = n.getChild(i); } catch (Throwable ignored) {}
            if (child != null) collectNode(service, child, screen, depth + 1, count, out);
        }
    }

    private static boolean isNonTextCandidate(LensAccessibilityService service, AccessibilityNodeInfo n,
                                              Rect r, CharSequence ownText, boolean explicitImage) {
        if (ownText != null) return false;
        float density = service.getResources().getDisplayMetrics().density;
        int min = Math.round(20f * density);
        if (r.width() < min || r.height() < min) return false;
        if (explicitImage) return true;

        int children = safeChildCount(n);
        if (children == 0 && (safeClickable(n) || safeFocusable(n))) return true;

        String id = safeId(n).toLowerCase(Locale.ROOT);
        return id.contains("icon") || id.contains("image") || id.contains("avatar")
                || id.contains("thumb") || id.contains("photo");
    }

    private static boolean isExplicitImageClass(String cls) {
        String c = cls == null ? "" : cls.toLowerCase(Locale.ROOT);
        return c.equals("android.widget.imageview") || c.equals("android.widget.image")
                || c.contains("imageview") || c.contains("imagebutton") || c.contains("iconview");
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
