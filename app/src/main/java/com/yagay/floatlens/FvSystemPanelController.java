package com.yagay.floatlens;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Clean-room equivalent of FV's notification/system-panel state and close path.
 *
 * Re-verified FV behaviour:
 *  - FooAccessibilityService.B0() detects an expanded SystemUI TYPE_SYSTEM window;
 *  - FooViewService keeps that state continuously ("notification is expand/collapse");
 *  - capture/circle code waits until the frozen/candidate result UI is ready;
 *  - m5/w2.n() broadcasts ACTION_CLOSE_SYSTEM_DIALOGS and then still invokes its callback;
 *  - on Android 12+ the callback reaches performGlobalAction(15), i.e.
 *    GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE;
 *  - FV's ShadowActivity request=21 starts a transient Activity and finishes it 300 ms later.
 *
 * FV itself targets SDK 29, while FloatLens intentionally stays on a modern target SDK. Android 12+
 * rejects ACTION_CLOSE_SYSTEM_DIALOGS for modern targets, so FloatLens keeps the same high-level FV
 * sequence but uses the supported accessibility action first, verifies the live shade state, then
 * falls back to an FV-style transient Activity. Root remains the final optional fallback only.
 */
public final class FvSystemPanelController {
    private static final ExecutorService ROOT_IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FloatLens-shade-collapse");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long PRIMARY_RECHECK_MS = 140L;
    private static final long SHADOW_RECHECK_MS = 380L;

    private static volatile boolean cachedShadeExpanded;
    private static volatile boolean cachedShadeKnown;
    private static volatile long cachedShadeUpdatedAt;
    private static volatile Boolean cachedMiui;

    private FvSystemPanelController() {}

    /** Immutable-per-capture token with one-shot result delivery, matching FV's capture -> candidate flow. */
    public static final class CaptureState {
        private final boolean expandedAtCapture;
        private final long startedAt;
        private final String startReason;
        private final AtomicBoolean consumed = new AtomicBoolean(false);

        private CaptureState(boolean expandedAtCapture, long startedAt, String startReason) {
            this.expandedAtCapture = expandedAtCapture;
            this.startedAt = startedAt;
            this.startReason = startReason == null ? "capture" : startReason;
        }

        public boolean expandedAtCapture() { return expandedAtCapture; }
        public long startedAt() { return startedAt; }
        public String startReason() { return startReason; }
        private boolean consume() { return consumed.compareAndSet(false, true); }
    }

    /**
     * Called by LensAccessibilityService whenever its environment snapshot changes. This is the
     * FloatLens equivalent of FV's service-level j0 notification-expanded state.
     */
    public static void onAccessibilityEnvironment(Context context, EnvironmentState state) {
        if (context == null || state == null) return;
        updateCachedShade(context.getApplicationContext(), state.notificationExpanded(), "accessibility_env");
    }

    /** Accessibility service disappeared; keep the last value only as history, not as authoritative state. */
    public static void onAccessibilityDisconnected(Context context) {
        boolean wasKnown = cachedShadeKnown;
        boolean wasExpanded = cachedShadeExpanded;
        cachedShadeKnown = false;
        cachedShadeUpdatedAt = SystemClock.uptimeMillis();
        if (context != null && wasKnown) {
            DiagnosticLog.i(context.getApplicationContext(), "FV_SHADE",
                    "accessibility disconnected lastExpanded=" + wasExpanded);
        }
    }

    /** Start one screenshot/circle/View operation and remember the notification state at its boundary. */
    public static CaptureState beginCapture(Context context, String reason) {
        Context app = context.getApplicationContext();
        boolean expanded = notificationShadeExpanded();
        CaptureState state = new CaptureState(expanded, SystemClock.uptimeMillis(), reason);
        DiagnosticLog.i(app, "FV_SHADE", "capture begin reason=" + state.startReason()
                + " expanded=" + expanded + " cachedKnown=" + cachedShadeKnown);
        return state;
    }

    /**
     * Candidate/frozen/result UI callback. This is the central equivalent of FV's shared e0()/
     * onCircelCandidateDialogShown path. It is intentionally one-shot per CaptureState.
     */
    public static void onResultReady(Context context, CaptureState state, String reason) {
        if (context == null || state == null || !state.consume()) return;
        Context app = context.getApplicationContext();

        // FV keeps a live service-level flag. Preserve the capture snapshot, but also honor a newer
        // live expanded state that appeared while capture/result UI was being prepared.
        boolean liveExpanded = cachedShadeKnown && cachedShadeExpanded;
        boolean miuiCompat = isMiuiDevice();
        boolean shouldDismiss = state.expandedAtCapture() || liveExpanded || miuiCompat;
        long elapsed = Math.max(0L, SystemClock.uptimeMillis() - state.startedAt());

        DiagnosticLog.i(app, "FV_SHADE", "result ready reason=" + reason
                + " start=" + state.startReason()
                + " captureExpanded=" + state.expandedAtCapture()
                + " liveExpanded=" + liveExpanded
                + " miuiCompat=" + miuiCompat
                + " elapsedMs=" + elapsed);

        if (!shouldDismiss) return;
        dismissSystemPanel(context, reason == null ? state.startReason() : reason);
    }

