param(
    [string]$Adb = 'adb',
    [string]$Serial = '',
    [string]$Package = 'com.muyuchat.mca',
    [Parameter(Mandatory = $true)]
    [string]$ModelPath,
    [string]$PromptFile = '',
    [string]$DisplayName = '',
    [string]$Prompt = 'Reply with the exact words: LiteRT-LM Qualcomm NPU smoke passed.',
    [string]$SystemPrompt = 'You are an MCA LiteRT-LM smoke test. Answer briefly.',
    [string]$FollowUpPrompt = 'Reply with only the word cached.',
    [ValidateSet('full', 'api_only', 'litertlm_cache_ab')]
    [string]$SmokeMode = 'full',
    [ValidateSet('cpu', 'gpu', 'npu')]
    [string]$Backend = 'npu',
    [ValidateRange(1, 2147483647)]
    [int]$Runs = 1,
    [ValidateRange(1, 2147483647)]
    # LiteRT-LM compiled Gemma packages commonly require the 4096-token
    # signature.  A smaller value can make Engine.invoke fail with Status 13
    # even when the model and backend are otherwise healthy.
    [int]$ContextTokens = 4096,
    [ValidateRange(1, 1024)]
    [int]$Threads = 4,
    [ValidateRange(1, 2147483647)]
    [int]$MaxTokens = 32,
    [ValidateSet('reuse', 'cold')]
    [string]$Lifecycle = 'cold',
    [string]$OutDir = 'docs\experiments\device-smoke\litertlm-chat',
    [ValidateRange(1, 86400)]
    [int]$TimeoutSeconds = 900,
    [ValidateRange(100, 60000)]
    [int]$PollMilliseconds = 1000,
    [string]$SessionId = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Import-Module (Join-Path $scriptDir 'DeviceSmoke.psm1') -Force

if ($ModelPath.IndexOf([char]0) -ge 0 -or -not $ModelPath.StartsWith('/')) {
    throw 'ModelPath must be an absolute Android path without NUL characters.'
}
if (-not $ModelPath.EndsWith('.litertlm', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'ModelPath must point to a .litertlm model file.'
}
if ($Prompt.IndexOf([char]0) -ge 0 -or $Prompt.Contains("`r") -or $Prompt.Contains("`n")) {
    throw 'Prompt must not contain NUL characters or newlines.'
}
if ($SystemPrompt.IndexOf([char]0) -ge 0 -or $SystemPrompt.Contains("`r") -or $SystemPrompt.Contains("`n")) {
    throw 'SystemPrompt must not contain NUL characters or newlines.'
}
if ($FollowUpPrompt.IndexOf([char]0) -ge 0 -or $FollowUpPrompt.Contains("`r") -or $FollowUpPrompt.Contains("`n")) {
    throw 'FollowUpPrompt must not contain NUL characters or newlines.'
}
if (-not [string]::IsNullOrWhiteSpace($PromptFile)) {
    if (-not (Test-Path -LiteralPath $PromptFile -PathType Leaf)) {
        throw "PromptFile does not exist: $PromptFile"
    }
    $promptFileBytes = (Get-Item -LiteralPath $PromptFile).Length
    if ($promptFileBytes -lt 1 -or $promptFileBytes -gt 524288) {
        throw 'PromptFile must contain 1..524288 bytes.'
    }
}
if ([string]::IsNullOrWhiteSpace($DisplayName)) {
    $DisplayName = Split-Path -Leaf $ModelPath.TrimEnd('/')
}
if ([string]::IsNullOrWhiteSpace($DisplayName)) { $DisplayName = 'LiteRT-LM Qualcomm NPU model' }
if ([string]::IsNullOrWhiteSpace($SessionId)) { $SessionId = New-DeviceSmokeSessionId -Prefix 'litertlm-chat' }
$SessionId = Get-DeviceSmokeSafeName -Value $SessionId

# The JSON travels through the normal profile resolver, while the explicit
# intent extras make the requested execution unit unambiguous.
$advancedJson = '{"backend":"' + $Backend + '"}'
$component = "$Package/.debug.LocalChatSmokeActivity"
$serial = Initialize-DeviceSmokeDevice -Adb $Adb -Serial $Serial
Assert-DeviceSmokePackageInstalled -Adb $Adb -Serial $serial -Package $Package
Assert-DeviceSmokeActivityAvailable -Adb $Adb -Serial $serial -Component $component

$runOutputDir = Join-Path $OutDir $SessionId
New-Item -ItemType Directory -Force -Path $runOutputDir | Out-Null
$externalRoot = "/storage/emulated/0/Android/data/$Package/files"
$remotePromptFile = ''
if (-not [string]::IsNullOrWhiteSpace($PromptFile)) {
    $remotePromptFile = "$externalRoot/chat_smoke/inputs/$SessionId-prompt.txt"
    & $Adb -s $serial shell mkdir -p "$externalRoot/chat_smoke/inputs" | Out-Null
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $pushOutput = & $Adb -s $serial push $PromptFile $remotePromptFile 2>&1
        $pushExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($pushExitCode -ne 0) {
        throw "Unable to push PromptFile: $($pushOutput -join ' ')"
    }
}
$safeModel = Get-DeviceSmokeSafeName -Value $DisplayName
$summaries = @()

Write-Host "Device: $serial"
Write-Host "Model: $ModelPath"
Write-Host "Runtime: litert_lm ($Backend)"
Write-Host "Smoke mode: $SmokeMode"
Write-Host "Output: $runOutputDir"

for ($run = 1; $run -le $Runs; $run++) {
    $runId = "$safeModel-litertlm-$SessionId-r$run"
    $remoteJson = "$externalRoot/chat_smoke/runs/$runId.json"
    $localJson = Join-Path $runOutputDir "$runId.json"
    $result = $null
    $contractError = $null
    try {
        $activityArguments = @(
            'am', 'start', '-W', '-n', $component,
            '--es', 'runtime', 'litert_lm',
            '--es', 'computeUnit', $Backend,
            '--es', 'backend', $Backend,
            '--es', 'advancedJson', $advancedJson,
            '--es', 'modelPath', $ModelPath,
            '--es', 'displayName', $DisplayName,
            '--es', 'followUpPrompt', $FollowUpPrompt,
            '--es', 'systemPrompt', $SystemPrompt,
            '--es', 'runId', $runId,
            '--es', 'smokeMode', $SmokeMode,
            '--ei', 'nCtx', [string]$ContextTokens,
            '--ei', 'nThreads', [string]$Threads,
            '--ei', 'maxTokens', [string]$MaxTokens
        )
        if ([string]::IsNullOrWhiteSpace($remotePromptFile)) {
            $activityArguments += @('--es', 'prompt', $Prompt)
        } else {
            $activityArguments += @('--es', 'promptPath', $remotePromptFile)
        }
        $result = Invoke-DeviceSmokeActivityRun `
            -Adb $Adb -Serial $serial -Package $Package -Lifecycle $Lifecycle `
            -ActivityArguments $activityArguments -RemoteJson $remoteJson -LocalJson $localJson `
            -ExpectedRunId $runId -TimeoutSeconds $TimeoutSeconds -PollMilliseconds $PollMilliseconds
        if ($result.status -eq 'completed') {
            Assert-DeviceSmokeLiteRtLmChatContract -Json $result.json -SmokeMode $SmokeMode -Backend $Backend | Out-Null
        }
    } catch {
        $contractError = $_.Exception.Message
        if ($null -eq $result) {
            $result = [pscustomobject]@{
                runId = $runId; status = 'tool_failed'; failureKind = 'tool_failed'; error = $contractError
                waitOutcome = $null; remoteJson = $remoteJson; localJson = $localJson; rawResultPreserved = $false; json = $null
            }
        } elseif ($result.status -eq 'completed') {
            $result.status = 'contract_failed'
            $result.failureKind = 'contract_failed'
            $result.error = $contractError
        }
    }
    $nativeStats = if ($null -ne $result.json) {
        Get-DeviceSmokeLatestNativeStats -Json $result.json
    } else {
        $null
    }
    $summaries += [pscustomobject][ordered]@{
        run = $run
        runId = $runId
        status = $result.status
        failureKind = $result.failureKind
        error = $result.error
        contractError = $contractError
        remoteJson = $result.remoteJson
        localJson = $result.localJson
        rawResultPreserved = [bool]$result.rawResultPreserved
        nativeStats = $nativeStats
    }
    Write-Host "[$run/$Runs] status=$($result.status) rawJson=$($result.rawResultPreserved)"
}

$summary = [pscustomobject][ordered]@{
    sessionId = $SessionId
    serial = $serial
    package = $Package
    component = $component
    runtime = 'litert_lm'
    backend = $Backend
    computeUnit = $Backend
    modelPath = $ModelPath
    promptFile = $PromptFile
    displayName = $DisplayName
    prompt = $Prompt
    followUpPrompt = $FollowUpPrompt
    systemPrompt = $SystemPrompt
    smokeMode = $SmokeMode
    advancedJson = $advancedJson
    contextTokens = $ContextTokens
    threads = $Threads
    maxTokens = $MaxTokens
    lifecycle = $Lifecycle
    benchmarkRequired = $true
    runs = @($summaries)
}
$summaryPath = Join-Path $runOutputDir 'summary.json'
Write-DeviceSmokeSessionSummary -Path $summaryPath -Summary $summary
Write-Host "Summary: $summaryPath"

$failed = @($summaries | Where-Object { $_.status -ne 'completed' })
if ($failed.Count -gt 0) {
    throw "$($failed.Count) of $Runs LiteRT-LM $Backend chat smoke run(s) failed."
}
