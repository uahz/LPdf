# PDFium JNI
-keep class io.legere.pdfiumandroid.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# PDFBox
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.**
-dontwarn org.apache.**
-dontwarn javax.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# Compose graphics layer / AGSL
-keep class androidx.compose.ui.graphics.layer.** { *; }
