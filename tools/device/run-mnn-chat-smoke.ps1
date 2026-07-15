param(
    [string]$Adb = 'adb',
    [string]$Serial = '',
    [string]$Package = 'com.muyuchat.mca',
    [Parameter(Mandatory = $true)]
    [string]$ModelPath,
    [string]$DisplayName = '',
    [string]$Prompt = 'Reply with the exact words: MNN smoke passed.',
    [string]$PromptFile = '',
    [string]$ImagePath = '',
    [string]$SecondImagePath = '',
    [string]$TextPreludePrompt = 'Reply with only 42. What is 6 multiplied by 7?',
    [string]$SystemPrompt = 'You are MCA smoke test. Answer briefly in Chinese.',
    [ValidateSet('full', 'api_only', 'direct_twice', 'api_twice', 'direct_counterfactual', 'text_then_image')]
    [string]$SmokeMode = 'full',
    [ValidateRange(1, 2147483647)]
    [int]$Runs = 1,
    [ValidateRange(1, 2147483647)]
    [int]$ContextTokens = 2048,
    [ValidateRange(1, 1024)]
    [int]$Threads = 4,
    [ValidateRange(1, 2147483647)]
    [int]$MaxTokens = 32,
    [ValidateRange(0.0, 2.0)]
    [double]$Temperature = 0.0,
    [ValidateRange(1, 256)]
    [int]$TopK = 1,
    [ValidateRange(0.0, 1.0)]
    [double]$TopP = 1.0,
    [int]$Seed = 0,
    [ValidateRange(1, 64)]
    [int]$ContinuousTurns = 1,
    [ValidateSet('reuse', 'cold')]
    [string]$Lifecycle = 'cold',
    [string[]]$ExpectedTextFragments = @(),
    [string[]]$FirstImageExpectedTextFragments = @(),
    [string[]]$SecondImageExpectedTextFragments = @(),
    [switch]$RequireApiExpectedTextFragments,
    [switch]$AllowIdenticalCounterfactualOutputs,
    [switch]$VisionValidated,
    [string]$OutDir = 'docs\experiments\device-smoke\mnn-chat',
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

# Keep the runner script independent from private module helpers.  The JSON
# result is deserialized as PSCustomObject, so this is sufficient for the two
# nested fields needed by continuous-turn validation.
function Get-MnnChatSmokeEventValue {
    param(
        [object]$Object,
        [string[]]$Path
    )

    $current = $Object
    foreach ($segment in $Path) {
        if ($null -eq $current) { return $null }
        $property = $current.PSObject.Properties[$segment]
        if ($null -eq $property) { return $null }
        $current = $property.Value
    }
    return $current
}

function ConvertTo-MnnChatSmokeShellLiteral {
    param([AllowNull()][string]$Value)

    if ($null -eq $Value) { return "''" }
    if ($Value.IndexOf([char]0) -ge 0) { throw 'Remote shell values must not contain NUL characters.' }
    if ($Value.Contains("`r") -or $Value.Contains("`n")) { throw 'Remote shell values must not contain newlines.' }
    return "'" + $Value.Replace("'", "'\''") + "'"
}

function Invoke-MnnChatSmokeAdbCapture {
    param(
        [string]$Adb,
        [string]$Serial,
        [string[]]$Arguments
    )

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& $Adb -s $Serial @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $lines = @($output | ForEach-Object { [string]$_ })
    return [pscustomobject][ordered]@{
        exitCode = $exitCode
        lines = $lines
        text = $lines -join [Environment]::NewLine
    }
}

function Invoke-MnnChatSmokeRemoteCommand {
    param(
        [string]$Adb,
        [string]$Serial,
        [string]$Command
    )

    return Invoke-MnnChatSmokeAdbCapture -Adb $Adb -Serial $Serial -Arguments @('shell', $Command)
}

function Get-MnnChatSmokeSha256Hex {
    param([byte[]]$Bytes)

    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($sha.ComputeHash($Bytes))).Replace('-', '').ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Get-MnnChatSmokeStringSha256 {
    param([AllowEmptyString()][string]$Text)

    return Get-MnnChatSmokeSha256Hex -Bytes ([Text.Encoding]::UTF8.GetBytes($Text))
}

function New-MnnChatSmokeManifestEvidence {
    param(
        [object[]]$Entries,
        [string]$Path,
        [string]$Kind
    )

    $byPath = [Collections.Generic.Dictionary[string, string]]::new([StringComparer]::Ordinal)
    foreach ($entry in @($Entries)) {
        $entryPath = Get-MnnChatSmokeEventValue -Object $entry -Path @('relativePath')
        if ($entryPath -isnot [string] -or [string]::IsNullOrWhiteSpace($entryPath)) {
            $entryPath = Get-MnnChatSmokeEventValue -Object $entry -Path @('path')
        }
        $entrySha = Get-MnnChatSmokeEventValue -Object $entry -Path @('sha256')
        if ($entryPath -isnot [string] -or [string]::IsNullOrWhiteSpace($entryPath)) {
            throw "$Kind evidence contains an entry without a path."
        }
        if ($entrySha -isnot [string] -or $entrySha -notmatch '^[0-9A-Fa-f]{64}$') {
            throw "$Kind evidence contains an invalid SHA-256 for $entryPath."
        }
        if ($byPath.ContainsKey($entryPath)) {
            throw "$Kind evidence contains a duplicate path: $entryPath"
        }
        $byPath.Add($entryPath, $entrySha.ToLowerInvariant())
    }
    if ($byPath.Count -eq 0) { throw "$Kind evidence did not contain any hashed files under $Path." }

    $paths = [string[]]@($byPath.Keys)
    [Array]::Sort($paths, [StringComparer]::Ordinal)
    $normalizedEntries = @($paths | ForEach-Object {
        [pscustomobject][ordered]@{
            relativePath = $_
            sha256 = $byPath[$_]
        }
    })
    $manifestText = (@($normalizedEntries | ForEach-Object { "$($_.relativePath)`t$($_.sha256)" }) -join "`n") + "`n"
    return [pscustomobject][ordered]@{
        kind = $Kind
        path = $Path
        fileCount = $normalizedEntries.Count
        sha256 = Get-MnnChatSmokeStringSha256 -Text $manifestText
        manifestUtf8Base64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($manifestText))
        files = $normalizedEntries
    }
}

