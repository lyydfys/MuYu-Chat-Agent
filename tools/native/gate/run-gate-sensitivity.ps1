# MCA repair runId=20261009-parity / T07 test-trust
# GATE-SENSITIVITY driver for the defect classes T01's controlled negative tests
# do NOT cover (PLAN 9.3).  Run under the real Android NDK toolchain.
#
# Two products of this run:
#   (1) the T07 case set - ORPHAN_TU / MISSING_TARGET / MACRO_FLIP /
#       JNI_OVERLOAD - each injected in an isolated fixture, each asserted to be
#       DETECTED, with the evidence that the competing gates (compile+link exit
#       code, dynamic-export set) are BLIND to it;
#   (2) a faithful reproduction of the T01 driver's missing failure channel:
#       run-gate-negative-tests.ps1 is executed with a deliberately broken
#       toolchain path.  Nothing compiles, yet its SYNTAX and MISSING_SYMBOL
#       cases are still reported detected=True (because the detection criterion
#       is only "cmake --build returned non-zero"), allDefectsDetected=False is
#       printed, and the process still exits 0.
#
# Exit code: 0 only when every case was detected AND all expectations held.
param(
    [string]$RepoRoot = 'E:\MAC',
    [string]$BuildRoot = 'E:\MAC\build\t07-gate-sensitivity',
    [string]$EvidenceRoot = 'E:\MAC\artifacts\repair\20261009-parity\tests\gate',
    [string]$CmakeExe = 'D:\model\android-sdk\cmake\3.22.1\bin\cmake.exe',
    [string]$NinjaExe = 'D:\model\android-sdk\cmake\3.22.1\bin\ninja.exe',
    [string]$Toolchain = 'D:\model\android-sdk\ndk\29.0.13113456\build\cmake\android.toolchain.cmake',
    [string]$LlvmNm = 'D:\model\android-sdk\ndk\29.0.13113456\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-nm.exe',
    [switch]$SkipT01MetaRepro
)

$ErrorActionPreference = 'Continue'

$fixture = Join-Path $RepoRoot 'tools\native\gate\fixture'
$fixtureKotlin = Join-Path $RepoRoot 'tools\native\gate\fixture-kotlin'
$orphanTemplate = Join-Path $fixture 'pending\t07_dropped_tu.cpp.template'
$orphanTarget = Join-Path $fixture 't07_dropped_tu.cpp'
$extractTool = Join-Path $RepoRoot 'artifacts\repair\20261009-parity\native\tools\extract-target-graph.ps1'
$coverageTool = Join-Path $RepoRoot 'tools\native\gate\verify-source-set-coverage.ps1'
$jniTool = Join-Path $RepoRoot 'tools\native\gate\check-jni-overload-exports.ps1'
$t01Driver = Join-Path $RepoRoot 'artifacts\repair\20261009-parity\native\tools\run-gate-negative-tests.ps1'

foreach ($p in @($fixture, $fixtureKotlin, $orphanTemplate, $extractTool, $coverageTool, $jniTool, $t01Driver)) {
    if (-not (Test-Path -LiteralPath $p)) { throw "required asset missing: $p" }
}
New-Item -ItemType Directory -Force -Path $BuildRoot, $EvidenceRoot | Out-Null

function Get-DynamicExports {
    param([string]$SoPath)
    if (-not (Test-Path -LiteralPath $SoPath)) { return @() }
    $out = @(& $LlvmNm '-D' '--defined-only' $SoPath 2>&1 | ForEach-Object { "$_" })
    $syms = [System.Collections.Generic.List[string]]::new()
    foreach ($l in $out) { if ($l -match '^\s*[0-9a-fA-F]+\s+\S\s+(\S+)\s*$') { $syms.Add($matches[1]) } }
    return @($syms)
}

