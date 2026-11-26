# SmartTrip Clock — Privacy Policy

Effective 25 November 2026

Auto Clock 2 is a travel clock and weather companion maintained by the [SmartTrip Clock developer](https://github.com/skotarov).

## Data and purpose

The app optionally accesses approximate location to determine the current locality, provide weather forecasts and warnings while connected to Android Auto or while phone settings are open. The clock works without location permission; local weather may be unavailable.

Approximate coordinates are sent over HTTPS to Open-Meteo in the non-commercial beta. A commercial build requires a separately configured licensed forecast gateway. Before commercial distribution this policy must identify the gateway operator and its retention practices. The endpoint receives coordinates and ordinary network information such as IP address. Android's Geocoder may also process coordinates through its system provider. NOAA Aviation Weather Center receives ordinary network information but no coordinates as request parameters; station selection is performed on the device.

Preferences, last location/locality and cached weather are stored on the device, not a journey history. The clock, weather and locality are exposed through the Android Auto media service and artwork provider; apps with access to those exported interfaces can potentially read displayed locality/weather.

The current beta has no accounts, advertising or analytics SDKs, or developer-operated tracking service. We do not sell personal data or use location for advertising. External providers may retain their own logs.

The app uses an installed offline English Android text-to-speech voice to read the displayed notice text while weather and voice alerts are enabled. It does not intentionally select a network voice or transmit notice text to a developer-operated speech service.

An optional car phone audio setting selects a connected hands-free output during alerts and selected stream playback. The route is released when audio ends, the setting is disabled, the media client disconnects, or the system interrupts it. The app does not place calls, read call history, record audio or request microphone access.

The Lite beta optionally requests Android phone-state permission to pause media and spoken alerts during incoming or outgoing calls and resume only if playback remains requested. It processes only the current busy/idle state locally while the media service is active; it does not read or store phone numbers, contacts, call history or call contents, and does not transmit call state. The permission can be revoked in Android Settings; on Android 12 and newer an audio-mode callback provides a limited fallback.

Custom streams are optional. Entered HTTPS or public HTTP audio URLs, station names and small icons are stored locally. On saving a station, the app attempts to read its stream name and same-host icon. The phone connects directly to the selected provider, which receives normal network information such as IP address. HTTP audio and stream metadata are not encrypted and may be observed or modified in transit. HTTP addresses must not contain credentials or query parameters; never enter a private or tokenized HTTP address. Stream icons are fetched only over HTTPS. Weather requests remain HTTPS-only. Audio is not relayed through an Auto Clock server. When using Bluetooth phone audio, an on-device silent PCM audio buffer is looped to keep the route active during alerts or active phone-channel stream playback. Speech is synthesized directly without temporary audio files. The silent buffer does not contain recorded audio.

User-selected local audio/video and plain-text documents are opened through Android's system document picker. The app stores the selected document URI, display name and read permission locally, without copying or uploading the file. Removing the last list entry for a document releases its saved access permission. The phone settings can connect to the same playback service while open.

## Controls and retention

Revoke location permission in Android Settings to stop new location access. Previously cached data may remain until refreshed or deleted. Clear app storage or uninstall to delete local settings and cached data. Android cloud backup is disabled for this app version. HTTPS is used for weather requests. Third-party retention is governed by provider policies.

[Open-Meteo terms](https://open-meteo.com/en/terms), [NOAA privacy](https://www.noaa.gov/privacy-policy), [Google privacy](https://policies.google.com/privacy).

## Contact

For privacy questions see the [developer profile](https://github.com/skotarov); for non-sensitive questions use the [project issue tracker](https://github.com/skotarov/androidauto-mediaclock/issues). Do not post personal information in public issues. A direct privacy contact and the commercial forecast gateway details must be confirmed before commercial distribution.

## Play distribution and reading

The initial Play release does not request location or contact weather providers. It uses ordinary media playback, with an Android media foreground notification while playing. Local audio and text positions are saved on the device. TXT reading creates a temporary UTF-16 text cache in private app storage, deleted when reading stops normally; stale caches are cleaned on later reading. The offline system speech engine receives text chunks for synthesis; the app does not deliberately upload the text. The test build retains optional location and weather features described above. The Play release has no payment, advertising, analytics or account system at launch. Future monetization or weather services require a revised disclosure before activation.

## Additional local book formats

EPUB, FB2 and DOCX documents are parsed locally and converted to a temporary private text cache for offline speech. Markdown is read as plain text. ZIP-based books may briefly require a private temporary archive copy; this is deleted after extraction, and old orphaned book caches are cleaned on later reading. No document content is sent to a developer server.

PDF (embedded text only), local HTML and RTF are also processed on the device. PDF extraction can use temporary scratch storage in the private app cache, which is normally deleted when extraction closes. HTML scripts and remote resources are not executed or fetched. Scanned documents and password-protected PDFs are not supported. No OCR or online conversion service is used.

Extracted book text may remain in private app cache for reuse (up to 3 files, 64 MiB total, 7 days; cleaned on subsequent reading). Documents without reliable size and modification metadata use temporary files only. Clearing app cache removes retained extracts. Bookmarks remain separate.

## Web Reader (Lite beta)

When you explicitly add a public HTTPS webpage, the phone requests that page directly from its provider, which receives normal connection information such as IP address. Redirects are HTTPS-only. No browser cookies or login credentials are supplied, scripts are not executed, and embedded images, ads and other resources are not requested. An on-device parser extracts readable article text, saved in private app storage as a local document for offline speech. The saved text is removed when its last library entry is deleted or app data is cleared. No developer server or automatic page refresh is used.

## Web Media folders (Lite beta)

Public HTTPS page or playlist URLs and folder names are stored locally. Opening a folder fetches the page directly and extracts public media links without executing scripts or loading embedded players. No scheduled refresh runs. Selecting an item stores its most recently selected URL locally and connects directly to that media provider using the existing player. A provider receives ordinary network information. Deleting a folder removes its local reference; it does not change remote content.
