---
name: commit-windows-version
description: The full Windows-version release-and-commit ritual for OffBridge — bump the Windows version, build and package the installer, upload it to the OffBridge-Showcase GitHub release, refresh and deploy the website, and commit. Use this WHENEVER the user asks to "git commit for the windows version", "commit the windows version/release", "release windows", "ship the windows version", "bump windows and commit", "cut a windows release", or otherwise wants to publish a new Windows build — even if they only say "commit" in a turn that is clearly about the Windows client. Windows-only: it never bumps or touches the Android version (Android is on a separate track and ships via Google Play), and never changes the Android download link.
---

# Commit the Windows version (release ritual)

"Git commit for the Windows version" is never just a commit. A Windows release bumps the version in several files, builds and packages the installer, publishes it to the public Showcase repo, refreshes the website (version + installer SHA-256), and deploys — all of which must stay consistent or the download link serves the wrong file or a mismatched hash. This skill is the repeatable recipe so nothing is skipped or left inconsistent. It automates the manual steps documented in `release/RUNBOOK.md`.

Android is on a **separate version track** and ships via Google Play. Never bump Android here, and never touch its download link — only the Windows version moves.

## Before you start
- Land in the **primary checkout on `main`** — the release lane. The website deploys from `main` (Cloudflare Pages), so the release must live there; the single-platform worktrees can't hold website + code together. Confirm with `git worktree list` + `git branch --show-current`.
- The pending changes should be Windows-only (`Window Ver/…`). Check `git status`; if there are stray Android changes, stop and ask.
- **Build-verify Windows first** — the `verify` skill (or `:window-ver:compileKotlin`) — and run the `code-review` skill on the diff (medium effort default; low for a tiny UI-only diff). Don't package a broken build.
- Decide the new version: default is a **patch bump (+0.0.1)** unless the user names one.
- **No AI co-author trailer** on any commit (project rule).

## 1 — Bump the Windows version everywhere
Grep the OLD version across the repo first so you don't miss a spot, then change each to the NEW version:
- `Window Ver/build.gradle.kts` — **both** `version = "…"` and `packageVersion = "…"`.
- `Window Ver/OffBridge.iss` — `#define MyAppVersion "…"`.
- `release/RUNBOOK.md` — the "the Windows module is at X" line.
- `README.md` — the version-badge URL (Windows half) and the "Android a · Windows b" text line.
- `CHANGELOG.md` — **prepend** a new `## <ver> — Windows (<short summary>)` entry; never rewrite past entries. Keep the notes humanized and concrete (what changed and why), not marketing.
- `website/index.html` — three spots: the Windows card `<span class="badge">v<ver></span>`, the `<span class="badge" data-dl-version>v<ver></span>`, and the footer `Windows <ver>` link.
- `website/js/config.js` — the `windows` block `version` (the `sha256` is filled in step 3).

Traps: don't touch any Android version string; the `zxing 3.5.3` note in `build.gradle.kts` is a **library** version, not the app version — never bump it. Dated audit reports under `release/REPORT-*.md` are frozen snapshots — leave them.

## 2 — Build and package the installer
Needs a full JDK with **jpackage** (the Android Studio JBR does NOT have jpackage) and **Inno Setup**. Delete the stale app image first so a leftover build can't be repackaged under the new version number:

```bash
rm -rf "Window Ver/build2/compose/binaries/main-release"
JDK=$(ls -d ~/.gradle/jdks/eclipse_adoptium-17-* 2>/dev/null | head -1)   # jpackage-capable Adoptium JDK 17
JAVA_HOME="$JDK" ./gradlew.bat :window-ver:createReleaseDistributable
(cd "Window Ver" && "/c/Program Files (x86)/Inno Setup 6/ISCC.exe" OffBridge.iss)
```
Output: `Window Ver/build2/inno-setup/OffBridgeSetup.exe`. **Wait for ISCC's "Successful compile" before hashing** — the file grows during LZMA compression, so a mid-write read gives a wrong size/SHA. If the JDK or Inno Setup path differs on this machine, find them (`ls ~/.gradle/jdks`, check `Program Files (x86)/Inno Setup 6`) rather than assuming.

