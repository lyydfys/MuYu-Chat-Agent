Set-StrictMode -Version Latest

$script:Utf8Strict = New-Object System.Text.UTF8Encoding($false, $true)
$script:LegacyChineseStrict = [Text.Encoding]::GetEncoding(
    936,
    (New-Object Text.EncoderExceptionFallback),
    (New-Object Text.DecoderExceptionFallback)
)

if (-not ("Mca.Benchmark.StrictJsonValidator" -as [type])) {
    Add-Type -Path (Join-Path $PSScriptRoot "StrictJsonValidator.cs")
}
if (-not ("Mca.Benchmark.PngInspector" -as [type])) {
    # Supplying an explicit reference list on .NET Core replaces the compiler's
    # default references, so System.Collections.Generic.List<T> is otherwise
    # missing despite the source's using directive. Keep the compression
    # reference explicit and add the collections assembly where it exists.
    $pngInspectorReferences = @("System.IO.Compression.dll")
    $runtimeDirectory = [System.Runtime.InteropServices.RuntimeEnvironment]::GetRuntimeDirectory()
    $collectionsAssembly = "System.Collections.dll"
    if ($runtimeDirectory -and (Test-Path -LiteralPath (Join-Path $runtimeDirectory $collectionsAssembly))) {
        $pngInspectorReferences += $collectionsAssembly
    }
    Add-Type -Path (Join-Path $PSScriptRoot "PngInspector.cs") -ReferencedAssemblies $pngInspectorReferences
}

function Get-StrictFileSha256 {
    param([Parameter(Mandatory = $true)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

# PowerShell 7's ConvertFrom-Json coerces ISO-8601-looking strings into
# System.DateTime, while Windows PowerShell 5.1 keeps them as strings. Benchmark
# artifacts must parse to identical runtime types on both hosts, so -DateKind
# String is requested wherever the parameter exists. The parameter is absent on
# Windows PowerShell 5.1, where the string-preserving behaviour is already the
# default, so it is only splatted when supported.
$script:ConvertFromJsonSupportsDateKind = $null
function Test-ConvertFromJsonDateKindSupport {
    if ($null -eq $script:ConvertFromJsonSupportsDateKind) {
        $command = Get-Command ConvertFrom-Json -ErrorAction Stop
        $script:ConvertFromJsonSupportsDateKind = $command.Parameters.ContainsKey('DateKind')
    }
    return $script:ConvertFromJsonSupportsDateKind
}

function ConvertFrom-StrictJsonText {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Text)
    $arguments = @{ ErrorAction = 'Stop' }
    if (Test-ConvertFromJsonDateKindSupport) { $arguments['DateKind'] = 'String' }
    return ConvertFrom-Json -InputObject $Text @arguments
}

function Read-StrictUtf8JsonFile {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [bool]$RequireObject = $true
    )
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "JSON file not found: $Path" }
    $bytes = [IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
        throw "UTF-8 BOM is not allowed: $Path"
    }
    try { $text = $script:Utf8Strict.GetString($bytes) }
    catch { throw "File is not strict UTF-8: $Path. $($_.Exception.Message)" }
    try { [Mca.Benchmark.StrictJsonValidator]::Validate($text) }
    catch { throw "Strict JSON validation failed for '$Path': $($_.Exception.Message)" }
    try { $value = ConvertFrom-StrictJsonText -Text $text }
    catch { throw "PowerShell JSON conversion failed for '$Path': $($_.Exception.Message)" }
    if ($RequireObject -and $value -isnot [pscustomobject]) { throw "JSON root must be an object: $Path" }
    return [pscustomobject][ordered]@{
        path = [IO.Path]::GetFullPath($Path)
        value = $value
        text = $text
        byteLength = $bytes.LongLength
        sha256 = Get-StrictFileSha256 -Path $Path
        encoding = "utf-8-no-bom"
    }
}

