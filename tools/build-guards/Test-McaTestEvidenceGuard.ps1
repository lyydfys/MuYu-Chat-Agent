<#
.SYNOPSIS
  E01 test-evidence guard: binds test results to the current snapshot and the actual APK.

.DESCRIPTION
  Implements the "test results <-> artifacts binding" row of the S-grade plan (19.2). Evidence is refused
  (non-zero exit) when:

    * tests=0: no test was actually executed (empty or missing report), or every report is stale.
    * stale:  a report file is older than -FreshMinutes (it belongs to an earlier run/snapshot).
    * failures/errors > 0.
    * mandatory: a case from -RequiredCasesFile ("Class#name" or "Class", JSON array or {cases:[...]})
      is missing from the fresh reports.
    * skips:  the fresh skipped count exceeds -MaxSkipped (a new skip must be re-approved, not
      silently absorbed).
    * apk:    the APK sha256 does not match the sha256 recorded in the build record, or the build
      record is missing/does not declare an APK, or (when -ExpectedWorktreeSha256 is given) the
      record's sourceProvenance.worktreeSha256 does not match the frozen snapshot. A build-record
      PASS is evidence only for the worktree/APK it records - never for the current sources.

  Read-only: parses XML/JSON only; never stages, commits or publishes anything.

.EXIT CODES
  0 = evidence valid   1 = refused   2 = usage/IO error (fail closed)
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string[]]$ResultsDirs,
    [string]$RequiredCasesFile,
    [int]$FreshMinutes = 240,
    [int]$MaxSkipped = 8,
    [int]$MinCases = 0,
    [string]$ApkPath,
    [string]$BuildRecordPath,
    [string]$ExpectedWorktreeSha256,
    [string]$OutFile,
    [switch]$Quiet
)

$ErrorActionPreference = 'Stop'

$errors = New-Object System.Collections.Generic.List[string]

function Add-McaTegError { param([string]$Message) $errors.Add($Message); if (-not $Quiet) { [Console]::Error.WriteLine('REFUSE: ' + $Message) } }

# ------------------------------------------------------------------ required cases
$required = New-Object System.Collections.Generic.List[string]
if ($RequiredCasesFile) {
    if (-not (Test-Path -LiteralPath $RequiredCasesFile -PathType Leaf)) { Add-McaTegError "required-cases file not found: $RequiredCasesFile" }
    else {
        $rc = [System.IO.File]::ReadAllText($RequiredCasesFile) | ConvertFrom-Json
        if ($rc -is [System.Array]) { foreach ($c in $rc) { $required.Add([string]$c) } }
        elseif ($rc.cases) { foreach ($c in @($rc.cases)) { $required.Add([string]$c) } }
        else { Add-McaTegError "required-cases file '$RequiredCasesFile' has neither an array root nor a cases[] list" }
    }
}

# ------------------------------------------------------------------ collect fresh reports
$totals = [ordered]@{ tests = 0; failures = 0; errors = 0; skipped = 0 }
$freshFiles = New-Object System.Collections.Generic.List[string]
$staleFiles = New-Object System.Collections.Generic.List[string]
$observed = New-Object 'System.Collections.Generic.HashSet[string]'
$caseByClass = @{}
$cutoff = (Get-Date).ToUniversalTime().AddMinutes(-1 * [Math]::Abs($FreshMinutes))
$dirsFound = 0

foreach ($dir in @($ResultsDirs)) {
    if (-not (Test-Path -LiteralPath $dir -PathType Container)) { continue }
    $dirsFound++
    foreach ($file in @(Get-ChildItem -LiteralPath $dir -Filter '*.xml' -File -ErrorAction SilentlyContinue)) {
        $age = ($file.LastWriteTimeUtc)
        if ($age -lt $cutoff) { $staleFiles.Add($file.FullName); continue }
        $freshFiles.Add($file.FullName)
        try { $doc = New-Object System.Xml.XmlDocument; $doc.Load($file.FullName) } catch { Add-McaTegError "unparseable report: $($file.FullName)"; continue }
        $suites = @()
        if ($doc.DocumentElement.LocalName -eq 'testsuites') { $suites = @($doc.DocumentElement.SelectNodes('testsuite')) }
        elseif ($doc.DocumentElement.LocalName -eq 'testsuite') { $suites = @($doc.DocumentElement) }
        else { Add-McaTegError "unexpected report root <$($doc.DocumentElement.LocalName)> in $($file.FullName)"; continue }
        foreach ($suite in $suites) {
            $totals.tests    += [int]$suite.GetAttribute('tests')
            $totals.failures += [int]$suite.GetAttribute('failures')
            $totals.errors   += [int]$suite.GetAttribute('errors')
            $totals.skipped  += [int]$suite.GetAttribute('skipped')
            foreach ($tc in @($suite.SelectNodes('testcase'))) {
                $cn = $tc.GetAttribute('classname'); $nm = $tc.GetAttribute('name')
                [void]$observed.Add($cn + '#' + $nm)
                if (-not $caseByClass.ContainsKey($cn)) { $caseByClass[$cn] = New-Object System.Collections.Generic.List[string] }
                $caseByClass[$cn].Add($nm)
            }
        }
    }
}

