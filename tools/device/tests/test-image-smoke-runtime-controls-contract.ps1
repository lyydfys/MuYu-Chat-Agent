param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$deviceDir = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)

foreach ($scriptName in @('run-mnn-diffusion-smoke.ps1', 'run-qnn-image-smoke.ps1')) {
    $source = Get-Content (Join-Path $deviceDir $scriptName) -Raw
    if ($source -match "'--ef', 'distilledGuidance'" -or $source -match "'--ef', 'flowShift'") {
        throw "$scriptName must not forward stable-diffusion.cpp-only controls."
    }
    if ($source -notmatch 'DistilledGuidance or FlowShift') {
        throw "$scriptName must reject explicit unsupported controls."
    }
}

$mnnSource = Get-Content (Join-Path $deviceDir 'run-mnn-diffusion-smoke.ps1') -Raw
if ($mnnSource -notmatch "\[string\]\s*\`$Runner\s*=\s*''") {
    throw 'MNN image smoke must resolve its runner after inspecting the model family.'
}
if ($mnnSource -notmatch "if\s*\(\`$isSanaFamily\)\s*\{\s*'sana_varp'\s*\}\s*else\s*\{\s*'direct'\s*\}") {
    throw 'MNN image smoke must default SANA to sana_varp and Stable Diffusion to direct.'
}
if ($mnnSource -notmatch "@\('sana_varp', 'sana', 'module'\)" -or
    $mnnSource -notmatch "@\('direct', 'module'\)") {
    throw 'MNN image smoke must validate explicit runner values for SANA and Stable Diffusion.'
}
if ($mnnSource -match "'--es',\s*'tokenEmbeddingMode'") {
    throw 'MNN image smoke must not send the removed tokenEmbeddingMode control.'
}

$mnnPath = Join-Path $deviceDir 'run-mnn-diffusion-smoke.ps1'
foreach ($case in @(
        [pscustomobject]@{
            Family = 'SANA'
            Runner = 'direct'
            ErrorFragment = 'Unsupported MNN SANA runner'
        },
        [pscustomobject]@{
            Family = 'SD15'
            Runner = 'sana_varp'
            ErrorFragment = 'Unsupported MNN Stable Diffusion runner'
        }
    )) {
    $observedError = $null
    try {
        & $mnnPath -BundleRoot '/bundle' -Family $case.Family -Runner $case.Runner
    } catch {
        $observedError = $_.Exception.Message
    }
    if ([string]::IsNullOrWhiteSpace($observedError) -or
        -not $observedError.Contains($case.ErrorFragment)) {
        throw "MNN runner validation did not reject family=$($case.Family) runner=$($case.Runner)."
    }
}

$qnnPath = Join-Path $deviceDir 'run-qnn-image-smoke.ps1'
$tokens = $null
$parseErrors = $null
$qnnAst = [System.Management.Automation.Language.Parser]::ParseFile(
    $qnnPath,
    [ref]$tokens,
    [ref]$parseErrors
)
if ($parseErrors.Count -gt 0) {
    throw "QNN image smoke script has parser errors: $($parseErrors.Message -join '; ')"
}
$selector = @($qnnAst.FindAll({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'Select-DeviceSmokeFirstPositiveHtpArch'
}, $true))
if ($selector.Count -ne 1) {
    throw 'QNN image smoke must define one positive HTP-arch selector.'
}
. ([scriptblock]::Create($selector[0].Extent.Text))
if ((Select-DeviceSmokeFirstPositiveHtpArch -Candidates @(0, $null, 79, 81)) -ne 79) {
    throw 'QNN HTP-arch selector must skip missing/zero preflight evidence.'
}
if ((Select-DeviceSmokeFirstPositiveHtpArch -Candidates @('invalid', '0', 81)) -ne 81) {
    throw 'QNN HTP-arch selector must skip invalid evidence and retain the first positive value.'
}
$qnnSource = Get-Content $qnnPath -Raw

$semanticStepResolver = @($qnnAst.FindAll({
    param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'Resolve-DeviceSmokeQnnSemanticSteps'
}, $true))
if ($semanticStepResolver.Count -ne 1) {
    throw 'QNN image smoke must define one semantic PNDM step resolver.'
}
. ([scriptblock]::Create($semanticStepResolver[0].Extent.Text))
if ((Resolve-DeviceSmokeQnnSemanticSteps -Mode semantic -Steps 1 -SampleMethod pndm -StepsWasExplicit:$false) -ne 4) {
    throw 'QNN semantic PNDM smoke must promote an omitted one-step default to four steps.'
}
if ((Resolve-DeviceSmokeQnnSemanticSteps -Mode graph -Steps 1 -SampleMethod pndm -StepsWasExplicit:$false) -ne 1) {
    throw 'QNN graph smoke must retain its one-step default.'
}
if ((Resolve-DeviceSmokeQnnSemanticSteps -Mode semantic -Steps 1 -SampleMethod euler -StepsWasExplicit:$true) -ne 1) {
    throw 'QNN semantic Euler smoke must allow an explicit one-step request.'
}
$lowStepError = $null
try {
    Resolve-DeviceSmokeQnnSemanticSteps -Mode semantic -Steps 3 -SampleMethod pndm -StepsWasExplicit:$true
} catch {
    $lowStepError = $_.Exception.Message
}
if ([string]::IsNullOrWhiteSpace($lowStepError) -or
    -not $lowStepError.Contains('requires at least four steps')) {
    throw 'QNN semantic PNDM smoke must reject explicit step counts below four with a clear error.'
}
if ($qnnSource -notmatch 'Resolve-DeviceSmokeQnnSemanticSteps' -or
    $qnnSource -notmatch 'PSBoundParameters\.ContainsKey\(''Steps''\)') {
    throw 'QNN image smoke must resolve semantic step defaults before starting the device run.'
}
$executionIndex = $qnnSource.IndexOf("Get-DeviceSmokeProperty -Object `$executionRuntime -Name 'htpArchVersion'")
$inspectionIndex = $qnnSource.IndexOf("Get-DeviceSmokeProperty -Object `$runtimeInspection -Name 'htpArchVersion'", $executionIndex + 1)
$preflightIndex = $qnnSource.IndexOf("Get-DeviceSmokeProperty -Object `$preflightRuntime -Name 'htpArchVersion'", $inspectionIndex + 1)
$selectedIndex = $qnnSource.IndexOf("Get-DeviceSmokeProperty -Object `$terminalResult -Name 'selectedHtpArch'", $preflightIndex + 1)
if ($executionIndex -lt 0 -or $inspectionIndex -le $executionIndex -or
    $preflightIndex -le $inspectionIndex -or $selectedIndex -le $preflightIndex) {
    throw 'QNN summary must prefer executionRuntime, runtimeInspection, preflight runtime, then selectedHtpArch.'
}
if ($qnnSource.IndexOf("`$executionRuntime = Get-DeviceSmokeProperty -Object `$terminalJson -Name 'executionRuntime'") -lt 0) {
    throw 'QNN summary must recover executionRuntime when the terminal event publishes it beside result.'
}

Write-Host 'PASS: image smoke scripts enforce runtime-specific controls and preserve positive QNN HTP evidence.'
