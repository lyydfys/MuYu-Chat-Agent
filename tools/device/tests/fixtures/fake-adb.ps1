param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# The production script invokes this fixture in-process. Keep adb probes
# deterministic without using exit, which would terminate the parent host.
$global:LASTEXITCODE = 0

function Get-FakeRoot {
    if ([string]::IsNullOrWhiteSpace($env:QAIRT_VLM_FAKE_ADB_ROOT)) {
        throw "QAIRT_VLM_FAKE_ADB_ROOT is required."
    }
    if (-not (Test-Path -LiteralPath $env:QAIRT_VLM_FAKE_ADB_ROOT -PathType Container)) {
        New-Item -ItemType Directory -Force -Path $env:QAIRT_VLM_FAKE_ADB_ROOT | Out-Null
    }
    return $env:QAIRT_VLM_FAKE_ADB_ROOT
}

function Get-StatePath {
    return Join-Path (Get-FakeRoot) "state.json"
}

function Write-Utf8File {
    param(
        [string]$Path,
        [AllowEmptyString()]
        [string]$Content
    )

    $directory = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($directory)) {
        New-Item -ItemType Directory -Force -Path $directory | Out-Null
    }
    $utf8 = New-Object System.Text.UTF8Encoding $false
    [IO.File]::WriteAllText($Path, $Content, $utf8)
}

function Convert-RemoteToLocalPath {
    param([string]$RemotePath)

    $path = Get-FakeRoot
    foreach ($segment in @($RemotePath.TrimStart('/') -split '/')) {
        $path = Join-Path $path $segment
    }
    return $path
}

function Get-State {
    $path = Get-StatePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        return [pscustomobject]@{
            package = "com.muyuchat.mca"
            packageRunning = $false
            pid = "4242"
            forceStopCount = 0
            startCount = 0
            operations = @()
            starts = @()
        }
    }
    return Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json
}

function Save-State {
    param([object]$State)

    Write-Utf8File -Path (Get-StatePath) -Content ($State | ConvertTo-Json -Depth 10)
}

function Add-Operation {
    param(
        [object]$State,
        [string]$Kind,
        [AllowNull()][string]$RunId
    )

    $operations = @($State.operations)
    $operations += [pscustomobject][ordered]@{
        sequence = $operations.Count + 1
        kind = $Kind
        runId = $RunId
    }
    $State.operations = $operations
}

function Parse-ShellWords {
    param([string]$CommandText)

    $matches = [regex]::Matches($CommandText, "'([^']*)'|(\S+)")
    $words = New-Object System.Collections.ArrayList
    foreach ($match in $matches) {
        if ($match.Groups[1].Success) {
            [void]$words.Add($match.Groups[1].Value)
        } else {
            [void]$words.Add($match.Groups[2].Value)
        }
    }
    return @($words)
}

function Get-Scenario {
    if ([string]::IsNullOrWhiteSpace($env:QAIRT_VLM_FAKE_ADB_SCENARIO)) {
        return "terminal_completed"
    }
    return $env:QAIRT_VLM_FAKE_ADB_SCENARIO
}

function New-HistoricalRunnerStagePayload {
    param(
        [string]$RunId,
        [string]$RootStatus
    )

    return [ordered]@{
        runId = $RunId
        status = $RootStatus
        events = @(
            [ordered]@{
                status = "completed"
                loadMs = 222
                nativeStats = [ordered]@{
                    visionReady = $true
                }
            },
            [ordered]@{
                status = "runner_stage"
                stage = "qairt_vlm_destroy_start"
            }
        )
    }
}

