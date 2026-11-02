<#
.SYNOPSIS
  Measures real time-to-first-drawn-and-actionable (TTFD) for the installed MCA app.

.DESCRIPTION
  Cold starts the installed package N times (am force-stop -> am start -W) and records, per round:

    * launchMs  - the framework's `am start -W` TotalTime. It is a launch-cost number kept only as
                  a reference and is NEVER used as TTFD;
    * firstFrameMs / inputReadyMs / ttfdMs / drawnAndActionable - read from the app's own
                  `McaTtfd` logcat lines (fixed tag, key=value fields).

  TTFD definition (see app/src/main/java/com/muyuchat/mca/presentation/startup/TtfdSignal.kt):

    TTFD = (the chat composer is present in the Compose semantics tree, not disabled, laid out and
            exposes the text-entry action)
         - (process start: android.os.Process.getStartUptimeMillis())

  A round is valid ONLY when one `event=ttfd ... drawnAndActionable=true` line was observed for
  that round's process. A missing logcat signal is recorded as valid=false and the overall result
  becomes NOT_RUN; launchMs is never substituted for TTFD.

.PARAMETER DryRun
  Prints the resolved plan and the exact adb commands without touching any device.

.EXAMPLE
  .\scripts\measure-ttfd.ps1 -ColdStarts 3

.EXAMPLE
  .\scripts\measure-ttfd.ps1 -DryRun
