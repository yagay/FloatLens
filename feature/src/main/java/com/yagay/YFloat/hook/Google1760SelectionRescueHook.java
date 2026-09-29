package com.yagay.YFloat.hook;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Rect;
import android.util.Log;
import android.view.View;

import com.yagay.YFloat.GoogleCtsContract;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * Compatibility rescue for Google App 17.60 Circle-to-Search.
 *
 * <p>Device diagnostics show dsxb.y(dsws, boolean) can fire before the concrete dufy selection
 * object exposes text/bounds, while doms.g/dupt.run already contain the selected word list and
 * correct geometry a few milliseconds later. This hook therefore publishes only non-empty text
 * from the post-callback/live-word state and emits a terminal END when LensientActivity is really
 * destroyed, without treating transient Launcher/Recents windows as session end.</p>
 */
final class Google1760SelectionRescueHook {
    private static final String TAG = "YFloat-GoogleCTS";
    private static final String LENS_ACTIVITY =
            "com.google.android.apps.search.lens.LensientActivity";

    private final XposedModule module;
    private final LsposedRuntimeProvider provider;
    private final ClassLoader classLoader;
    private final GoogleBridgeSender bridgeSender;

    private volatile Context lastContext;
    private String lastSelectionSignature = "";
    private String lastSelectionToken = "";

    Google1760SelectionRescueHook(
            XposedModule module,
            LsposedRuntimeProvider provider,
            ClassLoader classLoader) {
        this.module = module;
        this.provider = provider;
        this.classLoader = classLoader;
        this.bridgeSender = new GoogleBridgeSender(
                module,
                this::currentContext,
                this::currentToken,
                this::active,
                provider::diagnosticsEnabled);
    }

    int install() {
        if (!hasClass(GoogleLens1760Profile.CONTROLLER)
                || !hasClass(GoogleLens1760Profile.TEXT_SELECTION_STATE)) {
            return 0;
        }
        int count = 0;
        count += installPostSelectionHook();
        count += installLiveWordHook();
        count += installFinalTaskHook();
        count += installLensDestroyHook();
        module.log(Log.INFO, TAG,
                "Google 17.60 selection rescue hooks=" + count
                        + " sources=dsxb.y,doms.g,dupt.run,LensientActivity.destroy");
        return count;
    }

    private int installPostSelectionHook() {
        try {
            Class<?> controller = Class.forName(
                    GoogleLens1760Profile.CONTROLLER, false, classLoader);
            Method target = null;
            for (Method method : GoogleReflection.declaredMethods(controller)) {
                if (GoogleLens1760Profile.isSelectionMethod(method)) {
                    target = method;
                    break;
                }
            }
            if (target == null) return 0;

            module.hook(target).intercept(chain -> {
                Object metadata = chain.getArg(0);
                Object result = chain.proceed();
                if (!active() || metadata == null) return result;
                try {
                    GoogleLens1760Profile.SelectionSnapshot selection =
                            GoogleLens1760Profile.selection(metadata, currentContext());
                    publishSelection(
                            selection.text(),
                            selection.bounds(),
                            "post_dsxb_y " + selection.detail());
                } catch (Throwable error) {
                    module.log(Log.WARN, TAG,
                            "Google 17.60 post-selection rescue failed", error);
                }
                return result;
            });
            return 1;
        } catch (Throwable error) {
            module.log(Log.WARN, TAG,
                    "Google 17.60 post-selection hook unavailable", error);
            return 0;
        }
    }

    private int installLiveWordHook() {
        try {
            Class<?> stateClass = Class.forName(
                    GoogleLens1760Profile.TEXT_SELECTION_STATE, false, classLoader);
            Method target = null;
            for (Method method : GoogleReflection.declaredMethods(stateClass)) {
                if (!"g".equals(method.getName())
                        || method.getParameterCount() != 3
                        || method.getReturnType() != void.class) continue;
                Class<?>[] p = method.getParameterTypes();
                if (GoogleLens1760Profile.IMMUTABLE_LIST.equals(p[0].getName())
                        && p[1] == boolean.class && p[2] == int.class) {
                    target = method;
                    break;
                }
            }
            if (target == null) return 0;

            module.hook(target).intercept(chain -> {
                Object state = chain.getThisObject();
                Object selected = chain.getArg(0);
                Object result = chain.proceed();
                if (!active() || state == null) return result;
                try {
                    LiveSelection live = readLiveSelection(state, selected, "doms.g");
                    publishSelection(live.text, live.bounds, live.detail);
                } catch (Throwable error) {
                    module.log(Log.WARN, TAG,
                            "Google 17.60 live-word rescue failed", error);
                }
                return result;
            });
            return 1;
        } catch (Throwable error) {
            module.log(Log.WARN, TAG,
                    "Google 17.60 live-word hook unavailable", error);
            return 0;
        }
    }

