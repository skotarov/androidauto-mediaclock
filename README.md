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

## Clear visual choices (v0.12)

The phone settings screen now shows **three main radio buttons**:

- **Аналогов часовник** — clean analog dial and hands, no text by default
- **Цифров часовник** — clean large HH:mm, no dial or weather by default
- **Времето** — large weather symbol and temperature, no clock by default

Under the selected radio option, there are exactly **two independent
checkboxes**. Leave both unchecked for the pure main style:

| Primary radio | First optional checkbox | Second optional checkbox |
| --- | --- | --- |
| Аналогов часовник | Добави цифров час | Добави времето |
| Цифров часовник | Добави аналогов часовник | Добави времето |
| Времето | Добави аналогов часовник | Добави цифров час |

Each radio option has four possible checkbox states (none / first /
second / both): **12 combinations in total**. The selected checkboxes
are saved independently for each main style. The Android Auto browser
shows only the three main styles, avoiding a long confusing catalogue.
The phone controls add-ons and the active media session refreshes when
these settings change. The Weather-first layout keeps its weather data
dominant even when digital time is added.

On first launch, old v0.10 radio choices are migrated to these checkboxes
without discarding the previous display preference. The existing city,
compact/standard size, progress and hidden media labels remain unchanged.

Bitmap rendering: 512×512 RGB_565 provider artwork, and 320×320 RGB_565
direct metadata artwork. There is no continuous animation, fake sound,
location tracking, or wake lock. The clock refreshes each minute while
active, and weather is requested hourly only for modes that need it.

Weather uses [Open-Meteo](https://open-meteo.com/) for permitted
non-commercial use. The city is selected on the phone.

## Compact card refinements (v0.12)

- Analog-containing layouts have an adjusted vertical centre on the artwork:
  compact centre moves from 190 to **226 px**, while standard uses **204 px**.
  The compact dial radius remains 96 px, placing its ring between y=130 and
  y=322 inside the 512×512 source. This creates more visible breathing room
  at the top without extending into the lower transport-control overlay.
- Weather artwork now sizes its icon slot to the **actual shape bounds**.
  A small crescent doesn't reserve the width of a full storm cloud, so it
  appears close to the degrees and shares the row's optical centreline.
- Numbers use three text-only layers: a very soft blue stroke outside the
  glyphs, a dark **negative contrast outline** which separates white text
  from analog hands, and an opaque clean white face. No blur or glow is
  applied to the white face and no rounded rectangular backdrop is added.
- Moon and cloud icon strokes are protected from the analog hands too.
- Changing style version to v12 invalidates previous Android Auto artwork
  URIs and cached generated images.

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
[AutoClock-2-v0.12-beta.apk](https://github.com/skotarov/androidauto-mediaclock/releases/tag/v0.12.0-beta)
from GitHub Releases after tests pass.

1. Open the public **Releases** page and choose **v0.12.0-beta**.
2. Under **Assets**, tap **AutoClock-2-v0.12-beta.apk** (not the source ZIP).
3. Install on the Android phone connected to the car, allowing installation
   from the browser/files app if Android requests permission.
4. Open Auto Clock 2 on the phone and select the mode and weather city.
5. In Android Auto, enable developer-mode unknown sources for the local test
   if Auto Clock 2 is absent from the media app launcher.
6. Select **Auto Clock 2** from the media menu, tap its mode and press Play
   if needed; return to navigation/split-screen mode.

### Testing checklist

- [ ] Compact analog dial's top and bottom edges remain visible in split-screen
- [ ] Moon icon sits close to the temperature rather than with a large gap
- [ ] White digits stay crisp over the hands, with a dark outline not a filled box
- [ ] Full-screen analog clock remains visually balanced


- [ ] Each main radio has two independent checkboxes, both off means clean style
- [ ] Clean analog only draws face and hands
- [ ] Clean digital only draws HH:mm
- [ ] Clean weather only draws icon, temperature and weather description
- [ ] Each family remembers its checked add-ons after switching away and back
- [ ] All twelve combinations render and do not obscure media controls


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
