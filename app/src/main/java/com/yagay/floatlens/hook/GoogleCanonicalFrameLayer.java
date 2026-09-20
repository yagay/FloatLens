package com.yagay.floatlens.hook;

import com.yagay.floatlens.CanonicalFramePolicy;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.BlurMaskFilter;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import android.content.res.Resources;
import android.content.res.TypedArray;

/**
 * Session-scoped immutable visual source for FloatLens-owned Google CTS sessions.
 *
 * <p>Google may mutate FrozenImageView's internal viewport even while the outer View still reports
 * scale=1/translation=0. This presenter keeps a private copy of the best full-screen frame and
 * inserts it immediately above FrozenImageView but below Google's later selection/chrome siblings.
 * Google continues owning touch/selection logic; its mutable screenshot presentation is no longer
 * the pixels the user sees.</p>
 */
final class GoogleCanonicalFrameLayer {
    private static final long[] PRESENT_RETRY_MS = {0L, 16L, 64L, 160L, 320L};

    private final BooleanSupplier active;
    private final Supplier<Activity> activity;
    private final BiConsumer<String, String> reporter;
    private final Handler main = new Handler(Looper.getMainLooper());

    private Bitmap canonicalFrame;
    private final List<Bitmap> retiredFrames = new ArrayList<>();
    private WeakReference<ImageView> layerRef = new WeakReference<>(null);
    private WeakReference<SelectionView> selectionRef = new WeakReference<>(null);
    private WeakReference<ViewGroup> parentRef = new WeakReference<>(null);
    private final List<PointF> gesturePoints = new ArrayList<>();
    private Rect textSelectionBounds;

    GoogleCanonicalFrameLayer(BooleanSupplier active,
                              Supplier<Activity> activity,
                              BiConsumer<String, String> reporter) {
        this.active = active;
        this.activity = activity;
        this.reporter = reporter;
    }

    void offer(Bitmap candidate) {
        if (!active.getAsBoolean() || !usable(candidate)) return;

        Bitmap accepted = null;
        Bitmap old = null;
        synchronized (this) {
            if (!active.getAsBoolean()) return;
            if (canonicalFrame != null && !canonicalFrame.isRecycled()
                    && !CanonicalFramePolicy.shouldReplace(
                    canonicalFrame.getWidth(), canonicalFrame.getHeight(),
                    candidate.getWidth(), candidate.getHeight())) {
                reporter.accept("GOOGLE_CANONICAL_FRAME",
                        "candidate_ignored existing=" + canonicalFrame.getWidth() + "x"
                                + canonicalFrame.getHeight()
                                + " candidate=" + candidate.getWidth() + "x"
                                + candidate.getHeight());
                return;
            }
            try {
                accepted = candidate.copy(Bitmap.Config.ARGB_8888, false);
            } catch (Throwable ignored) {
                accepted = null;
            }
            if (!usable(accepted)) return;
            old = canonicalFrame;
            canonicalFrame = accepted;
            if (usable(old)) retiredFrames.add(old);
        }

        reporter.accept("GOOGLE_CANONICAL_FRAME",
                "accepted size=" + accepted.getWidth() + "x" + accepted.getHeight());
        schedulePresent();
    }

    void updateSelection(Rect screenBounds, boolean regionSelection, String text) {
        synchronized (this) {
            if (screenBounds != null && !screenBounds.isEmpty()) gesturePoints.clear();

            // Never mirror a text selection into Google's RegionView state. RegionView is a real
            // screenshot-region editor; driving it makes the text box draggable and can steal text
            // gestures. Keep the text shell purely visual in our non-interactive SelectionView.
            if (!regionSelection
                    && screenBounds != null
                    && !screenBounds.isEmpty()
                    && text != null
                    && !text.isBlank()) {
                textSelectionBounds = new Rect(screenBounds);
            } else {
                textSelectionBounds = null;
            }
        }
        main.post(() -> {
            SelectionView view = selectionRef.get();
            if (view != null) view.invalidate();
        });
        reporter.accept("GOOGLE_CANONICAL_SELECTION",
                "screenBounds=" + String.valueOf(screenBounds)
                        + " region=" + regionSelection
                        + " textLen=" + (text == null ? 0 : text.length())
                        + " frameRenderer=passive_google_mirror");
    }

    void updateLiveTextSelection(Rect screenBounds) {
        if (screenBounds == null || screenBounds.isEmpty()) return;
        synchronized (this) {
            textSelectionBounds = new Rect(screenBounds);
        }
        main.post(() -> {
            SelectionView view = selectionRef.get();
            if (view != null) view.invalidate();
        });
    }

