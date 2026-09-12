package com.yagay.floatlens;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** FloatLens pointer operation hint; geometry follows the behavior observed in FV m2/g. */
public final class FlPointerOperationHintOverlay {
    public enum Mode { TEXT, IMAGE, SCREENSHOT }

    private static final float HINT_HEIGHT_DP = 20f;
    private static final float PROBE_X_OFFSET_DP = 15f;
    private static final float ICON_SIZE_DP = 20f;
    private static final float TEXT_SIZE_DP = 10f;
    private static final float TEXT_MAX_WIDTH_DP = 120f;
    private static final int FL_YELLOW = 0xFFFBC02D;
    private static final int FL_PICTURE = 0xFFC2185B;

    private final Context context;
    private final FlOverlayWindowHost windowHost;
    private final int hintHeightPx;
    private final int probeXOffsetPx;
    private final int iconSizePx;
    private final LinearLayout root;
    private final ImageView icon;
    private final TextView text;
    private final WindowManager.LayoutParams lp;
    private boolean attached;
    private boolean contentVisible;
    private Mode mode;

    public FlPointerOperationHintOverlay(Context c) {
        context = c.getApplicationContext();
        windowHost = new FlOverlayWindowHost(context);
        hintHeightPx = dp(HINT_HEIGHT_DP);
        probeXOffsetPx = dp(PROBE_X_OFFSET_DP);
        iconSizePx = dp(ICON_SIZE_DP);

        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        root.setBackgroundColor(Color.TRANSPARENT);

        icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int innerPad = Math.max(1, Math.round(iconSizePx / 6f));
        icon.setPadding(innerPad, innerPad, innerPad, innerPad);
        icon.setVisibility(View.GONE);
        root.addView(icon, new LinearLayout.LayoutParams(iconSizePx, iconSizePx));

        text = new TextView(context);
        text.setTextColor(Color.BLACK);
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DP);
        text.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        text.setMaxWidth(dp(TEXT_MAX_WIDTH_DP));
        text.setMaxLines(1);
        text.setVisibility(View.GONE);
        root.addView(text, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT));

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                hintHeightPx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = -hintHeightPx;
        lp.y = 0;
    }

    public void syncToProbeWindow(int probeWindowX, int probeWindowY) {
        int nextX = probeWindowX + probeXOffsetPx;
        int nextY = probeWindowY - hintHeightPx;
        boolean changed = lp.x != nextX || lp.y != nextY;
        lp.x = nextX;
        lp.y = nextY;

        if (!attached) {
            attached = windowHost.add(root, lp, "pointer_hint");
            if (attached) {
                DiagnosticLog.i(context, "FL_POINTER_HINT",
                        "ATTACH pos=" + lp.x + "," + lp.y
                                + " height=" + hintHeightPx + " xOffset=" + probeXOffsetPx
                                + " accessibilityHost=" + windowHost.isAccessibilityHosted());
            }
            return;
        }
        if (changed) windowHost.update(root, lp, "pointer_hint");
    }

    public void show(Mode next, int probeWindowX, int probeWindowY) {
        show(next, probeWindowX, probeWindowY, null);
    }

    public void show(Mode next, int probeWindowX, int probeWindowY, String optionalText) {
        if (next == null) next = Mode.SCREENSHOT;
        syncToProbeWindow(probeWindowX, probeWindowY);
        if (!attached) return;

        if (mode != next) {
            mode = next;
            int drawableRes;
            int background;
            switch (next) {
                case TEXT -> { drawableRes = R.drawable.fl_pointer_text; background = FL_YELLOW; }
                case IMAGE -> { drawableRes = R.drawable.fl_pointer_image; background = FL_PICTURE; }
                case SCREENSHOT -> { drawableRes = R.drawable.fl_pointer_screenshot; background = FL_YELLOW; }
                default -> { drawableRes = R.drawable.fl_pointer_screenshot; background = FL_YELLOW; }
            }
            icon.setImageResource(drawableRes);
            icon.setBackground(makeCircle(background));
        }

        icon.setVisibility(View.VISIBLE);
        if (optionalText == null) {
            text.setVisibility(View.GONE);
        } else {
            text.setText(optionalText);
            text.setVisibility(View.VISIBLE);
        }
        contentVisible = true;
        root.requestLayout();
        windowHost.update(root, lp, "pointer_hint");
        DiagnosticLog.i(context, "FL_POINTER_HINT",
                "SHOW mode=" + mode + " pos=" + lp.x + "," + lp.y
                        + " icon=" + iconSizePx + "x" + iconSizePx
                        + " text=" + (optionalText == null ? "none" : optionalText));
    }

    public void hideContent() {
        if (!attached) return;
        if (!contentVisible && icon.getVisibility() == View.GONE && text.getVisibility() == View.GONE) return;
        contentVisible = false;
        icon.setVisibility(View.GONE);
        text.setVisibility(View.GONE);
        root.requestLayout();
        windowHost.update(root, lp, "pointer_hint");
        DiagnosticLog.i(context, "FL_POINTER_HINT", "CONTENT_HIDE pos=" + lp.x + "," + lp.y);
    }

    public void close() {
        contentVisible = false;
        if (attached) windowHost.remove(root, "pointer_hint");
        attached = false;
        mode = null;
    }

    public boolean isAttached() { return attached; }

    private GradientDrawable makeCircle(int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(color);
        return bg;
    }

    private int dp(float value) {
        float density = Math.max(.1f, context.getResources().getDisplayMetrics().density);
        return Math.max(1, Math.round(value * density));
    }
}
