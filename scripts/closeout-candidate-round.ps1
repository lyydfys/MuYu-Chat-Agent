param(
    [string] $DeviceSerial = '',
    [string] $OutRoot = '',
    [string] $CompareBefore = '',
    [int] $QuietMinutes = 5,
    [int] $MaxWaitMinutes = 120,
    [int] $MinFreeMemoryMb = 2500,
    [int] $PollSeconds = 30,
    [switch] $PlanOnly,
    [switch] $SkipVerify,
    # Build and record the candidate without touching a device. Needed when the only reachable device
    # is in the middle of another workstream's test run - installing there would disturb it.
    [switch] $SkipInstall
)
<#
One command for the R12 closeout round: wait for a usable environment, build the signed candidate,
install it, run the behaviour smoke with the round-2 comparison, and write one summary.

Why a wrapper instead of doing the steps by hand:
  * the shared tree is edited by another workstream every few seconds, the build lock is often held and
    free physical memory oscillates below release.ps1's 2500 MB preflight. Doing this by hand means the
    build either refuses to start or is invalidated by my own edit while it runs;
  * sequencing is the whole point: nothing tracked is touched between "start build" and "record passed".

It never weakens the guards: release.ps1 still captures inputs before/after and still decides whether the
binary is attributable. This wrapper only decides WHEN to start it and what to do with the result.

Exit codes:
  0  record passed + behaviour verdict PASS + round-2 comparison not a regression
  1  a step ran and failed (the failing step is named in the summary and on stdout)
  2  usage/environment error
  3  waited out the window without a usable environment (nothing was built)
#>
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$adb = 'D:\model\android-sdk\platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adb)) { $adb = 'adb' }

if (-not $OutRoot) {
    $OutRoot = Join-Path $repo ('artifacts\releases\v9-closeout-round-' + (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss'))
}
if (-not $PSBoundParameters.ContainsKey('CompareBefore')) {
    $default = Join-Path $repo 'artifacts\ui-quality\v9-closeout\r12-before'
    if (Test-Path -LiteralPath $default) { $CompareBefore = $default }
}

function Get-FreeMemoryMb {
    $os = Get-CimInstance Win32_OperatingSystem
    return [int]($os.FreePhysicalMemory / 1024)
}

function Test-BuildLockFree {
    return -not (Test-Path -LiteralPath (Join-Path $repo 'build\.mca-exclusive-build.lock'))
}

function Get-ChangeStamp {
    $parts = New-Object System.Collections.Generic.List[string]
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $status = @(& git -C $repo status --porcelain=v2 2>&1 | Where-Object {
                $_ -isnot [System.Management.Automation.ErrorRecord]
            } | ForEach-Object { [string]$_ })
        $parts.Add('status:' + ($status -join "`n")) | Out-Null
    } finally { $ErrorActionPreference = $previous }
    $newest = [datetime]::MinValue
    foreach ($root in @('app', 'core', 'feature', 'benchmark', 'scripts', 'gradle', 'build-logic')) {
        $path = Join-Path $repo $root
        if (-not (Test-Path -LiteralPath $path)) { continue }
        $item = Get-ChildItem -LiteralPath $path -Recurse -File -Force -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -notmatch '\\(build|\.gradle|\.cxx|\.kotlin|out|artifacts)\\' } |
            Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
        if ($item -and $item.LastWriteTimeUtc -gt $newest) { $newest = $item.LastWriteTimeUtc }
    }
    $parts.Add('newestWriteUtc:' + $newest.ToString('o')) | Out-Null
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes(($parts -join "`n"))
        return ([BitConverter]::ToString($sha.ComputeHash($bytes)) -replace '-', '').ToLowerInvariant()
    } finally { $sha.Dispose() }
}

$deadline = (Get-Date).AddMinutes($MaxWaitMinutes)
$quietFor = [TimeSpan]::FromMinutes($QuietMinutes)
$lastStamp = Get-ChangeStamp
$lastChangeAt = Get-Date
Write-Output ("closeout: waiting for free>={0}MB, quiet {1}min, free build lock (max {2}min)" -f
    $MinFreeMemoryMb, $QuietMinutes, $MaxWaitMinutes)

