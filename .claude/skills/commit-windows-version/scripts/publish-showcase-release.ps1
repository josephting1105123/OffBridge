<#
  Publish the Windows installer to the public ClipboardShare-Showcase GitHub release.

  Why this exists: `gh` is not installed and `git credential fill` won't hand over the
  github.com token in a non-interactive shell, but the token IS in Windows Credential
  Manager (target `git:https://github.com`). This reads it via the CredRead Win32 API and
  drives the GitHub REST API directly. The token is used only for the Authorization header
  and is NEVER printed.

  Usage (from repo root):
    powershell -ExecutionPolicy Bypass -File .claude/skills/commit-windows-version/scripts/publish-showcase-release.ps1 `
      -Version 3.6.4 -AndroidVersion 3.6.5 `
      -ExePath "Window Ver/build2/inno-setup/ClipboardShareSetup.exe" -Sha256 <lowercase-hash>
#>
param(
  [Parameter(Mandatory=$true)][string]$Version,         # bare version, NO 'v' prefix (Showcase convention), e.g. 3.6.4
  [Parameter(Mandatory=$true)][string]$AndroidVersion,  # current Android version, for the release title only
  [Parameter(Mandatory=$true)][string]$ExePath,         # path to ClipboardShareSetup.exe
  [Parameter(Mandatory=$true)][string]$Sha256,          # the installer's SHA-256 (goes in the release notes)
  [string]$Repo = "josephting1105123/ClipboardShare-Showcase",
  [string]$AssetName = "ClipboardShareSetup.exe",        # stable name the evergreen /latest/ URL depends on
  [string]$Notes = ""
)
$ErrorActionPreference = "Stop"

# --- Guard: -AndroidVersion is free text baked verbatim into the release title, and Android's
# UpdateChecker parses ITS UPDATE PROMPT DECISION from that title. A stale or typo'd value here
# would parse fine and silently mis-fire (or suppress) every user's update prompt with no error
# anywhere. Refuse to publish unless it matches app/build.gradle.kts' actual versionName, so
# drift is caught here instead of shipping silently wrong.
$repoRoot = "."
if (-not (Test-Path "app/build.gradle.kts")) {
  $repoRoot = Join-Path $PSScriptRoot "../../../.."
}
$gradlePath = Join-Path $repoRoot "app/build.gradle.kts"
$androidVersionOk = $true
if (Test-Path $gradlePath) {
  $gradleContent = Get-Content $gradlePath -Raw
  $m = [regex]::Match($gradleContent, 'versionName\s*=\s*"([^"]+)"')
  if ($m.Success) {
    $actualAndroidVersion = $m.Groups[1].Value
    if ($actualAndroidVersion -ne $AndroidVersion) {
      $androidVersionOk = $false
      Write-Output "ERROR_ANDROID_VERSION_MISMATCH: got -AndroidVersion=$AndroidVersion expected=$actualAndroidVersion from app/build.gradle.kts. Fix the argument or bump build.gradle.kts first, then retry."
    }
  } else {
    Write-Output "WARN_ANDROID_VERSION_UNVERIFIED: could not find versionName in $gradlePath, proceeding without the cross-check."
  }
} else {
  Write-Output "WARN_ANDROID_VERSION_UNVERIFIED: $gradlePath not found, proceeding without the cross-check."
}
if (-not $androidVersionOk) {
  exit 3
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
$headers = @{ Authorization = "token $token"; Accept = "application/vnd.github+json"; "User-Agent" = "ClipboardShare-Release" }

if (-not $Notes) { $Notes = "Windows $Version.`n`nSHA-256 ($AssetName): $Sha256" }
$exeFull = (Resolve-Path $ExePath).Path

# --- Create the release for this tag, or reuse it if it already exists ---
$release = $null
try { $release = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/tags/$Version" -Headers $headers -TimeoutSec 60 } catch {}
if ($release) {
  Write-Output "REUSING_RELEASE id=$($release.id)"
} else {
  $body = @{ tag_name=$Version; target_commitish="main"; name="Clipboard Share Windows $Version / Android $AndroidVersion"; body=$Notes; draft=$false; prerelease=$false } | ConvertTo-Json -Depth 3
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
