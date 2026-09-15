package com.yagay.floatlens;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import io.github.libxposed.service.HookedTarget;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/** App-side LSPosed framework/status + writable Remote Preferences bridge. */
public final class LsposedStatusManager implements XposedServiceHelper.OnServiceListener {
    public interface Listener {
        void onStatusChanged(Snapshot snapshot);
    }

    public static final class Snapshot {
        public final boolean serviceConnected;
        public final String frameworkName;
        public final String frameworkVersion;
        public final int apiVersion;
        public final List<String> scope;
        public final List<String> runningProcesses;
        public final boolean systemScopeEnabled;
        public final boolean systemUiScopeEnabled;
        public final boolean systemLoaded;
        public final boolean systemUiLoaded;
        public final boolean remoteConfigReady;
        public final boolean remoteEnhancedMode;
        public final boolean remoteLsposedEnabled;
        public final long remoteUpdatedAt;
        public final String detail;

        private Snapshot(boolean serviceConnected,
                         String frameworkName,
                         String frameworkVersion,
                         int apiVersion,
                         List<String> scope,
                         List<String> runningProcesses,
                         boolean systemScopeEnabled,
                         boolean systemUiScopeEnabled,
                         boolean systemLoaded,
                         boolean systemUiLoaded,
                         boolean remoteConfigReady,
                         boolean remoteEnhancedMode,
                         boolean remoteLsposedEnabled,
                         long remoteUpdatedAt,
                         String detail) {
            this.serviceConnected = serviceConnected;
            this.frameworkName = safe(frameworkName);
            this.frameworkVersion = safe(frameworkVersion);
            this.apiVersion = apiVersion;
            this.scope = Collections.unmodifiableList(new ArrayList<>(scope));
            this.runningProcesses = Collections.unmodifiableList(new ArrayList<>(runningProcesses));
            this.systemScopeEnabled = systemScopeEnabled;
            this.systemUiScopeEnabled = systemUiScopeEnabled;
            this.systemLoaded = systemLoaded;
            this.systemUiLoaded = systemUiLoaded;
            this.remoteConfigReady = remoteConfigReady;
            this.remoteEnhancedMode = remoteEnhancedMode;
            this.remoteLsposedEnabled = remoteLsposedEnabled;
            this.remoteUpdatedAt = remoteUpdatedAt;
            this.detail = safe(detail);
        }

        public boolean remoteProviderEnabled() {
            return remoteConfigReady
                    && LsposedRuntimeConfig.isEnabled(remoteEnhancedMode, remoteLsposedEnabled);
        }

        private static Snapshot disconnected(String detail) {
            return new Snapshot(false, "", "", 0,
                    Collections.emptyList(), Collections.emptyList(),
                    false, false, false, false,
                    false, false, false, 0L, detail);
        }

        private static String safe(String value) {
            return value == null ? "" : value;
        }
    }

    private static final LsposedStatusManager INSTANCE = new LsposedStatusManager();
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FloatLens-lsposed-status");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final CopyOnWriteArraySet<Listener> listeners = new CopyOnWriteArraySet<>();
    private volatile XposedService service;
    private volatile Context appContext;
    private volatile SharedPreferences localPreferences;
    private volatile Snapshot snapshot = Snapshot.disconnected("等待 LSPosed 服务连接");

    private final SharedPreferences.OnSharedPreferenceChangeListener localPreferenceListener =
            (preferences, key) -> {
                if (FloatSettings.K_ENHANCED_MODE.equals(key)
                        || FloatSettings.K_LSPOSED_ENABLED.equals(key)
                        || FloatSettings.K_LSPOSED_SECURE_SCREENSHOT.equals(key)) {
                    syncRuntimeConfigAsync();
                }
            };

    private LsposedStatusManager() {}

    /** Must be called once from the module app process. Safe to call repeatedly. */
    public static void initialize(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        INSTANCE.appContext = app;
        if (INITIALIZED.compareAndSet(false, true)) {
            SharedPreferences preferences = app.getSharedPreferences(FloatSettings.PREF, Context.MODE_PRIVATE);
            INSTANCE.localPreferences = preferences;
            preferences.registerOnSharedPreferenceChangeListener(INSTANCE.localPreferenceListener);
            XposedServiceHelper.registerListener(INSTANCE);
        }
    }

