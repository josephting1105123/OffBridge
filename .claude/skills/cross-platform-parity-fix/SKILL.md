---
name: cross-platform-parity-fix
description: Make one platform conform to the other (Android <-> Windows) without wire drift. Use when mirroring a fix, behavior, security defense, or frame handler across clients ("make Windows match Android" or vice versa).
---

# Cross-Platform Parity Fix

The reference implementation is the spec. These rules exist because receiver-local state has no wire test to catch divergence — fidelity must be enforced by reading, not paraphrasing.

## Rules

1. **Read the reference platform's exact implementation first.** Open the counterpart file and copy its structure verbatim — exact constants (cache bounds, fingerprint truncation, timeouts), control flow, and record/check ordering. The only allowed deltas are language/import plumbing (e.g. a local `hkdfSha256` vs `SessionCrypto.hkdfSha256`). Never reimplement from the bug description or from memory.

2. **For any frame emit/parse, read the counterpart's exact emit line** (e.g. Android's `"FILEBRIDGE_REPLY|$name|$ip|Android|$caps"`) and derive the conforming line from it — never invent a field order. Also confirm the signature/receiver of every helper API you are about to call in the target module (object vs class, arg list, imports) before writing the call.

3. **Gate risky fixes on dependency inventory, not correctness-in-principle.** Before a fix that needs a native/crypto capability (DPAPI, JNA, a new radio API), grep the target's build file for the binding. If absent: FLAG with the exact current definitions and the required dependency + migration — do not add a native dep or shell-out as a silent workaround.

4. **Wire-pinned constants require coordinated two-platform change.** Anything covered by shared golden vectors (AAD construction, key derivation, framing) must not be changed unilaterally even when the change is strictly better. Flag for a coordinated change with the golden-vector update; see the Cross-Platform gate in CLAUDE.md.

5. **Honor file-scope boundaries by partitioning, not overreaching.** When the stated behavior needs a hook outside the assigned files (a controller callback, a new interface seam), implement the full in-scope subset and flag the exact missing seam — file + method + the one-line change — under "flagged" in the summary. No vague "may need more work", no silent scope break.

## Done means

- The conforming code diffs cleanly against the reference (same constants, same order).
- Both platform builds green (`verify` skill).
- Any golden-vector/interop test touching the changed path updated or explicitly confirmed unaffected.
- Flags (missing deps, out-of-scope seams, wire-pinned deferrals) listed with exact file:line.