function Test-LikelyMojibake {
    param([AllowEmptyString()][string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text) -or $Text.Length -lt 6 -or $Text -notmatch '[\u3400-\u9fff]') {
        return $false
    }
    if ($Text -match '璇风敤|璇烽槄|涓€|绾㈣壊|锛岀|銆傞|鐨勬|闂') {
        return $true
    }
    if ($Text -match '璇风敤|璇疯|锛屽|銆傛|鐨勫|浠诲|绔嬪|鎴愬|缁撴') {
        return $true
    }
    try {
        $legacyBytes = $script:LegacyChineseStrict.GetBytes($Text)
        $repaired = $script:Utf8Strict.GetString($legacyBytes)
    } catch {
        return $false
    }
    return ($repaired -ne $Text -and $repaired.Length -lt $Text.Length -and $repaired -match '[\u3400-\u9fff]')
}

function Get-JsonStringIntegrityIssues {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]$Value,
        [string]$Path = '$'
    )
    $issues = New-Object System.Collections.Generic.List[object]
    function Visit-JsonValue {
        param($Current, [string]$CurrentPath)
        if ($null -eq $Current) { return }
        if ($Current -is [string]) {
            if ($Current.IndexOf([char]0xFFFD) -ge 0) {
                [void]$issues.Add([pscustomobject]@{ path = $CurrentPath; reason = 'unicode_replacement_character' })
            }
            if (Test-LikelyMojibake -Text $Current) {
                [void]$issues.Add([pscustomobject]@{ path = $CurrentPath; reason = 'likely_utf8_gbk_mojibake' })
            }
            return
        }
        if ($Current -is [pscustomobject]) {
            foreach ($property in $Current.PSObject.Properties) {
                Visit-JsonValue -Current $property.Value -CurrentPath ($CurrentPath + '.' + $property.Name)
            }
            return
        }
        if ($Current -is [Collections.IDictionary]) {
            foreach ($key in $Current.Keys) {
                Visit-JsonValue -Current $Current[$key] -CurrentPath ($CurrentPath + '.' + $key)
            }
            return
        }
        if ($Current -is [Collections.IEnumerable]) {
            $index = 0
            foreach ($item in $Current) {
                Visit-JsonValue -Current $item -CurrentPath ($CurrentPath + '[' + $index + ']')
                $index++
            }
        }
    }
    Visit-JsonValue -Current $Value -CurrentPath $Path
    return @($issues.ToArray())
}

function Assert-JsonStringIntegrity {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)]$Value)
    $issues = @(Get-JsonStringIntegrityIssues -Value $Value)
    if ($issues.Count -gt 0) {
        $summary = @($issues | ForEach-Object { "$($_.path):$($_.reason)" }) -join ', '
        throw "JSON contains invalid or likely mojibake text: $summary"
    }
}

