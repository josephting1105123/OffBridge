---
name: read-context-video
description: Understand a video in "Context and References File" cheaply via a cached text summary instead of re-decoding it every time. Use whenever you need to read, reference, or act on a context/reference video the user points to (e.g. "the animation I added", "the reference video", "the screen recording", a named .mp4 in Context and References File). Reads a small cached summary on a hit; only reprocesses when the video actually changed.
---

# Read Context Video

Get a description of a reference video **without** re-decoding its frames every time. A one-file summary is cached next to the videos; decoding only happens on a miss or when the video changed. This is the cheap entry point — it delegates the expensive decode to [[process-context-video]] only when necessary.

## Paths (relative to repo root)

- Video: `Context and References File/<name>.mp4`
- Cache: `Context and References File/.processed/<name>.md`

## Steps

1. Resolve the target video's basename `<name>` (the filename without `.mp4`). Cache file = `Context and References File/.processed/<name>.md`.

2. **If the cache file exists**, check freshness in one shell line:

   ```
   stat -c %Y "Context and References File/<name>.mp4"
   ```

   Compare that epoch to the `source_mtime_epoch:` in the cache file's frontmatter:
   - **video mtime ≤ cached `source_mtime_epoch`** → **cache hit**. Use the summary body as your understanding of the video. **Stop — do not extract frames.** (This is the whole point: one small file read.)
   - video mtime **>** cached value → the video changed; treat as a miss (step 3).

3. **On a miss** (no cache file, or stale) → invoke the **process-context-video** skill for `<name>`. It extracts frames, writes/overwrites the cache, and returns. Then read the fresh cache file and use its summary.

## Why

Frame extraction + vision analysis is expensive and identical every run; the answer only changes when the video does. A fresh cache turns that into a single small read. Never re-decode a video whose cached summary is still current.
