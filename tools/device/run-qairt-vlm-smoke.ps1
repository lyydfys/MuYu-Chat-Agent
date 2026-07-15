param(
    [string]$Adb = "adb",
    [string]$Serial = "",
    [string]$Package = "com.muyuchat.mca",
    [string]$ModelName = "Qwen3-VL-4B-Instruct",
    [Parameter(Mandatory = $true)]
    [string]$ImagePath,
    [string]$Prompt = "",
    [ValidateRange(1, 2147483647)]
    [int]$Runs = 1,
    [AllowEmptyString()]
    [string]$SmokeMode = "api_only",
    [string]$OutDir = "docs\experiments\device-smoke\qairt-vlm",
    [ValidateRange(1, 86400)]
    [int]$TimeoutSeconds = 900,
    [ValidateRange(100, 60000)]
    [int]$PollMilliseconds = 1000,
    [ValidateRange(1, 2147483647)]
    [int]$ContextTokens = 1024,
    [ValidateRange(1, 1024)]
    [int]$Threads = 4,
    [ValidateRange(1, 2147483647)]
    [int]$MaxTokens = 32,
    [ValidateSet("reuse", "cold")]
    [string]$Lifecycle = "reuse"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($Prompt)) {
    $Prompt = [Text.Encoding]::UTF8.GetString(
        [Convert]::FromBase64String(
            "6K+355So5Lit5paH566A55+t5o+P6L+w6L+Z5byg5Zu+54mH77yM5bm25oyH5Ye65pyA5pi+6JGX55qE54mp5L2T44CC"
        )
    )
}

if ($Package -notmatch '^[A-Za-z0-9._]+$') {
    throw "Package must be an Android application id containing only letters, digits, dots, and underscores."
}
if ([string]::IsNullOrWhiteSpace($ModelName)) {
    throw "ModelName must not be empty."
}
if ($Prompt.IndexOf([char]0) -ge 0) {
    throw "Prompt must not contain a NUL character."
}
if ($Prompt -notmatch '[\u3400-\u9fff]') {
    throw "Prompt must contain Chinese text for this VLM smoke."
}
if (-not [string]::IsNullOrWhiteSpace($SmokeMode) -and
    $SmokeMode -notin @("full", "api_only", "direct_twice", "api_twice")) {
    throw "SmokeMode must be empty, full, api_only, direct_twice, or api_twice."
}

