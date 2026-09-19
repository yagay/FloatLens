package com.yagay.floatlens;

import android.content.Context;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Central privilege gate for optional Root / LSPosed enhancements. */
public final class PrivilegeManager {
    public enum Mode {
        NORMAL,
        ROOT,
        LSPOSED,
        ROOT_AND_LSPOSED
    }

    public static final class RootStatus {
        public final boolean granted;
        public final String detail;

        RootStatus(boolean granted, String detail) {
            this.granted = granted;
            this.detail = detail == null ? "" : detail;
        }
    }

    private static final ExecutorService ROOT_IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FloatLens-root-check");
        t.setDaemon(true);
        return t;
    });

    private PrivilegeManager() {}

    public static boolean canUseRoot(Context context) {
        return canUseRoot(new FloatSettings(context));
    }

    public static boolean canUseRoot(FloatSettings settings) {
        if (settings == null) return false;
        FloatSettingsDomains.Privilege privilege = FloatSettingsDomains.privilege(settings);
        return privilege.enhanced() && privilege.rootEnabled();
    }

    /** Generic controlled API-102 provider availability. */
    public static boolean lsposedProviderAvailable() {
        return LsposedStatusManager.providerAvailable();
    }

    /** Secure screenshot is a system_server capability, so SystemUI-only loading is not enough. */
    public static boolean lsposedSecureScreenshotProviderAvailable() {
        LsposedStatusManager.Snapshot s = LsposedStatusManager.snapshot();
        return s.serviceConnected && s.remoteConfigReady && s.systemLoaded
                && HookReloadManager.systemServerHookCurrent();
    }

    public static boolean canUseLsposed(Context context) {
        return canUseLsposed(new FloatSettings(context));
    }

    public static boolean canUseLsposed(FloatSettings settings) {
        if (settings == null || !lsposedProviderAvailable()) return false;
        FloatSettingsDomains.Privilege privilege = FloatSettingsDomains.privilege(settings);
        return privilege.enhanced() && privilege.lsposedEnabled();
    }

    public static boolean canUseLsposedSecureScreenshot(FloatSettings settings) {
        if (settings == null || !lsposedSecureScreenshotProviderAvailable()) return false;
        FloatSettingsDomains.Privilege privilege = FloatSettingsDomains.privilege(settings);
        return privilege.enhanced()
                && privilege.lsposedEnabled()
                && privilege.secureScreenshotEnabled();
    }

    public static Mode mode(FloatSettings settings) {
        boolean root = canUseRoot(settings);
        boolean lsposed = canUseLsposed(settings);
        if (root && lsposed) return Mode.ROOT_AND_LSPOSED;
        if (root) return Mode.ROOT;
        if (lsposed) return Mode.LSPOSED;
        return Mode.NORMAL;
    }

    public static String modeLabel(FloatSettings settings) {
        return switch (mode(settings)) {
            case ROOT -> "Root 增强";
            case LSPOSED -> "LSPosed Provider";
            case ROOT_AND_LSPOSED -> "Root + LSPosed Provider";
            default -> "普通模式";
        };
    }

    /** Explicit Root authorization test. Opening settings alone never calls su. */
    public static void checkRootAsync(Context context, Consumer<RootStatus> callback) {
        Context app = context.getApplicationContext();
        ROOT_IO.execute(() -> {
            RootStatus status = runRootCheck();
            new FloatSettings(app).saveRootCheck(
                    status.granted, System.currentTimeMillis(), status.detail);
            DiagnosticLog.i(app, "PRIVILEGE", "root check granted=" + status.granted
                    + " detail=" + status.detail);
            if (callback != null) app.getMainExecutor().execute(() -> callback.accept(status));
        });
    }

    private static RootStatus runRootCheck() {
        RootCommandExecutor.Result result = RootCommandExecutor.runText("id", 5, 4 * 1024);
        String detail = result.text();
        boolean granted = result.success() && detail.contains("uid=0");
        if (detail.isBlank()) detail = result.failureMessage("授权检测超时");
        return new RootStatus(granted, detail);
    }

    /** Human-readable stored Root test result; does not execute su. */
    public static String storedRootStatus(Context context) {
        FloatSettings settings = new FloatSettings(context);
        if (settings.rootLastCheckMs() <= 0L) return "尚未检测";
        return settings.rootLastGranted() ? "已授权" : "未授权 / 不可用";
    }
}
