package com.yagay.floatlens;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.switchmaterial.SwitchMaterial;

/** Shared visual language for FloatLens configuration screens. */
final class AppUi {
    static final class Section {
        final MaterialCardView card;
        final LinearLayout body;
        Section(MaterialCardView card, LinearLayout body) {
            this.card = card;
            this.body = body;
        }
    }

    static LinearLayout pageRoot(Context c, String title, String subtitle) {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 28));
        root.setBackgroundColor(background(c));

        LinearLayout header = new LinearLayout(c);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        if (c instanceof Activity activity && !(activity instanceof MainActivity)) {
            TextView back = text(c, "‹", 30, false);
            back.setGravity(Gravity.CENTER);
            back.setClickable(true);
            back.setFocusable(true);
            back.setBackground(rowBackground(c));
            back.setContentDescription("返回");
            back.setOnClickListener(v -> activity.finish());
            header.addView(back, new LinearLayout.LayoutParams(dp(c, 42), dp(c, 44)));
        }

        TextView titleView = text(c, title, 24, true);
        titleView.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        if (subtitle != null && !subtitle.isBlank()) {
            TextView subtitleView = caption(c, subtitle, 13);
            subtitleView.setPadding(0, dp(c, 2), 0, dp(c, 14));
            root.addView(subtitleView, new LinearLayout.LayoutParams(-1, -2));
        } else {
            header.setPadding(0, 0, 0, dp(c, 12));
        }
        return root;
    }

    static ScrollView scrollPage(Context c, View content) {
        ScrollView scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(background(c));
        scroll.setClipToPadding(false);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    static Section section(Context c, String title, String subtitle) {
        MaterialCardView card = new MaterialCardView(c);
        card.setCardBackgroundColor(surface(c));
        card.setRadius(dp(c, 18));
        card.setStrokeWidth(dp(c, 1));
        card.setStrokeColor(outline(c));
        card.setCardElevation(0);
        card.setUseCompatPadding(false);

        LinearLayout holder = new LinearLayout(c);
        holder.setOrientation(LinearLayout.VERTICAL);
        card.addView(holder, new MaterialCardView.LayoutParams(-1, -2));

        if ((title != null && !title.isBlank()) || (subtitle != null && !subtitle.isBlank())) {
            LinearLayout header = new LinearLayout(c);
            header.setOrientation(LinearLayout.VERTICAL);
            header.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 10));
            if (title != null && !title.isBlank()) {
                TextView heading = text(c, title, 14, true);
                heading.setTextColor(accent(c));
                header.addView(heading, new LinearLayout.LayoutParams(-1, -2));
            }
            if (subtitle != null && !subtitle.isBlank()) {
                TextView sub = caption(c, subtitle, 12);
                sub.setPadding(0, dp(c, 2), 0, 0);
                header.addView(sub, new LinearLayout.LayoutParams(-1, -2));
            }
            holder.addView(header, new LinearLayout.LayoutParams(-1, -2));
            holder.addView(divider(c), new LinearLayout.LayoutParams(-1, dp(c, 1)));
        }

        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        holder.addView(body, new LinearLayout.LayoutParams(-1, -2));
        return new Section(card, body);
    }

    static void addSection(LinearLayout root, Section section) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(root.getContext(), 12);
        root.addView(section.card, lp);
    }

    static void addRow(LinearLayout parent, View row) {
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    static View navRow(Context c, String title, String subtitle, Runnable action) {
        LinearLayout row = baseRow(c);
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(rowBackground(c));

        LinearLayout copy = new LinearLayout(c);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER_VERTICAL);
        copy.addView(text(c, title, 15, false), new LinearLayout.LayoutParams(-1, -2));
        if (subtitle != null && !subtitle.isBlank()) {
            TextView sub = caption(c, subtitle, 12);
            sub.setPadding(0, dp(c, 2), dp(c, 8), 0);
            copy.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        }
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView arrow = text(c, "›", 24, false);
        arrow.setTextColor(textSecondary(c));
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(c, 30), dp(c, 44)));
        row.setOnClickListener(v -> { if (action != null) action.run(); });
        return row;
    }

    static SwitchMaterial switchRow(Context c, String title, String subtitle,
                                    boolean checked,
                                    android.widget.CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = baseRow(c);
        row.setBackground(rowBackground(c));

        LinearLayout copy = new LinearLayout(c);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER_VERTICAL);
        copy.addView(text(c, title, 15, false), new LinearLayout.LayoutParams(-1, -2));
        if (subtitle != null && !subtitle.isBlank()) {
            TextView sub = caption(c, subtitle, 12);
            sub.setPadding(0, dp(c, 2), dp(c, 8), 0);
            copy.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        }
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));

        SwitchMaterial toggle = new SwitchMaterial(c);
        toggle.setUseMaterialThemeColors(true);
        toggle.setChecked(checked);
        if (listener != null) toggle.setOnCheckedChangeListener(listener);
        row.addView(toggle, new LinearLayout.LayoutParams(-2, -2));
        return toggle;
    }

    static LinearLayout switchContainer(SwitchMaterial toggle) {
        return (LinearLayout) toggle.getParent();
    }

    static LinearLayout baseRow(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(c, 14), dp(c, 10), dp(c, 10), dp(c, 10));
        row.setMinimumHeight(dp(c, 56));
        return row;
    }

    static LinearLayout settingBlock(Context c) {
        LinearLayout block = new LinearLayout(c);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10));
        return block;
    }

    static LinearLayout buttonRow(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
        return row;
    }

    static TextView text(Context c, String value, float sp, boolean bold) {
        TextView tv = new TextView(c);
        tv.setText(value == null ? "" : value);
        tv.setTextSize(sp);
        tv.setTextColor(textPrimary(c));
        if (bold) tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return tv;
    }

    static TextView caption(Context c, String value, float sp) {
        TextView tv = text(c, value, sp, false);
        tv.setTextColor(textSecondary(c));
        tv.setLineSpacing(0f, 1.08f);
        return tv;
    }

    static TextView statusPill(Context c, String value, boolean positive) {
        TextView tv = text(c, value, 12, true);
        tv.setTextColor(positive ? success(c) : warning(c));
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(c, 10), dp(c, 5), dp(c, 10), dp(c, 5));
        tv.setBackground(rounded(c, positive ? successSurface(c) : warningSurface(c), 999));
        return tv;
    }

    static MaterialButton primaryButton(Context c, String value) {
        MaterialButton b = new MaterialButton(c);
        styleButton(c, b, value);
        return b;
    }

    static MaterialButton secondaryButton(Context c, String value) {
        MaterialButton b = new MaterialButton(c, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        styleButton(c, b, value);
        return b;
    }

    static MaterialButton compactButton(Context c, String value) {
        MaterialButton b = secondaryButton(c, value);
        b.setTextSize(13);
        b.setMinHeight(dp(c, 38));
        b.setMinimumHeight(dp(c, 38));
        b.setMinimumWidth(0);
        b.setPadding(dp(c, 10), 0, dp(c, 10), 0);
        return b;
    }

    private static void styleButton(Context c, MaterialButton b, String value) {
        b.setText(value);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setMinHeight(dp(c, 44));
        b.setMinimumHeight(dp(c, 44));
        b.setCornerRadius(dp(c, 13));
    }

    static void styleInput(Context c, EditText input) {
        input.setTextColor(textPrimary(c));
        input.setHintTextColor(textSecondary(c));
        input.setTextSize(14);
        input.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        input.setBackground(rounded(c, surfaceAlt(c), 12));
    }

    static android.graphics.drawable.Drawable rowBackground(Context c) {
        return new RippleDrawable(
                ColorStateList.valueOf(ripple(c)),
                new ColorDrawable(Color.TRANSPARENT),
                null);
    }

    static View divider(Context c) {
        View divider = new View(c);
        divider.setBackgroundColor(outline(c));
        return divider;
    }

    static android.graphics.drawable.Drawable rounded(Context c, int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    static int background(Context c) { return dark(c) ? 0xFF0E1013 : 0xFFF4F5F7; }
    static int surface(Context c) { return dark(c) ? 0xFF181B20 : 0xFFFFFFFF; }
    static int surfaceAlt(Context c) { return dark(c) ? 0xFF23272E : 0xFFF0F2F5; }
    static int textPrimary(Context c) { return dark(c) ? 0xFFF4F5F7 : 0xFF17191D; }
    static int textSecondary(Context c) { return dark(c) ? 0xFFAEB4BE : 0xFF69707B; }
    static int outline(Context c) { return dark(c) ? 0xFF292E35 : 0xFFE6E8EC; }
    static int success(Context c) { return dark(c) ? 0xFF7ED7A2 : 0xFF197A45; }
    static int warning(Context c) { return dark(c) ? 0xFFFFC266 : 0xFFA05A00; }
    static int successSurface(Context c) { return dark(c) ? 0xFF173527 : 0xFFE9F6EE; }
    static int warningSurface(Context c) { return dark(c) ? 0xFF3A2A13 : 0xFFFFF1DF; }
    static int accent(Context c) { return dark(c) ? 0xFF9CC2FF : 0xFF285FBE; }
    static int ripple(Context c) { return dark(c) ? 0x22FFFFFF : 0x12000000; }

    static boolean dark(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    static int dp(Context c, int value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    private AppUi() {}
}
