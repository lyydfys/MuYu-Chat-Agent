[CmdletBinding()]
param([string]$LogPath = 'artifacts\gate-agent.log')
$ErrorActionPreference = 'Stop'
Set-Location 'E:\MAC'
Import-Module 'E:\MAC\tools\build-guards\McaBuildLock.psm1' -Force
$lock = $null
try {
    $lock = Enter-McaBuildLock -Kind test -RetrySeconds 2400 -RetryIntervalSeconds 15 -CommandLine 'lead: agent feature gate'
    $env:MCA_QNN_SDK_ROOT = 'E:\tools\qairt-sdk\2.45.0.260326'
    & .\gradlew.bat :feature:agent-workspace:testDebugUnitTest :app:testDebugUnitTest --console=plain --max-workers=2 --no-daemon *>&1 | Tee-Object -FilePath $LogPath
    Write-Host ("gradle exit=" + $LASTEXITCODE)
}
finally { if ($lock) { Exit-McaBuildLock -Lock $lock | Out-Null; Write-Host 'lock released' } }