    void onGesturePoint(int action, float x, float y) {
        synchronized (this) {
            if (action == MotionEvent.ACTION_DOWN
                    || action == MotionEvent.ACTION_POINTER_DOWN) {
                gesturePoints.clear();
            }
            if (action == MotionEvent.ACTION_DOWN
                    || action == MotionEvent.ACTION_POINTER_DOWN
                    || action == MotionEvent.ACTION_MOVE
                    || action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL) {
                if (gesturePoints.size() >= 192) gesturePoints.remove(0);
                gesturePoints.add(new PointF(x, y));
            }
        }
        main.post(() -> {
            SelectionView view = selectionRef.get();
            if (view != null) view.invalidate();
        });
    }

    boolean attached() {
        ImageView layer = layerRef.get();
        SelectionView selection = selectionRef.get();
        return layer != null && layer.getParent() != null
                && selection != null && selection.getParent() != null;
    }

    void onActivityAvailable() {
        if (!active.getAsBoolean()) return;
        schedulePresent();
    }

    void reset() {
        Bitmap current;
        List<Bitmap> retired;
        synchronized (this) {
            current = canonicalFrame;
            canonicalFrame = null;
            retired = new ArrayList<>(retiredFrames);
            retiredFrames.clear();
            gesturePoints.clear();
            textSelectionBounds = null;
        }
        main.post(() -> {
            // Detach the ImageView before recycling any bitmap it may still reference.
            removeLayerOnMain();
            recycle(current);
            for (Bitmap bitmap : retired) recycle(bitmap);
        });
    }

    private void schedulePresent() {
        for (long delay : PRESENT_RETRY_MS) {
            if (delay == 0L) main.post(this::presentOnMain);
            else main.postDelayed(this::presentOnMain, delay);
        }
    }

    private void presentOnMain() {
        if (!active.getAsBoolean()) {
            removeLayerOnMain();
            return;
        }
        Activity owner = activity.get();
        Bitmap frame;
        synchronized (this) {
            frame = canonicalFrame;
        }
        if (owner == null || owner.getWindow() == null || !usable(frame)) return;

        View root = owner.getWindow().getDecorView();
        View frozen = GoogleLensViewIntrospection.findByClassName(
                root, GoogleLens1758Profile.FROZEN_IMAGE_VIEW);
        if (frozen == null || frozen.getWidth() <= 0 || frozen.getHeight() <= 0) return;

        ViewParent rawParent = frozen.getParent();
        if (!(rawParent instanceof ViewGroup parent)) return;
        int frozenIndex = parent.indexOfChild(frozen);
        if (frozenIndex < 0) return;

        ImageView existing = layerRef.get();
        SelectionView existingSelection = selectionRef.get();
        ViewGroup previousParent = parentRef.get();
        if (existing != null && existingSelection != null
                && previousParent == parent
                && existing.getParent() == parent
                && existingSelection.getParent() == parent) {
            existing.setImageBitmap(frame);
            normalizeLayer(existing, frozen);
            existingSelection.invalidate();
            return;
        }

        removeLayerOnMain();

        ImageView layer = new ImageView(owner);
        layer.setTag("floatlens_google_canonical_frame");
        layer.setScaleType(ImageView.ScaleType.FIT_XY);
        layer.setImageBitmap(frame);
        layer.setClickable(false);
        layer.setFocusable(false);
        layer.setEnabled(false);
        layer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        layer.setContentDescription(null);
        layer.setAlpha(1f);

        ViewGroup.LayoutParams params;
        if (parent instanceof FrameLayout) {
            params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        } else {
            params = new ViewGroup.LayoutParams(
                    Math.max(1, frozen.getWidth()),
                    Math.max(1, frozen.getHeight()));
        }

        // Insert directly after the mutable FrozenImageView. FloatLens draws its own selection
        // feedback above the immutable frame; later Google siblings remain above both and can keep
        // handling touch/recognition without owning the visible screenshot.
        int insertIndex = Math.min(parent.getChildCount(), frozenIndex + 1);
        SelectionView selectionLayer = new SelectionView(owner);
        selectionLayer.setTag("floatlens_google_selection");
        selectionLayer.setClickable(false);
        selectionLayer.setFocusable(false);
        selectionLayer.setEnabled(false);
        selectionLayer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        try {
            parent.addView(layer, insertIndex, params);
            parent.addView(selectionLayer,
                    Math.min(parent.getChildCount(), insertIndex + 1), params);
        } catch (Throwable t) {
            try {
                if (layer.getParent() instanceof ViewGroup actual) actual.removeView(layer);
            } catch (Throwable ignored) { }
            reporter.accept("GOOGLE_CANONICAL_LAYER",
                    "attach_failed parent=" + parent.getClass().getName()
                            + " error=" + t.getClass().getSimpleName());
            return;
        }

        layerRef = new WeakReference<>(layer);
        selectionRef = new WeakReference<>(selectionLayer);
        parentRef = new WeakReference<>(parent);
        normalizeLayer(layer, frozen);
        reporter.accept("GOOGLE_CANONICAL_LAYER",
                "attached frame=" + frame.getWidth() + "x" + frame.getHeight()
                        + " frozen=" + frozen.getWidth() + "x" + frozen.getHeight()
                        + " parent=" + parent.getClass().getName()
                        + " frozenIndex=" + frozenIndex
                        + " layerIndex=" + parent.indexOfChild(layer)
                        + " selectionIndex=" + parent.indexOfChild(selectionLayer)
                        + " siblings=" + parent.getChildCount());
    }

