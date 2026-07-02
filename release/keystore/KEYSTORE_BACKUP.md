# Keystore backup & Play App Signing — do this before anything else

The release upload key is the **one irreversible thing** in this whole launch. Lose it without
Play App Signing and you can never publish an update under the same signature — not to Play, not
as a same-signature GitHub APK. This is the single most important "do it while you still can" task.

## 1. Generate it
Run `release/keystore/generate-upload-keystore.ps1`. It writes
`%USERPROFILE%\.android-keystores\clipboardshare-upload.jks` and prompts for passwords.

## 2. Record the credentials
Put these in a password manager (1Password / Bitwarden / KeePass) as one entry:
- store password
- key password
- key alias: `clipboardshare-upload`
- the distinguished name (CN/O) you typed
- SHA-256 fingerprint — get it with:
  `keytool -list -v -keystore <path>\clipboardshare-upload.jks -alias clipboardshare-upload`

## 3. Back up the .jks to 2+ offline places
The file is small. Copy it to at least two separate, durable locations, e.g.:
- an encrypted USB drive kept physically apart from your laptop
- a password-manager file attachment, or an encrypted archive in personal cloud storage

Do **not** rely on the single copy on your dev machine, and do **not** commit it (it is gitignored).

## 4. Enroll in Play App Signing (your recovery net)
At your first upload, opt in to **Play App Signing**. Google then holds the *app-signing* key and
you keep the *upload* key. If the upload key is ever lost, Google support can reset it — a multi-day
queue, but it means a lost upload key is recoverable. Without enrollment, it is not.

## Note on the two distribution lanes
With Play App Signing, the APK users install **from Play** is re-signed by Google's app-signing key,
so it has a **different signature** than the APK you sign directly for **GitHub/website**. A tester who
installs one and then sideloads the other gets `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Decide one:
- **Same applicationId** (`com.EntropicSoftwareLab.ClipboardShare`) — simplest; document "install from
  ONE channel, you can't cross-update between Play and the sideload APK," or
- give the GitHub build a distinct `applicationIdSuffix` (e.g. `.direct`) so both can coexist on a device.

See `release/RUNBOOK.md` for the rebuild/re-sign recipe and the post-expiry rollback story.
