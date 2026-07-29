# Windows: Smart App Control block & code-signing plan

**Symptom (v3.5.2+):** the Windows installer (`OffBridgeSetup.exe`) is blocked by
**Smart App Control (SAC)** with *"couldn't verify the publisher."* Older versions ran
without a warning because SAC ships in a silent **evaluation mode** on clean Windows 11
installs and later switches itself **on**; once on, every unsigned/unknown `.exe` is blocked,
including a fresh build of an app that ran fine before.

**Cause:** the build is **unsigned** (no Authenticode signature) and each new build is a new
hash with zero reputation. This is a signing/reputation gate, not a malware detection.

- **SmartScreen** = *warns* ("unknown publisher"), bypassable via **More info → Run anyway**.
- **Smart App Control** = *blocks*, **no per-app override** by design. Only the publisher can fix it.
- SAC is on for a **minority** of machines (auto-enabled clean Win11 22H2+). Turning it off is
  **irreversible without reinstalling Windows** — never advise end users to do that.

Publisher identity: **individual** (EntropicSoftwareLab is not a registered company).

---

## Now (free route): submit the build to Microsoft for review

Buys reputation / can clear specific false blocks while a signed build is prepared. It is
**per-hash** (every new build must be resubmitted until signing lands) and **not guaranteed** to
fully satisfy SAC — signing is the real fix — but it costs nothing and is worth doing.

1. Go to **https://www.microsoft.com/en-us/wdsi/filesubmission**
2. Submission type: **Software developer** (submitting software you develop). Sign in with any
   Microsoft account.
