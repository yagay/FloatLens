package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.util.Collections;
import java.util.List;

/**
 * Cached Accessibility candidate + highlight layer used by ViewSelectionEngine.
 *
 * Mirrors FV o1/n1: this class is only the selected-View visual. The gesture/service state machine
 * decides when d(false)/d(true) happens; this layer merely renders TRACKING red or READY yellow.
 * Region dragging is a separate visual and never participates in this state.
 */
public final class ViewHoverOverlay {
    public interface CandidateListener {
        void onCandidateChanged(ScreenCandidate candidate);
    }

    private static final int LARGE_TARGET_PERCENT = 72;

    private final Context context;
    private final FlOverlayWindowHost windowHost;
    private final LensAccessibilityService accessibility;
    private final ScreenSelectionModel model = new ScreenSelectionModel();
    private HoverView view;
    private ViewCandidateFrameOverlay largeCandidateFrame;
    private ScreenCandidate current;
    private SelectionVisualState visualState = SelectionVisualState.TRACKING;
    private CandidateListener candidateListener;

    public ViewHoverOverlay(Context c) {
        context = c.getApplicationContext();
        windowHost = new FlOverlayWindowHost(context);
        accessibility = LensAccessibilityService.get();
    }

    public boolean available() { return accessibility != null; }

    public void setCandidateListener(CandidateListener listener) {
        candidateListener = listener;
    }

    /** FV o1/n1.d(false/true) equivalent. */
    public void setVisualState(SelectionVisualState next) {
        if (next == null) next = SelectionVisualState.TRACKING;
        if (visualState == next) return;
        visualState = next;
        if (view != null) view.setVisualState(next);
        if (largeCandidateFrame != null) largeCandidateFrame.setVisualState(next);
        DiagnosticLog.i(context, "VIEW_HOVER", "visualState=" + next
                + " candidate=" + (current == null ? "none" : current.type()));
    }

