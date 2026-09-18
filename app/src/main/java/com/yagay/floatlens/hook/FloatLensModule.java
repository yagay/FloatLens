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
            new GoogleCtsSystemHook(this, provider, param.getClassLoader()).install();
        } catch (Throwable t) {
            // CTS takeover is optional and must never destabilize system_server.
            log(Log.ERROR, TAG, "Failed to install Google CTS capture-only hook", t);
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
        // Google CTS capture-only is intentionally implemented at the Android contextual-search
        // boundary in system_server. No obfuscated Google App hook is required on Android 15/16.
    }
}
