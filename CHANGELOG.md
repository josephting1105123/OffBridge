# Changelog

Notable changes leading into the **3.0** releases (Android `3.0.2` / Windows `3.0.2`).

---

## 3.5.2 — Android (transfer/receive fixes)

Android bumps to **3.5.2** (versionCode 19). Three transfer bugs reported on the Android client;
the folder-send fix also lands on the Windows sender/receiver (Windows keeps 3.5.4 until its next build).

### Fixed
- **Received text files open again.** Tapping a received `.txt` — or any non image/video/apk/pdf file —
  in transfer history opened the containing folder instead of the file. It now opens the file itself
  with a MIME resolved from the extension, and only falls back to the folder when nothing on the device
  can view the type.
- **Sending a folder works.** The Windows sender handed a picked directory straight to the byte pump,
  where `FileInputStream(dir)` threw — the transfer stalled on "resume" at 0% and never sent. Folders
  are now walked into their files before the offer. Two files that flatten to the same leaf name (e.g.
  `a.txt` under two subfolders) are de-collided on the receiver so neither overwrites the other — mirrored
  on the Android and Windows receivers.
- **Incoming requests pop in-app.** When the app is open, an incoming transfer request now shows its
  accept/decline screen immediately instead of being buried in a notification; the notification is used
  only when the app is in the background.

---

## 3.5.1 — Android + Windows (cross-platform transfer fixes, audit pass)

Both platforms now report **3.5.1** (Android versionCode 18). A read-only audit of the Android
client, Windows client, and website found 44 bugs; the high-severity, cross-platform, and crash
bugs were fixed and build-verified. Full findings and the deferred list: `release/AUDIT-3.5.1.md`.

### Cross-platform transfer correctness
- **Manifest-bounded upload (Android):** plaintext sends now stream exactly the manifest byte count
  and abort on a null stream or short read, instead of streaming to EOF. A source file that changed
  size mid-batch previously desynced the receiver's positional read and corrupted every later file
  (off-grid sends included). Android now matches the Windows sender.
- **Manifest parse parity:** one shared parser skips empty-name `:size` entries (`lastColonIndex > 0`)
  so Android LAN, Android off-grid, and Windows agree on a degenerate/hostile manifest. Mirrored
  golden tests on both platforms.
- **AEAD resume guard:** both senders reject an `fgcm`/`fgcm2` RESUME_REQUEST that carries a non-zero
  offset (AEAD restarts the file) instead of computing a wrong chunk index that only failed later at
  the GCM tag.

### Windows
- Fixed a crash: the resume error path used `Dispatchers.Main`, which the desktop module has no
  dispatcher for — the first failed resume threw an uncaught exception. The controller scope also
  gained a `SupervisorJob` so one failure can't kill discovery and all networking until restart.
- Escaped single quotes in the file-dialog PowerShell scripts (paths containing `'`); marked the
  daemons' listening sockets `@Volatile` so `stop()` reliably closes them.

### Android
- History no longer wipes on a single malformed record (per-record parse); receiver aborts instead
  of silently skipping a file whose output stream can't open; `TransferDaemon.start()` is synchronized
  so concurrent callers can't leak the Wi-Fi/multicast locks; fixed a dead unpair-broadcast receiver,
  a Settings-tab crash on a malformed saved folder URI, and a crash when dismissing the history sheet
  mid-resume.

### Website
- Download-link `aria-label` matches its visible state (no "download" announced on a disabled card);
  privacy page stamps the year dynamically. All on-page version strings updated to 3.5.1.

---

## 3.5.0 — Android + Windows (authenticated file stream v2, admission proof, stronger PIN)

Both platforms report **3.5.0**. Security-focused release building on 3.4.0's anti-downgrade pins.

### Crypto & protocol
- **CBC clipboard retired** on both platforms: every clipboard send is now ClipboardSeal (AES-GCM);
  the legacy CBC path is `@Deprecated(ERROR)` and non-sealed payloads are rejected.
- **FileSeal v2 (FGCM2):** per-session key (`HKDF(pairKey, "file-aead-v2"||sessionId)`) with
  session/file/chunk-bound AAD for splice/replay protection; negotiated via the `fgcm2` flag, falling
  back to v1 when a peer doesn't advertise it.
- **AUTH2 admission proof:** a receiver challenges an AUTH2-advertising sender
  (`OFFER_CHALLENGE`/`OFFER_PROOF`, HMAC over a nonce) before showing the incoming-file popup, closing
  an IP-spoof hole.
