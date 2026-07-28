# Capturing screenshots for the website + showcase

Every image the site serves is a `.webp`. Raw captures go in `_raw/`, and
`website/tools/shots.mjs` turns them into correctly-sized `.webp` files. You
never resize or convert by hand.

```
capture (emulator / Windows app)  →  _raw/<exact-target-name>.png  →  node tools/shots.mjs  →  <name>.webp
```

Commands were verified on 2026-07-18 against the `Pixel_10_Pro` AVD (API 37)
and the committed assets — the pipeline reproduces the existing 800×1733 files
byte-for-byte in dimensions.

---

## 0. One-time setup

```bash
cd website/tools
npm install          # pulls sharp (Apache-2.0); node_modules is gitignored
```

There is no `cwebp`, ImageMagick, or ffmpeg on this machine. `sharp` is the
only encoder in play, and it is a capture-time tool only — the site itself
still has no build step.

---

## 1. What to shoot

The site wires in **five pairs**. Anything missing renders the CSS
"Screenshot pending" diagonal-stripe placeholder on the live page, so these are
the ones that matter:

| base name | platform | screen | where it appears |
|---|---|---|---|
| `android-clipboard` | Android | Clipboard tab — push button, toggle, recent copies | hero phone + Clipboard section |
| `android-sas-verify` | Android | 6-digit pairing verification | Security section |
| `android-transfer` | Android | A transfer mid-flight: bar, %, filename | Transfer section |
| `windows-main` | Windows | Main window (System Status / home view) | hero desktop |
| `windows-transfer` | Windows | Transfer view or QR sender | Transfer section |

Each needs a `-dark` and a `-light` variant, e.g. `android-clipboard-dark.webp`.
Dark-only is tolerated — `device.css` falls back automatically — but a mismatched
pair looks worse than no pair, so shoot the *same screen with the same content*
in both themes.

Spare shots worth having for the showcase repo (not referenced by `index.html`,
so they cost nothing if skipped): `android-pairing-qr`, `android-history`,
`android-send-sheet`, `android-nfc`, `android-scanner`, `android-home`,
`windows-settings`.

Run `node tools/shots.mjs --check` any time to see what's present.

---

## 2. Privacy pass — do this before you capture

These images ship publicly and are hard to un-ship.

- **Rename both devices to something generic** — "My Phone" and "Windows PC" —
  in Settings on each platform.
  The Windows shot currently live on the site leaks a real hardware model
  (`Xiaomi 25053PC47G`) in the paired-devices list. Don't repeat that.
- **Seed the clipboard with throwaway text** before the clipboard shot:
  something like `The quick brown fox…`, `https://example.com`,
  `Meeting notes — Q3 draft`. Realistic beats `Sample 1 / Sample 2 / Sample 3`,
  which is what's on the site today and reads as unfinished.
- **Use plausible filenames** for transfer shots — `holiday-photos.zip`,
  `contract-draft.pdf`, `demo-clip.mp4`. Nothing personal, nothing named
  `test123`.
- **Check for IP addresses** in device lists. The tutorial replicas use
  `10.0.0.2` / `10.0.0.3`, which are fine; your real LAN addresses are not.

---

## 3. Android capture (Pixel 10 Pro emulator)

### 3a. Boot and install

```bash
export ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
"$LOCALAPPDATA/Android/Sdk/emulator/emulator.exe" -avd Pixel_10_Pro -no-boot-anim &
"$ADB" wait-for-device
until [ "$("$ADB" shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done
```

Install a fresh build if the app isn't already on the AVD:

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat :app:assembleDebug
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3b. Clean the status bar (demo mode)

This is what separates a staged screenshot from a snapshot of your phone. Run
it once per boot:

```bash
"$ADB" shell settings put global sysui_demo_allowed 1
D() { "$ADB" shell am broadcast -a com.android.systemui.demo "$@" >/dev/null; }
D -e command enter
D -e command clock -e hhmm 0941
D -e command battery -e level 100 -e plugged false
D -e command network -e wifi show -e level 4 -e fully true
D -e command network -e mobile hide
D -e command notifications -e visible false
D -e command status -e volume hide -e bluetooth hide -e alarm hide -e location hide
```

`-e fully true` on the network line is not optional — without it the Wi-Fi icon
carries a "no internet" exclamation badge, because the emulator's Wi-Fi never
validates.

