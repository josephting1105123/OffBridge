[Setup]
; App Information
AppName=Clipboard Share
AppVersion=3.0.2
AppPublisher=Joseph Ting
AppId={{8b417e82-2b63-487a-b582-a05ffcba14d3}
DefaultDirName={autopf}\Clipboard Share
DefaultGroupName=Clipboard Share
; Paths and Icons
OutputDir=build\inno
OutputBaseFilename=Clipboard Share Setup
Compression=lzma2
SolidCompression=yes
; Architecture
ArchitecturesAllowed=x64
ArchitecturesInstallIn64BitMode=x64

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked
Name: "startmenuicon"; Description: "Create a Start Menu shortcut"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
; The actual executable and its runtime dependencies
Source: "build\compose\binaries\main-release\app\Clipboard Share\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\Clipboard Share"; Filename: "{app}\Clipboard Share.exe"; Tasks: startmenuicon
Name: "{autodesktop}\Clipboard Share"; Filename: "{app}\Clipboard Share.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\Clipboard Share.exe"; Description: "Launch Clipboard Share"; Flags: nowait postinstall skipifsilent
