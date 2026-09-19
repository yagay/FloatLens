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
import android.view.ViewParent;
import android.view.WindowManager;
import android.widget.TextView;

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
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedModule;

/**
 * Runtime inspector for the Google side of Circle to Search.
 *
 * <p>Nothing is activated for normal Home/gesture CTS sessions. A session becomes active only
 * after a FloatLens marker is observed in either the VIS show Bundle or the Omnient Activity
 * launch Intent. The inspector then traces the whole marked session in one run.</p>
 */
final class GoogleCtsRuntimeInspector implements GoogleCtsLifecycleHooks.Host {
    private static final String TAG = "FloatLens-GoogleCTS";
    private static final long SESSION_TTL_MS = 120_000L;
    private static final long RESULT_HANDOFF_TIMEOUT_MS = 2_500L;
    private static final long RESULT_HANDOFF_POLL_MS = 16L;
    private static final int MAX_EVENT_LOGS = 500;

    private final XposedModule module;
    private final LsposedRuntimeProvider provider;
    private final ClassLoader classLoader;
    private final AtomicInteger eventCount = new AtomicInteger();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final GoogleHookCapabilityMatrix capabilities;

    private final GoogleCtsSessionState sessionState = new GoogleCtsSessionState();
    private final GoogleSessionTimeline timeline = new GoogleSessionTimeline();
    private volatile Object voiceSession;
    private volatile WeakReference<Activity> markedActivity = new WeakReference<>(null);
    private final GoogleLensUiSanitizer uiSanitizer;
    private final GoogleBridgeSender bridgeSender;
    private final GoogleLensDiagnosticsHooks diagnosticsHooks;
    private final GoogleLensViewportHook viewportHook;
    private final GoogleCanonicalFrameLayer canonicalFrameLayer;
    private final GoogleCtsLifecycleHooks lifecycleHooks;
    private final GoogleLensFrameCapture frameCapture;
    private final GoogleRegionGestureHook regionGestureHook;
    private String regionConfirmDetail = "";

    GoogleCtsRuntimeInspector(XposedModule module,
                              LsposedRuntimeProvider provider,
                              ClassLoader classLoader) {
        this.module = module;
        this.provider = provider;
        this.classLoader = classLoader;
        this.capabilities = GoogleHookCapabilityMatrix.resolve(classLoader);
        this.uiSanitizer = new GoogleLensUiSanitizer(
                () -> active() && sessionState.selectionSeen(),
                this::report);
        this.bridgeSender = new GoogleBridgeSender(
                module, this::currentApplicationContext, sessionState::token,
                this::active, provider::diagnosticsEnabled);
        this.diagnosticsHooks = new GoogleLensDiagnosticsHooks(
                module, classLoader, provider::diagnosticsEnabled, this::active,
                sessionState::selectionSeen, () -> markedActivity.get(),
                this::currentSelectionBounds,
                this::report);
        this.viewportHook = new GoogleLensViewportHook(
                module, classLoader, this::active, sessionState::selectionSeen,
                sessionState::regionSelectionActive,
                () -> markedActivity.get(), this::report);
        this.canonicalFrameLayer = new GoogleCanonicalFrameLayer(
                this::active, () -> markedActivity.get(), this::report);
        this.lifecycleHooks = new GoogleCtsLifecycleHooks(module, provider, this);
        this.frameCapture = new GoogleLensFrameCapture(
                module, classLoader, this::active, this::sendBridgeFrame, this::report);
        this.regionGestureHook = new GoogleRegionGestureHook(
                module, classLoader, this::active,
                (adjusting, detail) -> {
                    sessionState.onRegionGesture(adjusting);
                    report(adjusting ? "GOOGLE_REGION_GESTURE_START"
                                    : "GOOGLE_REGION_GESTURE_END",
                            detail);
                    sendBridgeEvent(adjusting
                                    ? GoogleCtsContract.EVENT_REGION_GESTURE_START
                                    : GoogleCtsContract.EVENT_REGION_GESTURE_END,
                            "", detail, currentSelectionBounds());
                },
                (action, x, y, detail) ->
                        canonicalFrameLayer.onGesturePoint(action, x, y));
        provider.setObserver((token, confirmedAtElapsed) ->
                mainHandler.post(() -> onGoogleRegionConfirm(token, confirmedAtElapsed)));
    }

