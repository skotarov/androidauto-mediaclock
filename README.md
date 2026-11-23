# Auto Clock 2 — beta 0.26

An experimental battery-conscious clock for Android Auto's media card next to
navigation. Android Auto officially reserves the media template for audio apps;
this project displays a clock without playing audio, so not every head unit is
guaranteed to support it.

## v0.26 experiment: zero buttons and zero songs

This diagnostic Android Auto version deliberately advertises **zero playable
media items** and an **empty queue**, registers no media transport callbacks,
and publishes a playback state of **STATE_NONE** with **zero actions** and
**no media duration or progress metadata**, even if an old progress setting
was previously enabled.
No Play/Pause/Next/Previous/Stop commands are advertised and no audio is played.

The media session is still active and continues generating the clock's main
artwork once a minute. Since the car can no longer switch between Play and
Pause, a checkbox on the phone ("Показвай времето вместо часовника") selects
the weather-only artwork. In that mode the existing 15-minute weather check
is used instead of the minute clock tick. Existing selected clock, sizes,
themes, colored weather icons and source settings are retained.

**Important:** Android Auto may hide the *whole media card* or continue to
draw a system button regardless. This is a compatibility experiment, not a
guarantee of a fully buttonless visible card. The previous **v0.25** APK
remains available for restoring normal Play/Pause switching.

## Phone settings

The two selectable clock styles have their existing optional checkboxes:

| Play clock style | Without checkbox | With checkbox |
| --- | --- | --- |
| Аналогов часовник | Pure analog dial and hands | Add digital time and/or weather |
| Цифров часовник | Large HH:mm | Also add weather |

**Play** always shows the selected analog/digital clock and its options.
**Pause** immediately switches the same media card and mini media artwork to
the **weather-only** display (colored weather icon, temperature, description,
and city-only label). Pause does not change the saved clock selection; the
following Play restores exactly the selected analog/digital clock.

The old multi-add-on combinations are migrated into these simpler options.
The analog style now starts with **Добави цифров час** unchecked. On upgrade
from v0.13–v0.19 the previous forced-on setting is cleared **once**; after
that, any manual change is remembered. Other style and weather preferences stay unchanged.
Each clock style remembers its checkbox(es) independently. Analog has two
independent switches; Digital has one. Weather-only remains an internal renderer
for Pause and legacy compatibility, not an option in the clock chooser.

Other phone settings: Standard (default) / Compact artwork, Dark (default) / Light (reversed) theme, hide media text, city/source,
and **Движение на прогрес бара (секунди)**. The progress option now defaults
to **OFF for fresh installations**. Previously saved choices are preserved.

When enabled, progress is a virtual 60-second track anchored to the actual
second; no second-by-second bitmap or background job is created. When disabled,
metadata omits duration and reports an unknown position with speed zero. The
Android Auto host may still draw an empty/static line.

## v0.25: Play = clock, Pause = weather

The phone and Android Auto media-browser selectors contain only Analog and
Digital. Pause switches the main media artwork, small quick-control artwork,
queue entry, title and subtitle to the weather-only display. The default
weather source remains Auto; previous manual selections stay saved.

The weather report is requested immediately on entering Pause if due, then
checked every **15 minutes** while the bound media service is alive, using
a delayed main-thread Handler callback rather than a wake lock, alarm or
continuous location listener. The cached result updates the paused media
metadata through the existing preferences listener. Paused mode does not
run the 1-minute clock artwork ticker. When Play resumes, the paused weather
callback is cancelled and minute-aligned clock updates resume.

Legacy saved "Weather" clock mode and stale Android Auto weather media IDs
safely select Analog instead. The internal Weather renderer is retained for
the Pause screen. All sizes, colors and existing clock add-ons are unchanged.

## v0.24: clean Android Auto media card text

The textual lines below the album-art dial are **not** the same as the
drawn analog face. When media text is visible:
- Pure analog (no add-ons): title is live **HH:mm** and the subtitle is blank
- Analog + weather: title **HH:mm** and subtitle is the matching weather symbol
  plus **city only** (e.g. **☁ София**) — never "Станция", "(LBSF)" or NOAA
- Digital + weather: HH:mm plus weather symbol and city
- Weather family: weather glyph and temperature, plus weather glyph and city

The same concise labels populate regular and DISPLAY media metadata and the
queue's current item. No playlist/mode names or album filler appear underneath.
Detailed METAR station and time data remain available in phone settings.

