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
final class GoogleCtsRuntimeInspector {
    private static final String TAG = "FloatLens-GoogleCTS";
    private static final String SHOW_SESSION_ID = "android.service.voice.SHOW_SESSION_ID";
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
    private volatile boolean bridgePendingSeen;
    private volatile String bridgeSelectionText = "";
    private volatile Rect bridgeSelectionBounds;
    private volatile boolean presentationAlreadyAbsentReported;
    private volatile WeakReference<Activity> markedActivity = new WeakReference<>(null);
    private final GoogleLensUiSanitizer uiSanitizer;
    private final GoogleBridgeSender bridgeSender;

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
    }

    void install() {
        int hooks = 0;
        hooks += hookGoogle1758OmnientBoundary();
        // Stable boundaries own invocation/lifecycle/UI. Only two Google-internal hook groups are
        // installed now: Selection (OCR/text/region/query bridge) and Viewport (prevent text-focus
        // auto zoom). Legacy presentation/ActionMenu/InfoPanel/dujo hooks remain in source for
        // diagnostics/rollback but are intentionally not installed.
        hooks += hookGoogleFrozenImageAutoFocus();
        hooks += hookGoogleLensSelectionBoundary();
        // v169 device/APK analysis proved the visible menu is Lens' own ActionMenuView,
        // not framework/Material FloatingToolbar. WindowManager inspection is diagnostic-only.
        if (provider.diagnosticsEnabled()) {
            hooks += hookGoogleWindowInspector();
        } else {
            module.log(Log.INFO, TAG,
                    "Google diagnostic WindowManager hook skipped (diagnostics disabled)");
        }
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

                if (p.length == 3 && p[1] == Bundle.class) {
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

                if (p.length == 3 && p[1] == Intent.class) {
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

                if (p.length == 3
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
                    if (!active() || !bridgeSelectionSeen) {
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
     * Google 17.58 eseu is LensInteractionResult. Its six-argument constructor stores argument 3
     * (index 2) directly into field c == presentationResult. For FloatLens-owned selections,
     * replace that Optional with Google's canonical absent singleton before the value object exists.
     */
    private int hookGoogleInteractionPresentationResult() {
        try {
            Class<?> resultClass = Class.forName(
                    GoogleLens1758Profile.INTERACTION_RESULT, false, classLoader);
            Object absent = GoogleLens1758Profile.absentOptional(classLoader);
            if (absent == null) {
                module.log(Log.WARN, TAG,
                        "Google presentation-result absent Optional unavailable");
                return 0;
            }

            int count = 0;
            for (Executable constructor : resultClass.getDeclaredConstructors()) {
                Class<?>[] params = constructor.getParameterTypes();
                if (params.length != 6
                        || !GoogleLens1758Profile.hasTypeInHierarchy(
                                params[2], GoogleLens1758Profile.OPTIONAL)) {
                    continue;
                }
                module.hook(constructor).intercept(chain -> {
                    if (!active() || !bridgeSelectionSeen) return chain.proceed();

                    Object before = chain.getArg(2);
                    if (before == absent) {
                        Object result = chain.proceed();
                        if (!presentationAlreadyAbsentReported) {
                            presentationAlreadyAbsentReported = true;
                            report("GOOGLE_PRESENTATION_RESULT_ALREADY_ABSENT",
                                    "class=eseu ctorArg=2 value="
                                            + absent.getClass().getName());
                        }
                        return result;
                    }

                    Object[] args = chain.getArgs().toArray();
                    args[2] = absent;
                    Object result = chain.proceed(args);
                    report("GOOGLE_PRESENTATION_RESULT_STRIPPED",
                            "class=eseu ctorArg=2 before="
                                    + (before == null ? "null" : before.getClass().getName())
                                    + " after=" + absent.getClass().getName());
                    return result;
                });
                count++;
            }
            module.log(Log.INFO, TAG,
                    "Google LensInteractionResult presentation hooks=" + count);
            return count;
        } catch (Throwable t) {
            module.log(Log.WARN, TAG,
                    "Google LensInteractionResult presentation boundary unavailable", t);
            return 0;
        }
    }

    /**
     * Google 17.58 classes8.dex:
     * dscu.q(dtqi) reaches dokz.f(), and dokz.f() reaches dokz.g(dnqv). APK call-site analysis
     * also shows independent callers entering g(dnqv), so suppress both the menu layout wrapper
     * and the action-population method for FloatLens-owned text sessions. Google's OCR, selection
     * highlight and DRAG_TEXT_HANDLE live outside this controller and continue normally.
     */
    private int hookGoogleLensActionMenuController() {
        try {
            Class<?> controller = Class.forName(
                    GoogleLens1758Profile.ACTION_MENU_CONTROLLER, false, classLoader);
            int count = 0;
            for (Executable executable : HiddenApiBypass.getDeclaredMethods(controller)) {
                if (!(executable instanceof Method method)) continue;

                if ("a".equals(method.getName())
                        && method.getParameterCount() == 0
                        && GoogleLens1758Profile.ACTION_MENU_VIEW
                                .equals(method.getReturnType().getName())) {
                    module.hook(method).intercept(chain -> {
                        Object result = chain.proceed();
                        if (active() && bridgeSelectionSeen && result instanceof View view
                                && hideGoogleShellView(view)) {
                            report("GOOGLE_ACTION_MENU_ROOT_SUPPRESSED",
                                    "controller=dokz.a visibility="
                                            + visibilityName(view.getVisibility()));
                        }
                        return result;
                    });
                    count++;
                    continue;
                }

                if (GoogleLens1758Profile.isActionMenuLayoutMethod(method)) {
                    module.hook(method).intercept(chain -> {
                        if (!active() || !GoogleLens1758Profile.shouldSuppressPostSelectionResult(
                                bridgeSelectionSeen, bridgeSelectionText)) {
                            return chain.proceed();
                        }

                        report("GOOGLE_ACTION_MENU_LAYOUT_SUPPRESSED",
                                "controller=dokz.f textLen=" + bridgeSelectionText.length()
                                        + " bounds=" + String.valueOf(bridgeSelectionBounds));
                        // f() lays out/refreshes Lens ActionMenuView and normally reaches g(dnqv).
                        // Keep this as a fail-safe, but do not rely on it alone: 17.58 also has
                        // independent callers that enter g(dnqv) without going through f().
                        return null;
                    });
                    count++;
                    continue;
                }

                if (GoogleLens1758Profile.isActionMenuPopulationMethod(method)) {
                    module.hook(method).intercept(chain -> {
                        if (!active() || !GoogleLens1758Profile.shouldSuppressPostSelectionResult(
                                bridgeSelectionSeen, bridgeSelectionText)) {
                            return chain.proceed();
                        }

                        report("GOOGLE_ACTION_MENU_POPULATION_SUPPRESSED",
                                "controller=dokz.g arg="
                                        + (chain.getArg(0) == null
                                        ? "null" : chain.getArg(0).getClass().getName())
                                        + " textLen=" + bridgeSelectionText.length()
                                        + " bounds=" + String.valueOf(bridgeSelectionBounds));
                        // g(dnqv) is the action-population path. APK analysis shows it is called
                        // not only by f(), but also from independent async/synthetic callbacks.
                        // Blocking it prevents Copy/Translate/overflow from being repopulated.
                        return null;
                    });
                    count++;
                }
            }
            module.log(Log.INFO, TAG,
                    "Google Lens ActionMenu controller hooks=" + count);
            return count;
        } catch (Throwable t) {
            module.log(Log.WARN, TAG,
                    "Google Lens ActionMenu controller unavailable", t);
            return 0;
        }
    }

    /**
     * Google 17.58 classes8.dex:
     * dqsi.e == HIDDEN and dqqt.z(dqsi,int) directly drives InfoPanelView/
     * LensResultPanelBottomsheetBehavior. For every FloatLens-owned selection, immediately hide
     * the actual InfoPanelView and also rewrite state to HIDDEN so WebX/SearchBox never flashes.
     */
    private int hookGoogleLensInfoPanelController() {
        try {
            Class<?> controller = Class.forName(
                    GoogleLens1758Profile.INFO_PANEL_CONTROLLER, false, classLoader);
            Class<?> panelState = Class.forName("dqsi", false, classLoader);
            Field hiddenField = panelState.getDeclaredField("e");
            hiddenField.setAccessible(true);
            Object hiddenState = hiddenField.get(null);
            if (hiddenState == null) {
                module.log(Log.WARN, TAG,
                        "Google Lens InfoPanel HIDDEN state unavailable");
                return 0;
            }

            int count = 0;
            for (Executable executable : HiddenApiBypass.getDeclaredMethods(controller)) {
                if (!(executable instanceof Method method)) continue;
                Class<?>[] params = method.getParameterTypes();
                if (!"z".equals(method.getName())
                        || params.length != 2
                        || params[0] != panelState
                        || params[1] != int.class
                        || method.getReturnType() != void.class) {
                    continue;
                }

                module.hook(method).intercept(chain -> {
                    if (!active() || !bridgeSelectionSeen) {
                        return chain.proceed();
                    }

                    // HIDDEN alone animates the panel below the screen and leaves WebX visible for
                    // ~80-220ms. Hide the actual InfoPanelView immediately, then also feed HIDDEN
                    // into Google's state machine so its internal bottom-sheet state stays sane.
                    int hiddenBefore = forceGoogleInfoPanelGone(chain.getThisObject());
                    Object requested = chain.getArg(0);
                    Object[] args = chain.getArgs().toArray();
                    args[0] = hiddenState;
                    Object result = chain.proceed(args);
                    int hiddenAfter = forceGoogleInfoPanelGone(chain.getThisObject());
                    report("GOOGLE_INFO_PANEL_SUPPRESSED",
                            "controller=dqqt.z requested="
                                    + (requested == null ? "null" : String.valueOf(requested))
                                    + " forced=HIDDEN goneBefore=" + hiddenBefore
                                    + " goneAfter=" + hiddenAfter
                                    + " textLen="
                                    + (bridgeSelectionText == null ? 0
                                    : bridgeSelectionText.length()));
                    return result;
                });
                count++;
            }
            try {
                Class<?> owner = Class.forName(
                        GoogleLens1758Profile.INFO_PANEL_OWNER, false, classLoader);
                for (Executable executable : HiddenApiBypass.getDeclaredMethods(owner)) {
                    if (!(executable instanceof Method method)) continue;
                    if (!"d".equals(method.getName())
                            || method.getParameterCount() != 0
                            || !GoogleLens1758Profile.INFO_PANEL_VIEW
                                    .equals(method.getReturnType().getName())) {
                        continue;
                    }
                    module.hook(method).intercept(chain -> {
                        Object result = chain.proceed();
                        if (active() && bridgeSelectionSeen && result instanceof View view
                                && hideGoogleShellView(view)) {
                            report("GOOGLE_INFO_PANEL_OWNER_SUPPRESSED",
                                    "owner=dqpx.d visibility="
                                            + visibilityName(view.getVisibility()));
                        }
                        return result;
                    });
                    count++;
                }
            } catch (Throwable t) {
                module.log(Log.WARN, TAG,
                        "Google Lens InfoPanel owner dqpx unavailable", t);
            }

            module.log(Log.INFO, TAG,
                    "Google Lens InfoPanel controller/owner hooks=" + count);
            return count;
        } catch (Throwable t) {
            module.log(Log.WARN, TAG,
                    "Google Lens InfoPanel controller unavailable", t);
            return 0;
        }
    }

    private int forceGoogleInfoPanelGone(Object controller) {
        Object panel = fieldByName(controller, "b");
        if (!(panel instanceof View)
                || !GoogleLens1758Profile.INFO_PANEL_VIEW.equals(panel.getClass().getName())) {
            panel = fieldByTypeName(controller, GoogleLens1758Profile.INFO_PANEL_VIEW);
        }
        if (!(panel instanceof View view)) return 0;
        try {
            return hideGoogleShellView(view) ? 1 : 0;
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "Failed to hide Google InfoPanelView", t);
            return 0;
        }
    }

    private boolean hideGoogleShellView(View view) {
        if (view == null) return false;
        boolean changed = view.getVisibility() != View.GONE
                || view.getAlpha() != 0f
                || view.isClickable();
        if (view.getVisibility() != View.GONE) view.setVisibility(View.GONE);
        if (view.getAlpha() != 0f) view.setAlpha(0f);
        if (view.isClickable()) view.setClickable(false);
        return changed;
    }

    /**
     * v173 diagnostics show WebX/InfoPanel is gone, while Google 17.58's independent bottom
     * OmniBoxView and four top Lens chrome controls can remain visible. Block only those exact
     * views from becoming VISIBLE during a FloatLens-owned selection. Selection overlay/handles
     * use different classes/resources and are intentionally untouched.
     */
    private int hookGoogleLensChromeVisibility() {
        try {
            Method setVisibility = View.class.getDeclaredMethod("setVisibility", int.class);
            module.hook(setVisibility).intercept(chain -> {
                if (!active() || !bridgeSelectionSeen
                        || !(chain.getThisObject() instanceof View view)) {
                    return chain.proceed();
                }
                int requested = (Integer) chain.getArg(0);
                if (requested != View.VISIBLE || !isGoogleLensChromeView(view)) {
                    return chain.proceed();
                }

                boolean changed = hideGoogleShellView(view);
                if (changed) {
                    report("GOOGLE_CHROME_VISIBILITY_BLOCKED",
                            "target=" + googleLensChromeLabel(view)
                                    + " requested=VISIBLE forced=GONE");
                }
                return null;
            });
            module.log(Log.INFO, TAG, "Google Lens chrome visibility hook=1");
            return 1;
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "Google Lens chrome visibility hook unavailable", t);
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

    private void hideGoogleLensShellViewsSoon() {
        Activity activity = markedActivity.get();
        if (activity == null) return;
        Runnable hide = () -> {
            if (!active() || !bridgeSelectionSeen) return;
            try {
                View root = activity.getWindow() == null
                        ? null : activity.getWindow().getDecorView();
                int hidden = hideGoogleLensShellViews(root);
                if (hidden > 0) {
                    report("GOOGLE_LENS_SHELLS_SUPPRESSED",
                            "hidden=" + hidden);
                }
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, "Failed to hide Google Lens shell views", t);
            }
        };
        activity.runOnUiThread(hide);
        mainHandler.postDelayed(() -> activity.runOnUiThread(hide), 24L);
        mainHandler.postDelayed(() -> activity.runOnUiThread(hide), 72L);
        mainHandler.postDelayed(() -> activity.runOnUiThread(hide), 180L);
    }

    private void hideGoogleLensShellViewsNow() {
        Activity activity = markedActivity.get();
        if (activity == null || !active() || !bridgeSelectionSeen) return;
        try {
            View root = activity.getWindow() == null
                    ? null : activity.getWindow().getDecorView();
            int hidden = hideGoogleLensShellViews(root);
            if (hidden > 0) {
                report("GOOGLE_LENS_SHELLS_SUPPRESSED",
                        "path=selectionImmediate hidden=" + hidden);
            }
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "Failed immediate Google Lens shell suppression", t);
        }
    }

    private int hideGoogleLensShellViews(View view) {
        if (view == null) return 0;
        int hidden = 0;
        if (isGoogleLensShellView(view) || isGoogleLensChromeView(view)) {
            if (hideGoogleShellView(view)) hidden++;
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                hidden += hideGoogleLensShellViews(group.getChildAt(i));
            }
        }
        return hidden;
    }

    /**
     * Capture the real Google menu window/view boundary instead of guessing by protobuf type.
     * This is diagnostic-only: it never hides a view. A later version can suppress the exact
     * class/resource once the device log identifies it.
     */
    private int hookGoogleWindowInspector() {
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
                    if (!active() || !bridgeSelectionSeen) return result;

                    Object rawView = chain.getArg(vIdx);
                    Object rawLp = lIdx >= 0 ? chain.getArg(lIdx) : null;
                    if (rawView instanceof View view) {
                        report("GOOGLE_WINDOW_ADD",
                                describeView(view, true)
                                        + " lp=" + describeWindowLayoutParams(rawLp));
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

    private void inspectGoogleSelectionViewsSoon() {
        if (!provider.diagnosticsEnabled()) return;
        Activity activity = markedActivity.get();
        if (activity == null) return;
        final long[] delays = {0L, 80L, 220L, 500L};
        for (long delay : delays) {
            Runnable scan = () -> {
                if (!active() || !bridgeSelectionSeen) return;
                try {
                    View root = activity.getWindow() == null
                            ? null : activity.getWindow().getDecorView();
                    if (root == null) return;
                    StringBuilder out = new StringBuilder();
                    int[] count = {0};
                    collectInterestingViews(root, 0, out, count);
                    report("GOOGLE_VIEW_SNAPSHOT",
                            "delayMs=" + delay
                                    + " activity=" + activity.getClass().getName()
                                    + " selectedBounds=" + String.valueOf(bridgeSelectionBounds)
                                    + " nodes=" + count[0]
                                    + "\n" + trimViewDump(out.toString(), 6500));
                } catch (Throwable t) {
                    module.log(Log.WARN, TAG, "Google view snapshot failed", t);
                }
            };
            if (delay == 0L) activity.runOnUiThread(scan);
            else mainHandler.postDelayed(() -> activity.runOnUiThread(scan), delay);
        }
    }

    private void collectInterestingViews(View view, int depth,
                                         StringBuilder out, int[] count) {
        if (view == null || count[0] >= 56 || out.length() >= 6200) return;
        if ((view.getVisibility() == View.VISIBLE
                || isGoogleLensShellView(view)
                || isGoogleLensChromeView(view))
                && interestingView(view)) {
            out.append("\n").append(depth).append(":").append(describeView(view, false));
            count[0]++;
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                collectInterestingViews(group.getChildAt(i), depth + 1, out, count);
                if (count[0] >= 56 || out.length() >= 6200) break;
            }
        }
    }

    private boolean isGoogleLensShellView(View view) {
        if (view == null) return false;
        String cls = view.getClass().getName();
        return GoogleLens1758Profile.INFO_PANEL_VIEW.equals(cls)
                || GoogleLens1758Profile.ACTION_MENU_VIEW.equals(cls);
    }

    private boolean isGoogleLensChromeView(View view) {
        if (view == null) return false;
        if (GoogleLens1758Profile.OMNIBOX_VIEW.equals(view.getClass().getName())) return true;
        String id = resourceEntryName(view);
        return id.endsWith(":id/lens_overlay_back_button")
                || id.endsWith(":id/lens_overflow_menu_button")
                || id.endsWith(":id/lens_overlay_history_button")
                || id.endsWith(":id/lens_product_lockup_view");
    }

    private String googleLensChromeLabel(View view) {
        if (view == null) return "null";
        if (GoogleLens1758Profile.OMNIBOX_VIEW.equals(view.getClass().getName())) {
            return "OmniBoxView";
        }
        return resourceEntryName(view);
    }

    private boolean interestingView(View view) {
        if (view != null
                && GoogleLens1758Profile.FROZEN_IMAGE_VIEW
                        .equals(view.getClass().getName())) {
            return true;
        }
        CharSequence text = view instanceof TextView tv ? tv.getText() : null;
        CharSequence desc = view.getContentDescription();
        String cls = view.getClass().getName().toLowerCase(Locale.ROOT);
        return (text != null && !text.toString().isBlank())
                || (desc != null && !desc.toString().isBlank())
                || view.isClickable()
                || cls.contains("menu")
                || cls.contains("toolbar")
                || cls.contains("popup")
                || cls.contains("chip")
                || cls.contains("button")
                || cls.contains("compose");
    }

    private String describeView(View view, boolean includeChildren) {
        if (view == null) return "view=null";
        int[] loc = new int[2];
        try { view.getLocationOnScreen(loc); } catch (Throwable ignored) {}
        String id = resourceEntryName(view);
        String text = "";
        if (view instanceof TextView tv && tv.getText() != null) {
            text = trimViewDump(tv.getText().toString(), 180);
        }
        String desc = view.getContentDescription() == null
                ? "" : trimViewDump(view.getContentDescription().toString(), 180);
        ViewParent parent = view.getParent();
        return "class=" + view.getClass().getName()
                + " id=" + id
                + " visibility=" + visibilityName(view.getVisibility())
                + " shown=" + view.isShown()
                + " text=" + quote(text, 180)
                + " desc=" + quote(desc, 180)
                + " xy=" + loc[0] + "," + loc[1]
                + " wh=" + view.getWidth() + "x" + view.getHeight()
                + " alpha=" + view.getAlpha()
                + " scale=" + view.getScaleX() + "," + view.getScaleY()
                + " translation=" + view.getTranslationX() + "," + view.getTranslationY()
                + " clickable=" + view.isClickable()
                + " enabled=" + view.isEnabled()
                + " parent=" + (parent == null ? "null" : parent.getClass().getName())
                + (includeChildren && view instanceof ViewGroup g
                ? " children=" + g.getChildCount() : "");
    }

    private String describeWindowLayoutParams(Object raw) {
        if (!(raw instanceof WindowManager.LayoutParams lp)) {
            return raw == null ? "null" : raw.getClass().getName();
        }
        CharSequence title = lp.getTitle();
        return "type=" + lp.type
                + " title=" + quote(title == null ? "" : title.toString(), 180)
                + " flags=0x" + Integer.toHexString(lp.flags)
                + " gravity=" + lp.gravity
                + " xy=" + lp.x + "," + lp.y
                + " wh=" + lp.width + "x" + lp.height;
    }

    private String visibilityName(int visibility) {
        return visibility == View.VISIBLE ? "VISIBLE"
                : visibility == View.INVISIBLE ? "INVISIBLE"
                : visibility == View.GONE ? "GONE"
                : String.valueOf(visibility);
    }

    private String resourceEntryName(View view) {
        int id = view == null ? View.NO_ID : view.getId();
        if (id == View.NO_ID || id == 0) return "none";
        try {
            return view.getResources().getResourceName(id);
        } catch (Throwable ignored) {
            return "0x" + Integer.toHexString(id);
        }
    }

    private String trimViewDump(String value, int max) {
        if (value == null) return "";
        String out = value.replace("\n", " ").replace("\r", " ").replace("\u0000", "?");
        return out.length() <= max ? out : out.substring(0, max) + "…";
    }

    /**
     * Google 17.58 classes8.dex: dsfk.Q() builds a TEXT AreaOfInterest and calls duec.r(dudp)
     * before the user-selection callback reaches dscu.y(). Therefore the first focus animation
     * must be rejected from dudp's own source enum rather than waiting for bridgeSelectionSeen.
     * APK mapping: dudp.c=RectF region, dudp.e=int source, source 1=TEXT.
     *
     * duec.n(dsyc) is a second path into duec.ai(). v176 only traces it so we can distinguish
     * independent viewport changes without suppressing unrelated initialization/region behavior.
     */
    private int hookGoogleFrozenImageAutoFocus() {
        try {
            Class<?> controller = Class.forName(
                    GoogleLens1758Profile.VIEWPORT_CONTROLLER, false, classLoader);
            Class<?> requestClass = Class.forName(
                    GoogleLens1758Profile.VIEWPORT_REQUEST, false, classLoader);
            Class<?> stateClass = Class.forName(
                    GoogleLens1758Profile.VIEWPORT_STATE, false, classLoader);
            int count = 0;
            for (Executable executable : HiddenApiBypass.getDeclaredMethods(controller)) {
                if (!(executable instanceof Method method)) continue;
                Class<?>[] params = method.getParameterTypes();

                if ("r".equals(method.getName())
                        && params.length == 1
                        && params[0] == requestClass
                        && method.getReturnType() == void.class) {
                    module.hook(method).intercept(chain -> {
                        Object request = chain.getArg(0);
                        Object rawBounds = fieldByName(request, "c");
                        RectF focusBounds = rawBounds instanceof RectF rect
                                ? new RectF(rect) : null;
                        boolean hasBounds = focusBounds != null
                                && focusBounds.width() > 0f
                                && focusBounds.height() > 0f;
                        Object rawSource = fieldByName(request, "e");
                        int source = rawSource instanceof Integer value ? value : -1;

                        if (!active()
                                || !GoogleLens1758Profile.shouldSuppressTextViewportFocus(
                                        request == null ? "" : request.getClass().getName(),
                                        source,
                                        hasBounds)) {
                            return chain.proceed();
                        }

                        report("GOOGLE_FROZEN_IMAGE_TEXT_FOCUS_SUPPRESSED_EARLY",
                                "controller=duec.r source=" + source
                                        + " selectionSeen=" + bridgeSelectionSeen
                                        + " bounds=" + focusBounds
                                        + " request=" + compactObject(request, 360));
                        reportFrozenImageTransform("beforeEarlyTextFocus");
                        mainHandler.postDelayed(
                                () -> reportFrozenImageTransform("after120ms"), 120L);
                        mainHandler.postDelayed(
                                () -> reportFrozenImageTransform("after300ms"), 300L);
                        return null;
                    });
                    count++;
                    continue;
                }

                if ("n".equals(method.getName())
                        && params.length == 1
                        && params[0] == stateClass
                        && method.getReturnType() == void.class) {
                    module.hook(method).intercept(chain -> {
                        if (!active()) return chain.proceed();

                        Object state = chain.getArg(0);
                        report("GOOGLE_FROZEN_IMAGE_VIEWPORT_STATE_PATH",
                                "controller=duec.n selectionSeen=" + bridgeSelectionSeen
                                        + " state=" + compactObject(state, 420));
                        reportFrozenImageTransform("beforeDuecN");
                        Object result = chain.proceed();
                        mainHandler.postDelayed(
                                () -> reportFrozenImageTransform("afterDuecN120ms"), 120L);
                        return result;
                    });
                    count++;
                }
            }
            module.log(Log.INFO, TAG,
                    "Google FrozenImage viewport hooks=" + count);
            return count;
        } catch (Throwable t) {
            module.log(Log.WARN, TAG,
                    "Google FrozenImage viewport boundary unavailable", t);
            return 0;
        }
    }

    private void reportFrozenImageTransform(String phase) {
        if (!active()) return;
        Activity activity = markedActivity.get();
        if (activity == null) return;
        activity.runOnUiThread(() -> {
            if (!active()) return;
            try {
                View root = activity.getWindow() == null
                        ? null : activity.getWindow().getDecorView();
                View image = findViewByClassName(root, GoogleLens1758Profile.FROZEN_IMAGE_VIEW);
                if (image == null) {
                    report("GOOGLE_FROZEN_IMAGE_TRANSFORM",
                            "phase=" + phase + " view=missing");
                    return;
                }
                int[] loc = new int[2];
                try { image.getLocationOnScreen(loc); } catch (Throwable ignored) {}
                report("GOOGLE_FROZEN_IMAGE_TRANSFORM",
                        "phase=" + phase
                                + " scaleX=" + image.getScaleX()
                                + " scaleY=" + image.getScaleY()
                                + " translationX=" + image.getTranslationX()
                                + " translationY=" + image.getTranslationY()
                                + " xy=" + loc[0] + "," + loc[1]
                                + " wh=" + image.getWidth() + "x" + image.getHeight());
            } catch (Throwable t) {
                module.log(Log.WARN, TAG,
                        "Failed to inspect FrozenImage transform", t);
            }
        });
    }

    private View findViewByClassName(View view, String className) {
        if (view == null || className == null) return null;
        if (className.equals(view.getClass().getName())) return view;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findViewByClassName(group.getChildAt(i), className);
                if (found != null) return found;
            }
        }
        return null;
    }

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
                    report("USER_SELECTION_" + binding.source().toUpperCase(Locale.ROOT), detail);
                    sendBridgeEvent(GoogleCtsContract.EVENT_SELECTION,
                            selection.text(), detail, effectiveBounds);
                }

                boolean directRegionCommit = active()
                        && selection != null
                        && selection.directRegionCommit()
                        && selectionBounds != null
                        && !selectionBounds.isEmpty();

                Object result = chain.proceed();

                if (active() && bridgeSelectionSeen) uiSanitizer.sanitizeNow();

                if (directRegionCommit && active() && !bridgeCommitted) {
                    report("LENS_REGION_SELECTION_COMMIT",
                            "class=" + selection.selectionClass()
                                    + " bounds=" + String.valueOf(bridgeSelectionBounds)
                                    + " source=selection_adapter");
                    commitBridgeResult("", selection.detail(), "lens_region_selection");
                }

                if (active() && bridgeSelectionSeen
                        && bridgeSelectionText != null
                        && !bridgeSelectionText.isBlank()) {
                    uiSanitizer.sanitizeNow();
                    inspectGoogleSelectionViewsSoon();
                }
                return result;
            });
            return 1;
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "Google Lens selection hook install failed", t);
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
                || (!bridgeSelectionSeen && !bridgePendingSeen && !bridgeSender.frameQueued())) {
            return false;
        }

        // Never close Google's marked Lens UI unless FloatLens actually has something it can
        // display. v155 showed non-null dtqi placeholders with frame/text/LensResult all null;
        // treating those as a settled result closed the UI and delivered nothing.
        String finalText = bridgeSelectionText == null || bridgeSelectionText.isBlank()
                ? text : bridgeSelectionText;
        if ((finalText == null || finalText.isBlank()) && !bridgeSender.frameQueued()) {
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
                        + shortToken(token)
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

    private void rememberMarkedActivity(Object value) {
        if (!(value instanceof Activity activity)) return;
        String name = activity.getClass().getName();
        Activity current = markedActivity.get();
        if (name.endsWith(".LensientActivity") || current == null || current.isFinishing()) {
            markedActivity = new WeakReference<>(activity);
            uiSanitizer.attach(activity);
            report("GOOGLE_UI_OWNER", "activity=" + name + " sanitizer=attached");
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

    private Object fieldByName(Object target, String fieldName) {
        if (target == null || fieldName == null) return null;
        for (Class<?> current = target.getClass();
             current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (Throwable ignored) {
            }
        }
        return null;
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
                        contextualPayload = hasText || bridgeSender.frameQueued();
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

                    boolean contextualText = contextualBoundary
                            && bridgeSelectionText != null
                            && !bridgeSelectionText.isBlank();

                    // If Google reuses the original LensientActivity via onNewIntent, do not
                    // deliver the contextual-search intent at all. Finishing that Activity would
                    // destroy the live selection handles that FloatLens intentionally preserves.
                    if (contextualText && "callActivityOnNewIntent".equals(name)) {
                        report("CONTEXTUAL_TEXT_SEARCH_SUPPRESSED",
                                "path=activity_new_intent keepSelectionAlive=true textLen="
                                        + bridgeSelectionText.length());
                        module.log(Log.INFO, TAG,
                                "Contextual text-search newIntent suppressed; selection kept alive");
                        return null;
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
            return bridgeSender.frameQueued();
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
                    if (provider.diagnosticsEnabled()) {
                        report("START_ACTIVITY", describeIntent(intent)
                                + " caller=" + googleCaller());
                    }

                    if (intent == null
                            || !GoogleCtsContract.isContextualSearchAction(
                                    intent.getAction())) {
                        return chain.proceed();
                    }

                    boolean frameQueued = captureContextualSearchFrame(intent);
                    boolean hasText = bridgeSelectionText != null
                            && !bridgeSelectionText.isBlank();
                    boolean hasRenderablePayload = hasText || bridgeSender.frameQueued();

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

    private synchronized void activate(String token, int id, Object session, String path) {
        String nextToken = token == null ? "" : token;
        boolean newBridgeSession = !nextToken.equals(sessionToken)
                || SystemClock.elapsedRealtime() >= activeUntil;
        activeUntil = SystemClock.elapsedRealtime() + SESSION_TTL_MS;
        sessionToken = nextToken;
        if (newBridgeSession) {
            uiSanitizer.detach();
            bridgeSender.reset();
            bridgeCommitted = false;
            bridgeSelectionSeen = false;
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
        bridgeSender.reset();
        bridgeCommitted = false;
        bridgeSelectionSeen = false;
        bridgePendingSeen = false;
        bridgeSelectionText = "";
        bridgeSelectionBounds = null;
        presentationAlreadyAbsentReported = false;
        uiSanitizer.detach();
        markedActivity = new WeakReference<>(null);
    }

    private boolean active() {
        return provider.isActive()
                && !sessionToken.isBlank()
                && provider.ownsGoogleCtsSession(sessionToken)
                && SystemClock.elapsedRealtime() < activeUntil;
    }

    private void report(String event, String message) {
        if (!provider.diagnosticsEnabled() || !active()) return;
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
        bridgeSender.sendTrace(line);
    }

    private void sendBridgeEvent(String event, String text, String detail, Rect bounds) {
        bridgeSender.sendEvent(event, text, detail, bounds);
    }

    private void sendBridgeFrame(Bitmap bitmap) {
        bridgeSender.sendFrame(bitmap);
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
        if (!provider.diagnosticsEnabled() || !active() || cls == null) return;
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