function Get-MnnChatSmokeRemoteFileEvidence {
    param(
        [string]$Adb,
        [string]$Serial,
        [string]$Path,
        [string]$Kind = 'remote_file'
    )

    $literal = ConvertTo-MnnChatSmokeShellLiteral -Value $Path
    $command = "if command -v sha256sum >/dev/null 2>&1; then sha256sum $literal; else toybox sha256sum $literal; fi"
    $result = Invoke-MnnChatSmokeRemoteCommand -Adb $Adb -Serial $Serial -Command $command
    if ($result.exitCode -ne 0) {
        throw "Unable to hash $Kind at ${Path}: $($result.text)"
    }
    $match = @($result.lines | ForEach-Object {
        [regex]::Match($_, '^\s*([0-9A-Fa-f]{64})\s+\*?.+$')
    } | Where-Object Success | Select-Object -First 1)
    if ($match.Count -ne 1) {
        throw "Unable to parse SHA-256 for $Kind at ${Path}: $($result.text)"
    }
    return [pscustomobject][ordered]@{
        kind = $Kind
        path = $Path
        sha256 = $match[0].Groups[1].Value.ToLowerInvariant()
    }
}

function Get-MnnChatSmokeRemoteDirectoryEvidence {
    param(
        [string]$Adb,
        [string]$Serial,
        [string]$Path,
        [string]$Kind
    )

    $root = $Path.TrimEnd('/')
    $literal = ConvertTo-MnnChatSmokeShellLiteral -Value $root
    $command = "root=$literal; if [ ! -d `"`$root`" ]; then echo missing-directory >&2; exit 4; fi; find `"`$root`" -type f -exec sha256sum {} \;"
    $result = Invoke-MnnChatSmokeRemoteCommand -Adb $Adb -Serial $Serial -Command $command
    if ($result.exitCode -ne 0) {
        throw "Unable to hash $Kind directory at ${Path}: $($result.text)"
    }

    $entries = New-Object System.Collections.ArrayList
    foreach ($line in $result.lines) {
        $match = [regex]::Match($line, '^\s*([0-9A-Fa-f]{64})\s+\*?(.+?)\s*$')
        if (-not $match.Success) { continue }
        $remotePath = $match.Groups[2].Value
        $prefix = "$root/"
        if (-not $remotePath.StartsWith($prefix, [StringComparison]::Ordinal)) {
            throw "$Kind hash output escaped its root: $remotePath"
        }
        [void]$entries.Add([pscustomobject][ordered]@{
            path = $remotePath
            relativePath = $remotePath.Substring($prefix.Length)
            sha256 = $match.Groups[1].Value.ToLowerInvariant()
        })
    }
    return New-MnnChatSmokeManifestEvidence -Entries @($entries) -Path $root -Kind $Kind
}

