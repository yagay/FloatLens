package com.yagay.floatlens;

import android.content.SharedPreferences;

/** Shared Remote Preferences contract between the FloatLens app and hooked LSPosed processes. */
public final class LsposedRuntimeConfig {
    public static final String GROUP = "floatlens_runtime";
    public static final String K_SCHEMA_VERSION = "schema_version";
    public static final String K_ENHANCED_MODE = "enhanced_mode";
    public static final String K_LSPOSED_ENABLED = "lsposed_enabled";
    public static final String K_SECURE_SCREENSHOT_ENABLED = "secure_screenshot_enabled";
    public static final String K_SECURE_CAPTURE_ARMED_UNTIL = "secure_capture_armed_until_elapsed";
    public static final String K_UPDATED_AT = "updated_at";
    public static final String K_GOOGLE_CTS_SESSION_TOKEN = "google_cts_session_token_v1";
    public static final String K_GOOGLE_CTS_TRIGGER_ELAPSED = "google_cts_trigger_elapsed_v1";
    public static final String K_GOOGLE_CTS_SESSION_UNTIL = "google_cts_session_until_elapsed_v1";
    public static final int SCHEMA_VERSION = 4;

    /** Short lease: never leave secure capture armed after a stalled/aborted capture. */
    public static final long SECURE_CAPTURE_LEASE_MS = 3_000L;
    public static final long MAX_VALID_SECURE_CAPTURE_FUTURE_MS = 10_000L;
    /** Marker fallback is intentionally much shorter than the diagnostic receiver TTL. */
    public static final long GOOGLE_CTS_FALLBACK_WINDOW_MS = 5_000L;
    public static final long GOOGLE_CTS_MAX_FUTURE_MS = 10_000L;

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

    public static boolean isGoogleCtsFallbackArmed(boolean providerEnabled,
                                                   String token,
                                                   long triggerElapsed,
                                                   long armedUntilElapsed,
                                                   long nowElapsed) {
        if (!providerEnabled || token == null || token.isBlank()) return false;
        long age = nowElapsed - triggerElapsed;
        long remaining = armedUntilElapsed - nowElapsed;
        return triggerElapsed > 0L
                && age >= -250L
                && age <= GOOGLE_CTS_FALLBACK_WINDOW_MS
                && remaining >= 0L
                && remaining <= GOOGLE_CTS_MAX_FUTURE_MS;
    }

    public static boolean isSecureCaptureActive(boolean enhancedMode,
                                                boolean lsposedEnabled,
                                                boolean secureScreenshotEnabled,
                                                long armedUntilElapsed,
                                                long nowElapsed) {
        if (!isEnabled(enhancedMode, lsposedEnabled) || !secureScreenshotEnabled) return false;
        long remaining = armedUntilElapsed - nowElapsed;
        return remaining > 0L && remaining <= MAX_VALID_SECURE_CAPTURE_FUTURE_MS;
    }

    public static boolean isSecureCaptureActive(SharedPreferences preferences, long nowElapsed) {
        if (preferences == null) return false;
        if (preferences.getInt(K_SCHEMA_VERSION, 0) < SCHEMA_VERSION) return false;
        return isSecureCaptureActive(
                preferences.getBoolean(K_ENHANCED_MODE, false),
                preferences.getBoolean(K_LSPOSED_ENABLED, false),
                preferences.getBoolean(K_SECURE_SCREENSHOT_ENABLED, false),
                preferences.getLong(K_SECURE_CAPTURE_ARMED_UNTIL, 0L),
                nowElapsed);
    }
}
