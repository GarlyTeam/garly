-keepclassmembers class pro.garlyapp.app.GarlyNativeBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class pro.garlyapp.app.GarlyNativeBridge { *; }
-keep class pro.garlyapp.app.GarlyWebActivity { *; }
# Reached only through a listener the billing library holds, and only in
# a release build is R8 there to notice. A stripped purchase callback
# would take somebody's money and never tell the app about it.
-keep class pro.garlyapp.app.GarlyBilling { *; }

# The release build minifies. TensorFlow Lite Task Audio resolves classes from
# JNI, so anything renamed or stripped here fails at runtime instead of at
# build time - which is the worst way to find out.
-keep class org.tensorflow.** { *; }
-keep interface org.tensorflow.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-dontwarn org.tensorflow.**
-dontwarn javax.lang.model.**
-dontwarn com.google.auto.value.**

# Started by class name string, so the shrinker cannot see the reference.
-keep class pro.garlyapp.app.AcousticDiagnosticService { *; }
