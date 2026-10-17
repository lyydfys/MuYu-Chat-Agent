# MCA repair runId=20261009-parity / T07 test-trust
# SOURCE-SET / REQUIRED-TARGET / REQUIRED-DEFINE coverage gate.
#
# Gap this closes (PLAN 9.3 "门禁对 target_sources、core/sd-native、真实 macros
# 的漏检"): a global compile+link+JNI-export gate is blind to three defect
# classes:
#   (a) a translation unit that exists on disk but is listed in no
#       target_sources and is not a JNI entry point (link succeeds, no export
#       disappears);
#   (b) a whole required library target that was never created - the real case
#       is core/sd-native's `mca_sd_native`, which lives in its own CMake
#       project and its own Gradle module, so a build that only configures
#       core/native/src/main/cpp cannot see it at all;
#   (c) a required capability macro that was configured OFF while the export set
#       stays identical (e.g. MCA_QNN_TYPED_BINDINGS_REQUIRED=OFF silently
#       degrading QNN to stub bindings).
#
# Evidence basis - per PLAN 9.3 the target/source graph must come from the build
# system, not from a regex over add_library():
#   * target existence / target defines : T01 extract-target-graph.ps1 output
#     (CMake File API codemodel-v2), consumed read-only;
#   * the authoritative absolute TU list and the compiled -D flags:
#     compile_commands.json (pass -CompileCommandsJson).  This is required in
#     practice: CMake 3.22 codemodel-v2 reports sources[].path RELATIVE to the
#     target's paths.source, and reports defines as objects {"define": "X=1"},
#     so a consumer that reads T01's graph json alone cannot resolve either.
#     Both limitations are recorded in T01-GATE-PATCH-SUGGESTIONS.md.
#
# Exit code: 0 = every assertion passed, 1 = at least one assertion failed.
# A gate that cannot fail the build is itself a gate bug (PLAN 9.3).
param(
    [Parameter(Mandatory = $true)][string]$TargetGraphJson,
    [Parameter(Mandatory = $true)][string]$SourceRoot,
    [string]$CompileCommandsJson = '',
    [string[]]$RequiredTargets = @(),
    [string[]]$RequiredDefines = @(),   # "target:NAME=VALUE" or "target:NAME"
    [string[]]$ExcludeDirNames = @('build', 'build-stable-diffusion', '.cxx', 'third_party', '.git', '_deps'),
    [string[]]$SourceExtensions = @('.cpp', '.cc', '.cxx'),
    [Parameter(Mandatory = $true)][string]$OutTxt,
    [Parameter(Mandatory = $true)][string]$OutJson
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $TargetGraphJson)) { throw "target graph json not found: $TargetGraphJson" }
if (-not (Test-Path -LiteralPath $SourceRoot)) { throw "source root not found: $SourceRoot" }
if ($CompileCommandsJson -and -not (Test-Path -LiteralPath $CompileCommandsJson)) {
    throw "compile_commands.json not found: $CompileCommandsJson"
}

function Normalize-Path {
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path)) { return '' }
    return ($Path -replace '\\', '/').TrimEnd('/').ToLowerInvariant()
}
function Get-DefineText {
    # codemodel-v2 defines are objects {"define": "NAME=VALUE"}; older/other
    # producers emit plain strings.  Accept both.
    param($Entry)
    if ($null -eq $Entry) { return '' }
    if ($Entry -is [string]) { return $Entry }
    if ($Entry.PSObject.Properties.Name -contains 'define') { return "$($Entry.define)" }
    return "$Entry"
}

$graph = Get-Content -LiteralPath $TargetGraphJson -Raw | ConvertFrom-Json
$targets = @($graph.targets)

# --- (b) required targets exist and actually own translation units -----------
$missingTargets = [System.Collections.Generic.List[string]]::new()
$targetsWithoutSources = [System.Collections.Generic.List[string]]::new()
foreach ($name in $RequiredTargets) {
    $t = @($targets | Where-Object { $_.name -eq $name })
    if ($t.Count -eq 0) { $missingTargets.Add($name); continue }
    if ([int]$t[0].sourceCount -le 0) { $targetsWithoutSources.Add($name) }
}

