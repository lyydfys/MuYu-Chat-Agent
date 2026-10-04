<#
.SYNOPSIS
    Fast code-layer verification for a pure-Kotlin change.

.DESCRIPTION
    The daily feedback loop for refactoring work. This is NOT the release path: `scripts\build.ps1`
    stays the strict full-APK build (with `--rerun-tasks`) whose job is to make sure a QAIRT SDK path
    change cannot ship stale native artifacts. Nothing here weakens that; this script just does not
    run it.

    It compiles the affected modules incrementally (no cache clearing, no forced reruns), runs either
    the whole module's unit tests or a --tests filter, and reports the outcome using BUILD STATUS plus
    a compiler-crash probe. The crash probe matters: a Kotlin codegen failure
    (`Exception while generating code for`) prints a bare `e:` line with no file prefix, so a scan for
    `e: file:///` reports success while the build is actually broken.

.PARAMETER Tests
    --tests filters. Repeatable. When omitted the whole `:app:testDebugUnitTest` task runs.

.PARAMETER FullTests
    Run `:core:engine:test` and `:api:local:test` as well as the app unit tests.

.PARAMETER SkipTests
    Compile only.

.EXAMPLE
    .\scripts\verify-code.ps1 -Tests '*KnowledgeLibraryOperationsTest'

.EXAMPLE
    .\scripts\verify-code.ps1 -SkipTests
#>
[CmdletBinding()]
param(
    [string[]] $Tests = @(),
    [switch] $FullTests,
    [switch] $SkipTests,
    [switch] $FeatureTests,
    [switch] $Rerun
)

# Native tools write progress to stderr; with 'Stop' a PowerShell host turns that into a terminating
# error even when the tool succeeded. Every Gradle call below is checked by exit code instead.
$ErrorActionPreference = 'Continue'

$repo = Split-Path -Parent $PSScriptRoot
$gradlew = Join-Path $repo 'gradlew.bat'
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'E:\model\toolchains\jdk-21\jdk-21.0.12.1+1' }
if (-not $env:MCA_QNN_SDK_ROOT) { $env:MCA_QNN_SDK_ROOT = 'E:\tools\qairt-sdk\2.45.0.260326' }
if (-not (Test-Path $env:MCA_QNN_SDK_ROOT)) {
    throw "MCA_QNN_SDK_ROOT does not exist: $env:MCA_QNN_SDK_ROOT"
}
if (-not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    throw "JAVA_HOME does not look like a JDK: $env:JAVA_HOME"
}

# Module -> compile task. All three are Android modules (the two libraries have debug/release
# variants too), so compileKotlin is ambiguous; the variant-specific name is required.
$compileTasks = @(
    ':app:compileDebugKotlin',
    ':core:engine:compileDebugKotlin',
    ':api:local:compileDebugKotlin'
)

$failed = @()

# Gradle writes into a single shared `app/build`. Two concurrent invocations (two agents, or an agent
# and a human) corrupt each other's incremental caches: "Detected multiple Kotlin daemon sessions",
# "Could not delete .../kotlin-classes", "Could not close incremental caches". `--no-daemon` does not
# prevent that, because the Kotlin compiler runs its own daemon and both share the build directory.
# A named system mutex serialises every Gradle call from any process on this machine, so parallel
# work queues instead of failing. Wait up to 30 minutes, then fail loudly rather than corrupt.
$script:buildMutex = New-Object System.Threading.Mutex($false, 'Global\McaGradleBuildQueue')

