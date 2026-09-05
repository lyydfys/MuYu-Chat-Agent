[CmdletBinding()]
param(
    [string]$RunnerPath = "",
    [string]$FixtureRoot = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RunnerPath)) {
    $RunnerPath = Join-Path $PSScriptRoot "run-real-device-comparison.ps1"
}
if ([string]::IsNullOrWhiteSpace($FixtureRoot)) {
    $FixtureRoot = Join-Path $PSScriptRoot "fixtures\real-device-comparison-offline"
}

Import-Module (Join-Path $PSScriptRoot "benchmark-validation.psm1") -Force

function Assert-OfflineTestEqual {
    param($Actual, $Expected, [string]$Description)
    if ("$Actual" -cne "$Expected") {
        throw "$Description expected '$Expected', got '$Actual'."
    }
}

$resolvedRunnerPath = [IO.Path]::GetFullPath($RunnerPath)
$resolvedFixtureRoot = [IO.Path]::GetFullPath($FixtureRoot)
if (-not (Test-Path -LiteralPath $resolvedRunnerPath -PathType Leaf)) {
    throw "Runner not found: $resolvedRunnerPath"
}
if (-not (Test-Path -LiteralPath $resolvedFixtureRoot -PathType Container)) {
    throw "Fixture root not found: $resolvedFixtureRoot"
}

$unusedAdbPath = Join-Path $resolvedFixtureRoot "adb-must-not-be-invoked.exe"
$offlineOutput = & $resolvedRunnerPath -OfflineValidation -FixtureRoot $resolvedFixtureRoot -Adb $unusedAdbPath
$offlineSummary = ($offlineOutput | Out-String) | ConvertFrom-Json -ErrorAction Stop
Assert-OfflineTestEqual -Actual $offlineSummary.artifactType -Expected "benchmark_offline_validation" -Description "Offline artifact type"
Assert-OfflineTestEqual -Actual $offlineSummary.status -Expected "passed" -Description "Offline validation status"
Assert-OfflineTestEqual -Actual $offlineSummary.testCount -Expected 5 -Description "Offline validation test count"
$offlineTestIds = @($offlineSummary.tests | ForEach-Object { $_.id })
foreach ($expectedId in @("fixture_manifest_schema", "config_schema", "chat_terminal_metrics", "image_terminal_metrics", "failure_diagnostics")) {
    if ($expectedId -notin $offlineTestIds) {
        throw "Offline validation summary is missing test '$expectedId'."
    }
}

$configPath = Join-Path $resolvedFixtureRoot "config.json"
$configOutput = & $resolvedRunnerPath -ValidateConfigOnly -ConfigPath $configPath -Adb $unusedAdbPath
$configSummary = ($configOutput | Out-String) | ConvertFrom-Json -ErrorAction Stop
Assert-OfflineTestEqual -Actual $configSummary.artifactType -Expected "benchmark_config_validation" -Description "Config validation artifact type"
Assert-OfflineTestEqual -Actual $configSummary.valid -Expected $true -Description "Config validation status"
Assert-OfflineTestEqual -Actual $configSummary.configSchemaVersion -Expected 1 -Description "Config schema version"
Assert-OfflineTestEqual -Actual $configSummary.configArtifactType -Expected "benchmark_config" -Description "Config artifact type"
Assert-OfflineTestEqual -Actual $configSummary.caseCount -Expected 2 -Description "Config case count"
Assert-OfflineTestEqual -Actual $configSummary.definitionOnlyCaseCount -Expected 0 -Description "Definition-only case count"
Assert-OfflineTestEqual -Actual $configSummary.unresolvedPlaceholderCount -Expected 0 -Description "Unresolved placeholder count"
Assert-OfflineTestEqual -Actual $configSummary.executionReady -Expected $true -Description "Execution readiness"
if ("$($configSummary.configSha256)" -notmatch '^[0-9a-f]{64}$') {
    throw "Config validation summary did not contain a SHA-256 digest."
}
foreach ($case in @($configSummary.cases)) {
    Assert-OfflineTestEqual -Actual $case.runs -Expected 3 -Description "Measured run count for $($case.id)"
    Assert-OfflineTestEqual -Actual $case.warmupRuns -Expected 1 -Description "Warmup run count for $($case.id)"
    Assert-OfflineTestEqual -Actual $case.expectedTotalRuns -Expected 4 -Description "Total planned run count for $($case.id)"
    $plan = @($case.executionPlan)
    Assert-OfflineTestEqual -Actual $plan.Count -Expected 4 -Description "Execution plan item count for $($case.id)"
    Assert-OfflineTestEqual -Actual $plan[0].phase -Expected "warmup" -Description "First phase for $($case.id)"
    Assert-OfflineTestEqual -Actual $plan[0].phaseRunIndex -Expected 1 -Description "Warmup phase index for $($case.id)"
    Assert-OfflineTestEqual -Actual $plan[0].aggregateEligible -Expected $false -Description "Warmup aggregate eligibility for $($case.id)"
    for ($index = 1; $index -lt $plan.Count; $index++) {
        Assert-OfflineTestEqual -Actual $plan[$index].phase -Expected "measured" -Description "Measured phase for $($case.id) plan index $index"
        Assert-OfflineTestEqual -Actual $plan[$index].phaseRunIndex -Expected $index -Description "Measured phase index for $($case.id) plan index $index"
        Assert-OfflineTestEqual -Actual $plan[$index].aggregateEligible -Expected $true -Description "Measured aggregate eligibility for $($case.id) plan index $index"
    }
}

