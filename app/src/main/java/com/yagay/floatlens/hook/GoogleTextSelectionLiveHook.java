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
    private static final String HIGHLIGHT = "dnrf";
    private static final String HIGHLIGHT_GEOMETRY = "dnsa";
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

            int hooks = 0;
            module.hook(run).intercept(chain -> {
                Object task = chain.getThisObject();
                Object result = chain.proceed();
                if (!active.getAsBoolean() || task == null) return result;

                try {
                    LiveBounds live = readLiveBounds(task);
                    emit(live);
                } catch (Throwable t) {
                    module.log(Log.WARN, TAG,
                            "Google live text selection bounds read failed", t);
                }
                return result;
            });
            hooks++;

            // dnrs.g(ImmutableList, boolean, int) is the actual live selected-highlight update.
            // Its first argument contains dnrf items whose dnsa.a Rect changes while handles move.
            Class<?> stateClass = Class.forName(STATE, false, classLoader);
            for (Method method : GoogleReflection.declaredMethods(stateClass)) {
                if (!"g".equals(method.getName())
                        || method.getParameterCount() != 3
                        || method.getReturnType() != void.class) {
                    continue;
                }
                Class<?>[] p = method.getParameterTypes();
                if (!GoogleLens1758Profile.IMMUTABLE_LIST.equals(p[0].getName())
                        || p[1] != boolean.class
                        || p[2] != int.class) {
                    continue;
                }
                module.hook(method).intercept(chain -> {
                    Object state = chain.getThisObject();
                    Object selected = chain.getArg(0);
                    Object result = chain.proceed();
                    if (!active.getAsBoolean() || state == null) return result;
                    try {
                        emit(readStateBounds(state, selected, "dnrs.g"));
                    } catch (Throwable t) {
                        module.log(Log.WARN, TAG,
                                "Google live highlight bounds read failed", t);
                    }
                    return result;
                });
                hooks++;
                break;
            }

            module.log(Log.INFO, TAG,
                    "Google live text selection hooks=" + hooks
                            + " sources=dnrs.g,dtvm.run");
            return hooks;
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
        LiveBounds live = readStateBounds(state, selected, "dtvm");
        if (live.bounds == null) return live;

        String rangeText = range == null ? "null" : safe(String.valueOf(range));
        return new LiveBounds(
                live.bounds,
                live.detail + " range=" + trim(rangeText, 260));
    }

    private void emit(LiveBounds live) {
        if (live != null && live.bounds != null && !live.bounds.isEmpty()) {
            sink.accept(live.bounds, live.detail);
        }
    }

    private LiveBounds readStateBounds(Object state, Object selected, String source) {
        if (state == null) return LiveBounds.empty(source + " state_missing");

        Object rawView = GoogleReflection.readField(state, "g", TEXT_SELECTION_VIEW);
        if (!(rawView instanceof View textView)
                || textView.getWidth() <= 0
                || textView.getHeight() <= 0) {
            return LiveBounds.empty(source + " text_view_missing");
        }

        Rect union = new Rect();
        int highlights = addSelectionItems(union, selected);

        // During a handoff between highlight lists, keep the moving endpoint geometry as a
        // one-frame fallback. Normally dnrf -> dnsa.a is the authoritative live path.
        if (highlights == 0) {
            highlights += addSelectionItem(union, GoogleReflection.readField(state, "a", WORD));
            highlights += addSelectionItem(union, GoogleReflection.readField(state, "b", WORD));
        }
        if (highlights == 0 || union.isEmpty()) {
            return LiveBounds.empty(source + " selected_items_empty");
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
                        + " selectedItems=" + highlights
                        + " local=" + union
                        + " screen=" + screen);
    }

    private static int addSelectionItems(Rect union, Object value) {
        if (!(value instanceof Iterable<?> items)) return 0;
        int count = 0;
        for (Object item : items) count += addSelectionItem(union, item);
        return count;
    }

    private static int addSelectionItem(Rect union, Object item) {
        if (item == null) return 0;
        Rect rect = null;
        String type = item.getClass().getName();

        if (WORD.equals(type)) {
            Object value = GoogleReflection.readField(item, "d", Rect.class.getName());
            if (value instanceof Rect r) rect = r;
        } else if (HIGHLIGHT.equals(type)) {
            Object geometry = GoogleReflection.readField(item, "a", HIGHLIGHT_GEOMETRY);
            Object value = GoogleReflection.readField(
                    geometry, "a", Rect.class.getName());
            if (value instanceof Rect r) rect = r;
        }

        if (rect == null || rect.isEmpty()) return 0;
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
