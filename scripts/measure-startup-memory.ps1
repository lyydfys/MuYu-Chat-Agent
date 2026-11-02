param(
    [string] $DeviceSerial = '',
    [string] $Package = 'com.muyuchat.mca',
    [string] $Activity = 'com.muyuchat.mca/.MainActivity',
    [int] $ColdStarts = 5,
    [string] $OutDir = '',
    [string] $ExpectedApkSha256 = ''
)
<#
R13 startup and memory diagnostics for one installed candidate.

What it measures, and what it does not:
  * `am force-stop` + `am start -W` repeated N times: the framework's own TotalTime/WaitTime for a
    cold activity launch. That is a launch-cost number, not a cold-start-to-first-frame trace;
  * one `dumpsys meminfo` snapshot after the last launch: total PSS in KB;
  * the display mode actually in use, so a frame budget can be justified instead of assumed.

It refuses to call these "V9 performance acceptance": there is no Perfetto deadline trace, no per-frame
timeline, no 90/120 Hz run. The refresh-rate caveat is recorded in the output, not hidden.
#>
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$adb = 'D:\model\android-sdk\platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adb)) { $adb = 'adb' }

function Invoke-AdbSerial {
    param([string[]] $Arguments)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $text = (& $adb @Arguments 2>&1 | Out-String)
        return [pscustomobject]@{ exitCode = $LASTEXITCODE; text = $text.Trim() }
    } finally { $ErrorActionPreference = $previous }
}

$online = @((Invoke-AdbSerial @('devices')).text -split "`r?`n" | Where-Object { $_ -match '\sdevice\s*$' } | ForEach-Object { ($_ -split '\s+')[0] })
if (-not $DeviceSerial) {
    if ($online.Count -ne 1) { throw "expected exactly one online device or -DeviceSerial; online: $($online -join ',')" }
    $DeviceSerial = $online[0]
}
if (-not $OutDir) {
    $OutDir = Join-Path $repo ('artifacts/ui-quality/v9-closeout/startup-memory-' + $DeviceSerial)
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$installedPath = (Invoke-AdbSerial @('-s', $DeviceSerial, 'shell', 'pm', 'path', $Package)).text
if (-not $installedPath) { throw "package '$Package' is not installed on '$DeviceSerial'" }

$apkShaOnDevice = ''
$remote = ($installedPath -split "`n")[0] -replace '^package:', ''
if ($remote) {
    $pull = Join-Path $OutDir 'installed-base.apk'
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $adb -s $DeviceSerial pull $remote $pull *> $null } finally { $ErrorActionPreference = $previous }
    if (Test-Path -LiteralPath $pull) {
        $apkShaOnDevice = (Get-FileHash -LiteralPath $pull -Algorithm SHA256).Hash.ToLowerInvariant()
        Remove-Item -LiteralPath $pull -Force
    }
}

$display = (Invoke-AdbSerial @('-s', $DeviceSerial, 'shell', 'dumpsys', 'display')).text
$activeMode = if ($display -match 'mActiveModeId=(\d+)') { [int]$Matches[1] } else { -1 }
$renderRate = if ($display -match 'renderFrameRate\s+([\d.]+)') { [double]$Matches[1] } else { -1 }
$presDeadline = if ($display -match 'presDeadline\s+(\d+)') { [int]$Matches[1] } else { -1 }
$supported = if ($display -match 'supportedRefreshRates\s+\[([^\]]+)\]') { $Matches[1] } else { '' }
$peak = (Invoke-AdbSerial @('-s', $DeviceSerial, 'shell', 'settings', 'get', 'system', 'peak_refresh_rate')).text

