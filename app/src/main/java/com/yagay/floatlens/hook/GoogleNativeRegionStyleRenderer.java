package com.yagay.floatlens.hook;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.view.View;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Reuses Google Lens 17.58's actual EffectsV2 Aurora pipeline without writing RegionView state.
 *
 * <p>dpoc is cloned privately and produces Google's RGB cutout/matte mask. The RuntimeShader is
 * Google's already-initialized live dpoz instance captured from dpls.d(RuntimeShader). FloatLens
 * never mutates that shader; it only uses it to build a RenderEffect for our private RenderNode.</p>
 */
final class GoogleNativeRegionStyleRenderer {
    private static final String EFFECTS_PEER = "dpls";
    private static final String AURORA_RENDERER = "dpoc";
    private static final String GOOGLE_SHADER = "dpoz";
    private static final String REGION_PEER = "dtch";
    private static final String RUNTIME_SHADER = "android.graphics.RuntimeShader";
    private static final String RENDER_EFFECT = "android.graphics.RenderEffect";

    private final ClassLoader classLoader;
    private final Supplier<Activity> activity;
    private final BiConsumer<String, String> reporter;

    private Object donorAurora;
    private Object privateAurora;
    private Method auroraGeometry;
    private Method auroraDraw;

    private Object runtimeShader;
    private Object runtimeEffect;
    private Object runtimeEffectShaderIdentity;
    private Method renderNodeSetEffect;
    private RenderNode auroraNode;

    GoogleNativeRegionStyleRenderer(
            ClassLoader classLoader,
            Supplier<Activity> activity,
            BiConsumer<String, String> reporter) {
        this.classLoader = classLoader;
        this.activity = activity;
        this.reporter = reporter;
    }

    synchronized void captureRuntimeShader(Object shader) {
        if (shader == null) return;
        try {
            Class<?> runtimeShaderClass = Class.forName(
                    RUNTIME_SHADER, false, classLoader);
            if (!runtimeShaderClass.isInstance(shader)) return;

            // dpls can receive RuntimeShader subclasses. Google 17.58's actual Aurora shader is
            // dpoz, so do not reject it just because its concrete class name != RuntimeShader.
            if (!GOOGLE_SHADER.equals(shader.getClass().getName())) {
                reporter.accept("GOOGLE_NATIVE_STYLE",
                        "runtime_shader_ignored=" + shader.getClass().getName());
                return;
            }

            if (runtimeShader == shader) return;
            runtimeShader = shader;
            runtimeEffect = null;
            runtimeEffectShaderIdentity = null;
            reporter.accept("GOOGLE_NATIVE_STYLE",
                    "runtime_shader=dpoz_captured source=EffectsV2View initialized=true");
        } catch (Throwable t) {
            reporter.accept("GOOGLE_NATIVE_STYLE",
                    "runtime_shader_capture_failed=" + t.getClass().getSimpleName()
                            + ":" + safe(t.getMessage()));
        }
    }