function Get-ObjectProperty {
    param([object]$Object, [string]$Name)
    if ($null -eq $Object) { return $null }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
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

function Get-MojibakeReason {
    param([AllowNull()][string]$Text)

    if ($null -eq $Text) { return $null }
    if ($Text.IndexOf([char]0xFFFD) -ge 0) {
        return "contains the Unicode replacement character U+FFFD"
    }

    $strictUtf8 = New-Object System.Text.UTF8Encoding -ArgumentList @($false, $true)
    foreach ($codePage in @(1252, 28591)) {
        try {
            $legacyEncoding = [Text.Encoding]::GetEncoding(
                $codePage,
                [Text.EncoderFallback]::ExceptionFallback,
                [Text.DecoderFallback]::ExceptionFallback
            )
            $repaired = $strictUtf8.GetString($legacyEncoding.GetBytes($Text))
            if ($repaired -cne $Text -and $repaired -match '[\u3400-\u9fff]') {
                return "looks like UTF-8 text decoded through code page $codePage"
            }
        } catch {
            continue
        }
    }
    return $null
}

function Read-StrictJsonFile {
    param(
        [string]$Path,
        [AllowEmptyString()]
        [string]$ExpectedRunId = ""
    )

    try {
        $bytes = [IO.File]::ReadAllBytes($Path)
    } catch {
        return [pscustomobject]@{
            Json = $null
            RootStatus = $null
            ParseError = "read_error: $($_.Exception.Message)"
            FailureKind = "read_error"
        }
    }

    try {
        $utf8 = New-Object System.Text.UTF8Encoding -ArgumentList @($false, $true)
        $text = $utf8.GetString($bytes)
    } catch {
        return [pscustomobject]@{
            Json = $null
            RootStatus = $null
            ParseError = "invalid_utf8: JSON bytes are not strict UTF-8: $($_.Exception.Message)"
            FailureKind = "invalid_utf8"
        }
    }

    $mojibakeReason = Get-MojibakeReason -Text $text
    if (-not [string]::IsNullOrWhiteSpace($mojibakeReason)) {
        return [pscustomobject]@{
            Json = $null
            RootStatus = $null
            ParseError = "mojibake: JSON text rejected: $mojibakeReason."
            FailureKind = "mojibake"
        }
    }
    if ([string]::IsNullOrWhiteSpace($text)) {
        return [pscustomobject]@{
            Json = $null
            RootStatus = $null
            ParseError = "parse_error: JSON file is empty."
            FailureKind = "parse_error"
        }
    }

    $trimmed = $text.TrimStart()
    if ($trimmed.Length -gt 0 -and $trimmed[0] -eq [char]0xFEFF) {
        $trimmed = $trimmed.Substring(1).TrimStart()
    }
    if ($trimmed.Length -eq 0 -or $trimmed[0] -ne '{') {
        return [pscustomobject]@{
            Json = $null
            RootStatus = $null
            ParseError = "invalid_root: JSON root must be an object."
            FailureKind = "invalid_root"
        }
    }

    try {
        $json = ConvertFrom-Json -InputObject $text -ErrorAction Stop
    } catch {
        return [pscustomobject]@{
            Json = $null
            RootStatus = $null
            ParseError = "parse_error: JSON parse failed: $($_.Exception.Message)"
            FailureKind = "parse_error"
        }
    }
    if ($json -isnot [System.Management.Automation.PSCustomObject]) {
        return [pscustomobject]@{
            Json = $null
            RootStatus = $null
            ParseError = "invalid_root: JSON root must be an object."
            FailureKind = "invalid_root"
        }
    }

    if (-not [string]::IsNullOrWhiteSpace($ExpectedRunId)) {
        $actualRunId = Get-ObjectProperty -Object $json -Name "runId"
        if ($actualRunId -isnot [string] -or [string]$actualRunId -cne $ExpectedRunId) {
            return [pscustomobject]@{
                Json = $null
                RootStatus = $null
                ParseError = "run_id_mismatch: Expected runId '$ExpectedRunId' but found '$actualRunId'."
                FailureKind = "run_id_mismatch"
            }
        }
    }

    $statusValue = Get-ObjectProperty -Object $json -Name "status"
    $rootStatus = if ($statusValue -is [string]) { [string]$statusValue } else { $null }
    return [pscustomobject]@{
        Json = $json
        RootStatus = $rootStatus
        ParseError = $null
        FailureKind = $null
    }
}

function Join-Message {
    param(
        [AllowNull()][string]$Primary,
        [AllowNull()][string]$Secondary
    )
    if ([string]::IsNullOrWhiteSpace($Secondary)) { return $Primary }
    if ([string]::IsNullOrWhiteSpace($Primary)) { return $Secondary }
    if ($Primary.Contains($Secondary)) { return $Primary }
    return "$Primary | $Secondary"
}

function Convert-ToShellLiteral {
    param([AllowNull()][string]$Value)
    if ($null -eq $Value) { return "''" }
    return "'" + $Value.Replace("'", "'\''") + "'"
}

function Assert-AdbAvailable {
    $looksLikePath = [IO.Path]::IsPathRooted($Adb) -or $Adb.Contains("\") -or $Adb.Contains("/")
    if ($looksLikePath) {
        if (-not (Test-Path -LiteralPath $Adb -PathType Leaf)) {
            throw "ADB executable not found: $Adb"
        }
    } elseif ($null -eq (Get-Command $Adb -ErrorAction SilentlyContinue)) {
        throw "ADB command not found on PATH: $Adb"
    }
}

function Invoke-AdbResult {
    param([string[]]$Arguments, [switch]$NoSerial)
    $adbArguments = @()
    if (-not $NoSerial -and -not [string]::IsNullOrWhiteSpace($script:Serial)) {
        $adbArguments += @("-s", $script:Serial)
    }
    $adbArguments += $Arguments

    $output = @()
    $exitCode = 0
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = @(& $Adb @adbArguments 2>&1)
        $exitCode = if (Test-Path variable:LASTEXITCODE) { $global:LASTEXITCODE } else { 0 }
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($null -eq $exitCode) { $exitCode = 0 }
    return [pscustomobject]@{
        ExitCode = [int]$exitCode
        Text = (@($output | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine).Trim()
    }
}

function Invoke-Adb {
    param([string[]]$Arguments, [switch]$NoSerial)
    $result = Invoke-AdbResult -Arguments $Arguments -NoSerial:$NoSerial
    if ($result.ExitCode -ne 0) {
        $detail = if ([string]::IsNullOrWhiteSpace($result.Text)) { "no output" } else { $result.Text }
        throw "ADB failed (exit $($result.ExitCode)): $detail"
    }
    return $result.Text
}

function Require-Device {
    $result = Invoke-AdbResult -Arguments @("devices", "-l") -NoSerial
    if ($result.ExitCode -ne 0) {
        throw "Unable to list ADB devices: $($result.Text)"
    }
    $devices = @()
    foreach ($line in @($result.Text -split "\r?\n" | Select-Object -Skip 1)) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $parts = $line.Trim() -split "\s+"
        if ($parts.Count -ge 2 -and $parts[1] -eq "device") {
            $devices += $parts[0]
        }
    }
    if (-not [string]::IsNullOrWhiteSpace($script:Serial)) {
        if ($script:Serial -notin $devices) {
            throw "ADB device $script:Serial is not connected or authorized."
        }
    } elseif ($devices.Count -eq 1) {
        $script:Serial = $devices[0]
    } elseif ($devices.Count -eq 0) {
        throw "No authorized ADB device is connected."
    } else {
        throw "Multiple ADB devices are connected. Pass -Serial."
    }
}

function Invoke-RemoteShell {
    param([string[]]$Arguments)
    $command = ($Arguments | ForEach-Object { Convert-ToShellLiteral ([string]$_) }) -join " "
    return Invoke-Adb -Arguments @("shell", $command)
}

function Test-RemoteFile {
    param([string]$Path)
    $command = "if [ -f $(Convert-ToShellLiteral $Path) ]; then echo yes; else echo no; fi"
    $result = Invoke-AdbResult -Arguments @("shell", $command)
    return $result.ExitCode -eq 0 -and $result.Text.Trim() -eq "yes"
}

function Get-RemoteJsonSnapshot {
    param(
        [string]$Path,
        [string]$ExpectedRunId
    )

    if (-not (Test-RemoteFile -Path $Path)) {
        return [pscustomobject]@{
            Exists = $false
            Json = $null
            ParseError = $null
            FailureKind = $null
            RootStatus = $null
            TerminalStatus = $null
            Malformed = $false
        }
    }

    $snapshotPath = Join-Path ([IO.Path]::GetTempPath()) ("qairt-vlm-poll-" + [Guid]::NewGuid().ToString("N") + ".json")
    try {
        $pullResult = Invoke-AdbResult -Arguments @("pull", $Path, $snapshotPath)
        if ($pullResult.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $snapshotPath -PathType Leaf)) {
            $detail = if ([string]::IsNullOrWhiteSpace($pullResult.Text)) { "no output" } else { $pullResult.Text }
            return [pscustomobject]@{
                Exists = $true
                Json = $null
                ParseError = "ADB snapshot pull failed: $detail"
                FailureKind = "snapshot_pull_failed"
                RootStatus = $null
                TerminalStatus = $null
                Malformed = $false
            }
        }

        $parsed = Read-StrictJsonFile -Path $snapshotPath -ExpectedRunId $ExpectedRunId
        $terminalStatus = if ($parsed.RootStatus -ceq "completed" -or $parsed.RootStatus -ceq "failed") {
            $parsed.RootStatus
        } else {
            $null
        }
        return [pscustomobject]@{
            Exists = $true
            Json = $parsed.Json
            ParseError = $parsed.ParseError
            FailureKind = $parsed.FailureKind
            RootStatus = $parsed.RootStatus
            TerminalStatus = $terminalStatus
            Malformed = -not [string]::IsNullOrWhiteSpace($parsed.ParseError)
        }
    } finally {
        Remove-Item -LiteralPath $snapshotPath -Force -ErrorAction SilentlyContinue
    }
}

function Get-PackageProcessSnapshot {
    param([string]$PackageName)

    $pidofResult = Invoke-AdbResult -Arguments @("shell", "pidof $(Convert-ToShellLiteral $PackageName)")
    $pidText = $pidofResult.Text.Trim()
    $pidList = @(
        if (-not [string]::IsNullOrWhiteSpace($pidText)) {
            $pidText -split '\s+' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
        }
    )
    $canDetermine = -not ($pidofResult.ExitCode -ne 0 -and $pidofResult.Text -match '(?i)not found|inaccessible|unknown')
    return [pscustomobject]@{
        Package = $PackageName
        Probe = "pidof"
        ExitCode = $pidofResult.ExitCode
        Output = $pidofResult.Text
        Pids = $pidText
        PidList = $pidList
        CanDetermine = $canDetermine
        IsRunning = $pidList.Count -gt 0
        CheckedAt = (Get-Date).ToString("o")
    }
}

function Wait-SmokeTerminal {
    param(
        [string]$RemoteJson,
        [string]$PackageName,
        [string]$ExpectedRunId,
        [AllowNull()][object]$InitialProcess
    )

    # This host-side ADB deadline is the hard boundary for JNI that blocks the app process.
    # The app deliberately does not cancel a native call and then continue reusing that process.
    $waitStartedAt = Get-Date
    $deadline = $waitStartedAt.AddSeconds($TimeoutSeconds)
    $startupGraceMilliseconds = [Math]::Min([Math]::Max(($PollMilliseconds * 2), 1500), 5000)
    $processGraceDeadline = (Get-Date).AddMilliseconds($startupGraceMilliseconds)
    $lastSnapshot = $null
    $lastProcess = $InitialProcess
    $firstProcess = $null
    $lastRunningProcess = $null
    $processSeen = $false
    $observedPids = New-Object System.Collections.ArrayList

    if ($null -ne $InitialProcess -and $InitialProcess.IsRunning) {
        $processSeen = $true
        $firstProcess = $InitialProcess
        $lastRunningProcess = $InitialProcess
        foreach ($pidValue in @($InitialProcess.PidList)) {
            if (-not $observedPids.Contains([string]$pidValue)) {
                [void]$observedPids.Add([string]$pidValue)
            }
        }
    }

    while ((Get-Date) -lt $deadline) {
        $snapshot = Get-RemoteJsonSnapshot -Path $RemoteJson -ExpectedRunId $ExpectedRunId
        $lastSnapshot = $snapshot
        $process = Get-PackageProcessSnapshot -PackageName $PackageName
        $lastProcess = $process

        if ($process.IsRunning) {
            if (-not $processSeen) {
                $processSeen = $true
                $firstProcess = $process
            }
            $lastRunningProcess = $process
            foreach ($pidValue in @($process.PidList)) {
                if (-not $observedPids.Contains([string]$pidValue)) {
                    [void]$observedPids.Add([string]$pidValue)
                }
            }
        } elseif ($processSeen -and $process.CanDetermine) {
            return [pscustomobject]@{
                Outcome = "process_died"
                Json = $snapshot.Json
                ParseError = $snapshot.ParseError
                FailureKind = "process_died"
                TerminalStatus = $null
                Process = $process
                ProcessSeen = $true
                FirstProcess = $firstProcess
                LastRunningProcess = $lastRunningProcess
                ObservedPids = @($observedPids)
                RemoteJsonObserved = $snapshot.Exists
                ExitPhase = if ($snapshot.Exists) { "after_result_file" } else { "before_result_file" }
                StartupGraceMilliseconds = $startupGraceMilliseconds
                WaitElapsedMilliseconds = [long](((Get-Date) - $waitStartedAt).TotalMilliseconds)
            }
        }

        if ($snapshot.Malformed) {
            return [pscustomobject]@{
                Outcome = "invalid_result"
                Json = $null
                ParseError = $snapshot.ParseError
                FailureKind = $snapshot.FailureKind
                TerminalStatus = $null
                Process = $process
                ProcessSeen = $processSeen
                FirstProcess = $firstProcess
                LastRunningProcess = $lastRunningProcess
                ObservedPids = @($observedPids)
                RemoteJsonObserved = $true
                ExitPhase = $null
                StartupGraceMilliseconds = $startupGraceMilliseconds
            }
        }

        if ($snapshot.TerminalStatus -ceq "completed" -or $snapshot.TerminalStatus -ceq "failed") {
            return [pscustomobject]@{
                Outcome = "terminal"
                Json = $snapshot.Json
                ParseError = $snapshot.ParseError
                FailureKind = $snapshot.FailureKind
                TerminalStatus = $snapshot.TerminalStatus
                Process = $process
                ProcessSeen = $processSeen
                FirstProcess = $firstProcess
                LastRunningProcess = $lastRunningProcess
                ObservedPids = @($observedPids)
                RemoteJsonObserved = $snapshot.Exists
                ExitPhase = $null
                StartupGraceMilliseconds = $startupGraceMilliseconds
            }
        }

        if (-not $processSeen -and (Get-Date) -ge $processGraceDeadline -and $process.CanDetermine) {
            return [pscustomobject]@{
                Outcome = "process_exited"
                Json = $snapshot.Json
                ParseError = $snapshot.ParseError
                FailureKind = "process_exited"
                TerminalStatus = $null
                Process = $process
                ProcessSeen = $false
                FirstProcess = $null
                LastRunningProcess = $null
                ObservedPids = @()
                RemoteJsonObserved = $snapshot.Exists
                ExitPhase = if ($snapshot.Exists) { "after_result_file" } else { "before_result_file" }
                StartupGraceMilliseconds = $startupGraceMilliseconds
                WaitElapsedMilliseconds = [long](((Get-Date) - $waitStartedAt).TotalMilliseconds)
            }
        }
        Start-Sleep -Milliseconds $PollMilliseconds
    }

    return [pscustomobject]@{
        Outcome = "timed_out"
        Json = if ($null -ne $lastSnapshot) { $lastSnapshot.Json } else { $null }
        ParseError = if ($null -ne $lastSnapshot) { $lastSnapshot.ParseError } else { $null }
        FailureKind = "timed_out"
        TerminalStatus = $null
        Process = $lastProcess
        ProcessSeen = $processSeen
        FirstProcess = $firstProcess
        LastRunningProcess = $lastRunningProcess
        ObservedPids = @($observedPids)
        RemoteJsonObserved = if ($null -ne $lastSnapshot) { $lastSnapshot.Exists } else { $false }
        ExitPhase = $null
        StartupGraceMilliseconds = $startupGraceMilliseconds
    }
}

function Shorten-Text {
    param([AllowNull()][string]$Value, [int]$Maximum = 240)
    if ([string]::IsNullOrWhiteSpace($Value)) { return $null }
    $clean = ($Value -replace "\s+", " ").Trim()
    if ($clean.Length -le $Maximum) { return $clean }
    return $clean.Substring(0, $Maximum) + "..."
}

function Test-JsonBooleanTrue {
    param([AllowNull()][object]$Value)
    return $Value -is [bool] -and [bool]$Value
}

function New-RunSummary {
    param(
        [int]$RunNumber,
        [string]$RunId,
        [string]$RemoteJson,
        [string]$LocalJson,
        [object]$Json,
        [string]$FallbackStatus,
        [string]$FallbackError,
        [string]$ParseError,
        [AllowNull()][string]$FailureKind,
        [bool]$RawResultPreserved,
        [string]$ProcessEvidencePath
    )
    $eventsValue = Get-ObjectProperty -Object $Json -Name "events"
    $events = if ($null -eq $eventsValue) { @() } else { @($eventsValue) }
    $rootStatusValue = Get-ObjectProperty -Object $Json -Name "status"
    $rootStatus = if ($rootStatusValue -is [string]) { [string]$rootStatusValue } else { $null }
    if ($FallbackStatus -in @("invalid_result", "process_died", "process_exited", "timed_out", "tool_failed")) {
        $status = $FallbackStatus
    } elseif ($rootStatus -ceq "completed" -or $rootStatus -ceq "failed") {
        $status = $rootStatus
    } else {
        $status = "failed"
    }

    $rootFailureKind = Get-ObjectProperty -Object $Json -Name "failureKind"
    if ([string]::IsNullOrWhiteSpace($FailureKind) -and $rootFailureKind -is [string]) {
        $FailureKind = [string]$rootFailureKind
    }

    $loadMs = $null
    $nativeStats = Get-ObjectProperty -Object $Json -Name "nativeStats"
    $outputPreview = $null
    $errorText = $FallbackError
    $keyStages = New-Object System.Collections.ArrayList
    $destroyFailureStages = New-Object System.Collections.ArrayList
    $rootDestroyFailed = Get-ObjectProperty -Object $Json -Name "destroyFailed"
    $destroyFailed = Test-JsonBooleanTrue -Value $rootDestroyFailed

    foreach ($event in $events) {
        $eventStatus = [string](Get-ObjectProperty -Object $event -Name "status")
        $stage = [string](Get-ObjectProperty -Object $event -Name "stage")
        $eventLoadMs = Get-ObjectProperty -Object $event -Name "loadMs"
        if ($null -ne $eventLoadMs) { $loadMs = $eventLoadMs }
        $eventStats = Get-ObjectProperty -Object $event -Name "nativeStats"
        if ($null -ne $eventStats) { $nativeStats = $eventStats }
        $eventError = Get-ObjectProperty -Object $event -Name "error"
        if (-not [string]::IsNullOrWhiteSpace([string]$eventError)) {
            $errorText = Join-Message -Primary $errorText -Secondary ([string]$eventError)
        }

        if ($eventStatus -eq "runner_stage") {
            if (-not [string]::IsNullOrWhiteSpace($stage) -and -not $keyStages.Contains($stage)) {
                [void]$keyStages.Add($stage)
            }
        } elseif ($eventStatus -match '(load|generation|api_engine|completed|failed)') {
            if (-not [string]::IsNullOrWhiteSpace($eventStatus) -and -not $keyStages.Contains($eventStatus)) {
                [void]$keyStages.Add($eventStatus)
            }
        }

        if ($stage -match '(?i)destroy_failed' -or $eventStatus -match '(?i)destroy_failed') {
            $destroyFailed = $true
            $failureStage = if (-not [string]::IsNullOrWhiteSpace($stage)) { $stage } else { $eventStatus }
            if (-not $destroyFailureStages.Contains($failureStage)) {
                [void]$destroyFailureStages.Add($failureStage)
            }
        }

        $generation = Get-ObjectProperty -Object $event -Name "generation"
        $preview = Get-ObjectProperty -Object $generation -Name "textPreview"
        if (-not [string]::IsNullOrWhiteSpace([string]$preview)) { $outputPreview = [string]$preview }
        $directPreview = Get-ObjectProperty -Object $event -Name "textPreview"
        if (-not [string]::IsNullOrWhiteSpace([string]$directPreview)) { $outputPreview = [string]$directPreview }
        $apiEngine = Get-ObjectProperty -Object $event -Name "apiEngine"
        if ($null -ne $apiEngine -and [string]::IsNullOrWhiteSpace($outputPreview)) {
            $visibleSeen = Get-ObjectProperty -Object $apiEngine -Name "visibleSeen"
            $responseBytes = Get-ObjectProperty -Object $apiEngine -Name "responseBytes"
            $outputPreview = "api visibleSeen=$visibleSeen responseBytes=$responseBytes"
        }
    }

    $declaredFailureStages = Get-ObjectProperty -Object $Json -Name "destroyFailureStages"
    foreach ($failureStage in @($declaredFailureStages)) {
        if (-not [string]::IsNullOrWhiteSpace([string]$failureStage) -and -not $destroyFailureStages.Contains([string]$failureStage)) {
            [void]$destroyFailureStages.Add([string]$failureStage)
        }
    }
    if ($destroyFailureStages.Count -gt 0) { $destroyFailed = $true }

    if ([string]::IsNullOrWhiteSpace($errorText) -and -not [string]::IsNullOrWhiteSpace($ParseError)) {
        $errorText = $ParseError
    }
    if (($FallbackStatus -ceq "completed" -or $FallbackStatus -ceq "failed") -and
        -not ($rootStatus -ceq "completed" -or $rootStatus -ceq "failed")) {
        $errorText = Join-Message -Primary $errorText -Secondary "Pulled JSON root does not contain status=completed or status=failed."
    }

    $visionReady = Get-ObjectProperty -Object $nativeStats -Name "visionReady"
    if ($status -ceq "completed") {
        $contractErrors = New-Object System.Collections.ArrayList
        if (-not [string]::IsNullOrWhiteSpace($ParseError)) {
            [void]$contractErrors.Add("Strict UTF-8/JSON validation failed: $ParseError")
        }
        if (-not $RawResultPreserved) {
            [void]$contractErrors.Add("Raw result JSON was not preserved by the final adb pull.")
        }
        if (-not (Test-JsonBooleanTrue -Value $visionReady)) {
            [void]$contractErrors.Add("QAIRT VLM completion requires nativeStats.visionReady=true as a JSON boolean.")
        }
        if ($destroyFailed) {
            [void]$contractErrors.Add("Native teardown reported destroy_failed.")
        }
        if ($contractErrors.Count -gt 0) {
            $status = "failed"
            $errorText = Join-Message -Primary $errorText -Secondary (@($contractErrors) -join " | ")
        }
    }

    return [pscustomobject]@{
        run = $RunNumber
        runId = $RunId
        status = [string]$status
        failureKind = $FailureKind
        terminalRootStatus = $rootStatus
        loadMs = $loadMs
        visionReady = $visionReady
        outputPreview = Shorten-Text -Value $outputPreview
        keyStages = @($keyStages)
        stageTrace = (@($keyStages) -join " -> ")
        nativeStats = $nativeStats
        destroyFailed = [bool]$destroyFailed
        destroyFailureStages = @($destroyFailureStages)
        error = Shorten-Text -Value $errorText
        parseError = Shorten-Text -Value $ParseError
        rawResultPreserved = $RawResultPreserved
        processEvidence = $ProcessEvidencePath
        remoteJson = $RemoteJson
        localJson = $LocalJson
    }
}
function Assert-SmokeSessionSummarySchema {
    param([object]$Summary)

    if ($Summary -isnot [System.Management.Automation.PSCustomObject]) {
        throw "Smoke session summary must be a PSCustomObject, not $($Summary.GetType().FullName)."
    }
    foreach ($name in @("sessionId", "serial", "package", "modelName", "runtime", "lifecycle", "smokeMode", "runs")) {
        if ($null -eq $Summary.PSObject.Properties[$name]) {
            throw "Smoke session summary is missing required property '$name'."
        }
    }
    if ([string]::IsNullOrWhiteSpace([string]$Summary.sessionId) -or
        [string]::IsNullOrWhiteSpace([string]$Summary.package)) {
        throw "Smoke session summary has an empty sessionId or package."
    }
    if ($Summary.runs -is [System.Management.Automation.Host.PSHost] -or
        $Summary.runs -is [System.Management.Automation.Runspaces.Runspace]) {
        throw "Smoke session summary runs must not contain PowerShell host or runspace objects."
    }
    foreach ($runSummary in @($Summary.runs)) {
        if ($runSummary -isnot [System.Management.Automation.PSCustomObject]) {
            throw "Smoke run summary must be a PSCustomObject."
        }
        foreach ($name in @("run", "runId", "status", "rawResultPreserved", "remoteJson", "localJson")) {
            if ($null -eq $runSummary.PSObject.Properties[$name]) {
                throw "Smoke run summary is missing required property '$name'."
            }
        }
        if ($runSummary.rawResultPreserved -isnot [bool]) {
            throw "Smoke run summary rawResultPreserved must be a JSON boolean."
        }
    }
}

function Get-SafeName {
    param([string]$Value)
    $safe = ($Value -replace '[^A-Za-z0-9._-]+', '-').Trim('-', '.')
    if ([string]::IsNullOrWhiteSpace($safe)) { return "model" }
    if ($safe.Length -gt 48) { return $safe.Substring(0, 48) }
    return $safe
}

Assert-AdbAvailable
Require-Device

$sessionId = "qairt-vlm-$((Get-Date).ToString('yyyyMMdd-HHmmssfff'))-$([Guid]::NewGuid().ToString('N').Substring(0, 8))"
$externalRoot = "/storage/emulated/0/Android/data/$Package/files"
$stubRoot = "$externalRoot/qairt_vlm_smoke/stubs/$sessionId"
$remoteMetadata = "$stubRoot/metadata.json"
$runOutputDir = Join-Path $OutDir $sessionId
New-Item -ItemType Directory -Force -Path $runOutputDir | Out-Null

$tempDir = Join-Path ([IO.Path]::GetTempPath()) $sessionId
$tempMetadata = Join-Path $tempDir "metadata.json"
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
try {
    $metadata = [ordered]@{
        model_name = $ModelName
        model_type = "vlm"
        architecture = "qwen3-vl"
        modalities = @("text", "vision")
        smoke_stub = $true
        # Mirror the verified Qwen3-VL segmented KV graph for service admission.
        model_files = [ordered]@{
            "part2_of_4.bin" = [ordered]@{
                inputs = [ordered]@{
                    "past_key_0_in" = [ordered]@{ shape = @(1, 1, 128, 1023); dtype = "uint8" }
                    "past_value_0_in" = [ordered]@{ shape = @(1, 1, 1023, 128); dtype = "uint8" }
                }
            }
        }
    }
    Write-Utf8File -Path $tempMetadata -Content ($metadata | ConvertTo-Json -Depth 4)
    Invoke-Adb -Arguments @("shell", "mkdir -p $(Convert-ToShellLiteral $stubRoot)") | Out-Null
    Invoke-Adb -Arguments @("push", $tempMetadata, $remoteMetadata) | Out-Null
    $verifyStub = 'count=$(ls -1A ' + (Convert-ToShellLiteral $stubRoot) +
        ' 2>/dev/null | wc -l); if [ "$count" = "1" ] && [ -f ' +
        (Convert-ToShellLiteral $remoteMetadata) +
        ' ]; then echo ok; else echo "invalid:$count"; exit 4; fi'
    $stubResult = Invoke-AdbResult -Arguments @("shell", $verifyStub)
    if ($stubResult.ExitCode -ne 0 -or $stubResult.Text.Trim() -ne "ok") {
        throw "Stub bundle verification failed: $($stubResult.Text)"
    }
} finally {
    if (Test-Path -LiteralPath $tempMetadata -PathType Leaf) {
        Remove-Item -LiteralPath $tempMetadata -Force
    }
    if (Test-Path -LiteralPath $tempDir -PathType Container) {
        Remove-Item -LiteralPath $tempDir -Force
    }
}

$deviceImagePath = $ImagePath
if (-not $ImagePath.StartsWith("/")) {
    if (-not (Test-Path -LiteralPath $ImagePath -PathType Leaf)) {
        throw "Local image not found: $ImagePath"
    }
    $localImage = (Resolve-Path -LiteralPath $ImagePath).ProviderPath
    if ((Get-Item -LiteralPath $localImage).Length -le 0) {
        throw "Local image is empty: $localImage"
    }
    $extension = [IO.Path]::GetExtension($localImage)
    if ($extension -notmatch '^\.[A-Za-z0-9]{1,8}$') { $extension = ".jpg" }
    $remoteImageDir = "$externalRoot/qairt_vlm_smoke/images/$sessionId"
    $deviceImagePath = "$remoteImageDir/input$extension"
    Invoke-Adb -Arguments @("shell", "mkdir -p $(Convert-ToShellLiteral $remoteImageDir)") | Out-Null
    Invoke-Adb -Arguments @("push", $localImage, $deviceImagePath) | Out-Null
}
if (-not (Test-RemoteFile -Path $deviceImagePath)) {
    throw "Device image not found after preparation: $deviceImagePath"
}

Write-Host "Device: $script:Serial"
Write-Host "Stub: $stubRoot"
Write-Host "Image: $deviceImagePath"
Write-Host "Output: $runOutputDir"
Write-Host "Lifecycle: $Lifecycle"

$component = "$Package/.debug.LocalChatSmokeActivity"
$safeModelName = Get-SafeName -Value $ModelName
$summaries = @()
for ($run = 1; $run -le $Runs; $run++) {
    $runId = "$safeModelName-vlm-$((Get-Date).ToString('yyyyMMdd-HHmmssfff'))-r$run"
    $remoteJson = "$externalRoot/chat_smoke/runs/$runId.json"
    $localJson = Join-Path $runOutputDir "$runId.json"
    $processEvidencePath = $null
    $rawResultPreserved = $false
    $failureKind = $null
    Write-Host "[$run/$Runs] Starting $runId"

    try {
        if ($Lifecycle -eq "cold") {
            Invoke-RemoteShell -Arguments @("am", "force-stop", $Package) | Out-Null
            Start-Sleep -Milliseconds 500
        }

        $activityArguments = @(
            "am", "start", "-W", "-n", $component,
            "--es", "runtime", "geniex_qairt",
            "--es", "modelPath", $stubRoot,
            "--es", "displayName", $ModelName,
            "--es", "imagePath", $deviceImagePath,
            "--es", "prompt", $Prompt,
            "--es", "runId", $runId,
            "--ei", "nCtx", [string]$ContextTokens,
            "--ei", "nThreads", [string]$Threads,
            "--ei", "maxTokens", [string]$MaxTokens
        )
        if (-not [string]::IsNullOrWhiteSpace($SmokeMode)) {
            $activityArguments += @("--es", "smokeMode", $SmokeMode)
        }
        $startText = Invoke-RemoteShell -Arguments $activityArguments
        if ($startText -match 'Error type 3|does not exist|Permission Denial') {
            throw "Unable to start debug LocalChatSmokeActivity: $startText"
        }

        $initialProcess = Get-PackageProcessSnapshot -PackageName $Package
        $waitResult = Wait-SmokeTerminal             -RemoteJson $remoteJson             -PackageName $Package             -ExpectedRunId $runId             -InitialProcess $initialProcess
        $terminalJson = $null
        $fallbackStatus = $null
        $fallbackError = $null
        switch ($waitResult.Outcome) {
            "terminal" {
                $fallbackStatus = $waitResult.TerminalStatus
                $failureKind = $waitResult.FailureKind
            }
            "invalid_result" {
                $fallbackStatus = "invalid_result"
                $failureKind = $waitResult.FailureKind
                $fallbackError = "Remote result was rejected by strict UTF-8/JSON validation: $($waitResult.ParseError)"
            }
            "process_died" {
                $fallbackStatus = "process_died"
                $failureKind = "process_died"
                $fallbackError = if ($waitResult.RemoteJsonObserved) {
                    "Package PID was observed and then disappeared after $remoteJson appeared; any terminal JSON is not trusted."
                } else {
                    "Package PID was observed and then disappeared before $remoteJson was created."
                }
                $processEvidencePath = Join-Path $runOutputDir "$runId.process-died.json"
                $processEvidence = [pscustomobject][ordered]@{
                    runId = $runId
                    status = "process_died"
                    failureKind = "process_died"
                    remoteJson = $remoteJson
                    remoteJsonObserved = $waitResult.RemoteJsonObserved
                    exitPhase = $waitResult.ExitPhase
                    observedAt = (Get-Date).ToString("o")
                    observedAfterMilliseconds = $waitResult.WaitElapsedMilliseconds
                    startupGraceMilliseconds = $waitResult.StartupGraceMilliseconds
                    observedPids = @($waitResult.ObservedPids)
                    firstProcess = $waitResult.FirstProcess
                    lastRunningProcess = $waitResult.LastRunningProcess
                    process = $waitResult.Process
                    remoteParseError = $waitResult.ParseError
                    lifecycle = $Lifecycle
                    smokeMode = $SmokeMode
                    diagnosticHint = "Inspect logcat, dumpsys activity exit-info, and Dropbox for lmkd, lowmemorykiller, DMA-BUF, or OOM evidence."
                }
                $processEvidenceJson = ConvertTo-Json -InputObject $processEvidence -Depth 10
                Write-Utf8File -Path $processEvidencePath -Content $processEvidenceJson
            }
            "process_exited" {
                $fallbackStatus = "process_exited"
                $failureKind = "process_exited"
                $fallbackError = if ($waitResult.RemoteJsonObserved) {
                    "Package process was not observed running and $remoteJson remained non-terminal."
                } else {
                    "Package process was not observed running before $remoteJson was created."
                }
                $processEvidencePath = Join-Path $runOutputDir "$runId.process-exited.json"
                $processEvidence = [pscustomobject][ordered]@{
                    runId = $runId
                    status = "process_exited"
                    failureKind = "process_exited"
                    remoteJson = $remoteJson
                    remoteJsonObserved = $waitResult.RemoteJsonObserved
                    exitPhase = $waitResult.ExitPhase
                    observedAt = (Get-Date).ToString("o")
                    observedAfterMilliseconds = $waitResult.WaitElapsedMilliseconds
                    startupGraceMilliseconds = $waitResult.StartupGraceMilliseconds
                    process = $waitResult.Process
                    remoteParseError = $waitResult.ParseError
                    lifecycle = $Lifecycle
                    smokeMode = $SmokeMode
                }
                $processEvidenceJson = ConvertTo-Json -InputObject $processEvidence -Depth 8
                Write-Utf8File -Path $processEvidencePath -Content $processEvidenceJson
            }
            default {
                $fallbackStatus = "timed_out"
                $failureKind = "timed_out"
                $fallbackError = "Timed out after $TimeoutSeconds seconds waiting for completed/failed."
            }
        }

        $parseError = $waitResult.ParseError
        $terminalJson = $null
        if (Test-RemoteFile -Path $remoteJson) {
            Remove-Item -LiteralPath $localJson -Force -ErrorAction SilentlyContinue
            Invoke-Adb -Arguments @("pull", $remoteJson, $localJson) | Out-Null
            $rawResultPreserved = Test-Path -LiteralPath $localJson -PathType Leaf
            if ($rawResultPreserved) {
                $parsedFinal = Read-StrictJsonFile -Path $localJson -ExpectedRunId $runId
                if (-not [string]::IsNullOrWhiteSpace($parsedFinal.ParseError)) {
                    if ([string]::IsNullOrWhiteSpace($failureKind)) {
                        $failureKind = $parsedFinal.FailureKind
                    }
                    $parseError = Join-Message -Primary $parseError -Secondary "Pulled JSON rejected: $($parsedFinal.ParseError)"
                } else {
                    $terminalJson = $parsedFinal.Json
                    if ($waitResult.Outcome -eq "terminal" -and
                        -not ($parsedFinal.RootStatus -ceq "completed" -or $parsedFinal.RootStatus -ceq "failed")) {
                        $fallbackError = Join-Message -Primary $fallbackError -Secondary "Pulled JSON root status is no longer terminal."
                    }
                }
            } else {
                $fallbackError = Join-Message -Primary $fallbackError -Secondary "Remote JSON existed but pull did not leave a local file."
            }
        } else {
            if ($waitResult.Outcome -eq "process_exited" -or $waitResult.Outcome -eq "process_died") {
                $missingRemoteMessage = if ($waitResult.RemoteJsonObserved) {
                    "Remote result JSON was observed during execution but was no longer present when pull was attempted."
                } else {
                    "Remote result JSON was not created before process exit."
                }
                $fallbackError = Join-Message -Primary $fallbackError -Secondary $missingRemoteMessage
            } else {
                $fallbackError = Join-Message -Primary $fallbackError -Secondary "Remote result JSON was not present for the final evidence pull."
            }
        }

        if (-not [string]::IsNullOrWhiteSpace($parseError)) {
            $fallbackError = Join-Message -Primary $fallbackError -Secondary $parseError
        }
        $summary = New-RunSummary `
            -RunNumber $run `
            -RunId $runId `
            -RemoteJson $remoteJson `
            -LocalJson $localJson `
            -Json $terminalJson `
            -FallbackStatus $fallbackStatus `
            -FallbackError $fallbackError `
            -ParseError $parseError `
            -FailureKind $failureKind `
            -RawResultPreserved $rawResultPreserved `
            -ProcessEvidencePath $processEvidencePath
        $summaries += $summary
        Write-Host "[$run/$Runs] status=$($summary.status) loadMs=$($summary.loadMs) visionReady=$($summary.visionReady)"
    } catch {
        $summaries += New-RunSummary `
            -RunNumber $run `
            -RunId $runId `
            -RemoteJson $remoteJson `
            -LocalJson $localJson `
            -Json $null `
            -FallbackStatus "tool_failed" `
            -FallbackError $_.Exception.Message `
            -ParseError $null `
            -FailureKind "tool_failed" `
            -RawResultPreserved $rawResultPreserved `
            -ProcessEvidencePath $processEvidencePath
        Write-Warning "[$run/$Runs] $($_.Exception.Message)"
    }
}

$sessionSummary = [pscustomobject][ordered]@{
    sessionId = $sessionId
    serial = $script:Serial
    package = $Package
    modelName = $ModelName
    runtime = "geniex_qairt"
    lifecycle = $Lifecycle
    stubPath = $stubRoot
    imagePath = $deviceImagePath
    smokeMode = $SmokeMode
    runs = @($summaries)
}
Assert-SmokeSessionSummarySchema -Summary $sessionSummary
$sessionSummaryJson = ConvertTo-Json -InputObject $sessionSummary -Depth 20
$summaryPath = Join-Path $runOutputDir "summary.json"
$hostSummaryPath = Join-Path $runOutputDir "host-summary.json"
Write-Utf8File -Path $summaryPath -Content $sessionSummaryJson
Write-Utf8File -Path $hostSummaryPath -Content $sessionSummaryJson

$summaries |
    Select-Object run, status, failureKind, loadMs, visionReady, destroyFailed, outputPreview, stageTrace, error, parseError, rawResultPreserved, localJson |
    Format-Table -AutoSize -Wrap
Write-Host "Summary: $summaryPath"

$failed = @($summaries | Where-Object { $_.status -ne "completed" })
if ($failed.Count -gt 0) {
    throw "$($failed.Count) of $Runs QAIRT VLM smoke run(s) did not complete successfully."
}
