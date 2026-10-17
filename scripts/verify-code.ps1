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
    [switch] $Rerun,
    [switch] $Changed,
    [string] $Since = 'HEAD'
)

# Native tools write progress to stderr; with 'Stop' a PowerShell host turns that into a terminating
# error even when the tool succeeded. Every Gradle call below is checked by exit code instead.
$ErrorActionPreference = 'Continue'

$repo = Split-Path -Parent $PSScriptRoot
# --- E01: unified build/test lock + run-input fingerprint (fail-closed) ---------------------
# Every Gradle call below takes the SAME exclusive lock as scripts/build.ps1; a missing guard
# module refuses the run instead of proceeding unguarded. The worktree fingerprint is compared
# before/after the run: if production inputs change while the run executes, the result belongs
# to no single snapshot and the run fails (`input-changed-during-run`).
$guardDir = if ($env:MCA_BUILD_GUARDS_DIR) { $env:MCA_BUILD_GUARDS_DIR } else { Join-Path $repo 'tools\build-guards' }
$mcaLockModule = Join-Path $guardDir 'McaBuildLock.psm1'
$mcaFingerprintModule = Join-Path $guardDir 'McaWorktreeFingerprint.ps1'
if (-not (Test-Path -LiteralPath $mcaLockModule) -or -not (Test-Path -LiteralPath $mcaFingerprintModule)) {
    [Console]::Error.WriteLine("verify-code: build-guard modules missing under '$guardDir' (fail-closed, exit 3). Expected McaBuildLock.psm1 and McaWorktreeFingerprint.ps1.")
    exit 3
}
Import-Module $mcaLockModule -Force
. $mcaFingerprintModule
$fpBefore = Get-McaWorktreeFingerprint -RepoRoot $repo
Write-Host ('   run-input fingerprint: ' + $fpBefore.fingerprint + ' (' + $fpBefore.files + ' dirty/untracked inputs)') -ForegroundColor DarkGray
try { Assert-McaBuildSlot | Out-Null } catch {
    [Console]::Error.WriteLine('verify-code: refusing to start - ' + $_.Exception.Message)
    exit 3
}
$gradlew = Join-Path $repo 'gradlew.bat'
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'E:\model\toolchains\jdk-21\jdk-21.0.12.1+1' }
if (-not $env:MCA_QNN_SDK_ROOT) { $env:MCA_QNN_SDK_ROOT = 'E:\tools\qairt-sdk\2.45.0.260326' }
if (-not (Test-Path $env:MCA_QNN_SDK_ROOT)) {
    throw "MCA_QNN_SDK_ROOT does not exist: $env:MCA_QNN_SDK_ROOT"
}
if (-not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    throw "JAVA_HOME does not look like a JDK: $env:JAVA_HOME"
}
# The LiteRT-LM SDK dependency ships class-file 65.0 (Java 21). Running the build on an older JDK
# fails five :core:engine tests with UnsupportedClassVersionError while every other module stays
# green -- observed 2026-10-05 from a shell whose JAVA_HOME was JDK 17, after the app module had
# already passed. Fail loudly instead of letting a half-green gate through.
$javaMajor = & (Join-Path $env:JAVA_HOME 'bin\java.exe') -version 2>&1 |
    Select-String -Pattern 'version "(\d+)' | ForEach-Object { [int] $_.Matches[0].Groups[1].Value } |
    Select-Object -First 1
