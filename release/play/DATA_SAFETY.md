# Play Console — Data Safety form answers

Ground truth: the dependency list (app/build.gradle.kts) has **no analytics, no ads, no Firebase,
no Crashlytics, no Sentry** — no SDK that collects or transmits user data. The app has no servers.
All clipboard/file data stays on the device or moves directly between the user's paired devices over
the LAN / off-grid link. So the honest, defensible answer throughout is **no data collected, no data
shared**. Paste these into Play Console → App content → Data safety.

> ⚠️ This form must match your final telemetry decision. If you EVER add a crash/analytics SDK,
> "no data collected" becomes false and you must revise this form AND the privacy policy. Lock the
> "no SDK / Play vitals only" decision before submitting. (Play's Android vitals crash/ANR data is
> collected by Google from the OS, not by your app, and does not need to be declared here.)

## Does your app collect or share any of the required user data types?
**No.** (This unlocks a short form.)

Rationale you can give if review asks: Clipboard Share is a peer-to-peer utility. Clipboard contents
and transferred files are sent **directly between the user's own paired devices** over local Wi-Fi or
an off-grid link (Wi-Fi Direct / QR / NFC), encrypted end-to-end with AES-256-GCM. None of it reaches
the developer or any third party. The app requires no account and has no backend.

## Per-category answers (if the long form is shown)
Answer **Not collected** for every category:

| Category | Collected? | Note |
|---|---|---|
| Location | No | `ACCESS_FINE_LOCATION` is `maxSdkVersion=32`, used only to satisfy legacy Wi-Fi-scan requirements; `NEARBY_WIFI_DEVICES` carries `neverForLocation`. Location is never read or transmitted. |
| Personal info (name, email, IDs) | No | No account, no sign-in. |
| Financial info | No | — |
| Health & fitness | No | — |
| Messages / clipboard | No | Clipboard text is transferred device-to-device, never collected by the developer. |
| Photos / videos / files | No | Files move P2P between the user's devices; not uploaded anywhere. |
| Audio | No | — |
| Contacts | No | — |
| Calendar | No | — |
| App activity / browsing | No | — |
| App info & performance (crash logs, diagnostics) | No | No crash SDK ships. (If you later add one, change this.) |
| Device or other IDs | No | — |

## Security section
- **Is data encrypted in transit?** Yes. All clipboard and file transfers use AES-256-GCM with keys
  derived from an ECDH pairing handshake. (Answer Yes even though nothing goes to a server — it
  describes the P2P link.)
- **Can users request data deletion?** The app stores only local data (transfer history, paired-device
  list, keys). Users clear it in-app (history screen / unpair), and uninstalling removes everything.
  There is no server-side data to delete.
- **Committed to Play Families Policy?** Only if you opt into the Families program — you are not (see
  CONTENT_RATING.md, target audience 13+).

## Privacy policy URL
Required and must be a live, public HTTPS page (no PDF). Use the hosted page:
`https://<your-pages-domain>/privacy.html` — deploy `website/privacy.html` to GitHub Pages first so the
URL returns 200 before you submit. The page already serves the policy in 10 languages.
