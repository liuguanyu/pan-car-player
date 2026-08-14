# R8 Full Mode ProGuard Rules

# Keep Gson serialization models
-keep class com.panmusic.carplayer.model.** { *; }
-keepclassmembers class com.panmusic.carplayer.model.** { *; }

# Keep Retrofit interfaces
-keep,allowobfuscation interface com.panmusic.carplayer.data.remote.BaiduPanService

# Keep Room entities
-keep class com.panmusic.carplayer.data.local.** { *; }

# Keep sealed classes for Gson
-keep class com.panmusic.carplayer.model.PlaybackState { *; }
-keep class com.panmusic.carplayer.model.FocusChange { *; }
-keep class com.panmusic.carplayer.model.PlaybackUiEvent { *; }

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Keep MediaSession/Notification
-keep class androidx.media.** { *; }
-keep class android.support.v4.media.** { *; }

# Keep Glide
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keep class com.bumptech.glide.GeneratedAppGlideModuleImpl

# Keep ExoPlayer
-dontwarn com.google.android.exoplayer2.**

# Keep ZXing
-keep class com.google.zxing.** { *; }

# Keep ViewBinding
-keep class * implements androidx.viewbinding.ViewBinding { *; }
