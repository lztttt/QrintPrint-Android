# ProGuard rules

# Keep data classes used for serialization
-keep class com.qring.print.model.** { *; }

# ZXing
-keep class com.google.zxing.** { *; }

# Timber
-dontwarn timber.log.Timber
