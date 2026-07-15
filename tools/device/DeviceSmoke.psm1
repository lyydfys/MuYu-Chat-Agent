Set-StrictMode -Version Latest

function Get-DeviceSmokeProperty {
    param([AllowNull()][object]$Object, [string]$Name)

    if ($null -eq $Object) { return $null }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function Write-DeviceSmokeUtf8File {
    param(
        [string]$Path,
        [AllowEmptyString()][string]$Content
    )

    $directory = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($directory)) {
        New-Item -ItemType Directory -Force -Path $directory | Out-Null
    }
    $utf8 = New-Object System.Text.UTF8Encoding $false
    [IO.File]::WriteAllText($Path, $Content, $utf8)
}

function Get-DeviceSmokeSafeName {
    param([AllowNull()][string]$Value)

    $source = if ($null -eq $Value) { '' } else { $Value }
    $safe = ($source -replace '[^A-Za-z0-9._-]+', '-').Trim('-', '.')
    if ([string]::IsNullOrWhiteSpace($safe)) { return 'smoke' }
    if ($safe.Length -gt 56) { return $safe.Substring(0, 56) }
    return $safe
}

function New-DeviceSmokeSessionId {
    param([string]$Prefix)

    return "$Prefix-$((Get-Date).ToString('yyyyMMdd-HHmmssfff'))-$([Guid]::NewGuid().ToString('N').Substring(0, 8))"
}

function ConvertTo-DeviceSmokeShellLiteral {
    param([AllowNull()][string]$Value)

    if ($null -eq $Value) { return "''" }
    if ($Value.IndexOf([char]0) -ge 0) { throw 'Remote shell arguments must not contain NUL.' }
    if ($Value.Contains("`r") -or $Value.Contains("`n")) { throw 'Remote shell arguments must not contain newlines.' }
    return "'" + $Value.Replace("'", "'\''") + "'"
}