    private int installFinalTaskHook() {
        try {
            Class<?> taskClass = Class.forName(
                    GoogleLens1760Profile.TEXT_SELECTION_UPDATE_TASK, false, classLoader);
            Method target = null;
            for (Method method : GoogleReflection.declaredMethods(taskClass)) {
                if ("run".equals(method.getName())
                        && method.getParameterCount() == 0
                        && method.getReturnType() == void.class) {
                    target = method;
                    break;
                }
            }
            if (target == null) return 0;

            module.hook(target).intercept(chain -> {
                Object task = chain.getThisObject();
                Object result = chain.proceed();
                if (!active() || task == null) return result;
                try {
                    Object controller = GoogleReflection.readField(
                            task, "a", GoogleLens1760Profile.TEXT_SELECTION_CONTROLLER);
                    Object state = GoogleReflection.invokeNoArg(controller, "c");
                    if (state == null) return result;
                    LiveSelection live = readLiveSelection(state, null, "dupt.run");
                    publishSelection(live.text, live.bounds, live.detail);
                } catch (Throwable error) {
                    module.log(Log.WARN, TAG,
                            "Google 17.60 final-task rescue failed", error);
                }
                return result;
            });
            return 1;
        } catch (Throwable error) {
            module.log(Log.WARN, TAG,
                    "Google 17.60 final-task hook unavailable", error);
            return 0;
        }
    }

    private int installLensDestroyHook() {
        try {
            Method target = Instrumentation.class.getDeclaredMethod(
                    "callActivityOnDestroy", Activity.class);
            module.hook(target).intercept(chain -> {
                Activity activity = (Activity) chain.getArg(0);
                boolean lens = activity != null
                        && LENS_ACTIVITY.equals(activity.getClass().getName());
                boolean changing = activity != null && activity.isChangingConfigurations();
                if (activity != null) rememberContext(activity);
                Object result = chain.proceed();
                if (!lens || changing || !active()) return result;

                String token = currentToken();
                if (!token.isBlank()) {
                    boolean sent = bridgeSender.sendEvent(
                            GoogleCtsContract.EVENT_END,
                            "",
                            "lensient_activity_destroyed",
                            null);
                    module.log(Log.INFO, TAG,
                            "Google CTS terminal rescue session=" + shortToken(token)
                                    + " event=LensientActivity.destroy sent=" + sent);
                    synchronized (this) {
                        lastSelectionSignature = "";
                        lastSelectionToken = "";
                    }
                }
                return result;
            });
            return 1;
        } catch (Throwable error) {
            module.log(Log.WARN, TAG,
                    "Google Lens destroy rescue hook unavailable", error);
            return 0;
        }
    }

    private LiveSelection readLiveSelection(
            Object state,
            Object selectedOverride,
            String source) {
        if (state == null
                || !GoogleLens1760Profile.TEXT_SELECTION_STATE.equals(
                state.getClass().getName())) {
            return LiveSelection.empty(source + " state_missing");
        }

        Object rawView = GoogleReflection.readField(
                state, "g", GoogleLens1760Profile.TEXT_SELECTION_VIEW);
        if (!(rawView instanceof View textView)
                || textView.getWidth() <= 0 || textView.getHeight() <= 0) {
            return LiveSelection.empty(source + " text_view_missing");
        }
        rememberContext(textView.getContext());

        Object selected = selectedOverride;
        if (!(selected instanceof Iterable<?>)) {
            selected = GoogleReflection.readField(
                    state, "c", GoogleLens1760Profile.IMMUTABLE_LIST);
        }

        Rect union = new Rect();
        StringBuilder text = new StringBuilder();
        int words = addWords(union, text, selected);
        if (words == 0) {
            words += addWord(union, text, GoogleReflection.readField(
                    state, "a", GoogleLens1760Profile.WORD));
            words += addWord(union, text, GoogleReflection.readField(
                    state, "b", GoogleLens1760Profile.WORD));
        }
        if (words == 0 || union.isEmpty() || text.toString().trim().isEmpty()) {
            return LiveSelection.empty(source + " selected_words_empty");
        }

        int[] origin = new int[2];
        textView.getLocationOnScreen(origin);
        Rect screen = new Rect(union);
        screen.offset(origin[0], origin[1]);
        Rect viewScreen = new Rect(
                origin[0], origin[1],
                origin[0] + textView.getWidth(),
                origin[1] + textView.getHeight());
        if (!screen.intersect(viewScreen) || screen.isEmpty()) {
            return LiveSelection.empty(source + " outside_text_view");
        }

        String value = normalize(text.toString());
        return new LiveSelection(
                value,
                screen,
                "profile=" + GoogleLens1760Profile.NAME
                        + " source=" + source
                        + " selectedWords=" + words
                        + " textLen=" + value.length()
                        + " local=" + union
                        + " screen=" + screen);
    }