function Invoke-Case {
    param([string]$CaseName)
    $bdir = Join-Path $BuildRoot $CaseName
    if (Test-Path -LiteralPath $bdir) { Remove-Item -LiteralPath $bdir -Recurse -Force }

    # the ORPHAN_TU defect is materialized on disk and never listed in any target
    if ($CaseName -eq 'ORPHAN_TU') {
        Copy-Item -LiteralPath $orphanTemplate -Destination $orphanTarget -Force
    } elseif (Test-Path -LiteralPath $orphanTarget) {
        Remove-Item -LiteralPath $orphanTarget -Force
    }

    # seed the CMake File API query BEFORE configure so the target graph comes
    # from CMake itself (same contract as T01's run-cmake.ps1)
    $queryDir = Join-Path $bdir '.cmake\api\v1\query\client-t07'
    New-Item -ItemType Directory -Force -Path $queryDir | Out-Null
    $query = @{ requests = @(
        @{ kind = 'codemodel'; version = 2 },
        @{ kind = 'cache'; version = 2 }) } | ConvertTo-Json -Depth 5
    [System.IO.File]::WriteAllText((Join-Path $queryDir 'query.json'), $query, (New-Object System.Text.UTF8Encoding($false)))

    $cfgArgs = @('-S', $fixture, '-B', $bdir, '-G', 'Ninja', "-DCMAKE_MAKE_PROGRAM=$NinjaExe",
        "-DCMAKE_TOOLCHAIN_FILE=$Toolchain", '-DANDROID_ABI=arm64-v8a', '-DANDROID_PLATFORM=android-26',
        '-DCMAKE_BUILD_TYPE=Release', "-DMCA_T07_CASE=$CaseName", '-DCMAKE_EXPORT_COMPILE_COMMANDS=ON')
    $cfgOut = @(& $CmakeExe @cfgArgs 2>&1 | ForEach-Object { "$_" })
    $cfgExit = $LASTEXITCODE

    $buildOut = @(& $CmakeExe '--build' $bdir 2>&1 | ForEach-Object { "$_" })
    $buildExit = $LASTEXITCODE

    $soPath = Join-Path $bdir 'libt07_gate.so'
    $exports = @(Get-DynamicExports -SoPath $soPath)

    # --- authoritative target graph (CMake File API) via T01's extractor -----
    $graphJson = Join-Path $EvidenceRoot "graph-$CaseName.json"
    $graphMd = Join-Path $EvidenceRoot "graph-$CaseName.md"
    $graphOut = @(& $extractTool -BuildDir $bdir -OutJson $graphJson -OutMd $graphMd 2>&1 | ForEach-Object { "$_" })
    $graphExit = $LASTEXITCODE

    # --- NEW check 1: source-set / required targets / required defines -------
    $covJson = Join-Path $EvidenceRoot "coverage-$CaseName.json"
    $covTxt = Join-Path $EvidenceRoot "coverage-$CaseName.txt"
    $covOut = @(& $coverageTool -TargetGraphJson $graphJson -SourceRoot $fixture `
        -CompileCommandsJson (Join-Path $bdir 'compile_commands.json') `
        -RequiredTargets @('t07_gate', 'mca_t07_sd_native') `
        -RequiredDefines @('t07_gate:MCA_T07_REQUIRED_BINDINGS=1') `
        -OutTxt $covTxt -OutJson $covJson 2>&1 | ForEach-Object { "$_" })
    $covExit = $LASTEXITCODE
    $cov = $null
    if (Test-Path -LiteralPath $covJson) { $cov = Get-Content -LiteralPath $covJson -Raw | ConvertFrom-Json }

    # --- NEW check 2: exact-signature JNI overload exports -------------------
    $jniJson = Join-Path $EvidenceRoot "jni-$CaseName.json"
    $jniTxt = Join-Path $EvidenceRoot "jni-$CaseName.txt"
    $jniOut = @(& $jniTool -KotlinRoots @($fixtureKotlin) -LibDir $bdir -OutTxt $jniTxt -OutJson $jniJson -RepoRoot $RepoRoot -LlvmNm $LlvmNm 2>&1 | ForEach-Object { "$_" })
    $jniExit = $LASTEXITCODE
    $jni = $null
    if (Test-Path -LiteralPath $jniJson) { $jni = Get-Content -LiteralPath $jniJson -Raw | ConvertFrom-Json }

    [pscustomobject]@{
        case = $CaseName
        configureExit = $cfgExit
        buildExit = $buildExit
        graphExit = $graphExit
        coverageExit = $covExit
        coverageVerdict = $(if ($cov) { $cov.verdict } else { 'NO_OUTPUT' })
        orphanSources = @(if ($cov) { $cov.orphanSources | ForEach-Object { $_.path } })
        missingTargets = @(if ($cov) { $cov.missingTargets })
        missingDefines = @(if ($cov) { $cov.missingDefines | ForEach-Object { $_.requirement } })
        coverageOut = @($covOut)
        jniExit = $jniExit
        jniVerdict = $(if ($jni) { $jni.verdict } else { 'NO_OUTPUT' })
        jniMissing = @(if ($jni) { $jni.declarations | Where-Object { $_.status -eq 'MISSING' } | ForEach-Object { $_.requiredSymbol } })
        jniLooseDivergences = @(if ($jni) { $jni.declarations | Where-Object { $_.status -eq 'MISSING' -and $_.looseMatcherWouldSayPresent } | ForEach-Object { $_.requiredSymbol } })
        exports = @($exports)
        buildOut = @($buildOut)
    }
}

