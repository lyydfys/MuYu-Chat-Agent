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

$openClResult = [pscustomobject]@{
    runId = 'mnn-contract-opencl'
    status = 'completed'
    events = @(
        [pscustomobject]@{
            status = 'generation_ok'
            generation = [pscustomobject]@{ text = 'MNN OpenCL passed.'; textPreview = 'MNN OpenCL passed.' }
        },
        [pscustomobject]@{
            status = 'api_engine_stream_ok'
            apiEngine = [pscustomobject]@{ visibleSeen = $true; text = 'MNN OpenCL passed.' }
        },
        [pscustomobject]@{
            status = 'completed'
            nativeStats = [pscustomobject]@{
                backend = 'mnn_opencl'
                loaded = $true
                runnerReady = $true
                decodeTps = 64.0
            }
        }
    )
}
Assert-DeviceSmokeMnnChatContract -Json $openClResult -ExpectedBackend 'mnn_opencl'
try {
    Assert-DeviceSmokeMnnChatContract -Json $openClResult -ExpectedBackend 'mnn_cpu'
    throw 'MNN smoke contract unexpectedly accepted a mismatched OpenCL backend.'
} catch {
    if ($_.Exception.Message -notmatch 'nativeStats.backend must be mnn_cpu') { throw }
}

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

Write-Host 'PASS: MNN smoke contract accepts CPU/OpenCL terminal stats and rejects backend mismatches and template leaks.'

foreach ($valid in @(
    [pscustomobject]@{ text = '!'; nativeStats = [pscustomobject]@{ mnnDebugGeneratedTokenIds = @(0) } },
    [pscustomobject]@{ text = 'MNN smoke passed.'; nativeStats = [pscustomobject]@{ mnnDebugGeneratedTokenIds = @(44,9455,15728,5642,13); logitsMin = -9.87; logitsMax = 29.97 } },
    [pscustomobject]@{ text = 'Unavailable diagnostics'; nativeStats = [pscustomobject]@{ logitsCount = 0; logitsMin = $null; logitsMax = $null } }
)) { Assert-DeviceSmokeMnnGenerationQuality -Generation $valid }
foreach ($invalid in @(
    [pscustomobject]@{ text = '' },
    [pscustomobject]@{ text = ('!' * 32) },
    [pscustomobject]@{ text = 'Repeated token evidence'; nativeStats = [pscustomobject]@{ mnnDebugGeneratedTokenIds = @(0) * 32 } },
    [pscustomobject]@{ text = 'Repeated nonzero token'; nativeStats = [pscustomobject]@{ mnnDebugGeneratedTokenIds = @(5) * 32 } },
    [pscustomobject]@{ text = 'All zero vocabulary'; nativeStats = [pscustomobject]@{ logitsCount = 248320; logitsFiniteCount = 248320; logitsMin = 0; logitsMax = 0 } },
    [pscustomobject]@{ text = 'Non-finite vocabulary'; nativeStats = [pscustomobject]@{ logitsNonFiniteCount = 1 } }
)) {
    $rejected = $false
    try { Assert-DeviceSmokeMnnGenerationQuality -Generation $invalid } catch {
        if ($_.Exception.Message -notmatch 'MNN generation quality') { throw }
        $rejected = $true
    }
    if (-not $rejected) { throw 'Degenerate generation was incorrectly accepted.' }
}
Write-Host 'PASS: MNN quality checks accept normal/single-token output and reject empty, repeated, zero and non-finite outputs.'
