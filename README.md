# Auto Clock 2 — beta 0.35

An experimental battery-conscious clock for Android Auto's media card next to
navigation. Android Auto officially reserves the media template for audio apps;
this project displays a clock without playing audio, so not every head unit is
guaranteed to support it.

## v0.35: separate full media browser from dashboard suggestions

Real-world v0.34 result: Android Auto kept its second dashboard page even
though `onLoadChildren` returned zero items and MediaSession had an empty queue.
That demonstrates the second page is **not solely controlled** by whether
our MediaBrowserService advertises songs.

v0.35 tests a more targeted distinction:
- Normal media browsers (such as `com.google.android.projection.gearhead`)
  receive a root with the familiar **three daily forecast rows** and weather
  icon miniatures. Full-screen media browsing is restored.
- Clients requesting the Android `BrowserRoot.EXTRA_SUGGESTED` root hint
  are **not offered a browser root**. The same applies to the separate
  `com.google.android.googlequicksearchbox` caller reported by Android Auto
  developers to request the dashboard's "For You" recommendations.
- The media queue remains empty. The active session still supplies the
  same clock/weather artwork: Play = clock, Pause/Stop = current weather,
  no Next/Previous. No changes to digital or analog positioning.
- ForecastStore again updates only from the existing weather worker,
  at most once every three hours, with no additional location fix.
  `notifyChildrenChanged` is emitted only on new forecast/source/theme,
  never at minute boundaries.

**Important:** The host may still show the two dots and populate suggestions
from other apps, even if our app refuses recommendations. Android Auto's
dashboard UI is not controllable from a single media app. No workaround
using an undocumented "hide the dots" flag is claimed.

Reference: Android official `MediaBrowserServiceCompat.BrowserRoot.EXTRA_SUGGESTED`
and observation from androidx/media issue #1731 that the main full-screen
browser and "For You" may use different package-name controllers.
Changing the root may require restarting Android Auto because of cache.

## v0.34: no media lists, test dashboard page dots, digital dial 10px lower

**Goal:** test whether removing media browsing makes Android Auto's
two dashboard page dots disappear when the clock is beside navigation.
Android Auto's dashboard compositor owns the dots. The app cannot force
their removal for just one layout. If they remain, they are host-controlled.

- `onLoadChildren()` returns **zero** media items for every browser root.
  No playable songs, forecast entries or folders. The MediaSession queue
  is also empty; only the latest active clock/weather metadata remains.
- `onPlay()` still shows the chosen clock and `onPause()/onStop()`
  still show current weather, without fake audio or skip actions.
- The digital-only face (with or without its weather add-on) is shifted
  **down 10 artwork pixels**. Analog's original high position and size
  from v0.33 are left entirely untouched. Weather-only artwork unchanged.
- With no forecast list, the previous extra 3-day HTTP request is not
  made anymore. Current weather still refreshes no more often than
  every 15 minutes; clock art only once each minute while Play is active.
  Obsolete forecast code is dormant; none of its network methods is called.
- The phone no longer describes the old forecast library. Existing
  theme, city, hide-text, analog/digital and size preferences survive.

**Compatibility risk:** Returning an empty media library and queue can
cause some Android Auto versions to hide the entire media panel. If that
happens, v0.33 retains the working browseable layout and can be restored.

## v0.33: return to nearly original high analog dial position

- Verified original renderer geometry from v0.29 and v0.30:
  standard analog centre **Y=216**, radius **122**, horizontal centre X=256.
- v0.32 moved the centre too far down (Y=254) and visibly centered the dial.
  This release uses **Y=222**: only **6 pixels lower** than the original,
  with the same full radius 122 and horizontal centre 256.
- No artificial top padding, new scaling, translation or centering.
  The dial should just clear the top edge in the compact dashboard tile.
  The exact crop is controlled by Android Auto and needs a car test.
- Compact clock, digital mode, weather forecast and refresh rates are untouched.
  Bump artwork cache ID so the car does not reuse the previous image.

## v0.32: restore original large analog face without horizontal shift

- **Standard (large) analog artwork only:** restore the original full
  122px artwork radius from v0.30 (v0.31 reduced it to 104px).
- Restore the previous **horizontal center X=256px** by removing the
  38px right-shift applied in v0.31.
- Move the standard analog dial **down only** to Y=254px, so that
  its upper rim is at Y=132px, matching v0.31's upper margin despite
  the larger radius. This makes it less likely the Android Auto host will
  clip the upper rim. The saved user size setting still works normally.
- Compact analog, digital-only and weather-only drawings are unchanged.
  The existing forecast-only browse refresh and three weather icon rows
  remain unchanged, as does Play clock / Pause weather.
- Bump artwork cache identity to avoid stale v0.31 images after updating.

## v0.31: clean forecast icons, less list work, safer dial geometry

- Each of the three upcoming daily forecast entries now has **only the
  meteorological symbol** in its thumbnail (no baked-in °C). High/low values
  are numbers solely in the title, with precipitation in the subtitle.