while ($true) {
    $freeMb = Get-FreeMemoryMb
    $lockFree = Test-BuildLockFree
    $stamp = Get-ChangeStamp
    $now = Get-Date
    if ($stamp -ne $lastStamp) { $lastStamp = $stamp; $lastChangeAt = $now }
    $quietElapsedMinutes = ($now - $lastChangeAt).TotalMinutes
    Write-Output ("closeout: {0} free={1}MB lock={2} quiet={3:N1}/{4}min" -f
        $now.ToString('HH:mm:ss'), $freeMb, $lockFree, $quietElapsedMinutes, $QuietMinutes)

    $ready = ($freeMb -ge $MinFreeMemoryMb) -and $lockFree -and (($now - $lastChangeAt) -ge $quietFor)
    if ($PlanOnly) {
        # Plan mode is an inspection command: "the environment is busy" is a RESULT to report, not a
        # failure of the command. It never builds, and it always exits 0 unless the arguments are
        # wrong, so callers can inspect a saturated machine without treating it as an error.
        $verdict = if ($ready) { 'usable' } else { 'not-usable' }
        Write-Output ('closeout: -PlanOnly verdict=' + $verdict + ' freeMb=' + $freeMb +
            ' lockFree=' + $lockFree + ' quietMinutes=' + [Math]::Round(($now - $lastChangeAt).TotalMinutes, 1) +
            ' requiredQuietMinutes=' + $QuietMinutes)
        Write-Output 'closeout: -PlanOnly, not starting the build'
        exit 0
    }
    if ($ready) { Write-Output 'closeout: environment is usable'; break }
    if ($now -ge $deadline) {
        Write-Output ("closeout: no usable environment within {0} min; nothing was built" -f $MaxWaitMinutes)
        exit 3
    }
    Start-Sleep -Seconds $PollSeconds
}

$summary = [ordered]@{
    schema = 'mca.closeout-round/1'
    startedUtc = (Get-Date).ToUniversalTime().ToString('o')
    outRoot = $OutRoot
    compareBefore = $CompareBefore
    preconditions = [ordered]@{
        freeMemoryMb = (Get-FreeMemoryMb)
        minFreeMemoryMb = $MinFreeMemoryMb
        quietMinutes = $QuietMinutes
        buildLockFree = (Test-BuildLockFree)
    }
    steps = New-Object System.Collections.Generic.List[object]
}

function Add-Step {
    param([string] $Name, [string] $Status, [string] $Detail)
    $summary.steps.Add([pscustomobject]@{ name = $Name; status = $Status; detail = $Detail }) | Out-Null
    Write-Output ('closeout: {0} -> {1} {2}' -f $Name, $Status, $Detail)
}

# ---------------------------------------------------------------- build
$env:JAVA_HOME = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'E:\model\toolchains\jdk-21\jdk-21.0.12.1+1' }
$env:MCA_QNN_SDK_ROOT = if ($env:MCA_QNN_SDK_ROOT) { $env:MCA_QNN_SDK_ROOT } else { 'E:\tools\qairt-sdk\2.45.0.260326' }
$env:ANDROID_HOME = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'D:\model\android-sdk' }

$buildLog = Join-Path $repo ('artifacts\ui-quality\closeout\build-' + (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss') + '.log')
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $buildLog) | Out-Null
$previous = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    & (Join-Path $repo 'scripts\release.ps1') -OutRoot $OutRoot *> $buildLog
    $buildExit = $LASTEXITCODE
} finally { $ErrorActionPreference = $previous }
$summary.build = [ordered]@{ exitCode = $buildExit; log = $buildLog }

