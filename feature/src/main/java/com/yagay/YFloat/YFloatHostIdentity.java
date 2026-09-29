package com.yagay.YFloat;

import android.content.Context;
import android.content.SharedPreferences;

import java.lang.reflect.Method;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * Publishes the physical app host package to hooked processes when YFloat runs as a YSuite plugin.
 * Standalone YFloat does not need this listener; hook-side transport defaults to its own package.
 */
final class YFloatHostIdentity implements XposedServiceHelper.OnServiceListener {
    private static final String SUITE_PACKAGE = "com.yagay.YSuite";
    private static final String SUITE_BROKER = "com.yagay.suite.core.SuiteXposedServiceBroker";
    private static final String PLUGIN_ID = "yfloat.host";

    private static volatile YFloatHostIdentity instance;
    private final String hostPackage;

    private YFloatHostIdentity(Context context) {
        hostPackage = context.getPackageName();
    }

    static void initialize(Context context) {
        if (context == null || !SUITE_PACKAGE.equals(context.getPackageName()) || instance != null) return;
        synchronized (YFloatHostIdentity.class) {
            if (instance != null) return;
            YFloatHostIdentity listener = new YFloatHostIdentity(context.getApplicationContext());
            try {
                Class<?> broker = Class.forName(SUITE_BROKER, false, YFloatHostIdentity.class.getClassLoader());
                Method attach = broker.getMethod("attachFromPlugin", String.class, Object.class);
                if (Boolean.TRUE.equals(attach.invoke(null, PLUGIN_ID, listener))) {
                    instance = listener;
                }
            } catch (Throwable error) {
                DiagnosticLog.i(context, "HOST_IDENTITY", "YSuite host identity attach failed=" + error);
            }
        }
    }

    @Override
    public void onServiceBind(XposedService service) {
        try {
            SharedPreferences remote = service.getRemotePreferences(LsposedRuntimeConfig.GROUP);
            if (remote != null) {
                remote.edit()
                        .putString(LsposedRuntimeConfig.K_HOST_PACKAGE, hostPackage)
                        .putLong(LsposedRuntimeConfig.K_UPDATED_AT, System.currentTimeMillis())
                        .commit();
            }
        } catch (Throwable ignored) { }
    }

    @Override
    public void onServiceDied(XposedService service) { }
}
