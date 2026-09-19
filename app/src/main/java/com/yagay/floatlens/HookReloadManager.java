package com.yagay.floatlens;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Separates app updates from LSPosed hook updates.
 *
 * <p>BuildConfig carries three fingerprints generated only from hook runtime sources. Updating UI,
 * OCR, result dialogs, settings or other app-side code does not change these fingerprints and
 * therefore does not require any target-process restart.</p>
 */
final class HookReloadManager {
    private static final String PREF = "floatlens_hook_reload";
    private static final String K_GOOGLE_APPLIED = "google_hook_fingerprint_applied_v2";
    private static final String K_SYSTEMUI_APPLIED = "systemui_hook_fingerprint_applied_v2";
    private static final String K_SYSTEM_SERVER_APPLIED = "system_server_hook_fingerprint_applied_v2";
    private static final String K_BOOT_ID = "hook_fingerprint_boot_id_v2";

    private static final String GOOGLE = GoogleCtsContract.GOOGLE_PACKAGE;
    private static final String SYSTEM_UI = "com.android.systemui";

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FloatLens-hook-reload");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean GOOGLE_RELOAD_RUNNING = new AtomicBoolean(false);
    private static final AtomicBoolean FULL_RELOAD_RUNNING = new AtomicBoolean(false);
    private static volatile Context appContext;

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
            if (googleReloaded) out.append("Google Hook 已热重载");
            if (systemUiReloaded) {
                if (out.length() > 0) out.append("；");
                out.append("SystemUI Hook 已热重载");
            }
            if (out.length() == 0) out.append("Google / SystemUI Hook 没有变化，无需重载");
            if (systemServerNeedsReboot) {
                out.append("；system_server Hook 有变化，需要重启手机后才会加载");
            }
            return out.toString();
        }
    }

    private HookReloadManager() {}

    static void initialize(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        appContext = app;

        SharedPreferences p = preference(app);
        String bootId = readBootId();
        String oldBootId = p.getString(K_BOOT_ID, "");
        boolean first = !p.contains(K_GOOGLE_APPLIED)
                || !p.contains(K_SYSTEMUI_APPLIED)
                || !p.contains(K_SYSTEM_SERVER_APPLIED);
        boolean rebooted = !bootId.isBlank() && !oldBootId.isBlank() && !bootId.equals(oldBootId);

        SharedPreferences.Editor edit = p.edit();
        if (first || rebooted) {
            // First rollout: current running hooks are source-identical to the baseline build.
            // After an actual device reboot every scoped target necessarily loads current APK code.
            edit.putString(K_GOOGLE_APPLIED, BuildConfig.GOOGLE_HOOK_FINGERPRINT)
                    .putString(K_SYSTEMUI_APPLIED, BuildConfig.SYSTEMUI_HOOK_FINGERPRINT)
                    .putString(K_SYSTEM_SERVER_APPLIED, BuildConfig.SYSTEM_SERVER_HOOK_FINGERPRINT);
        }
        if (!bootId.isBlank()) edit.putString(K_BOOT_ID, bootId);
        edit.apply();

        DiagnosticLog.i(app, "HOOK_RELOAD",
                "init first=" + first
                        + " rebooted=" + rebooted
                        + " google=" + shortHash(BuildConfig.GOOGLE_HOOK_FINGERPRINT)
                        + " systemui=" + shortHash(BuildConfig.SYSTEMUI_HOOK_FINGERPRINT)
                        + " system=" + shortHash(BuildConfig.SYSTEM_SERVER_HOOK_FINGERPRINT));
    }

    static boolean googleNeedsReload(Context context, LsposedStatusManager.Snapshot snapshot) {
        if (context == null || snapshot == null) return false;
        Context app = context.getApplicationContext();
        String applied = preference(app).getString(K_GOOGLE_APPLIED, "");
        boolean running = hasProcess(snapshot.runningProcesses, GOOGLE);
        boolean changed = fingerprintNeedsReload(
                BuildConfig.GOOGLE_HOOK_FINGERPRINT, applied, running);
        if (!changed) return false;

        if (!running) {
            // No old process exists. The next Google process will load the current hook directly.
            markGoogleCurrent(app);
            return false;
        }
        return true;
    }

    static boolean systemUiNeedsReload(Context context, LsposedStatusManager.Snapshot snapshot) {
        if (context == null || snapshot == null || !snapshot.systemUiScopeEnabled) return false;
        Context app = context.getApplicationContext();
        String applied = preference(app).getString(K_SYSTEMUI_APPLIED, "");
        boolean running = hasProcess(snapshot.runningProcesses, SYSTEM_UI);
        boolean changed = fingerprintNeedsReload(
                BuildConfig.SYSTEMUI_HOOK_FINGERPRINT, applied, running);
        if (!changed) return false;

        if (!running) {
            markSystemUiCurrent(app);
            return false;
        }
        return true;
    }

    static boolean fingerprintNeedsReload(
            String currentFingerprint, String appliedFingerprint, boolean targetRunning) {
        if (currentFingerprint == null || currentFingerprint.isBlank()) return false;
        if (currentFingerprint.equals(appliedFingerprint)) return false;
        return targetRunning;
    }

    static boolean systemServerNeedsReboot(Context context) {
        if (context == null) return false;
        Context app = context.getApplicationContext();
        return !BuildConfig.SYSTEM_SERVER_HOOK_FINGERPRINT.equals(
                preference(app).getString(K_SYSTEM_SERVER_APPLIED, ""));
    }

    static boolean systemServerHookCurrent() {
        Context app = appContext;
        return app != null && !systemServerNeedsReboot(app);
    }

    static boolean googleHookCurrent(Context context) {
        if (context == null) return false;
        return BuildConfig.GOOGLE_HOOK_FINGERPRINT.equals(
                preference(context).getString(K_GOOGLE_APPLIED, ""));
    }

    static boolean systemUiHookCurrent(Context context) {
        if (context == null) return false;
        return BuildConfig.SYSTEMUI_HOOK_FINGERPRINT.equals(
                preference(context).getString(K_SYSTEMUI_APPLIED, ""));
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
                result = new Result(false, false, false, systemServerNeedsReboot(app),
                        "Google Hook 有变化；自动热重载需要启用增强模式和 Root 功能");
            } else {
                RootCommandExecutor.Result command = RootCommandExecutor.runText(
                        killGoogleScript(), 8L, 16 * 1024);
                boolean ok = command.success();
                if (ok) markGoogleCurrent(app);
                String detail = command.text();
                if (!ok && detail.isBlank()) {
                    detail = command.failureMessage("重新加载 Google Hook 超时");
                }
                result = new Result(ok, ok, false, systemServerNeedsReboot(app), detail);
            }

            GOOGLE_RELOAD_RUNNING.set(false);
            LsposedStatusManager.refreshAsync();
            deliver(app, callback, result);
        });
        return true;
    }

    static boolean reloadChangedTargetsAsync(Context context, Consumer<Result> callback) {
        if (context == null) return false;
        Context app = context.getApplicationContext();
        if (!FULL_RELOAD_RUNNING.compareAndSet(false, true)) return false;

        LsposedStatusManager.Snapshot before = LsposedStatusManager.snapshot();
        boolean google = googleNeedsReload(app, before);
        boolean systemUi = systemUiNeedsReload(app, before);
        boolean systemServer = systemServerNeedsReboot(app);

        if (!google && !systemUi) {
            FULL_RELOAD_RUNNING.set(false);
            deliver(app, callback, new Result(true, false, false, systemServer, ""));
            return true;
        }

        if (google) {
            GoogleCtsBridgeController.onNativeRelease(app, "manual_hook_reload");
            LsposedStatusManager.clearGoogleCtsSessionRemoteNow();
        }

        IO.execute(() -> {
            Result result;
            FloatSettings fs = new FloatSettings(app);
            if (!fs.canUseRoot()) {
                result = new Result(false, false, false, systemServer,
                        "Hook 有变化；热重载 Google / SystemUI 需要启用增强模式和 Root 功能");
            } else {
                RootCommandExecutor.Result command = RootCommandExecutor.runText(
                        buildReloadScript(google, systemUi), 10L, 24 * 1024);
                boolean ok = command.success();
                if (ok) {
                    if (google) markGoogleCurrent(app);
                    if (systemUi) markSystemUiCurrent(app);
                }
                String detail = command.text();
                if (!ok && detail.isBlank()) {
                    detail = command.failureMessage("重新加载 Hook 超时");
                }
                result = new Result(ok, ok && google, ok && systemUi, systemServer, detail);
            }

            FULL_RELOAD_RUNNING.set(false);
            LsposedStatusManager.refreshAsync();
            deliver(app, callback, result);
        });
        return true;
    }

    static String statusSummary(Context context, LsposedStatusManager.Snapshot snapshot) {
        if (context == null) return "Hook 状态未知";
        boolean google = googleNeedsReload(context, snapshot);
        boolean systemUi = systemUiNeedsReload(context, snapshot);
        boolean system = systemServerNeedsReboot(context);
        return "Hook 代际：Google " + (google ? "需热重载" : "当前")
                + " · SystemUI " + (systemUi ? "需热重载" : "当前")
                + " · system_server " + (system ? "需重启" : "当前");
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
        preference(context).edit()
                .putString(K_GOOGLE_APPLIED, BuildConfig.GOOGLE_HOOK_FINGERPRINT).apply();
    }

    private static void markSystemUiCurrent(Context context) {
        preference(context).edit()
                .putString(K_SYSTEMUI_APPLIED, BuildConfig.SYSTEMUI_HOOK_FINGERPRINT).apply();
    }

    private static String readBootId() {
        try (BufferedReader reader = new BufferedReader(
                new FileReader("/proc/sys/kernel/random/boot_id"))) {
            String value = reader.readLine();
            return value == null ? "" : value.trim();
        } catch (Throwable ignored) {
            return "";
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
        if (callback != null) app.getMainExecutor().execute(() -> callback.accept(result));
    }

    private static String shortHash(String value) {
        if (value == null || value.isBlank()) return "none";
        return value.substring(0, Math.min(10, value.length()));
    }
}