3. Upload the **exact** `OffBridgeSetup.exe` you publish on the release (same bytes users
   download — verify against the release's SHA-256).
4. Reason: **incorrectly detected / request reputation review**. Fill in:
   - Company/developer: *EntropicSoftwareLab*
   - Product: *OffBridge* (off-grid peer-to-peer clipboard sync + file transfer)
   - Note: *Unsigned indie build, no telemetry/network callout except to the user's paired
     device; requesting reputation so Smart App Control / SmartScreen stop blocking it.*
5. Submit and keep the submission ID. Turnaround is typically **1–3 business days**.

Also add the SHA-256 to the GitHub release notes (the site's Windows card already surfaces it)
so cautious users can verify integrity themselves.

---

## Durable fix: code-sign the build

Trust attaches to the **publisher**, so once signed, future builds inherit reputation instead of
starting from zero. Two viable paths for an individual:

### Option A — Azure Artifact Signing, formerly Trusted Signing (~US$10/mo) — NOT AVAILABLE HERE
Microsoft's cloud signing service; its signatures are **trusted by SmartScreen immediately** and
**satisfy SAC**. Renamed Azure Artifact Signing in 2026; same service.

**Ruled out 2026-07-28:** individual sign-up is open only to **the USA and Canada** (organizations
in the EU and UK). The publisher is an individual in **Malaysia**, so this route is closed until
Microsoft widens eligibility. Re-check before paying for anything else — it is by far the cheapest
option if it ever opens up.

### Option B — SignPath Foundation (free, requires going open source) — ACTIVE PLAN, needs a repo split
Free OV-level signing for qualifying open-source projects. Criteria re-read from signpath.org/terms
on 2026-07-30.

**The unit SignPath judges is the repository, not the artifact.** Their terms tie the subscription to
"ownership of the source code repository", and each signature asserts the binary is "a valid,
automated build resulting from the source code at the noted source code repository". So "we only want
the Windows installer signed" does not by itself narrow what they assess — whatever lives in the repo
they are pointed at is the project.

That matters because of one rule: **no proprietary components**. ML Kit (Google, closed source) is an
Android-only dependency; the Windows client has none. In today's single repo the Android app drags
ML Kit into the same project as the Windows client, so the monorepo cannot qualify.

Two ways out:
1. **Split the Windows client into its own public repo** and apply with that. The signed project then
   genuinely contains no proprietary code, Android keeps ML Kit and its better scanner, and the two
   applications are separated for signing as well as in name. Cost: the shared wire protocol and the
   10-language string set now span two repos, and the parity discipline in CLAUDE.md has to be
   enforced across them rather than inside one tree.
2. **Drop ML Kit from Android** so the monorepo qualifies whole. Tried on 2026-07-30 (commit 80c7b5a,
   QR decoding moved to ZXing) and reverted the same day: it cut 22.5 MB from the APK but traded away
   ML Kit's much better tolerance of low light, motion blur and screen glare, which is the core
   scanning path.

Status of the remaining criteria, assuming route 1:
- **OSI licence, no dual-licensing** — met (Apache-2.0).
- **Maintained / released / documented** — met (active, 3.7.8 shipped, website + README).
- **Reputation** — *the open risk.* They state plainly that they "cannot sign binaries based on
  source code that nobody knows" and require "a certain verifiable reputation". A freshly split
  Windows repo starts from zero, which is worse on this axis than the existing one. Discretionary,
  no appeal, and the most likely rejection reason.
- **CI-built artifacts** — not met. Signing runs from a pipeline and SignPath verifies the binary came
  from the repo. `ci.yml` runs on `ubuntu-latest` and never builds the installer; jpackage and ISCC
  need a Windows runner. Do this after they accept in principle.
- **Code signing policy page** — not written. Required: the heading "Code signing policy", the line
  "Free code signing provided by SignPath.io, certificate by SignPath Foundation", team roles, and a
  privacy statement.
- **MFA on GitHub** — required; confirm before applying.

- Original condition list: OSI-approved licence with no commercial dual-licensing, **no proprietary
  components**, actively maintained, already released, functionality documented on the download page.
- The certificate belongs to SignPath Foundation, not to us: Windows shows the publisher as
  **"SignPath Foundation"**, not EntropicSoftwareLab.
- Signing happens server-side from a **CI pipeline** (GitHub Actions / Azure DevOps / Jenkins). The
  current ritual builds locally and uploads by hand, so this route means porting the whole Windows
  build — Gradle, `createReleaseDistributable`, ISCC — to a Windows CI runner first.

### Option C — Certum Open Source Code Signing (~US$50/yr, requires a public OSS project)
Cheapest paid route for an individual, and unlike Option B it keeps **our own name** as the
publisher and leaves the local build ritual intact.
- Needs identity documents (notarised ID copy or an identity-confirmation visit), a utility bill,
  and the URL of an active open-source project showing the applicant's involvement.
- Since June 2023 all code-signing keys must live on hardware, so pick the **SimplySign cloud**
  variant unless a cryptographic card and reader can be shipped to Malaysia.
- From 2026-02-27 a single code-signing certificate is valid for at most **459 days**, so multi-year
  purchases include mandatory free reissues.

### Option D — Commercial OV/EV certificate for an individual (~US$150–400/yr)
SSL.com, Sectigo and resellers issue to individuals with cloud-HSM signing (no token shipping). No
open-sourcing required, our own name as publisher. The most expensive route, and the only one that
needs no change to how or what we publish.

### OV versus EV, honestly
EV signatures carry **instant** SmartScreen reputation. OV signatures (Options B, C and most of D)
**accrue** it, so early signed builds may still show a SmartScreen warning for a while. Whether a
fresh OV signature clears **Smart App Control** immediately is **unverified** — do not promise
testers it will.

### What actually needs signing (measured, not assumed)
`Get-AuthenticodeSignature` over every PE file in
`build2\compose\binaries\main-release\app\OffBridge` on 2026-07-28: **69 files, 67 already
signed** — the whole bundled Temurin runtime (`CN="Eclipse.org Foundation, Inc."`) and the Microsoft
UCRT redist DLLs. Only three files need our signature:

1. `OffBridge.exe` — the jpackage launcher
2. `app\skiko-windows-x64.dll` — Compose's Skia binding
3. `build2\inno-setup\OffBridgeSetup.exe` — the installer

Re-run the sweep after any Compose or JDK upgrade; a new unsigned native dependency would slip
through silently otherwise.

### Local tooling gaps (as of 2026-07-28)
- `signtool` — **not installed**. Needs the Windows SDK signing tools:
  `winget install Microsoft.WindowsSDK.10.0.22621` (or the standalone SDK installer, "Windows SDK
  Signing Tools for Desktop Apps" component only).
- .NET SDK — **not installed** (runtime only), so `dotnet tool install --global AzureSignTool`
  fails today. Only needed for the Azure route, which is closed anyway.
- Inno Setup 6 (`ISCC.exe`) and `winget` — present.

### Wiring signing into the build (once a cert exists)
Sign **both** the inner app files *and* the installer — SAC checks the app on launch too, so a
signed installer around an unsigned app still gets the app blocked.

1. Sign files 1 and 2 above **before** Inno Setup packages them, and file 3 after ISCC produces it.
2. In `Window Ver/OffBridge.iss`, define a sign tool and reference it in `[Setup]`:
   ```
   ; Inno Setup IDE: Tools → Configure Sign Tools… → add "signclipboard" mapping to your signtool cmd
   [Setup]
   SignTool=signclipboard
   SignedUninstaller=yes
   ```
   With Azure Trusted Signing the sign command is `azuresigntool sign` with your endpoint/account/
   profile and `/tr` timestamp URL, `/fd sha256 /td sha256`.
3. Always **timestamp** (`/tr`) so signatures stay valid after the cert expires.
4. Re-verify the build (`signtool verify /pa /v OffBridgeSetup.exe`) before publishing.

---

## Status
- [ ] Submit current `OffBridgeSetup.exe` to WDSI (free route)
- [x] Azure Trusted Signing ruled out — individual sign-up is USA/Canada only (2026-07-28)
- [ ] Choose between Option B (free, open source + CI), C (~$50/yr, open source) and D (~$150–400/yr)
- [ ] Install the Windows SDK signing tools so `signtool` exists
- [ ] Wire signing into the app image + `.iss` once a certificate is in hand
- [x] Website FAQ updated to explain SmartScreen vs Smart App Control honestly

Also relevant: the Microsoft Store's **EXE/MSI** submission flow requires a signed installer and
does **not** sign it for you (only MSIX submissions get free Store re-signing). That submission is
parked until signing is sorted.