if ($freshFiles.Count -eq 0) {
    Add-McaTegError ("no fresh test report: 0 fresh .xml under $($ResultsDirs -join '; ') (searched $dirsFound dir(s), $($staleFiles.Count) stale file(s) excluded, window ${FreshMinutes}min)")
}
if ($totals.tests -eq 0) {
    Add-McaTegError "tests=0 - no test was actually executed in the fresh reports; a green result would prove nothing"
}
if ($totals.failures -gt 0 -or $totals.errors -gt 0) {
    Add-McaTegError ("failures=$($totals.failures) errors=$($totals.errors) - evidence must be green")
}
if ($MinCases -gt 0 -and $totals.tests -lt $MinCases) {
    Add-McaTegError "tests=$($totals.tests) below -MinCases $MinCases"
}
if ($totals.skipped -gt $MaxSkipped) {
    Add-McaTegError "skipped=$($totals.skipped) exceeds -MaxSkipped $MaxSkipped (a new skip must be approved, not absorbed)"
}

# ------------------------------------------------------------------ mandatory cases
$missing = New-Object System.Collections.Generic.List[string]
foreach ($req in $required) {
    $req = $req.Trim(); if (-not $req) { continue }
    $hash = $req.IndexOf('#')
    if ($hash -gt 0) {
        $cls = $req.Substring(0, $hash); $name = $req.Substring($hash + 1)
        if (-not $observed.Contains($cls + '#' + $name)) { $missing.Add($req) }
    } else {
        if (-not $caseByClass.ContainsKey($req)) { $missing.Add($req) }
    }
}
if ($missing.Count -gt 0) {
    Add-McaTegError ("mandatory case(s) missing from the fresh reports: " + ($missing -join ', '))
}

# ------------------------------------------------------------------ APK binding
$apkInfo = [ordered]@{ checked = $false }
if ($ApkPath -or $BuildRecordPath) {
    if (-not $ApkPath -or -not $BuildRecordPath) { Add-McaTegError '-ApkPath and -BuildRecordPath must be given together'; }
    elseif (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) { Add-McaTegError "APK not found: $ApkPath" }
    elseif (-not (Test-Path -LiteralPath $BuildRecordPath -PathType Leaf)) { Add-McaTegError "build record not found: $BuildRecordPath" }
    else {
        $rec = [System.IO.File]::ReadAllText($BuildRecordPath) | ConvertFrom-Json
        $recSha = ''
        if ($rec.PSObject.Properties['apkSha256'] -and $rec.apkSha256) { $recSha = ([string]$rec.apkSha256).ToLower() }
        $actualSha = (Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash.ToLower()
        $apkInfo.checked = $true
        $apkInfo.recordSha256 = $recSha
        $apkInfo.actualSha256 = $actualSha
        $apkInfo.buildRunId = $rec.buildRunId
        $apkInfo.recordStatus = $rec.status
        if (-not $recSha) { Add-McaTegError "build record '$BuildRecordPath' does not declare apkSha256" }
        elseif ($recSha -ne $actualSha) { Add-McaTegError "APK sha256 mismatch: record=$recSha apk=$actualSha (the record describes a different APK)" }
        if ($rec.status -and $rec.status -ne 'passed') { Add-McaTegError "build record status is '$($rec.status)', not 'passed'" }
        $recWt = ''
        if ($rec.PSObject.Properties['sourceProvenance'] -and $rec.sourceProvenance -and $rec.sourceProvenance.PSObject.Properties['worktreeSha256']) { $recWt = ([string]$rec.sourceProvenance.worktreeSha256).ToLower() }
        $apkInfo.worktreeSha256 = $recWt
        if ($ExpectedWorktreeSha256) {
            if (-not $recWt) { Add-McaTegError "build record does not declare sourceProvenance.worktreeSha256; cannot bind it to the expected worktree" }
            elseif ($recWt -ne $ExpectedWorktreeSha256.ToLower()) { Add-McaTegError "build record worktreeSha256=$recWt does not match expected $($ExpectedWorktreeSha256.ToLower()) (the APK was not built from this snapshot)" }
        }
    }
}

# ------------------------------------------------------------------ verdict
$ok = ($errors.Count -eq 0)
$report = [pscustomobject]@{
    schema          = 'mca.test-evidence-guard/1'
    generatedUtc    = [datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
    resultsDirs     = @($ResultsDirs)
    freshFileCount  = $freshFiles.Count
    staleFileCount  = $staleFiles.Count
    staleFiles      = $staleFiles.ToArray()
    totals          = [pscustomobject]$totals
    requiredCount   = $required.Count
    missingRequired = $missing.ToArray()
    apk             = [pscustomobject]$apkInfo
    ok              = $ok
    errorCount      = $errors.Count
    errors          = $errors.ToArray()
}
if ($OutFile) {
    $dir = Split-Path -Parent $OutFile
    if ($dir -and -not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    [System.IO.File]::WriteAllText($OutFile, ($report | ConvertTo-Json -Depth 6), (New-Object System.Text.UTF8Encoding($false)))
}
if (-not $Quiet) {
    Write-Host ("test evidence: files fresh=$($freshFiles.Count) stale=$($staleFiles.Count); tests=$($totals.tests) failures=$($totals.failures) errors=$($totals.errors) skipped=$($totals.skipped) mandatory=$($required.Count) missing=$($missing.Count)")
    if ($apkInfo.checked) { Write-Host ("  apk binding: record=$($apkInfo.recordSha256) apk=$($apkInfo.actualSha256) runId=$($apkInfo.buildRunId)") }
}
if ($ok) { if (-not $Quiet) { Write-Host 'TEST EVIDENCE OK' }; exit 0 }
exit 1