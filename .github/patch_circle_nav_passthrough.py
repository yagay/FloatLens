from pathlib import Path

# Patch CircleSelectFrame: reserve bottom navigation-bar strip from the interactive overlay.
p = Path('app/src/main/java/com/yagay/floatlens/CircleSelectFrame.java')
s = p.read_text()
if 'import android.graphics.Insets;' not in s:
    s = s.replace('import android.graphics.Bitmap;\nimport android.graphics.Rect;\n', 'import android.graphics.Bitmap;\nimport android.graphics.Insets;\nimport android.graphics.Rect;\n')
if 'import android.view.WindowInsets;' not in s:
    s = s.replace('import android.view.WindowManager;\n', 'import android.view.WindowInsets;\nimport android.view.WindowManager;\n')
old = '''    /** Circle Select now owns the complete display, including status and navigation bar areas. */\n    static Rect contentBounds(Context c) {\n        return displayBounds(c);\n    }\n\n    static Rect displayBounds(Context c) {\n        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);\n        return new Rect(wm.getCurrentWindowMetrics().getBounds());\n    }\n'''
new = '''    /** Full display coordinate space used by screenshot/OCR/touch mapping. */\n    static Rect contentBounds(Context c) {\n        return displayBounds(c);\n    }\n\n    /**\n     * Interactive Circle Select window. Leave the bottom navigation-bar strip outside this window\n     * so 3-button/gesture navigation keeps receiving input directly from SystemUI.\n     */\n    static Rect interactiveBounds(Context c) {\n        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);\n        Rect display = new Rect(wm.getCurrentWindowMetrics().getBounds());\n        try {\n            Insets safe = wm.getCurrentWindowMetrics().getWindowInsets().getInsetsIgnoringVisibility(\n                    WindowInsets.Type.navigationBars() | WindowInsets.Type.displayCutout());\n            int bottomInset = Math.max(0, safe.bottom);\n            if (bottomInset > 0 && bottomInset < display.height()) {\n                display.bottom -= bottomInset;\n            }\n        } catch (Throwable ignored) {}\n        return display;\n    }\n\n    static Rect displayBounds(Context c) {\n        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);\n        return new Rect(wm.getCurrentWindowMetrics().getBounds());\n    }\n'''
if old in s:
    s = s.replace(old, new, 1)
elif 'static Rect interactiveBounds(Context c)' not in s:
    raise SystemExit('CircleSelectFrame target block not found')
p.write_text(s)

# Patch overlay window and preserve full-display coordinate mapping.
p = Path('app/src/main/java/com/yagay/floatlens/CircleSelectOverlay.java')
s = p.read_text()
s = s.replace('import androidx.core.graphics.Insets;\nimport androidx.core.view.ViewCompat;\nimport androidx.core.view.WindowInsetsCompat;\n\n', '')
s = s.replace('        Rect contentBounds = CircleSelectFrame.contentBounds(app);\n        Rect displayBounds = CircleSelectFrame.displayBounds(app);\n',
              '        Rect contentBounds = CircleSelectFrame.interactiveBounds(app);\n        Rect displayBounds = CircleSelectFrame.displayBounds(app);\n')
s = s.replace('        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN\n                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;\n',
              '        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN\n                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS\n                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL\n                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;\n')
s = s.replace('        WorkspaceView view = new WorkspaceView(app, host, lp, screenshot, onClosed, !shadeExpanded);\n',
              '        WorkspaceView view = new WorkspaceView(app, host, lp, screenshot, onClosed, !shadeExpanded,\n                displayBounds.width(), displayBounds.height());\n')
if '                + " display=" + displayBounds.toShortString()\n' not in s:
    s = s.replace('                + " bounds=" + contentBounds.toShortString()\n',
                  '                + " bounds=" + contentBounds.toShortString()\n                + " display=" + displayBounds.toShortString()\n', 1)

if '        private final int coordinateWidth;\n' not in s:
    s = s.replace('        private final Runnable onClosed;\n        private final CircleTextSelectionModel selection;\n',
                  '        private final Runnable onClosed;\n        private final int coordinateWidth;\n        private final int coordinateHeight;\n        private final CircleTextSelectionModel selection;\n', 1)
if '                      int coordinateWidth, int coordinateHeight) {\n' not in s:
    s = s.replace('        WorkspaceView(Context c, FlOverlayWindowHost host, WindowManager.LayoutParams windowLayout,\n                      Bitmap screenshot, Runnable onClosed, boolean keyFocusEnabled) {\n',
                  '        WorkspaceView(Context c, FlOverlayWindowHost host, WindowManager.LayoutParams windowLayout,\n                      Bitmap screenshot, Runnable onClosed, boolean keyFocusEnabled,\n                      int coordinateWidth, int coordinateHeight) {\n', 1)

assign = '            this.coordinateWidth = Math.max(1, coordinateWidth);\n            this.coordinateHeight = Math.max(1, coordinateHeight);\n'
dup = assign + assign
while dup in s:
    s = s.replace(dup, assign, 1)