    boolean drawAurora(Canvas canvas, View host, RectF localRect, float radius) {
        if (canvas == null || host == null || localRect == null || localRect.isEmpty()
                || host.getWidth() <= 0 || host.getHeight() <= 0
                || !canvas.isHardwareAccelerated()) {
            return false;
        }
        try {
            if (!ensureAurora() || !ensureRuntimeEffect()) return false;

            copyVisualState(donorAurora, privateAurora);
            auroraGeometry.invoke(
                    privateAurora,
                    new RectF(localRect),
                    radius,
                    1.0f,
                    Math.max(1, host.getHeight()));

            int width = Math.max(1, host.getWidth());
            int height = Math.max(1, host.getHeight());
            if (auroraNode == null) {
                auroraNode = new RenderNode("floatlens_google_native_aurora");
            }
            auroraNode.setPosition(0, 0, width, height);

            RecordingCanvas recording = auroraNode.beginRecording(width, height);
            boolean recorded = false;
            try {
                // dpoc.c() intentionally draws a red/green channel mask. It is never presented
                // directly; the captured Google dpoz RuntimeShader converts it to final EffectsV2.
                auroraDraw.invoke(privateAurora, recording);
                recorded = true;
            } finally {
                auroraNode.endRecording();
            }
            if (!recorded) return false;

            Object effect;
            Method setter;
            synchronized (this) {
                effect = runtimeEffect;
                setter = renderNodeSetEffect;
            }
            if (effect == null || setter == null) return false;

            setter.invoke(auroraNode, effect);
            canvas.drawRenderNode(auroraNode);
            return true;
        } catch (Throwable t) {
            reporter.accept("GOOGLE_NATIVE_STYLE",
                    "aurora_pipeline_failed=" + t.getClass().getSimpleName()
                            + ":" + safe(t.getMessage()));
            invalidateAuroraVisualOnly();
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

    synchronized void reset() {
        invalidateAuroraVisualOnly();
        runtimeShader = null;
        runtimeEffect = null;
        runtimeEffectShaderIdentity = null;
        renderNodeSetEffect = null;
        auroraNode = null;
    }

    private synchronized boolean ensureRuntimeEffect() {
        if (runtimeShader == null) return false;
        if (runtimeEffect != null
                && runtimeEffectShaderIdentity == runtimeShader
                && renderNodeSetEffect != null) {
            return true;
        }
        try {
            Class<?> runtimeShaderClass = Class.forName(
                    RUNTIME_SHADER, false, classLoader);
            Class<?> effectClass = Class.forName(
                    RENDER_EFFECT, false, classLoader);
            if (!runtimeShaderClass.isInstance(runtimeShader)) return false;

            Method create = effectClass.getMethod(
                    "createRuntimeShaderEffect", runtimeShaderClass, String.class);
            Object effect = create.invoke(null, runtimeShader, "in_src");
            Method setter = RenderNode.class.getMethod(
                    "setRenderEffect", effectClass);

            runtimeEffect = effect;
            runtimeEffectShaderIdentity = runtimeShader;
            renderNodeSetEffect = setter;
            reporter.accept("GOOGLE_NATIVE_STYLE",
                    "runtime_effect=ready shader=dpoz pipeline=dpoc+GoogleLiveShader");
            return true;
        } catch (Throwable t) {
            reporter.accept("GOOGLE_NATIVE_STYLE",
                    "runtime_effect_unavailable=" + t.getClass().getSimpleName()
                            + ":" + safe(t.getMessage()));
            runtimeEffect = null;
            runtimeEffectShaderIdentity = null;
            renderNodeSetEffect = null;
            return false;
        }
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
            copyVisualState(donor, privateAurora);
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

        Object clone;
        try {
            clone = constructor.newInstance(contextWrapper, variant, modeN, modeO);
        } catch (Throwable error) {
            reporter.accept("GOOGLE_NATIVE_STYLE",
                    "aurora_clone_failed=" + error.getClass().getSimpleName()
                            + ":" + safe(error.getMessage()));
            return false;
        }

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
        copyVisualState(donor, clone);

        reporter.accept("GOOGLE_NATIVE_STYLE",
                "aurora_mask=dpoc_clone shader=GoogleLiveDpoz state_write=false");
        return true;
    }

    private static void copyVisualState(Object from, Object to) {
        if (from == null || to == null) return;
        copyField(from, to, "e");
        copyField(from, to, "q");
        copyField(from, to, "i");
        copyField(from, to, "j");
        copyField(from, to, "A");
        copyField(from, to, "r");
    }

    private static void copyField(Object from, Object to, String name) {
        if (from == null || to == null) return;
        Object value = GoogleReflection.readNamedField(from, name);
        if (value instanceof int[] ints) value = Arrays.copyOf(ints, ints.length);
        for (Field field : GoogleReflection.instanceFieldsInHierarchy(to.getClass())) {
            if (!name.equals(field.getName())) continue;
            try {
                field.setAccessible(true);
                field.set(to, value);
            } catch (Throwable ignored) { }
            return;
        }
    }

    private synchronized void invalidateAuroraVisualOnly() {
        donorAurora = null;
        privateAurora = null;
        auroraGeometry = null;
        auroraDraw = null;
        auroraNode = null;
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace("\n", " ").replace("\r", " ");
    }
}
