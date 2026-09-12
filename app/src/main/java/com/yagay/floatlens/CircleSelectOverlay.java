package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Magnifier;

import java.util.ArrayList;
import java.util.List;

/** Frozen-screen Circle Select workspace with a fast per-session text index. */
public final class CircleSelectOverlay {
    private static WorkspaceView active;

    public static synchronized boolean show(Context c, Bitmap screenshot, Runnable onClosed) {
        if (screenshot == null || screenshot.isRecycled()) return false;
        dismissActive("replace");
        Context app = c.getApplicationContext();
        FlOverlayWindowHost host = new FlOverlayWindowHost(app);
        Rect contentBounds = CircleSelectFrame.contentBounds(app);
        Rect displayBounds = CircleSelectFrame.displayBounds(app);
        boolean shadeExpanded = FlSystemPanelController.notificationShadeExpanded();

        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (shadeExpanded) flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                Math.max(1, contentBounds.width()), Math.max(1, contentBounds.height()),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = contentBounds.left - displayBounds.left;
        lp.y = contentBounds.top - displayBounds.top;

        WorkspaceView view = new WorkspaceView(app, host, lp, screenshot, onClosed, !shadeExpanded);
        if (!host.add(view, lp, "circle_select")) {
            DiagnosticLog.i(app, "CIRCLE_SELECT", "overlay add failed");
            return false;
        }

        active = view;
        if (!shadeExpanded) view.promoteKeyFocus("initial_no_shade");
        view.startRecognitionSession();
        DiagnosticLog.i(app, "CIRCLE_SELECT", "overlay shown "
                + screenshot.getWidth() + "x" + screenshot.getHeight()
                + " bounds=" + contentBounds.toShortString()
                + " accessibilityHost=" + host.isAccessibilityHosted());
        return true;
    }

    public static synchronized void promoteActiveFocus(String reason) {
        WorkspaceView v = active;
        if (v != null) v.promoteKeyFocus(reason == null ? "cleanup_complete" : reason);
    }

    public static synchronized void dismissActive(String reason) {
        WorkspaceView v = active;
        active = null;
        if (v != null) v.close(reason == null ? "dismiss" : reason);
    }

    private static synchronized void onClosed(WorkspaceView v) {
        if (active == v) active = null;
    }

    private static final class WorkspaceView extends View {
        private static final int MODE_NONE = 0;
        private static final int MODE_TEXT = 1;
        private static final int MODE_START_HANDLE = 2;
        private static final int MODE_END_HANDLE = 3;
        private static final int MODE_CIRCLE = 4;
        private static final long RECT_SNAP_PREVIEW_MS = 170L;
        private static final float HANDLE_SNAP_DISTANCE_DP = 96f;
        private static final float TEXT_TAP_SNAP_DISTANCE_DP = 18f;
        private static final float ROI_RESULT_SNAP_DISTANCE_DP = 42f;
        private static final float TAP_GESTURE_SLOP_DP = 18f;
        private static final long SYSTEM_NAV_FOCUS_LOSS_DELAY_MS = 80L;

        private final Context context;
        private final FlOverlayWindowHost host;
        private final WindowManager.LayoutParams windowLayout;
        private final Bitmap screenshot;
        private final Runnable onClosed;
        private final CircleTextSelectionModel selection;
        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint toolbarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint toolbarTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ArrayList<PointF> circlePoints = new ArrayList<>();
        private final RectF snappedCircleRect = new RectF();
        private final RectF closeRect = new RectF();

        private CircleRecognitionSession recognitionSession;
        private OcrDocument pendingDocument;
        private boolean fastIndexReady;
        private boolean tapRefining;
        private float refinementTapX;
        private float refinementTapY;
        private boolean closed;
        private boolean circleResolving;
        private boolean hadWindowFocus;
        private boolean keyFocusEnabled;
        private int mode = MODE_NONE;
        private boolean closePressed;
        private Magnifier magnifier;

