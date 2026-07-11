---
name: release-notes
description: Draft user-facing release notes for a ClipboardShare release (GitHub lane and/or Play "what's new"), optionally localized into all 10 languages. Use when asked for release notes, changelog text, a "what's new" blurb, or store-listing update copy.
---

# Release Notes

Assemble notes from the record, write them for users, and never invent history.

## Sources (in order)

1. `CHANGELOG.md` — the curated record.
2. Commits since the last release tag of the **relevant platform track** — Android and Windows version independently (separate tracks since 3.5.1; bump/describe only the platform being shipped).
3. Release docs (`release/RUNBOOK.md`, prior notes on the `ClipboardShare-Showcase` releases) for tone and precedent.

**Flag history gaps instead of filling them.** The 2.x era predates the migrated repo — if a range has no record, say so and ask, don't reconstruct plausible-sounding features.

## Writing rules

- **User-facing language only.** No internal codenames (FGCM2, XP-05, AUTH2, LOHS), no file names, no audit IDs. Translate mechanism into benefit: "file streams are now authenticated end-to-end", not "FileSeal v2 AAD binding".
- Humanizer voice (global default): lead with what changed for the user, plain words, no marketing filler.
- Order by user impact, not commit order. Fixes users reported come first.
- **Pre-3.2 upgraders caveat:** the app id changed in the rebrand — upgrading from ≤3.1 is a fresh install (no in-place update). Include this whenever the audience could span that boundary.

## Formats

- **GitHub lane:** full notes on the `ClipboardShare-Showcase` release (assets keep stable names: `ClipboardShareSetup.exe`, `ClipboardShare-android.apk`).
- **Play "what's new":** ≤500 chars per locale, hard limit — write the English cut first, then check every translation still fits.
- **Localized variants:** all 10 languages (en, zh-CN, zh-TW, es, fr, ja, de, pt, in, ru) when requested; follow the add-i18n-strings conventions for tone consistency with in-app strings.

## Verify before delivering

Cross-check every claimed change against the actual diff/tag range (a note describing an unshipped or reverted feature is worse than a missing one), and per report-verify: version numbers and asset links checked against the live release page, not memory.
