# Changelog

Notable changes leading into the **3.0** releases (Android `3.0.2` / Windows `3.0.2`).

---

## 3.6.5 — Android (build 27) — download page, scrollable send-text

### Changed
- **"Windows Version" screen redesigned** as a "Download Clipboard Share for Windows" page with
  a button that opens the website download section in the browser.
- **Send-text dialog** capped at 6 visible lines and scrolls internally instead of growing
  the dialog off-screen.
- New `download_windows_heading`, `download_windows_blurb`, `download_open_website` strings
  added to all 10 locale files.

## 3.6.3 — Windows (open-original-file, portrait window, download page, scrollable send-text)

### Changed
- **Open / Open Folder for sent files** now resolves the original source path (not the
  receive folder). Outgoing records store source paths; legacy records fall back gracefully.
- **Floating window is portrait** (9:19.5, ~80% of monitor height) instead of landscape,
  mirroring the Android app's shape. Corner-drag preserves the ratio; minimum width
  guardrail keeps the nav rail usable.
- **"Android Version" screen redesigned** as a "Download Clipboard Share for Android" page
  with a button that opens the website download section in the browser.
- **Add-Text dialog** fixed at 140 dp height and scrolls internally.
- New `download_android_heading`, `download_android_blurb`, `download_open_website`
  translation keys added to all locale blocks.

## 3.6.4 — Android (build 26) — tutorial rework, Lite-first default

### Changed
- **First-launch tutorial reworked.** The per-page pro tutorials that auto-fired on first open of
  each tab are removed. The tutorial is still reachable from each screen's "How to use" chip.
- **Fresh installs start in Lite mode.** The full Pro interface is one toggle away but no longer
  the default for new users.
- **First Lite→Pro switch triggers the guided tour.** The 7-step tour (previously 6) walks through
  every main screen, highlights the "How to use" chip, and ends on a new 7th step that opens the
  power menu to show the Lite/Pro toggle with a brief explanation of what each mode offers.
- New `tour_design_mode` string added to all 10 locale files.

## 3.6.2 — Windows (fixed-ratio window sizing)

### Changed
- **Non-fullscreen window uses a proportional size.** The floating window defaults to 70% of the
  monitor's work area (same aspect ratio, smaller) instead of the stock 800×600, so it always looks
  like a smaller version of the maximized layout.
- **Resize locked to the fixed aspect ratio.** Four corner drag-handles are the only resize
  mechanism (native resize border disabled); dragging any corner preserves the window's proportions
  at every frame. Minimum size is 50% of the default floating width.

## 3.6.3 — Android (receive-path data safety, Android 10 storage, cleanup)

Fixes the confirmed file-handling bugs from the pre-tester audit. No wire-protocol, crypto or
pairing changes.

### Fixed
- **Same-name files are never overwritten or deleted.** A received file whose name already exists
  at the destination is saved as "name (1).ext" (the old promote deleted the existing file
  unprompted); history points at the name actually on disk, and "Open" finds it.
- **Android 10 (API 29) receives work.** Files stage app-privately and publish through MediaStore
  (raw public-folder writes fail under scoped storage); history "Open" resolves published files
  via MediaStore instead of raw paths that Android 10 can't read.
- **Resume corruption closed.** The save destination is pinned per transfer (changing the save
  folder mid-transfer corrupted resumed files), the resume offset is read from the real partial
  file, and resuming an off-grid transfer keeps the delivery-confirmation guarantee.
- **Concurrent receives can't collide.** Two simultaneous transfers carrying the same filename no
  longer stream into one shared temp file; a double resume (manual + automatic) can't run twice.
- **Empty-file edge cases.** Providers that don't report a file size no longer cause silent
  0-byte sends; genuinely empty files aren't discarded on name collisions.

### Changed
- Theme toggle is a two-state Light/Dark slider; tapping the side already showing (while following
  the system) now pins it. Internal cleanup: dead code removed, duplicated scaffolds unified into
  shared helpers, imports normalized.