        WorkspaceView(Context c, FlOverlayWindowHost host, WindowManager.LayoutParams windowLayout,
                      Bitmap screenshot, Runnable onClosed, boolean keyFocusEnabled) {
            super(c);
            context = c;
            this.host = host;
            this.windowLayout = windowLayout;
            this.screenshot = screenshot;
            this.onClosed = onClosed;
            this.keyFocusEnabled = keyFocusEnabled;
            selection = new CircleTextSelectionModel(screenshot.getWidth(), screenshot.getHeight());
            setClickable(true);
            setFocusable(true);
            setFocusableInTouchMode(true);

            shadePaint.setColor(0x33000000);
            selectedPaint.setColor(0x884285F4);
            selectedPaint.setStyle(Paint.Style.FILL);
            linePaint.setColor(Color.WHITE);
            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setStrokeWidth(dp(3));
            linePaint.setStrokeCap(Paint.Cap.ROUND);
            linePaint.setStrokeJoin(Paint.Join.ROUND);
            handlePaint.setColor(0xFF4285F4);
            handlePaint.setStyle(Paint.Style.FILL);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(dp(15));
            textPaint.setShadowLayer(dp(3), 0, dp(1), Color.BLACK);
            toolbarPaint.setColor(0xEE202124);
            toolbarPaint.setStyle(Paint.Style.FILL);
            toolbarTextPaint.setColor(Color.WHITE);
            toolbarTextPaint.setTextSize(dp(14));
            toolbarTextPaint.setTextAlign(Paint.Align.CENTER);
        }

        void promoteKeyFocus(String reason) {
            if (closed) return;
            if (!keyFocusEnabled) {
                windowLayout.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                if (!host.update(this, windowLayout, "circle_select_focus")) return;
                keyFocusEnabled = true;
            }
            post(() -> {
                if (!closed && isAttachedToWindow()) requestFocus();
            });
        }

        void startRecognitionSession() {
            recognitionSession = new CircleRecognitionSession(context, screenshot,
                    new CircleRecognitionSession.Callback() {
                        @Override public void onUpdate(OcrDocument document,
                                                       CircleRecognitionSession.Stage stage,
                                                       boolean ready) {
                            if (closed) return;
                            fastIndexReady = fastIndexReady || ready;
                            if (stage == CircleRecognitionSession.Stage.ROI_PRECISE) {
                                tapRefining = false;
                                pendingDocument = null;
                                selection.setDocument(document);
                                int hit = selection.findSelectionWord(refinementTapX, refinementTapY,
                                        getWidth(), getHeight(), dp(ROI_RESULT_SNAP_DISTANCE_DP));
                                if (hit >= 0) {
                                    selection.selectSingle(hit);
                                    invalidate();
                                    post(WorkspaceView.this::showSelectionMenu);
                                } else {
                                    invalidate();
                                }
                                DiagnosticLog.i(context, "CIRCLE_SELECT", "roi update chars="
                                        + selection.size() + " hit=" + hit);
                                return;
                            }

                            if (!selection.hasSelection() && mode == MODE_NONE) {
                                selection.setDocument(document);
                                pendingDocument = null;
                            } else {
                                pendingDocument = document;
                            }
                            DiagnosticLog.i(context, "CIRCLE_SELECT", "index stage=" + stage
                                    + " chars=" + document.chars().size()
                                    + " applied=" + (pendingDocument == null)
                                    + " ready=" + fastIndexReady);
                            invalidate();
                        }

                        @Override public void onFailure(CircleRecognitionSession.Stage stage,
                                                        Throwable error, boolean ready) {
                            if (closed) return;
                            fastIndexReady = fastIndexReady || ready;
                            if (stage == CircleRecognitionSession.Stage.ROI_PRECISE) tapRefining = false;
                            DiagnosticLog.i(context, "CIRCLE_SELECT", "index failure stage=" + stage
                                    + " error=" + safe(error) + " ready=" + fastIndexReady);
                            invalidate();
                        }
                    });
            // Let the overlay attach and draw before walking the accessibility tree.
            post(() -> {
                if (!closed && recognitionSession != null) recognitionSession.start();
            });
        }

