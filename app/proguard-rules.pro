# ProGuard / R8 Rules for AnyFilm

# Preserve line numbers and source files for clean crash logs
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault

# 1. Moshi & Data Models
-keep class com.example.data.model.** { *; }
-keep class com.squareup.moshi.** { *; }
-keepclassmembers class * {
    @com.squareup.moshi.Json *;
    @com.squareup.moshi.JsonClass *;
}
-keep class * extends com.squareup.moshi.JsonAdapter { *; }
-keep class com.example.data.model.*JsonAdapter { *; }

# 2. Room Database & Local Cache
-keep class com.example.data.local.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }

# 3. Google Cast Framework
-keep class com.example.cast.** { *; }
-keep class * extends com.google.android.gms.cast.framework.OptionsProvider { *; }
-keep class com.google.android.gms.cast.framework.** { *; }

# 4. Local Proxy Server (LocalMovieBoxServer)
-keep class com.example.data.network.LocalMovieBoxServer** { *; }
-keep class fi.iki.elonen.** { *; }

# 5. Retrofit & OkHttp
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# 6. Coil & Coil SVG
-keep class coil.** { *; }
-keep class com.caverock.androidsvg.** { *; }

# 7. Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# 8. Kotlin & Coroutines
-dontwarn kotlin.reflect.**
