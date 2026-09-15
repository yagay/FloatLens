package com.yagay.floatlens.hook;

import android.util.Log;

import io.github.libxposed.api.XposedModule;

/**
 * Clean libxposed API 102 entry point for FloatLens.
 *
 * <p>Loading the module never changes Android behavior on its own. The only runtime component
 * created here is a Remote Preferences backed provider gate. It mirrors the app's enhancement /
 * LSPosed switches into each loaded target process and currently installs no functional hooks.</p>
 *
 * <p>Future hook providers must be explicitly user-gated through {@link LsposedRuntimeProvider}
 * and must retain the normal Accessibility / Android fallback path.</p>
 */
public final class FloatLensModule extends XposedModule {
    private static final String TAG = "FloatLens-LSPosed";
    private LsposedRuntimeProvider runtimeProvider;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        runtimeProvider = new LsposedRuntimeProvider(this, param.getProcessName());
        runtimeProvider.start();
        log(Log.INFO, TAG,
                "Module loaded in " + param.getProcessName()
                        + "; provider=" + (runtimeProvider.isActive() ? "enabled" : "disabled")
                        + "; functional hooks=none");
    }

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        // Provider/config channel only. Never install a device-wide hook merely because the module
        // is loaded. A concrete system_server capability must be added behind runtimeProvider.
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        // Provider/config channel only. No SystemUI or third-party functional hooks are installed.
    }
}
