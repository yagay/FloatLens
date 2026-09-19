package com.yagay.floatlens;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** FloatLens-owned text action menu using the shared floating-menu UI and positioning core. */
public final class FloatActionMenu {
    private static final int MODE_MAIN = 0;
    private static final int MODE_SHARE = 1;
    private static final int MODE_PROCESS = 2;
    private static final int MODE_MORE = 3;
    private static final int NO_POSITION = Integer.MIN_VALUE;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static FlOverlayWindowHost activeHost;
    private static View activeView;
    private static int activeCenterX = NO_POSITION;
    private static int activeTopY = NO_POSITION;
    private static int lockedCenterX = NO_POSITION;
    private static int lockedTopY = NO_POSITION;
    private static final OverlayRegistry.Owner OVERLAY_OWNER = new OverlayRegistry.Owner() {
        @Override public void onAccessibilityHostChanged(boolean available) {
            FlOverlayWindowHost host = activeHost;
            if (!available && host != null && host.isAccessibilityHosted()) dismiss();
        }
        @Override public void onDisplayGeometryChanged() { dismiss(); }
    };

    public static void showText(Context c, String value, Runnable selectAll) {
        resetLockedRow();
        show(c, value, selectAll, MODE_MAIN, null);
    }

    public static void showTextAt(Context c, String value, Runnable selectAll, Rect anchor) {
        resetLockedRow();
        show(c, value, selectAll, MODE_MAIN,
                anchor == null || anchor.isEmpty() ? null : new Rect(anchor));
    }

    public static void showShareTargets(Context c, String value) {
        resetLockedRow();
        show(c, value, null, MODE_SHARE, null);
    }

    public static void showProcessTargets(Context c, String value) {
        resetLockedRow();
        show(c, value, null, MODE_PROCESS, null);
    }

    /** Keep submenus/back navigation attached to the row where the main toolbar first appeared. */
    private static void showOnCurrentRow(Context c, String value, Runnable selectAll, int mode) {
        if (!hasLockedRow() && activeCenterX != NO_POSITION && activeTopY != NO_POSITION) {
            lockedCenterX = activeCenterX;
            lockedTopY = activeTopY;
        }
        show(c, value, selectAll, mode, null);
    }

    private static synchronized void show(Context c, String value, Runnable selectAll,
                                          int mode, Rect anchor) {
        if (c == null) return;
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) return;
        dismiss();

        Context app = c.getApplicationContext();
        WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;
        FlOverlayWindowHost host = new FlOverlayWindowHost(app);

        LinearLayout root = FloatingMenuUi.root(app, mode == MODE_MAIN ? 24 : 20);

        if (mode == MODE_MAIN) {
            buildMainToolbar(app, root, text, selectAll);
        } else if (mode == MODE_MORE) {
            buildMoreMenu(app, root, text, selectAll);
        } else {
            buildTargetMenu(app, root, text, selectAll, mode);
        }

