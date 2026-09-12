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

/**
 * Clean-room equivalent of FV m2/g.pointer_op_hint.
 *
 * APK facts:
 * - sibling WindowManager window, not a child of the owner floating icon;
 * - width = WRAP_CONTENT, height = 20dp, gravity = TOP|LEFT/START;
 * - op_hint_icon = 20dp x 20dp CircleImageView;
 * - op_paste_text_hint = one-line 10dp black TextView, maxWidth 120dp;
 * - D(): hint.x = float_pen_view.x + 15dp; hint.y = float_pen_view.y - 20dp;
 * - E(color, drawable, text) changes the CircleImageView drawable/background and optional text.
 */
public final class FvPointerOperationHintOverlay {
    public enum Mode { TEXT, IMAGE, SCREENSHOT }

    private static final float HINT_HEIGHT_DP = 20f;
    private static final float PROBE_X_OFFSET_DP = 15f;
    private static final float ICON_SIZE_DP = 20f;
    private static final float TEXT_SIZE_DP = 10f;
    private static final float TEXT_MAX_WIDTH_DP = 120f;

    // m5/e drawable-color map in FV 1.6.4.
    private static final int FV_YELLOW = 0xFFFBC02D; // foo_text/foo_screenshot_02/foo_paste
    private static final int FV_PICTURE = 0xFFC2185B; // window_picture_1

    private final Context context;
    private final WindowManager wm;
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

    public FvPointerOperationHintOverlay(Context c) {
        context = c.getApplicationContext();
        wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

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

    /**
     * Mirrors m2/g.D(). This is called whenever the pen window moves, even when the hint's internal
     * icon is currently GONE, so both sibling WindowManager windows preserve FV's exact trajectory.
     */
    public void syncToProbeWindow(int probeWindowX, int probeWindowY) {
        int nextX = probeWindowX + probeXOffsetPx;
        int nextY = probeWindowY - hintHeightPx;

        boolean changed = lp.x != nextX || lp.y != nextY;
        lp.x = nextX;
        lp.y = nextY;

        if (!attached) {
            try {
                wm.addView(root, lp);
                attached = true;
                DiagnosticLog.i(context, "FV_POINTER_HINT",
                        "ATTACH pos=" + lp.x + "," + lp.y
                                + " height=" + hintHeightPx + " xOffset=" + probeXOffsetPx);
            } catch (Throwable t) {
                DiagnosticLog.i(context, "FV_POINTER_HINT", "attach failed=" + t);
            }
            return;
        }

        if (changed) {
            try {
                wm.updateViewLayout(root, lp);
            } catch (Throwable t) {
                DiagnosticLog.i(context, "FV_POINTER_HINT", "move failed=" + t);
            }
        }
    }

    /**
     * Mirrors m2/g.E(color, drawable, optionalText) for the states FloatLens currently exposes.
     * The screenshot/text drawables are clean-room XML equivalents of FV foo_screenshot_02/foo_text.
     */
    public void show(Mode next, int probeWindowX, int probeWindowY) {
        show(next, probeWindowX, probeWindowY, null);
    }

    public void show(Mode next,
                     int probeWindowX,
                     int probeWindowY,
                     String optionalText) {
        if (next == null) next = Mode.SCREENSHOT;
        syncToProbeWindow(probeWindowX, probeWindowY);
        if (!attached) return;

        if (mode != next) {
            mode = next;
            int drawableRes;
            int background;
            switch (next) {
                case TEXT -> {
                    drawableRes = R.drawable.fv_pointer_text;
                    background = FV_YELLOW;
                }
                case IMAGE -> {
                    drawableRes = R.drawable.fv_pointer_image;
                    background = FV_PICTURE;
                }
                case SCREENSHOT -> {
                    drawableRes = R.drawable.fv_pointer_screenshot;
                    background = FV_YELLOW;
                }
                default -> {
                    drawableRes = R.drawable.fv_pointer_screenshot;
                    background = FV_YELLOW;
                }
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
        try { wm.updateViewLayout(root, lp); } catch (Throwable ignored) {}

        DiagnosticLog.i(context, "FV_POINTER_HINT",
                "SHOW mode=" + mode + " pos=" + lp.x + "," + lp.y
                        + " icon=" + iconSizePx + "x" + iconSizePx
                        + " text=" + (optionalText == null ? "none" : optionalText));
    }

    /** Mirrors E(..., drawable=0, ...): keep the sibling window but make its content GONE. */
    public void hideContent() {
        if (!attached) return;
        if (!contentVisible && icon.getVisibility() == View.GONE && text.getVisibility() == View.GONE) {
            return;
        }
        contentVisible = false;
        icon.setVisibility(View.GONE);
        text.setVisibility(View.GONE);
        root.requestLayout();
        try { wm.updateViewLayout(root, lp); } catch (Throwable ignored) {}
        DiagnosticLog.i(context, "FV_POINTER_HINT", "CONTENT_HIDE pos=" + lp.x + "," + lp.y);
    }

    /** Mirrors m2/g.s(): remove pointer_op_hint together with float_pen_view. */
    public void close() {
        contentVisible = false;
        if (attached) {
            try { wm.removeView(root); } catch (Throwable ignored) {}
        }
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