#>
[CmdletBinding()]
param(
    [string] $DeviceSerial = '',
    [string] $Package = 'com.muyuchat.mca',
    [string] $Activity = 'com.muyuchat.mca/.MainActivity',
    [int] $ColdStarts = 3,
    [int] $WaitSeconds = 120,
    [string] $OutFile = '',
    [string] $ApkPath = '',
    [switch] $DryRun
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ($ColdStarts -lt 1) { throw '-ColdStarts must be >= 1' }
if ($WaitSeconds -lt 1) { throw '-WaitSeconds must be >= 1' }
if (-not $OutFile) { $OutFile = Join-Path $repo 'build\ttfd\summary.json' }
if (-not [System.IO.Path]::IsPathRooted($OutFile)) { $OutFile = Join-Path $repo $OutFile }

$schema = 'mca.ttfd/1'
$logTag = 'McaTtfd'

function Resolve-Adb {
    $candidates = @()
    if ($env:ANDROID_HOME) { $candidates += (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe') }
    if ($env:ANDROID_SDK_ROOT) { $candidates += (Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe') }
    $candidates += 'D:\model\android-sdk\platform-tools\adb.exe'
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    $onPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    return $null
}

$adb = Resolve-Adb

if ($DryRun) {
    Write-Output ('schema       : ' + $schema)
    Write-Output ('repo         : ' + $repo)
    Write-Output ('adb          : ' + $(if ($adb) { $adb } else { '<not resolved: set ANDROID_HOME or add platform-tools to PATH>' }))
    Write-Output ('package      : ' + $Package)
    Write-Output ('activity     : ' + $Activity)
    Write-Output ('cold starts  : ' + $ColdStarts)
    Write-Output ('wait seconds : ' + $WaitSeconds)
    Write-Output ('output       : ' + $OutFile)
    Write-Output 'per round (DryRun: nothing below is executed):'
    for ($index = 1; $index -le $ColdStarts; $index++) {
        Write-Output ('  [' + $index + '] adb shell am force-stop ' + $Package)
        Write-Output ('  [' + $index + '] adb shell am start -W -n ' + $Activity)
        Write-Output ('  [' + $index + '] adb shell pidof ' + $Package)
        Write-Output ('  [' + $index + '] adb logcat -d -s ' + $logTag + ' --pid=<pid>')
    }
    Write-Output 'valid round = exactly one event=ttfd line with drawnAndActionable=true; otherwise NOT_RUN'
    exit 0
}

if (-not $adb) { throw 'adb was not found: set ANDROID_HOME or add platform-tools to PATH' }

function Invoke-Adb {
    param([string[]] $Arguments, [switch] $AllowFailure)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $text = (& $adb @Arguments 2>&1 | Out-String).Trim()
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previous }
    if (-not $AllowFailure -and $code -ne 0) {
        throw ('adb ' + ($Arguments -join ' ') + ' failed (exit ' + $code + '): ' + $text)
    }
    return [pscustomobject]@{ exitCode = $code; text = $text }
}

function Invoke-AdbDevice {
    param([string[]] $Arguments, [switch] $AllowFailure)
    return Invoke-Adb -Arguments ((@('-s', $DeviceSerial)) + $Arguments) -AllowFailure:$AllowFailure
}

$online = @(
    (Invoke-Adb -Arguments @('devices') -AllowFailure).text -split "`r?`n" |
        Where-Object { $_ -match '\sdevice\s*$' } |
        ForEach-Object { ($_ -split '\s+')[0] }
)
if (-not $DeviceSerial) {
    if ($online.Count -ne 1) { throw ('expected exactly one online device or -DeviceSerial; online: ' + ($online -join ',')) }
    $DeviceSerial = $online[0]
} elseif ($online -notcontains $DeviceSerial) {
    throw ('device ' + $DeviceSerial + ' is not online; online: ' + ($online -join ','))
}

function Get-DeviceProp {
    param([string] $Name)
    return (Invoke-AdbDevice -Arguments @('shell', 'getprop', $Name)).text.Trim()
}

$device = [ordered]@{
    serial           = $DeviceSerial
    manufacturer     = Get-DeviceProp 'ro.product.manufacturer'
    model            = Get-DeviceProp 'ro.product.model'
    device           = Get-DeviceProp 'ro.product.device'
    androidRelease   = Get-DeviceProp 'ro.build.version.release'
    sdk              = Get-DeviceProp 'ro.build.version.sdk'
    buildFingerprint = Get-DeviceProp 'ro.build.fingerprint'
}

$resolvedApk = $null
if ($ApkPath) {
    if (-not (Test-Path -LiteralPath $ApkPath)) { throw ('APK not found: ' + $ApkPath) }
    $resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
} else {
    foreach ($candidate in @('app\build\outputs\apk\debug\app-debug.apk', 'app\build\outputs\apk\diagnostic\app-diagnostic.apk')) {
        $full = Join-Path $repo $candidate
        if (Test-Path -LiteralPath $full) { $resolvedApk = $full; break }
    }
}
$apkSha256 = $null
if ($resolvedApk) { $apkSha256 = (Get-FileHash -LiteralPath $resolvedApk -Algorithm SHA256).Hash.ToLowerInvariant() }

function Get-Stats {
    param([int64[]] $Values)
    if (-not $Values) { return $null }
    if ($Values.Count -eq 0) { return $null }
    $sorted = @($Values | Sort-Object)
    return [ordered]@{
        count  = $sorted.Count
        min    = [int64]$sorted[0]
        median = [int64]$sorted[[int][Math]::Floor($sorted.Count / 2)]
        max    = [int64]$sorted[$sorted.Count - 1]
    }
}

$rounds = New-Object System.Collections.Generic.List[object]
for ($index = 1; $index -le $ColdStarts; $index++) {
    Write-Output ('round ' + $index + '/' + $ColdStarts + ': force-stop + cold start ' + $Package)
    Invoke-AdbDevice -Arguments @('shell', 'am', 'force-stop', $Package) | Out-Null
    Start-Sleep -Milliseconds 1500

    $launchText = (Invoke-AdbDevice -Arguments @('shell', 'am', 'start', '-W', '-n', $Activity)).text
    $launchStatus = if ($launchText -match 'Status:\s*(\S+)') { $Matches[1] } else { 'unknown' }
    $launchMs = if ($launchText -match 'TotalTime:\s*(\d+)') { [int64]$Matches[1] } else { -1 }
    $waitMs = if ($launchText -match 'WaitTime:\s*(\d+)') { [int64]$Matches[1] } else { -1 }

    $processId = -1
    $pidDeadline = (Get-Date).AddSeconds(10)
    while ($processId -le 0 -and (Get-Date) -lt $pidDeadline) {
        $pidText = (Invoke-AdbDevice -Arguments @('shell', 'pidof', $Package) -AllowFailure).text.Trim()
        if ($pidText -match '^\d+') { $processId = [int](($pidText -split '\s+')[0]) }
        else { Start-Sleep -Milliseconds 200 }
    }

    $signalLines = @()
    $logcatRejected = $false
    if ($processId -gt 0) {
        $signalDeadline = (Get-Date).AddSeconds($WaitSeconds)
        while ($true) {
            $logcatText = (Invoke-AdbDevice -Arguments @('logcat', '-d', '-s', $logTag, ('--pid=' + $processId)) -AllowFailure).text
            if ($logcatText -match 'unknown option|unrecognized option|invalid option') {
                $logcatRejected = $true
                break
            }
            $signalLines = @($logcatText -split "`r?`n" | Where-Object { $_ -match $logTag })
            $hasTtfd = @($signalLines | Where-Object { $_ -match 'event=ttfd\b' }).Count -gt 0
            $hasTimeout = @($signalLines | Where-Object { $_ -match 'event=probe-timeout\b' }).Count -gt 0
            if ($hasTtfd -or $hasTimeout) { break }
            if ((Get-Date) -ge $signalDeadline) { break }
            Start-Sleep -Milliseconds 1000
        }
    }

    $signals = @()
    foreach ($line in $signalLines) {
        $fields = [ordered]@{}
        foreach ($match in [regex]::Matches($line, '([A-Za-z][A-Za-z0-9]*)=(\S+)')) {
            $fields[$match.Groups[1].Value] = $match.Groups[2].Value
        }
        if ($fields.Count -gt 0) { $signals += [pscustomobject]$fields }
    }
    $ttfdSignal = $signals | Where-Object { $_.event -eq 'ttfd' } | Select-Object -Last 1

    $valid = $false
    $failure = ''
    $firstFrameMs = -1
    $inputReadyMs = -1
    $ttfdMs = -1
    if ($logcatRejected) {
        $failure = 'logcat-without-pid-filter'
    } elseif ($processId -le 0) {
        $failure = 'no-process'
    } elseif (-not $ttfdSignal) {
        $timedOut = @($signals | Where-Object { $_.event -eq 'probe-timeout' }).Count -gt 0
        $inputOnly = @($signals | Where-Object { $_.event -eq 'input-ready' }).Count -gt 0
        if ($timedOut) { $failure = 'probe-timeout' }
        elseif ($inputOnly) { $failure = 'input-ready-without-drawn-frame' }
        else { $failure = 'missing-signal' }
    } else {
        $drawnAndActionable = ($ttfdSignal.drawnAndActionable -eq 'true')
        $ttfdMs = if ($ttfdSignal.ttfdMs -match '^\d+$') { [int64]$ttfdSignal.ttfdMs } else { -1 }
        $firstFrameMs = if ($ttfdSignal.firstFrameMs -match '^\d+$') { [int64]$ttfdSignal.firstFrameMs } else { -1 }
        $inputReadyMs = if ($ttfdSignal.inputReadyMs -match '^\d+$') { [int64]$ttfdSignal.inputReadyMs } else { -1 }
        if ($drawnAndActionable -and $ttfdMs -ge 0 -and $firstFrameMs -ge 0 -and
            $inputReadyMs -ge 0 -and $ttfdMs -eq $inputReadyMs) {
            $valid = $true
        } else {
            $failure = 'inconsistent-signal'
        }
    }

    $round = [ordered]@{
        index              = $index
        valid              = $valid
        launchMs           = $launchMs
        waitMs             = $waitMs
        launchStatus       = $launchStatus
        processId          = $processId
        firstFrameMs       = if ($valid) { $firstFrameMs } else { $null }
        inputReadyMs       = if ($valid) { $inputReadyMs } else { $null }
        ttfdMs             = if ($valid) { $ttfdMs } else { $null }
        drawnAndActionable = if ($valid) { $true } else { $false }
        failure            = if ($valid) { $null } else { $failure }
        signals            = $signals
    }
    $rounds.Add([pscustomobject]$round) | Out-Null
    if ($valid) {
        Write-Output ('round ' + $index + ': valid ttfdMs=' + $ttfdMs + ' firstFrameMs=' + $firstFrameMs + ' inputReadyMs=' + $inputReadyMs + ' launchMs=' + $launchMs)
    } else {
        Write-Output ('round ' + $index + ': INVALID (' + $failure + ') launchMs=' + $launchMs + ' - launchMs is not TTFD')
    }
}

$validRounds = @($rounds | Where-Object { $_.valid })
$overallResult = 'FAIL'
if ($validRounds.Count -eq 0) { $overallResult = 'NOT_RUN' }
elseif ($validRounds.Count -eq $rounds.Count) { $overallResult = 'PASS' }

$limits = @(
    'TTFD = (actionable chat composer) - (process start); read only from event=ttfd lines with drawnAndActionable=true. launchMs is never substituted for it.',
    'launchMs is am start -W TotalTime, a framework launch-cost number kept for comparison only. It is not TTFD and not a first-frame timestamp.',
    'Origin is android.os.Process.getStartUptimeMillis(). If the platform cannot report it the app falls back to its first code execution, which under-counts fork/zygote/class-load time; the log line cannot distinguish that fallback.',
    'First frame comes from the window frame commit callback (API 29+) or ViewTreeObserver.OnDrawListener (API 26-28); it is the app window content, not the system splash window.',
    'Input readiness is a semantics-level proof (node present, not disabled, non-empty bounds, SetText action). It does not inject a tap; physical tap-to-focus latency is a different metric.',
    'The composer semantics contract is chat.editor / "消息输入框" from feature/chat; if that contract changes the probe stops reporting and the result becomes NOT_RUN.',
    'Round isolation uses the per-round process id (adb logcat --pid); a device whose logcat lacks --pid cannot produce valid rounds.',
    'apkSha256 hashes the local APK file when one is found; it does not prove the device has that exact build installed.',
    'The installed APK must be built from a revision that contains TtfdSignal; an older install emits no McaTtfd lines and therefore produces NOT_RUN, not a short TTFD.'
    'No connected device in this environment means NOT_RUN, never PASS. There is no emulator or synthetic substitute for a real cold start.',
    'result semantics: PASS = every round produced a valid TTFD; FAIL = at least one round did not; NOT_RUN = no round did (its launchMs values remain launch-cost references only).'
)

$summary = [ordered]@{
    schema         = $schema
    generatedUtc   = (Get-Date).ToUniversalTime().ToString('o')
    result         = $overallResult
    package        = $Package
    activity       = $Activity
    runs           = $ColdStarts
    waitSeconds    = $WaitSeconds
    ttfdDefinition = 'TTFD = (chat composer present in the Compose semantics tree, not disabled, laid out, text-entry action available) - (process start, android.os.Process.getStartUptimeMillis())'
    device         = [pscustomobject]$device
    apkPath        = $resolvedApk
    apkSha256      = $apkSha256
    coverage       = [ordered]@{ validRounds = $validRounds.Count; totalRounds = $rounds.Count }
    statistics     = [ordered]@{
        ttfdMs       = Get-Stats -Values @($validRounds | ForEach-Object { [int64]$_.ttfdMs })
        firstFrameMs = Get-Stats -Values @($validRounds | ForEach-Object { [int64]$_.firstFrameMs })
        inputReadyMs = Get-Stats -Values @($validRounds | ForEach-Object { [int64]$_.inputReadyMs })
        launchMs     = Get-Stats -Values @($rounds | Where-Object { $_.launchMs -gt 0 } | ForEach-Object { [int64]$_.launchMs })
    }
    rounds         = @($rounds | ForEach-Object { $_ })
    limits         = $limits
}

$directory = Split-Path -Parent $OutFile
if ($directory -and -not (Test-Path -LiteralPath $directory)) { New-Item -ItemType Directory -Force -Path $directory | Out-Null }
[IO.File]::WriteAllText($OutFile, ($summary | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))

Write-Output ('TTFD result: ' + $overallResult + ' (' + $validRounds.Count + '/' + $rounds.Count + ' valid rounds)')
if ($validRounds.Count -gt 0) {
    Write-Output ('TTFD min/median/max = ' + $summary.statistics.ttfdMs.min + '/' + $summary.statistics.ttfdMs.median + '/' + $summary.statistics.ttfdMs.max + ' ms')
} else {
    Write-Output 'TTFD was NOT measured; launchMs values above are not TTFD.'
}
Write-Output ('EVIDENCE: ' + $OutFile)
exit 0
