param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$deviceDir = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Import-Module (Join-Path $deviceDir 'DeviceSmoke.psm1') -Force

function Assert-Throws {
    param([scriptblock]$Action, [string]$ExpectedFragment, [string]$Message)
    try {
        & $Action
    } catch {
        if ($_.Exception.Message.IndexOf($ExpectedFragment, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
            return
        }
        throw "$Message (unexpected error=$($_.Exception.Message))"
    }
    throw "$Message (no error thrown)"
}

$valid = [pscustomobject]@{
    status = 'completed'
    workerProductPath = $true
    workerIsolated = $true
    mainProcessAlive = $true
    mainPid = 1200
    workerPid = 1300
    mainLeaseHeld = $true
    workerWaitedForNativeLease = $true
}
Assert-DeviceSmokeWorkerIsolationContract -Json $valid
Assert-DeviceSmokeWorkerIsolationContract -Json $valid -RequireMainLeaseWait

$samePid = $valid.PSObject.Copy()
$samePid.workerPid = 1200
Assert-Throws `
    -Action { Assert-DeviceSmokeWorkerIsolationContract -Json $samePid } `
    -ExpectedFragment 'must be distinct' `
    -Message 'Worker isolation contract must reject equal process ids'

$deadMain = $valid.PSObject.Copy()
$deadMain.mainProcessAlive = $false
Assert-Throws `
    -Action { Assert-DeviceSmokeWorkerIsolationContract -Json $deadMain } `
    -ExpectedFragment 'mainProcessAlive must be true' `
    -Message 'Worker isolation contract must require a live main process'

$missingLeaseWait = $valid.PSObject.Copy()
$missingLeaseWait.workerWaitedForNativeLease = $false
Assert-Throws `
    -Action { Assert-DeviceSmokeWorkerIsolationContract -Json $missingLeaseWait -RequireMainLeaseWait } `
    -ExpectedFragment 'workerWaitedForNativeLease must be true' `
    -Message 'Worker isolation contract must require cross-process lease waiting evidence when requested'

Write-Host 'PASS: worker isolation contract requires distinct PIDs and a live main process.'
