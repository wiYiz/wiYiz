package com.capsulebudget.app;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 小组件配色选择页面
 * 从 app 长按快捷方式「组件配色」进入
 */
public class WidgetColorActivity extends AppCompatActivity {

    private static final String THEME_PREFS = "capsule_widget_theme";

    private static final String[][] THEMES = CapsuleWidgetProvider.THEMES;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 检查是否有小组件
        AppWidgetManager wm = AppWidgetManager.getInstance(this);
        int[] ids = wm.getAppWidgetIds(new ComponentName(this, CapsuleWidgetProvider.class));
        if (ids.length == 0) {
            // 没有小组件，提示用户先添加
            setContentView(createNoWidgetView());
            return;
        }

        setContentView(createColorPickerView(ids));
    }

    private View createNoWidgetView() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(48, 48, 48, 48);

        TextView tv = new TextView(this);
        tv.setText("还没有添加胶囊水族缸小组件哦\n\n长按桌面空白处 → 小组件 → 胶囊记账\n添加后再来选配色吧 💊");
        tv.setTextSize(16);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(0xFF666666);
        layout.addView(tv);

        // 关闭按钮
        TextView closeBtn = new TextView(this);
        closeBtn.setText("知道了");
        closeBtn.setTextSize(15);
        closeBtn.setTextColor(0xFF6c63ff);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setPadding(32, 24, 32, 8);
        closeBtn.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 32;
        layout.addView(closeBtn, lp);

        return layout;
    }

    private View createColorPickerView(int[] widgetIds) {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(40, 32, 40, 32);

        // 标题
        TextView title = new TextView(this);
        title.setText("选择胶囊配色");
        title.setTextSize(20);
        title.setTextColor(0xFF333333);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        container.addView(title);

        // 副标题
        TextView subtitle = new TextView(this);
        subtitle.setText("选择你喜欢的配色，小组件即时更新");
        subtitle.setTextSize(13);
        subtitle.setTextColor(0xFF999999);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = 8;
        subLp.bottomMargin = 24;
        container.addView(subtitle, subLp);

        // 当前选中的配色索引（取第一个小组件的）
        int currentTheme = getCurrentThemeIndex(widgetIds[0]);

        // 配色选项
        for (int i = 0; i < THEMES.length; i++) {
            final int themeIdx = i;
            String[] theme = THEMES[i];

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(16, 20, 16, 20);

            // 配色预览圆点（用liquidTop颜色）
            ImageView dot = new ImageView(this);
            int dotSize = (int) (36 * getResources().getDisplayMetrics().density);
            dot.setBackgroundColor(android.graphics.Color.parseColor(theme[1]));
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dotSize, dotSize);
            row.addView(dot, dotLp);

            // 配色名
            TextView name = new TextView(this);
            name.setText(theme[0]);
            name.setTextSize(16);
            name.setTextColor(0xFF333333);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            nameLp.leftMargin = 20;
            row.addView(name, nameLp);

            // 选中标记
            if (i == currentTheme) {
                TextView check = new TextView(this);
                check.setText("✓");
                check.setTextSize(18);
                check.setTextColor(android.graphics.Color.parseColor(theme[1]));
                row.addView(check);
            }

            // 点击选择
            row.setOnClickListener(v -> {
                for (int id : widgetIds) {
                    CapsuleWidgetProvider.saveThemeIndex(this, id, themeIdx);
                }
                // 立即刷新小组件
                CapsuleWidgetProvider.refreshAllWidgets(this);
                // 刷新页面
                setContentView(createColorPickerView(widgetIds));
            });

            // 分隔线
            if (i < THEMES.length - 1) {
                View divider = new View(this);
                divider.setBackgroundColor(0xFFEEEEEE);
                LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1);
                divLp.leftMargin = 56;
                container.addView(divider);
            }

            container.addView(row);
        }

        // 底部关闭
        TextView closeBtn = new TextView(this);
        closeBtn.setText("完成");
        closeBtn.setTextSize(15);
        closeBtn.setTextColor(0xFF6c63ff);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setPadding(32, 24, 32, 16);
        closeBtn.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        closeLp.topMargin = 16;
        container.addView(closeBtn, closeLp);

        scrollView.addView(container);
        return scrollView;
    }

    private int getCurrentThemeIndex(int widgetId) {
        SharedPreferences sp = getSharedPreferences(THEME_PREFS, MODE_PRIVATE);
        return sp.getInt("theme_" + widgetId, 0);
    }
}
