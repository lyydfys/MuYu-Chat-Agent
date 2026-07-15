param(
    [string]$Adb = "D:\model\android-sdk\platform-tools\adb.exe",
    [string]$Serial = "",
    [string]$PackageName = "com.muyuchat.mca",
    [string]$ApkPath = ".release\apk\app-debug-mnn-nchw-meinamix-releasekey.apk",
    [string]$OutDir = "docs\experiments\device-smoke",
    [string]$MeinaMixBundleRoot = "/storage/emulated/0/Android/data/com.muyuchat.mca/files/qnn-bundles/meinamix-sd15-qnn228-8gen2",
    [string]$MnnSd15BundleRoot = "/storage/emulated/0/Android/data/com.muyuchat.mca/files/models/mnn-sd15",
    [string]$GenieXQwen35ModelPath = "/storage/emulated/0/Android/data/com.muyuchat.mca/files/models/qwen35_2b_geniex_q4/Qwen3.5-2B-Q4_0.gguf",
    [switch]$SkipInstall,
    [switch]$RunQnnImage,
    [switch]$RunMnnImage,
    [switch]$RunQnnChat4B,
    [switch]$RunQnnChatVl4B,
    [switch]$RunQnnChat8B,
    [switch]$RunGenieXQwen35Hybrid,
    [switch]$RunGenieXQwen35Cpu,
    [switch]$NoForceStopBeforeRun
)

$ErrorActionPreference = "Stop"

function Invoke-Adb {
    param([string[]]$Arguments)
    $adbArgs = @()
    if (-not [string]::IsNullOrWhiteSpace($Serial)) {
        $adbArgs += @("-s", $Serial)
    }
    $adbArgs += $Arguments
    & $Adb @adbArgs
}

function Invoke-AdbText {
    param([string[]]$Arguments)
    (@(Invoke-Adb -Arguments $Arguments) -join "`n").Trim()
}

function Convert-AdbShellArg {
    param([string]$Value)
    if ($null -eq $Value) { return "''" }
    return "'" + $Value.Replace("'", "'\''") + "'"
}

function Require-Device {
    $lines = @(& $Adb devices -l)
    $devices = @()
    foreach ($line in $lines | Select-Object -Skip 1) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $parts = $line.Trim() -split "\s+"
        if ($parts.Count -ge 2 -and $parts[1] -eq "device") {
            $devices += $parts[0]
        }
    }
    if (-not [string]::IsNullOrWhiteSpace($Serial)) {
        if ($Serial -notin $devices) {
            throw "ADB device $Serial is not connected/authorized."
        }
    } elseif ($devices.Count -eq 1) {
        $script:Serial = $devices[0]
    } elseif ($devices.Count -eq 0) {
        throw "No authorized ADB device connected."
    } else {
        throw "Multiple ADB devices connected. Pass -Serial."
    }
    Write-Host "Using device: $script:Serial"
}

function Install-Apk {
    if ($SkipInstall) { return }
    if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) {
        throw "APK not found: $ApkPath"
    }
    Write-Host "Installing $ApkPath ..."
    $adbArgs = @()
    if (-not [string]::IsNullOrWhiteSpace($Serial)) {
        $adbArgs += @("-s", $Serial)
    }
    $adbArgs += @("install", "--no-incremental", "-r", $ApkPath)
    $oldErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = @(& $Adb @adbArgs 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $oldErrorActionPreference
    }
    $text = $output -join "`n"
    Write-Host $text
    if ($text -match "INSTALL_FAILED_USER_RESTRICTED") {
        throw "Device rejected ADB install with INSTALL_FAILED_USER_RESTRICTED. Enable USB install / USB debugging security settings on the phone, then rerun this script."
    }
    if ($exitCode -ne 0 -or $text -notmatch "Success") {
        throw "APK install did not report Success."
    }
}