function Get-MnnChatSmokeApkEvidence {
    param(
        [string]$Adb,
        [string]$Serial,
        [string]$Package
    )

    $result = Invoke-MnnChatSmokeRemoteCommand -Adb $Adb -Serial $Serial -Command "pm path $Package"
    if ($result.exitCode -ne 0) { throw "Unable to query APK paths for ${Package}: $($result.text)" }
    $paths = @($result.lines | ForEach-Object {
        if ($_ -match '^package:(/.+?\.apk)\s*$') { $matches[1] }
    })
    if ($paths.Count -eq 0) { throw "Package $Package did not report an APK path." }

    $files = @($paths | ForEach-Object {
        $file = Get-MnnChatSmokeRemoteFileEvidence -Adb $Adb -Serial $Serial -Path $_ -Kind 'apk'
        [pscustomobject][ordered]@{
            path = $file.path
            relativePath = $file.path
            sha256 = $file.sha256
        }
    })
    $manifest = New-MnnChatSmokeManifestEvidence -Entries $files -Path $Package -Kind 'apk_set'
    return [pscustomobject][ordered]@{
        package = $Package
        sha256 = if ($files.Count -eq 1) { $files[0].sha256 } else { $manifest.sha256 }
        manifestSha256 = $manifest.sha256
        files = $files
    }
}

function New-MnnChatSmokeTextEvidence {
    param(
        [AllowEmptyString()][string]$Text,
        [string]$Label,
        [string]$RunOutputDir,
        [string]$RunId,
        [string[]]$ExpectedFragments = @()
    )

    if ([string]::IsNullOrWhiteSpace($Text)) { throw "$Label output text must be nonempty." }
    $missing = @($ExpectedFragments | Where-Object { -not $Text.Contains([string]$_) })
    if ($missing.Count -gt 0) {
        $quoted = @($missing | ForEach-Object { "'$_'" }) -join ', '
        throw "$Label output is missing expected fragment(s): $quoted."
    }

    $bytes = [Text.Encoding]::UTF8.GetBytes($Text)
    $safeLabel = ($Label -replace '[^A-Za-z0-9._-]+', '-').Trim('-')
    $path = Join-Path $RunOutputDir "$RunId-$safeLabel.utf8.txt"
    [IO.File]::WriteAllBytes($path, $bytes)
    return [pscustomobject][ordered]@{
        label = $Label
        rawText = $Text
        expectedFragments = @($ExpectedFragments)
        utf16Length = $Text.Length
        utf8Bytes = $bytes.Length
        utf8Hex = ([BitConverter]::ToString($bytes)).Replace('-', '')
        utf8Base64 = [Convert]::ToBase64String($bytes)
        sha256 = Get-MnnChatSmokeSha256Hex -Bytes $bytes
        utf8File = $path
    }
}

function New-MnnChatSmokeConfigEvidence {
    param(
        [AllowEmptyString()][string]$RawJson,
        [string]$Label,
        [string]$RunOutputDir,
        [string]$RunId
    )

    if ([string]::IsNullOrWhiteSpace($RawJson)) { throw "$Label config JSON must be nonempty." }
    try { $null = $RawJson | ConvertFrom-Json } catch { throw "$Label config JSON is invalid: $($_.Exception.Message)" }
    $textEvidence = New-MnnChatSmokeTextEvidence -Text $RawJson -Label $Label -RunOutputDir $RunOutputDir -RunId $RunId
    return [pscustomobject][ordered]@{
        label = $Label
        rawJson = $RawJson
        sha256 = $textEvidence.sha256
        utf8Bytes = $textEvidence.utf8Bytes
        utf8Hex = $textEvidence.utf8Hex
        utf8Base64 = $textEvidence.utf8Base64
        utf8File = $textEvidence.utf8File
    }
}

