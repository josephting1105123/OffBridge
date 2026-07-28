<#
  make-brand-assets.ps1 — regenerate the OffBridge installer artwork.

  Renders the brand mark (emerald clipboard + check, from website/assets/favicon.svg)
  into the assets the Inno installer and the packaged app reference:
    - ../src/main/resources/windows/app.ico   (multi-size app + installer icon)
    - wizard-164/246/328.bmp                   (WizardImageFile — left banner, 100/150/200% DPI)
    - wizard-small-55/83/110.bmp               (WizardSmallImageFile — interior corner logo)

  Pure GDI+ (System.Drawing) — no external tools/deps. Run from anywhere:
    powershell -ExecutionPolicy Bypass -File "Window Ver/installer/make-brand-assets.ps1"

  Brand palette: bg #141414, chip #1F1F1F, mark #4ADE88 (on dark) / #22C55E (on white).
#>

Add-Type -AssemblyName System.Drawing
$ErrorActionPreference = 'Stop'

# ---- geometry helpers (force the Single overloads; PS would otherwise pick int) ----
function AddArcF($p, $x, $y, $w, $h, $sa, $sw) {
    $p.AddArc([single]$x, [single]$y, [single]$w, [single]$h, [single]$sa, [single]$sw)
}
function AddLineF($p, $x1, $y1, $x2, $y2) {
    $p.AddLine([single]$x1, [single]$y1, [single]$x2, [single]$y2)
}
function New-RoundRectPath($x, $y, $w, $h, $r) {
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = 2.0 * $r
    if ($d -le 0) {
        $p.AddRectangle((New-Object System.Drawing.RectangleF([single]$x, [single]$y, [single]$w, [single]$h)))
        return $p
    }
    AddArcF $p  $x            $y            $d $d 180 90   # top-left
    AddArcF $p ($x + $w - $d)  $y            $d $d 270 90   # top-right
    AddArcF $p ($x + $w - $d) ($y + $h - $d) $d $d   0 90   # bottom-right
    AddArcF $p  $x           ($y + $h - $d) $d $d  90 90   # bottom-left
    $p.CloseFigure()
    return $p
}

