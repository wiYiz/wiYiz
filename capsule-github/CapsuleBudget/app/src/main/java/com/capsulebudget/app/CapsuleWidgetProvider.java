package com.capsulebudget.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.widget.RemoteViews;

/**
 * 胶囊水族缸桌面小组件 v7
 *
 * 架构：左侧胶囊区 + 右侧文字区，各自独立 Bitmap + 点击事件
 * 颜色：严格延用 app COLORS 原色，液面/液滴/发光均从主色派生
 * 动画：与 app 内 pulseLiquid 完全一致——4段弹性膨胀 + 液面光泽闪 + 溅射液滴
 */
public class CapsuleWidgetProvider extends AppWidgetProvider {

    private static final String TAG = "CapsuleWidget";
    private static final String PREFS_NAME = "capsule_widget_data";
    private static final String THEME_PREFS = "capsule_widget_theme";
    private static final String SPLASH_PREFS = "capsule_splash_state";

    public static final String ACTION_SPLASH = "com.capsulebudget.app.ACTION_SPLASH";
    public static final String ACTION_SNAPSHOT = "com.capsulebudget.app.ACTION_SNAPSHOT";

    // ===== 配色方案（严格延用 app COLORS，所有颜色从此派生） =====
    // app: COLORS = ['#f87171','#fb923c','#fbbf24','#34d399','#60a5fa','#818cf8','#a78bfa','#f472b6','#2dd4bf','#94a3b8']
    // 每行：{name, mainColor=COLORS原色}
    // 其余颜色全部在代码中从 mainColor 算出，不再手动定义
    public static final String[][] THEMES = {
        {"珊瑚红",   "#f87171"},
        {"活力橙",   "#fb923c"},
        {"琥珀黄",   "#fbbf24"},
        {"翡翠绿",   "#34d399"},
        {"天空蓝",   "#60a5fa"},
        {"靛青蓝",   "#818cf8"},
        {"星云紫",   "#a78bfa"},
        {"樱花粉",   "#f472b6"},
        {"薄荷绿",   "#2dd4bf"},
        {"石墨灰",   "#94a3b8"},
    };

    // ===== 动画时长（对齐 app 内 pulseLiquid） =====
    public static final long SPLASH_DURATION = 1400;   // 膨胀总时长 1.4s
    public static final long SPLASH_DROP_DELAY = 250;   // 液滴延迟 250ms
    public static final long SPLASH_DROP_DURATION = 1000; // 液滴飞行 1s
    public static final long QUOTE_FADE_IN = 200;
    public static final long QUOTE_HOLD = 1800;
    public static final long QUOTE_FADE_OUT = 400;
    public static final long QUOTE_TOTAL = QUOTE_FADE_IN + QUOTE_HOLD + QUOTE_FADE_OUT;

    // 快照提示：语录HOLD期的后60%显示"保存快照"提示，点击右侧触发保存
    public static final long SNAPSHOT_HINT_START = QUOTE_FADE_IN + (long)(QUOTE_HOLD * 0.4f);
    public static final long SNAPSHOT_HINT_END = QUOTE_FADE_IN + QUOTE_HOLD;

    // ===== 液滴预设（对齐 app 内 spawnDrop） =====
    // app: spawnDrop(x, y, color, dx, dy, mini)
    //   大液滴: dx=35+random*20, dy=-45-random*25
    //   小液滴: dx=22+random*15, dy=-30-random*20, mini
    //   可选液滴: dx=15+random*12, dy=-38-random*15, mini
    // 这里用固定值（不依赖随机），保证每帧一致
    private static final float[][] DROP_PRESETS = {
        {0.5f,  40, -55, 0f},   // 大液滴，居中偏右飞
        {0.3f,  25, -38, 1f},   // 小液滴，偏左
        {0.7f,  18, -45, 1f},   // 小液滴，偏右
    };

    // ===== 语录系统 v2：三层液态感语录 =====
    // 层级优先级：成就彩蛋 > 日签 > 液面状态句

