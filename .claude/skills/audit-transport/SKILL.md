---
name: audit-transport
description: Audit the existing transport/connection stack before planning changes. Launches a focused Explore agent to report which transport tiers and fallbacks are already implemented (ladder, UDP/LAN, Wi-Fi Direct, hotspot/SoftAP/LOHS, BLE control, session controllers) so new work reuses what exists instead of rebuilding it. Use before designing any transport/connection solution.
---

# Audit Transport

Before designing a transport or connection solution, audit what already exists so working components aren't rebuilt.

## What to do

Launch a focused **Explore** agent (Task tool, `subagent_type: Explore`) with a read-only, breadth-first brief. Keep the heavy exploration inside the agent so the planning context stays lean. The agent must NOT edit anything — it reports only.

Agent prompt to use (adapt the focus to the task):

> Audit the existing transport-fallback stack and report which tiers are already implemented before we plan changes. Search breadth: thorough. Cover:
> - The transport "ladder" / tier sequence and where tiers are tried in order (P2P_CRED_JOIN, SPECIFIER, P2P_NEGOTIATED, LOHS, AWARE, RECEIVER_AS_GO, SOFTAP_JOIN, SENDER_JOINS_AP) — which are wired in vs gated/retired.
> - The data plane (TCP file server on port 65433, `TransferDaemon`, resume/`RESUME_REQUEST`) and the BLE control plane (`BleControlClient`/`BleControlServer`, HELLO handshake).
> - The session controllers (`SenderSessionController`, `ReceiverSessionController`), host election (`GoFitness`), and `buildPlan`.
> - Any UDP/LAN discovery + same-network probe path.
>
> For each, give file:line, a one-line status (implemented / partial / gated / retired), and the key constants. Do NOT modify files. Return a structured map grouped by plane (control / data) and tier.

**Every claim must carry an exact file path + line range as a dedicated field, not buried in prose.** Line-anchored findings turn any later verification (automated or by-hand) into a targeted read instead of a re-search — this is what makes fallback verification viable when a verify stage dies.

## Then

Read the agent's map, confirm what's reusable, and only plan NEW code for genuine gaps. Reference existing helpers by path in the plan (reuse over rebuild). See also the `## Architecture & Planning` rule in CLAUDE.md.

**Spike gate for unproven native calls.** An audit de-risks *reuse*, not *novelty*. When the plan depends on a native/OS integration the codebase does NOT already exercise (a WinRT API family it never drove, a new radio path), gate the plan on a throwaway spike that proves the exact risky calls end-to-end BEFORE writing the production abstraction. The most expensive defects hide in the one platform call the repo has never made; only running it reveals them (e.g. PowerShell 5.1 cannot subscribe to WinRT events at all; GATT advertising reports a transient `Aborted` before `Started`).

## When the audit fans out (multi-agent)

- **Never drop findings when a verify phase fails** (session limit, terminal API error). Tag them `unverified` and carry them forward; then the orchestrator hand-verifies the highest-severity few directly against the code and labels each finding's verification status in the deliverable. A verify stage that fails silently is worse than none.
- **Adjudicate from evidence, not verdict enums.** A verifier's verdict is a summary of its evidence and can contradict it. Treat non-CONFIRMED verdicts as "orchestrator reads the evidence text and decides."

## Refactor wire-preservation audits

To prove a refactor kept wire bytes/timeouts identical, don't prose-read both versions: diff the *sorted sets of string literals* (`grep -o '"[^"]*"' | sort -u`) and a timeout/port/constant regex between the pre-refactor git blob (`git show <commit>:<path>`) and the union of the successor files, then read only the residue. Prefer git blobs over `*_temp.kt` scratch copies as baselines — scratch files are mid-refactor snapshots.
