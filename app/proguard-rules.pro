# NZEUS NVG ProGuard rules
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.android.gms.** { *; }
-keep class com.nzeus.nvg.ml.** { *; }
-dontwarn org.tensorflow.lite.gpu.**
