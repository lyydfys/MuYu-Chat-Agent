param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$smokeScript = Join-Path $scriptDir "run-qairt-vlm-smoke.ps1"
$fakeAdb = Join-Path $scriptDir "fake-adb.ps1"
$powerShellExe = (Get-Command powershell.exe -ErrorAction Stop).Source

function Assert-True {
    param(
        [bool]$Condition,
        [string]$Message
    )
    if (-not $Condition) {
        throw $Message
    }
}

function Assert-Equal {
    param(
        $Actual,
        $Expected,
        [string]$Message
    )
    if ($Actual -ne $Expected) {
        throw "$Message (expected=$Expected actual=$Actual)"
    }
}

function Assert-NoParseErrors {
    param([string]$Path)
    $tokens = $null
    $errors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile($Path, [ref]$tokens, [ref]$errors)
    Assert-True ($errors.Count -eq 0) ("AST parse failed for " + $Path + ": " + (($errors | ForEach-Object { $_.Message }) -join "; "))
}

function New-CaseRoot {
    $name = "qairt-vlm-smoke-test-" + [Guid]::NewGuid().ToString("N")
    $root = Join-Path ([IO.Path]::GetTempPath()) $name
    New-Item -ItemType Directory -Force -Path $root | Out-Null
    return $root
}

function Get-SessionSummary {
    param([string]$OutDir)
    $summary = Get-ChildItem -LiteralPath $OutDir -Recurse -Filter summary.json | Select-Object -First 1
    Assert-True ($null -ne $summary) ("summary.json missing under " + $OutDir)
    return Get-Content -LiteralPath $summary.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
}

function Get-State {
    param([string]$FakeRoot)
    $statePath = Join-Path $FakeRoot "state.json"
    Assert-True (Test-Path -LiteralPath $statePath -PathType Leaf) ("fake adb state missing: " + $statePath)
    return Get-Content -LiteralPath $statePath -Raw -Encoding UTF8 | ConvertFrom-Json
}

function Invoke-SmokeCase {
    param(
        [string]$Scenario,
        [string]$Lifecycle,
        [string]$SmokeMode,
        [int]$Runs,
        [bool]$ExpectSuccess
    )

    $caseRoot = New-CaseRoot
    $fakeRoot = Join-Path $caseRoot "fake-device"
    $outDir = Join-Path $caseRoot "out"
    $imagePath = Join-Path $caseRoot "input.jpg"
    [IO.File]::WriteAllBytes($imagePath, [byte[]](1, 2, 3, 4))
    New-Item -ItemType Directory -Force -Path $fakeRoot | Out-Null
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null

    $env:FAKE_ADB_ROOT = $fakeRoot
    $env:FAKE_ADB_SCENARIO = $Scenario

    $arguments = @(
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", $smokeScript,
        "-Adb", $fakeAdb,
        "-ImagePath", $imagePath,
        "-OutDir", $outDir,
        "-Runs", [string]$Runs,
        "-Lifecycle", $Lifecycle,
        "-SmokeMode", $SmokeMode,
        "-PollMilliseconds", "100",
        "-TimeoutSeconds", "5"
    )
    $output = @()
    $exitCode = $null
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = @(& $powerShellExe @arguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }

    if ($ExpectSuccess) {
        Assert-Equal $exitCode 0 ("Smoke case failed unexpectedly: scenario=$Scenario lifecycle=$Lifecycle smokeMode=$SmokeMode output=" + (($output | ForEach-Object { $_.ToString() }) -join " | "))
    } else {
        Assert-True ($exitCode -ne 0) ("Smoke case unexpectedly succeeded: scenario=$Scenario lifecycle=$Lifecycle smokeMode=$SmokeMode")
    }

    return [pscustomobject]@{
        CaseRoot = $caseRoot
        FakeRoot = $fakeRoot
        OutDir = $outDir
        Output = @($output | ForEach-Object { $_.ToString() })
        ExitCode = [int]$exitCode
        Summary = Get-SessionSummary -OutDir $outDir
        State = Get-State -FakeRoot $fakeRoot
    }
}

