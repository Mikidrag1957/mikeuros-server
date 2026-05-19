# Keep JavaScript interface methods
-keepclassmembers class com.bingopro.app.MainActivity$AndroidBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep WebView
-keep class android.webkit.** { *; }
-dontwarn android.webkit.**
