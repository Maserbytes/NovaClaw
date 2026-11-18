# Keep JS bridge methods reachable from WebView
-keepclassmembers class ai.novaclaw.ui.NovaWebBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes *JavascriptInterface*

# LiteRT-LM native bindings
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Gson models
-keep class ai.novaclaw.engine.** { *; }
-keep class ai.novaclaw.a11y.** { *; }