function Get-BenchmarkPropertyValue {
    param($Object, [string]$Name)
    if ($null -eq $Object) { return $null }
    if ($Object -is [Collections.IDictionary]) {
        if ($Object.Contains($Name)) { return $Object[$Name] }
        return $null
    }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function Get-BenchmarkPropertyNames {
    param($Object)
    if ($Object -is [Collections.IDictionary]) {
        return @($Object.Keys | ForEach-Object { "$_" })
    }
    return @($Object.PSObject.Properties | ForEach-Object { $_.Name })
}

function Test-BenchmarkJsonNumber {
    param($Value)
    if ($Value -isnot [byte] -and $Value -isnot [sbyte] -and
        $Value -isnot [int16] -and $Value -isnot [uint16] -and
        $Value -isnot [int32] -and $Value -isnot [uint32] -and
        $Value -isnot [int64] -and $Value -isnot [uint64] -and
        $Value -isnot [single] -and $Value -isnot [double] -and
        $Value -isnot [decimal]) {
        return $false
    }
    if ($Value -is [single] -or $Value -is [double]) {
        $number = [double]$Value
        if ([double]::IsNaN($number) -or [double]::IsInfinity($number)) {
            throw 'JSON numbers must be finite.'
        }
    }
    return $true
}

function Test-BenchmarkSchemaVersion {
    param($Value, [long]$Expected = 1)
    # JSON integers parse to different runtime types across PowerShell hosts
    # (Windows PowerShell 5.1: Int32/Decimal; PowerShell 7+: Int64/Double), so
    # acceptance must key on numeric type plus value, never on one CLR type.
    if ($null -eq $Value) { return $false }
    if ($Value -is [bool] -or $Value -is [string] -or $Value -is [char]) { return $false }
    if ($Value -is [uint64]) { return ($Value -eq [uint64]$Expected) }
    if ($Value -is [byte] -or $Value -is [sbyte] -or
        $Value -is [int16] -or $Value -is [uint16] -or
        $Value -is [int32] -or $Value -is [uint32] -or
        $Value -is [int64]) {
        return ([long]$Value -eq $Expected)
    }
    if ($Value -is [single] -or $Value -is [double]) {
        $number = [double]$Value
        if ([double]::IsNaN($number) -or [double]::IsInfinity($number)) { return $false }
        return ($number -eq [double]$Expected -and $number -eq [Math]::Floor($number))
    }
    if ($Value -is [decimal]) {
        $number = [decimal]$Value
        return ($number -eq [decimal]$Expected -and $number -eq [Math]::Floor($number))
    }
    return $false
}

function Assert-JsonDataShape {
    [CmdletBinding()]
    param(
        [AllowNull()]$Value,
        [string]$Path = '$'
    )
    if ($null -eq $Value -or $Value -is [string] -or $Value -is [bool]) { return }
    if (Test-BenchmarkJsonNumber -Value $Value) { return }
    if ($Value -is [pscustomobject]) {
        foreach ($property in $Value.PSObject.Properties) {
            Assert-JsonDataShape -Value $property.Value -Path ($Path + '.' + $property.Name)
        }
        return
    }
    if ($Value -is [Collections.IDictionary]) {
        foreach ($key in $Value.Keys) {
            if ($key -isnot [string]) { throw "JSON object key at '$Path' must be a string." }
            Assert-JsonDataShape -Value $Value[$key] -Path ($Path + '.' + $key)
        }
        return
    }
    if ($Value -is [array] -or $Value -is [Collections.IList]) {
        $index = 0
        foreach ($item in $Value) {
            Assert-JsonDataShape -Value $item -Path ($Path + '[' + $index + ']')
            $index++
        }
        return
    }
    throw "Value at '$Path' has non-JSON runtime type '$($Value.GetType().FullName)'."
}

function Assert-TopLevelProperties {
    param(
        [Parameter(Mandatory = $true)]$Value,
        [string[]]$RequiredProperties = @(),
        [string[]]$AllowedProperties = @(),
        [string]$DisplayName = 'JSON object'
    )
    $names = @(Get-BenchmarkPropertyNames -Object $Value)
    foreach ($name in $RequiredProperties) {
        if ($name -notin $names) { throw "$DisplayName is missing required property '$name'." }
    }
    if ($AllowedProperties.Count -gt 0) {
        foreach ($name in $names) {
            if ($name -notin $AllowedProperties) { throw "$DisplayName contains unexpected property '$name'." }
        }
    }
}

function ConvertFrom-PngInspectorInfo {
    param(
        [Parameter(Mandatory = $true)]$Info,
        [Parameter(Mandatory = $true)][string]$InfoPath,
        [switch]$IncludeQuality
    )
    $result = [ordered]@{
        path = [IO.Path]::GetFullPath($InfoPath)
        bytes = $Info.Bytes
        width = $Info.Width
        height = $Info.Height
        chunkCount = $Info.ChunkCount
        sha256 = Get-StrictFileSha256 -Path $InfoPath
        signature = '89504e470d0a1a0a'
    }
    if ($IncludeQuality) {
        $quality = $Info.Quality
        if ($null -eq $quality) { throw "PNG quality inspection returned no quality result: $InfoPath" }
        $result['quality'] = [pscustomobject][ordered]@{
            passed = [bool]$quality.Passed
            sampleCount = $quality.SampleCount
            lumaP02 = $quality.LumaP02
            lumaP98 = $quality.LumaP98
            lumaDynamicRange = $quality.LumaDynamicRange
            redDynamicRange = $quality.RedDynamicRange
            greenDynamicRange = $quality.GreenDynamicRange
            blueDynamicRange = $quality.BlueDynamicRange
            meanHorizontalLumaDelta = [Math]::Round([double]$quality.MeanHorizontalLumaDelta, 4)
            meanVerticalLumaDelta = [Math]::Round([double]$quality.MeanVerticalLumaDelta, 4)
            rowLumaStandardDeviation = [Math]::Round([double]$quality.RowLumaStandardDeviation, 4)
            monochrome = [bool]$quality.IsMonochrome
            lowDynamicRange = [bool]$quality.IsLowDynamicRange
            horizontalStriped = [bool]$quality.IsHorizontalStriped
            failureReasons = @($quality.FailureReasons)
        }
    }
    return [pscustomobject]$result
}

function Get-StrictPngInfo {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "PNG file not found: $Path" }
    $info = [Mca.Benchmark.PngInspector]::Inspect($Path)
    return ConvertFrom-PngInspectorInfo -Info $info -InfoPath $Path
}

