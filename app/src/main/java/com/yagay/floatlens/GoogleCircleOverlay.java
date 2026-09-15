package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import java.util.ArrayList;

/**
 * Fresh Google-style frozen-screen selector.
 *
 * It supports tap, circle, highlight and scribble as equivalent ways to create one ROI. The legacy
 * CircleSelectOverlay/TextIndex/ROI-refinement stack is intentionally not referenced by this class.
 */
final class GoogleCircleOverlay {
    private static WorkspaceView active;

    static synchronized boolean show(Context c, GoogleCircleCapture.Frame frame, Runnable onClosed) {
        if (frame == null || frame.bitmap == null || frame.bitmap.isRecycled()) return false;
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

        WorkspaceView view = new WorkspaceView(app, host, lp, frame, onClosed, !shadeExpanded);
        if (!host.add(view, lp, "google_circle")) return false;
        active = view;
        if (!shadeExpanded) view.promoteKeyFocus("initial");
        DiagnosticLog.i(app, "G_CIRCLE", "overlay shown frame=" + bounds.toShortString()
                + " bitmap=" + frame.bitmap.getWidth() + "x" + frame.bitmap.getHeight()
                + " coordinateSpace=BITMAP_ONLY");
        return true;
    }

    static synchronized void promoteActiveFocus(String reason) {
        WorkspaceView v = active;
        if (v != null) v.promoteKeyFocus(reason);
    }

    static synchronized void dismissActive(String reason) {
        WorkspaceView v = active;
        active = null;
        if (v != null) v.close(reason == null ? "dismiss" : reason);
    }

    private static synchronized void onClosed(WorkspaceView v) {
        if (active == v) active = null;
    }

    private static final class WorkspaceView extends View {
        private static final int MODE_NONE = 0;
        private static final int MODE_DRAW = 1;
        private static final int MODE_MOVE = 2;
        private static final int MODE_LEFT = 3;
        private static final int MODE_TOP = 4;
        private static final int MODE_RIGHT = 5;
        private static final int MODE_BOTTOM = 6;

        private final Context context;
        private final FlOverlayWindowHost host;
        private final WindowManager.LayoutParams windowLayout;
        private final GoogleCircleCapture.Frame frame;
        private final Runnable onClosed;
        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokeGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint closePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint closeGlyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hintTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ArrayList<PointF> stroke = new ArrayList<>();
        private final RectF closeRect = new RectF();

        private GoogleCircleSelection.Selection selection;
        private RectF editOrigin;
        private PointF editStart;
        private int editMode = MODE_NONE;
        private int recognitionGeneration;
        private boolean recognizing;
        private boolean closed;
        private boolean closePressed;
        private boolean keyFocusEnabled;

        WorkspaceView(Context c, FlOverlayWindowHost host, WindowManager.LayoutParams windowLayout,
                      GoogleCircleCapture.Frame frame, Runnable onClosed, boolean keyFocusEnabled) {
            super(c);
            context = c;
            this.host = host;
            this.windowLayout = windowLayout;
            this.frame = frame;
            this.onClosed = onClosed;
            this.keyFocusEnabled = keyFocusEnabled;
            setClickable(true);
            setFocusable(true);
            setFocusableInTouchMode(true);

            shadePaint.setColor(0x52000000);
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
            selectionPaint.setColor(Color.WHITE);
            selectionPaint.setStyle(Paint.Style.STROKE);
            selectionPaint.setStrokeWidth(dp(2));
            handlePaint.setColor(0xFF4285F4);
            handlePaint.setStyle(Paint.Style.FILL);
            closePaint.setColor(0xD9222222);
            closeGlyphPaint.setColor(Color.WHITE);
            closeGlyphPaint.setStyle(Paint.Style.STROKE);
            closeGlyphPaint.setStrokeWidth(dp(2));
            closeGlyphPaint.setStrokeCap(Paint.Cap.ROUND);
            hintPaint.setColor(0xD9222222);
            hintTextPaint.setColor(Color.WHITE);
            hintTextPaint.setTextSize(dp(14));
            hintTextPaint.setTextAlign(Paint.Align.CENTER);
        }

        void promoteKeyFocus(String reason) {
            if (closed) return;
            if (!keyFocusEnabled) {
                windowLayout.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                if (!host.update(this, windowLayout, "google_circle_focus")) return;
                keyFocusEnabled = true;
            }
            post(() -> {
                if (!closed && isAttachedToWindow()) requestFocus();
            });
            DiagnosticLog.i(context, "G_CIRCLE", "focus promoted reason=" + reason);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            Bitmap b = frame.bitmap;
            if (b == null || b.isRecycled()) return;
            canvas.drawBitmap(b, null, new Rect(0, 0, getWidth(), getHeight()), bitmapPaint);

            if (selection != null) {
                RectF v = frame.bitmapToView(selection.bounds, getWidth(), getHeight());
                drawOutsideShade(canvas, v);
                canvas.drawRoundRect(v, dp(10), dp(10), selectionPaint);
                float hr = dp(6);
                canvas.drawCircle(v.left, v.centerY(), hr, handlePaint);
                canvas.drawCircle(v.right, v.centerY(), hr, handlePaint);
                canvas.drawCircle(v.centerX(), v.top, hr, handlePaint);
                canvas.drawCircle(v.centerX(), v.bottom, hr, handlePaint);
            } else {
                canvas.drawColor(0x18000000);
            }

            if (!stroke.isEmpty()) drawStroke(canvas);
            drawClose(canvas);
            drawHint(canvas);
        }

