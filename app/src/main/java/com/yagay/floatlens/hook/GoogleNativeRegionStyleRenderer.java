package com.yagay.floatlens.hook;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.util.TypedValue;
import android.view.View;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Reuses Google Lens 17.58's actual region Aurora pipeline without touching RegionView state.
 *
 * <p>dpoc produces Google's RGB mask. dpoz is Google's RuntimeShader subclass that converts the
 * mask into the broad blue/red/yellow/green Aurora field. Both objects are private clones owned by
 * FloatLens: no Region is created, no real EffectsV2View shader is mutated, and no touch state is
 * shared with Google's screenshot-region editor.</p>
 */
final class GoogleNativeRegionStyleRenderer {
    private static final String EFFECTS_PEER = "dpls";
    private static final String AURORA_RENDERER = "dpoc";
    private static final String GOOGLE_SHADER = "dpoz";
    private static final String GOOGLE_CONFIG = "dpnc";
    private static final String REGION_PEER = "dtch";
    private static final String SCRIM_RENDERER = "dudd";
    private static final String RUNTIME_SHADER = "android.graphics.RuntimeShader";
    private static final String RENDER_EFFECT = "android.graphics.RenderEffect";

    // Theme attrs used by dpll.onLayoutChange in Google 17.58.
    private static final int ATTR_BLUE_500 = 0x7f0409ee;
    private static final int ATTR_GREEN_400 = 0x7f0409f0;
    private static final int ATTR_ORANGE_TRANSITION = 0x7f0409f1;
    private static final int ATTR_RED_500 = 0x7f0409f2;
    private static final int ATTR_RED_LEGACY = 0x7f0409f3;
    private static final int ATTR_TEAL_TRANSITION = 0x7f0409f4;
    private static final int ATTR_YELLOW_500 = 0x7f0409f5;
    private static final int ATTR_YELLOW_GREEN_TRANSITION = 0x7f0409f6;
    private static final int ATTR_YELLOW_LEGACY = 0x7f0409f7;

    private final ClassLoader classLoader;
    private final Supplier<Activity> activity;
    private final BiConsumer<String, String> reporter;

    private Object effectsPeer;
    private Object shaderConfig;
    private Context shaderContext;

    private Object donorAurora;
    private Object privateAurora;
    private Method auroraGeometry;
    private Method auroraDraw;

    private Object privateShader;
    private Object runtimeEffect;
    private Object shaderConfigIdentity;
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