    private void normalizeLayer(ImageView layer, View frozen) {
        if (layer == null || frozen == null) return;
        layer.setScaleX(1f);
        layer.setScaleY(1f);
        layer.setTranslationX(0f);
        layer.setTranslationY(0f);
        ViewGroup.LayoutParams lp = layer.getLayoutParams();
        if (lp != null && (lp.width != ViewGroup.LayoutParams.MATCH_PARENT
                || lp.height != ViewGroup.LayoutParams.MATCH_PARENT)
                && frozen.getWidth() > 0 && frozen.getHeight() > 0) {
            lp.width = frozen.getWidth();
            lp.height = frozen.getHeight();
            layer.setLayoutParams(lp);
        }
    }

    private void removeLayerOnMain() {
        ImageView layer = layerRef.get();
        SelectionView selection = selectionRef.get();
        ViewGroup parent = parentRef.get();
        layerRef = new WeakReference<>(null);
        selectionRef = new WeakReference<>(null);
        parentRef = new WeakReference<>(null);
        try {
            if (selection != null && selection.getParent() instanceof ViewGroup actual) {
                actual.removeView(selection);
            } else if (selection != null && parent != null) {
                parent.removeView(selection);
            }
        } catch (Throwable ignored) { }
        try {
            if (layer != null && layer.getParent() instanceof ViewGroup actual) {
                actual.removeView(layer);
            } else if (layer != null && parent != null) {
                parent.removeView(layer);
            }
        } catch (Throwable ignored) { }
        try { if (layer != null) layer.setImageDrawable(null); } catch (Throwable ignored) { }
    }

    private final class SelectionView extends View {
        // Resource IDs verified against Google App 17.58.16.ve.
        private static final int RES_REGION_MASK_COLOR = 0x7f061c89;
        private static final int RES_REGION_BOUNDING_PADDING = 0x7f0719e9;
        private static final int RES_REGION_HANDLE_MAX_SIZE = 0x7f0719ee;
        private static final int RES_REGION_HANDLE_STROKE = 0x7f0719ef;
        private static final int RES_REGION_HANDLE_STROKE_UPDATED = 0x7f07125b;
        private static final int RES_REGION_MAX_CORNER_RADIUS = 0x7f0719f0;
        private static final int RES_AURORA_COLORS = 0x7f030012;
        private static final int RES_AURORA_STOPS = 0x7f030013;

        private final Paint trail = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint scrim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint auroraGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint auroraHalo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF localTextRect = new RectF();
        private final int[] auroraColors;
        private final float[] auroraStops;
        private final float boxPadding;
        private final float maxHandleSize;
        private final float handleStroke;
        private final float maxCornerRadius;

