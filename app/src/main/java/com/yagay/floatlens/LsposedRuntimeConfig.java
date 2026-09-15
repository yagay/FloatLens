package com.yagay.floatlens;

import android.content.SharedPreferences;

/** Shared Remote Preferences contract between the FloatLens app and hooked LSPosed processes. */
public final class LsposedRuntimeConfig {
    public static final String GROUP = "floatlens_runtime";
    public static final String K_SCHEMA_VERSION = "schema_version";
    public static final String K_ENHANCED_MODE = "enhanced_mode";
    public static final String K_LSPOSED_ENABLED = "lsposed_enabled";
    public static final String K_UPDATED_AT = "updated_at";
    public static final int SCHEMA_VERSION = 1;

    private LsposedRuntimeConfig() {}

    public static boolean isEnabled(boolean enhancedMode, boolean lsposedEnabled) {
        return enhancedMode && lsposedEnabled;
    }

    public static boolean isEnabled(SharedPreferences preferences) {
        if (preferences == null) return false;
        if (preferences.getInt(K_SCHEMA_VERSION, 0) < SCHEMA_VERSION) return false;
        return isEnabled(
                preferences.getBoolean(K_ENHANCED_MODE, false),
                preferences.getBoolean(K_LSPOSED_ENABLED, false));
    }
}