function Get-PngQualityInfo {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "PNG file not found: $Path" }
    $info = [Mca.Benchmark.PngInspector]::InspectQuality($Path)
    return ConvertFrom-PngInspectorInfo -Info $info -InfoPath $Path -IncludeQuality
}

function Assert-PngQuality {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$Path)
    $png = Get-PngQualityInfo -Path $Path
    if (-not $png.quality.passed) {
        $reasons = @($png.quality.failureReasons) -join ', '
        throw (
            "PNG quality gate rejected '{0}': {1} (lumaP02={2}, lumaP98={3}, horizontalDelta={4}, verticalDelta={5})." -f `
            $png.path, $reasons, $png.quality.lumaP02, $png.quality.lumaP98, `
            $png.quality.meanHorizontalLumaDelta, $png.quality.meanVerticalLumaDelta
        )
    }
    return $png
}

function Assert-BenchmarkArtifact {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]$Value,
        [Parameter(Mandatory = $true)][string]$ExpectedArtifactType,
        [int]$ExpectedSchemaVersion = 1,
        [string[]]$RequiredProperties = @(),
        [string[]]$AllowedProperties = @()
    )
    if ($Value -isnot [pscustomobject] -and $Value -isnot [Collections.IDictionary]) {
        $actualType = if ($null -eq $Value) { 'null' } else { $Value.GetType().FullName }
        throw "Benchmark artifact '$ExpectedArtifactType' must be a JSON object, not $actualType."
    }
    Assert-JsonDataShape -Value $Value
    Assert-JsonStringIntegrity -Value $Value
    $required = @('schemaVersion', 'artifactType') + @($RequiredProperties)
    Assert-TopLevelProperties -Value $Value -RequiredProperties $required -AllowedProperties $AllowedProperties -DisplayName "Benchmark artifact '$ExpectedArtifactType'"
    $schemaValue = Get-BenchmarkPropertyValue -Object $Value -Name 'schemaVersion'
    $typeValue = Get-BenchmarkPropertyValue -Object $Value -Name 'artifactType'
    if ($schemaValue -ne $ExpectedSchemaVersion) {
        throw "Benchmark artifact '$ExpectedArtifactType' requires schemaVersion=$ExpectedSchemaVersion."
    }
    if ("$typeValue" -cne $ExpectedArtifactType) {
        throw "Benchmark artifact type mismatch. Expected '$ExpectedArtifactType'."
    }
}

