package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Locale;

/**
 * One normal-mode interpretation of Accessibility nodes shared by Direct, View picker and Circle.
 * Visible text and semantic labels are deliberately different concepts.
 */
final class AccessibilityNodeSemantics {
    private AccessibilityNodeSemantics() {}

    static String visibleText(AccessibilityNodeInfo node) {
        return trim(safeText(node));
    }

    static String semanticLabel(AccessibilityNodeInfo node) {
        String value = trim(safeContentDescription(node));
        if (!value.isEmpty()) return value;
        value = trim(safeHint(node));
        if (!value.isEmpty()) return value;
        return trim(safeStateDescription(node));
    }

    static String className(AccessibilityNodeInfo node) {
        try { return node != null && node.getClassName() != null ? node.getClassName().toString() : ""; }
        catch (Throwable ignored) { return ""; }
    }

    static String viewId(AccessibilityNodeInfo node) {
        try { return node != null && node.getViewIdResourceName() != null ? node.getViewIdResourceName() : ""; }
        catch (Throwable ignored) { return ""; }
    }

    static String packageName(AccessibilityNodeInfo node) {
        try { return node != null && node.getPackageName() != null ? node.getPackageName().toString() : ""; }
        catch (Throwable ignored) { return ""; }
    }

    static boolean clickable(AccessibilityNodeInfo node) {
        try { return node != null && (node.isClickable() || node.isLongClickable()); }
        catch (Throwable ignored) { return false; }
    }

    static boolean editable(AccessibilityNodeInfo node) {
        try { return node != null && node.isEditable(); }
        catch (Throwable ignored) { return false; }
    }

    static boolean focusable(AccessibilityNodeInfo node) {
        try { return node != null && node.isFocusable(); }
        catch (Throwable ignored) { return false; }
    }

    static int childCount(AccessibilityNodeInfo node) {
        try { return node == null ? 0 : node.getChildCount(); }
        catch (Throwable ignored) { return 0; }
    }

    static Rect clippedBounds(AccessibilityNodeInfo node, Rect screen) {
        Rect bounds = new Rect();
        try { if (node != null) node.getBoundsInScreen(bounds); }
        catch (Throwable ignored) { return new Rect(); }
        if (bounds.isEmpty()) return new Rect();
        if (screen != null && !screen.isEmpty() && !bounds.intersect(screen)) return new Rect();
        return bounds;
    }

    static boolean isImage(Context context, AccessibilityNodeInfo node, Rect bounds) {
        if (context == null || bounds == null || bounds.isEmpty()) return false;
        float density = context.getResources().getDisplayMetrics().density;
        int min = Math.round(12f * density);
        if (bounds.width() < min || bounds.height() < min) return false;

        String c = className(node).toLowerCase(Locale.ROOT);
        String v = viewId(node).toLowerCase(Locale.ROOT);
        return c.equals("android.widget.imageview")
                || c.equals("android.widget.image")
                || c.contains("imageview")
                || c.contains("imagebutton")
                || c.contains("iconview")
                || c.endsWith(".image")
                || containsToken(v, "icon")
                || containsToken(v, "image")
                || containsToken(v, "avatar")
                || containsToken(v, "thumbnail")
                || containsToken(v, "thumb")
                || containsToken(v, "photo")
                || containsToken(v, "picture");
    }

    static boolean isGenericView(Context context, AccessibilityNodeInfo node,
                                 Rect bounds, boolean fullscreen) {
        if (context == null || bounds == null || bounds.isEmpty()) return false;
        float density = context.getResources().getDisplayMetrics().density;
        int min = Math.max(1, Math.round(20f * density));
        if (bounds.width() < min || bounds.height() < min) return false;
        if (!viewId(node).isBlank()) return true;
        if (fullscreen) return true;
        String c = className(node).toLowerCase(Locale.ROOT);
        return c.equals("android.view.view")
                || c.contains("webview")
                || c.contains("surfaceview")
                || c.contains("textureview");
    }

    static boolean isFullscreenLike(Rect bounds, Rect screen) {
        if (bounds == null || bounds.isEmpty() || screen == null || screen.isEmpty()) return false;
        long area = (long) bounds.width() * bounds.height();
        long screenArea = (long) screen.width() * screen.height();
        if (screenArea <= 0) return false;
        return area >= screenArea * 88L / 100L
                || (bounds.width() >= screen.width() * 94L / 100L
                && bounds.height() >= screen.height() * 90L / 100L);
    }

    private static boolean containsToken(String value, String token) {
        if (value == null || value.isEmpty()) return false;
        return value.contains("/" + token)
                || value.contains("_" + token)
                || value.contains(token + "_")
                || value.endsWith(token)
                || value.contains(token);
    }

    private static CharSequence safeText(AccessibilityNodeInfo node) {
        try { return node == null ? null : node.getText(); } catch (Throwable ignored) { return null; }
    }
    private static CharSequence safeContentDescription(AccessibilityNodeInfo node) {
        try { return node == null ? null : node.getContentDescription(); } catch (Throwable ignored) { return null; }
    }
    private static CharSequence safeHint(AccessibilityNodeInfo node) {
        try { return node == null ? null : node.getHintText(); } catch (Throwable ignored) { return null; }
    }
    private static CharSequence safeStateDescription(AccessibilityNodeInfo node) {
        try { return node == null ? null : node.getStateDescription(); } catch (Throwable ignored) { return null; }
    }
    private static String trim(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }
}
