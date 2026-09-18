package com.yagay.floatlens;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** FloatLens-only diagnostics. */
public final class DiagnosticsActivity extends AppCompatActivity {
    private FloatSettings fs;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        fs = new FloatSettings(this);

        LinearLayout root = AppUi.pageRoot(this, "诊断与调试",
                "记录 FloatLens 自身运行状态，并可直接保存到 Download/FloatLens。" );

        AppUi.Section logging = AppUi.section(this, "FloatLens 诊断日志",
                "导出不再依赖系统文件选择器，Android 11+ 直接通过 MediaStore 写入下载目录。" );
        SwitchMaterial loggingSwitch = AppUi.switchRow(this,
                "记录诊断日志",
                "关闭时不会持续写入 FloatLens 诊断日志",
                fs.diagnosticLogging(),
                (button, checked) -> fs.setBoolean(FloatSettings.K_DIAGNOSTIC, checked));
        AppUi.addRow(logging.body, AppUi.switchContainer(loggingSwitch));
        addButtonPair(logging.body,
                button("保存诊断日志", this::exportDiagnostic),
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
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        leftLp.setMarginEnd(AppUi.dp(this, 6));
        row.addView(left, leftLp);
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rightLp.setMarginStart(AppUi.dp(this, 6));
        row.addView(right, rightLp);
        AppUi.addRow(parent, row);
    }

    private void exportDiagnostic() {
        String appText = DiagnosticLog.read(this);
        LsposedStatusManager.readGoogleCtsTraceAsync(ctsTrace -> {
            StringBuilder combined = new StringBuilder();
            if (!appText.isBlank()) combined.append(appText.trim()).append("\n");
            if (ctsTrace != null && !ctsTrace.isBlank()) {
                combined.append("\n===== LSPosed / Google CTS trace =====\n")
                        .append(ctsTrace.trim()).append("\n");
            }
            if (combined.length() == 0) {
                Toast.makeText(this, "暂无诊断日志，请先开启记录并操作一次 FloatLens",
                        Toast.LENGTH_LONG).show();
                return;
            }
            saveDiagnosticText(combined.toString());
        });
    }

    private void saveDiagnosticText(String text) {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String fileName = "FloatLens-diagnostic-" + stamp + ".txt";
        Uri uri = null;
        try {
            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/FloatLens");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new java.io.IOException("MediaStore insert returned null");

            try (OutputStream out = resolver.openOutputStream(uri, "w")) {
                if (out == null) throw new java.io.IOException("MediaStore returned null output stream");
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }

            ContentValues ready = new ContentValues();
            ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, ready, null, null);

            DiagnosticLog.i(this, "DIAGNOSTIC_EXPORT",
                    "saved uri=" + uri + " name=" + fileName + " bytes="
                            + text.getBytes(StandardCharsets.UTF_8).length);
            Toast.makeText(this,
                    "已保存到 下载/FloatLens/" + fileName,
                    Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            if (uri != null) {
                try { getContentResolver().delete(uri, null, null); } catch (Throwable ignored) {}
            }
            String message = t.getMessage();
            if (message == null || message.isBlank()) message = t.getClass().getSimpleName();
            Toast.makeText(this,
                    "保存失败: " + t.getClass().getSimpleName() + " · " + message,
                    Toast.LENGTH_LONG).show();
            DiagnosticLog.i(this, "DIAGNOSTIC_EXPORT",
                    "failed=" + t.getClass().getName() + ":" + message);
        }
    }
}