## 3 — Hash and finish the website config
```bash
sha256sum "Window Ver/build2/inno-setup/OffBridgeSetup.exe"   # lowercase hex
```
Put that hash in `website/js/config.js` `windows.sha256`. The `size` label is MiB-style (what Windows Explorer shows — e.g. "83 MB" ≈ 83 MiB); only change it if the real size shifted materially.

## 4 — Commit (on `main`, no co-author trailer)
Two commits, matching the established history:
1. **code + docs:** `Window Ver/build.gradle.kts`, `Window Ver/OffBridge.iss`, the Kotlin change(s), `release/RUNBOOK.md`, `CHANGELOG.md`, `README.md`.
2. **website:** `website/index.html`, `website/js/config.js`.

## 5 — Publish the installer to GitHub (no `gh` needed)
Releases live on the **separate public repo `josephting1105123/OffBridge-Showcase`** (this repo's remote is the private `Clipboard-Share`). `gh` isn't installed and `git credential fill` won't yield the token headlessly, but it's in Windows Credential Manager. The bundled script reads it (via CredRead) and drives the GitHub REST API — run it from the repo root:

```bash
powershell -ExecutionPolicy Bypass -File .claude/skills/commit-windows-version/scripts/publish-showcase-release.ps1 \
  -Version <ver> -AndroidVersion <android-ver> \
  -ExePath "Window Ver/build2/inno-setup/OffBridgeSetup.exe" -Sha256 <hash>
```
It applies the Showcase conventions automatically — **tag = bare version, no `v`** (e.g. `3.6.4`); name = `OffBridge Windows <win> / Android <android>` — creates or reuses that release, replaces the asset if one is already attached, uploads `OffBridgeSetup.exe`, marks it `make_latest`, and prints the result. It **never prints the token**. If it reports `ERROR_NO_CREDENTIAL`, run any `git push` once to cache the credential, then retry.

## 5b — Carry the Android APK forward (MANDATORY since 3.7.2)
The website's Direct APK card and Android's sideload updater both point at
`/releases/latest/download/OffBridge-android.apk` — but the publish script only uploads the
Windows installer, so the moment the new release becomes latest, that link 404s unless the APK is
re-attached. Copy it from the previous release (use the release id printed in step 5):

```bash
powershell -ExecutionPolicy Bypass -File .claude/skills/commit-windows-version/scripts/copy-apk-asset.ps1 \
  -FromTag <previous-windows-version> -ToReleaseId <id-from-step-5>
```
It downloads the APK from the old tag, prints its SHA-256 (must match `androidApk.sha256` in
`website/js/config.js` — if Android shipped a new APK since, update that hash instead), uploads it
to the new release, and verifies the latest release lists **both** assets. Skip only if the old
release genuinely has no APK asset yet.

## 6 — Deploy the website
```bash
git push origin main
```
Cloudflare Pages rebuilds from `main`. Advertise the new version proactively — don't hold this push waiting on anything (standing user preference); the installer upload in step 5 should already have landed so `/latest/download/…` matches the published SHA.

## 7 — Verify
The script checks `/releases/latest` — confirm it shows the new tag with `OffBridgeSetup.exe`. The evergreen link
`https://github.com/josephting1105123/OffBridge-Showcase/releases/latest/download/OffBridgeSetup.exe`
now serves the new build.

## Notes
- The installer is **unsigned** — SmartScreen / Smart App Control warns on fresh downloads; the published SHA-256 is the out-of-band integrity check (see RUNBOOK). Not a blocker.
- This skill covers a Windows release specifically. For a generic "build-verify-review-commit" on either platform, use the `ship` skill instead.