$flagshipExamplePath = Join-Path $PSScriptRoot "flagship-quality-cases.example.json"
$flagshipOutput = & $resolvedRunnerPath -ValidateConfigOnly -ConfigPath $flagshipExamplePath -Adb $unusedAdbPath
$flagshipSummary = ($flagshipOutput | Out-String) | ConvertFrom-Json -ErrorAction Stop
Assert-OfflineTestEqual -Actual $flagshipSummary.valid -Expected $true -Description "Flagship example schema validity"
Assert-OfflineTestEqual -Actual $flagshipSummary.caseCount -Expected 16 -Description "Flagship example case count"
Assert-OfflineTestEqual -Actual $flagshipSummary.definitionOnlyCaseCount -Expected 16 -Description "Flagship definition-only case count"
Assert-OfflineTestEqual -Actual $flagshipSummary.executionReady -Expected $false -Description "Flagship example execution readiness"
if ([int]$flagshipSummary.unresolvedPlaceholderCount -le 0) {
    throw "Flagship example should expose unresolved placeholders before evidence is frozen."
}

$executionGuardMessage = ""
try {
    & $resolvedRunnerPath -ConfigPath $flagshipExamplePath -Adb $unusedAdbPath | Out-Null
    throw "Flagship definition-only config unexpectedly reached device execution."
} catch {
    $executionGuardMessage = $_.Exception.Message
}
if ($executionGuardMessage -notmatch 'definition-only or contains unresolved placeholders') {
    throw "Flagship execution guard returned an unexpected error: $executionGuardMessage"
}

function Assert-SchemaVersionAcceptedValue {
    param($Value, [string]$Description)
    if (-not (Test-BenchmarkSchemaVersion -Value $Value)) {
        throw "Test-BenchmarkSchemaVersion should accept $Description."
    }
}

function Assert-SchemaVersionRejectedValue {
    param($Value, [string]$Description)
    if (Test-BenchmarkSchemaVersion -Value $Value) {
        throw "Test-BenchmarkSchemaVersion should reject $Description."
    }
}

# The same JSON literal parses to different runtime types across PowerShell
# versions (Windows PowerShell 5.1: Int32/Decimal, PowerShell 7+: Int64/Double),
# so schemaVersion handling is asserted per runtime type, not per JSON literal.
Assert-SchemaVersionAcceptedValue -Value ([byte]1) -Description "[byte]1"
Assert-SchemaVersionAcceptedValue -Value ([sbyte]1) -Description "[sbyte]1"
Assert-SchemaVersionAcceptedValue -Value ([int16]1) -Description "[int16]1"
Assert-SchemaVersionAcceptedValue -Value ([uint16]1) -Description "[uint16]1"
Assert-SchemaVersionAcceptedValue -Value ([int]1) -Description "[int]1"
Assert-SchemaVersionAcceptedValue -Value ([uint32]1) -Description "[uint32]1"
Assert-SchemaVersionAcceptedValue -Value ([long]1) -Description "[long]1"
Assert-SchemaVersionAcceptedValue -Value ([uint64]1) -Description "[uint64]1"
Assert-SchemaVersionAcceptedValue -Value ([double]1.0) -Description "[double]1.0"
Assert-SchemaVersionAcceptedValue -Value ([single]1.0) -Description "[single]1.0"
Assert-SchemaVersionAcceptedValue -Value ([decimal]1.0) -Description "[decimal]1.0"

