package com.yagay.floatlens.hook;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * Short-lived secure-window screenshot support for FloatLens captures.
 *
 * <p>The hook never removes a window/surface secure flag. It only changes system screenshot capture
 * arguments while {@link LsposedRuntimeProvider#isSecureCaptureArmed()} is true. The app arms that
 * gate for a short lease immediately around its own capture operation.</p>
 */
final class SecureScreenshotHook {
    private static final String TAG = "FloatLens-SecureCapture";

    private final XposedModule module;
    private final LsposedRuntimeProvider provider;
    private final ClassLoader classLoader;

    SecureScreenshotHook(XposedModule module,
                         LsposedRuntimeProvider provider,
                         ClassLoader classLoader) {
        this.module = module;
        this.provider = provider;
        this.classLoader = classLoader;
    }

    void install() {
        int captureHooks = 0;
        try {
            captureHooks += installCaptureArgsHook(
                    "android.window.ScreenCaptureInternal",
                    "android.window.ScreenCaptureInternal$CaptureArgs",
                    "mSecureContentPolicy",
                    true);
        } catch (Throwable t) {
            module.log(Log.INFO, TAG, "ScreenCaptureInternal path unavailable", t);
        }

        if (captureHooks == 0) {
            try {
                captureHooks += installCaptureArgsHook(
                        "android.window.ScreenCapture",
                        "android.window.ScreenCapture$CaptureArgs",
                        "mCaptureSecureLayers",
                        false);
            } catch (Throwable t) {
                module.log(Log.ERROR, TAG, "ScreenCapture path unavailable", t);
            }
        }

        try {
            installWindowStateCompatibilityHook();
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "WindowState compatibility hook unavailable", t);
        }

        module.log(captureHooks > 0 ? Log.INFO : Log.WARN, TAG,
                "Secure screenshot hooks installed captureHooks=" + captureHooks
                        + "; gated by short FloatLens lease");
    }

    private int installCaptureArgsHook(String captureClassName,
                                       String argsClassName,
                                       String secureFieldName,
                                       boolean integerPolicy) throws Exception {
        Class<?> captureClass = classLoader.loadClass(captureClassName);
        Class<?> argsClass = classLoader.loadClass(argsClassName);
        Field secureField = argsClass.getDeclaredField(secureFieldName);
        secureField.setAccessible(true);

        int installed = 0;
        for (Method method : captureClass.getDeclaredMethods()) {
            String name = method.getName();
            if (!"nativeCaptureDisplay".equals(name) && !"nativeCaptureLayers".equals(name)) continue;
            module.hook(method).intercept(chain -> {
                if (!provider.isSecureCaptureArmed()) return chain.proceed();
                Object captureArgs = chain.getArg(0);
                if (captureArgs != null && argsClass.isInstance(captureArgs)) {
                    try {
                        if (integerPolicy) secureField.setInt(captureArgs, 1);
                        else secureField.setBoolean(captureArgs, true);
                    } catch (Throwable t) {
                        module.log(Log.ERROR, TAG, "Failed to enable secure layers for " + name, t);
                    }
                }
                return chain.proceed();
            });
            installed++;
        }
        return installed;
    }

    /**
     * Some screenshot policy checks consult WindowState.isSecureLocked before capture args are
     * evaluated. Return false only during the short lease, except while WM is creating/configuring
     * the actual surface so the surface remains genuinely secure outside FloatLens capture.
     */
    private void installWindowStateCompatibilityHook() throws Exception {
        Class<?> windowState = classLoader.loadClass("com.android.server.wm.WindowState");
        Method isSecureLocked = windowState.getDeclaredMethod("isSecureLocked");
        module.hook(isSecureLocked).intercept(chain -> {
            if (!provider.isSecureCaptureArmed()) return chain.proceed();
            if (isSurfaceSecuritySetupCall()) return chain.proceed();
            return false;
        });
    }

    private boolean isSurfaceSecuritySetupCall() {
        StackTraceElement[] stack = new Throwable().getStackTrace();
        for (StackTraceElement frame : stack) {
            String method = frame.getMethodName();
            if (!("setInitialSurfaceControlProperties".equals(method)
                    || "createSurfaceLocked".equals(method))) continue;
            String clazz = frame.getClassName();
            if (clazz != null && clazz.startsWith("com.android.server.wm.")) return true;
        }
        return false;
    }
}