function Start-Activity {
    param([string]$ActivityName, [string[]]$Extras)
    $component = "$PackageName/$ActivityName"
    Write-Host "Starting $component"
    if (-not $NoForceStopBeforeRun) {
        Invoke-Adb -Arguments @("shell", "am force-stop '$PackageName'") | Out-Null
        Start-Sleep -Milliseconds 500
    }
    $commandArgs = @("am", "start", "-n", $component) + $Extras
    $command = ($commandArgs | ForEach-Object { Convert-AdbShellArg ([string]$_) }) -join " "
    $output = @(Invoke-Adb -Arguments @("shell", $command))
    $text = $output -join "`n"
    Write-Host $text
    if ($text -match "Error type 3" -or $text -match "does not exist") {
        throw "Activity $component does not exist. Install the debug smoke APK first."
    }
}

function Wait-RemoteFile {
    param(
        [string]$RemotePath,
        [int]$TimeoutSeconds = 900
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $exists = Invoke-AdbText -Arguments @(
            "shell",
            "if [ -f '$RemotePath' ]; then echo yes; fi"
        )
        if ($exists -eq "yes") { return }
        Start-Sleep -Seconds 2
    }
    throw "Timed out waiting for remote file: $RemotePath"
}

function Pull-IfExists {
    param([string]$RemotePath, [string]$LocalDir)
    New-Item -ItemType Directory -Force -Path $LocalDir | Out-Null
    $exists = Invoke-AdbText -Arguments @(
        "shell",
        "if [ -f '$RemotePath' ]; then echo yes; fi"
    )
    if ($exists -eq "yes") {
        Invoke-Adb -Arguments @("pull", $RemotePath, $LocalDir) | Out-Host
    } else {
        Write-Host "Remote file missing, skip pull: $RemotePath"
    }
}

function Wait-RunFinished {
    param(
        [string]$JsonPath,
        [int]$TimeoutSeconds = 900
    )
    Wait-RemoteFile -RemotePath $JsonPath -TimeoutSeconds $TimeoutSeconds
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $content = Invoke-AdbText -Arguments @("shell", "cat '$JsonPath'")
        if ($content -match '"status"\s*:\s*"(completed|failed)"') {
            return
        }
        Start-Sleep -Seconds 5
    }
    throw "Timed out waiting for smoke completion: $JsonPath"
}

function Run-QnnImageMeinaMix {
    param([string]$Mode)
    $runId = "qnn-meinamix-$Mode-$(Get-Date -Format yyyyMMdd-HHmmss)"
    $jsonPath = "/storage/emulated/0/Android/data/$PackageName/files/image_bench/runs/$runId.json"
    $pngPath = "/storage/emulated/0/Android/data/$PackageName/files/image_bench/runs/$runId.png"
    Start-Activity ".debug.LocalImageSmokeActivity" @(
        "--es", "runtime", "qnn_htp",
        "--es", "bundleRoot", $MeinaMixBundleRoot,
        "--es", "runId", $runId,
        "--ez", "semanticGenerate", "true",
        "--ei", "width", "512",
        "--ei", "height", "512",
        "--ei", "steps", "8",
        "--ei", "threads", "4",
        "--ei", "seed", "20260710",
        "--ef", "cfgScale", "7.0",
        "--es", "sampleMethod", "pndm",
        "--es", "family", "SD15",
        "--es", "backendMode", "cpu",
        "--es", "tokenEmbeddingMode", $Mode,
        "--es", "prompt", "natural light portrait photo of a young woman standing by a window, soft skin tones, realistic lens, clean background"
    )
    Wait-RunFinished $jsonPath 1200
    Pull-IfExists $jsonPath (Join-Path $OutDir "qnn-smoke")
    Pull-IfExists $pngPath (Join-Path $OutDir "qnn-smoke")
}

