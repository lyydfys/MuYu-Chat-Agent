param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$testDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$deviceDir = Split-Path -Parent $testDir
$smokeScript = Join-Path $deviceDir 'run-litertlm-chat-smoke.ps1'
Import-Module (Join-Path $deviceDir 'DeviceSmoke.psm1') -Force

function Assert-Equal {
    param($Actual, $Expected, [string]$Message)
    if ($Actual -ne $Expected) {
        throw "$Message (expected=$Expected actual=$Actual)"
    }
}

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
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

function Assert-NoParseErrors {
    param([string]$Path)
    $tokens = $null
    $errors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile($Path, [ref]$tokens, [ref]$errors)
    if ($errors.Count -gt 0) {
        throw ('PowerShell parser rejected {0}: {1}' -f $Path, (@($errors | ForEach-Object Message) -join '; '))
    }
}

Assert-NoParseErrors -Path $smokeScript
$source = Get-Content -LiteralPath $smokeScript -Raw -Encoding UTF8
Assert-True -Condition $source.Contains("'--es', 'runtime', 'litert_lm'") -Message 'Smoke script must select litert_lm runtime.'
Assert-True -Condition $source.Contains("'--es', 'computeUnit', `$Backend") -Message 'Smoke script must pass the selected compute unit.'
Assert-True -Condition $source.Contains("'--es', 'backend', `$Backend") -Message 'Smoke script must pass the selected backend.'
Assert-True -Condition $source.Contains("'--es', 'advancedJson', `$advancedJson") -Message 'Smoke script must carry the backend through advancedJson.'
Assert-True -Condition $source.Contains("EndsWith('.litertlm'") -Message 'Smoke script must enforce the .litertlm model extension.'
Assert-True -Condition $source.Contains('rawResultPreserved') -Message 'Smoke script must preserve raw activity results.'

function New-LiteRtStats {
    return [pscustomobject][ordered]@{
        backend = 'litert_lm'
        backendMode = 'npu'
        backendDevices = 'LiteRT-LM Qualcomm NPU'
        loaded = $true
        runnerReady = $true
        prefillTokens = 96
        completionTokens = 12
        prefillTps = 812.5
        decodeTps = 28.4
        benchmarkEnabled = $true
        effectiveConfig = [pscustomobject]@{
            backend = 'npu'
            max_num_tokens = 1024
            n_threads = 4
        }
    }
}

$valid = [pscustomobject]@{
    status = 'completed'
    nativeStats = New-LiteRtStats
    events = @(
        [pscustomobject]@{
            status = 'litert_lm_runtime_stage'
            ok = $true
            variant = 'v79'
            directory = '/data/user/0/com.muyuchat.mca/code_cache/litert-qualcomm-runtime/abc'
        }
        [pscustomobject]@{ status = 'runner_stage'; stage = 'litert_lm_load_ok' }
        [pscustomobject]@{
            status = 'generation_ok'
            generation = [pscustomobject]@{ text = 'LiteRT-LM smoke passed.'; textPreview = 'LiteRT-LM smoke passed.' }
        }
        [pscustomobject]@{
            status = 'api_engine_stream_ok'
            apiEngine = [pscustomobject]@{ visibleSeen = $true }
        }
    )
}

$selected = Assert-DeviceSmokeLiteRtLmChatContract -Json $valid -SmokeMode 'full'
Assert-Equal -Actual $selected.backend -Expected 'litert_lm' -Message 'LiteRT-LM contract must return terminal stats.'
Assert-Equal -Actual $selected.backendMode -Expected 'npu' -Message 'LiteRT-LM contract must retain NPU backend mode.'

$wrongBackend = [pscustomobject]($valid | Select-Object *)
$wrongBackend.nativeStats = New-LiteRtStats
$wrongBackend.nativeStats.backendMode = 'cpu'
Assert-Throws -Action { Assert-DeviceSmokeLiteRtLmChatContract -Json $wrongBackend } `
    -ExpectedFragment 'backendMode' -Message 'LiteRT-LM contract must reject CPU fallback.'

$missingBenchmark = [pscustomobject]($valid | Select-Object *)
$missingBenchmark.nativeStats = New-LiteRtStats
$missingBenchmark.nativeStats.benchmarkEnabled = $false
Assert-Throws -Action { Assert-DeviceSmokeLiteRtLmChatContract -Json $missingBenchmark } `
    -ExpectedFragment 'benchmarkEnabled' -Message 'LiteRT-LM contract must require benchmark evidence.'

$missingGeneration = [pscustomobject]($valid | Select-Object *)
$missingGeneration.nativeStats = New-LiteRtStats
$missingGeneration.events = @(
    [pscustomobject]@{ status = 'runner_stage'; stage = 'litert_lm_load_ok' }
    [pscustomobject]@{ status = 'api_engine_stream_ok'; apiEngine = [pscustomobject]@{ visibleSeen = $true } }
)
Assert-Throws -Action { Assert-DeviceSmokeLiteRtLmChatContract -Json $missingGeneration } `
    -ExpectedFragment 'generation_ok' -Message 'LiteRT-LM contract must require generated text.'

$missingStage = [pscustomobject]($valid | Select-Object *)
$missingStage.nativeStats = New-LiteRtStats
$missingStage.events = @(
    [pscustomobject]@{ status = 'generation_ok'; generation = [pscustomobject]@{ textPreview = 'ok' } }
    [pscustomobject]@{ status = 'api_engine_stream_ok'; apiEngine = [pscustomobject]@{ visibleSeen = $true } }
)
Assert-Throws -Action { Assert-DeviceSmokeLiteRtLmChatContract -Json $missingStage } `
    -ExpectedFragment 'litert_lm_load_ok' -Message 'LiteRT-LM contract must require native load evidence.'

$apiOnly = [pscustomobject]@{
    status = 'completed'
    nativeStats = New-LiteRtStats
    events = @(
        [pscustomobject]@{
            status = 'litert_lm_runtime_stage'
            ok = $true
            variant = 'v79'
            directory = '/data/user/0/com.muyuchat.mca/code_cache/litert-qualcomm-runtime/abc'
        }
        [pscustomobject]@{ status = 'runner_stage'; stage = 'litert_lm_load_ok' }
        [pscustomobject]@{ status = 'api_engine_stream_ok'; apiEngine = [pscustomobject]@{ visibleSeen = $true } }
    )
}
Assert-DeviceSmokeLiteRtLmChatContract -Json $apiOnly -SmokeMode 'api_only' | Out-Null

$cache = [pscustomobject]@{
    status = 'completed'
    nativeStats = New-LiteRtStats
    events = @(
        [pscustomobject]@{ status = 'litert_lm_runtime_stage'; ok = $true; variant = 'v79'; directory = '/data/user/0/com.muyuchat.mca/code_cache/litert-qualcomm-runtime/abc' }
        [pscustomobject]@{ status = 'runner_stage'; stage = 'litert_lm_load_ok' }
        [pscustomobject]@{ status = 'litertlm_cache_first_ok'; generation = [pscustomobject]@{ text = 'first' } }
        [pscustomobject]@{ status = 'litertlm_cache_ab_ok'; cacheHit = $true; firstKvCacheTokens = 100; secondKvCacheTokens = 130 }
        [pscustomobject]@{ status = 'api_engine_stream_ok'; apiEngine = [pscustomobject]@{ visibleSeen = $true } }
    )
}
Assert-DeviceSmokeLiteRtLmChatContract -Json $cache -SmokeMode 'litertlm_cache_ab' | Out-Null

Write-Host 'PASS: LiteRT-LM Qualcomm smoke parser and terminal NPU/benchmark contract are enforced.'