- **pv2 pairing PIN:** the SAS is derived from `HKDF(sharedSecret, "clipshare-pair-pin-v2")` instead
  of the AES key's first bytes.
- Discovery capability string is now `GCM1,FGCM1,FGCM2,AUTH2` on both platforms.

### Build & release
- R8 + CI validation; release signing via `keystore.properties` (no debug-signing fallback).

---

## 3.4.0 — Android + Windows (downgrade resistance, localization & release hardening)

Both platforms now report **3.4.0**.

### Anti-downgrade pins (security, both platforms)
- Capability advertisement rides the **unauthenticated** UDP discovery, so an on-path attacker could
  previously strip the `GCM1` / `FGCM1` token and force two AEAD-capable peers back onto the weaker
  path (malleable CBC clipboard / plaintext file bytes). We now **pin** a peer (TOFU, keyed by IP)
  the first time it negotiates an authenticated channel, and afterwards **fail closed** — a pinned
  peer that later drops the capability is treated as a downgrade attempt and refused, rather than
  silently downgraded. Clipboard and file streams both enforce it, on both Android and Windows; pins
  are cleared on unpair so a deliberate re-pair resets the trust-on-first-use state.
- The seal/plaintext/block decision is a small pure `DowngradePolicy` mirrored byte-for-byte on both
  clients and covered by host-JVM unit tests.

### Localization (Android)
- Completed the translation of the **94 previously-untranslated strings** (the first-launch tour,
  hotspot dialog, NFC guidance, status tiles, paired-devices and verify copy) across **all 9 locales**
  (de, es, fr, in, ja, pt, ru, zh-rCN, zh-rTW) — each locale now carries the full 504-string set.

### Cross-platform parity & robustness
- Windows now parses the file-offer `MANIFEST` line on the **last** `:` (extracted to a tested
  `ManifestCodec`), matching Android's `lastIndexOf(':')` so a filename containing `:` round-trips.

### Build, test & release
- **R8 enabled** for the Android release build (`isMinifyEnabled` + `isShrinkResources`) with a
  defensive keep set (Tink/EncryptedSharedPreferences, ML Kit, ZXing, Room). Safe here because all
  JSON is `org.json` with string-literal keys (no reflective (de)serialization) and there is no
  dynamic `getIdentifier`/`Class.forName` lookup. *Smoke-test the release variant on a device before a
  Play upload — R8 stripping can only be fully validated at runtime.*
- Added a **GitHub Actions CI** workflow that runs the Windows-module golden-vector tests, the Android
  unit tests, and both the debug and release (R8) Android builds on every push/PR.
- Repo cleanup: removed unreferenced `empty*.jar` / `empty.txt` / `dummy_app/` and the stale duplicate
  `installer.iss` (the live installer is `ClipboardShare.iss`).

### Accessibility (Android)
- Central TalkBack semantics on the shared components: tappable `GlassCard` surfaces (bento tiles,
  list rows) announce as **buttons**, `SegmentedControl` exposes a **tab** selection group, and the
  settings switch rows are a single merged **switch** target (label + on/off state). A full
  screen-by-screen TalkBack sweep of the bespoke screens remains a follow-up.

---

## 3.3.0 — Android + Windows (security hardening & encrypted file transfer)

Both platforms now report **3.3.0**.

### Encrypted LAN file transfer (new)
- **`FileSeal`** seals the bulk LAN file stream with **AES-256-GCM per 64 KiB chunk** (HKDF
  `file-aead-v1`; record `[u32be len][12B nonce][ct]`; AAD = chunk index), closing the gap where file
  bytes previously crossed the wire in the clear (clipboard text was already authenticated).
- Negotiated out-of-OFFER: peers advertise `FGCM1` in discovery and the receiver opts in with an
  `fgcm` flag on `ACCEPT_OFFER` / `RESUME_REQUEST`. Legacy peers and the off-grid (ECDH-token) path
  stay plaintext, so nothing breaks. Ported byte-identically to Windows and pinned by a shared
  cross-platform golden vector (`FileSealTest` / `FileSealVectorTest`).

### Security & robustness (Android)
- `ClipboardReaderActivity` / `ClipboardProxyActivity` are no longer `exported` — closes a hole that
  let any local app trigger a clipboard read-and-push. Removed the unused `REQUEST_INSTALL_PACKAGES`
  permission, un-exported `BootReceiver`, and enabled `supportsRtl`.
- `TransferDaemon.stop()` / `PairingDaemon.stop()` release the wake / Wi-Fi / multicast locks on
  Power Off (previously held for the whole process lifetime). Plus `@Volatile` cross-thread fixes, a
  guarded `unregisterReceiver`, and a carrier-aware auto-resume guard for off-grid sessions.

