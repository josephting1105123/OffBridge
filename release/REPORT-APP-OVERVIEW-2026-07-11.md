# ClipboardShare — Comprehensive App Report

**For:** Stakeholders and the CTO
**Date:** 2026-07-11
**Prepared from:** a full read-only audit of both clients (Android + Windows), the release kit, the test suites, and a live build/test run. Findings are cross-checked against the source; file references are given so anything here can be verified.

The report is layered: the Executive Summary is written for a non-technical reader; everything below it adds the engineering detail a CTO needs.

---

## 1. Executive summary

ClipboardShare is a cross-platform app that moves files and syncs the clipboard between an Android phone and a Windows PC. Its distinguishing feature is that it works **off-grid** — with no shared Wi-Fi and no internet — by standing up a direct radio link (Wi-Fi Direct / hotspot) that the two devices join via a scanned QR code or an NFC tap. It also does the ordinary thing (fast transfers over a shared LAN) and keeps the clipboard in sync both ways.

**Where it stands: a technically mature product held at the doorstep of launch by distribution logistics, not by code quality.** The engine is complete, the two clients are at feature parity on the things that matter, the cryptography is done properly, and both platforms build clean with all unit tests passing. What stops a normal person from installing it today is entirely outside the code: there is no release signing key generated yet for Android, the Windows installer is unsigned (so Windows warns or blocks it), and the store assets aren't produced. Separately, a small number of confirmed file-handling bugs should be fixed before a wide release because one of them can delete a user's existing file.

**Top three strengths**

1. **A genuinely differentiated feature.** Off-grid transfer (no shared network, no account, no cloud) is something most competitors don't do — see the market report. Combining that with two-way clipboard sync in one app is rare.
2. **Security done right.** Authenticated encryption everywhere on the live path, correct key handling, and defense-in-depth against downgrade attacks. An independent security sweep found no Critical or High issues.
3. **Real engineering discipline.** 160 passing unit tests across the two platforms, cross-platform "golden vector" tests that lock the two clients to the same wire format, and a clean codebase with no lingering `TODO`/`FIXME` debt.

**Top three risks**

1. **Distribution is unsolved.** No Android signing key exists yet (an irreversible, one-time gate for the Play Store), and the Windows installer is unsigned — which trips Windows SmartScreen for everyone and is hard-blocked by Smart App Control on some Windows 11 machines.
2. **A confirmed data-loss bug.** If a file arrives with the same name as one already in the user's chosen save folder, the existing file is deleted without asking. This is the single highest-priority fix.
3. **Narrow platform coverage and a solo maintainer.** Android + Windows only (no iOS, macOS, or Linux) in a category where the strongest free competitors cover five platforms. That caps the addressable audience and raises the bar for standing out.

**Maturity stage:** late pre-launch. The product works; the release process is half-finished.

---

## 2. What the app does — feature inventory

Nine subsystems make up the product. All nine are present and working on Android; Windows carries all of them except the two that need phone hardware (camera QR scanning and NFC).

| Subsystem | Android | Windows | Notes |
|---|---|---|---|
| Pairing & device discovery | Complete | Complete | ECDH key exchange with a 6-digit PIN you compare on both screens; LAN auto-discovery. |
| LAN file transfer | Complete | Complete | Resumable, encrypted, byte-identical wire format across platforms. |
| Off-grid QR / NFC transfer | Complete (flagship) | Host side only | Windows can host the QR + hotspot but can't scan (no camera) or tap (no NFC) — inherent to a desktop. |
| Clipboard sync | Complete | Complete | Two-way, encrypted; Android also has a Quick-Settings "Push to PC" tile. |
| Transfer history | Complete | Complete | Per-file progress, resume, backup that survives clear-data. |
| Tutorial / onboarding | Complete | Complete | Same spotlight tour on both; Android uses a real frosted-glass blur, Windows a dim scrim (a rendering-capability gap, below). |
| Settings | Complete | Complete | Theme, language, save-folder, discoverable/accept toggles, diagnostics. |
| Lite / Pro design mode | Complete | N/A | A UI-density toggle, **not** a paid tier — there is no billing or in-app-purchase code anywhere. Android-only. |
| Theming & i18n | Complete | Complete | Light/Dark, 10 languages, glass + serif design system on both. |

**Internationalization:** 10 languages on both platforms (English, Simplified & Traditional Chinese, Spanish, French, Japanese, German, Portuguese, Indonesian, Russian). The public privacy policy is also published in all 10.

### Cross-platform parity

As of the 2026-07-11 Windows 3.6.0 release, the two clients now share the same visual language (serif type voice, glass toggles, in-menu theme switch, animated "Receiving" pill). The remaining differences are deliberate, not gaps in progress:

