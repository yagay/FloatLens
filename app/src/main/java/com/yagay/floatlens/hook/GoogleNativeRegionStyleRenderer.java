package com.yagay.floatlens.hook;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Uses Google Lens 17.58's own visual renderers without touching RegionView's selection state.
 *
 * <p>dpoc is the native Aurora/effects painter used by EffectsV2View. We clone its renderer
 * configuration from Google's live EffectsV2View, then give the clone only FloatLens' RectF and
 * Canvas. No Region object is created and no Google gesture/selection state is changed.</p>
 */
final class GoogleNativeRegionStyleRenderer {
    private static final String EFFECTS_PEER = "dpls";
    private static final String AURORA_RENDERER = "dpoc";
    private static final String REGION_PEER = "dtch";
    private static final String SCRIM_RENDERER = "dudd";

    private final ClassLoader classLoader;
    private final Supplier<Activity> activity;
    private final BiConsumer<String, String> reporter;

    private Object donorAurora;
    private Object privateAurora;
    private Method auroraGeometry;
    private Method auroraDraw;

    GoogleNativeRegionStyleRenderer(
            ClassLoader classLoader,
            Supplier<Activity> activity,
            BiConsumer<String, String> reporter) {
        this.classLoader = classLoader;
        this.activity = activity;
        this.reporter = reporter;
    }

    boolean drawAurora(Canvas canvas, View host, RectF localRect, float radius) {
        if (canvas == null || host == null || localRect == null || localRect.isEmpty()) {
            return false;
        }
        try {
            if (!ensureAurora()) return false;

            // dpoc.d(RectF, radius, scale, viewHeight) only prepares visual geometry.
            // It does not create/update a Lens Region selection.
            auroraGeometry.invoke(
                    privateAurora,
                    new RectF(localRect),
                    radius,
                    1.0f,
                    Math.max(1, host.getHeight()));
            auroraDraw.invoke(privateAurora, canvas);
            return true;
        } catch (Throwable t) {
            reporter.accept("GOOGLE_NATIVE_STYLE",
                    "aurora_draw_failed=" + t.getClass().getSimpleName()
                            + ":" + safe(t.getMessage()));
            invalidateAurora();
            return false;
        }
    }

    boolean drawScrim(Canvas canvas, View host, RectF localRect, float radius) {
        Activity owner = activity.get();
        if (owner == null || owner.getWindow() == null
                || canvas == null || host == null || localRect == null || localRect.isEmpty()) {
            return false;
        }
        try {
            View root = owner.getWindow().getDecorView();
            View regionView = GoogleLensViewIntrospection.findByClassName(
                    root, GoogleLens1758Profile.REGION_VIEW);
            if (regionView == null) return false;

            Object peer = GoogleReflection.invokeNoArg(regionView, "a");
            if (peer == null || !REGION_PEER.equals(peer.getClass().getName())) return false;

            Object paintValue = GoogleReflection.readField(
                    peer, "h", Paint.class.getName());
            Object renderer = GoogleReflection.readField(peer, "C", SCRIM_RENDERER);
            if (!(paintValue instanceof Paint donorPaint) || renderer == null) return false;

            Paint paint = new Paint(donorPaint);
            Method draw = null;
            for (Method method : GoogleReflection.methodsInHierarchy(renderer.getClass())) {
                if (!"c".equals(method.getName())
                        || method.getParameterCount() != 6
                        || method.getReturnType() != void.class) continue;
                Class<?>[] p = method.getParameterTypes();
                if (p[0] == Canvas.class
                        && p[1] == Paint.class
                        && p[4] == RectF.class) {
                    draw = method;
                    break;
                }
            }
            if (draw == null) return false;
            draw.setAccessible(true);
            draw.invoke(null,
                    canvas,
                    paint,
                    (float) host.getWidth(),
                    (float) host.getHeight(),
                    new RectF(localRect),
                    radius);
            return true;
        } catch (Throwable t) {
            reporter.accept("GOOGLE_NATIVE_STYLE",
                    "scrim_draw_failed=" + t.getClass().getSimpleName()
                            + ":" + safe(t.getMessage()));
            return false;
        }
    }