    /**
     * Kept for diagnostics/backward compatibility with the hook. The visible renderer no longer
     * depends on catching Google's live shader; dpoz is constructed privately and deterministically.
     */
    synchronized void captureRuntimeShader(Object shader) {
        if (shader == null) return;
        Class<?> type = shader.getClass();
        boolean runtimeShader = false;
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (RUNTIME_SHADER.equals(current.getName())) {
                runtimeShader = true;
                break;
            }
        }
        if (runtimeShader) {
            reporter.accept("GOOGLE_NATIVE_STYLE",
                    "live_shader_seen=" + type.getName()
                            + " private_shader_path=true");
        }
    }

    boolean drawAurora(Canvas canvas, View host, RectF localRect, float radius) {
        if (canvas == null || host == null || localRect == null || localRect.isEmpty()
                || host.getWidth() <= 0 || host.getHeight() <= 0
                || !canvas.isHardwareAccelerated()) {
            return false;
        }
        try {
            if (!ensureAurora() || !ensurePrivateShader()) return false;

            // Use Google's fully settled region style instead of copying an in-progress animation
            // frame from the donor. This is what gives the broad, saturated color field rather than
            // a thin partially-animated rim.
            settlePrivateAuroraStyle();

            auroraGeometry.invoke(
                    privateAurora,
                    new RectF(localRect),
                    radius,
                    1.0f,
                    Math.max(1, host.getHeight()));

            configurePrivateShader(host, localRect);

            int width = Math.max(1, host.getWidth());
            int height = Math.max(1, host.getHeight());
            if (auroraNode == null) {
                auroraNode = new RenderNode("floatlens_google_native_aurora");
            }
            auroraNode.setPosition(0, 0, width, height);

            RecordingCanvas recording = auroraNode.beginRecording(width, height);
            boolean recorded = false;
            try {
                // dpoc.c() intentionally produces a red/green RGB mask. It is never displayed
                // directly; Google's private dpoz RuntimeShader below converts it to final Aurora.
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

            // No edge clipping and no SCREEN blend. The private shader has scrim/header alpha set
            // to zero, so pixels outside dpoc's blurred matte remain transparent. Drawing the full
            // node preserves Google's thick spatial gradient instead of collapsing it into a line.
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

            Object paintValue = GoogleReflection.readField(peer, "h", Paint.class.getName());
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
                        && p[2] == float.class
                        && p[3] == float.class
                        && p[4] == RectF.class
                        && p[5] == float.class) {
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

    synchronized void reset() {
        invalidateAuroraVisualOnly();
        effectsPeer = null;
        shaderConfig = null;
        shaderContext = null;
        privateShader = null;
        runtimeEffect = null;
        shaderConfigIdentity = null;
        renderNodeSetEffect = null;
        auroraNode = null;
    }

    private synchronized boolean ensurePrivateShader() {
        if (shaderConfig == null || shaderContext == null) return false;
        if (privateShader != null
                && runtimeEffect != null
                && shaderConfigIdentity == shaderConfig
                && renderNodeSetEffect != null) {
            return true;
        }
        try {
            Class<?> configClass = Class.forName(GOOGLE_CONFIG, false, classLoader);
            Class<?> shaderSubclass = Class.forName(GOOGLE_SHADER, false, classLoader);
            Class<?> runtimeShaderClass = Class.forName(RUNTIME_SHADER, false, classLoader);
            Class<?> effectClass = Class.forName(RENDER_EFFECT, false, classLoader);
            if (!configClass.isInstance(shaderConfig)
                    || !runtimeShaderClass.isAssignableFrom(shaderSubclass)) {
                return false;
            }

            Constructor<?> constructor = shaderSubclass.getDeclaredConstructor(
                    Context.class, configClass);
            constructor.setAccessible(true);
            Object shader = constructor.newInstance(shaderContext, shaderConfig);

            // Constructor already selects Google's radial mode 1. Re-assert it explicitly so this
            // private shader never inherits a later text/translate scene mode.
            invokeIntMethod(shader, "e", 1);
            configureBrandColors(shader, shaderContext);

            Method create = effectClass.getMethod(
                    "createRuntimeShaderEffect", runtimeShaderClass, String.class);
            Object effect = create.invoke(null, shader, "in_src");
            Method setter = RenderNode.class.getMethod("setRenderEffect", effectClass);

            privateShader = shader;
            runtimeEffect = effect;
            shaderConfigIdentity = shaderConfig;
            renderNodeSetEffect = setter;

            reporter.accept("GOOGLE_NATIVE_STYLE",
                    "private_shader=dpoz mode=radial source=Google17.58 fallback=false");
            return true;
        } catch (Throwable t) {
            reporter.accept("GOOGLE_NATIVE_STYLE",
                    "private_shader_unavailable=" + t.getClass().getSimpleName()
                            + ":" + safe(t.getMessage()));
            privateShader = null;
            runtimeEffect = null;
            shaderConfigIdentity = null;
            renderNodeSetEffect = null;
            return false;
        }
    }

    private void configurePrivateShader(View host, RectF rect) {
        Object shader;
        synchronized (this) {
            shader = privateShader;
        }
        if (shader == null) return;

        float width = Math.max(1f, host.getWidth());
        float height = Math.max(1f, host.getHeight());
        float density = Math.max(1f, host.getResources().getDisplayMetrics().density);

        // Values mirror dpll.onLayoutChange for dpoz, except FloatLens deliberately zeros Google's
        // selection/header scrims because our passive layer draws the #66000000 scrim separately.
        setFloatUniform(shader, "in_resolution", width, height);
        setFloatUniform(shader, "in_center", rect.centerX(), rect.centerY());
        setFloatUniform(shader, "in_centreBlendRadius", 0.5f * Math.min(width, height));
        setFloatUniform(shader, "in_blurFocalPoint", 1f);
        setFloatUniform(shader, "in_isRadialGradient", 1f);
        setFloatUniform(shader, "in_isCircles", 0f);

        setFloatUniform(shader, "in_scrimOpacity", 0f);
        setFloatUniform(shader, "in_headerScrimAlpha", 0f);
        setFloatUniform(shader, "in_headerScrimLength", height);
        setFloatUniform(shader, "in_a0", 0f);
        setFloatUniform(shader, "in_a2", 0f);
        setFloatUniform(shader, "in_a3", 0f);
        setFloatUniform(shader, "in_a4", 0f);
        setFloatUniform(shader, "in_linearGradientSkewFactor", 0f);
        setFloatUniform(shader, "in_extraScreenHeightForLinearGradientSkew", 0f);

        // Safe neutral values for Google builds whose dpoz source includes neural-energy dots.
        // Dots are disabled (alpha=0), but non-zero divisors prevent NaNs in the shader path.
        setFloatUniform(shader, "inResolution", width / density, height / density);
        setFloatUniform(shader, "inDotsAlpha", 0f);
        setFloatUniform(shader, "inDotsGridSize", 3.5f);
        setFloatUniform(shader, "inDotsBlurRadius", 0.1f);
        setFloatUniform(shader, "inDotsNoiseManhattanLimit", 0.35f);
        setFloatUniform(shader, "inDotsMaxRadius", 0.12f);
        setFloatUniform(shader, "inDotsMinRadius", -0.07f);
        setFloatUniform(shader, "inDotsMoveStrength", 1f);
        setFloatUniform(shader, "inDotsWaveFrequency", 0.02f);
        setFloatUniform(shader, "inDotsWaveAmplitude", 3f);
        setFloatUniform(shader, "inDotsWaveSpeed", 10f);
        setFloatUniform(shader, "inDotsNoiseSpatialScale", 0.0028f);
        setFloatUniform(shader, "inDotsYGradientWeight", 0f);
        setFloatUniform(shader, "inDotsMaskInnerStrokeWidth", 0f);
        setFloatUniform(shader, "inDotsMaskOuterStrokeWidth", 0f);
    }

    private void configureBrandColors(Object shader, Context context) {
        setColorVector(shader, "in_brandBlue500",
                resolveThemeColor(context, ATTR_BLUE_500, 0xFF3186FF));
        setColorVector(shader, "in_brandRedLegacy",
                resolveThemeColor(context, ATTR_RED_LEGACY, 0xFFFF4641));
        setColorVector(shader, "in_brandYellowLegacy",
                resolveThemeColor(context, ATTR_YELLOW_LEGACY, 0xFFFFD314));
        setColorVector(shader, "in_brandGreen400",
                resolveThemeColor(context, ATTR_GREEN_400, 0xFF34A853));
        setColorVector(shader, "in_brandOrangeTransition",
                resolveThemeColor(context, ATTR_ORANGE_TRANSITION, 0xFFFF6B2B));
        setColorVector(shader, "in_brandYellowGreenTransition",
                resolveThemeColor(context, ATTR_YELLOW_GREEN_TRANSITION, 0xFFA8C73A));
        setColorVector(shader, "in_brandTealTransition",
                resolveThemeColor(context, ATTR_TEAL_TRANSITION, 0xFF00A5B7));

        // Resolve these too so theme lookup follows the exact same Google palette family even
        // though the current shader source doesn't directly consume both values.
        resolveThemeColor(context, ATTR_RED_500, 0xFFFF4641);
        resolveThemeColor(context, ATTR_YELLOW_500, 0xFFFFCC00);
    }

    private void setColorVector(Object shader, String name, int color) {
        float[] values = colorVectorFromGoogle(color);
        if (values == null || values.length < 3) {
            values = new float[] {
                    Color.red(color) / 255f,
                    Color.green(color) / 255f,
                    Color.blue(color) / 255f
            };
        }
        setFloatUniform(shader, name, values[0], values[1], values[2]);
    }

    private float[] colorVectorFromGoogle(int color) {
        try {
            Class<?> shaderClass = Class.forName(GOOGLE_SHADER, false, classLoader);
            Method method = shaderClass.getDeclaredMethod("d", int.class);
            method.setAccessible(true);
            Object result = method.invoke(null, color);
            if (result instanceof float[] values) return values;
        } catch (Throwable ignored) { }
        return null;
    }

    private static int resolveThemeColor(Context context, int attr, int fallback) {
        if (context == null) return fallback;
        try {
            TypedValue value = new TypedValue();
            if (!context.getTheme().resolveAttribute(attr, value, true)) return fallback;
            if (value.resourceId != 0) {
                return context.getColor(value.resourceId);
            }
            if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return value.data;
            }
        } catch (Throwable ignored) { }
        return fallback;
    }

    private static void setFloatUniform(Object shader, String name, float... values) {
        if (shader == null || name == null || values == null || values.length == 0) return;
        try {
            Class<?>[] signature = new Class<?>[values.length + 1];
            Object[] args = new Object[values.length + 1];
            signature[0] = String.class;
            args[0] = name;
            for (int i = 0; i < values.length; i++) {
                signature[i + 1] = float.class;
                args[i + 1] = values[i];
            }
            Method method = shader.getClass().getMethod("setFloatUniform", signature);
            method.invoke(shader, args);
        } catch (Throwable ignored) {
            // Not every dpoz source variant contains every optional uniform.
        }
    }

    private static void invokeIntMethod(Object target, String name, int value) {
        if (target == null) return;
        for (Method method : GoogleReflection.methodsInHierarchy(target.getClass())) {
            if (!name.equals(method.getName())
                    || method.getParameterCount() != 1
                    || method.getParameterTypes()[0] != int.class) continue;
            try {
                method.setAccessible(true);
                method.invoke(target, value);
                return;
            } catch (Throwable ignored) { }
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

        Object config = GoogleReflection.readField(peer, "b", GOOGLE_CONFIG);
        Object donor = GoogleReflection.readField(peer, "g", AURORA_RENDERER);
        if (config == null || donor == null
                || !AURORA_RENDERER.equals(donor.getClass().getName())) {
            return false;
        }

        effectsPeer = peer;
        shaderConfig = config;
        shaderContext = effects.getContext();

        if (donor == donorAurora
                && privateAurora != null
                && auroraGeometry != null
                && auroraDraw != null) {
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
        settlePrivateAuroraStyle();

        // A changed EffectsV2 config requires a matching private dpoz instance.
        synchronized (this) {
            if (shaderConfigIdentity != shaderConfig) {
                privateShader = null;
                runtimeEffect = null;
                shaderConfigIdentity = null;
                renderNodeSetEffect = null;
            }
        }

        reporter.accept("GOOGLE_NATIVE_STYLE",
                "aurora_mask=dpoc private_shader=dpoz state_write=false");
        return true;
    }

    private void settlePrivateAuroraStyle() {
        Object peer = effectsPeer;
        Object clone = privateAurora;
        Object donor = donorAurora;
        if (clone == null) return;

        // First copy Google's current palette fields, then force animation progress to 1.0.
        copyField(donor, clone, "e");
        copyField(donor, clone, "q");
        copyField(donor, clone, "j");
        copyField(donor, clone, "A");

        // dpls.a(true) returns Google's own region-style dpoa. Apply its target palette directly
        // when available so the private renderer is saturated even if the donor is mid-transition.
        Object style = invokeBooleanMethod(peer, "a", true);
        if (style != null) {
            Object colors = GoogleReflection.invokeNoArg(style, "c");
            Object amount = GoogleReflection.invokeNoArg(style, "a");
            Object enabled = GoogleReflection.invokeNoArg(style, "b");
            if (colors instanceof int[] values) {
                setField(clone, "e", Arrays.copyOf(values, values.length));
                setField(clone, "q", Arrays.copyOf(values, values.length));
            }
            if (amount instanceof Number number) {
                setField(clone, "j", number.floatValue());
            }
            if (enabled instanceof Boolean value) {
                setField(clone, "A", value);
            }
        }
        setField(clone, "i", 1f);
    }

    private static Object invokeBooleanMethod(Object target, String name, boolean value) {
        if (target == null) return null;
        for (Method method : GoogleReflection.methodsInHierarchy(target.getClass())) {
            if (!name.equals(method.getName())
                    || method.getParameterCount() != 1
                    || method.getParameterTypes()[0] != boolean.class) continue;
            try {
                method.setAccessible(true);
                return method.invoke(target, value);
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static void copyField(Object from, Object to, String name) {
        if (from == null || to == null) return;
        Object value = GoogleReflection.readNamedField(from, name);
        if (value instanceof int[] ints) value = Arrays.copyOf(ints, ints.length);
        setField(to, name, value);
    }

    private static void setField(Object target, String name, Object value) {
        if (target == null || name == null) return;
        for (Field field : GoogleReflection.instanceFieldsInHierarchy(target.getClass())) {
            if (!name.equals(field.getName())) continue;
            try {
                field.setAccessible(true);
                field.set(target, value);
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
