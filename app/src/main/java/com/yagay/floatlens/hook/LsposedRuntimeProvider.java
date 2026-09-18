package com.yagay.floatlens.hook;

import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import com.yagay.floatlens.LsposedRuntimeConfig;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** Process-local LSPosed provider gate backed by framework Remote Preferences. */
final class LsposedRuntimeProvider {
    private static final String TAG = "FloatLens-LSPosed";
    private static final int MAX_CTS_TRACE_CHARS = 120_000;
    private final Object ctsTraceLock = new Object();

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
                        || LsposedRuntimeConfig.K_SECURE_CAPTURE_ARMED_UNTIL.equals(key)) {
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

    void resetGoogleCtsTrace(String header) {
        if (preferences == null) return;
        synchronized (ctsTraceLock) {
            String text = header == null ? "" : header;
            try {
                preferences.edit()
                        .putString(LsposedRuntimeConfig.K_GOOGLE_CTS_TRACE, text)
                        .putLong(LsposedRuntimeConfig.K_GOOGLE_CTS_TRACE_SEQ,
                                preferences.getLong(LsposedRuntimeConfig.K_GOOGLE_CTS_TRACE_SEQ, 0L) + 1L)
                        .commit();
            } catch (Throwable t) {
                module.log(Log.ERROR, TAG, "Failed to reset Google CTS trace", t);
            }
        }
    }

    void appendGoogleCtsTrace(String line) {
        if (preferences == null || line == null || line.isBlank()) return;
        synchronized (ctsTraceLock) {
            try {
                String old = preferences.getString(LsposedRuntimeConfig.K_GOOGLE_CTS_TRACE, "");
                String next = old.isEmpty() ? line : old + "\n" + line;
                if (next.length() > MAX_CTS_TRACE_CHARS) {
                    next = next.substring(next.length() - MAX_CTS_TRACE_CHARS);
                }
                preferences.edit()
                        .putString(LsposedRuntimeConfig.K_GOOGLE_CTS_TRACE, next)
                        .putLong(LsposedRuntimeConfig.K_GOOGLE_CTS_TRACE_SEQ,
                                preferences.getLong(LsposedRuntimeConfig.K_GOOGLE_CTS_TRACE_SEQ, 0L) + 1L)
                        .commit();
            } catch (Throwable t) {
                module.log(Log.ERROR, TAG, "Failed to append Google CTS trace", t);
            }
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
