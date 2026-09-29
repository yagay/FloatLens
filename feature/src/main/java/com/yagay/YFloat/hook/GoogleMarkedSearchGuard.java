package com.yagay.YFloat.hook;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import com.yagay.YFloat.GoogleCtsContract;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/** Last-resort search/result guard for a YFloat-owned Google Circle-to-Search session. */
final class GoogleMarkedSearchGuard {
    private static final String TAG = "YFloat-GoogleCTS";
    private static final String SHOW_SESSION_ID = "android.service.voice.SHOW_SESSION_ID";
    private static final String OMNIENT_HANDLER =
            "com.google.android.apps.search.omnient.host.invocation.OmnientInvocationHandler";

    private final XposedModule module;
    private final LsposedRuntimeProvider provider;
    private final ClassLoader classLoader;
    private volatile String markedToken = "";
    private volatile int showSessionId = -1;

    private static final class Pipeline {
        final String profile;
        final String controller;
        final String pending;
        final String result;
        final boolean v1760;

        Pipeline(String profile, String controller, String pending, String result, boolean v1760) {
            this.profile = profile;
            this.controller = controller;
            this.pending = pending;
            this.result = result;
            this.v1760 = v1760;
        }
    }

    GoogleMarkedSearchGuard(XposedModule module,
                            LsposedRuntimeProvider provider,
                            ClassLoader classLoader) {
        this.module = module;
        this.provider = provider;
        this.classLoader = classLoader;
    }

    int install() {
        int hooks = 0;
        hooks += hookVoiceSessionOwnership();
        hooks += hookOmnientOwnership();
        hooks += hookLensQueryPipeline();
        hooks += hookSearchIntentFallback();
        module.log(Log.INFO, TAG, "Google marked search guard hooks=" + hooks);
        return hooks;
    }

