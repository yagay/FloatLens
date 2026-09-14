package com.yagay.floatlens;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Central privilege gate for optional Root / LSPosed enhancements.
 *
 * <p>Feature code should never call {@code su} or an LSPosed provider by checking a raw preference
 * directly. Always go through this class so the master switch and fallback policy are respected.
 * When enhanced mode is off, FloatLens stays on its normal Android / Accessibility path.</p>
 */
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

    public static boolean canUseLsposed(Context context) {
        return canUseLsposed(new FloatSettings(context));
    }

    public static boolean canUseLsposed(FloatSettings settings) {
        return settings != null && settings.enhancedMode() && settings.lsposedEnabled();
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
            case LSPOSED -> "LSPosed 增强";
            case ROOT_AND_LSPOSED -> "Root + LSPosed 增强";
            default -> "普通模式";
        };
    }

    /**
     * Explicit Root authorization test. This is only called after the user taps the test button;
     * merely opening settings must not unexpectedly show a root-manager authorization prompt.
     */
    public static void checkRootAsync(Context context, Consumer<RootStatus> callback) {
        Context app = context.getApplicationContext();
        ROOT_IO.execute(() -> {
            RootStatus status = runRootCheck();
            long now = System.currentTimeMillis();
            app.getSharedPreferences(FloatSettings.PREF, Context.MODE_PRIVATE).edit()
                    .putBoolean(FloatSettings.K_ROOT_LAST_GRANTED, status.granted)
                    .putLong(FloatSettings.K_ROOT_LAST_CHECK, now)
                    .putString(FloatSettings.K_ROOT_LAST_DETAIL, status.detail)
                    .apply();
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
                    if (!out.isEmpty()) out.append(' ');
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

    /** Called by the hook-log receiver when a live LSPosed-injected process talks back to FloatLens. */
    public static void markLsposedSeen(Context context, String source) {
        Context app = context.getApplicationContext();
        app.getSharedPreferences(FloatSettings.PREF, Context.MODE_PRIVATE).edit()
                .putLong(FloatSettings.K_LSPOSED_LAST_SEEN, System.currentTimeMillis())
                .putString(FloatSettings.K_LSPOSED_LAST_SOURCE,
                        source == null || source.isBlank() ? "hook" : source)
                .apply();
    }

    public static boolean lsposedCommunicationSeen(Context context) {
        return new FloatSettings(context).lsposedLastSeenMs() > 0L;
    }

    /** Human-readable stored Root test result; does not execute su. */
    public static String storedRootStatus(Context context) {
        SharedPreferences p = context.getSharedPreferences(FloatSettings.PREF, Context.MODE_PRIVATE);
        long at = p.getLong(FloatSettings.K_ROOT_LAST_CHECK, 0L);
        if (at <= 0L) return "尚未检测";
        boolean granted = p.getBoolean(FloatSettings.K_ROOT_LAST_GRANTED, false);
        return granted ? "已授权" : "未授权 / 不可用";
    }
}