    private int addWords(Rect union, StringBuilder text, Object value) {
        if (!(value instanceof Iterable<?> items)) return 0;
        int count = 0;
        for (Object item : items) count += addWord(union, text, item);
        return count;
    }

    private int addWord(Rect union, StringBuilder text, Object word) {
        if (word == null
                || !GoogleLens1760Profile.WORD.equals(word.getClass().getName())) return 0;
        Object rawRect = GoogleReflection.readField(word, "d", Rect.class.getName());
        if (!(rawRect instanceof Rect rect) || rect.isEmpty()) return 0;
        String token = readWordText(word);
        if (token.isBlank()) return 0;
        if (union.isEmpty()) union.set(rect); else union.union(rect);
        appendToken(text, token);
        return 1;
    }

    private String readWordText(Object word) {
        // 17.60 domd keeps multiple String forms. Field a is the primary OCR token on this build;
        // the remaining names are safe fallbacks for minor obfuscation/model variations.
        for (String name : new String[]{"a", "b", "c", "k"}) {
            Object value = GoogleReflection.readField(word, name, String.class.getName());
            if (value instanceof String text) {
                String normalized = normalize(text);
                if (!normalized.isBlank()) return normalized;
            }
        }
        // Last-resort structural fallback: first non-empty String instance field.
        for (Field field : GoogleReflection.instanceFields(word.getClass())) {
            if (field.getType() != String.class) continue;
            try {
                field.setAccessible(true);
                Object value = field.get(word);
                if (value instanceof String text) {
                    String normalized = normalize(text);
                    if (!normalized.isBlank()) return normalized;
                }
            } catch (Throwable ignored) { }
        }
        return "";
    }

    private synchronized void publishSelection(String text, Rect bounds, String detail) {
        String value = normalize(text);
        if (value.isBlank()) return;
        String token = currentToken();
        if (token.isBlank()) return;

        Rect safeBounds = bounds == null || bounds.isEmpty() ? null : new Rect(bounds);
        String signature = value + "|" + String.valueOf(safeBounds);
        if (token.equals(lastSelectionToken) && signature.equals(lastSelectionSignature)) return;

        boolean sent = bridgeSender.sendEvent(
                GoogleCtsContract.EVENT_SELECTION,
                value,
                detail,
                safeBounds);
        if (sent) {
            lastSelectionToken = token;
            lastSelectionSignature = signature;
            module.log(Log.INFO, TAG,
                    "Google 17.60 selection rescue session=" + shortToken(token)
                            + " textLen=" + value.length()
                            + " bounds=" + safeBounds
                            + " source=" + sourceOf(detail));
        }
    }

    private boolean active() {
        return provider != null && provider.isActive() && !currentToken().isBlank();
    }

    private String currentToken() {
        if (provider == null) return "";
        String token = provider.googleCtsArmedToken();
        return token == null ? "" : token;
    }

    private Context currentContext() {
        Context context = lastContext;
        if (context != null) return context;
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            currentApplication.setAccessible(true);
            Object value = currentApplication.invoke(null);
            if (value instanceof Application app) {
                rememberContext(app);
                return lastContext;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private void rememberContext(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        lastContext = app == null ? context : app;
    }

    private boolean hasClass(String name) {
        try {
            Class.forName(name, false, classLoader);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void appendToken(StringBuilder out, String token) {
        String value = normalize(token);
        if (value.isBlank()) return;
        if (out.length() > 0 && needsSpace(out.charAt(out.length() - 1), value.charAt(0))) {
            out.append(' ');
        }
        out.append(value);
    }

    private static boolean needsSpace(char left, char right) {
        return isLatinOrDigit(left) && isLatinOrDigit(right);
    }

    private static boolean isLatinOrDigit(char value) {
        return Character.isDigit(value)
                || (value >= 'A' && value <= 'Z')
                || (value >= 'a' && value <= 'z');
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.replace('\n', ' ').replace('\r', ' ').trim().replaceAll("\\s+", " ");
    }

    private static String sourceOf(String detail) {
        if (detail == null) return "unknown";
        int marker = detail.indexOf("source=");
        if (marker < 0) return detail.startsWith("post_dsxb_y") ? "post_dsxb_y" : "unknown";
        int end = detail.indexOf(' ', marker + 7);
        return end < 0 ? detail.substring(marker + 7) : detail.substring(marker + 7, end);
    }

    private static String shortToken(String token) {
        if (token == null || token.isBlank()) return "none";
        return token.substring(0, Math.min(8, token.length()));
    }

    private static final class LiveSelection {
        final String text;
        final Rect bounds;
        final String detail;

        LiveSelection(String text, Rect bounds, String detail) {
            this.text = text == null ? "" : text;
            this.bounds = bounds == null ? null : new Rect(bounds);
            this.detail = detail == null ? "" : detail;
        }

        static LiveSelection empty(String detail) {
            return new LiveSelection("", null, detail);
        }
    }
}
