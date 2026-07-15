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

Write-Host "Offline real-device comparison self-test passed (fixtures, run planning, flagship schema, and execution guard)."
