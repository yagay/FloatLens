from pathlib import Path

p = Path('app/src/main/java/com/yagay/floatlens/CircleSelectOverlay.java')
s = p.read_text()

if 'androidx.core.graphics.Insets' not in s:
    marker = 'import android.widget.Magnifier;\n\n'
    s = s.replace(marker, marker + 'import androidx.core.graphics.Insets;\nimport androidx.core.view.ViewCompat;\nimport androidx.core.view.WindowInsetsCompat;\n\n', 1)

old = '''            canvas.drawText(status, dp(16), dp(34), textPaint);\n            drawClose(canvas);\n        }\n\n        private void drawClose(Canvas c) {\n            float size = dp(38);\n            closeRect.set(getWidth() - size - dp(12), dp(10), getWidth() - dp(12), dp(10) + size);\n            c.drawRoundRect(closeRect, size / 2f, size / 2f, toolbarPaint);\n            Paint p = new Paint(toolbarTextPaint);\n            p.setTextSize(dp(22));\n            c.drawText("×", closeRect.centerX(), closeRect.centerY() + dp(7), p);\n        }\n'''
new = '''            // Keep Circle Select full-screen for 1:1 capture/touch coordinates, but place\n            // transient controls above the navigation bar instead of over the status bar.\n            drawClose(canvas);\n            float statusBaseline = Math.max(dp(24), closeRect.top - dp(10));\n            canvas.drawText(status, dp(16), statusBaseline, textPaint);\n        }\n\n        private void drawClose(Canvas c) {\n            float size = dp(38);\n            float bottomInset = bottomSystemInset();\n            float bottom = getHeight() - bottomInset - dp(12);\n            float top = bottom - size;\n            closeRect.set(getWidth() - size - dp(12), top, getWidth() - dp(12), bottom);\n            c.drawRoundRect(closeRect, size / 2f, size / 2f, toolbarPaint);\n            Paint p = new Paint(toolbarTextPaint);\n            p.setTextSize(dp(22));\n            c.drawText("×", closeRect.centerX(), closeRect.centerY() + dp(7), p);\n        }\n\n        private int bottomSystemInset() {\n            WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(this);\n            if (insets == null) return 0;\n            Insets safe = insets.getInsetsIgnoringVisibility(\n                    WindowInsetsCompat.Type.navigationBars()\n                            | WindowInsetsCompat.Type.displayCutout());\n            return Math.max(0, safe.bottom);\n        }\n'''

if old not in s:
    raise SystemExit('target draw block not found')
s = s.replace(old, new, 1)
p.write_text(s)
print('moved Circle Select hint/close controls to bottom safe area')