    // 液面状态句——按 remainRatio 区间，跟胶囊隐喻绑定
    private static final String[][] QUOTE_LIQUID = {
        {"full",    "液面很满，很安心 ☁️"},
        {"full",    "满到要溢出来了"},
        {"full",    "稳如老狗 💧"},
        {"full",    "安心感拉满"},
        {"high",    "还够喝一阵"},
        {"high",    "液面舒适区"},
        {"high",    "不慌不忙"},
        {"high",    "节奏刚好"},
        {"mid",     "液面在降了"},
        {"mid",     "注意节奏"},
        {"mid",     "省着点用"},
        {"mid",     "还剩半瓶水"},
        {"low",     "快见底了 💧"},
        {"low",     "一滴都不多了"},
        {"low",     "紧急节水模式"},
        {"low",     "底部告急"},
        {"dry",     "干透了 🫠"},
        {"dry",     "液面归零"},
        {"dry",     "超支了但没放弃"},
        {"dry",     "下月重新注满"},
    };

    // 日签——根据星期几，每天一句，有连续性
    private static final String[] QUOTE_WEEKDAY = {
        "新一周，液面加满",        // 周一 (index=1, 但数组0开始, 用 Calendar.MONDAY-1 偏移)
        "周二，稳住液面",          // 周二
        "一周过半，液面还行吗",    // 周三
        "还差一点就到周末",        // 周四
        "周五了，别让液面漏了",    // 周五
        "周末诱惑多，守好胶囊",    // 周六
        "这周的液面，怎么样",      // 周日
    };

    // 成就彩蛋——特殊条件触发
    private static final String[][] QUOTE_ACHIEVEMENT = {
        {"zero_spend",   "一滴都没漏 💎"},
        {"zero_spend",   "今天零漏损"},
        {"light_spend",  "今天的液面纹丝不动"},
        {"light_spend",  "极轻消耗，液面没感觉"},
        {"month_start",  "新月份，液面重置 🔄"},
        {"month_start",  "全新一管胶囊"},
        {"month_end",    "月底冲刺，守住液面"},
        {"month_end",    "最后几天，别漏水"},
    };

    // ===== 颜色工具：从主色派生所有需要的颜色 =====

    /** 液面底部色：主色 +30% 黑色叠加，模拟液面底部微深 */
    static int deriveDarkerColor(int mainColor) {
        return blendColor(mainColor, Color.BLACK, 0.25f);
    }

    /** 发光带色：主色 +40% 白色叠加 */
    static int deriveGlowColor(int mainColor) {
        return blendColor(mainColor, Color.WHITE, 0.4f);
    }

    /** 气泡色：主色 +60% 白色叠加 */
    static int deriveBubbleColor(int mainColor) {
        return blendColor(mainColor, Color.WHITE, 0.6f);
    }

    /** 语录色：主色 -20% 亮度 */
    static int deriveQuoteColor(int mainColor) {
        return blendColor(mainColor, Color.BLACK, 0.2f);
    }

    /** 混合两个颜色 */
    static int blendColor(int base, int overlay, float ratio) {
        float[] baseHsv = new float[3];
        float[] overlayHsv = new float[3];
        Color.colorToHSV(base, baseHsv);
        Color.colorToHSV(overlay, overlayHsv);
        // 在RGB空间混合更准确
        int r = (int) (Color.red(base) * (1 - ratio) + Color.red(overlay) * ratio);
        int g = (int) (Color.green(base) * (1 - ratio) + Color.green(overlay) * ratio);
        int b = (int) (Color.blue(base) * (1 - ratio) + Color.blue(overlay) * ratio);
        return Color.rgb(
                Math.min(255, Math.max(0, r)),
                Math.min(255, Math.max(0, g)),
                Math.min(255, Math.max(0, b))
        );
    }

    // ===== Paint 缓存（避免每帧 new） =====
    private static Paint sFillPaint;
    private static Paint sStrokePaint;
    private static Paint sTextPaint;
    private static Paint sBoldTextPaint;