        SelectionView(Activity context) {
            super(context);
            Resources resources = getResources();
            float density = Math.max(1f, resources.getDisplayMetrics().density);

            int maskColor = 0x66000000;
            float padding = 4f * density;
            float handleMax = 22f * density;
            float stroke = 4f * density;
            float radius = 14f * density;
            int[] colors = {
                    0xFF3186FF, 0xFF3186FF, 0xFFFF4641, 0xFFFFD314,
                    0xFF34A853, 0xFF3186FF, 0xFF3186FF
            };
            float[] stops = {0f, 0.46f, 0.58f, 0.71f, 0.82f, 0.92f, 1f};

            // Read Google's own runtime resources where possible. The fallbacks above are the exact
            // values extracted from 17.58.16.ve, so a missing themed resource does not break UI.
            try { maskColor = resources.getColor(RES_REGION_MASK_COLOR, context.getTheme()); }
            catch (Throwable ignored) { }
            try { padding = resources.getDimension(RES_REGION_BOUNDING_PADDING); }
            catch (Throwable ignored) { }
            try { handleMax = resources.getDimension(RES_REGION_HANDLE_MAX_SIZE); }
            catch (Throwable ignored) { }
            try {
                float updated = resources.getDimension(RES_REGION_HANDLE_STROKE_UPDATED);
                float classic = resources.getDimension(RES_REGION_HANDLE_STROKE);
                // RegionView 17.58 can use either path depending on its experiment flag. The
                // thinner classic width matches the visible handle itself; aurora is separate.
                stroke = Math.min(updated, classic);
            } catch (Throwable ignored) { }
            try { radius = resources.getDimension(RES_REGION_MAX_CORNER_RADIUS); }
            catch (Throwable ignored) { }
            try {
                int[] runtimeColors = resources.getIntArray(RES_AURORA_COLORS);
                if (runtimeColors != null && runtimeColors.length >= 2) colors = runtimeColors;
            } catch (Throwable ignored) { }
            try {
                TypedArray ta = resources.obtainTypedArray(RES_AURORA_STOPS);
                if (ta.length() >= 2) {
                    float[] runtimeStops = new float[ta.length()];
                    for (int i = 0; i < ta.length(); i++) runtimeStops[i] = ta.getFloat(i, 0f);
                    stops = runtimeStops;
                }
                ta.recycle();
            } catch (Throwable ignored) { }

            boxPadding = padding;
            maxHandleSize = handleMax;
            handleStroke = stroke;
            maxCornerRadius = radius;
            auroraColors = colors;
            auroraStops = stops.length == colors.length ? stops : null;

            scrim.setStyle(Paint.Style.FILL);
            scrim.setColor(maskColor);

            // Aurora is not a colored border. It is a soft edge glow emitted from the outside
            // of the selection boundary. Both layers stay blurred; there is deliberately no
            // hard/solid colored stroke.
            auroraHalo.setStyle(Paint.Style.STROKE);
            auroraHalo.setStrokeCap(Paint.Cap.ROUND);
            auroraHalo.setStrokeJoin(Paint.Join.ROUND);
            auroraHalo.setStrokeWidth(Math.max(8f * density, handleStroke * 1.9f));
            auroraHalo.setAlpha(150);
            auroraHalo.setMaskFilter(
                    new BlurMaskFilter(10f * density, BlurMaskFilter.Blur.OUTER));

            auroraGlow.setStyle(Paint.Style.STROKE);
            auroraGlow.setStrokeCap(Paint.Cap.ROUND);
            auroraGlow.setStrokeJoin(Paint.Join.ROUND);
            auroraGlow.setStrokeWidth(Math.max(3.2f * density, handleStroke * 0.85f));
            auroraGlow.setAlpha(235);
            auroraGlow.setMaskFilter(
                    new BlurMaskFilter(4.5f * density, BlurMaskFilter.Blur.OUTER));

            handle.setStyle(Paint.Style.STROKE);
            handle.setStrokeCap(Paint.Cap.ROUND);
            handle.setStrokeJoin(Paint.Join.ROUND);
            handle.setStrokeWidth(handleStroke);
            handle.setColor(Color.WHITE);

            trail.setStyle(Paint.Style.STROKE);
            trail.setStrokeCap(Paint.Cap.ROUND);
            trail.setStrokeJoin(Paint.Join.ROUND);
            trail.setStrokeWidth(3f * density);
            trail.setColor(Color.WHITE);
            trail.setShadowLayer(1.5f * density, 0f, 0f, 0xAA000000);

            // BlurMaskFilter is software-rendered consistently on all supported Android versions.
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            setBackgroundColor(Color.TRANSPARENT);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            Rect textBounds;
            List<PointF> points;
            synchronized (GoogleCanonicalFrameLayer.this) {
                textBounds = textSelectionBounds == null
                        ? null : new Rect(textSelectionBounds);
                points = new ArrayList<>(gesturePoints);
            }

            if (textBounds != null && !textBounds.isEmpty()) {
                drawPassiveGoogleTextShell(canvas, textBounds);
            }

            if (points.size() >= 2) {
                Path path = new Path();
                PointF first = points.get(0);
                path.moveTo(first.x, first.y);
                for (int i = 1; i < points.size(); i++) {
                    PointF point = points.get(i);
                    path.lineTo(point.x, point.y);
                }
                canvas.drawPath(path, trail);
            }
        }

