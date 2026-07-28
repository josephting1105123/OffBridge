# Test plan — Windows 3.7.7 / Android 3.7.3

Covers everything landed since 3.7.4: AUTH2R admission proofs, the concurrent-transfer fixes, the
off-grid stream seal, QR pairing, the first-run tour, and the clipboard/version page changes.

## Read this first: what actually goes wrong here

Almost nothing in this codebase fails loudly. The dangerous defects found in the last two audits all
had the same shape — **the transfer succeeds, no error appears, and a security property is silently
absent.** Two examples, both real, both found by reading rather than by testing:

- The off-grid seal is negotiated in both directions. If either side doesn't ask, the stream falls
  back to plaintext and the file still arrives, byte-perfect, with no warning.
- A double key derivation would have failed every GCM tag — but only against the *other* platform,
  only on real hardware.

So a test that only asks **"did the file arrive intact?" is close to worthless here.** Every test
below states what to *observe*, not just what to do. Where a log line is the evidence, it is quoted
verbatim. If you cannot find the evidence, record the test as FAILED even if the transfer worked.

### Where the evidence lives

| Platform | Log | How to reach it |
|---|---|---|
| Windows | `diagnostics.log` | App data dir; also surfaced in the app's diagnostics view |
| Android | `TransportDiag` | In-app diagnostics screen, or `adb logcat -s TransportDiag` |

---

## Builds under test

| Build | Has AUTH2R | Has off-grid seal | Has QR pairing |
|---|---|---|---|
| Android 3.7.1 | no | no | no |
| Android 3.7.2 | yes | no | no |
| Android 3.7.3 | yes | **yes** | **yes** |
| Windows 3.7.4 | no | no | no |
| Windows 3.7.6 | yes | no | no |
| Windows 3.7.7 | yes | **yes** | **yes** |

Keep an installer for 3.7.4 and 3.7.6, and an APK for 3.7.1 and 3.7.2. The old-version cells are
where the interop bugs live; without them section 2 cannot be run at all. All are still downloadable
from their Showcase release tags.

---

## 1. Off-grid encryption — highest risk

New, two-sided, and fails silently. Test this before anything else.

### 1.1 Sealed path — Windows 3.7.7 sender → Android 3.7.3 receiver

1. Disconnect both devices from every network (aeroplane mode on the phone, Wi-Fi off on the PC).
2. Windows: select a file, Send → QR.
3. Android: scan the code, accept.

