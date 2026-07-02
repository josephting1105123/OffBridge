# 12-day release checklist

`[x]` = already done in-repo · `[ ]` = to do · **[HUMAN]** = only you can do this (account, payment,
physical backup, hardware). Goal: by day ~12 the app is feature-frozen and live in open testing via
GitHub + website (Lane A), with the Play clock (Lane B) running in parallel to finish after expiry.

## Already done (in this repo)
- [x] Privacy policy hosted in 10 languages — `website/privacy.html` (+ footer link, sitemap)
- [x] Android translation gaps filled; Windows `translation.json.txt` repaired (200 keys/lang)
- [x] Website versions bumped to 3.4.0; privacy effective date synced across platforms
- [x] `.gitignore` protects keystore + `keystore.properties` (verified)
- [x] Keystore generator script + properties example + backup guide (`release/keystore/`)
- [x] Windows installer `SignTool` block + SmartScreen guidance staged in `ClipboardShare.iss`
- [x] Play Data Safety / content-rating / store-listing copy drafted (`release/play/`)
- [x] Runbook + canned tester replies (`release/RUNBOOK.md`, `TESTER_REPLIES.md`)
- [x] Regression tests: 18 new pure-JVM tests (framing fuzz, GO election, transport-id codec), all green; off-grid payload instrumented test compiles

## Day 1 — start every external clock (the irreversible day)
- [ ] **[HUMAN]** Run `release/keystore/generate-upload-keystore.ps1`; back up the `.jks` to 2+ offline places; save passwords in a password manager
- [ ] Create `keystore.properties` at repo root from the example
- [ ] **[HUMAN]** Register / verify the Play developer account ($25, ID verification — queue outside your control)
- [ ] **[HUMAN]** Create the GitHub remote, push, enable GitHub Pages (Pages first build is its own mini-clock)
- [ ] **[HUMAN]** (optional) Start an Authenticode cert for the Windows installer (multi-day issuance)
- [ ] Decide the two-lane signing identity (same app id vs `.direct` suffix)

## Day 2–3 — clear the developer-controlled blockers
- [ ] Lock the crash-telemetry decision (recommended: Play vitals only, no SDK)
- [ ] Fill Play **Data Safety** (from `release/play/DATA_SAFETY.md`), **content rating** + target audience (`CONTENT_RATING.md`)
- [ ] **[HUMAN]** Produce store graphics: 512² icon, 1024×500 feature graphic, ≥4 screenshots (source in `website/assets/screenshots/`)
- [ ] Paste store listing copy (`STORE_LISTING.md`); set privacy-policy URL to the live Pages URL
- [ ] Wire `website/js/config.js` (repo, URLs, sizes, SHA-256); replace `REPLACE_SITE_URL` everywhere

## Day 4 — first signed build + pipeline check
- [ ] Build signed AAB + APK; first GitHub Release with stable asset names
- [ ] Upload AAB to Play **internal** track (no gate) to validate signing/upload

## Day 5–8 — the AI-irreplaceable work, then freeze
- [ ] Add the remaining tests while you have help: BootReceiver fail-safe, Room DAO tests, OffGridPayload in CI (see RUNBOOK §9)
- [ ] Security pass: assert `allowBackup` rules keep the EncryptedSharedPreferences vault + Room DB + TOFU pins out of cloud backup
- [ ] **Day 8: feature freeze.** Tag the release. Wire Windows signing decision. Promote the frozen build to the Play **closed** track and start the 12-tester / 14-day clock
- [ ] **[HUMAN]** Recruit 15–18 testers (over-recruit; some churn). Real Google accounts on real devices

## Day 9–12 — soak, stabilize, hand off
- [ ] **[HUMAN]** Soak on ≥3 real Android devices + Windows (repeated syncs, large/interrupted transfers; watch memory, FGS death, reconnect)
- [ ] Stabilize the test suite; make sure CI is green and deterministic
- [ ] Confirm crash signal arrives (force a test crash per platform)
- [ ] Keep `release/RUNBOOK.md` + `TESTER_REPLIES.md` at hand; reserve a buffer day for slippage

## After expiry — passive only
- [ ] **[HUMAN]** Watch crash signal + feedback inbox a few times a week; triage into a backlog (no fixing)
- [ ] **[HUMAN]** Reply with canned templates; keep the 12 testers opted in until the clock closes (~day 16–17)
- [ ] **[HUMAN]** When closed test completes and review clears: apply for production access, enable open testing (no-code Play action)
