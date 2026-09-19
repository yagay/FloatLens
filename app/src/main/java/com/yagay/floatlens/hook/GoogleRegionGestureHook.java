package com.yagay.floatlens.hook;

import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

import io.github.libxposed.api.XposedModule;

/** Tracks real FrozenImageView touch lifecycle so FloatLens confirm UI never covers refinements. */
final class GoogleRegionGestureHook {
    interface GesturePointSink {
        void onGesturePoint(int action, float x, float y, String detail);
    }

    private static final String TAG = "FloatLens-GoogleCTS";

    private final XposedModule module;
    private final ClassLoader classLoader;
    private final BooleanSupplier active;
    private final BiConsumer<Boolean, String> stateSink;
    private final GesturePointSink pointSink;

    private static final long MOVE_HEARTBEAT_MS = 120L;

    static final class Binding {
        final Class<?> viewClass;
        final Method[] touchMethods;
        final int confidence;
        final String source;
        final String detail;

        Binding(Class<?> viewClass, Method[] touchMethods,
                int confidence, String source, String detail) {
            this.viewClass = viewClass;
            this.touchMethods = touchMethods == null ? new Method[0] : touchMethods;
            this.confidence = confidence;
            this.source = source == null ? "none" : source;
            this.detail = detail == null ? "" : detail;
        }

        boolean available() { return viewClass != null && touchMethods.length > 0; }
    }

    static Binding resolve(ClassLoader loader) {
        if (loader == null) return new Binding(null, null, 0, "none", "classLoader=null");
        try {
            Class<?> cls = Class.forName(
                    GoogleLens1758Profile.FROZEN_IMAGE_VIEW, false, loader);
            List<Method> methods = new ArrayList<>();
            for (Method method : GoogleReflection.declaredMethods(cls)) {
                if (motionEventIndex(method.getParameterTypes()) >= 0) methods.add(method);
            }
            int confidence = methods.isEmpty() ? 0 : 95;
            return new Binding(cls, methods.toArray(new Method[0]), confidence,
                    "frozen-image-view",
                    "class=" + cls.getName() + " touchMethods=" + methods.size());
        } catch (Throwable t) {
            return new Binding(null, null, 0, "none",
                    "resolve=" + t.getClass().getSimpleName());
        }
    }

    private boolean gestureActive;
    private long lastHeartbeatElapsed;

    GoogleRegionGestureHook(XposedModule module,
                            ClassLoader classLoader,
                            BooleanSupplier active,
                            BiConsumer<Boolean, String> stateSink,
                            GesturePointSink pointSink) {
        this.module = module;
        this.classLoader = classLoader;
        this.active = active;
        this.stateSink = stateSink;
        this.pointSink = pointSink;
    }

    int install() {
        return install(resolve(classLoader));
    }

    int install(Binding binding) {
        if (binding == null) binding = resolve(classLoader);
        if (!binding.available()) {
            module.log(Log.WARN, TAG,
                    "Google region gesture capability unavailable: " + binding.detail);
            return 0;
        }
        try {
            int count = 0;
            for (Method method : binding.touchMethods) {
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
            module.log(Log.INFO, TAG,
                    "Google region gesture capability source=" + binding.source
                            + " confidence=" + binding.confidence
                            + " hooks=" + count + " detail=" + binding.detail);
            return count;
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "Google FrozenImage gesture hooks unavailable", t);
            return 0;
        }
    }

    synchronized void reset() {
        gestureActive = false;
        lastHeartbeatElapsed = 0L;
    }

    private synchronized void dispatchGestureState(MotionEvent event, String methodName) {
        int action = event.getActionMasked();
        long now = SystemClock.elapsedRealtime();
        String pointDetail = describe(event, methodName, action, "point");
        if (pointSink != null) {
            try {
                pointSink.onGesturePoint(action, event.getX(), event.getY(), pointDetail);
            } catch (Throwable ignored) { }
        }

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            gestureActive = true;
            lastHeartbeatElapsed = now;
            stateSink.accept(true, describe(event, methodName, action, "start"));
            return;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            if (!gestureActive) gestureActive = true;
            if (now - lastHeartbeatElapsed >= MOVE_HEARTBEAT_MS) {
                lastHeartbeatElapsed = now;
                stateSink.accept(true, describe(event, methodName, action, "heartbeat"));
            }
            return;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            gestureActive = false;
            lastHeartbeatElapsed = 0L;
            // Emit the end even if an earlier Google-internal method already altered our state.
            // Different 17.58 FrozenImageView paths can observe DOWN and terminal events in
            // different methods; the app-side state machine de-duplicates harmless duplicates.
            stateSink.accept(false, describe(event, methodName, action, "end"));
        }
    }

    private static String describe(
            MotionEvent event, String methodName, int action, String phase) {
        return "phase=" + phase
                + " action=" + actionName(action)
                + " method=" + methodName
                + " pointers=" + event.getPointerCount()
                + " downTime=" + event.getDownTime()
                + " eventTime=" + event.getEventTime();
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