if ($javaMajor -and $javaMajor -lt 21) {
    throw "JAVA_HOME is JDK $javaMajor; MCA requires JDK 21 (LiteRT-LM SDK classes are class-file 65.0). Unset JAVA_HOME to use the built-in jdk-21 default, or point it at the jdk-21 toolchain."
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
#
# E01: the former separate Global\McaGradleBuildQueue mutex is gone. Every Gradle call below takes
# the SAME file lock as scripts/build.ps1 (build\.mca-exclusive-build.lock via McaBuildLock.psm1).
# A live holder is named in the refusal, a dead holder is reclaimed, same-process nesting is
# refused, and a missing lock module was already refused above (exit 3) - so no Gradle call in
# this script can run unguarded.

function Invoke-Gradle {
    param([string[]] $Arguments, [string] $LogPath)
    $lock = $null
    try {
        try {
            $lock = Enter-McaBuildLock -Kind test -RetrySeconds 1800 -RetryIntervalSeconds 2 -CommandLine ("verify-code.ps1 " + ($Arguments -join ' '))
        } catch {
            Write-Host ('   could not acquire the shared build lock within 30 minutes: ' + $_.Exception.Message) -ForegroundColor Red
            return 1
        }
        # Serialization is the shared build lock acquired above; `--no-daemon` never prevented the Kotlin
        # daemon from sharing the build directory anyway. Reusing the resident Gradle daemon
        # removes the ~20-30s JVM cold start on every call, which the -Rerun lane (up to 7
        # separate invocations) pays repeatedly.
        $all = @('-p', $repo) + $Arguments + @('--console=plain')
    # No --rerun-tasks and no clean on purpose: Kotlin/Gradle incrementality and the build cache are
    # what make this the fast loop. The strict path lives in build.ps1.
        $started = Get-Date
        & $gradlew @all > $LogPath 2>&1
        $code = $LASTEXITCODE
        $elapsed = [int]((Get-Date) - $started).TotalSeconds
        Write-Host ("   gradle elapsed: {0}s" -f $elapsed) -ForegroundColor DarkGray
        return $code
    } finally {
        if ($lock) { try { Exit-McaBuildLock -Lock $lock | Out-Null } catch { } }
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
    # `-Changed` narrows the *selection* to the modules touched since `-Since`; it never narrows the
    # assertions. `--tests` filters are invocation-global, so combining it with any filter flag is
    # rejected the same way `-FullTests` rejects `-Tests` above: fail loudly, never run silently empty.
    if ($Changed -and ($FullTests -or $FeatureTests -or $Tests.Count -gt 0)) {
        Write-Host '   ERROR: -Changed/-Since cannot be combined with -Tests/-FullTests/-FeatureTests (Gradle --tests is invocation-global).' -ForegroundColor Red
        Write-Host '          Use -Changed alone for the affected-module lane, or the filter flags for the gate.' -ForegroundColor Red
        exit 2
    }
    if ($Changed) {
        # Native-command call shape note, PowerShell 5.1: this block must NOT name any local
        # `$changed` -- parameter variables are case-insensitive AND type-constrained, so
        # `$changed = @(...)` collides with the [switch] $Changed parameter and the assignment
        # is cast to SwitchParameter (Object[] -> SwitchParameter throws, a one-element array
        # silently becomes `True`). That collision, not `git`, was the original
        # ConvertToFinalInvalidCastException. Plain loops are also used instead of pipelines so
        # no filter can collapse the path list.
        $changedFiles = @()
        $changedLines = & git -C $repo diff --name-only $Since
        foreach ($line in @($changedLines)) {
            $text = "$line"
            if ($text.Trim().Length -gt 0) { $changedFiles += $text }
        }
        $map = [ordered]@{
            '^app/'              = ':app:testDebugUnitTest'
            '^core/engine/'      = ':core:engine:testDebugUnitTest'
            '^core/download/'    = ':core:download:testDebugUnitTest'
            '^core/advisor/'     = ':core:advisor:testDebugUnitTest'
            '^core/benchmark/'   = ':core:benchmark:testDebugUnitTest'
            '^core/deviceprofile/' = ':core:deviceprofile:testDebugUnitTest'
            '^core/modelstore/'  = ':core:modelstore:testDebugUnitTest'
            '^core/telemetry/'   = ':core:telemetry:testDebugUnitTest'
            '^core/tuning/'      = ':core:tuning:testDebugUnitTest'
            '^api/local/'        = ':api:local:testDebugUnitTest'
            '^feature/chat/'     = ':feature:chat:testDebugUnitTest'
            '^feature/agent/'    = ':feature:agent:testDebugUnitTest'
            '^feature/modelhub/' = ':feature:modelhub:testDebugUnitTest'
            '^feature/settings/' = ':feature:settings:testDebugUnitTest'
        }
        $sel = @()
        foreach ($k in $map.Keys) {
            foreach ($p in $changedFiles) {
                if ($p -match $k) { $sel += $map[$k]; break }
            }
        }
        if ($sel.Count -eq 0) {
            # Two distinct empty cases, each stated loudly (never a silent pass for nothing):
            if ($changedFiles.Count -eq 0) {
                # No tracked change at all since -Since. Reporting a pass here would be a green
                # badge for an empty change set, so this is an error, not a fallback.
                Write-Host ('   ERROR: -Changed found no changed files vs ' + $Since + '; refusing to run an empty lane.') -ForegroundColor Red
                Write-Host '          Use -FullTests for the full gate, or make the change first.' -ForegroundColor Red
                exit 2
            }
            Write-Host ('   -Changed: files changed but no module matched (' + ($changedFiles -join ', ') + '); falling back to :app:testDebugUnitTest') -ForegroundColor Yellow
            $sel = @(':app:testDebugUnitTest')
        }
        $testArgs = @($sel | Select-Object -Unique)
        Write-Host ('   -Changed vs ' + $Since + ': ' + ($testArgs -join ', '))
    }
    if ($FullTests) {
        # The feature modules ship their own test source sets, and three of the tests in
        # :feature:chat read ChatScreen.kt as source text. Running only :app would silently skip
        # 351 tests -- including exactly the anchors a presentation refactor can break.
        # :core:download carries the catalog/download suites (ModelScopeClient etc.); it sat outside
        # the gate until the P6 lane touched it (2026-10-05) and a `-Changed` run silently fell back
        # to :app. 101 tests, counted in the totals below via extraResultDirs.
        $testArgs += @(
            ':core:engine:testDebugUnitTest',
            ':core:download:testDebugUnitTest',
            ':core:advisor:testDebugUnitTest',
            ':core:benchmark:testDebugUnitTest',
            ':core:deviceprofile:testDebugUnitTest',
            ':core:modelstore:testDebugUnitTest',
            ':core:telemetry:testDebugUnitTest',
            ':core:tuning:testDebugUnitTest',
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
        Write-Host '   plus :core:engine, :core:download, :core:advisor, :core:benchmark, :core:deviceprofile, :core:modelstore, :core:telemetry, :core:tuning, :api:local and the four :feature:* test tasks'
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
        $extraResultDirs = @('core\engine', 'core\download', 'core\advisor', 'core\benchmark',
            'core\deviceprofile', 'core\modelstore', 'core\telemetry', 'core\tuning',
            'api\local', 'feature\chat', 'feature\agent',
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

    # A green result over ZERO executed tests is not evidence of anything. This used to pass:
    # `-FullTests` without `-Rerun` leaves the Gradle test task up-to-date, no fresh report is
    # written, tests=0 was printed, and the run still ended in VERIFY OK. Zero tests is now a
    # failure, and the message names the fix so it is actionable rather than mysterious.
    # (Skipped tests are separate: they are counted and reported above, and are not conflated
    # with "did not run" here.)
    $testsWereRequested = ($FullTests -or $FeatureTests -or ($Tests.Count -gt 0))
    if ($testsWereRequested -and $totalTests -eq 0) {
        Write-Host '   ERROR: tests=0 - no test was actually executed, so a green result would prove nothing.' -ForegroundColor Red
        Write-Host '          Most common cause: -FullTests without -Rerun. Gradle then treats the test task as' -ForegroundColor Red
        Write-Host '          up-to-date, writes no fresh report for this run, and the count above legitimately' -ForegroundColor Red
        Write-Host '          sees nothing. Re-run with -Rerun to force real execution.' -ForegroundColor Red
        Write-Host ("          counted report dirs: $countedDirs") -ForegroundColor Red
        $failed += 'no-tests-executed (tests=0)'
    }
}

$fpAfter = Get-McaWorktreeFingerprint -RepoRoot $repo
if ($fpAfter.fingerprint -ne $fpBefore.fingerprint) {
    Write-Host '   ERROR: production inputs changed while this run was executing; the result does not belong to one snapshot.' -ForegroundColor Red
    Write-Host ('      before=' + $fpBefore.fingerprint + '  after=' + $fpAfter.fingerprint) -ForegroundColor Red
    $failed += 'input-changed-during-run'
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
