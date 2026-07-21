# Play Console — Store listing copy

App: **Clipboard Share** · package `com.EntropicSoftwareLab.ClipboardShare` · v3.7.1 (versionCode 29)
Category: **Tools** (alt: Productivity) · Free · No ads · No in-app purchases
Contact email: `entropic.software.lab@gmail.com`

## App name (max 30 chars)
```
Clipboard Share
```

## Short description (max 80 chars)
```
Off-grid clipboard sync & file transfer. No cloud, no account. AES-256 encrypted.
```
(79 chars.)

## Full description (max 4000 chars)
```
Clipboard Share moves your clipboard and your files between Android and Windows — directly, privately, with no cloud in the middle.

Copy on your phone, paste on your PC. Send a file across the room without uploading it to anyone's server. Everything travels straight between your own paired devices and is encrypted end-to-end with AES-256.

NO CLOUD. NO ACCOUNT. NO TELEMETRY.
There are no servers behind Clipboard Share. We never see your clipboard, your files, or who you are. You don't sign in. Your data stays on your devices and on your local network.

WORKS EVEN WITH NO INTERNET
Clipboard Share finds the fastest link between your devices and steps down gracefully when there's no router:
• LAN — same Wi-Fi, fastest path
• Wi-Fi Direct / hotspot bridge — no router needed
• QR code — scan to connect with no shared network
• NFC tap — tap two phones to start an off-grid transfer (Android)
• Bluetooth control channel — used to set up the link when nothing else is available

SECURE BY DESIGN
• AES-256-GCM authenticated encryption on every clipboard item and file
• Pairing uses an ephemeral ECDH key exchange with a 6-digit verification code — confirm the codes match and impostors are locked out
• Anti-downgrade protection: once a device proves it supports encryption, Clipboard Share refuses to silently fall back to plaintext

BUILT FOR REAL USE
• Send single files or batches, with live progress and cancel
• Filtered transfer history — browse what you've sent and received
• Quick-settings tile and share-sheet integration on Android — push to your PC without opening the app
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

Clipboard Share is for people who'd rather not put their clipboard and their files in the cloud. Your data is yours — and we'd like to keep it that way.
```

## What's new (release notes — max 500 chars, for v3.4.0)
```
• Downgrade-resistant encryption: once a peer proves it supports AES-256, Clipboard Share won't fall back to plaintext
• Encrypted LAN file transfer end to end
• Full localization in 10 languages
• Smaller, faster build (R8) and hardened security
```

## Graphics to produce (Play HARD-BLOCKS publish without these)
Source material exists in `website/assets/screenshots/` (light + dark, Android + Windows).
- **App icon:** 512×512 PNG, 32-bit, no alpha rounding — export from `app/src/main/res/mipmap-*`.
- **Feature graphic:** 1024×500 PNG/JPG (shown at the top of the listing). Make one from the brand + a screenshot.
- **Phone screenshots:** ≥4, 16:9 or 9:16, min 320px side — crop from `website/assets/screenshots/android-*`.
- (Optional) 7"/10" tablet screenshots if you declare tablet support.

## Other listing fields
- **Languages:** add store-listing translations for the 9 non-English locales if you want localized listings (optional; the app itself is already localized).
- **Privacy policy URL:** `https://<pages-domain>/privacy.html` (deploy `website/privacy.html` first).
- **Tags / category:** Tools; tags like "file transfer", "clipboard", "privacy".
