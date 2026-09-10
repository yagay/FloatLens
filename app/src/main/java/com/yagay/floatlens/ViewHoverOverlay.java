package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import java.util.Collections;

/**
 * Non-touchable FV-style selection display layer.
 *
 * The original FloatIconView remains the only touch owner. After FV's move-idle trigger this layer
 * starts at the current selection hotspot. While the finger is still down, a small pointer marker
 * follows that hotspot. If the finger moves meaningfully away from the trigger point, selection
 * switches from single-View hit testing to a live rectangular region. ACTION_UP captures the live
 * region; if no region was drawn, the current TEXT / image / ROOT View candidate is used instead.
 */
public final class ViewHoverOverlay {
    private static final long TREE_REFRESH_MS = 120L;

    private final Context context;
    private final WindowManager wm;
    private final LensAccessibilityService accessibility;
    private final ScreenSelectionModel model = new ScreenSelectionModel();
    private final float regionStartSlopPx;
    private HoverView view;
    private ScreenCandidate current;
    private boolean confirmed;
    private long lastScanAt;
    private long lastTreeScanAt;
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

    public void begin() {
        if (accessibility == null || view != null) return;
        view = new HoverView(context);
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
            wm.addView(view, lp);
            refreshAccessibilityTree(true);
        } catch (Throwable t) {
            view = null;
            DiagnosticLog.i(context, "VIEW_HOVER", "add failed=" + t);
        }
    }

    /** Start FV same-touch direct selection at the current transformed selection hotspot. */
    public void beginDirect(float selectionX, float selectionY) {
        if (view == null) begin();
        directStartX = selectionX;
        directStartY = selectionY;
        directRegionMode = false;
        directRegion.setEmpty();
        if (view != null) view.setDirectState(selectionX, selectionY, false, directRegion);
        update(selectionX, selectionY);
        DiagnosticLog.i(context, "FV_REGION", "START x=" + Math.round(selectionX)
                + " y=" + Math.round(selectionY) + " slop=" + Math.round(regionStartSlopPx));
    }

    /**
     * Continue the exact same pointer stream after FV direct mode started. A meaningful displacement
     * from the trigger point becomes a region gesture; tiny motion keeps normal View hit testing.
     */
    public void updateDirect(float selectionX, float selectionY) {
        if (view == null) begin();
        if (view == null) return;
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
            view.setCandidate(null);
            DiagnosticLog.i(context, "FV_REGION", "ENTER dx=" + Math.round(dx)
                    + " dy=" + Math.round(dy));
        }

        if (directRegionMode) {
            int l = Math.round(Math.min(directStartX, selectionX));
            int t = Math.round(Math.min(directStartY, selectionY));
            int r = Math.round(Math.max(directStartX, selectionX));
            int b = Math.round(Math.max(directStartY, selectionY));
            directRegion.set(l, t, r, b);
            view.setDirectState(selectionX, selectionY, true, directRegion);
        } else {
            view.setDirectState(selectionX, selectionY, false, directRegion);
            update(selectionX, selectionY);
        }
    }

    /** Normal point-based View hit testing used before a direct region gesture takes over. */
    public void update(float selectionX, float selectionY) {
        if (accessibility == null) return;
        if (view == null) begin();
        if (view == null) return;

        long now = SystemClock.uptimeMillis();
        float dx = Float.isNaN(lastX) ? 999f : selectionX - lastX;
        float dy = Float.isNaN(lastY) ? 999f : selectionY - lastY;
        if (now - lastScanAt < 20L && dx * dx + dy * dy < 9f) return;
        lastScanAt = now;
        lastX = selectionX;
        lastY = selectionY;

        refreshAccessibilityTree(false);
        ScreenCandidate next = model.selectAt(selectionX, selectionY);
        if (!sameCandidate(current, next)) {
            current = next;
            confirmed = false;
            view.setCandidate(next);
            view.setConfirmed(false);
            if (next != null) {
                DiagnosticLog.i(context, "VIEW_HOVER", "source=" + next.source()
                        + " type=" + next.type() + " screenBounds=" + next.bounds()
                        + " depth=" + next.depth() + " textLen=" + next.text().length()
                        + " class=" + next.className() + " id=" + next.viewId());
            } else {
                DiagnosticLog.i(context, "VIEW_HOVER", "candidate=null selection="
                        + Math.round(selectionX) + "," + Math.round(selectionY));
            }
        }
    }

    private void refreshAccessibilityTree(boolean force) {
        long now = SystemClock.uptimeMillis();
        if (!force && now - lastTreeScanAt < TREE_REFRESH_MS
                && !model.accessibilityCandidates().isEmpty()) return;
        lastTreeScanAt = now;
        try {
            model.setAccessibility(AccessibilityCandidateCollector.collect(accessibility));
        } catch (Throwable t) {
            DiagnosticLog.i(context, "FV_TREE", "refresh failed=" + t);
            model.setAccessibility(Collections.emptyList());
        }
    }

    public void setConfirmed(boolean value) {
        confirmed = value && current != null;
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
     * an underlying fullscreen ROOT from swallowing a deliberate region drag.
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
        if (view != null) try { wm.removeView(view); } catch (Throwable ignored) {}
        view = null;
        current = null;
        confirmed = false;
        directRegionMode = false;
        directRegion.setEmpty();
        directStartX = directStartY = Float.NaN;
        model.setAccessibility(Collections.emptyList());
        model.setVisual(Collections.emptyList());
        lastScanAt = lastTreeScanAt = 0L;
        lastX = lastY = Float.NaN;
    }

    private boolean sameCandidate(ScreenCandidate a, ScreenCandidate b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.stableKey().equals(b.stableKey());
    }

    private static final class HoverView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint regionFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint regionBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pointerFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pointerBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] overlayLocation = new int[2];
        private ScreenCandidate candidate;
        private boolean confirmed;
        private boolean directRegionMode;
        private final Rect directRegion = new Rect();
        private float pointerX = Float.NaN, pointerY = Float.NaN;
        private int lastLoggedOriginX = Integer.MIN_VALUE;
        private int lastLoggedOriginY = Integer.MIN_VALUE;

        HoverView(Context c) {
            super(c);
            setBackgroundColor(Color.TRANSPARENT);
            fill.setStyle(Paint.Style.FILL);
            border.setStyle(Paint.Style.STROKE);
            border.setColor(0xFFFFFFFF);
            regionFill.setStyle(Paint.Style.FILL);
            regionFill.setColor(0x2233B5E5);
            regionBorder.setStyle(Paint.Style.STROKE);
            regionBorder.setColor(Color.WHITE);
            regionBorder.setStrokeWidth(dp(2.5f));
            pointerFill.setStyle(Paint.Style.FILL);
            pointerFill.setColor(0xDD1976D2);
            pointerBorder.setStyle(Paint.Style.STROKE);
            pointerBorder.setColor(Color.WHITE);
            pointerBorder.setStrokeWidth(dp(2));
            label.setColor(Color.WHITE);
            label.setTextSize(dp(14));
            label.setShadowLayer(dp(3), 0, dp(1), Color.BLACK);
            updatePaints();
        }

        void setCandidate(ScreenCandidate c) { candidate = c; invalidate(); }
        void setConfirmed(boolean value) { confirmed = value; updatePaints(); invalidate(); }

        void setDirectState(float screenX, float screenY, boolean regionMode, Rect screenRegion) {
            pointerX = screenX;
            pointerY = screenY;
            directRegionMode = regionMode;
            if (screenRegion == null) directRegion.setEmpty();
            else directRegion.set(screenRegion);
            invalidate();
        }

        private void updatePaints() {
            fill.setColor(confirmed ? 0x552196F3 : 0x332196F3);
            border.setStrokeWidth(dp(confirmed ? 4 : 2));
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
                    c.drawRect(rr, regionBorder);
                    String size = Math.max(0, directRegion.width()) + " × " + Math.max(0, directRegion.height());
                    float lx = Math.max(dp(8), Math.min(rr.left, getWidth() - dp(120)));
                    float ly = rr.top > dp(28) ? rr.top - dp(8) : Math.min(getHeight() - dp(8), rr.bottom + dp(20));
                    c.drawText(size, lx, ly, label);
                }
            } else if (candidate != null) {
                Rect r = candidate.bounds();
                r.offset(-overlayLocation[0], -overlayLocation[1]);
                if (Rect.intersects(localFrame, r)) {
                    c.drawRect(r, fill);
                    c.drawRect(r, border);
                    String base = candidate.type() == ScreenCandidate.Type.ROOT ? "整屏 View" : candidate.label();
                    String text = confirmed ? "已锁定 · " + base : base;
                    float x = Math.max(dp(8), Math.min(r.left, getWidth() - dp(180)));
                    float y = r.top > dp(28) ? r.top - dp(8)
                            : Math.min(getHeight() - dp(8), r.bottom + dp(20));
                    if (text.length() > 90) text = text.substring(0, 90) + "…";
                    c.drawText(text, x, y, label);
                }
            }

            // FV keeps a small helper/handler view after the main FloatIconView expands fullscreen.
            // Draw an equivalent non-touchable pointer marker at the transformed selection hotspot.
            if (!Float.isNaN(pointerX) && !Float.isNaN(pointerY)) {
                float px = pointerX - overlayLocation[0];
                float py = pointerY - overlayLocation[1];
                float r = dp(10);
                c.drawCircle(px, py, r, pointerFill);
                c.drawCircle(px, py, r, pointerBorder);
            }
        }

        private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
    }
}