        private void drawPassiveGoogleTextShell(Canvas canvas, Rect screenBounds) {
            int[] origin = new int[2];
            try { getLocationOnScreen(origin); } catch (Throwable ignored) { }

            localTextRect.set(
                    screenBounds.left - origin[0] - boxPadding,
                    screenBounds.top - origin[1] - boxPadding,
                    screenBounds.right - origin[0] + boxPadding,
                    screenBounds.bottom - origin[1] + boxPadding);

            localTextRect.left = Math.max(0f, localTextRect.left);
            localTextRect.top = Math.max(0f, localTextRect.top);
            localTextRect.right = Math.min(getWidth(), localTextRect.right);
            localTextRect.bottom = Math.min(getHeight(), localTextRect.bottom);
            if (localTextRect.isEmpty()) return;

            float radius = Math.min(maxCornerRadius,
                    Math.max(1f, Math.min(localTextRect.width(), localTextRect.height()) / 3f));

            // Google RegionView's dudd.c() draws the same #66000000 outside scrim around a rounded
            // clear opening. clipOutPath reproduces that presentation without mutating RegionView.
            int save = canvas.save();
            Path hole = new Path();
            hole.addRoundRect(localTextRect, radius, radius, Path.Direction.CW);
            canvas.clipOutPath(hole);
            canvas.drawRect(0f, 0f, getWidth(), getHeight(), scrim);
            canvas.restoreToCount(save);

            float cx = localTextRect.centerX();
            float cy = localTextRect.centerY();
            Shader shader = new SweepGradient(cx, cy, auroraColors, auroraStops);
            auroraHalo.setShader(shader);
            auroraGlow.setShader(shader);

            // Google 17.58 presents Aurora as color living on the OUTSIDE edge, not as a
            // rainbow outline. Clip away the selection interior before drawing the blurred
            // emission so no colored line is painted across the clear window itself.
            int glowSave = canvas.save();
            Path glowHole = new Path();
            glowHole.addRoundRect(localTextRect, radius, radius, Path.Direction.CW);
            canvas.clipOutPath(glowHole);
            canvas.drawRoundRect(localTextRect, radius, radius, auroraHalo);
            canvas.drawRoundRect(localTextRect, radius, radius, auroraGlow);
            canvas.restoreToCount(glowSave);

            auroraHalo.setShader(null);
            auroraGlow.setShader(null);

            // RegionView's actual neutral handles remain separate from EffectsV2/Aurora.
            drawNativeCornerHandles(canvas, localTextRect, radius);
        }

        private void drawNativeCornerHandles(Canvas canvas, RectF rect, float radius) {
            float armX = Math.min(maxHandleSize, Math.max(radius, rect.width() / 2f));
            float armY = Math.min(maxHandleSize, Math.max(radius, rect.height() / 2f));
            float r = Math.min(radius, Math.min(armX, armY));

            Path p = new Path();

            p.moveTo(rect.left, rect.top + armY);
            p.lineTo(rect.left, rect.top + r);
            p.quadTo(rect.left, rect.top, rect.left + r, rect.top);
            p.lineTo(rect.left + armX, rect.top);

            p.moveTo(rect.right - armX, rect.top);
            p.lineTo(rect.right - r, rect.top);
            p.quadTo(rect.right, rect.top, rect.right, rect.top + r);
            p.lineTo(rect.right, rect.top + armY);

            p.moveTo(rect.right, rect.bottom - armY);
            p.lineTo(rect.right, rect.bottom - r);
            p.quadTo(rect.right, rect.bottom, rect.right - r, rect.bottom);
            p.lineTo(rect.right - armX, rect.bottom);

            p.moveTo(rect.left + armX, rect.bottom);
            p.lineTo(rect.left + r, rect.bottom);
            p.quadTo(rect.left, rect.bottom, rect.left, rect.bottom - r);
            p.lineTo(rect.left, rect.bottom - armY);

            canvas.drawPath(p, handle);
        }
    }

    private static boolean usable(Bitmap bitmap) {
        return bitmap != null && !bitmap.isRecycled()
                && bitmap.getWidth() > 0 && bitmap.getHeight() > 0;
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return;
        try { bitmap.recycle(); } catch (Throwable ignored) { }
    }
}