function Write-RunResult {
    param(
        [object]$State,
        [string]$Package,
        [string]$RunId
    )

    $scenario = Get-Scenario
    $remoteJson = "/storage/emulated/0/Android/data/$Package/files/chat_smoke/runs/$RunId.json"
    $localJson = Convert-RemoteToLocalPath -RemotePath $remoteJson
    $directory = Split-Path -Parent $localJson
    New-Item -ItemType Directory -Force -Path $directory | Out-Null

    switch ($scenario) {
        "qnn_terminal_event_stats" {
            $payload = [ordered]@{
                runId = $RunId
                status = "completed"
                events = @(
                    [ordered]@{
                        status = "runner_stage"
                        stage = "generation_started"
                    },
                    [ordered]@{
                        status = "api_engine_stream_ok"
                        apiEngine = [ordered]@{
                            visibleSeen = $true
                        }
                    },
                    [ordered]@{
                        status = "completed"
                        nativeStats = [ordered]@{
                            backend = "geniex_qairt"
                            computeUnit = "npu"
                            loaded = $true
                            runnerReady = $true
                            decodeTps = 21.35
                        }
                    }
                )
            }
            Write-Utf8File -Path $localJson -Content ($payload | ConvertTo-Json -Depth 8)
            $State.packageRunning = $true
        }
        "process_exit_before_result" {
            $State.packageRunning = $false
        }
        "process_exit_after_result" {
            $content = '{"runId":"' + $RunId + '","status":"running","events":[{"status":"runner_stage","stage":"load_started"}]}'
            Write-Utf8File -Path $localJson -Content $content
            $State.packageRunning = $false
        }
        "garbled_json_completed" {
            $prefix = [Text.Encoding]::ASCII.GetBytes(
                '{"runId":"' + $RunId + '","status":"completed","events":[{"status":"completed","error":'
            )
            $suffix = [Text.Encoding]::ASCII.GetBytes('}]}')
            $bytes = New-Object byte[] ($prefix.Length + 1 + $suffix.Length)
            [Array]::Copy($prefix, 0, $bytes, 0, $prefix.Length)
            $bytes[$prefix.Length] = 0xC3
            [Array]::Copy($suffix, 0, $bytes, $prefix.Length + 1, $suffix.Length)
            [IO.File]::WriteAllBytes($localJson, $bytes)
            $State.packageRunning = $true
        }
        "history_root_nonterminal" {
            $payload = New-HistoricalRunnerStagePayload -RunId $RunId -RootStatus "runner_stage"
            Write-Utf8File -Path $localJson -Content ($payload | ConvertTo-Json -Depth 8)
            $State.packageRunning = $false
        }
        "history_root_completed" {
            $payload = New-HistoricalRunnerStagePayload -RunId $RunId -RootStatus "completed"
            Write-Utf8File -Path $localJson -Content ($payload | ConvertTo-Json -Depth 8)
            $State.packageRunning = $true
        }
        "terminal_completed" {
            $payload = [ordered]@{
                runId = $RunId
                status = "completed"
                events = @(
                    [ordered]@{
                        status = "runner_stage"
                        stage = "generation_started"
                    },
                    [ordered]@{
                        status = "completed"
                        loadMs = 321
                        nativeStats = [ordered]@{
                            visionReady = $true
                        }
                        generation = [ordered]@{
                            textPreview = "offline fake completed"
                        }
                    }
                )
            }
            Write-Utf8File -Path $localJson -Content ($payload | ConvertTo-Json -Depth 8)
            $State.packageRunning = $true
        }
        default {
            throw "Unsupported fake adb scenario: $scenario"
        }
    }
}