    public static Snapshot snapshot() {
        return INSTANCE.snapshot;
    }

    public static boolean frameworkConnected() {
        return INSTANCE.snapshot.serviceConnected;
    }

    /**
     * Provider infrastructure is considered available only when the framework service and writable
     * Remote Preferences work, and at least one recommended target process has actually loaded the
     * module. This is intentionally stricter than merely detecting LSPosed installation.
     */
    public static boolean providerAvailable() {
        Snapshot s = INSTANCE.snapshot;
        return s.serviceConnected && s.remoteConfigReady && (s.systemLoaded || s.systemUiLoaded);
    }

    public static void refreshAsync() {
        INSTANCE.refreshFromService();
    }

    public static void syncRuntimeConfigAsync() {
        INSTANCE.syncRuntimeConfig();
    }

    /** Opens a fail-closed, automatically expiring secure-capture request window. */
    public static void beginSecureCaptureWindow(long ttlMs, Consumer<Boolean> callback) {
        INSTANCE.setSecureCaptureWindow(Math.max(250L, Math.min(ttlMs, 2500L)), callback);
    }

    /** Clears the secure-capture request immediately; expiry remains a second fail-safe. */
    public static void endSecureCaptureWindow() {
        INSTANCE.clearSecureCaptureWindow();
    }

    public static void addListener(Listener listener, boolean notifyImmediately) {
        if (listener == null) return;
        INSTANCE.listeners.add(listener);
        if (notifyImmediately) {
            Snapshot current = INSTANCE.snapshot;
            MAIN.post(() -> {
                if (INSTANCE.listeners.contains(listener)) listener.onStatusChanged(current);
            });
        }
    }

    public static void removeListener(Listener listener) {
        if (listener != null) INSTANCE.listeners.remove(listener);
    }

    @Override
    public void onServiceBind(XposedService service) {
        this.service = service;
        syncRuntimeConfig();
    }

    @Override
    public void onServiceDied(XposedService service) {
        if (this.service == service) this.service = null;
        publish(Snapshot.disconnected("LSPosed 服务已断开"));
    }

    private void syncRuntimeConfig() {
        XposedService current = service;
        SharedPreferences local = localPreferences;
        if (current == null || local == null) {
            refreshFromService();
            return;
        }

        boolean enhanced = local.getBoolean(FloatSettings.K_ENHANCED_MODE, false);
        boolean lsposed = local.getBoolean(FloatSettings.K_LSPOSED_ENABLED, false);
        boolean secureScreenshot = local.getBoolean(FloatSettings.K_LSPOSED_SECURE_SCREENSHOT, false);
        long updatedAt = System.currentTimeMillis();
        IO.execute(() -> {
            try {
                SharedPreferences remote = current.getRemotePreferences(LsposedRuntimeConfig.GROUP);
                if (remote == null) {
                    publishSnapshot(current, false, false, false, 0L,
                            "框架没有提供可写 Remote Preferences");
                    return;
                }
                boolean committed = remote.edit()
                        .putInt(LsposedRuntimeConfig.K_SCHEMA_VERSION, LsposedRuntimeConfig.SCHEMA_VERSION)
                        .putBoolean(LsposedRuntimeConfig.K_ENHANCED_MODE, enhanced)
                        .putBoolean(LsposedRuntimeConfig.K_LSPOSED_ENABLED, lsposed)
                        .putBoolean(LsposedRuntimeConfig.K_SECURE_SCREENSHOT_ENABLED, secureScreenshot)
                        .putLong(LsposedRuntimeConfig.K_SECURE_CAPTURE_UNTIL_MS, 0L)
                        .putLong(LsposedRuntimeConfig.K_UPDATED_AT, updatedAt)
                        .commit();
                if (!committed) {
                    publishSnapshot(current, false, false, false, 0L,
                            "Remote Preferences 写入失败");
                    return;
                }
                publishSnapshot(current,
                        remote.getInt(LsposedRuntimeConfig.K_SCHEMA_VERSION, 0)
                                >= LsposedRuntimeConfig.SCHEMA_VERSION,
                        remote.getBoolean(LsposedRuntimeConfig.K_ENHANCED_MODE, false),
                        remote.getBoolean(LsposedRuntimeConfig.K_LSPOSED_ENABLED, false),
                        remote.getLong(LsposedRuntimeConfig.K_UPDATED_AT, 0L),
                        "");
            } catch (UnsupportedOperationException unsupported) {
                publishSnapshot(current, false, false, false, 0L,
                        "当前框架不支持 Remote Preferences");
            } catch (Throwable t) {
                publishSnapshot(current, false, false, false, 0L,
                        "同步 LSPosed 配置失败：" + messageOf(t));
            }
        });
    }