    private int hookVoiceSessionOwnership() {
        try {
            Class<?> cls = Class.forName("android.service.voice.VoiceInteractionSession");
            int count = 0;
            for (Executable executable : HiddenApiBypass.getDeclaredMethods(cls)) {
                if (!(executable instanceof Method method)) continue;
                if ("doShow".equals(method.getName())) {
                    Class<?>[] p = method.getParameterTypes();
                    if (p.length < 2 || p[0] != Bundle.class) continue;
                    module.hook(method).intercept(chain -> {
                        Bundle args = (Bundle) chain.getArg(0);
                        if (provider.isActive() && GoogleCtsContract.isYFloatSession(args)) {
                            arm(args.getString(GoogleCtsContract.K_SESSION_TOKEN, ""),
                                    args.getInt(SHOW_SESSION_ID, -1), "VIS_MARKER");
                        } else if (!markedToken.isBlank()) {
                            int nextId = args == null ? -1 : args.getInt(SHOW_SESSION_ID, -1);
                            if (nextId >= 0 && showSessionId >= 0 && nextId != showSessionId) {
                                clear("new_unmarked_voice_session");
                            }
                        }
                        return chain.proceed();
                    });
                    count++;
                } else if ("doHide".equals(method.getName())
                        && method.getParameterCount() == 0) {
                    // Google may hide the VIS immediately before dispatching the Lens result/search
                    // transition. Do not drop YFloat ownership here; Remote Preferences/token
                    // expiry, a new unmarked session, or the app-side bridge owns final cleanup.
                    module.hook(method).intercept(chain -> {
                        Object result = chain.proceed();
                        if (!markedToken.isBlank()) {
                            module.log(Log.INFO, TAG,
                                    "GOOGLE_SEARCH_GUARD_KEEP session="
                                            + shortToken(markedToken) + " reason=voice_session_hide");
                        }
                        return result;
                    });
                    count++;
                }
            }
            return count;
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "Google search guard VIS hook unavailable", t);
            return 0;
        }
    }

    private int hookOmnientOwnership() {
        try {
            Class<?> cls = Class.forName(OMNIENT_HANDLER, false, classLoader);
            int count = 0;
            for (Executable executable : HiddenApiBypass.getDeclaredMethods(cls)) {
                if (!(executable instanceof Method method)) continue;
                Class<?>[] p = method.getParameterTypes();
                if (p.length != 3) continue;

                if (p[1] == Bundle.class) {
                    module.hook(method).intercept(chain -> {
                        Bundle args = (Bundle) chain.getArg(1);
                        correlate(args, "OMNIENT_VIS");
                        return chain.proceed();
                    });
                    count++;
                } else if (p[1] == Intent.class) {
                    module.hook(method).intercept(chain -> {
                        Intent intent = (Intent) chain.getArg(1);
                        correlate(intent == null ? null : intent.getExtras(),
                                "OMNIENT_CONTEXTUAL");
                        return chain.proceed();
                    });
                    count++;
                }
            }
            return count;
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "Google search guard Omnient hook unavailable", t);
            return 0;
        }
    }

    private void correlate(Bundle extras, String path) {
        if (!provider.isActive()) return;
        if (GoogleCtsContract.isYFloatSession(extras)) {
            arm(extras.getString(GoogleCtsContract.K_SESSION_TOKEN, ""), showSessionId,
                    path + "_MARKER");
            return;
        }
        if (active()) return;
        String token = provider.googleCtsArmedToken(extras);
        if (!token.isBlank()) arm(token, showSessionId, path + "_ARMED_FALLBACK");
    }

    private int hookLensQueryPipeline() {
        Pipeline pipeline = resolvePipeline();
        if (pipeline == null) {
            module.log(Log.WARN, TAG,
                    "Google marked search guard found no supported Lens query pipeline");
            return 0;
        }
        try {
            Class<?> controller = Class.forName(pipeline.controller, false, classLoader);
            int count = 0;
            for (Executable executable : HiddenApiBypass.getDeclaredMethods(controller)) {
                if (!(executable instanceof Method method)) continue;
                Class<?>[] p = method.getParameterTypes();
                if (p.length != 1) continue;

                if (pipeline.pending.equals(p[0].getName())) {
                    module.hook(method).intercept(chain -> {
                        if (active()) {
                            Object pending = chain.getArg(0);
                            if (pipeline.v1760) {
                                module.log(Log.INFO, TAG,
                                        "GOOGLE_SEARCH_REQUEST_GUARD session="
                                                + shortToken(markedToken)
                                                + " profile=" + pipeline.profile
                                                + " method=" + method.getName()
                                                + " pending=" + className(pending));
                            } else {
                                GoogleLens1758Profile.PresentationRequestSuppression suppression =
                                        GoogleLens1758Profile
                                                .suppressSelectionPresentationRequest(pending);
                                GoogleLens1758Profile.PendingSnapshot snapshot =
                                        GoogleLens1758Profile.pending(pending);
                                module.log(Log.INFO, TAG,
                                        "GOOGLE_SEARCH_REQUEST_SUPPRESS session="
                                                + shortToken(markedToken)
                                                + " profile=" + pipeline.profile
                                                + " method=" + method.getName()
                                                + " suppressed=" + suppression.suppressed()
                                                + " detail=" + safe(suppression.detail())
                                                + " pending=" + safe(snapshot.detail()));
                            }
                        }
                        return chain.proceed();
                    });
                    count++;
                    continue;
                }

                if (pipeline.result.equals(p[0].getName())
                        && method.getReturnType() == void.class) {
                    module.hook(method).intercept(chain -> {
                        if (!active()) return chain.proceed();
                        Object rawResult = chain.getArg(0);

                        if (pipeline.v1760) {
                            GoogleLens1760Profile.ResultSnapshot snapshot =
                                    GoogleLens1760Profile.result(rawResult);
                            if (!snapshot.presentationPresent()) return chain.proceed();
                            module.log(Log.INFO, TAG,
                                    "GOOGLE_SEARCH_SUPPRESS session=" + shortToken(markedToken)
                                            + " profile=" + pipeline.profile
                                            + " method=" + method.getName()
                                            + " reason=presentation_result"
                                            + " complete=" + snapshot.complete()
                                            + " detail=" + safe(snapshot.detail()));
                            return null;
                        }

                        GoogleLens1758Profile.ResultSnapshot snapshot =
                                GoogleLens1758Profile.result(rawResult);
                        GoogleLens1758Profile.NativePresentationSuppression nativeSuppression =
                                GoogleLens1758Profile
                                        .suppressNativeRenderedPresentationFromQueryResult(rawResult);
                        if (!snapshot.presentationPresent()) return chain.proceed();
                        module.log(Log.INFO, TAG,
                                "GOOGLE_SEARCH_SUPPRESS session=" + shortToken(markedToken)
                                        + " profile=" + pipeline.profile
                                        + " method=" + method.getName()
                                        + " reason=presentation_result"
                                        + " complete=" + snapshot.complete()
                                        + " textLen=" + snapshot.text().length()
                                        + " nativeRemoved=" + nativeSuppression.removedCount()
                                        + " nativeSuppressed=" + nativeSuppression.suppressed()
                                        + " detail=" + safe(snapshot.detail()));
                        return null;
                    });
                    count++;
                }
            }
            module.log(Log.INFO, TAG,
                    "Google marked search pipeline profile=" + pipeline.profile
                            + " controller=" + pipeline.controller + " hooks=" + count);
            return count;
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "Google Lens query search guard unavailable", t);
            return 0;
        }
    }

    private Pipeline resolvePipeline() {
        if (hasClass(GoogleLens1760Profile.CONTROLLER)
                && hasClass(GoogleLens1760Profile.PENDING_QUERY)
                && hasClass(GoogleLens1760Profile.QUERY_RESULT)) {
            return new Pipeline(GoogleLens1760Profile.NAME,
                    GoogleLens1760Profile.CONTROLLER,
                    GoogleLens1760Profile.PENDING_QUERY,
                    GoogleLens1760Profile.QUERY_RESULT, true);
        }
        if (hasClass(GoogleLens1758Profile.CONTROLLER)
                && hasClass(GoogleLens1758Profile.PENDING_QUERY)
                && hasClass(GoogleLens1758Profile.QUERY_RESULT)) {
            return new Pipeline(GoogleLens1758Profile.NAME,
                    GoogleLens1758Profile.CONTROLLER,
                    GoogleLens1758Profile.PENDING_QUERY,
                    GoogleLens1758Profile.QUERY_RESULT, false);
        }
        return null;
    }

    private boolean hasClass(String name) {
        try {
            Class.forName(name, false, classLoader);
            return true;
        } catch (Throwable ignored) { return false; }
    }

    private int hookSearchIntentFallback() {
        try {
            int count = 0;
            for (Executable executable : HiddenApiBypass.getDeclaredMethods(Instrumentation.class)) {
                if (!(executable instanceof Method method)
                        || !"execStartActivity".equals(method.getName())) continue;
                int intentIndex = findParameter(method.getParameterTypes(), Intent.class);
                if (intentIndex < 0) continue;
                final int idx = intentIndex;
                module.hook(method).intercept(chain -> {
                    if (!active()) return chain.proceed();
                    Intent intent = (Intent) chain.getArg(idx);
                    if (!isSearchNavigation(intent)) return chain.proceed();
                    module.log(Log.INFO, TAG,
                            "GOOGLE_SEARCH_SUPPRESS session=" + shortToken(markedToken)
                                    + " reason=intent action="
                                    + (intent == null ? "null" : intent.getAction())
                                    + " component="
                                    + (intent == null ? "null" : String.valueOf(intent.getComponent())));
                    return null;
                });
                count++;
            }
            return count;
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "Google search intent fallback unavailable", t);
            return 0;
        }
    }

    private void arm(String token, int id, String path) {
        if (token == null || token.isBlank()) return;
        markedToken = token;
        if (id >= 0) showSessionId = id;
        module.log(Log.INFO, TAG,
                "GOOGLE_SEARCH_GUARD_ARM session=" + shortToken(token)
                        + " showId=" + showSessionId + " path=" + path);
    }

    private boolean active() {
        String token = markedToken;
        return provider.isActive() && token != null && !token.isBlank()
                && provider.ownsGoogleCtsSession(token);
    }

    private static boolean isSearchNavigation(Intent intent) {
        if (intent == null) return false;
        String action = intent.getAction();
        if (GoogleCtsContract.isContextualSearchAction(action)
                || Intent.ACTION_SEARCH.equals(action)
                || Intent.ACTION_WEB_SEARCH.equals(action)) return true;
        if (intent.getComponent() == null) return false;
        String pkg = intent.getComponent().getPackageName();
        String cls = intent.getComponent().getClassName();
        if (!GoogleCtsContract.GOOGLE_PACKAGE.equals(pkg) || cls == null) return false;
        String lower = cls.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("searchactivity")
                || lower.contains("searchresult")
                || lower.contains("resultactivity");
    }

    private void clear(String reason) {
        String old = markedToken;
        markedToken = "";
        showSessionId = -1;
        module.log(Log.INFO, TAG,
                "GOOGLE_SEARCH_GUARD_CLEAR session=" + shortToken(old) + " reason=" + reason);
    }

    private static int findParameter(Class<?>[] params, Class<?> type) {
        if (params == null || type == null) return -1;
        for (int i = 0; i < params.length; i++) {
            if (type.isAssignableFrom(params[i])) return i;
        }
        return -1;
    }

    private static String className(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    private static String shortToken(String token) {
        if (token == null || token.isBlank()) return "none";
        return token.substring(0, Math.min(8, token.length()));
    }

    private static String safe(String value) {
        if (value == null) return "";
        String out = value.replace('\n', ' ').replace('\r', ' ');
        return out.length() <= 1200 ? out : out.substring(0, 1200) + "…";
    }
}