    /** Snapshot the Accessibility target tree once; MOVE later uses only cached geometry. */
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
            int systemUi = 0;
            for (ScreenCandidate candidate : cached) {
                if (candidate.type() == ScreenCandidate.Type.VIEW) viewCount++;
                if (candidate.type() == ScreenCandidate.Type.ROOT || candidate.fullscreenLike()) broad++;
                if ("com.android.systemui".equals(candidate.packageName())) systemUi++;
            }
            DiagnosticLog.i(context, "FL_TREE_CACHE", "READY total=" + cached.size()
                    + " view=" + viewCount + " broad=" + broad + " systemUi=" + systemUi);
        } catch (Throwable t) {
            DiagnosticLog.i(context, "FL_TREE_CACHE", "refresh failed=" + t);
            model.setAccessibility(Collections.emptyList());
        }
    }

    /** Cached contains(x,y) lookup only; Accessibility is never traversed on MOVE. */
    public void update(float selectionX, float selectionY) {
        if (accessibility == null) return;
        ensureTreeCache();

        ScreenCandidate next = model.selectAt(selectionX, selectionY);
        if (sameCandidate(current, next)) return;

        current = next;
        visualState = SelectionVisualState.TRACKING;

        if (shouldRenderCandidate(next)) {
            closeLargeCandidateFrame();
            ensureView();
            if (view != null) {
                view.setVisualState(SelectionVisualState.TRACKING);
                view.setCandidate(next);
            }
        } else {
            detachView();
            if (next != null) {
                if (largeCandidateFrame == null) largeCandidateFrame = new ViewCandidateFrameOverlay(context);
                largeCandidateFrame.setVisualState(SelectionVisualState.TRACKING);
                largeCandidateFrame.show(next.bounds());
            } else {
                closeLargeCandidateFrame();
            }
        }

        if (next != null) {
            DiagnosticLog.i(context, "VIEW_HOVER", "candidate_changed tracking=true source=" + next.source()
                    + " type=" + next.type() + " screenBounds=" + next.bounds()
                    + " depth=" + next.depth() + " textLen=" + next.text().length()
                    + " class=" + next.className() + " id=" + next.viewId()
                    + " pkg=" + next.packageName()
                    + " fullscreenLike=" + next.fullscreenLike()
                    + " visual=" + shouldRenderCandidate(next));
        } else {
            DiagnosticLog.i(context, "VIEW_HOVER", "candidate_changed none selection="
                    + Math.round(selectionX) + "," + Math.round(selectionY)
                    + " cached=" + model.size());
        }

        CandidateListener listener = candidateListener;
        if (listener != null) listener.onCandidateChanged(next);
    }

    public ScreenCandidate currentCandidate() { return current; }

    public void cancel() {
        detachView();
        closeLargeCandidateFrame();
        current = null;
        visualState = SelectionVisualState.TRACKING;
        candidateListener = null;
        model.setAccessibility(Collections.emptyList());
        model.setVisual(Collections.emptyList());
    }

    private void ensureView() {
        if (accessibility == null || view != null) return;
        HoverView next = new HoverView(context);
        next.setVisualState(visualState);
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
        if (windowHost.add(next, lp, "view_hover")) {
            view = next;
            DiagnosticLog.i(context, "VIEW_HOVER", "visual_attach accessibilityHost="
                    + windowHost.isAccessibilityHosted() + " state=" + visualState);
        }
    }

    private void detachView() {
        if (view == null) return;
        windowHost.remove(view, "view_hover");
        view = null;
        DiagnosticLog.i(context, "VIEW_HOVER", "visual_detach");
    }

    private void closeLargeCandidateFrame() {
        if (largeCandidateFrame != null) largeCandidateFrame.close();
        largeCandidateFrame = null;
    }

    private boolean shouldRenderCandidate(ScreenCandidate candidate) {
        if (candidate == null || candidate.type() == ScreenCandidate.Type.ROOT) return false;
        Rect bounds = candidate.bounds();
        if (bounds == null || bounds.isEmpty()) return false;
        Rect screen;
        try { screen = accessibility.screenBounds(); }
        catch (Throwable t) { return true; }
        if (screen == null || screen.isEmpty()) return true;
        long screenArea = (long) screen.width() * screen.height();
        long area = (long) bounds.width() * bounds.height();
        return screenArea <= 0 || area * 100L < screenArea * LARGE_TARGET_PERCENT;
    }

    private boolean sameCandidate(ScreenCandidate a, ScreenCandidate b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.stableKey().equals(b.stableKey());
    }

    private static final class HoverView extends View {
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint unusedBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelUnused = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] overlayLocation = new int[2];
        private ScreenCandidate candidate;
        private SelectionVisualState visualState = SelectionVisualState.TRACKING;
        private int lastLoggedOriginX = Integer.MIN_VALUE;
        private int lastLoggedOriginY = Integer.MIN_VALUE;

        HoverView(Context c) {
            super(c);
            setBackgroundColor(Color.TRANSPARENT);
            SelectionVisuals.configureFramePaints(c, border, unusedBorder, visualState);
            SelectionVisuals.configureTextPaints(c, labelUnused, label, 14f);
        }

        void setVisualState(SelectionVisualState next) {
            if (next == null) next = SelectionVisualState.TRACKING;
            if (visualState == next) return;
            visualState = next;
            border.setColor(SelectionVisuals.frameColor(next));
            invalidate();
        }

        void setCandidate(ScreenCandidate candidate) {
            this.candidate = candidate;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            getLocationOnScreen(overlayLocation);
            if (overlayLocation[0] != lastLoggedOriginX || overlayLocation[1] != lastLoggedOriginY) {
                lastLoggedOriginX = overlayLocation[0];
                lastLoggedOriginY = overlayLocation[1];
                DiagnosticLog.i(getContext(), "VIEW_DRAW", "overlayOrigin="
                        + overlayLocation[0] + "," + overlayLocation[1]
                        + " size=" + getWidth() + "x" + getHeight());
            }
            if (candidate == null) return;

            Rect localFrame = new Rect(0, 0, getWidth(), getHeight());
            Rect r = candidate.bounds();
            r.offset(-overlayLocation[0], -overlayLocation[1]);
            if (!Rect.intersects(localFrame, r)) return;

            SelectionVisuals.drawFrame(canvas, r, border, unusedBorder);
            String text = (candidate.type() == ScreenCandidate.Type.ROOT || candidate.fullscreenLike())
                    ? "整屏 View" : candidate.label();
            float x = Math.max(dp(8), Math.min(r.left, getWidth() - dp(180)));
            float y = r.top > dp(28) ? r.top - dp(8)
                    : Math.min(getHeight() - dp(8), r.bottom + dp(20));
            if (text.length() > 90) text = text.substring(0, 90) + "…";
            SelectionVisuals.drawText(canvas, text, x, y, labelUnused, label);
        }

        private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
    }
}
