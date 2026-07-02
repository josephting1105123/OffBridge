# Product

## Register

product

## Users

People moving text and files between their own Android phone and Windows PC — often
without internet (off-grid QR / hotspot / LAN). Primary user today is the developer and
GitHub-release users; Play Store audience later. They're mid-task when they open the app:
something is on one device and needs to be on the other, fast.

## Product Purpose

Cross-platform clipboard sync and encrypted file transfer that works with no cloud and no
account. Success = a transfer that starts in seconds, survives bad network conditions via
the transport ladder, and never makes the user think about the plumbing.

## Brand Personality

Dependable tool. Quiet, precise, gets out of the way — the transfer is the star, the UI is
calm infrastructure. Not playful, not enterprise-sterile.

## Anti-references

- The 2024–25 AI-startup default look: Geist/Inter-everything, gradient text, glassy cards
  as decoration. (Geist-everywhere was tried; the owner read it as "AI-made" and replaced
  the content font.)
- Stock-Android Roboto sameness — the UI should read as designed, not defaulted.
- Enterprise file-manager density; this is a personal tool.

## Design Principles

- **Progress is the hero.** Live numbers (%, MB/s, ETA) must be steady and legible —
  tabular figures, no reflow, no decoration competing with them.
- **Two type voices.** Content (headings/body/captions) = IBM Plex Sans; controls
  (buttons/labels/chips) = Geist. Don't blur the roles.
- **Works everywhere it claims.** Every UI state has an offline/off-grid answer; nothing
  assumes connectivity.
- **Localized is not optional.** 10 locales incl. ru/ja/zh — type and layout choices must
  survive Cyrillic and CJK fallback.
- **Calm surfaces.** Slate + emerald tokens, hairlines over shadows, motion that respects
  reduced-motion (see ui/theme/TOKENS.md).

## Accessibility & Inclusion

- WCAG-aware color tokens already in place (e.g. `appGreenText` #047857 kept ≥4.5:1 on
  light surfaces; appGreen stays a fill color only).
- Reduced-motion support: Android honors the OS flag; desktop mirrors it via a settings
  toggle (`motion.dur(x)` → 0).
- Body text ≥15sp; no font below 12sp in the type scale.
