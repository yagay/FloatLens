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
        if (WorkflowSessionManager.googleCtsInFlight()) {
            WorkflowSessionManager.Session current = WorkflowSessionManager.current();
            DiagnosticLog.i(app, "GOOGLE_CTS_TRIGGER",
                    "duplicate ignored current="
                            + (current == null ? "none" : current.phase())
                            + " session="
                            + (current == null || current.externalKey().isBlank()
                                    ? "none"
                                    : current.externalKey().substring(
                                            0, Math.min(8, current.externalKey().length()))));
            // Treat the duplicate gesture as handled so ActionExecutor does not fall back to the
            // native FloatLens circle workflow while Google CTS is already on screen.
            return true;
        }

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
        WorkflowSessionManager.Session workflow = WorkflowSessionManager.beginExternal(
                app, WorkflowSessionManager.Type.GOOGLE_CTS, token, "google_cts_trigger");
        WorkflowSessionManager.transition(app, workflow,
                WorkflowSessionManager.Phase.CAPTURING, "google_cts_trigger");
        long nowElapsed = SystemClock.elapsedRealtime();
        fs.armGoogleCtsSession(token, nowElapsed + GoogleCtsContract.TRACE_SESSION_TTL_MS);
        long fallbackUntil = nowElapsed + LsposedRuntimeConfig.GOOGLE_CTS_FALLBACK_WINDOW_MS;
        boolean remoteArmed = LsposedStatusManager.armGoogleCtsSessionRemote(
                token, nowElapsed, fallbackUntil);
        DiagnosticLog.i(app, "GOOGLE_CTS_ARM",
                "remote=" + remoteArmed + " session=" + token.substring(0, 8)
                        + " fallbackMs=" + LsposedRuntimeConfig.GOOGLE_CTS_FALLBACK_WINDOW_MS);
        args.putLong(GoogleCtsContract.K_INVOCATION_TIME, nowElapsed);
        args.putInt(GoogleCtsContract.K_OMNI_ENTRY_POINT,
                LsposedRuntimeConfig.GOOGLE_CTS_EXPECTED_OMNI_ENTRY_POINT);
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
                            + " entryPoint="
                            + LsposedRuntimeConfig.GOOGLE_CTS_EXPECTED_OMNI_ENTRY_POINT
                            + " flags=" + CTS_SHOW_FLAGS);
            if (!ok) {
                fs.clearGoogleCtsSession();
                LsposedStatusManager.clearGoogleCtsSessionRemote(token);
                WorkflowSessionManager.fail(app, workflow, "google_cts_start_failed");
                Toast.makeText(app, "Google 圈画启动失败", Toast.LENGTH_SHORT).show();
            } else {
                WorkflowSessionManager.transition(app, workflow,
                        WorkflowSessionManager.Phase.SELECTING, "google_cts_visible");
            }
            return ok;
        } catch (Throwable t) {
            fs.clearGoogleCtsSession();
            LsposedStatusManager.clearGoogleCtsSessionRemote(token);
            WorkflowSessionManager.fail(app, workflow, "google_cts_exception");
            DiagnosticLog.i(app, "GOOGLE_CTS_TRIGGER",
                    "failed=" + t.getClass().getSimpleName() + ":" + String.valueOf(t.getMessage()));
            Toast.makeText(app, "Google 圈画启动失败: " + t.getClass().getSimpleName(),
                    Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private GoogleCtsTrigger() {}
}
