package com.yagay.floatlens;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Reloads reloadable LSPosed targets without rebooting Android.
 *
 * <p>Google App and SystemUI are ordinary processes and can safely pick up the newly installed
 * module after their process is recreated. system_server is deliberately never killed here.</p>
 */
final class HookReloadManager {
    private static final String PREF = "floatlens_hook_reload";
    private static final String K_GOOGLE_UPDATE_TIME = "google_reload_apk_update_time_v1";
    private static final String K_SYSTEMUI_UPDATE_TIME = "systemui_reload_apk_update_time_v1";

    private static final String GOOGLE = GoogleCtsContract.GOOGLE_PACKAGE;
    private static final String SYSTEM_UI = "com.android.systemui";

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FloatLens-hook-reload");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean GOOGLE_RELOAD_RUNNING = new AtomicBoolean(false);
    private static final AtomicBoolean FULL_RELOAD_RUNNING = new AtomicBoolean(false);

    static final class Result {
        final boolean success;
        final boolean googleReloaded;
        final boolean systemUiReloaded;
        final boolean systemServerNeedsReboot;
        final String detail;

        Result(boolean success,
               boolean googleReloaded,
               boolean systemUiReloaded,
               boolean systemServerNeedsReboot,
               String detail) {
            this.success = success;
            this.googleReloaded = googleReloaded;
            this.systemUiReloaded = systemUiReloaded;
            this.systemServerNeedsReboot = systemServerNeedsReboot;
            this.detail = detail == null ? "" : detail;
        }

        String userMessage() {
            if (!success) return detail.isBlank() ? "Hook 重载失败" : detail;
            StringBuilder out = new StringBuilder();
            if (googleReloaded) out.append("Google Hook 已重载");
            if (systemUiReloaded) {
                if (out.length() > 0) out.append("；");
                out.append("SystemUI 已重载");
            }
            if (out.length() == 0) out.append("可热重载目标已是当前版本");
            if (systemServerNeedsReboot) {
                out.append("；system_server 仍是旧版本，只有安全截图 Hook 有改动时才需要重启手机");
            }
            return out.toString();
        }
    }

    private HookReloadManager() {}

    static void initialize(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        long current = apkUpdateTime(app);
        if (current <= 0L) return;
        SharedPreferences p = app.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        SharedPreferences.Editor edit = null;
        if (!p.contains(K_GOOGLE_UPDATE_TIME)) {
            edit = p.edit().putLong(K_GOOGLE_UPDATE_TIME, current);
        }
        if (!p.contains(K_SYSTEMUI_UPDATE_TIME)) {
            if (edit == null) edit = p.edit();
            edit.putLong(K_SYSTEMUI_UPDATE_TIME, current);
        }
        if (edit != null) edit.apply();
    }

    /**
     * Same-version debug installs are detected through PackageInfo.lastUpdateTime, while LSPosed's
     * loadedVersionCode still catches normal version bumps.
     */
    static boolean googleNeedsReload(Context context, LsposedStatusManager.Snapshot snapshot) {
        if (context == null || snapshot == null) return false;
        long current = apkUpdateTime(context);
        long marked = preference(context).getLong(K_GOOGLE_UPDATE_TIME, 0L);
        boolean running = hasProcess(snapshot.runningProcesses, GOOGLE);
        boolean needs = needsReloadForUpdate(
                current, marked, running, snapshot.googleTargetStale());
        if (!needs && current > 0L && marked != current && !running) {
            markGoogleCurrent(context);
        }
        return needs;
    }

    static boolean systemUiNeedsReload(Context context, LsposedStatusManager.Snapshot snapshot) {
        if (context == null || snapshot == null || !snapshot.systemUiScopeEnabled) return false;
        long current = apkUpdateTime(context);
        long marked = preference(context).getLong(K_SYSTEMUI_UPDATE_TIME, 0L);
        boolean running = hasProcess(snapshot.runningProcesses, SYSTEM_UI);
        return needsReloadForUpdate(
                current, marked, running, snapshot.systemUiTargetStale());
    }

    static boolean needsReloadForUpdate(
            long currentUpdateTime, long markedUpdateTime, boolean targetRunning, boolean stale) {
        if (stale) return true;
        return targetRunning
                && currentUpdateTime > 0L
                && markedUpdateTime > 0L
                && currentUpdateTime != markedUpdateTime;
    }

