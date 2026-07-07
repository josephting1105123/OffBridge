# ClipboardShare 3.5.3 (Windows)

A Windows-only release. Features, crypto and pairing are the same as 3.5.2 — this release reworks the Windows **installer** and gives the in-app power menu a small visual refresh. Android stays on 3.5.1 (Google Play).

## Redesigned setup

The installer now matches the app's look end to end — charcoal and emerald, not a generic wizard:

- A custom app icon, used by the setup, the app, and its Start Menu and desktop shortcuts.
- A dark, branded wizard: charcoal pages, emerald headings and accent rule, and emerald primary buttons (Next / Install / Finish) with dark tonal Back / Cancel — all drawn from the Clipboard Share mark.

## Refreshed power menu

The in-app power menu (Restart Services / Power Off) now uses the app's button language — an emerald tonal action and a muted-red destructive one — instead of the older flat style.

## One install, never two copies

The setup now checks for an existing Clipboard Share before it installs:

- If a previous version is found, it confirms the installed version on the welcome page and updates it **in place**, replacing the current files. You can't end up with two parallel copies.
- If the same version is already installed, it asks whether to reinstall (repair) instead of installing again.
- If a newer version is installed, it warns before downgrading.
- On a clean machine with nothing installed, it proceeds as a normal fresh install.

## Notes

The installer is still unsigned, so Windows may show a SmartScreen "unknown publisher" notice the first time — choose **More info → Run anyway**. You can verify the download against the SHA-256 published on the release page and the website.
