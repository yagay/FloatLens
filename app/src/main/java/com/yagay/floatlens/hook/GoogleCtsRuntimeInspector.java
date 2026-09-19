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

    private volatile long activeUntil;
    private volatile String sessionToken = "";
    private volatile int showSessionId = -1;
    private volatile Object voiceSession;
    private volatile boolean bridgeCommitted;
    private volatile boolean bridgeSelectionSeen;
    private volatile boolean bridgeRegionSelectionActive;
    private volatile boolean bridgePendingSeen;
    private volatile String bridgeSelectionText = "";
    private volatile Rect bridgeSelectionBounds;
    private volatile boolean presentationAlreadyAbsentReported;
    private volatile WeakReference<Activity> markedActivity = new WeakReference<>(null);
    private final GoogleLensUiSanitizer uiSanitizer;
    private final GoogleBridgeSender bridgeSender;
    private final GoogleLensDiagnosticsHooks diagnosticsHooks;
    private final GoogleLensViewportHook viewportHook;
    private final GoogleCanonicalFrameLayer canonicalFrameLayer;
    private final GoogleCtsLifecycleHooks lifecycleHooks;
    private final GoogleLensFrameCapture frameCapture;
    private final GoogleRegionGestureHook regionGestureHook;
    private Runnable regionConfirmPollTask;
    private String regionConfirmDetail = "";

    GoogleCtsRuntimeInspector(XposedModule module,
                              LsposedRuntimeProvider provider,
                              ClassLoader classLoader) {
        this.module = module;
        this.provider = provider;
        this.classLoader = classLoader;
        this.uiSanitizer = new GoogleLensUiSanitizer(
                () -> active() && bridgeSelectionSeen,
                this::report);
        this.bridgeSender = new GoogleBridgeSender(
                module, this::currentApplicationContext, () -> sessionToken,
                this::active, provider::diagnosticsEnabled);
        this.diagnosticsHooks = new GoogleLensDiagnosticsHooks(
                module, classLoader, provider::diagnosticsEnabled, this::active,
                () -> bridgeSelectionSeen, () -> markedActivity.get(),
                () -> bridgeSelectionBounds == null ? null : new Rect(bridgeSelectionBounds),
                this::report);
        this.viewportHook = new GoogleLensViewportHook(
                module, classLoader, this::active, () -> bridgeSelectionSeen,
                () -> bridgeRegionSelectionActive,
                () -> markedActivity.get(), this::report);
        this.canonicalFrameLayer = new GoogleCanonicalFrameLayer(
                this::active, () -> markedActivity.get(), this::report);
        this.lifecycleHooks = new GoogleCtsLifecycleHooks(module, provider, this);
        this.frameCapture = new GoogleLensFrameCapture(
                module, classLoader, this::active, this::sendBridgeFrame, this::report);
        this.regionGestureHook = new GoogleRegionGestureHook(
                module, classLoader, this::active,
                (adjusting, detail) -> {
                    report(adjusting ? "GOOGLE_REGION_GESTURE_START"
                                    : "GOOGLE_REGION_GESTURE_END",
                            detail);
                    sendBridgeEvent(adjusting
                                    ? GoogleCtsContract.EVENT_REGION_GESTURE_START
                                    : GoogleCtsContract.EVENT_REGION_GESTURE_END,
                            "", detail, bridgeSelectionBounds);
                });
    }

    void install() {
        int hooks = 0;
        hooks += hookGoogle1758OmnientBoundary();
        // Stable boundaries own invocation/lifecycle/UI. Only two Google-internal hook groups are
        // installed now: Selection (OCR/text/region/query bridge) and Viewport (prevent text-focus
        // auto zoom). Legacy presentation/ActionMenu/InfoPanel/dujo hooks remain in source for
        // diagnostics/rollback but are intentionally not installed.
        hooks += viewportHook.install();
        hooks += frameCapture.install();
        hooks += regionGestureHook.install();
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
        GoogleSelectionAdapter.Binding binding = GoogleSelectionAdapter.resolve(classLoader);
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
                    bridgeSelectionSeen = true;
                    bridgeSelectionText = selection.text();
                    if (selectionBounds != null && !selectionBounds.isEmpty()) {
                        bridgeSelectionBounds = selectionBounds;
                    }
                    Rect effectiveBounds = bridgeSelectionBounds == null
                            ? null : new Rect(bridgeSelectionBounds);
                    String detail = selection.detail()
                            + " source=" + binding.source()
                            + " pixelBounds=" + String.valueOf(selectionBounds)
                            + " effectiveBounds=" + String.valueOf(effectiveBounds)
                            + " primary=" + chain.getArg(1);
                    boolean regionSelection = selection.directRegionCommit()
                            && selectionBounds != null && !selectionBounds.isEmpty();
                    bridgeRegionSelectionActive = regionSelection;
                    report("USER_SELECTION_" + binding.source().toUpperCase(Locale.ROOT), detail);
                    sendBridgeEvent(regionSelection
                                    ? GoogleCtsContract.EVENT_REGION_SELECTION
                                    : GoogleCtsContract.EVENT_SELECTION,
                            selection.text(), detail, effectiveBounds);
                    if (regionSelection) {
                        armRegionConfirmWait(selection.detail());
                    }
                }

                Object result = chain.proceed();

                if (active() && bridgeSelectionSeen) uiSanitizer.sanitizeNow();

                if (active() && bridgeSelectionSeen
                        && bridgeSelectionText != null
                        && !bridgeSelectionText.isBlank()) {
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



    private synchronized void armRegionConfirmWait(String detail) {
        if (!active() || bridgeCommitted || sessionToken.isBlank()) return;
        regionConfirmDetail = detail == null ? "" : detail;
        if (regionConfirmPollTask != null) return;

        String token = sessionToken;
        Runnable[] holder = new Runnable[1];
        holder[0] = new Runnable() {
            @Override public void run() {
                synchronized (GoogleCtsRuntimeInspector.this) {
                    if (regionConfirmPollTask != this
                            || !token.equals(sessionToken)
                            || bridgeCommitted) {
                        if (regionConfirmPollTask == this) regionConfirmPollTask = null;
                        return;
                    }
                }
                if (!active()) {
                    synchronized (GoogleCtsRuntimeInspector.this) {
                        if (regionConfirmPollTask == this) regionConfirmPollTask = null;
                    }
                    return;
                }
                if (provider.googleRegionConfirmRequested(token)) {
                    String confirmedDetail;
                    synchronized (GoogleCtsRuntimeInspector.this) {
                        if (regionConfirmPollTask == this) regionConfirmPollTask = null;
                        confirmedDetail = regionConfirmDetail;
                    }
                    report("LENS_REGION_SELECTION_CONFIRMED",
                            "bounds=" + String.valueOf(bridgeSelectionBounds));
                    commitBridgeResult("", confirmedDetail, "lens_region_confirmed");
                    return;
                }
                mainHandler.postDelayed(this, 24L);
            }
        };
        regionConfirmPollTask = holder[0];
        mainHandler.post(holder[0]);
    }

    private synchronized void stopRegionConfirmWait() {
        Runnable pending = regionConfirmPollTask;
        regionConfirmPollTask = null;
        regionConfirmDetail = "";
        if (pending != null) mainHandler.removeCallbacks(pending);
    }

    @Override public synchronized boolean commitBridgeResult(String text, String detail, String reason) {
        if (!active() || bridgeCommitted
                || (!bridgeSelectionSeen && !bridgePendingSeen && !bridgeSender.frameQueued())) {
            return false;
        }

        // Pixel data can be captured in a different Google process (for example :interactor
        // via eggn.onHandleScreenshot) and already be queued in the FloatLens app while this Lens
        // process owns the final region selection. For an explicitly confirmed region, the current
        // Google process only needs to deliver the final Rect; app-side state joins it with the
        // previously received frozen frame.
        Rect finalBounds = bridgeSelectionBounds == null
                ? null : new Rect(bridgeSelectionBounds);
        boolean regionMetadataCommit = bridgeRegionSelectionActive
                && finalBounds != null && !finalBounds.isEmpty()
                && "lens_region_confirmed".equals(reason);

        // Keep the old no-payload safety rule for text/general query results. Only the explicit
        // region-confirm path may commit metadata without a frame in this process.
        String finalText = bridgeSelectionText == null || bridgeSelectionText.isBlank()
                ? text : bridgeSelectionText;
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

        bridgeCommitted = true;

        // Make the final event self-contained. Explicit broadcasts are asynchronous; carrying the
        // latest selection again prevents a query-result delivery from racing ahead of the earlier
        // selection event in the FloatLens process.
        String committedToken = sessionToken;
        sendBridgeEvent(GoogleCtsContract.EVENT_QUERY_RESULT,
                finalText, detail, finalBounds);
        // Do not finish LensientActivity here. ResultActivity is translucent and its DialogFragment
        // needs ~50-120ms before its first visible frame. Finishing Google immediately exposes the
        // underlying app/launcher for one frame, which looks like a flash. Keep Google's frozen
        // image alive until the FloatLens app clears the remote token from its first-frame callback.
        awaitFloatLensResultHandoff(committedToken, reason,
                SystemClock.elapsedRealtime());
        return true;
    }

    private void awaitFloatLensResultHandoff(
            String token, String reason, long startedAtElapsed) {
        if (token == null || token.isBlank()) return;
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (!token.equals(sessionToken) || !bridgeCommitted) return;

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
                    showSessionId, voiceSession, path + "_MARKER");
            return;
        }
        if (active()) return;
        String token = provider.googleCtsArmedToken(extras);
        if (!token.isBlank()) {
            activate(token, showSessionId, voiceSession, path + "_ARMED");
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
        boolean newBridgeSession = !nextToken.equals(sessionToken)
                || SystemClock.elapsedRealtime() >= activeUntil;
        activeUntil = SystemClock.elapsedRealtime() + SESSION_TTL_MS;
        sessionToken = nextToken;
        if (newBridgeSession) {
            stopRegionConfirmWait();
            uiSanitizer.detach();
            bridgeSender.reset();
            regionGestureHook.reset();
            viewportHook.reset();
            canonicalFrameLayer.reset();
            bridgeCommitted = false;
            bridgeSelectionSeen = false;
            bridgeRegionSelectionActive = false;
            bridgePendingSeen = false;
            bridgeSelectionText = "";
            bridgeSelectionBounds = null;
            presentationAlreadyAbsentReported = false;
            markedActivity = new WeakReference<>(null);
        }
        if (id >= 0) showSessionId = id;
        if (session != null) voiceSession = session;
        eventCount.set(0);
        String header = "=== Google CTS marked session ===\n"
                + "ACTIVE path=" + path
                + " session=" + GoogleHookFormatting.shortToken(sessionToken)
                + " showId=" + showSessionId
                + " atElapsed=" + SystemClock.elapsedRealtime();
        sendTrace("=== Google CTS marked session ===");
        sendTrace("ACTIVE path=" + path
                + " session=" + GoogleHookFormatting.shortToken(sessionToken)
                + " showId=" + showSessionId
                + " atElapsed=" + SystemClock.elapsedRealtime());
        module.log(Log.INFO, TAG, header.replace("\n", " | "));
    }

    @Override public synchronized void clear(String reason) {
        String end = "END session=" + GoogleHookFormatting.shortToken(sessionToken)
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
        bridgeSender.reset();
        regionGestureHook.reset();
        viewportHook.reset();
        canonicalFrameLayer.reset();
        bridgeCommitted = false;
        bridgeSelectionSeen = false;
        bridgeRegionSelectionActive = false;
        bridgePendingSeen = false;
        bridgeSelectionText = "";
        bridgeSelectionBounds = null;
        presentationAlreadyAbsentReported = false;
        stopRegionConfirmWait();
        uiSanitizer.detach();
        markedActivity = new WeakReference<>(null);
    }

    @Override public int showSessionId() { return showSessionId; }
    @Override public Object voiceSession() { return voiceSession; }
    @Override public boolean selectionSeen() { return bridgeSelectionSeen; }
    @Override public String selectionText() { return bridgeSelectionText; }
    @Override public Rect selectionBounds() {
        return bridgeSelectionBounds == null ? null : new Rect(bridgeSelectionBounds);
    }
    @Override public boolean frameQueued() { return bridgeSender.frameQueued(); }

    @Override public boolean active() {
        return provider.isActive()
                && !sessionToken.isBlank()
                && provider.ownsGoogleCtsSession(sessionToken)
                && SystemClock.elapsedRealtime() < activeUntil;
    }

    @Override public void report(String event, String message) {
        if (!provider.diagnosticsEnabled() || !active()) return;
        int n = reserveEventNumber();
        if (n < 0) return;
        String line = "#" + n + " " + event + " session="
                + GoogleHookFormatting.shortToken(sessionToken) + " " + GoogleHookFormatting.safe(message);
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
        bridgeSender.sendFrame(bitmap);
    }

    @Override public void captureFrameFromIntent(Intent intent) {
        frameCapture.captureFromIntent(intent);
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
