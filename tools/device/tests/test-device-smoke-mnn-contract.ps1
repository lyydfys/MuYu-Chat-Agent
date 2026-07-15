param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$deviceDir = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Import-Module (Join-Path $deviceDir 'DeviceSmoke.psm1') -Force

if ($null -eq (Get-Command Get-DeviceSmokeEvents -ErrorAction SilentlyContinue)) {
    throw 'Get-DeviceSmokeEvents must be exported because run-mnn-chat-smoke.ps1 validates every continuous turn.'
}

function Assert-Equal {
    param($Actual, $Expected, [string]$Message)
    if ($Actual -ne $Expected) {
        throw "$Message (expected=$Expected actual=$Actual)"
    }
}

$terminalStats = [pscustomobject]@{
    backend = 'mnn_cpu'
    loaded = $true
    runnerReady = $true
    decodeTps = 42.0
}
$result = [pscustomobject]@{
    runId = 'mnn-contract-event-stats'
    status = 'completed'
    events = @(
        [pscustomobject]@{
            status = 'generation_ok'
            generation = [pscustomobject]@{ text = 'MNN smoke passed.'; textPreview = 'MNN smoke passed.' }
        },
        [pscustomobject]@{
            status = 'api_engine_stream_ok'
            apiEngine = [pscustomobject]@{ visibleSeen = $true; text = 'MNN smoke passed.' }
        },
        [pscustomobject]@{
            status = 'completed'
            nativeStats = $terminalStats
        }
    )
}

Assert-DeviceSmokeMnnChatContract -Json $result
$turnEvents = @(Get-DeviceSmokeEvents -Json $result | Where-Object { $_.status -eq 'generation_ok' })
Assert-Equal -Actual $turnEvents.Count -Expected 1 -Message 'Exported event lookup should find the generated turn'
$stats = Get-DeviceSmokeLatestNativeStats -Json $result
Assert-Equal -Actual $stats.backend -Expected 'mnn_cpu' -Message 'Event-nativeStats should be selected when root-nativeStats is absent'
Assert-Equal -Actual $stats.decodeTps -Expected 42.0 -Message 'Selected nativeStats should retain decode speed'

$protocolLeak = [pscustomobject]@{
    runId = 'mnn-contract-protocol-leak'
    status = 'completed'
    events = @(
        [pscustomobject]@{
            status = 'generation_ok'
            generation = [pscustomobject]@{
                text = 'answer<|user|>leaked prompt'
                textPreview = 'answer<|user|>leaked prompt'
            }
        },
        [pscustomobject]@{
            status = 'api_engine_stream_ok'
            apiEngine = [pscustomobject]@{ visibleSeen = $true; text = 'answer' }
        },
        [pscustomobject]@{
            status = 'completed'
            nativeStats = $terminalStats
        }
    )
}
try {
    Assert-DeviceSmokeMnnChatContract -Json $protocolLeak
    throw 'MNN smoke contract unexpectedly accepted a protocol-marker leak.'
} catch {
    if ($_.Exception.Message -notmatch 'template/protocol marker') { throw }
}

Write-Host 'PASS: MNN smoke contract accepts terminal event nativeStats and rejects template leaks.'
