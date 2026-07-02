# Skill Observation Log

Observations captured during task-oriented work. Each entry identifies a
potential skill improvement or new skill opportunity.

**Status key:** OPEN = not yet actioned | ACTIONED = skill updated/created |
DECLINED = user decided not to pursue

---

## 2026-07-02

### Observation 1: Multi-phase audit workflow lost its entire verify phase to a session limit; orchestrator self-verification saved it

**Date:** 2026-07-02
**Session context:** Read-only improvement audit of the Android app, Windows module, and website via a 3-phase Workflow (find → completeness-critic → adversarial-verify). All 4 critic agents and all 21 verify agents failed with "You've hit your session limit"; only the 9 first-round finder agents completed, leaving 102 findings marked "verifier unavailable — unreviewed."
**Skill:** audit-transport (and any future multi-agent audit/review workflow)
**Type:** open-source
**Phase/Area:** Workflow resilience / verification degradation

**Issue:** A later workflow phase can fail wholesale (rate/session limit, terminal API error) after the expensive discovery phase has already succeeded. Two design choices mattered: (a) the workflow script's per-agent `.then` fallback marked findings `real: true, verifyNote: "verifier unavailable — unreviewed"` instead of dropping them, so no discovery work was lost; (b) the orchestrator (main loop) then hand-verified the 7 highest-severity findings directly against the code — all 7 confirmed true, which calibrated confidence in the unverified remainder.

**Suggested improvement:** In audit/review workflow guidance, make graceful verification-degradation explicit: never drop findings when the verify phase fails — tag them unverified and carry them forward. And codify the orchestrator fallback: when the automated verify phase cannot run, the main loop should hand-verify the load-bearing (highest-severity) findings before presenting, and clearly label each finding's verification status in the deliverable.

**Principle:** A verification stage that can fail silently is worse than none — findings vanish and the report looks complete. Fail-open on data (keep + flag) and fail-over on verification (orchestrator checks the critical few). Verification status belongs in the deliverable, not just the pipeline.

### Observation 2: Auditor findings should carry exact file:line anchors so a follow-up verifier can confirm cheaply

**Date:** 2026-07-02
**Session context:** Same audit. Hand-verifying 7 findings took only a handful of targeted reads because every finding cited exact paths and line ranges (e.g. "build.gradle.kts:56-60", "FileTransferDaemon.kt:57-124").
**Skill:** audit-transport (and general review/audit workflows)
**Type:** open-source
**Phase/Area:** Finding schema / output contract

**Issue:** The audit's finding schema required file + detail, and the auditors consistently included precise line ranges in the detail. That precision is what made post-hoc orchestrator verification fast enough to be practical when the automated verify phase died.

**Suggested improvement:** Bake "cite exact file path + line range for every claim" into the finding schema/prompt as a hard requirement (a dedicated `line`/`anchor` field, not buried in prose), so verification — automated or fallback — is always a cheap targeted read rather than a re-search.

**Principle:** A finding's verifiability is a function of how precisely it points at the evidence. Structured, line-anchored findings turn verification from re-exploration into a lookup, which is what makes an orchestrator fallback viable under resource limits.

### Observation 3: Dead-code deletion coupled across two files needs whole-cluster reachability check

**Date:** 2026-07-02
**Session context:** Bug-fix subagent removing unused Compose components (StatusChips.kt, TransferProgress.kt) and trimming a layout pre-warm block in MainActivity.kt.
**Skill:** New skill candidate: dead-code-removal-verification
**Type:** open-source
**Phase/Area:** Verification before deletion

**Issue:** The two target files cross-referenced each other: TransferProgress.kt called TransferStatusChip/TransferState defined in StatusChips.kt. A naive "is symbol X referenced anywhere?" grep reports StatusChips symbols as live (referenced by TransferProgress) even though the whole cluster is dead externally. Correct call required computing external reachability of the cluster as a unit, then deleting both files.

**Suggested improvement:** When deleting suspected-dead code, build the intra-cluster reference graph first; a symbol referenced only by other in-cluster dead code is still dead. Grep for external call sites (imports + call syntax) excluding the candidate files themselves, and separately check test/androidTest and sibling platform modules before deleting whole files.

