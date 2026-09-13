package com.yagay.floatlens;

import android.content.Context;

/** User-facing behavior settings for the floating text action menu. */
final class TextMenuSettings {
    static final int MIN_MAIN_ITEMS = 4;
    static final int MAX_MAIN_ITEMS = 8;
    static final int DEFAULT_MAIN_ITEMS = 6;

    private static final String PREFS = "floatlens_text_menu";
    // v2 deliberately uses a new key because the old value meant "custom items only".
    private static final String KEY_MAIN_ITEM_COUNT = "main_item_count_v2";

    static int mainItemCount(Context c) {
        if (c == null) return DEFAULT_MAIN_ITEMS;
        return clamp(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_MAIN_ITEM_COUNT, DEFAULT_MAIN_ITEMS));
    }

    static void setMainItemCount(Context c, int count) {
        if (c == null) return;
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_MAIN_ITEM_COUNT, clamp(count)).apply();
    }

    /**
     * Convert the requested TOTAL toolbar item count into available custom-action slots.
     * Built-ins are copy + share + more, plus select-all when that action is available.
     */
    static int customSlots(Context c, boolean hasSelectAll, int customSize) {
        int builtIns = hasSelectAll ? 4 : 3;
        int available = Math.max(0, mainItemCount(c) - builtIns);
        return Math.min(available, Math.max(0, customSize));
    }

    private static int clamp(int count) {
        return Math.max(MIN_MAIN_ITEMS, Math.min(MAX_MAIN_ITEMS, count));
    }

    private TextMenuSettings() { }
}
