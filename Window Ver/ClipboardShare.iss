#define MyAppName "Clipboard Share"
#define MyAppVersion "3.3.0"
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
; Remove previous versions of files silently before installing the new ones
DirExistsWarning=no
CloseApplications=yes
RestartApplications=no
OutputDir=build2\inno-setup
OutputBaseFilename=Clipboard Share Setup
Compression=lzma
SolidCompression=yes
WizardStyle=modern

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[InstallDelete]
; Delete all existing files in the app directory before installing the new version to ensure a clean slate
Type: filesandordirs; Name: "{app}\*"

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
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Clipboard Share (TCP 65433)"""; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""Clipboard Share (TCP 65433)"" dir=in action=allow protocol=TCP localport=65433 profile=any"; Flags: runhidden
; Auto-launch at the end
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#StringChange(MyAppName, '&', '&&')}}"; Flags: nowait postinstall skipifsilent runasoriginaluser

[UninstallRun]
; Remove the firewall rule when the app is uninstalled.
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Clipboard Share (TCP 65433)"""; Flags: runhidden
