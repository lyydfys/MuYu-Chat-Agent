<#
T08 production-UI model loader (build6 window).

Why this exists: the authenticated Local API only serves the model the real MainActivity has
loaded (409 model_mismatch otherwise). After `install -r` the engine is `unloaded`, so the chat
and SSE cases cannot run until the model is loaded through the production UI.

Hard-won UI rules encoded here (each one cost a failed attempt earlier in this run):
  1. Every tap re-dumps the hierarchy and uses LIVE bounds. The model chip's y drifts with chat
     history length, so replayed hard-coded coordinates silently miss (and can hit a chat link,
     which switches the foreground to another app).
  2. uiautomator frequently returns a null root just after launch/recomposition; retries are
     mandatory, and a tiny dump (< 2 KB) is treated as "not settled yet".
  3. Never use KEYCODE_BACK on this device: it exits MCA to whatever app was last used.
  4. The authoritative success signal is the API's `"loaded":true`, not any UI label.

ASCII-only source on purpose: this host decodes UTF-8 scripts as GBK, so non-ASCII literals
corrupt the parser. UI text is matched via ConvertFrom-T08UnicodeEscapes code points.
#>
[CmdletBinding()]
param(
    [string]$Adb = 'D:\model\android-sdk\platform-tools\adb.exe',
    [string]$Serial = 'f9d98aa3',
    [string]$Package = 'com.muyuchat.mca',
    [int]$Port = 11435,
    [string]$ModelName = 'Huihui-Qwen3.5-2B-abliterated.Q4_K_M',
    [string]$OutDir = '',
    [int]$TimeoutSeconds = 150
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $OutDir) { $OutDir = Join-Path (Get-Location).Path 'artifacts\repair\20261009-parity\device\ui-load' }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function ConvertFrom-T08UnicodeEscapes([string]$text) {
    $sb = [Text.StringBuilder]::new()
    $i = 0
    while ($i -lt $text.Length) {
        if ($text[$i] -eq '[' -and ($i + 1) -lt $text.Length -and $text[$i + 1] -eq '\') {
            $end = $text.IndexOf(']', $i)
            if ($end -gt $i) {
                $parts = $text.Substring($i + 2, $end - $i - 2) -split '\\u'
                foreach ($p in $parts) { if ($p.Length -eq 4) { [void]$sb.Append([char][Convert]::ToInt32($p, 16)) } }
                $i = $end + 1
                continue
            }
        }
        [void]$sb.Append($text[$i]); $i = $i + 1
    }
    return $sb.ToString()
}
# code points used above: chip / local-section / loaded / unload labels
$chipText   = ConvertFrom-T08UnicodeEscapes '[\u9009\u62e9\u6a21\u578b]'
$localText  = ConvertFrom-T08UnicodeEscapes '[\u672c\u5730\u63a8\u7406]'

function Invoke-Tolerant {
    param([Parameter(Mandatory = $true)][scriptblock]$Block)
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $Block } finally { $ErrorActionPreference = $saved }
}

function Get-Foreground {
    $o = Invoke-Tolerant { & $Adb -s $Serial shell dumpsys activity activities 2>$null }
    $line = ($o | Select-String 'topResumedActivity' | Select-Object -First 1)
    if (-not $line) { return '' }
    return $line.Line.Trim()
}

