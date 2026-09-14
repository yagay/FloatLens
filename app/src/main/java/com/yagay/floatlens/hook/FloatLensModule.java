package com.yagay.floatlens.hook;

import io.github.libxposed.api.XposedModule;

/**
 * Clean libxposed API 102 entry point for FloatLens.
 *
 * <p>LSPosed is an optional enhancement layer, so loading the module must never globally change
 * Android behavior on its own. In particular, do not install unconditional system_server hooks
 * that disable FLAG_SECURE for every application: an in-app SharedPreferences switch cannot
 * reliably gate an already-installed system_server hook.</p>
 *
 * <p>Future LSPosed capabilities belong behind an explicit cross-process provider/configuration
 * channel and must fall back to the normal Accessibility / Android implementation when disabled.
 * Until such a provider is present this module intentionally installs no hooks.</p>
 */
public final class FloatLensModule extends XposedModule {
    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        // Intentionally empty. Never apply a device-wide hook merely because the module is loaded.
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        // Intentionally empty. No third-party target-specific hooks are installed.
    }
}
