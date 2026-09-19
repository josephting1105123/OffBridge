---
name: ship
description: Build-verify-review-commit ritual. Confirms the correct worktree/branch, runs an adversarial self-review of the pending diff via the code-review skill, sweeps untracked files for secrets/artifacts, build-verifies both platforms via the verify skill, and commits only when everything is green. Use when asked to "ship", "ship this", "build and commit", "commit when green", "commit after finishing", or "commit when done".
---

# Ship

Land the pending changes safely: right branch, reviewed diff, clean untracked set, green builds, then commit.

## Steps

1. **Confirm location.** Run `git worktree list` and `git branch --show-current`. State which worktree/branch the commit will land on. If it doesn't match the target the user named — or you are about to commit feature work directly on `main` without being told to — stop and ask before proceeding.

2. **Adversarial self-review.** Invoke the `code-review` skill on the pending diff:
   - low effort for doc-only or tiny (<~30 line) low-risk diffs — inline, no agent fan-out;
   - medium effort as the default;
   - high effort only for diffs touching wire framing/control frames, crypto/key derivation, pairing/QR payloads, or the receive/resume/promote paths.

   When the review fans out agents: scope finder prompts to the changed files, launch finders/verifiers with `model: sonnet` (`haiku` for conventions/docs angles), and adjudicate in the main loop (see AGENTS.md "Review economy").

   Ask verifiers to tag each confirmed finding's **origin** — `introduced` (this diff broke it), `widened` (pre-existing, made worse), or `preexisting` — by comparing against HEAD/merge-base. Origin, not severity, decides what belongs in this commit. When confirmed findings exceed the literal request (e.g. "commit changes" surfaces pre-existing bugs), surface a scope decision — fix diff-tied findings only / fix everything / commit as-is — instead of silently expanding or ignoring.

   Fix all high and medium `introduced`/`widened` findings, then continue. Skip this step only for pure doc/config diffs with no code paths — and say you skipped it.

3. **Untracked sweep.** Run `git status --porcelain` and inspect the untracked paths before staging anything. Look for key material (keystores, `*key*`, `*.pem`, `*.jks`, extensionless private keys — gitignore patterns miss those), build artifacts (`.aab`/`.apk`/`.exe`/large binaries), and personal files. Extend `.gitignore` for anything that must never land. Then stage **explicitly by path** — never `git add -A` / `git add .`. (This repo has had the ADB private key and a release keystore sitting untracked at the root.)

4. **Build-verify.** Invoke the `verify` skill (Android + Windows builds). If the change touches only one platform, build just that one and say which you skipped and why. Fix self-introduced errors and re-run until clean.

5. **Commit.** Only when the review findings are resolved and the relevant builds are green. Write a concise message describing the change. Never add `Co-Authored-By` trailers or AI attribution (standing user rule).

If any step fails and can't be resolved, report the failure and stop — do not commit a red or unreviewed diff.
