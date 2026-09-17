package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Lightweight image action menu using the shared FloatLens menu geometry/theme foundations. */
public final class ImageActionMenu {
    private static FlOverlayWindowHost activeHost;
    private static View activeView;

    public static synchronized void show(Context c, Bitmap image, Rect anchor) {
        if (c == null || image == null || image.isRecycled()) return;
        dismiss();

        Context app = c.getApplicationContext();
        WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;
        FlOverlayWindowHost host = new FlOverlayWindowHost(app);

        LinearLayout root = new LinearLayout(app);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(app, 4), dp(app, 4), dp(app, 4), dp(app, 4));
        root.setBackground(AppUi.rounded(app, UiTokens.menuSurface(app), 18));
        root.setElevation(dp(app, 10));
        root.setClipToOutline(true);
        root.setClickable(true);

        TextView copy = row(app, "复制图片");
        TextView share = row(app, "分享图片");
        TextView openWith = row(app, "打开方式");
        TextView save = row(app, "保存图片");
        root.addView(copy, new LinearLayout.LayoutParams(-1, dp(app, 48)));
        root.addView(share, new LinearLayout.LayoutParams(-1, dp(app, 48)));
        root.addView(openWith, new LinearLayout.LayoutParams(-1, dp(app, 48)));
        root.addView(save, new LinearLayout.LayoutParams(-1, dp(app, 48)));

        root.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                dismiss();
                return true;
            }
            return false;
        });

        Rect usable = ScreenGeometry.usableBounds(app);
        int width = Math.min(dp(app, 176), Math.max(dp(app, 132), usable.width() - dp(app, 16)));
        int height = dp(app, 200);
        int[] pos = menuPosition(app, usable, anchor, width, height);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = pos[0];
        lp.y = pos[1];

        if (host.add(root, lp, "image_action_menu")) {
            activeHost = host;
            activeView = root;
            DiagnosticLog.i(app, "IMAGE_ACTION_MENU", "SHOW anchor="
                    + (anchor == null ? "none" : anchor.toShortString())
                    + " pos=" + lp.x + "," + lp.y
                    + " actions=copy/share/open/save"
                    + " geometry=ScreenGeometry theme=UiTokens"
                    + " accessibilityHost=" + host.isAccessibilityHosted()
                    + " type=" + lp.type);
        } else {
            DiagnosticLog.i(app, "IMAGE_ACTION_MENU", "SHOW_FAILED all hosts");
            return;
        }

        copy.setOnClickListener(v -> {
            dismiss();
            ImageShareUtils.copyToClipboard(app, image);
        });
        share.setOnClickListener(v -> {
            dismiss();
            ImageShareUtils.share(app, image);
        });
        openWith.setOnClickListener(v -> {
            dismiss();
            ImageShareUtils.openWith(app, image);
        });
        save.setOnClickListener(v -> {
            dismiss();
            ScreenshotController.save(app, image);
        });
    }

    public static synchronized void dismiss() {
        View view = activeView;
        FlOverlayWindowHost host = activeHost;
        activeView = null;
        activeHost = null;
        if (view != null && host != null) host.remove(view, "image_action_menu");
    }

    private static TextView row(Context c, String text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextColor(UiTokens.textPrimary(c));
        tv.setTextSize(14);
        tv.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        tv.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        tv.setBackground(AppUi.rowBackground(c));
        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
    }

    private static int[] menuPosition(Context c, Rect usable, Rect anchor, int w, int h) {
        int margin = dp(c, 8);
        int gap = dp(c, 8);
        int minX = usable.left + margin;
        int maxX = Math.max(minX, usable.right - margin - w);
        int minY = usable.top + margin;
        int maxY = Math.max(minY, usable.bottom - margin - h);

        if (anchor == null || anchor.isEmpty()) {
            return new int[]{
                    ScreenGeometry.clamp(usable.centerX() - w / 2, minX, maxX),
                    ScreenGeometry.clamp(usable.centerY() - h / 2, minY, maxY)};
        }

        int x = ScreenGeometry.clamp(anchor.centerX() - w / 2, minX, maxX);
        int above = anchor.top - gap - h;
        int below = anchor.bottom + gap;
        int y;
        if (above >= minY) y = above;
        else if (below <= maxY) y = below;
        else {
            int roomAbove = Math.max(0, anchor.top - minY);
            int roomBelow = Math.max(0, usable.bottom - margin - anchor.bottom);
            y = roomBelow >= roomAbove
                    ? ScreenGeometry.clamp(below, minY, maxY)
                    : ScreenGeometry.clamp(above, minY, maxY);
        }
        return new int[]{x, y};
    }

    private static int dp(Context c, int v) {
        return UiTokens.dp(c, v);
    }

    private ImageActionMenu() {}
}
