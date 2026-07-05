---
name: audit-testable
description: Auto-audit every JVM-testable function in both clients for test coverage and correctness bugs. Fans out Explore agents to enumerate testable (pure-logic) functions across the Android app and the Windows module, cross-references existing unit tests, hunts bugs in the testable set with adversarial verification, and writes a ranked report to release/AUDIT-FN-<date>.md. Use when asked to "audit testable functions", "find untested functions", "coverage audit", or to re-run the function-level audit after a large change. Read-only — it reports; it never edits code.
---

# Audit Testable Functions

A repeatable, scoped-down version of the release/AUDIT-3.5.1.md methodology: instead of auditing everything, it sweeps the functions a JVM unit test could actually pin down — crypto, codecs, parsers, wire framing, policies, pure utilities — and reports (1) which lack tests and (2) which contain likely bugs.

The skill is **read-only**. It produces a report; applying fixes or generating tests is a separate, user-approved follow-up.

## Phase 1 — Enumerate (two Explore agents, parallel)

Launch two **Explore** agents (Task tool, `subagent_type: Explore`) in one message:

- **Android**: sweep `app/src/main/java` (packages `com.EntropicSoftwareLab.ClipboardShare` and `com.example.josephclipboardclient`; some files sit at the java root).
- **Windows**: sweep `Window Ver/src/main/kotlin/com/joseph/clipboard/windows`.

Each agent lists `fun` declarations in non-UI code and classifies each as:
- **JVM-testable**: pure logic — no android.*/androidx.*/compose imports needed by the function body; typical homes: crypto (Seal/CryptoEngine/SessionCrypto/DowngradePolicy), codecs (ManifestCodec/WireFrames/GattChunker), parsers (TransferUtils, TranslationParser), models/policies, `Net`-style helpers with injectable inputs.
- **Framework-bound**: needs Context/Compose/Bluetooth/WinRT — out of scope (note the file so the report can say what was excluded).

Output per agent: `file:line — funName(signature) — testable|framework — one-line what-it-does`.

## Phase 2 — Coverage cross-reference

For every testable symbol, Grep `app/src/test` and `Window Ver/src/test` for references. Rank the **untested** ones by risk:
1. crypto / key derivation / anti-downgrade decisions
2. wire framing / codecs / parsers (anything a hostile peer feeds)
3. state machines / policies
4. pure utilities

Cross-platform pairs (mirrored ports like ClipboardSeal, ManifestCodec, DowngradePolicy) get flagged when only ONE side has the test — mirrored golden tests are the project norm.

## Phase 3 — Bug hunt (finder → adversarial verify)

Group the testable functions into 3-6 clusters (crypto, codecs/parsers, policies/state, utils). For each cluster, launch one Explore finder agent with a correctness lens: for every function ask what input makes it wrong (empty/huge/malformed input, boundary sizes, non-ASCII, negative/overflow numbers, duplicate keys, replay/ordering). Up to 6 candidates per cluster with `file:line`, summary, and a concrete failure scenario.

Then verify: for each candidate, one verifier agent tries to REFUTE it from the code (quote the guarding line) — keep only CONFIRMED/PLAUSIBLE, like the code-review skill's verify pass.

## Phase 4 — Report

Write `release/AUDIT-FN-<yyyy-mm-dd>.md` with:
1. **Header**: date, tree hash (`git rev-parse --short HEAD`), counts (testable functions found / tested / untested / bugs confirmed).
2. **Coverage gaps table**, ranked by the Phase-2 risk order: `function — file:line — risk tier — suggested test type (golden vector / property / round-trip) — mirrored-pair status`.
3. **Confirmed bugs table**, ranked by severity: `function — file:line — finding — failure scenario — verifier verdict`.
4. **Excluded**: one line per framework-bound area skipped, so the report never silently reads as "everything was covered".

Finish by offering (do not start) the follow-up: generate the missing tests for the top-N gaps, or fix the confirmed bugs — both go through the normal build + `code-review` + commit gates in CLAUDE.md.

## Constraints

- Never edit production code or tests during the audit — report only.
- Verify any load-bearing claim against the code before it enters the report (agents paraphrase; the report must cite real lines).
- Don't re-flag items already catalogued in an existing `release/AUDIT-*.md` unless the code has changed since — check the newest audit file first and link instead of duplicating.
