# 保留JS Bridge类
-keepclassmembers class com.capsulebudget.app.MainActivity$CapsuleBridge {
    @android.webkit.JavascriptInterface <methods>;
}
