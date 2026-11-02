<#
.SYNOPSIS
    Serial UI-quality verification runner for the V9 measurement facility.

.DESCRIPTION
    Runs the existing `scripts/verify-code.ps1` exactly as it is (compile + relevant unit tests),
    optionally runs a Macrobenchmark lane, and archives the raw evidence under
    artifacts/ui-quality/<runId>/ together with a RUN.md/RUN.json summary.

    This runner does NOT fake results:
      * every lane records its real exit code and log path;
      * a lane that executed no test XML is FAIL or NOT_RUN, never PASS;
      * a skipped benchmark is NOT_RUN, never a passing performance result;
      * no device -> BLOCKED (exit 3), and an emulator is never substituted silently.

    Build-safety contract (V9 25.3):
      * verify-code.ps1 is invoked as a CHILD process (it acquires the shared build lock itself);
        this runner never holds the lock while that child runs, so there is no re-entrant lock.
      * lanes that call Gradle directly take the SAME McaBuildLock.psm1 lock in this process.
      * the worktree fingerprint is compared before/after; a difference is
        FAIL(input-changed-during-run).

.PARAMETER Lane
    One or more lanes, executed in this order: compile, unit, macro.
    Default: compile + unit.

.PARAMETER TestFilters
    For `unit`: verify-code.ps1 -Tests filters (app module scope).
    For `macro`: instrumentation class filters (`-Pandroid.testInstrumentationRunnerArguments.class`).

.PARAMETER FeatureTests
    For `unit`: pass -FeatureTests to verify-code.ps1 (feature:chat test task).

.PARAMETER Rerun
    For `unit`: pass -Rerun to verify-code.ps1 so up-to-date test tasks really execute.

.PARAMETER DeviceSerial
    Required for the `macro` lane. The device must be listed as `device` by adb; otherwise BLOCKED.

.PARAMETER Variant
    Macrobenchmark target build type name used to discover the connected task. Default: benchmark.

.PARAMETER Iterations
    Forwarded as `-Pandroid.testInstrumentationRunnerArguments.mca.iterations` when > 0.

.PARAMETER CompilationMode
    Forwarded as `-Pandroid.testInstrumentationRunnerArguments.mca.compilationMode`.

.PARAMETER GradleTasks
    Extra explicit Gradle tasks (string[]). They run in ONE invocation under the shared lock and
    their log is archived. Task names are never guessed: pass only names verified from
    `gradlew :module:tasks`. Requires network for `:benchmark` artifacts if a benchmark task is
    listed; the runner does not add `-Pmca.includeBenchmark` unless a task starts with ':benchmark'.

.PARAMETER OutputDirectory
    Evidence root. Default: <repo>/artifacts/ui-quality. Keep it under `artifacts/` (or outside the
    repo): the worktree fingerprint excludes `artifacts`, so the runner's own logs/traces cannot
    make a run look like an input change.

.PARAMETER RequireComposeReports
    Fail when no fresh Compose compiler report/metrics file was produced by this run.

.EXIT CODES
    0 = every requested lane PASS
    1 = FAIL (including input-changed-during-run and tests=0)
    3 = BLOCKED (missing lock/guard/JDK, missing device, or unresolved benchmark dependencies)
    Invalid parameter values are rejected by PowerShell binding before the runner starts.

.EXAMPLE
    .\scripts\verify-ui-quality.ps1

.EXAMPLE
    .\scripts\verify-ui-quality.ps1 -Lane compile,unit -TestFilters '*ChatProjection*' -Rerun

.EXAMPLE
    .\scripts\verify-ui-quality.ps1 -Lane macro -DeviceSerial R5CT12345 -Iterations 10
#>
[CmdletBinding()]
param(
    [ValidateSet('compile', 'unit', 'macro')]
    [string[]] $Lane = @('compile', 'unit'),
    [string[]] $TestFilters = @(),
    [switch] $FeatureTests,
    [switch] $Rerun,
    [string] $DeviceSerial = '',
    [ValidateSet('benchmark', 'debug', 'release')]
    [string] $Variant = 'benchmark',
    [int] $Iterations = 0,
    [ValidateSet('default', 'partial', 'partial-disable', 'partial-require', 'full', 'none')]
    [string] $CompilationMode = 'default',
    [string] $ReplayFixtureSha256 = '',
    [string[]] $GradleTasks = @(),
    [string] $OutputDirectory = '',
    [switch] $RequireComposeReports
)

# Native tools (adb/Gradle) may write progress to stderr; do not turn that into a terminating
# error. Every external call is judged by exit code + its own log.
$ErrorActionPreference = 'Continue'

$repo = Split-Path -Parent $PSScriptRoot
$verifyCode = Join-Path $repo 'scripts\verify-code.ps1'
$gradlew = Join-Path $repo 'gradlew.bat'

# --- fail-closed guards -----------------------------------------------------------------------
$guardDir = if ($env:MCA_BUILD_GUARDS_DIR) { $env:MCA_BUILD_GUARDS_DIR } else { Join-Path $repo 'tools\build-guards' }
$lockModule = Join-Path $guardDir 'McaBuildLock.psm1'
$fingerprintModule = Join-Path $guardDir 'McaWorktreeFingerprint.ps1'
if (-not (Test-Path -LiteralPath $lockModule) -or -not (Test-Path -LiteralPath $fingerprintModule)) {
    [Console]::Error.WriteLine("verify-ui-quality: build-guard modules missing under '$guardDir' (fail-closed, exit 3).")
    exit 3
}
if (-not (Test-Path -LiteralPath $verifyCode)) {
    [Console]::Error.WriteLine("verify-ui-quality: missing $verifyCode (fail-closed, exit 3).")
    exit 3
}
if (-not (Test-Path -LiteralPath $gradlew)) {
    [Console]::Error.WriteLine("verify-ui-quality: missing $gradlew (fail-closed, exit 3).")
    exit 3
}

Import-Module $lockModule -Force
. $fingerprintModule

# The direct Gradle lanes need the same toolchain defaults as scripts/verify-code.ps1. Keep this
# in one place: environment values set by the caller always win.
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'E:\model\toolchains\jdk-21\jdk-21.0.12.1+1' }
if (-not $env:MCA_QNN_SDK_ROOT) { $env:MCA_QNN_SDK_ROOT = 'E:\tools\qairt-sdk\2.45.0.260326' }

