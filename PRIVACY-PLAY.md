# SmartTrip Clock — Google Play privacy policy

This policy applies to the initial audio-player edition distributed through Google Play, not the experimental GitHub clock/weather beta.

## Developer and contact

The application is maintained by the SmartTrip Clock developer, [developer profile](https://github.com/skotarov). For privacy inquiries use the developer contact published on the Google Play listing when available. Do not post private information in public issues. A direct contact must be confirmed by the developer before public distribution.

## Information used

The app allows users to select audio or TXT documents through Android's document picker. It stores document names, URIs, read permission and playback/reading bookmarks locally. TXT reading uses a private temporary text cache and an installed offline Android text-to-speech voice. Text is not intentionally uploaded to a developer-operated service. Files remain under the user's control.

If you add a direct HTTPS stream address, your device connects to the selected third-party provider for playback and available stream metadata. The provider receives normal network information, such as IP address, and may process it under its own policy. The developer does not relay this traffic. Only add streams you trust and are entitled to access.

This edition does not request location permission or send location to weather services. It has no accounts, advertisements, analytics SDKs or purchases. It uses a media playback foreground service and system audio focus while playing, plus Android Auto connection status to stop work when disconnected.

## Retention and controls

Settings and bookmarks remain on the device until removed or app storage is cleared. Temporary text caches are removed when reading stops normally; orphaned caches are cleaned on later reading. You can remove source entries, clear app storage, revoke document access through Android or uninstall the app. Android cloud backup is disabled. External stream providers may have their own retention and deletion practices. HTTPS is used for direct streams.

The developer does not sell personal data or use it for advertising. Any future weather or payment functionality requires an updated policy and applicable Play declarations before activation.

## Additional local book formats

EPUB, FB2 and DOCX documents are parsed locally and converted to a temporary private text cache for offline speech. Markdown is read as plain text. ZIP-based books may briefly require a private temporary archive copy; this is deleted after extraction, and old orphaned book caches are cleaned on later reading. No document content is sent to a developer server.
