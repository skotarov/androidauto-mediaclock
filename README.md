# Android Auto Media Clock

Android/Kotlin starter project for an Android Auto media-style clock app.

The idea is simple: Android Auto sees the app as a media/radio app, while the current time is shown as media metadata and as generated artwork.

## MVP behavior

- Appears in Android Auto as a media app.
- Uses `MediaBrowserServiceCompat` + `MediaSessionCompat`.
- Generates clock artwork through a `ContentProvider`.
- Updates the artwork URI once per minute to avoid stale cached images.
- Planned display modes from the phone settings screen:
  - Digital
  - Analog
  - Hybrid

## Package

`com.kotarov.autoclock`

## Testing idea

1. Open the project in Android Studio.
2. Sync Gradle.
3. Install on the phone.
4. Enable Android Auto developer settings and unknown sources for local testing.
5. Open Android Auto and choose **Auto Clock** from media apps.
