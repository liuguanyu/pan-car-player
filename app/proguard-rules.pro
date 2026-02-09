# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep AndroidX Core classes
-keep class androidx.core.app.CoreComponentFactory { *; }
-keep class androidx.** { *; }
-dontwarn androidx.**

# Keep MultiDex classes
-keep class androidx.multidex.** { *; }
-dontwarn androidx.multidex.**

# Keep all Application classes
-keep public class * extends android.app.Application

# Keep all Activity classes
-keep public class * extends android.app.Activity

# Keep all Service classes
-keep public class * extends android.app.Service

# Keep all BroadcastReceiver classes
-keep public class * extends android.content.BroadcastReceiver

# Keep all ContentProvider classes
-keep public class * extends android.content.ContentProvider

# Keep Retrofit classes
-keepattributes Signature
-keepattributes Exceptions
-keepattributes *Annotation*
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# Keep Gson classes
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Keep model classes
-keep class com.baidu.carplayer.model.** { *; }

# Keep Room classes
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Keep Media3 classes
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Keep Glide classes
-keep class com.bumptech.glide.** { *; }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
  **[] $VALUES;
  public *;
}

# Keep ZXing classes
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.barcodescanner.** { *; }
-dontwarn com.google.zxing.**