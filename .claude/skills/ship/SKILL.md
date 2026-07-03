---
name: ship
description: Build-verify-review-commit ritual. Confirms the correct worktree/branch, runs an adversarial self-review of the pending diff via the code-review skill, build-verifies both platforms via the verify skill, and commits only when everything is green. Use when asked to "ship", "ship this", "build and commit", or "commit when green".
---

# Ship

Land the pending changes safely: right branch, reviewed diff, green builds, then commit.

## Steps

1. **Confirm location.** Run `git worktree list` and `git branch --show-current`. State which worktree/branch the commit will land on. If it doesn't match the target the user named — or you are about to commit feature work directly on `main` without being told to — stop and ask before proceeding.

2. **Adversarial self-review.** Invoke the `code-review` skill on the pending diff:
   - low/medium effort for small UI, copy, or doc changes;
   - high effort for anything touching concurrency, wire framing/control frames, pairing/QR payloads, or security.

   Fix all high and medium findings, then continue. Skip this step only for pure doc/config diffs with no code paths — and say you skipped it.

3. **Build-verify.** Invoke the `verify` skill (Android + Windows builds). If the change touches only one platform, build just that one and say which you skipped and why. Fix self-introduced errors and re-run until clean.

4. **Commit.** Only when the review findings are resolved and the relevant builds are green. Write a concise message describing the change. Never add `Co-Authored-By` trailers or AI attribution (standing user rule).

If any step fails and can't be resolved, report the failure and stop — do not commit a red or unreviewed diff.
