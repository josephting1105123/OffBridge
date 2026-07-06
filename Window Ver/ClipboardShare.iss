#define MyAppName "Clipboard Share"
#define MyAppVersion "3.5.1"
#define MyAppPublisher "EntropicSoftwareLab"
#define MyAppExeName "Clipboard Share.exe"

[Setup]
; AppId uniquely identifies this application. Do not change this in future versions so upgrades work seamlessly.
AppId={{8A19B1A0-7A0B-48F8-95BA-153D7614B27C}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\{#MyAppName}
DisableProgramGroupPage=yes
; Warn (Inno default) when the user browses to an existing non-empty folder, so an accidental
; install target isn't wiped silently by the file overwrite below. Do NOT set DirExistsWarning=no.
CloseApplications=yes
RestartApplications=no
OutputDir=build2\inno-setup
; No spaces: GitHub Releases sanitizes spaces in asset filenames, which would break the
; clean ClipboardShareSetup.exe download link published on the website / downloads repo.
OutputBaseFilename=ClipboardShareSetup
Compression=lzma
SolidCompression=yes
WizardStyle=modern

; --- Code signing (Authenticode) ---------------------------------------------
; The installer ships UNSIGNED today, so a brand-new publisher trips SmartScreen
; ("Windows protected your PC / unknown publisher"). Until a cert is in hand, the
; website download flow and the canned tester replies tell users to click
; "More info -> Run anyway" and verify the installer's PUBLISHED SHA-256 out-of-band (publish the
; hash on the GitHub Release / website; get it with `certutil -hashfile <exe> SHA256`) so an unsigned
; download can still be integrity-checked. See release/RUNBOOK.md.
;
; Once you have an OV/EV Authenticode cert, uncomment the two lines below and define
; the "signtool" helper on the ISCC command line so BOTH the installer and the
; bundled uninstaller are signed:
;   SignTool=signtool
;   SignedUninstaller=yes
; Define the helper when invoking the compiler (PowerShell, one line):
;   & ISCC.exe /Ssigntool="signtool sign /fd sha256 /tr http://timestamp.digicert.com /td sha256 /f $qC:\path\to\cert.pfx$q /p $qPFX_PASSWORD$q $f" "ClipboardShare.iss"
; -----------------------------------------------------------------------------

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

; [InstallDelete] intentionally omitted: the [Files] entry below uses the `ignoreversion` flag, which
; already overwrites every app file on upgrade. A blanket `Type: filesandordirs; Name: "{app}\*"` would
; recursively wipe whatever lives under the chosen target dir before copying — dangerous if the user
; browses to an existing non-empty folder. If a future version orphans files, add a targeted delete for
; those specific paths rather than restoring the blanket wildcard.

[Files]
; Copy everything from the AppImage directory to {app}
Source: "build2\compose\binaries\main-release\app\Clipboard Share\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
; Start Menu Shortcut (Always created unless DisableProgramGroupPage=no and user unchecks, but here we force it to keep it simple or we can add a task)
Name: "{autoprograms}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
; Desktop Shortcut (Optional via Task)
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Run]
; Allow inbound off-grid file transfer (TCP 65433) through Windows Defender Firewall — the daemon
; listens on 0.0.0.0:65433 and inbound is blocked by default on every interface (LAN and the Wi-Fi
; Direct hotspot alike). Delete-then-add keeps exactly one rule across reinstalls. Runs elevated.
; profile=any is REQUIRED (not a mistake): the primary off-grid path is the Wi-Fi Direct autonomous GO
; (HotspotController serves 192.168.49.1:65433). Windows classifies that fresh/unidentified adapter as
; a PUBLIC network by default, so narrowing this to profile=private would leave the off-grid hotspot
; inbound blocked and break the core use case. Keep profile=any until the AP interface is provably
; Private-classified.
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Clipboard Share (TCP 65433)"""; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""Clipboard Share (TCP 65433)"" dir=in action=allow protocol=TCP localport=65433 profile=any"; Flags: runhidden
; Auto-launch at the end
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#StringChange(MyAppName, '&', '&&')}}"; Flags: nowait postinstall skipifsilent runasoriginaluser

[UninstallRun]
; Remove the firewall rule when the app is uninstalled.
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Clipboard Share (TCP 65433)"""; Flags: runhidden
