package com.yagay.floatlens.hook;

import android.graphics.Rect;
import android.util.Log;
import android.view.View;

import java.lang.reflect.Method;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

import io.github.libxposed.api.XposedModule;

/**
 * Reads Google's in-progress text selection geometry without changing Google's selection model.
 *
 * <p>There are two paths in 17.58:
 * 1) dnrs.g(ImmutableList, boolean, int) receives the current selected-word list during handle
 *    dragging. dnrt.onScroll -> dnrs.a(...) -> dnrs.g(...) runs repeatedly while the handle moves.
 * 2) dtvm.run() publishes the later/final range update. We keep it only as a final correction.
 * This avoids waiting until USER_SELECTION commits before moving FloatLens' passive frame.</p>
 */
final class GoogleTextSelectionLiveHook {
    private static final String TAG = "FloatLens-GoogleCTS";
    private static final String UPDATE_TASK = "dtvm";
    private static final String CONTROLLER = "dtvr";
    private static final String STATE = "dnrs";
    private static final String WORD = "dnrd";
    private static final String TEXT_SELECTION_VIEW =
            "com.google.android.libraries.lens.common.text.selection.ui.TextSelectionView";

    private final XposedModule module;
    private final ClassLoader classLoader;
    private final BooleanSupplier active;
    private final BiConsumer<Rect, String> sink;

    GoogleTextSelectionLiveHook(
            XposedModule module,
            ClassLoader classLoader,
            BooleanSupplier active,
            BiConsumer<Rect, String> sink) {
        this.module = module;
        this.classLoader = classLoader;
        this.active = active;
        this.sink = sink;
    }

    int install() {
        int count = 0;
        count += installStateListHook();
        count += installFinalTaskHook();
        module.log(Log.INFO, TAG,
                "Google live text selection hooks=" + count
                        + " sources=dnrs.g,dtvm.run");
        return count;
    }

    private int installStateListHook() {
        try {
            Class<?> stateClass = Class.forName(STATE, false, classLoader);
            Method target = null;
            for (Method method : GoogleReflection.declaredMethods(stateClass)) {
                if (!"g".equals(method.getName())
                        || method.getParameterCount() != 3
                        || method.getReturnType() != void.class) {
                    continue;
                }
                Class<?>[] p = method.getParameterTypes();
                if (GoogleLens1758Profile.IMMUTABLE_LIST.equals(p[0].getName())
                        && p[1] == boolean.class
                        && p[2] == int.class) {
                    target = method;
                    break;
                }
            }
            if (target == null) {
                module.log(Log.WARN, TAG,
                        "Google live word-list hook unavailable: dnrs.g missing");
                return 0;
            }

            module.hook(target).intercept(chain -> {
                Object state = chain.getThisObject();
                Object selected = chain.getArg(0);
                Object result = chain.proceed();
                if (!active.getAsBoolean() || state == null) return result;

                try {
                    LiveBounds live = readStateBounds(
                            state, selected, "dnrs.g");
                    publish(live);
                } catch (Throwable t) {
                    module.log(Log.WARN, TAG,
                            "Google live dnrs.g bounds read failed", t);
                }
                return result;
            });
            return 1;
        } catch (Throwable t) {
            module.log(Log.WARN, TAG,
                    "Google live word-list hook unavailable", t);
            return 0;
        }
    }

    private int installFinalTaskHook() {
        try {
            Class<?> taskClass = Class.forName(UPDATE_TASK, false, classLoader);
            Method run = null;
            for (Method method : GoogleReflection.declaredMethods(taskClass)) {
                if ("run".equals(method.getName())
                        && method.getParameterCount() == 0
                        && method.getReturnType() == void.class) {
                    run = method;
                    break;
                }
            }
            if (run == null) {
                module.log(Log.WARN, TAG,
                        "Google final text selection hook unavailable: dtvm.run missing");
                return 0;
            }

            module.hook(run).intercept(chain -> {
                Object task = chain.getThisObject();
                Object result = chain.proceed();
                if (!active.getAsBoolean() || task == null) return result;

                try {
                    LiveBounds live = readTaskBounds(task);
                    publish(live);
                } catch (Throwable t) {
                    module.log(Log.WARN, TAG,
                            "Google final text selection bounds read failed", t);
                }
                return result;
            });
            return 1;
        } catch (Throwable t) {
            module.log(Log.WARN, TAG,
                    "Google final text selection hook unavailable", t);
            return 0;
        }
    }

