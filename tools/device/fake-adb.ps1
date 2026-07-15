param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$fixturePath = Join-Path $PSScriptRoot "tests\fixtures\fake-adb.ps1"
if (-not (Test-Path -LiteralPath $fixturePath -PathType Leaf)) {
    throw "Fake adb fixture is missing: $fixturePath"
}

$previousRoot = $env:QAIRT_VLM_FAKE_ADB_ROOT
$previousScenario = $env:QAIRT_VLM_FAKE_ADB_SCENARIO
try {
    if (-not [string]::IsNullOrWhiteSpace($env:FAKE_ADB_ROOT)) {
        $env:QAIRT_VLM_FAKE_ADB_ROOT = $env:FAKE_ADB_ROOT
    }

    if (-not [string]::IsNullOrWhiteSpace($env:FAKE_ADB_SCENARIO)) {
        $env:QAIRT_VLM_FAKE_ADB_SCENARIO = switch ($env:FAKE_ADB_SCENARIO) {
            "success" { "terminal_completed" }
            "invalid_utf8_completed" { "garbled_json_completed" }
            default { $env:FAKE_ADB_SCENARIO }
        }
    }

    $arguments = @($args)
    & $fixturePath @arguments
} finally {
    if ($null -eq $previousRoot) {
        Remove-Item Env:QAIRT_VLM_FAKE_ADB_ROOT -ErrorAction SilentlyContinue
    } else {
        $env:QAIRT_VLM_FAKE_ADB_ROOT = $previousRoot
    }
    if ($null -eq $previousScenario) {
        Remove-Item Env:QAIRT_VLM_FAKE_ADB_SCENARIO -ErrorAction SilentlyContinue
    } else {
        $env:QAIRT_VLM_FAKE_ADB_SCENARIO = $previousScenario
    }
}