        private void applyPendingIfIdle() {
            if (pendingDocument == null || selection.hasSelection() || mode != MODE_NONE) return;
            selection.setDocument(pendingDocument);
            pendingDocument = null;
            invalidate();
        }

        @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
            super.onWindowFocusChanged(hasWindowFocus);
            if (closed || !keyFocusEnabled) return;
            if (hasWindowFocus) { hadWindowFocus = true; return; }
            if (!hadWindowFocus) return;
            postDelayed(() -> {
                if (closed || !keyFocusEnabled || !hadWindowFocus || hasWindowFocus()) return;
                close("system_navigation");
            }, SYSTEM_NAV_FOCUS_LOSS_DELAY_MS);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawBitmap(screenshot, null, new Rect(0, 0, getWidth(), getHeight()), bitmapPaint);
            canvas.drawRect(0, 0, getWidth(), getHeight(), shadePaint);

            if (selection.hasSelection()) {
                for (int index : selection.selectionIndices()) {
                    canvas.drawRoundRect(selection.wordViewRect(index, getWidth(), getHeight()),
                            dp(2), dp(2), selectedPaint);
                }
                drawHandles(canvas, selection.low(), selection.high());
            }

            if (circlePoints.size() > 1) {
                Path p = new Path();
                p.moveTo(circlePoints.get(0).x, circlePoints.get(0).y);
                for (int i = 1; i < circlePoints.size(); i++) p.lineTo(circlePoints.get(i).x, circlePoints.get(i).y);
                canvas.drawPath(p, linePaint);
            }

            if (!snappedCircleRect.isEmpty()) {
                canvas.drawRoundRect(snappedCircleRect, dp(8), dp(8), selectedPaint);
                canvas.drawRoundRect(snappedCircleRect, dp(8), dp(8), linePaint);
            }

            String status;
            if (circleResolving) status = "正在生成圈画截图…";
            else if (tapRefining) status = "正在精识别点击位置… · 圈画仍是截图";
            else if (!fastIndexReady && !selection.isEmpty()) status = "View 文字已可选 · 正在补充图片文字 · 圈画截图";
            else if (!fastIndexReady) status = "正在建立快速文字索引… · 圈画截图";
            else if (selection.isEmpty()) status = "未检测到可选文字 · 轻点可局部精识别 · 圈画截图";
            else status = "点按文字提取 · 手柄调整范围 · 圈画截图";
            canvas.drawText(status, dp(16), dp(34), textPaint);
            drawClose(canvas);
        }

        private void drawClose(Canvas c) {
            float size = dp(38);
            closeRect.set(getWidth() - size - dp(12), dp(10), getWidth() - dp(12), dp(10) + size);
            c.drawRoundRect(closeRect, size / 2f, size / 2f, toolbarPaint);
            Paint p = new Paint(toolbarTextPaint);
            p.setTextSize(dp(22));
            c.drawText("×", closeRect.centerX(), closeRect.centerY() + dp(7), p);
        }

        private void drawHandles(Canvas c, int lo, int hi) {
            if (lo < 0 || hi < 0 || lo >= selection.size() || hi >= selection.size()) return;
            RectF first = selection.wordViewRect(lo, getWidth(), getHeight());
            RectF last = selection.wordViewRect(hi, getWidth(), getHeight());
            float stem = dp(7), radius = dp(7);
            c.drawLine(first.left, first.bottom, first.left, first.bottom + stem, handlePaint);
            c.drawCircle(first.left, first.bottom + stem, radius, handlePaint);
            c.drawLine(last.right, last.bottom, last.right, last.bottom + stem, handlePaint);
            c.drawCircle(last.right, last.bottom + stem, radius, handlePaint);
        }

