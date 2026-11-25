# Preserve media/browser IPC compatibility and Android manifest entry points.
-keep class com.kotarov.autoclock.media.AutoClockMediaService { *; }
-keep class com.kotarov.autoclock.artwork.ClockArtworkProvider { *; }
-keep class android.support.v4.media.** implements android.os.Parcelable { public static final android.os.Parcelable$Creator CREATOR; }

# PDFBox optional JPEG2000 image decoder; this app extracts text only.
-dontwarn com.gemalto.jp2.JP2Decoder
