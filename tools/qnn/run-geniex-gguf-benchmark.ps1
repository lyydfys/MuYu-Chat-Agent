param(
    [string]$Adb = "D:\model\android-sdk\platform-tools\adb.exe",
    [string]$Serial = "",
    [string]$PackageName = "com.muyuchat.mca",
    [string]$ModelPath = "/storage/emulated/0/Android/data/com.muyuchat.mca/files/models/qwen35_2b_geniex_q4/Qwen3.5-2B-Q4_0.gguf",
    [string]$OutDir = "docs\experiments\device-smoke\geniex-chat",
    [int]$ContextTokens = 1024,
    [int]$Threads = 4,
    [int]$MaxTokens = 64,
    [int]$CooldownSeconds = 45,
    [switch]$SkipHybrid,
    [switch]$SkipCpu
)

Set-StrictMode -Version Latest
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
    return (@(Invoke-Adb -Arguments $Arguments) -join [Environment]::NewLine).Trim()
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
            throw "ADB device $Serial is not connected or authorized."
        }
    } elseif ($devices.Count -eq 1) {
        $script:Serial = $devices[0]
    } elseif ($devices.Count -eq 0) {
        throw "No authorized ADB device is connected."
    } else {
        throw "Multiple ADB devices are connected. Pass -Serial."
    }
}

function Add-EvidenceBlock {
    param(
        [string]$Path,
        [string]$Title,
        [string]$Content
    )
    $header = "===== $Title @ $([DateTimeOffset]::Now.ToString('o')) ====="
    Add-Content -LiteralPath $Path -Value @($header, $Content, "") -Encoding UTF8
}

function Get-AppPid {
    return Invoke-AdbText -Arguments @("shell", "pidof", $PackageName)
}

function Capture-FastEvidence {
    param(
        [string]$EvidencePath,
        [string]$Mode
    )
    $appPid = Get-AppPid
    if ([string]::IsNullOrWhiteSpace($appPid)) { return }
    $appPid = ($appPid -split "\s+")[0]

    $maps = Invoke-AdbText -Arguments @("shell", "run-as", $PackageName, "cat", "/proc/$appPid/maps")
    $mapLines = @($maps -split "\r?\n" | Where-Object {
        $_ -match "(?i)(geniex|ggml|llama|qnn|htp|hexagon|adsprpc|cdsprpc|fastrpc)"
    })
    if ($mapLines.Count -gt 0) {
        Add-EvidenceBlock -Path $EvidencePath -Title "$Mode pid=$appPid maps" -Content ($mapLines -join [Environment]::NewLine)
    }

    $fds = Invoke-AdbText -Arguments @("shell", "run-as", $PackageName, "ls", "-l", "/proc/$appPid/fd")
    $fdLines = @($fds -split "\r?\n" | Where-Object {
        $_ -match "(?i)(adsprpc|cdsprpc|fastrpc|ion|dma_heap|kgsl|qnn|htp)"
    })
    if ($fdLines.Count -gt 0) {
        Add-EvidenceBlock -Path $EvidencePath -Title "$Mode pid=$appPid fds" -Content ($fdLines -join [Environment]::NewLine)
    }
}

function Capture-SlowEvidence {
    param(
        [string]$EvidencePath,
        [string]$Mode
    )
    $appPid = Get-AppPid
    if ([string]::IsNullOrWhiteSpace($appPid)) { return }
    $appPid = ($appPid -split "\s+")[0]
    Add-EvidenceBlock -Path $EvidencePath -Title "$Mode pid=$appPid meminfo" -Content (
        Invoke-AdbText -Arguments @("shell", "dumpsys", "meminfo", $PackageName)
    )
    Add-EvidenceBlock -Path $EvidencePath -Title "$Mode pid=$appPid top" -Content (
        Invoke-AdbText -Arguments @("shell", "top", "-b", "-n", "1", "-p", $appPid)
    )
    Add-EvidenceBlock -Path $EvidencePath -Title "$Mode thermal" -Content (
        Invoke-AdbText -Arguments @(
            "shell",
            'for z in /sys/class/thermal/thermal_zone*; do n=$(cat "$z/type" 2>/dev/null); t=$(cat "$z/temp" 2>/dev/null); case "$n" in *cpu*|*gpu*|*npu*|*dsp*|*soc*|*skin*|*battery*) echo "$n=$t";; esac; done'
        )
    )
}

