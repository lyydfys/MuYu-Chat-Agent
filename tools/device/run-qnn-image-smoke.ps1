param(
    [string]$Adb = 'adb',
    [string]$Serial = '',
    [string]$Package = 'com.muyuchat.mca',
    [Parameter(Mandatory = $true)]
    [string]$BundleRoot,
    [ValidateSet('graph', 'pipeline', 'semantic')]
    [string]$Mode = 'graph',
    [string]$ModelPath = '',
    [string]$Prompt = 'A single red cube on a white background, no text.',
    [AllowEmptyString()]
    [string]$NegativePrompt = '',
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
    [Nullable[double]]$DistilledGuidance = $null,
    [Nullable[double]]$FlowShift = $null,
    [string]$SampleMethod = 'pndm',
    [string]$Family = 'SD15',
    [string]$BackendMode = 'cpu',
    [string]$TokenEmbeddingMode = 'auto',
    [ValidateRange(0, 2147483647)]
    [int]$MemoryMode = 0,
    [AllowEmptyString()]
    [string]$Runner = '',
    [AllowEmptyString()]
    [string]$RuntimeDirsJson = '',
    [ValidateRange(1, 2147483647)]
    [int]$Runs = 1,
    [ValidateSet('reuse', 'cold')]
    [string]$Lifecycle = 'cold',
    [switch]$WorkerProductPath,
    [ValidateRange(0, 300)]
    [int]$WorkerKillWindowSeconds = 0,
    [ValidateRange(0, 300)]
    [int]$WorkerMainLeaseHoldSeconds = 0,
    [string]$OutDir = 'docs\experiments\device-smoke\qnn-image',
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

function Select-DeviceSmokeFirstPositiveHtpArch {
    param([object[]]$Candidates)

    foreach ($candidate in $Candidates) {
        if ($null -eq $candidate) { continue }
        try {
            $value = [Convert]::ToInt32($candidate, [Globalization.CultureInfo]::InvariantCulture)
        } catch {
            continue
        }
        if ($value -gt 0) { return $value }
    }
    return $null
}

function Resolve-DeviceSmokeQnnSemanticSteps {
    <#
        The native PNDM implementation needs four inference steps to build its
        PRK warm-up schedule. Keep graph/pipeline smoke runs at their cheap
        one-step default, but make an omitted semantic step count safe and
        reject an explicit impossible request before starting an adb run.
    #>
    param(
        [ValidateSet('graph', 'pipeline', 'semantic')]
        [string]$Mode,
        [ValidateRange(1, 2147483647)]
        [int]$Steps,
        [AllowEmptyString()]
        [string]$SampleMethod,
        [bool]$StepsWasExplicit
    )

    $normalizedSampleMethod = $SampleMethod.Trim().ToLowerInvariant()
    $normalizedSampleMethod = $normalizedSampleMethod.Replace('-', '_').Replace(' ', '_')
    $isPndm = $normalizedSampleMethod -in @('pndm', 'pndm_plms', 'plms')
    if ($Mode -eq 'semantic' -and $isPndm) {
        if (-not $StepsWasExplicit) {
            return 4
        }
        if ($Steps -lt 4) {
            throw 'QNN semantic mode with PNDM/PLMS requires at least four steps; use -Steps 4 or higher, or choose an Euler sampler.'
        }
    }
    return $Steps
}

function Assert-DeviceSmokeRuntimeDirsJson {
    param([AllowEmptyString()][string]$Value)

    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    Assert-DeviceSmokeSingleLineArgument -Value $Value -Name 'RuntimeDirsJson'
    $trimmed = $Value.Trim()
    if (-not ($trimmed.StartsWith('[') -and $trimmed.EndsWith(']'))) {
        throw 'RuntimeDirsJson must be a one-line JSON array of absolute Android directories.'
    }
    try {
        $dirs = ConvertFrom-Json -InputObject $trimmed -ErrorAction Stop
    } catch {
        throw "RuntimeDirsJson is not valid JSON: $($_.Exception.Message)"
    }
    if ($trimmed -match '^\[\s*\]$') { return }
    foreach ($directory in @($dirs)) {
        if ($directory -isnot [string] -or -not $directory.StartsWith('/')) {
            throw 'RuntimeDirsJson entries must be absolute Android directory paths.'
        }
        Assert-DeviceSmokeSingleLineArgument -Value $directory -Name 'RuntimeDirsJson entry'
    }
}

foreach ($argument in @(
        [pscustomobject]@{ Name = 'BundleRoot'; Value = $BundleRoot },
        [pscustomobject]@{ Name = 'Prompt'; Value = $Prompt },
        [pscustomobject]@{ Name = 'NegativePrompt'; Value = $NegativePrompt },
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
    $ModelPath = Join-DeviceSmokeRemotePath -Root $BundleRoot -Child 'manifest.json'
}
Assert-DeviceSmokeSingleLineArgument -Value $ModelPath -Name 'ModelPath'
if (-not $ModelPath.StartsWith('/')) {
    throw 'ModelPath must be an absolute Android path.'
}
Assert-DeviceSmokeRuntimeDirsJson -Value $RuntimeDirsJson
$Steps = Resolve-DeviceSmokeQnnSemanticSteps `
    -Mode $Mode `
    -Steps $Steps `
    -SampleMethod $SampleMethod `
    -StepsWasExplicit $PSBoundParameters.ContainsKey('Steps')
if ($WorkerProductPath -and $Mode -ne 'semantic') {
    throw 'WorkerProductPath is available only with Mode=semantic.'
}
if ($WorkerMainLeaseHoldSeconds -gt 0 -and -not $WorkerProductPath) {
    throw 'WorkerMainLeaseHoldSeconds requires WorkerProductPath.'
}
if ($WorkerKillWindowSeconds -gt 0 -and $WorkerMainLeaseHoldSeconds -gt 0) {
    throw 'WorkerKillWindowSeconds and WorkerMainLeaseHoldSeconds must be tested in separate runs.'
}
if ($PSBoundParameters.ContainsKey('DistilledGuidance') -or $PSBoundParameters.ContainsKey('FlowShift')) {
    throw 'QNN image graphs do not support DistilledGuidance or FlowShift. Those controls are stable-diffusion.cpp-only.'
}
if ([string]::IsNullOrWhiteSpace($SessionId)) {
    $SessionId = New-DeviceSmokeSessionId -Prefix "qnn-image-$Mode"
}
$SessionId = Get-DeviceSmokeSafeName -Value $SessionId

$component = "$Package/.debug.LocalImageSmokeActivity"
$serial = Initialize-DeviceSmokeDevice -Adb $Adb -Serial $Serial
Assert-DeviceSmokePackageInstalled -Adb $Adb -Serial $serial -Package $Package
Assert-DeviceSmokeActivityAvailable -Adb $Adb -Serial $serial -Component $component
$bundlePreflight = Assert-DeviceSmokeQnnImageBundle -Adb $Adb -Serial $serial -BundleRoot $BundleRoot

$runOutputDir = Join-Path $OutDir $SessionId
New-Item -ItemType Directory -Force -Path $runOutputDir | Out-Null
$externalRoot = "/storage/emulated/0/Android/data/$Package/files"
$safeBundle = Get-DeviceSmokeSafeName -Value (Split-Path -Leaf $BundleRoot.TrimEnd('/'))
$requiresPng = $Mode -in @('pipeline', 'semantic')
$pipelineProbe = $Mode -eq 'pipeline'
$semanticGenerate = $Mode -eq 'semantic'
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
    $runId = "$safeBundle-qnn-image-$Mode-$SessionId-r$run"
    $remoteJson = "$externalRoot/image_bench/runs/$runId.json"
    $remotePng = "$externalRoot/image_bench/runs/$runId.png"
    $localJson = Join-Path $runOutputDir "$runId.json"
    $localPng = Join-Path $runOutputDir "$runId.png"
    $result = $null
    $contractError = $null
    $pngEvidence = [pscustomobject]@{ Preserved = $false; Error = $null; LocalPng = $localPng }
    try {
        $activityArguments = @(
            'am', 'start', '-W', '-n', $component,
            '--es', 'runtime', 'qnn_htp',
            '--es', 'bundleRoot', $BundleRoot,
            '--es', 'modelPath', $ModelPath,
            '--es', 'prompt', $Prompt,
            '--es', 'negativePrompt', $NegativePrompt,
            '--es', 'runId', $runId,
            '--ei', 'width', [string]$Width,
            '--ei', 'height', [string]$Height,
            '--ei', 'steps', [string]$Steps,
            '--ei', 'threads', [string]$Threads,
            '--ei', 'seed', [string]$Seed,
            '--ef', 'cfgScale', (ConvertTo-DeviceSmokeInvariantDouble -Value $CfgScale),
            '--es', 'sampleMethod', $SampleMethod,
            '--es', 'family', $Family,
            '--es', 'backendMode', $BackendMode,
            '--es', 'tokenEmbeddingMode', $TokenEmbeddingMode,
            '--ei', 'memoryMode', [string]$MemoryMode,
            '--es', 'runner', $Runner,
            '--ez', 'directUnetSmoke', 'false',
            '--ez', 'preflightOnly', 'false',
            '--ez', 'pipelineProbe', ([string]$pipelineProbe).ToLowerInvariant(),
            '--ez', 'semanticGenerate', ([string]$semanticGenerate).ToLowerInvariant(),
            '--ez', 'workerProductPath', ([string][bool]$WorkerProductPath).ToLowerInvariant(),
            '--el', 'workerStartPauseMs', [string]($WorkerKillWindowSeconds * 1000),
            '--el', 'workerMainLeaseHoldMs', [string]($WorkerMainLeaseHoldSeconds * 1000)
        )
        if (-not [string]::IsNullOrWhiteSpace($RuntimeDirsJson)) {
            # Android's `am --es` parser can remove the double quotes from a
            # JSON array even when adb shell receives a single-quoted value.
            # Carry it as Base64 so the debug activity can reconstruct and
            # validate the original JSON exactly.
            $runtimeDirsJsonBase64 = [Convert]::ToBase64String(
                [Text.Encoding]::UTF8.GetBytes($RuntimeDirsJson)
            )
            $activityArguments += @('--es', 'runtimeDirsJsonBase64', $runtimeDirsJsonBase64)
        }
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
            Assert-DeviceSmokeQnnImageContract -Json $result.json -Mode $Mode
            if ($WorkerProductPath) {
                Assert-DeviceSmokeWorkerIsolationContract `
                    -Json $result.json `
                    -RequireMainLeaseWait:($WorkerMainLeaseHoldSeconds -gt 0)
            }
            if ($requiresPng -and -not $pngEvidence.Preserved) {
                throw "QNN image $Mode requires a nonempty output PNG: $($pngEvidence.Error)"
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
    # QNN image activity publishes native evidence under the terminal event's
    # result/runtimeInspection fields, unlike chat activities which expose a
    # nativeStats object. Keep the extraction here tolerant of both layouts so
    # summary.json remains useful for completed and failed runs.
    $terminalJson = $result.json
    $terminalResult = Get-DeviceSmokeProperty -Object $terminalJson -Name 'result'
    $runtimeInspection = Get-DeviceSmokeProperty -Object $terminalResult -Name 'runtimeInspection'
    if ($null -eq $runtimeInspection) {
        $runtimeInspection = Get-DeviceSmokeProperty -Object $terminalJson -Name 'runtimeInspection'
    }
    # Runtime directories are published on the qnn_starting event, while the
    # completed native call publishes the selected transport under
    # result.executionRuntime. Read both stages so the audit never reports the
    # metadata-only preflight (HTP arch 0) as the executed runtime.
    $events = @(Get-DeviceSmokeEvents -Json $terminalJson)
    $runtimeStartEvents = @($events | Where-Object {
        $eventStatus = Get-DeviceSmokeProperty -Object $_ -Name 'status'
        $eventStatus -in @('qnn_starting', 'qnn_pipeline_probe_starting', 'qnn_semantic_product_starting')
    })
    $runtimeStart = if ($runtimeStartEvents.Count -gt 0) {
        $runtimeStartEvents[$runtimeStartEvents.Count - 1]
    } else {
        $null
    }
    if ($null -eq $runtimeInspection) {
        $runtimeInspection = Get-DeviceSmokeProperty -Object $runtimeStart -Name 'runtimeInspection'
    }
    $preflightRuntime = Get-DeviceSmokeProperty -Object $terminalResult -Name 'runtime'
    $executionRuntime = Get-DeviceSmokeProperty -Object $terminalResult -Name 'executionRuntime'
    if ($null -eq $executionRuntime) {
        # Semantic/graph terminal events publish executionRuntime beside result;
        # retain compatibility with older payloads that nested it under result.
        $executionRuntime = Get-DeviceSmokeProperty -Object $terminalJson -Name 'executionRuntime'
    }
    $htpArchCandidates = @(
        (Get-DeviceSmokeProperty -Object $executionRuntime -Name 'htpArchVersion'),
        (Get-DeviceSmokeProperty -Object $runtimeInspection -Name 'htpArchVersion'),
        (Get-DeviceSmokeProperty -Object $preflightRuntime -Name 'htpArchVersion'),
        (Get-DeviceSmokeProperty -Object $terminalResult -Name 'selectedHtpArch'),
        (Get-DeviceSmokeProperty -Object $terminalJson -Name 'selectedHtpArch')
    )
    $nativeStats = Get-DeviceSmokeLatestNativeStats -Json $terminalJson
    $nativeEvidence = [pscustomobject][ordered]@{
        runtimeDirs = Get-DeviceSmokeProperty -Object $terminalJson -Name 'runtimeDirs'
        htpArch = Select-DeviceSmokeFirstPositiveHtpArch -Candidates $htpArchCandidates
        transportHtpArch = Get-DeviceSmokeProperty -Object $terminalResult -Name 'transportHtpArch'
        qnnGraphExecution = Get-DeviceSmokeProperty -Object $terminalJson -Name 'qnnGraphExecution'
        npuExecutionProven = Get-DeviceSmokeProperty -Object $terminalResult -Name 'npuExecutionProven'
        outputBytes = Get-DeviceSmokeProperty -Object $terminalJson -Name 'outputBytes'
    }
    if ($null -eq $nativeEvidence.runtimeDirs) {
        $nativeEvidence.runtimeDirs = Get-DeviceSmokeProperty -Object $terminalResult -Name 'runtimeDirs'
    }
    if ($null -eq $nativeEvidence.runtimeDirs) {
        $nativeEvidence.runtimeDirs = Get-DeviceSmokeProperty -Object $runtimeStart -Name 'runtimeDirs'
    }
    if ($null -eq $nativeEvidence.qnnGraphExecution) {
        $nativeEvidence.qnnGraphExecution = Get-DeviceSmokeProperty -Object $terminalResult -Name 'qnnGraphExecution'
    }
    if ($null -eq $nativeEvidence.npuExecutionProven) {
        $nativeEvidence.npuExecutionProven = (
            (Get-DeviceSmokeProperty -Object $terminalResult -Name 'npuActive') -eq $true -and
            (Get-DeviceSmokeProperty -Object $terminalResult -Name 'qnnGraphExecution') -eq $true -and
            (Get-DeviceSmokeProperty -Object $terminalResult -Name 'nativeExecution') -eq $true
        )
    }
    $nativeEvidence.transportHtpArch = Select-DeviceSmokeFirstPositiveHtpArch -Candidates @(
        $nativeEvidence.transportHtpArch,
        (Get-DeviceSmokeProperty -Object $terminalJson -Name 'transportHtpArch'),
        (Get-DeviceSmokeProperty -Object $executionRuntime -Name 'htpArchVersion'),
        $nativeEvidence.htpArch
    )
    if ($null -eq $nativeEvidence.outputBytes) {
        $nativeEvidence.outputBytes = Get-DeviceSmokeProperty -Object $terminalResult -Name 'outputBytes'
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
        runtimeDirs = $nativeEvidence.runtimeDirs
        htpArch = $nativeEvidence.htpArch
        transportHtpArch = $nativeEvidence.transportHtpArch
        qnnGraphExecution = $nativeEvidence.qnnGraphExecution
        npuExecutionProven = $nativeEvidence.npuExecutionProven
        outputBytes = $nativeEvidence.outputBytes
        remotePng = if ($requiresPng) { $remotePng } else { $null }
        localPng = if ($requiresPng) { $pngEvidence.LocalPng } else { $null }
        pngPreserved = if ($requiresPng) { [bool]$pngEvidence.Preserved } else { $false }
        pngError = if ($requiresPng) { $pngEvidence.Error } else { $null }
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
    runtime = 'qnn_htp'
    mode = $Mode
    bundleRoot = $BundleRoot
    modelPath = $ModelPath
    prompt = $Prompt
    negativePrompt = $NegativePrompt
    width = $Width
    height = $Height
    steps = $Steps
    threads = $Threads
    seed = $Seed
    cfgScale = $CfgScale
    distilledGuidance = $null
    flowShift = $null
    sampleMethod = $SampleMethod
    family = $Family
    backendMode = $BackendMode
    tokenEmbeddingMode = $TokenEmbeddingMode
    memoryMode = $MemoryMode
    runner = $Runner
    runtimeDirsJson = $RuntimeDirsJson
    workerProductPath = [bool]$WorkerProductPath
    workerKillWindowSeconds = $WorkerKillWindowSeconds
    workerMainLeaseHoldSeconds = $WorkerMainLeaseHoldSeconds
    lifecycle = $Lifecycle
    requiresPng = $requiresPng
    bundlePreflight = $bundlePreflight
    runs = @($summaries)
    latestNativeEvidence = $null
}
$summaryPath = Join-Path $runOutputDir 'summary.json'
$latestRun = @($summaries | Select-Object -Last 1)
$latestNativeEvidence = if ($latestRun.Count -eq 1) {
    [pscustomobject][ordered]@{
        runtimeDirs = $latestRun[0].runtimeDirs
        htpArch = $latestRun[0].htpArch
        transportHtpArch = $latestRun[0].transportHtpArch
        qnnGraphExecution = $latestRun[0].qnnGraphExecution
        npuExecutionProven = $latestRun[0].npuExecutionProven
        outputBytes = $latestRun[0].outputBytes
    }
} else {
    $null
}
$summary.latestNativeEvidence = $latestNativeEvidence
Write-DeviceSmokeSessionSummary -Path $summaryPath -Summary $summary
Write-Host "Summary: $summaryPath"

$failed = @($summaries | Where-Object { $_.status -ne 'completed' })
if ($failed.Count -gt 0) {
    throw "$($failed.Count) of $Runs QNN image smoke run(s) failed."
}
