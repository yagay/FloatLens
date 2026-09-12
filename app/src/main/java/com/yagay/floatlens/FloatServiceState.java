package com.yagay.floatlens;

import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

/** Persistent user-controlled state for the FloatLens floating service. */
public final class FloatServiceState {
    private static final String PREF = "floatlens_service_state";
    private static final String KEY_ENABLED = "service_enabled";

    private FloatServiceState() { }

    public static boolean isEnabled(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        context.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, enabled)
                .apply();
    }

    /**
     * Persist the enabled state first, then request the foreground service.
     * Keeping the preference true on a transient start failure lets boot/app resume retry later.
     */
    public static boolean start(Context context) {
        Context app = context.getApplicationContext();
        setEnabled(app, true);
        try {
            ContextCompat.startForegroundService(app,
                    new Intent(app, FloatService.class).setAction(FloatService.ACT_START));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Explicit user disable: persist OFF before stopping the service. */
    public static void stop(Context context) {
        Context app = context.getApplicationContext();
        setEnabled(app, false);
        try {
            app.stopService(new Intent(app, FloatService.class));
        } catch (Throwable ignored) { }
    }
}
