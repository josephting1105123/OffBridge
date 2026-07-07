# ClipboardShare 3.5.5 (Windows)

A Windows-only release. Features, crypto and pairing are the same as 3.5.4 — this release fixes
**sending a folder**. Android is on 3.5.2 (which carries the receiver-side and Android-only fixes).

## Sending a folder works

Picking a folder to send used to stall the transfer on **Resume at 0%** and never start. The sender
was handing the folder itself to the file stream instead of the files inside it, which failed
immediately. Now a picked folder is walked into its files before the offer — on both the LAN and the
off-grid QR paths — so the whole folder sends.

If two files in the folder share the same name (for example `a.txt` in two different subfolders), each
is saved under its own name on the receiving side instead of one overwriting the other. This holds
through a resumed transfer as well.

## Notes

The installer is still unsigned, so Windows may show a SmartScreen "unknown publisher" notice the first
time — choose **More info → Run anyway**. You can verify the download against the SHA-256 published on
the release page and the website.