$runStart = Get-Date
$runId = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $PID
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $repo 'artifacts\ui-quality' }
if (-not [System.IO.Path]::IsPathRooted($OutputDirectory)) {
    $OutputDirectory = Join-Path $repo $OutputDirectory
}
$runDir = Join-Path $OutputDirectory $runId
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
$evidenceRoot = $runDir

$results = New-Object System.Collections.Generic.List[object]
$notes = New-Object System.Collections.Generic.List[string]

function Add-LaneResult {
    param(
        [string] $Name,
        [string] $Command,
        [Nullable[int]] $ExitCode,
        [ValidateSet('PASS', 'FAIL', 'BLOCKED', 'NOT_RUN')]
        [string] $Status,
        [string] $Reason,
        [string] $LogPath,
        [string[]] $Evidence = @(),
        [hashtable] $Conclusions = @{}
    )
    $row = [pscustomobject]@{
        lane     = $Name
        command  = $Command
        exitCode = $ExitCode
        status   = $Status
        reason   = $Reason
        log      = $LogPath
        evidence = @($Evidence)
        conclusions = $Conclusions
    }
    $results.Add($row) | Out-Null
    $color = switch ($Status) {
        'PASS' { 'Green' }
        'FAIL' { 'Red' }
        'BLOCKED' { 'Yellow' }
        default { 'Yellow' }
    }
    Write-Host ("   [{0}] {1}: {2}" -f $Status, $Name, $Reason) -ForegroundColor $color
    foreach ($key in $Conclusions.Keys) {
        Write-Host ("       {0}: {1}" -f $key, $Conclusions[$key]) -ForegroundColor DarkGray
    }
}

function Get-CurrentPowerShell {
    try { return (Get-Process -Id $PID -ErrorAction Stop).Path } catch { }
    $pwsh = Join-Path $PSHOME 'pwsh.exe'
    if (Test-Path -LiteralPath $pwsh) { return $pwsh }
    return (Join-Path $PSHOME 'powershell.exe')
}
$psExe = Get-CurrentPowerShell

function Copy-EvidenceTree {
    param(
        [string] $Source,
        [string] $Destination,
        [datetime] $Since
    )
    if (-not (Test-Path -LiteralPath $Source)) { return @() }
    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    $copied = New-Object System.Collections.Generic.List[string]
    foreach ($file in @(Get-ChildItem -LiteralPath $Source -Recurse -File -ErrorAction SilentlyContinue)) {
        if ($file.LastWriteTime -lt $Since) { continue }
        $relative = $file.FullName.Substring($Source.Length).TrimStart('\', '/')
        $target = Join-Path $Destination $relative
        $targetDir = Split-Path -Parent $target
        if (-not (Test-Path -LiteralPath $targetDir)) { New-Item -ItemType Directory -Force -Path $targetDir | Out-Null }
        Copy-Item -LiteralPath $file.FullName -Destination $target -Force
        $hash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
        $copied.Add(('{0} sha256={1} mtime={2:o}' -f $relative, $hash, $file.LastWriteTimeUtc)) | Out-Null
    }
    return $copied.ToArray()
}

function Measure-JUnitXml {
    param([string[]] $Directories, [datetime] $Since)
    $summary = [pscustomobject]@{ files = 0; tests = 0; failures = 0; errors = 0; skipped = 0; directories = @() }
    $xmlFiles = New-Object System.Collections.Generic.List[object]
    foreach ($dir in @($Directories)) {
        if (-not $dir -or -not (Test-Path -LiteralPath $dir)) { continue }
        $summary.directories += $dir
        foreach ($file in @(Get-ChildItem -LiteralPath $dir -Filter '*.xml' -File -Recurse -ErrorAction SilentlyContinue)) {
            if ($file.LastWriteTime -lt $Since) { continue }
            $xmlFiles.Add($file) | Out-Null
        }
    }
    $summary.files = $xmlFiles.Count
    foreach ($file in $xmlFiles) {
        $text = [System.IO.File]::ReadAllText($file.FullName)
        $suite = [regex]::Match($text, '<testsuite\s[^>]*>')
        if (-not $suite.Success) { continue }
        $head = $suite.Value
        $summary.tests += [int]([regex]::Match($head, 'tests="(\d+)"').Groups[1].Value)
        $summary.failures += [int]([regex]::Match($head, 'failures="(\d+)"').Groups[1].Value)
        $summary.errors += [int]([regex]::Match($head, 'errors="(\d+)"').Groups[1].Value)
        $summary.skipped += [int]([regex]::Match($head, 'skipped="(\d+)"').Groups[1].Value)
    }
    return [pscustomobject]@{
        files      = $summary.files
        tests      = $summary.tests
        failures   = $summary.failures
        errors     = $summary.errors
        skipped    = $summary.skipped
        directories = @($summary.directories)
        xmlFiles   = @($xmlFiles | ForEach-Object { $_.FullName })
    }
}

function Measure-MacrobenchmarkSample {
    param([string[]] $Directories, [datetime] $Since)
    $files = New-Object System.Collections.Generic.List[object]
    foreach ($dir in @($Directories)) {
        if (-not $dir -or -not (Test-Path -LiteralPath $dir)) { continue }
        foreach ($file in @(Get-ChildItem -LiteralPath $dir -Recurse -File -Filter '*.json' -ErrorAction SilentlyContinue)) {
            if ($file.LastWriteTime -lt $Since) { continue }
            $files.Add($file) | Out-Null
        }
    }
    $metricNames = New-Object System.Collections.Generic.List[string]
    $maxRuns = 0
    $parsedFiles = 0
    foreach ($file in $files) {
        $text = [System.IO.File]::ReadAllText($file.FullName)
        # Tolerant scan of the Macrobenchmark result JSON. The exact nesting has changed across
        # androidx.benchmark releases, so this counts per-metric "runs" arrays and the metric keys
        # that carry an aggregate. It proves "how many samples exist", never a performance verdict.
        $metricHit = $false
        foreach ($match in [regex]::Matches($text, '"([A-Za-z][A-Za-z0-9_]*)"\s*:\s*\{\s*"(?:minimum|median|maximum)"')) {
            $name = $match.Groups[1].Value
            if (-not $metricNames.Contains($name)) { $metricNames.Add($name) | Out-Null }
            $metricHit = $true
        }
        foreach ($match in [regex]::Matches($text, '"runs"\s*:\s*\[([^\]]*)\]')) {
            $body = $match.Groups[1].Value.Trim()
            $count = if ($body.Length -eq 0) { 0 } else { @($body -split ',').Count }
            if ($count -gt 0) { $metricHit = $true }
            if ($count -gt $maxRuns) { $maxRuns = $count }
        }
        if ($metricHit) { $parsedFiles++ }
    }
    return [pscustomobject]@{
        files        = @($files | ForEach-Object { $_.FullName })
        parsedFiles  = $parsedFiles
        metricNames  = @($metricNames.ToArray())
        maxIterations = $maxRuns
        supported    = ($parsedFiles -gt 0 -and $maxRuns -gt 0)
        scope        = 'Counts samples present in the raw Macrobenchmark JSON. It does not validate frame content, refresh rate or thresholds.'
    }
}

function Get-McaReplayFixtureFacts {
    param([string] $RepoRoot)
    $root = Join-Path $RepoRoot 'benchmark\src\main\assets\controlled-replay'
    $manifestPath = Join-Path $root 'manifest.json'
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
        return [pscustomobject]@{ available = $false; reason = 'controlled-replay manifest is missing (benchmark/src/main/assets/controlled-replay/manifest.json)' }
    }
    try { $manifest = [IO.File]::ReadAllText($manifestPath, [Text.Encoding]::UTF8) | ConvertFrom-Json }
    catch { return [pscustomobject]@{ available = $false; reason = ('controlled-replay manifest is not valid JSON: ' + $_.Exception.Message) } }
    if ([string]$manifest.schema -ne 'mca.controlled-replay.manifest/1') {
        return [pscustomobject]@{ available = $false; reason = ("controlled-replay manifest schema is '{0}', expected mca.controlled-replay.manifest/1" -f [string]$manifest.schema) }
    }
    $fixturePath = Join-Path $root ([string]$manifest.fixture)
    if (-not (Test-Path -LiteralPath $fixturePath -PathType Leaf)) {
        return [pscustomobject]@{ available = $false; reason = ('controlled-replay fixture is missing: ' + $fixturePath) }
    }
    $sha = (Get-FileHash -LiteralPath $fixturePath -Algorithm SHA256).Hash.ToLowerInvariant()
    $declared = ([string]$manifest.fixtureSha256).ToLowerInvariant()
    return [pscustomobject]@{
        available     = $true
        manifestPath  = $manifestPath
        fixturePath   = $fixturePath
        fixtureSha256 = $sha
        declaredSha256 = $declared
        hashMatches   = ($declared -match '^[0-9a-f]{64}$' -and $sha -eq $declared)
        cases         = @($manifest.cases)
        chain         = $manifest.chain
    }
}

