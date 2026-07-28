# OffBridge — Security Assessment & Market Plausibility

**Date:** 2026-07-11
**Scope:** Android client (`app/`) and Windows Compose-Desktop module (`Window Ver/`), at commit `331a71f` (Windows 3.6.0) with the current Android working tree (3.6.3). Read-only audit; no code was changed.
**Method:** manual source review of the crypto, pairing, network, input-validation, storage, and platform-hardening surfaces, cross-checked against the deferred-item catalog in `release/AUDIT-3.5.1.md`. The Windows 3.6.0 UI release was verified to make no crypto, wire, or pairing changes.

---

# Part A — Security assessment

## A.1 Headline

**No Critical or High findings.** The cryptographic core is correct, consistent across both platforms, and locked down by cross-platform test vectors. The residual risk lives on the *unauthenticated* LAN surfaces (pairing and discovery ports) and in a few Windows platform-hardening details. Every security-relevant item that was deferred in the last formal audit has since been implemented in code.

This is an above-average security posture for an app of this size. The findings below are hardening opportunities, not open doors.

## A.2 What is done well (the strengths)

- **Authenticated encryption on every live path.** Clipboard, file streams, and the BLE control channel are all AES-256-GCM. The old AES-CBC path is retired and compile-fenced (`@Deprecated(level = ERROR)`); the receive path refuses any non-sealed payload rather than falling back.
- **Correct key derivation.** Pairing key = `SHA-256(ECDH-P256 shared secret)`; per-purpose subkeys via HKDF-SHA256. The KDF is byte-identical across all four implementations and pinned by golden-vector tests (`ClipboardSealVectorTest`, `FileSealVectorTest`, `ControlPlaneVectorTest`, `Auth2VectorTest`, `CryptoEnginePinV2Test`).
- **Real replay protection.** Control channel uses strictly monotonic per-direction sequence numbers with distinct TX/RX nonce salts. Clipboard uses a timestamp freshness window plus a bounded per-peer nonce cache keyed on the blob's own timestamp. Files bind position (and, in v2, session and file index) into the GCM AAD, so truncation, reorder, and cross-session splicing all fail the tag.
- **Fail-closed downgrade protection.** Capability advertisement rides unauthenticated UDP discovery, so the app pins each negotiated capability (clipboard-GCM, file-GCM v1/v2, AUTH2) on first use (trust-on-first-use) and **blocks** if a previously-pinned peer stops advertising it. Pins clear on unpair for a clean re-pair.
- **Authenticated pairing and admission.** The 6-digit SAS (short authentication string) genuinely protects the ECDH — a man-in-the-middle produces two different PINs, and the human comparison catches it. Unpair requires an HMAC proof over a fresh nonce. File offers from an AUTH2-capable sender require an HMAC challenge/response before the incoming-file prompt appears. All MAC/token/PIN comparisons are constant-time.
- **Thorough untrusted-input hardening.** Filename sanitization closes path traversal and Windows reserved-device-name capture (byte-identical on both platforms, tested); manifest parsing is defensive and identical across all three parse sites; resume offsets are bounds-checked; every network read is length-capped; a hostile length field can't cause an over-allocation.
- **Secrets encrypted at rest, nothing leaked to logs.** Android `EncryptedSharedPreferences`, Windows DPAPI. Diagnostic logs record IPs, device names, session IDs, and drop reasons — never keys, PINs, filenames, or clipboard contents.
- **Minimal, correctly-guarded Android attack surface.** Only the launcher, the share-target activity, the QS tile (permission-guarded), and the NFC service (permission-guarded) are exported. The historical API 29–32 pairing-receiver export hole is closed (`RECEIVER_NOT_EXPORTED` on all API levels).

## A.3 Findings (full detail)

