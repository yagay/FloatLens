package com.yagay.floatlens.hook;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import com.yagay.floatlens.GoogleCtsContract;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import dalvik.system.BaseDexClassLoader;
import io.github.libxposed.api.XposedModule;

/**
 * Runtime inspector for the Google side of Circle to Search.
 *
 * <p>Nothing is activated for normal Home/gesture CTS sessions. A session becomes active only
 * after a FloatLens marker is observed in either the VIS show Bundle or the Omnient Activity
 * launch Intent. The inspector then traces the whole marked session in one run.</p>
 */
final class GoogleCtsRuntimeInspector {
    private static final String TAG = "FloatLens-GoogleCTS";
    private static final String SHOW_SESSION_ID = "android.service.voice.SHOW_SESSION_ID";
    private static final long SESSION_TTL_MS = 120_000L;
    private static final int MAX_EVENT_LOGS = 500;

    private final XposedModule module;
    private final LsposedRuntimeProvider provider;
    private final ClassLoader classLoader;
    private final Set<String> seenClasses = new HashSet<>();
    private final AtomicInteger eventCount = new AtomicInteger();

    private volatile long activeUntil;
    private volatile String sessionToken = "";
    private volatile int showSessionId = -1;
    private volatile Object voiceSession;

    GoogleCtsRuntimeInspector(XposedModule module,
                              LsposedRuntimeProvider provider,
                              ClassLoader classLoader) {
        this.module = module;
        this.provider = provider;
        this.classLoader = classLoader;
    }

