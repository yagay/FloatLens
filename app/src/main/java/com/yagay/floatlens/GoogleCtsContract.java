package com.yagay.floatlens;

import android.net.Uri;
import android.os.Bundle;

/** Stable marker contract for FloatLens-triggered Google Circle-to-Search sessions. */
public final class GoogleCtsContract {
    public static final String GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox";
    public static final String K_TRIGGER = "floatlens_trigger";
    public static final String K_SESSION_TOKEN = "floatlens_session_token";
    public static final String K_INVOCATION_TIME = "invocation_time_ms";
    public static final String K_OMNI_ENTRY_POINT = "omni.entry_point";
    public static final String CONTEXTUAL_SEARCH_ACTION =
            "android.app.contextualsearch.action.LAUNCH_CONTEXTUAL_SEARCH";
    public static final String CONTEXTUAL_SCREENSHOT =
            "android.app.contextualsearch.extra.SCREENSHOT";
    public static final String ACTION_TRACE = "com.yagay.floatlens.action.GOOGLE_CTS_TRACE";
    public static final String TRACE_RECEIVER_CLASS = "com.yagay.floatlens.GoogleCtsTraceReceiver";
    public static final String EXTRA_TRACE_SESSION = "com.yagay.floatlens.extra.CTS_TRACE_SESSION";
    public static final String EXTRA_TRACE_LINE = "com.yagay.floatlens.extra.CTS_TRACE_LINE";

    public static final String BRIDGE_AUTHORITY = "com.yagay.floatlens.googlebridge";
    public static final String ACTION_BRIDGE = "com.yagay.floatlens.action.GOOGLE_CTS_BRIDGE";
    public static final String BRIDGE_RECEIVER_CLASS =
            "com.yagay.floatlens.GoogleCtsBridgeReceiver";
    public static final String EXTRA_BRIDGE_SESSION =
            "com.yagay.floatlens.extra.CTS_BRIDGE_SESSION";
    public static final String EXTRA_BRIDGE_EVENT =
            "com.yagay.floatlens.extra.CTS_BRIDGE_EVENT";
    public static final String EXTRA_BRIDGE_TEXT =
            "com.yagay.floatlens.extra.CTS_BRIDGE_TEXT";
    public static final String EXTRA_BRIDGE_DETAIL =
            "com.yagay.floatlens.extra.CTS_BRIDGE_DETAIL";
    public static final String EXTRA_LEFT = "com.yagay.floatlens.extra.CTS_LEFT";
    public static final String EXTRA_TOP = "com.yagay.floatlens.extra.CTS_TOP";
    public static final String EXTRA_RIGHT = "com.yagay.floatlens.extra.CTS_RIGHT";
    public static final String EXTRA_BOTTOM = "com.yagay.floatlens.extra.CTS_BOTTOM";
    public static final String EVENT_SELECTION = "selection";
    public static final String EVENT_COMMIT = "commit";
    public static final String EVENT_QUERY_RESULT = "query_result";
    /** Finalize a text selection whose FloatLens action menu was already shown at selection time. */
    public static final String EVENT_TEXT_MENU_COMMIT = "text_menu_commit";
    public static final String EVENT_END = "end";
    public static final String Q_WIDTH = "w";
    public static final String Q_HEIGHT = "h";
    public static final String Q_BYTES = "bytes";

    public static final long TRACE_SESSION_TTL_MS = 120_000L;

    public static boolean isContextualSearchAction(String action) {
        return CONTEXTUAL_SEARCH_ACTION.equals(action);
    }

    public static boolean isFloatLensSession(Bundle args) {
        return args != null && isFloatLensSession(
                args.getBoolean(K_TRIGGER, false),
                args.getString(K_SESSION_TOKEN, ""));
    }

    static boolean isFloatLensSession(boolean trigger, String token) {
        return trigger && token != null && !token.isBlank();
    }

    public static Uri bridgeFrameUri(String token, int width, int height, int bytes) {
        return new Uri.Builder()
                .scheme("content")
                .authority(BRIDGE_AUTHORITY)
                .appendPath("frame")
                .appendPath(token == null ? "" : token)
                .appendQueryParameter(Q_WIDTH, String.valueOf(width))
                .appendQueryParameter(Q_HEIGHT, String.valueOf(height))
                .appendQueryParameter(Q_BYTES, String.valueOf(bytes))
                .build();
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