**Principle:** Reachability, not raw reference count, determines dead code. Two mutually-referencing dead symbols keep each other "alive" under a naive grep; scope the search to references outside the deletion set.

### Observation 4: Assigned-file boundary vs. cross-referenced sibling bugs

**Date:** 2026-07-02
**Session context:** Subagent bug-fix pass; assigned exactly four Android files, but the bug brief named twin bugs living in two sibling files (P2pFallbackJoiner.kt, BleControlClient.kt) that were NOT in the assigned set.
**Skill:** New skill candidate: scoped-bugfix-subagent
**Type:** open-source
**Phase/Area:** Scope discipline / flagging

**Issue:** Two of the four assigned bugs (sticky-broadcast false-success, notify-congestion busy-loop) exist in near-identical form in a sibling file the brief mentioned by line number but did not assign. The correct move was to fix only the assigned file and flag the sibling with concrete file+line so the orchestrator can dispatch it, rather than silently editing an out-of-scope file or ignoring the twin.

**Suggested improvement:** A scoped-bugfix skill should codify: (1) edit ONLY assigned files; (2) when a bug has a known twin outside the assigned set, flag it with exact path + line + the one-line fix so it isn't lost; (3) read the sibling to confirm the twin is real before flagging (avoid flagging a phantom).

**Principle:** In a multi-agent fan-out, staying in-lane is only safe if out-of-lane duplicates are surfaced precisely; a flagged twin with file:line is actionable, a vague "similar issue elsewhere" is not.

### Observation 5: Verify-first pays off on crypto replay caches — check the singleton test interaction

**Date:** 2026-07-02
**Session context:** Added a receiver-local nonce replay cache to a Kotlin `object` (singleton) AEAD helper; existing unit tests share that singleton's state across test methods in one JVM.
**Skill:** New skill candidate: scoped-bugfix-subagent
**Type:** open-source
**Phase/Area:** Change-safety verification

**Issue:** The replay cache lives in a Kotlin `object`, so its state is shared across all tests in the suite. Before adding it I had to confirm no existing test opens the same blob (same nonce) twice expecting success both times — one test did open the same blob twice, but the first open was rejected for staleness *before* the nonce was recorded, so the cache never populated and the test still passed. Missing that ordering would have produced a false test break attributed to the fix.

**Suggested improvement:** When adding stateful receiver-local defenses (replay caches, seen-sets) to a singleton, always read the existing test suite for repeated-input-expects-success patterns and reason about record-point ordering (record only after all prior rejections) before declaring the change safe.

**Principle:** Singleton state added for security silently couples otherwise-independent tests; the safe insertion point for "mark as seen" is after every existing rejection path, and that must be verified against the actual tests, not assumed.

### Observation 6: Cross-platform parity fixes should read the reference platform's exact impl, not reimplement from the bug description

**Date:** 2026-07-02
**Session context:** Fixing 3 security bugs in the Windows client's storage layer (ClipboardSeal replay cache, FileSeal AAD, SecurityVault plaintext keys) where fixes must make Windows conform to Android without changing wire bytes.
**Skill:** New skill candidate: cross-platform-parity-fix
**Type:** internal
**Phase/Area:** Verification / conforming one platform to another

**Issue:** The task said "mirror what the Android ClipboardSeal fix does." Rather than invent a nonce cache from the description, the correct move was to open the Android ClipboardSeal.kt and copy its exact structure (MAX_NONCES_PER_PEER=512, bounded LinkedHashMap with removeEldestEntry, SHA-256 peer fingerprint truncated to 16 bytes, prune-then-check-then-record order). The only deltas were import differences (Android uses SessionCrypto.hkdfSha256; Windows has a local hkdfSha256 + needed a new MessageDigest import) and abs already being imported as kotlin.math.abs. Reimplementing freehand would have risked subtle divergence (e.g., different cache bound, different fingerprint) that no wire test would catch because this is receiver-local state.

**Suggested improvement:** When a task is "make platform B conform to platform A," always Read A's file first and diff against B, treating A as the golden source. Port structure verbatim; only adapt language/import plumbing. Flag rather than fix anything that would change shared golden-vector-pinned bytes (AAD, key derivation, framing).

