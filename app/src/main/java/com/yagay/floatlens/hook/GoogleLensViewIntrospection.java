package com.yagay.floatlens.hook;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.Locale;

/** Pure Google Lens View/window classification and diagnostic description helpers. */
final class GoogleLensViewIntrospection {
    static final class Dump {
        final String text;
        final int nodes;
        Dump(String text, int nodes) {
            this.text = text == null ? "" : text;
            this.nodes = Math.max(0, nodes);
        }
    }

    static Dump dumpInteresting(View root) {
        StringBuilder out = new StringBuilder();
        int[] count = {0};
        collect(root, 0, out, count);
        return new Dump(trim(out.toString(), 6500), count[0]);
    }

    private static void collect(View view, int depth, StringBuilder out, int[] count) {
        if (view == null || count[0] >= 56 || out.length() >= 6200) return;
        if ((view.getVisibility() == View.VISIBLE || isShellView(view) || isChromeView(view))
                && interesting(view)) {
            out.append("\n").append(depth).append(":").append(describeView(view, false));
            count[0]++;
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                collect(group.getChildAt(i), depth + 1, out, count);
                if (count[0] >= 56 || out.length() >= 6200) break;
            }
        }
    }

    static boolean isShellView(View view) {
        if (view == null) return false;
        String cls = view.getClass().getName();
        return GoogleLens1758Profile.INFO_PANEL_VIEW.equals(cls)
                || GoogleLens1758Profile.ACTION_MENU_VIEW.equals(cls);
    }

    static boolean isChromeView(View view) {
        if (view == null) return false;
        if (GoogleLens1758Profile.OMNIBOX_VIEW.equals(view.getClass().getName())) return true;
        String id = resourceName(view);
        return id.endsWith(":id/lens_overlay_back_button")
                || id.endsWith(":id/lens_overflow_menu_button")
                || id.endsWith(":id/lens_overlay_history_button")
                || id.endsWith(":id/lens_product_lockup_view");
    }

    static String chromeLabel(View view) {
        if (view == null) return "null";
        if (GoogleLens1758Profile.OMNIBOX_VIEW.equals(view.getClass().getName())) {
            return "OmniBoxView";
        }
        return resourceName(view);
    }

    private static boolean interesting(View view) {
        if (view == null) return false;
        if (GoogleLens1758Profile.FROZEN_IMAGE_VIEW.equals(view.getClass().getName())) return true;
        CharSequence text = view instanceof TextView tv ? tv.getText() : null;
        CharSequence desc = view.getContentDescription();
        String cls = view.getClass().getName().toLowerCase(Locale.ROOT);
        return (text != null && !text.toString().isBlank())
                || (desc != null && !desc.toString().isBlank())
                || view.isClickable()
                || cls.contains("menu")
                || cls.contains("toolbar")
                || cls.contains("popup")
                || cls.contains("chip")
                || cls.contains("button")
                || cls.contains("compose");
    }

    static String describeView(View view, boolean includeChildren) {
        if (view == null) return "view=null";
        int[] loc = new int[2];
        try { view.getLocationOnScreen(loc); } catch (Throwable ignored) { }
        String id = resourceName(view);
        String text = "";
        if (view instanceof TextView tv && tv.getText() != null) {
            text = trim(tv.getText().toString(), 180);
        }
        String desc = view.getContentDescription() == null
                ? "" : trim(view.getContentDescription().toString(), 180);
        ViewParent parent = view.getParent();
        return "class=" + view.getClass().getName()
                + " id=" + id
                + " visibility=" + visibilityName(view.getVisibility())
                + " shown=" + view.isShown()
                + " text=" + quote(text, 180)
                + " desc=" + quote(desc, 180)
                + " xy=" + loc[0] + "," + loc[1]
                + " wh=" + view.getWidth() + "x" + view.getHeight()
                + " alpha=" + view.getAlpha()
                + " scale=" + view.getScaleX() + "," + view.getScaleY()
                + " translation=" + view.getTranslationX() + "," + view.getTranslationY()
                + " clickable=" + view.isClickable()
                + " enabled=" + view.isEnabled()
                + " parent=" + (parent == null ? "null" : parent.getClass().getName())
                + (includeChildren && view instanceof ViewGroup group
                ? " children=" + group.getChildCount() : "");
    }

    static String describeWindowLayoutParams(Object raw) {
        if (!(raw instanceof WindowManager.LayoutParams lp)) {
            return raw == null ? "null" : raw.getClass().getName();
        }
        CharSequence title = lp.getTitle();
        return "type=" + lp.type
                + " title=" + quote(title == null ? "" : title.toString(), 180)
                + " flags=0x" + Integer.toHexString(lp.flags)
                + " gravity=" + lp.gravity
                + " xy=" + lp.x + "," + lp.y
                + " wh=" + lp.width + "x" + lp.height;
    }

    static String visibilityName(int visibility) {
        return visibility == View.VISIBLE ? "VISIBLE"
                : visibility == View.INVISIBLE ? "INVISIBLE"
                : visibility == View.GONE ? "GONE"
                : String.valueOf(visibility);
    }

    static String resourceName(View view) {
        int id = view == null ? View.NO_ID : view.getId();
        if (id == View.NO_ID || id == 0) return "none";
        try {
            return view.getResources().getResourceName(id);
        } catch (Throwable ignored) {
            return "0x" + Integer.toHexString(id);
        }
    }

    static String trim(String value, int max) {
        if (value == null) return "";
        String out = value.replace("\n", " ").replace("\r", " ").replace("\u0000", "?");
        return out.length() <= max ? out : out.substring(0, max) + "…";
    }

    static View findByClassName(View view, String className) {
        if (view == null || className == null) return null;
        if (className.equals(view.getClass().getName())) return view;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findByClassName(group.getChildAt(i), className);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String quote(String value, int max) {
        String safe = trim(value, max);
        return "\"" + safe.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private GoogleLensViewIntrospection() {}
}
