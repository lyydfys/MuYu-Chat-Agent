[CmdletBinding()]
param([string]$LogPath = 'artifacts\gate-r28.log')
# Local helper for one controlled unit-test gate. Beyond invoking Gradle it must report the REAL
# exit code: the previous revision printed "gradle exit=..." and then exited 0.
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $repo
Import-Module (Join-Path $repo 'tools\build-guards\McaBuildLock.psm1') -Force

if (-not [IO.Path]::IsPathRooted($LogPath)) { $LogPath = Join-Path $repo $LogPath }
$logDir = Split-Path -Parent $LogPath
if ($logDir -and -not (Test-Path -LiteralPath $logDir)) { New-Item -ItemType Directory -Force -Path $logDir | Out-Null }

$lock = $null
$gradleExit = -1
try {
    $lock = Enter-McaBuildLock -Kind test -RetrySeconds 2400 -RetryIntervalSeconds 15 -CommandLine 'tools/_gate_locked.ps1'
    $env:MCA_QNN_SDK_ROOT = 'E:\tools\qairt-sdk\2.45.0.260326'
    $gradleArgs = @(':feature:chat:testDebugUnitTest', ':feature:settings:testDebugUnitTest', ':feature:modelhub:testDebugUnitTest', '--console=plain', '--max-workers=2', '--no-daemon')
    # Gradle writes progress to stderr; only the native exit code decides success (see build.ps1).
    $nativePreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & .\gradlew.bat @gradleArgs 2>&1 | Tee-Object -FilePath $LogPath
        $gradleExit = [int]$LASTEXITCODE
    } finally {
        $ErrorActionPreference = $nativePreference
    }
} catch {
    Write-Host ('FAILED before Gradle completed: ' + $_.Exception.Message)
    $gradleExit = 1
} finally {
    if ($lock) {
        try {
            Exit-McaBuildLock -Lock $lock | Out-Null
            Write-Host 'lock released'
        } catch {
            Write-Host ('lock release failed: ' + $_.Exception.Message)
            if ($gradleExit -eq 0) { $gradleExit = 1 }
        }
    }
}
Write-Host ('gradle exit=' + $gradleExit)
Write-Host ('log=' + $LogPath)
exit $gradleExit