    private void publish(LiveBounds live) {
        if (live != null && live.bounds != null && !live.bounds.isEmpty()) {
            sink.accept(live.bounds, live.detail);
        }
    }

    private LiveBounds readTaskBounds(Object task) {
        Object controller = GoogleReflection.readField(task, "a", CONTROLLER);
        Object range = GoogleReflection.readField(task, "b", "dnqv");
        if (controller == null) return LiveBounds.empty("controller_missing");

        Object state = GoogleReflection.invokeNoArg(controller, "c");
        if (state == null || !STATE.equals(state.getClass().getName())) {
            return LiveBounds.empty("state_missing");
        }

        LiveBounds live = readStateBounds(state, null, "dtvm");
        if (live.bounds == null) return live;

        String rangeText = range == null ? "null" : safe(String.valueOf(range));
        return new LiveBounds(
                live.bounds,
                live.detail + " range=" + trim(rangeText, 260));
    }

    private LiveBounds readStateBounds(
            Object state, Object selectedOverride, String source) {
        if (state == null || !STATE.equals(state.getClass().getName())) {
            return LiveBounds.empty(source + " state_missing");
        }

        Object rawView = GoogleReflection.readField(
                state, "g", TEXT_SELECTION_VIEW);
        if (!(rawView instanceof View textView)
                || textView.getWidth() <= 0
                || textView.getHeight() <= 0) {
            return LiveBounds.empty(source + " text_view_missing");
        }

        Object selected = selectedOverride;
        if (!(selected instanceof Iterable<?>)) {
            selected = GoogleReflection.readField(
                    state, "c", GoogleLens1758Profile.IMMUTABLE_LIST);
        }

        Rect union = new Rect();
        int words = addWords(union, selected);

        // Endpoints are updated during handle motion even for the brief instant where the list is
        // being replaced, so use them as a one-frame fail-soft fallback.
        if (words == 0) {
            words += addWord(union, GoogleReflection.readField(state, "a", WORD));
            words += addWord(union, GoogleReflection.readField(state, "b", WORD));
        }
        if (words == 0 || union.isEmpty()) {
            return LiveBounds.empty(source + " selected_words_empty");
        }

        int[] origin = new int[2];
        textView.getLocationOnScreen(origin);
        Rect screen = new Rect(union);
        screen.offset(origin[0], origin[1]);

        Rect viewScreen = new Rect(
                origin[0],
                origin[1],
                origin[0] + textView.getWidth(),
                origin[1] + textView.getHeight());
        if (!screen.intersect(viewScreen) || screen.isEmpty()) {
            return LiveBounds.empty(source + " outside_text_view");
        }

        return new LiveBounds(
                screen,
                "source=" + source
                        + " selectedWords=" + words
                        + " local=" + union
                        + " screen=" + screen);
    }

    private static int addWords(Rect union, Object value) {
        if (!(value instanceof Iterable<?> items)) return 0;
        int count = 0;
        for (Object item : items) count += addWord(union, item);
        return count;
    }

    private static int addWord(Rect union, Object word) {
        if (word == null || !WORD.equals(word.getClass().getName())) return 0;
        Object value = GoogleReflection.readField(word, "d", Rect.class.getName());
        if (!(value instanceof Rect rect) || rect.isEmpty()) return 0;
        if (union.isEmpty()) union.set(rect);
        else union.union(rect);
        return 1;
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace("\n", " ").replace("\r", " ");
    }

    private static String trim(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static final class LiveBounds {
        final Rect bounds;
        final String detail;

        LiveBounds(Rect bounds, String detail) {
            this.bounds = bounds == null ? null : new Rect(bounds);
            this.detail = detail == null ? "" : detail;
        }

        static LiveBounds empty(String detail) {
            return new LiveBounds(null, detail);
        }
    }
}