    static boolean reloadGoogleForCtsAsync(Context context, Consumer<Result> callback) {
        if (context == null) return false;
        Context app = context.getApplicationContext();
        if (!GOOGLE_RELOAD_RUNNING.compareAndSet(false, true)) return false;

        GoogleCtsBridgeController.onNativeRelease(app, "hook_hot_reload");
        new FloatSettings(app).clearGoogleCtsSession();
        LsposedStatusManager.clearGoogleCtsSessionRemoteNow();

        IO.execute(() -> {
            Result result;
            FloatSettings fs = new FloatSettings(app);
            if (!fs.canUseRoot()) {
                result = new Result(false, false, false, false,
                        "自动重载 Google Hook 需要启用增强模式和 Root 功能");
            } else {
                RootCommandExecutor.Result command = RootCommandExecutor.runText(
                        killGoogleScript(), 8L, 16 * 1024);
                boolean ok = command.success();
                if (ok) markGoogleCurrent(app);
                String detail = command.text();
                if (!ok && detail.isBlank()) {
                    detail = command.failureMessage("重新加载 Google Hook 超时");
                }
                result = new Result(ok, ok, false,
                        LsposedStatusManager.snapshot().systemServerTargetStale(), detail);
            }

            GOOGLE_RELOAD_RUNNING.set(false);
            LsposedStatusManager.refreshAsync();
            deliver(app, callback, result);
        });
        return true;
    }

    static boolean reloadReloadableTargetsAsync(Context context, Consumer<Result> callback) {
        if (context == null) return false;
        Context app = context.getApplicationContext();
        if (!FULL_RELOAD_RUNNING.compareAndSet(false, true)) return false;

        LsposedStatusManager.Snapshot before = LsposedStatusManager.snapshot();
        boolean google = googleNeedsReload(app, before)
                || hasProcess(before.runningProcesses, GOOGLE);
        boolean systemUi = systemUiNeedsReload(app, before);
        boolean systemServerNeedsReboot = before.systemServerTargetStale();

        GoogleCtsBridgeController.onNativeRelease(app, "manual_hook_reload");
        LsposedStatusManager.clearGoogleCtsSessionRemoteNow();

        IO.execute(() -> {
            Result result;
            FloatSettings fs = new FloatSettings(app);
            if (!fs.canUseRoot()) {
                result = new Result(false, false, false, systemServerNeedsReboot,
                        "重新加载 Hook 需要启用增强模式和 Root 功能");
            } else {
                String script = buildReloadScript(google, systemUi);
                RootCommandExecutor.Result command = RootCommandExecutor.runText(
                        script, 10L, 24 * 1024);
                boolean ok = command.success();
                if (ok) {
                    if (google) markGoogleCurrent(app);
                    if (systemUi) markSystemUiCurrent(app);
                }
                String detail = command.text();
                if (!ok && detail.isBlank()) {
                    detail = command.failureMessage("重新加载 Hook 超时");
                }
                result = new Result(ok, ok && google, ok && systemUi,
                        systemServerNeedsReboot, detail);
            }

            FULL_RELOAD_RUNNING.set(false);
            LsposedStatusManager.refreshAsync();
            deliver(app, callback, result);
        });
        return true;
    }

    private static String buildReloadScript(boolean google, boolean systemUi) {
        StringBuilder script = new StringBuilder("COUNT=0; ");
        if (google) script.append(killPackageProcessesSnippet(GOOGLE, "GOOGLE"));
        if (systemUi) script.append(killPackageProcessesSnippet(SYSTEM_UI, "SYSTEMUI"));
        script.append("echo FLOATLENS_HOOK_RELOAD_DONE count=$COUNT; ");
        return script.toString();
    }

    private static String killGoogleScript() {
        return "COUNT=0; "
                + killPackageProcessesSnippet(GOOGLE, "GOOGLE")
                + "echo FLOATLENS_GOOGLE_HOOK_RELOAD count=$COUNT; ";
    }

    private static String killPackageProcessesSnippet(String packageName, String label) {
        String escaped = packageName.replace(".", "\\.");
        return "for PID in $(ps -A -o PID,NAME 2>/dev/null "
                + "| awk '$2 ~ /^" + escaped + "(:|$)/ {print $1}'); "
                + "do kill -9 \"$PID\" >/dev/null 2>&1 || true; COUNT=$((COUNT+1)); done; "
                + "sleep 0.12; echo \"FLOATLENS_" + label + "_KILLED=$COUNT\"; ";
    }

    private static SharedPreferences preference(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static void markGoogleCurrent(Context context) {
        long current = apkUpdateTime(context);
        if (current > 0L) {
            preference(context).edit().putLong(K_GOOGLE_UPDATE_TIME, current).apply();
        }
    }

    private static void markSystemUiCurrent(Context context) {
        long current = apkUpdateTime(context);
        if (current > 0L) {
            preference(context).edit().putLong(K_SYSTEMUI_UPDATE_TIME, current).apply();
        }
    }

    private static long apkUpdateTime(Context context) {
        if (context == null) return 0L;
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    context.getPackageName(), 0);
            return info == null ? 0L : info.lastUpdateTime;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static boolean hasProcess(List<String> processes, String packageName) {
        if (processes == null || packageName == null) return false;
        for (String process : processes) {
            if (process == null) continue;
            int state = process.indexOf('[');
            String name = state > 0 ? process.substring(0, state) : process;
            if (name.equals(packageName) || name.startsWith(packageName + ":")) return true;
        }
        return false;
    }

    private static void deliver(Context app, Consumer<Result> callback, Result result) {
        if (callback == null) return;
        MAIN.post(() -> callback.accept(result));
    }
}
