package com.yagay.floatlens.hook;

import android.util.Log;

import io.github.libxposed.api.XposedModule;

/** libxposed API 102 entry point for FloatLens controlled providers. */
public final class FloatLensModule extends XposedModule {
    private static final String TAG = "FloatLens-LSPosed";
    private LsposedRuntimeProvider runtimeProvider;

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
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        // SystemUI currently uses the Remote Preferences provider/status channel only.
        // Secure screenshot capture is implemented in system_server, so no package hook is needed.
    }
}
