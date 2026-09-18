package com.yagay.floatlens;

import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.widget.Toast;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.util.UUID;

/** FloatLens-owned trigger for Google Circle to Search. MiCTS is referenced only for this layer. */
final class GoogleCtsTrigger {
    private static final int CTS_SHOW_FLAGS = 7;

    static boolean trigger(Context c) {
        if (c == null) return false;
        Context app = c.getApplicationContext();
        FloatSettings fs = new FloatSettings(app);
        if (!fs.enhancedMode() || !fs.lsposedEnabled()) {
            Toast.makeText(app, "Google 圈画模式需要启用 LSPosed 增强", Toast.LENGTH_SHORT).show();
            return false;
        }
        LsposedStatusManager.Snapshot status = LsposedStatusManager.snapshot();
        if (!status.serviceConnected || !status.remoteConfigReady
                || !status.googleScopeEnabled()) {
            Toast.makeText(app, "请在 LSPosed 作用域勾选 Google App", Toast.LENGTH_LONG).show();
            return false;
        }
        if (status.googleTargetStale()) {
            DiagnosticLog.i(app, "GOOGLE_CTS_TRIGGER",
                    "blocked stale Google LSPosed target currentVersion=" + BuildConfig.VERSION_CODE
                            + " running=" + status.runningProcesses);
            Toast.makeText(app,
                    "Google Hook 仍是旧版本，请先强制停止 Google App 或重启手机",
                    Toast.LENGTH_LONG).show();
            return false;
        }

        Bundle args = new Bundle();
        String token = UUID.randomUUID().toString();
        long nowElapsed = SystemClock.elapsedRealtime();
        fs.armGoogleCtsSession(token, nowElapsed + GoogleCtsContract.TRACE_SESSION_TTL_MS);
        args.putLong(GoogleCtsContract.K_INVOCATION_TIME, nowElapsed);
        args.putInt(GoogleCtsContract.K_OMNI_ENTRY_POINT, 1);
        args.putBoolean(GoogleCtsContract.K_TRIGGER, true);
        args.putString(GoogleCtsContract.K_SESSION_TOKEN, token);

        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Object rawBinder = HiddenApiBypass.invoke(
                    serviceManager, null, "getService", "voiceinteraction");
            if (!(rawBinder instanceof IBinder binder)) {
                throw new IllegalStateException("voiceinteraction binder unavailable");
            }
            Class<?> stub = Class.forName(
                    "com.android.internal.app.IVoiceInteractionManagerService$Stub");
            Object vims = HiddenApiBypass.invoke(stub, null, "asInterface", binder);
            Class<?> iface = Class.forName(
                    "com.android.internal.app.IVoiceInteractionManagerService");

            Object result;
            try {
                result = HiddenApiBypass.invoke(iface, vims, "showSessionFromSession",
                        null, args, CTS_SHOW_FLAGS, "floatlens");
            } catch (NoSuchMethodException older) {
                result = HiddenApiBypass.invoke(iface, vims, "showSessionFromSession",
                        null, args, CTS_SHOW_FLAGS);
            }
            boolean ok = Boolean.TRUE.equals(result);
            DiagnosticLog.i(app, "GOOGLE_CTS_TRIGGER",
                    "result=" + ok + " session=" + token.substring(0, 8)
                            + " entryPoint=1 flags=" + CTS_SHOW_FLAGS);
            if (!ok) {
                fs.clearGoogleCtsSession();
                Toast.makeText(app, "Google 圈画启动失败", Toast.LENGTH_SHORT).show();
            }
            return ok;
        } catch (Throwable t) {
            fs.clearGoogleCtsSession();
            DiagnosticLog.i(app, "GOOGLE_CTS_TRIGGER",
                    "failed=" + t.getClass().getSimpleName() + ":" + String.valueOf(t.getMessage()));
            Toast.makeText(app, "Google 圈画启动失败: " + t.getClass().getSimpleName(),
                    Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private GoogleCtsTrigger() {}
}