function Invoke-ShellCommand {
    param([string]$CommandText)

    if ($CommandText -match "^if \[ -d '([^']+)' \]; then ls -1A '([^']+)'; fi$") {
        if ($matches[1] -ne $matches[2]) {
            throw "Fake adb received mismatched directory list paths: $CommandText"
        }
        $path = Convert-RemoteToLocalPath -RemotePath $matches[1]
        if (Test-Path -LiteralPath $path -PathType Container) {
            Get-ChildItem -Force -LiteralPath $path | ForEach-Object { Write-Output $_.Name }
        }
        return
    }

    if ($CommandText -match "^mkdir -p '([^']+)'$") {
        New-Item -ItemType Directory -Force -Path (Convert-RemoteToLocalPath -RemotePath $matches[1]) | Out-Null
        return
    }

    if ($CommandText -match "^if \[ -f '([^']+)' \]; then echo yes; else echo no; fi$") {
        if (Test-Path -LiteralPath (Convert-RemoteToLocalPath -RemotePath $matches[1]) -PathType Leaf) {
            Write-Output "yes"
        } else {
            Write-Output "no"
        }
        return
    }

    if ($CommandText -match "^if \[ (-[dfs]) '([^']+)' \]; then printf __DEVICE_SMOKE_OK__; fi$") {
        $flag = $matches[1]
        $path = Convert-RemoteToLocalPath -RemotePath $matches[2]
        $exists = switch ($flag) {
            '-d' { Test-Path -LiteralPath $path -PathType Container }
            '-f' { Test-Path -LiteralPath $path -PathType Leaf }
            '-s' { (Test-Path -LiteralPath $path -PathType Leaf) -and ((Get-Item -LiteralPath $path).Length -gt 0) }
            default { $false }
        }
        if ($exists) {
            Write-Output '__DEVICE_SMOKE_OK__'
        }
        return
    }

    if ($CommandText -match "^cat '([^']+)'$") {
        $path = Convert-RemoteToLocalPath -RemotePath $matches[1]
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Fake adb cannot cat missing file: $path"
        }
        Write-Output ([Text.Encoding]::UTF8.GetString([IO.File]::ReadAllBytes($path)))
        return
    }

    if ($CommandText -match '^count=\$\(ls -1A ''([^'']+)'' 2>/dev/null \| wc -l\); if \[ "\$count" = "1" \] && \[ -f ''([^'']+)'' \]; then echo ok; else echo "invalid:\$count"; exit 4; fi$') {
        $rootPath = Convert-RemoteToLocalPath -RemotePath $matches[1]
        $metadataPath = Convert-RemoteToLocalPath -RemotePath $matches[2]
        $count = 0
        if (Test-Path -LiteralPath $rootPath -PathType Container) {
            $count = @(Get-ChildItem -Force -LiteralPath $rootPath).Count
        }
        if ($count -ne 1 -or -not (Test-Path -LiteralPath $metadataPath -PathType Leaf)) {
            throw "Fake stub verification failed: count=$count metadata=$metadataPath"
        }
        Write-Output "ok"
        return
    }

    $words = @(Parse-ShellWords -CommandText $CommandText)
    if ($words.Count -eq 0) {
        return
    }

    $state = Get-State
    switch ($words[0]) {
        "pm" {
            if ($words.Count -eq 3 -and $words[1] -eq "path") {
                Write-Output "package:/data/app/fake/base.apk"
                return
            }
            throw "Unsupported fake pm command: $CommandText"
        }
        "cmd" {
            if ($words.Count -ge 6 -and $words[1] -eq "package" -and $words[2] -eq "resolve-activity") {
                Write-Output $words[$words.Count - 1]
                return
            }
            throw "Unsupported fake cmd command: $CommandText"
        }
        "dumpsys" {
            if ($words.Count -ge 4 -and $words[1] -eq "activity" -and $words[2] -eq "exit-info") {
                return
            }
            throw "Unsupported fake dumpsys command: $CommandText"
        }
        "rm" {
            if ($words.Count -eq 3 -and $words[1] -eq "-f") {
                Remove-Item -LiteralPath (Convert-RemoteToLocalPath -RemotePath $words[2]) -Force -ErrorAction SilentlyContinue
                return
            }
            throw "Unsupported fake rm command: $CommandText"
        }
        "pidof" {
            $package = $words[1]
            if ($state.packageRunning -and $package -eq $state.package) {
                Write-Output $state.pid
            }
            return
        }
        "am" {
            if ($words.Count -lt 2) {
                throw "Unsupported fake am command: $CommandText"
            }
            switch ($words[1]) {
                "force-stop" {
                    $state.forceStopCount = [int]$state.forceStopCount + 1
                    $state.packageRunning = $false
                    if ($words.Count -ge 3) {
                        $state.package = $words[2]
                    }
                    Add-Operation -State $state -Kind "force-stop" -RunId $null
                    Save-State -State $state
                    return
                }
                "start" {
                    $state.startCount = [int]$state.startCount + 1
                    $state.packageRunning = $true
                    $extras = @{}
                    $component = $null
                    for ($i = 2; $i -lt $words.Count; $i++) {
                        switch ($words[$i]) {
                            "-n" {
                                $i++
                                $component = $words[$i]
                            }
                            "--es" {
                                $extras[$words[$i + 1]] = $words[$i + 2]
                                $i += 2
                            }
                            "--ei" {
                                $extras[$words[$i + 1]] = $words[$i + 2]
                                $i += 2
                            }
                            "--ez" {
                                $extras[$words[$i + 1]] = $words[$i + 2]
                                $i += 2
                            }
                        }
                    }
                    if ($component -and $component.Contains('/')) {
                        $state.package = $component.Split('/')[0]
                    }
                    if (-not $extras.ContainsKey("runId")) {
                        throw "runId extra missing from fake am start."
                    }
                    $runId = [string]$extras["runId"]
                    $smokeMode = if ($extras.ContainsKey("smokeMode")) { [string]$extras["smokeMode"] } else { $null }
                    $starts = @($state.starts)
                    $starts += [pscustomobject][ordered]@{
                        runId = $runId
                        smokeMode = $smokeMode
                    }
                    $state.starts = $starts
                    Add-Operation -State $state -Kind "start" -RunId $runId
                    Write-RunResult -State $state -Package $state.package -RunId $runId
                    Save-State -State $state
                    Write-Output ("Starting: Intent { cmp=" + $component + " }")
                    return
                }
                default {
                    throw "Unsupported fake am command: $CommandText"
                }
            }
        }
        default {
            throw "Unsupported fake adb shell command: $CommandText"
        }
    }
}

