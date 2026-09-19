package com.yagay.floatlens.hook;

import android.util.Log;
import android.view.MotionEvent;

import java.lang.reflect.Method;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

import io.github.libxposed.api.XposedModule;

/** Tracks real FrozenImageView touch lifecycle so FloatLens confirm UI never covers refinements. */
final class GoogleRegionGestureHook {
    private static final String TAG = "FloatLens-GoogleCTS";

    private final XposedModule module;
    private final ClassLoader classLoader;
    private final BooleanSupplier active;
    private final BiConsumer<Boolean, String> stateSink;

    private boolean gestureActive;

    GoogleRegionGestureHook(XposedModule module,
                            ClassLoader classLoader,
                            BooleanSupplier active,
                            BiConsumer<Boolean, String> stateSink) {
        this.module = module;
        this.classLoader = classLoader;
        this.active = active;
        this.stateSink = stateSink;
    }

    int install() {
        try {
            Class<?> cls = Class.forName(
                    GoogleLens1758Profile.FROZEN_IMAGE_VIEW, false, classLoader);
            int count = 0;
            for (Method method : GoogleReflection.declaredMethods(cls)) {
                int motionIndex = motionEventIndex(method.getParameterTypes());
                if (motionIndex < 0) continue;
                final int index = motionIndex;
                module.hook(method).intercept(chain -> {
                    MotionEvent event = chain.getArg(index) instanceof MotionEvent motion
                            ? motion : null;
                    if (active.getAsBoolean() && event != null) {
                        dispatchGestureState(event, method.getName());
                    }
                    return chain.proceed();
                });
                count++;
            }
            module.log(Log.INFO, TAG, "Google region gesture hooks=" + count);
            return count;
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "Google FrozenImage gesture hooks unavailable", t);
            return 0;
        }
    }

    synchronized void reset() {
        gestureActive = false;
    }

    private synchronized void dispatchGestureState(MotionEvent event, String methodName) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE
                || action == MotionEvent.ACTION_POINTER_DOWN) {
            if (!gestureActive) {
                gestureActive = true;
                stateSink.accept(true,
                        "action=" + actionName(action)
                                + " method=" + methodName
                                + " pointers=" + event.getPointerCount()
                                + " downTime=" + event.getDownTime());
            }
            return;
        }

        if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                && gestureActive) {
            gestureActive = false;
            stateSink.accept(false,
                    "action=" + actionName(action)
                            + " method=" + methodName
                            + " pointers=" + event.getPointerCount()
                            + " downTime=" + event.getDownTime());
        }
    }

    private static int motionEventIndex(Class<?>[] parameters) {
        if (parameters == null) return -1;
        for (int i = 0; i < parameters.length; i++) {
            if (MotionEvent.class.isAssignableFrom(parameters[i])) return i;
        }
        return -1;
    }

    private static String actionName(int action) {
        return switch (action) {
            case MotionEvent.ACTION_DOWN -> "DOWN";
            case MotionEvent.ACTION_MOVE -> "MOVE";
            case MotionEvent.ACTION_UP -> "UP";
            case MotionEvent.ACTION_CANCEL -> "CANCEL";
            case MotionEvent.ACTION_POINTER_DOWN -> "POINTER_DOWN";
            case MotionEvent.ACTION_POINTER_UP -> "POINTER_UP";
            default -> String.valueOf(action);
        };
    }
}