function Assert-DeviceSmokeAdbAvailable {
    param([string]$Adb)

    $looksLikePath = [IO.Path]::IsPathRooted($Adb) -or $Adb.Contains('\') -or $Adb.Contains('/')
    if ($looksLikePath) {
        if (-not (Test-Path -LiteralPath $Adb -PathType Leaf)) {
            throw "ADB executable not found: $Adb"
        }
    } elseif ($null -eq (Get-Command $Adb -ErrorAction SilentlyContinue)) {
        throw "ADB command not found on PATH: $Adb"
    }
}

function Invoke-DeviceSmokeAdbResult {
    param(
        [string]$Adb,
        [AllowEmptyString()][string]$Serial,
        [string[]]$Arguments,
        [switch]$NoSerial
    )

    $adbArguments = @()
    if (-not $NoSerial -and -not [string]::IsNullOrWhiteSpace($Serial)) {
        $adbArguments += @('-s', $Serial)
    }
    $adbArguments += $Arguments
    $output = @()
    $exitCode = 0
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $global:LASTEXITCODE = 0
    try {
        $output = @(& $Adb @adbArguments 2>&1)
        $exitCode = $global:LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($null -eq $exitCode) { $exitCode = 0 }
    return [pscustomobject]@{
        ExitCode = [int]$exitCode
        Text = (@($output | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine).Trim()
    }
}

function Invoke-DeviceSmokeAdb {
    param(
        [string]$Adb,
        [AllowEmptyString()][string]$Serial,
        [string[]]$Arguments,
        [switch]$NoSerial
    )

    $result = Invoke-DeviceSmokeAdbResult -Adb $Adb -Serial $Serial -Arguments $Arguments -NoSerial:$NoSerial
    if ($result.ExitCode -ne 0) {
        $detail = if ([string]::IsNullOrWhiteSpace($result.Text)) { 'no output' } else { $result.Text }
        throw "ADB failed (exit $($result.ExitCode)): $detail"
    }
    return $result.Text
}

function Initialize-DeviceSmokeDevice {
    param(
        [string]$Adb,
        [AllowEmptyString()][string]$Serial
    )

    Assert-DeviceSmokeAdbAvailable -Adb $Adb
    $result = Invoke-DeviceSmokeAdbResult -Adb $Adb -Serial '' -Arguments @('devices', '-l') -NoSerial
    if ($result.ExitCode -ne 0) {
        throw "Unable to list ADB devices: $($result.Text)"
    }

    $devices = @()
    foreach ($line in @($result.Text -split "`r?`n" | Select-Object -Skip 1)) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $parts = $line.Trim() -split '\s+'
        if ($parts.Count -ge 2 -and $parts[1] -eq 'device') {
            $devices += $parts[0]
        }
    }

    if (-not [string]::IsNullOrWhiteSpace($Serial)) {
        if ($Serial -notin $devices) {
            throw "ADB device $Serial is not connected or authorized."
        }
        return $Serial
    }
    if ($devices.Count -eq 1) { return $devices[0] }
    if ($devices.Count -eq 0) { throw 'No authorized ADB device is connected.' }
    throw 'Multiple ADB devices are connected. Pass -Serial.'
}

function Invoke-DeviceSmokeRemoteShell {
    param(
        [string]$Adb,
        [string]$Serial,
        [string[]]$Arguments
    )

    $command = ($Arguments | ForEach-Object { ConvertTo-DeviceSmokeShellLiteral ([string]$_) }) -join ' '
    return Invoke-DeviceSmokeAdb -Adb $Adb -Serial $Serial -Arguments @('shell', $command)
}

function New-DeviceSmokeActivityExitInfoRecord {
    param(
        [string[]]$Lines,
        [string]$Package
    )

    if ($Lines.Count -eq 0 -or [string]::IsNullOrWhiteSpace($Package)) { return $null }
    $recordText = $Lines -join [Environment]::NewLine
    # Android 16 can emit process/reason/status on one line, while older builds
    # use one key per line. Match field boundaries instead of line boundaries.
    $processMatch = [regex]::Match($recordText, '(?mi)(?:^|\s)process\s*=\s*([^\s]+)')
    if (-not $processMatch.Success) { return $null }
    $process = $processMatch.Groups[1].Value.Trim()
    if ($process -cne $Package -and -not $process.StartsWith("${Package}:", [System.StringComparison]::Ordinal)) {
        return $null
    }

    $reasonMatch = [regex]::Match($recordText, '(?mi)(?:^|\s)reason\s*=\s*(.*?)(?=\s+(?:subreason|status|importance|pss|rss|description|state|trace)\s*=|$)')
    $reasonText = if ($reasonMatch.Success) { $reasonMatch.Groups[1].Value.Trim() } else { '' }
    $reason = $reasonText
    $reasonNameMatch = [regex]::Match($reasonText, '\(([^)]+)\)')
    if ($reasonNameMatch.Success) {
        $reason = $reasonNameMatch.Groups[1].Value.Trim()
    }

    $timestampMatch = [regex]::Match($recordText, '(?mi)(?:^|\s)timestamp\s*=\s*(.*?)(?=\s+pid\s*=|$)')
    $pidMatch = [regex]::Match($recordText, '(?mi)(?:^|\s)pid\s*=\s*([^\s]+)')
    $statusMatch = [regex]::Match($recordText, '(?mi)(?:^|\s)status\s*=\s*([^\s]+)')
    $timestamp = if ($timestampMatch.Success) { $timestampMatch.Groups[1].Value.Trim() } else { '' }
    $pid = if ($pidMatch.Success) { $pidMatch.Groups[1].Value.Trim() } else { '' }
    $status = if ($statusMatch.Success) { $statusMatch.Groups[1].Value.Trim() } else { '' }
    return [pscustomobject]@{
        Process = $process
        Reason = $reason
        ReasonText = $reasonText
        Timestamp = $timestamp
        Pid = $pid
        Status = $status
        Identity = "$process|$timestamp|$pid|$reasonText|$status"
    }
}

function ConvertFrom-DeviceSmokeActivityExitInfo {
    param(
        [AllowEmptyString()][string]$Text,
        [string]$Package
    )

    if ([string]::IsNullOrWhiteSpace($Text)) { return @() }
    $entries = New-Object System.Collections.ArrayList
    $recordLines = $null
    foreach ($line in @($Text -split "`r?`n")) {
        if ($line -match '^\s*ApplicationExitInfo(?:\s*#\d+)?\s*:\s*$') {
            if ($null -ne $recordLines) {
                $entry = New-DeviceSmokeActivityExitInfoRecord -Lines $recordLines.ToArray() -Package $Package
                if ($null -ne $entry) { [void]$entries.Add($entry) }
            }
            $recordLines = New-Object System.Collections.Generic.List[string]
        }
        if ($null -ne $recordLines) {
            [void]$recordLines.Add($line)
        }
    }
    if ($null -ne $recordLines) {
        $entry = New-DeviceSmokeActivityExitInfoRecord -Lines $recordLines.ToArray() -Package $Package
        if ($null -ne $entry) { [void]$entries.Add($entry) }
    }
    return @($entries)
}

function Get-DeviceSmokePackageExitInfoSnapshot {
    param(
        [string]$Adb,
        [string]$Serial,
        [string]$Package
    )

    if ($Package -notmatch '^[A-Za-z0-9._]+$') {
        return [pscustomobject]@{ Available = $false; Entries = @(); Error = 'exit_info_unavailable: malformed package id' }
    }
    $command = (@('dumpsys', 'activity', 'exit-info', $Package) | ForEach-Object {
        ConvertTo-DeviceSmokeShellLiteral ([string]$_)
    }) -join ' '
    $result = Invoke-DeviceSmokeAdbResult -Adb $Adb -Serial $Serial -Arguments @('shell', $command)
    if ($result.ExitCode -ne 0) {
        $detail = if ([string]::IsNullOrWhiteSpace($result.Text)) { 'no output' } else { $result.Text }
        return [pscustomobject]@{ Available = $false; Entries = @(); Error = "exit_info_failed: $detail" }
    }
    if ($result.Text -match '(?i)unknown (?:command|option)|not found|can''t find service|permission denial') {
        return [pscustomobject]@{ Available = $false; Entries = @(); Error = "exit_info_unavailable: $($result.Text)" }
    }
    return [pscustomobject]@{
        Available = $true
        Entries = @(ConvertFrom-DeviceSmokeActivityExitInfo -Text $result.Text -Package $Package)
        Error = $null
    }
}

function New-DeviceSmokeExitInfoBaseline {
    param([AllowNull()][object]$Snapshot)

    $entryCounts = @{}
    $available = $null -ne $Snapshot -and [bool](Get-DeviceSmokeProperty -Object $Snapshot -Name 'Available')
    if ($available) {
        foreach ($entry in @(Get-DeviceSmokeProperty -Object $Snapshot -Name 'Entries')) {
            $identity = Get-DeviceSmokeProperty -Object $entry -Name 'Identity'
            if ($identity -isnot [string] -or [string]::IsNullOrWhiteSpace($identity)) { continue }
            if ($entryCounts.ContainsKey($identity)) {
                $entryCounts[$identity] = [int]$entryCounts[$identity] + 1
            } else {
                $entryCounts[$identity] = 1
            }
        }
    }
    return [pscustomobject]@{ Available = $available; EntryCounts = $entryCounts }
}

function Find-DeviceSmokeNewExitInfo {
    param(
        [AllowNull()][object]$Baseline,
        [AllowNull()][object]$Snapshot,
        [AllowEmptyString()][string]$RequiredProcess = ''
    )

    if ($null -eq $Baseline -or $null -eq $Snapshot -or
        -not [bool](Get-DeviceSmokeProperty -Object $Baseline -Name 'Available') -or
        -not [bool](Get-DeviceSmokeProperty -Object $Snapshot -Name 'Available')) {
        return $null
    }
    $baselineCounts = Get-DeviceSmokeProperty -Object $Baseline -Name 'EntryCounts'
    if ($baselineCounts -isnot [hashtable]) { return $null }
    $currentCounts = @{}
    foreach ($entry in @(Get-DeviceSmokeProperty -Object $Snapshot -Name 'Entries')) {
        if (-not [string]::IsNullOrWhiteSpace($RequiredProcess) -and
            (Get-DeviceSmokeProperty -Object $entry -Name 'Process') -cne $RequiredProcess) {
            continue
        }
        $identity = Get-DeviceSmokeProperty -Object $entry -Name 'Identity'
        if ($identity -isnot [string] -or [string]::IsNullOrWhiteSpace($identity)) { continue }
        $currentCount = if ($currentCounts.ContainsKey($identity)) { [int]$currentCounts[$identity] + 1 } else { 1 }
        $currentCounts[$identity] = $currentCount
        $baselineCount = if ($baselineCounts.ContainsKey($identity)) { [int]$baselineCounts[$identity] } else { 0 }
        if ($currentCount -gt $baselineCount) { return $entry }
    }
    return $null
}

function Get-DeviceSmokeExitFailureKind {
    param([AllowNull()][object]$ExitInfo)

    $reason = Get-DeviceSmokeProperty -Object $ExitInfo -Name 'Reason'
    if ($reason -isnot [string] -or [string]::IsNullOrWhiteSpace($reason)) {
        return 'process_exited'
    }
    $normalized = ($reason.ToLowerInvariant() -replace '[^a-z0-9]+', '_').Trim('_')
    if ($normalized.StartsWith('reason_')) { $normalized = $normalized.Substring('reason_'.Length) }
    if ([string]::IsNullOrWhiteSpace($normalized)) { return 'process_exited' }
    return "process_exited_$normalized"
}

function Test-DeviceSmokeRemotePath {
    param(
        [string]$Adb,
        [string]$Serial,
        [string]$Path,
        [ValidateSet('Directory', 'File', 'NonEmptyFile')][string]$Kind
    )

    $testFlag = switch ($Kind) {
        'Directory' { '-d' }
        'File' { '-f' }
        'NonEmptyFile' { '-s' }
    }
    $command = "if [ $testFlag $(ConvertTo-DeviceSmokeShellLiteral $Path) ]; then printf __DEVICE_SMOKE_OK__; fi"
    $result = Invoke-DeviceSmokeAdbResult -Adb $Adb -Serial $Serial -Arguments @('shell', $command)
    return $result.ExitCode -eq 0 -and $result.Text.Trim() -eq '__DEVICE_SMOKE_OK__'
}

function Assert-DeviceSmokeRemotePath {
    param(
        [string]$Adb,
        [string]$Serial,
        [string]$Path,
        [ValidateSet('Directory', 'File', 'NonEmptyFile')][string]$Kind,
        [string]$Description = ''
    )

    if (-not (Test-DeviceSmokeRemotePath -Adb $Adb -Serial $Serial -Path $Path -Kind $Kind)) {
        $label = if ([string]::IsNullOrWhiteSpace($Description)) { $Kind.ToLowerInvariant() } else { $Description }
        throw "Device $label is missing or empty: $Path"
    }
}

function Assert-DeviceSmokePackageInstalled {
    param(
        [string]$Adb,
        [string]$Serial,
        [string]$Package
    )

    if ($Package -notmatch '^[A-Za-z0-9._]+$') {
        throw 'Package must be an Android application id containing only letters, digits, dots, and underscores.'
    }
    $text = Invoke-DeviceSmokeRemoteShell -Adb $Adb -Serial $Serial -Arguments @('pm', 'path', $Package)
    if ([string]::IsNullOrWhiteSpace($text) -or $text -notmatch '(?m)^package:') {
        throw "Package is not installed or is not queryable through adb shell pm path: $Package. Output: $text"
    }
}

function Assert-DeviceSmokeActivityAvailable {
    param(
        [string]$Adb,
        [string]$Serial,
        [string]$Component
    )

    if ($Component -notmatch '^[A-Za-z0-9._]+/[A-Za-z0-9._$]+$') {
        throw "Activity component is malformed: $Component"
    }
    $text = Invoke-DeviceSmokeRemoteShell -Adb $Adb -Serial $Serial -Arguments @('cmd', 'package', 'resolve-activity', '--brief', '-n', $Component)
    if ([string]::IsNullOrWhiteSpace($text) -or $text -match '(?i)error|exception|not found|no activity') {
        throw "Debug activity is unavailable: $Component. Output: $text"
    }
}

function Join-DeviceSmokeRemotePath {
    param([string]$Root, [string]$Child)

    $trimmedRoot = $Root.TrimEnd('/')
    $trimmedChild = $Child.TrimStart('/').Replace('\', '/')
    return "$trimmedRoot/$trimmedChild"
}

function Assert-DeviceSmokeMnnChatBundle {
    param(
        [string]$Adb,
        [string]$Serial,
        [string]$ModelPath
    )

    Assert-DeviceSmokeRemotePath -Adb $Adb -Serial $Serial -Path $ModelPath -Kind Directory -Description 'MNN chat bundle directory'
    foreach ($relativePath in @('config.json', 'llm_config.json', 'llm.mnn', 'llm.mnn.weight')) {
        $path = Join-DeviceSmokeRemotePath -Root $ModelPath -Child $relativePath
        Assert-DeviceSmokeRemotePath -Adb $Adb -Serial $Serial -Path $path -Kind NonEmptyFile -Description "MNN chat component $relativePath"
    }
    $tokenizerMtok = Join-DeviceSmokeRemotePath -Root $ModelPath -Child 'tokenizer.mtok'
    $tokenizerText = Join-DeviceSmokeRemotePath -Root $ModelPath -Child 'tokenizer.txt'
    if (-not (Test-DeviceSmokeRemotePath -Adb $Adb -Serial $Serial -Path $tokenizerMtok -Kind NonEmptyFile) -and
        -not (Test-DeviceSmokeRemotePath -Adb $Adb -Serial $Serial -Path $tokenizerText -Kind NonEmptyFile)) {
        throw "MNN chat bundle requires a nonempty tokenizer.mtok or tokenizer.txt under $ModelPath"
    }
}

function Assert-DeviceSmokeQairtChatBundle {
    param(
        [string]$Adb,
        [string]$Serial,
        [string]$ModelPath
    )

    if (-not (Test-DeviceSmokeQairtChatBundleDirectory -Adb $Adb -Serial $Serial -ModelPath $ModelPath)) {
        throw "QAIRT chat bundle is incomplete: $ModelPath. Expected nonempty metadata.json, a GenieX/QAIRT runtime config, and a nonempty QAIRT artifact."
    }
}

function Get-DeviceSmokeRemoteDirectoryEntryNames {
    param(
        [string]$Adb,
        [string]$Serial,
        [string]$Path
    )

    $quotedPath = ConvertTo-DeviceSmokeShellLiteral $Path
    $command = "if [ -d $quotedPath ]; then ls -1A $quotedPath; fi"
    $result = Invoke-DeviceSmokeAdbResult -Adb $Adb -Serial $Serial -Arguments @('shell', $command)
    if ($result.ExitCode -ne 0) {
        $detail = if ([string]::IsNullOrWhiteSpace($result.Text)) { 'no output' } else { $result.Text }
        throw "Unable to list remote directory ${Path}: $detail"
    }

    return @($result.Text -split "`r?`n" | Where-Object {
        -not [string]::IsNullOrWhiteSpace($_) -and $_ -ne '.' -and $_ -ne '..' -and $_.IndexOf([char]0) -lt 0 -and -not $_.Contains('/')
    })
}

function Test-DeviceSmokeQairtRuntimeConfigName {
    param([string]$Name)

    $normalized = $Name.ToLowerInvariant()
    return $normalized -eq 'genie_config.json' -or
        $normalized -eq 'htp_backend_ext_config.json' -or
        $normalized -eq 'config.json' -or
        ($normalized.EndsWith('.json') -and ($normalized.Contains('genie') -or $normalized.Contains('qairt') -or $normalized.Contains('qnn')))
}

function Test-DeviceSmokeQairtArtifactName {
    param([string]$Name)

    $normalized = $Name.ToLowerInvariant()
    return $normalized.EndsWith('.bin') -or
        $normalized.EndsWith('.serialized') -or
        $normalized.EndsWith('.ctx') -or
        $normalized.EndsWith('.qnn') -or
        ($normalized.EndsWith('.so') -and ($normalized.Contains('qnn') -or $normalized.Contains('genie')))
}

function Test-DeviceSmokeQairtChatBundleDirectory {
    param(
        [string]$Adb,
        [string]$Serial,
        [string]$ModelPath
    )

    if (-not (Test-DeviceSmokeRemotePath -Adb $Adb -Serial $Serial -Path $ModelPath -Kind Directory)) {
        return $false
    }

    $names = New-Object System.Collections.Generic.List[string]
    foreach ($name in @(Get-DeviceSmokeRemoteDirectoryEntryNames -Adb $Adb -Serial $Serial -Path $ModelPath)) {
        $entryPath = Join-DeviceSmokeRemotePath -Root $ModelPath -Child $name
        if (Test-DeviceSmokeRemotePath -Adb $Adb -Serial $Serial -Path $entryPath -Kind NonEmptyFile) {
            [void]$names.Add($name)
        }
    }

    $hasMetadata = $names.Contains('metadata.json')
    $hasRuntimeConfig = @($names | Where-Object { Test-DeviceSmokeQairtRuntimeConfigName -Name $_ }).Count -gt 0
    $hasArtifact = @($names | Where-Object { Test-DeviceSmokeQairtArtifactName -Name $_ }).Count -gt 0
    return $hasMetadata -and $hasRuntimeConfig -and $hasArtifact
}

function Resolve-DeviceSmokeQairtChatBundleRoot {
    param(
        [string]$Adb,
        [string]$Serial,
        [string]$ModelPath
    )

    Assert-DeviceSmokeRemotePath -Adb $Adb -Serial $Serial -Path $ModelPath -Kind Directory -Description 'QAIRT chat bundle directory'
    if (Test-DeviceSmokeQairtChatBundleDirectory -Adb $Adb -Serial $Serial -ModelPath $ModelPath) {
        return $ModelPath
    }

    $candidates = New-Object System.Collections.Generic.List[string]
    $inspected = New-Object System.Collections.Generic.List[string]
    [void]$inspected.Add($ModelPath)
    foreach ($name in @(Get-DeviceSmokeRemoteDirectoryEntryNames -Adb $Adb -Serial $Serial -Path $ModelPath)) {
        $childPath = Join-DeviceSmokeRemotePath -Root $ModelPath -Child $name
        if (-not (Test-DeviceSmokeRemotePath -Adb $Adb -Serial $Serial -Path $childPath -Kind Directory)) {
            continue
        }
        [void]$inspected.Add($childPath)
        if (Test-DeviceSmokeQairtChatBundleDirectory -Adb $Adb -Serial $Serial -ModelPath $childPath) {
            [void]$candidates.Add($childPath)
        }
    }

    if ($candidates.Count -eq 1) {
        return $candidates[0]
    }

    if ($candidates.Count -gt 1) {
        throw "Unable to determine QAIRT chat model root under $ModelPath. Multiple complete candidate paths were found: $($candidates -join ', '). Pass one candidate explicitly with -ModelPath."
    }

    throw "Unable to determine QAIRT chat model root under $ModelPath. No complete candidate was found. Inspected paths: $($inspected -join ', '). A complete candidate requires nonempty metadata.json, a GenieX/QAIRT runtime config, and a nonempty QAIRT artifact."
}

function Assert-DeviceSmokeMnnDiffusionBundle {
    param(
        [string]$Adb,
        [string]$Serial,
        [string]$BundleRoot
    )

    Assert-DeviceSmokeRemotePath -Adb $Adb -Serial $Serial -Path $BundleRoot -Kind Directory -Description 'MNN diffusion bundle directory'
    foreach ($relativePath in @('text_encoder.mnn', 'text_encoder.mnn.weight', 'unet.mnn', 'unet.mnn.weight', 'vae_decoder.mnn', 'vae_decoder.mnn.weight')) {
        $path = Join-DeviceSmokeRemotePath -Root $BundleRoot -Child $relativePath
        Assert-DeviceSmokeRemotePath -Adb $Adb -Serial $Serial -Path $path -Kind NonEmptyFile -Description "MNN diffusion component $relativePath"
    }
    $hasTokenizer = (Test-DeviceSmokeRemotePath -Adb $Adb -Serial $Serial -Path (Join-DeviceSmokeRemotePath -Root $BundleRoot -Child 'tokenizer.mtok') -Kind NonEmptyFile) -or
        (Test-DeviceSmokeRemotePath -Adb $Adb -Serial $Serial -Path (Join-DeviceSmokeRemotePath -Root $BundleRoot -Child 'tokenizer.txt') -Kind NonEmptyFile) -or
        ((Test-DeviceSmokeRemotePath -Adb $Adb -Serial $Serial -Path (Join-DeviceSmokeRemotePath -Root $BundleRoot -Child 'vocab.json') -Kind NonEmptyFile) -and
            (Test-DeviceSmokeRemotePath -Adb $Adb -Serial $Serial -Path (Join-DeviceSmokeRemotePath -Root $BundleRoot -Child 'merges.txt') -Kind NonEmptyFile))
    if (-not $hasTokenizer) {
        throw "MNN diffusion bundle requires tokenizer.mtok, tokenizer.txt, or both vocab.json and merges.txt under $BundleRoot"
    }
}

function Read-DeviceSmokeStrictJsonFile {
    param(
        [string]$Path,
        [AllowEmptyString()][string]$ExpectedRunId = ''
    )

    try {
        $bytes = [IO.File]::ReadAllBytes($Path)
    } catch {
        return [pscustomobject]@{ Json = $null; RootStatus = $null; ParseError = "read_error: $($_.Exception.Message)"; FailureKind = 'read_error' }
    }
    try {
        $utf8 = New-Object System.Text.UTF8Encoding -ArgumentList @($false, $true)
        $text = $utf8.GetString($bytes)
    } catch {
        return [pscustomobject]@{ Json = $null; RootStatus = $null; ParseError = "invalid_utf8: $($_.Exception.Message)"; FailureKind = 'invalid_utf8' }
    }
    if ([string]::IsNullOrWhiteSpace($text)) {
        return [pscustomobject]@{ Json = $null; RootStatus = $null; ParseError = 'parse_error: JSON file is empty.'; FailureKind = 'parse_error' }
    }

    $trimmed = $text.TrimStart()
    if ($trimmed.Length -gt 0 -and $trimmed[0] -eq [char]0xFEFF) {
        $trimmed = $trimmed.Substring(1).TrimStart()
    }
    if ($trimmed.Length -eq 0 -or $trimmed[0] -ne '{') {
        return [pscustomobject]@{ Json = $null; RootStatus = $null; ParseError = 'invalid_root: JSON root must be an object.'; FailureKind = 'invalid_root' }
    }
    try {
        $json = ConvertFrom-Json -InputObject $text -ErrorAction Stop
    } catch {
        return [pscustomobject]@{ Json = $null; RootStatus = $null; ParseError = "parse_error: $($_.Exception.Message)"; FailureKind = 'parse_error' }
    }
    if ($json -isnot [System.Management.Automation.PSCustomObject]) {
        return [pscustomobject]@{ Json = $null; RootStatus = $null; ParseError = 'invalid_root: JSON root must be an object.'; FailureKind = 'invalid_root' }
    }
    if (-not [string]::IsNullOrWhiteSpace($ExpectedRunId)) {
        $actualRunId = Get-DeviceSmokeProperty -Object $json -Name 'runId'
        if ($actualRunId -isnot [string] -or $actualRunId -cne $ExpectedRunId) {
            return [pscustomobject]@{ Json = $null; RootStatus = $null; ParseError = "run_id_mismatch: expected '$ExpectedRunId' but found '$actualRunId'."; FailureKind = 'run_id_mismatch' }
        }
    }
    $status = Get-DeviceSmokeProperty -Object $json -Name 'status'
    return [pscustomobject]@{
        Json = $json
        RootStatus = if ($status -is [string]) { [string]$status } else { $null }
        ParseError = $null
        FailureKind = $null
    }
}

function Get-DeviceSmokeRemoteJsonSnapshot {
    param(
        [string]$Adb,
        [string]$Serial,
        [string]$RemoteJson,
        [string]$ExpectedRunId
    )

    if (-not (Test-DeviceSmokeRemotePath -Adb $Adb -Serial $Serial -Path $RemoteJson -Kind File)) {
        return [pscustomobject]@{ Exists = $false; Json = $null; RootStatus = $null; ParseError = $null; FailureKind = $null; TerminalStatus = $null }
    }
    $snapshot = Join-Path ([IO.Path]::GetTempPath()) ("device-smoke-poll-$([Guid]::NewGuid().ToString('N')).json")
    try {
        $pull = Invoke-DeviceSmokeAdbResult -Adb $Adb -Serial $Serial -Arguments @('pull', $RemoteJson, $snapshot)
        if ($pull.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $snapshot -PathType Leaf)) {
            $detail = if ([string]::IsNullOrWhiteSpace($pull.Text)) { 'no output' } else { $pull.Text }
            return [pscustomobject]@{ Exists = $true; Json = $null; RootStatus = $null; ParseError = "snapshot_pull_failed: $detail"; FailureKind = 'snapshot_pull_failed'; TerminalStatus = $null }
        }
        $parsed = Read-DeviceSmokeStrictJsonFile -Path $snapshot -ExpectedRunId $ExpectedRunId
        $terminalStatus = if ($parsed.RootStatus -ceq 'completed' -or $parsed.RootStatus -ceq 'failed') { $parsed.RootStatus } else { $null }
        return [pscustomobject]@{
            Exists = $true
            Json = $parsed.Json
            RootStatus = $parsed.RootStatus
            ParseError = $parsed.ParseError
            FailureKind = $parsed.FailureKind
            TerminalStatus = $terminalStatus
        }
    } finally {
        Remove-Item -LiteralPath $snapshot -Force -ErrorAction SilentlyContinue
    }
}

function Wait-DeviceSmokeTerminal {
    param(
        [string]$Adb,
        [string]$Serial,
        [string]$RemoteJson,
        [string]$ExpectedRunId,
        [int]$TimeoutSeconds,
        [int]$PollMilliseconds,
        [AllowEmptyString()][string]$Package = '',
        [AllowNull()][object]$ExitInfoBaseline = $null,
        [switch]$IgnoreChildProcessExit
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $observed = $false
    do {
        $snapshot = Get-DeviceSmokeRemoteJsonSnapshot -Adb $Adb -Serial $Serial -RemoteJson $RemoteJson -ExpectedRunId $ExpectedRunId
        if ($snapshot.Exists) { $observed = $true }
        if (-not [string]::IsNullOrWhiteSpace($snapshot.ParseError)) {
            return [pscustomobject]@{ Outcome = 'invalid_result'; Snapshot = $snapshot; RemoteJsonObserved = $observed; FailureKind = $snapshot.FailureKind; Error = $snapshot.ParseError; ExitInfo = $null }
        }
        if (-not [string]::IsNullOrWhiteSpace($snapshot.TerminalStatus)) {
            return [pscustomobject]@{ Outcome = 'terminal'; Snapshot = $snapshot; RemoteJsonObserved = $observed; FailureKind = $null; Error = $null; ExitInfo = $null }
        }
        if (-not [string]::IsNullOrWhiteSpace($Package) -and
            [bool](Get-DeviceSmokeProperty -Object $ExitInfoBaseline -Name 'Available')) {
            $exitInfoSnapshot = Get-DeviceSmokePackageExitInfoSnapshot -Adb $Adb -Serial $Serial -Package $Package
            $newExitInfo = Find-DeviceSmokeNewExitInfo `
                -Baseline $ExitInfoBaseline -Snapshot $exitInfoSnapshot `
                -RequiredProcess $(if ($IgnoreChildProcessExit) { $Package } else { '' })
            if ($null -ne $newExitInfo) {
                $reason = Get-DeviceSmokeProperty -Object $newExitInfo -Name 'Reason'
                if ($reason -isnot [string] -or [string]::IsNullOrWhiteSpace($reason)) { $reason = 'UNKNOWN' }
                $details = @("ApplicationExitInfo reason $reason")
                $pid = Get-DeviceSmokeProperty -Object $newExitInfo -Name 'Pid'
                if ($pid -is [string] -and -not [string]::IsNullOrWhiteSpace($pid)) { $details += "pid $pid" }
                $timestamp = Get-DeviceSmokeProperty -Object $newExitInfo -Name 'Timestamp'
                if ($timestamp -is [string] -and -not [string]::IsNullOrWhiteSpace($timestamp)) { $details += "timestamp $timestamp" }
                return [pscustomobject]@{
                    Outcome = 'process_exited'
                    Snapshot = $snapshot
                    RemoteJsonObserved = $observed
                    FailureKind = Get-DeviceSmokeExitFailureKind -ExitInfo $newExitInfo
                    Error = "Target package '$Package' exited before a terminal result ($($details -join ', '))."
                    ExitInfo = $newExitInfo
                }
            }
        }
        Start-Sleep -Milliseconds $PollMilliseconds
    } while ((Get-Date) -lt $deadline)
    return [pscustomobject]@{ Outcome = 'timed_out'; Snapshot = $snapshot; RemoteJsonObserved = $observed; FailureKind = 'timed_out'; Error = $null; ExitInfo = $null }
}

function Get-DeviceSmokeFinalJsonEvidence {
    param(
        [string]$Adb,
        [string]$Serial,
        [string]$RemoteJson,
        [string]$LocalJson,
        [string]$ExpectedRunId
    )

    if (-not (Test-DeviceSmokeRemotePath -Adb $Adb -Serial $Serial -Path $RemoteJson -Kind File)) {
        return [pscustomobject]@{ RawResultPreserved = $false; Json = $null; RootStatus = $null; ParseError = 'remote_result_missing'; FailureKind = 'remote_result_missing' }
    }
    Remove-Item -LiteralPath $LocalJson -Force -ErrorAction SilentlyContinue
    $pull = Invoke-DeviceSmokeAdbResult -Adb $Adb -Serial $Serial -Arguments @('pull', $RemoteJson, $LocalJson)
    if ($pull.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $LocalJson -PathType Leaf)) {
        $detail = if ([string]::IsNullOrWhiteSpace($pull.Text)) { 'no output' } else { $pull.Text }
        return [pscustomobject]@{ RawResultPreserved = $false; Json = $null; RootStatus = $null; ParseError = "final_pull_failed: $detail"; FailureKind = 'final_pull_failed' }
    }
    $parsed = Read-DeviceSmokeStrictJsonFile -Path $LocalJson -ExpectedRunId $ExpectedRunId
    return [pscustomobject]@{
        RawResultPreserved = $true
        Json = $parsed.Json
        RootStatus = $parsed.RootStatus
        ParseError = $parsed.ParseError
        FailureKind = $parsed.FailureKind
    }
}

function Pull-DeviceSmokeRemotePng {
    param(
        [string]$Adb,
        [string]$Serial,
        [string]$RemotePng,
        [string]$LocalPng
    )

    if (-not (Test-DeviceSmokeRemotePath -Adb $Adb -Serial $Serial -Path $RemotePng -Kind NonEmptyFile)) {
        return [pscustomobject]@{ Preserved = $false; Error = "remote_png_missing: $RemotePng"; LocalPng = $LocalPng }
    }
    Remove-Item -LiteralPath $LocalPng -Force -ErrorAction SilentlyContinue
    $pull = Invoke-DeviceSmokeAdbResult -Adb $Adb -Serial $Serial -Arguments @('pull', $RemotePng, $LocalPng)
    if ($pull.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $LocalPng -PathType Leaf) -or (Get-Item -LiteralPath $LocalPng).Length -le 0) {
        $detail = if ([string]::IsNullOrWhiteSpace($pull.Text)) { 'no output' } else { $pull.Text }
        return [pscustomobject]@{ Preserved = $false; Error = "png_pull_failed: $detail"; LocalPng = $LocalPng }
    }
    return [pscustomobject]@{ Preserved = $true; Error = $null; LocalPng = $LocalPng }
}

function Invoke-DeviceSmokeActivityRun {
    param(
        [string]$Adb,
        [string]$Serial,
        [string]$Package,
        [ValidateSet('reuse', 'cold')][string]$Lifecycle,
        [string[]]$ActivityArguments,
        [string]$RemoteJson,
        [string]$LocalJson,
        [string]$ExpectedRunId,
        [int]$TimeoutSeconds,
        [int]$PollMilliseconds,
        [AllowEmptyString()][string]$RemotePng = '',
        [switch]$IgnoreChildProcessExit
    )

    if ($Lifecycle -eq 'cold') {
        Invoke-DeviceSmokeRemoteShell -Adb $Adb -Serial $Serial -Arguments @('am', 'force-stop', $Package) | Out-Null
        Start-Sleep -Milliseconds 500
    }
    Invoke-DeviceSmokeRemoteShell -Adb $Adb -Serial $Serial -Arguments @('rm', '-f', $RemoteJson) | Out-Null
    if (-not [string]::IsNullOrWhiteSpace($RemotePng)) {
        Invoke-DeviceSmokeRemoteShell -Adb $Adb -Serial $Serial -Arguments @('rm', '-f', $RemotePng) | Out-Null
    }
    $exitInfoBaseline = New-DeviceSmokeExitInfoBaseline -Snapshot (Get-DeviceSmokePackageExitInfoSnapshot -Adb $Adb -Serial $Serial -Package $Package)
    $startText = Invoke-DeviceSmokeRemoteShell -Adb $Adb -Serial $Serial -Arguments $ActivityArguments
    if ($startText -match 'Error type 3|does not exist|Permission Denial') {
        throw "Unable to start debug activity: $startText"
    }

    $wait = Wait-DeviceSmokeTerminal `
        -Adb $Adb -Serial $Serial -RemoteJson $RemoteJson -ExpectedRunId $ExpectedRunId `
        -TimeoutSeconds $TimeoutSeconds -PollMilliseconds $PollMilliseconds `
        -Package $Package -ExitInfoBaseline $exitInfoBaseline `
        -IgnoreChildProcessExit:$IgnoreChildProcessExit
    $evidence = Get-DeviceSmokeFinalJsonEvidence -Adb $Adb -Serial $Serial -RemoteJson $RemoteJson -LocalJson $LocalJson -ExpectedRunId $ExpectedRunId
    $status = $null
    $failureKind = $evidence.FailureKind
    $error = $evidence.ParseError
    switch ($wait.Outcome) {
        'terminal' {
            if (-not [string]::IsNullOrWhiteSpace($evidence.ParseError)) {
                $status = 'invalid_result'
            } elseif ($evidence.RootStatus -ceq 'completed' -or $evidence.RootStatus -ceq 'failed') {
                $status = $evidence.RootStatus
            } else {
                $status = 'invalid_result'
                $failureKind = 'nonterminal_final_result'
                $error = 'Final JSON was no longer terminal after polling.'
            }
        }
        'invalid_result' {
            $status = 'invalid_result'
            $failureKind = $wait.Snapshot.FailureKind
            $error = $wait.Snapshot.ParseError
        }
        'process_exited' {
            $status = 'process_exited'
            $failureKind = $wait.FailureKind
            $error = $wait.Error
        }
        default {
            $status = 'timed_out'
            $failureKind = 'timed_out'
            $error = "Timed out after $TimeoutSeconds seconds waiting for a terminal result."
        }
    }
    return [pscustomobject][ordered]@{
        runId = $ExpectedRunId
        status = $status
        failureKind = $failureKind
        error = $error
        waitOutcome = $wait.Outcome
        remoteJson = $RemoteJson
        localJson = $LocalJson
        rawResultPreserved = [bool]$evidence.RawResultPreserved
        json = $evidence.Json
        startOutput = $startText
        exitInfo = $wait.ExitInfo
    }
}

function Write-DeviceSmokeSessionSummary {
    param([string]$Path, [object]$Summary)
    $json = ConvertTo-Json -InputObject $Summary -Depth 30
    Write-DeviceSmokeUtf8File -Path $Path -Content $json
}

function Get-DeviceSmokeRemoteJsonDocument {
    param(
        [string]$Adb,
        [string]$Serial,
        [string]$RemotePath
    )

    Assert-DeviceSmokeRemotePath -Adb $Adb -Serial $Serial -Path $RemotePath -Kind NonEmptyFile -Description 'JSON document'
    $localPath = Join-Path ([IO.Path]::GetTempPath()) ("device-smoke-document-$([Guid]::NewGuid().ToString('N')).json")
    try {
        $pull = Invoke-DeviceSmokeAdbResult -Adb $Adb -Serial $Serial -Arguments @('pull', $RemotePath, $localPath)
        if ($pull.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $localPath -PathType Leaf)) {
            $detail = if ([string]::IsNullOrWhiteSpace($pull.Text)) { 'no output' } else { $pull.Text }
            throw "Unable to pull device JSON document ${RemotePath}: $detail"
        }
        $parsed = Read-DeviceSmokeStrictJsonFile -Path $localPath
        if (-not [string]::IsNullOrWhiteSpace($parsed.ParseError)) {
            throw "Device JSON document $RemotePath is invalid: $($parsed.ParseError)"
        }
        return $parsed.Json
    } finally {
        Remove-Item -LiteralPath $localPath -Force -ErrorAction SilentlyContinue
    }
}

