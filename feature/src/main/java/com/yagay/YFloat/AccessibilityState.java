package com.yagay.YFloat;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Host-aware accessibility authorization state shared by standalone YFloat and YSuite.
 *
 * <p>Do not equate authorization with {@link LensAccessibilityService#ready()}. Android can keep
 * the service enabled in Settings while the service process is temporarily reconnecting. In
 * YSuite the actual enabled component is
 * {@code com.yagay.YSuite/com.yagay.YFloat.LensAccessibilityService}, while in the standalone APK
 * the package is {@code com.yagay.YFloat}. The current Context package is therefore authoritative.
 */
public final class AccessibilityState {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private AccessibilityState() {}

    /** True when Android Settings has this host's LensAccessibilityService enabled. */
    public static boolean enabled(Context context) {
        if (LensAccessibilityService.ready()) return true;
        if (context == null) return false;

        Context app = context.getApplicationContext();
        if (app == null) app = context;
        ComponentName expected = new ComponentName(app, LensAccessibilityService.class);

        // Primary path: ask AccessibilityManager for services enabled for the current user.
        try {
            AccessibilityManager manager =
                    (AccessibilityManager) app.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (manager != null && manager.isEnabled()) {
                List<AccessibilityServiceInfo> services = manager.getEnabledAccessibilityServiceList(
                        AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
                if (services != null) {
                    for (AccessibilityServiceInfo info : services) {
                        if (info == null) continue;
                        ResolveInfo resolve = info.getResolveInfo();
                        if (resolve == null || resolve.serviceInfo == null) continue;
                        ComponentName actual = new ComponentName(
                                resolve.serviceInfo.packageName,
                                resolve.serviceInfo.name);
                        if (expected.equals(actual)) return true;
                    }
                }
            }
        } catch (Throwable ignored) {
            // Fall through to Settings.Secure for OEM implementations with incomplete manager data.
        }

        // OEM fallback: compare flattened enabled-service component names.
        try {
            String enabled = Settings.Secure.getString(
                    app.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null || enabled.isBlank()) return false;
            String[] entries = enabled.split(":");
            for (String entry : entries) {
                ComponentName actual = ComponentName.unflattenFromString(entry);
                if (expected.equals(actual)) return true;
            }
        } catch (Throwable ignored) {
            // Treat an unreadable setting as unknown/not enabled rather than reporting a false grant.
        }
        return false;
    }

    /** True only while Android has actually bound the AccessibilityService instance. */
    public static boolean connected() {
        return LensAccessibilityService.ready();
    }

    public static String statusLabel(Context context) {
        if (connected()) return "已授权 · 已连接";
        if (enabled(context)) return "已授权 · 等待连接";
        return "未授权";
    }

    /**
     * Register a visible UI listener. This keeps permission state event-driven instead of relying
     * on a short polling window after returning from Accessibility Settings. OxygenOS can finish
     * binding the service several seconds later even though the Settings toggle is already on.
     */
    public static void addListener(Runnable listener) {
        if (listener != null) LISTENERS.addIfAbsent(listener);
    }

    public static void removeListener(Runnable listener) {
        if (listener != null) LISTENERS.remove(listener);
    }

    /** Called by LensAccessibilityService whenever Android binds or destroys the live service. */
    static void notifyServiceStateChanged() {
        Runnable dispatch = () -> {
            for (Runnable listener : LISTENERS) {
                try { listener.run(); } catch (Throwable ignored) { }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) dispatch.run();
        else MAIN.post(dispatch);
    }
}
