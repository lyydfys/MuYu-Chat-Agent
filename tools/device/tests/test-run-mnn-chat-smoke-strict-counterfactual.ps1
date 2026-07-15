param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$testDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$deviceDir = Split-Path -Parent $testDir
$smokeScript = Join-Path $deviceDir 'run-mnn-chat-smoke.ps1'
$modulePath = Join-Path $deviceDir 'DeviceSmoke.psm1'
$caseRoot = $null

Import-Module $modulePath -Force

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Assert-Equal {
    param($Actual, $Expected, [string]$Message)
    if ($Actual -ne $Expected) { throw "$Message (expected=$Expected actual=$Actual)" }
}

function Assert-Throws {
    param(
        [scriptblock]$Action,
        [string]$ExpectedFragment,
        [string]$Message
    )
    try {
        & $Action
    } catch {
        if ($_.Exception.Message.Contains($ExpectedFragment)) { return }
        throw "$Message Unexpected error: $($_.Exception.Message)"
    }
    throw "$Message Expected an exception."
}

function Import-SmokeScriptFunctions {
    param([string[]]$Names)

    $tokens = $null
    $errors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile($smokeScript, [ref]$tokens, [ref]$errors)
    if ($errors.Count -gt 0) {
        throw ('PowerShell parser rejected {0}: {1}' -f $smokeScript, (@($errors | ForEach-Object Message) -join '; '))
    }
    $definitions = @($ast.FindAll({
        param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst]
    }, $true))
    foreach ($name in $Names) {
        $definition = @($definitions | Where-Object Name -CEQ $name)
        if ($definition.Count -ne 1) { throw "Expected exactly one function named $name in the smoke runner." }
        $globalDefinition = $definition[0].Extent.Text -replace ('^function\s+' + [regex]::Escape($name)), "function global:$name"
        . ([scriptblock]::Create($globalDefinition))
    }
    return $ast
}

function New-CounterfactualJson {
    param(
        [string]$FirstText,
        [string]$SecondText,
        [string]$FirstImage = '/sdcard/first.png',
        [string]$SecondImage = '/sdcard/second.png',
        [string]$FirstReportedSha = '',
        [string]$SecondReportedSha = '',
        [bool]$VisionReady = $true
    )

    if ([string]::IsNullOrWhiteSpace($FirstReportedSha)) {
        $FirstReportedSha = Get-MnnChatSmokeStringSha256 -Text $FirstText
    }
    if ([string]::IsNullOrWhiteSpace($SecondReportedSha)) {
        $SecondReportedSha = Get-MnnChatSmokeStringSha256 -Text $SecondText
    }
    $stats = [pscustomobject]@{
        backend = 'mnn_cpu'
        loaded = $true
        runnerReady = $true
        visionReady = $VisionReady
        visualModelPath = '/models/visual.mnn'
        nativeLibDir = '/data/app/fake/lib/arm64'
        modelPath = '/models/mca_runtime_config.json'
        lastConfigJson = '{"thread_num":4}'
    }
    return [pscustomobject]@{
        status = 'completed'
        events = @(
            [pscustomobject]@{
                status = 'generation_first_ok'
                generation = [pscustomobject]@{
                    text = $FirstText
                    textSha256 = $FirstReportedSha
                    imagePath = $FirstImage
                }
            },
            [pscustomobject]@{
                status = 'generation_second_ok'
                generation = [pscustomobject]@{
                    text = $SecondText
                    textSha256 = $SecondReportedSha
                    imagePath = $SecondImage
                }
            },
            [pscustomobject]@{
                status = 'completed'
                nativeStats = $stats
            }
        )
    }
}

