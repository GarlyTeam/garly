-keepclassmembers class pro.garlyapp.app.GarlyNativeBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class pro.garlyapp.app.GarlyNativeBridge { *; }
-keep class pro.garlyapp.app.GarlyWebActivity { *; }
