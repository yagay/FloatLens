package com.yagay.floatlens;

import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/** Shared extras contract written by LSPosed target-app hooks and read by Accessibility snapshots. */
public final class LsposedViewContentMetadata {
    public static final int SCHEMA_VERSION = 1;

    public static final String EXTRA_SCHEMA = "com.yagay.floatlens.viewcontent.schema";
    public static final String EXTRA_KIND = "com.yagay.floatlens.viewcontent.kind";
    public static final String EXTRA_DRAWABLE_CLASS = "com.yagay.floatlens.viewcontent.drawable_class";
    public static final String EXTRA_IMAGE_BOUNDS = "com.yagay.floatlens.viewcontent.image_bounds";
    public static final String EXTRA_SOURCE_CLASS = "com.yagay.floatlens.viewcontent.source_class";

    public static final int KIND_NONE = 0;
    public static final int KIND_TEXT = 1;
    public static final int KIND_IMAGE = 2;
    public static final int KIND_TEXT_IMAGE = 3;
    public static final int KIND_CONTAINER = 4;

    public static List<Rect> imageBounds(AccessibilityNodeInfo node) {
        if (node == null) return List.of();
        try {
            Bundle extras = node.getExtras();
            if (extras == null || extras.getInt(EXTRA_SCHEMA, 0) < SCHEMA_VERSION) return List.of();
            int[] raw = extras.getIntArray(EXTRA_IMAGE_BOUNDS);
            if (raw == null || raw.length < 4) return List.of();
            ArrayList<Rect> out = new ArrayList<>(raw.length / 4);
            for (int i = 0; i + 3 < raw.length; i += 4) {
                Rect r = new Rect(raw[i], raw[i + 1], raw[i + 2], raw[i + 3]);
                if (!r.isEmpty()) out.add(r);
            }
            return List.copyOf(out);
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    public static int kind(AccessibilityNodeInfo node) {
        if (node == null) return KIND_NONE;
        try {
            Bundle extras = node.getExtras();
            if (extras == null || extras.getInt(EXTRA_SCHEMA, 0) < SCHEMA_VERSION) return KIND_NONE;
            return extras.getInt(EXTRA_KIND, KIND_NONE);
        } catch (Throwable ignored) {
            return KIND_NONE;
        }
    }

    private LsposedViewContentMetadata() {}
}
