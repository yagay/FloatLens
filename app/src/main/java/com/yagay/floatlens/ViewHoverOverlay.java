package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import java.util.Collections;
import java.util.List;

/**
 * Non-touchable FV-style selection display layer.
 *
 * A complete Accessibility candidate tree is captured once when dragging starts. Pointer MOVE then
 * performs rectangle hit testing only. Small candidates use one translucent drawing surface;
 * ROOT/near-fullscreen candidates use the lightweight four-edge frame so whole-screen Views remain
 * visibly selectable without keeping a fullscreen translucent surface over video playback.
 */
public final class ViewHoverOverlay {
    private static final int LARGE_TARGET_PERCENT = 72;

    private final Context context;
    private final WindowManager wm;
    private final LensAccessibilityService accessibility;
    private final ScreenSelectionModel model = new ScreenSelectionModel();
    private final float regionStartSlopPx;
    private HoverView view;
    private FvRegionFrameOverlay regionFrame;
    private ScreenCandidate current;
    private boolean confirmed;
    private float lastX = Float.NaN, lastY = Float.NaN;
    private float directStartX = Float.NaN, directStartY = Float.NaN;
    private boolean directRegionMode;
    private final Rect directRegion = new Rect();

    public ViewHoverOverlay(Context c) {
        context = c.getApplicationContext();
        wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        accessibility = LensAccessibilityService.get();
        regionStartSlopPx = Math.max(1f, ViewConfiguration.get(context).getScaledTouchSlop());
    }

    public boolean available() { return accessibility != null; }

    /** Compatibility no-op: visual screenshot candidates are no longer used for View selection. */
    public void setScreenSnapshot(Bitmap bitmap, Rect displayBounds) {}

    /**
     * FV-style arming: snapshot and prepare the whole Accessibility tree once before MOVE hit tests.
     * This intentionally happens before FloatLens adds its own highlight overlay.
     */
    public void begin() {
        refreshAccessibilityTree();
    }

    private void ensureTreeCache() {
        if (!model.isEmpty()) return;
        refreshAccessibilityTree();
    }

    private void refreshAccessibilityTree() {
        if (accessibility == null) return;
        try {
            model.setAccessibility(AccessibilityCandidateCollector.collect(accessibility));
            List<ScreenCandidate> cached = model.accessibilityCandidates();
            int broad = 0;
            int viewCount = 0;
            for (ScreenCandidate c : cached) {
                if (c.type() == ScreenCandidate.Type.VIEW) viewCount++;
                if (c.type() == ScreenCandidate.Type.ROOT || c.fullscreenLike()) broad++;
            }
            DiagnosticLog.i(context, "FV_TREE_CACHE", "READY total=" + cached.size()
                    + " view=" + viewCount + " broad=" + broad);
        } catch (Throwable t) {
            DiagnosticLog.i(context, "FV_TREE_CACHE", "refresh failed=" + t);
            model.setAccessibility(Collections.emptyList());
        }
    }

