# Reads what "dumpsys display" says about the TV and prints the highest picture mode it supports,
# plus the drawing size: "width height fps drawWidth drawHeight density".
# Everything is drawn at 1920x1080 (smooth on a 2 GB TV); on a 4K TV films, series and games still play in 4K.
param([string]$Path)
$text = Get-Content -Raw -Path $Path
$culture = [Globalization.CultureInfo]::InvariantCulture
$modes = [regex]::Matches($text, 'width=(\d+), height=(\d+), fps=([0-9.]+)') | ForEach-Object {
    [pscustomobject]@{
        W   = [int]$_.Groups[1].Value
        H   = [int]$_.Groups[2].Value
        Fps = $_.Groups[3].Value
        F   = [double]::Parse($_.Groups[3].Value, $culture)
    }
} | Where-Object { $_.W -ge $_.H }
if (-not $modes) { exit 1 }
$best = $modes | Sort-Object @{ Expression = { $_.W * $_.H }; Descending = $true }, @{ Expression = { $_.F }; Descending = $true } | Select-Object -First 1
# PlaizierOS and your apps are always drawn at 1920 x 1080 (density 320: everything keeps its size, just sharper).
$uw = 1920; $uh = 1080; $d = 320
Write-Output ("{0} {1} {2} {3} {4} {5}" -f $best.W, $best.H, $best.Fps, $uw, $uh, $d)