**Expected — all four must hold:**
- Windows `diagnostics.log`: `QR send: off-grid stream SEALED (AES-256-GCM, FileSeal v2 off the ECDH secret)`
- Android `TransportDiag`: `handshake: off-grid stream SEALED (AES-256-GCM, FileSeal v2 off the ECDH secret)`
- The file arrives and opens correctly
- Byte-compare source and received file (`certutil -hashfile <f> SHA256` vs the phone's copy)

A transfer that arrives intact but logs `PLAINTEXT` is a **FAIL**, not a pass.

### 1.2 Prove the bytes are actually encrypted

The log line proves negotiation, not encryption. This proves encryption.

1. Create `marker.txt` containing a long unique string, e.g. `ZZQX-PLAINTEXT-CANARY-8471-ZZQX`.
2. Start Wireshark on the hotspot / Wi-Fi Direct adapter, filter `tcp.port == 65433`.
3. Send `marker.txt` off-grid from Windows 3.7.7 → Android 3.7.3.
4. In Wireshark: Edit → Find Packet → Packet bytes → String → search the canary.

**Expected:** not found. The canary appearing in the capture means the stream is plaintext.

Run the same capture against a 3.7.2 phone as a **positive control** — the canary *should* appear
there. If it doesn't appear in either run, your capture is wrong, not the encryption.

### 1.3 Back-compat — new sender, old receiver

Windows 3.7.7 → Android **3.7.2**, off-grid.

**Expected:**
- Transfer succeeds
- Windows logs `off-grid stream PLAINTEXT — the phone did not request the seal`
- No error, no hang, no corrupt file

### 1.4 Back-compat — old sender, new receiver

Windows **3.7.6** → Android 3.7.3, off-grid.

**Expected:** succeeds; Android logs `off-grid stream PLAINTEXT — sender did not advertise SEALED_STREAM (cap=…)`.

### 1.5 Android → Android off-grid (NFC)

Two phones, both 3.7.3, NFC tap to start. Expect `SEALED` on both. Then one 3.7.3 and one 3.7.2 —
expect `PLAINTEXT` on both, and a successful transfer.

### 1.6 Sealed resume

Start a sealed off-grid transfer of a large file (≥500 MB), then at ~40% walk the phone out of range
until it drops. Return and resume.

**Expected:** resumes and completes; final file hash matches. AEAD resume restarts the in-progress
file at chunk 0 by design, so progress may jump backwards — that is correct, not a bug.

---

## 2. Cross-version interop matrix

The single most valuable table here. Each cell: pair, send a file both directions, sync clipboard.

| Windows ↓ / Android → | 3.7.1 | 3.7.2 | 3.7.3 |
|---|---|---|---|
| **3.7.4** | baseline | AUTH2R one-sided | AUTH2R one-sided |
| **3.7.6** | AUTH2R one-sided | AUTH2R both | seal one-sided |
| **3.7.7** | AUTH2R one-sided | seal one-sided | **everything on** |

**Expected in every cell:** pairing works, LAN file transfer works both directions, clipboard syncs.
No cell may hang, and no cell may produce a corrupt file. Capability differences must degrade, never
fail.

### 2.1 The small-file regression (do not skip)

This reproduces a real bug that shipped in 3.7.5 and was fixed in 3.7.6. It only triggers on a
narrow input, which is exactly why it survived a release.

1. Create a file **under 400 bytes with no newline character at all** — e.g. `printf 'hello world'
   > tiny.txt` (11 bytes, no trailing newline). A short binary works too.
2. Send it over **LAN** from an **old** peer (Windows 3.7.4 or Android 3.7.1) to a **3.7.7 / 3.7.3**
   receiver.
3. Repeat with the file sealed (both peers paired and FGCM-capable).

**Expected:** arrives within a second or two.
**The bug being tested for:** a ~10 second stall followed by a failed transfer. If you see that, the
accept-challenge probe has regressed.

---

## 3. QR pairing — new, never run on hardware

Flagged by the implementing session as untested on real devices. Treat every case as unproven.

- **3.1 LAN pair.** Both devices on the same Wi-Fi. Windows shows a code, Android scans it. Expect
  both sides to land in each other's paired list with matching device names.
- **3.2 Off-grid pair.** No shared network. Windows raises its AP and puts credentials in the code.
  Expect the phone to join and pair without ever touching a router.
- **3.3 Expired code.** Show a code, wait past the freshness window (>120 s), then scan.
  **Expect a clear refusal on both sides** — and specifically *not* a one-sided pairing where one
  device shows the peer as trusted and the other doesn't. Check both paired lists afterwards.
- **3.4 Two scanners race one code.** Two phones scan the same code near-simultaneously.
  Expect exactly one to pair; the loser must fail cleanly, not half-pair.
- **3.5 Interrupted mid-handshake.** Scan, then kill the app / turn off Wi-Fi before it completes.
  Expect neither side to store trust. Re-pair afterwards to confirm no wedged state.
- **3.6 Long device name.** Set a device name at and beyond the length cap. Expect pairing to still
  work — an unbounded name previously made long-named devices unpairable.
- **3.7 Key equivalence.** After a QR pair, send a **sealed LAN file** and sync the clipboard. The QR
  key is meant to be byte-identical to a PIN-derived key, so every downstream seal must work
  unchanged. This is the test that proves the trust records are truly indistinguishable.

---

## 4. AUTH2R admission proofs

- **4.1 Normal accept.** Paired 3.7.7 ↔ 3.7.3. Send a file, accept it. Expect success and no
  `AUTH2R proof failed` in either log.
- **4.2 Resume after drop.** Start a large LAN transfer, drop Wi-Fi mid-stream, restore. Expect
  auto-resume within ~1.5–6 s, and completion.
- **4.3 Manual resume racing auto-resume.** Drop the link, then tap Resume repeatedly while the
  auto-retry timer is also firing. **Expect exactly one transfer to proceed** and the final file to
  hash-match. Two concurrent writers to one `.part` is the bug this guards.
- **4.4 Downgrade block.** Pair, transfer once (pins AUTH2R), then put an un-updated build back on
  the same IP and try to receive. Expect a refusal logged as `AUTH2R downgrade blocked`. Unpair and
  re-pair to confirm the pin clears.

---

## 5. Concurrent transfers and name collisions

- **5.1 Same filename, two senders.** Two devices send a file with the **same name** to one receiver
  simultaneously. Expect two files on disk, both intact, second de-collided (e.g. `report (1).pdf`).
  A single file, or either file corrupt, is a FAIL.
- **5.2 Same filename, sequential with a resume between.** Start transfer A, drop it, start transfer
  B with the same filename, then resume A. Expect A to resume into *its own* `.part`, not B's.
- **5.3 Folder with duplicate leaf names.** Send a folder containing `a/notes.txt` and `b/notes.txt`.
  Expect both to survive on disk.
- **5.4 Cancel outgoing.** Cancel a large transfer from the **sending** side mid-stream, both
  platforms. Expect both sides to show cancelled and no orphaned `.part`.

---

## 6. Clipboard sync

- **6.1 Auto-sync.** Copy text on either device; expect it on the other within a couple of seconds.
- **6.2 No notification spam.** With auto-sync on and **no paired device online**, copy text ten
  times on Windows. **Expect zero tray balloons.** A warning balloon per copy is the 3.7.5 bug that
  3.7.6 fixed.
- **6.3 Manual push feedback.** With a device online, use the tray "Push Clipboard". Expect exactly
  one confirmation balloon. With no device online, expect exactly one warning balloon.
- **6.4 Large clipboard.** Copy ~1 MB of text. Expect it to transfer or fail cleanly, not hang.
- **6.5 Accept toggle.** Turn off "accept clipboard from other devices" and confirm nothing arrives.

---

## 7. First-run tour, i18n and UI

- **7.1 Fresh install tour.** Install Windows 3.7.7 on a machine with no existing config. Expect the
  tour to auto-start and chain Send → History → System Status. Quit mid-tour, relaunch: it must
  **not** re-offer.
- **7.2 Tour in all 10 languages.** Switch language, trigger the tour, and read every step. Check for
  untranslated English, clipped text, and overflowing bubbles.
- **7.3 Nav-rail clipping (known suspect).** Set German, French, then Russian. The rail is 80 dp with
  `maxLines=1, softWrap=false`, and "Aktualisierungen (Beta)" / "Mises à jour (bêta)" /
  "Обновления (бета)" are expected to clip. Confirm and capture screenshots — this is an open defect,
  not a regression.
- **7.4 Tour copy uses desktop verbs.** Windows tour strings still say "tap" ("tap a capsule"). Known
  and unfixed; confirm scope before it is fixed across 10 locales.
- **7.5 Version pages.** Check the Android-version and Windows-version screens render correctly in
  every language, with Updates marked Beta.

---

## 8. Update detection

- **8.1 Android update prompt.** Install Android 3.7.2 with 3.7.3 published. Expect exactly one
  "update available" prompt naming 3.7.3.
- **8.2 No phantom prompt.** With the newest build installed, open the Updates screen. Expect
  "up to date" — **not** a prompt for a version the download would not deliver. This is the failure
  the release-title guard exists to prevent.
- **8.3 Windows updater.** Same, on the Windows client.

---

## 9. Install, upgrade, uninstall

- **9.1 Clean install** of 3.7.7 on a machine that has never had it.
- **9.2 Upgrade over 3.7.6** with the app running. Expect the installer's "still running" prompt, and
  paired devices to survive the upgrade.
- **9.3 Uninstall.** Both shortcuts removed, tray icon gone, no leftover process.
- **9.4 SmartScreen / SAC.** Expect an unsigned-publisher warning — currently correct behaviour, not
  a bug. Confirm the SHA-256 on the website matches the downloaded file.

---

## 10. Regression smoke (run last, every release)

- Pair by PIN, transfer both directions, unpair, confirm keys are gone.
- Send a multi-GB file over LAN; confirm progress is accurate and the hash matches.
- Send a folder of 500+ small files.
- Send from the Android share sheet.
- Quick-settings tile pushes the clipboard.
- Light and dark themes on both clients.
- Kill the Android app mid-transfer and confirm the resume path.

---

## Reporting

For each failure record: build versions on both devices, the exact steps, the relevant log excerpt,
and whether the transfer *appeared* to succeed. That last field matters more than usual here —
"worked, but logged PLAINTEXT" is a security defect, and it will look like a pass to anyone who
isn't reading the log.