$log = [System.Collections.Generic.List[string]]::new()
$log.Add('===== MCA T07 gate sensitivity (defect classes T01 does not cover) =====')
$log.Add("generatedUtc=$([datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.fffZ'))")
$log.Add("fixture=$fixture")
$log.Add("toolchain=$Toolchain")
$log.Add('')

$cases = @('OK', 'ORPHAN_TU', 'MISSING_TARGET', 'MACRO_FLIP', 'JNI_OVERLOAD')
$results = [System.Collections.Generic.List[object]]::new()
$okExports = $null

foreach ($c in $cases) {
    $r = Invoke-Case -CaseName $c
    if ($c -eq 'OK') { $okExports = @($r.exports) }

    $nativeOk = $r.exports -contains 'Java_com_muyuchat_t07_Gate_nativeOk'
    $nativeFeature = $r.exports -contains 'Java_com_muyuchat_t07_Gate_nativeFeature'
    $exportSetUnchanged = $false
    if ($okExports) {
        $exportDiff = @(Compare-Object -ReferenceObject $okExports -DifferenceObject @($r.exports))
        $exportSetUnchanged = ($exportDiff.Count -eq 0)
    }

    $detected = $false
    $expectation = ''
    $blindGates = ''
    switch ($c) {
        'OK' {
            $expectation = 'control: build+link green, all coverage assertions pass, exact-signature JNI gate passes'
            $detected = ($r.buildExit -eq 0) -and ($r.coverageVerdict -eq 'PASS') -and ($r.jniVerdict -eq 'PASS') -and $nativeOk -and $nativeFeature
            $blindGates = 'n/a (control case)'
        }
        'ORPHAN_TU' {
            $expectation = 'build+link green and export set intact (compile/link/export gates blind) while the source-set gate reports t07_dropped_tu.cpp'
            $detected = ($r.buildExit -eq 0) -and ($r.coverageVerdict -eq 'FAIL') -and ($r.orphanSources -contains 't07_dropped_tu.cpp') -and $nativeOk -and $nativeFeature
            $blindGates = "compiler/linker exit=$($r.buildExit); export gate sees nativeOk=$nativeOk nativeFeature=$nativeFeature"
        }
        'MISSING_TARGET' {
            $expectation = 'build+link green while the required-target assertion reports mca_t07_sd_native absent'
            $detected = ($r.buildExit -eq 0) -and ($r.coverageVerdict -eq 'FAIL') -and ($r.missingTargets -contains 'mca_t07_sd_native') -and $nativeOk -and $nativeFeature
            $blindGates = "compiler/linker exit=$($r.buildExit); export set unchanged=$exportSetUnchanged"
        }
        'MACRO_FLIP' {
            $expectation = 'build+link green AND dynamic export set byte-identical, only the required-define assertion reports MCA_T07_REQUIRED_BINDINGS=1 missing'
            $detected = ($r.buildExit -eq 0) -and ($r.coverageVerdict -eq 'FAIL') -and ($r.missingDefines -contains 't07_gate:MCA_T07_REQUIRED_BINDINGS=1') -and $exportSetUnchanged
            $blindGates = "compiler/linker exit=$($r.buildExit); export set identical to OK=$exportSetUnchanged; JNI gate=$($r.jniVerdict)"
        }
        'JNI_OVERLOAD' {
            $expectation = 'build+link green while the exact-signature JNI gate reports Java_com_muyuchat_t07_Gate_nativeOverloaded__I missing and the loose __* matcher diverges'
            $detected = ($r.buildExit -eq 0) -and ($r.jniVerdict -eq 'FAIL') -and ($r.jniMissing -contains 'Java_com_muyuchat_t07_Gate_nativeOverloaded__I') -and (@($r.jniLooseDivergences).Count -gt 0)
            $blindGates = "compiler/linker exit=$($r.buildExit); __J export present=$($r.exports -contains 'Java_com_muyuchat_t07_Gate_nativeOverloaded__J')"
        }
    }

    $log.Add("--- case $c : detected=$detected")
    $log.Add("    configureExit=$($r.configureExit) buildExit=$($r.buildExit) graphExit=$($r.graphExit)")
    $log.Add("    coverage: verdict=$($r.coverageVerdict) exit=$($r.coverageExit) orphans=[$((@($r.orphanSources) -join ','))] missingTargets=[$((@($r.missingTargets) -join ','))] missingDefines=[$((@($r.missingDefines) -join ','))]")
    $log.Add("    jni: verdict=$($r.jniVerdict) exit=$($r.jniExit) missing=[$((@($r.jniMissing) -join ','))] looseMatcherDivergences=[$((@($r.jniLooseDivergences) -join ','))]")
    $log.Add("    dynamic exports ($(@($r.exports).Count)): $((@($r.exports) -join ', '))")
    $log.Add("    blind competing gates: $blindGates")
    $log.Add("    expectation: $expectation")
    $log.Add('')

    $results.Add([pscustomobject]@{
        case = $c
        configureExit = $r.configureExit
        buildExit = $r.buildExit
        coverageVerdict = $r.coverageVerdict
        coverageExit = $r.coverageExit
        orphanSources = @($r.orphanSources)
        missingTargets = @($r.missingTargets)
        missingDefines = @($r.missingDefines)
        jniVerdict = $r.jniVerdict
        jniExit = $r.jniExit
        jniMissing = @($r.jniMissing)
        jniLooseMatcherDivergences = @($r.jniLooseDivergences)
        exports = @($r.exports)
        exportSetIdenticalToControl = $exportSetUnchanged
        blindCompetingGates = $blindGates
        expectation = $expectation
        detected = $detected
    })
}