        @Override public boolean dispatchKeyEvent(KeyEvent event) {
            if (event != null && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled() && !closed) close("back");
                return true;
            }
            return super.dispatchKeyEvent(event);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (closed || circleResolving) return true;
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    applyPendingIfIdle();
                    snappedCircleRect.setEmpty();
                    if (closeRect.contains(x, y)) {
                        dismissMagnifier(); closePressed = true; return true;
                    }
                    closePressed = false;
                    FloatActionMenu.dismiss();
                    FloatMenuAnchor.clear();

                    if (selection.hasSelection()) {
                        int handle = hitSelectionHandle(x, y);
                        if (handle != MODE_NONE) {
                            mode = handle;
                            showSelectionMagnifier();
                            return true;
                        }
                    }

                    int hit = selection.findSelectionWord(x, y, getWidth(), getHeight(),
                            dp(TEXT_TAP_SNAP_DISTANCE_DP));
                    if (hit >= 0) {
                        selection.selectSingle(hit);
                        mode = MODE_TEXT;
                        circlePoints.clear();
                        invalidate();
                        return true;
                    }

                    dismissMagnifier();
                    selection.clear();
                    mode = MODE_CIRCLE;
                    circlePoints.clear();
                    circlePoints.add(new PointF(x, y));
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_MOVE -> {
                    if (closePressed) return true;
                    if (mode == MODE_TEXT || mode == MODE_START_HANDLE || mode == MODE_END_HANDLE) {
                        int hit = findSelectionWord(x, y);
                        if (hit >= 0) { updateSelectionEndpoint(hit); invalidate(); }
                        if (mode == MODE_START_HANDLE || mode == MODE_END_HANDLE) showSelectionMagnifier();
                        return true;
                    }
                    if (mode == MODE_CIRCLE) {
                        PointF last = circlePoints.isEmpty() ? null : circlePoints.get(circlePoints.size() - 1);
                        if (last == null || Math.abs(last.x - x) >= 1f || Math.abs(last.y - y) >= 1f) {
                            circlePoints.add(new PointF(x, y)); invalidate();
                        }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP -> {
                    dismissMagnifier();
                    if (closePressed) {
                        closePressed = false;
                        if (closeRect.contains(x, y)) close("user_close");
                        return true;
                    }
                    if (mode == MODE_CIRCLE) {
                        circlePoints.add(new PointF(x, y));
                        ArrayList<PointF> gesture = new ArrayList<>(circlePoints);
                        RectF snapped = CircleCropGeometry.snapToRectangle(
                                circlePoints, getWidth(), getHeight(), getResources().getDisplayMetrics().density);
                        circlePoints.clear(); mode = MODE_NONE;
                        if (snapped != null) {
                            snappedCircleRect.set(snapped);
                            circleResolving = true;
                            invalidate();
                            postDelayed(() -> finishSnappedCircle(new RectF(snapped)), RECT_SNAP_PREVIEW_MS);
                        } else if (isTapLike(gesture)) {
                            startTapRefinement(x, y);
                        } else {
                            invalidate();
                        }
                        return true;
                    }
                    if (mode == MODE_TEXT || mode == MODE_START_HANDLE || mode == MODE_END_HANDLE) {
                        int finalHit = findSelectionWord(x, y);
                        if (finalHit >= 0) updateSelectionEndpoint(finalHit);
                        mode = MODE_NONE;
                        invalidate();
                        showSelectionMenu();
                        return true;
                    }
                    mode = MODE_NONE;
                    return true;
                }
                case MotionEvent.ACTION_CANCEL -> {
                    dismissMagnifier(); closePressed = false; mode = MODE_NONE;
                    circlePoints.clear(); snappedCircleRect.setEmpty(); invalidate(); return true;
                }
            }
            return true;
        }

        private boolean isTapLike(List<PointF> points) {
            if (points == null || points.isEmpty()) return false;
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (PointF p : points) {
                if (p == null) continue;
                minX = Math.min(minX, p.x); minY = Math.min(minY, p.y);
                maxX = Math.max(maxX, p.x); maxY = Math.max(maxY, p.y);
            }
            float slop = dp(TAP_GESTURE_SLOP_DP);
            return minX != Float.MAX_VALUE && maxX - minX <= slop && maxY - minY <= slop;
        }

