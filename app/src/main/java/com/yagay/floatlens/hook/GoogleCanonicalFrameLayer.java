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
    private Rect selectionBounds;
    private final List<PointF> gesturePoints = new ArrayList<>();

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
            selectionBounds = screenBounds == null || screenBounds.isEmpty()
                    ? null : new Rect(screenBounds);
            if (selectionBounds != null) gesturePoints.clear();
        }
        main.post(() -> {
            SelectionView view = selectionRef.get();
            if (view != null) view.invalidate();
        });
        reporter.accept("GOOGLE_CANONICAL_SELECTION",
                "screenBounds=" + String.valueOf(screenBounds)
                        + " region=" + regionSelection
                        + " textLen=" + (text == null ? 0 : text.length()));
    }

    void onGesturePoint(int action, float x, float y) {
        synchronized (this) {
            if (action == MotionEvent.ACTION_DOWN
                    || action == MotionEvent.ACTION_POINTER_DOWN) {
                gesturePoints.clear();
                selectionBounds = null;
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
            selectionBounds = null;
            gesturePoints.clear();
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
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint trail = new Paint(Paint.ANTI_ALIAS_FLAG);

        SelectionView(Activity context) {
            super(context);
            float density = Math.max(1f, getResources().getDisplayMetrics().density);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(2f * density);
            border.setColor(Color.WHITE);
            border.setShadowLayer(1.5f * density, 0f, 0f, 0xAA000000);
            trail.setStyle(Paint.Style.STROKE);
            trail.setStrokeCap(Paint.Cap.ROUND);
            trail.setStrokeJoin(Paint.Join.ROUND);
            trail.setStrokeWidth(3f * density);
            trail.setColor(Color.WHITE);
            trail.setShadowLayer(1.5f * density, 0f, 0f, 0xAA000000);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            setBackgroundColor(Color.TRANSPARENT);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            Bitmap frame;
            Rect bounds;
            List<PointF> points;
            synchronized (GoogleCanonicalFrameLayer.this) {
                frame = canonicalFrame;
                bounds = selectionBounds == null ? null : new Rect(selectionBounds);
                points = new ArrayList<>(gesturePoints);
            }

            if (frame != null && !frame.isRecycled() && bounds != null && !bounds.isEmpty()) {
                int[] origin = new int[2];
                try { getLocationOnScreen(origin); } catch (Throwable ignored) { }
                canvas.drawRect(
                        bounds.left - origin[0],
                        bounds.top - origin[1],
                        bounds.right - origin[0],
                        bounds.bottom - origin[1],
                        border);
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