    /** FV B0()-style SystemUI window test, with the service environment and cached state as fallbacks. */
    public static boolean notificationShadeExpanded() {
        LensAccessibilityService service = LensAccessibilityService.get();
        Boolean live = probeNotificationShadeExpanded(service);
        if (live != null) {
            Context app = service == null ? null : service.getApplicationContext();
            if (app != null) updateCachedShade(app, live, "live_probe");
            return live;
        }
        return cachedShadeKnown && cachedShadeExpanded;
    }

    private static Boolean probeNotificationShadeExpanded(LensAccessibilityService service) {
        if (service == null) return null;
        boolean windowListRead = false;
        try {
            WindowManager wm = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
            int screenHeight = Math.max(1, wm.getCurrentWindowMetrics().getBounds().height());
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows != null) {
                windowListRead = true;
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null || window.getType() != AccessibilityWindowInfo.TYPE_SYSTEM) continue;
                    AccessibilityNodeInfo root = null;
                    try { root = window.getRoot(); } catch (Throwable ignored) {}
                    if (root == null || root.getPackageName() == null
                            || !"com.android.systemui".contentEquals(root.getPackageName())) continue;
                    Rect bounds = new Rect();
                    try { window.getBoundsInScreen(bounds); } catch (Throwable ignored) {}
                    if (!bounds.isEmpty() && bounds.height() >= screenHeight / 2) {
                        DiagnosticLog.i(service, "FV_SHADE", "expanded via TYPE_SYSTEM bounds="
                                + bounds.toShortString());
                        return Boolean.TRUE;
                    }
                }
            }
        } catch (Throwable t) {
            DiagnosticLog.i(service, "FV_SHADE", "window check failed=" + t);
        }

        try {
            EnvironmentState env = service.environment();
            if (env != null) return env.notificationExpanded();
        } catch (Throwable ignored) {}
        return windowListRead ? Boolean.FALSE : null;
    }

    private static void updateCachedShade(Context app, boolean expanded, String source) {
        boolean changed = !cachedShadeKnown || cachedShadeExpanded != expanded;
        cachedShadeExpanded = expanded;
        cachedShadeKnown = true;
        cachedShadeUpdatedAt = SystemClock.uptimeMillis();
        if (changed && app != null) {
            DiagnosticLog.i(app, "FV_SHADE", expanded
                    ? "notification is expand source=" + source
                    : "notification is collapse source=" + source);
        }
    }

    private static void dismissSystemPanel(Context caller, String reason) {
        Context app = caller.getApplicationContext();
        MAIN.post(() -> {
            int targetSdk = app.getApplicationInfo().targetSdkVersion;
            boolean broadcast = false;

            // FV targets SDK 29 and can still benefit from the compatibility exception. FloatLens
            // targets modern Android, where this broadcast is guaranteed to be rejected, so avoid
            // intentionally throwing SecurityException while retaining the FV order for old targets.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || targetSdk < Build.VERSION_CODES.S) {
                try {
                    app.sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
                    broadcast = true;
                } catch (Throwable t) {
                    DiagnosticLog.i(app, "FV_SHADE", "CLOSE_SYSTEM_DIALOGS failed=" + t);
                }
            } else {
                DiagnosticLog.i(app, "FV_SHADE", "skip CLOSE_SYSTEM_DIALOGS targetSdk=" + targetSdk);
            }

            LensAccessibilityService service = LensAccessibilityService.get();
            SystemActions actions = inspectSystemActions(service);
            boolean global = false;
            if (service != null) {
                try {
                    global = service.global(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
                } catch (Throwable t) {
                    DiagnosticLog.i(app, "FV_SHADE", "dismiss global action failed=" + t);
                }
            }

            DiagnosticLog.i(app, "FV_SHADE", "dismiss result-ready reason=" + reason
                    + " broadcast=" + broadcast
                    + " action15Available=" + actions.hasDismissShade
                    + " global15=" + global
                    + " systemActions=" + actions.ids
                    + " cachedAgeMs=" + Math.max(0L, SystemClock.uptimeMillis() - cachedShadeUpdatedAt));

            MAIN.postDelayed(() -> verifyAfterPrimary(caller, app, reason), PRIMARY_RECHECK_MS);
        });
    }

    private static void verifyAfterPrimary(Context caller, Context app, String reason) {
        boolean expanded = notificationShadeExpanded();
        DiagnosticLog.i(app, "FV_SHADE", "primary recheck reason=" + reason
                + " expanded=" + expanded + " delayMs=" + PRIMARY_RECHECK_MS);
        if (!expanded) return;

        // FV ShadowActivity request=21 is a transient Activity that closes itself after 300 ms.
        // Use the same shape only after the supported accessibility action failed to collapse the
        // live shade on this ROM.
        boolean launched = ShadeDismissActivity.launch(caller, reason);
        if (!launched) {
            collapseWithRoot(app, reason + ":shadow_launch_failed");
            return;
        }

        MAIN.postDelayed(() -> {
            boolean stillExpanded = notificationShadeExpanded();
            DiagnosticLog.i(app, "FV_SHADE", "shadow recheck reason=" + reason
                    + " expanded=" + stillExpanded + " delayMs=" + SHADOW_RECHECK_MS);
            if (stillExpanded) collapseWithRoot(app, reason + ":shadow_still_expanded");
        }, SHADOW_RECHECK_MS);
    }

    private static SystemActions inspectSystemActions(LensAccessibilityService service) {
        if (service == null) return new SystemActions(false, "[]");
        try {
            List<AccessibilityNodeInfo.AccessibilityAction> actions = service.getSystemActions();
            if (actions == null || actions.isEmpty()) return new SystemActions(false, "[]");
            boolean has15 = false;
            StringBuilder ids = new StringBuilder("[");
            for (int i = 0; i < actions.size(); i++) {
                AccessibilityNodeInfo.AccessibilityAction action = actions.get(i);
                if (action == null) continue;
                int id = action.getId();
                if (id == AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE) has15 = true;
                if (ids.length() > 1) ids.append(',');
                ids.append(id);
            }
            ids.append(']');
            return new SystemActions(has15, ids.toString());
        } catch (Throwable t) {
            DiagnosticLog.i(service, "FV_SHADE", "getSystemActions failed=" + t);
            return new SystemActions(false, "[error]");
        }
    }

    private static final class SystemActions {
        final boolean hasDismissShade;
        final String ids;
        SystemActions(boolean hasDismissShade, String ids) {
            this.hasDismissShade = hasDismissShade;
            this.ids = ids;
        }
    }

    /**
     * Compatibility wrappers for older call sites. New capture code should use beginCapture() and
     * onResultReady() so all operations share the same one-shot state machine.
     */
    @Deprecated
    public static void dismissAfterCapture(Context context, boolean wasExpanded, String reason) {
        CaptureState state = new CaptureState(wasExpanded, SystemClock.uptimeMillis(), "legacy");
        onResultReady(context, state, reason);
    }

    private static boolean isMiuiDevice() {
        Boolean cached = cachedMiui;
        if (cached != null) return cached;

        boolean miui = !systemProperty("ro.miui.ui.version.code").isEmpty()
                || !systemProperty("ro.miui.ui.version.name").isEmpty()
                || !systemProperty("ro.miui.internal.storage").isEmpty();
        if (!miui) {
            String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase(Locale.ROOT);
            String brand = Build.BRAND == null ? "" : Build.BRAND.toLowerCase(Locale.ROOT);
            miui = maker.contains("xiaomi") || brand.contains("xiaomi")
                    || brand.contains("redmi") || brand.contains("poco");
        }
        cachedMiui = miui;
        return miui;
    }

    private static String systemProperty(String key) {
        try {
            Class<?> cls = Class.forName("android.os.SystemProperties");
            Object value = cls.getMethod("get", String.class).invoke(null, key);
            return value == null ? "" : String.valueOf(value).trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void collapseWithRoot(Context app, String reason) {
        ROOT_IO.execute(() -> {
            int code = -1;
            String error = "";
            try {
                Process p = new ProcessBuilder("su", "-c", "cmd statusbar collapse")
                        .redirectErrorStream(true)
                        .start();
                code = p.waitFor();
            } catch (Throwable t) {
                error = String.valueOf(t);
            }
            final int exitCode = code;
            final String failure = error;
            MAIN.post(() -> DiagnosticLog.i(app, "FV_SHADE",
                    "root collapse reason=" + reason + " exit=" + exitCode
                            + (failure.isEmpty() ? "" : " error=" + failure)));
        });
    }
}
