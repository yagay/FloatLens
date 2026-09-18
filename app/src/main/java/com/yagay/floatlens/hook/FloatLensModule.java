package com.yagay.floatlens.hook;

import android.util.Log;

import io.github.libxposed.api.XposedModule;

/** libxposed API 102 entry point for FloatLens controlled providers. */
public final class FloatLensModule extends XposedModule {
    private static final String TAG = "FloatLens-LSPosed";
    private static final String GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox";
    private LsposedRuntimeProvider runtimeProvider;
    private boolean googleCtsInspectorInstalled;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        runtimeProvider = new LsposedRuntimeProvider(this, param.getProcessName());
        runtimeProvider.start();
        log(Log.INFO, TAG,
                "Module loaded in " + param.getProcessName()
                        + "; provider=" + (runtimeProvider.isActive() ? "enabled" : "disabled"));
    }

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        LsposedRuntimeProvider provider = runtimeProvider;
        if (provider == null) {
            log(Log.ERROR, TAG, "system_server provider missing; secure screenshot hook not installed");
            return;
        }
        try {
            new SecureScreenshotHook(this, provider, param.getClassLoader()).install();
        } catch (Throwable t) {
            // Never let an optional screenshot enhancement destabilize system_server startup.
            log(Log.ERROR, TAG, "Failed to install controlled secure screenshot hooks", t);
        }
        // Google CTS isolation is now handled entirely inside FloatLens-marked Google App
        // sessions. Never hook ContextualSearch in system_server: a process-wide component block
        // can interfere with the user's native Home/gesture Circle to Search session.
        log(Log.INFO, TAG,
                "Google contextual-search system_server blocker disabled; native CTS left untouched");
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        // Google CTS hooks are installed at PackageReady so the app ClassLoader is final.
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (googleCtsInspectorInstalled || !GOOGLE_PACKAGE.equals(param.getPackageName())) return;
        LsposedRuntimeProvider provider = runtimeProvider;
        if (provider == null) return;
        try {
            new GoogleCtsRuntimeInspector(this, provider, param.getClassLoader()).install();
            googleCtsInspectorInstalled = true;
            log(Log.INFO, TAG, "Google CTS marked-session inspector installed");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to install Google CTS marked-session inspector", t);
        }
    }
}
