param(
    [string]$Adb = 'adb',
    [string]$Serial = '',
    [string]$Package = 'com.muyuchat.mca',
    [Parameter(Mandatory = $true)]
    [string]$ModelPath,
    [string]$BundleRoot = '',
    [string]$Prompt = 'A tiny ceramic robot sitting on a wooden desk, soft morning light, clean background, no text.',
    [ValidateRange(64, 4096)]
    [int]$Width = 512,
    [ValidateRange(64, 4096)]
    [int]$Height = 512,
    [ValidateRange(1, 2147483647)]
    [int]$Steps = 1,
    [ValidateRange(1, 1024)]
    [int]$Threads = 4,
    [int]$Seed = 42,
    [double]$CfgScale = 1.0,
    [double]$DistilledGuidance = 3.5,
    [double]$FlowShift = 0.0,
    [string]$SampleMethod = 'euler',
    [string]$Family = 'SD_TURBO',
    [ValidateRange(1, 100)]
    [int]$Runs = 1,
    [ValidateSet('reuse', 'cold')]
    [string]$Lifecycle = 'cold',
    [switch]$WorkerProductPath,
    [ValidateRange(0, 300000)]
    [long]$WorkerStartPauseMs = 0,
    [ValidateRange(0, 300000)]
    [long]$WorkerMainLeaseHoldMs = 0,
    [string]$OutDir = 'docs\experiments\device-smoke\sdcpp-image',
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
    if ($Value.IndexOf([char]0) -ge 0 -or $Value.Contains("`r") -or $Value.Contains("`n")) {
        throw "$Name must not contain NUL characters or newlines."
    }
}

function ConvertTo-DeviceSmokeInvariantDouble {
    param([double]$Value)

    return $Value.ToString([Globalization.CultureInfo]::InvariantCulture)
}

function ConvertTo-PosixSingleQuoted {
    param([string]$Value)

    return "'" + $Value.Replace("'", "'\\''") + "'"
}

function Assert-RemoteNonEmptyFile {
    param(
        [string]$Adb,
        [string]$Serial,
        [string]$Path,
        [string]$Description
    )

    $quotedPath = ConvertTo-PosixSingleQuoted -Value $Path
    $probe = (& $Adb -s $Serial shell "test -s $quotedPath && echo MCA_FILE_OK" 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $probe -notmatch 'MCA_FILE_OK') {
        throw "$Description is missing or empty on the device: $Path"
    }
}

function Assert-SdcppImageContract {
    param(
        [object]$Json,
        [int]$ExpectedWidth,
        [int]$ExpectedHeight,
        [int]$ExpectedSteps,
        [int]$ExpectedSeed,
        [string]$ExpectedSampleMethod,
        [bool]$ExpectedWorkerProductPath
    )

    if ($null -eq $Json -or $Json.status -cne 'completed') {
        throw 'stable-diffusion.cpp did not report a completed terminal result.'
    }
    $result = $Json.result
    if ($null -eq $result) { throw 'stable-diffusion.cpp result object is missing.' }
    if ($result.ok -ne $true) { throw 'stable-diffusion.cpp result.ok must be true.' }
    if ($result.backend -cne 'stable-diffusion.cpp') {
        throw "Unexpected image backend '$($result.backend)'; expected stable-diffusion.cpp."
    }
    if ($result.runtimeBackend -cne 'cpu') {
        throw "Unexpected runtime backend '$($result.runtimeBackend)'; expected cpu."
    }
    if ([int]$result.width -ne $ExpectedWidth -or [int]$result.height -ne $ExpectedHeight) {
        throw "Unexpected output dimensions $($result.width)x$($result.height); expected ${ExpectedWidth}x${ExpectedHeight}."
    }
    if ([int]$result.steps -ne $ExpectedSteps) {
        throw "Unexpected step count '$($result.steps)'; expected $ExpectedSteps."
    }
    if ([int]$result.seed -ne $ExpectedSeed) {
        throw "Unexpected seed '$($result.seed)'; expected $ExpectedSeed."
    }
    if ($result.sampleMethod -cne $ExpectedSampleMethod) {
        throw "Unexpected sample method '$($result.sampleMethod)'; expected $ExpectedSampleMethod."
    }
    if ($result.contextReleased -ne $true) {
        throw 'stable-diffusion.cpp did not confirm terminal native context release.'
    }
    if ($ExpectedWorkerProductPath) {
        if ($Json.executionMode -cne 'worker_product' -or $Json.workerProductPath -ne $true) {
            throw 'stable-diffusion.cpp did not execute through the product worker path.'
        }
        if ($Json.workerIsolated -ne $true -or [int]$Json.workerPid -le 0 -or [int]$Json.workerPid -eq [int]$Json.mainPid) {
            throw 'stable-diffusion.cpp product smoke did not prove an isolated worker process.'
        }
    }
}

foreach ($argument in @(
        [pscustomobject]@{ Name = 'ModelPath'; Value = $ModelPath },
        [pscustomobject]@{ Name = 'BundleRoot'; Value = $BundleRoot },
        [pscustomobject]@{ Name = 'Prompt'; Value = $Prompt },
        [pscustomobject]@{ Name = 'SampleMethod'; Value = $SampleMethod },
        [pscustomobject]@{ Name = 'Family'; Value = $Family }
    )) {
    Assert-DeviceSmokeSingleLineArgument -Value $argument.Value -Name $argument.Name
}

