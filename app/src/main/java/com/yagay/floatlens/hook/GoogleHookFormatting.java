package com.yagay.floatlens.hook;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;

import java.util.Collection;

/** Shared bounded diagnostic formatting for Google App hooks. */
final class GoogleHookFormatting {
    static String bitmapSummary(Bitmap bitmap) {
        return bitmap == null ? "null"
                : bitmap.getWidth() + "x" + bitmap.getHeight() + "/" + bitmap.getConfig();
    }

    static String googleCaller() {
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            String cls = frame.getClassName();
            if (cls != null && cls.startsWith("com.google.")) {
                return cls + "#" + frame.getMethodName() + ":" + frame.getLineNumber();
            }
        }
        return "unknown";
    }

    static String describeIntent(Intent intent) {
        if (intent == null) return "intent=null";
        Uri data = intent.getData();
        return "action=" + intent.getAction()
                + " component=" + intent.getComponent()
                + " package=" + intent.getPackage()
                + " data=" + (data == null ? "null" : data.toString())
                + " extras=" + safeKeys(intent.getExtras());
    }

    static String describeValue(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) return "String(len=" + s.length() + ")";
        if (value instanceof Bitmap b) return "Bitmap(" + b.getWidth() + "x" + b.getHeight() + ")";
        if (value instanceof Bundle b) return "Bundle" + safeKeys(b);
        if (value instanceof Intent i) return "Intent{" + describeIntent(i) + "}";
        if (value instanceof Rect || value instanceof RectF) return value.toString();
        if (value instanceof Collection<?> c) {
            return value.getClass().getSimpleName() + "(size=" + c.size() + ")";
        }
        Class<?> cls = value.getClass();
        if (cls.isArray()) return cls.getComponentType().getSimpleName() + "[]";
        return cls.getName();
    }

    static String safeKeys(Bundle bundle) {
        if (bundle == null) return "[]";
        try { return bundle.keySet().toString(); }
        catch (Throwable t) { return "[unreadable]"; }
    }

    static String shortToken(String token) {
        if (token == null || token.isBlank()) return "none";
        return token.substring(0, Math.min(8, token.length()));
    }

    static String safe(String value) {
        return value == null ? "" : value.replace("\u0000", "?");
    }

    private GoogleHookFormatting() {}
}