# --- (a) on-disk translation units vs configured translation units ----------
# Preferred basis: compile_commands.json (absolute paths, one entry per TU).
# Fallback: the graph's sources[] plus suffix resolution (see header caveat).
$configured = [System.Collections.Generic.HashSet[string]]::new()
$tuBasis = 'graph-sources'
$compileCommandCount = 0
$tuToDefines = @{}
if ($CompileCommandsJson) {
    $tuBasis = 'compile_commands.json'
    $cc = Get-Content -LiteralPath $CompileCommandsJson -Raw | ConvertFrom-Json
    foreach ($e in $cc) {
        $compileCommandCount++
        $n = Normalize-Path $e.file
        [void]$configured.Add($n)
        $defs = [System.Collections.Generic.List[string]]::new()
        $cmd = "$($e.command)"
        foreach ($m in [regex]::Matches($cmd, '(?:^|\s)-D([A-Za-z_][A-Za-z0-9_]*(?:=[^\s'']*)?)')) { $defs.Add($m.Groups[1].Value) }
        $tuToDefines[$n] = @($defs)
    }
} else {
    foreach ($t in $targets) {
        foreach ($s in @($t.sources)) {
            if ([string]::IsNullOrWhiteSpace($s)) { continue }
            [void]$configured.Add((Normalize-Path $s))
        }
    }
}

