#define MyAppName "OffBridge"
#define MyAppVersion "3.7.8"
#define MyAppPublisher "EntropicSoftwareLab"
#define MyAppExeName "OffBridge.exe"

[Setup]
; AppId uniquely identifies this application. Do not change this in future versions so upgrades work seamlessly.
AppId={{8A19B1A0-7A0B-48F8-95BA-153D7614B27C}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\{#MyAppName}
; Elevate to admin. This is also Inno's default when PrivilegesRequired is unset, but make it explicit:
; the all-users install location ({autopf}) AND the [Run] netsh firewall rules both require the elevated
; token, so a per-user/lowest install would silently drop the firewall rules the phone->PC path needs.
PrivilegesRequired=admin
DisableProgramGroupPage=yes
; Warn (Inno default) when the user browses to an existing non-empty folder, so an accidental
; install target isn't wiped silently by the file overwrite below. Do NOT set DirExistsWarning=no.
CloseApplications=yes
RestartApplications=no
; Single-instance mutex the app creates at startup (Kernel32.CreateMutex — see os\SingleInstance.kt).
; Lets Setup detect a running instance — even one only minimized to the system tray — and tell the user
; to close it BEFORE files are copied, so an upgrade can't fail renaming an in-use runtime file
; (runtime\bin\ucrtbase.dll -> "MoveFile failed; code 5. Access is denied."). The name MUST match the
; app's mutex exactly. NOTE: this only catches instances of a build that already creates the mutex —
; upgrading FROM a pre-mutex version still relies on CloseApplications + the restartreplace fallback on
; [Files] + the user quitting from the tray.
AppMutex=ClipboardShareInstanceMutex
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

[Messages]
; Make the AppMutex "please close the app" prompt explicit that OffBridge hides in the tray —
; users routinely think it's closed when it's only minimized there, which is what leaves the bundled
; runtime DLL locked during an upgrade (the code-5 rename failure this whole guard exists to prevent).
SetupAppRunningError=OffBridge is still running.%n%nPlease close it completely — including the icon in the system tray (right-click it and choose Quit) — then click OK to continue, or Cancel to exit Setup.

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

; [InstallDelete] intentionally omitted: the [Files] entry below uses the `ignoreversion` flag, which
; already overwrites every app file on upgrade. A blanket `Type: filesandordirs; Name: "{app}\*"` would
; recursively wipe whatever lives under the chosen target dir before copying — dangerous if the user
; browses to an existing non-empty folder. If a future version orphans files, add a targeted delete for
; those specific paths rather than restoring the blanket wildcard.

[Files]
; Copy everything from the AppImage directory to {app}
; restartreplace: last-resort safety net. If a file is still in use despite AppMutex + CloseApplications
; (e.g. an AV scanner holding it, or upgrading from a pre-mutex build the user didn't quit), schedule
; its replacement on next reboot (MoveFileEx delay-until-reboot) instead of hard-failing with the
; Retry/Skip/Cancel "code 5" box. No-op per file unless that file is actually locked.
Source: "build2\compose\binaries\main-release\app\OffBridge\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs restartreplace

; [Icons] intentionally omitted. Shortcuts are created in [Code] (CreateAppShortcuts, called on
; ssPostInstall) wrapped in try/except so a Controlled Folder Access / AV block on the Start Menu
; folder ("IPersistFile::Save failed; code 0x80070005. Access is denied.") can't abort the whole
; install: an [Icons] failure is fatal to Setup, whereas a [Code] failure we can catch and continue —
; the app files and firewall rules are already in place by then. The [Code] handler honors the
; desktopicon task, and CurUninstallStepChanged deletes both .lnk files on uninstall (Inno does not
; track shortcuts created via CreateShellLink the way it auto-removes [Icons] entries).

[Run]
; Allow inbound LAN traffic to ALL of OffBridge's listening ports through Windows Defender
; Firewall. Windows blocks unsolicited inbound by default on every interface, and the app never opens
; these itself — so without these rules any PEER that DIALS this PC (phone-initiated pairing, clipboard
; push, or a file OFFER) is silently dropped and times out, while this PC dialing the phone still works
; (outbound is allowed by default). That one-sided block is exactly the "phone can't connect to the
; laptop, but the laptop connects to the phone" symptom. The daemons bind 0.0.0.0 on: TCP 65432
; (clipboard), 65433 (file transfer + session control), 65435 (pairing); UDP 50001 (discovery).
; Delete-then-add keeps exactly one rule across reinstalls; the legacy single-65433 rule name is also
; deleted so an upgrade doesn't leave a stale duplicate behind.
; profile=any is REQUIRED (not a mistake): the primary off-grid path is the Wi-Fi Direct autonomous GO
; (HotspotController serves 192.168.49.1:65433). Windows classifies that fresh/unidentified adapter as
; a PUBLIC network by default, so narrowing to profile=private would leave the off-grid hotspot inbound
; blocked and break the core use case. Runs elevated. Keep profile=any until the AP interface is
; provably Private-classified.
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""OffBridge (TCP 65433)"""; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""OffBridge (LAN TCP)"""; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""OffBridge (LAN TCP)"" dir=in action=allow protocol=TCP localport=65432,65433,65435 profile=any"; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""OffBridge (LAN UDP)"""; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""OffBridge (LAN UDP)"" dir=in action=allow protocol=UDP localport=50001 profile=any"; Flags: runhidden
; Auto-launch at the end
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#StringChange(MyAppName, '&', '&&')}}"; Flags: nowait postinstall skipifsilent runasoriginaluser

[UninstallRun]
; Remove the firewall rules when the app is uninstalled (including the legacy single-65433 rule name).
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""OffBridge (TCP 65433)"""; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""OffBridge (LAN TCP)"""; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""OffBridge (LAN UDP)"""; Flags: runhidden

[Code]
// ---------------------------------------------------------------------------
// Previous-version detection.
// If OffBridge is already installed, confirm its version and update in
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
  // App design language (charcoal + emerald). TColor is $00BBGGRR; greys are symmetric,
  // and emerald #22C55E -> $005EC522. Used to repaint the whole wizard dark (ApplyDarkTheme).
  cBg = $141414;          // window / page background (#141414)
  cSurface = $2A2A2A;     // inputs: dir box, task list, ready memo (#2A2A2A)
  cTextLight = $F5F5F5;   // primary text (#F5F5F5)
  cAccent = $5EC522;      // emerald fill #22C55E (accent line)
  cAccentText = $88DE4A;  // emerald text #4ADE88 (WCAG-safe on dark — page headings)
  BM_CLICK = $00F5;       // Win32 button-click message (drive native nav from our overlays)

var
  gInstalledVer: String;
  gIsUpgrade: Boolean;
  gAccentLine: TPanel;   // emerald underline under the interior-page header (brand signature)
  gBtnNext, gBtnBack, gBtnCancel: TPanel;  // emerald overlays for the native nav buttons
  gTimerID: Longint;     // re-theme timer id (killed in DeinitializeSetup)

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
    if MsgBox('OffBridge {#MyAppVersion} is already installed.' + NL + NL +
        'Do you want to reinstall (repair) it?', mbConfirmation, MB_YESNO) = IDNO then
      Result := False;
  end
  else if Cmp > 0 then
  begin
    if MsgBox('A newer version of OffBridge (' + gInstalledVer + ') is already installed.' + NL + NL +
        'Do you want to replace it with the older version {#MyAppVersion}? ' +
        'This is not recommended.', mbConfirmation, MB_YESNO or MB_DEFBUTTON2) = IDNO then
      Result := False;
  end;
  // Cmp < 0 (normal update): proceed; the Welcome page confirms the version being replaced.
end;

// Repaint every wizard control to the app's charcoal + emerald language. Inno's modern
// wizard is otherwise the OS light style (white pages, black text) — only the banner and
// icon were branded. Walk the whole control tree so every page is covered.
procedure DarkenTree(Parent: TWinControl);
var
  i: Integer;
  C: TControl;
begin
  for i := 0 to Parent.ControlCount - 1 do
  begin
    C := Parent.Controls[i];
    if C is TNewNotebookPage then TNewNotebookPage(C).Color := cBg
    else if C is TPanel then
    begin
      // Never repaint our own emerald panels (buttons + accent line), or the periodic
      // re-theme would toggle them dark->emerald every tick and flicker.
      if (C <> gBtnNext) and (C <> gBtnBack) and (C <> gBtnCancel) and (C <> gAccentLine) then
        TPanel(C).Color := cBg;
    end
    else if C is TNewStaticText then TNewStaticText(C).Font.Color := cTextLight
    else if C is TNewCheckBox then TNewCheckBox(C).Font.Color := cTextLight
    else if C is TNewRadioButton then TNewRadioButton(C).Font.Color := cTextLight
    else if C is TLabel then TLabel(C).Font.Color := cTextLight
    else if C is TNewEdit then
    begin
      TNewEdit(C).Color := cSurface;
      TNewEdit(C).Font.Color := cTextLight;
    end
    else if C is TNewMemo then
    begin
      TNewMemo(C).Color := cSurface;
      TNewMemo(C).Font.Color := cTextLight;
    end
    else if C is TNewCheckListBox then
    begin
      TNewCheckListBox(C).Color := cSurface;
      TNewCheckListBox(C).Font.Color := cTextLight;
    end
    else if C is TRichEditViewer then
    begin
      TRichEditViewer(C).Color := cSurface;
      TRichEditViewer(C).Font.Color := cTextLight;
    end;

    if C is TWinControl then
      DarkenTree(TWinControl(C));
  end;
end;

// --- Custom emerald buttons over Inno's native (uncolorable) ones ------------
// Inno's Next/Back/Cancel are native Windows buttons with no Color property. We cover
// each with an emerald/dark TPanel that mirrors the native button's caption, bounds,
// visibility and enabled-state on every page, and forwards clicks to the real button
// underneath (kept under ours, so Enter/Esc keyboard nav still works). The installer is
// English-only (no [Languages]) so mirrored captions need no per-locale handling.
// Inno's Pascal Script can't invoke a native button's OnClick directly, so drive the real
// button by posting it the standard Win32 BM_CLICK message (runs its full handler, including
// the Cancel confirmation prompt and page navigation).
function SendMessage(hWnd: Longint; Msg: Longint; wParam: Longint; lParam: Longint): Longint;
  external 'SendMessageW@user32.dll stdcall';
function SetTimer(hWnd: Longint; nIDEvent: Longint; uElapse: Longint; lpTimerFunc: Longint): Longint;
  external 'SetTimer@user32.dll stdcall';
function KillTimer(hWnd: Longint; nIDEvent: Longint): Longint;
  external 'KillTimer@user32.dll stdcall';

procedure FakeNextClick(Sender: TObject);
begin
  if WizardForm.NextButton.Enabled then
    SendMessage(WizardForm.NextButton.Handle, BM_CLICK, 0, 0);
end;

procedure FakeBackClick(Sender: TObject);
begin
  if WizardForm.BackButton.Enabled then
    SendMessage(WizardForm.BackButton.Handle, BM_CLICK, 0, 0);
end;

procedure FakeCancelClick(Sender: TObject);
begin
  if WizardForm.CancelButton.Enabled then
    SendMessage(WizardForm.CancelButton.Handle, BM_CLICK, 0, 0);
end;

function NewFakeButton(Native: TNewButton): TPanel;
var
  P: TPanel;
begin
  P := TPanel.Create(WizardForm);
  P.Parent := Native.Parent;
  P.BevelOuter := bvNone;
  // Under Windows themes a TPanel defaults to ParentBackground=True, which makes it IGNORE
  // its Color and paint the parent's background — so the emerald fill never shows. Turn it
  // off so Color actually paints.
  P.ParentBackground := False;
  P.Font.Style := [fsBold];
  P.SetBounds(Native.Left, Native.Top, Native.Width, Native.Height);
  P.BringToFront;
  Result := P;
end;

// Mirror one native button onto its emerald overlay. Primary = the emerald fill
// (Next/Install/Finish); secondary = the dark tonal (Back/Cancel).
procedure StyleFakeButton(P: TPanel; Native: TNewButton; Primary: Boolean);
begin
  if P = nil then
    Exit;
  P.Visible := Native.Visible;
  P.Caption := Native.Caption;
  P.SetBounds(Native.Left, Native.Top, Native.Width, Native.Height);
  if not Native.Enabled then
  begin
    P.Color := cSurface;
    P.Font.Color := $707070;
  end
  else if Primary then
  begin
    P.Color := cAccent;
    P.Font.Color := clBlack;
  end
  else
  begin
    P.Color := cSurface;
    P.Font.Color := cTextLight;
  end;
end;

procedure SyncFakeButtons();
begin
  StyleFakeButton(gBtnNext, WizardForm.NextButton, True);
  StyleFakeButton(gBtnBack, WizardForm.BackButton, False);
  StyleFakeButton(gBtnCancel, WizardForm.CancelButton, False);
end;

procedure ApplyDarkTheme();
begin
  WizardForm.Color := cBg;
  WizardForm.MainPanel.Color := cBg;
  DarkenTree(WizardForm);
  // Emerald page heading (re-set after the walk paints it the light default).
  WizardForm.PageNameLabel.Font.Color := cAccentText;
  // Emerald underline: DarkenTree just painted it cBg, so re-color it and re-pin it to the
  // bottom of the header as pages/DPI settle.
  if gAccentLine <> nil then
  begin
    gAccentLine.Color := cAccent;
    gAccentLine.Width := WizardForm.MainPanel.Width;
    gAccentLine.Top := WizardForm.MainPanel.Height - gAccentLine.Height;
  end;
  // Emerald buttons: re-apply after the walk painted the overlays dark.
  SyncFakeButtons();
end;

// Periodic re-theme (WM_TIMER callback) so controls Inno creates AFTER a page is shown get
// themed too — chiefly the "close applications" radios on the Preparing page.
procedure ThemeTick(H: Longint; Msg: Longint; IDEvent: Longint; Time: Longint);
begin
  // Guard against a tick arriving while the wizard is being torn down.
  if WizardForm <> nil then
    ApplyDarkTheme();
end;

procedure InitializeWizard();
begin
  if gIsUpgrade then
    // Neutral wording: correct whether the installed version is older (upgrade), the
    // same (repair), or newer (user-confirmed downgrade) than {#MyAppVersion}.
    WizardForm.WelcomeLabel2.Caption :=
      'OffBridge ' + gInstalledVer + ' is already installed on this computer.' + NL + NL +
      'Setup will replace it with version {#MyAppVersion} in the existing location, so you will ' +
      'not end up with a second copy of the app.' + NL + NL +
      'Click Next to continue, or Cancel to exit.';

  // The website/banner signature: a 2px emerald rule under the interior-page header.
  gAccentLine := TPanel.Create(WizardForm);
  gAccentLine.Parent := WizardForm.MainPanel;
  gAccentLine.BevelOuter := bvNone;
  gAccentLine.ParentBackground := False;   // paint our emerald, not the themed parent bg
  gAccentLine.Color := cAccent;
  gAccentLine.Height := ScaleY(2);
  gAccentLine.Left := 0;

  // Emerald overlays for the native nav buttons (styled per-page by SyncFakeButtons).
  gBtnNext := NewFakeButton(WizardForm.NextButton);
  gBtnNext.OnClick := @FakeNextClick;
  gBtnBack := NewFakeButton(WizardForm.BackButton);
  gBtnBack.OnClick := @FakeBackClick;
  gBtnCancel := NewFakeButton(WizardForm.CancelButton);
  gBtnCancel.OnClick := @FakeCancelClick;

  // ~5x/second re-theme to catch late-created controls (see ThemeTick). Killed in DeinitializeSetup.
  gTimerID := SetTimer(0, 0, 200, CreateCallback(@ThemeTick));

  ApplyDarkTheme();
end;

// Re-assert the dark colors + emerald overlays when a page becomes current: Inno populates
// or resets some controls (and the native buttons' caption/enabled) as pages are shown.
procedure CurPageChanged(CurPageID: Integer);
begin
  ApplyDarkTheme();
end;

// Create the Start Menu (and optional desktop) shortcut AFTER files are installed, but tolerate a
// failure instead of aborting Setup. Controlled Folder Access ("Ransomware protection") and some AV
// products deny writes to the Start Menu Programs folder even for an elevated installer, surfacing as
// "IPersistFile::Save failed; code 0x80070005. Access is denied." As an [Icons] entry that error is
// fatal; here we catch it and continue, because the app files and firewall rules are already installed.
procedure CreateAppShortcuts();
var
  LinkPath: String;
begin
  try
    LinkPath := CreateShellLink(
      ExpandConstant('{autoprograms}\{#MyAppName}.lnk'),
      '{#MyAppName}',
      ExpandConstant('{app}\{#MyAppExeName}'),
      '',
      ExpandConstant('{app}'),
      '',
      0,
      SW_SHOWNORMAL);
  except
    Log('Start Menu shortcut could not be created (likely Controlled Folder Access / AV): ' + GetExceptionMessage);
    // Don't pop a modal during a /SILENT or /VERYSILENT run — it would hang an unattended install.
    if not WizardSilent then
      MsgBox('OffBridge was installed successfully, but its Start Menu shortcut could not be created.' + NL + NL +
        'This is usually security software (Controlled Folder Access / "Ransomware protection", or antivirus) '
        + 'blocking writes to the Start Menu folder.' + NL + NL +
        'You can still launch the app from its install folder, or allow this installer through Controlled '
        + 'Folder Access and run it again to add the shortcut.', mbInformation, MB_OK);
  end;

  if WizardIsTaskSelected('desktopicon') then
  begin
    try
      LinkPath := CreateShellLink(
        ExpandConstant('{autodesktop}\{#MyAppName}.lnk'),
        '{#MyAppName}',
        ExpandConstant('{app}\{#MyAppExeName}'),
        '',
        ExpandConstant('{app}'),
        '',
        0,
        SW_SHOWNORMAL);
    except
      // Desktop icon is optional — never interrupt the install for it.
      Log('Desktop shortcut could not be created: ' + GetExceptionMessage);
    end;
  end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssPostInstall then
    CreateAppShortcuts();
end;

// Remove the [Code]-created shortcuts on uninstall. Inno auto-removes [Icons] entries, but shortcuts
// made via CreateShellLink are not tracked, so delete them explicitly (mirrors the firewall-rule
// cleanup in [UninstallRun]). Missing files are a no-op.
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usUninstall then
  begin
    DeleteFile(ExpandConstant('{autoprograms}\{#MyAppName}.lnk'));
    DeleteFile(ExpandConstant('{autodesktop}\{#MyAppName}.lnk'));
  end;
end;

// Stop the re-theme timer before Setup terminates so it can't fire against a torn-down wizard.
procedure DeinitializeSetup();
begin
  if gTimerID <> 0 then
  begin
    KillTimer(0, gTimerID);
    gTimerID := 0;
  end;
end;
