package com.yagay.floatlens.hook;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import java.lang.reflect.Method;

/**
 * Enhanced LSPosed module for FloatLens.
 *
 * Provides system-level bypass for FLAG_SECURE to enable screenshots and view selection
 * in secure windows (e.g., banking apps).
 */
public final class FloatLensModule extends XposedModule {

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        try {
            // Hook WindowState.isSecureLocked to globally disable FLAG_SECURE.
            // This allows the system to capture screenshots of "secure" windows.
            Class<?> windowStateClass = param.getClassLoader().loadClass("com.android.server.wm.WindowState");
            Method isSecureLocked = windowStateClass.getDeclaredMethod("isSecureLocked");
            hook(isSecureLocked).intercept(chain -> {
                // Always return false for secure check.
                return false;
            });

            // Hook SurfaceControl.Builder.setSecure to prevent physical layer security flags.
            Class<?> builderClass = param.getClassLoader().loadClass("android.view.SurfaceControl$Builder");
            Method setSecure = builderClass.getDeclaredMethod("setSecure", boolean.class);
            hook(setSecure).intercept(chain -> {
                // Force the secure flag to be false.
                Object[] args = chain.getArgs().toArray();
                args[0] = false;
                return chain.proceed(args);
            });
        } catch (Throwable ignored) {
            // System server hooks are critical; avoid throwing to prevent boot loops.
        }
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        // Intentionally empty. No third-party runtime capture is installed here.
    }
}
