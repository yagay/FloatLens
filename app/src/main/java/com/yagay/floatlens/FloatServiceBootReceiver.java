package com.yagay.floatlens;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restores the floating icon after reboot or app replacement when the user left it enabled. */
public final class FloatServiceBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        if (FloatServiceState.isEnabled(context)) {
            FloatServiceState.start(context);
        }
    }
}
