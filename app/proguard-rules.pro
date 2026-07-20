# Add project specific ProGuard rules here.

# Preserve Line Numbers for stacktraces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Media3 / ExoPlayer rules
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Room Database rules
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-dontwarn androidx.room.**

# Moshi & Retrofit rules
-keep class com.squareup.moshi.** { *; }
-keep class retrofit2.** { *; }
-keepattributes *Annotation*
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}

# App Data Models
-keep class com.example.data.** { *; }
