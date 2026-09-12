package com.yagay.floatlens;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class FlInspectorReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (i == null || !"com.yagay.floatlens.FL_HOOK_LOG".equals(i.getAction())) return;
        String payload = i.getStringExtra("payload");
        InspectorLog.append(c, payload);
    }
}
