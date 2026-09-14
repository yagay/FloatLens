package com.yagay.floatlens;

import android.text.format.DateFormat;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

/** Builds the optional Root / LSPosed settings page using the same UI primitives as FloatLens. */
public final class PrivilegeSettingsPanel {
    private PrivilegeSettingsPanel() {}

    public static LinearLayout build(AppCompatActivity activity, FloatSettings fs) {
        LinearLayout root = AppUi.pageRoot(activity, "高级权限",
                "Root 和 LSPosed 都是可选增强。默认关闭；关闭总开关时 FloatLens 只使用普通 Android / 无障碍方法。" );

        TextView modeStatus = AppUi.caption(activity, "", 13);
        TextView rootStatus = AppUi.caption(activity, "", 13);
        TextView lsposedStatus = AppUi.caption(activity, "", 13);

        AppUi.Section master = AppUi.section(activity, "增强模式总开关",
                "这个开关优先级最高。关闭后会保留下面各项的选择，但运行时不会进入 Root 或 LSPosed 增强 Provider。" );
        SwitchMaterial enhanced = preferenceSwitch(activity, fs,
                "启用增强模式",
                "开启后才允许使用下面单独启用的 Root / LSPosed 功能；关闭时始终使用普通方法。",
                FloatSettings.K_ENHANCED_MODE, fs.enhancedMode(),
                () -> refresh(activity, fs, modeStatus, rootStatus, lsposedStatus));
        AppUi.addRow(master.body, AppUi.switchContainer(enhanced));
        AppUi.addRow(master.body, statusBlock(activity, "当前生效模式", modeStatus));
        AppUi.addSection(root, master);

        AppUi.Section rootSection = AppUi.section(activity, "Root",
                "Root 不会因为进入设置页而自动申请授权。只有实际使用 Root 功能，或你主动点击“检测 Root 授权”时，才可能出现 KernelSU / Magisk 的授权提示。" );
        SwitchMaterial rootSwitch = preferenceSwitch(activity, fs,
                "使用 Root 功能",
                "仅在“启用增强模式”同时开启时生效。目前已接入截图后端；其他 Root Provider 以后也必须经过同一个总开关。",
                FloatSettings.K_ROOT_ENABLED, fs.rootEnabled(),
                () -> refresh(activity, fs, modeStatus, rootStatus, lsposedStatus));
        AppUi.addRow(rootSection.body, AppUi.switchContainer(rootSwitch));
        AppUi.addRow(rootSection.body, statusBlock(activity, "Root 状态", rootStatus));

        LinearLayout rootButtons = AppUi.buttonRow(activity);
        MaterialButton checkRoot = AppUi.secondaryButton(activity, "检测 Root 授权");
        checkRoot.setOnClickListener(v -> {
            checkRoot.setEnabled(false);
            rootStatus.setText("正在检测…可能会弹出 Root 管理器授权窗口");
            PrivilegeManager.checkRootAsync(activity, result -> {
                checkRoot.setEnabled(true);
                refresh(activity, fs, modeStatus, rootStatus, lsposedStatus);
                Toast.makeText(activity,
                        result.granted ? "Root 已授权" : "Root 不可用或未授权",
                        Toast.LENGTH_SHORT).show();
            });
        });
        rootButtons.addView(checkRoot, new LinearLayout.LayoutParams(0, -2, 1f));
        AppUi.addRow(rootSection.body, rootButtons);

        TextView rootNote = AppUi.caption(activity,
                "截图要真正使用 Root，需要同时满足：① 增强模式开启；② 使用 Root 功能开启；③ “截图与 OCR → Root 截图增强”开启。缺少任意一项都不会调用 Root 截图。",
                12);
        AppUi.addRow(rootSection.body, simpleBlock(activity, rootNote));
        AppUi.addSection(root, rootSection);

        AppUi.Section lsposedSection = AppUi.section(activity, "LSPosed",
                "这个开关控制 FloatLens 是否允许使用 LSPosed 增强 Provider。LSPosed 模块是否被注入、作用域包含哪些应用，仍由 LSPosed 管理器负责。" );
        SwitchMaterial lsposedSwitch = preferenceSwitch(activity, fs,
                "使用 LSPosed 功能",
                "仅在“启用增强模式”同时开启时生效。关闭后 FloatLens 功能代码应继续走 Accessibility / OCR 等普通实现。",
                FloatSettings.K_LSPOSED_ENABLED, fs.lsposedEnabled(),
                () -> refresh(activity, fs, modeStatus, rootStatus, lsposedStatus));
        AppUi.addRow(lsposedSection.body, AppUi.switchContainer(lsposedSwitch));
        AppUi.addRow(lsposedSection.body, statusBlock(activity, "Hook 通信状态", lsposedStatus));
        TextView lsposedNote = AppUi.caption(activity,
                "“检测到 Hook 通信”表示至少有一次已注入的 LSPosed 作用域进程向 FloatLens 回传过消息。它用于确认模块链路，不等于所有应用都已加入作用域。关闭本页开关也不会卸载 LSPosed 模块，只会禁止 FloatLens 选择 LSPosed 增强 Provider。",
                12);
        AppUi.addRow(lsposedSection.body, simpleBlock(activity, lsposedNote));
        AppUi.addSection(root, lsposedSection);

        AppUi.Section fallback = AppUi.section(activity, "失败回退",
                "建议保持开启。增强能力在不同 ROM、应用和 Android 版本上可能不可用，回退可以避免因为 Root / Hook 失败导致原本能用的普通功能也失败。" );
        SwitchMaterial fallbackSwitch = preferenceSwitch(activity, fs,
                "增强方法失败时回退普通方法",
                "例如 Root 截图失败后重新尝试无障碍截图。关闭后，选中的增强后端失败会直接返回错误。",
                FloatSettings.K_PRIVILEGE_FALLBACK, fs.privilegeFallback(),
                () -> refresh(activity, fs, modeStatus, rootStatus, lsposedStatus));
        AppUi.addRow(fallback.body, AppUi.switchContainer(fallbackSwitch));
        AppUi.addSection(root, fallback);

        AppUi.Section behavior = AppUi.section(activity, "实际选择规则", null);
        TextView rules = AppUi.caption(activity,
                "普通模式：Accessibility / 系统 API / OCR。\n\n"
                        + "Root：只有功能自己的 Root 开关也开启时才会使用；当前截图已经按此规则接入。\n\n"
                        + "LSPosed：只有总开关和 LSPosed 开关都开启时，功能 Provider 才允许请求 Hook 增强；不可用时按回退设置使用普通 Provider。\n\n"
                        + "因此你可以长期安装 Root / LSPosed 版本，但平时关闭“启用增强模式”，FloatLens 的功能行为就与普通模式一致。",
                12);
        AppUi.addRow(behavior.body, simpleBlock(activity, rules));
        AppUi.addSection(root, behavior);

        refresh(activity, fs, modeStatus, rootStatus, lsposedStatus);
        return root;
    }