### Build & UX
- Release **signing-config** scaffold (gitignored `keystore.properties`).
- Localized several hardcoded UI strings and added TalkBack `Role` semantics to shared controls.

---

## 3.2.0 — Android + Windows (release alignment & repo cleanup)

Version-alignment release: Android and Windows now both report **3.2.0**. Previously the
version strings had drifted — Android sat at `3.0.3`, the Windows module disagreed with
itself (Gradle `version 3.0.3` vs Compose `packageVersion 3.1.0` vs installer `3.1.0`),
and the marketing site mixed `3.0.3` / `3.1.0`. All are now `3.2.0`.

### Housekeeping (post laptop-migration)
- Removed a stray UTF-16 `build_info.log` from the repo root that had leaked old-machine
  absolute paths.
- Fixed the Windows launcher (`Run JosephClipboard.bat`): it pointed at a stale `2.0.0`
  jar under the pre-redirect `build/` directory; it now resolves the built uber-jar under
  `build2/compose/jars/`.
- Repaired the Android instrumented test that still asserted the pre-rebrand
  `com.example.josephclipboardclient` application id (the app is now
  `com.EntropicSoftwareLab.ClipboardShare`).

No transport, protocol, or behavioural changes in this release.

---

## 3.0 — Android (in-app tutorial refinements)

These changes target the in-app guided tutorials only (no transport/behaviour changes).

### Send-Files tutorial (steps 5–7) — real-interface mocks
- **Step 5 (Send Via):** the fake chooser is now anchored to the **bottom** of the screen like the real bottom sheet, and the tooltip is **pinned to the top** (previously a popup floating in the middle).
- **Step 6 (Connected Devices):** now mocks the actual "Send to Devices" interface — the **selected-files row** (as in step 2) above a **paired-device card** (`tutorial_placeholder_connected_devices.xml`).
- **Step 7 (NFC / QR):** now mocks the "Off-Grid Send" screen — title + a **large QR** + NFC chip (`tutorial_placeholder_offgrid_qr.xml`).

### Receive tutorial
- After the scanner tip (**step 3**), the non-forced receive tutorial now **finishes the scanner activity and returns to the Receive screen** instead of stranding the user on the live camera.

### System-Status tutorial
- The **step-3 device card** is now the **same size as the step-4 card** (the placeholder reserves the Pair button's height with an invisible button) — `tutorial_placeholder_device.xml`.

### First-launch forced tour (22-step)
- Segment-final steps **13 / 17 / 19** now read **"Next"** instead of "Done" (they chain into the next tab; standalone per-tab tutorials still read "Done").
- Tapping the **non-spotlight (dim) area now advances** to the next step in the forced tour too — previously the forced tour ignored outside taps. (Per-tab tutorials already advanced on outside tap.)

### Tutorial engine
- New `TutorialStep` capabilities in `TutorialOverlayView.kt`:
  - `spotlightBottomAnchorMarginDp` — slide a fixed-height spotlight to the screen bottom (used by the Send-Via sheet mock).
  - `tooltipPinTopMarginDp` — pin the tooltip near the top of the screen.

---

## 3.0 — Windows (desktop)

### Tutorial parity (ported from Android, adapted for desktop)
- **System-Status step 3** device card resized to match step 4 (`TutorialPlaceholderDevice` reserves the Pair-button height).
- **Send step 6** now mocks the Connected-Devices interface (selected-files row + device card).
- **Send step 5 (Send Via)** mock kept **centered** to mirror the real Windows centered `Dialog` (not a bottom sheet), with the tooltip lifted to the top.
- **Send step 7** is **QR-only** — all NFC wording removed (Windows has no NFC hardware).
- Outside-tap-to-advance and the standalone per-screen tutorials already match Android; Windows has no Receive tab / QR scanner / forced 22-step tour, so those Android-only tutorial changes have no Windows counterpart.

### Functional QR sending (NFC excluded)
- Windows can now act as an **off-grid QR sender**: it shows a QR encoding an off-grid payload with its LAN address; an Android phone scans it and receives the selected files over the shared LAN.
- **NFC is intentionally not available** on Windows (no hardware); **QR is**.
- The "Send Via → QR" option is enabled and opens a QR-display screen.

> Interop note: the Windows QR sender targets the **LAN-first** off-grid path (same network as the Android receiver). Wi-Fi Direct / Bluetooth / hotspot transport tiers remain Android-only. Cross-device interop is verified against the Android receiver's protocol but requires two physical devices on the same network to fully validate.
