# Cross-Cutting Principles

Principles that apply to all skills. This file is read as a mandatory
checklist during any skill creation or regeneration.

---

## Active Principles

### 1. Verify anomalies against the primary source, never a secondary rendering
**Added:** 2026-07-11 (from Observation 18)
**Applies to:** all skills
**Requirement:** Before treating an anomaly seen in search/grep output, diffs in logs, or terminal echoes as file damage, confirm by reading the file region directly. Search output is a rendering of the file, not the file.
**Propagation:** opportunistic
**Status:** active

### 2. Runtime self-calibration over hard-coded environment compensation
**Added:** 2026-07-11 (from Observation 29)
**Applies to:** all skills touching platform/runtime quirks
**Requirement:** A workaround based on a single environment measurement encodes that environment forever. When a quirk's presence can be detected cheaply at runtime, detect it (apply spec-correct behavior, measure the outcome, then compensate) instead of baking in an unconditional correction.
**Propagation:** opportunistic
**Status:** active

### 3. Triage generated recommendations against live config before implementing
**Added:** 2026-07-11 (from Observation 22)
**Applies to:** all skills that apply tool-generated recommendations (reports, linters, insights)
**Requirement:** Inventory what the project already has (hooks, skills, CLAUDE.md sections), classify each suggestion as already-covered / new / harmful-as-stated, and compose new automation by reference to existing automation instead of restating it. The deliverable is the delta.
**Propagation:** opportunistic
**Status:** active

### 4. Scripted batch edits require a post-write delta assertion
**Added:** 2026-07-11 (from Observation 36)
**Applies to:** all skills doing mechanical multi-file edits
**Requirement:** Assert the expected change (removal/insertion count, `git diff --numstat`) immediately after any scripted batch edit; abort on mismatch. For line removal, split keeping terminators and filter — never multiline-anchored regex replacement (JS `^`/`$` in multiline mode treat `\r` as a line terminator; mixed-EOL repos corrupt silently).
**Propagation:** opportunistic
**Status:** active
