package com.yagay.floatlens.hook;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import com.yagay.floatlens.GoogleCtsContract;
import com.yagay.floatlens.LsposedRuntimeConfig;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** Process-local LSPosed provider gate backed by framework Remote Preferences. */
final class LsposedRuntimeProvider {
    private static final String TAG = "FloatLens-LSPosed";

    private final XposedModule module;
    private final String processName;
    private SharedPreferences preferences;
    private SharedPreferences.OnSharedPreferenceChangeListener listener;
    private volatile boolean active;

    LsposedRuntimeProvider(XposedModule module, String processName) {
        this.module = module;
        this.processName = processName == null ? "" : processName;
    }

    void start() {
        long properties = module.getFrameworkProperties();
        if ((properties & XposedInterface.PROP_CAP_REMOTE) == 0L) {
            module.log(Log.WARN, TAG,
                    "Remote Preferences unsupported in " + displayProcess() + "; provider disabled");
            active = false;
            return;
        }

        try {
            preferences = module.getRemotePreferences(LsposedRuntimeConfig.GROUP);
            listener = (prefs, key) -> {
                if (LsposedRuntimeConfig.K_SCHEMA_VERSION.equals(key)
                        || LsposedRuntimeConfig.K_ENHANCED_MODE.equals(key)
                        || LsposedRuntimeConfig.K_LSPOSED_ENABLED.equals(key)
                        || LsposedRuntimeConfig.K_SECURE_SCREENSHOT_ENABLED.equals(key)
                        || LsposedRuntimeConfig.K_SECURE_CAPTURE_ARMED_UNTIL.equals(key)
                        || LsposedRuntimeConfig.K_DIAGNOSTIC_ENABLED.equals(key)
                        || LsposedRuntimeConfig.K_GOOGLE_CTS_SESSION_TOKEN.equals(key)
                        || LsposedRuntimeConfig.K_GOOGLE_CTS_TRIGGER_ELAPSED.equals(key)
                        || LsposedRuntimeConfig.K_GOOGLE_CTS_SESSION_UNTIL.equals(key)) {
                    refresh();
                }
            };
            preferences.registerOnSharedPreferenceChangeListener(listener);
            refresh();
            module.log(Log.INFO, TAG,
                    "Controlled provider ready in " + displayProcess());
        } catch (UnsupportedOperationException unsupported) {
            active = false;
            module.log(Log.WARN, TAG,
                    "Remote Preferences unavailable in " + displayProcess(), unsupported);
        } catch (Throwable t) {
            active = false;
            module.log(Log.ERROR, TAG,
                    "Failed to initialize controlled provider in " + displayProcess(), t);
        }
    }

    boolean isActive() {
        return active;
    }

    boolean diagnosticsEnabled() {
        if (!active || preferences == null) return false;
        try {
            return preferences.getBoolean(LsposedRuntimeConfig.K_DIAGNOSTIC_ENABLED, false);
        } catch (Throwable t) {
            return false;
        }
    }

    boolean ownsGoogleCtsSession(String token) {
        if (!active || preferences == null || token == null || token.isBlank()) return false;
        try {
            String current = preferences.getString(
                    LsposedRuntimeConfig.K_GOOGLE_CTS_SESSION_TOKEN, "");
            return token.equals(current);
        } catch (Throwable t) {
            module.log(Log.WARN, TAG,
                    "Failed to verify Google CTS session ownership in " + displayProcess(), t);
            return false;
        }
    }

    String googleCtsArmedToken() {
        return googleCtsArmedToken(null);
    }

    String googleCtsArmedToken(Bundle observedExtras) {
        if (!active || preferences == null) return "";
        try {
            long now = SystemClock.elapsedRealtime();
            String token = preferences.getString(
                    LsposedRuntimeConfig.K_GOOGLE_CTS_SESSION_TOKEN, "");
            long trigger = preferences.getLong(
                    LsposedRuntimeConfig.K_GOOGLE_CTS_TRIGGER_ELAPSED, 0L);
            long until = preferences.getLong(
                    LsposedRuntimeConfig.K_GOOGLE_CTS_SESSION_UNTIL, 0L);
            if (!LsposedRuntimeConfig.isGoogleCtsFallbackArmed(
                    active, token, trigger, until, now)) {
                return "";
            }

            boolean hasInvocation = observedExtras != null
                    && observedExtras.containsKey(GoogleCtsContract.K_INVOCATION_TIME);
            long observedInvocation = hasInvocation
                    ? observedExtras.getLong(GoogleCtsContract.K_INVOCATION_TIME, -1L) : -1L;
            boolean hasEntryPoint = observedExtras != null
                    && observedExtras.containsKey(GoogleCtsContract.K_OMNI_ENTRY_POINT);
            int observedEntryPoint = hasEntryPoint
                    ? observedExtras.getInt(GoogleCtsContract.K_OMNI_ENTRY_POINT, -1) : -1;

            return LsposedRuntimeConfig.matchesGoogleCtsFallbackInvocation(
                    trigger, hasInvocation, observedInvocation,
                    hasEntryPoint, observedEntryPoint) ? token : "";
        } catch (Throwable t) {
            module.log(Log.WARN, TAG,
                    "Failed to read Google CTS armed session in " + displayProcess(), t);
            return "";
        }
    }

    /** Read live Remote Preferences on every capture call so lease expiry never depends on listener timing. */
    boolean isSecureCaptureArmed() {
        if (!active || preferences == null) return false;
        try {
            return LsposedRuntimeConfig.isSecureCaptureActive(
                    preferences, SystemClock.elapsedRealtime());
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG,
                    "Failed to read secure capture lease in " + displayProcess(), t);
            return false;
        }
    }

    private void refresh() {
        boolean next;
        try {
            next = LsposedRuntimeConfig.isEnabled(preferences);
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG,
                    "Failed to read runtime config in " + displayProcess(), t);
            next = false;
        }

        boolean previous = active;
        active = next;
        if (previous != next) {
            module.log(Log.INFO, TAG,
                    "Provider state in " + displayProcess() + ": " + (next ? "enabled" : "disabled"));
        }
    }

    private String displayProcess() {
        return processName.isBlank() ? "unknown process" : processName;
    }
}
