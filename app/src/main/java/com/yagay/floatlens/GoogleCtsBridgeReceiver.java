package com.yagay.floatlens;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.SystemClock;

/** Authenticated small-event channel for one FloatLens-owned Google Circle-to-Search session. */
public final class GoogleCtsBridgeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null
                || !GoogleCtsContract.ACTION_BRIDGE.equals(intent.getAction())) return;

        SharedPreferences prefs = context.getSharedPreferences(
                FloatSettings.PREF, Context.MODE_PRIVATE);
        String expected = prefs.getString(FloatSettings.K_GOOGLE_CTS_ACTIVE_SESSION, "");
        long validUntil = prefs.getLong(FloatSettings.K_GOOGLE_CTS_ACTIVE_UNTIL, 0L);
        String token = intent.getStringExtra(GoogleCtsContract.EXTRA_BRIDGE_SESSION);
        if (!GoogleCtsContract.isAuthorizedTrace(
                expected, validUntil, token, SystemClock.elapsedRealtime())) return;

        String event = safe(intent.getStringExtra(GoogleCtsContract.EXTRA_BRIDGE_EVENT), 80);
        String text = safe(intent.getStringExtra(GoogleCtsContract.EXTRA_BRIDGE_TEXT), 20_000);
        String detail = safe(intent.getStringExtra(GoogleCtsContract.EXTRA_BRIDGE_DETAIL), 8_000);

        Rect bounds = null;
        int left = intent.getIntExtra(GoogleCtsContract.EXTRA_LEFT, Integer.MIN_VALUE);
        int top = intent.getIntExtra(GoogleCtsContract.EXTRA_TOP, Integer.MIN_VALUE);
        int right = intent.getIntExtra(GoogleCtsContract.EXTRA_RIGHT, Integer.MIN_VALUE);
        int bottom = intent.getIntExtra(GoogleCtsContract.EXTRA_BOTTOM, Integer.MIN_VALUE);
        if (left != Integer.MIN_VALUE && top != Integer.MIN_VALUE
                && right > left && bottom > top) {
            bounds = new Rect(left, top, right, bottom);
        }

        switch (event) {
            case GoogleCtsContract.EVENT_SELECTION ->
                    GoogleCtsBridgeController.onSelection(context, token, text, bounds, detail);
            case GoogleCtsContract.EVENT_REGION_SELECTION ->
                    GoogleCtsBridgeController.onRegionSelection(
                            context, token, bounds, detail);
            case GoogleCtsContract.EVENT_REGION_GESTURE_START ->
                    GoogleCtsBridgeController.onRegionGesture(
                            context, token, true, detail);
            case GoogleCtsContract.EVENT_REGION_GESTURE_END ->
                    GoogleCtsBridgeController.onRegionGesture(
                            context, token, false, detail);
            case GoogleCtsContract.EVENT_COMMIT ->
                    GoogleCtsBridgeController.onCommit(context, token, detail);
            case GoogleCtsContract.EVENT_QUERY_RESULT ->
                    GoogleCtsBridgeController.onQueryResult(
                            context, token, text, bounds, detail);
            case GoogleCtsContract.EVENT_TEXT_MENU_COMMIT ->
                    GoogleCtsBridgeController.onTextMenuCommit(
                            context, token, text, bounds, detail);
            case GoogleCtsContract.EVENT_END ->
                    GoogleCtsBridgeController.onEnd(context, token, detail);
            default -> DiagnosticLog.i(context, "GOOGLE_BRIDGE",
                    "unknown event=" + event + " session=" + shortToken(token));
        }
    }

    private static String safe(String value, int max) {
        if (value == null) return "";
        String out = value.replace("\u0000", "?");
        return out.length() <= max ? out : out.substring(0, max);
    }

    private static String shortToken(String token) {
        if (token == null || token.isBlank()) return "none";
        return token.substring(0, Math.min(8, token.length()));
    }
}