function ConvertTo-ValidatedBenchmarkJson {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]$Value,
        [Parameter(Mandatory = $true)][string]$ExpectedArtifactType,
        [int]$Depth = 40,
        [string[]]$RequiredProperties = @(),
        [string[]]$AllowedProperties = @(),
        [int]$ExpectedSchemaVersion = 1
    )
    Assert-BenchmarkArtifact -Value $Value -ExpectedArtifactType $ExpectedArtifactType -ExpectedSchemaVersion $ExpectedSchemaVersion -RequiredProperties $RequiredProperties -AllowedProperties $AllowedProperties
    $json = $Value | ConvertTo-Json -Depth $Depth -Compress
    [Mca.Benchmark.StrictJsonValidator]::Validate($json)
    $roundTrip = $json | ConvertFrom-Json -ErrorAction Stop
    Assert-BenchmarkArtifact -Value $roundTrip -ExpectedArtifactType $ExpectedArtifactType -ExpectedSchemaVersion $ExpectedSchemaVersion -RequiredProperties $RequiredProperties -AllowedProperties $AllowedProperties
    return $json
}

function Write-ValidatedBenchmarkJson {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)]$Value,
        [Parameter(Mandatory = $true)][string]$ExpectedArtifactType,
        [int]$Depth = 40,
        [string[]]$RequiredProperties = @(),
        [string[]]$AllowedProperties = @(),
        [int]$ExpectedSchemaVersion = 1
    )
    $json = ConvertTo-ValidatedBenchmarkJson -Value $Value -ExpectedArtifactType $ExpectedArtifactType -Depth $Depth -RequiredProperties $RequiredProperties -AllowedProperties $AllowedProperties -ExpectedSchemaVersion $ExpectedSchemaVersion
    $fullPath = [IO.Path]::GetFullPath($Path)
    $parent = Split-Path -Parent $fullPath
    if ([string]::IsNullOrWhiteSpace($parent)) { $parent = [Environment]::CurrentDirectory }
    [void](New-Item -ItemType Directory -Force -Path $parent)
    $tempPath = Join-Path $parent ('.' + [IO.Path]::GetFileName($fullPath) + '.' + [guid]::NewGuid().ToString('N') + '.tmp')
    $backupPath = Join-Path $parent ('.' + [IO.Path]::GetFileName($fullPath) + '.' + [guid]::NewGuid().ToString('N') + '.bak')
    try {
        [IO.File]::WriteAllText($tempPath, $json, (New-Object Text.UTF8Encoding($false, $true)))
        $tempReadBack = Read-StrictUtf8JsonFile -Path $tempPath
        Assert-BenchmarkArtifact -Value $tempReadBack.value -ExpectedArtifactType $ExpectedArtifactType -ExpectedSchemaVersion $ExpectedSchemaVersion -RequiredProperties $RequiredProperties -AllowedProperties $AllowedProperties
        if (Test-Path -LiteralPath $fullPath -PathType Leaf) {
            # Windows PowerShell's .NET File.Replace requires a concrete backup
            # path. Passing null works on neither the desktop framework nor all
            # supported Android-host toolchains and aborts the benchmark halfway
            # through when an existing run artifact is rewritten with aggregates.
            [IO.File]::Replace($tempPath, $fullPath, $backupPath)
        } else {
            [IO.File]::Move($tempPath, $fullPath)
        }
        $readBack = Read-StrictUtf8JsonFile -Path $fullPath
        Assert-BenchmarkArtifact -Value $readBack.value -ExpectedArtifactType $ExpectedArtifactType -ExpectedSchemaVersion $ExpectedSchemaVersion -RequiredProperties $RequiredProperties -AllowedProperties $AllowedProperties
    } finally {
        if (Test-Path -LiteralPath $tempPath -PathType Leaf) {
            Remove-Item -LiteralPath $tempPath -Force -ErrorAction SilentlyContinue
        }
        if (Test-Path -LiteralPath $backupPath -PathType Leaf) {
            Remove-Item -LiteralPath $backupPath -Force -ErrorAction SilentlyContinue
        }
    }
}

