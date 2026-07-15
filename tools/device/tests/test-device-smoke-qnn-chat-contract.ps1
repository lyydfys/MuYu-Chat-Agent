param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$deviceDir = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Import-Module (Join-Path $deviceDir 'DeviceSmoke.psm1') -Force

function Assert-Equal {
    param($Actual, $Expected, [string]$Message)
    if ($Actual -ne $Expected) {
        throw "$Message (expected=$Expected actual=$Actual)"
    }
}

function Assert-Null {
    param($Actual, [string]$Message)
    if ($null -ne $Actual) {
        throw "$Message (actual=$Actual)"
    }
}

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

function New-QnnStats {
    param([int]$CompletionTokens = 8, [double]$DecodeTps = 21.35)
    return [pscustomobject]@{
        backend = 'geniex_qairt'
        computeUnit = 'npu'
        loaded = $true
        runnerReady = $true
        completionTokens = $CompletionTokens
        decodeTps = $DecodeTps
    }
}

$terminalStats = New-QnnStats
$completed = [pscustomobject]@{
    status = 'completed'
    events = @(
        [pscustomobject]@{
            status = 'first_load_ok'
            nativeStats = (New-QnnStats -CompletionTokens 0 -DecodeTps 0.0)
        },
        [pscustomobject]@{
            status = 'api_engine_stream_ok'
            apiEngine = [pscustomobject]@{ visibleSeen = $true }
        },
        [pscustomobject]@{
            status = 'completed'
            nativeStats = $terminalStats
        }
    )
}

Assert-DeviceSmokeQnnChatContract -Json $completed
$selected = Get-DeviceSmokeLatestNativeStats -Json $completed
Assert-Equal -Actual $selected.completionTokens -Expected 8 -Message 'Terminal completion token count must be selected'
Assert-Equal -Actual $selected.decodeTps -Expected 21.35 -Message 'Terminal throughput must be selected'

$missingTerminalStats = [pscustomobject]@{
    status = 'completed'
    events = @(
        [pscustomobject]@{
            status = 'first_load_ok'
            nativeStats = (New-QnnStats -CompletionTokens 0 -DecodeTps 0.0)
        },
        [pscustomobject]@{
            status = 'api_engine_stream_ok'
            apiEngine = [pscustomobject]@{ visibleSeen = $true }
        },
        [pscustomobject]@{ status = 'completed' }
    )
}

Assert-Null -Actual (Get-DeviceSmokeLatestNativeStats -Json $missingTerminalStats) -Message 'Load-stage stats must not be reported as terminal stats'
Assert-Throws -Action { Assert-DeviceSmokeQnnChatContract -Json $missingTerminalStats } -ExpectedFragment 'nativeStats.backend' -Message 'QNN contract must reject completed output without terminal stats'

$failed = [pscustomobject]@{
    status = 'failed'
    events = @(
        [pscustomobject]@{
            status = 'first_load_ok'
            nativeStats = (New-QnnStats -CompletionTokens 0 -DecodeTps 0.0)
        }
    )
}
Assert-Null -Actual (Get-DeviceSmokeLatestNativeStats -Json $failed) -Message 'Failed output must not expose load-stage stats'

$running = [pscustomobject]@{
    status = 'runner_stage'
    events = @(
        [pscustomobject]@{
            status = 'first_load_ok'
            nativeStats = (New-QnnStats -CompletionTokens 0 -DecodeTps 0.0)
        }
    )
}
Assert-Null -Actual (Get-DeviceSmokeLatestNativeStats -Json $running) -Message 'Nonterminal output must not expose load-stage stats'

$nullEvents = [pscustomobject]@{
    status = 'completed'
    events = @($null)
}
Assert-Null -Actual (Get-DeviceSmokeLatestNativeStats -Json $nullEvents) -Message 'Null events must not throw or fabricate stats'

$dryRun = [pscustomobject]@{
    status = 'completed'
    events = @(
        [pscustomobject]@{ status = 'qairt_dry_run_start' }
        [pscustomobject]@{ status = 'qairt_dry_run_load_ok'; nativeStats = (New-QnnStats -CompletionTokens 0 -DecodeTps 0.0) }
        [pscustomobject]@{ status = 'qairt_dry_run_npu_evidence_ok'; nativeStats = (New-QnnStats -CompletionTokens 0 -DecodeTps 0.0) }
        [pscustomobject]@{ status = 'qairt_dry_run_generation_ok'; generation = [pscustomobject]@{ text = 'QAIRT dry run visible text' } }
        [pscustomobject]@{ status = 'qairt_dry_run_destroy_ok'; nativeStats = [pscustomobject]@{ backend = 'geniex_qairt'; loaded = $false; lastError = '' } }
        [pscustomobject]@{ status = 'qairt_dry_run_verified' }
        [pscustomobject]@{ status = 'completed' }
    )
}
Assert-DeviceSmokeQairtDryRunContract -Json $dryRun

Write-Host 'PASS: QNN chat contract requires terminal nativeStats.'
