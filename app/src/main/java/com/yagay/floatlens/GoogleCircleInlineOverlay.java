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

/**
 * Frozen-screen Google-style selector.
 *
 * The frozen screenshot remains the visual source of truth. Text results are never redrawn into a
 * translucent result box: the original text stays exactly where it was and only a thin marker is
 * drawn around the resolved text position. Only CIRCLE may own a screenshot crop.
 */
final class GoogleCircleInlineOverlay {
    private static WorkspaceView active;

    static synchronized boolean show(Context c, GoogleCircleCapture.Frame frame,
                                     GoogleCircleContentSnapshot content, Runnable onClosed) {
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

        GoogleCircleContentSnapshot safeContent = content == null
                ? GoogleCircleContentSnapshot.empty(display) : content;
        WorkspaceView view = new WorkspaceView(app, host, lp, frame, safeContent,
                onClosed, !shadeExpanded);
        if (!host.add(view, lp, "google_circle_inline")) return false;

        active = view;
        if (!shadeExpanded) view.promoteKeyFocus("initial");
        DiagnosticLog.i(app, "G_CIRCLE_INLINE", "overlay shown frame=" + bounds.toShortString()
                + " bitmap=" + frame.bitmap.getWidth() + "x" + frame.bitmap.getHeight()
                + " semanticText=" + safeContent.textCount()
                + " semanticImages=" + safeContent.imageCount()
                + " presentation=original_pixels textRedraw=false autoExpand=false");
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
        private static final int MODE_MOVE = 2;
        private static final int MODE_LEFT = 3;
        private static final int MODE_TOP = 4;
        private static final int MODE_RIGHT = 5;
        private static final int MODE_BOTTOM = 6;

        private final Context context;
        private final FlOverlayWindowHost host;
        private final WindowManager.LayoutParams windowLayout;
        private final GoogleCircleCapture.Frame frame;
        private final GoogleCircleContentSnapshot content;
        private final Runnable onClosed;

        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokeGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textMarkerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint imageMarkerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint closePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint closeGlyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hintTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint badgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

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

        private GoogleCircleResultCoordinator.Kind inlineKind;
        private Rect inlineAnchor = new Rect();
        private String inlineText = "";
        private String inlineSource = "";
        private Bitmap inlineCrop;

        WorkspaceView(Context c, FlOverlayWindowHost host,
                      WindowManager.LayoutParams windowLayout,
                      GoogleCircleCapture.Frame frame,
                      GoogleCircleContentSnapshot content,
                      Runnable onClosed, boolean keyFocusEnabled) {
            super(c);
            context = c;
            this.host = host;
            this.windowLayout = windowLayout;
            this.frame = frame;
            this.content = content;
            this.onClosed = onClosed;
            this.keyFocusEnabled = keyFocusEnabled;

            setClickable(true);
            setFocusable(true);
            setFocusableInTouchMode(true);

            shadePaint.setColor(0x50000000);

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

            textMarkerPaint.setColor(0xFF4285F4);
            textMarkerPaint.setStyle(Paint.Style.STROKE);
            textMarkerPaint.setStrokeWidth(dp(2.5f));

            imageMarkerPaint.setColor(0xFF8AB4F8);
            imageMarkerPaint.setStyle(Paint.Style.STROKE);
            imageMarkerPaint.setStrokeWidth(dp(3));

            closePaint.setColor(0xD9222222);
            closeGlyphPaint.setColor(Color.WHITE);
            closeGlyphPaint.setStyle(Paint.Style.STROKE);
            closeGlyphPaint.setStrokeWidth(dp(2));
            closeGlyphPaint.setStrokeCap(Paint.Cap.ROUND);

            hintPaint.setColor(0xD9222222);
            hintTextPaint.setColor(Color.WHITE);
            hintTextPaint.setTextSize(dp(14));
            hintTextPaint.setTextAlign(Paint.Align.CENTER);

            badgePaint.setColor(0xE02B2B2B);
            badgePaint.setStyle(Paint.Style.FILL);
            badgeTextPaint.setColor(Color.WHITE);
            badgeTextPaint.setTextSize(dp(12));
            badgeTextPaint.setTextAlign(Paint.Align.CENTER);
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

            if (selection != null) {
                RectF selected = frame.bitmapToView(selection.bounds, getWidth(), getHeight());
                drawOutsideShade(canvas, selected);
                canvas.drawRoundRect(selected, dp(8), dp(8), selectionPaint);
                drawSelectionHandles(canvas, selected);
            } else {
                canvas.drawColor(0x18000000);
            }

            drawInlineResult(canvas);
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

        private void drawSelectionHandles(Canvas canvas, RectF selected) {
            float radius = dp(6);
            canvas.drawCircle(selected.left, selected.centerY(), radius, handlePaint);
            canvas.drawCircle(selected.right, selected.centerY(), radius, handlePaint);
            canvas.drawCircle(selected.centerX(), selected.top, radius, handlePaint);
            canvas.drawCircle(selected.centerX(), selected.bottom, radius, handlePaint);
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

        /**
         * Never repaint recognized text. The underlying frozen screenshot already contains the
         * original glyphs at their exact location. We only mark that location with a thin outline.
         */
        private void drawInlineResult(Canvas canvas) {
            if (inlineKind == null || inlineAnchor == null || inlineAnchor.isEmpty()) return;
            RectF target = screenToView(inlineAnchor);
            if (target.isEmpty()) return;

            if (inlineKind == GoogleCircleResultCoordinator.Kind.IMAGE) {
                canvas.drawRoundRect(target, dp(7), dp(7), imageMarkerPaint);
                drawBadgeOutside(canvas, target, "截图");
                return;
            }

            // Text: no fill and no drawText(). Original pixels remain visible and untouched.
            canvas.drawRoundRect(target, dp(3), dp(3), textMarkerPaint);
        }

        private void drawBadgeOutside(Canvas canvas, RectF target, String label) {
            float width = Math.max(dp(46), badgeTextPaint.measureText(label) + dp(18));
            float height = dp(28);
            float left = Math.max(dp(4), Math.min(getWidth() - width - dp(4), target.left));
            float top = target.top - height - dp(6);
            if (top < dp(4)) top = Math.min(getHeight() - height - dp(4), target.bottom + dp(6));
            RectF badge = new RectF(left, top, left + width, top + height);
            canvas.drawRoundRect(badge, height / 2f, height / 2f, badgePaint);
            Paint.FontMetrics fm = badgeTextPaint.getFontMetrics();
            float baseline = badge.centerY() - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(label, badge.centerX(), baseline, badgeTextPaint);
        }

        private RectF screenToView(Rect screen) {
            Rect workspace = frame.screenBounds;
            float sx = getWidth() / (float) Math.max(1, workspace.width());
            float sy = getHeight() / (float) Math.max(1, workspace.height());
            RectF out = new RectF(
                    (screen.left - workspace.left) * sx,
                    (screen.top - workspace.top) * sy,
                    (screen.right - workspace.left) * sx,
                    (screen.bottom - workspace.top) * sy);
            out.left = Math.max(0f, Math.min(getWidth(), out.left));
            out.top = Math.max(0f, Math.min(getHeight(), out.top));
            out.right = Math.max(out.left, Math.min(getWidth(), out.right));
            out.bottom = Math.max(out.top, Math.min(getHeight(), out.bottom));
            return out;
        }

        private void drawClose(Canvas canvas) {
            float size = dp(42);
            float margin = dp(14);
            closeRect.set(margin, margin, margin + size, margin + size);
            canvas.drawOval(closeRect, closePaint);
            float cx = closeRect.centerX();
            float cy = closeRect.centerY();
            float d = dp(7);
            canvas.drawLine(cx - d, cy - d, cx + d, cy + d, closeGlyphPaint);
            canvas.drawLine(cx + d, cy - d, cx - d, cy + d, closeGlyphPaint);
        }

        private void drawHint(Canvas canvas) {
            String text;
            if (recognizing) {
                text = selection != null && selection.kind == GoogleCircleSelection.Kind.CIRCLE
                        ? "正在生成圈画截图…" : "正在定位 View 文字…";
            } else if (inlineKind == GoogleCircleResultCoordinator.Kind.IMAGE) {
                text = "圈画截图已就绪 · 原选区不扩大";
            } else if (inlineKind != null && inlineText != null && !inlineText.isBlank()) {
                text = "View 文字已在原位置标记 · 不重绘文字";
            } else if (selection == null) {
                text = "点击/涂抹/高亮选文字 · 圈画截图";
            } else {
                text = kindLabel(selection.kind) + " · 精确选区 · 可继续调整";
            }

            float width = Math.min(getWidth() - dp(32), hintTextPaint.measureText(text) + dp(30));
            float height = dp(38);
            float left = (getWidth() - width) / 2f;
            float top = Math.max(dp(70), getHeight() - height - dp(22));
            RectF pill = new RectF(left, top, left + width, top + height);
            canvas.drawRoundRect(pill, height / 2f, height / 2f, hintPaint);
            Paint.FontMetrics fm = hintTextPaint.getFontMetrics();
            float baseline = pill.centerY() - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(text, pill.centerX(), baseline, hintTextPaint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (closed || event == null) return false;

            float x = event.getX();
            float y = event.getY();
            PointF point = frame.viewToBitmap(x, y, getWidth(), getHeight());

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    closePressed = closeRect.contains(x, y);
                    if (closePressed) return true;

                    recognitionGeneration++;
                    recognizing = false;
                    clearInlineResult();

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
                    if (closePressed) return true;
                    if (editMode == MODE_DRAW) addStrokePoint(point);
                    else if (editMode != MODE_NONE) updateEditedSelection(point);
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
                        scheduleRecognition(220L);
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
            selection = GoogleCircleSelection.fromStroke(stroke,
                    frame.bitmap.getWidth(), frame.bitmap.getHeight(), tapSlop, minShape);
            stroke.clear();
            if (selection == null) return;

            DiagnosticLog.i(context, "G_CIRCLE_GESTURE", "kind=" + selection.kind
                    + " bounds=" + selection.bounds.toShortString()
                    + " routing=" + (selection.kind == GoogleCircleSelection.Kind.CIRCLE
                    ? "screenshot" : "view_text")
                    + " autoExpand=false");
            scheduleRecognition(180L);
        }

        private int hitEditMode(PointF point) {
            if (selection == null) return MODE_NONE;
            RectF rect = selection.bounds;
            float hitSlop = bitmapPxForDp(24f);
            boolean yInside = point.y >= rect.top - hitSlop && point.y <= rect.bottom + hitSlop;
            boolean xInside = point.x >= rect.left - hitSlop && point.x <= rect.right + hitSlop;

            if (yInside && Math.abs(point.x - rect.left) <= hitSlop) return MODE_LEFT;
            if (yInside && Math.abs(point.x - rect.right) <= hitSlop) return MODE_RIGHT;
            if (xInside && Math.abs(point.y - rect.top) <= hitSlop) return MODE_TOP;
            if (xInside && Math.abs(point.y - rect.bottom) <= hitSlop) return MODE_BOTTOM;
            if (rect.contains(point.x, point.y)) return MODE_MOVE;
            return MODE_NONE;
        }

        private void updateEditedSelection(PointF point) {
            if (selection == null || editOrigin == null || editStart == null) return;

            RectF rect = new RectF(editOrigin);
            float dx = point.x - editStart.x;
            float dy = point.y - editStart.y;
            switch (editMode) {
                case MODE_MOVE -> rect.offset(dx, dy);
                case MODE_LEFT -> rect.left = Math.min(rect.right - 1f, editOrigin.left + dx);
                case MODE_TOP -> rect.top = Math.min(rect.bottom - 1f, editOrigin.top + dy);
                case MODE_RIGHT -> rect.right = Math.max(rect.left + 1f, editOrigin.right + dx);
                case MODE_BOTTOM -> rect.bottom = Math.max(rect.top + 1f, editOrigin.bottom + dy);
                default -> {
                    return;
                }
            }

            selection = selection.withBounds(GoogleCircleSelection.clampEditable(rect,
                    frame.bitmap.getWidth(), frame.bitmap.getHeight()));
        }

        private void scheduleRecognition(long delayMs) {
            if (selection == null || closed) return;

            int generation = ++recognitionGeneration;
            recognizing = true;
            clearInlineResult();
            invalidate();
            GoogleCircleSelection.Selection requestSelection = selection;

            postDelayed(() -> {
                if (closed || generation != recognitionGeneration || selection == null) return;
                GoogleCircleResultCoordinator.resolve(context, frame, content, requestSelection,
                        resolution -> post(() -> onResolution(generation, requestSelection, resolution)));
            }, delayMs);
        }

        private void onResolution(int generation,
                                  GoogleCircleSelection.Selection requestSelection,
                                  GoogleCircleResultCoordinator.Resolution resolution) {
            if (resolution == null) return;
            if (closed || generation != recognitionGeneration || selection != requestSelection) {
                recycle(resolution.crop);
                return;
            }

            recognizing = false;
            clearInlineResult();
            inlineKind = resolution.kind;
            inlineAnchor = resolution.screenAnchor == null
                    ? new Rect() : new Rect(resolution.screenAnchor);
            inlineText = resolution.text == null ? "" : resolution.text.trim();
            inlineSource = resolution.source == null ? "" : resolution.source;

            if (inlineKind == GoogleCircleResultCoordinator.Kind.IMAGE) {
                // CIRCLE screenshot owns this crop until a new selection or workspace close.
                inlineCrop = resolution.crop;
            } else {
                recycle(resolution.crop);
            }

            boolean shown = inlineKind == GoogleCircleResultCoordinator.Kind.IMAGE
                    ? inlineCrop != null && !inlineCrop.isRecycled() && !inlineAnchor.isEmpty()
                    : !inlineText.isBlank() && !inlineAnchor.isEmpty();

            DiagnosticLog.i(context, "G_CIRCLE_RESULT", "gesture=" + requestSelection.kind
                    + " content=" + resolution.kind
                    + " source=" + inlineSource
                    + " textChars=" + inlineText.length()
                    + " crop=" + (inlineCrop == null ? "none"
                    : inlineCrop.getWidth() + "x" + inlineCrop.getHeight())
                    + " shown=" + shown
                    + " textRedraw=false screenshotOnlyForCircle=true autoExpand=false"
                    + " error=" + (resolution.error == null ? "none"
                    : ScreenCaptureBackend.safeMessage(resolution.error)));

            if (!shown) {
                clearInlineResult();
                Toast.makeText(context,
                        requestSelection.kind == GoogleCircleSelection.Kind.CIRCLE
                                ? "圈画截图失败" : "未找到 View 文字",
                        Toast.LENGTH_SHORT).show();
            }
            invalidate();
        }

        private void clearInlineResult() {
            Bitmap crop = inlineCrop;
            inlineCrop = null;
            recycle(crop);
            inlineKind = null;
            inlineAnchor = new Rect();
            inlineText = "";
            inlineSource = "";
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
            OcrEngine.invalidateDocumentPending(context, "google_circle_inline_close");
            clearInlineResult();
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

        private static String kindLabel(GoogleCircleSelection.Kind kind) {
            return switch (kind) {
                case TAP -> "点击选 View 文字";
                case CIRCLE -> "圈画截图";
                case HIGHLIGHT -> "高亮选 View 文字";
                case SCRIBBLE -> "涂抹选 View 文字";
            };
        }

        private static void recycle(Bitmap bitmap) {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private GoogleCircleInlineOverlay() {}
}
