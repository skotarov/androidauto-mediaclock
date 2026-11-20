# Auto Clock 2 — beta 0.13

An experimental battery-conscious clock for Android Auto's media card next to
navigation. Android Auto officially reserves the media template for audio apps;
this project displays a clock without playing audio, so not every head unit is
guaranteed to support it.

## Phone settings

The three primary radio styles each have a single optional checkbox:

| Main style | Without checkbox | With checkbox |
| --- | --- | --- |
| Аналогов часовник | Analog dial + centred digital HH:mm | Also add weather |
| Цифров часовник | Large HH:mm | Also add weather |
| Времето | Large weather icon, temperature and description | Also add smaller digital HH:mm |

The old multi-add-on combinations are migrated into these simpler options.
Each main style remembers its checkbox independently.

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

## Resource and compatibility design

- Minute-aligned clock artwork and media metadata only while the clock is active
- Weather attempts no more than hourly unless manually refreshed or source changed
- 512×512 RGB_565 original artwork and 320×320 RGB_565 direct media artwork
- Small capped PNG cache; no fake audio, polling of GPS, wake locks or alarms
- Immediate best-effort redraw when settings are changed on the phone
- Android Auto may keep native playback buttons, text placeholders or static bars
- Only one location request is made per Auto-weather refresh; never every minute

Actual battery, split-screen crop, and native progress behavior must still be
tested on the target phone and Android Auto head unit.

## Download and install

[AutoClock-2-v0.13-beta.apk](https://github.com/skotarov/androidauto-mediaclock/releases/tag/v0.13.0-beta)

1. Open the beta release under **Releases**, then tap the APK under **Assets**.
2. Install it on the Android phone connected to the car.
3. If Android refuses to update a previous beta due to debug signing keys,
   uninstall **Auto Clock 2 only** and install the new APK.
4. Choose a primary style and its optional checkbox; for Auto weather, grant
   approximate location permission on the phone.
5. Open Auto Clock 2 in the Android Auto media launcher and press Play, then
   switch to navigation's split-screen view.

## Testing checklist

- [ ] New install: moving seconds/progress checkbox is **unchecked**
- [ ] Analog without weather keeps its large analog face and digital HH:mm
- [ ] Digital without weather shows HH:mm without hands
- [ ] Weather without added clock shows only its weather data
- [ ] Three styles restore their independently chosen checkbox
- [ ] Choose Auto on the phone, grant approximate location, check station name
- [ ] Android Auto artwork shows the station/city when in Auto weather
- [ ] If METAR is unavailable, label says Open-Meteo model, not station
- [ ] Switching from Auto back to a city updates data and labels
- [ ] Foreground and split-screen clock updates without high CPU usage
- [ ] Battery and RAM measurements compared to the original application

## Development

Kotlin / Android SDK 35 / Java 17; minSdk 23. Run
`gradle testDebugUnitTest assembleDebug` locally or inspect **Android Build**
and **Auto Clock Beta APK** GitHub Actions workflows.

The stable `main` branch is preserved. This beta lives in
`revamp/low-power-v2` until tested in the car.
