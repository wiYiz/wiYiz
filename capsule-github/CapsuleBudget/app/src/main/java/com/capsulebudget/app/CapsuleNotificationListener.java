package com.capsulebudget.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通知监听服务 —— 拦截支付类通知，在通知栏弹出"快速记账"通知
 *
 * 流程：
 * 1. 监听所有通知，识别支付关键词+金额
 * 2. 在系统通知栏弹出一个"快速记账"通知（显示金额和来源）
 * 3. 用户点击通知 → 打开 MainActivity → 弹出记账面板
 */
public class CapsuleNotificationListener extends NotificationListenerService {

    private static final String TAG = "CapsuleNotifListener";
    private static final String CHANNEL_ID = "capsule_quick_add";
    private static final int NOTIFICATION_ID = 1001;

    // 金额正则
    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
            "[¥￥]\\s*(\\d+\\.?\\d{0,2})|(?:RMB|人民币)\\s*(\\d+\\.?\\d{0,2})|(\\d+\\.?\\d{0,2})\\s*[元圆]",
            Pattern.CASE_INSENSITIVE
    );

    // 支付关键词
    private static final String[] PAYMENT_KEYWORDS = {
            "付款", "支付", "消费", "扣除", "转出", "支出", "刷卡", "收款码付款",
            "你已付款", "已支付", "成功付款", "支付成功", "付款成功",
            "账单", "扣款", "代扣", "交易成功", "转账成功", "转入",
            "尾号", "入账", "出账", "余额"
    };

    // 排除的包名
    private static final String[] EXCLUDED_PACKAGES = {
            "com.capsulebudget.app",
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.packageinstaller",
            "com.google.android.gms",
            "com.miui.",
    };

    // 防重复：5秒内同一金额不重复触发
    private static long lastTriggerTime = 0;
    private static float lastTriggerAmount = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.e(TAG, "=== Notification listener CONNECTED ===");
        StatusBarNotification[] active = getActiveNotifications();
        Log.e(TAG, "Active notifications count: " + (active != null ? active.length : 0));
    }

    @Override
    public void onListenerDisconnected() {
        Log.e(TAG, "=== Notification listener DISCONNECTED ===");
        super.onListenerDisconnected();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            String pkg = sbn.getPackageName();
            Notification n = sbn.getNotification();
            if (n == null) return;

            // 排除系统/自身通知
            if (isExcluded(pkg)) return;

            // 提取通知文本
            String text = extractNotificationText(n);
            if (TextUtils.isEmpty(text)) return;

            // 检查支付关键词
            if (!containsPaymentKeyword(text)) return;

            // 提取金额
            float amount = extractAmount(text);
            if (amount <= 0) {
                Log.e(TAG, "Payment keyword found but no amount: pkg=" + pkg + " text=" + text);
                return;
            }

            Log.e(TAG, ">>> Payment detected: pkg=" + pkg + " amount=" + amount + " text=" + text);

            // 防重复
            long now = System.currentTimeMillis();
            if (now - lastTriggerTime < 5000 && Math.abs(amount - lastTriggerAmount) < 0.01f) {
                Log.e(TAG, "Duplicate trigger blocked");
                return;
            }
            lastTriggerTime = now;
            lastTriggerAmount = amount;

            // 在通知栏弹出"快速记账"通知
            String source = getAppName(pkg);
            showQuickAddNotification(amount, source);

        } catch (Exception e) {
            Log.e(TAG, "onNotificationPosted error: " + e.getMessage(), e);
        }
    }

    /**
     * 在系统通知栏弹出一个"快速记账"通知
     */
    private void showQuickAddNotification(float amount, String source) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        // 点击通知后打开 MainActivity，传递金额
        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction("com.capsulebudget.QUICK_ADD");
        intent.putExtra("quick_add_amount", amount);
        intent.putExtra("quick_add_source", source);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, flags);

        // 构建通知
        String title = "⚡ 快速记账";
        String body = "检测到" + source + " ¥" + String.format("%.2f", amount) + "，点击记账";

        Notification notification;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification = new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_menu_agenda)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(new Notification.BigTextStyle().bigText(body))
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE)
                    .build();
        } else {
            notification = new Notification.Builder(this)
                    .setSmallIcon(android.R.drawable.ic_menu_agenda)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(new Notification.BigTextStyle().bigText(body))
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE)
                    .build();
        }

        nm.notify(NOTIFICATION_ID, notification);
        Log.e(TAG, ">>> Quick add notification posted: amount=" + amount + " source=" + source);
    }

    /**
     * 创建通知渠道（Android 8.0+ 必须）
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "快速记账",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("检测到支付后弹出快速记账通知");
            channel.enableVibration(true);
            channel.setShowBadge(true);

            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private boolean isExcluded(String pkg) {
        if (pkg == null) return true;
        for (String excluded : EXCLUDED_PACKAGES) {
            if (pkg.startsWith(excluded) || pkg.equals(excluded)) return true;
        }
        return false;
    }

    private boolean containsPaymentKeyword(String text) {
        for (String kw : PAYMENT_KEYWORDS) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    private String extractNotificationText(Notification n) {
        StringBuilder sb = new StringBuilder();
        Bundle extras = n.extras;
        if (extras != null) {
            CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
            CharSequence bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
            CharSequence subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
            CharSequence infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT);

            if (title != null) sb.append(title).append(" ");
            if (text != null) sb.append(text).append(" ");
            if (bigText != null) sb.append(bigText).append(" ");
            if (subText != null) sb.append(subText).append(" ");
            if (infoText != null) sb.append(infoText);
        }
        return sb.toString().trim();
    }

    private float extractAmount(String text) {
        Matcher m = AMOUNT_PATTERN.matcher(text);
        float maxAmount = 0;
        while (m.find()) {
            String numStr = null;
            if (m.group(1) != null) numStr = m.group(1);
            else if (m.group(2) != null) numStr = m.group(2);
            else if (m.group(3) != null) numStr = m.group(3);

            if (numStr != null) {
                try {
                    float val = Float.parseFloat(numStr);
                    if (val > maxAmount && val < 100000) {
                        maxAmount = val;
                    }
                } catch (NumberFormatException e) { /* ignore */ }
            }
        }
        return maxAmount;
    }

    private String getAppName(String pkg) {
        if (pkg == null) return "支付通知";
        if (pkg.contains("tencent.mm")) return "微信支付";
        if (pkg.contains("AlipayGphone") || pkg.contains("alipay")) return "支付宝";
        if (pkg.contains("unionpay")) return "云闪付";
        if (pkg.contains("bank") || pkg.contains("icbc") || pkg.contains("ccb") ||
            pkg.contains("boc") || pkg.contains("abc") || pkg.contains("psbc") ||
            pkg.contains("cmb") || pkg.contains("citic") || pkg.contains("cebbank") ||
            pkg.contains("spdb") || pkg.contains("cmbc") || pkg.contains("pab") ||
            pkg.contains("cib") || pkg.contains("hxb") || pkg.contains("bob") ||
            pkg.contains("bocom")) return "银行通知";
        if (pkg.contains("mms") || pkg.contains("messaging") || pkg.contains("message")) return "银行短信";
        return "支付通知";
    }
}