        private void startTapRefinement(float viewX, float viewY) {
            if (recognitionSession == null || tapRefining || closed) {
                invalidate();
                return;
            }
            Rect region = imageRectAroundTap(viewX, viewY);
            if (region == null || region.isEmpty()) {
                invalidate();
                return;
            }
            refinementTapX = viewX;
            refinementTapY = viewY;
            tapRefining = true;
            DiagnosticLog.i(context, "CIRCLE_SELECT", "tap miss -> roi precise "
                    + region.toShortString());
            invalidate();
            recognitionSession.refine(region);
        }

        private Rect imageRectAroundTap(float viewX, float viewY) {
            if (getWidth() <= 0 || getHeight() <= 0) return null;
            float halfW = Math.min(240f, getWidth() * 0.20f);
            float halfH = Math.min(100f, getHeight() * 0.055f);
            RectF viewRect = new RectF(
                    Math.max(0f, viewX - halfW),
                    Math.max(0f, viewY - halfH),
                    Math.min(getWidth(), viewX + halfW),
                    Math.min(getHeight(), viewY + halfH));
            return imageRectFromView(viewRect);
        }

        private void updateSelectionEndpoint(int hit) {
            if (mode == MODE_START_HANDLE) selection.updateStart(hit);
            else selection.updateEnd(hit);
        }

        private int hitSelectionHandle(float x, float y) {
            int lo = selection.low(), hi = selection.high();
            if (lo < 0 || hi < 0) return MODE_NONE;
            RectF first = selection.wordViewRect(lo, getWidth(), getHeight());
            RectF last = selection.wordViewRect(hi, getWidth(), getHeight());
            float stem = dp(7), r = dp(28);
            if (distance(x, y, first.left, first.bottom + stem) <= r) {
                return selection.startIndex() <= selection.endIndex() ? MODE_START_HANDLE : MODE_END_HANDLE;
            }
            if (distance(x, y, last.right, last.bottom + stem) <= r) {
                return selection.startIndex() <= selection.endIndex() ? MODE_END_HANDLE : MODE_START_HANDLE;
            }
            return MODE_NONE;
        }

        private int findSelectionWord(float x, float y) {
            return selection.findSelectionWord(x, y, getWidth(), getHeight(), dp(HANDLE_SNAP_DISTANCE_DP));
        }

        private Rect selectionScreenRect() {
            RectF union = selection.selectionViewBounds(getWidth(), getHeight());
            if (union == null || union.isEmpty()) return null;
            int[] loc = new int[2];
            try { getLocationOnScreen(loc); } catch (Throwable ignored) { return null; }
            return new Rect(Math.round(union.left) + loc[0], Math.round(union.top) + loc[1],
                    Math.round(union.right) + loc[0], Math.round(union.bottom) + loc[1]);
        }

        private Rect imageRectFromView(RectF viewRect) {
            if (viewRect == null || viewRect.isEmpty() || getWidth() <= 0 || getHeight() <= 0) return null;
            float sx = screenshot.getWidth() / (float) getWidth();
            float sy = screenshot.getHeight() / (float) getHeight();
            int left = Math.max(0, Math.min(screenshot.getWidth() - 1, (int) Math.floor(viewRect.left * sx)));
            int top = Math.max(0, Math.min(screenshot.getHeight() - 1, (int) Math.floor(viewRect.top * sy)));
            int right = Math.max(left + 1, Math.min(screenshot.getWidth(), (int) Math.ceil(viewRect.right * sx)));
            int bottom = Math.max(top + 1, Math.min(screenshot.getHeight(), (int) Math.ceil(viewRect.bottom * sy)));
            return new Rect(left, top, right, bottom);
        }