Assert-NoParseErrors -Path $smokeScript
Assert-NoParseErrors -Path $fakeAdb
Assert-NoParseErrors -Path $MyInvocation.MyCommand.Path

$coldCase = Invoke-SmokeCase -Scenario "success" -Lifecycle "cold" -SmokeMode "direct_twice" -Runs 2 -ExpectSuccess $true
Assert-Equal $coldCase.State.forceStopCount 2 "cold lifecycle should force-stop once per run"
Assert-Equal $coldCase.Summary.lifecycle "cold" "summary should record cold lifecycle"
Assert-Equal $coldCase.Summary.smokeMode "direct_twice" "summary should record direct_twice"
Assert-True (@($coldCase.Summary.runs | Where-Object { $_.status -eq "completed" }).Count -eq 2) "cold direct_twice runs should complete"

$reuseCase = Invoke-SmokeCase -Scenario "success" -Lifecycle "reuse" -SmokeMode "api_twice" -Runs 2 -ExpectSuccess $true
Assert-Equal $reuseCase.State.forceStopCount 0 "reuse lifecycle should not force-stop"
Assert-Equal $reuseCase.Summary.lifecycle "reuse" "summary should record reuse lifecycle"
Assert-Equal $reuseCase.Summary.smokeMode "api_twice" "summary should record api_twice"
Assert-True (@($reuseCase.Summary.runs | Where-Object { $_.status -eq "completed" }).Count -eq 2) "reuse api_twice runs should complete"

$exitCase = Invoke-SmokeCase -Scenario "process_exit_after_result" -Lifecycle "reuse" -SmokeMode "api_twice" -Runs 1 -ExpectSuccess $false
$exitRun = $exitCase.Summary.runs[0]
Assert-Equal $exitRun.status "process_exited" "process exit should be surfaced immediately"
Assert-True ($exitRun.rawResultPreserved) "process exit case should still preserve raw result"
Assert-True (-not [string]::IsNullOrWhiteSpace($exitRun.processEvidence)) "process exit case should save evidence path"
Assert-True (Test-Path -LiteralPath $exitRun.processEvidence -PathType Leaf) "process evidence file missing"
Assert-True (Test-Path -LiteralPath $exitRun.localJson -PathType Leaf) "process exit local json missing"

$utf8Case = Invoke-SmokeCase -Scenario "invalid_utf8_completed" -Lifecycle "cold" -SmokeMode "direct_twice" -Runs 1 -ExpectSuccess $false
$utf8Run = $utf8Case.Summary.runs[0]
Assert-Equal $utf8Run.status "invalid_result" "invalid UTF-8 should terminate as invalid_result"
Assert-Equal $utf8Run.failureKind "invalid_utf8" "invalid UTF-8 should retain its failure kind"
Assert-True ($utf8Run.rawResultPreserved) "invalid UTF-8 case should preserve raw result"
Assert-True (-not [string]::IsNullOrWhiteSpace($utf8Run.parseError)) "invalid UTF-8 case should report a parse error"
Assert-True ($utf8Run.parseError -match "invalid_utf8") "invalid UTF-8 parse error should be explicit"
Assert-True (Test-Path -LiteralPath $utf8Run.localJson -PathType Leaf) "invalid UTF-8 local json missing"
$bytes = [IO.File]::ReadAllBytes($utf8Run.localJson)
Assert-True ($bytes -contains 0xC3) "invalid UTF-8 evidence byte missing from pulled json"

Write-Host ("Validated with " + $powerShellExe + " on PowerShell " + $PSVersionTable.PSVersion)
Write-Host "PASS: legacy offline smoke tests, including strict invalid UTF-8 classification and raw preservation"