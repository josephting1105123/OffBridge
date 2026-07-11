# Canned tester replies

Copy-paste responses so post-expiry tester correspondence needs zero thinking. Keep them friendly and
short. The point of the "known behavior" ones is so expected quirks don't get logged as bugs.

## Acknowledge a report
> Thanks for trying Clipboard Share and for the report — really helpful. I've logged it and I'll look
> at it in the next update cycle. If you hit it again, a screenshot or the rough steps would help a lot.

## Ask for a repro
> Thanks! To track this down, could you tell me: which device + Android (or Windows) version, what you
> were doing right before it happened, and whether it repeats every time or was a one-off? A screenshot
> of the screen helps too.

## Known behavior — doesn't auto-start after a reboot (Android)
> That's expected, not a bug. On Android 14 and newer, the system no longer lets apps start their
> background service straight from boot, so Clipboard Share starts its sync the first time you open the
> app after a restart. Open it once after rebooting and it stays running.

## Known behavior — Windows "unknown publisher" / SmartScreen warning
> That warning is expected for now — the Windows installer isn't code-signed yet, so Windows shows the
> "unknown publisher" screen for any new app. It's safe to proceed: click **More info → Run anyway**.
> If you want to verify the download first, the SHA-256 checksum is on the download page — it should
> match `certutil -hashfile "Clipboard Share Setup.exe" SHA256`.

## Windows install stops at "Creating shortcuts" (Access is denied / 0x80070005)
> That error is your security software — Windows "Ransomware protection" (Controlled Folder Access) or
> your antivirus — blocking the app from adding a Start Menu shortcut. It's not a virus warning about
> the app. The latest installer (3.5.7+) works around it so the app still installs; if you're on it,
> just click OK and the app is ready (launch it from its folder in Program Files). To get the Start Menu
> shortcut too: Windows Security → *Virus & threat protection* → *Ransomware protection* → *Allow an app
> through Controlled folder access* → add the installer (or turn Controlled folder access off briefly),
> then run the installer again.

## Known behavior — "app won't install / install failed" (signature mismatch)
> This usually happens when both versions are installed from different sources. The Play Store version
> and the direct-download (GitHub/website) version are signed differently, so a device can't update
> from one to the other. Please uninstall the one you have, then install from a single source — Play
> **or** the website — and it'll work.

## Feature request
> Appreciate the idea — noted it on the list. The current focus is stability for this release, so new
> features come after, but this is exactly the kind of feedback I want.

## Close / thanks
> Thanks again for testing. Feedback like this is what makes the release solid — much appreciated.