        private Rect screenRectFromView(RectF viewRect) {
            int[] loc = new int[2];
            try { getLocationOnScreen(loc); } catch (Throwable ignored) { loc[0] = loc[1] = 0; }
            return new Rect(
                    Math.round(viewRect.left) + loc[0],
                    Math.round(viewRect.top) + loc[1],
                    Math.round(viewRect.right) + loc[0],
                    Math.round(viewRect.bottom) + loc[1]);
        }

        private void finishSnappedCircle(RectF viewRect) {
            if (closed) return;
            Bitmap crop = CircleCropGeometry.crop(screenshot, viewRect, getWidth(), getHeight());
            if (crop == null) {
                circleResolving = false;
                snappedCircleRect.setEmpty();
                invalidate();
                return;
            }

            Rect anchor = screenRectFromView(viewRect);
            DiagnosticLog.i(context, "CIRCLE_SELECT", "circle screenshot="
                    + crop.getWidth() + "x" + crop.getHeight()
                    + " anchor=" + anchor.toShortString());
            close("circle_screenshot");
            boolean shown = ResultSurfaceRouter.showScreenshot(context, crop, anchor);
            if (!shown) {
                DiagnosticLog.i(context, "CIRCLE_SELECT", "screenshot result failed -> save fallback");
                ScreenshotController.save(context, crop);
            }
        }

        private void showSelectionMenu() {
            if (closed || !selection.hasSelection()) return;
            String selected = selection.selectedText();
            if (selected.isBlank()) return;
            FloatActionMenu.showTextAt(context, selected, () -> {
                if (selection.isEmpty()) return;
                selection.selectAll();
                invalidate();
                post(() -> FloatActionMenu.showTextAt(
                        context, selection.selectedText(), null, selectionScreenRect()));
            }, selectionScreenRect());
        }

        private void showSelectionMagnifier() {
            if (closed || getWidth() <= 0 || getHeight() <= 0 || !isAttachedToWindow()) return;
            int index = mode == MODE_START_HANDLE ? selection.startIndex()
                    : mode == MODE_END_HANDLE ? selection.endIndex() : -1;
            if (index < 0 || index >= selection.size()) return;
            RectF symbol = selection.wordViewRect(index, getWidth(), getHeight());
            if (symbol.isEmpty()) return;
            boolean rightEdge = mode == MODE_START_HANDLE
                    ? selection.startIndex() > selection.endIndex()
                    : selection.startIndex() <= selection.endIndex();
            float sourceX = Math.max(0f, Math.min(getWidth() - 1f, rightEdge ? symbol.right : symbol.left));
            float sourceY = Math.max(0f, Math.min(getHeight() - 1f, symbol.centerY()));
            try {
                if (magnifier == null) magnifier = new Magnifier(this);
                magnifier.show(sourceX, sourceY);
            } catch (Throwable t) { DiagnosticLog.i(context, "CIRCLE_MAGNIFIER", "show failed=" + safe(t)); }
        }

        private void dismissMagnifier() {
            Magnifier m = magnifier;
            if (m != null) try { m.dismiss(); } catch (Throwable ignored) {}
        }

        void close(String reason) {
            if (closed) return;
            closed = true;
            CircleRecognitionSession session = recognitionSession;
            recognitionSession = null;
            if (session != null) session.cancel();
            dismissMagnifier(); magnifier = null;
            FloatActionMenu.dismiss(); FloatMenuAnchor.clear();
            removeCallbacks(null);
            host.remove(this, "circle_select");
            try { if (!screenshot.isRecycled()) screenshot.recycle(); } catch (Throwable ignored) {}
            CircleSelectOverlay.onClosed(this);
            if (onClosed != null) try { onClosed.run(); } catch (Throwable ignored) {}
            DiagnosticLog.i(context, "CIRCLE_SELECT", "closed reason=" + reason);
        }

        private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
        private float distance(float x1, float y1, float x2, float y2) {
            return (float) Math.hypot(x1 - x2, y1 - y2);
        }
        private String safe(Throwable t) {
            if (t == null) return "unknown";
            String m = t.getMessage();
            return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
        }
    }

    private CircleSelectOverlay() {}
}
