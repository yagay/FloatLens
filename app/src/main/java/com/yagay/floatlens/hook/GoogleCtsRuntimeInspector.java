package com.yagay.floatlens.hook;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import com.yagay.floatlens.GoogleCtsContract;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.io.FileOutputStream;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private final ThreadLocal<Boolean> traceDispatching = new ThreadLocal<>();
    private final ExecutorService bridgeIo = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FloatLens-GoogleBridge-Tx");
        t.setDaemon(true);
        return t;
    });

    private volatile long activeUntil;
    private volatile String sessionToken = "";
    private volatile int showSessionId = -1;
    private volatile Object voiceSession;
    private volatile boolean bridgeFrameQueued;
    private volatile boolean bridgeCommitted;

    GoogleCtsRuntimeInspector(XposedModule module,
                              LsposedRuntimeProvider provider,
                              ClassLoader classLoader) {
        this.module = module;
        this.provider = provider;
        this.classLoader = classLoader;
    }

    void install() {
        int hooks = 0;
        hooks += hookGoogle1758OmnientBoundary();
        hooks += hookGoogle1758LensSelectionBoundary();
        hooks += hookVoiceSessionShow();
        hooks += hookVoiceScreenshot();
        hooks += hookActivityLifecycle();
        hooks += hookActivityDispatch();
        module.log(Log.INFO, TAG,
                "Google CTS inspector ready hooks=" + hooks + " profile=17.58.16.ve");
    }

    /** Google 17.58.16.ve real Omnient invocation boundary from classes6.dex. */
    private int hookGoogle1758OmnientBoundary() {
        try {
            Class<?> cls = Class.forName(
                    "com.google.android.apps.search.omnient.host.invocation.OmnientInvocationHandler",
                    false, classLoader);
            int count = 0;
            for (Executable executable : HiddenApiBypass.getDeclaredMethods(cls)) {
                if (!(executable instanceof Method method)) continue;
                Class<?>[] p = method.getParameterTypes();

                if ("f".equals(method.getName()) && p.length == 3 && p[1] == Bundle.class) {
                    module.hook(method).intercept(chain -> {
                        Bundle args = (Bundle) chain.getArg(1);
                        correlateGoogleBoundary(args, null, "OMNIENT_VIS");
                        if (active()) {
                            report("OMNIENT_VIS_ENTRY", "keys=" + safeKeys(args)
                                    + " owner=" + chain.getThisObject().getClass().getName());
                        }
                        return chain.proceed();
                    });
                    count++;
                    continue;
                }

                if ("c".equals(method.getName()) && p.length == 3 && p[1] == Intent.class) {
                    module.hook(method).intercept(chain -> {
                        Intent intent = (Intent) chain.getArg(1);
                        correlateGoogleBoundary(intent == null ? null : intent.getExtras(),
                                intent, "OMNIENT_CONTEXTUAL");
                        if (active()) report("OMNIENT_CONTEXTUAL_ENTRY", describeIntent(intent));
                        return chain.proceed();
                    });
                    count++;
                    continue;
                }

                if ("a".equals(method.getName()) && p.length == 3
                        && p[1] == Bitmap.class && p[2] == Intent.class
                        && method.getReturnType() == Intent.class) {
                    module.hook(method).intercept(chain -> {
                        Bitmap bitmap = (Bitmap) chain.getArg(1);
                        Intent source = (Intent) chain.getArg(2);
                        if (active()) {
                            report("OMNIENT_BUILD_VIS_INTENT",
                                    "bitmap=" + bitmapSummary(bitmap)
                                            + " source=" + describeIntent(source));
                            sendBridgeFrame(bitmap);
                        }
                        Object result = chain.proceed();
                        if (active() && result instanceof Intent out) {
                            report("OMNIENT_VIS_INTENT_READY", describeIntent(out));
                        }
                        return result;
                    });
                    count++;
                }
            }
            module.log(Log.INFO, TAG, "Google 17.58 Omnient hooks=" + count);
            return count;
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "Google 17.58 Omnient boundary unavailable", t);
            return 0;
        }
    }

    /** Google 17.58.16.ve Lens user-selection/query boundary from classes8.dex. */
    private int hookGoogle1758LensSelectionBoundary() {
        try {
            Class<?> controller = Class.forName("dscu", false, classLoader);
            int count = 0;
            for (Executable executable : HiddenApiBypass.getDeclaredMethods(controller)) {
                if (!(executable instanceof Method method)) continue;
                Class<?>[] p = method.getParameterTypes();

                if ("y".equals(method.getName()) && p.length == 2
                        && "dscl".equals(p[0].getName()) && p[1] == boolean.class) {
                    module.hook(method).intercept(chain -> {
                        if (active()) {
                            SelectionSnapshot selection = selectionSnapshot(chain.getArg(0));
                            report("USER_SELECTION",
                                    selection.detail() + " primary=" + chain.getArg(1));
                            sendBridgeEvent(GoogleCtsContract.EVENT_SELECTION,
                                    selection.text(), selection.detail(), selection.bounds());
                        }
                        return chain.proceed();
                    });
                    count++;
                    continue;
                }

                if ("p".equals(method.getName()) && p.length == 1
                        && "dtqj".equals(p[0].getName())) {
                    module.hook(method).intercept(chain -> {
                        if (!active()) return chain.proceed();
                        String detail = compactObject(chain.getArg(0), 4500);
                        report("LENS_QUERY_START", detail);
                        if (!bridgeCommitted) {
                            bridgeCommitted = true;
                            sendBridgeEvent(GoogleCtsContract.EVENT_COMMIT, "", detail, null);
                        }
                        if (method.getReturnType() == void.class) {
                            report("LENS_QUERY_SUPPRESSED",
                                    "FloatLens owns marked session; Google query submission skipped");
                            clear("bridge_commit_suppressed");
                            return null;
                        }
                        return chain.proceed();
                    });
                    count++;
                    continue;
                }

                if ("q".equals(method.getName()) && p.length == 1
                        && "dtqi".equals(p[0].getName())) {
                    module.hook(method).intercept(chain -> {
                        if (!active()) return chain.proceed();
                        Object queryResult = chain.getArg(0);
                        Object result = chain.proceed();
                        if (active()) {
                            String detail = compactObject(queryResult, 4500);
                            report("LENS_QUERY_RESULT", detail);
                            sendBridgeEvent(GoogleCtsContract.EVENT_QUERY_RESULT,
                                    firstStringField(queryResult), detail, null);
                            clear("bridge_query_result");
                        }
                        return result;
                    });
                    count++;
                }
            }
            module.log(Log.INFO, TAG, "Google 17.58 Lens selection hooks=" + count);
            return count;
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "Google 17.58 Lens selection boundary unavailable", t);
            return 0;
        }
    }

    private void correlateGoogleBoundary(Bundle extras, Intent intent, String path) {
        if (!provider.isActive()) return;
        if (GoogleCtsContract.isFloatLensSession(extras)) {
            activate(extras.getString(GoogleCtsContract.K_SESSION_TOKEN, ""),
                    showSessionId, voiceSession, path + "_MARKER");
            return;
        }
        if (active()) return;
        String token = provider.googleCtsArmedToken();
        if (!token.isBlank()) {
            activate(token, showSessionId, voiceSession, path + "_ARMED");
            report("BOUNDARY_CORRELATION",
                    "marker=false extras=" + safeKeys(extras)
                            + " intent=" + describeIntent(intent));
        }
    }

    private SelectionSnapshot selectionSnapshot(Object metadata) {
        if (metadata == null) return new SelectionSnapshot("", null, "metadata=null");
        StringBuilder out = new StringBuilder();
        out.append("metadataClass=").append(metadata.getClass().getName());
        Object selection = fieldByTypeName(metadata, "dtlp");
        if (selection == null) {
            out.append(" raw=").append(compactObject(metadata, 3500));
            return new SelectionSnapshot("", firstRectField(metadata), out.toString());
        }

        out.append(" userSelectionClass=").append(selection.getClass().getName());
        Object textSelection = null;
        String text = "";
        if ("dtlr".equals(selection.getClass().getName())) {
            textSelection = fieldByTypeName(selection, "dtvz");
            String found = firstStringField(textSelection);
            if (found != null && !found.isBlank()) {
                text = found;
                out.append(" selectedText=").append(quote(found, 2000));
            }
            out.append(" textSelection=").append(compactObject(textSelection, 2500));
        } else {
            out.append(" selection=").append(compactObject(selection, 3500));
        }

        Rect bounds = firstRectField(textSelection, selection, metadata);
        if (bounds != null) out.append(" bounds=").append(bounds.toShortString());
        return new SelectionSnapshot(text, bounds, out.toString());
    }

    private Rect firstRectField(Object... targets) {
        if (targets == null) return null;
        for (Object target : targets) {
            if (target == null) continue;
            if (target instanceof Rect rect && !rect.isEmpty()) return new Rect(rect);
            if (target instanceof RectF rectF && rectF.width() > 0f && rectF.height() > 0f) {
                Rect out = new Rect();
                rectF.roundOut(out);
                if (!out.isEmpty()) return out;
            }
            for (Field field : HiddenApiBypass.getInstanceFields(target.getClass())) {
                Class<?> type = field.getType();
                if (type != Rect.class && type != RectF.class) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(target);
                    if (value instanceof Rect rect && !rect.isEmpty()) return new Rect(rect);
                    if (value instanceof RectF rectF
                            && rectF.width() > 0f && rectF.height() > 0f) {
                        Rect out = new Rect();
                        rectF.roundOut(out);
                        if (!out.isEmpty()) return out;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private record SelectionSnapshot(String text, Rect bounds, String detail) {}

    private Object fieldByTypeName(Object target, String typeName) {
        if (target == null) return null;
        for (Field field : HiddenApiBypass.getInstanceFields(target.getClass())) {
            if (!typeName.equals(field.getType().getName())) continue;
            try {
                field.setAccessible(true);
                return field.get(target);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private String firstStringField(Object target) {
        if (target == null) return null;
        for (Field field : HiddenApiBypass.getInstanceFields(target.getClass())) {
            if (field.getType() != String.class) continue;
            try {
                field.setAccessible(true);
                Object value = field.get(target);
                if (value instanceof String s && !s.isBlank()) return s;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private String compactObject(Object value, int max) {
        if (value == null) return "null";
        String text;
        try {
            text = value.getClass().getName() + "{" + String.valueOf(value) + "}";
        } catch (Throwable t) {
            text = value.getClass().getName();
        }
        text = safe(text).replace("\n", " ").replace("\r", " ");
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private String quote(String value, int max) {
        String text = safe(value).replace("\n", " ").replace("\r", " ");
        if (text.length() > max) text = text.substring(0, max) + "…";
        return "\"" + text + "\"";
    }

    private String bitmapSummary(Bitmap bitmap) {
        return bitmap == null ? "null"
                : bitmap.getWidth() + "x" + bitmap.getHeight() + "/" + bitmap.getConfig();
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
                                id, chain.getThisObject(), "VIS_MARKER");
                        report("SESSION_SHOW", "path=VIS_MARKER sessionClass="
                                + chain.getThisObject().getClass().getName()
                                + " flags=" + chain.getArg(1)
                                + " keys=" + safeKeys(args));
                        dumpClassStructure(chain.getThisObject().getClass(), "voiceSession");
                    } else if (provider.isActive() && !active()) {
                        String armedToken = provider.googleCtsArmedToken();
                        if (!armedToken.isBlank()) {
                            activate(armedToken, id, chain.getThisObject(), "VIS_ARMED_FALLBACK");
                            report("SESSION_SHOW", "path=VIS_ARMED_FALLBACK marker=false sessionClass="
                                    + chain.getThisObject().getClass().getName()
                                    + " flags=" + chain.getArg(1)
                                    + " keys=" + safeKeys(args));
                            dumpClassStructure(chain.getThisObject().getClass(), "voiceSessionFallback");
                        }
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
                            sendBridgeFrame(bitmap);
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
                        activate(token, -1, null, "CONTEXTUAL_ACTIVITY_MARKER");
                        report("SESSION_SHOW", "path=ContextualActivity activity="
                                + (activity == null ? "null" : activity.getClass().getName())
                                + " intent=" + describeIntent(intent));
                        if (activity != null) dumpClassStructure(activity.getClass(), "activity");
                    } else if (provider.isActive() && !active() && intent != null) {
                        String armedToken = provider.googleCtsArmedToken();
                        if (!armedToken.isBlank()) {
                            activate(armedToken, -1, null, "CONTEXTUAL_ACTIVITY_ARMED_FALLBACK");
                            report("SESSION_SHOW", "path=ContextualActivityArmedFallback marker=false activity="
                                    + (activity == null ? "null" : activity.getClass().getName())
                                    + " intent=" + describeIntent(intent));
                            if (activity != null) dumpClassStructure(activity.getClass(), "activityFallback");
                        }
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
                if (active() && !isTraceDispatching()) {
                    String key = (String) chain.getArg(0);
                    if (!internalTraceKey(key) && relevantKey(key)) {
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
                if (active() && !isTraceDispatching()) {
                    String key = (String) chain.getArg(0);
                    if (!internalTraceKey(key) && relevantKey(key)) {
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
        String nextToken = token == null ? "" : token;
        boolean newBridgeSession = !nextToken.equals(sessionToken)
                || SystemClock.elapsedRealtime() >= activeUntil;
        activeUntil = SystemClock.elapsedRealtime() + SESSION_TTL_MS;
        sessionToken = nextToken;
        if (newBridgeSession) {
            bridgeFrameQueued = false;
            bridgeCommitted = false;
        }
        if (id >= 0) showSessionId = id;
        if (session != null) voiceSession = session;
        eventCount.set(0);
        seenClasses.clear();
        String header = "=== Google CTS marked session ===\n"
                + "ACTIVE path=" + path
                + " session=" + shortToken(sessionToken)
                + " showId=" + showSessionId
                + " atElapsed=" + SystemClock.elapsedRealtime();
        sendTrace("=== Google CTS marked session ===");
        sendTrace("ACTIVE path=" + path
                + " session=" + shortToken(sessionToken)
                + " showId=" + showSessionId
                + " atElapsed=" + SystemClock.elapsedRealtime());
        module.log(Log.INFO, TAG, header.replace("\n", " | "));
    }

    private synchronized void clear(String reason) {
        String end = "END session=" + shortToken(sessionToken)
                + " reason=" + reason + " events=" + eventCount.get();
        if (!bridgeCommitted && !sessionToken.isBlank()) {
            sendBridgeEvent(GoogleCtsContract.EVENT_END, "", reason, null);
        }
        sendTrace(end);
        module.log(Log.INFO, TAG, end);
        activeUntil = 0L;
        sessionToken = "";
        showSessionId = -1;
        voiceSession = null;
        bridgeFrameQueued = false;
        bridgeCommitted = false;
        seenClasses.clear();
    }

    private boolean active() {
        return provider.isActive()
                && !sessionToken.isBlank()
                && SystemClock.elapsedRealtime() < activeUntil;
    }

    private void report(String event, String message) {
        if (!active()) return;
        int n = reserveEventNumber();
        if (n < 0) return;
        String line = "#" + n + " " + event + " session="
                + shortToken(sessionToken) + " " + safe(message);
        sendTrace(line);
        module.log(Log.INFO, TAG, line);
    }

    private int reserveEventNumber() {
        while (true) {
            int current = eventCount.get();
            if (current >= MAX_EVENT_LOGS) return -1;
            if (eventCount.compareAndSet(current, current + 1)) return current + 1;
        }
    }

    private void sendTrace(String line) {
        if (line == null || line.isBlank() || sessionToken.isBlank()) return;
        if (isTraceDispatching()) return;
        traceDispatching.set(Boolean.TRUE);
        try {
            Context context = currentApplicationContext();
            if (context == null) return;
            Intent intent = new Intent(GoogleCtsContract.ACTION_TRACE)
                    .setClassName("com.yagay.floatlens", GoogleCtsContract.TRACE_RECEIVER_CLASS)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra(GoogleCtsContract.EXTRA_TRACE_SESSION, sessionToken)
                    .putExtra(GoogleCtsContract.EXTRA_TRACE_LINE,
                            line.length() > 8000 ? line.substring(0, 8000) : line);
            context.sendBroadcast(intent);
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "CTS trace broadcast failed", t);
        } finally {
            traceDispatching.remove();
        }
    }

    private void sendBridgeEvent(String event, String text, String detail, Rect bounds) {
        String token = sessionToken;
        if (token == null || token.isBlank() || event == null || event.isBlank()) return;
        try {
            Context context = currentApplicationContext();
            if (context == null) return;
            Intent intent = new Intent(GoogleCtsContract.ACTION_BRIDGE)
                    .setClassName("com.yagay.floatlens", GoogleCtsContract.BRIDGE_RECEIVER_CLASS)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra(GoogleCtsContract.EXTRA_BRIDGE_SESSION, token)
                    .putExtra(GoogleCtsContract.EXTRA_BRIDGE_EVENT, event);
            if (text != null && !text.isBlank()) {
                intent.putExtra(GoogleCtsContract.EXTRA_BRIDGE_TEXT,
                        text.length() > 20_000 ? text.substring(0, 20_000) : text);
            }
            if (detail != null && !detail.isBlank()) {
                intent.putExtra(GoogleCtsContract.EXTRA_BRIDGE_DETAIL,
                        detail.length() > 8_000 ? detail.substring(0, 8_000) : detail);
            }
            if (bounds != null && !bounds.isEmpty()) {
                intent.putExtra(GoogleCtsContract.EXTRA_LEFT, bounds.left);
                intent.putExtra(GoogleCtsContract.EXTRA_TOP, bounds.top);
                intent.putExtra(GoogleCtsContract.EXTRA_RIGHT, bounds.right);
                intent.putExtra(GoogleCtsContract.EXTRA_BOTTOM, bounds.bottom);
            }
            context.sendBroadcast(intent);
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "CTS bridge event failed event=" + event, t);
        }
    }

    private synchronized void sendBridgeFrame(Bitmap bitmap) {
        if (!active() || bridgeFrameQueued || bitmap == null || bitmap.isRecycled()) return;
        String token = sessionToken;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        ByteBuffer pixels = snapshotPixels(bitmap);
        if (pixels == null) return;
        bridgeFrameQueued = true;
        bridgeIo.execute(() -> writeBridgeFrame(token, width, height, pixels));
    }

    private ByteBuffer snapshotPixels(Bitmap bitmap) {
        Bitmap normalized = null;
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            long byteCountLong = (long) width * (long) height * 4L;
            if (width <= 0 || height <= 0 || byteCountLong <= 0L
                    || byteCountLong > 64L * 1024L * 1024L) {
                return null;
            }
            int bytes = (int) byteCountLong;
            Bitmap source = bitmap;
            if (bitmap.getConfig() != Bitmap.Config.ARGB_8888
                    || bitmap.getRowBytes() != width * 4) {
                normalized = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                if (normalized == null) return null;
                source = normalized;
            }
            ByteBuffer pixels = ByteBuffer.allocateDirect(bytes);
            source.copyPixelsToBuffer(pixels);
            pixels.flip();
            if (pixels.remaining() != bytes) return null;
            return pixels;
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "Google bridge pixel snapshot failed", t);
            return null;
        } finally {
            if (normalized != null && !normalized.isRecycled()) {
                try { normalized.recycle(); } catch (Throwable ignored) {}
            }
        }
    }

    private void writeBridgeFrame(String token, int width, int height, ByteBuffer pixels) {
        int bytes = pixels == null ? 0 : pixels.remaining();
        try {
            Context context = currentApplicationContext();
            if (context == null || bytes <= 0) {
                throw new IllegalStateException("context/pixels unavailable");
            }
            Uri uri = GoogleCtsContract.bridgeFrameUri(token, width, height, bytes);
            ParcelFileDescriptor descriptor =
                    context.getContentResolver().openFileDescriptor(uri, "w");
            if (descriptor == null) throw new IllegalStateException("bridge pipe unavailable");
            try (FileOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
                FileChannel channel = out.getChannel();
                while (pixels.hasRemaining()) channel.write(pixels);
            }
            module.log(Log.INFO, TAG,
                    "Google bridge frame sent session=" + shortToken(token)
                            + " size=" + width + "x" + height + " bytes=" + bytes);
        } catch (Throwable t) {
            if (token != null && token.equals(sessionToken) && !bridgeCommitted) {
                bridgeFrameQueued = false;
            }
            module.log(Log.WARN, TAG,
                    "Google bridge frame send failed session=" + shortToken(token), t);
        }
    }

    private boolean isTraceDispatching() {
        return Boolean.TRUE.equals(traceDispatching.get());
    }

    private boolean internalTraceKey(String key) {
        if (key == null) return false;
        return GoogleCtsContract.EXTRA_TRACE_SESSION.equals(key)
                || GoogleCtsContract.EXTRA_TRACE_LINE.equals(key)
                || GoogleCtsContract.EXTRA_BRIDGE_SESSION.equals(key)
                || GoogleCtsContract.EXTRA_BRIDGE_EVENT.equals(key)
                || GoogleCtsContract.EXTRA_BRIDGE_TEXT.equals(key)
                || GoogleCtsContract.EXTRA_BRIDGE_DETAIL.equals(key);
    }

    private Context currentApplicationContext() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object value = HiddenApiBypass.invoke(activityThread, null, "currentApplication");
            if (value instanceof Context context) return context.getApplicationContext();
        } catch (Throwable ignored) {
        }
        return null;
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
