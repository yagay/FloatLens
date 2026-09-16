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
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Frozen-screen Circle workflow with two separate modes.
 *
 * Text is resolved only after TAP/SCRIBBLE/HIGHLIGHT ends. The resolver first checks fresh native
 * View text; only a View miss triggers OCR on a small ROI around that gesture. Both sources are
 * normalized into absolute screen-space characters and rendered with the old selectable-text model.
 *
 * A closed CIRCLE never runs text recognition. It creates an exact editable screenshot rectangle;
 * the user adjusts it and presses 完成 before the image action menu is shown.
 */
final class GoogleCircleInlineOverlay {
    private static WorkspaceView active;

    static synchronized boolean show(Context c, GoogleCircleCapture.Frame frame, Runnable onClosed) {
        if (c == null || frame == null || frame.bitmap == null || frame.bitmap.isRecycled()) {
            return false;
        }

        dismissActive("replace");
        Context app = c.getApplicationContext();
        FlOverlayWindowHost host = new FlOverlayWindowHost(app);
        Rect display = ScreenGeometry.displayBounds(app);
        Rect bounds = frame.screenBounds;
        boolean shadeExpanded = FlSystemPanelController.notificationShadeExpanded();

        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (shadeExpanded) flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                Math.max(1, bounds.width()), Math.max(1, bounds.height()),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                flags, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = bounds.left - display.left;
        lp.y = bounds.top - display.top;

        WorkspaceView view = new WorkspaceView(app, host, lp, frame,
                onClosed, !shadeExpanded);
        if (!host.add(view, lp, "google_circle_inline")) return false;

        active = view;
        if (!shadeExpanded) view.promoteKeyFocus("initial");
        DiagnosticLog.i(app, "G_CIRCLE_INLINE", "overlay shown frame=" + bounds.toShortString()
                + " bitmap=" + frame.bitmap.getWidth() + "x" + frame.bitmap.getHeight()
                + " textRecognition=deferred"
                + " classifier=view_then_local_ocr"
                + " screenshotMode=circle_edit_confirm autoExpand=false");
        return true;
    }

    static synchronized void promoteActiveFocus(String reason) {
        WorkspaceView view = active;
        if (view != null) view.promoteKeyFocus(reason);
    }

    static synchronized void dismissActive(String reason) {
        WorkspaceView view = active;
        active = null;
        if (view != null) view.close(reason == null ? "dismiss" : reason);
    }

    private static synchronized void onClosed(WorkspaceView view) {
        if (active == view) active = null;
    }

    private static final class WorkspaceView extends View {
        private static final int MODE_NONE = 0;
        private static final int MODE_DRAW = 1;
        private static final int MODE_SCREEN_MOVE = 2;
        private static final int MODE_SCREEN_LEFT = 3;
        private static final int MODE_SCREEN_TOP = 4;
        private static final int MODE_SCREEN_RIGHT = 5;
        private static final int MODE_SCREEN_BOTTOM = 6;
        private static final int MODE_TEXT_START = 7;
        private static final int MODE_TEXT_END = 8;

        private static final float VIEW_TEXT_TAP_SNAP_DP = 10f;
        private static final float OCR_TEXT_TAP_SNAP_DP = 44f;
        private static final float TEXT_HANDLE_HIT_DP = 28f;
        private static final float TEXT_HANDLE_SNAP_DP = 96f;
        private static final float RANGE_TOLERANCE_DP = 6f;

        private static final float CLOSE_SIZE_DP = 42f;
        private static final float CLOSE_EDGE_MARGIN_DP = 14f;
        private static final float CLOSE_BOTTOM_GAP_DP = 18f;
        private static final long CLOSE_MOVE_LONG_PRESS_MS = 350L;
        private static final String PREF_CLOSE_X_BP = "circle_cancel_x_bp_v1";
        private static final String PREF_CLOSE_Y_BP = "circle_cancel_y_bp_v1";

        private final Context context;
        private final FlOverlayWindowHost host;
        private final WindowManager.LayoutParams windowLayout;
        private final GoogleCircleCapture.Frame frame;
        private final Runnable onClosed;
        private final ScreenBitmapTransform textTransform;
        private final CircleTextSelectionModel textSelection;
        private final Runnable closeLongPressRunnable;

        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint lightShadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokeGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint screenshotFramePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint screenshotHandlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textSelectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textHandlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint closePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint closeGlyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hintTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint confirmPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint confirmTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final ArrayList<PointF> stroke = new ArrayList<>();
        private final RectF closeRect = new RectF();
        private final RectF confirmRect = new RectF();

