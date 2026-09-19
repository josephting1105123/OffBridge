<#
  Publish the Windows installer to the public OffBridge-Showcase GitHub release.

  Why this exists: `gh` is not installed and `git credential fill` won't hand over the
  github.com token in a non-interactive shell, but the token IS in Windows Credential
  Manager (target `git:https://github.com`). This reads it via the CredRead Win32 API and
  drives the GitHub REST API directly. The token is used only for the Authorization header
  and is NEVER printed.

  Usage (from repo root):
    powershell -ExecutionPolicy Bypass -File .claude/skills/commit-windows-version/scripts/publish-showcase-release.ps1 `
      -Version 3.6.4 -AndroidVersion 3.6.5 `
      -ExePath "Window Ver/build2/inno-setup/OffBridgeSetup.exe" -Sha256 <lowercase-hash>
#>
param(
  [Parameter(Mandatory=$true)][string]$Version,         # bare version, NO 'v' prefix (Showcase convention), e.g. 3.6.4
  [Parameter(Mandatory=$true)][string]$AndroidVersion,  # current Android version, for the release title only
  [Parameter(Mandatory=$true)][string]$ExePath,         # path to OffBridgeSetup.exe
  [Parameter(Mandatory=$true)][string]$Sha256,          # the installer's SHA-256 (goes in the release notes)
  [string]$Repo = "josephting1105123/OffBridge-Showcase",
  [string]$AssetName = "OffBridgeSetup.exe",        # stable name the evergreen /latest/ URL depends on
  [string]$Notes = ""
)
$ErrorActionPreference = "Stop"

# --- Guard: -AndroidVersion is free text baked verbatim into the release title, and Android's
# UpdateChecker parses ITS UPDATE PROMPT DECISION from that title. A wrong value here parses fine
# and mis-fires every user's update prompt with no error anywhere.
#
# It must match the Android build we ACTUALLY DISTRIBUTE, which is website/js/config.js's
# androidApk.version (the same version the site advertises and the same APK carried onto this
# release by copy-apk-asset.ps1) — NOT app/build.gradle.kts' versionName. Those two diverge
# whenever source moves ahead of the last signed APK, and an earlier revision of this guard
# checked the source instead: it forced the release title to announce an Android version that
# exists only in the repo. Users on the previous build then saw a permanent "update available"
# prompt for a version no download would ever deliver. Check what ships, not what compiles.
$repoRoot = "."
if (-not (Test-Path "website/js/config.js")) {
  $repoRoot = Join-Path $PSScriptRoot "../../../.."
}
$configPath = Join-Path $repoRoot "website/js/config.js"
$gradlePath = Join-Path $repoRoot "app/build.gradle.kts"
$androidVersionOk = $true
if (Test-Path $configPath) {
  $configContent = Get-Content $configPath -Raw
  $m = [regex]::Match($configContent, 'androidApk\s*:\s*\{[^}]*?version\s*:\s*"([^"]+)"')
  if ($m.Success) {
    $shippedAndroidVersion = $m.Groups[1].Value
    if ($shippedAndroidVersion -ne $AndroidVersion) {
      $androidVersionOk = $false
      Write-Output "ERROR_ANDROID_VERSION_MISMATCH: got -AndroidVersion=$AndroidVersion but the distributed APK is $shippedAndroidVersion per website/js/config.js androidApk.version. The release title must name the version users can actually download. Publish a new signed APK and update config.js first, or pass the shipped version."
    }
  } else {
    Write-Output "WARN_ANDROID_VERSION_UNVERIFIED: could not find androidApk.version in $configPath, proceeding without the cross-check."
  }
} else {
  Write-Output "WARN_ANDROID_VERSION_UNVERIFIED: $configPath not found, proceeding without the cross-check."
}
if (-not $androidVersionOk) {
  exit 3
}
# Source ahead of the shipped APK is normal (unreleased work accumulating), so this is a note, not
# a failure. It exists so the gap is visible at publish time rather than discovered later.
if (Test-Path $gradlePath) {
  $gradleContent = Get-Content $gradlePath -Raw
  $g = [regex]::Match($gradleContent, 'versionName\s*=\s*"([^"]+)"')
  if ($g.Success -and $g.Groups[1].Value -ne $AndroidVersion) {
    Write-Output ("NOTE_ANDROID_SOURCE_AHEAD: app/build.gradle.kts versionName=" + $g.Groups[1].Value + " but shipping/advertising " + $AndroidVersion + ". Expected when Android source has unreleased changes.")
  }
}