$starts = New-Object System.Collections.Generic.List[object]
for ($i = 1; $i -le $ColdStarts; $i++) {
    Invoke-AdbSerial @('-s', $DeviceSerial, 'shell', 'am', 'force-stop', $Package) | Out-Null
    Start-Sleep -Milliseconds 1500
    $launch = (Invoke-AdbSerial @('-s', $DeviceSerial, 'shell', 'am', 'start', '-W', '-n', $Activity)).text
    $total = if ($launch -match 'TotalTime:\s*(\d+)') { [int]$Matches[1] } else { -1 }
    $wait = if ($launch -match 'WaitTime:\s*(\d+)') { [int]$Matches[1] } else { -1 }
    $status = if ($launch -match 'Status:\s*(\S+)') { $Matches[1] } else { 'unknown' }
    # TotalTime 0 with a successful status means the activity was already resumed (the force-stop had
    # not settled yet). That iteration is recorded but flagged invalid instead of being silently used
    # in the statistics or silently dropped.
    $valid = ($status -eq 'ok') -and ($total -gt 0)
    $starts.Add([pscustomobject]@{
            iteration = $i; status = $status; totalTimeMs = $total; waitTimeMs = $wait; valid = $valid
        }) | Out-Null
    Write-Output ("startup: iteration $i status=$status TotalTime=${total}ms WaitTime=${wait}ms")
    Start-Sleep -Seconds 2
}

$meminfo = (Invoke-AdbSerial @('-s', $DeviceSerial, 'shell', 'dumpsys', 'meminfo', $Package)).text
[IO.File]::WriteAllText((Join-Path $OutDir 'meminfo.txt'), $meminfo, [Text.UTF8Encoding]::new($false))
$pssTotal = if ($meminfo -match 'TOTAL PSS:\s*(\d+)') { [int]$Matches[1] } elseif ($meminfo -match '(?m)^\s*TOTAL\s+(\d+)') { [int]$Matches[1] } else { -1 }

$validStarts = @($starts | Where-Object { $_.totalTimeMs -gt 0 })
$summary = [ordered]@{
    schema = 'mca.startup-memory/1'
    device = $DeviceSerial
    package = $Package
    generatedUtc = (Get-Date).ToUniversalTime().ToString('o')
    installedApkSha256 = $apkShaOnDevice
    expectedApkSha256 = $ExpectedApkSha256
    apkShaMatches = if ($ExpectedApkSha256) { $apkShaOnDevice -eq $ExpectedApkSha256.ToLowerInvariant() } else { $null }
    coldStarts = @($starts | ForEach-Object { $_ })
    validColdStartIterations = @($validStarts | ForEach-Object { $_.iteration })
    coldStartCount = $validStarts.Count
    totalTimeMinMs = if ($validStarts.Count -gt 0) { ($validStarts | Measure-Object -Property totalTimeMs -Minimum).Minimum } else { -1 }
    totalTimeMaxMs = if ($validStarts.Count -gt 0) { ($validStarts | Measure-Object -Property totalTimeMs -Maximum).Maximum } else { -1 }
    totalTimeMedianMs = if ($validStarts.Count -gt 0) {
        $sorted = @($validStarts | Sort-Object totalTimeMs | ForEach-Object { $_.totalTimeMs })
        $sorted[[int][Math]::Floor($sorted.Count / 2)]
    } else { -1 }
    pssTotalKb = $pssTotal
    display = [ordered]@{
        activeModeId = $activeMode
        renderFrameRate = $renderRate
        presentationDeadlineNs = $presDeadline
        supportedRefreshRates = $supported
        peakRefreshRateSetting = $peak
    }
    limits = @(
        'TotalTime/WaitTime come from `am start -W`; they are launch-cost numbers, not cold-start-to-first-frame traces.',
        'One meminfo snapshot after the last launch; no sustained-growth or leak analysis was performed.',
        'No Perfetto deadline trace and no 90/120 Hz run are part of this measurement.',
        'The display mode above is what the device actually used; a frame budget must be read from it, not assumed.'
    )
}
[IO.File]::WriteAllText((Join-Path $OutDir 'summary.json'), ($summary | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))

Write-Output ('startup: TotalTime min/median/max = {0}/{1}/{2} ms over {3} cold starts' -f
    $summary.totalTimeMinMs, $summary.totalTimeMedianMs, $summary.totalTimeMaxMs, $summary.coldStartCount)
Write-Output ('memory: PSS total = ' + $pssTotal + ' KB')
Write-Output ('display: modeId=' + $activeMode + ' renderFrameRate=' + $renderRate + ' presDeadline=' + $presDeadline + 'ns')
Write-Output ('EVIDENCE: ' + $OutDir)
exit 0