- **Camera QR scan and NFC** — Android-only, because a desktop has neither.
- **Lite/Pro density mode** — Android-only.
- **Tutorial real-blur** — Android composites a live frosted snapshot; the Windows toolkit version (Compose Multiplatform 1.6.11) can't capture a layer to a bitmap, so it uses a dim scrim instead. Cosmetic.
- **"Android" promo tab (Windows)** — a static placeholder screen that just says "The mobile app is currently running." It occupies a top-level navigation slot without doing anything. Minor, but worth either wiring up (link to the phone app / a QR to download) or removing before launch.

---

## 3. Architecture (CTO layer)

**Two clients, one protocol.** The Android app (`com.EntropicSoftwareLab.ClipboardShare`) and the Windows Compose-Desktop module (`Window Ver/`, `com.joseph.clipboard.windows`) speak a shared, line-based UTF-8 wire protocol over four ports: UDP 50001 (discovery), TCP 65432 (clipboard), 65433 (file transfer / session control), 65435 (pairing). The two implementations are pinned to byte-identical framing by cross-platform golden-vector tests, so a change on one side that breaks interop fails the build.

**Transport ladder (off-grid).** The off-grid path is the sophisticated part. A sender-driven controller negotiates a connection over a Bluetooth LE control channel and walks a ladder of transports — Wi-Fi Direct, `WifiNetworkSpecifier`, LocalOnlyHotspot, SoftAP join — picking the one that works for the specific pair of devices and remembering what worked for next time. Telemetry (which tier won, why others failed) is recorded to a Room database and surfaced on a diagnostics screen. The Windows side has a matching BLE peripheral so a PC hosting a hotspot QR gets live status and coordinated retries instead of waiting blind for the phone to join.

**Cryptography stack.** Every live-path payload is authenticated encryption (AES-256-GCM): `ClipboardSeal` for clipboard blobs, `FileSeal` (v1 and a hardened per-session v2) for file streams, and `SessionCrypto` for the BLE control channel. Keys derive from an ECDH secret via HKDF-SHA256. The legacy AES-CBC path was retired and is now compile-fenced so it can't be reintroduced. Full detail is in the companion security report.

**Persistence — one correction to the internal architecture notes.** The user-facing transfer/clipboard history is stored as **JSON in SharedPreferences** (`TransferHistoryManager.kt:17`), not in Room. Room exists, but it holds the transport-ladder telemetry and off-grid session data, and acts as a secondary mirror of history. If any deck or doc claims "history is in Room," that's the mirror, not the source of truth. On Windows the equivalent is `config.json` plus in-memory history.

**Security at rest.** Android keeps pairing keys in `EncryptedSharedPreferences` (Keystore-backed); Windows wraps each key with DPAPI in `config.json`. No secrets are written to logs on either platform.

---

## 4. Engineering quality

### Build and test proof (run 2026-07-11 for this report)

Not asserted — actually run. Both platforms are green, including the current uncommitted Android working-tree changes (`assembleDebug` packaged them successfully):

| Platform | Command | Result |
|---|---|---|
| Android | `:app:testDebugUnitTest :app:assembleDebug` | **BUILD SUCCESSFUL** (16s). **85 unit tests, 0 failures, 0 skipped.** APK packaged. |
| Windows | `:window-ver:cleanTest :window-ver:test` (forced rerun) | **BUILD SUCCESSFUL**. **75 unit tests, 0 failures, 0 skipped.** |

### What the tests cover — and what they don't

The 160 tests are concentrated where correctness is unforgiving and cheap to test in pure JVM: crypto vectors (clipboard/file/control-plane/AUTH2/PIN), the wire protocol (manifest codec, transport descriptors, downgrade policy), filename sanitization, and BLE framing. This is the right place to spend test budget, and the cross-platform golden vectors are a real strength.

The untested surface is the framework- and radio-bound code: the transport-ladder state machines, Wi-Fi Direct / hotspot orchestration, Room DAOs, the boot receiver, and all UI. Most of this genuinely can't be unit-tested (it needs a device and live radios), but three items are worth closing before or shortly after launch because they're testable: the Room DAOs, the boot-receiver fail-safe, and getting the off-grid payload test into CI (it currently needs an emulator).

### CI

GitHub Actions builds on every push/PR: Windows module tests → Android unit tests → Android debug build → Android release build (for R8 validation only; the release APK is unsigned on CI because no key is present). CI does not build the Windows installer, does not build the Play AAB, and runs no emulator tests. Adequate for a small project; the gaps are known.

### Code hygiene