        /** Non-null only in screenshot mode. Text mode never owns this rectangle. */
        private GoogleCircleSelection.Selection screenshotSelection;
        private RectF editOrigin;
        private PointF editStart;
        private int editMode = MODE_NONE;
        private int textResolutionGeneration;
        private boolean resolvingText;
        private boolean closed;
        private boolean closePressed;
        private boolean closeDragging;
        private float closeDragOffsetX;
        private float closeDragOffsetY;
        private float closeCenterX = Float.NaN;
        private float closeCenterY = Float.NaN;
        private boolean confirmPressed;
        private boolean keyFocusEnabled;
        private String selectedTextSource = "";

        WorkspaceView(Context c, FlOverlayWindowHost host,
                      WindowManager.LayoutParams windowLayout,
                      GoogleCircleCapture.Frame frame,
                      Runnable onClosed, boolean keyFocusEnabled) {
            super(c);
            context = c;
            this.host = host;
            this.windowLayout = windowLayout;
            this.frame = frame;
            this.onClosed = onClosed;
            this.keyFocusEnabled = keyFocusEnabled;
            closeLongPressRunnable = () -> {
                if (closed || !closePressed) return;
                closeDragging = true;
                try {
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                } catch (Throwable ignored) {
                }
                DiagnosticLog.i(context, "G_CIRCLE_CANCEL",
                        "long_press_drag_start center=" + Math.round(closeRect.centerX())
                                + "," + Math.round(closeRect.centerY()));
                invalidate();
            };

            textTransform = new ScreenBitmapTransform(frame.screenBounds,
                    frame.bitmap.getWidth(), frame.bitmap.getHeight());
            textSelection = new CircleTextSelectionModel(textTransform);
            textSelection.setChars(List.of());

            setClickable(true);
            setFocusable(true);
            setFocusableInTouchMode(true);

            shadePaint.setColor(0x50000000);
            lightShadePaint.setColor(0x18000000);

            strokeGlow.setColor(0x664285F4);
            strokeGlow.setStyle(Paint.Style.STROKE);
            strokeGlow.setStrokeWidth(dp(10));
            strokeGlow.setStrokeCap(Paint.Cap.ROUND);
            strokeGlow.setStrokeJoin(Paint.Join.ROUND);

            strokePaint.setColor(Color.WHITE);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(dp(3));
            strokePaint.setStrokeCap(Paint.Cap.ROUND);
            strokePaint.setStrokeJoin(Paint.Join.ROUND);

            screenshotFramePaint.setColor(Color.WHITE);
            screenshotFramePaint.setStyle(Paint.Style.STROKE);
            screenshotFramePaint.setStrokeWidth(dp(2));

            screenshotHandlePaint.setColor(0xFF4285F4);
            screenshotHandlePaint.setStyle(Paint.Style.FILL);

            textSelectedPaint.setColor(0x884285F4);
            textSelectedPaint.setStyle(Paint.Style.FILL);

            textHandlePaint.setColor(0xFF4285F4);
            textHandlePaint.setStyle(Paint.Style.FILL);

            closePaint.setColor(0xD9222222);
            closeGlyphPaint.setColor(Color.WHITE);
            closeGlyphPaint.setStyle(Paint.Style.STROKE);
            closeGlyphPaint.setStrokeWidth(dp(2));
            closeGlyphPaint.setStrokeCap(Paint.Cap.ROUND);

            hintPaint.setColor(0xD9222222);
            hintTextPaint.setColor(Color.WHITE);
            hintTextPaint.setTextSize(dp(14));
            hintTextPaint.setTextAlign(Paint.Align.CENTER);

            confirmPaint.setColor(0xFF4285F4);
            confirmPaint.setStyle(Paint.Style.FILL);
            confirmTextPaint.setColor(Color.WHITE);
            confirmTextPaint.setTextSize(dp(14));
            confirmTextPaint.setTextAlign(Paint.Align.CENTER);

            DiagnosticLog.i(context, "G_CIRCLE_TEXT_SELECT",
                    "ready chars=0 recognition=deferred_until_gesture");
        }