**Principle:** For cross-platform conformance fixes, the reference implementation is the spec. Copy its exact constants and control flow; the only allowed deltas are local plumbing. Receiver-local state has no wire test to catch divergence, so fidelity to the reference must be enforced by reading it, not by paraphrasing the bug report.

### Observation 7: Gate risky fixes on actual dependency inventory, not on the fix being "correct in principle"

**Date:** 2026-07-02
**Session context:** Same session. SecurityVault stores per-peer keys as Base64 plaintext; DPAPI (ProtectedData.Protect) is the right fix but only if a clean binding already exists.
**Skill:** New skill candidate: cross-platform-parity-fix
**Type:** internal
**Phase/Area:** Fix-vs-flag decision

**Issue:** The right cryptographic fix (DPAPI wrapping) was explicitly gated on the module already having a usable binding (JNA / native crypto dep). Reading Window Ver/build.gradle.kts showed only Gson, ZXing, and Compose — no JNA, no native crypto. The correct action was to FLAG (needs a DPAPI/JNA dependency plus a one-time migration to re-wrap existing plaintext keys) and make no change, rather than shell out to PowerShell or add a native dep. Same pattern for FileSeal: the AAD/sub-key change is genuinely more secure but is pinned by a shared golden vector, so it must be flagged for a coordinated two-platform change, not applied unilaterally.

**Suggested improvement:** Before applying a security fix that needs a native/crypto capability, grep the build file for the required binding. If absent, flag with the exact current definitions (aad(), deriveKey(), the plaintext-write lines) so both platforms can change together, and state which path was taken. Never introduce a native dep or shell-out as a silent workaround.

**Principle:** A fix that is correct in isolation can still be the wrong action: wire-pinned constants require coordinated multi-platform changes, and security fixes requiring new native capabilities must not smuggle in risky dependencies. Verify the platform actually supports the clean fix (check the manifest/build file) before writing code; otherwise flag with exact current definitions and stop.

### Observation 8: Cross-platform receiver fixes constrained by "edit only these files" scope

**Date:** 2026-07-02
**Session context:** Fixing four cited bugs in the Windows client (ClipboardDaemon.kt, CryptoEngine.kt) of the cross-platform clipboard app, with a hard rule to edit only those two files.
**Skill:** New skill candidate: cross-platform-conform-fix (or an addition to an existing transport/interop skill)
**Type:** internal
**Phase/Area:** Scoping a "make Windows match Android" bug fix

**Issue:** The CMD_DELETE fix asked to "remove the suffix text from history and wipe the OS clipboard." The daemon only receives an onClipboardReceived (add-to-history) callback; the delete-from-history method lives in ClipboardAppController (out of scope). Within the two assigned files I could fully stop the literal being pasted/re-added and wipe the OS clipboard on match, but removing an already-present older history entry needs a controller callback that couldn't be added without editing a third file. The right move was to implement the in-scope portion completely and flag the residual as needing a one-line callback in the controller, rather than either silently skipping it or breaking the file-scope rule.

**Suggested improvement:** When a fix's stated behavior spans a file outside the assigned set, split it: implement everything achievable within scope, then flag the exact out-of-scope hook needed (file + method + one-line change) so the parent can decide. Record this partition explicitly in the summary under "flagged" with the concrete missing wiring, not a vague "may need more work."

**Principle:** A "conform to the other platform" fix often needs a callback the current interface doesn't expose. Honor the file-scope boundary by delivering the safe, in-scope subset and precisely naming the missing seam, rather than overreaching or under-delivering.

### Observation 9: Cross-platform conformance fixes need the counterpart file read for exact field order

**Date:** 2026-07-02
**Session context:** Fixing four discovery-daemon bugs in the Windows client (TransferDaemon.kt) to conform to Android behavior without a wire change: node-timeout window, FILEBRIDGE_REPLY accept+emit, packet-source-over-declared-IP trust, and the discoverable gate.
**Skill:** verify (and cross-platform conformance workflow generally)
**Type:** internal
**Phase/Area:** Cross-platform wire-conformance changes

