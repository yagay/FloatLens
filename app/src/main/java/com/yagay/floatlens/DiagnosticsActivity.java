package com.yagay.floatlens;

import android.content.Intent;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

/** Advanced diagnostics kept out of the normal FloatLens home screen. */
public final class DiagnosticsActivity extends AppCompatActivity {
    private static final int REQ_EXPORT_LOG = 701;
    private static final int REQ_EXPORT_INSPECTOR = 702;

    private TextView inspectorStatus;
    private FloatSettings fs;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        fs = new FloatSettings(this);

        LinearLayout root = AppUi.pageRoot(this, "诊断与调试",
                "日常使用不需要这里。只有排查 FloatLens / fooView / LSPosed 行为时再开启。" );

        AppUi.Section logging = AppUi.section(this, "FL 诊断日志",
                "记录悬浮图标、截图、OCR 和结果窗口的关键运行状态。" );
        SwitchMaterial loggingSwitch = AppUi.switchRow(this,
                "记录诊断日志",
                "关闭时不会持续写入 FL 诊断日志",
                fs.diagnosticLogging(),
                (button, checked) -> fs.prefs().edit()
                        .putBoolean(FloatSettings.K_DIAGNOSTIC, checked).apply());
        AppUi.addRow(logging.body, AppUi.switchContainer(loggingSwitch));
        addButtonPair(logging.body,
                button("导出诊断日志", this::exportDiagnostic),
                button("清空", () -> {
                    DiagnosticLog.clear(this);
                    Toast.makeText(this, "诊断日志已清空", Toast.LENGTH_SHORT).show();
                }));
        AppUi.addSection(root, logging);

        AppUi.Section hook = AppUi.section(this, "Runtime Inspector",
                "用于检查 LSPosed Hook 和 fooView 运行时行为。Method Probe 日志量较大。" );
        inspectorStatus = AppUi.caption(this, "正在读取 Hook 自检状态…", 13);
        inspectorStatus.setPadding(AppUi.dp(this, 10), AppUi.dp(this, 8),
                AppUi.dp(this, 10), AppUi.dp(this, 10));
        inspectorStatus.setBackground(AppUi.rounded(this, AppUi.surfaceAlt(this), 14));
        AppUi.addRow(hook.body, inspectorStatus);

        addButtonPair(hook.body,
                button("刷新自检", () -> {
                    sendInspectorCommand("selftest");
                    inspectorStatus.postDelayed(this::refreshInspectorStatus, 700);
                }),
                button("全部记录开启", () -> sendInspectorCommand("all_on")));
        addButtonPair(hook.body,
                button("Method Probe 开启", () -> sendInspectorCommand("probe_on")),
                button("Method Probe 关闭", () -> sendInspectorCommand("probe_off")));
        addButtonPair(hook.body,
                button("导出 Inspector ZIP", this::exportInspector),
                button("清空 Inspector", () -> {
                    InspectorLog.clear(this);
                    refreshInspectorStatus();
                    Toast.makeText(this, "Runtime Inspector 日志已清空", Toast.LENGTH_SHORT).show();
                }));
        AppUi.addSection(root, hook);

        TextView note = AppUi.caption(this,
                "如果 Inspector 没有数据，请确认 LSPosed 已启用 FloatLens，并把作用域设为 fooView 后重新打开目标应用。",
                12);
        note.setPadding(AppUi.dp(this, 4), AppUi.dp(this, 2),
                AppUi.dp(this, 4), AppUi.dp(this, 8));
        root.addView(note);

        setContentView(AppUi.scrollPage(this, root));
        inspectorStatus.post(this::refreshInspectorStatus);
    }

    private MaterialButton button(String label, Runnable action) {
        MaterialButton button = AppUi.secondaryButton(this, label);
        button.setOnClickListener(v -> { if (action != null) action.run(); });
        return button;
    }

    private void addButtonPair(LinearLayout parent, MaterialButton left, MaterialButton right) {
        LinearLayout row = AppUi.buttonRow(this);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        leftLp.setMarginEnd(AppUi.dp(this, 6));
        row.addView(left, leftLp);
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rightLp.setMarginStart(AppUi.dp(this, 6));
        row.addView(right, rightLp);
        AppUi.addRow(parent, row);
    }

    private void refreshInspectorStatus() {
        if (inspectorStatus != null) inspectorStatus.setText(InspectorLog.selfTestStatus(this));
    }

    private void exportDiagnostic() {
        String text = DiagnosticLog.read(this);
        if (text.isBlank()) {
            Toast.makeText(this, "暂无诊断日志，请先开启记录并操作一次 FloatLens", Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TITLE, "FloatLens-FL-diagnostic.txt");
        startActivityForResult(i, REQ_EXPORT_LOG);
    }

    private void exportInspector() {
        if (!InspectorLog.hasAny(this)) {
            Toast.makeText(this, "暂无 Hook 日志，请先运行 Runtime Inspector", Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .setType("application/zip")
                .putExtra(Intent.EXTRA_TITLE, "FloatLens-FL-runtime-inspector.zip");
        startActivityForResult(i, REQ_EXPORT_INSPECTOR);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_EXPORT_LOG && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            try (java.io.OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                if (out != null) {
                    out.write(DiagnosticLog.read(this).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    out.flush();
                    Toast.makeText(this, "诊断日志已导出", Toast.LENGTH_SHORT).show();
                }
            } catch (Throwable t) {
                Toast.makeText(this, "导出失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_EXPORT_INSPECTOR && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            try (java.io.OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                if (out != null) {
                    InspectorLog.exportZip(this, out);
                    out.flush();
                    Toast.makeText(this, "Runtime Inspector ZIP 已导出", Toast.LENGTH_SHORT).show();
                }
            } catch (Throwable t) {
                Toast.makeText(this, "导出失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void sendInspectorCommand(String cmd) {
        try {
            Intent i = new Intent("com.yagay.floatlens.FL_HOOK_COMMAND")
                    .setPackage("com.fooview.android.fooview");
            i.putExtra("cmd", cmd);
            sendBroadcast(i);
            Toast.makeText(this, "已发送: " + cmd, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "发送失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
