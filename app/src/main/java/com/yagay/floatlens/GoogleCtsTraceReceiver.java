package com.yagay.floatlens;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

/** Authenticated one-way diagnostic bridge from the hooked Google process to FloatLens. */
public final class GoogleCtsTraceReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null
                || !GoogleCtsContract.ACTION_TRACE.equals(intent.getAction())) return;

        SharedPreferences prefs = context.getSharedPreferences(
                FloatSettings.PREF, Context.MODE_PRIVATE);
        String expected = prefs.getString(FloatSettings.K_GOOGLE_CTS_ACTIVE_SESSION, "");
        long validUntil = prefs.getLong(FloatSettings.K_GOOGLE_CTS_ACTIVE_UNTIL, 0L);
        String supplied = intent.getStringExtra(GoogleCtsContract.EXTRA_TRACE_SESSION);
        if (!GoogleCtsContract.isAuthorizedTrace(
                expected, validUntil, supplied, SystemClock.elapsedRealtime())) return;

        String line = intent.getStringExtra(GoogleCtsContract.EXTRA_TRACE_LINE);
        if (line == null || line.isBlank()) return;
        if (line.length() > 8000) line = line.substring(0, 8000);
        DiagnosticLog.i(context, "GOOGLE_CTS_HOOK", line);
    }
}
