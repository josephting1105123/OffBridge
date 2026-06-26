# Screenshots for the website

Drop screenshot files in this folder using these exact names so they wire in
without renaming. PNG preferred. Hide any private content before capturing.

You chose **both themes**, so each shot has a `-dark` and a `-light` version.
If you can only do one theme for some shots, just send the `-dark` one — the
site falls back to the dark image in light mode automatically.

⚠️ Use NON-SENSITIVE / dummy data when capturing — these images ship publicly.
Before the clipboard shot, copy a few throwaway items (e.g. "The quick brown fox…",
"https://example.com", "Sample copied text"). To hide your real device model, rename
the device to something generic (e.g. "My Phone") in the app before shooting the
System Status / Send Files screens.

Capture tips:
- **Android:** portrait, full-screen (Power + Volume-Down). Native res is fine
  (~1080×2340). Capture once in the app's Dark theme, once in Light theme.
- **Windows:** capture the app window only (Alt + PrtScn, or Snipping Tool →
  Window). Aim ≥1200px wide. Once dark, once light.
- Don't worry about exact cropping — the site frames/masks them in CSS.
- Try to keep the SAME screen/content between the dark and light pair so the
  theme swap looks clean.

## Essential (one per showcase section)
| dark filename | light filename | platform | what to show |
|---------------|----------------|----------|--------------|
| `android-pairing-qr-dark.png` | `android-pairing-qr-light.png` | Android | Pairing screen showing the QR code |
| `android-sas-verify-dark.png` | `android-sas-verify-light.png` | Android | 6-digit security-code verify screen |
| `android-clipboard-dark.png`  | `android-clipboard-light.png`  | Android | Clipboard tab: push button + recent history |
| `android-transfer-dark.png`   | `android-transfer-light.png`   | Android | A file transfer in progress (bar, %, filename) |
| `windows-main-dark.png`       | `windows-main-light.png`       | Windows | Main app window (clipboard / home view) |
| `windows-transfer-dark.png`   | `windows-transfer-light.png`   | Windows | Transfer view or QR sender window |

## Nice-to-have (richer sections; skip any you don't have)
| dark filename | light filename | platform | what to show |
|---------------|----------------|----------|--------------|
| `android-history-dark.png`    | `android-history-light.png`    | Android | History with filter capsules |
| `android-send-sheet-dark.png` | `android-send-sheet-light.png` | Android | Send chooser (Connected Devices vs NFC/QR) |
| `android-nfc-dark.png`        | `android-nfc-light.png`        | Android | NFC tap / antenna-position guide |
| `android-scanner-dark.png`    | `android-scanner-light.png`    | Android | QR scanner (Receive) |
| `android-home-dark.png`       | `android-home-light.png`       | Android | Paired devices / home |
| `windows-settings-dark.png`   | `windows-settings-light.png`   | Windows | Settings or system-tray |

## Branding (single file, no theme variants)
| filename | what to show |
|----------|--------------|
| `app-icon.png` | 512×512 app icon (used for favicon + brand mark) |
