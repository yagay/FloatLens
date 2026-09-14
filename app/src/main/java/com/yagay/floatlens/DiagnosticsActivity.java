package com.yagay.floatlens;

import android.content.Intent;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

/** FloatLens-only diagnostics. No third-party runtime inspection or method probing lives here. */
public final class DiagnosticsActivity extends AppCompatActivity {
    private static final int REQ_EXPORT_LOG = 701;

    private FloatSettings fs;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        fs = new FloatSettings(this);

        LinearLayout root = AppUi.pageRoot(this, "诊断与调试",
                "仅记录 FloatLens 自身的悬浮、截图、OCR、View 选择和结果窗口状态。" );

        AppUi.Section logging = AppUi.section(this, "FloatLens 诊断日志",
                "用于排查 FloatLens 自身问题；不 Hook、抓取或分析其他应用的运行时方法。" );
        SwitchMaterial loggingSwitch = AppUi.switchRow(this,
                "记录诊断日志",
                "关闭时不会持续写入 FloatLens 诊断日志",
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

        setContentView(AppUi.scrollPage(this, root));
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

    private void exportDiagnostic() {
        String text = DiagnosticLog.read(this);
        if (text.isBlank()) {
            Toast.makeText(this, "暂无诊断日志，请先开启记录并操作一次 FloatLens", Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TITLE, "FloatLens-diagnostic.txt");
        startActivityForResult(i, REQ_EXPORT_LOG);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_EXPORT_LOG || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        try (java.io.OutputStream out = getContentResolver().openOutputStream(data.getData())) {
            if (out != null) {
                out.write(DiagnosticLog.read(this).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
                Toast.makeText(this, "诊断日志已导出", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            Toast.makeText(this, "导出失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
