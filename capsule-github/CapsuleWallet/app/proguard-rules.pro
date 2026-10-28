# 保留JS Bridge类
-keepclassmembers class com.capsulewallet.app.MainActivity$CapsuleBridge {
    @android.webkit.JavascriptInterface <methods>;
}