        root.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                dismiss();
                return true;
            }
            return false;
        });

        Rect usable = ScreenGeometry.usableBounds(app);
        int maxPopupWidth = Math.max(dp(app, 120), usable.width() - dp(app, 16));
        int width = mode == MODE_MAIN
                ? WindowManager.LayoutParams.WRAP_CONTENT
                : contentAdaptiveWidth(app, root, mode, maxPopupWidth);

        int maxMeasureW = Math.max(dp(app, 46), usable.width() - dp(app, 16));
        int maxMeasureH = Math.max(dp(app, 46), usable.height() - dp(app, 16));
        int widthSpec = width == WindowManager.LayoutParams.WRAP_CONTENT
                ? View.MeasureSpec.makeMeasureSpec(maxMeasureW, View.MeasureSpec.AT_MOST)
                : View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(maxMeasureH, View.MeasureSpec.AT_MOST);
        root.measure(widthSpec, heightSpec);
        int menuWidth = width == WindowManager.LayoutParams.WRAP_CONTENT
                ? Math.max(dp(app, 46), root.getMeasuredWidth()) : width;
        int menuHeight = Math.max(dp(app, 46), root.getMeasuredHeight());

        int[] pos = hasLockedRow()
                ? FloatingMenuPositioner.lockedRow(app, usable, lockedCenterX, lockedTopY,
                        menuWidth, menuHeight)
                : FloatingMenuPositioner.aroundAnchor(app, usable, anchor,
                        menuWidth, menuHeight, false);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = pos[0];
        lp.y = pos[1];
        if (host.add(root, lp, "float_action_menu")) {
            activeHost = host;
            activeView = root;
            activeCenterX = lp.x + menuWidth / 2;
            activeTopY = lp.y;
            OverlayRegistry.register("text_action_menu", OVERLAY_OWNER);
            DiagnosticLog.i(app, "FLOAT_ACTION_MENU", "show system-style mode=" + mode
                    + " chars=" + text.length()
                    + " anchor=" + (anchor == null ? "none" : anchor.toShortString())
                    + " lockedRow=" + (hasLockedRow() ? lockedTopY : -1)
                    + " width=" + menuWidth
                    + " height=" + menuHeight
                    + (mode == MODE_MAIN ? " mainItems=" + TextMenuSettings.mainItemCount(app) : "")
                    + " pos=" + lp.x + "," + lp.y
                    + " ui=FloatingMenuUi positioner=FloatingMenuPositioner"
                    + " accessibilityHost=" + host.isAccessibilityHosted()
                    + " type=" + lp.type);
        } else {
            DiagnosticLog.i(app, "FLOAT_ACTION_MENU", "show failed all hosts mode=" + mode);
            if (mode == MODE_SHARE) launchSystemShare(app, text);
            else if (mode == MODE_PROCESS) launchSystemProcess(app, text);
        }
    }

    /** Match Android's compact popup feel without forcing every submenu to a fixed wide size. */
    private static int contentAdaptiveWidth(Context app, View root, int mode, int screenMax) {
        int min = dp(app, mode == MODE_MORE ? 156 : 188);
        int cap = Math.min(screenMax, dp(app, mode == MODE_MORE ? 260 : 300));
        cap = Math.max(min, cap);
        int desired = widestTextRow(root) + root.getPaddingLeft() + root.getPaddingRight();
        return ScreenGeometry.clamp(Math.max(min, desired), min, cap);
    }

    private static int widestTextRow(View view) {
        int best = 0;
        if (view instanceof TextView tv) {
            CharSequence raw = tv.getText();
            String text = raw == null ? "" : raw.toString();
            int width = (int) Math.ceil(tv.getPaint().measureText(text))
                    + tv.getPaddingLeft() + tv.getPaddingRight();
            Drawable[] drawables = tv.getCompoundDrawables();
            Drawable left = drawables != null && drawables.length > 0 ? drawables[0] : null;
            Drawable right = drawables != null && drawables.length > 2 ? drawables[2] : null;
            if (left != null) {
                int dw = left.getBounds().width();
                if (dw <= 0) dw = left.getIntrinsicWidth();
                width += Math.max(0, dw) + tv.getCompoundDrawablePadding();
            }
            if (right != null) {
                int dw = right.getBounds().width();
                if (dw <= 0) dw = right.getIntrinsicWidth();
                width += Math.max(0, dw) + tv.getCompoundDrawablePadding();
            }
            best = width;
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                best = Math.max(best, widestTextRow(group.getChildAt(i)));
            }
        }
        return best;
    }

    private static void buildMainToolbar(Context app, LinearLayout root, String text,
                                         Runnable selectAll) {
        List<CustomMenuActionStore.Item> customs = CustomMenuActionStore.load(app);
        boolean hasSelectAll = selectAll != null;
        int customLimit = mainCustomCount(app, customs.size(), hasSelectAll);

        ArrayList<View> items = new ArrayList<>();

        TextView copy = FloatingMenuUi.action(app, "复制", 58);
        items.add(copy);

        TextView all = null;
        if (hasSelectAll) {
            all = FloatingMenuUi.action(app, "全选", 58);
            items.add(all);
        }

        TextView share = FloatingMenuUi.action(app, "分享", 58);
        items.add(share);

        for (int i = 0; i < customLimit; i++) {
            CustomMenuActionStore.Item item = customs.get(i);
            TextView custom = FloatingMenuUi.action(app, item.label, 58);
            custom.setEllipsize(TextUtils.TruncateAt.END);
            custom.setSingleLine(true);
            custom.setOnClickListener(v -> launchCustom(app, item, text));
            items.add(custom);
        }

        TextView more = FloatingMenuUi.action(app, "⋮", 46);
        more.setTextSize(22);
        items.add(more);

        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("FloatLens", text));
            Toast.makeText(app, "已复制", Toast.LENGTH_SHORT).show();
            dismiss();
        });
        if (all != null) {
            all.setOnClickListener(v -> {
                try { selectAll.run(); } catch (Throwable ignored) {}
                dismiss();
            });
        }
        share.setOnClickListener(v -> showOnCurrentRow(app, text, selectAll, MODE_SHARE));
        more.setOnClickListener(v -> showOnCurrentRow(app, text, selectAll, MODE_MORE));

        Rect usable = ScreenGeometry.usableBounds(app);
        int maxRowWidth = Math.max(dp(app, 180), usable.width() - dp(app, 16));

        int slotWidth = Math.max(dp(app, 32),
                Math.min(dp(app, 72), maxRowWidth / Math.max(1, items.size())));
        LinearLayout row = toolbarRow(app);
        for (View item : items) {
            if (item instanceof TextView tv) {
                tv.setMinWidth(0);
                tv.setPadding(dp(app, 4), 0, dp(app, 4), 0);
                tv.setSingleLine(true);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                if (items.size() >= 7 && !"⋮".contentEquals(tv.getText())) {
                    tv.setTextSize(13);
                }
            }
            row.addView(item, new LinearLayout.LayoutParams(slotWidth, dp(app, 46)));
        }
        root.addView(row, new LinearLayout.LayoutParams(-2, -2));
    }

    private static LinearLayout toolbarRow(Context app) {
        LinearLayout row = new LinearLayout(app);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(app, 2), dp(app, 2), dp(app, 2), dp(app, 2));
        return row;
    }

    private static int mainCustomCount(Context app, int size, boolean hasSelectAll) {
        return TextMenuSettings.customSlots(app, hasSelectAll, size);
    }

    private static void buildMoreMenu(Context app, LinearLayout root, String text,
                                      Runnable selectAll) {
        root.setPadding(dp(app, 4), dp(app, 4), dp(app, 4), dp(app, 4));
        TextView back = FloatingMenuUi.row(app, "‹   返回", null);
        root.addView(back, new LinearLayout.LayoutParams(-1, dp(app, 46)));
        back.setOnClickListener(v -> showOnCurrentRow(app, text, selectAll, MODE_MAIN));

        List<CustomMenuActionStore.Item> customs = CustomMenuActionStore.load(app);
        int skip = mainCustomCount(app, customs.size(), selectAll != null);
        for (int i = skip; i < customs.size(); i++) {
            CustomMenuActionStore.Item item = customs.get(i);
            Drawable icon = null;
            try { icon = app.getPackageManager().getApplicationIcon(item.packageName); }
            catch (Throwable ignored) {}
            TextView custom = FloatingMenuUi.row(app, item.label, icon);
            root.addView(custom, new LinearLayout.LayoutParams(-1, dp(app, 48)));
            custom.setOnClickListener(v -> launchCustom(app, item, text));
        }

        TextView process = FloatingMenuUi.row(app, "打开 / 处理", null);
        root.addView(process, new LinearLayout.LayoutParams(-1, dp(app, 46)));
        process.setOnClickListener(v -> showOnCurrentRow(app, text, selectAll, MODE_PROCESS));
    }

    private static void launchCustom(Context app, CustomMenuActionStore.Item item, String text) {
        launchExternal(app, "custom", () -> CustomMenuActionStore.launch(app, item, text));
    }

    private static void buildTargetMenu(Context app, LinearLayout root, String text,
                                        Runnable selectAll, int mode) {
        root.setPadding(dp(app, 4), dp(app, 4), dp(app, 4), dp(app, 4));

        TextView back = FloatingMenuUi.row(app,
                mode == MODE_SHARE ? "‹   分享到" : "‹   打开 / 处理", null);
        back.setTypeface(back.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(back, new LinearLayout.LayoutParams(-1, dp(app, 46)));
        back.setOnClickListener(v -> showOnCurrentRow(app, text, selectAll, MODE_MAIN));

        PackageManager pm = app.getPackageManager();
        Intent base = mode == MODE_SHARE
                ? new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                : new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
                    .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                    .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true);

        List<ResolveInfo> resolved;
        try { resolved = pm.queryIntentActivities(base, PackageManager.MATCH_DEFAULT_ONLY); }
        catch (Throwable t) { resolved = new ArrayList<>(); }
        if (resolved == null) resolved = new ArrayList<>();
        resolved = new ArrayList<>(resolved);
        resolved.removeIf(ri -> ri == null || ri.activityInfo == null
                || app.getPackageName().equals(ri.activityInfo.packageName));

        String targetMode = mode == MODE_SHARE ? TargetMenuStore.MODE_SHARE : TargetMenuStore.MODE_PROCESS;
        resolved = applyTargetCustomization(app, targetMode, resolved);

        ScrollView scroll = new ScrollView(app);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(resolved.size() > 6);
        LinearLayout list = new LinearLayout(app);
        list.setOrientation(LinearLayout.VERTICAL);
        if (resolved.isEmpty()) {
            TextView none = FloatingMenuUi.secondaryRow(app,
                    mode == MODE_SHARE ? "当前没有已启用的分享应用" : "当前没有已启用的处理应用");
            none.setGravity(Gravity.CENTER_VERTICAL);
            none.setPadding(dp(app, 16), 0, dp(app, 16), 0);
            list.addView(none, new LinearLayout.LayoutParams(-1, dp(app, 48)));
        } else {
            for (ResolveInfo ri : resolved) {
                CharSequence rawLabel;
                try { rawLabel = ri.loadLabel(pm); }
                catch (Throwable ignored) { rawLabel = ri.activityInfo.name; }
                String fallbackLabel = rawLabel == null
                        ? ri.activityInfo.name : rawLabel.toString();
                String displayLabel = TargetMenuStore.displayLabel(app, targetMode,
                        ri.activityInfo.packageName + "|" + ri.activityInfo.name, fallbackLabel);
                Drawable icon = null;
                try { icon = ri.loadIcon(pm); } catch (Throwable ignored) {}
                TextView target = FloatingMenuUi.row(app, displayLabel, icon);
                target.setOnClickListener(v -> launchExplicit(app, base, ri));
                list.addView(target, new LinearLayout.LayoutParams(-1, dp(app, 50)));
            }
        }
        scroll.addView(list);
        int visibleRows = Math.min(Math.max(1, resolved.size()), 6);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, dp(app, 50 * visibleRows)));

        TextView moreApps = FloatingMenuUi.row(app,
                mode == MODE_SHARE ? "系统分享菜单…" : "更多处理应用…", null);
        root.addView(moreApps, new LinearLayout.LayoutParams(-1, dp(app, 46)));
        moreApps.setOnClickListener(v -> {
            if (mode == MODE_SHARE) launchSystemShare(app, text);
            else launchSystemProcess(app, text);
        });
    }

    /** Keep Android as the source of targets; FloatLens only layers its saved ordering/hide rules. */
    private static List<ResolveInfo> applyTargetCustomization(Context app, String mode,
                                                              List<ResolveInfo> resolved) {
        if (!TargetMenuStore.isCustomized(app, mode)) return resolved;

        PackageManager pm = app.getPackageManager();
        Map<String, ResolveInfo> byComponent = new HashMap<>();
        ArrayList<TargetMenuStore.Item> systemItems = new ArrayList<>();
        for (ResolveInfo ri : resolved) {
            if (ri == null || ri.activityInfo == null) continue;
            String key = ri.activityInfo.packageName + "|" + ri.activityInfo.name;
            byComponent.put(key, ri);
            CharSequence label;
            try { label = ri.loadLabel(pm); }
            catch (Throwable ignored) { label = ri.activityInfo.name; }
            systemItems.add(new TargetMenuStore.Item(
                    label == null ? ri.activityInfo.name : label.toString(),
                    ri.activityInfo.packageName,
                    ri.activityInfo.name));
        }

        ArrayList<ResolveInfo> ordered = new ArrayList<>();
        for (TargetMenuStore.Item item : TargetMenuStore.mergeWithSystem(app, mode, systemItems)) {
            ResolveInfo ri = byComponent.get(item.key());
            if (ri != null) ordered.add(ri);
        }
        return ordered;
    }

    private static void launchExplicit(Context app, Intent base, ResolveInfo ri) {
        launchExternal(app, "explicit", () -> {
            try {
                Intent target = new Intent(base)
                        .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                app.startActivity(target);
            } catch (Throwable t) {
                Toast.makeText(app, "无法打开该应用", Toast.LENGTH_SHORT).show();
                DiagnosticLog.i(app, "FLOAT_ACTION_MENU", "explicit launch failed=" + t);
            }
        });
    }

    static void launchSystemShare(Context app, String text) {
        launchExternal(app, "system_share", () -> {
            try {
                Intent share = new Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, text);
                app.startActivity(Intent.createChooser(share, "分享文字")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable t) {
                Toast.makeText(app, "无法打开分享菜单", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static void launchSystemProcess(Context app, String text) {
        launchExternal(app, "system_process", () -> {
            try {
                Intent process = new Intent(Intent.ACTION_PROCESS_TEXT)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                        .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true);
                app.startActivity(Intent.createChooser(process, "处理文字")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable t) {
                Toast.makeText(app, "没有可用的文本处理应用", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Accessibility overlays stay above normal activities. Remove the action menu and active
     * Circle workspace first, then launch the target on the next main-loop turn.
     */
    private static void launchExternal(Context app, String reason, Runnable launch) {
        dismiss();
        resetLockedRow();
        FLCircleInlineOverlay.dismissActive("external_text_action_" + reason);
        DiagnosticLog.i(app, "FLOAT_ACTION_MENU", "external prepare reason=" + reason);
        MAIN.post(() -> {
            try {
                launch.run();
                DiagnosticLog.i(app, "FLOAT_ACTION_MENU", "external launched reason=" + reason);
            } catch (Throwable t) {
                DiagnosticLog.i(app, "FLOAT_ACTION_MENU", "external launch crashed reason="
                        + reason + " error=" + t);
            }
        });
    }

    public static synchronized void dismiss() {
        View v = activeView;
        FlOverlayWindowHost host = activeHost;
        activeView = null;
        activeHost = null;
        activeCenterX = NO_POSITION;
        activeTopY = NO_POSITION;
        OverlayRegistry.unregister("text_action_menu", OVERLAY_OWNER);
        if (v != null && host != null) host.remove(v, "float_action_menu");
    }

    private static boolean hasLockedRow() {
        return lockedCenterX != NO_POSITION && lockedTopY != NO_POSITION;
    }

    private static void resetLockedRow() {
        lockedCenterX = NO_POSITION;
        lockedTopY = NO_POSITION;
    }

    private static int dp(Context c, int v) {
        return FloatingMenuUi.dp(c, v);
    }

    private FloatActionMenu() {}
}
