package com.yagay.floatlens;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_EXPORT_LOG = 701;
    private static final int REQ_EXPORT_INSPECTOR = 702;
    private TextView inspectorStatus;
    private boolean statusLoadedOnce;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(28), dp(24), dp(32));
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText("FloatLens\n悬浮取词 / 截图 / OCR");
        title.setTextSize(24); root.addView(title);

        TextView desc = new TextView(this);
        desc.setText("只保留 fooView 风格悬浮图标、手势、截图、区域截图、OCR 和系统动作。没有文件管理器、浏览器等其他功能。");
        desc.setTextSize(16); desc.setPadding(0, dp(16), 0, dp(24)); root.addView(desc);

        Button overlay = button("1. 授予悬浮窗权限");
        overlay.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        root.addView(overlay);

        Button access = button("2. 开启无障碍（截图/系统动作）");
        access.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(access);

        if (Build.VERSION.SDK_INT >= 33) {
            Button notify = button("3. 授予通知权限（推荐）");
            notify.setOnClickListener(v -> {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 20);
                else Toast.makeText(this, "通知权限已授予", Toast.LENGTH_SHORT).show();
            });
            root.addView(notify);
        }

        Button start = button("启动悬浮图标");
        start.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) { Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show(); return; }
            ContextCompat.startForegroundService(this, new Intent(this, FloatService.class).setAction(FloatService.ACT_START));
        });
        root.addView(start);

        Button stop = button("停止悬浮图标");
        stop.setOnClickListener(v -> stopService(new Intent(this, FloatService.class)));
        root.addView(stop);

        Button settings = button("悬浮图标与手势参数");
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        root.addView(settings);

        Button customMenu = button("自定义文字操作菜单");
        customMenu.setOnClickListener(v -> startActivity(new Intent(this, CustomActionPickerActivity.class)));
        root.addView(customMenu);

        Button shareMenu = button("自定义分享菜单");
        shareMenu.setOnClickListener(v -> startActivity(new Intent(this, TargetMenuPickerActivity.class)
                .putExtra(TargetMenuPickerActivity.EXTRA_MODE, TargetMenuStore.MODE_SHARE)));
        root.addView(shareMenu);

        Button processMenu = button("自定义打开 / 处理菜单");
        processMenu.setOnClickListener(v -> startActivity(new Intent(this, TargetMenuPickerActivity.class)
                .putExtra(TargetMenuPickerActivity.EXTRA_MODE, TargetMenuStore.MODE_PROCESS)));
        root.addView(processMenu);

        Button exportLog = button("导出 FV 诊断日志");
        exportLog.setOnClickListener(v -> {
            String text=DiagnosticLog.read(this);
            if(text.isBlank()){Toast.makeText(this,"暂无诊断日志，请先在参数页开启 FV 诊断日志并操作悬浮图标",Toast.LENGTH_LONG).show();return;}
            Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain").putExtra(Intent.EXTRA_TITLE,"FloatLens-FV-diagnostic.txt");
            startActivityForResult(i,REQ_EXPORT_LOG);
        });
        root.addView(exportLog);

        Button clearLog = button("清空 FV 诊断日志");
        clearLog.setOnClickListener(v -> { DiagnosticLog.clear(this); Toast.makeText(this,"诊断日志已清空",Toast.LENGTH_SHORT).show(); });
        root.addView(clearLog);

        inspectorStatus = new TextView(this);
        inspectorStatus.setPadding(0, dp(18), 0, dp(12));
        inspectorStatus.setText("FV Hook 自检：点击刷新");
        root.addView(inspectorStatus);

        Button selfTest = button("刷新 FV Hook 自检");
        selfTest.setOnClickListener(v -> { sendInspectorCommand("selftest"); inspectorStatus.postDelayed(this::refreshInspectorStatus, 700); });
        root.addView(selfTest);

        Button inspectorOn = button("FV Runtime Inspector：全部记录开启");
        inspectorOn.setOnClickListener(v -> sendInspectorCommand("all_on"));
        root.addView(inspectorOn);

        Button probeOn = button("Method Probe：开启（高级/高日志量）");
        probeOn.setOnClickListener(v -> sendInspectorCommand("probe_on"));
        root.addView(probeOn);

        Button probeOff = button("Method Probe：关闭");
        probeOff.setOnClickListener(v -> sendInspectorCommand("probe_off"));
        root.addView(probeOff);

        Button exportInspector = button("导出 FV Runtime Inspector ZIP");
        exportInspector.setOnClickListener(v -> {
            if(!InspectorLog.hasAny(this)){Toast.makeText(this,"暂无 Hook 日志。请先在 LSPosed 启用 FloatLens，并把作用域设为 fooView，然后强制停止并重新打开 fooView。",Toast.LENGTH_LONG).show();return;}
            Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").putExtra(Intent.EXTRA_TITLE,"FloatLens-FV-runtime-inspector.zip");
            startActivityForResult(i,REQ_EXPORT_INSPECTOR);
        });
        root.addView(exportInspector);

        Button clearInspector = button("清空 FV Runtime Inspector 日志");
        clearInspector.setOnClickListener(v -> { InspectorLog.clear(this); refreshInspectorStatus(); Toast.makeText(this,"Runtime Inspector 日志已清空",Toast.LENGTH_SHORT).show(); });
        root.addView(clearInspector);

        TextView status = new TextView(this);
        status.setPadding(0, dp(20), 0, 0);
        status.setText("提示：OCR 使用本地 ML Kit 中文识别。Root 截图可选，失败时会自动回退到无障碍截图。");
        root.addView(status);
        setContentView(sv);
    }

    @Override protected void onResume(){
        super.onResume();
        if(!statusLoadedOnce){statusLoadedOnce=true;inspectorStatus.post(this::refreshInspectorStatus);}
    }

    private void refreshInspectorStatus(){if(inspectorStatus!=null)inspectorStatus.setText(InspectorLog.selfTestStatus(this));}

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==REQ_EXPORT_LOG && resultCode==RESULT_OK && data!=null && data.getData()!=null){
            try(java.io.OutputStream out=getContentResolver().openOutputStream(data.getData())){
                if(out!=null){out.write(DiagnosticLog.read(this).getBytes(java.nio.charset.StandardCharsets.UTF_8));out.flush();Toast.makeText(this,"诊断日志已导出",Toast.LENGTH_SHORT).show();}
            }catch(Throwable t){Toast.makeText(this,"导出失败: "+t.getMessage(),Toast.LENGTH_LONG).show();}
        } else if(requestCode==REQ_EXPORT_INSPECTOR && resultCode==RESULT_OK && data!=null && data.getData()!=null){
            try(java.io.OutputStream out=getContentResolver().openOutputStream(data.getData())){
                if(out!=null){InspectorLog.exportZip(this,out);out.flush();Toast.makeText(this,"Runtime Inspector ZIP 已导出",Toast.LENGTH_SHORT).show();}
            }catch(Throwable t){Toast.makeText(this,"导出失败: "+t.getMessage(),Toast.LENGTH_LONG).show();}
        }
    }

    private void sendInspectorCommand(String cmd){
        try{Intent i=new Intent("com.yagay.floatlens.FV_HOOK_COMMAND").setPackage("com.fooview.android.fooview");i.putExtra("cmd",cmd);sendBroadcast(i);Toast.makeText(this,"已发送: "+cmd,Toast.LENGTH_SHORT).show();}
        catch(Throwable t){Toast.makeText(this,"发送失败: "+t.getMessage(),Toast.LENGTH_LONG).show();}
    }

    private Button button(String s) {Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setPadding(dp(10), dp(12), dp(10), dp(12)); return b;}
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
