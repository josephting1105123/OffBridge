# ClipboardShare 3.5.3 (Windows)

A Windows-only release. The app itself is unchanged from 3.5.2 — same features, same crypto, same pairing. This release reworks the Windows **installer**. Android stays on 3.5.1 (Google Play).

## Redesigned setup

The installer is now branded end to end: a custom app icon (used by the setup, the app, and its Start Menu and desktop shortcuts) and a matching setup wizard, all drawn from the Clipboard Share mark.

## One install, never two copies

The setup now checks for an existing Clipboard Share before it installs:

- If a previous version is found, it confirms the installed version on the welcome page and updates it **in place**, replacing the current files. You can't end up with two parallel copies.
- If the same version is already installed, it asks whether to reinstall (repair) instead of installing again.
- If a newer version is installed, it warns before downgrading.
- On a clean machine with nothing installed, it proceeds as a normal fresh install.

## Notes

The installer is still unsigned, so Windows may show a SmartScreen "unknown publisher" notice the first time — choose **More info → Run anyway**. You can verify the download against the SHA-256 published on the release page and the website.