        private void drawOutsideShade(Canvas canvas, RectF r) {
            canvas.drawRect(0, 0, getWidth(), Math.max(0, r.top), shadePaint);
            canvas.drawRect(0, Math.min(getHeight(), r.bottom), getWidth(), getHeight(), shadePaint);
            canvas.drawRect(0, Math.max(0, r.top), Math.max(0, r.left), Math.min(getHeight(), r.bottom), shadePaint);
            canvas.drawRect(Math.min(getWidth(), r.right), Math.max(0, r.top),
                    getWidth(), Math.min(getHeight(), r.bottom), shadePaint);
        }

        private void drawStroke(Canvas canvas) {
            Path path = new Path();
            boolean first = true;
            for (PointF p : stroke) {
                PointF v = frame.bitmapToView(p.x, p.y, getWidth(), getHeight());
                if (first) { path.moveTo(v.x, v.y); first = false; }
                else path.lineTo(v.x, v.y);
            }
            canvas.drawPath(path, strokeGlow);
            canvas.drawPath(path, strokePaint);
        }

        private void drawClose(Canvas canvas) {
            float size = dp(42);
            float margin = dp(14);
            closeRect.set(margin, margin, margin + size, margin + size);
            canvas.drawOval(closeRect, closePaint);
            float cx = closeRect.centerX(), cy = closeRect.centerY(), d = dp(7);
            canvas.drawLine(cx - d, cy - d, cx + d, cy + d, closeGlyphPaint);
            canvas.drawLine(cx + d, cy - d, cx - d, cy + d, closeGlyphPaint);
        }

