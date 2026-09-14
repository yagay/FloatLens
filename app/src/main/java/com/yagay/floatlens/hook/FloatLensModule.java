package com.yagay.floatlens.hook;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Clean LSPosed entry point for FloatLens.
 *
 * This module intentionally contains no target-specific hooks. In particular it does not inspect,
 * probe, log, or modify FV/fooView. Future generic FloatLens providers can be added here behind the
 * in-app LSPosed privilege switch.
 */
public final class FloatLensModule extends XposedModule {
    @Override public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        // Intentionally empty. No third-party runtime capture is installed here.
    }
}
