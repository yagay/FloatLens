package com.yagay.floatlens.hook;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import io.github.libxposed.api.XposedModule;

/** Diagnostic-only Google Lens window/view inspection, isolated from core selection hooks. */
final class GoogleLensDiagnosticsHooks {
    private static final String TAG = "FloatLens-GoogleCTS";

    private final XposedModule module;
    private final ClassLoader classLoader;
    private final BooleanSupplier enabled;
    private final BooleanSupplier active;
    private final BooleanSupplier selectionSeen;
    private final Supplier<Activity> activity;
    private final Supplier<Rect> selectionBounds;
    private final BiConsumer<String, String> reporter;
    private final Handler main = new Handler(Looper.getMainLooper());

    GoogleLensDiagnosticsHooks(XposedModule module,
                               ClassLoader classLoader,
                               BooleanSupplier enabled,
                               BooleanSupplier active,
                               BooleanSupplier selectionSeen,
                               Supplier<Activity> activity,
                               Supplier<Rect> selectionBounds,
                               BiConsumer<String, String> reporter) {
        this.module = module;
        this.classLoader = classLoader;
        this.enabled = enabled;
        this.active = active;
        this.selectionSeen = selectionSeen;
        this.activity = activity;
        this.selectionBounds = selectionBounds;
        this.reporter = reporter;
    }

    int installWindowInspector() {
        if (!enabled.getAsBoolean()) return 0;
        try {
            Class<?> global = Class.forName("android.view.WindowManagerGlobal", false, classLoader);
            int count = 0;
            for (Executable executable : HiddenApiBypass.getDeclaredMethods(global)) {
                if (!(executable instanceof Method method)) continue;
                if (!"addView".equals(method.getName())) continue;
                Class<?>[] params = method.getParameterTypes();
                int viewIndex = findParameter(params, View.class);
                int lpIndex = findParameter(params, ViewGroup.LayoutParams.class);
                if (viewIndex < 0) continue;
                final int vIdx = viewIndex;
                final int lIdx = lpIndex;

                module.hook(method).intercept(chain -> {
                    Object result = chain.proceed();
                    if (!active.getAsBoolean() || !selectionSeen.getAsBoolean()) return result;
                    Object rawView = chain.getArg(vIdx);
                    Object rawLp = lIdx >= 0 ? chain.getArg(lIdx) : null;
                    if (rawView instanceof View view) {
                        reporter.accept("GOOGLE_WINDOW_ADD",
                                GoogleLensViewIntrospection.describeView(view, true)
                                        + " lp="
                                        + GoogleLensViewIntrospection.describeWindowLayoutParams(rawLp));
                    }
                    return result;
                });
                count++;
            }
            module.log(Log.INFO, TAG, "Google window inspector hooks=" + count);
            return count;
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "Google window inspector unavailable", t);
            return 0;
        }
    }

    void inspectSelectionViewsSoon() {
        if (!enabled.getAsBoolean()) return;
        Activity owner = activity.get();
        if (owner == null) return;
        final long[] delays = {0L, 80L, 220L, 500L};
        for (long delay : delays) {
            Runnable scan = () -> {
                if (!active.getAsBoolean() || !selectionSeen.getAsBoolean()) return;
                try {
                    View root = owner.getWindow() == null
                            ? null : owner.getWindow().getDecorView();
                    if (root == null) return;
                    GoogleLensViewIntrospection.Dump dump =
                            GoogleLensViewIntrospection.dumpInteresting(root);
                    reporter.accept("GOOGLE_VIEW_SNAPSHOT",
                            "delayMs=" + delay
                                    + " activity=" + owner.getClass().getName()
                                    + " selectedBounds=" + String.valueOf(selectionBounds.get())
                                    + " nodes=" + dump.nodes
                                    + "\n" + dump.text);
                } catch (Throwable t) {
                    module.log(Log.WARN, TAG, "Google view snapshot failed", t);
                }
            };
            if (delay == 0L) owner.runOnUiThread(scan);
            else main.postDelayed(() -> owner.runOnUiThread(scan), delay);
        }
    }

    private static int findParameter(Class<?>[] params, Class<?> type) {
        if (params == null || type == null) return -1;
        for (int i = 0; i < params.length; i++) {
            if (type.isAssignableFrom(params[i])) return i;
        }
        return -1;
    }
}
