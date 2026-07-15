# Release runbook — the Claude-free operations manual

This is the manual to run releases and a beta **without AI help**. Everything here is mechanical:
follow the recipe. App id `com.EntropicSoftwareLab.ClipboardShare`, current versionName 3.6.5,
versionCode 27. (Android and Windows are on separate version tracks since 3.5.1; the Windows
module is at 3.7.0.)

---

## 1. Build & sign (Android)

Prerequisite: `keystore.properties` exists at the repo root (see `release/keystore/KEYSTORE_BACKUP.md`).
Without it the release build is **debug-signed** and Play will reject it.

```bash
# AAB for Play (Play App Signing re-signs this with Google's key):
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat :app:bundleRelease
#   -> app/build/outputs/bundle/release/app-release.aab

# APK for the GitHub/website lane (signed directly with YOUR upload key):
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat :app:assembleRelease
#   -> app/build/outputs/apk/release/app-release.apk
```

Rename the APK to the **stable** asset name before uploading: `ClipboardShare-android.apk`
(website/js/config.js builds the "latest download" URL from that exact name).

## 2. The versionCode rule (read this every time)
Play **rejects** an AAB whose `versionCode` already exists on any track. So for **every** upload after
the first:
1. bump `versionCode` in `app/build.gradle.kts` (15 → 16 → 17 …),
2. rebuild,
3. sign with the **same** keystore,
4. upload.
`versionName` ("3.4.0") is cosmetic; `versionCode` is the one Play enforces.

## 3. Two distribution lanes — and the signature trap
- **Lane A (GitHub Releases + website):** the `app-release.apk`, signed by your upload key. Live the
  same day. This is your real "open testing" before/at day 12.
- **Lane B (Google Play):** the `.aab`. With Play App Signing, Google re-signs it, so the Play install
  has a **different signature** than the Lane-A APK.

⚠️ A device cannot cross-update between the two (different signatures →
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Tell testers: **pick one channel.** If you want both installable
at once, give the Lane-A build an `applicationIdSuffix = ".direct"` in a product flavor (then it's a
separate app id and they coexist).

## 4. Windows build & signing
```bash
# IMPORTANT: `:window-ver:assemble` only builds a jar — it does NOT produce the app image the
# installer packages. The .iss copies from build2\compose\binaries\main-release\app\Clipboard Share\*,
# which comes from createReleaseDistributable. Delete the stale app image FIRST so a leftover build
# from a previous version can't be repackaged into a mislabeled installer:
rm -rf "Window Ver/build2/compose/binaries/main-release"
JAVA_HOME="C:/Users/josep/.gradle/jdks/eclipse_adoptium-17-amd64-windows.2" ./gradlew.bat :window-ver:createReleaseDistributable
# then compile the installer with Inno Setup (ClipboardShare.iss) -> ClipboardShareSetup.exe
```
The stable release asset name is `ClipboardShareSetup.exe` (no spaces, no version in the name) so the
evergreen URL `releases/latest/download/ClipboardShareSetup.exe` always resolves. **Every** future
release must attach both stable-named assets (`ClipboardShareSetup.exe` + `ClipboardShare-android.apk`
once the APK ships) so the `/latest/` links never break.

The installer is **unsigned**, so a new publisher trips SmartScreen ("unknown publisher"). Until you
buy an Authenticode cert (see the commented `SignTool` block in `ClipboardShare.iss`): publish the
installer's **SHA-256** and tell testers to click *More info → Run anyway* (canned reply in
`TESTER_REPLIES.md`). Get the hash:
```bash
certutil -hashfile "Window Ver\build2\inno-setup\ClipboardShareSetup.exe" SHA256
```

### Installer error: "IPersistFile::Save failed; code 0x80070005. Access is denied."
Seen at the **"Creating shortcuts…"** step. The elevated installer already copied the app into
`Program Files`, then was denied the write to the Start Menu `.lnk`. Root cause is almost always
**Controlled Folder Access** (Windows Security → *Virus & threat protection* → *Ransomware protection*)
or third-party **antivirus** blocking writes to the Start Menu Programs folder — it blocks even an
elevated installer, per-hash, and is not a malware verdict.

