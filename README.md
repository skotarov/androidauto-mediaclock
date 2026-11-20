# Auto Clock 2 — low-power prototype

An experimental, phone-hosted clock shown through Android Auto's **media** surface.

> Android Auto officially reserves its media interface for apps that play audio.
> This clock does not play audio: its "playing" state represents the active
> minute-by-minute display. Compatibility cannot be guaranteed across Android
> Auto versions and this is not ready for Play Store distribution.

## Goals

- Visible clock in the media panel beside navigation when Android Auto supports it.
- Digital and analog time views; weather and combined modes are placeholders.
- No sound, continuous animation, GPS polling, network polling, wake lock,
  or continuously running foreground worker.
- Update only when the clock has been explicitly started, once per minute.
- Remain paused when Android Auto merely binds to the service.
- Prefer the phone's own time and timezone.

## How v2 avoids wasted resources

1. Artwork is **512 x 512 RGB_565**, about 0.5 MiB per rendered bitmap
   (the old 1024 x 1024 ARGB_8888 needed 4 MiB).
2. One artwork file per mode/minute, reused for repeated content requests.
3. Cache limited to **6 PNG files** (on best-effort cleanup after generation).
4. Bitmap memory explicitly released after PNG creation.
5. Media timer removed on Pause, Stop, or service destruction.
6. Service returns `START_NOT_STICKY` instead of automatically resuming after
   Android recreates it.
7. A minimal phone settings screen persists the preferred mode without a
   background service.

## Current limitations

- Weather is not fetched yet: temperature is displayed as a placeholder.
- No guarantee that an audio-less media session will remain visible in the
  Android Auto dashboard. Actual-device testing is required.
- No second-hand smoothing, so the analog hand moves once a minute.
- The activity's mode selection is applied at the next media start.

## Local testing

1. Build using Android Studio or run `gradle assembleDebug` with Android SDK.
2. Install on an Android 9+ phone with Android Auto support.
3. If testing a locally built app, use Android Auto developer settings /
   appropriate test configuration for apps from unknown sources.
4. Choose **Auto Clock** under the Android Auto media menu.
5. Start the clock; switch to the navigation split-screen layout.
6. Observe the time for 20+ minutes and then pause. Verify no further updates.
7. Compare battery usage and app memory (Android Settings / adb dumpsys) to v1.

Please do not merge until an APK builds and the clock has been tested on the
actual phone/head unit.
