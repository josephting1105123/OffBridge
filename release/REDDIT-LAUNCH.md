# Reddit plan — OffBridge

Written 2026-08-03, revised the same day after the repo went public.

**Do not commit this file.** It's a marketing playbook; in a public repo it reads as an
astroturfing plan and it's the first thing someone will screenshot. A `.gitignore` entry has been
added for it. See "Repo hygiene" below — `release/` is public now, which is the bigger version of
this same problem.

**Reddit is unreachable from the dev environment** (the site, old.reddit, and the JSON endpoints
all refuse), so no subreddit rule below was verified today. Read each sidebar and pinned mod post
before you post.

Baseline to beat: the 3.7.10 release has 0 downloads, 3.7.9 has 1–2, 3.7.8 has 5.

## Before any of this: the repo is public and uncurated

`josephting1105123/OffBridge` is public, Apache-2.0, full 131-commit history. But
[OPEN-SOURCING.md](OPEN-SOURCING.md) called for a curated snapshot with no history, excluding
`release/`, `.claude/`, `CLAUDE.md`, `PRODUCT.md` and `website/`. What actually went out is the
private repo with its visibility flipped, so all of those are public except the two that happen to
be gitignored.

The good news first: no key material. `keystore.properties`, `*.jks`, `CLAUDE.md`, `.claude/`,
`PORTFOLIO.md` and `SESSION-LOG.md` are all gitignored and none appear in the public tree. Only
`keystore.properties.example` and the keystore *docs* are there. The history was scanned clean per
your own notes before the push.

What's exposed and actually matters, in order:

1. **`release/AUDIT-3.5.1.md`, `AUDIT-FN-2026-07-05.md`, `FOLLOWUPS-2026-07-06.md`** — an itemized
   list of known security defects in a shipping privacy app, including ones marked deferred
   (API 29–32 pairing-receiver export, share-intent drop, TOFU first-contact). Several are likely
   fixed in 3.7.x, but the documents don't say so, so a reader assumes they're all live. This is a
   map for anyone who wants one, and it's a bad look on r/privacy specifically.
2. **`release/RUNBOOK.md`, `RELEASE_CHECKLIST.md`, `TESTER_REPLIES.md`, `REPORT-SECURITY-MARKET-*.md`**
   — internal ops and positioning. Not dangerous, just not yours to have published.
3. **`release/seed-public-repo.ps1`** — the exclusion script, published, unused. Confusing to any
   reader who opens it.
4. **`PRODUCT.md`, `website/`** — brand and site source. Harmless; still on the exclude list.

The fix is `git rm -r --cached release/` plus a `.gitignore` entry, then a push. Note this only
hides them going forward — they stay in the history, so anyone who cares can still read them.
Purging history means a force-push and a rewritten repo, which is a judgment call at 1 star and
essentially no audience: cheap now, impossible later. **This is your call, not mine — say the word
and I'll do either.** Nothing below assumes it's done except the r/privacy entry.

## Repo hygiene before you post

The GitHub repo is now the landing page for everyone arriving from Reddit, and right now it has no
description and no topics. Fifteen minutes, meaningful conversion effect:

- **Description** — one line, the off-grid hook, not "clipboard app". Something like
  "Clipboard sync and file transfer between Android and Windows, over LAN or with no router at all.
  No cloud, no account, no telemetry."
- **Topics** — `android`, `windows`, `kotlin`, `compose`, `clipboard`, `file-transfer`,
  `wifi-direct`, `offline`, `p2p`, `privacy`.
- **README** — read it once as a stranger who arrived from a Reddit comment. Screenshots near the
  top, the off-grid explanation above the fold, build instructions below it, download links to the
  Play listing and the Showcase releases. If the README currently opens with build setup, flip it.
- **Releases** — the code repo has no releases; the binaries live in `OffBridge-Showcase`. Someone
  who lands on the source repo and wants the app will look at the Releases tab, find it empty, and
  leave. Link Showcase prominently in the README, or publish releases in both.

## The other gate: Certum

Apply now — the public repo URL is the proof Certum needs, which is why this was blocked before.
Until that cert lands and signing is wired in, every Windows user your post converts hits
"Windows protected your PC" on an unsigned 85 MB exe. Post to Android-side subs now; hold the
Windows-facing ones. Details in [WINDOWS-SIGNING-SAC.md](WINDOWS-SIGNING-SAC.md).

## Where to post

Open-sourcing changed this table — four rows moved.

| Sub | Fit | Risk | Notes |
|---|---|---|---|
| r/androidapps | Best | Low–med | The main venue for devs posting their own apps. Has had a dev flair and, some periods, a dedicated promo thread — **verify which is current**. |
| r/opensource | Now good | Low | Unlocked by the licence. Apache-2.0 + a real problem is exactly their content. Lead with the code, not the download. |
| r/coolgithubprojects | Now good | Very low | Low-effort post, low traffic, no downside. |
| r/SideProject | Good | Very low | Solo-dev friendly. A fine place to make your first post's mistakes. |
| r/degoogle | Now decent | Med | No-cloud + open source fits. Play-only distribution cuts against it; mention the direct APK. |
| r/privacy | Viable now, not yet | Med–high | Open source removes the fatal objection. But the public audit docs are the new one — settle item 1 above first. |
| r/software | Decent | Med | Windows audience. Hold until the installer is signed. |
| r/selfhosted | Skip | — | Genuinely off-topic. There's no server to host, which is the point of the app. |
| r/Windows11, r/windows | Marginal | Med | App shares often removed. Not worth an early strike. |

The highest-converting tactic still isn't a launch post. People ask "how do I move files from my
phone to my PC without internet / without a cable / without uploading to Google" constantly in
r/AndroidQuestions, r/techsupport and r/androidapps. Search those, answer the ones you can actually
solve, disclose that you wrote it. Slow, but the person asking already wants what you built, and
the answer keeps earning after the thread dies.

