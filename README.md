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

## Three primary visual styles (v0.10)

Select **one main visual style**, then its corresponding additional information.
Settings keep the last detail option for each main style independently.

| Main style | Details | Emphasis |
| --- | --- | --- |
| Analog clock | Digital time | Analog hands + prominent HH:mm |
| Analog clock | Digital time and weather | Analog hands, HH:mm and smaller forecast |
| Analog clock | Weather only | Analog hands with prominent weather, no digital HH:mm |
| Digital clock | Time only | Large HH:mm with smaller date |
| Digital clock | Time + weather | Large HH:mm with smaller weather underneath |
| Weather | Weather only | Large weather symbol, temperature and condition |
| Weather | Weather + digital time | Large weather symbol and temperature with smaller HH:mm underneath |

The main weather style reverses the hierarchy of Digital + Weather:
the forecast becomes the focal point, not the clock.

The Android Auto media browser shows all seven choices. Changes on the
phone update the live media session via a debounced SharedPreferences
listener; the car still controls how quickly artwork is redrawn.
Compact/standard layout, clean media text and animated/static playback
progress retain their previous settings.

Bitmap rendering: 512×512 RGB_565 for content-provider artwork and a
small 320×320 RGB_565 direct media bitmap for display-change updates.
The analog dial is positioned above the native Android Auto controls.
No continuous frame-by-frame rendering is used.

Weather uses [Open-Meteo](https://open-meteo.com/) for permitted
non-commercial use. Its city is selected on the phone: there is no GPS
permission or continuous location service. Forecasts are fetched only
when a selected mode requires weather, no more than hourly while active.

## Minute progress bar

The media "track" is a virtual 60-second minute. At HH:mm:24 the app
publishes a position of 24 seconds in a 60-second duration and speed 1×.
The next minute resets it. There are no per-second wakeups. Turn off
**Движение на прогрес бара** to omit the duration and report no advancing
position. Android Auto may still draw its own static bar.

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
[AutoClock-2-v0.10-beta.apk](https://github.com/skotarov/androidauto-mediaclock/releases/tag/v0.10.0-beta)
from GitHub Releases after tests pass.

1. Open the public **Releases** page and choose **v0.10.0-beta**.
2. Under **Assets**, tap **AutoClock-2-v0.10-beta.apk** (not the source ZIP).
3. Install on the Android phone connected to the car, allowing installation
   from the browser/files app if Android requests permission.
4. Open Auto Clock 2 on the phone and select the mode and weather city.
5. In Android Auto, enable developer-mode unknown sources for the local test
   if Auto Clock 2 is absent from the media app launcher.
6. Select **Auto Clock 2** from the media menu, tap its mode and press Play
   if needed; return to navigation/split-screen mode.

### Testing checklist

- [ ] Select each of the three main visuals, then every detail option (3 + 2 + 2)
- [ ] Weather-first text and icon are clearly larger than its optional digital time
- [ ] Digital-first HH:mm is larger than its accompanying forecast
- [ ] Analog weather-only has hands and weather, but no printed digital time
- [ ] Switching the primary view restores its independently saved detail choice
- [ ] Existing v0.9 settings migrate without resetting chosen layout

- [ ] Digital-only mode shows no analog dial or hands on phone and Android Auto
- [ ] Weather-on / weather-off leaves HH:mm font size unchanged
- [ ] Weather glyph and temperature are aligned on the same second line
- [ ] Switch Compact ↔ Standard while Android Auto is open and check artwork redraw

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