if (-not $ModelPath.StartsWith('/')) {
    throw 'ModelPath must be an absolute Android path.'
}
if ([string]::IsNullOrWhiteSpace($BundleRoot)) {
    $lastSlash = $ModelPath.LastIndexOf('/')
    if ($lastSlash -le 0) { throw 'BundleRoot is required when ModelPath has no parent directory.' }
    $BundleRoot = $ModelPath.Substring(0, $lastSlash)
}
if (-not $BundleRoot.StartsWith('/')) {
    throw 'BundleRoot must be an absolute Android path.'
}
if ([string]::IsNullOrWhiteSpace($SessionId)) {
    $SessionId = New-DeviceSmokeSessionId -Prefix 'sdcpp-image'
}
$SessionId = Get-DeviceSmokeSafeName -Value $SessionId

$component = "$Package/.debug.LocalImageSmokeActivity"
$serial = Initialize-DeviceSmokeDevice -Adb $Adb -Serial $Serial
Assert-DeviceSmokePackageInstalled -Adb $Adb -Serial $serial -Package $Package
Assert-DeviceSmokeActivityAvailable -Adb $Adb -Serial $serial -Component $component
Assert-RemoteNonEmptyFile -Adb $Adb -Serial $serial -Path $ModelPath -Description 'stable-diffusion.cpp model file'

$runOutputDir = Join-Path $OutDir $SessionId
New-Item -ItemType Directory -Force -Path $runOutputDir | Out-Null
$externalRoot = "/storage/emulated/0/Android/data/$Package/files"
$safeModel = Get-DeviceSmokeSafeName -Value ([IO.Path]::GetFileName($ModelPath))
$summaries = @()

Write-Host "Device: $serial"
Write-Host "Model: $ModelPath"
Write-Host "Bundle: $BundleRoot"
Write-Host "Output: $runOutputDir"

for ($run = 1; $run -le $Runs; $run++) {
    $runId = "$safeModel-sdcpp-image-$SessionId-r$run"
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
            '--es', 'runtime', 'sdcpp',
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
            '--es', 'backendMode', 'cpu',
            '--ez', 'workerProductPath', ([string][bool]$WorkerProductPath).ToLowerInvariant(),
            '--el', 'workerStartPauseMs', [string]$WorkerStartPauseMs,
            '--el', 'workerMainLeaseHoldMs', [string]$WorkerMainLeaseHoldMs
        )
        $result = Invoke-DeviceSmokeActivityRun `
            -Adb $Adb -Serial $serial -Package $Package -Lifecycle $Lifecycle `
            -ActivityArguments $activityArguments -RemoteJson $remoteJson -LocalJson $localJson `
            -ExpectedRunId $runId -TimeoutSeconds $TimeoutSeconds -PollMilliseconds $PollMilliseconds `
            -RemotePng $remotePng
        $pngEvidence = Pull-DeviceSmokeRemotePng -Adb $Adb -Serial $serial -RemotePng $remotePng -LocalPng $localPng
        if ($result.status -eq 'completed') {
            Assert-SdcppImageContract `
                -Json $result.json `
                -ExpectedWidth $Width `
                -ExpectedHeight $Height `
                -ExpectedSteps $Steps `
                -ExpectedSeed $Seed `
                -ExpectedSampleMethod $SampleMethod.ToLowerInvariant() `
                -ExpectedWorkerProductPath ([bool]$WorkerProductPath)
            if (-not $pngEvidence.Preserved) {
                throw "stable-diffusion.cpp generation requires a nonempty output PNG: $($pngEvidence.Error)"
            }
            $pngQuality = Assert-PngQuality -Path $pngEvidence.LocalPng
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
        remotePng = $remotePng
        localPng = $pngEvidence.LocalPng
        pngPreserved = [bool]$pngEvidence.Preserved
        pngError = $pngEvidence.Error
        pngQuality = $pngQuality
    }
    Write-Host "[$run/$Runs] status=$($result.status) rawJson=$($result.rawResultPreserved) png=$($pngEvidence.Preserved)"
}

$summary = [pscustomobject][ordered]@{
    sessionId = $SessionId
    serial = $serial
    package = $Package
    component = $component
    runtime = 'stable-diffusion.cpp'
    modelPath = $ModelPath
    bundleRoot = $BundleRoot
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
    lifecycle = $Lifecycle
    workerProductPath = [bool]$WorkerProductPath
    workerStartPauseMs = $WorkerStartPauseMs
    workerMainLeaseHoldMs = $WorkerMainLeaseHoldMs
    runs = @($summaries)
}
$summaryPath = Join-Path $runOutputDir 'summary.json'
Write-DeviceSmokeSessionSummary -Path $summaryPath -Summary $summary
Write-Host "Summary: $summaryPath"

$failed = @($summaries | Where-Object { $_.status -ne 'completed' })
if ($failed.Count -gt 0) {
    throw "$($failed.Count) of $Runs stable-diffusion.cpp image smoke run(s) failed."
}
