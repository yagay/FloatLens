package com.yagay.floatlens.hook;

import android.app.Activity;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import io.github.libxposed.api.XposedModule;

/** Owns Google Lens frozen-image viewport suppression and transform diagnostics. */
final class GoogleLensViewportHook {
    private static final String TAG = "FloatLens-GoogleCTS";

    private final XposedModule module;
    private final ClassLoader classLoader;
    private final BooleanSupplier active;
    private final BooleanSupplier selectionSeen;
    private final Supplier<Activity> activity;
    private final BiConsumer<String, String> reporter;
    private final Handler main = new Handler(Looper.getMainLooper());

    GoogleLensViewportHook(XposedModule module,
                           ClassLoader classLoader,
                           BooleanSupplier active,
                           BooleanSupplier selectionSeen,
                           Supplier<Activity> activity,
                           BiConsumer<String, String> reporter) {
        this.module = module;
        this.classLoader = classLoader;
        this.active = active;
        this.selectionSeen = selectionSeen;
        this.activity = activity;
        this.reporter = reporter;
    }

    int install() {
        try {
            Class<?> controller = Class.forName(
                    GoogleLens1758Profile.VIEWPORT_CONTROLLER, false, classLoader);
            Class<?> requestClass = Class.forName(
                    GoogleLens1758Profile.VIEWPORT_REQUEST, false, classLoader);
            Class<?> stateClass = Class.forName(
                    GoogleLens1758Profile.VIEWPORT_STATE, false, classLoader);
            int count = 0;

            for (Executable executable : HiddenApiBypass.getDeclaredMethods(controller)) {
                if (!(executable instanceof Method method)) continue;
                Class<?>[] params = method.getParameterTypes();

                if ("r".equals(method.getName())
                        && params.length == 1
                        && params[0] == requestClass
                        && method.getReturnType() == void.class) {
                    module.hook(method).intercept(chain -> {
                        Object request = chain.getArg(0);
                        Object rawBounds = GoogleReflection.readNamedField(request, "c");
                        RectF focusBounds = rawBounds instanceof RectF rect
                                ? new RectF(rect) : null;
                        boolean hasBounds = focusBounds != null
                                && focusBounds.width() > 0f
                                && focusBounds.height() > 0f;
                        Object rawSource = GoogleReflection.readNamedField(request, "e");
                        int source = rawSource instanceof Integer value ? value : -1;

                        if (!active.getAsBoolean()
                                || !GoogleLens1758Profile.shouldSuppressTextViewportFocus(
                                        request == null ? "" : request.getClass().getName(),
                                        source, hasBounds)) {
                            return chain.proceed();
                        }

                        reporter.accept("GOOGLE_FROZEN_IMAGE_TEXT_FOCUS_SUPPRESSED_EARLY",
                                "controller=duec.r source=" + source
                                        + " selectionSeen=" + selectionSeen.getAsBoolean()
                                        + " bounds=" + focusBounds
                                        + " request=" + compact(request, 360));
                        reportTransform("beforeEarlyTextFocus");
                        main.postDelayed(() -> reportTransform("after120ms"), 120L);
                        main.postDelayed(() -> reportTransform("after300ms"), 300L);
                        return null;
                    });
                    count++;
                    continue;
                }

                if ("n".equals(method.getName())
                        && params.length == 1
                        && params[0] == stateClass
                        && method.getReturnType() == void.class) {
                    module.hook(method).intercept(chain -> {
                        if (!active.getAsBoolean()) return chain.proceed();
                        Object state = chain.getArg(0);
                        reporter.accept("GOOGLE_FROZEN_IMAGE_VIEWPORT_STATE_PATH",
                                "controller=duec.n selectionSeen=" + selectionSeen.getAsBoolean()
                                        + " state=" + compact(state, 420));
                        reportTransform("beforeDuecN");
                        Object result = chain.proceed();
                        main.postDelayed(() -> reportTransform("afterDuecN120ms"), 120L);
                        return result;
                    });
                    count++;
                }
            }

            module.log(Log.INFO, TAG, "Google FrozenImage viewport hooks=" + count);
            return count;
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "Google FrozenImage viewport boundary unavailable", t);
            return 0;
        }
    }

    private void reportTransform(String phase) {
        if (!active.getAsBoolean()) return;
        Activity owner = activity.get();
        if (owner == null) return;
        owner.runOnUiThread(() -> {
            if (!active.getAsBoolean()) return;
            try {
                View root = owner.getWindow() == null ? null : owner.getWindow().getDecorView();
                View image = GoogleLensViewIntrospection.findByClassName(
                        root, GoogleLens1758Profile.FROZEN_IMAGE_VIEW);
                if (image == null) {
                    reporter.accept("GOOGLE_FROZEN_IMAGE_TRANSFORM",
                            "phase=" + phase + " view=missing");
                    return;
                }
                int[] loc = new int[2];
                try { image.getLocationOnScreen(loc); } catch (Throwable ignored) { }
                reporter.accept("GOOGLE_FROZEN_IMAGE_TRANSFORM",
                        "phase=" + phase
                                + " scaleX=" + image.getScaleX()
                                + " scaleY=" + image.getScaleY()
                                + " translationX=" + image.getTranslationX()
                                + " translationY=" + image.getTranslationY()
                                + " xy=" + loc[0] + "," + loc[1]
                                + " wh=" + image.getWidth() + "x" + image.getHeight());
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, "Failed to inspect FrozenImage transform", t);
            }
        });
    }

    private static String compact(Object value, int max) {
        if (value == null) return "null";
        String text;
        try {
            text = value.getClass().getName() + "{" + String.valueOf(value) + "}";
        } catch (Throwable t) {
            text = value.getClass().getName();
        }
        return GoogleLensViewIntrospection.trim(text, max);
    }
}
