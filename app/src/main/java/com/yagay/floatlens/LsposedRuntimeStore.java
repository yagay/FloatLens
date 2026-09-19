package com.yagay.floatlens;

import android.content.SharedPreferences;

import io.github.libxposed.service.XposedService;

/** Single schema/write boundary for framework-backed LSPosed runtime preferences. */
final class LsposedRuntimeStore {
    static SharedPreferences open(XposedService service) {
        if (service == null) return null;
        return service.getRemotePreferences(LsposedRuntimeConfig.GROUP);
    }

    static SharedPreferences syncPersistent(XposedService service,
                                            boolean enhanced,
                                            boolean lsposed,
                                            boolean secureScreenshot,
                                            boolean diagnostic,
                                            long updatedAt) {
        SharedPreferences remote = open(service);
        if (remote == null) return null;
        boolean committed = remote.edit()
                .putInt(LsposedRuntimeConfig.K_SCHEMA_VERSION, LsposedRuntimeConfig.SCHEMA_VERSION)
                .putBoolean(LsposedRuntimeConfig.K_ENHANCED_MODE, enhanced)
                .putBoolean(LsposedRuntimeConfig.K_LSPOSED_ENABLED, lsposed)
                .putBoolean(LsposedRuntimeConfig.K_SECURE_SCREENSHOT_ENABLED, secureScreenshot)
                .putBoolean(LsposedRuntimeConfig.K_DIAGNOSTIC_ENABLED, diagnostic)
                .putLong(LsposedRuntimeConfig.K_UPDATED_AT, updatedAt)
                .commit();
        return committed ? remote : null;
    }

    static boolean armGoogle(XposedService service, String token,
                             long triggerElapsed, long armedUntilElapsed) {
        if (token == null || token.isBlank()) return false;
        SharedPreferences remote = open(service);
        if (remote == null) return false;
        return remote.edit()
                .putString(LsposedRuntimeConfig.K_GOOGLE_CTS_SESSION_TOKEN, token)
                .putLong(LsposedRuntimeConfig.K_GOOGLE_CTS_TRIGGER_ELAPSED, triggerElapsed)
                .putLong(LsposedRuntimeConfig.K_GOOGLE_CTS_SESSION_UNTIL, armedUntilElapsed)
                .remove(LsposedRuntimeConfig.K_GOOGLE_CTS_REGION_CONFIRM_TOKEN)
                .remove(LsposedRuntimeConfig.K_GOOGLE_CTS_REGION_CONFIRM_ELAPSED)
                .remove(LsposedRuntimeConfig.K_GOOGLE_CTS_COMPONENT_BLOCK_UNTIL)
                .commit();
    }

    static boolean confirmGoogleRegion(XposedService service, String token, long confirmedAtElapsed) {
        if (token == null || token.isBlank() || confirmedAtElapsed <= 0L) return false;
        SharedPreferences remote = open(service);
        if (remote == null) return false;
        String currentToken = remote.getString(LsposedRuntimeConfig.K_GOOGLE_CTS_SESSION_TOKEN, "");
        if (!token.equals(currentToken)) return false;
        return remote.edit()
                .putString(LsposedRuntimeConfig.K_GOOGLE_CTS_REGION_CONFIRM_TOKEN, token)
                .putLong(LsposedRuntimeConfig.K_GOOGLE_CTS_REGION_CONFIRM_ELAPSED,
                        confirmedAtElapsed)
                .commit();
    }

    static boolean clearGoogle(XposedService service, String token) {
        SharedPreferences remote = open(service);
        if (remote == null) return false;
        String currentToken = remote.getString(LsposedRuntimeConfig.K_GOOGLE_CTS_SESSION_TOKEN, "");
        if (token != null && !token.isBlank() && !token.equals(currentToken)) return false;
        return remote.edit()
                .remove(LsposedRuntimeConfig.K_GOOGLE_CTS_SESSION_TOKEN)
                .remove(LsposedRuntimeConfig.K_GOOGLE_CTS_TRIGGER_ELAPSED)
                .remove(LsposedRuntimeConfig.K_GOOGLE_CTS_SESSION_UNTIL)
                .remove(LsposedRuntimeConfig.K_GOOGLE_CTS_REGION_CONFIRM_TOKEN)
                .remove(LsposedRuntimeConfig.K_GOOGLE_CTS_REGION_CONFIRM_ELAPSED)
                .remove(LsposedRuntimeConfig.K_GOOGLE_CTS_COMPONENT_BLOCK_UNTIL)
                .commit();
    }

    static SharedPreferences armSecure(XposedService service,
                                       boolean enhanced,
                                       boolean lsposed,
                                       boolean secureScreenshot,
                                       long armedUntil,
                                       long updatedAt) {
        SharedPreferences remote = open(service);
        if (remote == null) return null;
        boolean committed = remote.edit()
                .putInt(LsposedRuntimeConfig.K_SCHEMA_VERSION, LsposedRuntimeConfig.SCHEMA_VERSION)
                .putBoolean(LsposedRuntimeConfig.K_ENHANCED_MODE, enhanced)
                .putBoolean(LsposedRuntimeConfig.K_LSPOSED_ENABLED, lsposed)
                .putBoolean(LsposedRuntimeConfig.K_SECURE_SCREENSHOT_ENABLED, secureScreenshot)
                .putLong(LsposedRuntimeConfig.K_SECURE_CAPTURE_ARMED_UNTIL, armedUntil)
                .putLong(LsposedRuntimeConfig.K_UPDATED_AT, updatedAt)
                .commit();
        return committed ? remote : null;
    }

    static SharedPreferences disarmSecure(XposedService service, long updatedAt) {
        SharedPreferences remote = open(service);
        if (remote == null) return null;
        boolean committed = remote.edit()
                .putLong(LsposedRuntimeConfig.K_SECURE_CAPTURE_ARMED_UNTIL, 0L)
                .putLong(LsposedRuntimeConfig.K_UPDATED_AT, updatedAt)
                .commit();
        return committed ? remote : null;
    }

    private LsposedRuntimeStore() {}
}