function Invoke-Gradle {
    param([string[]] $Arguments, [string] $LogPath)
    $held = $false
    try {
        try {
            $held = $script:buildMutex.WaitOne([TimeSpan]::FromMinutes(30))
        } catch [System.Threading.AbandonedMutexException] {
            # A previous invocation (or a killed agent) terminated while holding the queue. The
            # wait still transfers ownership, so the documented recovery is to proceed.
            $held = $true
        }
        if (-not $held) {
            Write-Host '   could not acquire the build queue within 30 minutes' -ForegroundColor Red
            return 1
        }
        $all = @('-p', $repo) + $Arguments + @('--no-daemon', '--console=plain')
    # No --rerun-tasks and no clean on purpose: Kotlin/Gradle incrementality and the build cache are
    # what make this the fast loop. The strict path lives in build.ps1.
        & $gradlew @all > $LogPath 2>&1
        return $LASTEXITCODE
    } finally {
        if ($held) { $script:buildMutex.ReleaseMutex() }
    }
}

Write-Host '== compile (incremental) ==' -ForegroundColor Cyan
Write-Host ('   ' + ($compileTasks -join ', '))
# Unique per run: several agents may run this script at once, and a shared path makes them
# clobber each other's log (observed).
$runTag = "$PID-" + (Get-Date -Format 'HHmmssfff')
$compileLog = Join-Path $env:TEMP "mca-verify-compile-$runTag.log"
$code = Invoke-Gradle -Arguments $compileTasks -LogPath $compileLog
$text = Get-Content $compileLog -Raw -ErrorAction SilentlyContinue

$crashed = $text -match 'Exception while generating code for'
$succeeded = $text -match 'BUILD SUCCESSFUL'
$taskFailures = ([regex]::Matches($text, '> Task \S+ FAILED')).Count

if ($crashed) {
    Write-Host '   COMPILER CRASH: the Kotlin backend could not generate code' -ForegroundColor Red
    foreach ($m in [regex]::Matches($text, 'FUN name:(\w+)')) {
        Write-Host ('      crashing method: ' + $m.Groups[1].Value) -ForegroundColor Red
    }
    Write-Host '      A method is at the JVM 64 KB bytecode limit. Extract its body into a separate' -ForegroundColor Yellow
    Write-Host '      suspend function; see commit d4bedf1b for the loadModel precedent.' -ForegroundColor Yellow
    $failed += 'compile-crash'
} elseif (-not $succeeded -or $taskFailures -gt 0) {
    Write-Host "   BUILD FAILED ($taskFailures task failure(s))" -ForegroundColor Red
    foreach ($line in ($text -split "`n" | Where-Object { $_ -match '^e: ' } | Select-Object -First 15)) {
        Write-Host ('      ' + $line.Trim()) -ForegroundColor Red
    }
    $failed += 'compile-error'
} else {
    Write-Host '   ok' -ForegroundColor Green
}

