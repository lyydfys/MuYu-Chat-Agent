param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$testDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$deviceDir = Split-Path -Parent $testDir
$smokeScript = Join-Path $deviceDir 'run-qnn-chat-smoke.ps1'
$fakeAdb = Join-Path $testDir 'fixtures\fake-adb.ps1'
$powerShellExe = (Get-Command powershell.exe -ErrorAction Stop).Source
$caseRoot = $null

function Assert-Equal {
    param($Actual, $Expected, [string]$Message)
    if ($Actual -ne $Expected) {
        throw "$Message (expected=$Expected actual=$Actual)"
    }
}

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) {
        throw $Message
    }
}

function Assert-NoParseErrors {
    param([string]$Path)
    $tokens = $null
    $errors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile($Path, [ref]$tokens, [ref]$errors)
    if ($errors.Count -gt 0) {
        throw ('PowerShell parser rejected {0}: {1}' -f $Path, (@($errors | ForEach-Object Message) -join '; '))
    }
}

function Get-RemoteMirrorPath {
    param([string]$Root, [string]$RemotePath)
    $path = $Root
    foreach ($segment in @($RemotePath.TrimStart('/') -split '/')) {
        $path = Join-Path $path $segment
    }
    return $path
}

function New-QairtBundle {
    param([string]$Root, [string]$RemotePath)
    $bundleDir = Get-RemoteMirrorPath -Root $Root -RemotePath $RemotePath
    New-Item -ItemType Directory -Force -Path $bundleDir | Out-Null
    $utf8 = New-Object System.Text.UTF8Encoding -ArgumentList $false
    [IO.File]::WriteAllText((Join-Path $bundleDir 'metadata.json'), '{"format":"qairt"}', $utf8)
    [IO.File]::WriteAllText((Join-Path $bundleDir 'genie_config.json'), '{"runtime":"qairt"}', $utf8)
    [IO.File]::WriteAllBytes((Join-Path $bundleDir 'qnn_context.bin'), [byte[]](1, 2, 3))
}

function Invoke-QnnChatSmoke {
    param(
        [string]$ModelPath,
        [string]$SessionId,
        [string]$OutDir
    )

    $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $smokeScript, '-Adb', $fakeAdb, '-Serial', 'FAKE123', '-ModelPath', $ModelPath, '-DisplayName', 'Offline QAIRT', '-OutDir', $OutDir, '-SessionId', $SessionId, '-Lifecycle', 'reuse', '-TimeoutSeconds', '5', '-PollMilliseconds', '100')
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = @(& $powerShellExe @arguments 2>&1)
        return [pscustomobject]@{
            ExitCode = $LASTEXITCODE
            Output = $output
        }
    } finally {
        $ErrorActionPreference = $previousPreference
    }
}