# --- (2) T01 driver failure-channel meta reproduction ----------------------
$meta = [ordered]@{ ran = $false }
if (-not $SkipT01MetaRepro) {
    $metaEvidence = Join-Path $EvidenceRoot 't01-meta'
    $metaBuild = Join-Path $BuildRoot 't01-meta'
    $metaOut = @(& $t01Driver -Toolchain 'D:\nonexistent\android.toolchain.cmake' -RepoRoot $RepoRoot -BuildRoot $metaBuild -EvidenceRoot $metaEvidence -CmakeExe $CmakeExe -NinjaExe $NinjaExe -LlvmNm $LlvmNm 2>&1 | ForEach-Object { "$_" })
    $metaExit = $LASTEXITCODE
    $metaTxt = Join-Path $metaEvidence 'gate-negative-results.txt'
    $metaJsonPath = Join-Path $metaEvidence 'gate-negative-results.json'
    $metaAllDetected = $null
    $metaFalsePasses = @()
    if (Test-Path -LiteralPath $metaJsonPath) {
        $mj = Get-Content -LiteralPath $metaJsonPath -Raw | ConvertFrom-Json
        $metaAllDetected = $mj.allDefectsDetected
        $metaFalsePasses = @($mj.cases | Where-Object { $_.detected -and $_.buildExit -ne 0 } | ForEach-Object { $_.case })
    }
    $meta = [ordered]@{
        ran = $true
        description = 'run-gate-negative-tests.ps1 executed with -Toolchain pointing at a nonexistent file: nothing can compile'
        processExitCode = $metaExit
        allDefectsDetected = $metaAllDetected
        casesFalselyReportedDetected = @($metaFalsePasses)
        falseVerdictButExitZero = (($metaAllDetected -eq $false) -and ($metaExit -eq 0))
        evidenceTxt = $metaTxt
        stdoutTail = @($metaOut | Select-Object -Last 12)
    }
    $log.Add('--- T01 driver failure-channel meta reproduction ---')
    $log.Add("    processExitCode=$metaExit allDefectsDetected=$metaAllDetected")
    $log.Add("    casesFalselyReportedDetected=[$((@($metaFalsePasses) -join ','))]")
    $log.Add("    NOTE: the non-zero exit code here is incidental - it is the stale exit code of the last failed cmake --build, not a designed verdict channel.")
    $log.Add('')

    # Second reproduction, ISOLATED to the missing failure channel: break only the
    # llvm-nm path.  Every cmake invocation succeeds, the control case is reported
    # not-detected (allDefectsDetected=False), and the process still exits 0.
    $meta2Evidence = Join-Path $EvidenceRoot 't01-meta-nm'
    $meta2Build = Join-Path $BuildRoot 't01-meta-nm'
    $meta2Out = @(& $t01Driver -LlvmNm 'D:\nonexistent\llvm-nm.exe' -RepoRoot $RepoRoot -BuildRoot $meta2Build -EvidenceRoot $meta2Evidence -CmakeExe $CmakeExe -NinjaExe $NinjaExe -Toolchain $Toolchain 2>&1 | ForEach-Object { "$_" })
    $meta2Exit = $LASTEXITCODE
    $meta2AllDetected = $null
    $meta2JsonPath = Join-Path $meta2Evidence 'gate-negative-results.json'
    if (Test-Path -LiteralPath $meta2JsonPath) {
        $meta2AllDetected = (Get-Content -LiteralPath $meta2JsonPath -Raw | ConvertFrom-Json).allDefectsDetected
    }
    $meta2 = [ordered]@{
        ran = $true
        description = 'run-gate-negative-tests.ps1 executed with -LlvmNm pointing at a nonexistent file: every cmake invocation succeeds'
        processExitCode = $meta2Exit
        allDefectsDetected = $meta2AllDetected
        falseVerdictButExitZero = (($meta2AllDetected -eq $false) -and ($meta2Exit -eq 0))
        evidenceTxt = (Join-Path $meta2Evidence 'gate-negative-results.txt')
        stdoutTail = @($meta2Out | Select-Object -Last 12)
    }
    $log.Add('--- T01 driver missing failure channel (isolated) ---')
    $log.Add("    processExitCode=$meta2Exit allDefectsDetected=$meta2AllDetected")
    $log.Add("    a FAIL verdict with a zero process exit code: $($meta2.falseVerdictButExitZero)")
    $log.Add('')
    $meta['secondReproduction'] = $meta2
}

