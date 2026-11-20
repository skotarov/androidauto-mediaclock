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

- Every display mode uses a compact analog clock, shifted up into the area above Android Auto media buttons.
- The clock-only modes show a bold **HH:mm in the exact centre of the hands**. Weather-only hides the central digits; Clock + Weather includes them.
- Clock-only dial: centre x=256, y=187, radius=146 on the 512px square. Weather dial: centre x=212, y=187, radius=125 to reserve right-side room for the forecast.
- No second hand or second-by-second drawing; still updates once per minute.
- Time and date appear as Android Auto metadata, never as text baked into the artwork
- Weather codes display as vector icons (sun, moon, cloud, fog, rain, snow and storm)
- Weather icon **and temperature** are drawn in a dedicated upper-right area
  **only in Weather and Clock + Weather modes**, without covering the dial.
- **Clean media labels** is enabled by default: playback metadata uses a
  non-breaking space rather than song title/artist. Users can turn this off
  in the phone settings if the Android Auto host renders placeholders.
- Temperature and condition appear in media metadata and next to the navigation panel
- Clock + weather combines time, temperature, and an illustrated weather condition
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

**Limit:** The app cannot suppress Android Auto's built-in media controls or
guarantee fully hiding the text area. The clean mode sends invisible media
metadata, but Android Auto may show fallback labels. The media app template
itself is controlled by Android Auto, not by the phone app. Android Auto does not reliably expose its compact/full-screen card geometry to this MediaBrowser service, so v0.6 intentionally uses one safe layout for both sizes.

## Install the APK

The latest beta APK is published as
[AutoClock-2-v0.6-beta.apk](https://github.com/skotarov/androidauto-mediaclock/releases/tag/v0.6.0-beta)
from GitHub Releases after tests pass.

1. Open the public **Releases** page and choose **v0.6.0-beta**.
2. Under **Assets**, tap **AutoClock-2-v0.6-beta.apk** (not the source ZIP).
3. Install on the Android phone connected to the car, allowing installation
   from the browser/files app if Android requests permission.
4. Open Auto Clock 2 on the phone and select the mode and weather city.
5. In Android Auto, enable developer-mode unknown sources for the local test
   if Auto Clock 2 is absent from the media app launcher.
6. Select **Auto Clock 2** from the media menu, tap its mode and press Play
   if needed; return to navigation/split-screen mode.

### Testing checklist

- [ ] Time appears and updates when minutes change
- [ ] Date is correct after switching timezone / after midnight
- [ ] The high-contrast analog dial is visible in every mode; hour/minute hands update each minute
- [ ] Weather glyphs match current conditions; clear night shows the moon
- [ ] No song/artist labels appear when 'Само фон' is selected; note whether
  the Android Auto host substitutes an app title or displays blank rows
- [ ] The Android Auto host still draws its playback controls (by design)
- [ ] In analog mode the time is centred at the hands and weather is absent
- [ ] Dial is entirely above the media transport controls and leaves breathing room at the edges
- [ ] In Weather and Clock+Weather modes, the forecast sits beside (never over) the dial
- [ ] The optional 'show media labels' fallback works if the host needs titles
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

If replacing an earlier GitHub Actions debug APK, Android may reject the update because each runner can generate a different debug signing key. If needed, uninstall only the existing **Auto Clock 2** before installing the new beta. The original **Auto Clock** has a different app ID and remains untouched.

The stable `main` branch remains unchanged; the next version lives in
`revamp/low-power-v2` until verified in the car.
