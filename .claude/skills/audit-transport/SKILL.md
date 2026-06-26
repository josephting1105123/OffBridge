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

## Then

Read the agent's map, confirm what's reusable, and only plan NEW code for genuine gaps. Reference existing helpers by path in the plan (reuse over rebuild). See also the `## Architecture & Planning` rule in CLAUDE.md.