try {
    Assert-NoParseErrors -Path $smokeScript
    Assert-NoParseErrors -Path $fakeAdb
    Assert-NoParseErrors -Path $MyInvocation.MyCommand.Path

    $caseRoot = Join-Path ([IO.Path]::GetTempPath()) ("qnn-chat-offline-" + [Guid]::NewGuid().ToString('N'))
    $fakeRoot = Join-Path $caseRoot 'fake-device'
    $outDir = Join-Path $caseRoot 'out'
    New-Item -ItemType Directory -Force -Path $fakeRoot, $outDir | Out-Null

    $previousRoot = $env:QAIRT_VLM_FAKE_ADB_ROOT
    $previousScenario = $env:QAIRT_VLM_FAKE_ADB_SCENARIO
    $env:QAIRT_VLM_FAKE_ADB_ROOT = $fakeRoot
    $env:QAIRT_VLM_FAKE_ADB_SCENARIO = 'qnn_terminal_event_stats'
    try {
        $directModelPath = '/data/local/tmp/qairt-chat-direct'
        New-QairtBundle -Root $fakeRoot -RemotePath $directModelPath
        $invocation = Invoke-QnnChatSmoke -ModelPath $directModelPath -SessionId 'event-stats' -OutDir $outDir
        Assert-Equal -Actual $invocation.ExitCode -Expected 0 -Message "QNN chat smoke should succeed with terminal event nativeStats: $($invocation.Output -join [Environment]::NewLine)"

        $nestedOuterPath = '/data/local/tmp/qairt-chat-nested'
        $nestedModelPath = "$nestedOuterPath/qwen3-geniex_qairt"
        New-QairtBundle -Root $fakeRoot -RemotePath $nestedModelPath
        $invocation = Invoke-QnnChatSmoke -ModelPath $nestedOuterPath -SessionId 'nested-root' -OutDir $outDir
        Assert-Equal -Actual $invocation.ExitCode -Expected 0 -Message "QNN chat smoke should resolve a single nested QAIRT model root: $($invocation.Output -join [Environment]::NewLine)"

        $ambiguousOuterPath = '/data/local/tmp/qairt-chat-ambiguous'
        $ambiguousModelPathA = "$ambiguousOuterPath/variant-a"
        $ambiguousModelPathB = "$ambiguousOuterPath/variant-b"
        New-QairtBundle -Root $fakeRoot -RemotePath $ambiguousModelPathA
        New-QairtBundle -Root $fakeRoot -RemotePath $ambiguousModelPathB
        $invocation = Invoke-QnnChatSmoke -ModelPath $ambiguousOuterPath -SessionId 'ambiguous-root' -OutDir $outDir
        Assert-True -Condition ($invocation.ExitCode -ne 0) -Message 'QNN chat smoke must reject ambiguous nested QAIRT model roots'
        $errorText = $invocation.Output -join [Environment]::NewLine
        Assert-True -Condition ($errorText.Contains('Unable to determine QAIRT chat model root')) -Message "Ambiguous root failure must explain the resolution problem: $errorText"
        Assert-True -Condition ($errorText.Contains($ambiguousModelPathA) -and $errorText.Contains($ambiguousModelPathB)) -Message "Ambiguous root failure must list candidate paths: $errorText"
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

    $summaryPath = Join-Path $outDir 'event-stats\summary.json'
    Assert-True -Condition (Test-Path -LiteralPath $summaryPath -PathType Leaf) -Message 'QNN chat summary was not written'
    $summary = Get-Content -LiteralPath $summaryPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $run = @($summary.runs)[0]
    Assert-Equal -Actual $run.status -Expected 'completed' -Message 'QNN chat smoke should complete'
    Assert-Equal -Actual $run.nativeStats.backend -Expected 'geniex_qairt' -Message 'Summary must retain event nativeStats backend'
    Assert-Equal -Actual $run.nativeStats.computeUnit -Expected 'npu' -Message 'Summary must retain event nativeStats compute unit'
    Assert-Equal -Actual $run.nativeStats.decodeTps -Expected 21.35 -Message 'Summary must retain event nativeStats throughput'

    $nestedSummaryPath = Join-Path $outDir 'nested-root\summary.json'
    Assert-True -Condition (Test-Path -LiteralPath $nestedSummaryPath -PathType Leaf) -Message 'Nested QAIRT root summary was not written'
    $nestedSummary = Get-Content -LiteralPath $nestedSummaryPath -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-Equal -Actual $nestedSummary.modelPath -Expected '/data/local/tmp/qairt-chat-nested/qwen3-geniex_qairt' -Message 'Nested QAIRT root must be passed to the activity and summary'

    Write-Host 'PASS: QNN chat smoke resolves direct and single nested QAIRT roots, rejects ambiguous roots, and preserves terminal event nativeStats.'
} finally {
    if ($null -ne $caseRoot -and (Test-Path -LiteralPath $caseRoot -PathType Container)) {
        Remove-Item -LiteralPath $caseRoot -Recurse -Force
    }
}
