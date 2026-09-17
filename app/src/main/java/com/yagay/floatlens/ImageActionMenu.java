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

/** Image actions using the same floating-menu UI/positioning foundation as text actions. */
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

        LinearLayout root = FloatingMenuUi.root(app, 18);
        root.setPadding(dp(app, 4), dp(app, 4), dp(app, 4), dp(app, 4));

        TextView copy = FloatingMenuUi.row(app, "复制图片", null);
        TextView share = FloatingMenuUi.row(app, "分享图片", null);
        TextView openWith = FloatingMenuUi.row(app, "打开方式", null);
        TextView save = FloatingMenuUi.row(app, "保存图片", null);
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
        int[] pos = FloatingMenuPositioner.aroundAnchor(app, usable, anchor, width, height, true);

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
                    + " ui=FloatingMenuUi positioner=FloatingMenuPositioner"
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

    private static int dp(Context c, int v) {
        return FloatingMenuUi.dp(c, v);
    }

    private ImageActionMenu() {}
}