| # | Severity | Area | Issue | Status |
|---|---|---|---|---|
| 1 | Medium | LAN DoS (Android) | `PairingDaemon` has no rate-limit or pending-handler cap | Open |
| 2 | Medium | Network exposure (Windows) | Installer opens 4 ports with firewall `profile=any` (incl. Public) | Open, documented tradeoff |
| 3 | Medium | Memory amplification (Windows) | `ClipboardDaemon` buffers up to 16 MiB from an unpaired peer before the key check | Open |
| 4 | Low–Med | Code-loading (Windows) | Folder picker compiles/loads a C# helper DLL from `%TEMP%` (plant / TOCTOU) | Open |
| 5 | Low–Med | LAN annoyance (Android) | Unauthenticated `FILEBRIDGE_RESUME_REQUEST` posts a notification | Open |
| 6 | Low | Crypto parity (Windows) | `deriveSecrets` doesn't wrap an off-curve peer key (Android does) | Open |
| 7 | Low | Crypto legacy | SAS PIN v1 (pre-3.5.0 peers) leaks ~20 key bits | Mitigated by v2 |
| 8 | Low | Data-at-rest privacy | `config.json` stores paired IPs/device-names in plaintext (keys are DPAPI-wrapped) | By design |
| 9 | Low | Platform hardening | Android `allowBackup="true"` (sensitive stores are excluded) | Minor |
| 10 | Info | Crypto DiD | BLE control-frame header not bound into GCM AAD | Defense-in-depth |
| 11 | Info | Dependencies | `androidx.security-crypto 1.1.0` is Google-deprecated / maintenance-only | Plan migration |

### Detail and recommended fix

**1 — Android pairing port has no rate limiting (Medium).** The Windows `PairingDaemon` caps concurrent handlers (8) and throttles repeat connects per source IP (2s). The Android `PairingDaemon` has neither: every inbound `PAIR_REQ` spawns a coroutine and raises `PairingConfirmationActivity`, which is declared `showOnLockScreen="true" turnScreenOn="true"`. An attacker on the LAN sending from many (spoofed) source IPs can repeatedly wake the screen and spam pairing prompts — with a residual risk that a fatigued user taps Accept. *Fix: mirror the Windows caps (per-IP throttle + pending-handler ceiling) on Android.*

**2 — Windows firewall `profile=any` on four ports (Medium, documented tradeoff).** The installer opens TCP 65432/65433/65435 and UDP 50001 for all firewall profiles including Public (`OffBridge.iss:116-125`). The code comment justifies it — the Wi-Fi Direct group-owner interface is classified Public, and narrowing to Private would break the core off-grid path. The effect is that on an untrusted public network the PC also accepts inbound pairing/clipboard/file connections. The trust gate and AUTH2 limit what an attacker can *do*, but the listeners are reachable. *Fix (harder): scope the rules to the group-owner adapter once it can be reliably identified, rather than opening Public globally.*

**3 — Windows clipboard daemon buffers before authenticating (Medium).** `ClipboardDaemon.kt:82-99` reads the entire payload (up to a 16 MiB cap) into memory *before* looking up the pairing key. An unpaired attacker can force the PC to buffer 16 MiB per connection with no authentication, and the file/clipboard daemons spawn one handler thread per connection with no concurrency cap. Android does this correctly — it checks the key first and drops unpaired peers before reading. *Fix: move the key lookup ahead of the bulk read on Windows and cap concurrent handlers on both daemons.*

**4 — Windows folder picker loads a DLL from %TEMP% (Low–Medium).** `FileDialogHelper.openDirectoryPicker` compiles `ComposeFolderPicker.dll` into `%TEMP%` only if it isn't already there, then `Add-Type -Path`s it. A same-user process that pre-plants that file at the predictable path gets its code loaded into the app on the next folder pick (a classic TOCTOU / DLL-plant). Same-user scope limits severity, but loading executable code from a predictable temp path is a pattern to remove. *Fix: compile to a per-run random path, or ship the helper as a signed asset.*

