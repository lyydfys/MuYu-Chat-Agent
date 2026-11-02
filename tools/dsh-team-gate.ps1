<#
.SYNOPSIS
  Locked Gradle gate shared by DSH V2 implementation lanes.

.DESCRIPTION
  Serialises every DSH V2 Gradle compile/test run behind the project's exclusive build lock
  (tools/build-guards/McaBuildLock.psm1) so two lanes never run Gradle in the same build
  directory. Blocks (with a bounded retry) instead of failing immediately, because lanes are
  expected to queue.

  This does not add a parallel build script: it calls the same gradlew wrapper with the module
  tasks the caller names, and keeps the full console log at -LogPath. Nothing is skipped.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File tools\dsh-team-gate.ps1 `
      -Tasks ':core:dsh-platform:testDebugUnitTest' -LogPath 'artifacts/dsh-v2/gates/platform.log' -Label 'lane platform'
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string[]]$Tasks,
    [string]$LogPath = 'artifacts/dsh-v2/gates/gate.log',
    [string]$Label = 'dsh-v2 gate',
    [int]$RetrySeconds = 5400,
    [int]$RetryIntervalSeconds = 20,
    [int]$TimeoutMinutes = 90
)
$ErrorActionPreference = 'Stop'
Set-Location 'E:\MAC'
$logDir = Split-Path -Parent $LogPath
if ($logDir -and -not (Test-Path -LiteralPath $logDir)) { New-Item -ItemType Directory -Force -Path $logDir | Out-Null }
Import-Module 'E:\MAC\tools\build-guards\McaBuildLock.psm1' -Force
$lock = $null
try {
    $lock = Enter-McaBuildLock -Kind test -RetrySeconds $RetrySeconds -RetryIntervalSeconds $RetryIntervalSeconds -CommandLine $Label
    $started = Get-Date
    Write-Host ("[" + $started.ToString('HH:mm:ss') + "] gradle " + ($Tasks -join ' '))
    & .\gradlew.bat @Tasks --console=plain --no-daemon --max-workers=2 2>&1 | Tee-Object -FilePath $LogPath
    $code = $LASTEXITCODE
    $elapsed = [math]::Round(((Get-Date) - $started).TotalMinutes, 1)
    Write-Host ("gradle exit=" + $code + " elapsedMin=" + $elapsed + " log=" + $LogPath)
    exit $code
}
finally {
    if ($lock) { Exit-McaBuildLock -Lock $lock | Out-Null }
}