**Issue:** The task required Windows to emit a FILEBRIDGE_REPLY frame identical to Android's. The correct, safe move was to read the Android counterpart's exact emit line (app/src/main/java/TransferDaemon.kt line 303: "FILEBRIDGE_REPLY|$customDeviceName|$myIp|Android|$DISCOVERY_CAPS") and the Windows broadcast line to derive the Windows reply as name|ip|Windows|caps, rather than inventing a field order. Also had to confirm SecurityVault.isDiscoverable() is a no-arg method on an object and add the cross-package import, since gating logic assumed an API shape.

**Suggested improvement:** When a fix makes one platform "conform" to the other, the workflow should mandate: (1) read the counterpart's exact frame-emit/parse line before writing the conforming code, (2) confirm the referenced helper API signature/receiver in the target module before calling it. Both are cheap reads that prevent wire-format drift or a compile break.

**Principle:** "Conform to the other platform" is only safe when the exact bytes and the exact helper API are read from source, not reconstructed from memory. The counterpart file is the contract; the referenced helper is the interface. Read both before emitting conforming code.

### Observation 10: Comment-audit sweeps need a "grounding pass" before editing

**Date:** 2026-07-02
**Session context:** User asked to "search for each comment in the windows version and change it to a more appropriate one, and latest description" — a full comment-accuracy sweep of the Windows module ahead of the 3.5.0 release.
**Skill:** New skill candidate: comment-hygiene-sweep
**Type:** open-source
**Phase/Area:** Pre-edit grounding

**Issue:** The request was ambiguous ("latest description") until the working tree was checked: build files showed uncommitted 3.5.0 work, which redefined what "stale" meant (e.g. a "3.5.0 always advertises pv2" comment was current, while "legacy peers stay on CBC" comments were newly wrong because CBC was retired in this same 3.5.0 tree). Recurring stale-comment classes found: (1) version-stamped comments that rot ("(3.0)", "§3.2 of the V3.0 plan"), (2) internal review-finding IDs left as prefixes (M-1/M-15/M-16/M-17), (3) template slop ("In a real app, handle..."), (4) behavior descriptions outdated by a protocol change in the same tree (CBC retirement), (5) headers documenting only v1 of a scheme after v2 was added.

**Suggested improvement:** A comment-hygiene skill should mandate: first establish the tree's actual version/feature state (build files + uncommitted diff) before judging any comment; then sweep with a checklist of the five stale-comment classes above; verify each "behavioral" comment against the code it describes (grep call sites) rather than editing from the comment text alone.

**Principle:** Comment accuracy is relative to the current tree state, not the last release — audit the code first, the comments second, or the sweep itself introduces errors.

**Status:** OPEN

### Observation 11: Adversarial verifier verdicts can contradict their own evidence — orchestrator must read the evidence

**Date:** 2026-07-02
**Session context:** Plan-mode design of an in-screen Send-Via overlay; a 5-agent workflow adversarially verified 27 file/line claims from a Plan agent. One verifier marked the plan's back-press mitigation "PARTIAL/insufficient" while its own numbered evidence (BackHandler unregisters on enabled=false; onHiddenChanged flips state to false) actually proved the mitigation sufficient.
**Skill:** New skill candidate: plan-claim-verification (or general review/audit workflow guidance)
**Type:** open-source
**Phase/Area:** Verify-stage output interpretation

**Issue:** Structured verify agents return a verdict enum plus prose evidence. The verdict can be miscalibrated (the agent misreads what the mitigation was) even when the evidence field is accurate and complete. Trusting the enum alone would have flagged a non-defect and prompted an unnecessary design change; reading the evidence showed the plan was correct as written.

**Suggested improvement:** In claim-verification workflows, treat non-CONFIRMED verdicts as "orchestrator must adjudicate from the evidence text," not as ground truth. The schema should require the evidence to state (a) what the claim/mitigation actually was and (b) the mechanism observed in code, so the orchestrator can spot verdict/evidence contradictions cheaply.

**Principle:** A verifier's verdict is a summary of its evidence, and summaries can be wrong while the evidence is right. The orchestrator's adjudication step — read evidence, not labels — is what converts a noisy verify fan-out into a reliable one.

