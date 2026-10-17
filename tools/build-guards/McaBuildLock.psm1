<#
.SYNOPSIS
  Exclusive single-execution lock shared by MCA build / lint / test entry points.

.DESCRIPTION
  Answers the weakness recorded in the 2026-10-10 re-review (搂8.2 / review 搂5.2): scanning for a
  Gradle wrapper process immediately before launch only proves that no other wrapper existed at
  that instant. It cannot stop a second executor from entering afterwards, and it says nothing
  about an idle daemon. This module gives build / lint / test ONE exclusive lock so that a second
  concurrent invocation FAILS IMMEDIATELY, naming the holder - it is not a warning.

  Mechanism: an exclusive OS handle on the lock file
  (`[System.IO.File]::Open(..., FileShare::None)`). A second opener gets an IOException; that is
  the rejection channel, and it cannot be raced by the TOCTOU gap a pre-launch scan has.

  Stale handling: the payload records the holder PID. If the recorded PID is no longer alive the
  lock is reclaimed and the reclaim is reported. A live holder is never displaced.

  This module never touches git: it does not stage, commit or publish anything, and it does not
  read or modify `.git/info/exclude`. The locally protected files (AGENTS.md,
  app/src/test/java/com/muyuchat/mca/UniversalDeviceAdmissionPolicyTest.kt) are outside its
  concern entirely - the lock only serialises processes.

.EXAMPLE
  Import-Module .\McaBuildLock.psm1
  $lock = Enter-McaBuildLock -Kind build -TimeoutMinutes 60
  try { <run gradle / cmake> } finally { Exit-McaBuildLock -Lock $lock }
#>

$script:McaLockDefaultPath = 'E:\MAC\build\.mca-exclusive-build.lock'

# Paths currently held by THIS process. E01: a second Enter-McaBuildLock for a path this process
# already holds is REFUSED instead of self-deadlocking (e.g. a wrapper that holds the lock calling
# a lock-holding entry point like build.ps1/verify-code.ps1). Sequential acquire/release of the
# same path stays legal - the registry only sees the live handle.
$script:McaHeldLockPaths = @{}

function Get-McaLockPath {
    param([string]$LockPath)
    if ($LockPath) { return $LockPath }
    if ($env:MCA_BUILD_LOCK_PATH) { return $env:MCA_BUILD_LOCK_PATH }
    return $script:McaLockDefaultPath
}

function Test-McaProcessAlive {
    param([int]$ProcessId)
    if ($ProcessId -le 0) { return $false }
    try { return [bool](Get-Process -Id $ProcessId -ErrorAction Stop) } catch { return $false }
}

