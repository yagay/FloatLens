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
 * <p>Google 17.58 posts dtvm tasks while text handles move. Each task owns the current dnqv range
 * and dtvr controller. After Google's task runs, dnrs.c contains the current selected word items;
 * every dnrd word carries its live Rect. Unioning those rectangles gives the visual bounds for the
 * passive FloatLens frame on every drag update instead of only after the final USER_SELECTION.</p>
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
                        "Google live text selection hook unavailable: dtvm.run missing");
                return 0;
            }

            module.hook(run).intercept(chain -> {
                Object task = chain.getThisObject();
                Object result = chain.proceed();
                if (!active.getAsBoolean() || task == null) return result;

                try {
                    LiveBounds live = readLiveBounds(task);
                    if (live.bounds != null && !live.bounds.isEmpty()) {
                        sink.accept(live.bounds, live.detail);
                    }
                } catch (Throwable t) {
                    module.log(Log.WARN, TAG,
                            "Google live text selection bounds read failed", t);
                }
                return result;
            });

            module.log(Log.INFO, TAG,
                    "Google live text selection hook installed source=dtvm.run");
            return 1;
        } catch (Throwable t) {
            module.log(Log.WARN, TAG,
                    "Google live text selection hook unavailable", t);
            return 0;
        }
    }

    private LiveBounds readLiveBounds(Object task) {
        Object controller = GoogleReflection.readField(task, "a", CONTROLLER);
        Object range = GoogleReflection.readField(task, "b", "dnqv");
        if (controller == null) return LiveBounds.empty("controller_missing");

        Object state = GoogleReflection.invokeNoArg(controller, "c");
        Object rawView = GoogleReflection.invokeNoArg(controller, "d");
        if (state == null || !STATE.equals(state.getClass().getName())) {
            return LiveBounds.empty("state_missing");
        }
        if (!(rawView instanceof View textView)
                || !TEXT_SELECTION_VIEW.equals(rawView.getClass().getName())
                || textView.getWidth() <= 0
                || textView.getHeight() <= 0) {
            return LiveBounds.empty("text_view_missing");
        }

        Object selected = GoogleReflection.readField(
                state, "c", GoogleLens1758Profile.IMMUTABLE_LIST);
        Rect union = new Rect();
        int words = addWords(union, selected);

        // Fail-soft fallback for the instant where Google's selected-word list is being swapped:
        // endpoints still carry word rectangles and prevent a one-frame jump/disappearance.
        if (words == 0) {
            words += addWord(union, GoogleReflection.readField(state, "a", WORD));
            words += addWord(union, GoogleReflection.readField(state, "b", WORD));
        }
        if (words == 0 || union.isEmpty()) {
            return LiveBounds.empty("selected_words_empty");
        }

        // dnrd rectangles are TextSelectionView-local in 17.58. Convert them to screen space.
        int[] origin = new int[2];
        textView.getLocationOnScreen(origin);
        Rect screen = new Rect(union);
        screen.offset(origin[0], origin[1]);

        // Reject clearly stale/full-document geometry. A text selection may span most of a screen,
        // but a union larger than the live TextSelectionView itself is not a valid local selection.
        Rect viewScreen = new Rect(
                origin[0],
                origin[1],
                origin[0] + textView.getWidth(),
                origin[1] + textView.getHeight());
        if (!screen.intersect(viewScreen) || screen.isEmpty()) {
            return LiveBounds.empty("outside_text_view");
        }

        String rangeText = range == null ? "null" : safe(String.valueOf(range));
        return new LiveBounds(
                screen,
                "source=dtvm selectedWords=" + words
                        + " range=" + trim(rangeText, 260)
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