function Assert-MnnChatSmokeDirectCounterfactualContract {
    param(
        [object]$Json,
        [string]$ImagePath,
        [string]$SecondImagePath,
        [object]$InputEvidence,
        [string[]]$FirstExpectedFragments = @(),
        [string[]]$SecondExpectedFragments = @(),
        [string]$RunOutputDir,
        [string]$RunId,
        [switch]$AllowIdenticalOutputs,
        [ref]$EvidenceOut
    )

    $firstInputSha = Get-MnnChatSmokeEventValue -Object $InputEvidence -Path @('first', 'sha256')
    $secondInputSha = Get-MnnChatSmokeEventValue -Object $InputEvidence -Path @('second', 'sha256')
    if ($firstInputSha -isnot [string] -or $firstInputSha -notmatch '^[0-9A-Fa-f]{64}$' -or
        $secondInputSha -isnot [string] -or $secondInputSha -notmatch '^[0-9A-Fa-f]{64}$') {
        throw 'direct_counterfactual requires valid SHA-256 evidence for both input images.'
    }
    if ($firstInputSha -ceq $secondInputSha) {
        throw 'direct_counterfactual input images have identical SHA-256 values.'
    }

    $nativeStats = Get-DeviceSmokeLatestNativeStats -Json $Json
    if ((Get-MnnChatSmokeEventValue -Object $nativeStats -Path @('visionReady')) -ne $true) {
        throw 'direct_counterfactual requires nativeStats.visionReady=true.'
    }
    $visualModelPath = Get-MnnChatSmokeEventValue -Object $nativeStats -Path @('visualModelPath')
    if ($visualModelPath -isnot [string] -or [string]::IsNullOrWhiteSpace($visualModelPath)) {
        throw 'direct_counterfactual requires a nonempty nativeStats.visualModelPath.'
    }

    $firstEvents = @(Get-DeviceSmokeEvents -Json $Json | Where-Object {
        $_.status -is [string] -and $_.status -ceq 'generation_first_ok'
    })
    $secondEvents = @(Get-DeviceSmokeEvents -Json $Json | Where-Object {
        $_.status -is [string] -and $_.status -ceq 'generation_second_ok'
    })
    if ($firstEvents.Count -ne 1 -or $secondEvents.Count -ne 1) {
        throw "direct_counterfactual requires exactly one generation_first_ok and generation_second_ok event; found $($firstEvents.Count) and $($secondEvents.Count)."
    }

    $firstEventImage = Get-MnnChatSmokeEventValue -Object $firstEvents[0] -Path @('generation', 'imagePath')
    $secondEventImage = Get-MnnChatSmokeEventValue -Object $secondEvents[0] -Path @('generation', 'imagePath')
    if ($firstEventImage -cne $ImagePath) {
        throw "generation_first_ok imagePath does not match the first input: '$firstEventImage'."
    }
    if ($secondEventImage -cne $SecondImagePath) {
        throw "generation_second_ok imagePath does not match the second input: '$secondEventImage'."
    }

    $firstText = Get-MnnChatSmokeEventValue -Object $firstEvents[0] -Path @('generation', 'text')
    $secondText = Get-MnnChatSmokeEventValue -Object $secondEvents[0] -Path @('generation', 'text')
    $firstOutput = New-MnnChatSmokeTextEvidence -Text $firstText -Label 'generation-first' `
        -RunOutputDir $RunOutputDir -RunId $RunId
    $secondOutput = New-MnnChatSmokeTextEvidence -Text $secondText -Label 'generation-second' `
        -RunOutputDir $RunOutputDir -RunId $RunId

    $evidence = [pscustomobject][ordered]@{
        strict = $true
        inputSha256Differ = $true
        outputSha256Differ = [bool]($firstOutput.sha256 -cne $secondOutput.sha256)
        identicalOutputsAllowed = [bool]$AllowIdenticalOutputs
        first = [pscustomobject][ordered]@{
            input = Get-MnnChatSmokeEventValue -Object $InputEvidence -Path @('first')
            output = $firstOutput
        }
        second = [pscustomobject][ordered]@{
            input = Get-MnnChatSmokeEventValue -Object $InputEvidence -Path @('second')
            output = $secondOutput
        }
    }
    if ($null -ne $EvidenceOut) { $EvidenceOut.Value = $evidence }

    $missingFirst = @($FirstExpectedFragments | Where-Object { -not $firstText.Contains([string]$_) })
    if ($missingFirst.Count -gt 0) {
        $quoted = @($missingFirst | ForEach-Object { "'$_'" }) -join ', '
        throw "generation-first output is missing expected fragment(s): $quoted."
    }
    $missingSecond = @($SecondExpectedFragments | Where-Object { -not $secondText.Contains([string]$_) })
    if ($missingSecond.Count -gt 0) {
        $quoted = @($missingSecond | ForEach-Object { "'$_'" }) -join ', '
        throw "generation-second output is missing expected fragment(s): $quoted."
    }

    $reportedFirstSha = Get-MnnChatSmokeEventValue -Object $firstEvents[0] -Path @('generation', 'textSha256')
    $reportedSecondSha = Get-MnnChatSmokeEventValue -Object $secondEvents[0] -Path @('generation', 'textSha256')
    if ($reportedFirstSha -isnot [string] -or $reportedFirstSha -notmatch '^[0-9A-Fa-f]{64}$' -or
        $reportedFirstSha.ToLowerInvariant() -cne $firstOutput.sha256) {
        throw 'generation_first_ok textSha256 is missing or does not match the raw UTF-8 output.'
    }
    if ($reportedSecondSha -isnot [string] -or $reportedSecondSha -notmatch '^[0-9A-Fa-f]{64}$' -or
        $reportedSecondSha.ToLowerInvariant() -cne $secondOutput.sha256) {
        throw 'generation_second_ok textSha256 is missing or does not match the raw UTF-8 output.'
    }
    if (-not $AllowIdenticalOutputs -and $firstOutput.sha256 -ceq $secondOutput.sha256) {
        throw 'direct_counterfactual produced identical output SHA-256 values for different input images.'
    }
    return $evidence
}