To undo: `D -e command exit`.

**Known residual:** on this API 37 system image a small shield glyph stays
pinned next to the clock and ignores every `status ... hide` slot. It's one
mono icon in an otherwise clean bar. Either accept it or paint it out in the
raw PNG before encoding.

### 3c. Set the theme — inside the app, not the system

`adb shell cmd uimode night no` **does not** flip this app to light. It has its
own theme setting and ignores the system value. Verified: the system went to
light mode and the app stayed dark.

Switch it by hand: **power FAB → Settings → theme mode**, then capture the
whole set, then switch and capture the whole set again. Batching by theme
beats toggling per shot.

### 3d. Lite vs Pro

Lite is the default UI since 3.5.4 (`design_mode` pref). Pro is the toggle in
the power-menu bottom sheet — the green/red slider.

Shoot **Pro** for the website. The Pro screens have the bottom nav and denser
content, which reads better inside a small phone bezel; the Lite hub is three
large tiles that look sparse when scaled to 244px wide. Use Lite shots for the
showcase repo if you want to show off the simpler mode.

### 3e. Capture

```bash
"$ADB" exec-out screencap -p > website/assets/screenshots/_raw/android-clipboard-dark.png
```

Name the file **exactly** its target name — the encoder derives everything from
that.

Two timing traps:

- **The clipboard toast.** The app reads the clipboard on launch and Android
  shows "OffBridge pasted from your clipboard" across the bottom nav.
  Wait ~4 seconds after any navigation before capturing. Verified: it covers
  the bottom nav bar completely.
- **Transfer progress.** For `android-transfer` you need a live transfer, which
  means a real peer. Start a large-enough file (100 MB+) from the Windows
  client so the bar sits mid-flight long enough to shoot. Alternatively use the
  tutorial replica screens (System Status → "How to use"), which render
  pre-populated fake transfers with no peer needed.

---

## 4. Windows capture

The window is undecorated with a custom title bar and opens **portrait** —
about 80% of monitor height at a locked 9:19.5. The site's desktop card is
landscape, so you must widen it first.

1. Launch: `./gradlew :window-ver:run`, or the built exe at
   `Window Ver/build2/compose/binaries/main-release/app/OffBridge/OffBridge.exe`.
2. Drag a corner handle out to roughly **16:8.5** — the committed shots are
   1600×848. Maximizing also works and gives ~16:8.7 on a 1920×1080 display.
3. Capture the window only: **Alt + PrtScn**, or Snipping Tool → Window mode.
   Aim for ≥1200px wide; the encoder downscales, it will not upscale.
4. Save as PNG into `_raw/` under the exact target name.

Do not add a drop shadow or a mockup frame. `device.css` deliberately puts no
chrome around desktop shots because the window already carries its own title
bar (see the comment at `css/device.css:53`).

Theme: same rule as Android — switch inside the app's Settings screen.

---

## 5. Encode

```bash
cd website/tools
node shots.mjs
```

Output:

```
Encoding 10 capture(s):
  android-clipboard-dark.webp  800x1733  36KB (cropped from 2856px tall)
  ...
```

What it does per platform:

- **Android** — crops the bottom of the frame so the image lands on exactly
  9:19.5, which is the ratio the CSS phone bezel masks to, then downscales to
  **800px wide** and encodes WebP q82. On a 1280×2856 Pixel 10 Pro capture that
  trims 84px, which is precisely the gesture pill. Result: 800×1733, matching
  the existing assets.
- **Windows** — downscales to **1200px wide**, no crop, WebP q82.

Then verify:

```bash
node shots.mjs --check
```

---

## 6. Housekeeping

- The `width`/`height` attributes on every `<img class="shot">` in `index.html`
  are intrinsic-size hints that prevent layout shift. Android shots are always
  `800×1733`. Windows shots vary with your crop — **update the attributes** if
  your new Windows captures aren't 1200×636.
- Four files in this folder are named `windows-*.png` but hold JPEG data. When
  you regenerate them, the `_raw/` originals should be true PNGs and the old
  mislabelled files should be deleted rather than overwritten.
- The `.jpg`/`.png` sources currently committed here are the pre-WebP originals.
  Keeping raws in `_raw/` is enough going forward.
- After replacing images: commit and push to `main`. Cloudflare Pages rebuilds
  the live site automatically.
