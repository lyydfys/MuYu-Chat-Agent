param(
    [string]$Adb = 'adb',
    [string]$Serial = '',
    [string]$Package = 'com.muyuchat.mca',
    [Parameter(Mandatory = $true)]
    [string]$BundleRoot,
    [ValidateSet('preflight', 'generate')]
    [string]$Mode = 'preflight',
    [string]$ModelPath = '',
    [string]$Prompt = 'A single red cube on a white background, no text.',
    [ValidateRange(1, 4096)]
    [int]$Width = 512,
    [ValidateRange(1, 4096)]
    [int]$Height = 512,
    [ValidateRange(1, 2147483647)]
    [int]$Steps = 1,
    [ValidateRange(1, 1024)]
    [int]$Threads = 4,
    [int]$Seed = 42,
    [double]$CfgScale = 7.0,
    [double]$DistilledGuidance = 3.5,
    [double]$FlowShift = -1.0,
    [string]$SampleMethod = 'euler',
    [string]$Family = 'SD15',
    [string]$BackendMode = 'cpu',
    [string]$TokenEmbeddingMode = 'auto',
    [ValidateRange(0, 2147483647)]
    [int]$MemoryMode = 0,
    [string]$Runner = 'module',
    [ValidateRange(1, 2147483647)]
    [int]$Runs = 1,
    [ValidateSet('reuse', 'cold')]
    [string]$Lifecycle = 'cold',
    [switch]$WorkerProductPath,
    [ValidateRange(0, 300)]
    [int]$WorkerKillWindowSeconds = 0,
    [ValidateRange(0, 300)]
    [int]$WorkerMainLeaseHoldSeconds = 0,
    [string]$OutDir = 'docs\experiments\device-smoke\mnn-diffusion',
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
$benchmarkValidationModule = Join-Path (Split-Path -Parent $scriptDir) 'benchmarks\benchmark-validation.psm1'
Import-Module $benchmarkValidationModule -Force

function Assert-DeviceSmokeSingleLineArgument {
    param(
        [AllowEmptyString()][string]$Value,
        [string]$Name
    )

    if ($null -eq $Value) { return }
    if ($Value.IndexOf([char]0) -ge 0) {
        throw "$Name must not contain a NUL character."
    }
    if ($Value.Contains("`r") -or $Value.Contains("`n")) {
        throw "$Name must not contain newlines."
    }
}

function ConvertTo-DeviceSmokeInvariantDouble {
    param([double]$Value)

    return $Value.ToString([Globalization.CultureInfo]::InvariantCulture)
}

foreach ($argument in @(
        [pscustomobject]@{ Name = 'BundleRoot'; Value = $BundleRoot },
        [pscustomobject]@{ Name = 'Prompt'; Value = $Prompt },
        [pscustomobject]@{ Name = 'SampleMethod'; Value = $SampleMethod },
        [pscustomobject]@{ Name = 'Family'; Value = $Family },
        [pscustomobject]@{ Name = 'BackendMode'; Value = $BackendMode },
        [pscustomobject]@{ Name = 'TokenEmbeddingMode'; Value = $TokenEmbeddingMode },
        [pscustomobject]@{ Name = 'Runner'; Value = $Runner }
    )) {
    Assert-DeviceSmokeSingleLineArgument -Value $argument.Value -Name $argument.Name
}
if (-not $BundleRoot.StartsWith('/')) {
    throw 'BundleRoot must be an absolute Android path.'
}
if ([string]::IsNullOrWhiteSpace($ModelPath)) {
    $ModelPath = Join-DeviceSmokeRemotePath -Root $BundleRoot -Child 'unet.mnn'
}
Assert-DeviceSmokeSingleLineArgument -Value $ModelPath -Name 'ModelPath'
if (-not $ModelPath.StartsWith('/')) {
    throw 'ModelPath must be an absolute Android path.'
}
if ($WorkerProductPath -and $Mode -ne 'generate') {
    throw 'WorkerProductPath is available only with Mode=generate.'
}
if ($WorkerMainLeaseHoldSeconds -gt 0 -and -not $WorkerProductPath) {
    throw 'WorkerMainLeaseHoldSeconds requires WorkerProductPath.'
}
if ($WorkerKillWindowSeconds -gt 0 -and $WorkerMainLeaseHoldSeconds -gt 0) {
    throw 'WorkerKillWindowSeconds and WorkerMainLeaseHoldSeconds must be tested in separate runs.'
}
if ([string]::IsNullOrWhiteSpace($SessionId)) {
    $SessionId = New-DeviceSmokeSessionId -Prefix "mnn-diffusion-$Mode"
}
$SessionId = Get-DeviceSmokeSafeName -Value $SessionId

$component = "$Package/.debug.LocalImageSmokeActivity"
$serial = Initialize-DeviceSmokeDevice -Adb $Adb -Serial $Serial
Assert-DeviceSmokePackageInstalled -Adb $Adb -Serial $serial -Package $Package
Assert-DeviceSmokeActivityAvailable -Adb $Adb -Serial $serial -Component $component
Assert-DeviceSmokeMnnDiffusionBundle -Adb $Adb -Serial $serial -BundleRoot $BundleRoot
$bundlePreflight = [pscustomobject]@{ passed = $true; bundleRoot = $BundleRoot }

$runOutputDir = Join-Path $OutDir $SessionId
New-Item -ItemType Directory -Force -Path $runOutputDir | Out-Null
$externalRoot = "/storage/emulated/0/Android/data/$Package/files"
$safeBundle = Get-DeviceSmokeSafeName -Value (Split-Path -Leaf $BundleRoot.TrimEnd('/'))
$preflightOnly = $Mode -eq 'preflight'
$requiresPng = $Mode -eq 'generate'
$summaries = @()

Write-Host "Device: $serial"
Write-Host "Bundle: $BundleRoot"
Write-Host "Mode: $Mode"
Write-Host "Worker product path: $([bool]$WorkerProductPath)"
Write-Host "Output: $runOutputDir"
if ($WorkerProductPath -and $WorkerKillWindowSeconds -gt 0) {
    Write-Host "Worker kill window: $WorkerKillWindowSeconds second(s). In another terminal, query: $Adb -s $serial shell pidof '${Package}:local_image'"
}
if ($WorkerMainLeaseHoldSeconds -gt 0) {
    Write-Host "Main native lease hold: $WorkerMainLeaseHoldSeconds second(s); worker must report waiting_for_native_lease."
}

for ($run = 1; $run -le $Runs; $run++) {
    $runId = "$safeBundle-mnn-diffusion-$Mode-$SessionId-r$run"
    $remoteJson = "$externalRoot/image_bench/runs/$runId.json"
    $remotePng = "$externalRoot/image_bench/runs/$runId.png"
    $localJson = Join-Path $runOutputDir "$runId.json"
    $localPng = Join-Path $runOutputDir "$runId.png"
    $result = $null
    $contractError = $null
    $pngEvidence = [pscustomobject]@{ Preserved = $false; Error = $null; LocalPng = $localPng }
    $pngQuality = $null
    try {
        $activityArguments = @(
            'am', 'start', '-W', '-n', $component,
            '--es', 'runtime', 'mnn_diffusion',
            '--es', 'bundleRoot', $BundleRoot,
            '--es', 'modelPath', $ModelPath,
            '--es', 'prompt', $Prompt,
            '--es', 'runId', $runId,
            '--ei', 'width', [string]$Width,
            '--ei', 'height', [string]$Height,
            '--ei', 'steps', [string]$Steps,
            '--ei', 'threads', [string]$Threads,
            '--ei', 'seed', [string]$Seed,
            '--ef', 'cfgScale', (ConvertTo-DeviceSmokeInvariantDouble -Value $CfgScale),
            '--ef', 'distilledGuidance', (ConvertTo-DeviceSmokeInvariantDouble -Value $DistilledGuidance),
            '--ef', 'flowShift', (ConvertTo-DeviceSmokeInvariantDouble -Value $FlowShift),
            '--es', 'sampleMethod', $SampleMethod,
            '--es', 'family', $Family,
            '--es', 'backendMode', $BackendMode,
            '--es', 'tokenEmbeddingMode', $TokenEmbeddingMode,
            '--ei', 'memoryMode', [string]$MemoryMode,
            '--es', 'runner', $Runner,
            '--ez', 'directUnetSmoke', ([string](-not $WorkerProductPath)).ToLowerInvariant(),
            '--ez', 'preflightOnly', ([string]$preflightOnly).ToLowerInvariant(),
            '--ez', 'pipelineProbe', 'false',
            '--ez', 'semanticGenerate', 'false',
            '--ez', 'workerProductPath', ([string][bool]$WorkerProductPath).ToLowerInvariant(),
            '--el', 'workerStartPauseMs', [string]($WorkerKillWindowSeconds * 1000),
            '--el', 'workerMainLeaseHoldMs', [string]($WorkerMainLeaseHoldSeconds * 1000)
        )
        $result = Invoke-DeviceSmokeActivityRun `
            -Adb $Adb -Serial $serial -Package $Package -Lifecycle $Lifecycle `
            -ActivityArguments $activityArguments -RemoteJson $remoteJson -LocalJson $localJson `
            -ExpectedRunId $runId -TimeoutSeconds $TimeoutSeconds -PollMilliseconds $PollMilliseconds `
            -RemotePng $(if ($requiresPng) { $remotePng } else { '' }) `
            -IgnoreChildProcessExit:$WorkerProductPath
        if ($requiresPng) {
            $pngEvidence = Pull-DeviceSmokeRemotePng -Adb $Adb -Serial $serial -RemotePng $remotePng -LocalPng $localPng
        }
        if ($result.status -eq 'completed') {
            Assert-DeviceSmokeMnnDiffusionContract -Json $result.json -Mode $Mode
            if ($WorkerProductPath) {
                Assert-DeviceSmokeWorkerIsolationContract `
                    -Json $result.json `
                    -RequireMainLeaseWait:($WorkerMainLeaseHoldSeconds -gt 0)
            }
            if ($requiresPng -and -not $pngEvidence.Preserved) {
                throw "MNN diffusion generation requires a nonempty output PNG: $($pngEvidence.Error)"
            }
            if ($requiresPng) {
                $pngQuality = Assert-PngQuality -Path $pngEvidence.LocalPng
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
    $mainPidAfterRun = ''
    if ($WorkerProductPath) {
        try {
            $mainPidAfterRun = ((& $Adb -s $serial shell pidof $Package 2>$null) | Out-String).Trim()
        } catch {
            $mainPidAfterRun = ''
        }
        Write-Host "[$run/$Runs] main process after worker run: $(if ($mainPidAfterRun) { $mainPidAfterRun } else { 'not running' })"
        if ([string]::IsNullOrWhiteSpace($mainPidAfterRun)) {
            $mainAliveError = 'Main application process was not alive after the worker run.'
            $contractError = if ([string]::IsNullOrWhiteSpace($contractError)) {
                $mainAliveError
            } else {
                "$contractError; $mainAliveError"
            }
            if ($result.status -eq 'completed') {
                $result.status = 'contract_failed'
                $result.failureKind = 'contract_failed'
                $result.error = $mainAliveError
            }
        }
    }
    $mainLeaseHeldEvidence = $false
    $workerWaitedForNativeLeaseEvidence = $false
    if ($null -ne $result.json) {
        $mainLeaseProperty = $result.json.PSObject.Properties['mainLeaseHeld']
        $workerWaitProperty = $result.json.PSObject.Properties['workerWaitedForNativeLease']
        if ($null -ne $mainLeaseProperty) { $mainLeaseHeldEvidence = [bool]$mainLeaseProperty.Value }
        if ($null -ne $workerWaitProperty) { $workerWaitedForNativeLeaseEvidence = [bool]$workerWaitProperty.Value }
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
        remotePng = if ($requiresPng) { $remotePng } else { $null }
        localPng = if ($requiresPng) { $pngEvidence.LocalPng } else { $null }
        pngPreserved = if ($requiresPng) { [bool]$pngEvidence.Preserved } else { $false }
        pngError = if ($requiresPng) { $pngEvidence.Error } else { $null }
        pngQuality = $pngQuality
        mainPidAfterRun = if ($WorkerProductPath) { $mainPidAfterRun } else { $null }
        mainProcessAliveAfterRun = if ($WorkerProductPath) { -not [string]::IsNullOrWhiteSpace($mainPidAfterRun) } else { $null }
        mainLeaseHeld = $mainLeaseHeldEvidence
        workerWaitedForNativeLease = $workerWaitedForNativeLeaseEvidence
    }
    Write-Host "[$run/$Runs] status=$($result.status) rawJson=$($result.rawResultPreserved) png=$($pngEvidence.Preserved)"
}

$summary = [pscustomobject][ordered]@{
    sessionId = $SessionId
    serial = $serial
    package = $Package
    component = $component
    runtime = 'mnn_diffusion'
    mode = $Mode
    bundleRoot = $BundleRoot
    modelPath = $ModelPath
    prompt = $Prompt
    width = $Width
    height = $Height
    steps = $Steps
    threads = $Threads
    seed = $Seed
    cfgScale = $CfgScale
    distilledGuidance = $DistilledGuidance
    flowShift = $FlowShift
    sampleMethod = $SampleMethod
    family = $Family
    backendMode = $BackendMode
    tokenEmbeddingMode = $TokenEmbeddingMode
    memoryMode = $MemoryMode
    runner = $Runner
    directUnetSmoke = [bool](-not $WorkerProductPath)
    preflightOnly = $preflightOnly
    workerProductPath = [bool]$WorkerProductPath
    workerKillWindowSeconds = $WorkerKillWindowSeconds
    workerMainLeaseHoldSeconds = $WorkerMainLeaseHoldSeconds
    lifecycle = $Lifecycle
    requiresPng = $requiresPng
    bundlePreflight = $bundlePreflight
    runs = @($summaries)
}
$summaryPath = Join-Path $runOutputDir 'summary.json'
Write-DeviceSmokeSessionSummary -Path $summaryPath -Summary $summary
Write-Host "Summary: $summaryPath"

$failed = @($summaries | Where-Object { $_.status -ne 'completed' })
if ($failed.Count -gt 0) {
    throw "$($failed.Count) of $Runs MNN diffusion smoke run(s) failed."
}
