package com.yagay.floatlens.hook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

import io.github.libxposed.api.XposedModule;

/**
 * Android 15/16 Google Circle-to-Search capture-only takeover.
 *
 * <p>AOSP ContextualSearchManagerService creates the frozen screenshot before launching the
 * contextual-search provider. Intercepting its final launch boundary gives FloatLens the exact
 * system frame without depending on Google's obfuscated implementation. The original launch is
 * suppressed only after the FloatLens bridge Activity was successfully started; every failure is
 * fail-open and proceeds to Google's normal flow.</p>
 */
final class GoogleCtsSystemHook {
    private static final String TAG = "FloatLens-GoogleCTS";
    private static final String SERVICE_CLASS =
            "com.android.server.contextualsearch.ContextualSearchManagerService";
    private static final String GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox";
    private static final String ACTION_CONTEXTUAL_SEARCH =
            "android.app.contextualsearch.action.LAUNCH_CONTEXTUAL_SEARCH";
    private static final String EXTRA_SCREENSHOT =
            "android.app.contextualsearch.extra.SCREENSHOT";
    private static final String EXTRA_ENTRYPOINT =
            "android.app.contextualsearch.extra.ENTRYPOINT";
    private static final String EXTRA_SECURE_FOUND =
            "android.app.contextualsearch.extra.FLAG_SECURE_FOUND";
    private static final String EXTRA_VISIBLE_PACKAGES =
            "android.app.contextualsearch.extra.VISIBLE_PACKAGE_NAMES";

    private static final ComponentName BRIDGE = new ComponentName(
            "com.yagay.floatlens", "com.yagay.floatlens.GoogleCtsBridgeActivity");
    private static final String BRIDGE_ACTION =
            "com.yagay.floatlens.action.GOOGLE_CTS_CAPTURE";
    private static final String BRIDGE_SCREENSHOT =
            "com.yagay.floatlens.extra.GOOGLE_CTS_SCREENSHOT";
    private static final String BRIDGE_ENTRYPOINT =
            "com.yagay.floatlens.extra.GOOGLE_CTS_ENTRYPOINT";
    private static final String BRIDGE_SECURE_FOUND =
            "com.yagay.floatlens.extra.GOOGLE_CTS_SECURE_FOUND";

    private final XposedModule module;
    private final LsposedRuntimeProvider provider;
    private final ClassLoader classLoader;

    GoogleCtsSystemHook(XposedModule module,
                        LsposedRuntimeProvider provider,
                        ClassLoader classLoader) {
        this.module = module;
        this.provider = provider;
        this.classLoader = classLoader;
    }

    void install() throws Exception {
        Class<?> service = classLoader.loadClass(SERVICE_CLASS);
        int installed = 0;
        for (Method method : service.getDeclaredMethods()) {
            if (!"invokeContextualSearchIntent".equals(method.getName())) continue;
            Class<?>[] p = method.getParameterTypes();
            if (p.length != 1 || !Intent.class.isAssignableFrom(p[0])) continue;
            if (method.getReturnType() != int.class && method.getReturnType() != Integer.class) {
                continue;
            }
            module.hook(method).intercept(chain -> interceptLaunch(
                    chain.getThisObject(), (Intent) chain.getArg(0), chain::proceed));
            installed++;
        }

        if (installed == 0) {
            module.log(Log.WARN, TAG,
                    "AOSP ContextualSearch launch boundary not found; Google CTS left untouched");
        } else {
            module.log(Log.INFO, TAG,
                    "Google CTS capture-only hook installed methods=" + installed
                            + " package=" + GOOGLE_PACKAGE);
        }
    }

    private Object interceptLaunch(Object service,
                                   Intent launchIntent,
                                   Proceed proceed) throws Throwable {
        if (!provider.isGoogleCtsCaptureOnlyEnabled()) return proceed.call();
        if (!isGoogleCtsLaunch(launchIntent)) return proceed.call();

        Bitmap screenshot = readScreenshot(launchIntent);
        int entrypoint = launchIntent.getIntExtra(EXTRA_ENTRYPOINT, -1);
        boolean secureFound = launchIntent.getBooleanExtra(EXTRA_SECURE_FOUND, false);
        ArrayList<String> visible = null;
        try {
            visible = launchIntent.getStringArrayListExtra(EXTRA_VISIBLE_PACKAGES);
        } catch (Throwable ignored) {
        }

        if (screenshot == null || screenshot.isRecycled()
                || screenshot.getWidth() <= 0 || screenshot.getHeight() <= 0) {
            module.log(Log.WARN, TAG,
                    "CTS candidate has no usable screenshot; fail-open"
                            + " entrypoint=" + entrypoint
                            + " secureFound=" + secureFound);
            return proceed.call();
        }

        Context context = findContext(service);
        if (context == null) {
            module.log(Log.WARN, TAG,
                    "CTS screenshot captured but system Context unavailable; fail-open");
            return proceed.call();
        }

        Intent bridge = new Intent(BRIDGE_ACTION)
                .setComponent(BRIDGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                .putExtra(BRIDGE_SCREENSHOT, screenshot)
                .putExtra(BRIDGE_ENTRYPOINT, entrypoint)
                .putExtra(BRIDGE_SECURE_FOUND, secureFound);

        try {
            context.startActivity(bridge);
            module.log(Log.INFO, TAG,
                    "CTS takeover success"
                            + " entrypoint=" + entrypoint
                            + " screenshot=" + screenshot.getWidth() + "x" + screenshot.getHeight()
                            + " secureFound=" + secureFound
                            + " visiblePackages=" + (visible == null ? -1 : visible.size())
                            + " component=" + launchIntent.getComponent());
            // ActivityManager.START_SUCCESS. The original Google activity is intentionally skipped.
            return 0;
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG,
                    "FloatLens bridge launch failed; fail-open to Google CTS", t);
            return proceed.call();
        }
    }

    private boolean isGoogleCtsLaunch(Intent intent) {
        if (intent == null) return false;
        if (!ACTION_CONTEXTUAL_SEARCH.equals(intent.getAction())) return false;
        String pkg = intent.getPackage();
        ComponentName component = intent.getComponent();
        String target = component != null ? component.getPackageName() : pkg;
        return GOOGLE_PACKAGE.equals(target);
    }

    @SuppressWarnings("deprecation")
    private Bitmap readScreenshot(Intent intent) {
        try {
            Object value = intent.getParcelableExtra(EXTRA_SCREENSHOT);
            return value instanceof Bitmap bitmap ? bitmap : null;
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "Failed to read CTS screenshot extra", t);
            return null;
        }
    }

    private Context findContext(Object service) {
        if (service == null) return null;
        try {
            Method getContext = service.getClass().getMethod("getContext");
            Object value = getContext.invoke(service);
            if (value instanceof Context context) return context;
        } catch (Throwable ignored) {
        }

        for (Class<?> c = service.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField("mContext");
                field.setAccessible(true);
                Object value = field.get(service);
                if (value instanceof Context context) return context;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    @FunctionalInterface
    private interface Proceed {
        Object call() throws Throwable;
    }
}