function Assert-MnnChatSmokeModeContract {
    param(
        [object]$Json,
        [string]$Mode
    )

    $rootStatus = Get-MnnChatSmokeEventValue -Object $Json -Path @('status')
    if ($rootStatus -cne 'completed') {
        throw "Activity result root status must be completed; found '$rootStatus'."
    }
    $nativeStats = Get-DeviceSmokeLatestNativeStats -Json $Json
    if ((Get-MnnChatSmokeEventValue -Object $nativeStats -Path @('backend')) -cne 'mnn_cpu' -or
        (Get-MnnChatSmokeEventValue -Object $nativeStats -Path @('loaded')) -ne $true -or
        (Get-MnnChatSmokeEventValue -Object $nativeStats -Path @('runnerReady')) -ne $true) {
        throw 'MNN smoke terminal nativeStats must report backend=mnn_cpu, loaded=true, and runnerReady=true.'
    }
    if ($Mode -eq 'full') {
        Assert-DeviceSmokeMnnChatContract -Json $Json
        return
    }

    $requiredStatuses = switch ($Mode) {
        'api_only' { @('api_engine_stream_ok') }
        'direct_twice' { @('generation_first_ok', 'generation_second_ok') }
        'direct_counterfactual' { @('generation_first_ok', 'generation_second_ok') }
        'text_then_image' { @('generation_text_ok', 'generation_image_ok') }
        'api_twice' { @('api_engine_first_ok', 'api_engine_second_ok') }
        default { throw "Unsupported MNN smoke mode contract: $Mode" }
    }
    foreach ($status in $requiredStatuses) {
        $events = @(Get-DeviceSmokeEvents -Json $Json | Where-Object {
            $_.status -is [string] -and $_.status -ceq $status
        })
        if ($events.Count -ne 1) {
            throw "MNN $Mode contract requires exactly one $status event; found $($events.Count)."
        }
        $payloadName = if ($status.StartsWith('api_engine_', [System.StringComparison]::Ordinal)) {
            'apiEngine'
        } else {
            'generation'
        }
        $text = Get-MnnChatSmokeEventValue -Object $events[0] -Path @($payloadName, 'text')
        if ($text -isnot [string] -or [string]::IsNullOrWhiteSpace($text)) {
            throw "MNN $status requires nonempty $payloadName.text."
        }
        if ($text -match '(?i)<\|[^|>\r\n]{1,80}\|>|<eop>|(?:^|\s)(?:human|user|assistant)\s*:') {
            throw "MNN $status leaks a template/protocol marker."
        }
    }
}