Assert-SchemaVersionRejectedValue -Value "1" -Description 'string "1"'
Assert-SchemaVersionRejectedValue -Value ([char]'1') -Description "[char]'1'"
Assert-SchemaVersionRejectedValue -Value $true -Description "boolean true"
Assert-SchemaVersionRejectedValue -Value $false -Description "boolean false"
Assert-SchemaVersionRejectedValue -Value ([double]1.5) -Description "[double]1.5"
Assert-SchemaVersionRejectedValue -Value ([single]1.5) -Description "[single]1.5"
Assert-SchemaVersionRejectedValue -Value ([decimal]1.5) -Description "[decimal]1.5"
# The [decimal] cast of this literal is host-dependent: PowerShell 7 parses it
# via double and rounds to exactly 1, which the validator must then accept as 1.
# Construct it through decimal.Parse so both hosts see the same fractional value.
Assert-SchemaVersionRejectedValue -Value ([decimal]::Parse('1.0000000000000000000000001', [Globalization.CultureInfo]::InvariantCulture)) -Description "decimal 1.0000000000000000000000001"
Assert-SchemaVersionRejectedValue -Value ([int]0) -Description "[int]0"
Assert-SchemaVersionRejectedValue -Value ([long]0) -Description "[long]0"
Assert-SchemaVersionRejectedValue -Value ([int]2) -Description "[int]2"
Assert-SchemaVersionRejectedValue -Value ([long]2) -Description "[long]2"
Assert-SchemaVersionRejectedValue -Value ([double]2.0) -Description "[double]2.0"
Assert-SchemaVersionRejectedValue -Value ([double]0.5) -Description "[double]0.5"
Assert-SchemaVersionRejectedValue -Value ([double]-1.0) -Description "[double]-1.0"
Assert-SchemaVersionRejectedValue -Value $null -Description "null"
Assert-SchemaVersionRejectedValue -Value ([double]::NaN) -Description "[double]::NaN"
Assert-SchemaVersionRejectedValue -Value ([double]::PositiveInfinity) -Description "[double]::PositiveInfinity"

