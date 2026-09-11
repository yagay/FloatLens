package com.yagay.floatlens;

import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Lets the user choose, hide and reorder targets shown by FloatLens share/process menus. */
public final class TargetMenuPickerActivity extends AppCompatActivity {
    public static final String EXTRA_MODE = "mode";

    private String mode;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        mode = TargetMenuStore.MODE_PROCESS.equals(getIntent().getStringExtra(EXTRA_MODE))
                ? TargetMenuStore.MODE_PROCESS : TargetMenuStore.MODE_SHARE;
        showManager();
    }

    private void showManager() {
        boolean customized = TargetMenuStore.isCustomized(this, mode);
        List<TargetMenuStore.Item> items = customized
                ? TargetMenuStore.load(this, mode)
                : discoverItems();

        LinearLayout root = page(isShare() ? "自定义分享菜单" : "自定义打开 / 处理菜单");
        TextView note = text(customized
                ? "当前使用自定义列表。长按 ≡ 拖动排序，也可以用 ↑ / ↓ 调整；× 可隐藏目标。"
                : "当前使用自动列表。第一次排序或隐藏后会保存为自定义列表。", 14);
        note.setPadding(0, 0, 0, dp(10));
        root.addView(note);

        Button add = button("添加已隐藏的应用");
        add.setOnClickListener(v -> showAdd());
        root.addView(add);

        Button reset = button("恢复自动列表");
        reset.setEnabled(customized);
        reset.setOnClickListener(v -> {
            TargetMenuStore.reset(this, mode);
            Toast.makeText(this, "已恢复自动列表", Toast.LENGTH_SHORT).show();
            showManager();
        });
        root.addView(reset);

        TextView current = text("当前显示（" + items.size() + "）", 18);
        current.setPadding(0, dp(18), 0, dp(6));
        root.addView(current);

        if (items.isEmpty()) {
            TextView empty = text("当前没有目标；仍可在菜单底部使用“更多应用…”打开系统选择器。", 14);
            empty.setAlpha(.72f);
            root.addView(empty);
        } else {
            LinearLayout sortable = new LinearLayout(this);
            sortable.setOrientation(LinearLayout.VERTICAL);
            sortable.setOnDragListener((v, event) -> onSortDrag(sortable, event));
            for (int i = 0; i < items.size(); i++) {
                sortable.addView(targetRow(items.get(i), i, items.size(), items));
            }
            root.addView(sortable);
        }
        setPage(root);
    }

    private View targetRow(TargetMenuStore.Item item, int index, int total,
                           List<TargetMenuStore.Item> snapshot) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(5), dp(2), dp(5));

        TextView handle = text("≡", 24);
        handle.setGravity(Gravity.CENTER);
        handle.setOnLongClickListener(v -> {
            ensureCustomized(snapshot);
            ClipData clip = ClipData.newPlainText("FloatLens target", item.key());
            return row.startDragAndDrop(clip, new View.DragShadowBuilder(row), item.key(), 0);
        });
        row.addView(handle, new LinearLayout.LayoutParams(dp(40), dp(54)));

        Drawable icon = targetIcon(item);
        if (icon != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(icon);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(36), dp(36));
            ip.setMarginEnd(dp(10));
            row.addView(iv, ip);
        }

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(item.label, 15);
        title.setSingleLine(true);
        texts.addView(title);
        TextView sub = text(shortClass(item.className), 12);
        sub.setAlpha(.62f);
        sub.setSingleLine(true);
        texts.addView(sub);
        row.addView(texts, new LinearLayout.LayoutParams(0, dp(54), 1));

        TextView up = sortButton("↑", index > 0);
        up.setOnClickListener(v -> {
            ensureCustomized(snapshot);
            if (TargetMenuStore.move(this, mode, item.key(), -1)) showManager();
        });
        row.addView(up, new LinearLayout.LayoutParams(dp(38), dp(44)));

        TextView down = sortButton("↓", index < total - 1);
        down.setOnClickListener(v -> {
            ensureCustomized(snapshot);
            if (TargetMenuStore.move(this, mode, item.key(), 1)) showManager();
        });
        row.addView(down, new LinearLayout.LayoutParams(dp(38), dp(44)));

        TextView remove = sortButton("×", true);
        remove.setOnClickListener(v -> {
            ensureCustomized(snapshot);
            TargetMenuStore.remove(this, mode, item.key());
            showManager();
        });
        row.addView(remove, new LinearLayout.LayoutParams(dp(40), dp(44)));
        return row;
    }

    private void showAdd() {
        List<TargetMenuStore.Item> discovered = discoverItems();
        List<TargetMenuStore.Item> current = TargetMenuStore.isCustomized(this, mode)
                ? TargetMenuStore.load(this, mode) : discoverItems();
        Set<String> selected = new HashSet<>();
        for (TargetMenuStore.Item item : current) selected.add(item.key());

        LinearLayout root = page(isShare() ? "添加分享应用" : "添加处理应用");
        Button back = button("‹ 返回");
        back.setOnClickListener(v -> showManager());
        root.addView(back);

        int available = 0;
        for (TargetMenuStore.Item item : discovered) {
            if (selected.contains(item.key())) continue;
            available++;
            View row = simpleRow(item, () -> {
                if (!TargetMenuStore.isCustomized(this, mode)) {
                    TargetMenuStore.save(this, mode, current);
                }
                if (TargetMenuStore.add(this, mode, item)) {
                    Toast.makeText(this, "已加入", Toast.LENGTH_SHORT).show();
                    showManager();
                }
            });
            root.addView(row);
        }
        if (available == 0) {
            TextView none = text("没有可添加的目标。隐藏某个应用后可在这里重新加入。", 14);
            none.setPadding(0, dp(14), 0, 0);
            none.setAlpha(.72f);
            root.addView(none);
        }
        setPage(root);
    }

    private View simpleRow(TargetMenuStore.Item item, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(7), dp(4), dp(7));
        row.setClickable(true);
        row.setFocusable(true);

        Drawable icon = targetIcon(item);
        if (icon != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(icon);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(40), dp(40));
            ip.setMarginEnd(dp(12));
            row.addView(iv, ip);
        }

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView a = text(item.label, 16);
        a.setSingleLine(true);
        texts.addView(a);
        TextView b = text(shortClass(item.className), 12);
        b.setSingleLine(true);
        b.setAlpha(.62f);
        texts.addView(b);
        row.addView(texts, new LinearLayout.LayoutParams(0, dp(54), 1));

        TextView add = text("加入", 13);
        add.setGravity(Gravity.CENTER);
        row.addView(add, new LinearLayout.LayoutParams(dp(58), dp(44)));
        row.setOnClickListener(v -> action.run());
        add.setOnClickListener(v -> action.run());
        return row;
    }

    private boolean onSortDrag(LinearLayout list, DragEvent event) {
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return event.getLocalState() instanceof String;
            case DragEvent.ACTION_DRAG_LOCATION:
                return true;
            case DragEvent.ACTION_DROP: {
                Object state = event.getLocalState();
                if (!(state instanceof String key)) return false;
                int target = dropIndexForY(list, event.getY());
                TargetMenuStore.moveTo(this, mode, key, target);
                showManager();
                return true;
            }
            case DragEvent.ACTION_DRAG_ENDED:
                return true;
            default:
                return true;
        }
    }

    private void ensureCustomized(List<TargetMenuStore.Item> snapshot) {
        if (!TargetMenuStore.isCustomized(this, mode)) {
            TargetMenuStore.save(this, mode, new ArrayList<>(snapshot));
        }
    }

    private List<TargetMenuStore.Item> discoverItems() {
        Intent base = isShare()
                ? new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "FloatLens")
                : new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
                    .putExtra(Intent.EXTRA_PROCESS_TEXT, "FloatLens")
                    .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true);

        List<ResolveInfo> resolved;
        try { resolved = getPackageManager().queryIntentActivities(base, PackageManager.MATCH_DEFAULT_ONLY); }
        catch (Throwable t) { resolved = new ArrayList<>(); }
        if (resolved == null) resolved = new ArrayList<>();
        resolved = new ArrayList<>(resolved);
        resolved.removeIf(ri -> ri == null || ri.activityInfo == null
                || getPackageName().equals(ri.activityInfo.packageName));
        resolved.sort(Comparator.comparing(this::sortLabel, String.CASE_INSENSITIVE_ORDER));

        ArrayList<TargetMenuStore.Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : resolved) {
            String pkg = ri.activityInfo.packageName;
            String cls = ri.activityInfo.name;
            String key = pkg + "|" + cls;
            if (!seen.add(key)) continue;
            out.add(new TargetMenuStore.Item(resolveLabel(ri), pkg, cls));
        }
        return out;
    }

    private String sortLabel(ResolveInfo ri) {
        if (ri == null || ri.activityInfo == null) return "";
        String app = ri.activityInfo.applicationInfo == null ? ri.activityInfo.packageName
                : appLabel(ri.activityInfo.applicationInfo.packageName);
        return app + " " + resolveLabel(ri);
    }

    private String resolveLabel(ResolveInfo ri) {
        try {
            CharSequence c = ri.loadLabel(getPackageManager());
            if (c != null && !c.toString().isBlank()) return c.toString();
        } catch (Throwable ignored) {}
        return ri.activityInfo == null ? "应用" : appLabel(ri.activityInfo.packageName);
    }

    private String appLabel(String pkg) {
        try {
            CharSequence c = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0));
            if (c != null && !c.toString().isBlank()) return c.toString();
        } catch (Throwable ignored) {}
        return pkg;
    }

    private Drawable targetIcon(TargetMenuStore.Item item) {
        try {
            return getPackageManager().getActivityIcon(new ComponentName(item.packageName, item.className));
        } catch (Throwable ignored) {
            try { return getPackageManager().getApplicationIcon(item.packageName); }
            catch (Throwable ignored2) { return null; }
        }
    }

    private int dropIndexForY(LinearLayout list, float y) {
        int count = list.getChildCount();
        if (count <= 1) return 0;
        for (int i = 0; i < count; i++) {
            View child = list.getChildAt(i);
            if (y < (child.getTop() + child.getBottom()) / 2f) return i;
        }
        return count - 1;
    }

    private TextView sortButton(String value, boolean enabled) {
        TextView tv = text(value, 20);
        tv.setGravity(Gravity.CENTER);
        tv.setEnabled(enabled);
        tv.setAlpha(enabled ? 1f : .28f);
        tv.setClickable(enabled);
        tv.setFocusable(enabled);
        return tv;
    }

    private LinearLayout page(String title) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(30));
        TextView t = text(title, 22);
        t.setPadding(0, 0, 0, dp(12));
        root.addView(t);
        return root;
    }

    private void setPage(LinearLayout content) {
        ScrollView sv = new ScrollView(this);
        sv.addView(content);
        setContentView(sv);
    }

    private TextView text(String value, int sp) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sp);
        return tv;
    }

    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setAllCaps(false);
        return b;
    }

    private String shortClass(String name) {
        if (name == null) return "Activity";
        int p = name.lastIndexOf('.');
        return p >= 0 ? name.substring(p + 1) : name;
    }

    private boolean isShare() {
        return TargetMenuStore.MODE_SHARE.equals(mode);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override public void onBackPressed() {
        finish();
    }
}
