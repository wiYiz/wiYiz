package com.capsulebudget.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.RemoteViews;

import androidx.annotation.Nullable;

/**
 * 胶囊水族缸呼吸动画服务 v5
 *
 * 20fps 推帧，驱动呼吸 + 溅射动画
 * 溅射：弹性膨胀 + 液面光泽 + 液滴飞出 + 语录淡入淡出
 * 语录在一次动画期间只选一条，不会闪烁
 */
public class WidgetBreathService extends Service {

    private static final String TAG = "WidgetBreath";
    private static final String CHANNEL_ID = "capsule_breath_channel";
    private static final int NOTIFICATION_ID = 2001;
    private static final long FRAME_INTERVAL = 50; // 20fps

    private Handler handler;
    private boolean running = false;
    private Bitmap lastCapsuleFrame;
    private Bitmap lastTextFrame;

    private static final String SPLASH_PREFS = "capsule_splash_state";

    private final Runnable frameRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            pushFrame();
            handler.postDelayed(this, FRAME_INTERVAL);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        startForeground();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            running = true;
            handler.post(frameRunnable);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacks(frameRunnable);
        recycleLast();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "胶囊水族缸", NotificationManager.IMPORTANCE_MIN);
            channel.setDescription("保持胶囊液面呼吸动画");
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            Notification notification = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("胶囊水族缸")
                    .setContentText("液面呼吸中")
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setOngoing(true)
                    .build();
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void recycleLast() {
        if (lastCapsuleFrame != null && !lastCapsuleFrame.isRecycled()) lastCapsuleFrame.recycle();
        lastCapsuleFrame = null;
        if (lastTextFrame != null && !lastTextFrame.isRecycled()) lastTextFrame.recycle();
        lastTextFrame = null;
    }

    /**
     * 启动溅射动画（由 Provider.onReceive 调用）
     */
    public static void startSplashAnim(Context context, int widgetId) {
        long now = System.currentTimeMillis();
        context.getSharedPreferences(SPLASH_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong("splash_" + widgetId, now)
                .putLong("quote_" + widgetId, now)
                .apply();
    }

    private void pushFrame() {
        try {
            AppWidgetManager wm = AppWidgetManager.getInstance(this);
            int[] ids = wm.getAppWidgetIds(new ComponentName(this, CapsuleWidgetProvider.class));
            if (ids.length == 0) return;

            SharedPreferences prefs = getSharedPreferences("capsule_widget_data", MODE_PRIVATE);
            float totalBudget = prefs.getFloat("totalBudget", 0);
            float totalSpent = prefs.getFloat("totalSpent", 0);
            float remain = totalBudget - totalSpent;
            float remainRatio = totalBudget > 0 ? Math.max(remain / totalBudget, 0f) : 0f;

            SharedPreferences themePrefs = getSharedPreferences("capsule_widget_theme", MODE_PRIVATE);
            SharedPreferences splashPrefs = getSharedPreferences(SPLASH_PREFS, MODE_PRIVATE);

            long now = System.currentTimeMillis();

            for (int id : ids) {
                int themeIdx = themePrefs.getInt("theme_" + id, 0);

                // --- 溅射动画状态 ---
                long splashStart = splashPrefs.getLong("splash_" + id, 0);
                long splashElapsed = 0;
                boolean isSplashing = false;

                if (splashStart > 0) {
                    splashElapsed = now - splashStart;
                    if (splashElapsed < CapsuleWidgetProvider.SPLASH_DURATION) {
                        isSplashing = true;
                    } else {
                        splashPrefs.edit().remove("splash_" + id).apply();
                    }
                }

                // --- 语录状态 ---
                long quoteStart = splashPrefs.getLong("quote_" + id, 0);
                long quoteElapsed = 0;
                boolean isQuoting = false;
                String quoteText = null;

                if (quoteStart > 0) {
                    quoteElapsed = now - quoteStart;
                    if (quoteElapsed < CapsuleWidgetProvider.QUOTE_TOTAL) {
                        isQuoting = true;
                        quoteText = CapsuleWidgetProvider.getQuote(this, id);
                    } else {
                        splashPrefs.edit().remove("quote_" + id).apply();
                    }
                }

                // --- 绘制 ---
                recycleLast();

                // 左侧胶囊
                lastCapsuleFrame = CapsuleWidgetProvider.drawCapsuleBitmap(
                        remainRatio, themeIdx,
                        isSplashing ? splashElapsed : 0);

                // 右侧文字：剩余预算
                String normalText = CapsuleWidgetProvider.getCaptainLogText(remainRatio, remain);

                String displayText;
                long textQuoteElapsed;
                boolean inSnapshotWindow = false;
                if (isQuoting && quoteText != null) {
                    displayText = quoteText;
                    textQuoteElapsed = quoteElapsed;
                    inSnapshotWindow = CapsuleWidgetProvider.isSnapshotWindow(quoteElapsed);
                } else {
                    displayText = normalText;
                    textQuoteElapsed = 0;
                }

                lastTextFrame = CapsuleWidgetProvider.drawTextBitmap(
                        displayText, textQuoteElapsed, themeIdx, inSnapshotWindow);

                RemoteViews views = new RemoteViews(getPackageName(), R.layout.capsule_widget_4x2);
                views.setImageViewBitmap(R.id.widget_capsule, lastCapsuleFrame);
                views.setImageViewBitmap(R.id.widget_text_area, lastTextFrame);

                // 左侧点击→溅射（动画中不重复触发）
                if (!isSplashing) {
                    Intent splashIntent = new Intent(this, CapsuleWidgetProvider.class);
                    splashIntent.setAction(CapsuleWidgetProvider.ACTION_SPLASH);
                    splashIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
                    android.app.PendingIntent splashPi = android.app.PendingIntent.getBroadcast(
                            this, id, splashIntent,
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
                    views.setOnClickPendingIntent(R.id.widget_capsule, splashPi);
                }

                // 右侧点击：快照窗口→保存快照，否则→打开app
                if (inSnapshotWindow) {
                    Intent snapIntent = new Intent(this, CapsuleWidgetProvider.class);
                    snapIntent.setAction(CapsuleWidgetProvider.ACTION_SNAPSHOT);
                    snapIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
                    android.app.PendingIntent snapPi = android.app.PendingIntent.getBroadcast(
                            this, id + 5000, snapIntent,
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
                    views.setOnClickPendingIntent(R.id.widget_text_area, snapPi);
                } else {
                    Intent launchIntent = new Intent(this, MainActivity.class);
                    android.app.PendingIntent launchPi = android.app.PendingIntent.getActivity(
                            this, id * 10, launchIntent,
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
                    views.setOnClickPendingIntent(R.id.widget_text_area, launchPi);
                }

                wm.updateAppWidget(id, views);
            }
        } catch (Exception e) {
            Log.e(TAG, "pushFrame error", e);
        }
    }
}