    private static SwitchMaterial preferenceSwitch(AppCompatActivity activity, FloatSettings fs,
                                                    String title, String subtitle,
                                                    String key, boolean current, Runnable changed) {
        return AppUi.switchRow(activity, title, subtitle, current, (button, checked) -> {
            fs.prefs().edit().putBoolean(key, checked).apply();
            DiagnosticLog.i(activity, "PRIVILEGE", "setting " + key + "=" + checked);
            if (changed != null) changed.run();
        });
    }

    private static LinearLayout statusBlock(AppCompatActivity activity, String title, TextView status) {
        LinearLayout block = AppUi.settingBlock(activity);
        block.addView(AppUi.text(activity, title, 14, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = AppUi.dp(activity, 4);
        block.addView(status, lp);
        return block;
    }

    private static LinearLayout simpleBlock(AppCompatActivity activity, TextView text) {
        LinearLayout block = AppUi.settingBlock(activity);
        block.addView(text, new LinearLayout.LayoutParams(-1, -2));
        return block;
    }

    private static void refresh(AppCompatActivity activity, FloatSettings fs,
                                TextView modeStatus, TextView rootStatus, TextView lsposedStatus) {
        modeStatus.setText(PrivilegeManager.modeLabel(fs)
                + (fs.enhancedMode() ? " · 增强总开关已开启" : " · 增强总开关已关闭"));
        modeStatus.setTextColor(fs.enhancedMode() ? AppUi.success(activity) : AppUi.textPrimary(activity));

        long rootAt = fs.rootLastCheckMs();
        if (rootAt <= 0L) {
            rootStatus.setText("尚未检测。检测只在你点击按钮后执行，不会自动调用 su。\n"
                    + "当前 Provider：" + (fs.canUseRoot() ? "允许使用 Root" : "不允许使用 Root"));
        } else {
            String when = DateFormat.format("yyyy-MM-dd HH:mm", rootAt).toString();
            rootStatus.setText((fs.rootLastGranted() ? "已授权" : "未授权 / 不可用")
                    + " · 上次检测 " + when
                    + "\n当前 Provider：" + (fs.canUseRoot() ? "允许使用 Root" : "不允许使用 Root"));
        }
        rootStatus.setTextColor(fs.rootLastGranted() ? AppUi.success(activity) : AppUi.textPrimary(activity));

        long seen = fs.lsposedLastSeenMs();
        if (seen <= 0L) {
            lsposedStatus.setText("尚未检测到 Hook 通信。请确认 LSPosed 中已启用模块和作用域；"
                    + "当前 Provider：" + (fs.canUseLsposed() ? "允许使用 LSPosed" : "不允许使用 LSPosed"));
            lsposedStatus.setTextColor(AppUi.textPrimary(activity));
        } else {
            String when = DateFormat.format("yyyy-MM-dd HH:mm", seen).toString();
            lsposedStatus.setText("已检测到 Hook 通信 · " + when
                    + (fs.lsposedLastSource().isBlank() ? "" : " · " + fs.lsposedLastSource())
                    + "\n当前 Provider：" + (fs.canUseLsposed() ? "允许使用 LSPosed" : "不允许使用 LSPosed"));
            lsposedStatus.setTextColor(AppUi.success(activity));
        }
    }
}
