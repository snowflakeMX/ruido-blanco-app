# Enum names are written to settings and night files: keep them stable across builds.
-keepclassmembers enum ruido.core.** { *; }

# TensorFlow Lite calls into these classes from native code.
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