Clean. No live `TODO`/`FIXME`/`STOPSHIP` markers in either client. A short list of cosmetic cleanups worth doing before a code-facing audit:

- An orphaned "…Full translation support coming soon." string (`strings.xml` / `translation.json.txt`) left over from before full localization — unreferenced, but it reads badly if someone greps the resources.
- A stray NUL byte in `app/src/androidTest/.../OffGridPayloadTest.kt` (harmless residue from an earlier encoding cleanup).
- A small dead-code backlog (the never-wired Bluetooth-data transport tier, an unused preview adapter) catalogued in `release/AUDIT-3.5.1.md` — safe to delete, no runtime impact.

---

## 5. Version and release state

Both platforms are now on the 3.6.x line. A version-drift issue flagged earlier in the day (the Windows installer labeled 3.5.7 over a 3.5.6 app image) was **resolved** by the 3.6.0 release — the module and installer now agree.

| | Version | State |
|---|---|---|
| Android | **3.6.3 / versionCode 25** | Uncommitted 2-line bump in the working tree; committed HEAD is 3.6.2 / 24. |
| Windows | **3.6.0** | Committed and consistent across `build.gradle.kts` and the Inno installer. |

**One loose end: the docs lag the code.** Several release/marketing files still cite old versions — `release/RUNBOOK.md` (3.5.1), `release/play/STORE_LISTING.md` (3.5.1), and the website config (Android 3.6.1). Reconcile these before publishing; a CTO or reviewer reading the RUNBOOK today would see a version three releases stale.

---

## 6. Known functional issues (ranked by user impact)

These are confirmed and deferred, catalogued in `release/FOLLOWUPS-2026-07-06.md`. None has a code-level marker, so they're only discoverable through the release docs — which is exactly why they belong in this report.

1. **Data loss: same-name file in the save folder is deleted.** If the user picked a custom "Save media to" folder and a peer sends a file whose name already exists there, the receive path deletes the existing file before writing the new one — unprompted. **Fix before any wide release.** Highest priority.
2. **Photos/videos fail to save on Android 10.** On the minimum supported OS (API 29), writing received gallery media throws a permissions error and the transfer stalls. This affects the floor of supported devices.
3. **Media resume corruption if the save folder is changed mid-transfer.** Pause a media transfer, change the destination, resume → the finished file is missing its first chunk. Narrow scenario, but silent.
4. **Windows Send dialog can drop the queued files.** If the device list momentarily empties for one discovery poll while the Send dialog is open, the picked files are discarded.

**Resolved but mis-documented:** the off-grid "resume after a dropped connection" bug (XP-05) is **fixed in code on both platforms** — `FileTransferServer.kt:87-100` now admits a resume request that matches a live off-grid session (the same authenticated peer reconnecting on a new IP). The release doc `AUDIT-3.5.1.md:38` still lists it as deferred; that entry is stale and should be updated.

---

## 7. Recommendation — pre-launch punch list

In priority order:

**Must-fix before a public release**
1. Fix the same-name data-loss bug (#1 above) — resolve the save destination once per session and check the target folder before deleting anything.
2. Fix the Android 10 gallery-media write (#2) — this is the minSdk floor.
3. Generate and back up the Android release keystore, and enroll it in Play App Signing. This is irreversible and one-time; nothing ships on Android without it.
4. Solve Windows signing — Azure Trusted Signing (individual identity, ~$10/mo) is the durable fix; the free interim is submitting each build hash to Microsoft's malware-analysis portal for reputation.

**Needed for the store, not blocking a GitHub release**
5. Produce the Play graphics (icon, feature graphic, ≥4 screenshots) — Play hard-blocks publish without them.
6. Fix the website download repo mismatch (the download button points at `ClipboardShare-Showcase`; the actual repo origin is `Clipboard-Share`) and fill the `REPLACE_SITE_URL` placeholders in `sitemap.xml`/`robots.txt`.
7. Reconcile the stale version strings across RUNBOOK / store listing / website.

**Worth doing, not blocking**
8. Fix the media-resume and Windows Send-dialog bugs (#3, #4).
9. Add Room DAO + boot-receiver tests and get the off-grid payload test into CI.
10. Cosmetic cleanups (orphan string, NUL byte, dead-code deletion); wire up or remove the Windows "Android" stub tab.

**Bottom line for the CTO:** the hard part — a correct, secure, cross-platform engine — is done and provably green. The remaining work is a finite, well-understood checklist of release logistics and a handful of file-handling fixes, none of which requires architectural change.

---

*See the companion report `REPORT-SECURITY-MARKET-2026-07-11.md` for the full security assessment and market-plausibility analysis.*
