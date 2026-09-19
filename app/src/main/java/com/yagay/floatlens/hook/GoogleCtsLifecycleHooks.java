package com.yagay.floatlens.hook;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Log;

import com.yagay.floatlens.GoogleCtsContract;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/** VoiceInteraction/Activity lifecycle hooks for one marked FloatLens Google CTS session. */
final class GoogleCtsLifecycleHooks {
    private static final String TAG = "FloatLens-GoogleCTS";
    private static final String SHOW_SESSION_ID = "android.service.voice.SHOW_SESSION_ID";

    interface Host {
        boolean active();
        int showSessionId();
        Object voiceSession();
        boolean selectionSeen();
        String selectionText();
        Rect selectionBounds();
        boolean frameQueued();

        void activate(String token, int id, Object session, String path);
        void clear(String reason);
        void rememberMarkedActivity(Object value);
        void finishActivity(Activity activity, String reason);
        boolean commitBridgeResult(String text, String detail, String reason);
        void report(String event, String message);
        void sendBridgeFrame(Bitmap bitmap);
    }

    private final XposedModule module;
    private final LsposedRuntimeProvider provider;
    private final Host host;

    GoogleCtsLifecycleHooks(XposedModule module,
                            LsposedRuntimeProvider provider,
                            Host host) {
        this.module = module;
        this.provider = provider;
        this.host = host;
    }

    int install() {
        int count = 0;
        count += hookVoiceSessionShow();
        count += hookVoiceScreenshot();
        count += hookActivityLifecycle();
        count += hookActivityDispatch();
        return count;
    }

