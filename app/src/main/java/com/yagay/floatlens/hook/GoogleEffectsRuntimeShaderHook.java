package com.yagay.floatlens.hook;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedModule;

/** Captures Google Lens' live EffectsV2 RuntimeShader without changing the real EffectsV2View. */
final class GoogleEffectsRuntimeShaderHook {
    private static final String TAG = "FloatLens-GoogleCTS";
    private static final String EFFECTS_PEER = "dpls";
    private static final String RUNTIME_SHADER = "android.graphics.RuntimeShader";

    private final XposedModule module;
    private final ClassLoader classLoader;
    private final Consumer<Object> sink;

    GoogleEffectsRuntimeShaderHook(
            XposedModule module,
            ClassLoader classLoader,
            Consumer<Object> sink) {
        this.module = module;
        this.classLoader = classLoader;
        this.sink = sink;
    }

    int install() {
        try {
            Class<?> peer = Class.forName(EFFECTS_PEER, false, classLoader);
            Method target = null;
            for (Method method : GoogleReflection.declaredMethods(peer)) {
                if (!"d".equals(method.getName())
                        || method.getParameterCount() != 1
                        || method.getReturnType() != void.class) {
                    continue;
                }
                if (RUNTIME_SHADER.equals(method.getParameterTypes()[0].getName())) {
                    target = method;
                    break;
                }
            }
            if (target == null) {
                module.log(Log.WARN, TAG,
                        "Google EffectsV2 RuntimeShader hook unavailable: dpls.d missing");
                return 0;
            }

            module.hook(target).intercept(chain -> {
                Object shader = chain.getArg(0);
                Object result = chain.proceed();
                if (shader != null && sink != null) {
                    try { sink.accept(shader); } catch (Throwable ignored) { }
                }
                return result;
            });
            module.log(Log.INFO, TAG,
                    "Google EffectsV2 RuntimeShader capture hook installed");
            return 1;
        } catch (Throwable t) {
            module.log(Log.WARN, TAG,
                    "Google EffectsV2 RuntimeShader capture hook unavailable", t);
            return 0;
        }
    }
}
