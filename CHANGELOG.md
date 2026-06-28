# Changelog

Notable changes leading into the **3.0** releases (Android `3.0.2` / Windows `3.0.2`).

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