### Observation 12: Workflow return values get truncated in task notifications — keep them compact, parse tasks/*.output for the rest

**Date:** 2026-07-02
**Session context:** Same session. A verification workflow returned {groups, nonConfirmed} with full evidence strings for 27 claims; the task-notification result truncated at ~2k chars mid-JSON. Full result was recovered from the tasks/<id>.output file — a JSON wrapper whose `result` key holds the workflow's return value (parse via ConvertFrom-Json, then read `.result`).
**Skill:** New skill candidate: workflow-orchestration-hygiene
**Type:** open-source
**Phase/Area:** Workflow return-value design

**Issue:** Returning verbose per-claim evidence in the workflow's return value made the notification unreadable and forced a recovery step. The compact essentials (counts + non-confirmed items only) would have fit; full evidence was only needed for the 3 flagged items.

**Suggested improvement:** Design workflow returns as "exceptions + counts": return only items needing orchestrator attention (non-confirmed findings, failures) with their evidence, plus summary counts for the confirmed bulk. When a notification does truncate, recover via the output file's `result` key rather than re-running.

**Principle:** A workflow's return value is a message to the orchestrator, not an archive; size it to the decision it must enable. Full detail belongs in retrievable artifacts (output file, transcript), not the notification channel.

### Observation 13: Impeccable skill setup assumes a web project

**Status:** OPEN
**Date:** 2026-07-02
**Session context:** Adding dim+blur backdrop and scale/fade entrance to spotlight overlays and dialogs in a Compose Android + Compose Desktop app
**Skill:** impeccable
**Type:** internal
**Phase/Area:** Setup steps 1 and 5 (context.mjs / NO_PRODUCT_MD -> init gate)

**Issue:** The impeccable skill auto-matched this UI-motion task (its description covers "polish", "animate", "motion, micro-interactions" for "product UI"), but its mandatory setup is web-oriented: context.mjs demanded a PRODUCT.md init flow, palette.mjs assumes CSS/OKLCH tokens, and the detector/live tooling assume HTML/CSS. For a Kotlin Compose (Android + Desktop) project, only the design/motion guidance applies; the init gate had to be consciously skipped to avoid derailing the task.

**Suggested improvement:** Add a note to the impeccable invocation habit (or a small internal companion note): when the target is a non-web UI stack (Compose, SwiftUI, Flutter), skip the script/init gates and apply only the General rules + register guidance. Alternatively, stop auto-invoking impeccable for non-web UI tasks and rely on its motion/design rules from memory of the skill body.

**Principle:** Skill descriptions that match on task intent ("polish UI") can still carry tech-stack assumptions in their setup steps. Auto-selection rules should distinguish "the guidance applies" from "the tooling applies", and skills with hard setup gates should state their stack scope in the description.

### Observation 14: Cross-platform string addition is a scripted, anchor-based pattern

**Date:** 2026-07-02
**Session context:** Adding a total-file-size display (Send-Files chip + Send-Via label) to Android and Windows, which required one new localized string in 10 Android strings.xml files and 10 Windows translation.json.txt language blocks.
**Skill:** New skill candidate: add-localized-string
**Type:** internal
**Phase/Area:** i18n / string resources

**Issue:** Adding one user-facing string to this project means 20 coordinated insertions (10 Android locale files + 10 contiguous language blocks in the Windows translation resource). The Edit tool requires reading each file first, which is wasteful for ~600-line XML files; a Node script anchored on a per-locale existing line (e.g. the translated `send_via_title` value) did all insertions safely with verification output. Note: this machine has no Python (Store stub only); Node v24 is the working scripting runtime.

**Suggested improvement:** A small internal skill (or addition to project docs) codifying: (1) anchor each insertion on a per-locale unique existing line, (2) Windows keys must land inside the contiguous language block for the fragile indexOf/regex loader, (3) use `%1$s` placeholders on Android vs `%s` on Windows, (4) units like MB/GB stay untranslated per existing convention, (5) script it with Node, verify with a grep counting 10+10 hits.

**Principle:** For repos with fan-out localization (same key duplicated across N files/blocks), mechanical scripted insertion with per-locale anchors plus a count-verification grep is faster and safer than hand-editing each file.

**Status:** OPEN