function Get-GradleVerdict {
    param([string] $Text, [int] $ExitCode)
    if ($Text -match 'Exception while generating code for') {
        return [pscustomobject]@{ ok = $false; reason = 'Kotlin codegen crash (Exception while generating code for)' }
    }
    $failedTasks = ([regex]::Matches($Text, '> Task \S+ FAILED')).Count
    if ($failedTasks -gt 0) {
        return [pscustomobject]@{ ok = $false; reason = "Gradle reported $failedTasks failed task(s)" }
    }
    if ($ExitCode -ne 0 -or $Text -notmatch 'BUILD SUCCESSFUL') {
        return [pscustomobject]@{ ok = $false; reason = "exit=$ExitCode / no BUILD SUCCESSFUL in log" }
    }
    return [pscustomobject]@{ ok = $true; reason = 'BUILD SUCCESSFUL' }
}

function Invoke-GradleUnderLock {
    param(
        [string] $Name,
        [string[]] $Arguments,
        [string] $LockKind = 'test'
    )
    $logPath = Join-Path $evidenceRoot ("gradle-$Name.log")
    $lock = $null
    try {
        try {
            $lock = Enter-McaBuildLock -Kind $LockKind -RetrySeconds 1800 -RetryIntervalSeconds 2 `
                -CommandLine ("verify-ui-quality.ps1 $Name " + ($Arguments -join ' '))
        } catch {
            Add-LaneResult -Name $Name -Command ('gradlew ' + ($Arguments -join ' ')) -ExitCode $null `
                -Status 'BLOCKED' -Reason ('could not acquire shared build lock: ' + $_.Exception.Message) `
                -LogPath $null
            return $null
        }
        & $gradlew @Arguments *> $logPath
        $code = $LASTEXITCODE
    } finally {
        if ($lock) { try { Exit-McaBuildLock -Lock $lock | Out-Null } catch { } }
    }
    $text = Get-Content -LiteralPath $logPath -Raw -ErrorAction SilentlyContinue
    return [pscustomobject]@{ exitCode = $code; text = $text; log = $logPath }
}

Write-Host '== verify-ui-quality ==' -ForegroundColor Cyan
Write-Host ("   run id:     {0}" -f $runId)
Write-Host ("   evidence:   {0}" -f $evidenceRoot)
Write-Host ("   lanes:      {0}" -f ($Lane -join ', '))

$fpBefore = Get-McaWorktreeFingerprint -RepoRoot $repo
Write-Host ('   input fingerprint (before): ' + $fpBefore.fingerprint + ' (' + $fpBefore.files + ' dirty/untracked inputs)') -ForegroundColor DarkGray

# --- lane: compile ---------------------------------------------------------------------------
if ($Lane -contains 'compile') {
    Write-Host '== lane: compile (verify-code.ps1 -SkipTests) ==' -ForegroundColor Cyan
    $compileLog = Join-Path $evidenceRoot 'verify-code-compile.log'
    $compileStart = Get-Date
    & $psExe -NoProfile -ExecutionPolicy Bypass -File $verifyCode -SkipTests *> $compileLog
    $compileCode = $LASTEXITCODE
    $compileText = Get-Content -LiteralPath $compileLog -Raw -ErrorAction SilentlyContinue
    $status = 'FAIL'
    $reason = "exit=$compileCode"
    if ($compileCode -eq 0 -and $compileText -match 'VERIFY OK \(code layer only') {
        $status = 'PASS'
        $reason = 'VERIFY OK (code layer only)'
    } elseif ($compileCode -eq 3) {
        $status = 'BLOCKED'
        $reason = 'verify-code refused to start (guard/JDK/lock)'
    }
    if ($compileText -match 'input-changed-during-run') {
        $status = 'FAIL'
        $reason = $reason + '; input-changed-during-run'
    }
    Add-LaneResult -Name 'compile' -Command 'scripts/verify-code.ps1 -SkipTests' -ExitCode $compileCode `
        -Status $status -Reason $reason -LogPath $compileLog -Evidence @($compileLog)
}

# --- lane: unit ------------------------------------------------------------------------------
if ($Lane -contains 'unit') {
    Write-Host '== lane: unit (verify-code.ps1 + selected tests) ==' -ForegroundColor Cyan
    $unitArgs = @()
    if ($FeatureTests) { $unitArgs += '-FeatureTests' }
    if ($TestFilters.Count -gt 0) {
        foreach ($filter in $TestFilters) { $unitArgs += @('-Tests', $filter) }
    }
    if ($Rerun) { $unitArgs += '-Rerun' }
    $unitLog = Join-Path $evidenceRoot 'verify-code-unit.log'
    $unitStart = Get-Date
    & $psExe -NoProfile -ExecutionPolicy Bypass -File $verifyCode @unitArgs *> $unitLog
    $unitCode = $LASTEXITCODE
    $unitText = Get-Content -LiteralPath $unitLog -Raw -ErrorAction SilentlyContinue
    $status = 'FAIL'
    $reason = "exit=$unitCode"
    if ($unitCode -eq 0 -and $unitText -match 'VERIFY OK \(code layer only') {
        $status = 'PASS'
        $reason = 'VERIFY OK (code layer only)'
    } elseif ($unitCode -eq 3) {
        $status = 'BLOCKED'
        $reason = 'verify-code refused to start (guard/JDK/lock)'
    }
    if ($unitText -match 'input-changed-during-run') {
        $status = 'FAIL'
        $reason = $reason + '; input-changed-during-run'
        $notes.Add('unit lane: production inputs changed while the run was executing.') | Out-Null
    }

    # Archive only reports written by THIS run; never count another agent's stale XML.
    $reportRoots = @(
        (Join-Path $repo 'app\build\test-results\testDebugUnitTest'),
        (Join-Path $repo 'feature\chat\build\test-results\testDebugUnitTest'),
        (Join-Path $repo 'feature\settings\build\test-results\testDebugUnitTest'),
        (Join-Path $repo 'feature\modelhub\build\test-results\testDebugUnitTest'),
        (Join-Path $repo 'feature\agent\build\test-results\testDebugUnitTest'),
        (Join-Path $repo 'core\engine\build\test-results\testDebugUnitTest'),
        (Join-Path $repo 'api\local\build\test-results\testDebugUnitTest')
    )
    $junit = Measure-JUnitXml -Directories $reportRoots -Since $unitStart.AddMinutes(-1)
    $unitEvidence = @($unitLog)
    foreach ($dir in $junit.directories) {
        if (-not (Test-Path -LiteralPath $dir)) { continue }
        $moduleLabel = $dir.Substring($repo.Length).TrimStart('\', '/') -replace '[\\/]', '-'
        $dest = Join-Path $evidenceRoot ('test-results\' + $moduleLabel)
        $copied = Copy-EvidenceTree -Source $dir -Destination $dest -Since $unitStart.AddMinutes(-1)
        $unitEvidence += $copied
    }
    if ($junit.tests -eq 0) {
        if ($status -eq 'PASS') { $status = 'FAIL' }
        $reason = $reason + '; tests=0 (no fresh test XML, a green result would prove nothing)'
    } else {
        $reason = $reason + ("; tests={0} failures={1} errors={2} skipped={3}" -f $junit.tests, $junit.failures, $junit.errors, $junit.skipped)
        if ($junit.failures -gt 0 -or $junit.errors -gt 0) { $status = 'FAIL' }
    }
    Add-LaneResult -Name 'unit' -Command ('scripts/verify-code.ps1 ' + ($unitArgs -join ' ')) -ExitCode $unitCode `
        -Status $status -Reason $reason -LogPath $unitLog -Evidence $unitEvidence
}

# --- archive Compose compiler reports/metrics -------------------------------------------------
$composeModules = @(
    @{ name = 'app'; path = 'app' },
    @{ name = 'feature-chat'; path = 'feature\chat' },
    @{ name = 'feature-settings'; path = 'feature\settings' },
    @{ name = 'feature-modelhub'; path = 'feature\modelhub' },
    @{ name = 'feature-agent'; path = 'feature\agent' },
    @{ name = 'core-designsystem'; path = 'core\designsystem' }
)
$composeFresh = 0
$composeTotal = 0
$composeFreshMetrics = 0
$composeConfigOk = 0
$composeConfigProblems = New-Object System.Collections.Generic.List[string]
$composeLines = New-Object System.Collections.Generic.List[string]
foreach ($module in $composeModules) {
    $gradleFile = Join-Path $repo ($module.path + '\build.gradle.kts')
    $configOk = $false
    if (Test-Path -LiteralPath $gradleFile) {
        $gradleText = [System.IO.File]::ReadAllText($gradleFile)
        $configOk = ($gradleText -match 'reportsDestination\s*=' -and $gradleText -match 'metricsDestination\s*=')
    }
    if ($configOk) { $composeConfigOk++ } else { $composeConfigProblems.Add($module.name) | Out-Null }
    $source = Join-Path $repo ($module.path + '\build\compose-compiler')
    if (-not (Test-Path -LiteralPath $source)) {
        $composeLines.Add(('{0}: config={1} reports=NONE' -f $module.name, $configOk)) | Out-Null
        continue
    }
    $dest = Join-Path $evidenceRoot ('compose-compiler\' + $module.name)
    $copied = Copy-EvidenceTree -Source $source -Destination $dest -Since $runStart.AddMinutes(-1)
    $moduleFiles = @(Get-ChildItem -LiteralPath $source -Recurse -File -ErrorAction SilentlyContinue)
    $moduleFresh = @($moduleFiles | Where-Object { $_.LastWriteTime -ge $runStart.AddMinutes(-1) })
    $metricsFiles = @($moduleFiles | Where-Object { $_.FullName -match '[\\/]metrics[\\/]' })
    $freshMetrics = @($metricsFiles | Where-Object { $_.LastWriteTime -ge $runStart.AddMinutes(-1) })
    $composeTotal += $moduleFiles.Count
    $composeFresh += $moduleFresh.Count
    $composeFreshMetrics += $freshMetrics.Count
    $composeLines.Add(('{0}: config={1} files={2} fresh={3} metricsFiles={4} metricsFresh={5} archived={6}' -f
        $module.name, $configOk, $moduleFiles.Count, $moduleFresh.Count, $metricsFiles.Count, $freshMetrics.Count, $copied.Count)) | Out-Null
}
Write-Host '== Compose compiler reports ==' -ForegroundColor Cyan
foreach ($line in $composeLines) { Write-Host ('   ' + $line) }
# `metrics/reports exist` only proves a file is on disk, never that the stability/skippable analysis
# was reviewed. Report the configuration fact separately from the review fact.
$notes.Add(('Compose compiler reports: reportsDestination/metricsDestination configured in {0}/{1} modules (missing: {2}).' -f
    $composeConfigOk, $composeModules.Count, (($composeConfigProblems -join ', ') -replace '^$', 'none'))) | Out-Null
$notes.Add('Compose metrics review (stability/skippable hotspots, long-list recomposition, layer reads) is NOT_RUN: this runner archives the files, it does not review them or produce a quality grade.') | Out-Null
if ($RequireComposeReports -and ($composeFresh -eq 0 -or $composeFreshMetrics -eq 0)) {
    Add-LaneResult -Name 'compose-reports' -Command '(compiler report archive)' -ExitCode $null -Status 'FAIL' `
        -Reason ("no fresh Compose compiler report/metrics file was produced by this run (freshReports={0} freshMetrics={1})" -f $composeFresh, $composeFreshMetrics) -LogPath $null
} elseif ($RequireComposeReports) {
    Add-LaneResult -Name 'compose-reports' -Command '(compiler report archive)' -ExitCode $null -Status 'PASS' `
        -Reason ("fresh report/metrics files: reports={0} metrics={1}; config modules={2}/{3}" -f $composeFresh, $composeFreshMetrics, $composeConfigOk, $composeModules.Count) -LogPath $null
}
if ($composeTotal -eq 0) {
    $notes.Add('Compose compiler reports: none found. Either Kotlin compilation did not run, the property disabled them, or the modules were not compiled.') | Out-Null
}

# --- lane: macro -----------------------------------------------------------------------------
if ($Lane -contains 'macro') {
    Write-Host '== lane: macro (Macrobenchmark) ==' -ForegroundColor Cyan
    $blockedReason = $null
    if (-not $DeviceSerial) {
        $blockedReason = 'no -DeviceSerial supplied; running on an arbitrary/attached emulator is not acceptable device evidence'
    }
    $adb = 'adb'
    $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
    if (-not $adbCommand -and $env:ANDROID_HOME) {
        $candidate = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
        if (Test-Path -LiteralPath $candidate) { $adb = $candidate }
    }
    if (-not $blockedReason) {
        $devices = & $adb devices 2>$null
        $connected = @($devices | Where-Object { $_ -match ('^\s*' + [regex]::Escape($DeviceSerial) + '\s+device\s*$') })
        if ($connected.Count -eq 0) {
            $blockedReason = "device '$DeviceSerial' is not listed as 'device' by adb (BLOCKED; emulator fallback deliberately not used)"
        }
    }

    $macroStatus = 'BLOCKED'
    $macroReason = $blockedReason
    $macroExit = $null
    $macroEvidence = @()
    # Three separate conclusions. exit code 0 only ever satisfies `execution`.
    $execution = 'BLOCKED'
    $sampleValidity = 'NOT_RUN'
    $performanceAcceptance = 'NOT_EVALUATED'
    $sampleNotes = New-Object System.Collections.Generic.List[string]
    if (-not $blockedReason) {
        # Discover the real connected task instead of trusting a documented name.
        $tasks = Invoke-GradleUnderLock -Name 'benchmark-tasks' -Arguments @(
            '-p', $repo, ':benchmark:tasks', '--all', '-Pmca.includeBenchmark=true', '--console=plain'
        ) -LockKind 'other'
        if ($null -eq $tasks) {
            $macroReason = 'could not acquire the shared build lock for :benchmark:tasks'
            $execution = 'BLOCKED'
        } else {
            $taskText = [string]$tasks.text
            if ($tasks.exitCode -ne 0 -or $taskText -notmatch 'BUILD SUCCESSFUL') {
                if ($taskText -match 'Could not resolve|No cached version|Could not find androidx\.benchmark') {
                    $macroStatus = 'BLOCKED'
                    $execution = 'BLOCKED'
                    $macroReason = 'benchmark dependencies could not be resolved (dependency status: not verified; enable network and retry)'
                } else {
                    $macroStatus = 'FAIL'
                    $execution = 'FAIL'
                    $macroReason = 'gradle :benchmark:tasks failed; see gradle-benchmark-tasks.log'
                }
            } else {
                $suffix = (Get-Culture).TextInfo.ToTitleCase($Variant.ToLowerInvariant())
                $expected = 'connected' + $suffix + 'AndroidTest'
                $availableTasks = @([regex]::Matches($taskText, '(?m)^\s*(connected[A-Za-z]+AndroidTest)\b') |
                    ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
                if ($availableTasks -notcontains $expected) {
                    # No fallback: running connectedDebugAndroidTest while the operator asked for
                    # the benchmark variant would silently measure a different binary.
                    if ($availableTasks.Count -eq 0) {
                        $macroStatus = 'BLOCKED'
                        $execution = 'BLOCKED'
                        $macroReason = 'no connected*AndroidTest task exists; :benchmark was not configured (opt-in include or AGP variant missing)'
                    } else {
                        $macroStatus = 'NOT_RUN'
                        $execution = 'NOT_RUN'
                        $macroReason = ("requested variant '{0}' has no task '{1}'; refusing to fall back to: {2}" -f
                            $Variant, $expected, ($availableTasks -join ', '))
                        $sampleNotes.Add('No benchmark task ran: the requested variant task is absent and no substitute variant is accepted.') | Out-Null
                    }
                } else {
                    $resolvedIterations = if ($Iterations -gt 0) { $Iterations } else { 10 }
                    $macroArgs = @('-p', $repo, (':benchmark:' + $expected), '-Pmca.includeBenchmark=true', '--console=plain')
                    if ($TestFilters.Count -gt 0) {
                        $macroArgs += ('-Pandroid.testInstrumentationRunnerArguments.class=' + ($TestFilters -join ','))
                    }
                    $macroArgs += ('-Pandroid.testInstrumentationRunnerArguments.mca.iterations=' + $resolvedIterations)
                    $macroArgs += ('-Pandroid.testInstrumentationRunnerArguments.mca.compilationMode=' + $CompilationMode)
                    $previousSerial = $env:ANDROID_SERIAL
                    $env:ANDROID_SERIAL = $DeviceSerial
                    try {
                        $run = Invoke-GradleUnderLock -Name 'macro' -Arguments $macroArgs -LockKind 'test'
                    } finally {
                        $env:ANDROID_SERIAL = $previousSerial
                    }
                    if ($null -eq $run) {
                        $macroReason = 'could not acquire the shared build lock for the Macrobenchmark task'
                        $execution = 'BLOCKED'
                    } else {
                        $macroExit = $run.exitCode
                        if ([string]$run.text -match 'Could not resolve|No cached version|Could not find androidx\.benchmark') {
                            $macroStatus = 'BLOCKED'
                            $execution = 'BLOCKED'
                            $macroReason = 'benchmark dependencies could not be resolved at execution time (dependency status: not verified; enable network and retry)'
                            $macroEvidence = @($run.log)
                        } else {
                            $verdict = Get-GradleVerdict -Text ([string]$run.text) -ExitCode $run.exitCode
                            $macroEvidence = @($run.log)
                            $benchmarkResults = Join-Path $repo 'benchmark\build\test-results'
                            $benchmarkOutputs = Join-Path $repo 'benchmark\build\outputs'
                            $benchmarkAdditional = Join-Path $benchmarkOutputs 'connected_android_test_additional_output'
                            # Connected-test XML lives under outputs/androidTest-results on AGP 8.x;
                            # keep the unit-test path too so a layout change cannot hide the evidence.
                            $benchmarkResultRoots = @(
                                $benchmarkResults,
                                (Join-Path $benchmarkOutputs 'androidTest-results'),
                                (Join-Path $repo 'benchmark\build\reports\androidTests')
                            )
                            $junit = Measure-JUnitXml -Directories $benchmarkResultRoots -Since $runStart.AddMinutes(-1)
                            $xmlText = ''
                            foreach ($xmlFile in @($junit.xmlFiles)) {
                                try { $xmlText += [System.IO.File]::ReadAllText([string]$xmlFile) } catch { }
                            }
                            if ($xmlText -match 'INSTALL_FAILED|Failed to install') {
                                $macroStatus = 'BLOCKED'
                                $execution = 'BLOCKED'
                                $installDetail = [regex]::Match($xmlText, '(INSTALL_FAILED_[A-Z_]+)').Groups[1].Value
                                if (-not $installDetail) { $installDetail = 'install failed' }
                                $macroReason = ("benchmark target/test APK install was rejected by the device ({0}); no measurement ran" -f $installDetail)
                                $sampleNotes.Add('Device install rejection is an environment BLOCK, not a benchmark FAIL and not a sample.') | Out-Null
                            } elseif ($junit.tests -eq 0) {
                                $macroStatus = 'FAIL'
                                $execution = 'FAIL'
                                $macroReason = 'no fresh benchmark test XML; the benchmark task did not execute tests'
                            } elseif ($junit.skipped -ge $junit.tests) {
                                $macroStatus = 'NOT_RUN'
                                $execution = 'NOT_RUN'
                                $macroReason = 'all benchmark tests were skipped (fixture/device precondition); NOT a performance result'
                            } elseif (-not $verdict.ok) {
                                $macroStatus = 'FAIL'
                                $execution = 'FAIL'
                                $macroReason = $verdict.reason
                            } elseif ($junit.failures -gt 0 -or $junit.errors -gt 0) {
                                $macroStatus = 'FAIL'
                                $execution = 'FAIL'
                                $macroReason = ("benchmark failures={0} errors={1}" -f $junit.failures, $junit.errors)
                            } else {
                                $macroStatus = 'PASS'
                                $execution = 'PASS'
                                $macroReason = ("tests={0} failures=0 errors=0 skipped={1}" -f $junit.tests, $junit.skipped)
                            }

                            # --- conclusion 2: sampleValidity ---------------------------------
                            if ($execution -eq 'PASS') {
                                # Two distinct classes: definitively invalid samples (FAIL) and
                                # unprovable samples (NOT_RUN). Neither may be reported as PASS.
                                $invalidReasons = New-Object System.Collections.Generic.List[string]
                                $unprovenReasons = New-Object System.Collections.Generic.List[string]
                                if ($resolvedIterations -lt 10) {
                                    $invalidReasons.Add(("iterations={0} < 10 required valid iterations" -f $resolvedIterations)) | Out-Null
                                }
                                $requiresReplay = (@($TestFilters | Where-Object { $_ -match 'ControlledReplay' }).Count -gt 0)
                                if ($requiresReplay) {
                                    $fixtureFacts = Get-McaReplayFixtureFacts -RepoRoot $repo
                                    if (-not $fixtureFacts.available) {
                                        $invalidReasons.Add(('replay fixture unavailable: ' + [string]$fixtureFacts.reason)) | Out-Null
                                    } elseif (-not $fixtureFacts.hashMatches) {
                                        $invalidReasons.Add(('replay fixture SHA-256 does not match its manifest (actual={0} declared={1})' -f
                                            $fixtureFacts.fixtureSha256, $fixtureFacts.declaredSha256)) | Out-Null
                                    } elseif (-not $ReplayFixtureSha256) {
                                        $unprovenReasons.Add('replay scenario was selected but -ReplayFixtureSha256 was not supplied, so the executed fixture is not pinned') | Out-Null
                                    } elseif (([string]$ReplayFixtureSha256).ToLowerInvariant() -ne $fixtureFacts.fixtureSha256) {
                                        $invalidReasons.Add(('-ReplayFixtureSha256 {0} does not match fixture {1}' -f $ReplayFixtureSha256, $fixtureFacts.fixtureSha256)) | Out-Null
                                    }
                                    if ($fixtureFacts.available) {
                                        if (Test-Path -LiteralPath $fixtureFacts.manifestPath) { $macroEvidence += $fixtureFacts.manifestPath }
                                        if (Test-Path -LiteralPath $fixtureFacts.fixturePath) { $macroEvidence += $fixtureFacts.fixturePath }
                                    }
                                }
                                $sample = Measure-MacrobenchmarkSample -Directories @($benchmarkAdditional) -Since $runStart.AddMinutes(-1)
                                $sampleIterations = 0
                                if ($null -ne $sample) { $sampleIterations = [int]$sample.maxIterations }
                                if ($null -eq $sample -or -not $sample.supported) {
                                    $unprovenReasons.Add(('no parseable Macrobenchmark JSON under {0}; valid frames/iterations cannot be established' -f $benchmarkAdditional)) | Out-Null
                                } elseif ($sampleIterations -lt $resolvedIterations) {
                                    $invalidReasons.Add(('raw samples per metric={0} < requested iterations={1}' -f $sampleIterations, $resolvedIterations)) | Out-Null
                                }
                                if ($invalidReasons.Count -gt 0) {
                                    $sampleValidity = 'FAIL'
                                    $macroStatus = 'FAIL'
                                    $macroReason = $macroReason + '; sampleValidity=FAIL: ' + ($invalidReasons -join ' | ')
                                    foreach ($item in $invalidReasons) { $sampleNotes.Add($item) | Out-Null }
                                } elseif ($unprovenReasons.Count -gt 0) {
                                    $sampleValidity = 'NOT_RUN'
                                    $macroStatus = 'NOT_RUN'
                                    $macroReason = $macroReason + '; sampleValidity=NOT_RUN: ' + ($unprovenReasons -join ' | ')
                                    foreach ($item in $unprovenReasons) { $sampleNotes.Add($item) | Out-Null }
                                } else {
                                    $sampleValidity = 'PASS'
                                    $macroReason = $macroReason + ("; sampleValidity=PASS metricSamples={0} metrics={1}" -f
                                        $sampleIterations, ($sample.metricNames -join ','))
                                }
                                if ($sample) { $macroEvidence += @($sample.files) }
                            }

                            $copiedResults = Copy-EvidenceTree -Source $benchmarkResults -Destination (Join-Path $evidenceRoot 'macro\test-results') -Since $runStart.AddMinutes(-1)
                            $copiedOutputs = Copy-EvidenceTree -Source $benchmarkOutputs -Destination (Join-Path $evidenceRoot 'macro\outputs') -Since $runStart.AddMinutes(-1)
                            $macroEvidence += $copiedResults
                            $macroEvidence += $copiedOutputs
                            $macroReason = $macroReason + ("; archived test-results={0} outputs={1}" -f $copiedResults.Count, $copiedOutputs.Count)
                        }
                    }
                }
            }
        }
    }
    $resolvedIterationsForRecord = if ($Iterations -gt 0) { $Iterations } else { 10 }
    $macroConclusions = @{
        execution              = $execution
        sampleValidity         = $sampleValidity
        performanceAcceptance  = $performanceAcceptance
        iterationsRequested    = $resolvedIterationsForRecord
        compilationMode        = $CompilationMode
        thresholdJudgement     = 'NOT_EVALUATED: frame thresholds, refresh rate and thermal conditions are reviewed against raw traces by the performance task, not by this runner.'
    }
    Add-LaneResult -Name 'macro' -Command ('connected ' + $Variant + ' AndroidTest on ' + $DeviceSerial) -ExitCode $macroExit `
        -Status $macroStatus -Reason $macroReason -LogPath $null -Evidence $macroEvidence -Conclusions $macroConclusions
    if ($execution -eq 'PASS') {
        $sampleStatus = if ($sampleValidity -eq 'PASS') { 'PASS' } else { 'FAIL' }
        Add-LaneResult -Name 'macro-sample' -Command '(raw Macrobenchmark JSON review)' -ExitCode $null `
            -Status $sampleStatus -Reason ([string]$sampleValidity + ': ' + ($sampleNotes -join ' | ')) -LogPath $null
    }
    Add-LaneResult -Name 'macro-performance' -Command '(threshold acceptance)' -ExitCode $null `
        -Status 'NOT_RUN' -Reason $macroConclusions.thresholdJudgement -LogPath $null
}

# --- extra explicit Gradle tasks --------------------------------------------------------------
if ($GradleTasks.Count -gt 0) {
    Write-Host '== lane: explicit Gradle tasks ==' -ForegroundColor Cyan
    $needsBenchmark = @($GradleTasks | Where-Object { $_ -like ':benchmark*' }).Count -gt 0
    $taskArgs = @('-p', $repo) + $GradleTasks + @('--console=plain')
    if ($needsBenchmark) { $taskArgs += '-Pmca.includeBenchmark=true' }
    $run = Invoke-GradleUnderLock -Name 'tasks' -Arguments $taskArgs -LockKind 'other'
    if ($null -eq $run) {
        Add-LaneResult -Name 'gradle-tasks' -Command ($GradleTasks -join ' ') -ExitCode $null `
            -Status 'BLOCKED' -Reason 'could not acquire the shared build lock' -LogPath $null
    } else {
        $verdict = Get-GradleVerdict -Text ([string]$run.text) -ExitCode $run.exitCode
        $status = 'PASS'
        if (-not $verdict.ok) { $status = 'FAIL' }
        Add-LaneResult -Name 'gradle-tasks' -Command ($GradleTasks -join ' ') -ExitCode $run.exitCode `
            -Status $status -Reason $verdict.reason -LogPath $run.log -Evidence @($run.log)
    }
}

# --- fingerprint after + summary --------------------------------------------------------------
$fpAfter = Get-McaWorktreeFingerprint -RepoRoot $repo
$inputChanged = ($fpAfter.fingerprint -ne $fpBefore.fingerprint)
if ($inputChanged) {
    Add-LaneResult -Name 'input-fingerprint' -Command '(before/after)' -ExitCode $null `
        -Status 'FAIL' -Reason ('input-changed-during-run: before=' + $fpBefore.fingerprint + ' after=' + $fpAfter.fingerprint) `
        -LogPath $null
}

$failCount = @($results | Where-Object { $_.status -eq 'FAIL' }).Count
$blockedCount = @($results | Where-Object { $_.status -eq 'BLOCKED' }).Count
$notRunCount = @($results | Where-Object { $_.status -eq 'NOT_RUN' }).Count
$overall = 'PASS'
if ($failCount -gt 0) { $overall = 'FAIL' }
elseif ($blockedCount -gt 0) { $overall = 'BLOCKED' }
elseif ($notRunCount -gt 0) { $overall = 'NOT_RUN' }
elseif ($results.Count -eq 0) { $overall = 'NOT_RUN'; $notes.Add('No lane was requested; nothing was executed.') | Out-Null }

$head = (& git -C $repo rev-parse HEAD 2>$null | Select-Object -First 1)
$statusLines = New-Object System.Collections.Generic.List[string]
$statusLines.Add('# verify-ui-quality run ' + $runId) | Out-Null
$statusLines.Add('') | Out-Null
$statusLines.Add('- date: ' + (Get-Date).ToString('o'))
$statusLines.Add('- repo: ' + $repo)
$statusLines.Add('- HEAD: ' + ([string]$head).Trim())
$statusLines.Add('- overall: **' + $overall + '**')
$statusLines.Add('- input fingerprint before: ' + $fpBefore.fingerprint)
$statusLines.Add('- input fingerprint after:  ' + $fpAfter.fingerprint)
$statusLines.Add('- input changed during run: ' + $inputChanged)
$statusLines.Add('- lanes requested: ' + ($Lane -join ', '))
$statusLines.Add('')
$statusLines.Add('## Lane results')
$statusLines.Add('')
$statusLines.Add('| lane | status | exit | reason | log |')
$statusLines.Add('|---|---|---:|---|---|')
foreach ($row in $results) {
    $exitText = if ($null -eq $row.exitCode) { '-' } else { [string]$row.exitCode }
    $logText = if ($row.log) { $row.log } else { '-' }
    $statusLines.Add(('| {0} | {1} | {2} | {3} | {4} |' -f $row.lane, $row.status, $exitText, ($row.reason -replace '\|', '\|'), $logText))
}
$statusLines.Add('')
$statusLines.Add('## Three separate conclusions (macro)')
$statusLines.Add('')
$statusLines.Add('| lane | execution | sampleValidity | performanceAcceptance |')
$statusLines.Add('|---|---|---|---|')
foreach ($row in $results) {
    if ($row.lane -ne 'macro') { continue }
    $statusLines.Add(('| macro | {0} | {1} | {2} |' -f
        $row.conclusions.execution, $row.conclusions.sampleValidity, $row.conclusions.performanceAcceptance))
    $statusLines.Add('')
    $statusLines.Add('- iterations requested: ' + [string]$row.conclusions.iterationsRequested)
    $statusLines.Add('- compilation mode: ' + [string]$row.conclusions.compilationMode)
    $statusLines.Add('- threshold judgement: ' + [string]$row.conclusions.thresholdJudgement)
}
$statusLines.Add('')
$statusLines.Add('`execution` = the task ran and fresh XML exists. `sampleValidity` = the requested iterations, pinned fixture and raw per-metric samples are present. `performanceAcceptance` is never derived from exit code 0 and stays NOT_EVALUATED in this runner.')
$statusLines.Add('')
$statusLines.Add('## Compose compiler reports')
$statusLines.Add('')
$statusLines.Add('- config modules with reportsDestination+metricsDestination: ' + $composeConfigOk + '/' + $composeModules.Count)
foreach ($line in $composeLines) { $statusLines.Add('- ' + $line) }
$statusLines.Add('- metric review (stability/skippable analysis): NOT_RUN in this runner')
$statusLines.Add('')
$statusLines.Add('## Evidence')
$statusLines.Add('')
$statusLines.Add('- archive root: ' + $evidenceRoot)
$statusLines.Add('- test XML archived: lane `unit`/`macro` evidence lists above; counts parsed from fresh XML only')
$statusLines.Add('- raw Macrobenchmark JSON/Perfetto traces: `macro/outputs/` (if any)')
$statusLines.Add('')
$statusLines.Add('## Notes / honest limits')
$statusLines.Add('')
if ($notes.Count -eq 0) {
    $statusLines.Add('- (none recorded)')
} else {
    foreach ($note in $notes) { $statusLines.Add('- ' + $note) }
}
$statusLines.Add('- This runner does not judge user-perceived smoothness and does not create performance claims. Raw traces and recorded device/build conditions are the evidence.')
$statusLines.Add('')

$runMarkdown = Join-Path $evidenceRoot 'RUN.md'
try {
    [System.IO.File]::WriteAllText($runMarkdown, ($statusLines -join "`r`n"), (New-Object System.Text.UTF8Encoding($false)))
} catch {
    Write-Host ('   WARNING: could not write RUN.md: ' + $_.Exception.Message) -ForegroundColor Yellow
}

$runJson = [pscustomobject]@{
    schema      = 'mca.ui-quality-run/1'
    runId       = $runId
    generatedAt = (Get-Date).ToString('o')
    repo        = $repo
    head        = ([string]$head).Trim()
    overall     = $overall
    fingerprint = [pscustomobject]@{
        before      = $fpBefore.fingerprint
        after       = $fpAfter.fingerprint
        inputChanged = $inputChanged
    }
    composeCompiler = [pscustomobject]@{
        totalFiles = $composeTotal
        freshFiles = $composeFresh
        freshMetrics = $composeFreshMetrics
        configuredModules = $composeConfigOk
        moduleCount = $composeModules.Count
        modules    = $composeLines.ToArray()
        metricReview = 'NOT_RUN'
    }
    # PowerShell 5.1: `@($genericList)` can throw "Argument types do not match"; ToArray() is safe.
    lanes       = $results.ToArray()
}
$runJsonPath = Join-Path $evidenceRoot 'RUN.json'
try {
    [System.IO.File]::WriteAllText($runJsonPath, ($runJson | ConvertTo-Json -Depth 8), (New-Object System.Text.UTF8Encoding($false)))
} catch {
    Write-Host ('   WARNING: could not write RUN.json: ' + $_.Exception.Message) -ForegroundColor Yellow
}

Write-Host ''
Write-Host ('OVERALL: ' + $overall) -ForegroundColor $(if ($overall -eq 'PASS') { 'Green' } else { 'Red' })
Write-Host ('evidence: ' + $evidenceRoot)
Write-Host ('summary:  ' + $runMarkdown)

switch ($overall) {
    'PASS' { exit 0 }
    'BLOCKED' { exit 3 }
    default { exit 1 }
}
