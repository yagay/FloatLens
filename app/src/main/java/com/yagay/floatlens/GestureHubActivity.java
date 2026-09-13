package com.yagay.floatlens;

import android.os.Bundle;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;

/** Groups gesture thresholds/feedback and action mapping under one top-level entry. */
public final class GestureHubActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = AppUi.pageRoot(this, "手势与轨迹",
                "手势参数、轨迹反馈和动作映射集中在这里。" );

        AppUi.Section gesture = AppUi.section(this, "手势设置", null);
        AppUi.addRow(gesture.body, AppUi.navRow(this,
                "手势参数与轨迹",
                "长按、双击、滑动阈值、震动和轨迹样式",
                () -> startActivity(SettingsActivity.intent(this, SettingsActivity.SECTION_GESTURE))));
        AppUi.addRow(gesture.body, AppUi.navRow(this,
                "手势动作映射",
                "指定单击、双击、长按和各方向滑动动作",
                () -> startActivity(SettingsActivity.intent(this, SettingsActivity.SECTION_ACTIONS))));
        AppUi.addSection(root, gesture);

        setContentView(AppUi.scrollPage(this, root));
    }
}