        private void drawHint(Canvas canvas) {
            String text;
            if (recognizing) text = "正在识别…";
            else if (selection == null) text = "圈画 · 涂抹 · 高亮 · 点击";
            else text = kindLabel(selection.kind) + "  ·  可拖动选区或边界";
            float w = Math.min(getWidth() - dp(32), hintTextPaint.measureText(text) + dp(30));
            float h = dp(38);
            float left = (getWidth() - w) / 2f;
            float top = Math.max(dp(70), getHeight() - h - dp(22));
            RectF pill = new RectF(left, top, left + w, top + h);
            canvas.drawRoundRect(pill, h / 2f, h / 2f, hintPaint);
            Paint.FontMetrics fm = hintTextPaint.getFontMetrics();
            float baseline = pill.centerY() - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(text, pill.centerX(), baseline, hintTextPaint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (closed || event == null) return false;
            float x = event.getX(), y = event.getY();
            PointF point = frame.viewToBitmap(x, y, getWidth(), getHeight());
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    closePressed = closeRect.contains(x, y);
                    if (closePressed) return true;
                    recognitionGeneration++;
                    recognizing = false;
                    if (selection != null) {
                        int hit = hitEditMode(point);
                        if (hit != MODE_NONE) {
                            editMode = hit;
                            editStart = point;
                            editOrigin = new RectF(selection.bounds);
                            invalidate();
                            return true;
                        }
                    }
                    selection = null;
                    stroke.clear();
                    stroke.add(point);
                    editMode = MODE_DRAW;
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_MOVE -> {
                    if (closePressed) {
                        invalidate();
                        return true;
                    }
                    if (editMode == MODE_DRAW) {
                        addStrokePoint(point);
                    } else if (editMode != MODE_NONE) {
                        updateEditedSelection(point);
                    }
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP -> {
                    if (closePressed) {
                        boolean close = closeRect.contains(x, y);
                        closePressed = false;
                        if (close) close("user_close");
                        return true;
                    }
                    if (editMode == MODE_DRAW) {
                        addStrokePoint(point);
                        finishStroke();
                    } else if (editMode != MODE_NONE && selection != null) {
                        scheduleRecognition(180L);
                    }
                    editMode = MODE_NONE;
                    editOrigin = null;
                    editStart = null;
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_CANCEL -> {
                    closePressed = false;
                    editMode = MODE_NONE;
                    editOrigin = null;
                    editStart = null;
                    stroke.clear();
                    invalidate();
                    return true;
                }
                default -> { return true; }
            }
        }

        private void addStrokePoint(PointF p) {
            if (stroke.isEmpty()) { stroke.add(p); return; }
            PointF last = stroke.get(stroke.size() - 1);
            float min = bitmapPxForDp(2f);
            if (Math.hypot(p.x - last.x, p.y - last.y) >= min) stroke.add(p);
        }

        private void finishStroke() {
            float tapSlop = bitmapPxForDp(12f);
            float minShape = bitmapPxForDp(34f);
            selection = GoogleCircleSelection.fromStroke(stroke,
                    frame.bitmap.getWidth(), frame.bitmap.getHeight(), tapSlop, minShape,
                    bitmapPxForDp(92f), bitmapPxForDp(70f));
            stroke.clear();
            if (selection == null) return;
            DiagnosticLog.i(context, "G_CIRCLE_GESTURE", "kind=" + selection.kind
                    + " bounds=" + selection.bounds.toShortString());
            scheduleRecognition(150L);
        }

        private int hitEditMode(PointF p) {
            RectF r = selection.bounds;
            float slop = bitmapPxForDp(24f);
            boolean yInside = p.y >= r.top - slop && p.y <= r.bottom + slop;
            boolean xInside = p.x >= r.left - slop && p.x <= r.right + slop;
            if (yInside && Math.abs(p.x - r.left) <= slop) return MODE_LEFT;
            if (yInside && Math.abs(p.x - r.right) <= slop) return MODE_RIGHT;
            if (xInside && Math.abs(p.y - r.top) <= slop) return MODE_TOP;
            if (xInside && Math.abs(p.y - r.bottom) <= slop) return MODE_BOTTOM;
            if (r.contains(p.x, p.y)) return MODE_MOVE;
            return MODE_NONE;
        }

        private void updateEditedSelection(PointF p) {
            if (selection == null || editOrigin == null || editStart == null) return;
            RectF r = new RectF(editOrigin);
            float dx = p.x - editStart.x;
            float dy = p.y - editStart.y;
            float min = bitmapPxForDp(42f);
            switch (editMode) {
                case MODE_MOVE -> r.offset(dx, dy);
                case MODE_LEFT -> r.left = Math.min(r.right - min, editOrigin.left + dx);
                case MODE_TOP -> r.top = Math.min(r.bottom - min, editOrigin.top + dy);
                case MODE_RIGHT -> r.right = Math.max(r.left + min, editOrigin.right + dx);
                case MODE_BOTTOM -> r.bottom = Math.max(r.top + min, editOrigin.bottom + dy);
                default -> { return; }
            }
            r = GoogleCircleSelection.clampEditable(r,
                    frame.bitmap.getWidth(), frame.bitmap.getHeight(), min);
            selection = selection.withBounds(r);
        }

        private void scheduleRecognition(long delayMs) {
            if (selection == null || closed) return;
            int generation = ++recognitionGeneration;
            recognizing = true;
            invalidate();
            GoogleCircleSelection.Selection requestSelection = selection;
            postDelayed(() -> {
                if (closed || generation != recognitionGeneration || selection == null) return;
                GoogleCircleResultCoordinator.recognize(context, frame, requestSelection,
                        resolution -> post(() -> onResolution(generation, requestSelection, resolution)));
            }, delayMs);
        }

        private void onResolution(int generation, GoogleCircleSelection.Selection requestSelection,
                                  GoogleCircleResultCoordinator.Resolution resolution) {
            if (resolution == null) return;
            if (closed || generation != recognitionGeneration || selection != requestSelection) {
                recycle(resolution.crop);
                return;
            }
            recognizing = false;
            boolean shown = false;
            if (resolution.crop != null && !resolution.crop.isRecycled()) {
                if (resolution.hasText()) {
                    shown = ResultSurfaceRouter.showOcr(context, resolution.text,
                            resolution.blocks, resolution.crop, resolution.screenAnchor);
                } else {
                    shown = ResultSurfaceRouter.showScreenshot(context,
                            resolution.crop, resolution.screenAnchor);
                }
            }
            DiagnosticLog.i(context, "G_CIRCLE_RESULT", "kind=" + requestSelection.kind
                    + " text=" + resolution.hasText() + " shown=" + shown
                    + " error=" + (resolution.error == null ? "none"
                    : ScreenCaptureBackend.safeMessage(resolution.error)));
            if (shown) {
                close("result_shown");
            } else {
                recycle(resolution.crop);
                Toast.makeText(context, "圈画识别结果显示失败", Toast.LENGTH_SHORT).show();
                invalidate();
            }
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
            recognitionGeneration++;
            OcrEngine.invalidateDocumentPending(context, "google_circle_close");
            host.remove(this, "google_circle");
            frame.recycle();
            GoogleCircleOverlay.onClosed(this);
            if (onClosed != null) onClosed.run();
            DiagnosticLog.i(context, "G_CIRCLE", "closed reason=" + reason);
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

        private static String kindLabel(GoogleCircleSelection.Kind kind) {
            return switch (kind) {
                case TAP -> "点击选择";
                case CIRCLE -> "圈选";
                case HIGHLIGHT -> "高亮选择";
                case SCRIBBLE -> "涂抹选择";
            };
        }

        private static void recycle(Bitmap bitmap) {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private GoogleCircleOverlay() {}
}