function Assert-SmokeResultDocument {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]$Value,
        [Parameter(Mandatory = $true)][string]$ExpectedRunId,
        [switch]$RequireTerminal
    )
    if ($Value -isnot [pscustomobject] -and $Value -isnot [Collections.IDictionary]) {
        throw 'Smoke result root must be a JSON object.'
    }
    Assert-JsonDataShape -Value $Value
    Assert-JsonStringIntegrity -Value $Value
    Assert-TopLevelProperties -Value $Value -RequiredProperties @('runId', 'status', 'events') -DisplayName 'Smoke result'
    $runIdValue = Get-BenchmarkPropertyValue -Object $Value -Name 'runId'
    if ($runIdValue -isnot [string] -or [string]::IsNullOrWhiteSpace($runIdValue)) {
        throw 'Smoke result runId must be a non-empty string.'
    }
    $runId = $runIdValue
    if ($runId -cne $ExpectedRunId) { throw "Smoke result runId mismatch. Expected '$ExpectedRunId', got '$runId'." }
    $statusValue = Get-BenchmarkPropertyValue -Object $Value -Name 'status'
    if ($statusValue -isnot [string] -or [string]::IsNullOrWhiteSpace($statusValue)) {
        throw 'Smoke result status must be a non-empty string.'
    }
    $status = $statusValue
    $eventValue = Get-BenchmarkPropertyValue -Object $Value -Name 'events'
    if ($eventValue -isnot [array] -and $eventValue -isnot [Collections.IList]) {
        throw 'Smoke result events must be a JSON array.'
    }
    $events = @($eventValue)
    if ($events.Count -eq 0) { throw 'Smoke result events must not be empty.' }
    $previousElapsed = -1.0
    for ($index = 0; $index -lt $events.Count; $index++) {
        $event = $events[$index]
        if ($event -isnot [pscustomobject] -and $event -isnot [Collections.IDictionary]) {
            throw "Smoke event [$index] must be a JSON object."
        }
        Assert-TopLevelProperties -Value $event -RequiredProperties @('runId', 'status', 'elapsedMs') -DisplayName "Smoke event [$index]"
        $eventRunIdValue = Get-BenchmarkPropertyValue -Object $event -Name 'runId'
        if ($eventRunIdValue -isnot [string] -or [string]::IsNullOrWhiteSpace($eventRunIdValue)) {
            throw "Smoke event [$index] runId must be a non-empty string."
        }
        $eventRunId = $eventRunIdValue
        if ($eventRunId -cne $ExpectedRunId) { throw "Smoke event [$index] runId mismatch." }
        $eventStatusValue = Get-BenchmarkPropertyValue -Object $event -Name 'status'
        if ($eventStatusValue -isnot [string] -or [string]::IsNullOrWhiteSpace($eventStatusValue)) {
            throw "Smoke event [$index] status must be a non-empty string."
        }
        $elapsed = Get-BenchmarkPropertyValue -Object $event -Name 'elapsedMs'
        if (-not (Test-BenchmarkJsonNumber -Value $elapsed) -or [double]$elapsed -lt 0) {
            throw "Smoke event [$index] elapsedMs must be a finite non-negative number."
        }
        if ([double]$elapsed -lt $previousElapsed) {
            throw "Smoke event [$index] elapsedMs must not decrease."
        }
        $previousElapsed = [double]$elapsed
    }
    $lastStatus = "$(Get-BenchmarkPropertyValue -Object $events[$events.Count - 1] -Name 'status')"
    if ($lastStatus -cne $status) { throw "Smoke result status '$status' does not match final event status '$lastStatus'." }
    $isTerminal = $status -in @('completed', 'failed')
    if ($RequireTerminal -and -not $isTerminal) { throw "Smoke result is not terminal: '$status'." }
    return [pscustomobject][ordered]@{
        runId = $runId
        status = $status
        isTerminal = $isTerminal
        eventCount = $events.Count
    }
}

Export-ModuleMember -Function Get-StrictFileSha256, Read-StrictUtf8JsonFile, Assert-JsonStringIntegrity, Get-JsonStringIntegrityIssues, Test-LikelyMojibake, Assert-JsonDataShape, Get-StrictPngInfo, Get-PngQualityInfo, Assert-PngQuality, Assert-SmokeResultDocument, Assert-BenchmarkArtifact, ConvertTo-ValidatedBenchmarkJson, Write-ValidatedBenchmarkJson, Test-BenchmarkSchemaVersion, Test-BenchmarkJsonNumber, ConvertFrom-StrictJsonText