function Start-SmokeActivity {
    param(
        [string]$Mode,
        [string]$RunId
    )
    $component = "$PackageName/.debug.LocalChatSmokeActivity"
    $commandArgs = @(
        "am", "start", "-W", "-n", $component,
        "--es", "modelPath", $ModelPath,
        "--es", "displayName", "Qwen3.5-2B Q4_0 / GenieX $Mode",
        "--es", "runtime", "geniex_llama_cpp",
        "--es", "computeUnit", $Mode,
        "--es", "runId", $RunId,
        "--ei", "nCtx", "$ContextTokens",
        "--ei", "nThreads", "$Threads",
        "--ei", "maxTokens", "$MaxTokens",
        "--es", "smokeMode", "api_only"
    )
    $command = ($commandArgs | ForEach-Object { Convert-AdbShellArg ([string]$_) }) -join " "
    Invoke-Adb -Arguments @("shell", $command)
}

function Run-BenchmarkMode {
    param([ValidateSet("hybrid", "cpu")][string]$Mode)

    $runId = "qwen35-2b-geniex-$Mode-$(Get-Date -Format yyyyMMdd-HHmmss)"
    $runDir = Join-Path $OutDir $runId
    New-Item -ItemType Directory -Force -Path $runDir | Out-Null
    $evidencePath = Join-Path $runDir "runtime-evidence.txt"
    $logcatPath = Join-Path $runDir "logcat-filtered.txt"
    $remoteJson = "/storage/emulated/0/Android/data/$PackageName/files/chat_smoke/runs/$runId.json"

    Invoke-Adb -Arguments @("shell", "am", "force-stop", $PackageName) | Out-Null
    Invoke-Adb -Arguments @("logcat", "-c") | Out-Null
    Start-Sleep -Milliseconds 750
    Start-SmokeActivity -Mode $Mode -RunId $runId | Out-Host

    $deadline = (Get-Date).AddMinutes(20)
    $nextFast = Get-Date
    $nextSlow = Get-Date
    $status = "running"
    while ((Get-Date) -lt $deadline) {
        $now = Get-Date
        if ($now -ge $nextFast) {
            Capture-FastEvidence -EvidencePath $evidencePath -Mode $Mode
            $nextFast = $now.AddMilliseconds(500)
        }
        if ($now -ge $nextSlow) {
            Capture-SlowEvidence -EvidencePath $evidencePath -Mode $Mode
            $nextSlow = $now.AddSeconds(5)
        }

        $json = Invoke-AdbText -Arguments @("shell", "cat", $remoteJson)
        if ($json -match '"status"\s*:\s*"(completed|failed)"') {
            $status = $Matches[1]
            break
        }
        Start-Sleep -Milliseconds 200
    }

    Capture-FastEvidence -EvidencePath $evidencePath -Mode $Mode
    Capture-SlowEvidence -EvidencePath $evidencePath -Mode $Mode
    Invoke-Adb -Arguments @("pull", $remoteJson, (Join-Path $runDir "$runId.json")) | Out-Host

    $logcat = Invoke-AdbText -Arguments @("logcat", "-d", "-v", "threadtime")
    $filtered = @($logcat -split "\r?\n" | Where-Object {
        $_ -match "(?i)(MCA-CHAT-SMOKE|geniex|ggml|llama|qnn|htp|hexagon|adsprpc|cdsprpc|fastrpc)"
    })
    [IO.File]::WriteAllText($logcatPath, ($filtered -join [Environment]::NewLine), [Text.UTF8Encoding]::new($false))

    if ($status -eq "running") {
        throw "$Mode benchmark timed out waiting for $remoteJson"
    }
    if ($status -eq "failed") {
        throw "$Mode benchmark reported failure. See $runDir"
    }
    return $runDir
}

Require-Device
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$metadata = @(
    "serial=$Serial",
    "model=$(Invoke-AdbText -Arguments @('shell', 'getprop', 'ro.product.model'))",
    "soc=$(Invoke-AdbText -Arguments @('shell', 'getprop', 'ro.soc.model'))",
    "android=$(Invoke-AdbText -Arguments @('shell', 'getprop', 'ro.build.version.release'))",
    "modelPath=$ModelPath",
    "nCtx=$ContextTokens",
    "threads=$Threads",
    "maxTokens=$MaxTokens",
    "startedAt=$([DateTimeOffset]::Now.ToString('o'))"
)
[IO.File]::WriteAllLines((Join-Path $OutDir "latest-device-metadata.txt"), $metadata, [Text.UTF8Encoding]::new($false))

$completed = @()
if (-not $SkipHybrid) {
    $completed += Run-BenchmarkMode -Mode "hybrid"
}
if (-not $SkipHybrid -and -not $SkipCpu) {
    Start-Sleep -Seconds $CooldownSeconds
}
if (-not $SkipCpu) {
    $completed += Run-BenchmarkMode -Mode "cpu"
}

Write-Host "GenieX GGUF benchmark completed:"
$completed | ForEach-Object { Write-Host "  $_" }
