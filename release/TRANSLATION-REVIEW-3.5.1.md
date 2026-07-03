# Translation accuracy review — 3.5.1 (2026-07-03)

## Independent cross-check (2026-07-03, after the apply pass)

All 43 sentence-level changed strings were verified by two BLIND Opus agents (a different model
from the Sonnet reviewers that proposed the wording; neither agent saw the English source or the
key names):

1. **Blind back-translation → English, compared to the real English source: 43/43 semantic match,
   zero meaning drift.** Every "tap to view", "Clipboard", drag-to-reorder, and QR/local-network/
   no-router string round-trips correctly.
2. **Blind native-fluency judge: 4/43 flagged for naturalness (no meaning errors).** Applied 3:
   de `transfer_tut_offgrid_code` (subject of "receive" was grammatically the QR code → now the
   phone scans it), fr `transfer_tut_offgrid_code` ("sans routeur nécessaire" calque → "sans
   routeur"; "téléphone récepteur" → "téléphone qui reçoit"), ru Android `copy_detail_title`
   ("Детали копии" → "Сведения о копии", also unifies with the Windows value). Kept 1: zh-TW
   `複製詳情` — the judge finds the verb-first parse ambiguous, but it matches Android zh-TW's
   pre-existing value and Windows zh-CN's construction; changing one copy would reintroduce
   cross-platform drift. If ever changed, change all three together (e.g. 複製內容詳情).

The unit fixes (MB→Mo/МБ) and the P2P term swap (端到端/端對端 → 点对点/點對點) were verified by
direct inspection — mechanical, well-attested term mappings.

**Not done: Google/Gemini engine cross-check.** The attempt to query Google Translate was blocked
by the permission sandbox (repo strings to an external endpoint). To run it: approve the network
call outside auto mode, or provide a Gemini API key. Given the 43/43 blind round-trip pass, the
marginal value is small.

One Sonnet reviewer per language (de, es, fr, id, ja, pt, ru, zh-CN, zh-TW) compared each locale
against the English source on both platforms, with web cross-check for disputed terminology. Read-only
review; the orchestrator applied the clear fixes below via `scratchpad/apply-trans.js` and build-verified.

## Applied this run (61 replacements, build-verified)

**Systematic English-source drift — all 9 Android locales** (the English strings changed; no locale
had been updated). These are **Android-only**: the Windows English source still reads "Clipboard & Push"
and "Recent Copies (Tap to copy)", so the Windows translations are correct and were left untouched.
- `recent_copies_hint` — every locale said "tap to **copy**"; English is "tap to **view**" (tapping opens
  the Copy Detail screen, it doesn't copy). Fixed in all 9.
- `history_and_push_title` — every locale still said "Clipboard **& Push/Send**"; English is now just
  "Clipboard". Fixed in all 9.
- `transfer_tut_long_press` — every locale described a "selection-mode then delete" flow; English is
  "long press to **drag and reorder**, or tap Select to remove several". Fixed in 8 (see ru caveat).
- `transfer_tut_offgrid_code` — every locale said "tap the phones together" (that's the separate NFC
  step); English describes the **QR scanning over the local network, no router needed**. Fixed in 8.

**Language-specific (clear):**
- fr: "MB" → "Mo" in 5 size-format strings; `toast_pairing_failed` restored "l'autre appareil" (was
  telling the user to check their own device); Windows `copy_detail_title` "Copier le détail" →
  "Détail de la copie".
- ru: `discoverable_to_devices` "Видимо" (reads as "apparently") → "Видимость"; "MB" → "МБ" in 5 strings;
  `copy_detail_title` → "Детали копии" (Android) / "Сведения о копии" (Windows).
- de: `nfc_guide_label_their` "Ihr Telefon (anderes)" (="your phone") → "Das andere Telefon".
- es / pt: Windows `copy_detail_title` imperative → title noun-phrase ("Detalle de la copia" /
  "Detalhe da Cópia").
- zh-CN / zh-TW: `privacy_policy_text` mistranslated "peer-to-peer (P2P)" as 端到端/端對端 (end-to-end) →
  点对点/點對點, on both platforms.

## Deferred — needs a human decision or is risky to auto-apply

1. **`user_agreement_text` uses raw XML newlines instead of `\n` escapes** — flagged in id, pt, fr, ru,
   zh-CN, zh-TW (and structurally likely de, es too). Android's aapt collapses raw whitespace, so the
   numbered EULA clauses render as one run-on paragraph instead of the spaced layout English gets. Real
   bug, but the fix rewrites a large multiline legal string per locale — deferred so each can be eyeballed
   rather than machine-rewritten unattended. Fix: replace the literal blank lines with `\n\n`, matching the
   English string's structure. (The privacy_policy_text raw newlines are harmless — its paragraph breaks
   come from `<br>` tags, not `\n`.)

2. **ja `privacy_policy_text` translates "off-grid" as オフライン (offline) in ~4 places** — should be
   オフグリッド, the term used everywhere else in the Japanese strings. Deferred because the four spots sit
   inside the large privacy string and want a careful in-context edit rather than a blind substring swap.

3. **ru `transfer_tut_long_press` / `transfer_tut_offgrid_code` not flagged** by the Russian reviewer,
   unlike the other 8 languages. Either the Russian values are already correct or the reviewer missed them —
   worth a manual look, since the English source did change. Left unmodified this run.

4. **de Windows `settings_title` = "Optionen"** (Options) where every other reference uses "Einstellungen"
   (Settings). Consistency nit, not a mistranslation — left for your call.

5. **Root-level `translation.json.txt` is stale** and lacks these keys' current values (5 misses when
   syncing) — it is not the loaded file (the `Window Ver/src/main/resources/` copy is authoritative).
   Recommend deleting the root copy; see AUDIT-3.5.1.md.