function Make-Font([single]$px) {
    # The Font ctor SUBSTITUTES silently for a missing family (never throws), so a try/catch
    # fallback would be dead code. Probe the installed families instead: prefer the crisp
    # "Segoe UI Semibold" face, else fall back to bold "Segoe UI" (present on all Win10/11).
    $hasSemibold = $false
    foreach ($fam in [System.Drawing.FontFamily]::Families) {
        if ($fam.Name -eq 'Segoe UI Semibold') { $hasSemibold = $true; break }
    }
    if ($hasSemibold) {
        return New-Object System.Drawing.Font("Segoe UI Semibold", $px, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
    }
    return New-Object System.Drawing.Font("Segoe UI", $px, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
}

# ---- draw the clipboard+check mark into an S-sized box at (ox,oy), scaling the 64px artwork ----
function Draw-Mark($g, $ox, $oy, $S, $color) {
    $f = $S / 64.0
    $w = 3.5 * $f; if ($w -lt 1) { $w = 1 }
    $pen = New-Object System.Drawing.Pen($color, [single]$w)
    $pen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
    $pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $pen.EndCap   = [System.Drawing.Drawing2D.LineCap]::Round

    # clip tab: rounded rect 22,12,20,9 r2.5
    $tab = New-RoundRectPath ($ox + 22 * $f) ($oy + 12 * $f) (20 * $f) (9 * $f) (2.5 * $f)
    $g.DrawPath($pen, $tab); $tab.Dispose()

    # board body: rounded rect 13,16 .. 51,52 (r4) with the top-middle segment left open for the tab
    $b = New-Object System.Drawing.Drawing2D.GraphicsPath
    $b.StartFigure()
    AddLineF $b ($ox + 42 * $f) ($oy + 16 * $f) ($ox + 47 * $f) ($oy + 16 * $f)
    AddArcF  $b ($ox + 43 * $f) ($oy + 16 * $f) (8 * $f) (8 * $f) -90 90   # TR corner
    AddArcF  $b ($ox + 43 * $f) ($oy + 44 * $f) (8 * $f) (8 * $f)   0 90   # BR corner (auto line = right edge)
    AddArcF  $b ($ox + 13 * $f) ($oy + 44 * $f) (8 * $f) (8 * $f)  90 90   # BL corner (auto line = bottom edge)
    AddArcF  $b ($ox + 13 * $f) ($oy + 16 * $f) (8 * $f) (8 * $f) 180 90   # TL corner (auto line = left edge)
    AddLineF $b ($ox + 17 * $f) ($oy + 16 * $f) ($ox + 22 * $f) ($oy + 16 * $f)
    $g.DrawPath($pen, $b); $b.Dispose()

    # checkmark: 24,36 -> 29,41 -> 40,29
    $chk = New-Object System.Drawing.Drawing2D.GraphicsPath
    $chk.StartFigure()
    AddLineF $chk ($ox + 24 * $f) ($oy + 36 * $f) ($ox + 29 * $f) ($oy + 41 * $f)
    AddLineF $chk ($ox + 29 * $f) ($oy + 41 * $f) ($ox + 40 * $f) ($oy + 29 * $f)
    $g.DrawPath($pen, $chk); $chk.Dispose()

    $pen.Dispose()
}

# ---- one .ico frame -> PNG bytes (transparent corners, charcoal badge, emerald mark) ----
function Get-IconPng($S) {
    $bmp = New-Object System.Drawing.Bitmap($S, $S, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'; $g.PixelOffsetMode = 'HighQuality'
    $g.Clear([System.Drawing.Color]::Transparent)
    $bg = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 0x14, 0x14, 0x14))
    $bgPath = New-RoundRectPath 0 0 $S $S (14.0 * $S / 64.0)
    $g.FillPath($bg, $bgPath); $bg.Dispose(); $bgPath.Dispose()
    Draw-Mark $g 0 0 $S ([System.Drawing.Color]::FromArgb(255, 0x4A, 0xDE, 0x88))
    $g.Dispose()
    $ms = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    $bytes = $ms.ToArray(); $ms.Dispose()
    return , $bytes
}

function Write-Ico($frames, $outPath) {
    $fs = [System.IO.File]::Create($outPath)
    $bw = New-Object System.IO.BinaryWriter($fs)
    $bw.Write([uint16]0); $bw.Write([uint16]1); $bw.Write([uint16]$frames.Count)   # ICONDIR
    $offset = 6 + 16 * $frames.Count
    foreach ($fr in $frames) {
        $dim = $fr.Size; if ($dim -ge 256) { $dim = 0 }
        $bw.Write([byte]$dim); $bw.Write([byte]$dim); $bw.Write([byte]0); $bw.Write([byte]0)
        $bw.Write([uint16]1); $bw.Write([uint16]32)
        $bw.Write([uint32]$fr.Bytes.Length); $bw.Write([uint32]$offset)
        $offset += $fr.Bytes.Length
    }
    foreach ($fr in $frames) { $bw.Write($fr.Bytes) }
    $bw.Flush(); $bw.Dispose(); $fs.Dispose()
}

# ---- left banner (Welcome/Finished pages): charcoal gradient + chip + wordmark ----
function Draw-Banner($W, $H, $path) {
    $bmp = New-Object System.Drawing.Bitmap($W, $H, [System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'; $g.PixelOffsetMode = 'HighQuality'
    $g.InterpolationMode = 'HighQualityBicubic'; $g.TextRenderingHint = 'AntiAlias'
    $scale = $W / 164.0

    $rect = New-Object System.Drawing.Rectangle(0, 0, $W, $H)
    $grad = New-Object System.Drawing.Drawing2D.LinearGradientBrush($rect, `
        [System.Drawing.Color]::FromArgb(255, 0x14, 0x14, 0x14), `
        [System.Drawing.Color]::FromArgb(255, 0x0C, 0x0C, 0x0C), [single]90)
    $g.FillRectangle($grad, $rect); $grad.Dispose()

    $chip = [int][math]::Round(94 * $scale)
    $chipX = [int](($W - $chip) / 2)
    $chipY = [int][math]::Round(48 * $scale)
    $chipBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 0x1F, 0x1F, 0x1F))
    $chipPath = New-RoundRectPath $chipX $chipY $chip $chip (22 * $scale)
    $g.FillPath($chipBrush, $chipPath)
    $hair = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(38, 255, 255, 255), [single](1 * $scale))
    $g.DrawPath($hair, $chipPath)
    $chipBrush.Dispose(); $hair.Dispose(); $chipPath.Dispose()

    $mSize = [int][math]::Round($chip * 0.66)
    $mX = $chipX + [int](($chip - $mSize) / 2)
    $mY = $chipY + [int](($chip - $mSize) / 2)
    Draw-Mark $g $mX $mY $mSize ([System.Drawing.Color]::FromArgb(255, 0x4A, 0xDE, 0x88))

    $font = Make-Font ([single](17 * $scale))
    $tb = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 0xF5, 0xF5, 0xF5))
    $fmt = New-Object System.Drawing.StringFormat
    $fmt.Alignment = [System.Drawing.StringAlignment]::Center
    $fmt.LineAlignment = [System.Drawing.StringAlignment]::Near
    $wy = $chipY + $chip + [int][math]::Round(22 * $scale)
    $wrect = New-Object System.Drawing.RectangleF(0, $wy, $W, [single](40 * $scale))
    $g.DrawString("OffBridge", $font, $tb, $wrect, $fmt)

    $uw = [int][math]::Round(28 * $scale); $uh = [int][math]::Round(3 * $scale); if ($uh -lt 2) { $uh = 2 }
    $ux = [int](($W - $uw) / 2); $uy = $wy + [int][math]::Round(30 * $scale)
    $ub = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 0x22, 0xC5, 0x5E))
    $up = New-RoundRectPath $ux $uy $uw $uh ($uh / 2.0)
    $g.FillPath($ub, $up)
    $font.Dispose(); $tb.Dispose(); $fmt.Dispose(); $ub.Dispose(); $up.Dispose()

    $g.Dispose()
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Bmp)
    $bmp.Dispose()
}

