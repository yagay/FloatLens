package com.yagay.floatlens.hook;

import android.app.Activity;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Reuses Google Lens' own RegionView/EffectsV2 rendering for FloatLens text selections.
 *
 * <p>This adapter never changes Google's text-selection model. It only feeds the current text
 * bounds into RegionView's visual peer after Google's selection callback has completed, so the
 * shell is rendered by the same native code used for region/image selection: RegionView owns the
 * scrim and rounded region frame, while EffectsV2View owns the RuntimeShader effects.</p>
 */
final class GoogleNativeRegionVisualAdapter {
    private static final long[] RETRY_MS = {0L, 16L, 64L, 160L};
    private static final int REGION_ANIMATION_NONE = 1;
    private static final int AUTO_SELECTION_NONE = 1;

    private final ClassLoader classLoader;
    private final Supplier<Activity> activity;
    private final BiConsumer<String, String> reporter;
    private final Handler main = new Handler(Looper.getMainLooper());

    private long revision;
    private boolean syntheticTextRegion;

    GoogleNativeRegionVisualAdapter(
            ClassLoader classLoader,
            Supplier<Activity> activity,
            BiConsumer<String, String> reporter) {
        this.classLoader = classLoader;
        this.activity = activity;
        this.reporter = reporter;
    }

    void showTextSelection(Rect screenBounds) {
        if (screenBounds == null || screenBounds.isEmpty()) return;
        final Rect requested = new Rect(screenBounds);
        final long requestRevision = ++revision;
        syntheticTextRegion = true;
        for (int i = 0; i < RETRY_MS.length; i++) {
            final int attempt = i;
            Runnable apply = () -> {
                if (requestRevision != revision || !syntheticTextRegion) return;
                if (applyOnMain(requested, attempt)) {
                    // Cancel later retries without changing the semantic state.
                    revision++;
                }
            };
            long delay = RETRY_MS[i];
            if (delay == 0L) main.post(apply);
            else main.postDelayed(apply, delay);
        }
    }

    void onNativeRegionSelection() {
        revision++;
        syntheticTextRegion = false;
        reporter.accept("GOOGLE_NATIVE_REGION_VISUAL",
                "mode=native_region owner=google");
    }

    void cancelTextVisual(String reason) {
        if (!syntheticTextRegion) return;
        revision++;
        syntheticTextRegion = false;
        reporter.accept("GOOGLE_NATIVE_REGION_VISUAL",
                "mode=text_cancel reason=" + safe(reason));
    }

    void reset() {
        revision++;
        syntheticTextRegion = false;
    }

    private boolean applyOnMain(Rect screenBounds, int attempt) {
        Activity owner = activity.get();
        if (owner == null || owner.isFinishing() || owner.isDestroyed()
                || owner.getWindow() == null) {
            return false;
        }

        View root = owner.getWindow().getDecorView();
        View regionView = GoogleLensViewIntrospection.findByClassName(
                root, GoogleLens1758Profile.REGION_VIEW);
        if (regionView == null || regionView.getWidth() <= 0 || regionView.getHeight() <= 0) {
            if (attempt == RETRY_MS.length - 1) {
                reporter.accept("GOOGLE_NATIVE_REGION_VISUAL",
                        "text_region_unavailable reason=region_view_missing");
            }
            return false;
        }

        RectF normalized = normalizeToView(regionView, screenBounds);
        if (normalized == null || normalized.isEmpty()) {
            reporter.accept("GOOGLE_NATIVE_REGION_VISUAL",
                    "text_region_unavailable reason=invalid_geometry"
                            + " screen=" + screenBounds);
            return true;
        }

        try {
            Object peer = GoogleReflection.invokeNoArg(regionView, "a");
            if (peer == null
                    || !GoogleLens1758Profile.REGION_VIEW_PEER.equals(
                    peer.getClass().getName())) {
                reporter.accept("GOOGLE_NATIVE_REGION_VISUAL",
                        "text_region_unavailable reason=peer_missing"
                                + " peer=" + (peer == null ? "null"
                                : peer.getClass().getName()));
                return false;
            }

            Class<?> regionClass = Class.forName(
                    GoogleLens1758Profile.REGION_MODEL, false, classLoader);
            Constructor<?> constructor = regionClass.getDeclaredConstructor(
                    RectF.class, int.class, PointF.class, int.class);
            constructor.setAccessible(true);
            Object region = constructor.newInstance(
                    normalized,
                    REGION_ANIMATION_NONE,
                    null,
                    AUTO_SELECTION_NONE);

            Method update = findRegionUpdate(peer.getClass(), regionClass);
            if (update == null) {
                reporter.accept("GOOGLE_NATIVE_REGION_VISUAL",
                        "text_region_unavailable reason=peer_update_missing");
                return true;
            }
            update.setAccessible(true);
            update.invoke(peer, region);
            regionView.invalidate();

            View effects = GoogleLensViewIntrospection.findByClassName(
                    root, GoogleLens1758Profile.EFFECTS_V2_VIEW);
            String effectsName = "EffectsV2View";
            if (effects == null) {
                effects = GoogleLensViewIntrospection.findByClassName(
                        root, GoogleLens1758Profile.NON_SHADER_EFFECTS_VIEW);
                effectsName = effects == null ? "none" : "NonShaderEffectsView";
            }
            if (effects != null) effects.invalidate();

            reporter.accept("GOOGLE_NATIVE_REGION_VISUAL",
                    "mode=text nativeRenderer=RegionView+" + effectsName
                            + " screen=" + screenBounds
                            + " normalized=" + normalized
                            + " animation=NONE autoSelection=NONE");
            return true;
        } catch (Throwable error) {
            reporter.accept("GOOGLE_NATIVE_REGION_VISUAL",
                    "text_region_failed attempt=" + attempt
                            + " error=" + error.getClass().getSimpleName()
                            + ":" + safe(error.getMessage()));
            return attempt == RETRY_MS.length - 1;
        }
    }

    private static Method findRegionUpdate(Class<?> peerClass, Class<?> regionClass) {
        for (Method method : GoogleReflection.methodsInHierarchy(peerClass)) {
            if (!"h".equals(method.getName())
                    || method.getParameterCount() != 1
                    || method.getReturnType() != void.class) {
                continue;
            }
            Class<?> parameter = method.getParameterTypes()[0];
            if (parameter == regionClass
                    || GoogleLens1758Profile.REGION_MODEL.equals(parameter.getName())) {
                return method;
            }
        }
        return null;
    }

    private static RectF normalizeToView(View view, Rect screenBounds) {
        if (view == null || screenBounds == null || screenBounds.isEmpty()
                || view.getWidth() <= 0 || view.getHeight() <= 0) {
            return null;
        }
        int[] origin = new int[2];
        try {
            view.getLocationOnScreen(origin);
        } catch (Throwable ignored) {
            return null;
        }

        float width = view.getWidth();
        float height = view.getHeight();
        float left = clamp((screenBounds.left - origin[0]) / width);
        float top = clamp((screenBounds.top - origin[1]) / height);
        float right = clamp((screenBounds.right - origin[0]) / width);
        float bottom = clamp((screenBounds.bottom - origin[1]) / height);
        if (right <= left || bottom <= top) return null;
        return new RectF(left, top, right, bottom);
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static String safe(String value) {
        if (value == null) return "";
        return value.replace("\n", " ").replace("\r", " ");
    }
}