function Get-DeviceSmokeFirstStringProperty {
    param([object]$Object, [string[]]$Names)

    foreach ($name in $Names) {
        $value = Get-DeviceSmokeProperty -Object $Object -Name $name
        if ($value -is [string] -and -not [string]::IsNullOrWhiteSpace($value)) {
            return $value.Trim()
        }
    }
    return ''
}

function Get-DeviceSmokeTensorBytes {
    param(
        [object]$Tensor,
        [string]$Role,
        [int]$Index
    )

    if ($Tensor -isnot [System.Management.Automation.PSCustomObject]) {
        throw "QNN $Role tensor #$($Index + 1) must be a JSON object."
    }
    $name = Get-DeviceSmokeFirstStringProperty -Object $Tensor -Names @('name', 'tensorName')
    if ([string]::IsNullOrWhiteSpace($name)) {
        throw "QNN $Role tensor #$($Index + 1) requires a name."
    }
    $dataType = Get-DeviceSmokeFirstStringProperty -Object $Tensor -Names @('dataType', 'dtype', 'type')
    $bytesPerElement = switch ($dataType.ToLowerInvariant()) {
        'bool' { 1 }
        'int8' { 1 }
        'uint8' { 1 }
        'float16' { 2 }
        'fp16' { 2 }
        'int16' { 2 }
        'uint16' { 2 }
        'float32' { 4 }
        'fp32' { 4 }
        'int32' { 4 }
        'uint32' { 4 }
        'float64' { 8 }
        'fp64' { 8 }
        'int64' { 8 }
        'uint64' { 8 }
        default { 0 }
    }
    if ($bytesPerElement -le 0) {
        throw "QNN $Role tensor '$name' has unsupported data type '$dataType'."
    }
    $shape = Get-DeviceSmokeProperty -Object $Tensor -Name 'shape'
    # An `if` expression writes a one-item array back to the pipeline as a scalar.
    # Keep this explicitly typed so strict-mode `.Count` checks remain safe.
    [object[]]$shapeValues = @()
    if ($null -ne $shape) {
        $shapeValues = @($shape)
    }
    if ($shapeValues.Count -eq 0) {
        throw "QNN $Role tensor '$name' requires a positive shape."
    }
    [Int64]$elements = 1
    foreach ($shapeValue in $shapeValues) {
        if ($shapeValue -isnot [ValueType]) {
            throw "QNN $Role tensor '$name' has a non-numeric shape value."
        }
        try {
            [Int64]$dimension = [Convert]::ToInt64($shapeValue)
        } catch {
            throw "QNN $Role tensor '$name' has an invalid shape value."
        }
        if ($dimension -le 0 -or [double]$dimension -ne [double]$shapeValue) {
            throw "QNN $Role tensor '$name' requires positive integer dimensions."
        }
        if ($elements -gt [Int64]::MaxValue / $dimension) {
            throw "QNN $Role tensor '$name' shape overflows Int64."
        }
        $elements *= $dimension
    }
    if ($elements -gt [Int64]::MaxValue / $bytesPerElement) {
        throw "QNN $Role tensor '$name' byte size overflows Int64."
    }
    return [pscustomobject]@{ Name = $name; Bytes = ($elements * $bytesPerElement) }
}