### Observation 15: Empirical micro-repro before fixing runtime-dependent windowing bugs

**Status:** OPEN
**Date:** 2026-07-02
**Session context:** Windows client maximize button oversized the window ~2.25x on a 150% HiDPI display
**Skill:** New skill candidate: runtime-behavior-probe (or fold into a debugging methodology skill)
**Type:** open-source
**Phase/Area:** Diagnosis before fix

**Issue:** The bug hinged on how the bundled JBR interprets `Frame.setMaximizedBounds` under HiDPI — an area where JDK versions genuinely differ and memory of bug-tracker lore is unreliable. Instead of patching from assumption, a ~40-line scratch AWT program run on the exact shipping runtime (resolved from the build's javaHome) measured the actual transform: input rect × scale², which pinned both the root cause and the exact compensation (pre-divide by scale). The fix then matched measured behavior on the first try.

**Suggested improvement:** Capture as a reusable pattern: (1) identify the exact runtime the shipping artifact bundles (toolchain/javaHome, not the IDE's JVM by assumption); (2) write a minimal scratch repro exercising only the suspect API; (3) run it on that runtime and measure input→output; (4) derive the fix from the measured transform and cite the measurement in a code comment with a re-verify note for runtime upgrades.

**Principle:** For bugs that depend on platform/runtime interpretation of an API (DPI scaling, font metrics, window management), a minimal empirical probe on the shipping runtime beats reasoning from documentation or remembered bug reports — it converts a guess into a measurement and leaves a verifiable trail.

### Observation 16: impeccable skill is web-centric; Compose (Android/Desktop) projects need adapted verification

**Date:** 2026-07-02
**Session context:** Replacing "AI-ish" fonts (Geist) with IBM Plex Sans for non-button text across the Android + Compose Desktop clipboard clients
**Skill:** impeccable
**Type:** open-source
**Phase/Area:** Setup steps / typeset command

**Issue:** The impeccable setup and typeset reference assume a web stack: context.mjs/detect.mjs scan HTML/CSS, guidance is in CSS units (rem, ch, clamp()), verification leans on browser screenshots, and live mode requires an HTML entry. On a Kotlin Compose project none of that applies directly, yet the core method (assess → plan on a contrast axis → two-voice system → verify) transferred fine; verification became Gradle builds (AAPT2 validates font resources) instead of browser checks, and "keep buttons distinct" mapped to M3 label* slots / M2 button slot rather than CSS selectors.

**Suggested improvement:** Add a short "non-web UI toolkits" note to the setup or typeset reference: state that the design rules apply toolkit-agnostically, map the common equivalents (sp/dp for rem, Compose Typography slots for type tokens), and say verification should use the platform's build + a native run instead of detect.mjs/browser passes. Skip the detector scripts silently when no HTML/CSS exists.

**Principle:** A design skill's rules usually generalize across rendering stacks, but its *enforcement tooling* rarely does; naming the fallback verification path per stack keeps the skill usable outside its home ecosystem instead of being silently skipped.

**Status:** OPEN

### Observation 17: Compile failure raced with an IDE auto-save — re-run before debugging

**Status:** OPEN
**Date:** 2026-07-02
**Session context:** Cross-platform transfer-cancel fix; :window-ver compile failed with "Missing return statement" at a line the session never touched
**Skill:** verify
**Type:** internal
**Phase/Area:** Interpreting build failures

**Issue:** The Windows module compile failed pointing at code far from the session's edits, in a file the harness flagged as externally modified (user's IDE was open on it). The on-disk code read as syntactically fine. A plain re-run of the same compile succeeded — the first compile had raced a mid-edit auto-save state of the file.

**Suggested improvement:** Add a rule to the verify skill: when a compile error (a) points at a location the session did not modify AND (b) the file was reported as externally modified, re-run the build once before investigating. Only debug if the error reproduces.

**Principle:** In a live-edited workspace, a build is a snapshot of a moving target; a failure that implicates untouched code should be reproduced before it is diagnosed.

### Observation 18: Search-tool output artifacts can mimic file corruption

**Date:** 2026-07-02
**Session context:** Adding flowing progress numbers + Send→history-detail navigation on both clients
**Skill:** New skill candidate: none (cross-cutting working practice)
**Type:** open-source
**Phase/Area:** Code investigation / verification before editing

**Issue:** Grep tool output rendered several Kotlin `//` comment lines as starting with `\ ` (backslash-space), making three files appear syntactically corrupted. Reading the actual files showed normal `//` comments — the "corruption" was purely a display artifact of the search output. Acting on it (e.g., "fixing" the comments or reporting corruption to the user) would have wasted a cycle and eroded trust.

**Suggested improvement:** Before treating any anomaly seen in search/grep output as file damage, confirm by reading the file region directly with the file-reading tool. Search output is a rendering of the file, not the file.

**Principle:** Verify suspected file corruption against the primary source (a direct file read), never against a secondary rendering (search results, diffs in logs, terminal echoes). Cheap to check, expensive to get wrong.

### Observation 19: "Source of truth" token doc had drifted from code and briefly misled the diagnosis

**Date:** 2026-07-03
**Session context:** Redesign of the Android Send-via slide-up sheet (dark-mode seam against the bottom nav bar). Read `ui/theme/TOKENS.md` early because it declares itself the single human-readable source of truth for design tokens.
**Skill:** impeccable
**Type:** open-source
**Phase/Area:** Setup step 3 (familiarize with existing design system)

**Issue:** The token doc documented a decommissioned palette (old blue-slate dark values). The initial root-cause hypothesis quoted its hex values; an exploration agent's diff against the real token files (`Color.kt`, `values-night/themes.xml`) showed the structure of the diagnosis was right but every hex was stale. The plan then had to add a doc-resync task, and the implementation verified XML and Compose values against each other before rewriting the table.

**Suggested improvement:** In the setup step that reads the existing design system, when a token/design doc exists alongside code tokens, spot-diff the doc's values against the code before quoting or designing from them. Treat code as ground truth, and when drift is found, fold a doc resync into the task's scope rather than working around the stale doc.

**Principle:** Docs rot faster than code, and a doc that says "source of truth" is a claim to verify, not a fact. Design work that touches a token system should end with the doc matching the code, otherwise the next session inherits the same trap.

### Observation 20: A spike validated the riskiest native assumption before the abstraction was built on top of it

**Date:** 2026-07-03
**Session context:** Adding a BLE control channel to the off-grid PC↔phone transfer path (Windows Kotlin/JVM driving WinRT GATT). The audit-transport skill's Explore pass mapped the existing stack; T0 of the plan was a throwaway PowerShell/WinRT spike run BEFORE any production Kotlin was written.
**Skill:** audit-transport
**Type:** open-source
**Phase/Area:** Post-audit, pre-implementation gate

**Issue:** The plan's core delivery mechanism ("persistent PowerShell 5.1 helper hosting WinRT GattServiceProvider") turned out to be wrong in two ways that only a real run exposed: (1) Windows PowerShell 5.1 cannot subscribe to WinRT events at all (`Register-ObjectEvent` → "cannot subscribe to Windows RT events"), forcing a pivot to compiling an embedded C# helper with the in-box `csc.exe`; (2) `GattServiceProvider` advertising reports a transient `Aborted` status *before* `Started`, so the naive "wait until Started-or-Aborted" loop would have reported failure on every working adapter. Both were caught in a ~30-min spike; had they surfaced during full implementation, the BleControlPeripheral abstraction, its callers, and the QR wiring would all have been built on a false premise and needed rework.

**Suggested improvement:** audit-transport's "Then" section (reuse-over-rebuild) should add: when the plan depends on a native/OS integration the codebase does NOT already exercise (here: WinRT GATT — the repo drove WinRT only for Wi-Fi Direct, never Bluetooth), gate the plan on a throwaway spike that proves the exact risky calls end-to-end before writing the production abstraction. The audit tells you what exists to reuse; it cannot tell you whether a *new* native path behaves as the docs claim.

**Principle:** An audit de-risks reuse, not novelty. The most expensive defects hide in the one platform call your codebase has never made before, and no amount of reading existing code reveals them — only running the specific call does. Order the work so the riskiest unproven assumption is tested first and cheapest, before any abstraction hardens around it.
