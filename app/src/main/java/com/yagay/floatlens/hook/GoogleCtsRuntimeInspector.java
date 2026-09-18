package com.yagay.floatlens.hook;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import com.yagay.floatlens.GoogleCtsContract;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
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
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
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
    private volatile boolean bridgeSelectionSeen;
    private volatile boolean bridgePendingSeen;
    private volatile String bridgeSelectionText = "";
    private volatile Rect bridgeSelectionBounds;
    private volatile WeakReference<Activity> markedActivity = new WeakReference<>(null);

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
        hooks += hookGoogleNativeRenderedPresentationData();
        hooks += hookGoogleFixedSelectionChips();
        hooks += hookGoogle1758LensSelectionBoundary();
        hooks += hookGoogleTextFloatingToolbar();
        hooks += hookGoogleMaterialFloatingToolbar();
        hooks += hookVoiceSessionShow();
        hooks += hookVoiceScreenshot();
        hooks += hookActivityLifecycle();
        hooks += hookActivityDispatch();
        module.log(Log.INFO, TAG,
                "Google CTS inspector ready hooks=" + hooks
                        + " profile=" + GoogleLens1758Profile.NAME);
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

    /**
     * 17.58 InteractionDataResult (eses) owns nativeRenderedPresentationResult in field f.
     * Strip that server/native action presentation as soon as the value object is constructed,
     * before any Google UI consumer can observe it. Selection geometry/state lives elsewhere.
     */
    private int hookGoogleNativeRenderedPresentationData() {
        try {
            Class<?> interactionData = Class.forName(
                    GoogleLens1758Profile.INTERACTION_DATA, false, classLoader);
            int count = 0;
            for (Executable constructor : interactionData.getDeclaredConstructors()) {
                module.hook(constructor).intercept(chain -> {
                    Object result = chain.proceed();
                    if (!active() || !bridgeSelectionSeen
                            || bridgeSelectionText == null
                            || bridgeSelectionText.isBlank()) {
                        return result;
                    }

                    GoogleLens1758Profile.NativePresentationSuppression suppression =
                            GoogleLens1758Profile.suppressNativeRenderedPresentationData(
                                    chain.getThisObject());
                    if (suppression.suppressed()) {
                        report("GOOGLE_NATIVE_PRESENTATION_STRIPPED",
                                "path=constructor " + suppression.detail());
                    }
                    return result;
                });
                count++;
            }
            module.log(Log.INFO, TAG,
                    "Google native presentation constructors hooked=" + count);
            return count;
        } catch (Throwable t) {
            module.log(Log.WARN, TAG,
                    "Google native presentation constructor boundary unavailable", t);
            return 0;
        }
    }

    /**
     * classes8.dex dujo is the fixed Google text-selection chip Fragment. Its onCreateView
     * explicitly wires the "Select all chip clicked" and "Listen all chip clicked" controls.
     * Hide only this Fragment's root for FloatLens-owned sessions; the Lens selection overlay and
     * DRAG_TEXT_HANDLE implementation are separate from this Fragment.
     */
    private int hookGoogleFixedSelectionChips() {
        try {
            Class<?> fragment = Class.forName("dujo", false, classLoader);
            int count = 0;
            for (Executable executable : HiddenApiBypass.getDeclaredMethods(fragment)) {
                if (!(executable instanceof Method method)) continue;
                if (!"onCreateView".equals(method.getName())
                        || method.getParameterCount() != 3
                        || !View.class.isAssignableFrom(method.getReturnType())) {
                    continue;
                }

                module.hook(method).intercept(chain -> {
                    Object result = chain.proceed();
                    if (active() && result instanceof View view) {
                        view.setVisibility(View.GONE);
                        report("GOOGLE_FIXED_SELECTION_CHIPS_SUPPRESSED",
                                "fragment=dujo root=" + view.getClass().getName());
                    }
                    return result;
                });
                count++;
            }
            module.log(Log.INFO, TAG,
                    "Google fixed selection chip hooks=" + count);
            return count;
        } catch (Throwable t) {
            module.log(Log.WARN, TAG,
                    "Google fixed selection chip boundary unavailable", t);
            return 0;
        }
    }

    /**
     * Preserve Google's native selection/highlight/resize handles while replacing only the
     * framework floating text toolbar with FloatLens' own menu. The hook lives only in the Google
     * process and is additionally gated by a live FloatLens-marked CTS session.
     */
    private int hookGoogleTextFloatingToolbar() {
        try {
            Class<?> toolbar = Class.forName(
                    "com.android.internal.widget.floatingtoolbar.FloatingToolbar",
                    false, classLoader);
            int count = 0;
            for (Executable executable : HiddenApiBypass.getDeclaredMethods(toolbar)) {
                if (!(executable instanceof Method method)) continue;
                if (!"show".equals(method.getName()) || method.getParameterCount() != 0) continue;

                module.hook(method).intercept(chain -> {
                    if (!active() || !bridgeSelectionSeen) return chain.proceed();

                    report("GOOGLE_TEXT_TOOLBAR_SUPPRESSED",
                            "class=" + chain.getThisObject().getClass().getName()
                                    + " textLen="
                                    + (bridgeSelectionText == null ? 0
                                    : bridgeSelectionText.length())
                                    + " bounds=" + String.valueOf(bridgeSelectionBounds));

                    Class<?> returnType = method.getReturnType();
                    if (returnType == void.class) return null;
                    if (returnType.isInstance(chain.getThisObject())) {
                        return chain.getThisObject();
                    }
                    return null;
                });
                count++;
            }
            module.log(Log.INFO, TAG,
                    "Google floating text toolbar hooks=" + count);
            return count;
        } catch (Throwable t) {
            module.log(Log.WARN, TAG,
                    "Google floating text toolbar boundary unavailable", t);
            return 0;
        }
    }

    /**
     * Google 17.58 also ships Material's FloatingToolbarLayout. Circle-to-Search can use this
     * app-owned toolbar instead of the framework FloatingToolbar, so suppress that layout as a
     * second, more specific boundary while preserving the selection state and handles.
     */
    private int hookGoogleMaterialFloatingToolbar() {
        try {
            Class<?> toolbar = Class.forName(
                    "com.google.android.material.floatingtoolbar.FloatingToolbarLayout",
                    false, classLoader);
            Class<?> viewClass = Class.forName("android.view.View", false, classLoader);
            Method setVisibility = viewClass.getDeclaredMethod("setVisibility", int.class);

            module.hook(setVisibility).intercept(chain -> {
                Object target = chain.getThisObject();
                int visibility = (Integer) chain.getArg(0);
                if (active() && bridgeSelectionSeen && visibility == View.VISIBLE
                        && toolbar.isInstance(target)) {
                    report("GOOGLE_MATERIAL_TOOLBAR_SUPPRESSED",
                            "path=setVisibility textLen="
                                    + (bridgeSelectionText == null ? 0
                                    : bridgeSelectionText.length())
                                    + " bounds=" + String.valueOf(bridgeSelectionBounds));
                    return null;
                }
                return chain.proceed();
            });

            // The layout has an internal no-arg update method in 17.58. Hide it again after any
            // update in case it was already visible before setVisibility was intercepted.
            int count = 1;
            for (Executable executable : HiddenApiBypass.getDeclaredMethods(toolbar)) {
                if (!(executable instanceof Method method)) continue;
                if (!"a".equals(method.getName()) || method.getParameterCount() != 0) continue;
                module.hook(method).intercept(chain -> {
                    Object result = chain.proceed();
                    if (active() && bridgeSelectionSeen
                            && chain.getThisObject() instanceof View view) {
                        view.setVisibility(View.GONE);
                        report("GOOGLE_MATERIAL_TOOLBAR_SUPPRESSED",
                                "path=layoutUpdate class="
                                        + chain.getThisObject().getClass().getName());
                    }
                    return result;
                });
                count++;
            }

            module.log(Log.INFO, TAG,
                    "Google Material floating toolbar hooks=" + count);
            return count;
        } catch (Throwable t) {
            module.log(Log.WARN, TAG,
                    "Google Material floating toolbar boundary unavailable", t);
            return 0;
        }
    }

    private void hideGoogleMaterialFloatingToolbarSoon() {
        Activity activity = markedActivity.get();
        if (activity == null) return;
        Runnable hide = () -> {
            try {
                View root = activity.getWindow() == null
                        ? null : activity.getWindow().getDecorView();
                int hidden = hideGoogleMaterialFloatingToolbar(root);
                if (hidden > 0) {
                    report("GOOGLE_MATERIAL_TOOLBAR_SUPPRESSED",
                            "path=decorTraversal hidden=" + hidden
                                    + " textLen="
                                    + (bridgeSelectionText == null ? 0
                                    : bridgeSelectionText.length()));
                }
            } catch (Throwable t) {
                module.log(Log.WARN, TAG,
                        "Failed to hide Google Material toolbar", t);
            }
        };
        activity.runOnUiThread(hide);
        mainHandler.postDelayed(() -> activity.runOnUiThread(hide), 48L);
        mainHandler.postDelayed(() -> activity.runOnUiThread(hide), 140L);
    }

    private int hideGoogleMaterialFloatingToolbar(View view) {
        if (view == null) return 0;
        int hidden = 0;
        if ("com.google.android.material.floatingtoolbar.FloatingToolbarLayout"
                .equals(view.getClass().getName())) {
            if (view.getVisibility() != View.GONE) {
                view.setVisibility(View.GONE);
            }
            hidden++;
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                hidden += hideGoogleMaterialFloatingToolbar(group.getChildAt(i));
            }
        }
        return hidden;
    }

    /** Google 17.58.16.ve Lens user-selection/query boundary from classes8.dex. */
    private int hookGoogle1758LensSelectionBoundary() {
        try {
            String profileError = GoogleLens1758Profile.validationError(classLoader);
            if (!profileError.isBlank()) {
                module.log(Log.WARN, TAG,
                        "Google " + GoogleLens1758Profile.NAME
                                + " Lens profile rejected: " + profileError);
                return 0;
            }

            Class<?> controller = Class.forName(
                    GoogleLens1758Profile.CONTROLLER, false, classLoader);
            int count = 0;
            for (Executable executable : HiddenApiBypass.getDeclaredMethods(controller)) {
                if (!(executable instanceof Method method)) continue;

                if (GoogleLens1758Profile.isSelectionMethod(method)) {
                    module.hook(method).intercept(chain -> {
                        if (active()) {
                            GoogleLens1758Profile.SelectionSnapshot selection =
                                    GoogleLens1758Profile.selection(chain.getArg(0));
                            Rect selectionBounds = selectionBoundsForDisplay(selection);
                            bridgeSelectionSeen = true;
                            bridgeSelectionText = selection.text();
                            if (selectionBounds != null && !selectionBounds.isEmpty()) {
                                bridgeSelectionBounds = selectionBounds;
                            }
                            Rect effectiveBounds = bridgeSelectionBounds == null
                                    ? null : new Rect(bridgeSelectionBounds);
                            report("USER_SELECTION",
                                    selection.detail()
                                            + " pixelBounds=" + String.valueOf(selectionBounds)
                                            + " effectiveBounds=" + String.valueOf(effectiveBounds)
                                            + " primary=" + chain.getArg(1));
                            sendBridgeEvent(GoogleCtsContract.EVENT_SELECTION,
                                    selection.text(), selection.detail(), effectiveBounds);
                        }
                        Object result = chain.proceed();
                        if (active() && bridgeSelectionSeen
                                && bridgeSelectionText != null
                                && !bridgeSelectionText.isBlank()) {
                            hideGoogleMaterialFloatingToolbarSoon();
                        }
                        return result;
                    });
                    count++;
                    continue;
                }

                if (GoogleLens1758Profile.isPendingQueryMethod(method)) {
                    module.hook(method).intercept(chain -> {
                        if (!active()) return chain.proceed();

                        Object pending = chain.getArg(0);
                        if (pending == null) {
                            report("LENS_QUERY_STATE",
                                    "pending=null ignored (LensUiController initial state)");
                            return chain.proceed();
                        }

                        if (bridgeSelectionText != null
                                && !bridgeSelectionText.isBlank()) {
                            GoogleLens1758Profile.PresentationRequestSuppression suppression =
                                    GoogleLens1758Profile.suppressTextPresentationRequest(pending);
                            report(suppression.suppressed()
                                            ? "GOOGLE_PRESENTATION_REQUEST_SUPPRESSED"
                                            : "GOOGLE_PRESENTATION_REQUEST_UNCHANGED",
                                    suppression.detail());
                        }

                        GoogleLens1758Profile.PendingSnapshot snapshot =
                                GoogleLens1758Profile.pending(pending);
                        bridgePendingSeen = true;
                        // Keep the query itself alive for OCR/selection updates. Only the
                        // requestPresentationResult bit is disabled for FloatLens-owned text
                        // selections so Google does not ask for its native action presentation.
                        report("LENS_QUERY_START", snapshot.detail());
                        sendBridgeFrame(snapshot.frame());

                        // Do not suppress 17.58 p(PendingLensQuery). Google still needs to finish
                        // Lens processing so FloatLens can consume the real LensQueryResult.
                        return chain.proceed();
                    });
                    count++;
                    continue;
                }

                if (GoogleLens1758Profile.isQueryResultMethod(method)) {
                    module.hook(method).intercept(chain -> {
                        if (!active()) return chain.proceed();

                        Object queryResult = chain.getArg(0);
                        if (queryResult == null) {
                            report("LENS_QUERY_STATE",
                                    "result=null ignored (LensUiController initial state)");
                            return chain.proceed();
                        }

                        // Constructor-level stripping is the earliest boundary. Repeat the
                        // native-presentation strip here as a fail-soft fallback in case a future
                        // Google path materializes InteractionDataResult before this process hook
                        // observes its constructor.
                        if (bridgeSelectionSeen && bridgeSelectionText != null
                                && !bridgeSelectionText.isBlank()) {
                            GoogleLens1758Profile.NativePresentationSuppression suppression =
                                    GoogleLens1758Profile
                                            .suppressNativeRenderedPresentationFromQueryResult(
                                                    queryResult);
                            if (suppression.suppressed()) {
                                report("GOOGLE_NATIVE_PRESENTATION_STRIPPED",
                                        "path=queryResult " + suppression.detail());
                            }
                        }

                        // Parse before invoking Google. In 17.58 the final q(dtqi) already contains
                        // InteractionPresentationResult/LensResultPanelResponse. If FloatLens owns
                        // the session we can consume this final state before Google renders LRP.
                        GoogleLens1758Profile.ResultSnapshot snapshot =
                                GoogleLens1758Profile.result(queryResult);
                        sendBridgeFrame(snapshot.frame());

                        boolean userInteractionSeen = bridgeSelectionSeen;
                        boolean presentationBoundary =
                                GoogleLens1758Profile.isPresentationBoundary(
                                        snapshot.complete(),
                                        snapshot.interactionPresent(),
                                        snapshot.presentationPresent());
                        boolean renderablePayload = bridgeFrameQueued
                                || (bridgeSelectionText != null
                                && !bridgeSelectionText.isBlank());

                        report("LENS_QUERY_RESULT",
                                "complete=" + snapshot.complete()
                                        + " selectionSeen=" + bridgeSelectionSeen
                                        + " pendingSeen=" + bridgePendingSeen
                                        + " interactionSeen=" + userInteractionSeen
                                        + " presentationBoundary=" + presentationBoundary
                                        + " renderablePayload=" + renderablePayload
                                        + " " + snapshot.detail());

                        if (!userInteractionSeen) {
                            report("LENS_QUERY_PRESELECTION",
                                    "result allowed until USER_SELECTION");
                            return chain.proceed();
                        }

                        if (presentationBoundary && renderablePayload) {
                            report("LENS_PRESENTATION_BOUNDARY",
                                    "Google LRP ready; handing selection to FloatLens");

                            boolean textMenuSelection = bridgeSelectionText != null
                                    && !bridgeSelectionText.isBlank();
                            boolean consumed = textMenuSelection
                                    ? suppressBridgeTextPresentation(snapshot.detail())
                                    : commitBridgeResult(snapshot.text(), snapshot.detail(),
                                    "lens_presentation_intercept");

                            if (consumed) {
                                module.log(Log.INFO, TAG,
                                        textMenuSelection
                                                ? "Google Lens result panel suppressed; selection layer kept alive"
                                                : "Google Lens result-panel presentation suppressed");
                                // dscu.q(dtqi) is void in the validated 17.58 profile. For text
                                // selections, skip only the result-panel callback: do not finish
                                // LensientActivity and do not clear the marked session. Google
                                // keeps its highlight/selection handles alive, and later y(dscl)
                                // callbacks can continue updating the FloatLens menu.
                                return null;
                            }
                        }

                        return chain.proceed();
                    });
                    count++;
                }
            }
            module.log(Log.INFO, TAG,
                    "Google " + GoogleLens1758Profile.NAME
                            + " Lens selection hooks=" + count);
            return count;
        } catch (Throwable t) {
            module.log(Log.WARN, TAG,
                    "Google " + GoogleLens1758Profile.NAME
                            + " Lens selection boundary unavailable", t);
            return 0;
        }
    }

    private synchronized boolean suppressBridgeTextPresentation(String detail) {
        if (!active() || !bridgeSelectionSeen
                || bridgeSelectionText == null || bridgeSelectionText.isBlank()) {
            return false;
        }
        report("LENS_TEXT_PRESENTATION_SUPPRESSED",
                "keepSelectionAlive=true textLen=" + bridgeSelectionText.length()
                        + " bounds=" + String.valueOf(bridgeSelectionBounds)
                        + " detail=" + safe(detail));
        return true;
    }

    private synchronized boolean commitBridgeResult(String text, String detail, String reason) {
        if (!active() || bridgeCommitted
                || (!bridgeSelectionSeen && !bridgePendingSeen)) return false;

        // Never close Google's marked Lens UI unless FloatLens actually has something it can
        // display. v155 showed non-null dtqi placeholders with frame/text/LensResult all null;
        // treating those as a settled result closed the UI and delivered nothing.
        String finalText = bridgeSelectionText == null || bridgeSelectionText.isBlank()
                ? text : bridgeSelectionText;
        if ((finalText == null || finalText.isBlank()) && !bridgeFrameQueued) {
            report("LENS_QUERY_NO_PAYLOAD",
                    "keep Google UI open reason=" + reason
                            + " detail=" + safe(detail));
            return false;
        }

        bridgeCommitted = true;

        // Make the final event self-contained. Explicit broadcasts are asynchronous; carrying the
        // latest selection again prevents a query-result delivery from racing ahead of the earlier
        // selection event in the FloatLens process.
        Rect finalBounds = bridgeSelectionBounds == null
                ? null : new Rect(bridgeSelectionBounds);
        sendBridgeEvent(GoogleCtsContract.EVENT_QUERY_RESULT,
                finalText, detail, finalBounds);
        finishMarkedGoogleActivity(reason);
        clear(reason);
        return true;
    }

    private Rect selectionBoundsForDisplay(
            GoogleLens1758Profile.SelectionSnapshot selection) {
        if (selection == null) return null;
        Rect direct = selection.bounds();
        if (direct != null) return direct;
        try {
            Context context = currentApplicationContext();
            if (context == null) return null;
            android.util.DisplayMetrics metrics =
                    context.getResources().getDisplayMetrics();
            return selection.boundsForFrame(metrics.widthPixels, metrics.heightPixels);
        } catch (Throwable t) {
            return null;
        }
    }

    private void rememberMarkedActivity(Object value) {
        if (!(value instanceof Activity activity)) return;
        String name = activity.getClass().getName();
        Activity current = markedActivity.get();
        if (name.endsWith(".LensientActivity") || current == null || current.isFinishing()) {
            markedActivity = new WeakReference<>(activity);
            report("GOOGLE_UI_OWNER", "activity=" + name);
        }
    }

    private void finishActivity(Activity activity, String reason) {
        if (activity == null) return;
        String name = activity.getClass().getName();
        module.log(Log.INFO, TAG,
                "finish activity=" + name + " reason=" + reason);
        activity.runOnUiThread(() -> {
            try {
                if (!activity.isFinishing() && !activity.isDestroyed()) {
                    activity.finish();
                    activity.overridePendingTransition(0, 0);
                }
            } catch (Throwable t) {
                module.log(Log.WARN, TAG,
                        "Failed to finish Google contextual search activity", t);
            }
        });
    }

    private void finishMarkedGoogleActivity(String reason) {
        Activity activity = markedActivity.get();
        if (activity == null) {
            report("GOOGLE_UI_FINISH", "activity=none reason=" + reason);
            return;
        }
        String name = activity.getClass().getName();
        report("GOOGLE_UI_FINISH", "activity=" + name + " reason=" + reason);
        activity.runOnUiThread(() -> {
            try {
                if (!activity.isFinishing() && !activity.isDestroyed()) {
                    activity.finish();
                    activity.overridePendingTransition(0, 0);
                }
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, "Failed to finish marked Google Lens activity", t);
            }
        });
    }

    private void correlateGoogleBoundary(Bundle extras, Intent intent, String path) {
        if (!provider.isActive()) return;
        if (GoogleCtsContract.isFloatLensSession(extras)) {
            activate(extras.getString(GoogleCtsContract.K_SESSION_TOKEN, ""),
                    showSessionId, voiceSession, path + "_MARKER");
            return;
        }
        if (active()) return;
        String token = provider.googleCtsArmedToken(extras);
        if (!token.isBlank()) {
            activate(token, showSessionId, voiceSession, path + "_ARMED");
            report("BOUNDARY_CORRELATION",
                    "marker=false extras=" + safeKeys(extras)
                            + " intent=" + describeIntent(intent));
        }
    }

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
                        String armedToken = provider.googleCtsArmedToken(args);
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
                        String armedToken = provider.googleCtsArmedToken(extras);
                        if (!armedToken.isBlank()) {
                            activate(armedToken, -1, null, "CONTEXTUAL_ACTIVITY_ARMED_FALLBACK");
                            report("SESSION_SHOW", "path=ContextualActivityArmedFallback marker=false activity="
                                    + (activity == null ? "null" : activity.getClass().getName())
                                    + " intent=" + describeIntent(intent));
                            if (activity != null) dumpClassStructure(activity.getClass(), "activityFallback");
                        }
                    } else if (active() && intent != null) {
                        report("ACTIVITY_LIFECYCLE", name
                                + " activityClass="
                                + (activity == null ? "null" : activity.getClass().getName())
                                + " " + describeIntent(intent));
                    }

                    boolean contextualBoundary = active() && intent != null
                            && GoogleCtsContract.isContextualSearchAction(intent.getAction());
                    boolean contextualFrame = false;
                    boolean contextualPayload = false;
                    String contextualDetail = "";
                    if (contextualBoundary) {
                        contextualFrame = captureContextualSearchFrame(intent);
                        boolean hasText = bridgeSelectionText != null
                                && !bridgeSelectionText.isBlank();
                        contextualPayload = hasText || bridgeFrameQueued;
                        contextualDetail = "source=activity_lifecycle"
                                + " activityClass="
                                + (activity == null ? "null" : activity.getClass().getName())
                                + " selectionSeen=" + bridgeSelectionSeen
                                + " textLen="
                                + (bridgeSelectionText == null ? 0 : bridgeSelectionText.length())
                                + " bounds=" + String.valueOf(bridgeSelectionBounds)
                                + " frameQueued=" + contextualFrame
                                + " extras=" + safeKeys(extras);
                        report("CONTEXTUAL_SEARCH_BOUNDARY", contextualDetail);
                    }

                    // Never replace the remembered selection Activity with a later Contextual
                    // Search Activity. The original LensientActivity owns Google's highlight and
                    // resize handles and must remain alive for FloatLens text-menu sessions.
                    if (active() && !contextualBoundary) rememberMarkedActivity(activity);
                    Object result = chain.proceed();

                    // Some Android builds launch Contextual Search through the framework service,
                    // bypassing this process' execStartActivity(). Text selections already have a
                    // live FloatLens menu, so close only this search Activity: do not commit the
                    // bridge, do not clear the marked session, and do not finish the underlying
                    // selection Activity. Region/image flows keep the previous result fallback.
                    if (contextualBoundary && contextualPayload && active()) {
                        boolean hasText = bridgeSelectionText != null
                                && !bridgeSelectionText.isBlank();
                        if (hasText && activity instanceof Activity contextualActivity) {
                            report("CONTEXTUAL_TEXT_SEARCH_SUPPRESSED",
                                    "path=activity_lifecycle keepSelectionAlive=true textLen="
                                            + bridgeSelectionText.length());
                            finishActivity(contextualActivity,
                                    "contextual_text_search_suppressed");
                            module.log(Log.INFO, TAG,
                                    "Contextual text search activity suppressed; selection kept alive");
                        } else {
                            boolean consumed = commitBridgeResult("", contextualDetail,
                                    "contextual_search_activity_intercept");
                            if (consumed && activity instanceof Activity contextualActivity) {
                                finishActivity(contextualActivity,
                                        "contextual_search_activity_intercept");
                                module.log(Log.INFO, TAG,
                                        "Contextual search activity consumed by FloatLens");
                            }
                        }
                    } else if (contextualBoundary && !contextualPayload && active()) {
                        report("CONTEXTUAL_SEARCH_PASSTHROUGH",
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
        if (!active() || intent == null
                || !GoogleCtsContract.isContextualSearchAction(intent.getAction())) {
            return false;
        }
        Bundle extras = intent.getExtras();
        if (extras == null || !extras.containsKey(GoogleCtsContract.CONTEXTUAL_SCREENSHOT)) {
            report("CONTEXTUAL_SCREENSHOT", "missing");
            return false;
        }
        Object value;
        try {
            value = extras.get(GoogleCtsContract.CONTEXTUAL_SCREENSHOT);
        } catch (Throwable t) {
            report("CONTEXTUAL_SCREENSHOT",
                    "read failed=" + t.getClass().getSimpleName());
            return false;
        }
        if (value instanceof Bitmap bitmap && !bitmap.isRecycled()) {
            report("CONTEXTUAL_SCREENSHOT",
                    "bitmap=" + bitmapSummary(bitmap));
            sendBridgeFrame(bitmap);
            return bridgeFrameQueued;
        }
        report("CONTEXTUAL_SCREENSHOT",
                "valueClass=" + (value == null ? "null" : value.getClass().getName())
                        + " value=" + describeValue(value));
        return false;
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
                    if (!active()) return chain.proceed();

                    Intent intent = (Intent) chain.getArg(idx);
                    report("START_ACTIVITY", describeIntent(intent)
                            + " caller=" + googleCaller());

                    if (intent == null
                            || !GoogleCtsContract.isContextualSearchAction(
                                    intent.getAction())) {
                        return chain.proceed();
                    }

                    boolean frameQueued = captureContextualSearchFrame(intent);
                    boolean hasText = bridgeSelectionText != null
                            && !bridgeSelectionText.isBlank();
                    boolean hasRenderablePayload = hasText || bridgeFrameQueued;

                    String detail = "action=" + intent.getAction()
                            + " selectionSeen=" + bridgeSelectionSeen
                            + " textLen="
                            + (bridgeSelectionText == null ? 0 : bridgeSelectionText.length())
                            + " bounds=" + String.valueOf(bridgeSelectionBounds)
                            + " frameQueued=" + frameQueued
                            + " extras=" + safeKeys(intent.getExtras());

                    report("CONTEXTUAL_SEARCH_BOUNDARY", detail);

                    // Suppress only when FloatLens can actually render something. This keeps the
                    // Google flow untouched if a future Google build changes the screenshot or
                    // selection payload shape.
                    if (!hasRenderablePayload) {
                        report("CONTEXTUAL_SEARCH_PASSTHROUGH",
                                "no renderable FloatLens payload; Google search allowed");
                        return chain.proceed();
                    }

                    if (hasText) {
                        report("CONTEXTUAL_TEXT_SEARCH_SUPPRESSED",
                                "path=execStartActivity keepSelectionAlive=true textLen="
                                        + bridgeSelectionText.length());
                        module.log(Log.INFO, TAG,
                                "Contextual text search launch suppressed; selection kept alive");
                        // Do not commit/clear a live text-menu session. Returning null prevents
                        // the search Activity from launching while Google's original selection UI
                        // and resize handles stay active underneath FloatLens.
                        return null;
                    }

                    report("CONTEXTUAL_SEARCH_TAKEOVER",
                            "renderable payload ready; suppressing marked Google search");
                    boolean consumed = commitBridgeResult(
                            "", detail, "contextual_search_intercept");
                    if (!consumed) {
                        report("CONTEXTUAL_SEARCH_PASSTHROUGH",
                                "bridge commit rejected; Google search allowed");
                        return chain.proceed();
                    }

                    module.log(Log.INFO, TAG,
                            "Contextual search launch suppressed for FloatLens session");
                    // Instrumentation.execStartActivity normally returns null for a successful
                    // external launch, so null is also the safest synthetic result when we consume
                    // this marked launch.
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
            bridgeSelectionSeen = false;
            bridgePendingSeen = false;
            bridgeSelectionText = "";
            bridgeSelectionBounds = null;
            markedActivity = new WeakReference<>(null);
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
        bridgeSelectionSeen = false;
        bridgePendingSeen = false;
        bridgeSelectionText = "";
        bridgeSelectionBounds = null;
        markedActivity = new WeakReference<>(null);
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
