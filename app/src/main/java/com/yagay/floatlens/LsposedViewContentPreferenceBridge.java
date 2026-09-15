package com.yagay.floatlens;

import android.content.Context;
import android.content.SharedPreferences;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/** Synchronizes the optional View-content preference to LSPosed Remote Preferences. */
public final class LsposedViewContentPreferenceBridge implements XposedServiceHelper.OnServiceListener {
    private static final LsposedViewContentPreferenceBridge INSTANCE = new LsposedViewContentPreferenceBridge();

    private volatile XposedService service;
    private volatile SharedPreferences local;
    private volatile boolean registered;

    public static synchronized void initialize(Context context) {
        if (context == null) return;
        INSTANCE.local = context.getApplicationContext()
                .getSharedPreferences(FloatSettings.PREF, Context.MODE_PRIVATE);
        if (!INSTANCE.registered) {
            INSTANCE.registered = true;
            XposedServiceHelper.registerListener(INSTANCE);
        }
    }

    public static void syncAsync() {
        INSTANCE.sync();
    }

    @Override public void onServiceBind(XposedService service) {
        this.service = service;
        sync();
    }

    @Override public void onServiceDied(XposedService service) {
        if (this.service == service) this.service = null;
    }

    private void sync() {
        XposedService current = service;
        SharedPreferences prefs = local;
        if (current == null || prefs == null) return;
        try {
            SharedPreferences remote = current.getRemotePreferences(LsposedRuntimeConfig.GROUP);
            if (remote == null) return;
            remote.edit()
                    .putInt(LsposedRuntimeConfig.K_SCHEMA_VERSION, LsposedRuntimeConfig.SCHEMA_VERSION)
                    .putBoolean(LsposedRuntimeConfig.K_VIEW_CONTENT_ENABLED,
                            prefs.getBoolean(LsposedViewContentPolicy.K_ENABLED, false))
                    .putLong(LsposedRuntimeConfig.K_UPDATED_AT, System.currentTimeMillis())
                    .apply();
        } catch (Throwable ignored) {
            // Main LSPosed status panel remains the source of framework availability diagnostics.
        }
    }

    private LsposedViewContentPreferenceBridge() {}
}
