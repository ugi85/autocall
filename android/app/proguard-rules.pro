# Proguard/R8 rules for Auto Call Center & Recording Syncer

# Flutter and Plugin rules (Required for release builds with R8 shrinking)
-keep class io.flutter.app.** { *; }
-keep class io.flutter.plugin.** { *; }
-keep class io.flutter.util.** { *; }
-keep class io.flutter.view.** { *; }
-keep class io.flutter.** { *; }
-keep class io.flutter.plugins.** { *; }
-dontwarn com.google.android.play.core.**
-dontwarn io.flutter.embedding.engine.deferredcomponents.**

# SharedPreferences plugin rules (Prevents NoSuchMethodError in release mode)
-keep class io.flutter.plugins.sharedpreferences.** { *; }
-keep class dev.flutter.plugins.shared_preferences.** { *; }
-keep class androidx.preference.** { *; }
-keep class androidx.datastore.** { *; }

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
