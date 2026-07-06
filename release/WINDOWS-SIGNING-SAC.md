# Windows: Smart App Control block & code-signing plan

**Symptom (v3.5.2+):** the Windows installer (`ClipboardShareSetup.exe`) is blocked by
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
3. Upload the **exact** `ClipboardShareSetup.exe` you publish on the release (same bytes users
   download — verify against the release's SHA-256).
4. Reason: **incorrectly detected / request reputation review**. Fill in:
   - Company/developer: *EntropicSoftwareLab*
   - Product: *Clipboard Share* (off-grid peer-to-peer clipboard sync + file transfer)
   - Note: *Unsigned indie build, no telemetry/network callout except to the user's paired
     device; requesting reputation so Smart App Control / SmartScreen stop blocking it.*
5. Submit and keep the submission ID. Turnaround is typically **1–3 business days**.

Also add the SHA-256 to the GitHub release notes (the site's Windows card already surfaces it)
so cautious users can verify integrity themselves.

---

## Durable fix: code-sign the build

Trust attaches to the **publisher**, so once signed, future builds inherit reputation instead of
starting from zero. Two viable paths for an individual:

### Option A — Azure Trusted Signing (recommended, ~US$10/mo)
Microsoft's cloud signing service; its signatures are **trusted by SmartScreen immediately** and
**satisfy SAC**.
- Azure subscription → create a **Trusted Signing** account (Basic tier ≈ $9.99/mo).
- Create a **certificate profile**, identity validation type **Individual** (added 2024;
  verifies your government photo ID — no registered business required).
- Sign with **`azuresigntool`** or the Trusted Signing dlib via `signtool` (cloud HSM; nothing to
  store locally). A GitHub Action is available if the build ever moves to CI.
- Caveat: identity validation takes a few days; region must support Trusted Signing.

### Option B — EV code-signing certificate (~US$250–400/yr + hardware token)
Sectigo / DigiCert / SSL.com. Instant SmartScreen reputation, satisfies SAC. Requires a hardware
token or cloud HSM and (usually) an organization; **individual EV is harder to obtain** than
Option A, so A is the better fit here.

### Wiring signing into the build (once a cert exists)
Sign **both** the inner app `.exe` *and* the installer — SAC checks the app on launch too, so a
signed installer around an unsigned app still gets the app blocked.

1. Sign the jpackage/`createReleaseDistributable` app image `.exe` **before** Inno Setup packages it.
2. In `Window Ver/ClipboardShare.iss`, define a sign tool and reference it in `[Setup]`:
   ```
   ; Inno Setup IDE: Tools → Configure Sign Tools… → add "signclipboard" mapping to your signtool cmd
   [Setup]
   SignTool=signclipboard
   SignedUninstaller=yes
   ```
   With Azure Trusted Signing the sign command is `azuresigntool sign` with your endpoint/account/
   profile and `/tr` timestamp URL, `/fd sha256 /td sha256`.
3. Always **timestamp** (`/tr`) so signatures stay valid after the cert expires.
4. Re-verify the build (`signtool verify /pa /v ClipboardShareSetup.exe`) before publishing.

---

## Status
- [ ] Submit current `ClipboardShareSetup.exe` to WDSI (free route)
- [ ] Decide on Azure Trusted Signing individual onboarding
- [ ] Wire `signtool`/`azuresigntool` into `.iss` + app image once validated
- [x] Website FAQ updated to explain SmartScreen vs Smart App Control honestly