        void promoteKeyFocus(String reason) {
            if (closed) return;
            if (!keyFocusEnabled) {
                windowLayout.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                if (!host.update(this, windowLayout, "google_circle_inline_focus")) return;
                keyFocusEnabled = true;
            }
            post(() -> {
                if (!closed && isAttachedToWindow()) requestFocus();
            });
            DiagnosticLog.i(context, "G_CIRCLE_INLINE", "focus promoted reason=" + reason);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            Bitmap bitmap = frame.bitmap;
            if (bitmap == null || bitmap.isRecycled()) return;

            canvas.drawBitmap(bitmap, null, new Rect(0, 0, getWidth(), getHeight()), bitmapPaint);

            if (screenshotSelection != null) {
                RectF selected = frame.bitmapToView(screenshotSelection.bounds,
                        getWidth(), getHeight());
                drawOutsideShade(canvas, selected);
                canvas.drawRoundRect(selected, dp(8), dp(8), screenshotFramePaint);
                drawScreenshotHandles(canvas, selected);
                drawScreenshotConfirm(canvas, selected);
            } else {
                canvas.drawRect(0, 0, getWidth(), getHeight(), lightShadePaint);
                drawTextSelection(canvas);
                confirmRect.setEmpty();
            }

            if (!stroke.isEmpty()) drawStroke(canvas);
            drawClose(canvas);
            drawHint(canvas);
        }

        private void drawOutsideShade(Canvas canvas, RectF r) {
            canvas.drawRect(0, 0, getWidth(), Math.max(0, r.top), shadePaint);
            canvas.drawRect(0, Math.min(getHeight(), r.bottom), getWidth(), getHeight(), shadePaint);
            canvas.drawRect(0, Math.max(0, r.top), Math.max(0, r.left),
                    Math.min(getHeight(), r.bottom), shadePaint);
            canvas.drawRect(Math.min(getWidth(), r.right), Math.max(0, r.top),
                    getWidth(), Math.min(getHeight(), r.bottom), shadePaint);
        }

        private void drawScreenshotHandles(Canvas canvas, RectF selected) {
            float radius = dp(6);
            canvas.drawCircle(selected.left, selected.centerY(), radius, screenshotHandlePaint);
            canvas.drawCircle(selected.right, selected.centerY(), radius, screenshotHandlePaint);
            canvas.drawCircle(selected.centerX(), selected.top, radius, screenshotHandlePaint);
            canvas.drawCircle(selected.centerX(), selected.bottom, radius, screenshotHandlePaint);
        }

        private void drawScreenshotConfirm(Canvas canvas, RectF selected) {
            String label = "完成";
            float width = dp(64);
            float height = dp(36);
            float gap = dp(8);
            float left = Math.max(dp(4), Math.min(getWidth() - width - dp(4), selected.right - width));
            float top = selected.bottom + gap;
            if (top + height > getHeight() - dp(4)) top = selected.top - gap - height;
            if (top < dp(4)) top = dp(4);
            confirmRect.set(left, top, left + width, top + height);
            canvas.drawRoundRect(confirmRect, height / 2f, height / 2f, confirmPaint);
            Paint.FontMetrics fm = confirmTextPaint.getFontMetrics();
            float baseline = confirmRect.centerY() - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(label, confirmRect.centerX(), baseline, confirmTextPaint);
        }

        private void drawTextSelection(Canvas canvas) {
            if (!textSelection.hasSelection()) return;
            for (int index : textSelection.selectionIndices()) {
                RectF box = textSelection.wordViewRect(index, getWidth(), getHeight());
                if (!box.isEmpty()) canvas.drawRoundRect(box, dp(2), dp(2), textSelectedPaint);
            }
            drawTextHandles(canvas, textSelection.low(), textSelection.high());
        }

        private void drawTextHandles(Canvas canvas, int lo, int hi) {
            if (lo < 0 || hi < 0 || lo >= textSelection.size() || hi >= textSelection.size()) return;
            RectF first = textSelection.wordViewRect(lo, getWidth(), getHeight());
            RectF last = textSelection.wordViewRect(hi, getWidth(), getHeight());
            if (first.isEmpty() || last.isEmpty()) return;
            float stem = dp(7);
            float radius = dp(7);
            canvas.drawLine(first.left, first.bottom, first.left, first.bottom + stem, textHandlePaint);
            canvas.drawCircle(first.left, first.bottom + stem, radius, textHandlePaint);
            canvas.drawLine(last.right, last.bottom, last.right, last.bottom + stem, textHandlePaint);
            canvas.drawCircle(last.right, last.bottom + stem, radius, textHandlePaint);
        }

        private void drawStroke(Canvas canvas) {
            Path path = new Path();
            boolean first = true;
            for (PointF point : stroke) {
                PointF view = frame.bitmapToView(point.x, point.y, getWidth(), getHeight());
                if (first) {
                    path.moveTo(view.x, view.y);
                    first = false;
                } else {
                    path.lineTo(view.x, view.y);
                }
            }
            canvas.drawPath(path, strokeGlow);
            canvas.drawPath(path, strokePaint);
        }