**5 — Unauthenticated resume-request notification (Low–Medium).** The Android UDP discovery listener posts a "resume request" notification on receipt of `FILEBRIDGE_RESUME_REQUEST` with no trust check (`LanDiscovery.kt:120-141`). Any LAN host can spam these notifications. *Fix: trust-gate or drop this path.*

**6 — Windows off-curve key wrap parity (Low).** Android wraps a malformed/off-curve peer public key and rethrows a clean exception; the Windows `deriveSecrets` doesn't, so a hostile key throws a raw `InvalidKeySpecException`. It's caught by the pairing handler's outer try, so the impact is a dropped pairing attempt, not a crash — but it's an unfixed cross-platform drift worth closing for parity.

**7 — SAS PIN v1 residual (Low, mitigated).** v1 derived the 6-digit PIN from the first 4 bytes of the AES key, disclosing ~20 bits on-screen. v2 (independent HKDF label) fixes this and is always advertised by current builds, so v1 only triggers against a genuinely pre-3.5.0 peer. 236 bits of key remain — not a practical break. Sunset v1 once both stores are updated.

**8, 9, 10, 11 — Low/Info.** `config.json` reveals which IPs/devices you've paired with (keys themselves are DPAPI-wrapped) — a minor privacy leak readable by any same-user process. `allowBackup="true"` is fine because the vault, Room DB, and TOFU pins are excluded from backup, but a security-focused release could set it `false`. Binding the BLE control-frame `type` byte into GCM AAD would close a theoretical type-confusion vector (the `seq` and `len` fields are already tamper-evident). And `androidx.security-crypto` is deprecated by Google — no active vulnerability, but it won't receive fixes, so plan a migration to Tink or the Keystore directly.

## A.4 Threat-model notes

- **On-network LAN attacker:** can spoof discovery entries (annoyance only — content is GCM-sealed and the trust gate + AUTH2 govern offers), and can exercise findings 1, 3, and 5. Cannot read or forge clipboard/file content without the pairing key.
- **Off-grid path is plaintext at the application layer, by design.** On the QR/Wi-Fi-Direct path the file bytes are not end-to-end encrypted; confidentiality reduces to *who can see the QR or make the tap*. The QR is a **bearer credential** — it carries the Wi-Fi PSK and the admission token together, so anyone who photographs or shoulder-surfs it can join and receive the files, and a second authorized member of the same hotspot could sniff the stream. For the intended use (in-person, single-use, ephemeral group) this is a reasonable and clearly-documented tradeoff. If stronger guarantees are ever wanted, wrapping the off-grid stream in the same `FileSeal` AEAD (keyed by the ECDH secret the two sides already derive) would remove the bearer/link-layer exposure without changing the user flow.
- **NFC:** while a send is staged, the HCE service serves the full credential payload to any reader that selects the app's AID — bounded to the active-send proximity window, and it returns "not found" when nothing is staged.

## A.5 Previously-deferred items — now resolved

Verified against current source. Everything security-relevant that `release/AUDIT-3.5.1.md` deferred is implemented:

| Item | Status | Evidence |
|---|---|---|
| AUTH2 / FGCM2 carried no TOFU pin | Fixed (both) | `SecurityVault.pinAuth2/pinFileGcmV2`, `FileTransferServer.kt:359-394` |
| Exported pairing receiver (API 29–32) | Fixed | `PairingDaemon` registers `RECEIVER_NOT_EXPORTED` on all API levels |
| Sender file-key null → silent plaintext fallback | Fixed | `TransferKeys.kt` throws instead of returning null |
| Android filename sanitization | Fixed | `TransferUtils.sanitizeIncomingFileName` |
| Clipboard outer nonce map unbounded | Fixed | `ClipboardSeal.recordNonce` sweeps aged-out peers |
| CBC stream helpers retired | Fixed | removed; `encryptString` `@Deprecated(ERROR)` |
| Off-grid resume trust-gate (XP-05) | Fixed (both) | `FileTransferServer.kt:87-100` admits a resume matching a live off-grid session — **note the doc `AUDIT-3.5.1.md:38` still lists this as deferred and should be updated** |