if assign not in s:
    s = s.replace('            this.onClosed = onClosed;\n            this.keyFocusEnabled = keyFocusEnabled;\n',
                  '            this.onClosed = onClosed;\n            this.keyFocusEnabled = keyFocusEnabled;\n' + assign, 1)

replacements = {
'                                            getWidth(), getHeight(), snap);':'                                            coordinateWidth, coordinateHeight, snap);',
'                                            getWidth(), getHeight(), dp(ROI_RESULT_SNAP_DISTANCE_DP));':'                                            coordinateWidth, coordinateHeight, dp(ROI_RESULT_SNAP_DISTANCE_DP));',
'            canvas.drawBitmap(screenshot, null, new Rect(0, 0, getWidth(), getHeight()), bitmapPaint);':'            // Draw in full-display coordinates; the shorter window clips the navigation strip without scaling.\n            canvas.drawBitmap(screenshot, null, new Rect(0, 0, coordinateWidth, coordinateHeight), bitmapPaint);',
'                    canvas.drawRoundRect(selection.wordViewRect(index, getWidth(), getHeight()),':'                    canvas.drawRoundRect(selection.wordViewRect(index, coordinateWidth, coordinateHeight),',
'            float bottomInset = bottomSystemInset();\n            float bottom = getHeight() - bottomInset - dp(12);':'            float bottom = getHeight() - dp(12);',
'        private int bottomSystemInset() {\n            WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(this);\n            if (insets == null) return 0;\n            Insets safe = insets.getInsetsIgnoringVisibility(\n                    WindowInsetsCompat.Type.navigationBars()\n                            | WindowInsetsCompat.Type.displayCutout());\n            return Math.max(0, safe.bottom);\n        }\n\n':'',
'            RectF first = selection.wordViewRect(lo, getWidth(), getHeight());':'            RectF first = selection.wordViewRect(lo, coordinateWidth, coordinateHeight);',
'            RectF last = selection.wordViewRect(hi, getWidth(), getHeight());':'            RectF last = selection.wordViewRect(hi, coordinateWidth, coordinateHeight);',
'                    int hit = selection.findSelectionWord(x, y, getWidth(), getHeight(),\n                            dp(TEXT_TAP_SNAP_DISTANCE_DP));':'                    int hit = selection.findSelectionWord(x, y, coordinateWidth, coordinateHeight,\n                            dp(TEXT_TAP_SNAP_DISTANCE_DP));',
'            int cached = selection.findSelectionWord(viewX, viewY, getWidth(), getHeight(),':'            int cached = selection.findSelectionWord(viewX, viewY, coordinateWidth, coordinateHeight,',
'            return selection.findSelectionWord(x, y, getWidth(), getHeight(), dp(HANDLE_SNAP_DISTANCE_DP));':'            return selection.findSelectionWord(x, y, coordinateWidth, coordinateHeight, dp(HANDLE_SNAP_DISTANCE_DP));',
'            RectF union = selection.selectionViewBounds(getWidth(), getHeight());':'            RectF union = selection.selectionViewBounds(coordinateWidth, coordinateHeight);',
'            float sx = screenshot.getWidth() / (float) getWidth();\n            float sy = screenshot.getHeight() / (float) getHeight();':'            float sx = screenshot.getWidth() / (float) coordinateWidth;\n            float sy = screenshot.getHeight() / (float) coordinateHeight;',
'            Bitmap crop = CircleCropGeometry.crop(screenshot, viewRect, getWidth(), getHeight());':'            Bitmap crop = CircleCropGeometry.crop(screenshot, viewRect, coordinateWidth, coordinateHeight);',
'            RectF symbol = selection.wordViewRect(index, getWidth(), getHeight());':'            RectF symbol = selection.wordViewRect(index, coordinateWidth, coordinateHeight);'
}
for a,b in replacements.items():
    s = s.replace(a,b)

old_touch = '        @Override public boolean onTouchEvent(MotionEvent e) {\n            if (closed || circleResolving) return true;\n            float x = e.getX(), y = e.getY();\n'
new_touch = '        @Override public boolean onTouchEvent(MotionEvent e) {\n            if (e != null && e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {\n                if (!closed) close("system_navigation_touch");\n                return true;\n            }\n            if (closed || circleResolving) return true;\n            float x = e.getX(), y = e.getY();\n'
if old_touch in s:
    s = s.replace(old_touch, new_touch, 1)
elif 'close("system_navigation_touch")' not in s:
    raise SystemExit('onTouchEvent header not found')

required = [
    'FLAG_NOT_TOUCH_MODAL', 'FLAG_WATCH_OUTSIDE_TOUCH',
    'CircleSelectFrame.interactiveBounds(app)', 'coordinateHeight',
    'close("system_navigation_touch")',
    'new Rect(0, 0, coordinateWidth, coordinateHeight)',
    'selection.findSelectionWord(x, y, coordinateWidth, coordinateHeight,\n                            dp(TEXT_TAP_SNAP_DISTANCE_DP))'
]
for token in required:
    if token not in s:
        raise SystemExit('missing token: ' + token)

p.write_text(s)
print('patched Circle Select navigation passthrough and full-display mapping')