$variantRoot = Join-Path ([IO.Path]::GetTempPath()) ("mca-benchmark-schema-version-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $variantRoot | Out-Null
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
try {
    $baseConfigText = [IO.File]::ReadAllText($configPath)
    $acceptVariants = @(
        [pscustomobject]@{ id = "integer-one"; replacement = '"schemaVersion": 1' },
        [pscustomobject]@{ id = "numeric-one-zero"; replacement = '"schemaVersion": 1.0' }
    )
    foreach ($variant in $acceptVariants) {
        $variantText = $baseConfigText.Replace('"schemaVersion": 1', $variant.replacement)
        if ($variantText -eq $baseConfigText -and $variant.replacement -ne '"schemaVersion": 1') {
            throw "Failed to inject schemaVersion literal '$($variant.replacement)' into fixture config."
        }
        $variantPath = Join-Path $variantRoot ("accept-" + $variant.id + ".json")
        [IO.File]::WriteAllText($variantPath, $variantText, $utf8NoBom)
        $variantOutput = & $resolvedRunnerPath -ValidateConfigOnly -ConfigPath $variantPath -Adb $unusedAdbPath
        $variantSummary = ($variantOutput | Out-String) | ConvertFrom-Json -ErrorAction Stop
        Assert-OfflineTestEqual -Actual $variantSummary.valid -Expected $true -Description "Config validity for schemaVersion literal $($variant.replacement)"
        if ([double]$variantSummary.configSchemaVersion -ne 1.0) {
            throw "Config schemaVersion for literal '$($variant.replacement)' expected numeric 1, got '$($variantSummary.configSchemaVersion)'."
        }
        Assert-OfflineTestEqual -Actual $variantSummary.caseCount -Expected 2 -Description "Config case count for schemaVersion literal $($variant.replacement)"
    }

    $rejectVariants = @(
        [pscustomobject]@{ id = "string-one"; replacement = '"schemaVersion": "1"' },
        [pscustomobject]@{ id = "boolean-true"; replacement = '"schemaVersion": true' },
        [pscustomobject]@{ id = "numeric-one-point-five"; replacement = '"schemaVersion": 1.5' },
        [pscustomobject]@{ id = "integer-zero"; replacement = '"schemaVersion": 0' },
        [pscustomobject]@{ id = "integer-two"; replacement = '"schemaVersion": 2' },
        [pscustomobject]@{ id = "json-null"; replacement = '"schemaVersion": null' },
        [pscustomobject]@{ id = "missing-key"; replacement = $null }
    )
    foreach ($variant in $rejectVariants) {
        if ($null -eq $variant.replacement) {
            $variantText = $baseConfigText.Replace('"schemaVersion": 1,', '')
            if ($variantText -eq $baseConfigText) {
                throw "Failed to remove the schemaVersion key from the fixture config."
            }
        } else {
            $variantText = $baseConfigText.Replace('"schemaVersion": 1', $variant.replacement)
            if ($variantText -eq $baseConfigText) {
                throw "Failed to inject schemaVersion literal '$($variant.replacement)' into fixture config."
            }
        }
        $variantPath = Join-Path $variantRoot ("reject-" + $variant.id + ".json")
        [IO.File]::WriteAllText($variantPath, $variantText, $utf8NoBom)
        $rejectMessage = ""
        try {
            & $resolvedRunnerPath -ValidateConfigOnly -ConfigPath $variantPath -Adb $unusedAdbPath | Out-Null
        } catch {
            $rejectMessage = $_.Exception.Message
        }
        if ($rejectMessage -notmatch 'schemaVersion must be integer 1') {
            throw "schemaVersion case '$($variant.id)' should be rejected by config validation, but got: $rejectMessage"
        }
    }
} finally {
    Remove-Item -LiteralPath $variantRoot -Recurse -Force -ErrorAction SilentlyContinue
}

# PowerShell 7's ConvertFrom-Json coerces ISO-8601-looking strings into
# System.DateTime, while Windows PowerShell 5.1 leaves them as strings. Benchmark
# artifacts must parse identically on both hosts, otherwise Assert-JsonDataShape
# rejects a valid timestamp string as a non-JSON runtime type.
$timestampRoot = Join-Path ([IO.Path]::GetTempPath()) ("mca-benchmark-timestamp-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $timestampRoot | Out-Null
try {
    $timestampPath = Join-Path $timestampRoot "timestamps.json"
    $timestampText = '{"capturedAt":"2026-07-11T00:00:00.0000000+00:00",' +
        '"localTime":"2026-07-11T00:00:00","dateOnly":"2026-07-11",' +
        '"nested":{"endedAt":"2026-07-11T00:00:01.5000000Z"},' +
        '"list":["2026-07-11T00:00:02.0000000+08:00"],' +
        '"plain":"not-a-timestamp","count":1}'
    [IO.File]::WriteAllText($timestampPath, $timestampText, $utf8NoBom)
    $timestampDocument = Read-StrictUtf8JsonFile -Path $timestampPath

    foreach ($field in @("capturedAt", "localTime", "dateOnly", "plain")) {
        $actual = $timestampDocument.value.$field
        if ($actual -isnot [string]) {
            $actualType = if ($null -eq $actual) { "null" } else { $actual.GetType().FullName }
            throw "Parsed JSON field '$field' must stay a string, got '$actualType'."
        }
    }
    if ($timestampDocument.value.nested.endedAt -isnot [string]) {
        throw "Parsed nested JSON timestamp must stay a string, got '$($timestampDocument.value.nested.endedAt.GetType().FullName)'."
    }
    if (@($timestampDocument.value.list)[0] -isnot [string]) {
        throw "Parsed JSON array timestamp must stay a string, got '$(@($timestampDocument.value.list)[0].GetType().FullName)'."
    }
    Assert-OfflineTestEqual -Actual $timestampDocument.value.capturedAt -Expected "2026-07-11T00:00:00.0000000+00:00" -Description "Round-tripped timestamp text"
    if (-not (Test-BenchmarkJsonNumber -Value $timestampDocument.value.count)) {
        throw "Parsed JSON number must remain numeric alongside timestamp preservation."
    }
    Assert-JsonDataShape -Value $timestampDocument.value
} finally {
    Remove-Item -LiteralPath $timestampRoot -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host "Offline real-device comparison self-test passed (fixtures, run planning, flagship schema, execution guard, schemaVersion type matrix, and timestamp preservation)."