The phone option "Скрий часа и времето под часовника в Android Auto" remains
available; for previously unconfigured installations the useful media labels
are now **visible by default**, while a previously saved hide choice persists.
No change to the bitmap dial, its add-on checkboxes, or weather fetch interval.

## v0.23: standard analog placement and legible colored weather

The standard/normal-size **analog** clock dial and its attached optional text,
weather row, and source label move down **12 px in 512px design coordinates**.
The compact layout, pure digital/weather main layout positions and mini
quick-control panel stay where they were.

A **Цветни иконки на времето** checkbox on the phone (enabled by default)
changes weather symbols between colored and monochrome. Rain now has
larger symbols and thicker high-contrast blue droplets with a bright halo,
designed to survive Android Auto's darkening overlay. Sun, cloud, snow, and
storm symbols also use theme-aware colors. The light/reversed theme preserves
the intended colors by preparing the icon palette before inversion. The
choice updates both main and mini weather art without an extra timer or
weather-network request.

## v0.22 weather and static icon defaults

The weather source defaults to **Auto**, which selects the closest available
fresh METAR report using one approximate device-location fix, falling back to
the labeled Open-Meteo model. First launch of phone settings requests coarse
location permission; the Android Auto media service never requests permission
by itself. Existing explicitly saved manual city selections remain unchanged.

On Android Auto's main clock the source label shows only the city/place, such
as **София** rather than **София (LBSF)**; the full station code, original
observation source and report time remain visible in phone settings.
The unchanging app/dock vector icon now uses subdued blue-gray clock hands,
marks and center dot instead of bright white hands.

`v0.21` features remain: Standard clock size, Dark theme, family-only
large mini-media display and 15-minute weather requests.

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

[AutoClock-2-v0.26-beta.apk](https://github.com/skotarov/androidauto-mediaclock/releases/tag/v0.26.0-beta)

1. Open the beta release under **Releases**, then tap the APK under **Assets**.
2. Install it on the Android phone connected to the car.
3. If Android refuses to update a previous beta due to debug signing keys,
   uninstall **Auto Clock 2 only** and install the new APK.
4. Choose a primary style and its optional checkbox; for Auto weather, grant
   approximate location permission on the phone.
5. Open Auto Clock 2 in the Android Auto media launcher and press Play, then
   switch to navigation's split-screen view.

## Testing checklist (v0.26)

- [ ] No playable songs or browse entries appear
- [ ] No Play/Pause/Next/Previous/Stop controls (host dependent)
- [ ] Clock artwork is visible; note if the entire media panel disappears
- [ ] Clock advances once per minute without pressing Play
- [ ] Phone checkbox switches to weather and back
- [ ] Weather refresh remains at most once every 15 minutes
- [ ] Restore v0.25 if Android Auto hides the media card



- [ ] Phone and Android Auto show ONLY Analog and Digital clock choices
- [ ] Play shows the chosen clock with its saved existing add-ons
- [ ] Pause immediately shows weather-only artwork (not the previous clock)
- [ ] Mini media-panel artwork also switches to weather on Pause
- [ ] Pause displays concise weather/temperature/city labels
- [ ] Paused weather checks only once every 15 minutes while service is alive
- [ ] Play resumes the originally selected clock, with minute updates
- [ ] Legacy previously selected Weather style defaults safely to Analog
- [ ] All previous themes, colors, clock sizes and weather options remain


- [ ] Pure analog: only HH:mm in the text below the clock
- [ ] Analog with weather: HH:mm and only weather glyph + city (e.g. ☁ София)
- [ ] No technical station or preset labels in the now-playing media text
- [ ] Other families retain useful weather/time labels
- [ ] Station name and ICAO continue to appear in phone's weather source status
- [ ] Hide-media-text checkbox still suppresses both lines when selected


- [ ] Standard analog dial is slightly lower than v0.22
- [ ] Compact and mini panel keep their former positions
- [ ] Color weather icons enabled by default; rain streaks remain visible
- [ ] Unchecking the option switches weather icons to monochrome
- [ ] Changing the option updates both main and mini weather artwork
- [ ] Color weather symbols remain recognizable in the Light/reversed theme


- [ ] Fresh install defaults to Auto weather; prompts for approximate location on phone
- [ ] Previously selected manual city persists after an update
- [ ] Android Auto clock source shows "София" rather than "София (LBSF)"
- [ ] Phone settings still identify the measured station/source
- [ ] Static app/dock icon hands look less bright

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
