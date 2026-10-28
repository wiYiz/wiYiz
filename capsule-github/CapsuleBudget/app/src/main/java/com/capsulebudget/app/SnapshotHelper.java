package com.capsulebudget.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 胶囊快照生成器
 * 将小组件当前状态（胶囊+文字+日期水印）合成一张图片保存到相册
 * 使用 MediaStore API，确保 Android 10+ 相册可见
 */
public class SnapshotHelper {

    private static final String TAG = "SnapshotHelper";

    /**
     * 生成并保存胶囊快照到相册
     */
    public static void saveSnapshot(Context context, int widgetId) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                SharedPreferences prefs = context.getSharedPreferences("capsule_widget_data", Context.MODE_PRIVATE);
                float totalBudget = prefs.getFloat("totalBudget", 0);
                float totalSpent = prefs.getFloat("totalSpent", 0);
                float remain = totalBudget - totalSpent;
                float remainRatio = totalBudget > 0 ? Math.max(remain / totalBudget, 0f) : 0f;
                int themeIdx = CapsuleWidgetProvider.getCurrentThemeIndex(context, widgetId);

                // 生成胶囊 Bitmap
                Bitmap capsuleBmp = CapsuleWidgetProvider.drawCapsuleBitmap(remainRatio, themeIdx, 0);

                // 生成文字 Bitmap（带语录，但不带快照提示）
                String remainText = remain >= 0
                        ? "本月剩余 ¥" + CapsuleWidgetProvider.formatMoney(remain)
                        : "超支 ¥" + CapsuleWidgetProvider.formatMoney(Math.abs(remain));
                String quoteText = CapsuleWidgetProvider.getQuote(context, widgetId);
                Bitmap textBmp = CapsuleWidgetProvider.drawTextBitmap(quoteText != null ? quoteText : remainText, 0, themeIdx, false);

                // 合成完整快照
                int totalW = capsuleBmp.getWidth() + textBmp.getWidth(); // 130 + 260 = 390
                int totalH = Math.max(capsuleBmp.getHeight(), textBmp.getHeight()) + 36; // 300 + 36(日期水印区)
                Bitmap snapshot = Bitmap.createBitmap(totalW, totalH, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(snapshot);

                // 白色背景
                canvas.drawColor(Color.WHITE);

                // 画胶囊和文字
                canvas.drawBitmap(capsuleBmp, 0, 0, null);
                canvas.drawBitmap(textBmp, capsuleBmp.getWidth(), 0, null);

                // 日期水印（底部居中）
                String dateStr = new SimpleDateFormat("M月d日 E", Locale.CHINA).format(new Date());
                Paint datePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                datePaint.setTextAlign(Paint.Align.CENTER);
                datePaint.setTextSize(20f);
                datePaint.setColor(Color.parseColor("#BBBBBB"));
                float dateX = totalW / 2f;
                float dateY = totalH - 10f;
                canvas.drawText(dateStr, dateX, dateY, datePaint);

                // 保存到相册（MediaStore API，Android 10+ 相册可见）
                boolean saved = saveToMediaStore(context, snapshot);

                if (saved) {
                    Toast.makeText(context, "快照已保存到相册 📸", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show();
                }

                // 回收
                capsuleBmp.recycle();
                textBmp.recycle();
                snapshot.recycle();

            } catch (Exception e) {
                Log.e(TAG, "saveSnapshot failed", e);
                Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * 使用 MediaStore API 保存图片到系统相册
     * Android 10+ 无需存储权限，直接写入 MediaStore
     * Android 9 及以下回退到旧方式
     */
    private static boolean saveToMediaStore(Context context, Bitmap bitmap) {
        try {
            String fileName = "胶囊快照_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date()) + ".png";

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                // Android 10+：使用 MediaStore API
                ContentResolver resolver = context.getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CapsuleBudget");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);

                Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    Log.e(TAG, "MediaStore insert failed");
                    return false;
                }

                try (OutputStream os = resolver.openOutputStream(uri)) {
                    if (os != null) {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
                    }
                }

                // 标记写入完成
                values.clear();
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(uri, values, null, null);

                Log.d(TAG, "Snapshot saved via MediaStore: " + uri);
                return true;
            } else {
                // Android 9 及以下：使用旧方式 + 通知扫描
                java.io.File picturesDir = new java.io.File(
                        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES),
                        "CapsuleBudget");
                picturesDir.mkdirs();
                java.io.File imageFile = new java.io.File(picturesDir, fileName);
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(imageFile)) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
                }
                Intent scanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                scanIntent.setData(Uri.fromFile(imageFile));
                context.sendBroadcast(scanIntent);

                Log.d(TAG, "Snapshot saved to file: " + imageFile.getAbsolutePath());
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "saveToMediaStore failed", e);
            return false;
        }
    }
}