    private int hookVoiceSessionShow() {
        try {
            Class<?> cls = Class.forName("android.service.voice.VoiceInteractionSession");
            int count = 0;
            for (Executable executable : HiddenApiBypass.getDeclaredMethods(cls)) {
                if (!(executable instanceof Method method)) continue;
                if (!"doShow".equals(method.getName())) continue;
                Class<?>[] p = method.getParameterTypes();
                if (p.length < 2 || p[0] != Bundle.class) continue;
                module.hook(method).intercept(chain -> {
                    Bundle args = (Bundle) chain.getArg(0);
                    int id = args == null ? -1 : args.getInt(SHOW_SESSION_ID, -1);
                    if (provider.isActive() && GoogleCtsContract.isFloatLensSession(args)) {
                        host.activate(args.getString(GoogleCtsContract.K_SESSION_TOKEN, ""),
                                id, chain.getThisObject(), "VIS_MARKER");
                        host.report("SESSION_SHOW", "path=VIS_MARKER sessionClass="
                                + chain.getThisObject().getClass().getName()
                                + " flags=" + chain.getArg(1)
                                + " keys=" + GoogleHookFormatting.safeKeys(args));
                        dumpClassStructure(chain.getThisObject().getClass(), "voiceSession");
                    } else if (provider.isActive() && !host.active()) {
                        String armedToken = provider.googleCtsArmedToken(args);
                        if (!armedToken.isBlank()) {
                            host.activate(armedToken, id, chain.getThisObject(), "VIS_ARMED_FALLBACK");
                            host.report("SESSION_SHOW",
                                    "path=VIS_ARMED_FALLBACK marker=false sessionClass="
                                            + chain.getThisObject().getClass().getName()
                                            + " flags=" + chain.getArg(1)
                                            + " keys=" + GoogleHookFormatting.safeKeys(args));
                            dumpClassStructure(chain.getThisObject().getClass(),
                                    "voiceSessionFallback");
                        }
                    } else if (host.active() && id >= 0 && id != host.showSessionId()) {
                        host.clear("new_unmarked_voice_session id=" + id);
                    }
                    return chain.proceed();
                });
                count++;
            }

            for (Executable executable : HiddenApiBypass.getDeclaredMethods(cls)) {
                if (!(executable instanceof Method method)) continue;
                if (!"doHide".equals(method.getName()) || method.getParameterCount() != 0) continue;
                module.hook(method).intercept(chain -> {
                    Object self = chain.getThisObject();
                    Object result = chain.proceed();
                    if (host.active() && self == host.voiceSession()) {
                        host.clear("voice_session_hide");
                    }
                    return result;
                });
                count++;
            }
            return count;
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "VIS session hooks unavailable", t);
            return 0;
        }
    }

    private int hookVoiceScreenshot() {
        try {
            Class<?> cls = Class.forName("android.service.voice.VoiceInteractionSession$MyCallbacks");
            int count = 0;
            for (Executable executable : HiddenApiBypass.getDeclaredMethods(cls)) {
                if (!(executable instanceof Method method)
                        || !"handleScreenshot".equals(method.getName())) continue;
                Class<?>[] p = method.getParameterTypes();
                if (p.length != 1 || p[0] != Bitmap.class) continue;
                module.hook(method).intercept(chain -> {
                    if (host.active()) {
                        Object owner = findVoiceSessionOwner(chain.getThisObject());
                        if (host.voiceSession() == null || owner == host.voiceSession()) {
                            Bitmap bitmap = (Bitmap) chain.getArg(0);
                            host.report("SCREENSHOT", bitmap == null ? "bitmap=null"
                                    : "bitmap=" + bitmap.getWidth() + "x" + bitmap.getHeight()
                                    + " config=" + bitmap.getConfig());
                            host.sendBridgeFrame(bitmap);
                        }
                    }
                    return chain.proceed();
                });
                count++;
            }
            return count;
        } catch (Throwable t) {
            module.log(Log.INFO, TAG, "VIS screenshot callback unavailable", t);
            return 0;
        }
    }

    private int hookActivityLifecycle() {
        int count = 0;
        try {
            for (Executable executable : HiddenApiBypass.getDeclaredMethods(Instrumentation.class)) {
                if (!(executable instanceof Method method)) continue;
                String name = method.getName();
                if (!"callActivityOnCreate".equals(name)
                        && !"callActivityOnNewIntent".equals(name)) continue;

                module.hook(method).intercept(chain -> {
                    Object activity = chain.getArg(0);
                    Intent intent = activity instanceof Activity a ? a.getIntent() : null;
                    Bundle extras = intent == null ? null : intent.getExtras();

                    if (provider.isActive() && GoogleCtsContract.isFloatLensSession(extras)) {
                        String token = extras.getString(GoogleCtsContract.K_SESSION_TOKEN, "");
                        host.activate(token, -1, null, "CONTEXTUAL_ACTIVITY_MARKER");
                        host.report("SESSION_SHOW", "path=ContextualActivity activity="
                                + (activity == null ? "null" : activity.getClass().getName())
                                + " intent=" + GoogleHookFormatting.describeIntent(intent));
                        if (activity != null) dumpClassStructure(activity.getClass(), "activity");
                    } else if (provider.isActive() && !host.active() && intent != null) {
                        String armedToken = provider.googleCtsArmedToken(extras);
                        if (!armedToken.isBlank()) {
                            host.activate(armedToken, -1, null,
                                    "CONTEXTUAL_ACTIVITY_ARMED_FALLBACK");
                            host.report("SESSION_SHOW",
                                    "path=ContextualActivityArmedFallback marker=false activity="
                                            + (activity == null ? "null"
                                            : activity.getClass().getName())
                                            + " intent="
                                            + GoogleHookFormatting.describeIntent(intent));
                            if (activity != null) {
                                dumpClassStructure(activity.getClass(), "activityFallback");
                            }
                        }
                    } else if (host.active() && intent != null) {
                        host.report("ACTIVITY_LIFECYCLE", name
                                + " activityClass="
                                + (activity == null ? "null" : activity.getClass().getName())
                                + " " + GoogleHookFormatting.describeIntent(intent));
                    }

                    boolean contextualBoundary = host.active() && intent != null
                            && GoogleCtsContract.isContextualSearchAction(intent.getAction());
                    boolean contextualFrame = false;
                    boolean contextualPayload = false;
                    String contextualDetail = "";
                    if (contextualBoundary) {
                        contextualFrame = captureContextualSearchFrame(intent);
                        boolean hasText = hasText();
                        contextualPayload = hasText || host.frameQueued();
                        contextualDetail = "source=activity_lifecycle"
                                + " activityClass="
                                + (activity == null ? "null" : activity.getClass().getName())
                                + " selectionSeen=" + host.selectionSeen()
                                + " textLen=" + textLength()
                                + " bounds=" + String.valueOf(host.selectionBounds())
                                + " frameQueued=" + contextualFrame
                                + " extras=" + GoogleHookFormatting.safeKeys(extras);
                        host.report("CONTEXTUAL_SEARCH_BOUNDARY", contextualDetail);
                    }

                    boolean contextualText = contextualBoundary && hasText();
                    if (contextualText && "callActivityOnNewIntent".equals(name)) {
                        host.report("CONTEXTUAL_TEXT_SEARCH_SUPPRESSED",
                                "path=activity_new_intent keepSelectionAlive=true textLen="
                                        + textLength());
                        module.log(Log.INFO, TAG,
                                "Contextual text-search newIntent suppressed; selection kept alive");
                        return null;
                    }

                    if (host.active() && !contextualBoundary) host.rememberMarkedActivity(activity);
                    Object result = chain.proceed();

                    if (contextualBoundary && contextualPayload && host.active()) {
                        if (hasText() && activity instanceof Activity contextualActivity) {
                            host.report("CONTEXTUAL_TEXT_SEARCH_SUPPRESSED",
                                    "path=activity_lifecycle keepSelectionAlive=true textLen="
                                            + textLength());
                            host.finishActivity(contextualActivity,
                                    "contextual_text_search_suppressed");
                            module.log(Log.INFO, TAG,
                                    "Contextual text search activity suppressed; selection kept alive");
                        } else {
                            boolean consumed = host.commitBridgeResult("", contextualDetail,
                                    "contextual_search_activity_intercept");
                            if (consumed && activity instanceof Activity contextualActivity) {
                                host.finishActivity(contextualActivity,
                                        "contextual_search_activity_intercept");
                                module.log(Log.INFO, TAG,
                                        "Contextual search activity consumed by FloatLens");
                            }
                        }
                    } else if (contextualBoundary && !contextualPayload && host.active()) {
                        host.report("CONTEXTUAL_SEARCH_PASSTHROUGH",
                                "activity lifecycle has no FloatLens payload");
                    }
                    return result;
                });
                count++;
            }
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "Activity lifecycle hook unavailable", t);
        }
        return count;
    }

    private boolean captureContextualSearchFrame(Intent intent) {
        if (!host.active() || intent == null
                || !GoogleCtsContract.isContextualSearchAction(intent.getAction())) {
            return false;
        }
        Bundle extras = intent.getExtras();
        if (extras == null || !extras.containsKey(GoogleCtsContract.CONTEXTUAL_SCREENSHOT)) {
            host.report("CONTEXTUAL_SCREENSHOT", "missing");
            return false;
        }
        Object value;
        try {
            value = extras.get(GoogleCtsContract.CONTEXTUAL_SCREENSHOT);
        } catch (Throwable t) {
            host.report("CONTEXTUAL_SCREENSHOT",
                    "read failed=" + t.getClass().getSimpleName());
            return false;
        }
        if (value instanceof Bitmap bitmap && !bitmap.isRecycled()) {
            host.report("CONTEXTUAL_SCREENSHOT",
                    "bitmap=" + GoogleHookFormatting.bitmapSummary(bitmap));
            host.sendBridgeFrame(bitmap);
            return host.frameQueued();
        }
        host.report("CONTEXTUAL_SCREENSHOT",
                "valueClass=" + (value == null ? "null" : value.getClass().getName())
                        + " value=" + GoogleHookFormatting.describeValue(value));
        return false;
    }

    private int hookActivityDispatch() {
        try {
            int count = 0;
            for (Executable executable : HiddenApiBypass.getDeclaredMethods(Instrumentation.class)) {
                if (!(executable instanceof Method method)
                        || !"execStartActivity".equals(method.getName())) continue;
                int intentIndex = findParameter(method.getParameterTypes(), Intent.class);
                if (intentIndex < 0) continue;
                final int idx = intentIndex;

                module.hook(method).intercept(chain -> {
                    if (!host.active()) return chain.proceed();
                    Intent intent = (Intent) chain.getArg(idx);

                    if (provider.diagnosticsEnabled()) {
                        host.report("START_ACTIVITY",
                                GoogleHookFormatting.describeIntent(intent)
                                        + " caller=" + GoogleHookFormatting.googleCaller());
                    }

                    if (intent == null
                            || !GoogleCtsContract.isContextualSearchAction(intent.getAction())) {
                        return chain.proceed();
                    }

                    boolean frameQueued = captureContextualSearchFrame(intent);
                    boolean hasText = hasText();
                    boolean hasRenderablePayload = hasText || host.frameQueued();
                    String detail = "action=" + intent.getAction()
                            + " selectionSeen=" + host.selectionSeen()
                            + " textLen=" + textLength()
                            + " bounds=" + String.valueOf(host.selectionBounds())
                            + " frameQueued=" + frameQueued
                            + " extras=" + GoogleHookFormatting.safeKeys(intent.getExtras());
                    host.report("CONTEXTUAL_SEARCH_BOUNDARY", detail);

                    if (!hasRenderablePayload) {
                        host.report("CONTEXTUAL_SEARCH_PASSTHROUGH",
                                "no renderable FloatLens payload; Google search allowed");
                        return chain.proceed();
                    }

                    if (hasText) {
                        host.report("CONTEXTUAL_TEXT_SEARCH_SUPPRESSED",
                                "path=execStartActivity keepSelectionAlive=true textLen="
                                        + textLength());
                        module.log(Log.INFO, TAG,
                                "Contextual text search launch suppressed; selection kept alive");
                        return null;
                    }

                    host.report("CONTEXTUAL_SEARCH_TAKEOVER",
                            "renderable payload ready; suppressing marked Google search");
                    boolean consumed = host.commitBridgeResult(
                            "", detail, "contextual_search_intercept");
                    if (!consumed) {
                        host.report("CONTEXTUAL_SEARCH_PASSTHROUGH",
                                "bridge commit rejected; Google search allowed");
                        return chain.proceed();
                    }

                    module.log(Log.INFO, TAG,
                            "Contextual search launch suppressed for FloatLens session");
                    return null;
                });
                count++;
            }
            return count;
        } catch (Throwable t) {
            module.log(Log.INFO, TAG, "Activity dispatch hook unavailable", t);
            return 0;
        }
    }

    private boolean hasText() {
        String text = host.selectionText();
        return text != null && !text.isBlank();
    }

    private int textLength() {
        String text = host.selectionText();
        return text == null ? 0 : text.length();
    }

    private void dumpClassStructure(Class<?> cls, String reason) {
        if (!provider.diagnosticsEnabled() || !host.active() || cls == null) return;
        StringBuilder out = new StringBuilder();
        out.append("reason=").append(reason).append(" class=").append(cls.getName());
        Class<?> parent = cls.getSuperclass();
        if (parent != null) out.append(" super=").append(parent.getName());
        int fields = 0;
        for (Field field : HiddenApiBypass.getInstanceFields(cls)) {
            if (fields++ >= 36) break;
            out.append("\n F ").append(field.getName()).append(":")
                    .append(field.getType().getName());
        }
        int methods = 0;
        for (Executable executable : HiddenApiBypass.getDeclaredMethods(cls)) {
            if (!(executable instanceof Method method)) continue;
            if (methods++ >= 60) break;
            out.append("\n M ").append(method.getName()).append("(");
            Class<?>[] p = method.getParameterTypes();
            for (int i = 0; i < p.length; i++) {
                if (i > 0) out.append(",");
                out.append(p[i].getSimpleName());
            }
            out.append("):").append(method.getReturnType().getSimpleName());
        }
        String text = out.toString();
        for (int i = 0; i < text.length(); i += 3000) {
            host.report("CLASS_STRUCT", text.substring(i, Math.min(text.length(), i + 3000)));
        }
    }

    private static Object findVoiceSessionOwner(Object callbacks) {
        if (callbacks == null) return null;
        for (Field field : HiddenApiBypass.getInstanceFields(callbacks.getClass())) {
            if (!field.getType().getName().equals("android.service.voice.VoiceInteractionSession")) {
                continue;
            }
            try {
                field.setAccessible(true);
                return field.get(callbacks);
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static int findParameter(Class<?>[] params, Class<?> type) {
        if (params == null || type == null) return -1;
        for (int i = 0; i < params.length; i++) {
            if (type.isAssignableFrom(params[i])) return i;
        }
        return -1;
    }
}
