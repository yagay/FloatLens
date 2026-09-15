package com.yagay.floatlens.hook;

import android.content.SharedPreferences;
import android.util.Log;

import com.yagay.floatlens.LsposedRuntimeConfig;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Process-local LSPosed provider gate.
 *
 * <p>This class deliberately installs no functional hooks. It only consumes the framework-backed
 * Remote Preferences written by the FloatLens app and keeps a process-local active flag in sync.
 * Future hook providers must check {@link #isActive()} before applying user-visible behavior.</p>
 */
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
                        || LsposedRuntimeConfig.K_LSPOSED_ENABLED.equals(key)) {
                    refresh();
                }
            };
            preferences.registerOnSharedPreferenceChangeListener(listener);
            refresh();
            module.log(Log.INFO, TAG,
                    "Controlled provider ready in " + displayProcess() + "; functional hooks=none");
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
