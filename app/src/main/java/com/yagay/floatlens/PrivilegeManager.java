package com.yagay.floatlens;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
        return settings != null && settings.enhancedMode() && settings.rootEnabled();
    }

    /** Generic controlled API-102 provider availability. */
    public static boolean lsposedProviderAvailable() {
        return LsposedStatusManager.providerAvailable();
    }

    /** Secure screenshot is a system_server capability, so SystemUI-only loading is not enough. */
    public static boolean lsposedSecureScreenshotProviderAvailable() {
        LsposedStatusManager.Snapshot s = LsposedStatusManager.snapshot();
        return s.serviceConnected && s.remoteConfigReady && s.systemLoaded;
    }

    public static boolean canUseLsposed(Context context) {
        return canUseLsposed(new FloatSettings(context));
    }

    public static boolean canUseLsposed(FloatSettings settings) {
        return settings != null && lsposedProviderAvailable()
                && settings.enhancedMode() && settings.lsposedEnabled();
    }

    public static boolean canUseLsposedSecureScreenshot(FloatSettings settings) {
        return settings != null && lsposedSecureScreenshotProviderAvailable()
                && settings.enhancedMode()
                && settings.lsposedEnabled()
                && settings.lsposedSecureScreenshot();
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
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new RootStatus(false, "授权检测超时");
            }
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null && out.length() < 512) {
                    if (out.length() > 0) out.append(' ');
                    out.append(line);
                }
            }
            String detail = out.toString().trim();
            boolean granted = process.exitValue() == 0 && detail.contains("uid=0");
            if (detail.isBlank()) detail = "su exit=" + process.exitValue();
            return new RootStatus(granted, detail);
        } catch (Throwable t) {
            String message = t.getMessage();
            return new RootStatus(false,
                    (message == null || message.isBlank()) ? t.getClass().getSimpleName() : message);
        } finally {
            if (process != null) {
                try { process.destroy(); } catch (Throwable ignored) {}
            }
        }
    }

    /** Human-readable stored Root test result; does not execute su. */
    public static String storedRootStatus(Context context) {
        FloatSettings settings = new FloatSettings(context);
        if (settings.rootLastCheckMs() <= 0L) return "尚未检测";
        return settings.rootLastGranted() ? "已授权" : "未授权 / 不可用";
    }
}
