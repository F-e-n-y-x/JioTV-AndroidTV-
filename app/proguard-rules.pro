# --- JioTV Go TV ProGuard Rules ---

# Keep data classes used by kotlinx.serialization (Navigation keys)
-keepclassmembers class com.fenyx.jiotv.Main { *; }
-keepclassmembers class com.fenyx.jiotv.Settings { *; }
-keepclassmembers class com.fenyx.jiotv.Login { *; }
-keepclassmembers class com.fenyx.jiotv.Player { *; }
-keep class com.fenyx.jiotv.Main { *; }
-keep class com.fenyx.jiotv.Settings { *; }
-keep class com.fenyx.jiotv.Login { *; }
-keep class com.fenyx.jiotv.Player { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.fenyx.jiotv.**$$serializer { *; }
-keepclassmembers class com.fenyx.jiotv.** {
    *** Companion;
}
-keepclasseswithmembers class com.fenyx.jiotv.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Data classes used in JSON parsing (keep field names)
-keepclassmembers class com.fenyx.jiotv.data.Channel { *; }
-keepclassmembers class com.fenyx.jiotv.data.JioApiClient$AuthData { *; }
-keepclassmembers class com.fenyx.jiotv.data.JioApiClient$StreamData { *; }
-keepclassmembers class com.fenyx.jiotv.data.EpgProgram { *; }

# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Coil
-keep class coil.** { *; }
-dontwarn coil.**

# AndroidX DataStore
-keep class androidx.datastore.** { *; }
