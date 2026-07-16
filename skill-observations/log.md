# Skill Observation Log

Observations captured during task-oriented work. Each entry identifies a
potential skill improvement or new skill opportunity.

**Status key:** OPEN = not yet actioned | ACTIONED = skill updated/created |
DECLINED = user decided not to pursue

---

## 2026-07-02

### Observation 1: Multi-phase audit workflow lost its entire verify phase to a session limit; orchestrator self-verification saved it
**Status:** ACTIONED — Applied to audit-transport (weekly review 2026-07-11)

**Date:** 2026-07-02
**Session context:** Read-only improvement audit of the Android app, Windows module, and website via a 3-phase Workflow (find → completeness-critic → adversarial-verify). All 4 critic agents and all 21 verify agents failed with "You've hit your session limit"; only the 9 first-round finder agents completed, leaving 102 findings marked "verifier unavailable — unreviewed."
**Skill:** audit-transport (and any future multi-agent audit/review workflow)
**Type:** open-source
**Phase/Area:** Workflow resilience / verification degradation

**Issue:** A later workflow phase can fail wholesale (rate/session limit, terminal API error) after the expensive discovery phase has already succeeded. Two design choices mattered: (a) the workflow script's per-agent `.then` fallback marked findings `real: true, verifyNote: "verifier unavailable — unreviewed"` instead of dropping them, so no discovery work was lost; (b) the orchestrator (main loop) then hand-verified the 7 highest-severity findings directly against the code — all 7 confirmed true, which calibrated confidence in the unverified remainder.

**Suggested improvement:** In audit/review workflow guidance, make graceful verification-degradation explicit: never drop findings when the verify phase fails — tag them unverified and carry them forward. And codify the orchestrator fallback: when the automated verify phase cannot run, the main loop should hand-verify the load-bearing (highest-severity) findings before presenting, and clearly label each finding's verification status in the deliverable.

**Principle:** A verification stage that can fail silently is worse than none — findings vanish and the report looks complete. Fail-open on data (keep + flag) and fail-over on verification (orchestrator checks the critical few). Verification status belongs in the deliverable, not just the pipeline.

### Observation 2: Auditor findings should carry exact file:line anchors so a follow-up verifier can confirm cheaply
**Status:** ACTIONED — Applied to audit-transport (weekly review 2026-07-11)

**Date:** 2026-07-02
**Session context:** Same audit. Hand-verifying 7 findings took only a handful of targeted reads because every finding cited exact paths and line ranges (e.g. "build.gradle.kts:56-60", "FileTransferDaemon.kt:57-124").
**Skill:** audit-transport (and general review/audit workflows)
**Type:** open-source
**Phase/Area:** Finding schema / output contract

**Issue:** The audit's finding schema required file + detail, and the auditors consistently included precise line ranges in the detail. That precision is what made post-hoc orchestrator verification fast enough to be practical when the automated verify phase died.

**Suggested improvement:** Bake "cite exact file path + line range for every claim" into the finding schema/prompt as a hard requirement (a dedicated `line`/`anchor` field, not buried in prose), so verification — automated or fallback — is always a cheap targeted read rather than a re-search.

**Principle:** A finding's verifiability is a function of how precisely it points at the evidence. Structured, line-anchored findings turn verification from re-exploration into a lookup, which is what makes an orchestrator fallback viable under resource limits.

### Observation 3: Dead-code deletion coupled across two files needs whole-cluster reachability check
**Status:** ACTIONED — New skill dead-code-removal created (user-level) (user-requested skill build 2026-07-11)

**Date:** 2026-07-02
**Session context:** Bug-fix subagent removing unused Compose components (StatusChips.kt, TransferProgress.kt) and trimming a layout pre-warm block in MainActivity.kt.
**Skill:** New skill candidate: dead-code-removal-verification
**Type:** open-source
**Phase/Area:** Verification before deletion

**Issue:** The two target files cross-referenced each other: TransferProgress.kt called TransferStatusChip/TransferState defined in StatusChips.kt. A naive "is symbol X referenced anywhere?" grep reports StatusChips symbols as live (referenced by TransferProgress) even though the whole cluster is dead externally. Correct call required computing external reachability of the cluster as a unit, then deleting both files.

**Suggested improvement:** When deleting suspected-dead code, build the intra-cluster reference graph first; a symbol referenced only by other in-cluster dead code is still dead. Grep for external call sites (imports + call syntax) excluding the candidate files themselves, and separately check test/androidTest and sibling platform modules before deleting whole files.

**Principle:** Reachability, not raw reference count, determines dead code. Two mutually-referencing dead symbols keep each other "alive" under a naive grep; scope the search to references outside the deletion set.

### Observation 4: Assigned-file boundary vs. cross-referenced sibling bugs
**Status:** ACTIONED — New skill scoped-bugfix-subagent created (weekly review 2026-07-11)

**Date:** 2026-07-02
**Session context:** Subagent bug-fix pass; assigned exactly four Android files, but the bug brief named twin bugs living in two sibling files (P2pFallbackJoiner.kt, BleControlClient.kt) that were NOT in the assigned set.
**Skill:** New skill candidate: scoped-bugfix-subagent
**Type:** open-source
**Phase/Area:** Scope discipline / flagging

**Issue:** Two of the four assigned bugs (sticky-broadcast false-success, notify-congestion busy-loop) exist in near-identical form in a sibling file the brief mentioned by line number but did not assign. The correct move was to fix only the assigned file and flag the sibling with concrete file+line so the orchestrator can dispatch it, rather than silently editing an out-of-scope file or ignoring the twin.

**Suggested improvement:** A scoped-bugfix skill should codify: (1) edit ONLY assigned files; (2) when a bug has a known twin outside the assigned set, flag it with exact path + line + the one-line fix so it isn't lost; (3) read the sibling to confirm the twin is real before flagging (avoid flagging a phantom).

**Principle:** In a multi-agent fan-out, staying in-lane is only safe if out-of-lane duplicates are surfaced precisely; a flagged twin with file:line is actionable, a vague "similar issue elsewhere" is not.

### Observation 5: Verify-first pays off on crypto replay caches — check the singleton test interaction
**Status:** ACTIONED — New skill scoped-bugfix-subagent created (weekly review 2026-07-11)

**Date:** 2026-07-02
**Session context:** Added a receiver-local nonce replay cache to a Kotlin `object` (singleton) AEAD helper; existing unit tests share that singleton's state across test methods in one JVM.
**Skill:** New skill candidate: scoped-bugfix-subagent
**Type:** open-source
**Phase/Area:** Change-safety verification

**Issue:** The replay cache lives in a Kotlin `object`, so its state is shared across all tests in the suite. Before adding it I had to confirm no existing test opens the same blob (same nonce) twice expecting success both times — one test did open the same blob twice, but the first open was rejected for staleness *before* the nonce was recorded, so the cache never populated and the test still passed. Missing that ordering would have produced a false test break attributed to the fix.

**Suggested improvement:** When adding stateful receiver-local defenses (replay caches, seen-sets) to a singleton, always read the existing test suite for repeated-input-expects-success patterns and reason about record-point ordering (record only after all prior rejections) before declaring the change safe.

**Principle:** Singleton state added for security silently couples otherwise-independent tests; the safe insertion point for "mark as seen" is after every existing rejection path, and that must be verified against the actual tests, not assumed.

### Observation 6: Cross-platform parity fixes should read the reference platform's exact impl, not reimplement from the bug description
**Status:** ACTIONED — New skill cross-platform-parity-fix created (weekly review 2026-07-11)

**Date:** 2026-07-02
**Session context:** Fixing 3 security bugs in the Windows client's storage layer (ClipboardSeal replay cache, FileSeal AAD, SecurityVault plaintext keys) where fixes must make Windows conform to Android without changing wire bytes.
**Skill:** New skill candidate: cross-platform-parity-fix
**Type:** internal
**Phase/Area:** Verification / conforming one platform to another

**Issue:** The task said "mirror what the Android ClipboardSeal fix does." Rather than invent a nonce cache from the description, the correct move was to open the Android ClipboardSeal.kt and copy its exact structure (MAX_NONCES_PER_PEER=512, bounded LinkedHashMap with removeEldestEntry, SHA-256 peer fingerprint truncated to 16 bytes, prune-then-check-then-record order). The only deltas were import differences (Android uses SessionCrypto.hkdfSha256; Windows has a local hkdfSha256 + needed a new MessageDigest import) and abs already being imported as kotlin.math.abs. Reimplementing freehand would have risked subtle divergence (e.g., different cache bound, different fingerprint) that no wire test would catch because this is receiver-local state.