## A.6 Prioritized remediation backlog

1. **Medium** — add per-IP throttle + pending-handler cap to the Android `PairingDaemon`.
2. **Medium** — move the pairing-key check ahead of the 16 MiB read in the Windows `ClipboardDaemon`, and cap concurrent handlers on the file/clipboard daemons.
3. **Medium** — narrow the Windows firewall rules off `profile=any` (scope to the group-owner adapter).
4. **Low–Medium** — compile/load the Windows folder-picker helper from a per-run random path (or ship it signed); trust-gate the resume-request notification.
5. **Low** — port the off-curve key wrap to Windows; set `allowBackup="false"`; plan the `androidx.security-crypto` migration; sunset SAS PIN v1.
6. **Optional hardening** — bind the BLE control-frame header into AAD; AEAD-wrap the off-grid stream if bearer-QR / link-layer exposure is in scope.

---

# Part B — Market plausibility

## B.1 The category and the incumbents

"Move files and sync the clipboard between my phone and my PC without the cloud" is a crowded, mostly-free category. The relevant competitors split into two groups:

**LAN-only (need a shared Wi-Fi network):**
- **LocalSend** — the category leader for offline file transfer. Open-source, cross-platform (Windows, macOS, Linux, Android, iOS), no accounts, no cloud. Files only, same-network only.
- **KDE Connect** — clipboard *and* files *and* remote control, open-source, but Linux-desktop-centric (its Windows support is rougher) and designed for your own devices on the same network.
- **ClipCascade / CrossPaste** — clipboard-focused, LAN or self-hosted, cross-platform, open-source.
- **Snapdrop / PairDrop** — browser-based, same-network. (Snapdrop was acquired by LimeWire and drew privacy criticism for cloud handling; PairDrop is the community fork.)

**Off-network (no shared Wi-Fi needed):**
- **Google Quick Share** (formerly Nearby Share) — OS-level on Android + a Windows app, uses Bluetooth + Wi-Fi Direct so it doesn't need a shared network. But it's **files only** (no clipboard sync), Android + Windows only, tied into the Google account model, and carries device/screen-on limits (up to 8 nearby devices, screens on).

Sources are listed at the end.

## B.2 Where OffBridge actually differs

Lay the field on two axes — *does it need a shared network?* and *does it sync the clipboard as well as files?* — and OffBridge occupies a corner almost no one else does.

| | Shared network required? | Clipboard sync? | Files? | Platforms | Encryption story |
|---|---|---|---|---|---|
| **OffBridge** | **No** (off-grid QR/NFC) or LAN | **Yes** | Yes | Android, Windows | Authenticated (AES-256-GCM), pairing-gated |
| LocalSend | Yes | No | Yes | 5 platforms | TLS on LAN |
| KDE Connect | Yes | Yes | Yes | Linux-centric + others | TLS, device-paired |
| ClipCascade / CrossPaste | Yes (or self-host) | Yes | Some | Cross-platform | Encrypted / self-hosted |
| Quick Share | No (BT + Wi-Fi Direct) | **No** | Yes | Android, Windows | Google-managed |

The genuine, defensible combination is **off-grid + clipboard + files + strong pairing-based encryption in one app**. The LAN-only tools can't work without a network; Quick Share works off-network but doesn't touch the clipboard. Nobody in the free tier does all three at once. That's a real wedge — most useful in exactly the situations where the LAN tools fail: airports, hotels, conferences, field/industrial sites, guest networks that block peer-to-peer, or any "no shared Wi-Fi, no data" moment.

## B.3 The honest weaknesses

A differentiator isn't a moat. Three things work against it:

1. **Platform coverage.** Android + Windows only. LocalSend and the clipboard tools cover five platforms including iOS, macOS, and Linux. A meaningful slice of the audience wants phone↔Mac or cross-vendor, and OffBridge simply can't serve them today. This is the biggest ceiling on addressable market.
2. **Distribution and trust.** It isn't installable by a normal user right now: the Android release keystore exists but isn't wired into the build (`keystore.properties` is missing at the repo root, so release builds come out debug-signed), and the Windows installer is unsigned — it trips SmartScreen for everyone and is hard-blocked by Smart App Control on some Windows 11 machines. Every one of those friction points costs installs, and in a category where the leaders are one-click and reputation-established, an "unknown publisher" warning is a real conversion killer.
3. **Discoverability and maintenance signal.** The incumbents are open-source with large communities, active repos, and years of reputation. OffBridge is a solo effort with a private codebase. Off-grid transfer is also the hardest thing to get reliably right across the messy space of Android OEM Wi-Fi-Direct behavior — the very complexity that differentiates it is also its top support-cost and reliability risk.

## B.4 Go-to-market blockers (must clear before it can compete)

- **Android:** the release keystore exists (`clipboardshare_key.jks`) but isn't wired into the build — create `keystore.properties` at the repo root (per `release/keystore/KEYSTORE_BACKUP.md`) and back the key up to 2+ offline places; its certificate doesn't match the Play upload key on record, so the Play lane additionally needs the original keystore or a Play upload-key reset. Produce the Play store graphics (hard block).
- **Windows:** code-sign the installer (Azure Trusted Signing is the durable, low-cost individual route) to clear SmartScreen and Smart App Control.
- **Website:** fix the download link (it points at repo `OffBridge-Showcase`; the actual origin is `OffBridge` — a dead-link risk) and fill the `REPLACE_SITE_URL` placeholders.
- **Product:** fix the confirmed same-name data-loss bug and the Android 10 media-write bug before inviting a wide audience — a data-loss review on day one is very hard to recover from.

## B.5 Verdict — can it plausibly compete?

**Yes, in a niche, if the off-grid path is rock-solid and distribution gets unblocked — but not as a broad LocalSend replacement.**

The realistic positioning is not "better than LocalSend at everything," because on shared-network file transfer with five-platform reach, it isn't. The realistic positioning is: *the app you reach for when there's no shared network and no cloud, that also carries your clipboard across.* Lead the marketing with the off-grid clipboard+files combination and the in-person, no-account, no-internet story. Concede the LAN-only, cross-platform ground to the incumbents rather than competing there.

For that to land, three conditions have to be true: (1) off-grid transfer works reliably across common Android devices — the make-or-break reliability bet; (2) the signing/distribution friction is removed so the "unknown publisher" wall doesn't kill installs; and (3) the confirmed data-integrity bugs are fixed so early reviews aren't about lost files. The security foundation and engineering quality needed to earn user trust are already in place — which is the hard part to fake and the easy part to squander on a bad first impression.

---

## Sources (market)

- [Snapdrop vs LocalSend vs TransP2P (2026)](https://transp2p.com/blog/snapdrop-vs-localsend-vs-transp2p)
- [LocalSend alternatives — AlternativeTo](https://alternativeto.net/software/localsend/)
- [Open-source AirDrop alternatives better than LocalSend — MakeUseOf](https://www.makeuseof.com/airdrop-alternatives-better-than-localsend/)
- [KDE Connect alternatives — MobiKin](https://www.mobikin.com/android-backup/kde-connect-alternative.html)
- [Self-hosted clipboard sync (ClipCascade) — XDA](https://www.xda-developers.com/open-source-self-hosted-app-syncs-clipboard-across-devices/)
- [CrossPaste / cross-device clipboard — MakeUseOf](https://www.makeuseof.com/found-free-app-that-syncs-clipboard-across-every-device-own/)
- [Quick Share for Windows ⇄ Android — Google Support](https://support.google.com/android/answer/13801258?hl=en)
- [Quick Share — Wikipedia (capabilities & limits)](https://en.wikipedia.org/wiki/Quick_Share)
