package com.yagay.floatlens;

import android.content.Context;

/** User-facing behavior settings for the floating text action menu. */
final class TextMenuSettings {
    static final int MIN_PINNED = 1;
    static final int MAX_PINNED = 8;
    static final int DEFAULT_PINNED = 6;

    private static final String PREFS = "floatlens_text_menu";
    private static final String KEY_PINNED_CUSTOM_COUNT = "pinned_custom_count";

    static int pinnedCustomCount(Context c) {
        if (c == null) return DEFAULT_PINNED;
        return clamp(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_PINNED_CUSTOM_COUNT, DEFAULT_PINNED));
    }

    static void setPinnedCustomCount(Context c, int count) {
        if (c == null) return;
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_PINNED_CUSTOM_COUNT, clamp(count)).apply();
    }

    private static int clamp(int count) {
        return Math.max(MIN_PINNED, Math.min(MAX_PINNED, count));
    }

    private TextMenuSettings() { }
}