## 3.6.1 — Windows (send-dialog race fix, discovery tuning)

### Fixed
- **Send dialog no longer drops picked files.** A momentary empty blink of the device list while
  clicking Send could land the click on "Go to Pairing" and discard the selection; the button now
  waits out a 2-second grace period before converting.

### Changed
- Discovery no longer recomputes the local IP for every received packet (cached briefly);
  clipboard fallback polling relaxed from 300 ms to 1 s (event-driven detection remains instant);
  QR encode failures are logged to `diagnostics.log`.

## 3.6.0 — Windows (serif voice, glass toggles, in-menu theme switch, receiving pill)

Ports the Android 3.6.1/3.6.2 UI language to the desktop client and hardens the installer. Android
unchanged. A UI layer over the existing engine — no wire-protocol, crypto or pairing changes.

### Added
- **In-menu theme switch.** The power menu now carries a Light / System / Dark segmented control,
  and the choice persists in `config.json` — previously the theme reset to System on every launch.
- **Receiving pill.** While files are incoming and the transfer dialog is closed, a small animated
  "Receiving" chip (liquid ring + staggered letters, ported from the Android receiving loader)
  floats at the top of every screen; clicking it jumps to the running transfer. Grounded in the
  live receive socket, so a stale history row can never pin it on.

### Changed
- **Serif type voice.** The whole type ramp moves to the platform serif at the Android 3.6.2
  weights; numeric/data text stays IBM Plex Sans with tabular figures so live values don't reflow.
- **Glass sliders.** Every toggle (Settings, System Status, Clipboard accept) is now the sliding
  glass switch from Android — red left = off, green right = on — replacing the stock Material switch.
- **Installer hardening.** `PrivilegesRequired=admin` is explicit (the firewall rules need the
  elevated token); shortcut creation moved to `[Code]` so a Controlled Folder Access / AV block on
  the Start Menu folder no longer aborts the whole install, with matching uninstall cleanup.

## 3.6.2 — Android (Lite design pass, part 2 + fixes)

Continues the 3.6.1 redesign — reveal-animated Lite navigation, a serif type voice, a restyled power
menu with an in-app theme switch, and a redesigned receiving animation — and repairs the structural
issues found auditing that work. Android bumps to **3.6.2** (versionCode 24); Windows unchanged. Still
a UI layer over the existing engine — no wire-protocol, crypto or pairing changes.

### Added
- **In-menu theme switch.** The power menu now carries a Light / System / Dark control alongside the
  Lite/Pro toggle, so appearance no longer lives only in Settings — and "System" (follow the phone)
  stays reachable.
- **Reveal navigation.** Lite hub cards open Send / Receive / Clipboard with a circular-reveal
  animation from the tapped card.

### Changed
- **Serif type voice** across the app, and a redesigned liquid "Receiving" animation on both receive
  screens.
- **Pro Clipboard** adopts the Lite card layout; **Push Current Clipboard** stays available beneath the
  recent-copies tray.

### Fixed
- Power-menu sheet could render beneath its own scrim and swallow taps (elevation was dropped from an
  `<include>`); it now sits above the scrim reliably.
- Lite clipboard "Rescan" pills were inert — they now flush discovery and repopulate the device list.
- Auto-sync device count no longer includes nearby-but-unpaired devices.
- Rapid double-taps on a Lite hub card no longer stack duplicate screens, and taps on an open child
  screen no longer fall through to the hub beneath.
- The Lite device tray's bounce hint no longer loops forever or thrashes during drags.
- Theme toggle no longer arms a phantom "rebooting" overlay when the resolved appearance doesn't change.
- Restored the two-option slider's external-state sync (it could stick on the wrong side) and dropped
  the input debounce that lagged real toggles.
- New Lite strings (hub prompt/subtitles, "Paired &amp; Nearby Devices", menu section headers) are
  localised across all 10 languages instead of hardcoded English.

---

## 3.6.1 — Android (Lite mode design pass)

