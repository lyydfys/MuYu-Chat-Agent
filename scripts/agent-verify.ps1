#!/usr/bin/env pwsh
# Serialized, module-scoped verification for parallel Agent V1 implementation.
#
# Every build/test invocation takes the SAME exclusive MCA build lock as scripts/build.ps1 and
# scripts/verify-code.ps1. Concurrent requests wait for the lock (bounded), so several agents can
# request compilation without ever running Gradle concurrently against this checkout.
#
# This is a convenience wrapper, not a replacement for the official gates:
#   - it does not weaken any rule,
#   - it only runs the tasks you name,
#   - it refuses an empty task list and reports Gradle's real exit code.
#
# Example:
#   .\scripts\agent-verify.ps1 -Tasks ':core:agent-contract:testDebugUnitTest'
#   .\scripts\agent-verify.ps1 -Tasks ':core:agent-runtime:compileDebugKotlin' -TimeoutMinutes 35

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string[]] $Tasks,
    [int] $TimeoutMinutes = 25,
    [string[]] $Tests = @(),
    [string[]] $GradleArgs = @(),
    [switch] $KeepDaemons,
    [switch] $KeepLog
)

$ErrorActionPreference = 'Continue'
$repo = Split-Path -Parent $PSScriptRoot
$guardDir = if ($env:MCA_BUILD_GUARDS_DIR) { $env:MCA_BUILD_GUARDS_DIR } else { Join-Path $repo 'tools\build-guards' }
$lockModule = Join-Path $guardDir 'McaBuildLock.psm1'
if (-not (Test-Path -LiteralPath $lockModule)) {
    [Console]::Error.WriteLine("agent-verify: build lock module missing: $lockModule")
    exit 3
}
Import-Module $lockModule -Force

if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'E:\model\toolchains\jdk-21\jdk-21.0.12.1+1' }
if (-not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    [Console]::Error.WriteLine("agent-verify: JAVA_HOME does not look like a JDK: $env:JAVA_HOME")
    exit 3
}
# The product build requires a complete QAIRT/QNN header set; verify-code.ps1 supplies the same
# default and the task fails closed without it. Keep this wrapper equivalent to the official lanes.
if (-not $env:MCA_QNN_SDK_ROOT) { $env:MCA_QNN_SDK_ROOT = 'E:\tools\qairt-sdk\2.45.0.260326' }
if (-not (Test-Path $env:MCA_QNN_SDK_ROOT)) {
    [Console]::Error.WriteLine("agent-verify: MCA_QNN_SDK_ROOT does not exist: $env:MCA_QNN_SDK_ROOT")
    exit 3
}
if ($Tasks.Count -eq 0 -or ($Tasks | Where-Object { $_.Trim() -eq '' })) {
    [Console]::Error.WriteLine('agent-verify: refusing an empty Gradle task list')
    exit 3
}

$lock = $null
try {
    $lock = Enter-McaBuildLock -Kind test -RetrySeconds ([Math]::Max(60, $TimeoutMinutes * 60)) `
        -CommandLine ('agent-verify ' + ($Tasks -join ' ')) | Select-Object -Last 1
} catch {
    [Console]::Error.WriteLine('agent-verify: could not acquire the MCA build lock: ' + $_.Exception.Message)
    exit 3
}

try {
    if (-not $KeepDaemons) {
        # We hold the exclusive MCA build lock, so no sanctioned build should be running. A crashed
        # or foreign Gradle/Kotlin daemon keeps Windows file handles on module jars, which makes the
        # next compile fail with "another process is using this file" or "Could not close
        # incremental caches". Clear those processes before starting our own build.
        $stale = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {
            $_.ProcessId -ne $PID -and
                ($_.CommandLine -match 'GradleDaemon|GradleWorkerMain|worker\.org\.gradle|KotlinCompileDaemon|kotlin-build-tools-compat|kotlin-daemon|gradle-wrapper\.jar')
        }
        foreach ($process in $stale) {
            Stop-Process -Id $process.ProcessId -Force -ErrorAction SilentlyContinue
        }
        if ($stale) { Start-Sleep -Seconds 2 }
    }
    # Single-use Gradle + in-process Kotlin avoids the stale Kotlin-daemon/whole-build-dir mmap
    # conflicts that produced repeated "Could not close incremental caches" failures while another
    # session was invoking Gradle. Same tasks, same assertions; only the process topology differs.
    $args = @('--console=plain', '--stacktrace', '--no-daemon',
        '-Pkotlin.compiler.execution.strategy=in-process') + $Tasks + $GradleArgs
    foreach ($filter in $Tests) {
        if ($filter.Trim()) { $args += @('--tests', $filter) }
    }
    $log = Join-Path $env:TEMP ("agent-verify-{0}-{1}.log" -f $PID, (Get-Date -Format 'HHmmss'))
    & (Join-Path $repo 'gradlew.bat') @args 2>&1 | Tee-Object -FilePath $log | Select-Object -Last 60
    $exit = $LASTEXITCODE
    $text = Get-Content $log -Raw -ErrorAction SilentlyContinue
    if ($text -match 'Exception while generating code for') {
        [Console]::Error.WriteLine('agent-verify: Kotlin codegen crash detected')
        Write-Output ("   full log: " + $log)
        exit 4
    }
    if ($exit -ne 0) {
        # Kotlin errors occur before the long Gradle stack trace; print the actionable lines first.
        Write-Output '--- compiler/test diagnostics (from the full log) ---'
        ($text -split "`n" | Where-Object { $_ -match '^e: |^e: file|> Task .* FAILED|What went wrong|Execution failed for task|There were failing tests|FAILED$' } |
            Select-Object -First 80) | ForEach-Object { Write-Output $_.TrimEnd() }
        Write-Output ("   full log: " + $log)
    } else {
        if (-not $KeepLog) { Remove-Item $log -ErrorAction SilentlyContinue }
    }
    exit $exit
} finally {
    if ($lock) { Exit-McaBuildLock -Lock $lock | Out-Null }
}
