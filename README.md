# Auto Clock 2 — beta 0.19

An experimental battery-conscious clock for Android Auto's media card next to
navigation. Android Auto officially reserves the media template for audio apps;
this project displays a clock without playing audio, so not every head unit is
guaranteed to support it.

## Phone settings

The three primary radio styles have optional checkboxes:

| Main style | Without checkbox | With checkbox |
| --- | --- | --- |
| Аналогов часовник | **Pure analog dial and hands** with no checkboxes | Check **Добави цифров час** and/or **Добави времето** |
| Цифров часовник | Large HH:mm | Also add weather |
| Времето | Large weather icon, temperature and description | Also add smaller digital HH:mm |

The old multi-add-on combinations are migrated into these simpler options.
Each main style remembers its checkbox(es) independently. Analogue has two
independent switches, allowing four distinct looks (pure dial, digital time,
weather, or both). Digital and Weather each have one optional switch.

Other phone settings: Compact/Standard artwork, hide media text, city/source,
and **Движение на прогрес бара (секунди)**. The progress option now defaults
to **OFF for fresh installations**. Previously saved choices are preserved.

When enabled, progress is a virtual 60-second track anchored to the actual
second; no second-by-second bitmap or background job is created. When disabled,
metadata omits duration and reports an unknown position with speed zero. The
Android Auto host may still draw an empty/static line.

## Weather source

### Manual city

