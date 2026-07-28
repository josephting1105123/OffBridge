# Deferred review findings — 2026-07-06

Surfaced by the high-effort `code-review` pass over the "Save media to" / discovery /
Send-to-Devices working-tree diff. The two default-config breakers (landscape tray collapse,
History-open miss for gallery media) and the `isMyOwnIp` guard were fixed in the same commit.
The items below were consciously deferred — they need either a small redesign or an
environment-specific fix. Seed these as GitHub issues when the repo goes public.

## 1. Media resume can corrupt the file if "Save media to" is flipped mid-transfer

- **Where:** `DownloadEngine.kt` — write path (~L94) and resume-offset probe (~L270) each re-read
  the `save_media_to` pref independently.
- **Scenario:** With a custom SAF folder, pause a media transfer, flip the setting, resume. The
  offset is computed against the *new* destination while the `.part` sits in the *old* one, so the
  sender resumes mid-file and the bytes are appended to a fresh empty `.part` → the promoted file is
  silently missing its first N bytes. Plaintext/off-grid path only; AEAD/LAN resume forces
  `trueOffset = 0` and rewrites from scratch, so it's unaffected.
- **Verdict:** CONFIRMED.
- **Fix direction (the deeper one):** resolve the media destination **once** when the session
  starts and store it on the session (like `localFileNames`), so the write path and the resume
  probe read one value instead of re-deriving two booleans that must agree over time. This also
  subsumes findings 2 and 4 below.

## 2. Duplicate re-receive of gallery media after a lost completion-ack

- **Where:** `DownloadEngine.kt` resume-offset probe (~L281) checks `Download/<name>` to detect
  "already fully received", but completed media is moved out of Download into
  `Pictures|Movies/OffBridge` by `TransferUtils.publishReceivedMedia`.
- **Scenario:** File finishes, gets published (moved), completion-ack is lost, sender re-drives
  resume. The probe finds nothing in Download, re-downloads the whole file, publishes a second copy
  as `photo (1).jpg`. New for custom-folder users; pre-existing for no-SAF users.
- **Verdict:** PLAUSIBLE (last verifier ran out of credits before finishing; reasoned from the
  move-not-copy semantics of `publishReceivedMedia`).
- **Fix direction:** probe the actual published location for media, or record a per-file
  "completed" flag in the session/history and short-circuit resume on it.

## 3. Android 10 (API 29): raw Download write for gallery media throws EACCES

- **Where:** `DownloadEngine.kt` (~L94) `saveTree = if (routeToGallery) null else saveDir` forces
  gallery-bound media onto a raw `java.io.File` write into public `Download`.
- **Scenario:** On API 29 (scoped storage, no `requestLegacyExternalStorage`, FUSE raw-path bridge
  is Android 11+), `FileOutputStream` into public Download throws EACCES; the session pauses and
  can't recover. The pre-diff no-SAF path was already broken on Android 10; this diff extends the
  breakage to custom-folder users' photos/videos.
- **Verdict:** CONFIRMED. `minSdk = 29` (`app/build.gradle.kts`).
- **Fix direction:** on API 29 stage into `context.getExternalFilesDir(...)` or a MediaStore
  pending item instead of the public Download raw path. Part of the same staging redesign as 1.

## 4. SAF filename collision deletes the user's pre-existing file

- **Where:** `DownloadEngine.kt` — session-start dedupe (~L68) only checks `Download` + the media
  publish dir, never the SAF tree; the SAF promote step does `saveTree.findFile(finalName)?.delete()`
  then renames the received file into place.
- **Scenario:** User's chosen folder already contains `report.pdf`; a peer sends `report.pdf`.
  Dedupe sees Download/gallery as free, keeps the name, and the promote deletes the user's original —
  permanent, unprompted data loss. Pre-existing (not introduced by this diff) but lives in a touched
  function.
- **Verdict:** CONFIRMED.
- **Fix direction:** include the SAF tree in the dedupe candidate set, or make the SAF promote
  non-destructive (rename-to-unique instead of delete-then-rename).

## 5. Windows Send dialog can discard the queued file selection

- **Where:** `SendToDevicesDialog.kt` (~L189) `isPairingMode = devices.isEmpty() && !showSkeleton`
  turns the bottom button into an always-enabled "Go to Pairing" the instant the live device list
  drains for one poll cycle; no `!sending`/debounce guard.
- **Scenario:** One device checked, user moves to click Send, that device's discovery entry expires
  that instant → the click lands on `onGoToPairing()`, which navigates away and disposes the picked
  files (`FileTransferScreen` `remember` + `Crossfade`). The queue is lost. (Android has the same
  mode-swap window; its `!sending` guard only blocks the swap mid-send.)
- **Verdict:** CONFIRMED.
- **Fix direction:** debounce the empty→pairing transition (require the list to stay empty for a
  poll cycle or two), and/or preserve the chosen-files state across the pairing navigation.

## 6. Windows: OS-initiated maximize (Win+Up) can overflow the taskbar

- **Where:** `Main.kt` (~L590) — `calibrateMaximizedBoundsOnce()` is wired only to the custom
  title-bar maximize button. OS maximize paths apply the spec-correct (undivided) `maximizedBounds`
  with no calibration.
- **Scenario:** On the bundled double-scaling JBR (21.0.10) at HiDPI, a Win+Up / taskbar maximize
  overflows the work area and covers the taskbar; calibration never fires for that path. Also, the
  `> wa * 1.1` overflow threshold misses Windows custom scaling at 105–110%.
- **Verdict:** PLAUSIBLE — whether an undecorated AWT frame actually receives Win+Up is
  environment-dependent, so it couldn't be confirmed from code alone.
- **Fix direction:** attach a single `WindowStateListener` on the window that runs
  refresh+calibrate whenever `extendedState` gains `MAXIMIZED_BOTH`, covering the button and every
  OS path; and/or pin the JBR and key the divide-by-scale off `java.runtime.version` instead of a
  runtime probe.

---

### Cleanup (non-blocking, from the reuse/simplification/altitude finders)

- Dead code: both platforms' `EmptyState` restyle a `actionLabel`/`onAction` pill that no caller
  passes anymore (the CTA moved to the bottom button). Drop the button + `EMPTY_ACTION_HEIGHT`
  consts, or fold them into a shared hairline-pill component with `SystemStatusScreen`'s Pair button.
- `save_media_to` pref read is duplicated 4–5× with the key literal inline; hoist a
  `KEY_SAVE_MEDIA_TO` const + a `TransferUtils.saveMediaTo(prefs)` reader next to the value consts.
- Windows `TranslationManager.getString(k).takeIf { it != k } ?: fallback` is repeated ~59×; add a
  `getString(key, fallback)` overload.
- `showSaveMediaDialog` is a third copy of the theme/language single-choice dialog builder; extract a
  `showSingleChoicePrefDialog(...)` helper.
- Possibly-dead `save_to_setting` string key across 11 locale files after `save_file_to_setting`
  replaced its only Compose consumer (still referenced by an unused `fragment_settings.xml`).