function Get-DeviceSmokeQnnImageSmokeSpecs {
    param(
        [object]$Manifest,
        [string]$ManifestPath
    )

    # Newer MCA image manifests carry executable graph metadata in a smoke
    # suite, while the legacy smoke object remains the full pipeline profile
    # (prompt, dimensions, and steps). Prefer the suite whenever present so a
    # legacy profile is not mistakenly treated as an incomplete graph spec.
    foreach ($arrayName in @('smokes', 'smokeSpecs')) {
        $candidate = Get-DeviceSmokeProperty -Object $Manifest -Name $arrayName
        [object[]]$items = @()
        if ($null -ne $candidate) { $items = @($candidate) }
        $specs = @($items | Where-Object { $_ -is [System.Management.Automation.PSCustomObject] })
        if ($specs.Count -gt 0) { return $specs }
    }

    foreach ($legacyName in @('smoke', 'smokeSpec')) {
        $candidate = Get-DeviceSmokeProperty -Object $Manifest -Name $legacyName
        if ($candidate -is [System.Management.Automation.PSCustomObject]) {
            return @($candidate)
        }
    }
    throw "QNN manifest requires graph specs in smokes[], smokeSpecs, smoke, or smokeSpec: $ManifestPath"
}

function Assert-DeviceSmokeQnnImageBundle {
    param(
        [string]$Adb,
        [string]$Serial,
        [string]$BundleRoot
    )

    Assert-DeviceSmokeRemotePath -Adb $Adb -Serial $Serial -Path $BundleRoot -Kind Directory -Description 'QNN image bundle directory'
    $manifestPath = Join-DeviceSmokeRemotePath -Root $BundleRoot -Child 'manifest.json'
    $manifest = Get-DeviceSmokeRemoteJsonDocument -Adb $Adb -Serial $Serial -RemotePath $manifestPath
    [object[]]$smokes = @(Get-DeviceSmokeQnnImageSmokeSpecs -Manifest $manifest -ManifestPath $manifestPath)
    $validatedSpecs = @()
    for ($specIndex = 0; $specIndex -lt $smokes.Count; $specIndex++) {
        $smoke = $smokes[$specIndex]
        $prefix = "QNN smoke spec #$($specIndex + 1)"
        $graphName = Get-DeviceSmokeFirstStringProperty -Object $smoke -Names @('graphName', 'graph', 'name')
        if ([string]::IsNullOrWhiteSpace($graphName)) {
            throw "$prefix requires graphName, graph, or name: $manifestPath"
        }
        $contextBinary = Get-DeviceSmokeFirstStringProperty -Object $smoke -Names @('contextBinary', 'context', 'contextPath')
        $normalizedContext = $contextBinary.Replace('\', '/').Trim()
        [string[]]$contextSegments = @($normalizedContext -split '/')
        [object[]]$unsafeContextSegments = @(
            $contextSegments | Where-Object {
                [string]::IsNullOrWhiteSpace($_) -or $_ -eq '.' -or $_ -eq '..'
            }
        )
        if ([string]::IsNullOrWhiteSpace($normalizedContext) -or
            $normalizedContext.StartsWith('/') -or
            $normalizedContext.StartsWith('./') -or
            $normalizedContext -match '^[A-Za-z]:' -or
            $unsafeContextSegments.Count -gt 0) {
            throw "$prefix contextBinary must be a safe relative bundle path: '$contextBinary'"
        }
        $contextPath = Join-DeviceSmokeRemotePath -Root $BundleRoot -Child $normalizedContext
        Assert-DeviceSmokeRemotePath -Adb $Adb -Serial $Serial -Path $contextPath -Kind NonEmptyFile -Description "$prefix context binary"

        [Int64]$totalBytes = 0
        $inputNames = @{}
        $outputNames = @{}
        foreach ($definition in @(
            [pscustomobject]@{ Role = 'input'; Value = Get-DeviceSmokeProperty -Object $smoke -Name 'inputs'; Names = $inputNames },
            [pscustomobject]@{ Role = 'output'; Value = Get-DeviceSmokeProperty -Object $smoke -Name 'outputs'; Names = $outputNames }
        )) {
            [object[]]$tensors = @()
            if ($null -ne $definition.Value) {
                $tensors = @($definition.Value)
            }
            if ($tensors.Count -eq 0) {
                throw "$prefix requires at least one $($definition.Role) tensor."
            }
            for ($index = 0; $index -lt $tensors.Count; $index++) {
                $plan = Get-DeviceSmokeTensorBytes -Tensor $tensors[$index] -Role $definition.Role -Index $index
                if ($definition.Names.ContainsKey($plan.Name)) {
                    throw "$prefix has a duplicate $($definition.Role) tensor name: $($plan.Name)"
                }
                $definition.Names[$plan.Name] = $true
                if ($totalBytes -gt [Int64]::MaxValue - $plan.Bytes) {
                    throw "$prefix tensor buffers overflow Int64."
                }
                $totalBytes += $plan.Bytes
            }
        }
        if ($totalBytes -gt (512L * 1024L * 1024L)) {
            throw "$prefix tensor buffers exceed the 512 MiB host preflight limit: $totalBytes bytes."
        }
        $validatedSpecs += [pscustomobject][ordered]@{
            graphName = $graphName
            contextBinary = $normalizedContext
            inputCount = $inputNames.Count
            outputCount = $outputNames.Count
            totalBufferBytes = $totalBytes
        }
    }
    $primary = $validatedSpecs[0]
    return [pscustomobject][ordered]@{
        manifestPath = $manifestPath
        graphName = $primary.graphName
        contextBinary = $primary.contextBinary
        inputCount = $primary.inputCount
        outputCount = $primary.outputCount
        totalBufferBytes = $primary.totalBufferBytes
        smokeCount = $validatedSpecs.Count
        smokeSpecs = @($validatedSpecs)
    }
}

function Get-DeviceSmokeEvents {
    param([object]$Json)

    $events = Get-DeviceSmokeProperty -Object $Json -Name 'events'
    if ($null -eq $events) { return @() }
    return @($events | Where-Object { $_ -is [System.Management.Automation.PSCustomObject] })
}

function Find-DeviceSmokeEvent {
    param([object]$Json, [string]$Status)

    return @(Get-DeviceSmokeEvents -Json $Json | Where-Object {
        $value = Get-DeviceSmokeProperty -Object $_ -Name 'status'
        $value -is [string] -and $value -ceq $Status
    } | Select-Object -Last 1)
}


function Get-DeviceSmokeLatestNativeStats {
    param([object]$Json)

    # Throughput and token metrics are only trustworthy after the activity reports completion.
    $rootStatus = Get-DeviceSmokeProperty -Object $Json -Name 'status'
    if ($rootStatus -isnot [string] -or $rootStatus -cne 'completed') {
        return $null
    }

    $rootStats = Get-DeviceSmokeProperty -Object $Json -Name 'nativeStats'
    if ($null -ne $rootStats) {
        return $rootStats
    }

    $events = @(Get-DeviceSmokeEvents -Json $Json)
    for ($index = $events.Count - 1; $index -ge 0; $index--) {
        $eventStatus = Get-DeviceSmokeProperty -Object $events[$index] -Name 'status'
        if ($eventStatus -isnot [string] -or $eventStatus -cne 'completed') {
            continue
        }
        $eventStats = Get-DeviceSmokeProperty -Object $events[$index] -Name 'nativeStats'
        if ($null -ne $eventStats) {
            return $eventStats
        }
    }
    return $null
}
function Get-DeviceSmokePathValue {
    param([object]$Object, [string[]]$Path)

    $current = $Object
    foreach ($name in $Path) {
        $current = Get-DeviceSmokeProperty -Object $current -Name $name
        if ($null -eq $current) { return $null }
    }
    return $current
}

function Test-DeviceSmokeJsonBooleanTrue {
    param([object]$Value)

    return $Value -is [bool] -and $Value
}

function Assert-DeviceSmokeCompleted {
    param([object]$Json)

    $status = Get-DeviceSmokeProperty -Object $Json -Name 'status'
    if ($status -isnot [string] -or $status -cne 'completed') {
        throw "Activity result root status must be completed; found '$status'."
    }
}

function Test-DeviceSmokeMnnProtocolLeak {
    param([object]$Text)

    if ($Text -isnot [string] -or [string]::IsNullOrWhiteSpace($Text)) {
        return $false
    }
    return $Text -match '(?i)<\|[^|>\r\n]{1,80}\|>|<eop>|(?:^|\s)(?:human|user|assistant)\s*:'
}

function Assert-DeviceSmokeMnnChatContract {
    param([object]$Json)

    Assert-DeviceSmokeCompleted -Json $Json
    $issues = New-Object System.Collections.ArrayList
    $nativeStats = Get-DeviceSmokeLatestNativeStats -Json $Json
    if ((Get-DeviceSmokeProperty -Object $nativeStats -Name 'backend') -cne 'mnn_cpu') { [void]$issues.Add('nativeStats.backend must be mnn_cpu') }
    if (-not (Test-DeviceSmokeJsonBooleanTrue (Get-DeviceSmokeProperty -Object $nativeStats -Name 'loaded'))) { [void]$issues.Add('nativeStats.loaded must be true') }
    if (-not (Test-DeviceSmokeJsonBooleanTrue (Get-DeviceSmokeProperty -Object $nativeStats -Name 'runnerReady'))) { [void]$issues.Add('nativeStats.runnerReady must be true') }
    $generation = @(Find-DeviceSmokeEvent -Json $Json -Status 'generation_ok')
    if ($generation.Count -ne 1) {
        [void]$issues.Add('generation_ok event is missing')
    } else {
        $preview = Get-DeviceSmokePathValue -Object $generation[0] -Path @('generation', 'textPreview')
        if ($preview -isnot [string] -or [string]::IsNullOrWhiteSpace($preview)) { [void]$issues.Add('generation_ok requires a nonempty textPreview') }
        $generationText = Get-DeviceSmokePathValue -Object $generation[0] -Path @('generation', 'text')
        $generationInspectableText = if ($generationText -is [string]) { $generationText } else { $preview }
        if (Test-DeviceSmokeMnnProtocolLeak $generationInspectableText) {
            [void]$issues.Add('generation_ok leaks an MNN template/protocol marker')
        }
    }
    $turns = @(Find-DeviceSmokeEvent -Json $Json -Status 'generation_turn_ok')
    foreach ($turn in $turns) {
        $turnText = Get-DeviceSmokePathValue -Object $turn -Path @('generation', 'text')
        $turnPreview = Get-DeviceSmokePathValue -Object $turn -Path @('generation', 'textPreview')
        $turnInspectableText = if ($turnText -is [string]) { $turnText } else { $turnPreview }
        if ($turnInspectableText -isnot [string] -or [string]::IsNullOrWhiteSpace($turnInspectableText)) {
            [void]$issues.Add('generation_turn_ok requires a nonempty textPreview')
        } elseif (Test-DeviceSmokeMnnProtocolLeak $turnInspectableText) {
            [void]$issues.Add('generation_turn_ok leaks an MNN template/protocol marker')
        }
    }
    $api = @(Find-DeviceSmokeEvent -Json $Json -Status 'api_engine_stream_ok')
    if ($api.Count -ne 1) {
        [void]$issues.Add('api_engine_stream_ok event is missing')
    } elseif (-not (Test-DeviceSmokeJsonBooleanTrue (Get-DeviceSmokePathValue -Object $api[0] -Path @('apiEngine', 'visibleSeen')))) {
        [void]$issues.Add('api_engine_stream_ok requires apiEngine.visibleSeen=true')
    } else {
        $apiText = Get-DeviceSmokePathValue -Object $api[0] -Path @('apiEngine', 'text')
        $apiPreview = Get-DeviceSmokePathValue -Object $api[0] -Path @('apiEngine', 'textPreview')
        $apiInspectableText = if ($apiText -is [string]) { $apiText } else { $apiPreview }
        if (Test-DeviceSmokeMnnProtocolLeak $apiInspectableText) {
            [void]$issues.Add('api_engine_stream_ok leaks an MNN template/protocol marker')
        }
    }
    if ($issues.Count -gt 0) { throw "MNN chat contract failed: $($issues -join '; ')." }
}

function Assert-DeviceSmokeMnnChatTextFragments {
    param(
        [object]$Json,
        [string[]]$ExpectedFragments
    )

    $fragments = @($ExpectedFragments | Where-Object { -not [string]::IsNullOrEmpty([string]$_) })
    if ($fragments.Count -eq 0) { return $null }

    $generation = @(Find-DeviceSmokeEvent -Json $Json -Status 'generation_ok')
    if ($generation.Count -ne 1) {
        throw 'MNN Unicode contract requires exactly one generation_ok event.'
    }
    $text = Get-DeviceSmokePathValue -Object $generation[0] -Path @('generation', 'text')
    if ($text -isnot [string]) {
        throw 'MNN Unicode contract requires generation_ok.generation.text.'
    }

    $missing = @($fragments | Where-Object { -not $text.Contains([string]$_) })
    if ($missing.Count -gt 0) {
        $quoted = $missing | ForEach-Object { "'$_'" }
        throw "MNN Unicode contract is missing expected output fragment(s): $($quoted -join ', ')."
    }

    $api = @(Find-DeviceSmokeEvent -Json $Json -Status 'api_engine_stream_ok')
    $apiText = if ($api.Count -eq 1) {
        Get-DeviceSmokePathValue -Object $api[0] -Path @('apiEngine', 'text')
    } else {
        $null
    }
    $apiContainsAll = $apiText -is [string] -and
        @($fragments | Where-Object { -not $apiText.Contains([string]$_) }).Count -eq 0
    $utf8Bytes = [Text.Encoding]::UTF8.GetBytes($text)
    return [pscustomobject][ordered]@{
        expectedFragments = @($fragments)
        generationText = $text
        generationUtf16Length = $text.Length
        generationUtf8Bytes = $utf8Bytes.Length
        generationUtf8Hex = ([BitConverter]::ToString($utf8Bytes)).Replace('-', '')
        generationUtf8Base64 = [Convert]::ToBase64String($utf8Bytes)
        apiTextContainsAll = [bool]$apiContainsAll
    }
}

function Test-DeviceSmokeQnnEvidence {
    param([object]$Json)

    $serialized = ConvertTo-Json -InputObject $Json -Depth 30 -Compress
    return $serialized -match '(?i)("computeUnit"\s*:\s*"npu"|"backend"\s*:\s*"geniex_qairt"|\bqairt\b|\bqnn\b|\bhtp\b|\bnpu\b)'
}

function Assert-DeviceSmokeQnnChatContract {
    param([object]$Json)

    Assert-DeviceSmokeCompleted -Json $Json
    $issues = New-Object System.Collections.ArrayList
    $nativeStats = Get-DeviceSmokeLatestNativeStats -Json $Json
    if ((Get-DeviceSmokeProperty -Object $nativeStats -Name 'backend') -cne 'geniex_qairt') { [void]$issues.Add('nativeStats.backend must be geniex_qairt') }
    if (-not (Test-DeviceSmokeJsonBooleanTrue (Get-DeviceSmokeProperty -Object $nativeStats -Name 'loaded'))) { [void]$issues.Add('nativeStats.loaded must be true') }
    if (-not (Test-DeviceSmokeJsonBooleanTrue (Get-DeviceSmokeProperty -Object $nativeStats -Name 'runnerReady'))) { [void]$issues.Add('nativeStats.runnerReady must be true') }
    $api = @(Find-DeviceSmokeEvent -Json $Json -Status 'api_engine_stream_ok')
    if ($api.Count -ne 1) {
        [void]$issues.Add('api_engine_stream_ok event is missing')
    } elseif (-not (Test-DeviceSmokeJsonBooleanTrue (Get-DeviceSmokePathValue -Object $api[0] -Path @('apiEngine', 'visibleSeen')))) {
        [void]$issues.Add('api_engine_stream_ok requires apiEngine.visibleSeen=true')
    }
    if (-not (Test-DeviceSmokeQnnEvidence -Json $Json)) { [void]$issues.Add('NPU/HTP/QAIRT evidence is missing') }
    if ($issues.Count -gt 0) { throw "QNN chat contract failed: $($issues -join '; ')." }
}

function Assert-DeviceSmokeQairtDryRunContract {
    param([object]$Json)

    Assert-DeviceSmokeCompleted -Json $Json
    $issues = New-Object System.Collections.ArrayList
    $requiredStages = @(
        'qairt_dry_run_start',
        'qairt_dry_run_load_ok',
        'qairt_dry_run_npu_evidence_ok',
        'qairt_dry_run_generation_ok',
        'qairt_dry_run_destroy_ok',
        'qairt_dry_run_verified'
    )
    foreach ($stage in $requiredStages) {
        if (@(Find-DeviceSmokeEvent -Json $Json -Status $stage).Count -ne 1) {
            [void]$issues.Add("$stage event is missing")
        }
    }

    $load = @(Find-DeviceSmokeEvent -Json $Json -Status 'qairt_dry_run_load_ok')
    if ($load.Count -eq 1) {
        $stats = Get-DeviceSmokeProperty -Object $load[0] -Name 'nativeStats'
        if ((Get-DeviceSmokeProperty -Object $stats -Name 'backend') -cne 'geniex_qairt') {
            [void]$issues.Add('dry-run load nativeStats.backend must be geniex_qairt')
        }
        if (-not (Test-DeviceSmokeJsonBooleanTrue (Get-DeviceSmokeProperty -Object $stats -Name 'loaded'))) {
            [void]$issues.Add('dry-run load nativeStats.loaded must be true')
        }
    }

    $generation = @(Find-DeviceSmokeEvent -Json $Json -Status 'qairt_dry_run_generation_ok')
    if ($generation.Count -eq 1) {
        $visibleText = Get-DeviceSmokePathValue -Object $generation[0] -Path @('generation', 'text')
        if ($visibleText -isnot [string] -or [string]::IsNullOrWhiteSpace($visibleText)) {
            [void]$issues.Add('dry-run generation must contain visible text')
        }
    }

    $destroy = @(Find-DeviceSmokeEvent -Json $Json -Status 'qairt_dry_run_destroy_ok')
    if ($destroy.Count -eq 1) {
        $stats = Get-DeviceSmokeProperty -Object $destroy[0] -Name 'nativeStats'
        if (-not (Test-DeviceSmokeJsonBooleanFalse (Get-DeviceSmokeProperty -Object $stats -Name 'loaded'))) {
            [void]$issues.Add('dry-run destroy nativeStats.loaded must be false')
        }
        if (-not [string]::IsNullOrWhiteSpace([string](Get-DeviceSmokeProperty -Object $stats -Name 'lastError'))) {
            [void]$issues.Add('dry-run destroy nativeStats.lastError must be empty')
        }
    }

    if (-not (Test-DeviceSmokeQnnEvidence -Json $Json)) {
        [void]$issues.Add('dry-run NPU/HTP/QAIRT evidence is missing')
    }
    if ($issues.Count -gt 0) { throw "QAIRT isolated dry-run contract failed: $($issues -join '; ')." }
}

function Test-DeviceSmokeJsonBooleanFalse {
    param([object]$Value)

    return $Value -is [bool] -and -not $Value
}

function Assert-DeviceSmokeQnnImageContract {
    param(
        [object]$Json,
        [ValidateSet('graph', 'pipeline', 'semantic')][string]$Mode
    )

    Assert-DeviceSmokeCompleted -Json $Json
    $issues = New-Object System.Collections.ArrayList
    if (-not (Test-DeviceSmokeJsonBooleanTrue (Get-DeviceSmokeProperty -Object $Json -Name 'qnnGraphExecution'))) { [void]$issues.Add('qnnGraphExecution must be true') }
    if (-not (Test-DeviceSmokeJsonBooleanFalse (Get-DeviceSmokeProperty -Object $Json -Name 'fallback'))) { [void]$issues.Add('fallback must be false') }
    $result = Get-DeviceSmokeProperty -Object $Json -Name 'result'
    if (-not (Test-DeviceSmokeJsonBooleanTrue (Get-DeviceSmokeProperty -Object $result -Name 'ok'))) { [void]$issues.Add('result.ok must be true') }
    if (-not (Test-DeviceSmokeJsonBooleanFalse (Get-DeviceSmokeProperty -Object $result -Name 'fallback'))) { [void]$issues.Add('result.fallback must be false') }
    switch ($Mode) {
        'graph' {
            if (-not (Test-DeviceSmokeJsonBooleanTrue (Get-DeviceSmokeProperty -Object $result -Name 'npuExecutionProven'))) { [void]$issues.Add('graph smoke requires result.npuExecutionProven=true') }
        }
        'pipeline' {
            $active = Test-DeviceSmokeJsonBooleanTrue (Get-DeviceSmokeProperty -Object $result -Name 'npuActive')
            $executed = Test-DeviceSmokeJsonBooleanTrue (Get-DeviceSmokeProperty -Object $result -Name 'graphExecute')
            if (-not ($active -or $executed)) { [void]$issues.Add('pipeline probe requires result.npuActive=true or result.graphExecute=true') }
        }
        'semantic' {
            if (-not (Test-DeviceSmokeJsonBooleanTrue (Get-DeviceSmokeProperty -Object $result -Name 'npuActive'))) { [void]$issues.Add('semantic generation requires result.npuActive=true') }
            if (-not (Test-DeviceSmokeJsonBooleanTrue (Get-DeviceSmokeProperty -Object $result -Name 'semanticReady'))) { [void]$issues.Add('semantic generation requires result.semanticReady=true') }
        }
    }
    if ($issues.Count -gt 0) { throw "QNN image $Mode contract failed: $($issues -join '; ')." }
}

function Assert-DeviceSmokeMnnDiffusionContract {
    param(
        [object]$Json,
        [ValidateSet('preflight', 'generate')][string]$Mode
    )

    Assert-DeviceSmokeCompleted -Json $Json
    $issues = New-Object System.Collections.ArrayList
    if ((Get-DeviceSmokeProperty -Object $Json -Name 'runtime') -cne 'mnn_diffusion') { [void]$issues.Add('runtime must be mnn_diffusion') }
    if ($Mode -eq 'preflight') {
        if (-not (Test-DeviceSmokeJsonBooleanTrue (Get-DeviceSmokeProperty -Object $Json -Name 'preflightOnly'))) { [void]$issues.Add('preflightOnly must be true') }
        if (-not (Test-DeviceSmokeJsonBooleanTrue (Get-DeviceSmokePathValue -Object $Json -Path @('unetPreflight', 'ok')))) { [void]$issues.Add('unetPreflight.ok must be true') }
    } else {
        if (-not (Test-DeviceSmokeJsonBooleanTrue (Get-DeviceSmokePathValue -Object $Json -Path @('result', 'ok')))) { [void]$issues.Add('result.ok must be true') }
    }
    if ($issues.Count -gt 0) { throw "MNN diffusion $Mode contract failed: $($issues -join '; ')." }
}

function Assert-DeviceSmokeWorkerIsolationContract {
    param(
        [object]$Json,
        [switch]$RequireMainLeaseWait
    )

    Assert-DeviceSmokeCompleted -Json $Json
    $issues = New-Object System.Collections.ArrayList
    if (-not (Test-DeviceSmokeJsonBooleanTrue (Get-DeviceSmokeProperty -Object $Json -Name 'workerProductPath'))) {
        [void]$issues.Add('workerProductPath must be true')
    }
    if (-not (Test-DeviceSmokeJsonBooleanTrue (Get-DeviceSmokeProperty -Object $Json -Name 'workerIsolated'))) {
        [void]$issues.Add('workerIsolated must be true')
    }
    if (-not (Test-DeviceSmokeJsonBooleanTrue (Get-DeviceSmokeProperty -Object $Json -Name 'mainProcessAlive'))) {
        [void]$issues.Add('mainProcessAlive must be true')
    }
    if ($RequireMainLeaseWait) {
        if (-not (Test-DeviceSmokeJsonBooleanTrue (Get-DeviceSmokeProperty -Object $Json -Name 'mainLeaseHeld'))) {
            [void]$issues.Add('mainLeaseHeld must be true when main lease hold is requested')
        }
        if (-not (Test-DeviceSmokeJsonBooleanTrue (Get-DeviceSmokeProperty -Object $Json -Name 'workerWaitedForNativeLease'))) {
            [void]$issues.Add('workerWaitedForNativeLease must be true when main lease hold is requested')
        }
    }
    $mainPid = Get-DeviceSmokeProperty -Object $Json -Name 'mainPid'
    $workerPid = Get-DeviceSmokeProperty -Object $Json -Name 'workerPid'
    try {
        [Int64]$mainPidValue = [Convert]::ToInt64($mainPid)
        [Int64]$workerPidValue = [Convert]::ToInt64($workerPid)
        if ($mainPidValue -le 0) { [void]$issues.Add('mainPid must be positive') }
        if ($workerPidValue -le 0) { [void]$issues.Add('workerPid must be positive') }
        if ($mainPidValue -eq $workerPidValue) { [void]$issues.Add('mainPid and workerPid must be distinct') }
    } catch {
        [void]$issues.Add('mainPid and workerPid must be numeric')
    }
    if ($issues.Count -gt 0) {
        throw "Worker isolation contract failed: $($issues -join '; ')."
    }
}

Export-ModuleMember -Function @(
    'Assert-DeviceSmokeActivityAvailable',
    'Assert-DeviceSmokeCompleted',
    'Assert-DeviceSmokeMnnChatBundle',
    'Assert-DeviceSmokeMnnChatContract',
    'Assert-DeviceSmokeMnnChatTextFragments',
    'Assert-DeviceSmokeMnnDiffusionBundle',
    'Assert-DeviceSmokeMnnDiffusionContract',
    'Assert-DeviceSmokePackageInstalled',
    'Assert-DeviceSmokeQairtChatBundle',
    'Assert-DeviceSmokeQairtDryRunContract',
    'Assert-DeviceSmokeQnnChatContract',
    'Assert-DeviceSmokeQnnImageBundle',
    'Assert-DeviceSmokeQnnImageContract',
    'Assert-DeviceSmokeWorkerIsolationContract',
    'Get-DeviceSmokeEvents',
    'Get-DeviceSmokeSafeName',
    'Get-DeviceSmokeLatestNativeStats',
    'Initialize-DeviceSmokeDevice',
    'Invoke-DeviceSmokeActivityRun',
    'Join-DeviceSmokeRemotePath',
    'New-DeviceSmokeSessionId',
    'Pull-DeviceSmokeRemotePng',
    'Resolve-DeviceSmokeQairtChatBundleRoot',
    'Write-DeviceSmokeSessionSummary'
)
