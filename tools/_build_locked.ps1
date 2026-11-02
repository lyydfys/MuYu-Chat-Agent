[CmdletBinding()]
param([string]$LogPath = 'artifacts\build-locked.log', [string]$Tasks = ':app:assembleRelease')
# Local helper for one controlled Gradle run. Beyond invoking Gradle it must report the REAL exit
# code: the previous revision printed "gradle exit=..." and then exited 0, so any caller that only
# looked at the parent exit code treated a failed build as success.
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
    $lock = Enter-McaBuildLock -Kind build -RetrySeconds 3600 -RetryIntervalSeconds 15 -CommandLine 'tools/_build_locked.ps1'
    $env:MCA_QNN_SDK_ROOT = 'E:\tools\qairt-sdk\2.45.0.260326'
    $gradleArgs = @($Tasks, '-Pmca.abis=arm64-v8a', '-Pmca.keepNativeSymbols=false', '-PmcaQnnSdkRoot=E:\tools\qairt-sdk\2.45.0.260326', '--console=plain', '--max-workers=2', '--no-daemon', '-Pkotlin.compiler.execution.strategy=in-process', '-Dorg.gradle.jvmargs=-Xmx6144m')
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
