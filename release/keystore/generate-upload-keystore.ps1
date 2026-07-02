<#
  generate-upload-keystore.ps1
  Creates the Android RELEASE upload keystore — the one irreversible release artifact.
  Run this ONCE. If you lose the resulting .jks (and you are NOT enrolled in Play App
  Signing), you can never ship an update under the same signature. Back it up before
  you do anything else (see KEYSTORE_BACKUP.md).

  Usage (from this folder):
      powershell -ExecutionPolicy Bypass -File .\generate-upload-keystore.ps1

  keytool will PROMPT for the store password, key password, and your name/org (the
  "distinguished name"). Use a long random password and store it in a password manager —
  it is NOT recoverable.
#>

$ErrorActionPreference = "Stop"

# keytool ships with the JDK bundled in Android Studio.
$jbr = "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"
if (-not (Test-Path $jbr)) {
    $jbr = (Get-Command keytool -ErrorAction SilentlyContinue).Source
}
if (-not $jbr -or -not (Test-Path $jbr)) {
    Write-Error "keytool not found. Point `$jbr at the keytool.exe inside Android Studio's jbr\bin."
    exit 1
}

$outDir   = "$env:USERPROFILE\.android-keystores"
$keystore = "$outDir\clipboardshare-upload.jks"
$alias    = "clipboardshare-upload"

New-Item -ItemType Directory -Force -Path $outDir | Out-Null

if (Test-Path $keystore) {
    Write-Host "A keystore already exists at:`n  $keystore" -ForegroundColor Yellow
    Write-Host "Refusing to overwrite. Delete it manually only if you are CERTAIN it is unused." -ForegroundColor Yellow
    exit 1
}

Write-Host "Generating upload keystore -> $keystore" -ForegroundColor Cyan
& $jbr -genkeypair -v `
    -keystore $keystore `
    -alias $alias `
    -keyalg RSA -keysize 4096 `
    -validity 10000 `
    -storetype PKCS12

if ($LASTEXITCODE -ne 0) { Write-Error "keytool failed."; exit 1 }

Write-Host "`nDone." -ForegroundColor Green
Write-Host "Keystore : $keystore"
Write-Host "Alias    : $alias"
Write-Host @"

NEXT (do all three NOW, while you still can):
  1. Copy release\keystore\keystore.properties.example to the REPO ROOT as keystore.properties
     and fill in storeFile=$keystore plus the alias and the two passwords you just typed.
     (keystore.properties and *.jks are already gitignored — they will NOT be committed.)
  2. BACK UP the .jks + passwords to 2+ offline locations — see KEYSTORE_BACKUP.md.
  3. At your FIRST upload to Play Console, ENROLL in Play App Signing so Google holds the
     app-signing key as your recovery path.
"@ -ForegroundColor Cyan