function Enter-McaBuildLock {
    <#
      Acquires the exclusive lock. Throws (terminating) when another LIVE holder owns it, so a
      caller sees a hard failure rather than a warning. Returns a lock object to pass to
      Exit-McaBuildLock.
    #>
    [CmdletBinding()]
    param(
        [ValidateSet('build', 'lint', 'test', 'other')][string]$Kind = 'build',
        [string]$LockPath,
        [string]$CommandLine = '',
        [int]$RetrySeconds = 0,
        [int]$RetryIntervalSeconds = 2
    )
    $path = Get-McaLockPath -LockPath $LockPath
    if ($script:McaHeldLockPaths.ContainsKey([string]$path)) {
        throw ("MCA build lock re-entrant acquisition refused: this process (pid=$PID) already holds $path as kind=" + $script:McaHeldLockPaths[[string]$path] + ". Do not nest lock-holding entry points; complete the outer operation first.")
    }
    $dir = Split-Path -Parent $path
    if ($dir -and -not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }

    $deadline = (Get-Date).AddSeconds([Math]::Max(0, $RetrySeconds))
    $reclaimed = $null
    while ($true) {
        try {
            # FileShare::Read (not None): this still excludes every other WRITER, but it lets a
            # blocked acquirer READ the payload so it can name the holder. With FileShare::None the
            # rejection worked but reported "unknown holder", which defeats the point of failing.
            $fs = [System.IO.File]::Open($path, [System.IO.FileMode]::OpenOrCreate,
                    [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::Read)
        } catch [System.IO.IOException] {
            # Someone holds it. Is the holder alive?
            $holder = $null
            try {
                $raw = Get-Content -LiteralPath $path -Raw -ErrorAction Stop
                if ($raw) { $holder = $raw | ConvertFrom-Json }
            } catch { }
            $holderPid = if ($holder -and $holder.pid) { [int]$holder.pid } else { 0 }
            $alive = Test-McaProcessAlive -ProcessId $holderPid
            if (-not $alive) {
                # Stale: the recorded holder is gone. Take it over and report the reclaim.
                Start-Sleep -Milliseconds 120
                try {
                    $fs = [System.IO.File]::Open($path, [System.IO.FileMode]::OpenOrCreate,
                            [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::Read)
                    $reclaimed = [pscustomobject]@{ stalePid = $holderPid; staleKind = $(if ($holder) { $holder.kind } else { $null }) }
                } catch {
                    $fs = $null
                }
            }
            if (-not $fs) {
                $who = if ($holder) { "pid=$($holder.pid) kind=$($holder.kind) started=$($holder.startedUtc) cmd=$($holder.commandLine)" } else { 'unknown holder' }
                if ((Get-Date) -lt $deadline) { Start-Sleep -Seconds $RetryIntervalSeconds; continue }
                throw "MCA build lock is held: $path :: $who"
            }
        }

        $payload = [pscustomobject]@{
            schema      = 'mca.build-lock/1'
            pid         = $PID
            kind        = $Kind
            startedUtc  = [datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
            machine     = $env:COMPUTERNAME
            commandLine = $CommandLine
        } | ConvertTo-Json -Depth 3
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($payload)
        $fs.SetLength(0)
        $fs.Write($bytes, 0, $bytes.Length)
        $fs.Flush()

        $script:McaHeldLockPaths[[string]$path] = $Kind
        return [pscustomobject]@{
            path      = $path
            kind      = $Kind
            pid       = $PID
            stream    = $fs
            reclaimed = $reclaimed
        }
    }
}

function Exit-McaBuildLock {
    [CmdletBinding()]
    param([Parameter(Mandatory)]$Lock)
    if ($null -eq $Lock) { return }
    try { [void]$script:McaHeldLockPaths.Remove([string]$Lock.path) } catch { }
    try {
        if ($Lock.PSObject.Properties.Name -contains 'stream' -and $Lock.stream) {
            $Lock.stream.Dispose()
        }
    } catch { }
    # DELETE the lock file, do not merely truncate it.
    #
    # Leaving the file behind (even emptied) is an observability defect: a stale file with a dead
    # pid in it makes an observer conclude a build is still running. That actually happened - a
    # teammate read the leftover payload, believed the build slot was occupied, and postponed its
    # own gate run. The correct reading is "a lock file existing is NOT the same as a build
    # running; you must read the payload and check whether that pid is alive", but the cheapest fix
    # is to not leave litter in the first place.
    #
    # Safety: we only reach here having held the exclusive handle, and the handle is disposed
    # above, so no acquirer can be holding this path while we delete it. If deletion fails we fall
    # back to truncating the payload, which at least removes the misleading pid.
    $deleted = $false
    try {
        if (Test-Path -LiteralPath $Lock.path) {
            Remove-Item -LiteralPath $Lock.path -Force -ErrorAction Stop
            $deleted = $true
        } else { $deleted = $true }
    } catch { }
    if (-not $deleted) {
        try {
            $fs = [System.IO.File]::Open($Lock.path, [System.IO.FileMode]::Open,
                    [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::Read)
            $fs.SetLength(0)
            $fs.Dispose()
        } catch { }
    }
    return $true
}

function Get-McaBuildActivity {
    <#
      Returns the compiler/build processes currently running, split into two classes:

        hard - cmake/ninja/cl/link/cargo/rustc: transient, present only while a build or link is
               actually executing. Their presence with no lock holder means somebody is building
               outside the lock.
        soft - java: usually a long-lived IDLE Gradle daemon, so it is a warning only. Refusing to
               start merely because a daemon exists would break every normal build (a false
               positive), which is why java is reported but never blocks.
    #>
    [CmdletBinding()]
    param([string[]]$HardNames = @('cmake', 'ninja', 'cl', 'link', 'lld', 'cargo', 'rustc', 'clang', 'clang++'),
          [string[]]$SoftNames = @('java'))
    $hard = @(); $soft = @()
    foreach ($n in $HardNames) {
        foreach ($x in @(Get-Process -Name $n -ErrorAction SilentlyContinue)) {
            $hard += [pscustomobject]@{
                name = $n; pid = $x.Id; rssMB = [math]::Round($x.WorkingSet64 / 1MB, 0)
                startedUtc = $(try { $x.StartTime.ToUniversalTime().ToString('HH:mm:ssZ') } catch { '?' })
            }
        }
    }
    foreach ($n in $SoftNames) {
        foreach ($x in @(Get-Process -Name $n -ErrorAction SilentlyContinue)) {
            $soft += [pscustomobject]@{
                name = $n; pid = $x.Id; rssMB = [math]::Round($x.WorkingSet64 / 1MB, 0)
                startedUtc = $(try { $x.StartTime.ToUniversalTime().ToString('HH:mm:ssZ') } catch { '?' })
            }
        }
    }
    return [pscustomobject]@{ hard = $hard; soft = $soft }
}

function Get-McaLockHolder {
    # Reads the holder payload without taking the lock. Returns $null when the lock is free, or
    # when the payload is unreadable/empty, or when the recorded holder is no longer alive.
    [CmdletBinding()]
    param([string]$LockPath)
    $path = Get-McaLockPath -LockPath $LockPath
    if (-not (Test-Path -LiteralPath $path)) { return $null }
    try {
        $raw = Get-Content -LiteralPath $path -Raw -ErrorAction Stop
        if (-not $raw -or -not $raw.Trim()) { return $null }
        $h = $raw | ConvertFrom-Json
        if (-not (Test-McaProcessAlive -ProcessId ([int]$h.pid))) { return $null }
        return $h
    } catch { return $null }
}

function Assert-McaBuildSlot {
    <#
      Runtime occupancy probe. Refuses to start when compiler/link processes are running but
      NOBODY holds the lock - the exact situation measured on 2026-10-10 10:40-10:45, where the
      lock file was free/acquirable while cmake x1, link x6 and java x3 were running with only
      913 MB free (the same order as the build6 JVM native OOM). A lock holder would otherwise
      have believed it was exclusive while three other executors were live.

      Honest scope: this closes the "nobody is even using the lock" case only. It does NOT make
      the lock global - a participant that never calls Enter-McaBuildLock still bypasses it, so
      the report must keep saying "mutex holds only when every participant uses the lock".

      Never kills anything; never makes the lock permanently exclusive.
    #>
    [CmdletBinding()]
    param([string]$LockPath, [switch]$WarnOnly,
          [string[]]$HardNames = @('cmake', 'ninja', 'cl', 'link', 'lld', 'cargo', 'rustc', 'clang', 'clang++'),
          [string[]]$SoftNames = @('java'))
    $holder = Get-McaLockHolder -LockPath $LockPath
    $act = Get-McaBuildActivity -HardNames $HardNames -SoftNames $SoftNames
    if ($holder) {
        Write-Host ("MCA build slot: lock held by pid={0} kind={1} started={2}" -f $holder.pid, $holder.kind, $holder.startedUtc)
        return $true
    }
    if ($act.soft.Count -gt 0) {
        Write-Warning ("MCA build slot: {0} java process(es) running with no live lock holder (likely an idle Gradle daemon; not blocking)." -f $act.soft.Count)
    }
    if ($act.hard.Count -gt 0) {
        $list = ($act.hard | ForEach-Object { "$($_.name)(pid=$($_.pid),rss=$($_.rssMB)MB,since=$($_.startedUtc))" }) -join ', '
        $msg = "MCA build slot: refusing to start - compiler/build activity is running while NOBODY holds the lock: $list. " +
               "Take the lock (Enter-McaBuildLock) or wait for that build to finish. Nothing is killed."
        if ($WarnOnly) { Write-Warning $msg; return $false }
        throw $msg
    }
    return $true
}

Export-ModuleMember -Function Enter-McaBuildLock, Exit-McaBuildLock, Test-McaProcessAlive, Get-McaLockPath, Get-McaBuildActivity, Get-McaLockHolder, Assert-McaBuildSlot