**Suggested improvement:** When a task is "make platform B conform to platform A," always Read A's file first and diff against B, treating A as the golden source. Port structure verbatim; only adapt language/import plumbing. Flag rather than fix anything that would change shared golden-vector-pinned bytes (AAD, key derivation, framing).

**Principle:** For cross-platform conformance fixes, the reference implementation is the spec. Copy its exact constants and control flow; the only allowed deltas are local plumbing. Receiver-local state has no wire test to catch divergence, so fidelity to the reference must be enforced by reading it, not by paraphrasing the bug report.

### Observation 7: Gate risky fixes on actual dependency inventory, not on the fix being "correct in principle"
**Status:** ACTIONED — New skill cross-platform-parity-fix created (weekly review 2026-07-11)

**Date:** 2026-07-02
**Session context:** Same session. SecurityVault stores per-peer keys as Base64 plaintext; DPAPI (ProtectedData.Protect) is the right fix but only if a clean binding already exists.
**Skill:** New skill candidate: cross-platform-parity-fix
**Type:** internal
**Phase/Area:** Fix-vs-flag decision

**Issue:** The right cryptographic fix (DPAPI wrapping) was explicitly gated on the module already having a usable binding (JNA / native crypto dep). Reading Window Ver/build.gradle.kts showed only Gson, ZXing, and Compose — no JNA, no native crypto. The correct action was to FLAG (needs a DPAPI/JNA dependency plus a one-time migration to re-wrap existing plaintext keys) and make no change, rather than shell out to PowerShell or add a native dep. Same pattern for FileSeal: the AAD/sub-key change is genuinely more secure but is pinned by a shared golden vector, so it must be flagged for a coordinated two-platform change, not applied unilaterally.

**Suggested improvement:** Before applying a security fix that needs a native/crypto capability, grep the build file for the required binding. If absent, flag with the exact current definitions (aad(), deriveKey(), the plaintext-write lines) so both platforms can change together, and state which path was taken. Never introduce a native dep or shell-out as a silent workaround.

**Principle:** A fix that is correct in isolation can still be the wrong action: wire-pinned constants require coordinated multi-platform changes, and security fixes requiring new native capabilities must not smuggle in risky dependencies. Verify the platform actually supports the clean fix (check the manifest/build file) before writing code; otherwise flag with exact current definitions and stop.

### Observation 8: Cross-platform receiver fixes constrained by "edit only these files" scope
**Status:** ACTIONED — New skill cross-platform-parity-fix created (weekly review 2026-07-11)

**Date:** 2026-07-02
**Session context:** Fixing four cited bugs in the Windows client (ClipboardDaemon.kt, CryptoEngine.kt) of the cross-platform clipboard app, with a hard rule to edit only those two files.
**Skill:** New skill candidate: cross-platform-conform-fix (or an addition to an existing transport/interop skill)
**Type:** internal
**Phase/Area:** Scoping a "make Windows match Android" bug fix

**Issue:** The CMD_DELETE fix asked to "remove the suffix text from history and wipe the OS clipboard." The daemon only receives an onClipboardReceived (add-to-history) callback; the delete-from-history method lives in ClipboardAppController (out of scope). Within the two assigned files I could fully stop the literal being pasted/re-added and wipe the OS clipboard on match, but removing an already-present older history entry needs a controller callback that couldn't be added without editing a third file. The right move was to implement the in-scope portion completely and flag the residual as needing a one-line callback in the controller, rather than either silently skipping it or breaking the file-scope rule.

**Suggested improvement:** When a fix's stated behavior spans a file outside the assigned set, split it: implement everything achievable within scope, then flag the exact out-of-scope hook needed (file + method + one-line change) so the parent can decide. Record this partition explicitly in the summary under "flagged" with the concrete missing wiring, not a vague "may need more work."

**Principle:** A "conform to the other platform" fix often needs a callback the current interface doesn't expose. Honor the file-scope boundary by delivering the safe, in-scope subset and precisely naming the missing seam, rather than overreaching or under-delivering.

### Observation 9: Cross-platform conformance fixes need the counterpart file read for exact field order
**Status:** ACTIONED — Applied to cross-platform-parity-fix (rule 2) (weekly review 2026-07-11)

**Date:** 2026-07-02
**Session context:** Fixing four discovery-daemon bugs in the Windows client (TransferDaemon.kt) to conform to Android behavior without a wire change: node-timeout window, FILEBRIDGE_REPLY accept+emit, packet-source-over-declared-IP trust, and the discoverable gate.
**Skill:** verify (and cross-platform conformance workflow generally)
**Type:** internal
**Phase/Area:** Cross-platform wire-conformance changes

**Issue:** The task required Windows to emit a FILEBRIDGE_REPLY frame identical to Android's. The correct, safe move was to read the Android counterpart's exact emit line (app/src/main/java/TransferDaemon.kt line 303: "FILEBRIDGE_REPLY|$customDeviceName|$myIp|Android|$DISCOVERY_CAPS") and the Windows broadcast line to derive the Windows reply as name|ip|Windows|caps, rather than inventing a field order. Also had to confirm SecurityVault.isDiscoverable() is a no-arg method on an object and add the cross-package import, since gating logic assumed an API shape.

**Suggested improvement:** When a fix makes one platform "conform" to the other, the workflow should mandate: (1) read the counterpart's exact frame-emit/parse line before writing the conforming code, (2) confirm the referenced helper API signature/receiver in the target module before calling it. Both are cheap reads that prevent wire-format drift or a compile break.

**Principle:** "Conform to the other platform" is only safe when the exact bytes and the exact helper API are read from source, not reconstructed from memory. The counterpart file is the contract; the referenced helper is the interface. Read both before emitting conforming code.

### Observation 10: Comment-audit sweeps need a "grounding pass" before editing
**Status:** ACTIONED — New skill comment-hygiene-sweep created (user-level) (user-requested skill build 2026-07-11)

**Date:** 2026-07-02
**Session context:** User asked to "search for each comment in the windows version and change it to a more appropriate one, and latest description" — a full comment-accuracy sweep of the Windows module ahead of the 3.5.0 release.
**Skill:** New skill candidate: comment-hygiene-sweep
**Type:** open-source
**Phase/Area:** Pre-edit grounding

**Issue:** The request was ambiguous ("latest description") until the working tree was checked: build files showed uncommitted 3.5.0 work, which redefined what "stale" meant (e.g. a "3.5.0 always advertises pv2" comment was current, while "legacy peers stay on CBC" comments were newly wrong because CBC was retired in this same 3.5.0 tree). Recurring stale-comment classes found: (1) version-stamped comments that rot ("(3.0)", "§3.2 of the V3.0 plan"), (2) internal review-finding IDs left as prefixes (M-1/M-15/M-16/M-17), (3) template slop ("In a real app, handle..."), (4) behavior descriptions outdated by a protocol change in the same tree (CBC retirement), (5) headers documenting only v1 of a scheme after v2 was added.

**Suggested improvement:** A comment-hygiene skill should mandate: first establish the tree's actual version/feature state (build files + uncommitted diff) before judging any comment; then sweep with a checklist of the five stale-comment classes above; verify each "behavioral" comment against the code it describes (grep call sites) rather than editing from the comment text alone.

**Principle:** Comment accuracy is relative to the current tree state, not the last release — audit the code first, the comments second, or the sweep itself introduces errors.


### Observation 11: Adversarial verifier verdicts can contradict their own evidence — orchestrator must read the evidence
**Status:** ACTIONED — New skill plan-claim-verification created (user-level) (user-requested skill build 2026-07-11)

**Date:** 2026-07-02
**Session context:** Plan-mode design of an in-screen Send-Via overlay; a 5-agent workflow adversarially verified 27 file/line claims from a Plan agent. One verifier marked the plan's back-press mitigation "PARTIAL/insufficient" while its own numbered evidence (BackHandler unregisters on enabled=false; onHiddenChanged flips state to false) actually proved the mitigation sufficient.
**Skill:** New skill candidate: plan-claim-verification (or general review/audit workflow guidance)
**Type:** open-source
**Phase/Area:** Verify-stage output interpretation

**Issue:** Structured verify agents return a verdict enum plus prose evidence. The verdict can be miscalibrated (the agent misreads what the mitigation was) even when the evidence field is accurate and complete. Trusting the enum alone would have flagged a non-defect and prompted an unnecessary design change; reading the evidence showed the plan was correct as written.

**Suggested improvement:** In claim-verification workflows, treat non-CONFIRMED verdicts as "orchestrator must adjudicate from the evidence text," not as ground truth. The schema should require the evidence to state (a) what the claim/mitigation actually was and (b) the mechanism observed in code, so the orchestrator can spot verdict/evidence contradictions cheaply.

**Principle:** A verifier's verdict is a summary of its evidence, and summaries can be wrong while the evidence is right. The orchestrator's adjudication step — read evidence, not labels — is what converts a noisy verify fan-out into a reliable one.