## Account hygiene

- Low-karma or new accounts get auto-removed by most subs' filters. If yours is fresh, spend a week
  making ordinary comments before posting anything of your own.
- Text posts, not link posts. Link posts read as drive-by promo and get less reach.
- Never paste the same body into two subs the same day — that pattern trips Reddit's spam
  detection site-wide, not just the sub's.
- Post Tuesday–Thursday, morning US Eastern, then stay at the keyboard for three hours and answer
  every comment. Reply speed is most of what decides whether the thread lives.
- Reply as a person, not a brand account. "I built this" beats "OffBridge offers".

## Draft — r/androidapps

Check the required flair first. Title:

> I built OffBridge — clipboard sync and file transfer between Android and Windows that works with
> no router and no internet. Source is Apache-2.0.

Body:

```
Every phone-to-PC transfer tool I tried assumed both devices were already on the same Wi-Fi.
That covers the easy case and none of the annoying ones — hotel networks with client isolation,
a relative's ancient router, a train, anywhere with no network at all.

So OffBridge handles the easy case too (same LAN, fastest path), but the reason it exists is the
other one: it pairs over a QR code and brings up a direct device-to-device link — Wi-Fi Direct or
a hotspot bridge — so the two devices talk with no router and no internet in the picture.

What it does:
- Two-way clipboard sync. Copy on the phone, paste on the PC.
- Files and whole folders, multi-gigabyte, resumes if the link drops.
- No account, no server, no telemetry. There's no backend at all — I have nowhere to store your
  data even if I wanted it.
- Free. No ads, no IAP, no paid tier.
- Android 10+, Windows 10/11, 10 languages.

Encryption, stated precisely instead of as a badge: clipboard content is always AES-256-GCM and
the app rejects anything arriving unencrypted. Files over your LAN get the same when both ends
support it. Off-grid transfers over Wi-Fi Direct/hotspot are encrypted from Android 3.7.3 and
Windows 3.7.7 onward — before those versions they weren't, and I'd rather say that than let an
"AES-256 encrypted" slogan cover for it.

Source: <repo link> (Apache-2.0)
Play: <play link>
Direct APK and Windows installer: https://offbridge.app

Honest caveats:
- Windows only on the desktop side. No macOS, no Linux.
- The Windows installer isn't code-signed yet, so SmartScreen will warn. SHA-256 is on the site;
  the certificate is in progress.

vs LocalSend and KDE Connect, since it's the first thing I'd ask: LocalSend is genuinely good, but
it needs both devices on the same network and doesn't do clipboard. KDE Connect does clipboard but
also needs the shared network, and its Windows side is rough. The no-router path is the part I
actually built this for.

Happy to answer anything.
```

Why it's shaped that way: the caveats section is what makes the rest believable — a post with no
downsides listed reads as marketing and gets treated as marketing. Naming LocalSend yourself takes
the top comment away from someone else. The licence goes in the title because it's the single
biggest change since you last considered posting, and it's what earns a hearing in the
open-source-leaning subs.

## Draft — r/opensource

Different emphasis: they care about the code and the licence, not the download. Lead with what the
project is, why Apache-2.0 specifically (patent grant, and clause 6 keeps the trademark), and one
technically interesting thing — the transport ladder stepping down from LAN to Wi-Fi Direct to
hotspot bridge is the honest answer, since it's the part that isn't just another file-sender. Links
to the repo first, binaries second.

## Draft — r/SideProject

Same story, shorter, more personal. The problem you hit, one paragraph on what it does, the links,
and a direct question — "what would stop you installing this?" That sub answers direct questions.

## Comment template for organic answers

```
If both devices are on the same Wi-Fi, LocalSend is the usual answer and it's good.

If they aren't — no router, hotel wifi that blocks device-to-device, nothing at all — that's the
gap I wrote OffBridge for (Android + Windows, free, no account, Apache-2.0): it pairs over a QR
code and brings up a direct link between the two devices. Fair disclosure, I'm the developer:
https://offbridge.app
```

Recommend the competitor first when the competitor fits. It costs nothing on threads you were never
going to win and buys credibility on the ones you can.

## The comments you will get, and the answers

**"Why not LocalSend?"** — Answered in the post. Same-network requirement, no clipboard sync.

**"Your own audit file says X is still unfixed."** — New, and the likeliest hostile comment now
that `release/` is public. Someone will find `AUDIT-3.5.1.md` and quote a deferred item. Know which
of those are actually fixed in 3.7.x before you post so you can answer specifically. If a finding
is genuinely still open, say so and say when it lands — a straight answer here plays well, a
defensive one doesn't. Best handled by resolving item 1 above first.

**"Unsigned installer / my antivirus flagged it."** — SHA-256 published on the site, link a
VirusTotal scan, cert in progress. Don't argue that SmartScreen is wrong; it's doing its job on an
unsigned binary.

**"Is it on F-Droid?"** — Now actually possible, since the licence qualifies. Worth saying "not
yet, it's on the list" only if it is.

**"Does it phone home?"** — No telemetry, no account, no server. Point at the privacy policy, which
states per-channel what is and isn't encrypted rather than making a blanket claim. And now: read
the source.

## Measuring it

There's no analytics on offbridge.app, so you'd be posting blind. Two cheap fixes, in order: put
Cloudflare Web Analytics on the Pages project (free, cookieless, doesn't contradict the privacy
pitch), and give each post a distinct UTM or path so you can tell r/androidapps from r/opensource.
GitHub release download counts are the crude backstop — 0–5 today, so any movement is signal.
GitHub stars are the second (1 today, and the traffic graph in Insights shows referrers for free).