        private void drawClose(Canvas canvas) {
            layoutCloseRect();
            canvas.drawOval(closeRect, closePaint);
            float cx = closeRect.centerX();
            float cy = closeRect.centerY();
            float d = dp(7);
            canvas.drawLine(cx - d, cy - d, cx + d, cy + d, closeGlyphPaint);
            canvas.drawLine(cx + d, cy - d, cx - d, cy + d, closeGlyphPaint);
        }

        private void ensureClosePosition() {
            if (!Float.isNaN(closeCenterX) && !Float.isNaN(closeCenterY)) return;
            if (getWidth() <= 0 || getHeight() <= 0) return;

            int xBp = -1;
            int yBp = -1;
            try {
                var prefs = context.getSharedPreferences(FloatSettings.PREF, Context.MODE_PRIVATE);
                xBp = prefs.getInt(PREF_CLOSE_X_BP, -1);
                yBp = prefs.getInt(PREF_CLOSE_Y_BP, -1);
            } catch (Throwable ignored) {
            }

            if (xBp >= 0 && xBp <= 10000 && yBp >= 0 && yBp <= 10000) {
                closeCenterX = getWidth() * (xBp / 10000f);
                closeCenterY = getHeight() * (yBp / 10000f);
            } else {
                closeCenterX = getWidth() / 2f;
                closeCenterY = getHeight() - dp(CLOSE_BOTTOM_GAP_DP) - dp(CLOSE_SIZE_DP) / 2f;
            }
        }

        private void layoutCloseRect() {
            ensureClosePosition();
            if (Float.isNaN(closeCenterX) || Float.isNaN(closeCenterY)) return;

            float half = dp(CLOSE_SIZE_DP) / 2f;
            float margin = dp(CLOSE_EDGE_MARGIN_DP);
            float minX = margin + half;
            float maxX = Math.max(minX, getWidth() - margin - half);
            float minY = margin + half;
            float maxY = Math.max(minY, getHeight() - margin - half);
            closeCenterX = Math.max(minX, Math.min(maxX, closeCenterX));
            closeCenterY = Math.max(minY, Math.min(maxY, closeCenterY));
            closeRect.set(closeCenterX - half, closeCenterY - half,
                    closeCenterX + half, closeCenterY + half);
        }

        private void moveCloseButton(float centerX, float centerY) {
            closeCenterX = centerX;
            closeCenterY = centerY;
            layoutCloseRect();
        }

        private void persistCloseButtonPosition() {
            if (getWidth() <= 0 || getHeight() <= 0
                    || Float.isNaN(closeCenterX) || Float.isNaN(closeCenterY)) return;
            int xBp = Math.max(0, Math.min(10000,
                    Math.round(closeCenterX * 10000f / getWidth())));
            int yBp = Math.max(0, Math.min(10000,
                    Math.round(closeCenterY * 10000f / getHeight())));
            try {
                context.getSharedPreferences(FloatSettings.PREF, Context.MODE_PRIVATE)
                        .edit()
                        .putInt(PREF_CLOSE_X_BP, xBp)
                        .putInt(PREF_CLOSE_Y_BP, yBp)
                        .apply();
                DiagnosticLog.i(context, "G_CIRCLE_CANCEL",
                        "position_saved xBp=" + xBp + " yBp=" + yBp);
            } catch (Throwable t) {
                DiagnosticLog.i(context, "G_CIRCLE_CANCEL", "position_save_failed="
                        + ScreenCaptureBackend.safeMessage(t));
            }
        }