# ---- interior corner logo: bright emerald mark on charcoal (#141414), so it blends into the
#      dark wizard header repainted by the installer's [Code]. Background MUST match cBg there. ----
function Draw-Small($W, $H, $path) {
    $bmp = New-Object System.Drawing.Bitmap($W, $H, [System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'; $g.PixelOffsetMode = 'HighQuality'
    $g.Clear([System.Drawing.Color]::FromArgb(255, 0x14, 0x14, 0x14))
    $side = [math]::Min($W, $H)
    $m = [int][math]::Round($side * 0.84)
    $mx = [int](($W - $m) / 2); $my = [int](($H - $m) / 2)
    Draw-Mark $g $mx $my $m ([System.Drawing.Color]::FromArgb(255, 0x4A, 0xDE, 0x88))
    $g.Dispose()
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Bmp)
    $bmp.Dispose()
}

# ---- main ----
$here = Split-Path -Parent $PSCommandPath
$icoDir = Join-Path (Split-Path -Parent $here) "src\main\resources\windows"
$icoPath = Join-Path $icoDir "app.ico"

$frames = @()
foreach ($s in 16, 20, 24, 32, 40, 48, 64, 128, 256) {
    $frames += @{ Size = $s; Bytes = (Get-IconPng $s) }
}
Write-Ico $frames $icoPath
Write-Host "wrote $icoPath ($($frames.Count) sizes)"

Draw-Banner 164 314  (Join-Path $here "wizard-164.bmp")
Draw-Banner 246 459  (Join-Path $here "wizard-246.bmp")
Draw-Banner 328 628  (Join-Path $here "wizard-328.bmp")
Draw-Small  55  55   (Join-Path $here "wizard-small-55.bmp")
Draw-Small  83  80   (Join-Path $here "wizard-small-83.bmp")
Draw-Small 110 106   (Join-Path $here "wizard-small-110.bmp")
Write-Host "wrote wizard banner + small images"