    Paint copyNativeHandlePaint() {
        Activity owner = activity.get();
        if (owner == null || owner.getWindow() == null) return null;
        try {
            View root = owner.getWindow().getDecorView();
            View regionView = GoogleLensViewIntrospection.findByClassName(
                    root, GoogleLens1758Profile.REGION_VIEW);
            if (regionView == null) return null;
            Object peer = GoogleReflection.invokeNoArg(regionView, "a");
            if (peer == null || !REGION_PEER.equals(peer.getClass().getName())) return null;
            Object value = GoogleReflection.readField(peer, "g", Paint.class.getName());
            return value instanceof Paint paint ? new Paint(paint) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    void reset() {
        invalidateAurora();
    }

    private boolean ensureAurora() {
        Activity owner = activity.get();
        if (owner == null || owner.getWindow() == null) return false;

        View root = owner.getWindow().getDecorView();
        View effects = GoogleLensViewIntrospection.findByClassName(
                root, GoogleLens1758Profile.EFFECTS_V2_VIEW);
        if (effects == null) return false;

        Object peer = GoogleReflection.invokeNoArg(effects, "a");
        if (peer == null || !EFFECTS_PEER.equals(peer.getClass().getName())) return false;

        Object donor = GoogleReflection.readField(peer, "g", AURORA_RENDERER);
        if (donor == null || !AURORA_RENDERER.equals(donor.getClass().getName())) return false;

        if (donor == donorAurora
                && privateAurora != null
                && auroraGeometry != null
                && auroraDraw != null) {
            copyDynamicVisualState(donor, privateAurora);
            return true;
        }

        Class<?> rendererClass = donor.getClass();
        Object contextWrapper = GoogleReflection.readField(donor, "l", "fpav");
        Object variantValue = GoogleReflection.readField(donor, "m", int.class.getName());
        Object modeNValue = GoogleReflection.readField(donor, "n", boolean.class.getName());
        Object modeOValue = GoogleReflection.readField(donor, "o", boolean.class.getName());
        if (contextWrapper == null
                || !(variantValue instanceof Integer variant)
                || !(modeNValue instanceof Boolean modeN)
                || !(modeOValue instanceof Boolean modeO)) {
            return false;
        }

        Constructor<?> constructor = null;
        for (Constructor<?> candidate : rendererClass.getDeclaredConstructors()) {
            Class<?>[] p = candidate.getParameterTypes();
            if (p.length == 4
                    && "fpav".equals(p[0].getName())
                    && p[1] == int.class
                    && p[2] == boolean.class
                    && p[3] == boolean.class) {
                constructor = candidate;
                break;
            }
        }
        if (constructor == null) return false;
        constructor.setAccessible(true);
        Object clone = constructor.newInstance(contextWrapper, variant, modeN, modeO);

        Method geometry = null;
        Method draw = null;
        for (Method method : GoogleReflection.methodsInHierarchy(rendererClass)) {
            if ("d".equals(method.getName())
                    && method.getParameterCount() == 4
                    && method.getReturnType() == void.class) {
                Class<?>[] p = method.getParameterTypes();
                if (p[0] == RectF.class
                        && p[1] == float.class
                        && p[2] == float.class
                        && p[3] == int.class) {
                    geometry = method;
                }
            } else if ("c".equals(method.getName())
                    && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == Canvas.class
                    && method.getReturnType() == void.class) {
                draw = method;
            }
        }
        if (geometry == null || draw == null) return false;
        geometry.setAccessible(true);
        draw.setAccessible(true);

        donorAurora = donor;
        privateAurora = clone;
        auroraGeometry = geometry;
        auroraDraw = draw;
        copyDynamicVisualState(donor, clone);

        reporter.accept("GOOGLE_NATIVE_STYLE",
                "aurora_renderer=dpoc_clone source=EffectsV2View state_write=false");
        return true;
    }

    private static void copyDynamicVisualState(Object from, Object to) {
        if (from == null || to == null) return;
        // These are renderer-only animation/style fields, not selection model fields.
        copyField(from, to, "i");
        copyField(from, to, "j");
        copyField(from, to, "A");
    }

    private static void copyField(Object from, Object to, String name) {
        for (Field field : GoogleReflection.instanceFieldsInHierarchy(from.getClass())) {
            if (!name.equals(field.getName())) continue;
            try {
                field.setAccessible(true);
                field.set(to, field.get(from));
            } catch (Throwable ignored) { }
            return;
        }
    }

    private void invalidateAurora() {
        donorAurora = null;
        privateAurora = null;
        auroraGeometry = null;
        auroraDraw = null;
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace("\n", " ").replace("\r", " ");
    }
}
