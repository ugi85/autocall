# Proguard/R8 rules for Auto Call Center & Recording Syncer

# OkHttp3 rules
-keepattributes Signature
-keepattributes *Annotation*
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# WorkManager rules
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.ListenableWorker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }
-keep class androidx.work.** { *; }

# Kotlin Coroutines
-keep class kotlinx.coroutines.** { *; }