    private static void ensurePaints() {
        if (sFillPaint == null) {
            sFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            sFillPaint.setStyle(Paint.Style.FILL);
        }
        if (sStrokePaint == null) {
            sStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            sStrokePaint.setStyle(Paint.Style.STROKE);
        }
        if (sTextPaint == null) {
            sTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            sTextPaint.setTextAlign(Paint.Align.CENTER);
        }
        if (sBoldTextPaint == null) {
            sBoldTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            sBoldTextPaint.setTypeface(android.graphics.Typeface.create(
                    android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
            sBoldTextPaint.setTextAlign(Paint.Align.CENTER);
        }
    }

    // ===== AppWidgetProvider 生命周期 =====

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            updateWidget(context, appWidgetManager, id);
        }
        ensureBreathService(context);
    }

    @Override
    public void onEnabled(Context context) {
        super.onEnabled(context);
        ensureBreathService(context);
    }

    @Override
    public void onDisabled(Context context) {
        super.onDisabled(context);
        context.stopService(new Intent(context, WidgetBreathService.class));
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (intent == null) return;
        String action = intent.getAction();
        if (ACTION_SPLASH.equals(action)) {
            int widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
            WidgetBreathService.startSplashAnim(context, widgetId);
        } else if (ACTION_SNAPSHOT.equals(action)) {
            int widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
            SnapshotHelper.saveSnapshot(context, widgetId);
        }
    }

