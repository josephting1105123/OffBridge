<#
.SYNOPSIS
  Assembles the public open-source tree for Clipboard Share into a fresh directory.

.DESCRIPTION
  Copies only the paths the app needs to build, leaving behind internal notes
  (release/, .claude/, skill-observations/, CLAUDE.md, PRODUCT.md), IDE config and the
  website (which Cloudflare Pages builds from the PRIVATE repo - a second copy would be a
  footgun). Build outputs are excluded so the public tree is source only.

  Nothing is pushed. The script stops after a local `git commit`, so the tree can be read
  before it becomes public. Publishing is a separate, deliberate `git push`.

  Anything at the repo root that is in neither the include nor the exclude list is
  reported as UNCLASSIFIED and left out. That is the safety net: a new file added months
  from now surfaces here instead of being silently published.

.PARAMETER Destination
  Directory to build the public tree in. Must not exist, or must be empty.

.PARAMETER RepoSlug
  Optional "owner/repo" - fills in the README's badge placeholders.

.EXAMPLE
  .\release\seed-public-repo.ps1 -Destination C:\Users\josep\AndroidStudioProjects\ClipboardShare-OSS -RepoSlug josephting1105123/clipboard-share
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Destination,
    [string]$RepoSlug = ""
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

# --- What ships -------------------------------------------------------------------
$includeDirs  = @("app", "Window Ver", "gradle", ".github", "licenses")
$includeFiles = @(
    "gradlew", "gradlew.bat", "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
    "README.md", "ARCHITECTURE.md", "CHANGELOG.md", "LICENSE", "NOTICE", ".gitignore"
)

# --- What deliberately stays private ----------------------------------------------
$excluded = @(
    "release", ".claude", "skill-observations", "CLAUDE.md", "PRODUCT.md", ".idea",
    "website", "append_translations.py", "translation.json.txt", "Run JosephClipboard.bat",
    ".git", ".gitattributes", "local.properties", "build", "build2", ".gradle", ".kotlin"
)

# Build outputs never belong in a source tree, wherever they appear.
$excludeDirNames = @("build", "build2", ".gradle", ".idea", ".kotlin", "generated")

# --- Safety: destination must be new or empty --------------------------------------
if (Test-Path $Destination) {
    $existing = Get-ChildItem -Force $Destination -ErrorAction SilentlyContinue
    if ($existing) { throw "Destination '$Destination' exists and is not empty. Refusing to overwrite." }
} else {
    New-Item -ItemType Directory -Path $Destination | Out-Null
}
$Destination = (Resolve-Path $Destination).Path

# --- Report anything unclassified before copying anything --------------------------
$known = $includeDirs + $includeFiles + $excluded
$unclassified = Get-ChildItem -Force $repoRoot | Where-Object { $known -notcontains $_.Name } | Select-Object -ExpandProperty Name
if ($unclassified) {
    Write-Host ""
    Write-Host "UNCLASSIFIED root entries - NOT copied. Decide where each belongs, then" -ForegroundColor Yellow
    Write-Host "add it to `$includeDirs/`$includeFiles or `$excluded in this script:" -ForegroundColor Yellow
    $unclassified | ForEach-Object { Write-Host "    $_" -ForegroundColor Yellow }
    Write-Host ""
}

# --- Copy ---------------------------------------------------------------------------
$xd = @(); foreach ($d in $excludeDirNames) { $xd += "/XD"; $xd += $d }

foreach ($dir in $includeDirs) {
    if (-not (Test-Path $dir)) { Write-Host "  skip (absent): $dir"; continue }
    $target = Join-Path $Destination $dir
    # robocopy exit codes 0-7 are success; 8+ are real failures.
    & robocopy $dir $target /E /NFL /NDL /NJH /NJS /NP @xd | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "robocopy failed for '$dir' (exit $LASTEXITCODE)" }
    $global:LASTEXITCODE = 0
    Write-Host "  copied dir : $dir"
}

foreach ($file in $includeFiles) {
    if (-not (Test-Path $file)) { Write-Host "  MISSING    : $file" -ForegroundColor Red; continue }
    Copy-Item $file (Join-Path $Destination $file)
    Write-Host "  copied file: $file"
}

# --- Fill the README badge placeholders --------------------------------------------
if ($RepoSlug) {
    $readme = Join-Path $Destination "README.md"
    if (Test-Path $readme) {
        $text = Get-Content $readme -Raw
        $text = $text.Replace("OWNER/REPO", $RepoSlug)
        $text = $text.Replace("license-TBD-lightgrey", "license-Apache_2.0-blue")
        Set-Content -Path $readme -Value $text -Encoding utf8
        Write-Host "  README     : badges pointed at $RepoSlug"
    }
}

# --- Verify no obvious secret slipped through ---------------------------------------
$suspects = Get-ChildItem -Recurse -Force -File $Destination |
    Where-Object { $_.Name -match '(?i)\.(jks|keystore|p12|pfx|pem)$|keystore\.properties$|settings\.local\.json$' }
if ($suspects) {
    Write-Host ""
    Write-Host "STOP - key material found in the staged tree:" -ForegroundColor Red
    $suspects | ForEach-Object { Write-Host "    $($_.FullName)" -ForegroundColor Red }
    throw "Refusing to commit a tree containing key material."
}

# --- One clean commit, no history, no push -------------------------------------------
Push-Location $Destination
try {
    & git init -q -b main
    & git add -A
    & git -c user.name="Joseph Ting" -c user.email="josephting1105123@gmail.com" commit -q -m "Initial public release

Clipboard Share - off-grid file transfer and clipboard sync between Android and
Windows. Published under Apache-2.0; see LICENSE and NOTICE."
    $count = (& git ls-files).Count
    Write-Host ""
    Write-Host "Staged $count files in $Destination" -ForegroundColor Green
} finally { Pop-Location }

Write-Host ""
Write-Host "NOT pushed. Review the tree, then:" -ForegroundColor Cyan
Write-Host "    cd `"$Destination`""
Write-Host "    git remote add origin https://github.com/<owner>/<repo>.git"
Write-Host "    git push -u origin main"
