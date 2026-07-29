# Play Console — Store listing copy

App: **OffBridge** · package `com.EntropicSoftwareLab.ClipboardShare` · v3.7.2 (live) → 3.7.3 (pending signing)
Category: **Tools** (alt: Productivity) · Free · No ads · No in-app purchases
Contact email: `entropic.software.lab@gmail.com`

> The package name stays `com.EntropicSoftwareLab.ClipboardShare` forever — Play identity is
> permanent and unrelated to the display name. Renaming the listing is safe; renaming the
> package is impossible.

## App name (max 30 chars)
```
OffBridge: Clipboard & Files
```
(28 chars.) A bare `OffBridge` is cleaner, but the name is brand-new and carries no recognition —
nobody searches for it. The descriptor is what makes the listing findable at all in the first months.
Drop it once the name means something.

## Short description (max 80 chars)
```
Off-grid file transfer & clipboard sync. No cloud, no account, no telemetry.
```
(75 chars.) The previous version ended "AES-256 encrypted." — removed, because it read as a blanket
claim that did not hold for off-grid transfers. Encryption is now described precisely below instead
of being compressed into a slogan that overstates it.

## Full description (max 4000 chars)
```
OffBridge moves your clipboard and your files between Android and Windows — directly, privately, with no cloud in the middle.

Copy on your phone, paste on your PC. Send a file across the room without uploading it to anyone's server. Everything travels straight between your own devices, over your local network or over a direct device-to-device radio link.

NO CLOUD. NO ACCOUNT. NO TELEMETRY.
There are no servers behind OffBridge. We never see your clipboard, your files, or who you are. You don't sign in. Your data stays on your devices and on your local network.

WORKS EVEN WITH NO INTERNET
OffBridge finds the fastest link between your devices and steps down gracefully when there's no router:
• LAN — same Wi-Fi, fastest path
• Wi-Fi Direct / hotspot bridge — no router needed
• QR code — scan to connect with no shared network
• NFC tap — tap two phones to start an off-grid transfer
• Bluetooth control channel — used to set up the link when nothing else is available

ENCRYPTION, STATED PRECISELY
• Clipboard content is always protected with authenticated AES-256-GCM. Since version 3.5.0 the app rejects any clipboard item that arrives unencrypted rather than accepting it.
• Files sent over your local network get the same authenticated AES-256-GCM protection whenever both devices support it — every version from 3.4.0 onward.
• Anti-downgrade protection: once a device has proved it supports encryption, OffBridge refuses the transfer rather than quietly falling back to plaintext.
• Files sent off-grid (Wi-Fi Direct or hotspot, no router) are encrypted from Android 3.7.3 and Windows 3.7.7 onward. Earlier versions sent those file contents unencrypted across the direct link between the two devices.
• Pairing uses an ephemeral ECDH key exchange with a 6-digit verification code — confirm the codes match on both screens and impostors are locked out.

BUILT FOR REAL USE
• Send single files or batches, with live progress and cancel
• Resume interrupted transfers instead of starting over
• Filtered transfer history — browse what you've sent and received
• Quick-settings tile and share-sheet integration — push to your PC without opening the app
• Light and dark themes
• Available in 10 languages

CROSS-PLATFORM
A companion Windows app (Windows 10/11, 64-bit) pairs with the Android app for two-way clipboard sync and file transfer.

PERMISSIONS — AND WHY
• Network / Wi-Fi / Nearby devices: discover and connect to your devices on the local network
• Camera: scan pairing QR codes
• NFC: tap-to-transfer
• Notifications & foreground service: keep the connection alive and show transfer status
• Battery-optimization exemption (optional): stop Android from killing the background sync

OffBridge is for people who'd rather not put their clipboard and their files in the cloud. Your data is yours — and we'd like to keep it that way.
```

## What's new (release notes — max 500 chars)
```
• Off-grid transfers over Wi-Fi Direct and hotspot are now encrypted with AES-256-GCM (needs Windows 3.7.7 on the other end)
• The app is now OffBridge — same app, same devices, new name
• Clipboard tab switched to Auto-Sync; the manual push button is gone
• Privacy policy rewritten to state exactly which channels are encrypted
• Fixes to transfer resume and pairing reliability
```

## Graphics to produce (Play HARD-BLOCKS publish without these)
Source material exists in `website/assets/screenshots/` (light + dark, Android + Windows).
- **App icon:** 512×512 PNG, 32-bit, no alpha rounding — export from `app/src/main/res/mipmap-*`.
- **Feature graphic:** 1024×500 PNG/JPG (top of the listing). Must show the new name.
- **Phone screenshots:** ≥4, 16:9 or 9:16, min 320px side — crop from `website/assets/screenshots/android-*`.
- (Optional) 7"/10" tablet screenshots if you declare tablet support.

Screenshot freshness, as of 29 Jul 2026:
- `android-clipboard-{dark,light}` — **current.** Re-captured 29 Jul (b1606c4) on the shipping build:
  serif heading, "Clipboard (Auto-Sync)", no manual push button, no old app name. Usable as-is, but
  they came through WhatsApp and so are 591px wide rather than the 1280px the others are; Play's
  minimum is 320px, so they clear it. Re-shoot straight off the device if you want full resolution.
- `android-transfer-*`, `android-sas-verify-*`, `windows-*` — captured 23 Jun, still show the older
  sans-serif type. No old app name is visible in any of them, so they are usable but off-brand next
  to the refreshed pair. Re-capture before upload if you want one consistent set.

## Other listing fields
- **Languages:** add store-listing translations for the 9 non-English locales if you want localized listings (optional; the app itself is already localized).
- **Privacy policy URL:** `https://offbridge.app/privacy.html` (live since 29 Jul 2026). The old
  `clipboard-share-a8e.pages.dev` URL still serves the same site and must keep resolving: it is
  compiled into every shipped build.
- **Tags / category:** Tools; tags like "file transfer", "clipboard", "privacy".
