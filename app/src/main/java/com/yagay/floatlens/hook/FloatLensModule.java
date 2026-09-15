package com.yagay.floatlens.hook;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * libxposed API 102 entry point for FloatLens.
 *
 * <p>Hooks may be installed at process start, but user-visible behavior must remain inert unless
 * {@link LsposedRuntimeProvider} reports the corresponding app-controlled gate as active.</p>
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
                        + "; provider=" + (runtimeProvider.isActive() ? "enabled" : "disabled"));
    }

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        if (runtimeProvider == null) return;
        ClassLoader classLoader = param.getClassLoader();
        installSecureWindowStateHook(classLoader);
        installScreenCaptureHooks(classLoader);
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        // The first functional capability is system_server secure screenshot handling. SystemUI
        // remains in recommended scope for status/future capabilities but is not modified here.
    }

    private void installSecureWindowStateHook(ClassLoader classLoader) {
        try {
            Class<?> windowStateClass = classLoader.loadClass("com.android.server.wm.WindowState");
            ClassLoader systemServerClassLoader = windowStateClass.getClassLoader();
            Method isSecureLocked = windowStateClass.getDeclaredMethod("isSecureLocked");
            isSecureLocked.setAccessible(true);
            try {
                deoptimize(isSecureLocked);
                deoptimizeNamed(classLoader, "com.android.server.wm.WindowStateAnimator", "createSurfaceLocked");
                deoptimizeNamed(classLoader, "com.android.server.wm.WindowManagerService", "relayoutWindow");
            } catch (Throwable t) {
                log(Log.WARN, TAG, "Secure screenshot deoptimize partially failed", t);
            }

            hook(isSecureLocked)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        LsposedRuntimeProvider provider = runtimeProvider;
                        if (provider == null || !provider.canBypassSecureNow()) {
                            return chain.proceed();
                        }
                        if (isSurfaceCreationCall(classLoader, systemServerClassLoader)) {
                            return chain.proceed();
                        }
                        return false;
                    });
            log(Log.INFO, TAG, "Installed controlled WindowState.isSecureLocked hook");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to install WindowState secure screenshot hook", t);
        }
    }

    private void installScreenCaptureHooks(ClassLoader classLoader) {
        String[][] candidates = {
                {"android.window.ScreenCaptureInternal", "android.window.ScreenCaptureInternal$CaptureArgs"},
                {"android.window.ScreenCapture", "android.window.ScreenCapture$CaptureArgs"},
                {"android.view.SurfaceControl", "android.view.SurfaceControl$CaptureArgs"}
        };

        Throwable last = null;
        for (String[] candidate : candidates) {
            try {
                Class<?> screenCaptureClass = classLoader.loadClass(candidate[0]);
                Class<?> captureArgsClass = classLoader.loadClass(candidate[1]);
                Field secureField = findSecureCaptureField(captureArgsClass);
                secureField.setAccessible(true);
                int count = 0;
                for (Method method : screenCaptureClass.getDeclaredMethods()) {
                    String name = method.getName();
                    if (!"nativeCaptureDisplay".equals(name) && !"nativeCaptureLayers".equals(name)) {
                        continue;
                    }
                    if (method.getParameterCount() == 0) continue;
                    method.setAccessible(true);
                    hook(method)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                LsposedRuntimeProvider provider = runtimeProvider;
                                if (provider != null && provider.canBypassSecureNow()) {
                                    Object captureArgs = chain.getArg(0);
                                    if (captureArgs != null) setCaptureSecureAllowed(secureField, captureArgs);
                                }
                                return chain.proceed();
                            });
                    count++;
                }
                if (count > 0) {
                    log(Log.INFO, TAG, "Installed " + count + " controlled ScreenCapture hooks via "
                            + candidate[0] + "." + secureField.getName());
                    return;
                }
            } catch (Throwable t) {
                last = t;
            }
        }
        if (last != null) {
            log(Log.ERROR, TAG, "Failed to install controlled ScreenCapture hooks", last);
        } else {
            log(Log.WARN, TAG, "No compatible ScreenCapture hook point found");
        }
    }

    private Field findSecureCaptureField(Class<?> captureArgsClass) throws NoSuchFieldException {
        try {
            return captureArgsClass.getDeclaredField("mSecureContentPolicy");
        } catch (NoSuchFieldException ignored) {
            return captureArgsClass.getDeclaredField("mCaptureSecureLayers");
        }
    }

    private static void setCaptureSecureAllowed(Field field, Object captureArgs) throws IllegalAccessException {
        Class<?> type = field.getType();
        if (type == boolean.class || type == Boolean.class) {
            field.setBoolean(captureArgs, true);
        } else if (type == int.class || type == Integer.class) {
            field.setInt(captureArgs, 1);
        } else {
            field.set(captureArgs, true);
        }
    }

    private boolean isSurfaceCreationCall(ClassLoader classLoader, ClassLoader systemServerClassLoader) {
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            String method = frame.getMethodName();
            if (!"setInitialSurfaceControlProperties".equals(method)
                    && !"createSurfaceLocked".equals(method)) {
                continue;
            }
            try {
                Class<?> owner = classLoader.loadClass(frame.getClassName());
                if (owner.getClassLoader() == systemServerClassLoader) return true;
            } catch (Throwable ignored) {
                // A frame from another loader cannot be the system_server surface-creation path.
            }
        }
        return false;
    }

    private void deoptimizeNamed(ClassLoader classLoader, String className, String methodName)
            throws ClassNotFoundException {
        Class<?> clazz = classLoader.loadClass(className);
        for (Method method : clazz.getDeclaredMethods()) {
            if (methodName.equals(method.getName())) deoptimize(method);
        }
    }
}
