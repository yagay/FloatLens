package com.yagay.floatlens;

import android.os.Bundle;

/** Stable marker contract for FloatLens-triggered Google Circle-to-Search sessions. */
public final class GoogleCtsContract {
    public static final String GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox";
    public static final String K_TRIGGER = "floatlens_trigger";
    public static final String K_SESSION_TOKEN = "floatlens_session_token";
    public static final String K_INVOCATION_TIME = "invocation_time_ms";
    public static final String K_OMNI_ENTRY_POINT = "omni.entry_point";

    public static boolean isFloatLensSession(Bundle args) {
        return args != null
                && args.getBoolean(K_TRIGGER, false)
                && !args.getString(K_SESSION_TOKEN, "").isBlank();
    }

    private GoogleCtsContract() {}
}
