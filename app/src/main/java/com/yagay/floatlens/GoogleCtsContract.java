package com.yagay.floatlens;

import android.os.Bundle;

/** Stable marker contract for FloatLens-triggered Google Circle-to-Search sessions. */
public final class GoogleCtsContract {
    public static final String GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox";
    public static final String K_TRIGGER = "floatlens_trigger";
    public static final String K_SESSION_TOKEN = "floatlens_session_token";
    public static final String K_INVOCATION_TIME = "invocation_time_ms";
    public static final String K_OMNI_ENTRY_POINT = "omni.entry_point";
    public static final String ACTION_TRACE = "com.yagay.floatlens.action.GOOGLE_CTS_TRACE";
    public static final String TRACE_RECEIVER_CLASS = "com.yagay.floatlens.GoogleCtsTraceReceiver";
    public static final String EXTRA_TRACE_SESSION = "com.yagay.floatlens.extra.CTS_TRACE_SESSION";
    public static final String EXTRA_TRACE_LINE = "com.yagay.floatlens.extra.CTS_TRACE_LINE";
    public static final long TRACE_SESSION_TTL_MS = 120_000L;

    public static boolean isFloatLensSession(Bundle args) {
        return args != null && isFloatLensSession(
                args.getBoolean(K_TRIGGER, false),
                args.getString(K_SESSION_TOKEN, ""));
    }

    static boolean isFloatLensSession(boolean trigger, String token) {
        return trigger && token != null && !token.isBlank();
    }

    static boolean isAuthorizedTrace(String expectedToken, long validUntilElapsed,
                                     String suppliedToken, long nowElapsed) {
        return expectedToken != null && !expectedToken.isBlank()
                && suppliedToken != null && expectedToken.equals(suppliedToken)
                && validUntilElapsed >= nowElapsed
                && validUntilElapsed - nowElapsed <= TRACE_SESSION_TTL_MS;
    }

    private GoogleCtsContract() {}
}