    void install() {
        int hooks = 0;
        hooks += hookGoogle1758OmnientBoundary();
        // Stable boundaries own invocation/lifecycle/UI. Only two Google-internal hook groups are
        // installed now: Selection (OCR/text/region/query bridge) and Viewport (prevent text-focus
        // auto zoom). Legacy presentation/ActionMenu/InfoPanel/dujo hooks remain in source for
        // diagnostics/rollback but are intentionally not installed.
        hooks += viewportHook.install(capabilities.viewport);
        hooks += frameCapture.install(capabilities.frame);
        hooks += regionGestureHook.install(capabilities.regionGesture);
        hooks += hookGoogleLensSelectionBoundary();
        // v169 device/APK analysis proved the visible menu is Lens' own ActionMenuView,
        // not framework/Material FloatingToolbar. WindowManager inspection is diagnostic-only.
        if (provider.diagnosticsEnabled()) {
            hooks += diagnosticsHooks.installWindowInspector();
        } else {
            module.log(Log.INFO, TAG,
                    "Google diagnostic WindowManager hook skipped (diagnostics disabled)");
        }
        hooks += lifecycleHooks.install();
        module.log(Log.INFO, TAG,
                "Google CTS inspector ready hooks=" + hooks
                        + " profile=" + GoogleLens1758Profile.NAME
                        + " capabilities={" + capabilities.detail() + "}");
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

                if (p.length == 3 && p[1] == Bundle.class) {
                    module.hook(method).intercept(chain -> {
                        Bundle args = (Bundle) chain.getArg(1);
                        correlateGoogleBoundary(args, null, "OMNIENT_VIS");
                        if (active()) {
                            report("OMNIENT_VIS_ENTRY", "keys=" + GoogleHookFormatting.safeKeys(args)
                                    + " owner=" + chain.getThisObject().getClass().getName());
                        }
                        return chain.proceed();
                    });
                    count++;
                    continue;
                }

                if (p.length == 3 && p[1] == Intent.class) {
                    module.hook(method).intercept(chain -> {
                        Intent intent = (Intent) chain.getArg(1);
                        correlateGoogleBoundary(intent == null ? null : intent.getExtras(),
                                intent, "OMNIENT_CONTEXTUAL");
                        if (active()) report("OMNIENT_CONTEXTUAL_ENTRY", GoogleHookFormatting.describeIntent(intent));
                        return chain.proceed();
                    });
                    count++;
                    continue;
                }

                if (p.length == 3
                        && p[1] == Bitmap.class && p[2] == Intent.class
                        && method.getReturnType() == Intent.class) {
                    module.hook(method).intercept(chain -> {
                        Bitmap bitmap = (Bitmap) chain.getArg(1);
                        Intent source = (Intent) chain.getArg(2);
                        if (active()) {
                            report("OMNIENT_BUILD_VIS_INTENT",
                                    "bitmap=" + GoogleHookFormatting.bitmapSummary(bitmap)
                                            + " source=" + GoogleHookFormatting.describeIntent(source));
                            sendBridgeFrame(bitmap);
                        }
                        Object result = chain.proceed();
                        if (active() && result instanceof Intent out) {
                            report("OMNIENT_VIS_INTENT_READY", GoogleHookFormatting.describeIntent(out));
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
    // Retired 17.58 presentation/ActionMenu/InfoPanel/Chrome hooks were removed from the active
    // source after the selection-only architecture stabilized. Git history remains the rollback
    // source; runtime behavior now depends only on selection, viewport and optional diagnostics.

    /** Resolve the exact 17.58 profile or structural fallback behind one hook contract. */
    private int hookGoogleLensSelectionBoundary() {
        GoogleSelectionAdapter.Binding binding = capabilities.selection;
        if (!binding.available()) {
            module.log(Log.WARN, TAG,
                    "Google Lens selection binding unavailable: " + binding.detail());
            return 0;
        }

        module.log(Log.INFO, TAG,
                "Google Lens selection binding source=" + binding.source()
                        + " confidence=" + binding.confidence()
                        + " detail=" + binding.detail());
        try {
            module.hook(binding.method()).intercept(chain -> {
                GoogleSelectionAdapter.Snapshot selection = null;
                Rect selectionBounds = null;
                if (active()) {
                    selection = binding.snapshot(chain.getArg(0), currentApplicationContext());
                    selectionBounds = selection.bounds();
                    boolean regionSelection = selection.directRegionCommit()
                            && selectionBounds != null && !selectionBounds.isEmpty();
                    sessionState.onSelection(
                            selection.text(), toSessionBounds(selectionBounds), regionSelection);
                    canonicalFrameLayer.updateSelection(
                            selectionBounds, regionSelection, selection.text());
                    Rect effectiveBounds = currentSelectionBounds();
                    String detail = selection.detail()
                            + " source=" + binding.source()
                            + " pixelBounds=" + String.valueOf(selectionBounds)
                            + " effectiveBounds=" + String.valueOf(effectiveBounds)
                            + " phase=" + sessionState.phase()
                            + " generation=" + sessionState.generation()
                            + " primary=" + chain.getArg(1);
                    if (regionSelection) regionConfirmDetail = selection.detail();
                    report("USER_SELECTION_" + binding.source().toUpperCase(Locale.ROOT), detail);
                    sendBridgeEvent(regionSelection
                                    ? GoogleCtsContract.EVENT_REGION_SELECTION
                                    : GoogleCtsContract.EVENT_SELECTION,
                            selection.text(), detail, effectiveBounds);
                }

                Object result = chain.proceed();

                if (active() && sessionState.selectionSeen()) uiSanitizer.sanitizeNow();

                if (active() && sessionState.selectionSeen()
                        && !sessionState.selectionText().isBlank()) {
                    uiSanitizer.sanitizeNow();
                    diagnosticsHooks.inspectSelectionViewsSoon();
                }
                return result;
            });
            return 1;
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "Google Lens selection hook install failed", t);
            return 0;
        }
    }



    private void onGoogleRegionConfirm(String token, long confirmedAtElapsed) {
        if (token == null || token.isBlank() || !active() || !sessionState.canConfirm(token)) {
            return;
        }
        report("LENS_REGION_SELECTION_CONFIRMED",
                "bounds=" + String.valueOf(currentSelectionBounds())
                        + " confirmedAtElapsed=" + confirmedAtElapsed
                        + " transport=remote_preferences_event");
        commitBridgeResult("", regionConfirmDetail, "lens_region_confirmed");
    }

    @Override public synchronized boolean commitBridgeResult(String text, String detail, String reason) {
        String currentToken = sessionState.token();
        if (!active()
                || sessionState.phase() == GoogleCtsSessionState.Phase.COMMITTED
                || (!sessionState.selectionSeen() && !sessionState.frameQueued()
                && !bridgeSender.frameQueued())) {
            return false;
        }

        // Pixel data can be captured in a different Google process (for example :interactor
        // via eggn.onHandleScreenshot) and already be queued in the FloatLens app while this Lens
        // process owns the final region selection. For an explicitly confirmed region, the current
        // Google process only needs to deliver the final Rect; app-side state joins it with the
        // previously received frozen frame.
        Rect finalBounds = currentSelectionBounds();
        boolean regionMetadataCommit = sessionState.regionSelectionActive()
                && finalBounds != null && !finalBounds.isEmpty()
                && "lens_region_confirmed".equals(reason);

        // Keep the old no-payload safety rule for text/general query results. Only the explicit
        // region-confirm path may commit metadata without a frame in this process.
        String selectedText = sessionState.selectionText();
        String finalText = selectedText.isBlank() ? text : selectedText;
        if ((finalText == null || finalText.isBlank())
                && !bridgeSender.frameQueued()
                && !regionMetadataCommit) {
            report("LENS_QUERY_NO_PAYLOAD",
                    "keep Google UI open reason=" + reason
                            + " detail=" + GoogleHookFormatting.safe(detail));
            return false;
        }
        if (regionMetadataCommit && !bridgeSender.frameQueued()) {
            report("LENS_REGION_METADATA_COMMIT",
                    "crossProcessFrame=true bounds=" + finalBounds
                            + " reason=" + reason);
        }

        if (!sessionState.markCommitted(currentToken)) return false;

        // Make the final event self-contained. Explicit broadcasts are asynchronous; carrying the
        // latest selection again prevents a query-result delivery from racing ahead of the earlier
        // selection event in the FloatLens process.
        String committedToken = currentToken;
        long committedGeneration = sessionState.generation();
        sendBridgeEvent(GoogleCtsContract.EVENT_QUERY_RESULT,
                finalText, detail, finalBounds);
        // Do not finish LensientActivity here. ResultActivity is translucent and its DialogFragment
        // needs ~50-120ms before its first visible frame. Finishing Google immediately exposes the
        // underlying app/launcher for one frame, which looks like a flash. Keep Google's frozen
        // image alive until the FloatLens app clears the remote token from its first-frame callback.
        awaitFloatLensResultHandoff(committedToken, committedGeneration, reason,
                SystemClock.elapsedRealtime());
        return true;
    }

    private void awaitFloatLensResultHandoff(
            String token, long generation, String reason, long startedAtElapsed) {
        if (token == null || token.isBlank()) return;
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (!sessionState.matches(generation, token)
                        || sessionState.phase() != GoogleCtsSessionState.Phase.COMMITTED) return;

                long elapsed = Math.max(0L,
                        SystemClock.elapsedRealtime() - startedAtElapsed);
                boolean stillOwned = provider.ownsGoogleCtsSession(token);
                if (stillOwned && elapsed < RESULT_HANDOFF_TIMEOUT_MS) {
                    // Google remains the stable frozen-image backdrop while FloatLens' translucent
                    // result host is being created. Keep its own chrome suppressed during the gap.
                    if (active()) uiSanitizer.sanitizeNow();
                    mainHandler.postDelayed(this, RESULT_HANDOFF_POLL_MS);
                    return;
                }

                String stage = stillOwned ? "timeout" : "result_first_frame";
                Activity activity = markedActivity.get();
                String activityName = activity == null ? "none"
                        : activity.getClass().getName();
                String line = "GOOGLE_UI_FINISH_AFTER_HANDOFF session="
                        + GoogleHookFormatting.shortToken(token)
                        + " activity=" + activityName
                        + " stage=" + stage
                        + " elapsedMs=" + elapsed
                        + " reason=" + reason;
                sendTrace(line);
                module.log(Log.INFO, TAG, line);

                if (stillOwned) {
                    // The app-side first-frame callback failed or never arrived. Tell FloatLens to
                    // tear down its remote lease before closing Google so a native CTS session is
                    // never left contaminated by this failed marked session.
                    sendBridgeEvent(GoogleCtsContract.EVENT_END, "",
                            "result_handoff_timeout", null);
                }
                if (activity != null) finishActivity(activity,
                        reason + "_" + stage);
                clear(reason + "_" + stage);
            }
        });
    }

    @Override public void rememberMarkedActivity(Object value) {
        if (!(value instanceof Activity activity)) return;
        String name = activity.getClass().getName();
        Activity current = markedActivity.get();
        if (name.endsWith(".LensientActivity") || current == null || current.isFinishing()) {
            markedActivity = new WeakReference<>(activity);
            uiSanitizer.attach(activity);
            canonicalFrameLayer.onActivityAvailable();
            report("GOOGLE_UI_OWNER", "activity=" + name
                    + " sanitizer=attached canonicalLayer=armed");
        }
    }

    @Override public void finishActivity(Activity activity, String reason) {
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

    private void correlateGoogleBoundary(Bundle extras, Intent intent, String path) {
        if (!provider.isActive()) return;
        if (GoogleCtsContract.isFloatLensSession(extras)) {
            activate(extras.getString(GoogleCtsContract.K_SESSION_TOKEN, ""),
                    sessionState.showSessionId(), voiceSession, path + "_MARKER");
            return;
        }
        if (active()) return;
        String token = provider.googleCtsArmedToken(extras);
        if (!token.isBlank()) {
            activate(token, sessionState.showSessionId(), voiceSession, path + "_ARMED");
            report("BOUNDARY_CORRELATION",
                    "marker=false extras=" + GoogleHookFormatting.safeKeys(extras)
                            + " intent=" + GoogleHookFormatting.describeIntent(intent));
        }
    }



    private Object fieldByTypeName(Object target, String typeName) {
        if (target == null || typeName == null) return null;
        for (Class<?> current = target.getClass();
             current != null; current = current.getSuperclass()) {
            try {
                for (Field field : current.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())
                            || !typeName.equals(field.getType().getName())) continue;
                    try {
                        field.setAccessible(true);
                        return field.get(target);
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            for (Field field : HiddenApiBypass.getInstanceFields(target.getClass())) {
                if (!typeName.equals(field.getType().getName())) continue;
                try {
                    field.setAccessible(true);
                    return field.get(target);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }







    @Override public synchronized void activate(String token, int id, Object session, String path) {
        String nextToken = token == null ? "" : token;
        long now = SystemClock.elapsedRealtime();
        String currentToken = sessionState.token();
        boolean newBridgeSession = !nextToken.equals(currentToken) || !sessionState.active(now);

        if (newBridgeSession) {
            uiSanitizer.detach();
            bridgeSender.reset();
            regionGestureHook.reset();
            viewportHook.reset();
            canonicalFrameLayer.reset();
            markedActivity = new WeakReference<>(null);
            regionConfirmDetail = "";
            sessionState.begin(nextToken, id, now + SESSION_TTL_MS);
            eventCount.set(0);
            timeline.reset(sessionState.generation(), nextToken);
        } else {
            sessionState.extend(now + SESSION_TTL_MS);
            sessionState.setShowSessionId(id);
        }
        if (session != null) voiceSession = session;

        String activeToken = sessionState.token();
        String header = "=== Google CTS marked session ===\n"
                + "ACTIVE path=" + path
                + " session=" + GoogleHookFormatting.shortToken(activeToken)
                + " generation=" + sessionState.generation()
                + " phase=" + sessionState.phase()
                + " showId=" + sessionState.showSessionId()
                + " atElapsed=" + now;
        sendTrace("=== Google CTS marked session ===");
        sendTrace("ACTIVE path=" + path
                + " session=" + GoogleHookFormatting.shortToken(activeToken)
                + " generation=" + sessionState.generation()
                + " phase=" + sessionState.phase()
                + " showId=" + sessionState.showSessionId()
                + " atElapsed=" + now);
        module.log(Log.INFO, TAG, header.replace("\n", " | "));
    }

    @Override public synchronized void clear(String reason) {
        String token = sessionState.token();
        boolean committed = sessionState.phase() == GoogleCtsSessionState.Phase.COMMITTED;
        String end = "END session=" + GoogleHookFormatting.shortToken(token)
                + " generation=" + sessionState.generation()
                + " phase=" + sessionState.phase()
                + " reason=" + reason + " events=" + eventCount.get();
        if (!committed && !token.isBlank()) {
            sendBridgeEvent(GoogleCtsContract.EVENT_END, "", reason, null);
        }
        String timelineSummary = "GOOGLE_SESSION_TIMELINE " + timeline.summary();
        sendTrace(timelineSummary);
        module.log(Log.INFO, TAG, timelineSummary);
        sendTrace(end);
        module.log(Log.INFO, TAG, end);
        sessionState.finish();
        voiceSession = null;
        bridgeSender.reset();
        regionGestureHook.reset();
        viewportHook.reset();
        canonicalFrameLayer.reset();
        regionConfirmDetail = "";
        uiSanitizer.detach();
        markedActivity = new WeakReference<>(null);
    }

    @Override public int showSessionId() { return sessionState.showSessionId(); }
    @Override public Object voiceSession() { return voiceSession; }
    @Override public boolean selectionSeen() { return sessionState.selectionSeen(); }
    @Override public String selectionText() { return sessionState.selectionText(); }
    @Override public Rect selectionBounds() { return currentSelectionBounds(); }
    @Override public boolean frameQueued() { return bridgeSender.frameQueued(); }

    @Override public boolean active() {
        String token = sessionState.token();
        return provider.isActive()
                && sessionState.active(SystemClock.elapsedRealtime())
                && provider.ownsGoogleCtsSession(token);
    }

    @Override public void report(String event, String message) {
        if (!provider.diagnosticsEnabled() || !active()) return;
        int n = reserveEventNumber();
        if (n < 0) return;
        String safeMessage = GoogleHookFormatting.safe(message);
        timeline.record(n, event, sessionState.phase(), safeMessage);
        String line = "#" + n + " " + event + " session="
                + GoogleHookFormatting.shortToken(sessionState.token())
                + " generation=" + sessionState.generation()
                + " phase=" + sessionState.phase()
                + " " + safeMessage;
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
        bridgeSender.sendTrace(line);
    }

    private void sendBridgeEvent(String event, String text, String detail, Rect bounds) {
        bridgeSender.sendEvent(event, text, detail, bounds);
    }

    @Override public void sendBridgeFrame(Bitmap bitmap) {
        // Keep a private immutable visual source in the Lens process before the bridge performs
        // any cross-process transport. Google may later mutate FrozenImageView's internal viewport,
        // but the pixels presented by FloatLens remain tied to this canonical frame.
        canonicalFrameLayer.offer(bitmap);
        if (bridgeSender.sendFrame(bitmap)) {
            sessionState.onFrameQueued();
        }
    }

    @Override public void captureFrameFromIntent(Intent intent) {
        frameCapture.captureFromIntent(intent);
    }

    private Rect currentSelectionBounds() {
        GoogleCtsSessionState.Bounds bounds = sessionState.selectionBounds();
        if (bounds == null || !bounds.valid()) return null;
        return new Rect(bounds.left, bounds.top, bounds.right, bounds.bottom);
    }

    private static GoogleCtsSessionState.Bounds toSessionBounds(Rect bounds) {
        if (bounds == null || bounds.isEmpty()) return null;
        return new GoogleCtsSessionState.Bounds(
                bounds.left, bounds.top, bounds.right, bounds.bottom);
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


















}