    private void setSecureCaptureWindow(long ttlMs, Consumer<Boolean> callback) {
        XposedService current = service;
        SharedPreferences local = localPreferences;
        Context app = appContext;
        if (current == null || local == null || app == null || !providerAvailable()) {
            postResult(app, callback, false);
            return;
        }
        boolean enhanced = local.getBoolean(FloatSettings.K_ENHANCED_MODE, false);
        boolean lsposed = local.getBoolean(FloatSettings.K_LSPOSED_ENABLED, false);
        boolean feature = local.getBoolean(FloatSettings.K_LSPOSED_SECURE_SCREENSHOT, false);
        if (!LsposedRuntimeConfig.isSecureCaptureActive(
                enhanced, lsposed, feature, System.currentTimeMillis() + ttlMs,
                System.currentTimeMillis())) {
            postResult(app, callback, false);
            return;
        }
        long until = System.currentTimeMillis() + ttlMs;
        IO.execute(() -> {
            boolean ok = false;
            try {
                SharedPreferences remote = current.getRemotePreferences(LsposedRuntimeConfig.GROUP);
                ok = remote != null && remote.edit()
                        .putInt(LsposedRuntimeConfig.K_SCHEMA_VERSION, LsposedRuntimeConfig.SCHEMA_VERSION)
                        .putBoolean(LsposedRuntimeConfig.K_ENHANCED_MODE, enhanced)
                        .putBoolean(LsposedRuntimeConfig.K_LSPOSED_ENABLED, lsposed)
                        .putBoolean(LsposedRuntimeConfig.K_SECURE_SCREENSHOT_ENABLED, feature)
                        .putLong(LsposedRuntimeConfig.K_SECURE_CAPTURE_UNTIL_MS, until)
                        .putLong(LsposedRuntimeConfig.K_UPDATED_AT, System.currentTimeMillis())
                        .commit();
            } catch (Throwable t) {
                DiagnosticLog.i(app, "LSPOSED_SECURE_CAPTURE", "open failed=" + messageOf(t));
            }
            boolean result = ok;
            postResult(app, callback, result);
        });
    }

    private void clearSecureCaptureWindow() {
        XposedService current = service;
        Context app = appContext;
        if (current == null) return;
        IO.execute(() -> {
            try {
                SharedPreferences remote = current.getRemotePreferences(LsposedRuntimeConfig.GROUP);
                if (remote != null) {
                    remote.edit()
                            .putLong(LsposedRuntimeConfig.K_SECURE_CAPTURE_UNTIL_MS, 0L)
                            .putLong(LsposedRuntimeConfig.K_UPDATED_AT, System.currentTimeMillis())
                            .commit();
                }
            } catch (Throwable t) {
                if (app != null) {
                    DiagnosticLog.i(app, "LSPOSED_SECURE_CAPTURE", "clear failed=" + messageOf(t));
                }
            }
        });
    }

    private static void postResult(Context app, Consumer<Boolean> callback, boolean result) {
        if (callback == null) return;
        if (app != null) app.getMainExecutor().execute(() -> callback.accept(result));
        else MAIN.post(() -> callback.accept(result));
    }

