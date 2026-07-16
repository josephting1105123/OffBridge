<#
  Copy ClipboardShare-android.apk from the previous Showcase release to the newly published one,
  so the evergreen /releases/latest/download/ClipboardShare-android.apk link (the website's
  Direct APK card) keeps working after the new release becomes latest. The publish script only
  uploads the Windows installer — run this right after it, every Windows release.
  Token read from Windows Credential Manager, same as the publish script.

  Usage (from repo root):
    powershell -ExecutionPolicy Bypass -File .claude/skills/commit-windows-version/scripts/copy-apk-asset.ps1 `
      -FromTag <previous-windows-version> -ToReleaseId <id printed by publish-showcase-release.ps1>
#>
param(
  [Parameter(Mandatory=$true)][string]$FromTag,       # previous release tag still carrying the APK, e.g. 3.7.2
  [Parameter(Mandatory=$true)][int]$ToReleaseId,      # numeric id from CREATED_RELEASE/REUSING_RELEASE output
  [string]$Repo = "josephting1105123/ClipboardShare-Showcase",
  [string]$AssetName = "ClipboardShare-android.apk"
)
$ErrorActionPreference = "Stop"

Add-Type -TypeDefinition @"
using System; using System.Runtime.InteropServices;
public class CredManCopy {
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
      return n ? System.Text.Encoding.Unicode.GetString(b) : System.Text.Encoding.UTF8.GetString(b);
    } finally { CredFree(p); } }
}
"@
$token = [CredManCopy]::Read("git:https://github.com")
if (-not $token) { Write-Output "ERROR_NO_CREDENTIAL"; exit 2 }
$token = $token.Trim()
$headers = @{ Authorization = "token $token"; Accept = "application/vnd.github+json"; "User-Agent" = "ClipboardShare-Release" }

[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

# Download the APK from the source tag (public asset, but authed is fine too).
$tmp = Join-Path $env:TEMP $AssetName
$dlUrl = "https://github.com/$Repo/releases/download/$FromTag/$AssetName"
Invoke-WebRequest -Uri $dlUrl -OutFile $tmp -TimeoutSec 600 -UseBasicParsing
$sha = (Get-FileHash $tmp -Algorithm SHA256).Hash.ToLower()
Write-Output ("DOWNLOADED size=" + (Get-Item $tmp).Length + " sha256=" + $sha)

# Replace any same-named asset on the target release, then upload.
$rel = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/$ToReleaseId" -Headers $headers -TimeoutSec 60
$old = $rel.assets | Where-Object { $_.name -eq $AssetName }
if ($old) { Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/assets/$($old.id)" -Method Delete -Headers $headers -TimeoutSec 60 | Out-Null; Write-Output "replaced_existing_asset" }
$bytes = [System.IO.File]::ReadAllBytes($tmp)
$upUrl = "https://uploads.github.com/repos/$Repo/releases/$ToReleaseId/assets?name=$AssetName"
$asset = Invoke-RestMethod -Uri $upUrl -Method Post -Headers $headers -Body $bytes -ContentType "application/vnd.android.package-archive" -TimeoutSec 600
Write-Output ("ASSET_UPLOADED name=" + $asset.name + " size=" + $asset.size + " state=" + $asset.state)
Remove-Item $tmp -Force

# Verify the latest release now lists both assets.
$latest = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/latest" -Headers $headers -TimeoutSec 60
Write-Output ("LATEST tag=" + $latest.tag_name + " assets=" + (($latest.assets | ForEach-Object { $_.name }) -join ','))
