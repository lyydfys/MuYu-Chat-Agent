param(
    [string]$Adb = 'adb',
    [string]$Serial = '',
    [string]$Package = 'com.muyuchat.mca',
    [Parameter(Mandatory = $true)]
    [string]$ModelPath,
    [string]$DisplayName = '',
    [string]$Prompt = 'Reply with the exact words: QNN smoke passed.',
    [ValidateRange(1, 2147483647)]
    [int]$Runs = 1,
    [ValidateRange(1, 2147483647)]
    [int]$ContextTokens = 1024,
    [ValidateRange(1, 1024)]
    [int]$Threads = 4,
    [ValidateRange(1, 2147483647)]
    [int]$MaxTokens = 32,
    [ValidateSet('reuse', 'cold')]
    [string]$Lifecycle = 'cold',
    [switch]$IsolatedDryRun,
    [string]$OutDir = 'docs\experiments\device-smoke\qnn-chat',
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
if ($Prompt.IndexOf([char]0) -ge 0) { throw 'Prompt must not contain a NUL character.' }
if ([string]::IsNullOrWhiteSpace($DisplayName)) {
    $DisplayName = Split-Path -Leaf $ModelPath.TrimEnd('/')
}
if ([string]::IsNullOrWhiteSpace($DisplayName)) { $DisplayName = 'QAIRT chat bundle' }
if ([string]::IsNullOrWhiteSpace($SessionId)) { $SessionId = New-DeviceSmokeSessionId -Prefix 'qnn-chat' }
$SessionId = Get-DeviceSmokeSafeName -Value $SessionId

$smokeMode = if ($IsolatedDryRun) { 'qairt_dry_run' } else { 'api_only' }
$component = if ($IsolatedDryRun) {
    "$Package/.debug.QairtDryRunSmokeActivity"
} else {
    "$Package/.debug.LocalChatSmokeActivity"
}
$serial = Initialize-DeviceSmokeDevice -Adb $Adb -Serial $Serial
Assert-DeviceSmokePackageInstalled -Adb $Adb -Serial $serial -Package $Package
Assert-DeviceSmokeActivityAvailable -Adb $Adb -Serial $serial -Component $component
$resolvedModelPath = Resolve-DeviceSmokeQairtChatBundleRoot -Adb $Adb -Serial $serial -ModelPath $ModelPath
$wasModelPathResolved = $resolvedModelPath -cne $ModelPath
$ModelPath = $resolvedModelPath
Assert-DeviceSmokeQairtChatBundle -Adb $Adb -Serial $serial -ModelPath $ModelPath

$runOutputDir = Join-Path $OutDir $SessionId
New-Item -ItemType Directory -Force -Path $runOutputDir | Out-Null
$externalRoot = "/storage/emulated/0/Android/data/$Package/files"
$safeModel = Get-DeviceSmokeSafeName -Value $DisplayName
$summaries = @()

Write-Host "Device: $serial"
Write-Host "Model: $ModelPath"
if ($wasModelPathResolved) { Write-Host 'Model root was resolved from the supplied outer directory.' }
Write-Host "Smoke mode: $smokeMode"
Write-Host "Output: $runOutputDir"

for ($run = 1; $run -le $Runs; $run++) {
    $runId = "$safeModel-qnn-$SessionId-r$run"
    $remoteJson = "$externalRoot/chat_smoke/runs/$runId.json"
    $localJson = Join-Path $runOutputDir "$runId.json"
    $result = $null
    $contractError = $null
    try {
        $activityArguments = @(
            'am', 'start', '-W', '-n', $component,
            '--es', 'runtime', 'geniex_qairt',
            '--es', 'modelPath', $ModelPath,
            '--es', 'displayName', $DisplayName,
            '--es', 'prompt', $Prompt,
            '--es', 'runId', $runId,
            '--es', 'smokeMode', $smokeMode,
            '--es', 'computeUnit', 'npu',
            '--ei', 'nCtx', [string]$ContextTokens,
            '--ei', 'nThreads', [string]$Threads,
            '--ei', 'maxTokens', [string]$MaxTokens
        )
        $result = Invoke-DeviceSmokeActivityRun `
            -Adb $Adb -Serial $serial -Package $Package -Lifecycle $Lifecycle `
            -ActivityArguments $activityArguments -RemoteJson $remoteJson -LocalJson $localJson `
            -ExpectedRunId $runId -TimeoutSeconds $TimeoutSeconds -PollMilliseconds $PollMilliseconds
        if ($result.status -eq 'completed') {
            if ($IsolatedDryRun) {
                Assert-DeviceSmokeQairtDryRunContract -Json $result.json
            } else {
                Assert-DeviceSmokeQnnChatContract -Json $result.json
            }
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
    runtime = 'geniex_qairt'
    modelPath = $ModelPath
    displayName = $DisplayName
    prompt = $Prompt
    smokeMode = $smokeMode
    isolatedDryRun = [bool]$IsolatedDryRun
    computeUnit = 'npu'
    contextTokens = $ContextTokens
    threads = $Threads
    maxTokens = $MaxTokens
    lifecycle = $Lifecycle
    runs = @($summaries)
}
$summaryPath = Join-Path $runOutputDir 'summary.json'
Write-DeviceSmokeSessionSummary -Path $summaryPath -Summary $summary
Write-Host "Summary: $summaryPath"

$failed = @($summaries | Where-Object { $_.status -ne 'completed' })
if ($failed.Count -gt 0) {
    throw "$($failed.Count) of $Runs QNN chat smoke run(s) failed."
}
