# Keep Media3 / ExoPlayer internals used via reflection.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Keep media session compat.
-keep class androidx.media.app.NotificationCompat$MediaStyle { *; }