    private void refreshFromService() {
        XposedService current = service;
        if (current == null) {
            publish(Snapshot.disconnected("未连接到 LSPosed 服务"));
            return;
        }
        IO.execute(() -> {
            try {
                SharedPreferences remote = current.getRemotePreferences(LsposedRuntimeConfig.GROUP);
                if (remote == null) {
                    publishSnapshot(current, false, false, false, 0L,
                            "框架没有提供 Remote Preferences");
                    return;
                }
                publishSnapshot(current,
                        remote.getInt(LsposedRuntimeConfig.K_SCHEMA_VERSION, 0)
                                >= LsposedRuntimeConfig.SCHEMA_VERSION,
                        remote.getBoolean(LsposedRuntimeConfig.K_ENHANCED_MODE, false),
                        remote.getBoolean(LsposedRuntimeConfig.K_LSPOSED_ENABLED, false),
                        remote.getLong(LsposedRuntimeConfig.K_UPDATED_AT, 0L),
                        "");
            } catch (UnsupportedOperationException unsupported) {
                publishSnapshot(current, false, false, false, 0L,
                        "当前框架不支持 Remote Preferences");
            } catch (Throwable t) {
                publishSnapshot(current, false, false, false, 0L,
                        "读取 LSPosed 状态失败：" + messageOf(t));
            }
        });
    }

    private void publishSnapshot(XposedService current,
                                 boolean remoteConfigReady,
                                 boolean remoteEnhancedMode,
                                 boolean remoteLsposedEnabled,
                                 long remoteUpdatedAt,
                                 String detail) {
        try {
            List<String> scope = copyStrings(current.getScope());
            List<HookedTarget> targets = current.getRunningTargets();
            List<String> running = new ArrayList<>();
            boolean systemLoaded = false;
            boolean systemUiLoaded = false;
            if (targets != null) {
                for (HookedTarget target : targets) {
                    if (target == null) continue;
                    String process = target.getProcessName();
                    if (process == null || process.isBlank()) continue;
                    running.add(process);
                    if (isSystemProcess(process)) systemLoaded = true;
                    if (isSystemUiProcess(process)) systemUiLoaded = true;
                }
            }
            Collections.sort(running);
            publish(new Snapshot(true,
                    current.getFrameworkName(),
                    current.getFrameworkVersion(),
                    current.getApiVersion(),
                    scope,
                    running,
                    scope.contains("system"),
                    scope.contains("com.android.systemui"),
                    systemLoaded,
                    systemUiLoaded,
                    remoteConfigReady,
                    remoteEnhancedMode,
                    remoteLsposedEnabled,
                    remoteUpdatedAt,
                    detail));
        } catch (Throwable t) {
            publish(new Snapshot(true, "", "", 0,
                    Collections.emptyList(), Collections.emptyList(),
                    false, false, false, false,
                    remoteConfigReady, remoteEnhancedMode, remoteLsposedEnabled, remoteUpdatedAt,
                    detail.isBlank() ? "读取 LSPosed 目标状态失败：" + messageOf(t) : detail));
        }
    }

    private static List<String> copyStrings(List<String> values) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        ArrayList<String> out = new ArrayList<>(values.size());
        for (String value : values) {
            if (value != null && !value.isBlank()) out.add(value);
        }
        return out;
    }

    static boolean isSystemProcess(String processName) {
        return "system_server".equals(processName) || "system".equals(processName);
    }

    static boolean isSystemUiProcess(String processName) {
        return "com.android.systemui".equals(processName)
                || processName.startsWith("com.android.systemui:");
    }

    private static String messageOf(Throwable t) {
        String message = t.getMessage();
        return (message == null || message.isBlank()) ? t.getClass().getSimpleName() : message;
    }

    private void publish(Snapshot next) {
        snapshot = next;
        MAIN.post(() -> {
            for (Listener listener : listeners) {
                try {
                    listener.onStatusChanged(next);
                } catch (Throwable ignored) {
                    // A detached UI listener must never break the status bridge.
                }
            }
        });
    }
}