    void install() {
        int hooks = 0;
        hooks += hookVoiceSessionShow();
        hooks += hookVoiceScreenshot();
        hooks += hookActivityLifecycle();
        hooks += hookIntentWrites();
        hooks += hookBundleWrites();
        hooks += hookActivityDispatch();
        hooks += hookRelevantClassLoads();
        module.log(Log.INFO, TAG, "marked-session inspector ready hooks=" + hooks);
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
                        activate(args.getString(GoogleCtsContract.K_SESSION_TOKEN, ""),
                                id, chain.getThisObject(), "VIS");
                        report("SESSION_SHOW", "path=VIS sessionClass="
                                + chain.getThisObject().getClass().getName()
                                + " flags=" + chain.getArg(1)
                                + " keys=" + safeKeys(args));
                        dumpClassStructure(chain.getThisObject().getClass(), "voiceSession");
                    } else if (active() && id >= 0 && id != showSessionId) {
                        clear("new_unmarked_voice_session id=" + id);
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
                    if (active() && self == voiceSession) clear("voice_session_hide");
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
                if (!(executable instanceof Method method)) continue;
                if (!"handleScreenshot".equals(method.getName())) continue;
                Class<?>[] p = method.getParameterTypes();
                if (p.length != 1 || p[0] != Bitmap.class) continue;
                module.hook(method).intercept(chain -> {
                    if (active()) {
                        Object owner = findVoiceSessionOwner(chain.getThisObject());
                        if (voiceSession == null || owner == voiceSession) {
                            Bitmap bitmap = (Bitmap) chain.getArg(0);
                            report("SCREENSHOT", bitmap == null ? "bitmap=null"
                                    : "bitmap=" + bitmap.getWidth() + "x" + bitmap.getHeight()
                                    + " config=" + bitmap.getConfig());
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
                if (!"callActivityOnCreate".equals(name) && !"callActivityOnNewIntent".equals(name)) continue;
                module.hook(method).intercept(chain -> {
                    Object activity = chain.getArg(0);
                    Intent intent = activity instanceof android.app.Activity a ? a.getIntent() : null;
                    Bundle extras = intent == null ? null : intent.getExtras();
                    if (provider.isActive() && GoogleCtsContract.isFloatLensSession(extras)) {
                        String token = extras.getString(GoogleCtsContract.K_SESSION_TOKEN, "");
                        activate(token, -1, null, "CONTEXTUAL_ACTIVITY");
                        report("SESSION_SHOW", "path=ContextualActivity activity="
                                + (activity == null ? "null" : activity.getClass().getName())
                                + " intent=" + describeIntent(intent));
                        if (activity != null) dumpClassStructure(activity.getClass(), "activity");
                    } else if (active() && intent != null) {
                        report("ACTIVITY_LIFECYCLE", name + " " + describeIntent(intent));
                    }
                    return chain.proceed();
                });
                count++;
            }
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "Activity lifecycle hook unavailable", t);
        }
        return count;
    }

    private int hookActivityDispatch() {
        try {
            int count = 0;
            for (Executable executable : HiddenApiBypass.getDeclaredMethods(Instrumentation.class)) {
                if (!(executable instanceof Method method)) continue;
                if (!"execStartActivity".equals(method.getName())) continue;
                int intentIndex = findParameter(method.getParameterTypes(), Intent.class);
                if (intentIndex < 0) continue;
                final int idx = intentIndex;
                module.hook(method).intercept(chain -> {
                    if (active()) {
                        Intent intent = (Intent) chain.getArg(idx);
                        report("START_ACTIVITY", describeIntent(intent)
                                + " caller=" + googleCaller());
                    }
                    return chain.proceed();
                });
                count++;
            }
            return count;
        } catch (Throwable t) {
            module.log(Log.INFO, TAG, "Activity dispatch hook unavailable", t);
            return 0;
        }
    }

    private int hookIntentWrites() {
        int count = 0;
        for (Method method : Intent.class.getDeclaredMethods()) {
            if (!"putExtra".equals(method.getName())) continue;
            Class<?>[] p = method.getParameterTypes();
            if (p.length != 2 || p[0] != String.class) continue;
            module.hook(method).intercept(chain -> {
                if (active()) {
                    String key = (String) chain.getArg(0);
                    if (relevantKey(key)) {
                        report("INTENT_EXTRA", "key=" + key
                                + " value=" + describeValue(chain.getArg(1))
                                + " caller=" + googleCaller());
                    }
                }
                return chain.proceed();
            });
            count++;
        }
        return count;
    }

    private int hookBundleWrites() {
        int count = 0;
        for (Method method : Bundle.class.getDeclaredMethods()) {
            if (!method.getName().startsWith("put")) continue;
            Class<?>[] p = method.getParameterTypes();
            if (p.length < 2 || p[0] != String.class) continue;
            module.hook(method).intercept(chain -> {
                if (active()) {
                    String key = (String) chain.getArg(0);
                    if (relevantKey(key)) {
                        report("BUNDLE_WRITE", "method=" + method.getName()
                                + " key=" + key
                                + " value=" + describeValue(chain.getArg(1))
                                + " caller=" + googleCaller());
                    }
                }
                return chain.proceed();
            });
            count++;
        }
        return count;
    }

    private int hookRelevantClassLoads() {
        try {
            Class<?> cls = BaseDexClassLoader.class;
            int count = 0;
            for (Executable executable : HiddenApiBypass.getDeclaredMethods(cls)) {
                if (!(executable instanceof Method method)) continue;
                if (!"findClass".equals(method.getName())) continue;
                Class<?>[] p = method.getParameterTypes();
                if (p.length < 1 || p[0] != String.class) continue;
                module.hook(method).intercept(chain -> {
                    Object result = chain.proceed();
                    if (active() && result instanceof Class<?> loaded) {
                        String name = loaded.getName();
                        if (relevantClass(name) && markClass(name)) {
                            report("CLASS_LOAD", name);
                            if (name.contains(".omnient.")) dumpClassStructure(loaded, "omnient");
                        }
                    }
                    return result;
                });
                count++;
            }
            return count;
        } catch (Throwable t) {
            module.log(Log.INFO, TAG, "Dex class-load trace unavailable", t);
            return 0;
        }
    }

    private synchronized void activate(String token, int id, Object session, String path) {
        activeUntil = SystemClock.elapsedRealtime() + SESSION_TTL_MS;
        sessionToken = token == null ? "" : token;
        if (id >= 0) showSessionId = id;
        if (session != null) voiceSession = session;
        eventCount.set(0);
        seenClasses.clear();
        module.log(Log.INFO, TAG, "ACTIVE path=" + path
                + " session=" + shortToken(sessionToken)
                + " showId=" + showSessionId);
    }

    private synchronized void clear(String reason) {
        module.log(Log.INFO, TAG, "END session=" + shortToken(sessionToken)
                + " reason=" + reason + " events=" + eventCount.get());
        activeUntil = 0L;
        sessionToken = "";
        showSessionId = -1;
        voiceSession = null;
        seenClasses.clear();
    }

    private boolean active() {
        return provider.isActive()
                && !sessionToken.isBlank()
                && SystemClock.elapsedRealtime() < activeUntil;
    }

    private void report(String event, String message) {
        if (!active()) return;
        int n = eventCount.incrementAndGet();
        if (n > MAX_EVENT_LOGS) return;
        module.log(Log.INFO, TAG, "#" + n + " " + event + " session="
                + shortToken(sessionToken) + " " + safe(message));
    }

    private void dumpClassStructure(Class<?> cls, String reason) {
        if (!active() || cls == null) return;
        StringBuilder out = new StringBuilder();
        out.append("reason=").append(reason).append(" class=").append(cls.getName());
        Class<?> parent = cls.getSuperclass();
        if (parent != null) out.append(" super=").append(parent.getName());
        int fields = 0;
        for (Field field : HiddenApiBypass.getInstanceFields(cls)) {
            if (fields++ >= 36) break;
            out.append("\n F ").append(field.getName()).append(":").append(field.getType().getName());
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
            report("CLASS_STRUCT", text.substring(i, Math.min(text.length(), i + 3000)));
        }
    }

    private Object findVoiceSessionOwner(Object callbacks) {
        if (callbacks == null) return null;
        for (Field field : HiddenApiBypass.getInstanceFields(callbacks.getClass())) {
            if (!field.getType().getName().equals("android.service.voice.VoiceInteractionSession")) continue;
            try {
                field.setAccessible(true);
                return field.get(callbacks);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private int findParameter(Class<?>[] params, Class<?> type) {
        for (int i = 0; i < params.length; i++) if (type.isAssignableFrom(params[i])) return i;
        return -1;
    }

    private boolean relevantKey(String key) {
        if (key == null) return false;
        String k = key.toLowerCase(Locale.ROOT);
        return k.contains("omni") || k.contains("query") || k.contains("text")
                || k.contains("select") || k.contains("image") || k.contains("bitmap")
                || k.contains("screen") || k.contains("crop") || k.contains("region")
                || k.contains("rect") || k.contains("polygon") || k.contains("lens")
                || k.contains("visual") || k.contains("object") || k.contains("translate");
    }

    private boolean relevantClass(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains(".omnient.") || n.contains(".lens.")
                || n.contains("contextualsearch") || n.contains("contextual_search");
    }

    private synchronized boolean markClass(String name) {
        if (seenClasses.size() >= 160) return false;
        return seenClasses.add(name);
    }

    private String googleCaller() {
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            String cls = frame.getClassName();
            if (cls != null && cls.startsWith("com.google.")) {
                return cls + "#" + frame.getMethodName() + ":" + frame.getLineNumber();
            }
        }
        return "unknown";
    }

    private String describeIntent(Intent intent) {
        if (intent == null) return "intent=null";
        Uri data = intent.getData();
        return "action=" + intent.getAction()
                + " component=" + intent.getComponent()
                + " package=" + intent.getPackage()
                + " data=" + (data == null ? "null" : data.toString())
                + " extras=" + safeKeys(intent.getExtras());
    }

    private String describeValue(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) return "String(len=" + s.length() + ")";
        if (value instanceof Bitmap b) return "Bitmap(" + b.getWidth() + "x" + b.getHeight() + ")";
        if (value instanceof Bundle b) return "Bundle" + safeKeys(b);
        if (value instanceof Intent i) return "Intent{" + describeIntent(i) + "}";
        if (value instanceof Rect || value instanceof RectF) return value.toString();
        if (value instanceof Collection<?> c) return value.getClass().getSimpleName() + "(size=" + c.size() + ")";
        Class<?> cls = value.getClass();
        if (cls.isArray()) return cls.getComponentType().getSimpleName() + "[]";
        return cls.getName();
    }

    private String safeKeys(Bundle bundle) {
        if (bundle == null) return "[]";
        try { return bundle.keySet().toString(); } catch (Throwable t) { return "[unreadable]"; }
    }

    private String shortToken(String token) {
        if (token == null || token.isBlank()) return "none";
        return token.substring(0, Math.min(8, token.length()));
    }

    private String safe(String value) {
        if (value == null) return "";
        return value.replace("\u0000", "?");
    }
}
