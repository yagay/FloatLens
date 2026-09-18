package com.yagay.floatlens.hook;

import android.content.ComponentName;
import android.content.Intent;
import android.util.Log;

import com.yagay.floatlens.GoogleCtsContract;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * system_server boundary for suppressing only FloatLens-owned Google Contextual Search launches.
 *
 * <p>Google text selection/highlight/handles stay in the already-running LensientActivity.
 * Only the later ContextualSearchEntrypoint activity start is aborted while a short FloatLens
 * component-block lease is active.</p>
 */
final class GoogleContextualSearchBlocker {
    private static final String TAG = "FloatLens-GoogleCTS-System";
    private static final String ACTIVITY_STARTER = "com.android.server.wm.ActivityStarter";
    private static final String TARGET_COMPONENT =
            "com.google.android.apps.lens.ContextualSearchEntrypoint";
    // Android ActivityManager.START_ABORTED. Kept numeric because it is @hide in the SDK.
    private static final int START_ABORTED = 102;

    private final XposedModule module;
    private final LsposedRuntimeProvider provider;
    private final ClassLoader classLoader;

    GoogleContextualSearchBlocker(XposedModule module,
                                  LsposedRuntimeProvider provider,
                                  ClassLoader classLoader) {
        this.module = module;
        this.provider = provider;
        this.classLoader = classLoader;
    }

    void install() {
        int installed = 0;
        try {
            Class<?> starter = classLoader.loadClass(ACTIVITY_STARTER);
            for (Executable executable : HiddenApiBypass.getDeclaredMethods(starter)) {
                if (!(executable instanceof Method method)) continue;
                if (!"executeRequest".equals(method.getName())
                        || method.getParameterCount() != 1
                        || method.getReturnType() != int.class) {
                    continue;
                }

                module.hook(method).intercept(chain -> {
                    String token = provider.googleCtsComponentBlockToken();
                    if (token.isBlank()) return chain.proceed();

                    Object request = chain.getArg(0);
                    Intent intent = intentFromRequest(request);
                    if (!isFloatLensContextualSearch(intent)) return chain.proceed();

                    ComponentName component = intent.getComponent();
                    module.log(Log.INFO, TAG,
                            "BLOCK_CONTEXTUAL_SEARCH session=" + shortToken(token)
                                    + " action=" + intent.getAction()
                                    + " component="
                                    + (component == null ? "null"
                                    : component.flattenToShortString()));
                    return START_ABORTED;
                });
                installed++;
            }
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG,
                    "Failed to install system_server contextual-search blocker", t);
        }

        module.log(installed > 0 ? Log.INFO : Log.WARN, TAG,
                "Contextual-search component hooks=" + installed);
    }

    private Intent intentFromRequest(Object request) {
        if (request == null) return null;
        for (Class<?> current = request.getClass(); current != null;
             current = current.getSuperclass()) {
            try {
                for (Field field : current.getDeclaredFields()) {
                    if (field.getType() != Intent.class) continue;
                    field.setAccessible(true);
                    Object value = field.get(request);
                    if (value instanceof Intent intent) return intent;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private boolean isFloatLensContextualSearch(Intent intent) {
        if (intent == null
                || !GoogleCtsContract.isContextualSearchAction(intent.getAction())) {
            return false;
        }

        ComponentName component = intent.getComponent();
        if (component != null) {
            return GoogleCtsContract.GOOGLE_PACKAGE.equals(component.getPackageName())
                    && TARGET_COMPONENT.equals(component.getClassName());
        }

        // Keep a fail-soft fallback for OEM resolution changes: exact contextual-search action
        // plus the Google package is still narrow enough while the FloatLens lease is active.
        return GoogleCtsContract.GOOGLE_PACKAGE.equals(intent.getPackage());
    }

    private static String shortToken(String token) {
        if (token == null || token.isBlank()) return "none";
        return token.substring(0, Math.min(8, token.length()));
    }
}
