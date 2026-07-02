# Release kit

Everything needed to take Clipboard Share 3.4.0 to open testing, prepared so the AI-heavy work is
already done and what's left is mostly account/hardware steps only you can do. Start with the checklist.

## Contents
- **RELEASE_CHECKLIST.md** — the day-by-day, with `[HUMAN]`-only items flagged. Start here.
- **RUNBOOK.md** — the Claude-free operations manual: build/sign recipe, the versionCode rule, the
  two-lane signing trap, rollback/halt/hotfix, crash-telemetry decision, current test status.
- **TESTER_REPLIES.md** — canned replies (incl. the "boot-autostart is expected" and SmartScreen ones).
- **keystore/**
  - `generate-upload-keystore.ps1` — generates the release upload key.
  - `keystore.properties.example` — copy to the repo root as `keystore.properties` and fill in.
  - `KEYSTORE_BACKUP.md` — back it up + enroll in Play App Signing. **Do this first; it's irreversible.**
- **play/**
  - `DATA_SAFETY.md` — Data Safety form answers (no data collected — matches the code).
  - `CONTENT_RATING.md` — IARC questionnaire + target-audience answers.
  - `STORE_LISTING.md` — name, short/full description, what's-new, and the graphics you must produce.

## The one-paragraph strategy
A new personal Play account can't reach Play *open testing* in 12 days — the closed-test gate (12
testers, 14 continuous days) plus first-app review outlast the window. So run **two lanes**: ship a
signed open beta on **GitHub Releases + the website** now (Lane A — that's your real live open testing),
and start Play's clocks on day 1 so the closed test finishes after expiry (Lane B — a no-code click to
promote later). Freeze features ~day 8 and spend the rest on stability so nothing needs fixing after
the plan lapses.

## The single most important thing
Generate and **back up the release keystore** (and enroll in Play App Signing) before you build
anything. There is no keystore in the repo today — the build silently debug-signs without one — and a
lost key after expiry means you can never update the app. See `keystore/KEYSTORE_BACKUP.md`.
