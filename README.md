# Auto Clock 2 — beta 0.21

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
The analog style now starts with **Добави цифров час** unchecked. On upgrade
from v0.13–v0.19 the previous forced-on setting is cleared **once**; after
that, any manual change is remembered. Other style and weather preferences stay unchanged.
Each main style remembers its checkbox(es) independently. Analogue has two
independent switches, allowing four distinct looks (pure dial, digital time,
weather, or both). Digital and Weather each have one optional switch.

Other phone settings: Standard (default) / Compact artwork, Dark (default) / Light (reversed) theme, hide media text, city/source,
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

## Mini now-playing panel (v0.21)

Removed the v0.17–v0.20 dynamic dock-icon experiment, color alternation and
periodic media-browser icon invalidations. The Android Auto dock application
shortcut remains a static platform-owned image.

The 160×160 RGB_565 now-playing DISPLAY_ICON is drawn specifically for the
primary selected mode, with no large-card additions:
- Analog: almost full-size analog hands and dial
- Digital: large centered HH:mm
- Weather: large condition glyph and temperature, no clock

Main artwork keeps all user-selected additions. Dark/black is the default
theme; Light is the reversed-color alternative. Standard is the default
main clock size, while Compact remains available. Explicit saved settings
remain unchanged when updating.

The mini display metadata and full media art refresh once a minute during Play.
A host may still cache or choose different image fields. Weather requests remain
limited to once per 15 minutes during active weather display. No extra timer
or background audio was introduced.

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

[AutoClock-2-v0.21-beta.apk](https://github.com/skotarov/androidauto-mediaclock/releases/tag/v0.21.0-beta)

1. Open the beta release under **Releases**, then tap the APK under **Assets**.
2. Install it on the Android phone connected to the car.
3. If Android refuses to update a previous beta due to debug signing keys,
   uninstall **Auto Clock 2 only** and install the new APK.
4. Choose a primary style and its optional checkbox; for Auto weather, grant
   approximate location permission on the phone.
5. Open Auto Clock 2 in the Android Auto media launcher and press Play, then
   switch to navigation's split-screen view.

## Testing checklist (v0.21)

- [ ] Standard size and dark background selected by default
- [ ] Light/reversed appearance applies to main clock and mini controls
- [ ] Analog mini uses dial only; Digital mini uses HH:mm only
- [ ] Weather mini uses condition plus temperature only
- [ ] Main clock retains its separate selectable additions
- [ ] Main media clock and mini update with Play once per minute
- [ ] Pause stops minute updates and weather respects 15-minute limit
- [ ] No red/green test remains; dock app shortcut stays static

## Development

Kotlin / Android SDK 35 / Java 17; minSdk 23. Run
`gradle testDebugUnitTest assembleDebug` locally or inspect **Android Build**
and **Auto Clock Beta APK** GitHub Actions workflows.

The stable `main` branch is preserved. This beta lives in
`revamp/low-power-v2` until tested in the car.