- The browse screen does **not** refresh each minute. Unlike current
  observations, it receives an explicit `notifyChildrenChanged` only
  when the forecast cache is updated, or a different city, theme or icon
  palette is selected. A paused weather tick stays at 15 minutes; the
  active clock bitmap still changes once a minute.
- The standard large analog dial is scaled slightly smaller, shifted right,
  and padded vertically so its upper rim is not cropped by the host and the
  media control does not cover the hands. Compact artwork is unchanged.
- Android Auto owns its dashboard two-dot/page selector and does not expose
  a supported `MediaBrowserServiceCompat` option to hide it **only** in the
  dashboard while keeping full-screen media browsing. It remains visible.
- The experimental compact-text suppression from v0.30 stays default-on;
  the full Now Playing title/artist is still submitted separately, though
  the host may use the same display fields on both screens.

## v0.30: 3-day forecast rows + compact labels hidden by default

The library screen contains exactly **three daily forecast rows**, one for
each of the three **upcoming** days (tomorrow, the day after, and the third
day). No clock/minute, pressure or terrain-elevation rows remain:
- Each row includes its date, a weather miniature, daytime high/overnight
  low temperatures and the model's maximum chance of precipitation.
- Open-Meteo's daily `weather_code`, `temperature_2m_max`,
  `temperature_2m_min`, and `precipitation_probability_max` fields are used.
- The forecast is cached and fetched **at most every 3 hours** in the
  existing background weather worker using the same approximate coordinates
  it already obtained. For manual cities no location fix is needed.
  Current METAR observations remain independent and capped to 15 minutes.
- If forecast data cannot be obtained, display dated "Няма прогноза"
  rows instead of inventing numbers.
- Browse rows do not need minute-aligned re-fetches because Android Auto
  did not redraw them while the dashboard was split with navigation.

**Text labels:** compact media display-title/subtitle fields now default
to hidden (a new setting key also makes this the default after upgrading
from v0.29). Full real title/artist remain in standard
`METADATA_KEY_TITLE`/`ARTIST` and the queue item description for
clients that use them on the fullscreen Now Playing screen.
Android Auto does not expose a reliable compact-vs-fullscreen state to
`MediaSessionCompat`, and may use display-title fields for both layouts.
Therefore differing compact/full text is **best effort and host-dependent**.

Play still shows the saved clock; Pause/Stop shows current weather.
No Next/Previous or audio. Minute updates run only while Play is active;
while stopped, weather is checked at most every 15 minutes and no
per-minute redraw is scheduled. No wake lock or persistent GPS polling.

## v0.29: Play clock, Stop weather, 3 live browse rows

Press **Play** to show the selected analog/digital clock. **Pause or Stop**
immediately shows the weather-only artwork. Play restores the saved clock.
No real audio, Next/Previous or song duration.

The media-library screen returns three playable rows:
1. Mini analog/digital clock + HH:mm and chosen clock style;
2. Mini weather graphic + city, temperature, condition;
3. Mini barometer/mountain + pressure in **hPa** and station/model elevation
   in **meters**.

For Auto weather, pressure is from NOAA METAR `altim` (hPa) and elevation
from `elev` (m). For model weather, Open-Meteo supplies
`surface_pressure` and `elevation` from the *existing request*.
The pressure quantities differ: METAR altimeter setting and modeled
surface pressure. Elevation describes the station/terrain, **not the car**.
Missing values display "няма данни".

The service calls `notifyChildrenChanged(ROOT_ID)` each minute even when
stopped, and uses fresh minute-specific mini clock artwork. Whether an
already-open Android Auto list updates is host-dependent and must be
tested. Network weather checks are still limited to 15 minutes; one
minute-aligned Handler ticker runs without a wake lock. The obsolete
phone weather override checkbox is removed.

## v0.28: one actually selectable Now Playing item

v0.27 already returned one `FLAG_PLAYABLE` media **leaf** and one queue
entry. However, the app had **no MediaSession callback** and returned
`STATE_NONE` / zero actions; pressing that item in Android Auto's
library opened nothing. It was not a browseable folder/channel — it
was a playable leaf without a working play handler.

This version registers `MediaSessionCompat.Callback` and recognizes the
stable single media ID through `onPlayFromMediaId`; it also handles
`onPrepareFromMediaId`, Play, Pause and Stop. Its playback state is
`STATE_PAUSED` until activated and `STATE_PLAYING` after selection,
with the corresponding actions and active queue item. This is a **silent
clock artwork media item**, not audio playback. The selected analog/digital
mode and phone weather toggle still control the art. There are no skip
actions, no track seeking and no track duration.

The sole leaf remains visible in Android Auto's library because that is how
a `MediaBrowserServiceCompat` exposes content. The difference is that
**tapping the leaf now handles the playback request and activates the
one item**. Android Auto may show Play/Pause buttons; it owns the layout.

## v0.27 experiment: exactly one item; still zero actions

