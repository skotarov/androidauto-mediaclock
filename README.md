# Auto Clock 2 — Android Auto clock (beta)

Auto Clock displays time as media title/album artwork in Android Auto.
It is a personal experiment for users who want a small clock in the
media card next to navigation, not a replacement for a real audio player.

> **Compatibility warning:** Android Auto's media templates are intended
> for audio apps. Auto Clock has no audio playback: it uses the media
> metadata interface only. Whether the clock is shown in the split-screen
> dashboard depends on the Android Auto/head unit version. There is no
> guaranteed supported API for a general-purpose custom clock in the media
> card. Not intended for Play Store publication as a music app.

## Features

- Digital clock: 24-hour HH:mm, local date
- Analog clock: hour/minute hands, no second-hand animation
- Weather: current temperature and a brief condition label
- Clock + weather: time, temperature, condition
- Select any mode from Android Auto's media list or the phone settings
- Phone preview of all four modes
- Choose forecast city on the phone (Sofia, Stara Zagora, Plovdiv, Varna,
  Burgas, Ruse, Pleven, Veliko Tarnovo), without location permission
- Manual weather refresh from the phone

Weather uses [Open-Meteo](https://open-meteo.com/) data with attribution,
no API key for permitted non-commercial use. Network is only needed for
weather; the clock works without a connection.

## Battery and memory strategy

- Minute-aligned updates *only while the clock is active*, not 60 FPS
- No GPS, location permission, wakelock, audio stream, alarms or work manager
- No app-start or Android Auto-bind automatic playback
- `START_NOT_STICKY` and timer cancellation on pause/stop/destroy
- 512×512 RGB_565 clock image (~0.5 MiB of pixels vs ~4 MiB in v1)
- Cache at most 6 PNG files and reuse repeated requests by Android Auto
- Weather downloaded in a short-lived thread at most once per hour while
  the selected display needs it (including failed attempt throttling)
- HTTP connect/read timeout 5 seconds, weather cache valid 3 hours
- No network access in the media session main thread
- The app screen does not run a background timer

Power consumption still **must be measured on the actual phone**.

## Install the APK

The release candidate APK will be published as
[AutoClock-2-beta.apk](https://github.com/skotarov/androidauto-mediaclock/releases)
once the release build succeeds.

1. Open the public **Releases** page and choose **v0.3.0-beta**.
2. Under **Assets**, tap **AutoClock-2-beta.apk** (not the source ZIP).
3. Install on the Android phone connected to the car, allowing installation
   from the browser/files app if Android requests permission.
4. Open Auto Clock on the phone and select the mode and weather city.
5. In Android Auto, enable developer-mode unknown sources for the local test
   if Auto Clock is absent from the media app launcher.
6. Select **Auto Clock** from the media menu, tap its mode and press Play
   if needed; return to navigation/split-screen mode.

### Testing checklist

- [ ] Time appears and updates when minutes change
- [ ] Date is correct after switching timezone / after midnight
- [ ] Analog hands move each minute
- [ ] Weather appears after first request, survives weak/offline signal
- [ ] Weather mode city selection is correct; manual refresh works
- [ ] Time appears in split-screen media card alongside maps
- [ ] Pause/Stop ends updates; app does not restart on its own
- [ ] No audio interrupts music/navigation prompts unexpectedly
- [ ] 30–60 min device test with CPU, RAM and battery-use measurements

## Development

Kotlin, Android Gradle Plugin 8.6.1, AndroidX Media 1.7.0.
`gradle testDebugUnitTest assembleDebug` builds the local debug APK.
The **Android Build** GitHub Actions workflow runs tests/build automatically.

The stable `main` branch remains unchanged; the next version lives in
`revamp/low-power-v2` until verified in the car.
