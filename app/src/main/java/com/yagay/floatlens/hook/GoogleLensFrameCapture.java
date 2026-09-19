package com.yagay.floatlens.hook;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;

import com.yagay.floatlens.GoogleCtsContract;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedModule;

/**
 * Captures the frozen Google Lens frame from Google App's own 17.58 data path.
 *
 * <p>The framework VoiceInteraction callback is retained elsewhere as a final fallback. This class
 * prefers Google-owned boundaries that are present in the analyzed 17.58.16.ve APK:
 * eggn.onHandleScreenshot(Bitmap), dqyy InitialActivityData.a():Bitmap and
 * com.google.android.libraries.lens.view.InProcessBitmap.</p>
 */
final class GoogleLensFrameCapture {
    private static final String TAG = "FloatLens-GoogleCTS";
    private static final String VOICE_SESSION_IMPL = "eggn";
    private static final String INITIAL_ACTIVITY_DATA = "dqyy";
    private static final String INITIAL_ACTIVITY_PARSER = "dqzb";
    private static final String IN_PROCESS_BITMAP =
            "com.google.android.libraries.lens.view.InProcessBitmap";

    private final XposedModule module;
    private final ClassLoader classLoader;
    private final BooleanSupplier active;
    private final Consumer<Bitmap> sink;
    private final BiConsumer<String, String> reporter;

    GoogleLensFrameCapture(XposedModule module,
                           ClassLoader classLoader,
                           BooleanSupplier active,
                           Consumer<Bitmap> sink,
                           BiConsumer<String, String> reporter) {
        this.module = module;
        this.classLoader = classLoader;
        this.active = active;
        this.sink = sink;
        this.reporter = reporter;
    }

    int install() {
        int hooks = 0;
        hooks += hookGoogleVoiceSessionScreenshot();
        hooks += hookInitialActivityDataBitmap();
        hooks += hookInitialActivityParser();
        hooks += hookInProcessBitmap();
        module.log(Log.INFO, TAG, "Google Lens frame capture hooks=" + hooks);
        return hooks;
    }

    void captureFromIntent(Intent intent) {
        if (!active.getAsBoolean() || intent == null) return;
        Bundle extras;
        try {
            extras = intent.getExtras();
        } catch (Throwable t) {
            return;
        }
        if (extras == null) return;

        captureCandidate(safeGet(extras, "injected_image_bitmap"),
                "intent.injected_image_bitmap");
        captureCandidate(safeGet(extras, "bootstrap_image"),
                "intent.bootstrap_image");
        captureCandidate(safeGet(extras, GoogleCtsContract.CONTEXTUAL_SCREENSHOT),
                "intent.contextual_screenshot");
    }

    private int hookGoogleVoiceSessionScreenshot() {
        try {
            Class<?> cls = Class.forName(VOICE_SESSION_IMPL, false, classLoader);
            int count = 0;
            for (Method method : GoogleReflection.declaredMethods(cls)) {
                Class<?>[] p = method.getParameterTypes();
                if (!"onHandleScreenshot".equals(method.getName())
                        || p.length != 1 || p[0] != Bitmap.class) {
                    continue;
                }
                module.hook(method).intercept(chain -> {
                    if (active.getAsBoolean()) {
                        captureCandidate(chain.getArg(0), "eggn.onHandleScreenshot");
                    }
                    return chain.proceed();
                });
                count++;
            }
            return count;
        } catch (Throwable t) {
            module.log(Log.INFO, TAG, "Google voice screenshot implementation unavailable", t);
            return 0;
        }
    }

