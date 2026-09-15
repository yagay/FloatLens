package com.yagay.floatlens;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.service.HookedTarget;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/** App-side LSPosed framework/status bridge. It never installs hooks. */
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
            this.detail = safe(detail);
        }

        private static Snapshot disconnected(String detail) {
            return new Snapshot(false, "", "", 0,
                    Collections.emptyList(), Collections.emptyList(),
                    false, false, false, false, detail);
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
    private volatile Snapshot snapshot = Snapshot.disconnected("等待 LSPosed 服务连接");

    private LsposedStatusManager() {}

    /** Must be called once from the module app process. Safe to call repeatedly. */
    public static void initialize() {
        if (INITIALIZED.compareAndSet(false, true)) {
            XposedServiceHelper.registerListener(INSTANCE);
        }
    }

    public static Snapshot snapshot() {
        return INSTANCE.snapshot;
    }

    public static boolean frameworkConnected() {
        return INSTANCE.snapshot.serviceConnected;
    }

    public static void refreshAsync() {
        INSTANCE.refreshFromService();
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
        refreshFromService();
    }

    @Override
    public void onServiceDied(XposedService service) {
        if (this.service == service) this.service = null;
        publish(Snapshot.disconnected("LSPosed 服务已断开"));
    }

    private void refreshFromService() {
        XposedService current = service;
        if (current == null) {
            publish(Snapshot.disconnected("未连接到 LSPosed 服务"));
            return;
        }
        IO.execute(() -> {
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
                        ""));
            } catch (Throwable t) {
                String message = t.getMessage();
                publish(new Snapshot(true, "", "", 0,
                        Collections.emptyList(), Collections.emptyList(),
                        false, false, false, false,
                        "读取 LSPosed 状态失败：" + ((message == null || message.isBlank())
                                ? t.getClass().getSimpleName() : message)));
            }
        });
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