$sourceRootNorm = Normalize-Path $SourceRoot
$onDisk = [System.Collections.Generic.List[string]]::new()
$excludedFiles = [System.Collections.Generic.List[string]]::new()
Get-ChildItem -LiteralPath $SourceRoot -Recurse -File -ErrorAction SilentlyContinue | ForEach-Object {
    if ($SourceExtensions -notcontains $_.Extension.ToLowerInvariant()) { return }
    $rel = $_.FullName.Substring($SourceRoot.Length).TrimStart('\', '/')
    $parts = $rel -split '[\\/]'
    $inExcluded = $false
    foreach ($p in $parts) { if ($ExcludeDirNames -contains $p) { $inExcluded = $true; break } }
    if ($inExcluded) { $excludedFiles.Add($rel); return }
    $onDisk.Add($rel)
}

$orphanSources = [System.Collections.Generic.List[object]]::new()
$resolvedBySuffix = 0
foreach ($rel in $onDisk) {
    $full = Normalize-Path (Join-Path $SourceRoot $rel)
    if ($configured.Contains($full)) { continue }
    $hit = $false
    foreach ($c in $configured) {
        if ($c -eq (Normalize-Path $rel) -or $c.EndsWith('/' + (Normalize-Path $rel))) { $hit = $true; break }
    }
    if ($hit) { $resolvedBySuffix++; continue }
    $orphanSources.Add([pscustomobject]@{ path = $rel; absolute = $full })
}

# --- (c) required defines actually compiled ---------------------------------
$missingDefines = [System.Collections.Generic.List[object]]::new()
foreach ($req in $RequiredDefines) {
    $targetName = ''
    $defineSpec = $req
    if ($req -match '^([^:]+):(.+)$') { $targetName = $matches[1]; $defineSpec = $matches[2] }
    $wantName = $defineSpec
    $wantValue = $null
    if ($defineSpec -match '^([^=]+)=(.*)$') { $wantName = $matches[1]; $wantValue = $matches[2] }
    $wantName = $wantName.Trim()

    $observed = [System.Collections.Generic.List[string]]::new()
    $found = $false

    # (1) authoritative: the -D flags of the actual compile commands
    if ($CompileCommandsJson) {
        foreach ($tu in $tuToDefines.Keys) {
            foreach ($d in @($tuToDefines[$tu])) {
                $dn = $d; $dv = $null
                if ($d -match '^([^=]+)=(.*)$') { $dn = $matches[1]; $dv = $matches[2] }
                if ($dn.Trim() -ne $wantName) { continue }
                $observed.Add("$tu::$d")
                if ($null -eq $wantValue) { $found = $true }
                elseif ($dv -eq $wantValue) { $found = $true }
            }
        }
    }

    # (2) fallback: the target graph's compileGroups defines
    if (-not $found) {
        $candidates = @($targets)
        if ($targetName) { $candidates = @($targets | Where-Object { $_.name -eq $targetName }) }
        foreach ($t in $candidates) {
            foreach ($raw in @($t.defines)) {
                $d = Get-DefineText $raw
                if (-not $d) { continue }
                $dn = $d; $dv = $null
                if ($d -match '^([^=]+)=(.*)$') { $dn = $matches[1]; $dv = $matches[2] }
                if ($dn.Trim() -ne $wantName) { continue }
                $observed.Add("$($t.name)::$d")
                if ($null -eq $wantValue) { $found = $true }
                elseif ($dv -eq $wantValue) { $found = $true }
            }
        }
    }

    if (-not $found) {
        $missingDefines.Add([pscustomobject]@{ requirement = $req; target = $targetName; define = $wantName; wantedValue = $wantValue; observed = @($observed) })
    }
}

$ok = ($missingTargets.Count -eq 0) -and ($targetsWithoutSources.Count -eq 0) -and ($orphanSources.Count -eq 0) -and ($missingDefines.Count -eq 0)

# --- emit -------------------------------------------------------------------
$txt = [System.Collections.Generic.List[string]]::new()
$txt.Add('===== MCA T07 source-set / required-target / required-define coverage =====')
$txt.Add("generatedUtc=$([datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.fffZ'))")
$txt.Add("targetGraphJson=$TargetGraphJson")
$txt.Add("compileCommandsJson=$CompileCommandsJson")
$txt.Add("sourceRoot=$SourceRoot")
$txt.Add("tuBasis=$tuBasis compileCommandEntryCount=$compileCommandCount graphTargetCount=$($targets.Count) configuredTuCount=$($configured.Count) onDiskTuCount=$($onDisk.Count) excludedFiles=$($excludedFiles.Count) resolvedBySuffix=$resolvedBySuffix")
if ($tuBasis -ne 'compile_commands.json') {
    $txt.Add('WARNING: no compile DB supplied; graph sources[].path is relative in codemodel-v2 and was matched by suffix only - this basis is NOT authoritative.')
}
$txt.Add("requiredTargets=$($RequiredTargets -join ', ')")
$txt.Add("requiredDefines=$($RequiredDefines -join ', ')")
$txt.Add('')
$txt.Add("--- missing required targets ($($missingTargets.Count)) ---")
foreach ($m in $missingTargets) { $txt.Add("  MISSING_TARGET $m") }
$txt.Add("--- required targets with zero configured TUs ($($targetsWithoutSources.Count)) ---")
foreach ($m in $targetsWithoutSources) { $txt.Add("  EMPTY_TARGET $m") }
$txt.Add("--- on-disk TUs in no target_sources ($($orphanSources.Count)) ---")
foreach ($o in $orphanSources) { $txt.Add("  ORPHAN_TU $($o.path)") }
$txt.Add("--- required defines not compiled as required ($($missingDefines.Count)) ---")
foreach ($d in $missingDefines) {
    $txt.Add("  MISSING_DEFINE $($d.requirement) observed=[$((@($d.observed) -join '; '))]")
}
$txt.Add('')
$txt.Add("verdict=$(if ($ok) { 'PASS' } else { 'FAIL' })")

$dir = Split-Path -Parent $OutTxt
if ($dir -and -not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
[System.IO.File]::WriteAllText($OutTxt, (($txt -join "`n") + "`n"), (New-Object System.Text.UTF8Encoding($false)))

$json = [ordered]@{
    generatedUtc = [datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
    targetGraphJson = $TargetGraphJson
    compileCommandsJson = $CompileCommandsJson
    sourceRoot = $SourceRoot
    tuBasis = $tuBasis
    compileCommandEntryCount = $compileCommandCount
    graphTargetCount = $targets.Count
    configuredTuCount = $configured.Count
    onDiskTuCount = $onDisk.Count
    resolvedBySuffix = $resolvedBySuffix
    requiredTargets = @($RequiredTargets)
    requiredDefines = @($RequiredDefines)
    missingTargets = @($missingTargets)
    targetsWithoutSources = @($targetsWithoutSources)
    orphanSources = @($orphanSources)
    missingDefines = @($missingDefines)
    excludedFiles = @($excludedFiles)
    verdict = $(if ($ok) { 'PASS' } else { 'FAIL' })
}
$jdir = Split-Path -Parent $OutJson
if ($jdir -and -not (Test-Path -LiteralPath $jdir)) { New-Item -ItemType Directory -Force -Path $jdir | Out-Null }
[System.IO.File]::WriteAllText($OutJson, ($json | ConvertTo-Json -Depth 8), (New-Object System.Text.UTF8Encoding($false)))

Write-Output "wrote: $OutTxt"
Write-Output "wrote: $OutJson"
Write-Output "tuBasis=$tuBasis missingTargets=$($missingTargets.Count) emptyTargets=$($targetsWithoutSources.Count) orphanTus=$($orphanSources.Count) missingDefines=$($missingDefines.Count) verdict=$($json.verdict)"
foreach ($o in $orphanSources) { Write-Output "ORPHAN_TU: $($o.path)" }
foreach ($m in $missingTargets) { Write-Output "MISSING_TARGET: $m" }
foreach ($d in $missingDefines) { Write-Output "MISSING_DEFINE: $($d.requirement)" }

if (-not $ok) { exit 1 }