# --- Read the github.com token from Windows Credential Manager (never printed) ---
Add-Type -TypeDefinition @"
using System; using System.Runtime.InteropServices;
public class CredManRel {
  [DllImport("advapi32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
  static extern bool CredRead(string t, int y, int f, out IntPtr c);
  [DllImport("advapi32.dll")] static extern void CredFree(IntPtr c);
  [StructLayout(LayoutKind.Sequential)] struct CRED {
    public int Flags; public int Type; public IntPtr TargetName; public IntPtr Comment;
    public long LastWritten; public int BlobSize; public IntPtr Blob; public int Persist;
    public int AttrCount; public IntPtr Attrs; public IntPtr Alias; public IntPtr User; }
  public static string Read(string target){
    IntPtr p; if(!CredRead(target,1,0,out p)) return null;
    try{ CRED c=(CRED)Marshal.PtrToStructure(p,typeof(CRED)); if(c.BlobSize==0) return "";
      byte[] b=new byte[c.BlobSize]; Marshal.Copy(c.Blob,b,0,c.BlobSize);
      bool n=false; foreach(byte x in b) if(x==0){n=true;break;}
      // GCM stores the secret as UTF-16 if it contains null bytes, else UTF-8.
      return n ? System.Text.Encoding.Unicode.GetString(b) : System.Text.Encoding.UTF8.GetString(b);
    } finally { CredFree(p); } }
}
"@
$token = [CredManRel]::Read("git:https://github.com")
if (-not $token) { Write-Output "ERROR_NO_CREDENTIAL: run any 'git push' once to cache the github.com token, then retry."; exit 2 }
$token = $token.Trim()
$headers = @{ Authorization = "token $token"; Accept = "application/vnd.github+json"; "User-Agent" = "OffBridge-Release" }

if (-not $Notes) { $Notes = "Windows $Version.`n`nSHA-256 ($AssetName): $Sha256" }
$exeFull = (Resolve-Path $ExePath).Path

# --- Create the release for this tag, or reuse it if it already exists ---
$release = $null
try { $release = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/tags/$Version" -Headers $headers -TimeoutSec 60 } catch {}
if ($release) {
  Write-Output "REUSING_RELEASE id=$($release.id)"
} else {
  $body = @{ tag_name=$Version; target_commitish="main"; name="OffBridge Windows $Version / Android $AndroidVersion"; body=$Notes; draft=$false; prerelease=$false } | ConvertTo-Json -Depth 3
  try { $release = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases" -Method Post -Headers $headers -Body $body -ContentType "application/json" -TimeoutSec 60; Write-Output "CREATED_RELEASE id=$($release.id)" }
  catch { Write-Output ("CREATE_ERROR: " + $_.Exception.Message); exit 4 }
}
Write-Output ("release_url=" + $release.html_url)

# --- Replace the asset if a same-named one is already attached, then upload ---
$old = $release.assets | Where-Object { $_.name -eq $AssetName }
if ($old) { try { Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/assets/$($old.id)" -Method Delete -Headers $headers -TimeoutSec 60 | Out-Null; Write-Output "replaced_existing_asset" } catch {} }
$bytes = [System.IO.File]::ReadAllBytes($exeFull)
$upUrl = "https://uploads.github.com/repos/$Repo/releases/$($release.id)/assets?name=$AssetName"
try {
  $asset = Invoke-RestMethod -Uri $upUrl -Method Post -Headers $headers -Body $bytes -ContentType "application/octet-stream" -TimeoutSec 600
  Write-Output ("ASSET_UPLOADED name=" + $asset.name + " size=" + $asset.size + " state=" + $asset.state)
} catch { Write-Output ("UPLOAD_ERROR: " + $_.Exception.Message); exit 5 }

# --- Mark this release 'latest' so /releases/latest/download/<asset> resolves to it ---
$patch = @{ make_latest="true" } | ConvertTo-Json
try { Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/$($release.id)" -Method Patch -Headers $headers -Body $patch -ContentType "application/json" -TimeoutSec 60 | Out-Null } catch {}

# --- Verify ---
try {
  $latest = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/latest" -Headers $headers -TimeoutSec 60
  Write-Output ("LATEST tag=" + $latest.tag_name + " assets=" + (($latest.assets | ForEach-Object { $_.name }) -join ','))
  Write-Output ("evergreen_url=https://github.com/$Repo/releases/latest/download/$AssetName")
} catch { Write-Output ("VERIFY_NOTE: " + $_.Exception.Message) }