    private int hookInitialActivityDataBitmap() {
        try {
            Class<?> cls = Class.forName(INITIAL_ACTIVITY_DATA, false, classLoader);
            int count = 0;
            for (Method method : GoogleReflection.declaredMethods(cls)) {
                if (!"a".equals(method.getName())
                        || method.getParameterCount() != 0
                        || method.getReturnType() != Bitmap.class) {
                    continue;
                }
                module.hook(method).intercept(chain -> {
                    Object result = chain.proceed();
                    if (active.getAsBoolean()) {
                        captureCandidate(result, "InitialActivityData.a");
                    }
                    return result;
                });
                count++;
            }
            return count;
        } catch (Throwable t) {
            module.log(Log.INFO, TAG, "InitialActivityData bitmap getter unavailable", t);
            return 0;
        }
    }

    private int hookInitialActivityParser() {
        try {
            Class<?> cls = Class.forName(INITIAL_ACTIVITY_PARSER, false, classLoader);
            int count = 0;
            for (Method method : GoogleReflection.declaredMethods(cls)) {
                if (!"b".equals(method.getName())
                        || !INITIAL_ACTIVITY_DATA.equals(method.getReturnType().getName())) {
                    continue;
                }
                module.hook(method).intercept(chain -> {
                    Object result = chain.proceed();
                    if (active.getAsBoolean()) {
                        captureCandidate(result, "InitialActivityData.parser");
                    }
                    return result;
                });
                count++;
            }
            return count;
        } catch (Throwable t) {
            module.log(Log.INFO, TAG, "InitialActivityData parser unavailable", t);
            return 0;
        }
    }

    private int hookInProcessBitmap() {
        try {
            Class<?> cls = Class.forName(IN_PROCESS_BITMAP, false, classLoader);
            int count = 0;
            for (Method method : GoogleReflection.declaredMethods(cls)) {
                if (method.getParameterCount() != 0 || method.getReturnType() != Bitmap.class) {
                    continue;
                }
                module.hook(method).intercept(chain -> {
                    Object result = chain.proceed();
                    if (active.getAsBoolean()) {
                        captureCandidate(result,
                                "InProcessBitmap." + method.getName());
                    }
                    return result;
                });
                count++;
            }
            return count;
        } catch (Throwable t) {
            module.log(Log.INFO, TAG, "InProcessBitmap getter unavailable", t);
            return 0;
        }
    }

    private void captureCandidate(Object value, String source) {
        if (!active.getAsBoolean() || value == null) return;
        Bitmap bitmap = extractBitmap(value, 0,
                Collections.newSetFromMap(new IdentityHashMap<>()));
        if (bitmap == null || bitmap.isRecycled()
                || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            return;
        }
        reporter.accept("GOOGLE_FRAME_CAPTURED",
                "source=" + source
                        + " bitmap=" + bitmap.getWidth() + "x" + bitmap.getHeight()
                        + "/" + bitmap.getConfig()
                        + " valueClass=" + value.getClass().getName());
        sink.accept(bitmap);
    }

    private Bitmap extractBitmap(Object value, int depth, Set<Object> seen) {
        if (value == null || depth > 3 || seen.contains(value)) return null;
        seen.add(value);
        if (value instanceof Bitmap bitmap) {
            return bitmap.isRecycled() ? null : bitmap;
        }

        String className = value.getClass().getName();
        boolean knownWrapper = INITIAL_ACTIVITY_DATA.equals(className)
                || IN_PROCESS_BITMAP.equals(className);
        if (!knownWrapper) return null;

        for (Method method : GoogleReflection.methodsInHierarchy(value.getClass())) {
            if (method.getParameterCount() != 0 || method.getReturnType() != Bitmap.class) {
                continue;
            }
            try {
                method.setAccessible(true);
                Object result = method.invoke(value);
                if (result instanceof Bitmap bitmap && !bitmap.isRecycled()) return bitmap;
            } catch (Throwable ignored) { }
        }

        Object field = GoogleReflection.readField(value, null, Bitmap.class.getName());
        return field instanceof Bitmap bitmap && !bitmap.isRecycled() ? bitmap : null;
    }

    private static Object safeGet(Bundle bundle, String key) {
        if (bundle == null || key == null || key.isBlank()) return null;
        try {
            return bundle.get(key);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
