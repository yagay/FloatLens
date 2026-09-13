from pathlib import Path

p = Path('app/src/main/java/com/yagay/floatlens/AppUi.java')
s = p.read_text()

if 'androidx.core.graphics.Insets' not in s:
    marker = 'import android.widget.TextView;\n\n'
    s = s.replace(marker, marker + 'import androidx.core.graphics.Insets;\nimport androidx.core.view.ViewCompat;\nimport androidx.core.view.WindowInsetsCompat;\n\n', 1)

old = '''    static ScrollView scrollPage(Context c, View content) {\n        ScrollView scroll = new ScrollView(c);\n        scroll.setFillViewport(true);\n        scroll.setBackgroundColor(background(c));\n        scroll.setClipToPadding(false);\n        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));\n        return scroll;\n    }\n'''
new = '''    static ScrollView scrollPage(Context c, View content) {\n        ScrollView scroll = new ScrollView(c);\n        scroll.setFillViewport(true);\n        scroll.setBackgroundColor(background(c));\n        // Android 15+ enforces edge-to-edge for modern targets. Keep ordinary configuration\n        // pages inside the status/navigation bar safe area while full-screen overlays remain\n        // independent from this AppUi path.\n        scroll.setClipToPadding(true);\n        final int baseLeft = scroll.getPaddingLeft();\n        final int baseTop = scroll.getPaddingTop();\n        final int baseRight = scroll.getPaddingRight();\n        final int baseBottom = scroll.getPaddingBottom();\n        ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, windowInsets) -> {\n            Insets bars = windowInsets.getInsets(\n                    WindowInsetsCompat.Type.systemBars()\n                            | WindowInsetsCompat.Type.displayCutout());\n            v.setPadding(\n                    baseLeft + bars.left,\n                    baseTop + bars.top,\n                    baseRight + bars.right,\n                    baseBottom + bars.bottom);\n            return windowInsets;\n        });\n        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));\n        ViewCompat.requestApplyInsets(scroll);\n        return scroll;\n    }\n'''

if old in s:
    s = s.replace(old, new, 1)
elif 'WindowInsetsCompat.Type.systemBars()' not in s:
    raise SystemExit('scrollPage block not found')

p.write_text(s)
print('patched AppUi system bar insets')