try {
    $ast = Import-SmokeScriptFunctions -Names @(
        'Get-MnnChatSmokeEventValue',
        'ConvertTo-MnnChatSmokeShellLiteral',
        'Get-MnnChatSmokeSha256Hex',
        'Get-MnnChatSmokeStringSha256',
        'New-MnnChatSmokeManifestEvidence',
        'Get-MnnChatSmokeRemoteFileEvidence',
        'Get-MnnChatSmokeRemoteDirectoryEvidence',
        'Get-MnnChatSmokeApkEvidence',
        'New-MnnChatSmokeTextEvidence',
        'New-MnnChatSmokeConfigEvidence',
        'Assert-MnnChatSmokeDirectCounterfactualContract'
    )

    $parameterNames = @($ast.ParamBlock.Parameters | ForEach-Object { $_.Name.VariablePath.UserPath })
    foreach ($requiredParameter in @(
        'FirstImageExpectedTextFragments',
        'SecondImageExpectedTextFragments',
        'AllowIdenticalCounterfactualOutputs'
    )) {
        Assert-True -Condition ($parameterNames -ccontains $requiredParameter) -Message "Runner is missing parameter $requiredParameter."
    }

    $caseRoot = Join-Path ([IO.Path]::GetTempPath()) ('mnn-strict-counterfactual-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $caseRoot | Out-Null

    $shaA = 'a' * 64
    $shaB = 'b' * 64
    $inputEvidence = [pscustomobject]@{
        first = [pscustomobject]@{ path = '/sdcard/first.png'; sha256 = $shaA }
        second = [pscustomobject]@{ path = '/sdcard/second.png'; sha256 = $shaB }
    }
    $hanRed = ([string][char]0x7EA2) + ([string][char]0x8272)
    $hanBlue = ([string][char]0x84DD) + ([string][char]0x8272)
    $emoji = [char]::ConvertFromUtf32(0x1F534)
    $firstText = "$hanRed-circle-$emoji"
    $secondText = "$hanBlue-square"
    $json = New-CounterfactualJson -FirstText $firstText -SecondText $secondText

    $evidence = Assert-MnnChatSmokeDirectCounterfactualContract `
        -Json $json -ImagePath '/sdcard/first.png' -SecondImagePath '/sdcard/second.png' `
        -InputEvidence $inputEvidence -FirstExpectedFragments @($hanRed, $emoji) `
        -SecondExpectedFragments @($hanBlue) -RunOutputDir $caseRoot -RunId 'valid'
    Assert-True -Condition $evidence.strict -Message 'Valid counterfactual evidence must be marked strict.'
    Assert-True -Condition $evidence.inputSha256Differ -Message 'Valid counterfactual evidence must prove distinct input hashes.'
    Assert-True -Condition $evidence.outputSha256Differ -Message 'Valid counterfactual evidence must prove distinct output hashes.'
    Assert-Equal -Actual $evidence.first.output.rawText -Expected $firstText -Message 'First raw output must be preserved exactly.'
    Assert-Equal -Actual $evidence.second.output.rawText -Expected $secondText -Message 'Second raw output must be preserved exactly.'
    Assert-True -Condition ($evidence.first.output.utf8Hex.Contains('F09F94B4')) -Message 'Supplementary Unicode must be preserved in UTF-8 evidence.'
    $firstFileBytes = [IO.File]::ReadAllBytes($evidence.first.output.utf8File)
    Assert-Equal -Actual ([Convert]::ToBase64String($firstFileBytes)) -Expected $evidence.first.output.utf8Base64 -Message 'Raw UTF-8 evidence file must match the recorded bytes.'
    Assert-Equal -Actual (Get-MnnChatSmokeSha256Hex -Bytes $firstFileBytes) -Expected $evidence.first.output.sha256 -Message 'Raw UTF-8 evidence file hash must match the summary evidence.'

    $manifestA = New-MnnChatSmokeManifestEvidence -Path '/bundle' -Kind 'test_bundle' -Entries @(
        [pscustomobject]@{ relativePath = 'z.bin'; sha256 = $shaB },
        [pscustomobject]@{ relativePath = 'a.json'; sha256 = $shaA }
    )
    $manifestB = New-MnnChatSmokeManifestEvidence -Path '/bundle' -Kind 'test_bundle' -Entries @(
        [pscustomobject]@{ relativePath = 'a.json'; sha256 = $shaA },
        [pscustomobject]@{ relativePath = 'z.bin'; sha256 = $shaB }
    )
    Assert-Equal -Actual $manifestA.sha256 -Expected $manifestB.sha256 -Message 'Bundle manifest SHA must be independent of adb output order.'
    Assert-Equal -Actual $manifestA.files[0].relativePath -Expected 'a.json' -Message 'Bundle manifest paths must use ordinal ordering.'

    function global:Invoke-MnnChatSmokeRemoteCommand {
        param([string]$Adb, [string]$Serial, [string]$Command)
        if ($Command -eq 'pm path com.muyuchat.mca') {
            return [pscustomobject]@{ exitCode = 0; lines = @('package:/data/app/fake/base.apk'); text = 'package:/data/app/fake/base.apk' }
        }
        if ($Command.Contains("sha256sum '/data/app/fake/base.apk'")) {
            return [pscustomobject]@{ exitCode = 0; lines = @("$shaA  /data/app/fake/base.apk"); text = "$shaA  /data/app/fake/base.apk" }
        }
        if ($Command.Contains("root='/bundle'")) {
            $lines = @("$shaB  /bundle/z.bin", "$shaA  /bundle/a.json")
            return [pscustomobject]@{ exitCode = 0; lines = $lines; text = $lines -join "`n" }
        }
        throw "Unexpected mocked remote command: $Command"
    }
    $apkEvidence = Get-MnnChatSmokeApkEvidence -Adb 'fake-adb' -Serial 'FAKE123' -Package 'com.muyuchat.mca'
    Assert-Equal -Actual $apkEvidence.sha256 -Expected $shaA -Message 'APK evidence must expose the exact base APK SHA.'
    $remoteBundleEvidence = Get-MnnChatSmokeRemoteDirectoryEvidence -Adb 'fake-adb' -Serial 'FAKE123' -Path '/bundle' -Kind 'mnn_bundle'
    Assert-Equal -Actual $remoteBundleEvidence.files[0].relativePath -Expected 'a.json' -Message 'Remote bundle evidence must normalize unordered sha256sum output.'
    Assert-Equal -Actual $remoteBundleEvidence.sha256 -Expected $manifestA.sha256 -Message 'Remote bundle evidence must use the deterministic manifest SHA.'

    $configRaw = '{"temperature":0,"thread_num":4}'
    $configEvidence = New-MnnChatSmokeConfigEvidence -RawJson $configRaw -Label 'config-test' -RunOutputDir $caseRoot -RunId 'config'
    Assert-Equal -Actual $configEvidence.sha256 -Expected (Get-MnnChatSmokeStringSha256 -Text $configRaw) -Message 'Config SHA must cover the exact raw UTF-8 JSON.'
    Assert-Equal -Actual ([Text.Encoding]::UTF8.GetString([IO.File]::ReadAllBytes($configEvidence.utf8File))) -Expected $configRaw -Message 'Config evidence file must preserve exact raw JSON.'

    $sameInputEvidence = [pscustomobject]@{
        first = [pscustomobject]@{ sha256 = $shaA }
        second = [pscustomobject]@{ sha256 = $shaA }
    }
    Assert-Throws `
        -Action { Assert-MnnChatSmokeDirectCounterfactualContract -Json $json -ImagePath '/sdcard/first.png' -SecondImagePath '/sdcard/second.png' -InputEvidence $sameInputEvidence -RunOutputDir $caseRoot -RunId 'same-input' } `
        -ExpectedFragment 'identical SHA-256' `
        -Message 'Strict counterfactual must reject byte-identical image inputs.'

    $identicalJson = New-CounterfactualJson -FirstText $firstText -SecondText $firstText
    $failedIdenticalEvidence = $null
    Assert-Throws `
        -Action { Assert-MnnChatSmokeDirectCounterfactualContract -Json $identicalJson -ImagePath '/sdcard/first.png' -SecondImagePath '/sdcard/second.png' -InputEvidence $inputEvidence -RunOutputDir $caseRoot -RunId 'same-output' -EvidenceOut ([ref]$failedIdenticalEvidence) } `
        -ExpectedFragment 'identical output SHA-256' `
        -Message 'Strict counterfactual must reject identical outputs by default.'
    Assert-True -Condition ($null -ne $failedIdenticalEvidence) -Message 'Rejected identical outputs must still return raw evidence through EvidenceOut.'
    Assert-True -Condition (Test-Path -LiteralPath $failedIdenticalEvidence.first.output.utf8File -PathType Leaf) -Message 'Rejected identical outputs must retain the first raw UTF-8 file.'
    Assert-True -Condition (Test-Path -LiteralPath $failedIdenticalEvidence.second.output.utf8File -PathType Leaf) -Message 'Rejected identical outputs must retain the second raw UTF-8 file.'
    $allowedEvidence = Assert-MnnChatSmokeDirectCounterfactualContract `
        -Json $identicalJson -ImagePath '/sdcard/first.png' -SecondImagePath '/sdcard/second.png' `
        -InputEvidence $inputEvidence -RunOutputDir $caseRoot -RunId 'same-output-allowed' -AllowIdenticalOutputs
    Assert-True -Condition (-not $allowedEvidence.outputSha256Differ) -Message 'Explicit identical-output override must retain evidence that outputs matched.'
    Assert-True -Condition $allowedEvidence.identicalOutputsAllowed -Message 'Explicit identical-output override must be recorded.'

    Assert-Throws `
        -Action { Assert-MnnChatSmokeDirectCounterfactualContract -Json $json -ImagePath '/sdcard/first.png' -SecondImagePath '/sdcard/second.png' -InputEvidence $inputEvidence -FirstExpectedFragments @('missing-first') -RunOutputDir $caseRoot -RunId 'missing-first' } `
        -ExpectedFragment 'generation-first output is missing expected fragment' `
        -Message 'First-image fragments must be checked only against the first output.'
    Assert-Throws `
        -Action { Assert-MnnChatSmokeDirectCounterfactualContract -Json $json -ImagePath '/sdcard/first.png' -SecondImagePath '/sdcard/second.png' -InputEvidence $inputEvidence -SecondExpectedFragments @('missing-second') -RunOutputDir $caseRoot -RunId 'missing-second' } `
        -ExpectedFragment 'generation-second output is missing expected fragment' `
        -Message 'Second-image fragments must be checked only against the second output.'

    $wrongShaJson = New-CounterfactualJson -FirstText $firstText -SecondText $secondText -FirstReportedSha ('c' * 64)
    Assert-Throws `
        -Action { Assert-MnnChatSmokeDirectCounterfactualContract -Json $wrongShaJson -ImagePath '/sdcard/first.png' -SecondImagePath '/sdcard/second.png' -InputEvidence $inputEvidence -RunOutputDir $caseRoot -RunId 'wrong-reported-sha' } `
        -ExpectedFragment 'textSha256 is missing or does not match' `
        -Message 'Strict counterfactual must cross-check the activity-reported output SHA.'

    $wrongImageJson = New-CounterfactualJson -FirstText $firstText -SecondText $secondText -FirstImage '/sdcard/not-first.png'
    Assert-Throws `
        -Action { Assert-MnnChatSmokeDirectCounterfactualContract -Json $wrongImageJson -ImagePath '/sdcard/first.png' -SecondImagePath '/sdcard/second.png' -InputEvidence $inputEvidence -RunOutputDir $caseRoot -RunId 'wrong-image' } `
        -ExpectedFragment 'does not match the first input' `
        -Message 'Strict counterfactual must bind each output event to its requested image path.'

    $visionDisabledJson = New-CounterfactualJson -FirstText $firstText -SecondText $secondText -VisionReady $false
    Assert-Throws `
        -Action { Assert-MnnChatSmokeDirectCounterfactualContract -Json $visionDisabledJson -ImagePath '/sdcard/first.png' -SecondImagePath '/sdcard/second.png' -InputEvidence $inputEvidence -RunOutputDir $caseRoot -RunId 'vision-disabled' } `
        -ExpectedFragment 'visionReady=true' `
        -Message 'Strict counterfactual must require a ready visual encoder.'

    $source = Get-Content -LiteralPath $smokeScript -Raw
    foreach ($requiredEvidenceText in @(
        'Get-MnnChatSmokeApkEvidence',
        "Kind 'native_libraries'",
        "Kind 'mnn_bundle'",
        "Label 'native-runtime-config'",
        'counterfactualInputEvidence = $counterfactualInputEvidence',
        'artifactEvidence = $sessionArtifactEvidence'
    )) {
        Assert-True -Condition $source.Contains($requiredEvidenceText) -Message "Runner is missing strict evidence wiring: $requiredEvidenceText"
    }

    Write-Host 'PASS: strict MNN counterfactual validates distinct inputs/outputs, per-image semantics, artifact/config SHA evidence, and raw UTF-8 preservation.'
} finally {
    if ($null -ne $caseRoot -and (Test-Path -LiteralPath $caseRoot -PathType Container)) {
        Remove-Item -LiteralPath $caseRoot -Recurse -Force
    }
}
