param(
    [int] $QuietMinutes = 10,
    [int] $MaxWaitMinutes = 120,
    [string] $OutRoot = 'artifacts/releases/quiet-window',
    [switch] $DryRun,
    [int] $PollSeconds = 30
)
<#
Runs the signed Release build inside a proven-quiet window.

Why this exists: three Release attempts in this session were refused by release.ps1's own guard because a
second workstream kept editing the shared tree mid-build
(ChatComposerMenuLifecycle.kt once, then MainRuntimeAssembly.kt + DiagnosticsTest.kt, then four
app/.../agent/inference files). The guard was right every time, but "start it and hope" wastes ~20-30
minutes per attempt. This wrapper first waits until the input observation has been unchanged for
[QuietMinutes], then hands over to release.ps1, and finally reports the record it produced.

It cannot make the build unattributable by itself: release.ps1 still captures inputs before and after and
still refuses to record `passed` when they differ.

Exit codes:
  0  release.ps1 reported success (record status passed and an APK hash in the record)
  1  release.ps1 ran but the build/record failed (its own reason is printed)
  2  usage/environment error
  3  no quiet window within [MaxWaitMinutes] (nothing was built)
#>
$ErrorActionPreference = 'Stop'

$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not (Test-Path -LiteralPath (Join-Path $repo 'scripts\release.ps1'))) {
    Write-Error "release.ps1 not found under $repo"
    exit 2
}

# The observation below is deliberately cheaper than Get-McaSourceProvenance (which hashes every build
# input and takes minutes): a change stamp built from `git status --porcelain=v2`, submodule revisions
# and the newest modification time under the production source roots. It is a QUIETNESS PROXY only;
# the authoritative input comparison remains release.ps1's.
function Get-QuietStamp {
    $parts = New-Object System.Collections.Generic.List[string]
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $status = @(& git -C $repo status --porcelain=v2 2>&1 | Where-Object {
                $_ -isnot [System.Management.Automation.ErrorRecord]
            } | ForEach-Object { [string]$_ })
        $parts.Add('status:' + ($status -join "`n")) | Out-Null
        $submodules = @(& git -C $repo submodule status 2>&1 | Where-Object {
                $_ -isnot [System.Management.Automation.ErrorRecord]
            } | ForEach-Object { [string]$_ })
        $parts.Add('submodules:' + ($submodules -join "`n")) | Out-Null
    } finally { $ErrorActionPreference = $previous }

    $newest = [datetime]::MinValue
    foreach ($root in @('app', 'core', 'feature', 'benchmark', 'scripts', 'gradle', 'build-logic', 'vendor', 'third_party')) {
        $path = Join-Path $repo $root
        if (-not (Test-Path -LiteralPath $path)) { continue }
        $item = Get-ChildItem -LiteralPath $path -Recurse -File -Force -ErrorAction SilentlyContinue |
            Where-Object {
                $_.FullName -notmatch '\\(build|\.gradle|\.cxx|\.kotlin|out|artifacts)\\' -and
                $_.Extension -notin @('.o', '.obj', '.pyc', '.class', '.jar')
            } |
            Sort-Object LastWriteTimeUtc -Descending |
            Select-Object -First 1
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
$lastStamp = Get-QuietStamp
$lastChangeAt = Get-Date
Write-Output ("quiet-window: waiting for {0} min of no input changes (max {1} min)" -f $QuietMinutes, $MaxWaitMinutes)
Write-Output ("quiet-window: start stamp " + $lastStamp.Substring(0, 16))

while ($true) {
    Start-Sleep -Seconds $PollSeconds
    $stamp = Get-QuietStamp
    $now = Get-Date
    if ($stamp -ne $lastStamp) {
        $lastStamp = $stamp
        $lastChangeAt = $now
        Write-Output ("quiet-window: input changed at {0}; timer restarted (stamp {1})" -f
            $now.ToString('HH:mm:ss'), $stamp.Substring(0, 16))
    }
    $quiet = $now - $lastChangeAt
    if ($quiet -ge $quietFor) {
        Write-Output ("quiet-window: quiet for {0:N1} min at {1}" -f $quiet.TotalMinutes, $now.ToString('HH:mm:ss'))
        break
    }
    if ($now -ge $deadline) {
        Write-Output ("quiet-window: no quiet window within {0} min; nothing was built" -f $MaxWaitMinutes)
        exit 3
    }
    Write-Output ("quiet-window: {0:N1}/{1} min quiet" -f $quiet.TotalMinutes, $QuietMinutes)
}

if ($DryRun) {
    Write-Output 'quiet-window: -DryRun, not starting the build'
    exit 0
}

# release.ps1 acquires the shared build lock itself, captures inputs before and after, and refuses to
# record a passing build when they differ.
$log = Join-Path $repo ('artifacts\ui-quality\quiet-window\release-' +
    (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss-fffZ') + '.log')
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $log) | Out-Null
Write-Output ("quiet-window: starting release.ps1 (log: $log)")
& (Join-Path $repo 'scripts\release.ps1') -OutRoot $OutRoot *> $log
$exit = $LASTEXITCODE
Write-Output ("quiet-window: release.ps1 exit " + $exit)

$record = Get-ChildItem -LiteralPath (Join-Path $repo 'artifacts\release-build-records') -Filter '*.json' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($record) {
    $json = Get-Content -LiteralPath $record.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
    $apkSha = [string]$json.artifacts.apk.sha256
    Write-Output ('quiet-window: record ' + $record.Name)
    Write-Output ('quiet-window: status=' + [string]$json.status + ' binding=' + [string]$json.source.bindingStatus +
        ' apkSha=' + ($(if ($apkSha) { $apkSha } else { '(none)' })))
    if ($exit -eq 0 -and [string]$json.status -eq 'passed' -and $apkSha) { exit 0 }
}
if ($exit -ne 0) { exit 1 }
exit 1