Following the v0.26 car test, where the clock card remained visible but native
transport buttons were drawn **disabled**, this build changes ONLY the media
library/queue content: one playable browser item and one media-session queue
entry (a single Auto Clock display item, **not** actual audio). The selected
clock/weather mode shares the same stable media ID across artwork refreshes.

Zero actions, no transport callbacks, no media-button flags, no duration, and
`STATE_NONE` are retained from v0.26. The phone's clock/weather toggle and
all previous visual preferences remain intact. This isolates whether Android
Auto adjusts its controls or the layout when there is exactly **one** item.

**Android Auto still owns the buttons**; one item does not guarantee they will
disappear, nor that it will show playable controls. No audio is played.

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

[AutoClock-2-v0.35-beta.apk](https://github.com/skotarov/androidauto-mediaclock/releases/tag/v0.35.0-beta)

1. Open the beta release under **Releases**, then tap the APK under **Assets**.
2. Install it on the Android phone connected to the car.
3. If Android refuses to update a previous beta due to debug signing keys,
   uninstall **Auto Clock 2 only** and install the new APK.
4. Choose a primary style and its optional checkbox; for Auto weather, grant
   approximate location permission on the phone.
5. Open Auto Clock 2 in the Android Auto media launcher and press Play, then
   switch to navigation's split-screen view.

## Testing checklist (v0.35)

- [ ] Full-screen Auto Clock media library shows exactly 3 forecast days
- [ ] Compact dashboard stays on the clock; check whether its second dot hides
- [ ] If second page remains, check for other-app suggestions or empty panel
- [ ] Play/Pause clock/weather works; no Next/Previous
- [ ] Weather forecast updates on fetch, not every minute
- [ ] If results seem cached, disconnect/reconnect Android Auto and reopen



- [ ] Digital-only clock is slightly lower; analog remains in v0.33 position
- [ ] No rows/folders are shown in the media browser or queue
- [ ] Check whether the split-map dashboard loses its two page dots
- [ ] Main clock tile remains visible and Play/Pause still switches modes
- [ ] Weather updates continue but no forecast HTTP request is made
- [ ] If the clock tile vanishes, restore v0.33 and report the host behavior



- [ ] Full-sized analog dial is again near the TOP of the small dashboard tile
- [ ] Only ~6px vertical movement from old v0.29/0.30 high position
- [ ] Upper rim touches the top without being cut or showing excessive space
- [ ] No horizontal shift and no altered compact/digital/weather artwork



- [ ] Standard large analog dial regains full pre-v0.31 size
- [ ] Analog horizontal center is the original position, not shifted right
- [ ] Dial has more top clearance; upper rim does not clip
- [ ] Compact analog size and position stay unchanged
- [ ] Three forecast rows, controls and weather refresh remain unchanged



- [ ] Browse forecast icon has weather glyph only; temperatures are in text
- [ ] No clock/forecast list redraw at each minute while split with map
- [ ] New forecast refreshes list after successful cached weather fetch
- [ ] Large analog clock top rim fully visible, controls not overlapping
- [ ] Small card labels hidden by default; full screen tries standard text
- [ ] Media dashboard dots remain host-controlled; no extra controls added
- [ ] Play clock / Pause weather remain functional



- [ ] Browse shows three dates tomorrow/next 2 days, NO pressure/altitude
- [ ] Each day shows icon, forecast high/low, precipitation probability
- [ ] Forecast remains useful even if Android Auto does not redraw the list
- [ ] Forecast refetch at most every 3h; uses existing weather worker/location
- [ ] Compact media text hidden by default (new preference key)
- [ ] Fullscreen Now Playing title/subtitle visible if host uses standard fields
- [ ] Play clock, Pause/Stop current weather, no skip controls
- [ ] Minute clock redraw only in Play, 15-minute paused weather refresh
- [ ] Invalid/missing forecast displays placeholders without fake values



- [ ] Play: clock, Pause/Stop: weather, Play: same clock again
- [ ] Library: three rows, each with distinct miniature artwork
- [ ] Clock row title and mini hands advance at each minute boundary
- [ ] Open library updates without reopening if Android Auto honors refresh
- [ ] Weather city, temperature and condition are shown
- [ ] Third row uses actual pressure/elevation or "няма данни"
- [ ] Elevation represents station/model, not car GPS
- [ ] No additional requests; weather throttle 15 minutes
- [ ] No audio, Next/Previous or per-second animation



- [ ] The library contains exactly one playable Auto Clock item
- [ ] Tapping it activates clock Now Playing instead of doing nothing
- [ ] MediaSession reports PLAYING and the matching single queue item
- [ ] No Next/Previous or song progress/seeking
- [ ] Play/Pause can respond while the graphic remains silent
- [ ] Clock and weather phone selections and their updates still work



- [ ] Browse view lists exactly one Auto Clock media item
- [ ] Media session reports exactly one matching queue item
- [ ] Compare buttons against v0.26: fewer, same disabled, or different
- [ ] Clock continues changing its artwork each minute
- [ ] Phone switch continues selecting weather or clock
- [ ] No new progress bar, audio, or extra transport commands



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