    private void ensureView() {
        if (accessibility == null || view != null) return;
        HoverView next = new HoverView(context);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(next, lp);
            view = next;
            DiagnosticLog.i(context, "VIEW_HOVER", "visual_attach");
        } catch (Throwable t) {
            DiagnosticLog.i(context, "VIEW_HOVER", "add failed=" + t);
        }
    }

    /** Remove only the expensive visual surface; keep cached candidates and selection state. */
    private void detachView() {
        if (view != null) {
            try { wm.removeView(view); } catch (Throwable ignored) {}
            view = null;
            DiagnosticLog.i(context, "VIEW_HOVER", "visual_detach");
        }
    }

    private void closeLargeCandidateFrame() {
        if (regionFrame != null && !directRegionMode) {
            regionFrame.close();
            regionFrame = null;
        }
    }

    /** Start FV same-touch direct selection without rebuilding the already cached View tree. */
    public void beginDirect(float selectionX, float selectionY) {
        ensureTreeCache();
        directStartX = selectionX;
        directStartY = selectionY;
        directRegionMode = false;
        directRegion.setEmpty();
        update(selectionX, selectionY);
        if (view != null) view.setDirectState(false, directRegion);
        DiagnosticLog.i(context, "FV_REGION", "START x=" + Math.round(selectionX)
                + " y=" + Math.round(selectionY) + " slop=" + Math.round(regionStartSlopPx));
    }

    /**
     * Continue the exact same pointer stream after FV direct mode started. A meaningful displacement
     * from the trigger point becomes a region gesture; tiny motion keeps normal View hit testing.
     */
    public void updateDirect(float selectionX, float selectionY) {
        if (accessibility == null) return;
        if (Float.isNaN(directStartX) || Float.isNaN(directStartY)) {
            beginDirect(selectionX, selectionY);
            return;
        }

        float dx = selectionX - directStartX;
        float dy = selectionY - directStartY;
        if (!directRegionMode && dx * dx + dy * dy >= regionStartSlopPx * regionStartSlopPx) {
            directRegionMode = true;
            current = null;
            confirmed = false;
            detachView();
            if (regionFrame == null) regionFrame = new FvRegionFrameOverlay(context);
            DiagnosticLog.i(context, "FV_REGION", "ENTER lightweight-frame dx=" + Math.round(dx)
                    + " dy=" + Math.round(dy));
        }

        if (directRegionMode) {
            int l = Math.round(Math.min(directStartX, selectionX));
            int t = Math.round(Math.min(directStartY, selectionY));
            int r = Math.round(Math.max(directStartX, selectionX));
            int b = Math.round(Math.max(directStartY, selectionY));
            if (directRegion.left != l || directRegion.top != t
                    || directRegion.right != r || directRegion.bottom != b) {
                directRegion.set(l, t, r, b);
                if (regionFrame == null) regionFrame = new FvRegionFrameOverlay(context);
                regionFrame.show(directRegion);
            }
        } else {
            update(selectionX, selectionY);
            if (view != null) view.setDirectState(false, directRegion);
        }
    }

    /**
     * MOVE-time View hit testing. The Accessibility tree is NOT traversed here: selection is a
     * contains(x,y) lookup against the candidate snapshot prepared by begin().
     */
    public void update(float selectionX, float selectionY) {
        if (accessibility == null) return;
        ensureTreeCache();
        lastX = selectionX;
        lastY = selectionY;

        ScreenCandidate next = model.selectAt(selectionX, selectionY);
        if (!sameCandidate(current, next)) {
            current = next;
            confirmed = false;
            if (shouldRenderCandidate(next)) {
                closeLargeCandidateFrame();
                ensureView();
                if (view != null) {
                    view.setCandidate(next);
                    view.setConfirmed(false);
                }
            } else {
                // Large/fullscreen Views remain visible through a cheap four-edge frame.
                detachView();
                if (next != null) {
                    if (regionFrame == null) regionFrame = new FvRegionFrameOverlay(context);
                    regionFrame.show(next.bounds());
                } else {
                    closeLargeCandidateFrame();
                }
            }
            if (next != null) {
                DiagnosticLog.i(context, "VIEW_HOVER", "cacheHit=true source=" + next.source()
                        + " type=" + next.type() + " screenBounds=" + next.bounds()
                        + " depth=" + next.depth() + " textLen=" + next.text().length()
                        + " class=" + next.className() + " id=" + next.viewId()
                        + " fullscreenLike=" + next.fullscreenLike()
                        + " visual=" + shouldRenderCandidate(next));
            } else {
                DiagnosticLog.i(context, "VIEW_HOVER", "cacheHit=false selection="
                        + Math.round(selectionX) + "," + Math.round(selectionY)
                        + " cached=" + model.size());
            }
        }
    }

    private boolean shouldRenderCandidate(ScreenCandidate candidate) {
        if (candidate == null || candidate.type() == ScreenCandidate.Type.ROOT) return false;
        Rect b = candidate.bounds();
        if (b == null || b.isEmpty()) return false;
        Rect screen;
        try { screen = accessibility.screenBounds(); }
        catch (Throwable t) { return true; }
        if (screen == null || screen.isEmpty()) return true;
        long screenArea = (long) screen.width() * screen.height();
        long area = (long) b.width() * b.height();
        return screenArea <= 0 || area * 100L < screenArea * LARGE_TARGET_PERCENT;
    }

    public void setConfirmed(boolean value) {
        confirmed = value && current != null;
        if (confirmed && shouldRenderCandidate(current)) ensureView();
        if (view != null) view.setConfirmed(confirmed);
    }

    public boolean isConfirmed() { return confirmed; }
    public boolean isRegionMode() { return directRegionMode && !directRegion.isEmpty(); }
    public Rect currentRegion() { return new Rect(directRegion); }

    /** Compatibility path for the older dwell-confirmed flow. */
    public boolean finish(boolean extract) {
        ScreenCandidate picked = current;
        boolean wasConfirmed = confirmed;
        close();
        if (!extract || !wasConfirmed) return false;
        return extractPicked(picked, "VIEW_EXTRACT");
    }

    /**
     * FV same-touch ACTION_UP. A dragged region wins over the single View candidate. This prevents
     * an underlying fullscreen View from swallowing a deliberate region drag.
     */
    public boolean finishDirect() {
        if (directRegionMode && directRegion.width() >= 2 && directRegion.height() >= 2) {
            Rect pickedRegion = new Rect(directRegion);
            close();
            ScreenshotController.captureBoundsForRegion(context, pickedRegion);
            DiagnosticLog.i(context, "FV_DIRECT_EXTRACT", "capture REGION bounds=" + pickedRegion);
            return true;
        }
        ScreenCandidate picked = current;
        close();
        return extractPicked(picked, "FV_DIRECT_EXTRACT");
    }

    private boolean extractPicked(ScreenCandidate picked, String logTag) {
        if (picked == null) return false;
        Rect b = picked.bounds();
        if (b.isEmpty()) return false;
        if (picked.type() != ScreenCandidate.Type.TEXT
                && picked.type() != ScreenCandidate.Type.NON_TEXT
                && picked.type() != ScreenCandidate.Type.VIEW
                && picked.type() != ScreenCandidate.Type.ROOT) return false;

        ScreenshotController.captureBoundsForViewCandidate(
                context, b, picked.toViewNodeCandidate(), picked.hasText() ? picked.text() : "");
        DiagnosticLog.i(context, logTag, "capture type=" + picked.type()
                + " bounds=" + b + " textLen=" + picked.text().length()
                + " class=" + picked.className() + " id=" + picked.viewId());
        return true;
    }

    public void cancel() { close(); }
    public boolean hasCandidate() {
        return (directRegionMode && !directRegion.isEmpty())
                || (current != null && !current.bounds().isEmpty());
    }
    public ScreenCandidate currentCandidate() { return current; }

    private void close() {
        detachView();
        if (regionFrame != null) regionFrame.close();
        regionFrame = null;
        current = null;
        confirmed = false;
        directRegionMode = false;
        directRegion.setEmpty();
        directStartX = directStartY = Float.NaN;
        model.setAccessibility(Collections.emptyList());
        model.setVisual(Collections.emptyList());
        lastX = lastY = Float.NaN;
    }

    private boolean sameCandidate(ScreenCandidate a, ScreenCandidate b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.stableKey().equals(b.stableKey());
    }

    private static final class HoverView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderOuter = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderInner = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint regionFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint regionBorderOuter = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint regionBorderInner = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelOutline = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] overlayLocation = new int[2];
        private ScreenCandidate candidate;
        private boolean confirmed;
        private boolean directRegionMode;
        private final Rect directRegion = new Rect();
        private int lastLoggedOriginX = Integer.MIN_VALUE;
        private int lastLoggedOriginY = Integer.MIN_VALUE;

        HoverView(Context c) {
            super(c);
            setBackgroundColor(Color.TRANSPARENT);
            fill.setStyle(Paint.Style.FILL);
            regionFill.setStyle(Paint.Style.FILL);
            regionFill.setColor(0x2233B5E5);
            SelectionVisuals.configureFramePaints(c, regionBorderOuter, regionBorderInner, false);
            SelectionVisuals.configureTextPaints(c, labelOutline, labelFill, 14f);
            updatePaints();
        }

        void setCandidate(ScreenCandidate c) { candidate = c; invalidate(); }
        void setConfirmed(boolean value) {
            if (confirmed == value) return;
            confirmed = value;
            updatePaints();
            invalidate();
        }

        /**
         * No pointer is drawn in this fullscreen layer; the real probe lives in FvProbePointOverlay.
         * Therefore non-region MOVE events must not invalidate the whole screen. Only a region-mode
         * transition or changed rectangle requires a redraw.
         */
        void setDirectState(boolean regionMode, Rect screenRegion) {
            boolean changed = directRegionMode != regionMode;
            if (regionMode) {
                Rect next = screenRegion == null ? new Rect() : screenRegion;
                if (!directRegion.equals(next)) {
                    directRegion.set(next);
                    changed = true;
                }
            } else if (!directRegion.isEmpty()) {
                directRegion.setEmpty();
                changed = true;
            }
            directRegionMode = regionMode;
            if (changed) invalidate();
        }

        private void updatePaints() {
            fill.setColor(confirmed ? 0x552196F3 : 0x332196F3);
            SelectionVisuals.configureFramePaints(getContext(), borderOuter, borderInner, confirmed);
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            getLocationOnScreen(overlayLocation);
            if (overlayLocation[0] != lastLoggedOriginX || overlayLocation[1] != lastLoggedOriginY) {
                lastLoggedOriginX = overlayLocation[0];
                lastLoggedOriginY = overlayLocation[1];
                DiagnosticLog.i(getContext(), "VIEW_DRAW", "overlayOrigin="
                        + overlayLocation[0] + "," + overlayLocation[1]
                        + " size=" + getWidth() + "x" + getHeight());
            }

            Rect localFrame = new Rect(0, 0, getWidth(), getHeight());
            if (directRegionMode && !directRegion.isEmpty()) {
                Rect rr = new Rect(directRegion);
                rr.offset(-overlayLocation[0], -overlayLocation[1]);
                if (Rect.intersects(localFrame, rr)) {
                    c.drawRect(rr, regionFill);
                    SelectionVisuals.drawFrame(c, rr, regionBorderOuter, regionBorderInner);
                    String size = Math.max(0, directRegion.width()) + " × " + Math.max(0, directRegion.height());
                    float lx = Math.max(dp(8), Math.min(rr.left, getWidth() - dp(120)));
                    float ly = rr.top > dp(28) ? rr.top - dp(8)
                            : Math.min(getHeight() - dp(8), rr.bottom + dp(20));
                    SelectionVisuals.drawText(c, size, lx, ly, labelOutline, labelFill);
                }
            } else if (candidate != null) {
                Rect r = candidate.bounds();
                r.offset(-overlayLocation[0], -overlayLocation[1]);
                if (Rect.intersects(localFrame, r)) {
                    c.drawRect(r, fill);
                    SelectionVisuals.drawFrame(c, r, borderOuter, borderInner);
                    String base = (candidate.type() == ScreenCandidate.Type.ROOT || candidate.fullscreenLike())
                            ? "整屏 View" : candidate.label();
                    String text = confirmed ? "已锁定 · " + base : base;
                    float x = Math.max(dp(8), Math.min(r.left, getWidth() - dp(180)));
                    float y = r.top > dp(28) ? r.top - dp(8)
                            : Math.min(getHeight() - dp(8), r.bottom + dp(20));
                    if (text.length() > 90) text = text.substring(0, 90) + "…";
                    SelectionVisuals.drawText(c, text, x, y, labelOutline, labelFill);
                }
            }
        }

        private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
    }
}