$arguments = @($args)
if ($arguments.Count -ge 2 -and $arguments[0] -eq "-s") {
    $arguments = if ($arguments.Count -gt 2) { $arguments[2..($arguments.Count - 1)] } else { @() }
}
if ($arguments.Count -eq 0) {
    throw "fake adb requires arguments"
}

switch ($arguments[0]) {
    "devices" {
        Write-Output "List of devices attached"
        Write-Output "FAKE123 device usb:1-1 product:fake model:FakeDevice device:fake"
    }
    "push" {
        $source = $arguments[1]
        $destination = Convert-RemoteToLocalPath -RemotePath $arguments[2]
        $directory = Split-Path -Parent $destination
        if (-not [string]::IsNullOrWhiteSpace($directory)) {
            New-Item -ItemType Directory -Force -Path $directory | Out-Null
        }
        Copy-Item -LiteralPath $source -Destination $destination -Force
    }
    "pull" {
        $source = Convert-RemoteToLocalPath -RemotePath $arguments[1]
        $destination = $arguments[2]
        $directory = Split-Path -Parent $destination
        if (-not [string]::IsNullOrWhiteSpace($directory)) {
            New-Item -ItemType Directory -Force -Path $directory | Out-Null
        }
        Copy-Item -LiteralPath $source -Destination $destination -Force
    }
    "shell" {
        $commandText = if ($arguments.Count -ge 2) { [string]$arguments[1] } else { "" }
        Invoke-ShellCommand -CommandText $commandText
    }
    default {
        throw "Unsupported fake adb command: $($arguments[0])"
    }
}

$global:LASTEXITCODE = 0