function Get-Dump([string]$tag) {
    # returns parsed nodes, or $null when the hierarchy is not settled
    for ($try = 1; $try -le 5; $try++) {
        Invoke-Tolerant { & $Adb -s $Serial shell uiautomator dump --compressed /sdcard/t08ui.xml 2>$null | Out-Null }
        $local = Join-Path $OutDir ('dump-' + $tag + '-' + $try + '.xml')
        Invoke-Tolerant { & $Adb -s $Serial pull /sdcard/t08ui.xml $local 2>$null | Out-Null }
        if (-not (Test-Path $local)) { Start-Sleep -Milliseconds 900; continue }
        $raw = [Text.Encoding]::UTF8.GetString([IO.File]::ReadAllBytes($local))
        if ($raw.Length -lt 2000) { Start-Sleep -Milliseconds 1200; continue }
        $nodes = @()
        foreach ($m in [regex]::Matches($raw, '<node ([^>]*?)/?>')) {
            $a = $m.Groups[1].Value
            $t = [regex]::Match($a, 'text="([^"]*)"').Groups[1].Value
            $cd = [regex]::Match($a, 'content-desc="([^"]*)"').Groups[1].Value
            $b = [regex]::Match($a, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
            if (-not $b.Success) { continue }
            $x1 = [int]$b.Groups[1].Value; $y1 = [int]$b.Groups[2].Value
            $x2 = [int]$b.Groups[3].Value; $y2 = [int]$b.Groups[4].Value
            $nodes += [pscustomobject]@{
                text = $t; desc = $cd
                cx = [int](($x1 + $x2) / 2); cy = [int](($y1 + $y2) / 2)
                top = $y1; left = $x1
            }
        }
        if ($nodes.Count -gt 0) { return $nodes }
        Start-Sleep -Milliseconds 1200
    }
    return $null
}

function Tap-Live([string]$matchText, [string]$what, [int]$maxTop = 99999, [int]$minTop = 0) {
    # re-dump immediately before tapping and use the LIVE centre of the target node
    $nodes = Get-Dump ('tap-' + $what)
    if (-not $nodes) { Write-Host ('  [tap] ' + $what + ': dump not settled'); return $false }
    $hit = $nodes | Where-Object { $_.text -like ('*' + $matchText + '*') -and $_.top -ge $minTop -and $_.top -le $maxTop } |
           Sort-Object top | Select-Object -First 1
    if (-not $hit) { Write-Host ('  [tap] ' + $what + ': not found'); return $false }
    $fg = Get-Foreground
    if ($fg -notmatch [regex]::Escape($Package)) {
        Write-Host ('  [tap] ' + $what + ': foreground is not MCA (' + $fg + ') - refusing to tap')
        return $false
    }
    Write-Host ('  [tap] ' + $what + ' -> (' + $hit.cx + ',' + $hit.cy + ')')
    Invoke-Tolerant { & $Adb -s $Serial shell input tap $hit.cx $hit.cy | Out-Null }
    return $true
}

function Get-Loaded {
    $o = Invoke-Tolerant { & $Adb -s $Serial shell curl -s ('http://127.0.0.1:' + $Port + '/v1/models') 2>$null }
    $s = ($o -join '')
    return ($s -match '"loaded":true')
}

Write-Host ('foreground: ' + (Get-Foreground))
if (Get-Loaded) { Write-Host 'model already loaded'; exit 0 }

# 1) open the model chip (top area only: the chip lives in the header).
#    The Compose sheet can take a moment to compose, so poll for the section label rather than
#    assuming a fixed delay, and retry the chip tap if the sheet never appeared.
$opened = $false
for ($attempt = 1; $attempt -le 3; $attempt++) {
    if (-not (Tap-Live $chipText 'model-chip' 700 0)) { Write-Host 'FAIL: chip not found'; exit 2 }
    for ($w = 1; $w -le 8; $w++) {
        Start-Sleep -Seconds 1
        $nodes = Get-Dump ('sheet-' + $attempt + '-' + $w)
        if ($nodes -and ($nodes | Where-Object { $_.text -like ('*' + $localText + '*') })) {
            $opened = $true
            break
        }
    }
    if ($opened) { break }
    Write-Host ('  sheet did not open on attempt ' + $attempt + '; retrying chip tap')
}
if (-not $opened) { Write-Host 'FAIL: model sheet never opened'; exit 2 }
Write-Host '  sheet open'

# 2) expand the local-inference section
if (-not (Tap-Live $localText 'local-section' 2200 300)) { Write-Host 'FAIL: local section not found'; exit 2 }
Start-Sleep -Seconds 2

# 3) tap the model row by its own live bounds
if (-not (Tap-Live $ModelName 'model-row' 3000 0)) { Write-Host 'FAIL: model row not found'; exit 2 }

# 4) poll the API for the authoritative loaded signal
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 4
    if (Get-Loaded) { Write-Host 'LOADED=true'; exit 0 }
}
Write-Host 'FAIL: model did not become loaded within timeout'
exit 1
