# Google Play release preparation — 0.98

## Build and product
- Keep application ID com.kotarov.autoclock2 to preserve installation identity. Debug beta keeps existing clock/weather artwork and alert controls.
- Release is a real audio player: library of local audio, TXT reading and user-entered HTTPS and public HTTP audio streams, actual titles, static media artwork, normal audio focus and playback states. No Alert only in car library, no simulated phone audio route, no clock as media artwork. Phone clock remains. No weather requests in Play build until licensed and reviewed.
- CI builds and stores an unsigned AAB. Build locally with `gradle bundleRelease`; sign with the owner's upload key before Play submission. Enroll in Play App Signing and use owner-controlled upload key. Do not reuse GitHub beta certificate as upload key without explicit decision.
- Launch free, no ads or payments. Later paid features require Google Play Billing and a weather license before weather is enabled in monetized distribution. No fake purchase UI.
- Working brand SmartTrip Clock, slogan Listen. Plan. Travel. Verify availability and trademarks before public listing.

## Console declarations (draft for current Play release only)
- Category Music & Audio; opt in Android Auto, not Android Automotive OS unless separately tested.
- No accounts, ads, purchases or tracking SDKs in this initial release. User-entered external stream URLs connect to third parties; network IP/metadata and provider retention must be reviewed in Data safety. Public HTTP audio/metadata is unencrypted; no credentials or query parameters are accepted in HTTP URLs. Android cleartext is enabled for arbitrary user-entered media hosts; non-media weather connections remain HTTPS-only with redirects disabled. Do not assert no collection without verifying providers. Local documents and bookmarks stay on device; installed offline TTS engine handles speech.
- Foreground service: mediaPlayback for user-initiated audio and spoken text. Stop at explicit stop and projection disconnect. Supply reviewer video.
- Dedicated `PRIVACY-PLAY.md` is linked in-app. Confirm privacy contact email and public stable privacy URL; revise after provider/licensing or billing changes.
- Complete app access, content rating, target audience, ads, Data safety, EU trader status as applicable. Owner must verify answers and submit.
- Closed test if account requires it; verify Play signing, actual AAB, Play console eligibility and car review.

## Tests before submission
- Android Auto DHU and physical car: browse, Play/Pause/Stop, seek, search, playback state and title, audio focus loss/gain including incoming call, navigation guidance, no forced HFP, disconnect/reconnect.
- Phone API 23/31/35/36: file permissions, offline voice availability, large file, resume, notification Stop and process death. Confirm no location dialog or weather requests on Play build.
- Verify release bundle and signature, internal test, reviewer instructions, screenshots from actual Play build. Final review is not guaranteed.

## Listing draft (working brand)
Short: Listen to audio, radio and text files on the road with Android Auto.
Full: Bring your own audio on every journey. Play local recordings, audiobooks and lectures, add direct HTTPS or public HTTP radio or audio streams, or listen to plain-text files using an installed Android offline voice. Continue from your saved reading position and control playback from Android Auto. A phone clock is included. No account required. Content is supplied by the user; no built-in podcast directory or downloads are claimed.
