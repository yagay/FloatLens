package com.yagay.floatlens;
import android.content.*;
public final class FvInspectorReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i){if(i==null||!"com.yagay.floatlens.FV_HOOK_LOG".equals(i.getAction()))return;String p=i.getStringExtra("payload");InspectorLog.append(c,p);}
}