        private void drawHint(Canvas canvas) {
            String text;
            if (screenshotSelection != null) {
                text = "调整截图窗口 · 调好后点完成";
            } else if (resolvingText) {
                text = "正在识别当前位置 · View → 图片文字";
            } else if (textSelection.hasSelection()) {
                text = "IMAGE_OCR".equals(selectedTextSource)
                        ? "图片文字已在原位置可选 · 拖动手柄调整"
                        : "View 文字已在原位置可选 · 拖动手柄调整";
            } else {
                text = "点击/涂抹时即时识别文字 · 圈画截图";
            }

            float width = Math.min(getWidth() - dp(32), hintTextPaint.measureText(text) + dp(30));
            float height = dp(38);
            float left = (getWidth() - width) / 2f;
            float top = Math.max(dp(70), getHeight() - height - dp(22));
            RectF pill = new RectF(left, top, left + width, top + height);

            if (!closeRect.isEmpty() && RectF.intersects(pill, closeRect)) {
                float gap = dp(12);
                float above = closeRect.top - gap - height;
                float below = closeRect.bottom + gap;
                if (above >= dp(70)) {
                    top = above;
                } else if (below + height <= getHeight() - dp(4)) {
                    top = below;
                }
                pill.set(left, top, left + width, top + height);
            }

            canvas.drawRoundRect(pill, height / 2f, height / 2f, hintPaint);
            Paint.FontMetrics fm = hintTextPaint.getFontMetrics();
            float baseline = pill.centerY() - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(text, pill.centerX(), baseline, hintTextPaint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (closed || event == null) return false;

            float x = event.getX();
            float y = event.getY();
            PointF bitmapPoint = frame.viewToBitmap(x, y, getWidth(), getHeight());

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    closePressed = closeRect.contains(x, y);
                    if (closePressed) {
                        closeDragging = false;
                        closeDragOffsetX = x - closeRect.centerX();
                        closeDragOffsetY = y - closeRect.centerY();
                        removeCallbacks(closeLongPressRunnable);
                        postDelayed(closeLongPressRunnable, CLOSE_MOVE_LONG_PRESS_MS);
                        return true;
                    }

                    confirmPressed = screenshotSelection != null && confirmRect.contains(x, y);
                    if (confirmPressed) return true;

                    FloatActionMenu.dismiss();
                    ImageActionMenu.dismiss();
                    FloatMenuAnchor.clear();

                    if (textSelection.hasSelection()) {
                        int textHandle = hitTextHandle(x, y);
                        if (textHandle != MODE_NONE) {
                            editMode = textHandle;
                            invalidate();
                            return true;
                        }
                    }

                    if (screenshotSelection != null) {
                        int screenshotEdit = hitScreenshotEditMode(bitmapPoint);
                        if (screenshotEdit != MODE_NONE) {
                            editMode = screenshotEdit;
                            editStart = bitmapPoint;
                            editOrigin = new RectF(screenshotSelection.bounds);
                            invalidate();
                            return true;
                        }
                    }

                    cancelTextResolution("new_gesture");
                    screenshotSelection = null;
                    textSelection.clear();
                    selectedTextSource = "";
                    confirmRect.setEmpty();
                    stroke.clear();
                    stroke.add(bitmapPoint);
                    editMode = MODE_DRAW;
                    invalidate();
                    return true;
                }

                case MotionEvent.ACTION_MOVE -> {
                    if (closePressed) {
                        if (closeDragging) {
                            moveCloseButton(x - closeDragOffsetX, y - closeDragOffsetY);
                            invalidate();
                        }
                        return true;
                    }
                    if (confirmPressed) return true;
                    if (editMode == MODE_DRAW) {
                        addStrokePoint(bitmapPoint);
                    } else if (editMode == MODE_TEXT_START || editMode == MODE_TEXT_END) {
                        updateTextEndpoint(x, y);
                    } else if (isScreenshotEditMode(editMode)) {
                        updateScreenshotSelection(bitmapPoint);
                    }
                    invalidate();
                    return true;
                }

                case MotionEvent.ACTION_UP -> {
                    if (closePressed) {
                        removeCallbacks(closeLongPressRunnable);
                        boolean wasDragging = closeDragging;
                        if (wasDragging) {
                            moveCloseButton(x - closeDragOffsetX, y - closeDragOffsetY);
                            persistCloseButtonPosition();
                        }
                        boolean shouldClose = !wasDragging && closeRect.contains(x, y);
                        closePressed = false;
                        closeDragging = false;
                        invalidate();
                        if (shouldClose) close("user_close");
                        return true;
                    }

                    if (confirmPressed) {
                        boolean shouldConfirm = confirmRect.contains(x, y);
                        confirmPressed = false;
                        if (shouldConfirm) confirmScreenshot();
                        return true;
                    }

                    if (editMode == MODE_DRAW) {
                        addStrokePoint(bitmapPoint);
                        finishStroke();
                    } else if (editMode == MODE_TEXT_START || editMode == MODE_TEXT_END) {
                        updateTextEndpoint(x, y);
                        showTextSelectionMenu();
                    }
                    // Screenshot edits deliberately wait for the explicit 完成 button.
                    editMode = MODE_NONE;
                    editOrigin = null;
                    editStart = null;
                    invalidate();
                    return true;
                }

                case MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(closeLongPressRunnable);
                    closePressed = false;
                    closeDragging = false;
                    confirmPressed = false;
                    editMode = MODE_NONE;
                    editOrigin = null;
                    editStart = null;
                    stroke.clear();
                    invalidate();
                    return true;
                }