    private void ensureBreathService(Context context) {
        Intent svc = new Intent(context, WidgetBreathService.class);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(svc);
        } else {
            context.startService(svc);
        }
    }

    // ===== 小组件更新 =====

    private void updateWidget(Context context, AppWidgetManager wm, int widgetId) {
        RemoteViews views = buildNormalViews(context, widgetId);
        wm.updateAppWidget(widgetId, views);
    }

    public static RemoteViews buildNormalViews(Context context, int widgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.capsule_widget_4x2);

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        float totalBudget = prefs.getFloat("totalBudget", 0);
        float totalSpent = prefs.getFloat("totalSpent", 0);
        float remain = totalBudget - totalSpent;
        float remainRatio = totalBudget > 0 ? Math.max(remain / totalBudget, 0f) : 0f;
        int themeIdx = getCurrentThemeIndex(context, widgetId);

        // 左侧胶囊
        views.setImageViewBitmap(R.id.widget_capsule,
                drawCapsuleBitmap(remainRatio, themeIdx, 0));

        // 右侧文字
        String remainText = remain >= 0
                ? "本月剩余 ¥" + formatMoney(remain)
                : "超支 ¥" + formatMoney(Math.abs(remain));
        views.setImageViewBitmap(R.id.widget_text_area,
                drawTextBitmap(remainText, 0, themeIdx));

        // 左侧点击→溅射
        Intent splashIntent = new Intent(context, CapsuleWidgetProvider.class);
        splashIntent.setAction(ACTION_SPLASH);
        splashIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        PendingIntent splashPi = PendingIntent.getBroadcast(context, widgetId, splashIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_capsule, splashPi);

        // 右侧点击→打开app
        Intent launchIntent = new Intent(context, MainActivity.class);
        PendingIntent launchPi = PendingIntent.getActivity(context, widgetId * 10, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_text_area, launchPi);

        return views;
    }

    // ===== 左侧胶囊绘制 =====

    /**
     * 绘制竖胶囊 Bitmap
     * 胶囊在左半区水平+垂直居中，尺寸约占画布宽45%×高78%
     *
     * @param remainRatio   剩余比例 0~1
     * @param themeIdx      配色索引
     * @param splashElapsed 溅射动画已过毫秒，0=正常态
     */
    public static Bitmap drawCapsuleBitmap(float remainRatio, int themeIdx, long splashElapsed) {
        ensurePaints();
        int w = 130, h = 300;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        int mainColor = Color.parseColor(THEMES[themeIdx][1]);
        int glowColor = deriveGlowColor(mainColor);
        int bubbleColor = deriveBubbleColor(mainColor);

        // --- 动画插值（4段弹性，对齐 app bulgePop） ---
        boolean isSplashing = splashElapsed > 0 && splashElapsed < SPLASH_DURATION;
        float scaleX = 1f;
        float sheenAlpha = 0f;
        float liquidRise = 0f;

        if (isSplashing) {
            float t = (float) splashElapsed / SPLASH_DURATION;
            // app: 0%→15%→35%→55%→100% => scaleX 1→1.14→0.96→1.03→1
            if (t <= 0.15f) {
                float p = t / 0.15f;
                scaleX = 1f + 0.14f * easeOutCubic(p);
            } else if (t <= 0.35f) {
                float p = (t - 0.15f) / 0.2f;
                scaleX = 1.14f - 0.18f * easeInOutCubic(p);
            } else if (t <= 0.55f) {
                float p = (t - 0.35f) / 0.2f;
                scaleX = 0.96f + 0.07f * easeInOutCubic(p);
            } else {
                float p = (t - 0.55f) / 0.45f;
                scaleX = 1.03f - 0.03f * easeOutCubic(p);
            }
            // 光泽闪（对齐 app liquidSheen：0→15% alpha=0.2, 然后 fade out）
            if (t <= 0.15f) {
                sheenAlpha = (t / 0.15f) * 0.2f;
            } else {
                sheenAlpha = 0.2f * Math.max(0f, 1f - (t - 0.15f) / 0.85f);
            }
            liquidRise = (scaleX - 1f) * 20f;
        }

        // --- 胶囊尺寸（左半区居中） ---
        float capsuleW = w * 0.55f * scaleX;
        float capsuleH = h * 0.78f;
        float capsuleLeft = (w - capsuleW) / 2f;
        float capsuleTop = (h - capsuleH) / 2f;
        float radius = capsuleW * 0.5f;
        RectF capsuleRect = new RectF(capsuleLeft, capsuleTop, capsuleLeft + capsuleW, capsuleTop + capsuleH);

        // 1. 胶囊内部填充——白色（跟背景一致，不产生灰色蒙板）
        sFillPaint.setShader(null);
        sFillPaint.setColor(Color.WHITE);
        canvas.drawRoundRect(capsuleRect, radius, radius, sFillPaint);

        // 玻璃高光（左上角弧线）
        sStrokePaint.setStrokeWidth(1f);
        sStrokePaint.setColor(Color.argb(40, 255, 255, 255));
        RectF hlRect = new RectF(
                capsuleRect.left + 4, capsuleRect.top + 4,
                capsuleRect.left + capsuleW * 0.5f, capsuleRect.top + capsuleH * 0.2f);
        canvas.drawArc(hlRect, 190, 60, false, sStrokePaint);

        // 2. 液面
        if (remainRatio > 0) {
            canvas.save();
            Path clipPath = new Path();
            clipPath.addRoundRect(capsuleRect, radius, radius, Path.Direction.CW);
            canvas.clipPath(clipPath);

            long now = System.currentTimeMillis();
            float breatheOffset = (float) Math.sin(now / 2000.0) * 3f;

            float liquidHeight = capsuleRect.height() * remainRatio;
            float liquidTopY = capsuleRect.bottom - liquidHeight + breatheOffset - liquidRise;
            liquidTopY = Math.max(capsuleRect.top + 4, Math.min(liquidTopY, capsuleRect.bottom - 4));

            // 液面纯色（跟 app 一致，app 里液面就是 cat.color 纯色，不渐变）
            sFillPaint.setShader(null);
            sFillPaint.setColor(mainColor);

            // 波浪
            Path liquidPath = new Path();
            liquidPath.moveTo(capsuleRect.left, capsuleRect.bottom);
            liquidPath.lineTo(capsuleRect.left, liquidTopY);
            float waveAmp = 2f + remainRatio * 1.5f;
            for (float x = capsuleRect.left; x <= capsuleRect.right; x += 1) {
                float progress = (x - capsuleRect.left) / capsuleRect.width();
                float y = liquidTopY + waveAmp * (float) Math.sin(
                        progress * Math.PI * 2 + now * 0.0015);
                liquidPath.lineTo(x, y);
            }
            liquidPath.lineTo(capsuleRect.right, capsuleRect.bottom);
            liquidPath.close();
            canvas.drawPath(liquidPath, sFillPaint);

            // 光泽闪（溅射时白色叠加）
            if (sheenAlpha > 0.01f) {
                sFillPaint.setShader(null);
                sFillPaint.setColor(Color.argb((int)(sheenAlpha * 255), 255, 255, 255));
                canvas.drawRoundRect(capsuleRect, radius, radius, sFillPaint);
            }

            // 发光带（液面顶部微光）
            sFillPaint.setShader(null);
            float glowAlpha = 30 + 30 * (float) Math.sin(now / 2000.0);
            sFillPaint.setColor(glowColor);
            sFillPaint.setAlpha((int) glowAlpha);
            RectF glowRect = new RectF(capsuleRect.left, liquidTopY - 3,
                    capsuleRect.right, liquidTopY + 7);
            canvas.drawRoundRect(glowRect, 3, 3, sFillPaint);
            sFillPaint.setAlpha(255);

            // 气泡
            if (remainRatio > 0.5f) {
                drawBubbles(canvas, capsuleRect, liquidTopY, bubbleColor, now);
            }

            // 溅射液滴（颜色=主色，对齐 app）
            if (isSplashing && splashElapsed >= SPLASH_DROP_DELAY) {
                float dropElapsed = splashElapsed - SPLASH_DROP_DELAY;
                drawSplashDrops(canvas, capsuleRect, liquidTopY, mainColor, dropElapsed);
            }

            canvas.restore();
        }

        // 3. 胶囊边框
        sStrokePaint.setShader(null);
        sStrokePaint.setStrokeWidth(1.5f);
        sStrokePaint.setColor(Color.argb(120, 209, 213, 219)); // #D1D5DB alpha=120
        canvas.drawRoundRect(capsuleRect, radius, radius, sStrokePaint);

        // 4. 底部微阴影
        sFillPaint.setShader(null);
        sFillPaint.setColor(Color.argb(10, 0, 0, 0));
        RectF shadowRect = new RectF(
                capsuleRect.left + 3, capsuleRect.bottom - 4,
                capsuleRect.right - 3, capsuleRect.bottom + 2);
        canvas.drawOval(shadowRect, sFillPaint);

        return bmp;
    }

    // ===== 右侧文字绘制 =====

    /**
     * 绘制右侧文字 Bitmap
     * 画布尺寸 260×300（右2/3区域）
     * @param showSnapshotHint 是否显示"保存快照"提示
     */
    public static Bitmap drawTextBitmap(String text, long quoteElapsed, int themeIdx, boolean showSnapshotHint) {
        ensurePaints();
        int w = 260, h = 300;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        if (text == null || text.isEmpty()) return bmp;

        int mainColor = Color.parseColor(THEMES[themeIdx][1]);
        int quoteColor = deriveQuoteColor(mainColor);

        // 语录淡入淡出 alpha
        float textAlpha = 1f;
        boolean isQuoting = quoteElapsed > 0;
        if (isQuoting) {
            if (quoteElapsed < QUOTE_FADE_IN) {
                textAlpha = (float) quoteElapsed / QUOTE_FADE_IN;
            } else if (quoteElapsed < QUOTE_FADE_IN + QUOTE_HOLD) {
                textAlpha = 1f;
            } else if (quoteElapsed < QUOTE_TOTAL) {
                textAlpha = 1f - (float)(quoteElapsed - QUOTE_FADE_IN - QUOTE_HOLD) / QUOTE_FADE_OUT;
            } else {
                textAlpha = 0f;
            }
        }

        // 解析标签和金额
        String label, amount;
        if (text.startsWith("本月剩余")) {
            label = "本月剩余";
            amount = text.substring(4).trim();
        } else if (text.startsWith("超支")) {
            label = "超支";
            amount = text.substring(2).trim();
        } else if (text.startsWith("今日花费")) {
            label = "今日花费";
            amount = text.substring(4).trim();
        } else if (text.startsWith("剩余")) {
            label = "剩余";
            amount = text.substring(2).trim();
        } else {
            // 语录：单行大字
            label = "";
            amount = text;
        }

        float textX = w / 2f;
        int alpha255 = (int)(textAlpha * 255);

        // 语录单行居中（偏上，为快照提示留空间）
        if (label.isEmpty()) {
            float textCenterY = showSnapshotHint ? h * 0.4f : h / 2f;
            sBoldTextPaint.setTextSize(38f);
            sBoldTextPaint.setColor(isQuoting ? quoteColor : Color.parseColor("#333333"));
            sBoldTextPaint.setAlpha(alpha255);
            float textWidth = sBoldTextPaint.measureText(amount);
            float maxWidth = w * 0.85f;
            if (textWidth > maxWidth) {
                sBoldTextPaint.setTextSize(38f * maxWidth / textWidth);
            }
            float textY = textCenterY - (sBoldTextPaint.descent() + sBoldTextPaint.ascent()) / 2f;
            canvas.drawText(amount, textX, textY, sBoldTextPaint);
            sBoldTextPaint.setAlpha(255);

            // 快照提示（在语录下方）
            if (showSnapshotHint && isQuoting && textAlpha > 0.3f) {
                sTextPaint.setTextSize(18f);
                // 提示文字alpha：语录本身alpha的60%，柔和不抢眼
                int hintAlpha = (int)(textAlpha * 0.6f * 255);
                sTextPaint.setColor(mainColor);
                sTextPaint.setAlpha(hintAlpha);
                String hintText = "📸 点此保存快照";
                float hintWidth = sTextPaint.measureText(hintText);
                if (hintWidth > w * 0.8f) {
                    sTextPaint.setTextSize(18f * (w * 0.8f) / hintWidth);
                }
                float hintY = textY + 45f;
                canvas.drawText(hintText, textX, hintY, sTextPaint);
                sTextPaint.setAlpha(255);
            }
            return bmp;
        }

        // 两行垂直居中
        sTextPaint.setTextSize(26f);
        sTextPaint.setColor(isQuoting ? quoteColor : Color.parseColor("#999999"));
        sTextPaint.setAlpha(alpha255);

        sBoldTextPaint.setTextSize(38f);
        sBoldTextPaint.setColor(isQuoting ? quoteColor : Color.parseColor("#333333"));
        sBoldTextPaint.setAlpha(alpha255);

        float labelH = sTextPaint.descent() - sTextPaint.ascent();
        float amountH = sBoldTextPaint.descent() - sBoldTextPaint.ascent();
        float gap = 4f;
        float startY = h / 2f - (labelH + gap + amountH) / 2f;

        float labelY = startY - sTextPaint.ascent();
        canvas.drawText(label, textX, labelY, sTextPaint);

        float amountY = labelY + labelH + gap - sBoldTextPaint.ascent();
        canvas.drawText(amount, textX, amountY, sBoldTextPaint);

        sTextPaint.setAlpha(255);
        sBoldTextPaint.setAlpha(255);
        return bmp;
    }

    /**
     * 兼容旧调用：不带快照提示
     */
    public static Bitmap drawTextBitmap(String text, long quoteElapsed, int themeIdx) {
        return drawTextBitmap(text, quoteElapsed, themeIdx, false);
    }

    // ===== 动画曲线（对齐 app bulgePop + liquidSheen） =====

    private static float easeOutCubic(float t) {
        return 1f - (float) Math.pow(1f - t, 3);
    }

    private static float easeInOutCubic(float t) {
        return t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f;
    }

    // ===== 溅射液滴（对齐 app dropFly） =====
    // app: dropFly 1s cubic-bezier(0.22,1,0.36,1)
    //   translate(0,0) → translate(dx,dy), scale(1→0.3), opacity(1→0.8→0)

    private static void drawSplashDrops(Canvas canvas, RectF rect, float liquidTopY,
                                         int color, float dropElapsed) {
        float dropProgress = Math.min(dropElapsed / (float) SPLASH_DROP_DURATION, 1f);
        if (dropProgress >= 1f) return;

        // cubic-bezier(0.22, 1, 0.36, 1) 简化近似
        float easedProgress = easeOutCubic(dropProgress);

        for (float[] preset : DROP_PRESETS) {
            float originX = rect.left + rect.width() * preset[0];
            float originY = liquidTopY;
            float dx = preset[1];
            float dy = preset[2];
            boolean mini = preset[3] > 0.5f;

            // 直线飞出（跟 app 的 CSS translate 一致）
            float x = originX + dx * easedProgress;
            float y = originY + dy * easedProgress;

            // scale(1→0.3)
            float scale = 1f - 0.7f * dropProgress;
            float baseR = mini ? 4f : 6f;
            float r = baseR * scale;

            // opacity: 1 → 0.8(50%) → 0(100%)
            float alpha;
            if (dropProgress < 0.5f) {
                alpha = 1f - 0.2f * (dropProgress / 0.5f);
            } else {
                alpha = 0.8f * (1f - (dropProgress - 0.5f) / 0.5f);
            }

            // 液滴阴影
            sFillPaint.setShader(null);
            sFillPaint.setColor(Color.argb((int)(alpha * 60), 0, 0, 0));
            canvas.drawCircle(x + 1, y + 1, r, sFillPaint);

            // 液滴主体（主色）
            sFillPaint.setColor(color);
            sFillPaint.setAlpha((int)(alpha * 255));
            canvas.drawCircle(x, y, r, sFillPaint);

            // 高光
            sFillPaint.setColor(Color.argb((int)(alpha * 128), 255, 255, 255));
            canvas.drawCircle(x - r * 0.25f, y - r * 0.25f, r * 0.35f, sFillPaint);
        }
        sFillPaint.setAlpha(255);
    }

    // ===== 气泡 =====

    private static void drawBubbles(Canvas canvas, RectF rect, float liquidTopY, int color, long t) {
        float[] xPosRatios = {0.3f, 0.55f, 0.75f};
        for (int i = 0; i < 3; i++) {
            float cx = rect.left + rect.width() * xPosRatios[i];
            float baseY = rect.bottom - 8;
            float range = baseY - liquidTopY - 6;
            if (range <= 0) continue;
            float cycle = (t / (3000f + i * 1100f)) % 1f;
            float cy = baseY - cycle * range;
            float r = 1.5f + i * 0.6f;
            int alpha = Math.max(8, (int) (50 * (1 - cycle * 0.7f)));
            sFillPaint.setShader(null);
            sFillPaint.setColor(color);
            sFillPaint.setAlpha(alpha);
            canvas.drawCircle(cx, cy, r, sFillPaint);
        }
        sFillPaint.setAlpha(255);
    }

    // ===== 语录系统 v2 =====

    /**
     * 三层语录匹配：成就彩蛋 > 日签 > 液面状态句
     * 同一次动画期间只返回同一条（基于 splashStart 作为 seed）
     */
    public static String getQuote(Context context, int widgetId) {
        SharedPreferences splashPrefs = context.getSharedPreferences(SPLASH_PREFS, Context.MODE_PRIVATE);
        long splashStart = splashPrefs.getLong("splash_" + widgetId, 0);
        int seed = (int) (splashStart / 1000);
        java.util.Random rng = new java.util.Random(seed);

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        float totalBudget = prefs.getFloat("totalBudget", 0);
        float totalSpent = prefs.getFloat("totalSpent", 0);
        float todaySpent = prefs.getFloat("todaySpent", 0);
        float remain = totalBudget - totalSpent;
        float remainRatio = totalBudget > 0 ? remain / totalBudget : 0f;
        float dailyAvg = totalBudget / 30f;

        // --- 第1层：成就彩蛋 ---
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int dayOfMonth = cal.get(java.util.Calendar.DAY_OF_MONTH);
        int maxDay = cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH);

        String achievementCond = null;
        if (todaySpent == 0) {
            achievementCond = "zero_spend";
        } else if (dailyAvg > 0 && todaySpent < dailyAvg * 0.5f) {
            achievementCond = "light_spend";
        } else if (dayOfMonth <= 3) {
            achievementCond = "month_start";
        } else if (dayOfMonth >= maxDay - 2) {
            achievementCond = "month_end";
        }

        if (achievementCond != null) {
            java.util.List<String> achMatches = new java.util.ArrayList<>();
            for (String[] q : QUOTE_ACHIEVEMENT) {
                if (q[0].equals(achievementCond)) achMatches.add(q[1]);
            }
            if (!achMatches.isEmpty()) return achMatches.get(rng.nextInt(achMatches.size()));
        }

        // --- 第2层：日签（周一~周日） ---
        int dow = cal.get(java.util.Calendar.DAY_OF_WEEK); // 1=Sunday, 2=Monday, ...
        int weekdayIdx;
        if (dow == java.util.Calendar.SUNDAY) {
            weekdayIdx = 6; // 周日在数组末尾
        } else {
            weekdayIdx = dow - 2; // Monday=0, Tuesday=1, ...
        }
        // 30% 概率出日签，70% 出液面状态句（让日签不总是出现，有新鲜感）
        if (rng.nextFloat() < 0.3f && weekdayIdx >= 0 && weekdayIdx < QUOTE_WEEKDAY.length) {
            return QUOTE_WEEKDAY[weekdayIdx];
        }

        // --- 第3层：液面状态句 ---
        String liquidCond;
        if (remain < 0) {
            liquidCond = "dry";
        } else if (remainRatio > 0.8f) {
            liquidCond = "full";
        } else if (remainRatio > 0.5f) {
            liquidCond = "high";
        } else if (remainRatio > 0.2f) {
            liquidCond = "mid";
        } else {
            liquidCond = "low";
        }

        java.util.List<String> liquidMatches = new java.util.ArrayList<>();
        for (String[] q : QUOTE_LIQUID) {
            if (q[0].equals(liquidCond)) liquidMatches.add(q[1]);
        }
        if (!liquidMatches.isEmpty()) return liquidMatches.get(rng.nextInt(liquidMatches.size()));

        return "每一笔都算数";
    }

    /**
     * 判断当前是否处于"可保存快照"时间窗口
     * 语录HOLD期后60% + FADE_OUT期 = 可以保存快照的时段
     */
    public static boolean isSnapshotWindow(long quoteElapsed) {
        if (quoteElapsed <= 0) return false;
        return quoteElapsed >= SNAPSHOT_HINT_START && quoteElapsed < QUOTE_TOTAL;
    }

    // ===== 工具 =====

    static int getCurrentThemeIndex(Context context, int widgetId) {
        SharedPreferences sp = context.getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE);
        return sp.getInt("theme_" + widgetId, 0);
    }

    static void saveThemeIndex(Context context, int widgetId, int index) {
        SharedPreferences sp = context.getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE);
        sp.edit().putInt("theme_" + widgetId, index).apply();
    }

    static String formatMoney(float amount) {
        if (amount == (int) amount) return String.valueOf((int) amount);
        return String.format("%.1f", amount);
    }

    /**
     * 右侧文字：显示剩余预算金额
     */
    static String getCaptainLogText(float remainRatio, float remainingAmount) {
        String amountStr = "¥" + formatMoney(remainingAmount);
        if (remainingAmount < 0) {
            return "超支 " + amountStr;
        } else {
            return "剩余 " + amountStr;
        }
    }

    public static void refreshAllWidgets(Context context) {
        Intent intent = new Intent(context, CapsuleWidgetProvider.class);
        intent.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
        AppWidgetManager wm = AppWidgetManager.getInstance(context);
        int[] ids = wm.getAppWidgetIds(new ComponentName(context, CapsuleWidgetProvider.class));
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids);
        context.sendBroadcast(intent);
    }
}
