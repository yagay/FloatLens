package com.yagay.floatlens;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

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
        /** True only when the current APK version is UP_TO_DATE in system_server. */
        public final boolean systemLoaded;
        /** True only when the current APK version is UP_TO_DATE in SystemUI. */
        public final boolean systemUiLoaded;
        public final boolean remoteConfigReady;
        public final boolean remoteEnhancedMode;
        public final boolean remoteLsposedEnabled;
        public final boolean remoteSecureScreenshotEnabled;
        public final long remoteSecureCaptureArmedUntil;
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
                         boolean remoteSecureScreenshotEnabled,
                         long remoteSecureCaptureArmedUntil,
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
            this.remoteSecureScreenshotEnabled = remoteSecureScreenshotEnabled;
            this.remoteSecureCaptureArmedUntil = remoteSecureCaptureArmedUntil;
            this.remoteUpdatedAt = remoteUpdatedAt;
            this.detail = safe(detail);
        }

        public boolean remoteProviderEnabled() {
            return remoteConfigReady
                    && LsposedRuntimeConfig.isEnabled(remoteEnhancedMode, remoteLsposedEnabled);
        }

        public boolean googleScopeEnabled() {
            return scope.contains(GoogleCtsContract.GOOGLE_PACKAGE);
        }

        /** True when any running Google target still has an older FloatLens module generation. */
        public boolean googleTargetStale() {
            String expected = "[UP_TO_DATE v" + BuildConfig.VERSION_CODE + "]";
            for (String process : runningProcesses) {
                if (process != null && process.startsWith(GoogleCtsContract.GOOGLE_PACKAGE)
                        && !process.contains(expected)) return true;
            }
            return false;
        }

        /** True when at least one Google target is already running with the current module. */
        public boolean googleTargetLoaded() {
            String expected = "[UP_TO_DATE v" + BuildConfig.VERSION_CODE + "]";
            for (String process : runningProcesses) {
                if (process != null && process.startsWith(GoogleCtsContract.GOOGLE_PACKAGE)
                        && process.contains(expected)) return true;
            }
            return false;
        }

        public boolean remoteSecureCaptureArmed() {
            return remoteConfigReady && LsposedRuntimeConfig.isSecureCaptureActive(
                    remoteEnhancedMode,
                    remoteLsposedEnabled,
                    remoteSecureScreenshotEnabled,
                    remoteSecureCaptureArmedUntil,
                    SystemClock.elapsedRealtime());
        }

        private static Snapshot disconnected(String detail) {
            return new Snapshot(false, "", "", 0,
                    Collections.emptyList(), Collections.emptyList(),
                    false, false, false, false,
                    false, false, false, false, 0L, 0L, detail);
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
    private volatile SharedPreferences localPreferences;
    private volatile Snapshot snapshot = Snapshot.disconnected("等待 LSPosed 服务连接");

    private final SharedPreferences.OnSharedPreferenceChangeListener localPreferenceListener =
            (preferences, key) -> {
                if (FloatSettings.K_ENHANCED_MODE.equals(key)
                        || FloatSettings.K_LSPOSED_ENABLED.equals(key)
                        || FloatSettings.K_LSPOSED_SECURE_SCREENSHOT.equals(key)
                        || FloatSettings.K_DIAGNOSTIC.equals(key)) {
                    syncRuntimeConfigAsync();
                }
            };

    private LsposedStatusManager() {}

    /** Must be called once from the module app process. Safe to call repeatedly. */
    public static void initialize(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
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

    /** Generic provider availability requires at least one current, UP_TO_DATE recommended target. */
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

    /** Arms a very short Google CTS correlation lease in framework-backed preferences. */
    public static boolean armGoogleCtsSessionRemote(String token,
                                                    long triggerElapsed,
                                                    long armedUntilElapsed) {
        return INSTANCE.armGoogleCtsSession(token, triggerElapsed, armedUntilElapsed);
    }

    public static void clearGoogleCtsSessionRemote(String token) {
        INSTANCE.clearGoogleCtsSession(token);
    }

    /**
     * Synchronous safety release used immediately before native Home/gesture navigation.
     * This guarantees Google-process hooks lose ownership before the native CTS Activity starts.
     */
    public static boolean clearGoogleCtsSessionRemoteNow() {
        return INSTANCE.clearGoogleCtsSessionNow(null);
    }

    /** Arms secure-layer capture for one short FloatLens screenshot lease. Callback runs on main. */
    public static void armSecureCaptureAsync(Consumer<Boolean> callback) {
        INSTANCE.armSecureCapture(callback);
    }

    /** Best-effort early disarm; lease expiry is the final safety net. */
    public static void disarmSecureCaptureAsync() {
        INSTANCE.disarmSecureCapture();
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
        boolean diagnostic = local.getBoolean(FloatSettings.K_DIAGNOSTIC, false);
        long updatedAt = System.currentTimeMillis();
        IO.execute(() -> {
            try {
                SharedPreferences remote = current.getRemotePreferences(LsposedRuntimeConfig.GROUP);
                if (remote == null) {
                    publishSnapshot(current, false, false, false, false, 0L, 0L,
                            "框架没有提供可写 Remote Preferences");
                    return;
                }
                boolean committed = remote.edit()
                        .putInt(LsposedRuntimeConfig.K_SCHEMA_VERSION, LsposedRuntimeConfig.SCHEMA_VERSION)
                        .putBoolean(LsposedRuntimeConfig.K_ENHANCED_MODE, enhanced)
                        .putBoolean(LsposedRuntimeConfig.K_LSPOSED_ENABLED, lsposed)
                        .putBoolean(LsposedRuntimeConfig.K_SECURE_SCREENSHOT_ENABLED, secureScreenshot)
                        .putBoolean(LsposedRuntimeConfig.K_DIAGNOSTIC_ENABLED, diagnostic)
                        // Persistent config sync must never mutate short-lived capture/Google leases.
                        // Those keys have their own explicit arm/disarm lifecycle.
                        .putLong(LsposedRuntimeConfig.K_UPDATED_AT, updatedAt)
                        .commit();
                if (!committed) {
                    publishSnapshot(current, false, false, false, false, 0L, 0L,
                            "Remote Preferences 写入失败");
                    return;
                }
                publishFromRemote(current, remote, "");
            } catch (UnsupportedOperationException unsupported) {
                publishSnapshot(current, false, false, false, false, 0L, 0L,
                        "当前框架不支持 Remote Preferences");
            } catch (Throwable t) {
                publishSnapshot(current, false, false, false, false, 0L, 0L,
                        "同步 LSPosed 配置失败：" + messageOf(t));
            }
        });
    }

    private boolean armGoogleCtsSession(String token, long triggerElapsed, long armedUntilElapsed) {
        XposedService current = service;
        SharedPreferences local = localPreferences;
        if (current == null || local == null || token == null || token.isBlank()) return false;
        if (!snapshot.remoteProviderEnabled() || !snapshot.googleScopeEnabled()) return false;
        try {
            SharedPreferences remote = current.getRemotePreferences(LsposedRuntimeConfig.GROUP);
            if (remote == null) return false;
            return remote.edit()
                    .putString(LsposedRuntimeConfig.K_GOOGLE_CTS_SESSION_TOKEN, token)
                    .putLong(LsposedRuntimeConfig.K_GOOGLE_CTS_TRIGGER_ELAPSED, triggerElapsed)
                    .putLong(LsposedRuntimeConfig.K_GOOGLE_CTS_SESSION_UNTIL, armedUntilElapsed)
                    .remove(LsposedRuntimeConfig.K_GOOGLE_CTS_COMPONENT_BLOCK_UNTIL)
                    .commit();
        } catch (Throwable t) {
            return false;
        }
    }

    private void clearGoogleCtsSession(String token) {
        if (service == null) return;
        IO.execute(() -> clearGoogleCtsSessionNow(token));
    }

    private boolean clearGoogleCtsSessionNow(String token) {
        XposedService current = service;
        if (current == null) return false;
        try {
            SharedPreferences remote = current.getRemotePreferences(LsposedRuntimeConfig.GROUP);
            if (remote == null) return false;
            String currentToken = remote.getString(
                    LsposedRuntimeConfig.K_GOOGLE_CTS_SESSION_TOKEN, "");
            if (token != null && !token.isBlank() && !token.equals(currentToken)) return false;
            return remote.edit()
                    .remove(LsposedRuntimeConfig.K_GOOGLE_CTS_SESSION_TOKEN)
                    .remove(LsposedRuntimeConfig.K_GOOGLE_CTS_TRIGGER_ELAPSED)
                    .remove(LsposedRuntimeConfig.K_GOOGLE_CTS_SESSION_UNTIL)
                    .remove(LsposedRuntimeConfig.K_GOOGLE_CTS_COMPONENT_BLOCK_UNTIL)
                    .commit();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void armSecureCapture(Consumer<Boolean> callback) {
        XposedService current = service;
        SharedPreferences local = localPreferences;
        Snapshot currentSnapshot = snapshot;
        if (current == null || local == null || !currentSnapshot.systemLoaded) {
            complete(callback, false);
            return;
        }
        boolean enhanced = local.getBoolean(FloatSettings.K_ENHANCED_MODE, false);
        boolean lsposed = local.getBoolean(FloatSettings.K_LSPOSED_ENABLED, false);
        boolean secureScreenshot = local.getBoolean(FloatSettings.K_LSPOSED_SECURE_SCREENSHOT, false);
        if (!LsposedRuntimeConfig.isEnabled(enhanced, lsposed) || !secureScreenshot) {
            complete(callback, false);
            return;
        }

        long armedUntil = SystemClock.elapsedRealtime() + LsposedRuntimeConfig.SECURE_CAPTURE_LEASE_MS;
        long updatedAt = System.currentTimeMillis();
        IO.execute(() -> {
            boolean success = false;
            try {
                SharedPreferences remote = current.getRemotePreferences(LsposedRuntimeConfig.GROUP);
                if (remote != null) {
                    success = remote.edit()
                            .putInt(LsposedRuntimeConfig.K_SCHEMA_VERSION, LsposedRuntimeConfig.SCHEMA_VERSION)
                            .putBoolean(LsposedRuntimeConfig.K_ENHANCED_MODE, enhanced)
                            .putBoolean(LsposedRuntimeConfig.K_LSPOSED_ENABLED, lsposed)
                            .putBoolean(LsposedRuntimeConfig.K_SECURE_SCREENSHOT_ENABLED, secureScreenshot)
                            .putLong(LsposedRuntimeConfig.K_SECURE_CAPTURE_ARMED_UNTIL, armedUntil)
                            .putLong(LsposedRuntimeConfig.K_UPDATED_AT, updatedAt)
                            .commit();
                    publishFromRemote(current, remote, success ? "" : "安全截图短时授权写入失败");
                }
            } catch (Throwable t) {
                publishSnapshot(current, false, false, false, false, 0L, 0L,
                        "安全截图短时授权失败：" + messageOf(t));
            }
            complete(callback, success);
        });
    }

    private void disarmSecureCapture() {
        XposedService current = service;
        if (current == null) return;
        IO.execute(() -> {
            try {
                SharedPreferences remote = current.getRemotePreferences(LsposedRuntimeConfig.GROUP);
                if (remote == null) return;
                remote.edit()
                        .putLong(LsposedRuntimeConfig.K_SECURE_CAPTURE_ARMED_UNTIL, 0L)
                        .putLong(LsposedRuntimeConfig.K_UPDATED_AT, System.currentTimeMillis())
                        .commit();
                publishFromRemote(current, remote, "");
            } catch (Throwable ignored) {
                // Lease expiry guarantees the bypass cannot remain armed indefinitely.
            }
        });
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
                    publishSnapshot(current, false, false, false, false, 0L, 0L,
                            "框架没有提供 Remote Preferences");
                    return;
                }
                publishFromRemote(current, remote, "");
            } catch (UnsupportedOperationException unsupported) {
                publishSnapshot(current, false, false, false, false, 0L, 0L,
                        "当前框架不支持 Remote Preferences");
            } catch (Throwable t) {
                publishSnapshot(current, false, false, false, false, 0L, 0L,
                        "读取 LSPosed 状态失败：" + messageOf(t));
            }
        });
    }

    private void publishFromRemote(XposedService current, SharedPreferences remote, String detail) {
        publishSnapshot(current,
                remote.getInt(LsposedRuntimeConfig.K_SCHEMA_VERSION, 0)
                        >= LsposedRuntimeConfig.SCHEMA_VERSION,
                remote.getBoolean(LsposedRuntimeConfig.K_ENHANCED_MODE, false),
                remote.getBoolean(LsposedRuntimeConfig.K_LSPOSED_ENABLED, false),
                remote.getBoolean(LsposedRuntimeConfig.K_SECURE_SCREENSHOT_ENABLED, false),
                remote.getLong(LsposedRuntimeConfig.K_SECURE_CAPTURE_ARMED_UNTIL, 0L),
                remote.getLong(LsposedRuntimeConfig.K_UPDATED_AT, 0L),
                detail);
    }

    private void publishSnapshot(XposedService current,
                                 boolean remoteConfigReady,
                                 boolean remoteEnhancedMode,
                                 boolean remoteLsposedEnabled,
                                 boolean remoteSecureScreenshotEnabled,
                                 long remoteSecureCaptureArmedUntil,
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

                    HookedTarget.State state = target.getState();
                    long loadedVersion = target.getLoadedVersionCode();
                    boolean currentVersion = loadedVersion == BuildConfig.VERSION_CODE;
                    boolean upToDate = state == HookedTarget.State.UP_TO_DATE;
                    boolean currentTarget = currentVersion && upToDate;

                    running.add(process + "[" + state.name() + " v" + loadedVersion + "]");
                    if (currentTarget && isSystemProcess(process)) systemLoaded = true;
                    if (currentTarget && isSystemUiProcess(process)) systemUiLoaded = true;
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
                    remoteSecureScreenshotEnabled,
                    remoteSecureCaptureArmedUntil,
                    remoteUpdatedAt,
                    detail));
        } catch (Throwable t) {
            publish(new Snapshot(true, "", "", 0,
                    Collections.emptyList(), Collections.emptyList(),
                    false, false, false, false,
                    remoteConfigReady, remoteEnhancedMode, remoteLsposedEnabled,
                    remoteSecureScreenshotEnabled, remoteSecureCaptureArmedUntil, remoteUpdatedAt,
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

    private static void complete(Consumer<Boolean> callback, boolean value) {
        if (callback != null) MAIN.post(() -> callback.accept(value));
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
