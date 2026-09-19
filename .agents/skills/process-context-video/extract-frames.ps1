# Extracts evenly-spaced frames from a video using ONLY built-in Windows WPF media (no ffmpeg, no
# downloads). Prints one machine-readable META line, then writes f00.png.. into -OutDir.
#   powershell -ExecutionPolicy Bypass -File extract-frames.ps1 -Src <video> -OutDir <dir> -Count 6
param(
  [Parameter(Mandatory = $true)][string]$Src,
  [Parameter(Mandatory = $true)][string]$OutDir,
  [int]$Count = 6
)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
Get-ChildItem -Path $OutDir -Filter *.png -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue

Add-Type -AssemblyName PresentationCore, WindowsBase, PresentationFramework

$item = Get-Item -LiteralPath $Src
$mtime = [DateTimeOffset]::new($item.LastWriteTimeUtc).ToUnixTimeSeconds()
$size = $item.Length

$player = New-Object System.Windows.Media.MediaPlayer
$player.Volume = 0
$player.Open([Uri]$item.FullName)

# Pump the dispatcher until MediaOpened reports a duration (headless has no message loop of its own).
$deadline = (Get-Date).AddSeconds(15)
while ((-not $player.NaturalDuration.HasTimeSpan) -and ((Get-Date) -lt $deadline)) {
  [System.Windows.Threading.Dispatcher]::CurrentDispatcher.Invoke([Action] {}, [System.Windows.Threading.DispatcherPriority]::Background)
  Start-Sleep -Milliseconds 80
}
if (-not $player.NaturalDuration.HasTimeSpan) {
  Write-Output "ERROR: media did not open (missing codec, or not a video)"
  $player.Close(); exit 1
}

$w = $player.NaturalVideoWidth
$h = $player.NaturalVideoHeight
$dur = $player.NaturalDuration.TimeSpan.TotalSeconds
Write-Output ("META res={0}x{1} dur={2} mtime={3} size={4}" -f $w, $h, [math]::Round($dur), $mtime, $size)

# Capture during real-time playback so the media clock advances and presents new frames — seeking
# headless does not reliably present frames.
$player.Play()
$intervalMs = [Math]::Max(400, [int](($dur * 1000) / ($Count + 1)))
for ($i = 0; $i -lt $Count; $i++) {
  $end = (Get-Date).AddMilliseconds($intervalMs)
  while ((Get-Date) -lt $end) {
    [System.Windows.Threading.Dispatcher]::CurrentDispatcher.Invoke([Action] {}, [System.Windows.Threading.DispatcherPriority]::Background)
    Start-Sleep -Milliseconds 40
  }
  $dv = New-Object System.Windows.Media.DrawingVisual
  $dc = $dv.RenderOpen()
  $dc.DrawVideo($player, (New-Object System.Windows.Rect(0, 0, $w, $h)))
  $dc.Close()
  $rtb = New-Object System.Windows.Media.Imaging.RenderTargetBitmap($w, $h, 96, 96, [System.Windows.Media.PixelFormats]::Pbgra32)
  $rtb.Render($dv)
  $enc = New-Object System.Windows.Media.Imaging.PngBitmapEncoder
  $enc.Frames.Add([System.Windows.Media.Imaging.BitmapFrame]::Create($rtb))
  $fp = Join-Path $OutDir ("f{0:D2}.png" -f $i)
  $fs = [System.IO.File]::Create($fp)
  $enc.Save($fs); $fs.Close()
}
$player.Stop(); $player.Close()
Write-Output ("FRAMES {0} -> {1}" -f $Count, $OutDir)
