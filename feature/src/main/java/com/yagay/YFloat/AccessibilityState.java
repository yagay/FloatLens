package com.yagay.YFloat;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/** Host-aware accessibility authorization state shared by standalone YFloat and YSuite. */
public final class AccessibilityState {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile String lastLoggedSummary = "";

    private AccessibilityState() {}

    public static final class Snapshot {
        public final boolean hostEnabled;
        public final boolean connected;
        public final boolean otherYFloatEnabled;
        public final String expectedComponent;
        public final String enabledComponents;
        public final String secureRaw;

        Snapshot(
                boolean hostEnabled,
                boolean connected,
                boolean otherYFloatEnabled,
                String expectedComponent,
                String enabledComponents,
                String secureRaw
        ) {
            this.hostEnabled = hostEnabled;
            this.connected = connected;
            this.otherYFloatEnabled = otherYFloatEnabled;
            this.expectedComponent = expectedComponent;
            this.enabledComponents = enabledComponents;
            this.secureRaw = secureRaw;
        }

        public String statusLabel(Context context) {
            if (connected) return "已授权 · 已连接";
            if (hostEnabled) return "已授权 · 等待连接";
            if (otherYFloatEnabled && context != null
                    && "com.yagay.YSuite".equals(context.getPackageName())) {
                return "独立版已授权 · YSuite 未授权";
            }
            if (otherYFloatEnabled) return "其他宿主已授权 · 当前未授权";
            return "未授权";
        }
    }

    /** Full status snapshot. The current APK package is always authoritative. */
    public static Snapshot snapshot(Context context) {
        if (context == null) {
            return new Snapshot(false, LensAccessibilityService.ready(), false, "", "", "");
        }

        Context app = context.getApplicationContext();
        if (app == null) app = context;
        ComponentName expected = new ComponentName(app, LensAccessibilityService.class);
        boolean connected = LensAccessibilityService.ready();
        boolean hostEnabled = connected;
        boolean otherYFloatEnabled = false;
        Set<String> enabledComponents = new LinkedHashSet<>();

        // Primary path. Do not gate this on manager.isEnabled(): some OEM builds lag that flag
        // while already returning the concrete enabled services.
        try {
            AccessibilityManager manager =
                    (AccessibilityManager) app.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (manager != null) {
                List<AccessibilityServiceInfo> services = manager.getEnabledAccessibilityServiceList(
                        AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
                if (services != null) {
                    for (AccessibilityServiceInfo info : services) {
                        if (info == null) continue;

                        ComponentName byId = componentFromFlat(info.getId());
                        if (byId != null) {
                            enabledComponents.add(byId.flattenToString());
                            if (sameComponent(expected, byId)) hostEnabled = true;
                            else if (isYFloatService(byId)) otherYFloatEnabled = true;
                        }

                        ResolveInfo resolve = info.getResolveInfo();
                        if (resolve != null && resolve.serviceInfo != null) {
                            String pkg = resolve.serviceInfo.packageName;
                            String cls = normalizeClassName(pkg, resolve.serviceInfo.name);
                            if (pkg != null && cls != null) {
                                ComponentName actual = new ComponentName(pkg, cls);
                                enabledComponents.add(actual.flattenToString());
                                if (sameComponent(expected, actual)) hostEnabled = true;
                                else if (isYFloatService(actual)) otherYFloatEnabled = true;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) { }

        String secureRaw = "";
        try {
            secureRaw = Settings.Secure.getString(
                    app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (secureRaw == null) secureRaw = "";
            if (!secureRaw.isBlank()) {
                for (String entry : secureRaw.split(":")) {
                    ComponentName actual = componentFromFlat(entry);
                    if (actual == null) continue;
                    enabledComponents.add(actual.flattenToString());
                    if (sameComponent(expected, actual)) hostEnabled = true;
                    else if (isYFloatService(actual)) otherYFloatEnabled = true;
                }
            }
        } catch (Throwable ignored) { }

        Snapshot result = new Snapshot(
                hostEnabled,
                connected,
                otherYFloatEnabled,
                expected.flattenToString(),
                String.join(",", enabledComponents),
                secureRaw
        );
        logSnapshotIfChanged(app, result);
        return result;
    }

    public static boolean enabled(Context context) {
        return snapshot(context).hostEnabled;
    }

    public static boolean connected() {
        return LensAccessibilityService.ready();
    }

    public static String statusLabel(Context context) {
        return snapshot(context).statusLabel(context);
    }

    public static void addListener(Runnable listener) {
        if (listener != null) LISTENERS.addIfAbsent(listener);
    }

    public static void removeListener(Runnable listener) {
        if (listener != null) LISTENERS.remove(listener);
    }

    static void notifyServiceStateChanged() {
        Runnable dispatch = () -> {
            for (Runnable listener : LISTENERS) {
                try { listener.run(); } catch (Throwable ignored) { }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) dispatch.run();
        else MAIN.post(dispatch);
    }

    private static String normalizeClassName(String pkg, String cls) {
        if (pkg == null || cls == null || cls.isBlank()) return cls;
        if (cls.startsWith(".")) return pkg + cls;
        if (cls.indexOf('.') < 0) return pkg + "." + cls;
        return cls;
    }

    private static ComponentName componentFromFlat(String value) {
        if (value == null || value.isBlank()) return null;
        String text = value.trim();
        ComponentName parsed = ComponentName.unflattenFromString(text);
        if (parsed == null) return null;
        String pkg = parsed.getPackageName();
        String cls = normalizeClassName(pkg, parsed.getClassName());
        return pkg == null || cls == null ? null : new ComponentName(pkg, cls);
    }

    private static boolean sameComponent(ComponentName a, ComponentName b) {
        return a != null && b != null
                && a.getPackageName().equals(b.getPackageName())
                && a.getClassName().equals(normalizeClassName(
                        b.getPackageName(), b.getClassName()));
    }

    private static boolean isYFloatService(ComponentName component) {
        return component != null
                && LensAccessibilityService.class.getName().equals(
                        normalizeClassName(component.getPackageName(), component.getClassName()));
    }

    private static void logSnapshotIfChanged(Context context, Snapshot snapshot) {
        String summary = "host=" + context.getPackageName()
                + " expected=" + snapshot.expectedComponent
                + " hostEnabled=" + snapshot.hostEnabled
                + " connected=" + snapshot.connected
                + " otherYFloat=" + snapshot.otherYFloatEnabled
                + " enabled=[" + snapshot.enabledComponents + "]"
                + " secure=[" + snapshot.secureRaw + "]";
        if (summary.equals(lastLoggedSummary)) return;
        lastLoggedSummary = summary;
        DiagnosticLog.critical(context, "A11Y_STATE", summary);
    }
}