                default -> {
                    return true;
                }
            }
        }

        private void addStrokePoint(PointF point) {
            if (stroke.isEmpty()) {
                stroke.add(point);
                return;
            }
            PointF last = stroke.get(stroke.size() - 1);
            float sampleDistance = bitmapPxForDp(2f);
            if (Math.hypot(point.x - last.x, point.y - last.y) >= sampleDistance) {
                stroke.add(point);
            }
        }

        private void finishStroke() {
            float tapSlop = bitmapPxForDp(12f);
            float minShape = bitmapPxForDp(34f);
            GoogleCircleSelection.Selection gesture = GoogleCircleSelection.fromStroke(stroke,
                    frame.bitmap.getWidth(), frame.bitmap.getHeight(), tapSlop, minShape);
            stroke.clear();
            if (gesture == null) return;

            DiagnosticLog.i(context, "G_CIRCLE_GESTURE", "kind=" + gesture.kind
                    + " bounds=" + gesture.bounds.toShortString()
                    + " routing=" + (gesture.kind == GoogleCircleSelection.Kind.CIRCLE
                    ? "editable_screenshot" : "deferred_view_then_local_ocr")
                    + " autoExpand=false");

            if (gesture.kind == GoogleCircleSelection.Kind.CIRCLE) {
                cancelTextResolution("circle_gesture");
                textSelection.clear();
                selectedTextSource = "";
                FloatActionMenu.dismiss();
                screenshotSelection = gesture;
                DiagnosticLog.i(context, "G_CIRCLE_SCREENSHOT_FRAME", "created exact="
                        + gesture.bounds.toShortString() + " menu=wait_for_confirm autoExpand=false");
                invalidate();
                return;
            }

            screenshotSelection = null;
            beginTextResolution(gesture);
        }

        private void beginTextResolution(GoogleCircleSelection.Selection gesture) {
            final int generation = ++textResolutionGeneration;
            resolvingText = true;
            selectedTextSource = "";
            textSelection.clear();
            invalidate();

            GoogleCircleTextResolver.resolve(context, frame, gesture,
                    result -> post(() -> applyResolvedText(generation, gesture, result)));
        }

        private void applyResolvedText(int generation,
                                       GoogleCircleSelection.Selection gesture,
                                       GoogleCircleTextResolver.Result result) {
            if (closed || generation != textResolutionGeneration) return;
            resolvingText = false;

            if (result == null || result.source == GoogleCircleTextResolver.Source.NONE
                    || result.document == null || result.document.chars().isEmpty()) {
                textSelection.clear();
                selectedTextSource = "";
                DiagnosticLog.i(context, "G_CIRCLE_TEXT_SELECT", "resolved=false gesture="
                        + gesture.kind + " source=" + (result == null ? "null" : result.source)
                        + " error=" + (result == null || result.error == null ? "none"
                        : ScreenCaptureBackend.safeMessage(result.error)));
                Toast.makeText(context, "当前位置未识别到文字", Toast.LENGTH_SHORT).show();
                invalidate();
                return;
            }

            textSelection.setDocument(result.document);
            boolean selected = selectFromResolvedDocument(gesture, result.source);
            if (!selected) {
                textSelection.clear();
                selectedTextSource = "";
                Toast.makeText(context, "当前位置未识别到可选文字", Toast.LENGTH_SHORT).show();
                invalidate();
                return;
            }

            selectedTextSource = result.source.name();
            DiagnosticLog.i(context, "G_CIRCLE_TEXT_SELECT", "resolved=true gesture="
                    + gesture.kind + " source=" + result.source
                    + " chars=" + textSelection.selectionIndices().size()
                    + " textChars=" + textSelection.selectedText().length()
                    + " originalPosition=true coordinateSpace=SCREEN");
            invalidate();
            post(this::showTextSelectionMenu);
        }

        private boolean selectFromResolvedDocument(GoogleCircleSelection.Selection gesture,
                                                   GoogleCircleTextResolver.Source source) {
            if (gesture.kind == GoogleCircleSelection.Kind.TAP) {
                PointF viewPoint = frame.bitmapToView(gesture.focus.x, gesture.focus.y,
                        getWidth(), getHeight());
                float snap = source == GoogleCircleTextResolver.Source.IMAGE_OCR
                        ? dp(OCR_TEXT_TAP_SNAP_DP) : dp(VIEW_TEXT_TAP_SNAP_DP);
                int hit = textSelection.findSelectionWord(viewPoint.x, viewPoint.y,
                        getWidth(), getHeight(), snap);
                if (hit < 0) return false;
                textSelection.selectSingle(hit);
                return true;
            }

            Rect bitmapRect = GoogleCircleSelection.exactRectAndClamp(gesture.bounds,
                    frame.bitmap.getWidth(), frame.bitmap.getHeight());
            Rect screenRect = bitmapRect.isEmpty() ? new Rect()
                    : frame.bitmapRectToScreen(bitmapRect);
            if (screenRect.isEmpty()) return false;
            if (textSelection.selectIntersecting(screenRect)) return true;

            int tolerance = Math.max(1, Math.round(dp(RANGE_TOLERANCE_DP)));
            Rect tolerant = new Rect(screenRect);
            tolerant.inset(-tolerance, -tolerance);
            tolerant.intersect(frame.screenBounds);
            return textSelection.selectIntersecting(tolerant);
        }

        private void cancelTextResolution(String reason) {
            if (!resolvingText) {
                textResolutionGeneration++;
                return;
            }
            textResolutionGeneration++;
            resolvingText = false;
            DiagnosticLog.i(context, "G_CIRCLE_TEXT_RESOLVE", "ui generation cancelled reason="
                    + reason + " generation=" + textResolutionGeneration);
        }

        private int hitTextHandle(float x, float y) {
            int lo = textSelection.low();
            int hi = textSelection.high();
            if (lo < 0 || hi < 0) return MODE_NONE;
            RectF first = textSelection.wordViewRect(lo, getWidth(), getHeight());
            RectF last = textSelection.wordViewRect(hi, getWidth(), getHeight());
            if (first.isEmpty() || last.isEmpty()) return MODE_NONE;
            float stem = dp(7);
            float hit = dp(TEXT_HANDLE_HIT_DP);
            if (distance(x, y, first.left, first.bottom + stem) <= hit) {
                return textSelection.startIndex() <= textSelection.endIndex()
                        ? MODE_TEXT_START : MODE_TEXT_END;
            }
            if (distance(x, y, last.right, last.bottom + stem) <= hit) {
                return textSelection.startIndex() <= textSelection.endIndex()
                        ? MODE_TEXT_END : MODE_TEXT_START;
            }
            return MODE_NONE;
        }

        private void updateTextEndpoint(float x, float y) {
            int hit = textSelection.findSelectionWord(x, y, getWidth(), getHeight(),
                    dp(TEXT_HANDLE_SNAP_DP));
            if (hit < 0) return;
            if (editMode == MODE_TEXT_START) textSelection.updateStart(hit);
            else if (editMode == MODE_TEXT_END) textSelection.updateEnd(hit);
        }

        private void showTextSelectionMenu() {
            if (closed || !textSelection.hasSelection()) return;
            String selected = textSelection.selectedText();
            Rect anchor = textSelection.selectionScreenBounds();
            if (selected.isBlank() || anchor == null || anchor.isEmpty()) return;
            FloatActionMenu.showTextAt(context, selected, () -> {
                if (closed || textSelection.isEmpty()) return;
                textSelection.selectAll();
                invalidate();
                post(() -> {
                    if (closed || !textSelection.hasSelection()) return;
                    FloatActionMenu.showTextAt(context, textSelection.selectedText(), null,
                            textSelection.selectionScreenBounds());
                });
            }, anchor);
        }

        private int hitScreenshotEditMode(PointF point) {
            if (screenshotSelection == null) return MODE_NONE;
            RectF rect = screenshotSelection.bounds;
            float hitSlop = bitmapPxForDp(24f);
            boolean yInside = point.y >= rect.top - hitSlop && point.y <= rect.bottom + hitSlop;
            boolean xInside = point.x >= rect.left - hitSlop && point.x <= rect.right + hitSlop;

            if (yInside && Math.abs(point.x - rect.left) <= hitSlop) return MODE_SCREEN_LEFT;
            if (yInside && Math.abs(point.x - rect.right) <= hitSlop) return MODE_SCREEN_RIGHT;
            if (xInside && Math.abs(point.y - rect.top) <= hitSlop) return MODE_SCREEN_TOP;
            if (xInside && Math.abs(point.y - rect.bottom) <= hitSlop) return MODE_SCREEN_BOTTOM;
            if (rect.contains(point.x, point.y)) return MODE_SCREEN_MOVE;
            return MODE_NONE;
        }

        private void updateScreenshotSelection(PointF point) {
            if (screenshotSelection == null || editOrigin == null || editStart == null) return;

            RectF rect = new RectF(editOrigin);
            float dx = point.x - editStart.x;
            float dy = point.y - editStart.y;
            switch (editMode) {
                case MODE_SCREEN_MOVE -> rect.offset(dx, dy);
                case MODE_SCREEN_LEFT -> rect.left = Math.min(rect.right - 1f, editOrigin.left + dx);
                case MODE_SCREEN_TOP -> rect.top = Math.min(rect.bottom - 1f, editOrigin.top + dy);
                case MODE_SCREEN_RIGHT -> rect.right = Math.max(rect.left + 1f, editOrigin.right + dx);
                case MODE_SCREEN_BOTTOM -> rect.bottom = Math.max(rect.top + 1f, editOrigin.bottom + dy);
                default -> {
                    return;
                }
            }

            screenshotSelection = screenshotSelection.withBounds(
                    GoogleCircleSelection.clampEditable(rect,
                            frame.bitmap.getWidth(), frame.bitmap.getHeight()));
        }

        private void confirmScreenshot() {
            if (closed || screenshotSelection == null || frame.bitmap == null
                    || frame.bitmap.isRecycled()) return;

            Rect bitmapRect = GoogleCircleSelection.exactRectAndClamp(screenshotSelection.bounds,
                    frame.bitmap.getWidth(), frame.bitmap.getHeight());
            if (bitmapRect.isEmpty()) {
                Toast.makeText(context, "截图范围无效", Toast.LENGTH_SHORT).show();
                return;
            }

            final Bitmap crop;
            try {
                Bitmap made = Bitmap.createBitmap(frame.bitmap,
                        bitmapRect.left, bitmapRect.top, bitmapRect.width(), bitmapRect.height());
                if (made == frame.bitmap) {
                    Bitmap copy = frame.bitmap.copy(Bitmap.Config.ARGB_8888, false);
                    if (copy == null) throw new IllegalStateException("copy screenshot failed");
                    made = copy;
                }
                crop = made;
            } catch (Throwable t) {
                DiagnosticLog.i(context, "G_CIRCLE_SCREENSHOT_FRAME", "crop failed="
                        + ScreenCaptureBackend.safeMessage(t));
                Toast.makeText(context, "圈画截图失败", Toast.LENGTH_SHORT).show();
                return;
            }

            Rect anchor = frame.bitmapRectToScreen(bitmapRect);
            DiagnosticLog.i(context, "G_CIRCLE_SCREENSHOT_FRAME", "confirmed bitmap="
                    + bitmapRect.toShortString()
                    + " crop=" + crop.getWidth() + "x" + crop.getHeight()
                    + " anchor=" + anchor.toShortString()
                    + " autoExpand=false menu=image_action");

            close("circle_screenshot_confirmed");
            ImageActionMenu.show(context, crop, anchor);
        }

        private boolean isScreenshotEditMode(int mode) {
            return mode >= MODE_SCREEN_MOVE && mode <= MODE_SCREEN_BOTTOM;
        }

        @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                close("back");
                return true;
            }
            return super.onKeyUp(keyCode, event);
        }

        void close(String reason) {
            if (closed) return;
            closed = true;
            removeCallbacks(closeLongPressRunnable);
            closePressed = false;
            closeDragging = false;
            textResolutionGeneration++;
            resolvingText = false;
            FloatActionMenu.dismiss();
            ImageActionMenu.dismiss();
            FloatMenuAnchor.clear();
            host.remove(this, "google_circle_inline");
            frame.recycle();
            GoogleCircleInlineOverlay.onClosed(this);
            if (onClosed != null) onClosed.run();
            DiagnosticLog.i(context, "G_CIRCLE_INLINE", "closed reason=" + reason);
        }

        private float bitmapPxForDp(float value) {
            float screenPx = dp(value);
            float sx = frame.bitmap.getWidth() / (float) Math.max(1, frame.screenBounds.width());
            float sy = frame.bitmap.getHeight() / (float) Math.max(1, frame.screenBounds.height());
            return screenPx * (sx + sy) * 0.5f;
        }

        private float dp(float value) {
            return value * getResources().getDisplayMetrics().density;
        }

        private float distance(float x1, float y1, float x2, float y2) {
            return (float) Math.hypot(x1 - x2, y1 - y2);
        }
    }

    private GoogleCircleInlineOverlay() {}
}
