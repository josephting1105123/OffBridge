<#
  make-app-icon.ps1 — regenerate the Windows app icon from the ANDROID launcher source.

  Both platforms must show one mark. The single source of truth is the Android adaptive icon:
      app/src/main/res/drawable/ic_launcher_foreground_bridge.xml   (white glyph)
      app/src/main/res/values/colors.xml  ic_launcher_background_color  (#1E1E1E)
  This script reads that vector's path data directly, so the Windows icon cannot drift from the
  phone's — change the drawable and re-run, rather than hand-editing artwork twice.

  Why WPF and not GDI+ (which make-brand-assets.ps1 uses): System.Windows.Media.Geometry.Parse
  understands the SVG/Android path mini-language verbatim, so the glyph is the SAME curve data
  Android renders, not a hand-traced approximation. GDI+ has no path parser and would have meant
  redrawing the mark by hand — exactly the drift this script exists to prevent.

  Output: ../src/main/resources/windows/app.ico  (9 sizes, 32-bit PNG-compressed entries)
  Both build.gradle.kts (iconFile) and OffBridge.iss (SetupIconFile) point at that one file,
  so the app, its shortcuts and the installer all follow.

  Run from anywhere:
    powershell -ExecutionPolicy Bypass -File "Window Ver/installer/make-app-icon.ps1"
#>

Add-Type -AssemblyName PresentationCore, WindowsBase, PresentationFramework
$ErrorActionPreference = 'Stop'

$repoRoot   = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$vectorPath = Join-Path $repoRoot "app\src\main\res\drawable\ic_launcher_foreground_bridge.xml"
$colorsPath = Join-Path $repoRoot "app\src\main\res\values\colors.xml"
$outIco     = Join-Path $repoRoot "Window Ver\src\main\resources\windows\app.ico"

if (-not (Test-Path $vectorPath)) { Write-Output "ERROR_NO_VECTOR: $vectorPath"; exit 2 }

# ---- pull the glyph path + transform straight out of the Android vector ----
[xml]$vec = Get-Content $vectorPath -Raw
$ns = @{ android = "http://schemas.android.com/apk/res/android" }
$viewport = [double]$vec.vector.viewportWidth
$group = $vec.vector.group
$scale     = if ($group -and $group.scaleX)     { [double]$group.scaleX }     else { 1.0 }
$translate = if ($group -and $group.translateX) { [double]$group.translateX } else { 0.0 }
$pathNode  = if ($group) { $group.path } else { $vec.vector.path }
$pathData  = $pathNode.pathData
if (-not $pathData) { Write-Output "ERROR_NO_PATHDATA"; exit 3 }

# Background colour from the same resources the phone uses.
$bgHex = "#1E1E1E"
if (Test-Path $colorsPath) {
  $m = [regex]::Match((Get-Content $colorsPath -Raw), 'ic_launcher_background_color">(#[0-9A-Fa-f]{6,8})<')
  if ($m.Success) { $bgHex = $m.Groups[1].Value }
}
Write-Output ("source: viewport=$viewport scale=$scale translate=$translate bg=$bgHex")

$geometry = [System.Windows.Media.Geometry]::Parse($pathData)
$bgBrush  = New-Object System.Windows.Media.SolidColorBrush ([System.Windows.Media.ColorConverter]::ConvertFromString($bgHex))
$fgBrush  = [System.Windows.Media.Brushes]::White

function Render-Png([int]$size) {
  $visual = New-Object System.Windows.Media.DrawingVisual
  $dc = $visual.RenderOpen()

  # Rounded chip, matching how a launcher masks the adaptive icon and how the Windows
  # taskbar/Start tile present a square app icon. 22% radius reads as a squircle at every size.
  $r = [double]$size * 0.22
  $rect = New-Object System.Windows.Rect(0, 0, $size, $size)
  $dc.DrawRoundedRectangle($bgBrush, $null, $rect, $r, $r)

  # Android composes as: viewport(108) box, group scale+translate, then a 24dp path.
  # Reproduce that exactly, then map the 108 viewport onto the requested pixel size.
  $g = New-Object System.Windows.Media.TransformGroup
  $g.Children.Add((New-Object System.Windows.Media.ScaleTransform($scale, $scale))) | Out-Null
  $g.Children.Add((New-Object System.Windows.Media.TranslateTransform($translate, $translate))) | Out-Null
  $k = [double]$size / $viewport
  $g.Children.Add((New-Object System.Windows.Media.ScaleTransform($k, $k))) | Out-Null

  $dc.PushTransform($g)
  $dc.DrawGeometry($fgBrush, $null, $geometry)
  $dc.Pop()
  $dc.Close()

  $rtb = New-Object System.Windows.Media.Imaging.RenderTargetBitmap($size, $size, 96, 96, [System.Windows.Media.PixelFormats]::Pbgra32)
  $rtb.Render($visual)
  $enc = New-Object System.Windows.Media.Imaging.PngBitmapEncoder
  $enc.Frames.Add([System.Windows.Media.Imaging.BitmapFrame]::Create($rtb)) | Out-Null
  $ms = New-Object System.IO.MemoryStream
  $enc.Save($ms)
  return $ms.ToArray()
}

# Windows picks per context: 16/20/24/32 in lists and the tray, 40/48/64 on the desktop,
# 128/256 for large tiles and the installer's own icon.
$sizes = @(16, 20, 24, 32, 40, 48, 64, 128, 256)
$images = @{}
# Cast to [byte[]] explicitly. A PowerShell function returning a byte array unrolls it onto the
# pipeline, so the capture lands as Object[] — and BinaryWriter.Write(Object[]) does NOT bind the
# byte[] overload: it silently writes a single byte while the directory entries still record the
# true length. The result is a structurally valid .ico that every viewer reports at the right sizes
# and no tool rejects, containing no image data at all.
foreach ($s in $sizes) { $images[$s] = [byte[]](Render-Png $s) }

# ---- plain PNGs beside the .ico, for the surfaces the JDK can't read an .ico for ----
# The system tray (java.awt.TrayIcon) and the window/taskbar icon are AWT/Compose surfaces, and
# ImageIO ships no .ico reader — so they load these. Same render, same source vector, same run:
# the mark cannot drift between the exe icon and the running app the way it did when the tray drew
# its own flat green square. SystemTrayManager picks the entry nearest the tray's requested size.
$pngSizes = @(16, 24, 32, 48, 256)
foreach ($s in $pngSizes) {
  [System.IO.File]::WriteAllBytes((Join-Path (Split-Path $outIco) "app-$s.png"), $images[$s])
}

# ---- assemble the .ico container (ICONDIR + ICONDIRENTRY[] + PNG payloads) ----
$ms = New-Object System.IO.MemoryStream
$bw = New-Object System.IO.BinaryWriter($ms)
$bw.Write([uint16]0)                 # reserved
$bw.Write([uint16]1)                 # type: 1 = icon
$bw.Write([uint16]$sizes.Count)

$offset = 6 + (16 * $sizes.Count)
foreach ($s in $sizes) {
  $data = $images[$s]
  # 256 is encoded as 0 in the single width/height byte.
  $bw.Write([byte]($(if ($s -ge 256) { 0 } else { $s })))
  $bw.Write([byte]($(if ($s -ge 256) { 0 } else { $s })))
  $bw.Write([byte]0)                 # palette count (0 = truecolour)
  $bw.Write([byte]0)                 # reserved
  $bw.Write([uint16]1)               # colour planes
  $bw.Write([uint16]32)              # bits per pixel
  $bw.Write([uint32]$data.Length)
  $bw.Write([uint32]$offset)
  $offset += $data.Length
}
foreach ($s in $sizes) { $bw.Write([byte[]]$images[$s]) }
$bw.Flush()

[System.IO.File]::WriteAllBytes($outIco, $ms.ToArray())

# Guard the failure above: the payload must account for everything past the directory. Without this
# an empty-but-well-formed icon ships silently — it is only visible by looking at the taskbar.
$expected = 6 + (16 * $sizes.Count)
foreach ($s in $sizes) { $expected += $images[$s].Length }
$actual = (Get-Item $outIco).Length
if ($actual -ne $expected) { Write-Output "ERROR_ICO_TRUNCATED: wrote $actual bytes, expected $expected"; exit 4 }
Write-Output ("WROTE " + $outIco + "  bytes=" + $actual + "  sizes=" + ($sizes -join ','))