if ($ModelPath.IndexOf([char]0) -ge 0 -or -not $ModelPath.StartsWith('/')) {
    throw 'ModelPath must be an absolute Android path without NUL characters.'
}
if ($Prompt.IndexOf([char]0) -ge 0) { throw 'Prompt must not contain a NUL character.' }
if (-not [string]::IsNullOrWhiteSpace($ImagePath) -and
    ($ImagePath.IndexOf([char]0) -ge 0 -or -not $ImagePath.StartsWith('/'))) {
    throw 'ImagePath must be an absolute Android path without NUL characters.'
}
if (-not [string]::IsNullOrWhiteSpace($SecondImagePath) -and
    ($SecondImagePath.IndexOf([char]0) -ge 0 -or -not $SecondImagePath.StartsWith('/'))) {
    throw 'SecondImagePath must be an absolute Android path without NUL characters.'
}
if ($TextPreludePrompt.IndexOf([char]0) -ge 0) {
    throw 'TextPreludePrompt must not contain a NUL character.'
}
if ($SystemPrompt.IndexOf([char]0) -ge 0) {
    throw 'SystemPrompt must not contain a NUL character.'
}
if ($SmokeMode -eq 'direct_counterfactual' -and
    ([string]::IsNullOrWhiteSpace($ImagePath) -or [string]::IsNullOrWhiteSpace($SecondImagePath))) {
    throw 'direct_counterfactual requires both ImagePath and SecondImagePath.'
}
if ($SmokeMode -eq 'text_then_image' -and [string]::IsNullOrWhiteSpace($ImagePath)) {
    throw 'text_then_image requires ImagePath.'
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
foreach ($fragment in @($ExpectedTextFragments) + @($FirstImageExpectedTextFragments) + @($SecondImageExpectedTextFragments)) {
    if ($null -eq $fragment -or $fragment.IndexOf([char]0) -ge 0) {
        throw 'Expected text fragments must not contain null values or NUL characters.'
    }
    if ($fragment.Contains("`r") -or $fragment.Contains("`n")) {
        throw 'Expected text fragments must not contain newlines.'
    }
}
if ($RequireApiExpectedTextFragments -and @($ExpectedTextFragments).Count -eq 0) {
    throw 'RequireApiExpectedTextFragments requires at least one ExpectedTextFragments value.'
}
if ($SmokeMode -ne 'direct_counterfactual' -and
    (@($FirstImageExpectedTextFragments).Count -gt 0 -or @($SecondImageExpectedTextFragments).Count -gt 0)) {
    throw 'FirstImageExpectedTextFragments and SecondImageExpectedTextFragments are only valid for direct_counterfactual.'
}
if ($SmokeMode -eq 'direct_counterfactual' -and @($ExpectedTextFragments).Count -gt 0) {
    throw 'direct_counterfactual requires per-image expected fragments; use FirstImageExpectedTextFragments and SecondImageExpectedTextFragments.'
}
if ([string]::IsNullOrWhiteSpace($DisplayName)) {
    $DisplayName = Split-Path -Leaf $ModelPath.TrimEnd('/')
}
if ([string]::IsNullOrWhiteSpace($DisplayName)) { $DisplayName = 'MNN chat bundle' }
if ([string]::IsNullOrWhiteSpace($SessionId)) { $SessionId = New-DeviceSmokeSessionId -Prefix 'mnn-chat' }
$SessionId = Get-DeviceSmokeSafeName -Value $SessionId

$component = "$Package/.debug.LocalChatSmokeActivity"
$serial = Initialize-DeviceSmokeDevice -Adb $Adb -Serial $Serial
Assert-DeviceSmokePackageInstalled -Adb $Adb -Serial $serial -Package $Package
Assert-DeviceSmokeActivityAvailable -Adb $Adb -Serial $serial -Component $component
Assert-DeviceSmokeMnnChatBundle -Adb $Adb -Serial $serial -ModelPath $ModelPath

$runOutputDir = Join-Path $OutDir $SessionId
New-Item -ItemType Directory -Force -Path $runOutputDir | Out-Null
$externalRoot = "/storage/emulated/0/Android/data/$Package/files"
$remotePromptFile = ''
if (-not [string]::IsNullOrWhiteSpace($PromptFile)) {
    $remotePromptFile = "$externalRoot/chat_smoke/inputs/$SessionId-prompt.txt"
    & $Adb -s $serial shell mkdir -p "$externalRoot/chat_smoke/inputs" | Out-Null
    # adb reports successful push progress on stderr.  Under the script-wide
    # Stop preference PowerShell turns that progress record into a terminating
    # NativeCommandError before we can inspect the real process exit code.
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
$counterfactualInputEvidence = $null
$sessionArtifactEvidence = $null
if ($SmokeMode -eq 'direct_counterfactual') {
    $firstInput = Get-MnnChatSmokeRemoteFileEvidence -Adb $Adb -Serial $serial -Path $ImagePath -Kind 'counterfactual_first_image'
    $secondInput = Get-MnnChatSmokeRemoteFileEvidence -Adb $Adb -Serial $serial -Path $SecondImagePath -Kind 'counterfactual_second_image'
    if ($firstInput.sha256 -ceq $secondInput.sha256) {
        throw 'direct_counterfactual input images have identical SHA-256 values; distinct image bytes are required.'
    }
    $counterfactualInputEvidence = [pscustomobject][ordered]@{
        sha256Differ = $true
        first = $firstInput
        second = $secondInput
    }

    $apkEvidence = Get-MnnChatSmokeApkEvidence -Adb $Adb -Serial $serial -Package $Package
    $bundleEvidence = Get-MnnChatSmokeRemoteDirectoryEvidence -Adb $Adb -Serial $serial -Path $ModelPath -Kind 'mnn_bundle'
    $bundleConfigEntries = @($bundleEvidence.files | Where-Object {
        $_.relativePath -match '(^|/)(?:config|llm_config|mca_runtime_config)\.json$'
    })
    $bundleConfigEvidence = New-MnnChatSmokeManifestEvidence `
        -Entries $bundleConfigEntries -Path $ModelPath -Kind 'mnn_bundle_config'
    $requestConfig = [pscustomobject][ordered]@{
        runtime = 'mnn_cpu'
        modelPath = $ModelPath
        smokeMode = $SmokeMode
        prompt = $Prompt
        promptFile = $PromptFile
        imagePath = $ImagePath
        secondImagePath = $SecondImagePath
        systemPrompt = $SystemPrompt
        contextTokens = $ContextTokens
        threads = $Threads
        maxTokens = $MaxTokens
        temperature = $Temperature
        topK = $TopK
        topP = $TopP
        seed = $Seed
        lifecycle = $Lifecycle
        firstImageExpectedTextFragments = @($FirstImageExpectedTextFragments)
        secondImageExpectedTextFragments = @($SecondImageExpectedTextFragments)
        allowIdenticalCounterfactualOutputs = [bool]$AllowIdenticalCounterfactualOutputs
    }
    $requestConfigRaw = $requestConfig | ConvertTo-Json -Depth 8 -Compress
    $requestConfigEvidence = New-MnnChatSmokeConfigEvidence `
        -RawJson $requestConfigRaw -Label 'request-config' -RunOutputDir $runOutputDir -RunId $SessionId
    $sessionArtifactEvidence = [pscustomobject][ordered]@{
        apk = $apkEvidence
        bundle = $bundleEvidence
        bundleConfig = $bundleConfigEvidence
        requestConfig = $requestConfigEvidence
    }
}
$safeModel = Get-DeviceSmokeSafeName -Value $DisplayName
$summaries = @()

Write-Host "Device: $serial"
Write-Host "Model: $ModelPath"
Write-Host "Sampling: temperature=$Temperature topK=$TopK topP=$TopP seed=$Seed"
Write-Host "Output: $runOutputDir"

for ($run = 1; $run -le $Runs; $run++) {
    $runId = "$safeModel-mnn-$SessionId-r$run"
    $remoteJson = "$externalRoot/chat_smoke/runs/$runId.json"
    $localJson = Join-Path $runOutputDir "$runId.json"
    $result = $null
    $contractError = $null
    $unicodeEvidence = $null
    $counterfactualEvidence = $null
    $runArtifactEvidence = $null
    try {
        $activityArguments = @(
            'am', 'start', '-W', '-n', $component,
            '--es', 'runtime', 'mnn_cpu',
            '--es', 'modelPath', $ModelPath,
            '--es', 'displayName', $DisplayName,
            '--es', 'runId', $runId,
            '--es', 'smokeMode', $SmokeMode,
            '--es', 'systemPrompt', $SystemPrompt,
            '--es', 'computeUnit', 'cpu',
            '--ei', 'nCtx', [string]$ContextTokens,
            '--ei', 'nThreads', [string]$Threads,
            '--ei', 'maxTokens', [string]$MaxTokens,
            '--ef', 'temperature', ([string]::Format([Globalization.CultureInfo]::InvariantCulture, '{0:0.########}', $Temperature)),
            '--ei', 'topK', [string]$TopK,
            '--ef', 'topP', ([string]::Format([Globalization.CultureInfo]::InvariantCulture, '{0:0.########}', $TopP)),
            '--ei', 'seed', [string]$Seed,
            '--ei', 'continuousTurns', [string]$ContinuousTurns
        )
        if (-not [string]::IsNullOrWhiteSpace($ImagePath)) {
            $activityArguments += @('--es', 'imagePath', $ImagePath)
        }
        if (-not [string]::IsNullOrWhiteSpace($SecondImagePath)) {
            $activityArguments += @('--es', 'secondImagePath', $SecondImagePath)
        }
        if ($VisionValidated) {
            $activityArguments += @('--ez', 'visionValidated', 'true')
        }
        if ($SmokeMode -eq 'text_then_image') {
            $activityArguments += @('--es', 'textPreludePrompt', $TextPreludePrompt)
        }
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
            Assert-MnnChatSmokeModeContract -Json $result.json -Mode $SmokeMode
            if ($SmokeMode -eq 'direct_counterfactual') {
                $completedNativeStats = Get-DeviceSmokeLatestNativeStats -Json $result.json
                $nativeLibDir = Get-MnnChatSmokeEventValue -Object $completedNativeStats -Path @('nativeLibDir')
                if ($nativeLibDir -isnot [string] -or [string]::IsNullOrWhiteSpace($nativeLibDir) -or
                    -not $nativeLibDir.StartsWith('/')) {
                    throw 'direct_counterfactual requires an absolute nativeStats.nativeLibDir for native-library hashing.'
                }
                $nativeLibraryEvidence = Get-MnnChatSmokeRemoteDirectoryEvidence `
                    -Adb $Adb -Serial $serial -Path $nativeLibDir -Kind 'native_libraries'

                $lastConfigJson = Get-MnnChatSmokeEventValue -Object $completedNativeStats -Path @('lastConfigJson')
                $runtimeConfigEvidence = New-MnnChatSmokeConfigEvidence `
                    -RawJson $lastConfigJson -Label 'native-runtime-config' -RunOutputDir $runOutputDir -RunId $runId
                $runtimeConfigPath = Get-MnnChatSmokeEventValue -Object $completedNativeStats -Path @('modelPath')
                $runtimeConfigFileEvidence = $null
                if ($runtimeConfigPath -is [string] -and $runtimeConfigPath.StartsWith('/') -and
                    $runtimeConfigPath.EndsWith('.json', [StringComparison]::OrdinalIgnoreCase)) {
                    $runtimeConfigFileEvidence = Get-MnnChatSmokeRemoteFileEvidence `
                        -Adb $Adb -Serial $serial -Path $runtimeConfigPath -Kind 'native_runtime_config_file'
                }
                $runArtifactEvidence = [pscustomobject][ordered]@{
                    nativeLibraries = $nativeLibraryEvidence
                    nativeRuntimeConfig = $runtimeConfigEvidence
                    nativeRuntimeConfigFile = $runtimeConfigFileEvidence
                }

                $null = Assert-MnnChatSmokeDirectCounterfactualContract `
                    -Json $result.json -ImagePath $ImagePath -SecondImagePath $SecondImagePath `
                    -InputEvidence $counterfactualInputEvidence `
                    -FirstExpectedFragments $FirstImageExpectedTextFragments `
                    -SecondExpectedFragments $SecondImageExpectedTextFragments `
                    -RunOutputDir $runOutputDir -RunId $runId `
                    -AllowIdenticalOutputs:$AllowIdenticalCounterfactualOutputs `
                    -EvidenceOut ([ref]$counterfactualEvidence)
            }
            if ($SmokeMode -eq 'full' -and $ContinuousTurns -gt 1) {
                # Find-DeviceSmokeEvent intentionally returns only the final
                # matching event for single-result callers.  A continuous-turn
                # regression must inspect every turn instead.
                $turnEvents = @(Get-DeviceSmokeEvents -Json $result.json | Where-Object {
                    $_.status -is [string] -and $_.status -ceq 'generation_turn_ok'
                })
                if ($turnEvents.Count -ne $ContinuousTurns) {
                    throw "MNN continuous-turn contract expected $ContinuousTurns generation_turn_ok events, found $($turnEvents.Count)."
                }
            }
            if ($SmokeMode -eq 'full' -and @($ExpectedTextFragments).Count -gt 0) {
                $unicodeEvidence = Assert-DeviceSmokeMnnChatTextFragments `
                    -Json $result.json -ExpectedFragments $ExpectedTextFragments
                if ($ContinuousTurns -gt 1) {
                    foreach ($turnEvent in @(Get-DeviceSmokeEvents -Json $result.json | Where-Object {
                    $_.status -is [string] -and $_.status -ceq 'generation_turn_ok'
                })) {
                        $turn = Get-MnnChatSmokeEventValue -Object $turnEvent -Path @('turn')
                        $turnText = Get-MnnChatSmokeEventValue -Object $turnEvent -Path @('generation', 'text')
                        $missing = @($ExpectedTextFragments | Where-Object {
                            $turnText -isnot [string] -or -not $turnText.Contains([string]$_)
                        })
                        if ($missing.Count -gt 0) {
                            $quotedMissing = @($missing | ForEach-Object { "'$_'" }) -join ', '
                            throw "MNN turn $turn is missing expected text fragment(s): $quotedMissing."
                        }
                    }
                }
                if ($RequireApiExpectedTextFragments -and -not $unicodeEvidence.apiTextContainsAll) {
                    throw 'MNN API completion is missing one or more expected text fragments.'
                }
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
        unicodeEvidence = $unicodeEvidence
        counterfactualEvidence = $counterfactualEvidence
        artifactEvidence = $runArtifactEvidence
    }
    Write-Host "[$run/$Runs] status=$($result.status) rawJson=$($result.rawResultPreserved)"
}

$summary = [pscustomobject][ordered]@{
    sessionId = $SessionId
    serial = $serial
    package = $Package
    component = $component
    runtime = 'mnn_cpu'
    modelPath = $ModelPath
    displayName = $DisplayName
    prompt = $Prompt
    imagePath = $ImagePath
    secondImagePath = $SecondImagePath
    textPreludePrompt = $TextPreludePrompt
    systemPrompt = $SystemPrompt
    smokeMode = $SmokeMode
    computeUnit = 'cpu'
    contextTokens = $ContextTokens
    threads = $Threads
    maxTokens = $MaxTokens
    temperature = $Temperature
    topK = $TopK
    topP = $TopP
    seed = $Seed
    continuousTurns = $ContinuousTurns
    lifecycle = $Lifecycle
    expectedTextFragments = @($ExpectedTextFragments)
    firstImageExpectedTextFragments = @($FirstImageExpectedTextFragments)
    secondImageExpectedTextFragments = @($SecondImageExpectedTextFragments)
    requireApiExpectedTextFragments = [bool]$RequireApiExpectedTextFragments
    allowIdenticalCounterfactualOutputs = [bool]$AllowIdenticalCounterfactualOutputs
    visionValidated = [bool]$VisionValidated
    counterfactualInputEvidence = $counterfactualInputEvidence
    artifactEvidence = $sessionArtifactEvidence
    runs = @($summaries)
}
$summaryPath = Join-Path $runOutputDir 'summary.json'
Write-DeviceSmokeSessionSummary -Path $summaryPath -Summary $summary
Write-Host "Summary: $summaryPath"

$failed = @($summaries | Where-Object { $_.status -ne 'completed' })
if ($failed.Count -gt 0) {
    throw "$($failed.Count) of $Runs MNN chat smoke run(s) failed."
}