### Observation 12: Workflow return values get truncated in task notifications — keep them compact, parse tasks/*.output for the rest
**Status:** ACTIONED — New skill workflow-orchestration-hygiene created (user-level) (user-requested skill build 2026-07-11)

**Date:** 2026-07-02
**Session context:** Same session. A verification workflow returned {groups, nonConfirmed} with full evidence strings for 27 claims; the task-notification result truncated at ~2k chars mid-JSON. Full result was recovered from the tasks/<id>.output file — a JSON wrapper whose `result` key holds the workflow's return value (parse via ConvertFrom-Json, then read `.result`).
**Skill:** New skill candidate: workflow-orchestration-hygiene
**Type:** open-source
**Phase/Area:** Workflow return-value design

**Issue:** Returning verbose per-claim evidence in the workflow's return value made the notification unreadable and forced a recovery step. The compact essentials (counts + non-confirmed items only) would have fit; full evidence was only needed for the 3 flagged items.

**Suggested improvement:** Design workflow returns as "exceptions + counts": return only items needing orchestrator attention (non-confirmed findings, failures) with their evidence, plus summary counts for the confirmed bulk. When a notification does truncate, recover via the output file's `result` key rather than re-running.

**Principle:** A workflow's return value is a message to the orchestrator, not an archive; size it to the decision it must enable. Full detail belongs in retrievable artifacts (output file, transcript), not the notification channel.

### Observation 13: Impeccable skill setup assumes a web project
**Status:** ACTIONED — Applied to impeccable (weekly review 2026-07-11)

**Date:** 2026-07-02
**Session context:** Adding dim+blur backdrop and scale/fade entrance to spotlight overlays and dialogs in a Compose Android + Compose Desktop app
**Skill:** impeccable
**Type:** internal
**Phase/Area:** Setup steps 1 and 5 (context.mjs / NO_PRODUCT_MD -> init gate)

**Issue:** The impeccable skill auto-matched this UI-motion task (its description covers "polish", "animate", "motion, micro-interactions" for "product UI"), but its mandatory setup is web-oriented: context.mjs demanded a PRODUCT.md init flow, palette.mjs assumes CSS/OKLCH tokens, and the detector/live tooling assume HTML/CSS. For a Kotlin Compose (Android + Desktop) project, only the design/motion guidance applies; the init gate had to be consciously skipped to avoid derailing the task.

**Suggested improvement:** Add a note to the impeccable invocation habit (or a small internal companion note): when the target is a non-web UI stack (Compose, SwiftUI, Flutter), skip the script/init gates and apply only the General rules + register guidance. Alternatively, stop auto-invoking impeccable for non-web UI tasks and rely on its motion/design rules from memory of the skill body.

**Principle:** Skill descriptions that match on task intent ("polish UI") can still carry tech-stack assumptions in their setup steps. Auto-selection rules should distinguish "the guidance applies" from "the tooling applies", and skills with hard setup gates should state their stack scope in the description.

### Observation 14: Cross-platform string addition is a scripted, anchor-based pattern
**Status:** ACTIONED — New skill add-i18n-strings created (weekly review 2026-07-11)

**Date:** 2026-07-02
**Session context:** Adding a total-file-size display (Send-Files chip + Send-Via label) to Android and Windows, which required one new localized string in 10 Android strings.xml files and 10 Windows translation.json.txt language blocks.
**Skill:** New skill candidate: add-localized-string
**Type:** internal
**Phase/Area:** i18n / string resources

**Issue:** Adding one user-facing string to this project means 20 coordinated insertions (10 Android locale files + 10 contiguous language blocks in the Windows translation resource). The Edit tool requires reading each file first, which is wasteful for ~600-line XML files; a Node script anchored on a per-locale existing line (e.g. the translated `send_via_title` value) did all insertions safely with verification output. Note: this machine has no Python (Store stub only); Node v24 is the working scripting runtime.

**Suggested improvement:** A small internal skill (or addition to project docs) codifying: (1) anchor each insertion on a per-locale unique existing line, (2) Windows keys must land inside the contiguous language block for the fragile indexOf/regex loader, (3) use `%1$s` placeholders on Android vs `%s` on Windows, (4) units like MB/GB stay untranslated per existing convention, (5) script it with Node, verify with a grep counting 10+10 hits.

**Principle:** For repos with fan-out localization (same key duplicated across N files/blocks), mechanical scripted insertion with per-locale anchors plus a count-verification grep is faster and safer than hand-editing each file.


### Observation 15: Empirical micro-repro before fixing runtime-dependent windowing bugs
**Status:** ACTIONED — New skill runtime-behavior-probe created (user-level) (user-requested skill build 2026-07-11)

**Date:** 2026-07-02
**Session context:** Windows client maximize button oversized the window ~2.25x on a 150% HiDPI display
**Skill:** New skill candidate: runtime-behavior-probe (or fold into a debugging methodology skill)
**Type:** open-source
**Phase/Area:** Diagnosis before fix