$record = Get-ChildItem -LiteralPath (Join-Path $repo 'artifacts\release-build-records') -Filter '*.json' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $record) { Add-Step 'build' 'FAIL' 'no release record was produced'; exit 1 }
$recordJson = Get-Content -LiteralPath $record.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
$apkSha = [string]$recordJson.artifacts.apk.sha256
$summary.record = [ordered]@{
    path = $record.FullName
    status = [string]$recordJson.status
    bindingStatus = [string]$recordJson.source.bindingStatus
    apkSha256 = $apkSha
    head = [string]$recordJson.source.head
}
if ($buildExit -ne 0 -or [string]$recordJson.status -ne 'passed' -or -not $apkSha) {
    Add-Step 'build' 'FAIL' ("exit=$buildExit status=" + [string]$recordJson.status + ' apk=' + $(if ($apkSha) { $apkSha } else { '(none)' }))
    [IO.File]::WriteAllText((Join-Path $repo 'artifacts\ui-quality\closeout\summary.json'), ($summary | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
    exit 1
}
Add-Step 'build' 'PASS' ('apk=' + $apkSha.Substring(0, 16) + '… binding=' + [string]$recordJson.source.bindingStatus)

$apkPath = Join-Path $OutRoot ([string]$recordJson.artifacts.apk.name)
if (-not (Test-Path -LiteralPath $apkPath)) {
    $found = Get-ChildItem -LiteralPath $OutRoot -Filter '*.apk' -File -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($found) { $apkPath = $found.FullName }
}
$summary.apkPath = $apkPath

# ---------------------------------------------------------------- install
if ($SkipInstall) {
    Add-Step 'install' 'SKIPPED' 'no device was touched (-SkipInstall)'
    $summary.skippedInstall = $true
    [IO.File]::WriteAllText((Join-Path $repo 'artifacts\ui-quality\closeout\summary.json'), ($summary | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
    Write-Output ('closeout: summary written to artifacts\ui-quality\closeout\summary.json')
    exit 0
}
$target = $DeviceSerial
if (-not $target) {
    $online = @(& $adb devices 2>&1 | Where-Object { $_ -match '\sdevice\s*$' } | ForEach-Object { ($_ -split '\s+')[0] })
    if ($online.Count -eq 1) { $target = $online[0] }
}
if (-not $target) { Add-Step 'install' 'FAIL' 'no single online device was found (pass -DeviceSerial)'; exit 2 }

$installOut = (& $adb -s $target install -r $apkPath 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0) {
    if ($installOut -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match') {
        # A debug-signed build is installed on this device. Replacing it is allowed here because the
        # candidate is what this round verifies; the loss of app data is recorded, not hidden.
        Write-Output 'closeout: installed package has a different signature; uninstalling it first'
        & $adb -s $target uninstall com.muyuchat.mca *> $null
        $summary.appDataCleared = $true
        $installOut = (& $adb -s $target install -r $apkPath 2>&1 | Out-String)
    }
}
if ($LASTEXITCODE -ne 0) {
    Add-Step 'install' 'FAIL' $installOut.Trim()
    [IO.File]::WriteAllText((Join-Path $repo 'artifacts\ui-quality\closeout\summary.json'), ($summary | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
    exit 1
}
Add-Step 'install' 'PASS' ('device=' + $target)

# ---------------------------------------------------------------- behaviour + comparison
if (-not $SkipVerify) {
    $verifyOut = Join-Path $repo ('artifacts\ui-quality\closeout\verify-' + (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss') + '.log')
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        if ($CompareBefore) {
            & (Join-Path $repo 'scripts\verify-frontend-behavior.ps1') -DeviceSerial $target -ApkPath $apkPath -CompareBefore $CompareBefore *> $verifyOut
        } else {
            & (Join-Path $repo 'scripts\verify-frontend-behavior.ps1') -DeviceSerial $target -ApkPath $apkPath *> $verifyOut
        }
        $verifyExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previous }
    $verifyText = if (Test-Path -LiteralPath $verifyOut) { Get-Content -LiteralPath $verifyOut -Raw -Encoding UTF8 } else { '' }
    $verdict = if ($verifyText -match 'VERDICT:\s*(\S+)') { $Matches[1] } else { 'UNKNOWN' }
    $a11y = if ($verifyText -match 'A11Y: unnamed clickable controls = (\d+)') { [int]$Matches[1] } else { -1 }
    $round2 = if ($verifyText -match 'ROUND2: verdict=(\w+)') { $Matches[1] } else { 'NOT_RUN' }
    $summary.verify = [ordered]@{
        exitCode = $verifyExit; log = $verifyOut; behaviourVerdict = $verdict
        unnamedClickables = $a11y; round2Verdict = $round2
    }
    Add-Step 'verify' $(if ($verifyExit -eq 0) { 'PASS' } else { 'FAIL' }) ("verdict=$verdict a11y=$a11y round2=$round2")
    if ($verifyExit -ne 0) {
        [IO.File]::WriteAllText((Join-Path $repo 'artifacts\ui-quality\closeout\summary.json'), ($summary | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
        exit 1
    }
    if ($round2 -eq 'REGRESSION') {
        Add-Step 'round2' 'FAIL' 'the comparison reported a regression'
        [IO.File]::WriteAllText((Join-Path $repo 'artifacts\ui-quality\closeout\summary.json'), ($summary | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
        exit 1
    }
}

[IO.File]::WriteAllText((Join-Path $repo 'artifacts\ui-quality\closeout\summary.json'), ($summary | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
Write-Output ('closeout: summary written to artifacts\ui-quality\closeout\summary.json')
exit 0
