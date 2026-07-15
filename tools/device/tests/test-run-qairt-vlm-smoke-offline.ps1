param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$script:TestDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$script:TestScript = $MyInvocation.MyCommand.Path
$script:DeviceDir = Split-Path -Parent $script:TestDir
$script:SmokeScript = Join-Path $script:DeviceDir "run-qairt-vlm-smoke.ps1"
$script:FakeAdb = Join-Path $script:TestDir "fixtures\fake-adb.ps1"
$script:LegacyFakeAdb = Join-Path $script:DeviceDir "fake-adb.ps1"
$script:PowerShellExe = (Get-Command powershell.exe -ErrorAction Stop).Source
$script:CaseRoots = New-Object System.Collections.ArrayList
$script:Failures = New-Object System.Collections.ArrayList
$script:Passed = 0

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

function Assert-ContainsText {
    param(
        [AllowNull()][string]$Actual,
        [string]$ExpectedFragment,
        [string]$Message
    )

    if ($null -eq $Actual -or $Actual.IndexOf($ExpectedFragment, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
        throw "$Message (fragment=$ExpectedFragment actual=$Actual)"
    }
}

function Assert-NoParseErrors {
    param([string]$Path)

    $tokens = $null
    $errors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile(
        $Path,
        [ref]$tokens,
        [ref]$errors
    )
    if ($errors.Count -gt 0) {
        $messages = @($errors | ForEach-Object { $_.Message }) -join "; "
        throw "PowerShell parser rejected ${Path}: $messages"
    }
}

function Read-JsonFile {
    param([string]$Path)

    Assert-True -Condition (Test-Path -LiteralPath $Path -PathType Leaf) -Message "JSON file missing: $Path"
    return Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json
}

function Get-SessionSummary {
    param([string]$OutDir)

    $summaryFiles = @(Get-ChildItem -LiteralPath $OutDir -Recurse -Filter "summary.json" -File)
    Assert-Equal -Actual $summaryFiles.Count -Expected 1 -Message "Expected exactly one session summary under $OutDir"
    return Read-JsonFile -Path $summaryFiles[0].FullName
}

function Get-RemoteMirrorPath {
    param(
        [string]$FakeRoot,
        [string]$RemotePath
    )

    $path = $FakeRoot
    foreach ($segment in @($RemotePath.TrimStart('/') -split '/')) {
        $path = Join-Path $path $segment
    }
    return $path
}

function New-CaseRoot {
    param([string]$Scenario)

    $safeScenario = $Scenario -replace '[^A-Za-z0-9._-]', '-'
    $name = "qairt-vlm-offline-$safeScenario-$([Guid]::NewGuid().ToString('N'))"
    $root = Join-Path ([IO.Path]::GetTempPath()) $name
    New-Item -ItemType Directory -Force -Path $root | Out-Null
    [void]$script:CaseRoots.Add($root)
    return $root
}

function Invoke-SmokeCase {
    param(
        [string]$Scenario,
        [ValidateSet("reuse", "cold")]
        [string]$Lifecycle = "reuse",
        [int]$Runs = 1,
        [int]$TimeoutSeconds = 8,
        [bool]$ExpectSuccess
    )

    $caseRoot = New-CaseRoot -Scenario $Scenario
    $fakeRoot = Join-Path $caseRoot "fake-device"
    $outDir = Join-Path $caseRoot "out"
    $imagePath = Join-Path $caseRoot "input.jpg"
    New-Item -ItemType Directory -Force -Path $fakeRoot | Out-Null
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    [IO.File]::WriteAllBytes($imagePath, [byte[]](1, 2, 3, 4))

    $previousRoot = $env:QAIRT_VLM_FAKE_ADB_ROOT
    $previousScenario = $env:QAIRT_VLM_FAKE_ADB_SCENARIO
    $env:QAIRT_VLM_FAKE_ADB_ROOT = $fakeRoot
    $env:QAIRT_VLM_FAKE_ADB_SCENARIO = $Scenario

    $arguments = @(
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", $script:SmokeScript,
        "-Adb", $script:FakeAdb,
        "-ImagePath", $imagePath,
        "-OutDir", $outDir,
        "-Runs", [string]$Runs,
        "-Lifecycle", $Lifecycle,
        "-SmokeMode", "api_only",
        "-PollMilliseconds", "100",
        "-TimeoutSeconds", [string]$TimeoutSeconds
    )

    $output = @()
    $exitCode = $null
    $stopwatch = [Diagnostics.Stopwatch]::StartNew()
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = @(& $script:PowerShellExe @arguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $stopwatch.Stop()
        $ErrorActionPreference = $previousPreference
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

    $outputLines = @($output | ForEach-Object { $_.ToString() })
    $outputText = $outputLines -join [Environment]::NewLine
    if ($ExpectSuccess) {
        Assert-Equal -Actual $exitCode -Expected 0 -Message "Smoke case failed unexpectedly: scenario=$Scenario output=$outputText"
    } else {
        Assert-True -Condition ($exitCode -ne 0) -Message "Smoke case unexpectedly succeeded: scenario=$Scenario"
    }

    $statePath = Join-Path $fakeRoot "state.json"
    return [pscustomobject]@{
        CaseRoot = $caseRoot
        FakeRoot = $fakeRoot
        OutDir = $outDir
        Output = $outputLines
        ExitCode = [int]$exitCode
        ElapsedSeconds = $stopwatch.Elapsed.TotalSeconds
        Summary = Get-SessionSummary -OutDir $outDir
        State = Read-JsonFile -Path $statePath
    }
}

function Get-OnlyRun {
    param([object]$Case)

    $runs = @($Case.Summary.runs)
    Assert-Equal -Actual $runs.Count -Expected 1 -Message "Expected exactly one run in the session summary"
    return $runs[0]
}

function Get-ProcessEvidence {
    param([object]$Run)

    Assert-True -Condition (-not [string]::IsNullOrWhiteSpace([string]$Run.processEvidence)) -Message "Process evidence path was not recorded"
    return Read-JsonFile -Path ([string]$Run.processEvidence)
}

function Invoke-Test {
    param(
        [string]$Name,
        [scriptblock]$Body
    )

    Write-Host "[RUN ] $Name"
    try {
        & $Body
        $script:Passed++
        Write-Host "[PASS] $Name"
    } catch {
        [void]$script:Failures.Add([pscustomobject]@{
            Name = $Name
            Message = $_.Exception.Message
        })
        Write-Host "[FAIL] $Name"
        Write-Host ("       " + $_.Exception.Message)
    }
}

function Remove-CaseRoots {
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    foreach ($root in @($script:CaseRoots)) {
        $resolved = [IO.Path]::GetFullPath([string]$root)
        $leaf = Split-Path -Leaf $resolved
        $insideTemp = $resolved.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)
        if (-not $insideTemp -or $leaf -notlike "qairt-vlm-offline-*") {
            throw "Refusing to clean unexpected test path: $resolved"
        }
        if (Test-Path -LiteralPath $resolved -PathType Container) {
            Remove-Item -LiteralPath $resolved -Recurse -Force
        }
    }
}

Invoke-Test -Name "PowerShell AST parser" -Body {
    Assert-NoParseErrors -Path $script:SmokeScript
    Assert-NoParseErrors -Path $script:FakeAdb
    Assert-NoParseErrors -Path $script:LegacyFakeAdb
    Assert-NoParseErrors -Path $script:TestScript
}

Invoke-Test -Name "terminal completed result" -Body {
    $case = Invoke-SmokeCase -Scenario "terminal_completed" -Lifecycle "reuse" -Runs 1 -ExpectSuccess $true
    $run = Get-OnlyRun -Case $case
    Assert-Equal -Actual $run.status -Expected "completed" -Message "Terminal result should complete"
    Assert-True -Condition ([bool]$run.rawResultPreserved) -Message "Terminal result should be pulled"
    Assert-True -Condition ([string]::IsNullOrWhiteSpace([string]$run.parseError)) -Message "Valid terminal JSON should parse"
    Assert-Equal -Actual $run.loadMs -Expected 321 -Message "Completed result should retain metrics"
    Assert-True -Condition ([bool]$run.visionReady) -Message "Completed result should retain native stats"
}

Invoke-Test -Name "process exits before first result JSON" -Body {
    $case = Invoke-SmokeCase -Scenario "process_exit_before_result" -Lifecycle "reuse" -Runs 1 -TimeoutSeconds 8 -ExpectSuccess $false
    $run = Get-OnlyRun -Case $case
    $evidence = Get-ProcessEvidence -Run $run
    Assert-True -Condition ([long]$evidence.observedAfterMilliseconds -lt 7000) -Message "Process exit should be detected before the 8 second wait deadline (waitMs=$($evidence.observedAfterMilliseconds))"
    Assert-Equal -Actual $run.status -Expected "process_exited" -Message "Pre-result process exit should surface in summary"
    Assert-Equal -Actual $evidence.status -Expected "process_exited" -Message "Evidence should record process exit"
    Assert-Equal -Actual ([bool]$evidence.remoteJsonObserved) -Expected $false -Message "No result JSON should have been observed"
    Assert-Equal -Actual $evidence.exitPhase -Expected "before_result_file" -Message "Exit phase should identify the missing first JSON"
    Assert-True -Condition (-not [bool]$run.rawResultPreserved) -Message "Missing result JSON cannot be pulled"
    Assert-True -Condition (-not (Test-Path -LiteralPath ([string]$run.localJson) -PathType Leaf)) -Message "No local result JSON should exist"
}

Invoke-Test -Name "process exits after non-terminal result JSON" -Body {
    $case = Invoke-SmokeCase -Scenario "process_exit_after_result" -Lifecycle "reuse" -Runs 1 -TimeoutSeconds 8 -ExpectSuccess $false
    $run = Get-OnlyRun -Case $case
    $evidence = Get-ProcessEvidence -Run $run
    Assert-True -Condition ([long]$evidence.observedAfterMilliseconds -lt 7000) -Message "Post-result process exit should be detected before the 8 second wait deadline (waitMs=$($evidence.observedAfterMilliseconds))"
    Assert-Equal -Actual ([bool]$evidence.remoteJsonObserved) -Expected $true -Message "Result JSON should have been observed"
    Assert-Equal -Actual $evidence.exitPhase -Expected "after_result_file" -Message "Exit phase should identify post-result exit"
    Assert-True -Condition ([bool]$run.rawResultPreserved) -Message "Non-terminal result JSON should be pulled"
    Assert-True -Condition (Test-Path -LiteralPath ([string]$run.localJson) -PathType Leaf) -Message "Pulled non-terminal JSON is missing"
    Assert-Equal -Actual $run.status -Expected "process_exited" -Message "Post-result process exit should override the non-terminal JSON status"
}

Invoke-Test -Name "invalid UTF-8 fails fast and preserves original bytes" -Body {
    $case = Invoke-SmokeCase -Scenario "garbled_json_completed" -Lifecycle "reuse" -Runs 1 -TimeoutSeconds 8 -ExpectSuccess $false
    $run = Get-OnlyRun -Case $case
    Assert-Equal -Actual $run.status -Expected "invalid_result" -Message "Invalid UTF-8 must not be inferred as completed"
    Assert-Equal -Actual $run.failureKind -Expected "invalid_utf8" -Message "Invalid UTF-8 failure kind is wrong"
    Assert-ContainsText -Actual ([string]$run.parseError) -ExpectedFragment "invalid_utf8" -Message "Invalid UTF-8 parse error should be explicit"
    Assert-True -Condition ([bool]$run.rawResultPreserved) -Message "Invalid UTF-8 JSON should be pulled as evidence"
    Assert-True -Condition (Test-Path -LiteralPath ([string]$run.localJson) -PathType Leaf) -Message "Pulled invalid UTF-8 JSON is missing"

    $remoteMirror = Get-RemoteMirrorPath -FakeRoot $case.FakeRoot -RemotePath ([string]$run.remoteJson)
    Assert-True -Condition (Test-Path -LiteralPath $remoteMirror -PathType Leaf) -Message "Fake remote invalid UTF-8 JSON is missing"
    $remoteHash = (Get-FileHash -LiteralPath $remoteMirror -Algorithm SHA256).Hash
    $localHash = (Get-FileHash -LiteralPath ([string]$run.localJson) -Algorithm SHA256).Hash
    Assert-Equal -Actual $localHash -Expected $remoteHash -Message "Pulled invalid UTF-8 JSON bytes changed"
    $localBytes = [IO.File]::ReadAllBytes([string]$run.localJson)
    Assert-True -Condition ($localBytes -contains [byte]0xC3) -Message "Expected invalid UTF-8 evidence byte was lost"
}

Invoke-Test -Name "cold lifecycle force-stops every run" -Body {
    $case = Invoke-SmokeCase -Scenario "terminal_completed" -Lifecycle "cold" -Runs 3 -ExpectSuccess $true
    Assert-Equal -Actual $case.State.forceStopCount -Expected 3 -Message "Cold lifecycle should force-stop once per run"
    Assert-Equal -Actual $case.State.startCount -Expected 3 -Message "Cold lifecycle should start once per run"
    $operations = @($case.State.operations)
    Assert-Equal -Actual $operations.Count -Expected 6 -Message "Cold lifecycle operation count is wrong"
    for ($i = 0; $i -lt 3; $i++) {
        Assert-Equal -Actual $operations[$i * 2].kind -Expected "force-stop" -Message "Cold run $($i + 1) should begin with force-stop"
        Assert-Equal -Actual $operations[($i * 2) + 1].kind -Expected "start" -Message "Cold run $($i + 1) should start after force-stop"
    }
}

Invoke-Test -Name "reuse lifecycle never force-stops" -Body {
    $case = Invoke-SmokeCase -Scenario "terminal_completed" -Lifecycle "reuse" -Runs 3 -ExpectSuccess $true
    Assert-Equal -Actual $case.State.forceStopCount -Expected 0 -Message "Reuse lifecycle must not force-stop"
    Assert-Equal -Actual $case.State.startCount -Expected 3 -Message "Reuse lifecycle should start once per run"
    $operations = @($case.State.operations)
    Assert-Equal -Actual $operations.Count -Expected 3 -Message "Reuse lifecycle operation count is wrong"
    foreach ($operation in $operations) {
        Assert-Equal -Actual $operation.kind -Expected "start" -Message "Reuse lifecycle should contain only starts"
    }
}

Invoke-Test -Name "historical runner_stage does not terminate a nonterminal root" -Body {
    $case = Invoke-SmokeCase -Scenario "history_root_nonterminal" -Lifecycle "reuse" -Runs 1 -TimeoutSeconds 8 -ExpectSuccess $false
    $run = Get-OnlyRun -Case $case
    $evidence = Get-ProcessEvidence -Run $run
    Assert-Equal -Actual $run.status -Expected "process_exited" -Message "Historical completed events must not make a runner_stage root terminal"
    Assert-Equal -Actual ([bool]$evidence.remoteJsonObserved) -Expected $true -Message "Historical event result JSON should have been observed"
    Assert-Equal -Actual $evidence.exitPhase -Expected "after_result_file" -Message "Historical event case should record post-result process exit"
    Assert-True -Condition ([bool]$run.rawResultPreserved) -Message "Historical event result JSON should be pulled"
    Assert-True -Condition ([string]::IsNullOrWhiteSpace([string]$run.parseError)) -Message "Historical event result JSON should parse"
}

Invoke-Test -Name "completed root wins with historical runner_stage" -Body {
    $case = Invoke-SmokeCase -Scenario "history_root_completed" -Lifecycle "reuse" -Runs 1 -ExpectSuccess $true
    $run = Get-OnlyRun -Case $case
    Assert-Equal -Actual $run.status -Expected "completed" -Message "Root completed status should be terminal"
    Assert-True -Condition ([bool]$run.rawResultPreserved) -Message "Completed historical event result should be pulled"
    Assert-True -Condition ([string]::IsNullOrWhiteSpace([string]$run.parseError)) -Message "Completed historical event result should parse"
    Assert-True -Condition ($run.nativeStats.visionReady -is [bool]) -Message "Historical completion visionReady must be a JSON boolean"
    Assert-Equal -Actual $run.nativeStats.visionReady -Expected $true -Message "Historical completion visionReady must be true"
    Assert-ContainsText -Actual ([string]$run.stageTrace) -ExpectedFragment "qairt_vlm_destroy_start" -Message "Trailing runner_stage history should be retained"
}

$total = $script:Passed + $script:Failures.Count
Write-Host ""
Write-Host "Offline QAIRT VLM smoke tests: $($script:Passed)/$total passed"

if ($script:Failures.Count -gt 0) {
    foreach ($failure in @($script:Failures)) {
        Write-Host ("FAIL: " + $failure.Name + " - " + $failure.Message)
    }
    Write-Host "Artifacts retained under:"
    foreach ($root in @($script:CaseRoots)) {
        Write-Host ("  " + $root)
    }
    exit 1
}

try {
    Remove-CaseRoots
} catch {
    Write-Warning $_.Exception.Message
}

Write-Host "PASS: all offline QAIRT VLM smoke regressions"