A visual refinement pass over Lite mode. Switching Basic ⇄ Pro now animates in place instead of
restarting the app, every on/off toggle shares one glass slider design, and the Lite hub is laid out
as a grouped tile tray. Android bumps to **3.6.1** (versionCode 23); Windows is unchanged at 3.5.6.
Still no wire-protocol, crypto or pairing changes — a UI layer over the existing engine.

### Changed
- **In-place Basic/Pro switch.** Flipping the power-menu toggle crossfades the shell in place — the
  menu stays open and there's no full restart.
- **Glass sliders everywhere.** Every on/off toggle (Basic/Pro, clipboard auto-sync, accept-incoming,
  discoverable, and the settings toggles) now uses one sliding glass design — transparent fill with an
  accent outline, matching the pairing button. Green for on/Lite, red for off/Pro. The old striped
  thumb is gone.
- **Lite hub as a tray.** Send Files, Receive Files and Clipboard sit inside a grouped rounded frame —
  Send/Receive across the top, Clipboard below at half their height.
- **Lite send flow.** A shared network-choice tray with a Next button, one 3-state per-device button
  (Pair → Send → Sending), and a bottom-bar Cancel/Done on the QR screen.
- **Nav alignment.** The Lite bottom bar and power button line up with Pro's positions, and switching
  modes no longer stretches the navigation bar.

---

## 3.6.0 — Android (Lite mode)

A new **Lite** design is now the default on Android: a simple hub with three big actions — Send,
Receive, Clipboard — and no bottom navigation, for people who found the full app too dense. The
original interface is still there as **Pro**, reached from a Basic/Pro switch in the power menu (which
replaces the old Connection Diagnostics tile — diagnostics moved to Settings → Debugging). Android
bumps to **3.6.0** (versionCode 22); Windows is unchanged at 3.5.6. No wire-protocol, crypto or
pairing changes — this is a UI layer over the existing engine.

### Added
- **Lite mode.** Stacked Send / Receive / Clipboard hub, a streamlined send flow (pick files → same
  or different network → per-device Send/Pair tray), a calm looping Receive screen, and a Clipboard
  screen pairing recent copies with a connected-devices list. Switch to Pro any time from the power menu.
- **Live "Receiving…" indicator** on the Receive screen (Pro and Lite) while a transfer is incoming.

### Changed
- The Pro clipboard screen's **Push current clipboard** button moved to the bottom, matching Windows.

---

## 3.5.6 — Windows · 3.5.3 — Android (pairing screen layout)

The pairing screen's **Rescan** button now lives inside the device list as one scrolling section,
directly below the device cards, instead of a fixed bar pinned beneath the list. On short screens
that pinned bar left too little height to show even a single device card; folding it into the list
returns that space to the cards. Windows bumps to **3.5.6**, Android to **3.5.3** (versionCode 20).
No wire-protocol, crypto or pairing changes.

### Fixed
- **Device cards fit on short screens.** Reclaiming the rescan bar's fixed height lets the list
  render at least one full card where it used to collapse to nothing. The Rescan / Reboot pill now
  scrolls with the cards and sits directly below them, on both clients.

---

## 3.5.5 — Windows (folder send)

A Windows-only release carrying the sender/receiver half of the folder-send fix (see 3.5.2 below);
features, crypto and pairing are unchanged. Android stays on 3.5.2.

### Fixed
- **Sending a folder works.** The sender handed a picked directory straight to the byte pump, where
  `FileInputStream(dir)` threw — the send stalled on "resume" at 0% and never started. Folders are now
  walked into their files before the offer (LAN and off-grid QR paths). Two files that flatten to the
  same leaf name (e.g. `a.txt` under two subfolders) are de-collided on the receiver so neither
  overwrites the other, on both the receive and plaintext-resume paths.

---

## 3.5.2 — Android (transfer/receive fixes)

Android bumps to **3.5.2** (versionCode 19). Three transfer bugs reported on the Android client;
the folder-send fix's sender/receiver half also ships as Windows **3.5.5** (above).

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
