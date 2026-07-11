---
name: scoped-bugfix-subagent
description: Discipline for a bug-fix task scoped to an explicit file list (subagent fan-outs). Use when a brief says "fix bugs in these files" or "edit only these files".
---

# Scoped Bugfix (Subagent Discipline)

In a multi-agent fan-out, staying in-lane is only safe if out-of-lane findings are surfaced precisely. Three rules:

1. **Edit ONLY the assigned files.** Never silently modify an out-of-scope file, even to fix an obvious twin of an assigned bug.

2. **Flag twins precisely — after confirming them.** When an assigned bug has a near-identical sibling outside the assigned set (the brief may even name it by line), READ the sibling to confirm the twin is real (don't flag a phantom), then flag it with exact path + line + the one-line fix so the orchestrator can dispatch it. A flagged twin with file:line is actionable; "similar issue elsewhere" is not. Same partitioning when a fix's stated behavior needs a hook in an unassigned file: implement the in-scope subset fully, then name the exact missing seam (file + method + one-line change).

3. **Check singleton/test-state interactions before adding stateful defenses.** A replay cache or seen-set added to a Kotlin `object` shares state across every test in one JVM. Before declaring the change safe: read the existing test suite for repeated-input-expects-success patterns, and place the "record as seen" point AFTER every existing rejection path — verified against the actual tests, not assumed.

## Report format

End with three lists, each entry carrying file:line —
- **Fixed:** what changed, per assigned bug.
- **Flagged:** confirmed twins / missing seams outside scope, with the concrete one-line fix.
- **Verified:** which build/tests ran and their actual result (per the `verify` skill; "unverified" if not run).