$allDetected = (@($results | Where-Object { -not $_.detected }).Count -eq 0)
$log.Add("===== SUMMARY cases=$($results.Count) allDetected=$allDetected =====")
foreach ($r in $results) { $log.Add(("{0,-16} buildExit={1,-3} coverage={2,-5} jni={3,-5} detected={4}" -f $r.case, $r.buildExit, $r.coverageVerdict, $r.jniVerdict, $r.detected)) }

$txtPath = Join-Path $EvidenceRoot 't07-gate-sensitivity.txt'
[System.IO.File]::WriteAllText($txtPath, (($log -join "`n") + "`n"), (New-Object System.Text.UTF8Encoding($false)))
$jsonPath = Join-Path $EvidenceRoot 't07-gate-sensitivity.json'
$json = [ordered]@{
    generatedUtc = [datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
    fixture = $fixture
    kotlinFixture = $fixtureKotlin
    toolchain = $Toolchain
    allDefectsDetected = $allDetected
    cases = @($results)
    t01DriverMetaReproduction = $meta
}
[System.IO.File]::WriteAllText($jsonPath, ($json | ConvertTo-Json -Depth 8), (New-Object System.Text.UTF8Encoding($false)))

if (Test-Path -LiteralPath $orphanTarget) { Remove-Item -LiteralPath $orphanTarget -Force }

Write-Output "wrote: $txtPath"
Write-Output "wrote: $jsonPath"
foreach ($r in $results) { Write-Output ("{0,-16} buildExit={1,-3} coverage={2,-5} jni={3,-5} detected={4}" -f $r.case, $r.buildExit, $r.coverageVerdict, $r.jniVerdict, $r.detected) }
Write-Output "allDefectsDetected=$allDetected"
if ($meta.ran) { Write-Output "t01Meta: processExitCode=$($meta.processExitCode) allDefectsDetected=$($meta.allDefectsDetected) falseVerdictButExitZero=$($meta.falseVerdictButExitZero)" }
if ($meta.ran -and $meta.secondReproduction) { Write-Output "t01Meta(noFailureChannel): processExitCode=$($meta.secondReproduction.processExitCode) allDefectsDetected=$($meta.secondReproduction.allDefectsDetected) falseVerdictButExitZero=$($meta.secondReproduction.falseVerdictButExitZero)" }

if (-not $allDetected) { exit 1 }
exit 0
