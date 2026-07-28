# OffBridge 3.5.1

Android and Windows both move to 3.5.1. These notes cover everything since 2.1.

## Updating from 2.1 — read this first

The app changed its package identity when it became **OffBridge** by Entropic Software Lab. If your 2.1 install predates the rename, this version installs **alongside** the old app instead of updating it. Uninstall the old version first, then pair your devices again — pairings, history and settings do not carry over.

## Your transfers are now encrypted

The biggest change since 2.1 is that everything crossing your network is now encrypted and authenticated:

- **Files are end-to-end encrypted on your local network** (AES-256-GCM). Previously file contents traveled unencrypted; now every chunk is sealed with keys unique to each transfer session, so captured traffic can't be read, replayed, or spliced.
- **Clipboard sync uses authenticated encryption everywhere.** The older, weaker cipher mode has been removed entirely.
- **Downgrade protection.** Once two devices have talked encrypted, they remember it and refuse to fall back to an unencrypted connection — a hostile device on your network can no longer strip encryption by tampering with discovery.
- **Sender verification.** Before the incoming-file prompt appears, the receiver checks that the sender actually holds your shared pairing key, so a stranger on the network can't impersonate a paired device.
- Pairing PINs are derived more safely, and the app's internal screens are no longer reachable by other apps on your phone.

## New

- **Photos and videos land in your gallery.** Received media is filed into your gallery collections instead of a plain downloads folder.
- **Windows can send off-grid via QR.** Show a QR code on the PC, scan it with your phone, and the files transfer directly — no internet or pairing setup needed.
- **Better guidance when nothing is paired.** The send screen now explains the situation and offers a "Go to Pairing" shortcut instead of an empty list.
- **Reworked tutorials.** The first-launch tour and per-screen tutorials now use realistic previews of the actual screens, on both Android and Windows.

## Languages and accessibility

- Full translations in nine languages — German, Spanish, French, Indonesian, Japanese, Portuguese, Russian, Simplified and Traditional Chinese — reviewed for accuracy against native usage.
- Screen-reader support: buttons, tabs and settings switches announce correctly with TalkBack.
- The privacy policy is published in ten languages.

## Fixes and reliability

- Fixed a bug where a file that changed size during a batch send corrupted every file after it.
- Interrupted encrypted transfers now resume correctly instead of failing near the end.
- **Windows:** fixed a crash after a failed resume, helper-process hangs and resource leaks, and the app now shuts down cleanly on exit.
- **Android:** transfer history survives a corrupted record instead of wiping; fixed crashes in Settings and in the history sheet during a resume; battery and Wi-Fi locks are released properly when the service stops.
- A full audit of both clients logged 44 issues; every high-severity one is fixed in this release.

## Under the hood

Release builds are now minified (R8), and every change runs through CI covering unit tests and cross-platform protocol golden vectors on both clients.
