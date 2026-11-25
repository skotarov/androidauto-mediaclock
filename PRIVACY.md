# Auto Clock 2 — Privacy Policy

Effective 25 November 2026

Auto Clock 2 is a travel clock and weather companion maintained by the [Auto Clock developer](https://github.com/skotarov).

## Data and purpose

The app optionally accesses approximate location to determine the current locality, provide weather forecasts and warnings while connected to Android Auto or while phone settings are open. The clock works without location permission; local weather may be unavailable.

Approximate coordinates are sent over HTTPS to Open-Meteo in the non-commercial beta. A commercial build requires a separately configured licensed forecast gateway. Before commercial distribution this policy must identify the gateway operator and its retention practices. The endpoint receives coordinates and ordinary network information such as IP address. Android's Geocoder may also process coordinates through its system provider. NOAA Aviation Weather Center receives ordinary network information but no coordinates as request parameters; station selection is performed on the device.

Preferences, last location/locality and cached weather are stored on the device, not a journey history. The clock, weather and locality are exposed through the Android Auto media service and artwork provider; apps with access to those exported interfaces can potentially read displayed locality/weather.

The current beta has no accounts, advertising or analytics SDKs, or developer-operated tracking service. We do not sell personal data or use location for advertising. External providers may retain their own logs.

The app uses an installed offline English Android text-to-speech voice to read the displayed notice text while weather and voice alerts are enabled. It does not intentionally select a network voice or transmit notice text to a developer-operated speech service.

An optional Bluetooth phone audio setting temporarily selects a connected hands-free audio output for alerts. The app does not place calls, read call history, record audio or request microphone access.

## Controls and retention

Revoke location permission in Android Settings to stop new location access. Previously cached data may remain until refreshed or deleted. Clear app storage or uninstall to delete local settings and cached data. Android cloud backup is disabled for this app version. HTTPS is used for weather requests. Third-party retention is governed by provider policies.

[Open-Meteo terms](https://open-meteo.com/en/terms), [NOAA privacy](https://www.noaa.gov/privacy-policy), [Google privacy](https://policies.google.com/privacy).

## Contact

For privacy questions see the [developer profile](https://github.com/skotarov); for non-sensitive questions use the [project issue tracker](https://github.com/skotarov/androidauto-mediaclock/issues). Do not post personal information in public issues. A direct privacy contact and the commercial forecast gateway details must be confirmed before commercial distribution.
