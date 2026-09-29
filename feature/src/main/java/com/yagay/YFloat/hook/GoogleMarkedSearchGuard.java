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

/**
 * Last-resort search/result guard for a YFloat-owned Google Circle-to-Search session.
 *
 * <p>This guard intentionally does not depend on the selection adapter. Google may rename the
 * selection callback while keeping the query/result value objects stable. In that situation YFloat
 * must fail closed: never turn a YFloat-marked selection into Google's full search-result panel.
 * Native Home/gesture Circle-to-Search is untouched because the guard is armed only by YFloat's
 * explicit session marker and continuously re-validates the token against Remote Preferences.</p>
 */
final class GoogleMarkedSearchGuard {
    private static final String TAG = "YFloat-GoogleCTS";
    private static final String SHOW_SESSION_ID = "android.service.voice.SHOW_SESSION_ID";
    private static final String LENS_CONTROLLER = GoogleLens1758Profile.CONTROLLER;
    private static final String PENDING_QUERY = GoogleLens1758Profile.PENDING_QUERY;
    private static final String QUERY_RESULT = GoogleLens1758Profile.QUERY_RESULT;

    private final XposedModule module;
    private final LsposedRuntimeProvider provider;
    private final ClassLoader classLoader;
    private volatile String markedToken = "";
    private volatile int showSessionId = -1;

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
                            markedToken = args.getString(GoogleCtsContract.K_SESSION_TOKEN, "");
                            showSessionId = args.getInt(SHOW_SESSION_ID, -1);
                            module.log(Log.INFO, TAG,
                                    "GOOGLE_SEARCH_GUARD_ARM session=" + shortToken(markedToken)
                                            + " showId=" + showSessionId);
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
                    module.hook(method).intercept(chain -> {
                        Object result = chain.proceed();
                        if (!markedToken.isBlank()) clear("voice_session_hide");
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

    private int hookLensQueryPipeline() {
        try {
            Class<?> controller = Class.forName(LENS_CONTROLLER, false, classLoader);
            int count = 0;
            for (Executable executable : HiddenApiBypass.getDeclaredMethods(controller)) {
                if (!(executable instanceof Method method)) continue;
                Class<?>[] p = method.getParameterTypes();
                if (p.length != 1) continue;

                if (PENDING_QUERY.equals(p[0].getName())) {
                    module.hook(method).intercept(chain -> {
                        if (active()) {
                            Object pending = chain.getArg(0);
                            GoogleLens1758Profile.PresentationRequestSuppression suppression =
                                    GoogleLens1758Profile.suppressSelectionPresentationRequest(pending);
                            GoogleLens1758Profile.PendingSnapshot snapshot =
                                    GoogleLens1758Profile.pending(pending);
                            module.log(Log.INFO, TAG,
                                    "GOOGLE_SEARCH_REQUEST_SUPPRESS session="
                                            + shortToken(markedToken)
                                            + " method=" + method.getName()
                                            + " suppressed=" + suppression.suppressed()
                                            + " detail=" + safe(suppression.detail())
                                            + " pending=" + safe(snapshot.detail()));
                        }
                        // Keep the query pipeline alive. We only remove the request for Google's
                        // rendered result panel so native selection/geometry can continue working.
                        return chain.proceed();
                    });
                    count++;
                    continue;
                }

                if (QUERY_RESULT.equals(p[0].getName())
                        && method.getReturnType() == void.class) {
                    module.hook(method).intercept(chain -> {
                        if (!active()) return chain.proceed();

                        Object rawResult = chain.getArg(0);
                        GoogleLens1758Profile.ResultSnapshot snapshot =
                                GoogleLens1758Profile.result(rawResult);
                        GoogleLens1758Profile.NativePresentationSuppression nativeSuppression =
                                GoogleLens1758Profile
                                        .suppressNativeRenderedPresentationFromQueryResult(rawResult);

                        if (!snapshot.presentationPresent()) {
                            // Image/OCR/intermediate result: let Google update the frozen selection
                            // surface. Only the final presentation-result boundary is forbidden.
                            return chain.proceed();
                        }

                        module.log(Log.INFO, TAG,
                                "GOOGLE_SEARCH_SUPPRESS session=" + shortToken(markedToken)
                                        + " method=" + method.getName()
                                        + " reason=presentation_result"
                                        + " complete=" + snapshot.complete()
                                        + " textLen=" + snapshot.text().length()
                                        + " nativeRemoved=" + nativeSuppression.removedCount()
                                        + " nativeSuppressed=" + nativeSuppression.suppressed()
                                        + " detail=" + safe(snapshot.detail()));

                        // Fail closed for a YFloat-owned session. Even when the selection adapter
                        // cannot decode this Google build, never allow the Google results panel to
                        // replace the selection surface.
                        return null;
                    });
                    count++;
                }
            }
            if (count == 0) {
                module.log(Log.WARN, TAG,
                        "Google marked search guard found no Lens query/result methods");
            }
            return count;
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "Google Lens query search guard unavailable", t);
            return 0;
        }
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
                || Intent.ACTION_WEB_SEARCH.equals(action)) {
            return true;
        }
        if (intent.getComponent() == null) return false;
        String pkg = intent.getComponent().getPackageName();
        String cls = intent.getComponent().getClassName();
        if (!GoogleCtsContract.GOOGLE_PACKAGE.equals(pkg) || cls == null) return false;
        String lower = cls.toLowerCase(java.util.Locale.ROOT);
        // Explicit result/search activities only. LensientActivity itself is the selection host and
        // must never be blocked merely because its package/class contains "lens".
        return lower.contains("searchactivity")
                || lower.contains("searchresult")
                || lower.contains("resultactivity");
    }

    private void clear(String reason) {
        String old = markedToken;
        markedToken = "";
        showSessionId = -1;
        module.log(Log.INFO, TAG,
                "GOOGLE_SEARCH_GUARD_CLEAR session=" + shortToken(old)
                        + " reason=" + reason);
    }

    private static int findParameter(Class<?>[] params, Class<?> type) {
        if (params == null || type == null) return -1;
        for (int i = 0; i < params.length; i++) {
            if (type.isAssignableFrom(params[i])) return i;
        }
        return -1;
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