Since **3.5.7** the installer creates its shortcuts in `[Code]` with `try/except`, so this **no longer
aborts the install** — the app and its firewall rules install normally, and Setup shows an info dialog
if the shortcut was blocked. To also restore the Start Menu shortcut, tell the user to allow it and
reinstall:
- Windows Security → *Virus & threat protection* → *Ransomware protection* → *Manage ransomware
  protection* → *Allow an app through Controlled folder access* → add the installer (or temporarily turn
  Controlled folder access **Off**), then run Setup again.
- If a third-party AV is the blocker, add the installer to its exclusions and rerun.

## 5. Publish a GitHub Release (Lane A)
1. Create the release, tag `v3.4.0`.
2. Attach `ClipboardShare-android.apk` and `ClipboardShare-windows-setup.exe` (keep names stable across
   releases so the "latest" link never breaks).
3. Edit `website/js/config.js`: set `repo.owner` / `repo.name`, the `version`, `size`, and `sha256` for
   each platform; set `meta.changelogUrl`. Deploy the site (GitHub Pages).

## 6. Privacy policy URL (Play requires a live one)
Deploy `website/` to GitHub Pages and confirm `https://<pages-domain>/privacy.html` returns 200 before
submitting the Data Safety form. Replace `REPLACE_SITE_URL` in `index.html`, `privacy.html`,
`sitemap.xml`, and `robots.txt` with the real origin.

## 7. Crash telemetry — decide once, before the Data Safety form
- **Recommended (matches the app's zero-data promise):** ship **no SDK**. Use Play's built-in
  **Android vitals** (crash/ANR) for the Play lane — Google collects it from the OS, you declare
  nothing. For the Lane-A / Windows builds, you have no remote crash signal; rely on tester reports.
- **Alternative:** opt-in Sentry (Android + Windows .NET) for off-store crash stacks. If you do this,
  it **is** data collection — update `release/play/DATA_SAFETY.md` and the privacy policy to declare it.
Lock this choice before filling Data Safety. Then force one test crash per platform to confirm signal
arrives.

## 8. Rollback / halt / hotfix (when a beta build misbehaves after expiry)
- **Minor bug:** log it, reply with a template, fix in a later cycle. Don't touch the frozen build.
- **Build is DOA (crashes on launch / broken for testers):**
  - **Lane A:** re-point the "latest" release asset to the **previous** known-good APK (delete the bad
    asset, re-upload the prior one, or move the release tag). Keep a pre-built good APK aside before
    expiry so this needs no rebuild.
  - **Lane B (Play):** in Play Console, **halt the rollout** / discard the bad release on the track so
    testers stop receiving it. A broken closed-test build burns the 14-day clock — halting protects it.
  - If you must rebuild: bump `versionCode`, rebuild, sign with the **same** key, re-upload.

## 9. Test status (regression guards that exist now)
Green in CI (`./gradlew testDebugUnitTest`): crypto vectors (ClipboardSeal/FileSeal/SessionCrypto),
DowngradePolicy, DeviceCaps, PairingPin — plus the new pure suites: **GattChunkerTest** (BLE framing
round-trip + overflow/garbage fuzz), **GoFitnessTest** (GO-election truth tables), **TransportDescriptorTest**
(stable transport-id codec). 50 unit tests, all passing.

Compiled, runs on device/emulator: **OffGridPayloadTest** (QR/NFC payload round-trip + parse fuzz).
Wire it into a CI emulator job, or run `./gradlew connectedDebugAndroidTest` on a device.

**Not yet covered** (each needs infra you should add while you still can):
- `BootReceiver` fail-safe assertion → needs Mockito or an instrumented test (mock a Context whose
  `startForegroundService` throws; assert it's caught, not rethrown).
- Room DAOs (`PeerDao`/`SessionDao`/`AttemptDao`) → instrumented test with an in-memory Room DB.
- `OffGridPayload` in CI → add an emulator job, or convert to Robolectric (`testImplementation`
  robolectric + `org.json:json`) so it runs in the existing unit-test job.
