#define MyAppName "Clipboard Share"
#define MyAppVersion "3.5.3"
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

; --- Branding ----------------------------------------------------------------
; Custom setup look, all rendered from the emerald clipboard brand mark by
; installer\make-brand-assets.ps1 (re-run that script to regenerate). SetupIconFile
; is the same app.ico the packaged app uses, so the setup .exe and the installed app
; share one icon. The Wizard images are supplied at 100/150/200% DPI sizes.
SetupIconFile=src\main\resources\windows\app.ico
WizardImageFile=installer\wizard-164.bmp,installer\wizard-246.bmp,installer\wizard-328.bmp
WizardSmallImageFile=installer\wizard-small-55.bmp,installer\wizard-small-83.bmp,installer\wizard-small-110.bmp

; --- Upgrade behaviour: one install, never two copies ------------------------
; Show the Welcome page so the [Code] block can confirm the detected previous
; version on it (see InitializeWizard).
DisableWelcomePage=no
; Auto-hide the "Select Destination Location" page when a previous install of this
; AppId is detected. UsePreviousAppDir (yes by default) then reuses that folder, so an
; upgrade always overwrites in place and the user cannot browse elsewhere to create a
; second parallel copy. On a fresh install (no prior version) the directory page still
; shows as normal.
DisableDirPage=auto

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

[Code]
// ---------------------------------------------------------------------------
// Previous-version detection.
// If Clipboard Share is already installed, confirm its version and update in
// place (never a second copy). If nothing is installed, do a normal fresh install.
//
// UninstKey is "<AppId>_is1" and the GUID MUST stay in sync with [Setup] AppId
// (8A19B1A0-7A0B-48F8-95BA-153D7614B27C). A 32-bit installer on 64-bit Windows
// reads/writes this under the WOW6432Node view automatically, which is exactly
// where this installer wrote it, so the plain HKLM query below resolves correctly.
// (Only // line comments in here — brace comments would swallow the GUID's '}'.)
// ---------------------------------------------------------------------------
const
  UninstKey = 'Software\Microsoft\Windows\CurrentVersion\Uninstall\{8A19B1A0-7A0B-48F8-95BA-153D7614B27C}_is1';
  // Newline as a named constant so no line below has to START with '#': the ISPP
  // preprocessor reads a leading '#' as a directive and chokes on '#13#10'.
  NL = #13#10;

var
  gInstalledVer: String;
  gIsUpgrade: Boolean;

function GetInstalledVersion(): String;
var
  V: String;
begin
  Result := '';
  // HKLM is authoritative for the shipped all-users installer. The HKCU fallback only
  // helps a non-elevated per-user prior install whose hive matches the current user; it
  // will NOT see a per-user install from a different/original user once this setup elevates
  // for {autopf}. That path isn't shipped (the Compose per-user MSI is not the release
  // artifact), so this is an accepted gap, not full per-user coverage.
  if RegQueryStringValue(HKLM, UninstKey, 'DisplayVersion', V) then
    Result := V
  else if RegQueryStringValue(HKCU, UninstKey, 'DisplayVersion', V) then
    Result := V;
end;

// Pop the leading dotted-integer component off S and return it; S advances past the dot.
function PopVerPart(var S: String): Integer;
var
  P: Integer;
  Part: String;
begin
  P := Pos('.', S);
  if P = 0 then
  begin
    Part := S;
    S := '';
  end
  else
  begin
    Part := Copy(S, 1, P - 1);
    S := Copy(S, P + 1, Length(S));
  end;
  Result := StrToIntDef(Part, 0);
end;

// Numeric version compare: -1 if A<B, 0 if equal, 1 if A>B (so 3.5.2 < 3.5.10).
function CompareVersions(A, B: String): Integer;
var
  An, Bn: Integer;
begin
  Result := 0;
  while ((Length(A) > 0) or (Length(B) > 0)) and (Result = 0) do
  begin
    An := PopVerPart(A);
    Bn := PopVerPart(B);
    if An < Bn then Result := -1
    else if An > Bn then Result := 1;
  end;
end;

function InitializeSetup(): Boolean;
var
  Cmp: Integer;
begin
  Result := True;
  gInstalledVer := GetInstalledVersion();
  gIsUpgrade := (gInstalledVer <> '');
  if not gIsUpgrade then
    Exit;

  Cmp := CompareVersions(gInstalledVer, '{#MyAppVersion}');
  if Cmp = 0 then
  begin
    if MsgBox('Clipboard Share {#MyAppVersion} is already installed.' + NL + NL +
        'Do you want to reinstall (repair) it?', mbConfirmation, MB_YESNO) = IDNO then
      Result := False;
  end
  else if Cmp > 0 then
  begin
    if MsgBox('A newer version of Clipboard Share (' + gInstalledVer + ') is already installed.' + NL + NL +
        'Do you want to replace it with the older version {#MyAppVersion}? ' +
        'This is not recommended.', mbConfirmation, MB_YESNO or MB_DEFBUTTON2) = IDNO then
      Result := False;
  end;
  // Cmp < 0 (normal update): proceed; the Welcome page confirms the version being replaced.
end;

procedure InitializeWizard();
begin
  if gIsUpgrade then
    // Neutral wording: correct whether the installed version is older (upgrade), the
    // same (repair), or newer (user-confirmed downgrade) than {#MyAppVersion}.
    WizardForm.WelcomeLabel2.Caption :=
      'Clipboard Share ' + gInstalledVer + ' is already installed on this computer.' + NL + NL +
      'Setup will replace it with version {#MyAppVersion} in the existing location, so you will ' +
      'not end up with a second copy of the app.' + NL + NL +
      'Click Next to continue, or Cancel to exit.';
end;