**Issue:** The bug hinged on how the bundled JBR interprets `Frame.setMaximizedBounds` under HiDPI — an area where JDK versions genuinely differ and memory of bug-tracker lore is unreliable. Instead of patching from assumption, a ~40-line scratch AWT program run on the exact shipping runtime (resolved from the build's javaHome) measured the actual transform: input rect × scale², which pinned both the root cause and the exact compensation (pre-divide by scale). The fix then matched measured behavior on the first try.

**Suggested improvement:** Capture as a reusable pattern: (1) identify the exact runtime the shipping artifact bundles (toolchain/javaHome, not the IDE's JVM by assumption); (2) write a minimal scratch repro exercising only the suspect API; (3) run it on that runtime and measure input→output; (4) derive the fix from the measured transform and cite the measurement in a code comment with a re-verify note for runtime upgrades.

**Principle:** For bugs that depend on platform/runtime interpretation of an API (DPI scaling, font metrics, window management), a minimal empirical probe on the shipping runtime beats reasoning from documentation or remembered bug reports — it converts a guess into a measurement and leaves a verifiable trail.

### Observation 16: impeccable skill is web-centric; Compose (Android/Desktop) projects need adapted verification
**Status:** ACTIONED — Applied to impeccable (weekly review 2026-07-11)

**Date:** 2026-07-02
**Session context:** Replacing "AI-ish" fonts (Geist) with IBM Plex Sans for non-button text across the Android + Compose Desktop clipboard clients
**Skill:** impeccable
**Type:** open-source
**Phase/Area:** Setup steps / typeset command

**Issue:** The impeccable setup and typeset reference assume a web stack: context.mjs/detect.mjs scan HTML/CSS, guidance is in CSS units (rem, ch, clamp()), verification leans on browser screenshots, and live mode requires an HTML entry. On a Kotlin Compose project none of that applies directly, yet the core method (assess → plan on a contrast axis → two-voice system → verify) transferred fine; verification became Gradle builds (AAPT2 validates font resources) instead of browser checks, and "keep buttons distinct" mapped to M3 label* slots / M2 button slot rather than CSS selectors.

**Suggested improvement:** Add a short "non-web UI toolkits" note to the setup or typeset reference: state that the design rules apply toolkit-agnostically, map the common equivalents (sp/dp for rem, Compose Typography slots for type tokens), and say verification should use the platform's build + a native run instead of detect.mjs/browser passes. Skip the detector scripts silently when no HTML/CSS exists.

**Principle:** A design skill's rules usually generalize across rendering stacks, but its *enforcement tooling* rarely does; naming the fallback verification path per stack keeps the skill usable outside its home ecosystem instead of being silently skipped.


### Observation 17: Compile failure raced with an IDE auto-save — re-run before debugging
**Status:** ACTIONED — Applied to verify (weekly review 2026-07-11)

**Date:** 2026-07-02
**Session context:** Cross-platform transfer-cancel fix; :window-ver compile failed with "Missing return statement" at a line the session never touched
**Skill:** verify
**Type:** internal
**Phase/Area:** Interpreting build failures

**Issue:** The Windows module compile failed pointing at code far from the session's edits, in a file the harness flagged as externally modified (user's IDE was open on it). The on-disk code read as syntactically fine. A plain re-run of the same compile succeeded — the first compile had raced a mid-edit auto-save state of the file.

**Suggested improvement:** Add a rule to the verify skill: when a compile error (a) points at a location the session did not modify AND (b) the file was reported as externally modified, re-run the build once before investigating. Only debug if the error reproduces.

**Principle:** In a live-edited workspace, a build is a snapshot of a moving target; a failure that implicates untouched code should be reproduced before it is diagnosed.

### Observation 18: Search-tool output artifacts can mimic file corruption
**Status:** ACTIONED — Seeded cross-cutting-principles.md (principle 1) (weekly review 2026-07-11)

**Date:** 2026-07-02
**Session context:** Adding flowing progress numbers + Send→history-detail navigation on both clients
**Skill:** New skill candidate: none (cross-cutting working practice)
**Type:** open-source
**Phase/Area:** Code investigation / verification before editing

**Issue:** Grep tool output rendered several Kotlin `//` comment lines as starting with `\ ` (backslash-space), making three files appear syntactically corrupted. Reading the actual files showed normal `//` comments — the "corruption" was purely a display artifact of the search output. Acting on it (e.g., "fixing" the comments or reporting corruption to the user) would have wasted a cycle and eroded trust.

**Suggested improvement:** Before treating any anomaly seen in search/grep output as file damage, confirm by reading the file region directly with the file-reading tool. Search output is a rendering of the file, not the file.

**Principle:** Verify suspected file corruption against the primary source (a direct file read), never against a secondary rendering (search results, diffs in logs, terminal echoes). Cheap to check, expensive to get wrong.

### Observation 19: "Source of truth" token doc had drifted from code and briefly misled the diagnosis
**Status:** ACTIONED — Applied to impeccable (weekly review 2026-07-11)

**Date:** 2026-07-03
**Session context:** Redesign of the Android Send-via slide-up sheet (dark-mode seam against the bottom nav bar). Read `ui/theme/TOKENS.md` early because it declares itself the single human-readable source of truth for design tokens.
**Skill:** impeccable
**Type:** open-source
**Phase/Area:** Setup step 3 (familiarize with existing design system)

**Issue:** The token doc documented a decommissioned palette (old blue-slate dark values). The initial root-cause hypothesis quoted its hex values; an exploration agent's diff against the real token files (`Color.kt`, `values-night/themes.xml`) showed the structure of the diagnosis was right but every hex was stale. The plan then had to add a doc-resync task, and the implementation verified XML and Compose values against each other before rewriting the table.

**Suggested improvement:** In the setup step that reads the existing design system, when a token/design doc exists alongside code tokens, spot-diff the doc's values against the code before quoting or designing from them. Treat code as ground truth, and when drift is found, fold a doc resync into the task's scope rather than working around the stale doc.

**Principle:** Docs rot faster than code, and a doc that says "source of truth" is a claim to verify, not a fact. Design work that touches a token system should end with the doc matching the code, otherwise the next session inherits the same trap.

### Observation 20: A spike validated the riskiest native assumption before the abstraction was built on top of it
**Status:** ACTIONED — Applied to audit-transport (weekly review 2026-07-11)

**Date:** 2026-07-03
**Session context:** Adding a BLE control channel to the off-grid PC↔phone transfer path (Windows Kotlin/JVM driving WinRT GATT). The audit-transport skill's Explore pass mapped the existing stack; T0 of the plan was a throwaway PowerShell/WinRT spike run BEFORE any production Kotlin was written.
**Skill:** audit-transport
**Type:** open-source
**Phase/Area:** Post-audit, pre-implementation gate

**Issue:** The plan's core delivery mechanism ("persistent PowerShell 5.1 helper hosting WinRT GattServiceProvider") turned out to be wrong in two ways that only a real run exposed: (1) Windows PowerShell 5.1 cannot subscribe to WinRT events at all (`Register-ObjectEvent` → "cannot subscribe to Windows RT events"), forcing a pivot to compiling an embedded C# helper with the in-box `csc.exe`; (2) `GattServiceProvider` advertising reports a transient `Aborted` status *before* `Started`, so the naive "wait until Started-or-Aborted" loop would have reported failure on every working adapter. Both were caught in a ~30-min spike; had they surfaced during full implementation, the BleControlPeripheral abstraction, its callers, and the QR wiring would all have been built on a false premise and needed rework.

**Suggested improvement:** audit-transport's "Then" section (reuse-over-rebuild) should add: when the plan depends on a native/OS integration the codebase does NOT already exercise (here: WinRT GATT — the repo drove WinRT only for Wi-Fi Direct, never Bluetooth), gate the plan on a throwaway spike that proves the exact risky calls end-to-end before writing the production abstraction. The audit tells you what exists to reuse; it cannot tell you whether a *new* native path behaves as the docs claim.

**Principle:** An audit de-risks reuse, not novelty. The most expensive defects hide in the one platform call your codebase has never made before, and no amount of reading existing code reveals them — only running the specific call does. Order the work so the riskiest unproven assumption is tested first and cheapest, before any abstraction hardens around it.

### Observation 21: Literal-diff technique verifies refactors preserved wire bytes cheaply
**Status:** ACTIONED — Applied to audit-transport (weekly review 2026-07-11)

**Date:** 2026-07-03
**Session context:** Read-only cross-platform protocol/crypto audit after the TransferDaemon god-class split (commits d200032/b645508/50d141f)
**Skill:** New skill candidate: refactor-wire-audit (or fold into audit-transport)
**Type:** open-source
**Phase/Area:** Verification methodology

**Issue:** Verifying that a large refactor did not change wire behavior by reading old and new code side by side is slow and error-prone. What worked: diffing the *sorted sets of string literals* and *numeric constants* (timeouts, buffer sizes, ports) between the pre-refactor git blob (`git show <commit>:<path>`) and the union of the new split files. This caught that only intended tokens (fgcm2, OFFER_CHALLENGE) were added, surfaced an encoding regression (mojibake + a NUL byte making a crypto file "binary" to grep), and cleared everything else in minutes.

**Suggested improvement:** Add to audit-transport (or a new refactor-audit skill): "To prove a refactor kept wire bytes/timeouts identical, diff `grep -o '\"[^\"]*\"' | sort -u` and a timeout/constant regex between the pre-refactor git revision and the union of successor files; then read only the residue." Also: prefer git blobs over `*_temp.kt` scratch copies — the scratch copies here were mid-refactor snapshots, not true baselines.

**Principle:** For behavior-preservation audits, diff extracted invariants (literals, constants) across revisions instead of prose-reading both versions; the residue is the complete work list.

### Observation 22: Usage-report suggestions must be deduped against existing config before implementing
**Status:** ACTIONED — Seeded cross-cutting-principles.md (principle 3) (weekly review 2026-07-11)

**Date:** 2026-07-03
**Session context:** Implementing the /insights usage report's suggested improvements (CLAUDE.md rules, a /ship skill, build hooks) for this project.
**Skill:** New skill created outside weekly review: ship (internal); also relevant to any future "apply tool-generated recommendations" task
**Type:** internal
**Phase/Area:** Triage before implementation

**Issue:** The report's four suggestions were generated without knowledge of the project's current config. Two were already covered (a Stop hook already compiles Android Kotlin each turn; the `verify` skill already encodes both-platform builds), and one literal suggestion (PostToolUse gradle build after every Edit) would have been actively harmful — minutes of latency per edit. The genuinely new pieces were the Git Workflow rule (3 stale worktrees exist; wrong-worktree edits were real past friction), the Quality Gates rules, and a `ship` skill composing the existing `code-review` + `verify` skills rather than duplicating their commands. User confirmed both dedup decisions via explicit options.

**Suggested improvement:** When implementing recommendations from a generated report (insights, audit tooling, linters), first inventory what the project already has (settings.json hooks, existing skills, CLAUDE.md sections), classify each suggestion as already-covered / new / harmful-as-stated, and compose new skills from existing ones by reference instead of duplicating their steps.

**Principle:** Generated recommendations describe observed behavior, not current configuration — they lag whatever was recently fixed. Triage against the live config first; the deliverable is the delta, and new automation should reference existing automation, not restate it.

### Observation 23: Session-recovery from transcript JSONL is a repeatable workflow
**Status:** ACTIONED — New skill session-recovery created (user-level) (user-requested skill build 2026-07-11)

**Date:** 2026-07-03
**Session context:** A chat window was force-killed (claude-mem issue) mid-task; user asked a new window to check whether the tasks finished.
**Skill:** New skill candidate: session-recovery
**Type:** open-source
**Phase/Area:** Whole workflow

**Issue:** Recovering a killed session's state required a specific sequence with no skill coverage: list `~/.claude/projects/<project>/*.jsonl` by mtime, identify the dead session (ends with "Request interrupted by user" seconds before the new window opened), extract its user prompts / TodoWrite state / tool activity with a small Node script over the JSONL, then resume the unfinished work — reusing the dead session's scratchpad artifacts (its check script was still on disk and reusable).

**Suggested improvement:** Create a small `session-recovery` skill: (1) locate candidate transcripts by mtime, (2) canned JSONL extraction snippets for prompts/todos/tool-calls, (3) check the dead session's scratchpad dir for reusable artifacts, (4) report finished vs unfinished, then resume. Timezone note: transcript timestamps are UTC while file mtimes are local — compare accordingly.

**Principle:** Transcript JSONL files plus the per-session scratchpad are a complete crash log; a killed session's progress can be reconstructed mechanically instead of re-doing the task from scratch.

### Observation 24: Review findings should be classified introduced-by-diff vs pre-existing to drive commit scope
**Status:** ACTIONED — Applied to ship (weekly review 2026-07-11)

**Date:** 2026-07-04
**Session context:** "commit changes" on a large uncommitted diff. The ship skill's code-review pass (8 finders + 6 verifiers) surfaced ~10 confirmed findings across both platforms — several were missing Windows mirrors of Android changes, one was a regression the diff introduced, several were pre-existing bugs the diff merely widened.

**Skill:** ship (and code-review)
**Type:** open-source
**Phase/Area:** Verify phase / post-review triage

**Issue:** The literal request was "commit changes," but the review ballooned it into a security-fix project. The decisive signal for scoping was whether each finding was NEW (introduced by this diff), a HALF-DONE cross-platform mirror, or PRE-EXISTING. The verifier agents that explicitly reported "preexisting: yes/widened + why (git show HEAD)" made the fix/defer/commit-as-is call obvious; the ones that didn't left it ambiguous. I asked verifiers to compare against the shipped baseline, then used AskUserQuestion to let the user pick scope (fix diff-tied issues / fix everything / commit as-is) rather than silently expanding scope.

**Suggested improvement:** In the code-review skill's verify phase, require each verdict to carry an origin tag — `introduced` | `preexisting` | `widened` — established by diffing against the merge-base/HEAD, not just CONFIRMED/PLAUSIBLE/REFUTED. In the ship skill, when confirmed findings exceed the literal request, surface a scope decision (fix diff-tied only / fix all / commit as-is) with that origin classification, instead of auto-fixing everything or committing blind.

**Principle:** A finding's severity doesn't determine whether it belongs in the current commit — its origin does. Separating "this diff broke it" from "this was already broken" is what lets a review stay scoped to the change under review instead of turning every commit into an open-ended cleanup.

### Observation 25: Recurring 10-language string-addition ritual could be a project skill
**Status:** ACTIONED — New skill add-i18n-strings created (weekly review 2026-07-11)

**Date:** 2026-07-04
**Session context:** Adding a System-Status-style empty state (+ Go to Pairing button) to the Send-to-Connected-Devices flow on Android and Windows, including 3 new string keys and a tutorial-text change in 10 languages on both platforms.
**Skill:** New skill candidate: add-i18n-strings (project-internal)
**Type:** internal
**Phase/Area:** Cross-platform i18n string additions

**Issue:** Any user-visible string change in this repo fans out to 10 Android per-locale strings.xml files plus 10 language blocks in the Windows translation.json.txt. The mechanical part is error-prone in known ways: batch Edits need per-locale unique anchor lines (the translated neighbour string works well), Android XML needs \' apostrophe escaping (French), the Windows file's gson-per-block parser needs comma discipline, and completeness is only provable with a count grep (keys x languages). This session also confirmed Windows tutorial step text is hardcoded English (not in translation.json.txt), while Android tutorial text is fully localized — an asymmetry easy to forget.

**Suggested improvement:** Create a small internal skill (or CLAUDE.md section) codifying the ritual: (1) anchor batch edits on per-locale unique neighbour lines; (2) escape apostrophes in Android XML; (3) insert into every language block of translation.json.txt and verify with `grep -c` = keys x 10; (4) remember the repo-root translation.json.txt is a stale copy (classpath loads Window Ver/src/main/resources); (5) Windows tutorials are English-only — do not add tutorial keys there.

**Principle:** When a codebase has a fan-out invariant (one logical change = N mechanical copies), capture the copy discipline and its verification command as a checklist; correctness by grep-count beats correctness by memory.

### Observation 26: verify skill — combine both platform builds into one Gradle invocation
**Status:** ACTIONED — Applied to verify (weekly review 2026-07-11)

**Date:** 2026-07-04
**Session context:** Send-flow UI polish (device-card tray + Go to Pairing restyle, Android + Windows)
**Skill:** verify
**Type:** internal
**Phase/Area:** Steps 1-2 (build commands)

**Issue:** The verify skill lists the Android and Windows builds as two separate gradlew invocations. Running them as one command (`./gradlew.bat :app:compileDebugKotlin :window-ver:compileKotlin`) verified both platforms in a single 9s run, paying Gradle configuration cost once instead of twice.

**Suggested improvement:** In the verify skill, add the combined invocation as the default for cross-platform changes, keeping the separate commands for single-platform work.

**Principle:** When a verification ritual always runs multiple tasks of the same build tool, batching them into one invocation amortizes the tool's startup/configuration overhead.

### Observation 27: Ship skill not invoked when its ritual was performed manually
**Status:** ACTIONED — Applied to ship (weekly review 2026-07-11)

**Date:** 2026-07-05
**Session context:** Gallery-visibility fix for received photos/videos (publish to Pictures/Movies), user asked to "perform git commit after finishing"
**Skill:** ship
**Type:** internal
**Phase/Area:** Skill activation / end-of-task ritual

**Issue:** The task ended with the exact sequence the ship skill encodes (worktree check, code-review skill on the pending diff, build verify, scoped commit), and the steps were all performed — but by following CLAUDE.md quality gates manually rather than invoking /ship. The outcome was equivalent this time, yet the skill's codified ritual (e.g. its exact ordering and any future improvements to it) was bypassed.

**Suggested improvement:** In the ship skill description or CLAUDE.md skill-activation rule, add the trigger phrase "commit after finishing" / "commit when done" alongside "ship"/"build and commit", so end-of-task commit requests route through the skill instead of ad-hoc reimplementation of its steps.

**Principle:** When a user request matches a skill's ritual semantically but not lexically, description trigger phrases should cover the common paraphrases — otherwise the agent re-derives the ritual by hand and silently forks from the skill's maintained version.

### Observation 28: Release-notes generation is a recurring, localizable deliverable
**Status:** ACTIONED — New skill release-notes created (project) (user-requested skill build 2026-07-11)

**Date:** 2026-07-05
**Session context:** User asked for English release notes covering 2.1 -> 3.5.1 during release prep (keystore creation, GitHub lane).
**Skill:** New skill candidate: release-notes
**Type:** internal
**Phase/Area:** Release workflow (Lane A GitHub / Lane B Play)

**Issue:** Release notes had to be assembled manually from CHANGELOG.md, post-version-bump commits, and pre-repo-history knowledge gaps (2.x era predates the migrated repo). The phrasing "for english" implies localized notes in the app's 9 other locales will be needed next, and Play requires per-locale "what's new" text with a 500-char limit.

**Suggested improvement:** A skill that: reads CHANGELOG.md + commits since the last release tag, drafts user-facing notes (not internal codenames like FGCM2), flags history gaps instead of inventing content, applies the humanizer voice, and optionally emits per-locale variants + a 500-char Play whatsnew cut. Should note the app-id rebrand caveat for pre-3.2 upgraders.

**Principle:** Deliverables that recur every release cycle and fan out across locales (notes, store listings) are skill candidates the first time they appear, because the second occurrence lands under release-deadline pressure.


### Observation 29: Runtime-quirk workarounds need runtime verification, not hard-coded compensation
**Status:** ACTIONED — Seeded cross-cutting-principles.md (principle 2) (weekly review 2026-07-11)

**Date:** 2026-07-05
**Session context:** Fixing the Windows maximize button sizing the window to ~2/3 of the screen at 150% display scale
**Skill:** New skill candidate / cross-cutting principle
**Type:** open-source
**Phase/Area:** Platform-quirk workarounds in desktop UI code

**Issue:** A prior session measured a JBR HiDPI quirk (setMaximizedBounds allegedly double-scaling) and baked an unconditional pre-divide into the maximize path. On the user's live setup the runtime scales once, so the "fix" itself became the bug: every maximize landed at workArea/scale. Two contradictory observations of the same runtime existed and the hard-coded compensation picked the wrong one.

**Suggested improvement:** When compensating for a platform/runtime quirk that can't be verified across environments, prefer self-calibration: apply the spec-correct behavior first, measure the actual outcome, and only then apply the compensation (cached per session). The maximize fix now does exactly this.

**Principle:** A workaround based on a single environment measurement encodes that environment forever. If the quirk's presence can be detected cheaply at runtime, detect it — don't assume it.


### Observation 30: Model-fallback discipline belongs in always-loaded config, not in skills
**Status:** ACTIONED — Already implemented in global + project CLAUDE.md on 2026-07-06 (weekly review 2026-07-11)

**Date:** 2026-07-06
**Session context:** User asked to edit global and project CLAUDE.md so that when Fable 5 leaves the subscription tier, Opus/Sonnet sessions inherit the established working discipline ("logic base") and land between stock-Opus and Fable quality.
**Skill:** All skills
**Type:** open-source
**Phase/Area:** Skill/config architecture — where behavioral rules should live

**Issue:** The discipline that distinguishes a stronger model's sessions (verify before claiming, adversarial self-review of own diffs, root-cause errors instead of retrying, spec-before-code) mostly lives in habits, not files. Encoding it as a skill would be fragile: a weaker model is precisely the one most likely to skip invoking an optional skill. The fix was to encode it in CLAUDE.md (global baseline + project overlay conditioned on "check which model you are"), which is loaded unconditionally every session.

**Suggested improvement:** When capturing methodology meant to survive a capability downgrade, put the enforcement in always-loaded config (CLAUDE.md / project instructions) with a self-check condition the model can evaluate (its own model name from environment info), and keep skills as the detailed reference the config points to. Same pattern as the existing Operating Modes persona-by-mode conditional.

**Principle:** Rules that must hold when the agent is weakest cannot depend on the agent choosing to load them. Discipline meant as a floor goes in unconditional context; skills are for depth, not for enforcement.


### Observation 31: Manual Claude Code plugin install workflow on CLI-less machines
**Status:** ACTIONED — New skill plugin-ops created (user-level) (user-requested skill build 2026-07-11)

**Date:** 2026-07-06
**Session context:** Installing the claude-plugins-official marketplace plus kotlin-lsp, skill-creator, and context7 plugins on a machine where the `claude` CLI is not on PATH (VSCode-extension-only sessions).
**Skill:** New skill candidate: plugin-ops (or fold into dev-machine memory)
**Type:** internal
**Phase/Area:** Environment tooling / plugin management

**Issue:** `claude plugin marketplace add` / `claude plugin install` are unavailable, but the whole flow is reproducible by hand: (1) clone marketplace repo into `~/.claude/plugins/marketplaces/<name>` and register it in `known_marketplaces.json`; (2) copy plugin dir into `~/.claude/plugins/cache/<marketplace>/<plugin>/<version>`; (3) for `strict: false` plugins whose manifest lives only in marketplace.json (e.g. kotlin-lsp's lspServers block), synthesize `.claude-plugin/plugin.json` in the cache copy; (4) append to `installed_plugins.json`; (5) add `<plugin>@<marketplace>: true` to `enabledPlugins` in `~/.claude/settings.json`. Separately: kotlin-lsp's `kotlin-lsp.cmd` launcher is deprecated and prints a warning that could corrupt LSP stdio — shim `bin\intellij-server.exe` instead.

**Suggested improvement:** If plugin installs recur on this machine, capture the five-step recipe as a small internal skill; until then the dev-machine-tooling memory note suffices.

**Principle:** For any tool that speaks a protocol over stdio, verify the launcher emits nothing extra on stdout before wiring it in — a version-check with streams separated (`2>/dev/null`) is a 5-second test that prevents silent protocol corruption.

### Observation 32: Ship skill has no untracked-file/secrets sweep before commit
**Status:** ACTIONED — Applied to ship (weekly review 2026-07-11)

**Date:** 2026-07-06
**Session context:** User asked to "commit if the last committed version is not the latest". Repo root contained an untracked "JDK Passkey/" folder holding the ADB private key, debug.keystore, and the new release keystore (clipboardshare_key.jks), plus a 19 MB app-release.aab in "Installation package (Latest only)/" — none of it gitignored (adbkey has no extension, so the *.jks/*.keystore patterns missed it).
**Skill:** ship
**Type:** internal
**Phase/Area:** Pre-commit checks (between step 1 worktree confirm and step 4 commit)

**Issue:** The ship skill reviews and builds the tracked diff but never looks at untracked files. A `git add -A` or `git add .` at step 4 would have committed private key material and a 19 MB build artifact. It was caught this session only because the commit request happened to coincide with an open-sourcing question that made secrets top-of-mind.

**Suggested improvement:** Add a step to ship before committing: run `git status --porcelain`, inspect untracked paths for key material (keystores, *key*, *.pem, extensionless private keys), build artifacts (.aab/.apk/.msi/large binaries), and personal files; extend .gitignore for anything that must never land; stage explicitly by path rather than `git add -A`.

**Principle:** A commit ritual that only validates the diff-to-be-committed is blind to the adjacent failure mode — accidentally committing what should never be tracked. Pre-commit hygiene needs one glance at the untracked set, not just the staged one.

### Observation 33: compile-only verify misses AAPT2 resource errors
**Status:** ACTIONED — Applied to verify (weekly review 2026-07-11)

**Date:** 2026-07-10
**Session context:** Auditing + fixing Gemini's Android 3.6.2 Lite design pass; added 8 localized string keys across 10 strings.xml files, then build-verified.
**Skill:** verify
**Type:** internal
**Phase/Area:** Android build gate — which Gradle task to run

**Issue:** The change touched string resources (added keys to 10 locale files) and layouts. `:app:compileDebugKotlin` passed clean (it runs parseDebugLocalResources / generateDebugRFile, which only need resource NAMES for R.java). The real defect — an unescaped apostrophe in a French value (`Options d'alimentation`) — only surfaced in `:app:assembleDebug` at the `mergeDebugResources` (AAPT2 flatten) step, as "Invalid unicode escape sequence in string". Had I trusted the compile-only check, I'd have committed a build-breaking resource file.

**Suggested improvement:** In the `verify` skill / CLAUDE.md build-gate guidance, make the task selection conditional on what changed: if the diff touches `res/**` (strings, layouts, drawables, values), the verification MUST run `:app:assembleDebug` (or at least `:app:mergeDebugResources`), NOT `:app:compileDebugKotlin`. Compile-only is only sufficient for pure-Kotlin changes. Note the specific trap: unescaped `'` / `&` / stray `%` / `\` in translated values pass compile but fail AAPT2 flatten.

**Principle:** "It compiles" and "it builds" are different guarantees. A compile-only fast path validates one toolchain (Kotlin) and silently skips others (resource compilation, manifest merge, dexing). Pick the verification task by the class of files changed, not by whichever runs fastest — a green check on the wrong task is worse than no check.

### Observation 34: "BUILD SUCCESSFUL" with UP-TO-DATE tasks is a cached green, not fresh proof
**Status:** ACTIONED — Applied to verify (weekly review 2026-07-11)

**Date:** 2026-07-11
**Session context:** Final functionality + security audit producing a stakeholder/CTO report and a security/market report. The report needed real build/test proof, per CLAUDE.md "proof over assumption."
**Skill:** verify (also relevant to ship)
**Type:** internal
**Phase/Area:** Build-verification / reporting the result

**Issue:** Running `:app:testDebugUnitTest :app:assembleDebug` returned BUILD SUCCESSFUL, but the Windows `:window-ver:test` task came back `UP-TO-DATE` — Gradle skipped it because inputs were unchanged since a prior run. Reporting that as "tests pass, verified this run" would have overstated it: a cached green is Gradle asserting the last run passed, not evidence that tests executed now. Forcing `:window-ver:cleanTest :window-ver:test` produced a genuine execution (2 tasks executed, 75 tests, 0 failures), and parsing the JUnit XML under `build2/test-results/test/` gave exact counts for the report.

**Suggested improvement:** In the `verify` skill, add a rule: when a test task reports `UP-TO-DATE` (or `NO-SOURCE`) and the deliverable needs genuine proof, force a real run with `cleanTest`/`--rerun-tasks` before claiming tests passed this session. For precise numbers, aggregate `tests=/failures=/errors=` from the JUnit XML result files rather than trusting the console summary. Distinguish "compiles + packages" (assembleDebug genuinely ran) from "tests executed" (may be cached).

**Principle:** "Verified" means a step ran this session and its output was observed. A build tool's incremental cache can make a stale pass look like a fresh one; when proof is the deliverable, defeat the cache deliberately and read the machine-readable result, not the summary line.

### Observation 35: Environment-state claims in reports go stale faster than code claims
**Status:** ACTIONED — New skill report-verify created (user-level) (user-requested skill build 2026-07-11)

**Date:** 2026-07-11
**Session context:** Verifying/fixing REPORT-SECURITY-MARKET-2026-07-11.md against the codebase (parallel session fixing the overview report)
**Skill:** New skill candidate: report-verify (or a rule for any future report/audit skill)
**Type:** internal
**Phase/Area:** Claim verification pass

**Issue:** Every code-referenced claim in the security report (line numbers, constants, cross-platform comparisons — ~25 checks) survived verification, but the one defect found was an environment-state claim: "no Android signing key" was false — clipboardshare_key.jks exists outside the repo; the real gap is keystore.properties missing at the repo root. Code claims were verified when the report was written; environment/status claims (files outside the repo, signing state, deployment state) were asserted from stale context and never re-checked.

**Suggested improvement:** When verifying or writing any report/audit that mixes code findings with project-status statements, run a separate pass over the status claims (signing, distribution, deployed versions, external files) checking filesystem + project memory + release docs — not just the source tree. Project memory (MEMORY.md) flagged this one before the filesystem check confirmed it.

**Principle:** Source-tree claims are anchored by line references and get verified naturally; environment-state claims have no anchor and rot silently. Any verification checklist should treat them as a distinct, higher-risk claim class.

### Observation 36: JS multiline regex ^ anchors inside CRLF pairs — line-removal scripts must split-and-filter instead
**Status:** ACTIONED — Seeded cross-cutting-principles.md (principle 4) + add-i18n-strings (rule 4) (weekly review 2026-07-11)

**Date:** 2026-07-11
**Session context:** Punch-list fix session (report REPORT-APP-OVERVIEW-2026-07-11); removing an orphaned i18n key from 12 resource files with a Node one-liner.
**Skill:** New skill candidate: safe-batch-text-edit (or fold into a general scripting-discipline note)
**Type:** open-source
**Phase/Area:** Mechanical multi-file text edits via Node

**Issue:** A Node regex `/^\s*"key":.*\r?\n/gm` used to delete one line per file silently corrupted two CRLF files: in JavaScript multiline mode `^` also matches BETWEEN the `\r` and `\n` of a CRLF pair, so the match consumed the previous line's `\n` plus the target line, joining the preceding and following lines with a stray `\r`. The damage only surfaced because `git diff --numstat` was checked after the edit (net -20 lines instead of -10, whole-file EOL noise). The same regex worked fine on the ten LF files.

**Suggested improvement:** For scripted line removal, never use multiline-anchored regex replacement. Split keeping line terminators (`content.split(/(?<=\n)/)`), filter by `line.includes(needle)`, rejoin, and assert the expected removal count before writing (abort on mismatch). Always verify with `git diff --numstat` (expected N deletions, 0 insertions) immediately after any scripted batch edit.

**Principle:** Scripted batch edits need a post-write verification gate exactly like builds do: assert the expected delta (count, numstat) rather than trusting the transform. JS `^`/`$` in multiline mode treat `\r` as a line terminator — any repo with mixed EOLs will corrupt silently under anchored-regex line deletion.

### Observation 37: Delta assertions must verify placement, not just count — stale indexes after splice corrupted a batch edit that "passed"

**Status:** OPEN
**Date:** 2026-07-11
**Session context:** Weekly review + user-requested skill build; a Node script marking observation statuses inserted lines via splice() while iterating over pre-computed heading indexes, shifting every later index. The count assertion (entries updated == expected) passed while several status lines landed in the wrong entries; only the cross-total reconciliation (33 ACTIONED + 6 OPEN = 39 lines for 36 entries) exposed it. Repair = strip-all + re-insert in reverse index order + per-entry pairing verification.
**Skill:** All skills (cross-cutting-principles.md, principle 4)
**Type:** open-source
**Phase/Area:** Scripted batch edits — the assertion itself

**Issue:** Principle 4 (post-write delta assertion) as first written is satisfiable by a wrong edit: counting attempted mutations validates the loop ran, not that each mutation landed where intended. Positional edits over a mutating array need either reverse-order processing (later-index-first, so splices never shift pending targets) or index recomputation per mutation — and the assertion must check *pairing* (each target adjacent to its intended content), not aggregate counts, plus a cross-total reconciliation (lines added == entries touched).

**Suggested improvement:** Strengthen cross-cutting principle 4: the post-write assertion must verify placement/pairing per target (e.g. "status line directly follows its own heading and matches expected text"), and reconcile cross-totals; process positional array mutations in reverse index order.

**Principle:** An assertion that can pass while the edit is wrong is a false gate. Assert the postcondition you actually need (each item paired with its target), not a proxy for it (how many edits ran).

### Observation 38: Receive-path fixes need an open-path parity check

**Date:** 2026-07-11
**Session context:** Auditing two agents' receive-path fixes (same-name data-loss, API-29 staging) and closing the history-open gap they left
**Skill:** scoped-bugfix-subagent
**Type:** internal
**Phase/Area:** Post-fix verification

**Issue:** The agents' API-29 fix rerouted where received files physically land (app-private staging + MediaStore publish) but did not touch the history "open" lookup, which still probed raw java.io paths — so every received file became unopenable from history on API 29. A second agent flagged it as a caveat rather than fixing it. The same class of drift was then found INSIDE the fix for that caveat: the first MediaStore lookup only covered the gallery routing, missing "Same as files" media.

**Suggested improvement:** Add a rule to scoped-bugfix-subagent: when a fix changes where/how data is stored (path, collection, naming), enumerate every READ path of that data (open/resume/probe/lookup) and update or explicitly clear each one in the same change. A storage-side fix is not complete until the lookup side is proven to find the relocated data under every routing branch.

**Principle:** Write-path and read-path form one contract; fixing one side of a storage contract creates a latent bug on the other side that passes builds and unit tests, because the drift only shows at runtime on the affected platform.

### Observation 39: task-observer's monolithic body cost ~18k tokens per session; restructured to hot-path + on-demand references

**Status:** ACTIONED — task-observer SKILL.md restructured 2026-07-11 (73.3 KB → 8.7 KB; rare paths moved to references/, verbatim backup kept)
**Date:** 2026-07-11
**Session context:** User noticed task-observer "taking some portion of the usage." Measured: SKILL.md was 75 KB / 1,524 lines, fully loaded into context at the start of every tool-using session per the CLAUDE.md activation rule.
**Skill:** task-observer
**Type:** open-source
**Phase/Area:** Skill structure / per-session token cost

**Issue:** A skill invoked unconditionally every session paid its FULL body cost every session, but most of that body covers rare paths: the weekly-review procedure (runs ~weekly), skill taxonomy/licensing/attribution (only when authoring open-source skills), Cowork mount/staging and handoff-doc mode (environment-specific). The per-session hot path — observation logging protocol, session-start checks, surfacing, the acting gate — is a small fraction of the text.

**Suggested improvement:** Progressive disclosure for always-on skills: SKILL.md carries only what every session needs, with explicit "read references/X.md first" pointers at each rare-path trigger (review due → weekly-review.md; creating a skill → authoring.md). Keep the load-bearing templates and enforcement rules (logging format, numbering discipline) in the hot path. Preserve the original as a verbatim backup and keep attribution/licence intact (CC BY 4.0). Worth suggesting upstream via the skill's GitHub feedback channel.

**Principle:** For a skill that loads every session, per-session token cost is part of its design. Size the always-loaded body to the always-needed path; everything conditional should cost tokens only when its condition fires. The same lean-content test the skill applies to changelogs applies to its own rare-path procedures.

### Observation 40: Context-cost audit of always-on machinery — fan-out model tiering and full-dump bans are the levers

**Status:** ACTIONED — CLAUDE.md Review economy + read_graph ban added; ship effort tiers aligned; 4 longest skill descriptions trimmed (2026-07-11)
**Date:** 2026-07-11
**Session context:** User asked to optimize the memory MCP and code-review after noticing usage pressure (a session limit was hit earlier the same day during a high-effort review fan-out).
**Skill:** All skills (cost-awareness); ship; CLAUDE.md
**Type:** open-source
**Phase/Area:** Per-session and per-invocation token cost

**Issue:** Three distinct cost classes were conflated as "the skill is expensive": (1) always-loaded context (skill descriptions, CLAUDE.md — paid every session), (2) on-invocation bodies (paid when a skill runs; fixed by progressive disclosure), and (3) fan-out execution (code-review's 8 finders + verifiers all inheriting the session's top-tier model — the actual budget killer). The memory MCP turned out to already be cheap (deferred schemas, 37 KB graph); its only exposure was the full-dump call and observation verbosity.

**Suggested improvement:** When optimizing agent-tooling cost, audit by class: measure always-loaded bytes first, then invocation bodies, then fan-out policy. Standard levers per class: trim/tier descriptions; hot-path + references split; model-tier the fan-out (finder/verifier subagents on a smaller model, adjudication in the main loop) and gate effort levels on actual risk classes, not directory names. Ban whole-store dump calls (read_graph) in always-loaded config.

**Principle:** Subagents inherit the session model by default, so a fan-out multiplies the most expensive model by the agent count unless explicitly tiered — candidate generation rarely needs the top model; adjudication does. Match model tier to the stage's judgment density, and spend measurement before cutting: the component the user suspects (memory) may be cheap while the one that "works fine" (review fan-out) is the drain.

### Observation 41: Per-language audit fan-outs need a cross-locale sweep on every flagged key

**Status:** OPEN
**Date:** 2026-07-12
**Session context:** 9-language translation accuracy audit (one sonnet agent per locale, Android strings.xml + Windows translation.json.txt)
**Skill:** New skill candidate: translation-audit (or fold into add-i18n-strings)
**Type:** open-source
**Phase/Area:** Adjudication after fan-out

**Issue:** Agents' coverage varied: the German agent missed a "paired"-dropped string that the Spanish/Japanese agents caught on the identical key in their locales. A single cheap grep of the flagged key across ALL locale files completed the affected-language list and exposed one fully systemic finding (a key where all 9 locales translate an outdated English revision).

**Suggested improvement:** In any per-language audit workflow, add a mandatory adjudication step: for every key flagged by ANY agent, grep that key across all locales before reporting, and treat "same defect in N locales" as one systemic finding with an exact affected list.

**Principle:** In symmetric fan-outs (same check, different shard), any finding from one shard is a hypothesis about every other shard — verify it across all shards mechanically instead of trusting each shard's independent recall.

### Observation 42: Duplicate-key translation disputes are settled by code usage, not language judgment

**Status:** OPEN
**Date:** 2026-07-12
**Session context:** Same translation audit — zh-CN and zh-TW agents returned opposite verdicts on which "Transaction Details" translation was wrong
**Skill:** New skill candidate: translation-audit (or fold into add-i18n-strings)
**Type:** open-source
**Phase/Area:** Adjudication after fan-out

**Issue:** Two sibling keys share the English text "Transaction Details". The zh-CN agent called the financial rendering wrong; the zh-TW agent called the transfer rendering wrong. Grepping code usage settled it: the visible key is a file-transfer detail header (transfer reading correct), and the other key is referenced nowhere — dead, downgrading the finding entirely.

**Suggested improvement:** When agents dispute a translation of an ambiguous source string, or when two keys share source text, check where the key is used in code (and whether it is used at all) before adjudicating severity or direction.

**Principle:** The referent of an ambiguous UI string lives in the code that displays it; translation correctness disputes are resolved by usage lookup, and unused keys cap severity at informational.

## Release 3.6.4/3.6.2 Observations
- **Version Tracks Diverged**: Android is at 3.6.4 (versionCode 26) and Windows is at 3.6.2. They are on separate version tracks since 3.5.1.
- **Permanent Android Link Rule**: The ndroid.url in website/js/config.js is permanently set to the GitHub releases URL. Never revert it to '#'. It may 404 during testing, which is accepted.
- **jpackage dependency**: jpackage needs the Adoptium JDK (e.g., at ~/.gradle/jdks/...), not the JBR. The JBR lacks jpackage.exe.

---

### Observation 43: Deleting an Android string key needs a res/-wide reference sweep, not just a Kotlin grep

**Status:** OPEN
**Date:** 2026-07-14
**Session context:** Task 4 replaced the in-app "other platform" pages. I deleted two now-unused string keys (`clipboard_share_windows_title`, `desktop_app_in_development_desc`) after grepping only `app/src/main/java` for references and finding they were used solely by the Compose screen I rewrote. The `:app:assembleDebug` then failed at `processDebugResources`: a dead XML layout (`activity_windows_version.xml`, superseded by the Compose screen but never deleted) still referenced both keys via `@string/…`, and AAPT2 compiles every layout regardless of whether it is inflated.

**Skill:** add-i18n-strings
**Type:** open-source
**Phase/Area:** Removals ("split-and-filter") — pre-removal reference check

**Issue:** The Removals guidance covers *how* to delete a key safely (split-and-filter, count assertion) but not *when it is safe* — it doesn't require confirming zero remaining references first, and a code-only grep misses `@string/key` uses in layouts, menus, drawables, and `AndroidManifest.xml`. Those still fail the AAPT2 link even when the referencing view is dead.

**Suggested improvement:** Add a pre-removal step to the Removals section: before deleting any Android string key, grep the whole `app/src/main/res` (and `AndroidManifest.xml`) for `@string/<key>` and `R.string.<key>`, not just Kotlin/Java — expect zero hits outside the `values*/strings.xml` definitions. Same idea for Windows keys referenced from non-code assets.

**Principle:** A resource key's references are spread across code *and* declarative assets; deletion safety requires sweeping every referrer surface (layouts/menus/manifest), because the compiler links dead declarative files too and a code-only grep gives false confidence.

### Observation 44: Reuse the other platform's existing translations when porting a feature

**Status:** OPEN
**Date:** 2026-07-16
**Session context:** Porting the Windows in-app update page to Android (Android "Updates" screen). The feature already existed on Windows, whose translation.json.txt carried the same logical strings (nav_updates, update_checking, update_up_to_date, update_current/latest_version_label, update_available_label, update_check_again/failed/try_again) translated into all 10 languages.
**Skill:** add-i18n-strings
**Type:** internal
**Phase/Area:** Sourcing translations for a new key set

**Issue:** When the added strings mirror a feature that already shipped on the other platform, the correct source for the 9 non-English locales is the OTHER platform's existing translation file, not fresh machine translation. I grepped the 9 shared keys out of Window Ver/.../translation.json.txt and mapped them to the Android keys 1:1 (only the Android-specific update_play_button = "Update on Google Play" needed new translation), keeping wording identical across platforms.

**Suggested improvement:** Add a step to the skill's "Sibling updates that ride along" section: before translating fresh, check whether the sibling platform already localizes the same concept and lift those values verbatim (map by meaning, not key name — Windows nav_updates -> Android menu_updates). Preserves cross-platform wording parity for free.

**Principle:** When localizing a feature that already exists on a sibling platform, harvest the sibling's existing translations rather than re-translating — it is both cheaper and keeps user-facing wording consistent across platforms.

### Observation 45: `</resources>` is a valid append anchor for Android strings.xml

**Status:** OPEN
**Date:** 2026-07-16
**Session context:** Same Android "Updates" i18n fan-out. The skill's ritual says anchor each insertion on "the translated value of a neighbouring key ... never the English text or a line number."
**Skill:** add-i18n-strings
**Type:** internal
**Phase/Area:** Ritual step 1 (insertion anchoring)

**Issue:** For a pure append of a new key block (no ordering requirement relative to a neighbour), anchoring on `lastIndexOf('</resources>')` is simpler and uniformly correct across all 10 locale files (the closing tag is identical everywhere, unlike per-locale translated values which must be known per file). Verified clean: grep -c = 10 per key, git diff --numstat = 10/0 per file, no EOL churn. The stated "neighbour translated value" anchor is only necessary when the new key must sit at a specific position.

**Suggested improvement:** Clarify step 1: the neighbour-value anchor is for position-sensitive inserts; for order-agnostic appends, `</resources>` (Android) / the block's closing brace (Windows) is an acceptable and simpler anchor. Still forbid line-number and English-text anchors.

**Principle:** Match the insertion anchor to the requirement — a structural end-marker is fine for order-agnostic appends; reserve neighbour-relative anchors for position-sensitive inserts.

### Observation 46: add-i18n-strings must escape `&` as `&amp;` in Android XML, not just apostrophes

**Status:** OPEN
**Date:** 2026-07-16
**Session context:** Android in-app update page — adding "Download & Install" (key update_download_button) and other strings across 10 Android strings.xml locales via a Node insertion script.
**Skill:** add-i18n-strings
**Type:** internal
**Phase/Area:** Ritual step 2 (Android XML rules) / the insertion script's escaping function

**Issue:** The skill's Android rules call out escaping apostrophes as `\'` (French AAPT2 breakage) but say nothing about ampersands. A value like "Download & Install" (and "Unduh & Instal", "Herunterladen & Installieren") contains a literal `&`, which is an XML entity start — unescaped it fails mergeDebugResources with "The reference to entity ... must end with ';'". A first-pass esc() that only handled apostrophes would have shipped broken XML; caught it before build. Correct escape: `&` -> `&amp;` (apply FIRST, before other entity replacements), alongside the existing apostrophe rule.

**Suggested improvement:** Extend step 2's Android XML rules and any example esc() to: escape `&` -> `&amp;` first, then apostrophe -> `\'`; also note `<`/`>` -> `&lt;`/`&gt;` if ever present. The reused Windows JSON stores `&` literally, so any string lifted from there with an ampersand must be re-escaped for Android XML.

**Principle:** When targeting XML resource files, escape the ampersand as `&amp;` before any other entity — it is the highest-frequency breakage after the apostrophe and is easy to miss when values are lifted verbatim from a JSON (non-XML) source.
