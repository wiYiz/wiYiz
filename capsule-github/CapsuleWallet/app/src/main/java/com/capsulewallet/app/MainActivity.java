package com.capsulewallet.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private WebView webView;
    private boolean webViewReady = false;

    // 缓存通知点击传来的快速记账数据（WebView 未就绪时暂存）
    private float pendingAmount = 0;
    private String pendingSource = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 全屏 + 状态栏透明
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        }

        webView = new WebView(this);
        setContentView(webView);

        // WebView 配置
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);
        webView.getSettings().setSupportZoom(false);
        webView.getSettings().setUseWideViewPort(true);
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.getSettings().setDatabaseEnabled(true);

        // JS Bridge
        webView.addJavascriptInterface(new CapsuleBridge(), "CapsuleBridge");

        // WebViewClient
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("tel:") || url.startsWith("mailto:")) {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(i);
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                webViewReady = true;
                // 如果有缓存的支付通知，立即触发
                if (pendingAmount > 0) {
                    float amt = pendingAmount;
                    String src = pendingSource;
                    pendingAmount = 0;
                    pendingSource = null;
                    triggerQuickAdd(amt, src);
                }
            }
        });

        // WebChromeClient（支持alert/confirm等）
        webView.setWebChromeClient(new WebChromeClient());

        // 加载前端代码：优先外部覆盖文件（热更新，只 push 代码不重装），回退内置 assets
        loadIndexHtml();

        // Android 13+ 需要请求通知权限（用于弹快速记账通知）
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 100);
            }
        }
    }

    /**
     * 加载前端代码。
     * 优先读取外部覆盖文件 getExternalFilesDir(null)/index.html，实现"只更新代码、不重装"的开发流程；
     * 该目录为应用私有外部存储，无需额外权限。使用 android_asset 作为 base URL 注入，
     * 可保证 WebView 的 localStorage 作用域不变，用户记账数据连续无中断。
     * 外部文件不存在或读取失败时回退内置 assets。
     */
    private void loadIndexHtml() {
        File external = new File(getExternalFilesDir(null), "index.html");
        if (external.exists()) {
            try {
                FileInputStream fis = new FileInputStream(external);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int len;
                while ((len = fis.read(buf)) != -1) bos.write(buf, 0, len);
                fis.close();
                String html = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                webView.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null);
                return;
            } catch (Exception e) {
                Log.e(TAG, "加载外部 index.html 失败，回退内置 assets", e);
            }
        }
        webView.loadUrl("file:///android_asset/index.html");
    }

    // 返回键由WebView处理
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        // 每次 resume 时刷新小组件数据 + 通知权限状态
        webView.evaluateJavascript("if(typeof renderSummary==='function')renderSummary();if(typeof updateNotifStatusIcon==='function')updateNotifStatusIcon();", null);
        // 检查是否有通知点击传来的快速记账数据
        handleQuickAddIntent(getIntent());
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleQuickAddIntent(intent);
    }

    @Override
    protected void onDestroy() {
        webView.destroy();
        super.onDestroy();
    }

    /**
     * 处理通知点击传来的快速记账 Intent
     */
    private void handleQuickAddIntent(Intent intent) {
        if (intent == null) return;
        if (!"com.capsulewallet.QUICK_ADD".equals(intent.getAction())) return;

        float amount = intent.getFloatExtra("quick_add_amount", 0);
        String source = intent.getStringExtra("quick_add_source");

        if (amount <= 0) return;

        Log.e(TAG, ">>> Quick add from notification click: amount=" + amount + " source=" + source);

        // 清除 Intent 的 action，避免重复触发
        intent.setAction(null);

        // 触发快速记账
        if (webViewReady && webView != null) {
            triggerQuickAdd(amount, source);
        } else {
            // WebView 未就绪，缓存起来
            pendingAmount = amount;
            pendingSource = source;
        }
    }

    /**
     * 触发快速记账面板
     */
    private void triggerQuickAdd(float amount, String source) {
        if (webView == null) return;
        String js = String.format(
                "if(typeof showPaymentNotification==='function')showPaymentNotification(%s);",
                String.valueOf(amount)
        );
        runOnUiThread(() -> {
            webView.evaluateJavascript(js, null);
            Log.e(TAG, ">>> Quick add JS executed: " + amount);
        });
    }

    /**
     * 检查通知监听权限是否已授权
     */
    private boolean isNotificationListenerEnabled() {
        String pkg = getPackageName();
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(pkg);
    }

    /**
     * 打开通知监听权限设置页
     */
    private void openNotificationListenerSettings() {
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS);
            intent.putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    new ComponentName(this, CapsuleNotificationListener.class).flattenToString());
        } else {
            intent = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Exception e) {
            // fallback：打开通用通知监听设置
            intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }
    }

    /**
     * JS Bridge：供前端调用的原生能力
     */
    private class CapsuleBridge {

        @JavascriptInterface
        public void onWebViewReady() {
            webViewReady = true;
            Log.e(TAG, "WebView ready signaled from JS");
            if (pendingAmount > 0) {
                float amt = pendingAmount;
                String src = pendingSource;
                pendingAmount = 0;
                pendingSource = null;
                runOnUiThread(() -> triggerQuickAdd(amt, src));
            }
        }

        @JavascriptInterface
        public boolean isNotificationListenerEnabled() {
            return MainActivity.this.isNotificationListenerEnabled();
        }

        @JavascriptInterface
        public void openNotificationListenerSettings() {
            runOnUiThread(() -> MainActivity.this.openNotificationListenerSettings());
        }

        @JavascriptInterface
        public void shareImage(final String base64DataUrl) {
            runOnUiThread(() -> {
                try {
                    String base64 = base64DataUrl;
                    if (base64.contains(",")) {
                        base64 = base64.substring(base64.indexOf(",") + 1);
                    }
                    byte[] bytes = Base64.decode(base64, Base64.DEFAULT);

                    File cacheDir = new File(getCacheDir(), "share");
                    cacheDir.mkdirs();
                    File imageFile = new File(cacheDir, "胶囊月报.png");
                    try (FileOutputStream fos = new FileOutputStream(imageFile)) {
                        fos.write(bytes);
                    }

                    Uri uri = FileProvider.getUriForFile(
                        MainActivity.this,
                        getPackageName() + ".fileprovider",
                        imageFile
                    );

                    Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType("image/png");
                    shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                    Intent chooser = Intent.createChooser(shareIntent, "分享月报");
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(chooser);

                } catch (Exception e) {
                    Log.e("CapsuleBridge", "shareImage failed", e);
                    Toast.makeText(MainActivity.this, "分享失败", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void saveToGallery(final String base64DataUrl) {
            runOnUiThread(() -> {
                try {
                    String base64 = base64DataUrl;
                    if (base64.contains(",")) {
                        base64 = base64.substring(base64.indexOf(",") + 1);
                    }
                    byte[] bytes = Base64.decode(base64, Base64.DEFAULT);

                    String fileName = "胶囊月报_" + System.currentTimeMillis() + ".png";

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        android.content.ContentResolver resolver = getContentResolver();
                        android.content.ContentValues values = new android.content.ContentValues();
                        values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName);
                        values.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png");
                        values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CapsuleWallet");
                        values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 1);

                        Uri uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                        if (uri != null) {
                            try (java.io.OutputStream os = resolver.openOutputStream(uri)) {
                                if (os != null) os.write(bytes);
                            }
                            values.clear();
                            values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0);
                            resolver.update(uri, values, null, null);
                        }
                    } else {
                        File picturesDir = new File(
                                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES),
                                "CapsuleWallet");
                        picturesDir.mkdirs();
                        File imageFile = new File(picturesDir, fileName);
                        try (FileOutputStream fos = new FileOutputStream(imageFile)) {
                            fos.write(bytes);
                        }
                        Intent scanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                        scanIntent.setData(Uri.fromFile(imageFile));
                        sendBroadcast(scanIntent);
                    }

                    Toast.makeText(MainActivity.this, "已保存到相册", Toast.LENGTH_SHORT).show();

                } catch (Exception e) {
                    Log.e("CapsuleBridge", "saveToGallery failed", e);
                    Toast.makeText(MainActivity.this, "保存失败", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void pushWidgetData(final float totalBudget, final float totalSpent, final float todaySpent) {
            // 胶囊钱包无桌面小组件，保留此方法仅为兼容前端调用（no-op）
        }
    }
}