if (-not $SkipTests -and $failed.Count -eq 0) {
    Write-Host '== tests ==' -ForegroundColor Cyan
    # `-FeatureTests` runs the chat module's own test source set. It has to be its own Gradle
    # invocation: `--tests` filters are global to the whole invocation, so combining a chat-only
    # filter with :app/:core/:api would make every other task fail with "No tests found".
    # The @(...) wrapper is required: PowerShell unrolls a one-element array returned from an
    # if-expression into a string, and the later `+=` would then concatenate instead of append
    # (observed: -FullTests passed one glued ':app:testDebugUnitTest:core:engine:...' argument).
    $testArgs = @(if ($FeatureTests) {
        ':feature:chat:testDebugUnitTest'
    } else {
        ':app:testDebugUnitTest'
    })
    if ($Tests.Count -gt 0) {
        foreach ($t in $Tests) { $testArgs += @('--tests', $t) }
        Write-Host ('   filter: ' + ($Tests -join ', '))
    } else {
        Write-Host '   filter: (whole module)'
    }
    if ($FullTests) {
        # The feature modules ship their own test source sets, and three of the tests in
        # :feature:chat read ChatScreen.kt as source text. Running only :app would silently skip
        # 351 tests -- including exactly the anchors a presentation refactor can break.
        $testArgs += @(
            ':core:engine:testDebugUnitTest',
            ':api:local:testDebugUnitTest',
            ':feature:chat:testDebugUnitTest',
            ':feature:agent:testDebugUnitTest',
            ':feature:modelhub:testDebugUnitTest',
            ':feature:settings:testDebugUnitTest'
        )
        # NOTE: `--tests` is global to a Gradle invocation, so only pass filters when a single test
        # task is selected. `-FullTests` therefore rejects `-Tests` rather than silently reporting
        # "no tests found" for six of the seven tasks.
        if ($Tests.Count -gt 0) {
            Write-Host '   ERROR: -FullTests cannot be combined with -Tests (Gradle --tests is invocation-global).' -ForegroundColor Red
            Write-Host '          Use -FeatureTests for chat-module anchors, or -Tests alone for :app.' -ForegroundColor Red
            exit 2
        }
        Write-Host '   plus :core:engine, :api:local and the four :feature:* test tasks'
    }
    $testLog = Join-Path $env:TEMP "mca-verify-test-$runTag.log"
    if ($Rerun) {
        # `-Rerun` forces a real execution even when Gradle considers the test tasks up-to-date
        # (up-to-date state is a valid "still passing" signal, but a baseline freeze needs fresh
        # report files). Two dead ends are recorded here so they are not retried:
        #  * `--rerun-tasks` reruns the *whole* task graph, including :core:native's CMake /
        #    llama.cpp build -- a multi-tens-of-minutes native rebuild a test freeze must not
        #    trigger (observed and aborted);
        #  * `--rerun` with six test tasks in one invocation re-executed only one of them (the
        #    last; the other five modules kept their old report files -- XML mtimes proved it).
        # So each selected test task gets its own invocation with `--rerun`, which re-executes
        # just that task while its dependencies stay incremental.
        $taskNames = @($testArgs | Where-Object { $_ -like ':*' })
        $filterArgs = @($testArgs | Where-Object { $_ -notlike ':*' })
        $testCode = 0
        $merged = New-Object System.Text.StringBuilder
        foreach ($taskName in $taskNames) {
            $oneLog = "$testLog.$($taskName -replace '[:]', '_')"
            $code = Invoke-Gradle -Arguments (@($taskName) + $filterArgs + @('--rerun')) -LogPath $oneLog
            if (Test-Path $oneLog) {
                [void] $merged.AppendLine((Get-Content $oneLog -Raw -ErrorAction SilentlyContinue))
            }
            if ($code -ne 0) { $testCode = $code; break }
        }
        [System.IO.File]::WriteAllText($testLog, $merged.ToString())
    } else {
        $testCode = Invoke-Gradle -Arguments $testArgs -LogPath $testLog
    }
    $testText = Get-Content $testLog -Raw -ErrorAction SilentlyContinue
    if ($testText -match 'BUILD SUCCESSFUL' -and $testCode -eq 0) {
        Write-Host '   ok' -ForegroundColor Green
    } else {
        Write-Host '   TEST FAILURE' -ForegroundColor Red
        foreach ($line in ($testText -split "`n" | Where-Object { $_ -match 'FAILED|AssertionError|expected:' } | Select-Object -First 15)) {
            Write-Host ('      ' + $line.Trim()) -ForegroundColor Red
        }
        $failed += 'tests'
    }

    $dir = if ($FeatureTests) {
        Join-Path $repo 'feature\chat\build\test-results\testDebugUnitTest'
    } else {
        Join-Path $repo 'app\build\test-results\testDebugUnitTest'
    }
    # When -FullTests runs the feature/library modules too, their reports live beside app's.
    $extraResultDirs = @()
    if ($FullTests) {
        $extraResultDirs = @('core\engine', 'api\local', 'feature\chat', 'feature\agent',
            'feature\modelhub', 'feature\settings') |
            ForEach-Object { Join-Path $repo "$_\build\test-results\testDebugUnitTest" } |
            Where-Object { Test-Path $_ }
    }
    # -FullTests produces reports in every module's own build directory; aggregate them all so the
    # printed totals describe the whole invocation instead of only :app.
    $countDirs = @($dir) + $extraResultDirs
    # Each JUnit XML nests <testsuite> elements; read only the outermost one per file so counts
    # are not double-counted.
    [int] $totalTests = 0
    [int] $totalFailures = 0
    [int] $totalErrors = 0
    [int] $totalSkipped = 0
    # Only count reports written by THIS run. Parallel agents share the report directory, so
    # counting every XML on disk reports another agent's (possibly stale) results as ours -
    # observed: a filtered run reported tests=14 from a different test class entirely.
    [datetime] $runStart = (Get-Date).AddMinutes(-20)
    $countedDirs = 0
    foreach ($countDir in $countDirs) {
        if (-not (Test-Path $countDir)) { continue }
        $countedDirs = $countedDirs + 1
        foreach ($file in Get-ChildItem $countDir -Filter '*.xml' | Where-Object { $_.LastWriteTime -gt $runStart }) {
            [string] $xml = [System.IO.File]::ReadAllText($file.FullName)
            [System.Text.RegularExpressions.Match] $suite = [regex]::Match($xml, '<testsuite\s[^>]*>')
            if (-not $suite.Success) { continue }
            [string] $head = $suite.Value
            $totalTests = $totalTests + [int] ([regex]::Match($head, 'tests="(\d+)"').Groups[1].Value)
            $totalFailures = $totalFailures + [int] ([regex]::Match($head, 'failures="(\d+)"').Groups[1].Value)
            $totalErrors = $totalErrors + [int] ([regex]::Match($head, 'errors="(\d+)"').Groups[1].Value)
            $totalSkipped = $totalSkipped + [int] ([regex]::Match($head, 'skipped="(\d+)"').Groups[1].Value)
        }
    }
    if ($countedDirs -gt 0) {
        Write-Host ("   reports (this run only): tests=$totalTests failures=$totalFailures errors=$totalErrors skipped=$totalSkipped")
        if ($totalFailures -gt 0 -or $totalErrors -gt 0) {
            foreach ($countDir in $countDirs) {
                if (-not (Test-Path $countDir)) { continue }
                foreach ($file in Get-ChildItem $countDir -Filter '*.xml') {
                    [string] $xml = [System.IO.File]::ReadAllText($file.FullName)
                    foreach ($m in [regex]::Matches($xml, '<testcase name="([^"]+)" classname="([^"]+)"[^>]*>\s*<(failure|error)')) {
                        Write-Host ('      FAILED ' + $m.Groups[2].Value + '.' + $m.Groups[1].Value) -ForegroundColor Red
                    }
                }
            }
        }
    }
}

Write-Host '== working tree ==' -ForegroundColor Cyan
$status = & git -C $repo status --porcelain
if (-not $status) {
    Write-Host '   (clean)'
} else {
    foreach ($line in $status) { Write-Host ('   ' + $line) }
    Write-Host ''
    Write-Host '   dependency direction: nothing under core/engine or api/local may import app internals.'
    Write-Host '   A new import across that line means the slice is not finished.'
}

# Keep the logs when something failed - they are the only record of why, and deleting them made a
# failing run undiagnosable.
if ($failed.Count -gt 0) {
    Write-Host "   compile log: $compileLog" -ForegroundColor Yellow
    if ($testLog -and (Test-Path $testLog)) { Write-Host "   test log:    $testLog" -ForegroundColor Yellow }
} else {
    Remove-Item $compileLog -ErrorAction SilentlyContinue
    if ($testLog) { Remove-Item $testLog -ErrorAction SilentlyContinue }
}

Write-Host ''
if ($failed.Count -gt 0) {
    Write-Host ('VERIFY FAILED: ' + ($failed -join ', ')) -ForegroundColor Red
    exit 1
}
Write-Host 'VERIFY OK (code layer only - this is not the release build)' -ForegroundColor Green
exit 0
