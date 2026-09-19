---
name: process-context-video
description: Extract frames from a video in "Context and References File" and write (or refresh) its cached text summary so it never needs decoding again until it changes. Usually invoked by the read-context-video skill on a cache miss or when the source video changed; can also be run directly to (re)build a context video's cached description. Uses only built-in Windows media APIs — no ffmpeg, no downloads.
---

# Process Context Video

Decode a reference/context video into ONE compact cached summary, so future reads are a cheap file read instead of re-decoding frames. This is the expensive path; [[read-context-video]] is the cheap entry point that calls this only when needed.

## Paths (all relative to the repo root)

- Video: `Context and References File/<name>.mp4`
- Cache: `Context and References File/.processed/<name>.md`  (one summary per video; create `.processed/` if missing)
- Frame extractor: `extract-frames.ps1` in **this skill's directory** — built-in Windows WPF media only. Do **not** try ffmpeg or download any binary (third-party binary downloads are blocked in this environment).

## Steps

1. **Extract ~6 frames** to a scratch dir (your session scratchpad, or `%TEMP%`):

   ```
   powershell -ExecutionPolicy Bypass -File "<this-skill-dir>/extract-frames.ps1" \
     -Src "Context and References File/<name>.mp4" -OutDir "<scratch>/ctxvid" -Count 6
   ```

   Keep the `META res=WxH dur=<s> mtime=<epoch> size=<bytes>` line it prints — those values go straight into the cache frontmatter, no extra `stat` needed. If it prints `ERROR:`, report that and stop (don't guess the content).

2. **Read 4–6 of the extracted frames** (evenly spaced across the set) — enough to understand the content and, for an animation, the motion/sequence. Read only what you need; more frames = more tokens.

3. **Write the cache file** `Context and References File/.processed/<name>.md`, **overwriting** any existing one (this is also the stale-refresh path). Use this exact shape:

   ```
   ---
   source: <name>.mp4
   source_mtime_epoch: <mtime from META>
   source_size: <size from META>
   resolution: <res from META>
   duration_s: <dur from META>
   processed_at: <today's date, YYYY-MM-DD>
   ---
   <COMPACT factual summary — see below>
   ```

   The summary body (≤ ~15 lines) is read verbatim on every future cache hit, so make it tight and act-ready:
   - What the video shows (subject, setting, on-screen text).
   - Key colors as **hex**, shapes, layout.
   - For an animation/UI: the motion or the sequence of what happens over time.
   - Anything needed to reimplement or act on it. Nothing decorative.

4. Delete the scratch frames if convenient. Done — the next read is a one-file cache hit.

## Notes

- `source_mtime_epoch` is the freshness key: [[read-context-video]] reprocesses only when the video's mtime is newer than this.
- Static frame colors from `extract-frames.ps1` are trustworthy. (A shell-thumbnail fallback exists but mangles alpha/colors — avoid relying on its color.)