function Run-MnnSd15Preflight {
    $runId = "mnn-sd15-nchw-preflight-$(Get-Date -Format yyyyMMdd-HHmmss)"
    $jsonPath = "/storage/emulated/0/Android/data/$PackageName/files/image_bench/runs/$runId.json"
    Start-Activity ".debug.LocalImageSmokeActivity" @(
        "--es", "runtime", "mnn_diffusion",
        "--es", "bundleRoot", $MnnSd15BundleRoot,
        "--es", "runId", $runId,
        "--ez", "preflightOnly", "true",
        "--ez", "directUnetSmoke", "true",
        "--ei", "width", "512",
        "--ei", "height", "512",
        "--ei", "steps", "1",
        "--ei", "threads", "4",
        "--ei", "seed", "20260710",
        "--es", "backendMode", "cpu",
        "--es", "family", "SD15",
        "--es", "prompt", "a tiny ceramic robot sitting on a wooden desk, soft morning light"
    )
    Wait-RunFinished $jsonPath 300
    Pull-IfExists $jsonPath (Join-Path $OutDir "mnn-smoke")
}

function Run-QnnChat {
    param([string]$RecommendedId, [int]$ContextTokens, [int]$MaxTokens)
    $runId = "$RecommendedId-$(Get-Date -Format yyyyMMdd-HHmmss)"
    $jsonPath = "/storage/emulated/0/Android/data/$PackageName/files/chat_smoke/runs/$runId.json"
    Start-Activity ".debug.LocalChatSmokeActivity" @(
        "--es", "recommendedId", $RecommendedId,
        "--es", "runId", $runId,
        "--ei", "nCtx", "$ContextTokens",
        "--ei", "nThreads", "4",
        "--ei", "maxTokens", "$MaxTokens",
        "--es", "smokeMode", "api_only"
    )
    Wait-RunFinished $jsonPath 1800
    Pull-IfExists $jsonPath (Join-Path $OutDir "qnn-chat")
}

function Run-GenieXLlamaCppChat {
    param([string]$ComputeUnit)
    $runId = "qwen35-2b-geniex-$ComputeUnit-$(Get-Date -Format yyyyMMdd-HHmmss)"
    $jsonPath = "/storage/emulated/0/Android/data/$PackageName/files/chat_smoke/runs/$runId.json"
    Start-Activity ".debug.LocalChatSmokeActivity" @(
        "--es", "modelPath", $GenieXQwen35ModelPath,
        "--es", "displayName", "Qwen3.5-2B Q4_0 / GenieX $ComputeUnit",
        "--es", "runtime", "geniex_llama_cpp",
        "--es", "computeUnit", $ComputeUnit,
        "--es", "runId", $runId,
        "--ei", "nCtx", "1024",
        "--ei", "nThreads", "4",
        "--ei", "maxTokens", "64",
        "--es", "smokeMode", "api_only"
    )
    Wait-RunFinished $jsonPath 1200
    Pull-IfExists $jsonPath (Join-Path $OutDir "geniex-chat")
}

Require-Device
Install-Apk

if (
    -not $RunQnnImage -and
    -not $RunMnnImage -and
    -not $RunQnnChat4B -and
    -not $RunQnnChatVl4B -and
    -not $RunQnnChat8B -and
    -not $RunGenieXQwen35Hybrid -and
    -not $RunGenieXQwen35Cpu
) {
    $RunQnnImage = $true
    $RunMnnImage = $true
    $RunQnnChat4B = $true
}

if ($RunQnnImage) {
    Run-QnnImageMeinaMix "dual_slice_first_half"
    Run-QnnImageMeinaMix "dual_slice_second_half"
}
if ($RunMnnImage) {
    Run-MnnSd15Preflight
}
if ($RunQnnChat4B) {
    Run-QnnChat "qwen3_4b_2507_qairt_w4a16" 1024 8
}
if ($RunQnnChatVl4B) {
    Run-QnnChat "qwen3_vl_4b_qairt_w4a16" 1024 8
}
if ($RunQnnChat8B) {
    Run-QnnChat "qwen3_8b_qairt_w4a16" 1024 8
}
if ($RunGenieXQwen35Hybrid) {
    Run-GenieXLlamaCppChat "hybrid"
}
if ($RunGenieXQwen35Cpu) {
    Run-GenieXLlamaCppChat "cpu"
}

Write-Host "Smoke run complete. Pulled artifacts under $OutDir"
