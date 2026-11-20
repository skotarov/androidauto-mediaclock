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

## Features (v0.8)

- All modes use an analog face with clean **HH:mm text and a subtle glyph shadow**.
  There is no rectangle, badge, padding block or border behind the time.
- Weather and Clock + Weather modes show a weather **vector symbol and temperature
  on the second line directly below HH:mm, inside the analog clock face**.
  Pure analog clock mode does not fetch or display weather.
- Phone-side clock-size setting:
  - **Compact** (default) uses dial radius 96 px and centre (256, 196).
  - **Standard** uses radius 122 px and the same centre.
  - Android Auto does not reliably report its split/full media-card dimensions,
    so Compact is the conservative layout for the small card.
- **Moving progress bar** phone switch (default on). Off: no duration is
  supplied and the playback-state position is unknown with zero speed.
  Android Auto may keep its own non-moving progress decoration.
- Phone changes to mode, city, size, text visibility or progress behavior are
  **applied immediately to the active media session** through a debounced
  SharedPreferences listener (without waiting for the next minute).
  Artwork URIs include layout-specific cache keys; browser contents are refreshed.
- The media artwork and minute alignment continue to update only once per minute.
- Weather data via Open-Meteo, hourly only when a weather mode is active.
- Settings preview, city selection, manual forecast refresh.

Weather uses [Open-Meteo](https://open-meteo.com/) data with attribution,
no API key for permitted non-commercial use. Network is only needed for
weather; the clock works without a connection.

## Minute progress bar (v0.8)

The media "track" is a virtual 60-second minute, not real sound playback.
The service now sends `MediaMetadataCompat.METADATA_KEY_DURATION = 60_000`
and sets `PlaybackStateCompat.position = System.currentTimeMillis() % 60_000`
with playback speed 1.0 and a monotonic timestamp.

For example, if Auto Clock is started at 15:36:24 it publishes 24 seconds /
60 seconds (40%) immediately; it does **not** start at 0. On the next minute,
it sends a fresh position near the beginning of the new minute. The normal
artwork update and playback-state update are aligned to the same minute timer.
No wakeups are added between minute boundaries.

If "Движение на прогрес бара" is switched off on the phone, the
metadata no longer contains a duration and the playback state reports
unknown position with speed zero.

**Android Auto limitation:** The host owns the progress bar. The state and
duration give it the information needed to extrapolate progress, but it may
choose not to show or animate the bar for an audio-less media session.

## Phone settings and Android Auto updates

Open Auto Clock 2 on the phone, while Android Auto is running. Changing the
display mode, compact/standard dial, moving progress, clean media-label setting,
or weather city updates the bound Auto Clock media service at once. An Android
Auto host may still cache album art or delay repainting its view; in such cases
return to the app in Android Auto. This project cannot control the native
transport controls or detect the media panel's size with certainty.

## Battery and memory strategy

- Minute-aligned artwork and playback-state updates *only while the clock is active*, not 60 FPS
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
[AutoClock-2-v0.8-beta.apk](https://github.com/skotarov/androidauto-mediaclock/releases/tag/v0.8.0-beta)
from GitHub Releases after tests pass.

1. Open the public **Releases** page and choose **v0.8.0-beta**.
2. Under **Assets**, tap **AutoClock-2-v0.8-beta.apk** (not the source ZIP).
3. Install on the Android phone connected to the car, allowing installation
   from the browser/files app if Android requests permission.
4. Open Auto Clock 2 on the phone and select the mode and weather city.
5. In Android Auto, enable developer-mode unknown sources for the local test
   if Auto Clock 2 is absent from the media app launcher.
6. Select **Auto Clock 2** from the media menu, tap its mode and press Play
   if needed; return to navigation/split-screen mode.

### Testing checklist

- [ ] Switching Compact and Standard on the phone updates Android Auto without waiting for a minute
- [ ] HH:mm has no box or border, only a subtle shadow
- [ ] In weather modes the icon + temperature share a second line **inside** the dial
- [ ] Moving progress off freezes or removes the progress (the host may keep a static line)
- [ ] Changing mode or city on the phone updates the car media screen immediately

- [ ] Media progress initially equals the current seconds / 60, rather than 0
- [ ] Progress moves without second-by-second updates and resets at minute rollover
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