Uses [Open-Meteo](https://open-meteo.com/): current data from numerical weather
models, not observations at a physical station. Select Sofia, Stara Zagora,
Plovdiv, Varna, Burgas, Ruse, Pleven or Veliko Tarnovo.

### Авто — най-близка метеостанция

1. Choose **Авто** on the phone. Android requests **approximate location**
   permission; the user can deny it and switch back to a manual city.
2. When weather is needed, the app requests a **single network-based position**,
   using a recent known position if available. No continuous GPS updates.
3. It queries the latest reported METAR observations for eight Bulgarian
   aviation meteorological stations in **one HTTPS request** to the
   [NOAA Aviation Weather Center](https://aviationweather.gov/data/api/).
4. It chooses the **closest station with a valid temperature and observation
   newer than three hours**, within a 180 km radius.
5. The station name, airport ICAO ID and source appear in the phone settings;
   the station name also appears inside weather artwork on Android Auto.
6. If no recent station reports are available, the app asks Open-Meteo for
   model weather at the approximate device location. It is explicitly labeled
   **Модел**, not falsely presented as a meteorological station.

**Coverage:** the curated METAR network currently covers **Bulgaria**.
Outside the coverage area, automatic weather uses the labeled model fallback.
Many observatories do not publish real-time open observations; an aviation
station is a genuine reporting station but not necessarily the nearest
meteorological installation of any kind. During a journey the nearest station
is refreshed when the next weather update requests a location. Background
location restrictions on some Android versions may require opening phone
settings to renew the fix.

**Data privacy:** the phone's coordinates are not sent to NOAA (only a list of
station codes is sent). If the model fallback is needed, coordinates are sent
to Open-Meteo to obtain location-specific weather. The app does not retain a
history of positions. No ongoing location tracking or background location
permission.

## Analog dial geometry in v0.16

The compact 512px artwork centre is now y=208 (previously 216), moving the
whole analog dial, digital time and optional weather row 8 px up together.
The standard centre is restored to the v0.12 value y=204 (instead of 198).
Dial radii, relative layout and weather icon sizes are unchanged.

In v0.15 the analogue weather row moves up independently of the dial:
compact weather offset 30 px × radius/122 (previously 39); standard
weather offset 34 px (previously 39). Both analogue variants, with or
without HH:mm, reuse the same respective offset and font size (29 ×
radius/122), so turning digital time on/off never makes the weather jump.

With Auto weather, the source station label is positioned just below
the actual dial edge (compact gap 18 × radius/122 px; standard 16 px),
instead of inside the dial. Text is measured and shrunk only if
necessary to stay within dial-relative width. All non-analog layouts
retain their existing source label placement.

## Clean analog restored in v0.16

The Analog radio selection now displays two independent checkboxes:
**Добави цифров час** and **Добави времето**. Both unchecked means
`ANALOG_PURE`: no central digits, no weather, only the analog dial and
hands. Saved choices from 0.15 remain intact (digital time stays checked
until the user explicitly unchecks it). The compact/standard positions
and the observation-source label improvements from v0.15 are unchanged.

## Red / green minute-by-minute media icon experiment (v0.19)

**Diagnostic only:** the media-browse item thumbnail and the active MediaSession
`DISPLAY_ICON` now show a 160×160 analog dial with a prominent **RED ring on
even minutes** and **GREEN ring on odd minutes**. The image's timestamp URI
changes each minute, the active session republishes metadata, and
`notifyChildrenChanged(ROOT_ID)` is called once per minute **only during Play**.
No extra second-level timer or wake lock is used. Main media artwork is
unchanged and remains updated every minute during Play.

Test separately: (1) the fullscreen-map bottom media quick-control artwork;
(2) the bottom dock **application icon**; (3) the media browse list thumbnail.
The dock launcher icon is a static manifest resource. Android Auto chooses which
media images to show and may cache them. Seeing red/green in one surface does
not imply that a different surface can be updated programmatically.

**Weather:** the selected weather source is requested no more often than every
**15 minutes** while the app is playing and a weather-enabled style is selected.
Data remains cached and valid up to three hours; the station's report itself
may be older than the last fetch, because METAR stations publish on their own
schedules. On selecting a different city/source, a fetch may start immediately.
Auto mode uses one coarse location fix per fetch, not ongoing GPS tracking.
Pause stops scheduled minute updates and therefore also scheduled weather checks.

## Resource and compatibility design

- Minute-aligned clock artwork and media metadata only while the clock is active
- Weather attempts at most every 15 minutes unless manually refreshed or source changed
- 512×512 RGB_565 original artwork and 320×320 RGB_565 direct media artwork
- Small capped PNG cache; no fake audio, polling of GPS, wake locks or alarms
- Immediate best-effort redraw when settings are changed on the phone
- Android Auto may keep native playback buttons, text placeholders or static bars
- Only one location request is made per Auto-weather refresh; never every minute

Actual battery, split-screen crop, and native progress behavior must still be
tested on the target phone and Android Auto head unit.

## Download and install

[AutoClock-2-v0.19-beta.apk](https://github.com/skotarov/androidauto-mediaclock/releases/tag/v0.19.0-beta)

1. Open the beta release under **Releases**, then tap the APK under **Assets**.
2. Install it on the Android phone connected to the car.
3. If Android refuses to update a previous beta due to debug signing keys,
   uninstall **Auto Clock 2 only** and install the new APK.
4. Choose a primary style and its optional checkbox; for Auto weather, grant
   approximate location permission on the phone.
5. Open Auto Clock 2 in the Android Auto media launcher and press Play, then
   switch to navigation's split-screen view.

## Testing checklist (v0.19)

- [ ] Install the v0.19 APK; start Auto Clock in Android Auto and press Play.
- [ ] View fullscreen Google Maps; at a minute boundary check whether the
      **media quick-control thumbnail** switches RED ↔ GREEN.
- [ ] Check separately whether the **dock app shortcut** switches color,
      stays fixed, or only shows a native playback-state overlay.
- [ ] Open the clock media browse list and verify whether its icon alternates.
- [ ] Press Pause: minute updates should stop. Press Play to resume them.
- [ ] Confirm the main analog clock still updates each minute.
- [ ] With weather enabled, compare weather-fetch timestamps after ~15 minutes.
- [ ] Confirm no extra battery drain, location polling or background ticking.
- [ ] Recheck analog/digital/weather style options and source labels.

## Development

Kotlin / Android SDK 35 / Java 17; minSdk 23. Run
`gradle testDebugUnitTest assembleDebug` locally or inspect **Android Build**
and **Auto Clock Beta APK** GitHub Actions workflows.

The stable `main` branch is preserved. This beta lives in
`revamp/low-power-v2` until tested in the car.
