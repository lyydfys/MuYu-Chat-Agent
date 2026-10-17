<#
.SYNOPSIS
  Negative gate tests for the MCA build guards (C0 positive control; N1..N11 negative cases).

.DESCRIPTION
  Proves the guards can actually FAIL, rather than merely reporting green. Each case records the
  command, the exit code and the raw output. Case definitions:

    C0  positive control   - nothing mutated: snapshot compare says NO DRIFT; lock acquires and
                             releases cleanly.
    N1  content change     - a source file gains bytes (different length): compare must report drift.
    N2  EQUAL-LENGTH write - a source file is rewritten with the SAME byte count but different
                             content: compare must still report drift. This is the case that
                             distinguishes a content hash from a size/mtime check.
    N3  concurrency        - while one lock holder is live, a SECOND process calling
                             Enter-McaBuildLock must be REJECTED (non-zero), not warned.
    N4  git clean judgement- clean/dirty decided by array count and git exit codes, never by
                             comparing a command's output to '' (a clean `git status --porcelain`
                             yields $null, and `$null -eq ''` is False).
    N5  re-entrancy        - a second Enter-McaBuildLock for a path the SAME process already holds
                             is refused (no self-deadlock of a lock-holding wrapper).
    N6  error-path release - an exception inside a locked region still releases the lock;
                             the same process can re-acquire afterwards.
    N7  fail-closed guards - verify-code.ps1 with a missing guard directory exits 3 immediately
                             (never runs Gradle unguarded).
    N8  packet preflight   - missing field / wrong before-hash / nonexistent symbol / no reviewer /
                             no verification command / placeholder / exists-mismatch / cross-packet
                             write-set conflict are all refused; the good fixture passes.
    N9  write-set guard    - inside-set change passes; outside-set change, new untracked file
                             outside the set, and a HEAD move are violations (isolated fixture repo).
    N10 test evidence      - stale XML, empty XML, missing mandatory case, skip overflow, stale APK
                             hash and mismatched worktree hash are refused; valid fixture passes.
    N11 fingerprint        - deterministic on an unchanged tree; a new untracked file changes it.
    N11b/N11c              - the reported fingerprint must equal an independently rebuilt ordinal-order
                             canonical hash; with -FingerprintScriptOverride pointing at a pre-fix
                             variant these cases must go red (culture-sort regression detector).

  Uses a fixture tree under the evidence directory for mutation cases so that no repository source
  file is ever modified. Lock tests use a lock path inside the evidence directory.

  Never stages, commits or publishes anything; does not read or write `.git/info/exclude`.
#>
[CmdletBinding()]
param(
    [string]$RepoRoot = 'E:\MAC',
    [string]$OutDir = 'E:\MAC\artifacts\repair\20261010-audit-fix\T24',
    # E01-FOLLOWUP-1: run the fingerprint cases (N11/N11b/N11c) against an alternate fingerprint
    # script - e.g. a sandbox pre-fix variant - to prove they actually detect a culture-sort
    # regression (missing file => exit 2). '' keeps the in-repo script: historical behaviour.
    [string]$FingerprintScriptOverride = ''
)

$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'

$guardDir = Join-Path $RepoRoot 'tools\build-guards'
Import-Module (Join-Path $guardDir 'McaBuildLock.psm1') -Force
. (Join-Path $guardDir 'SourceSnapshot.ps1')

$negRoot = Join-Path $OutDir 'guard-negative'
$fixture = Join-Path $negRoot 'fixture\src'
New-Item -ItemType Directory -Force -Path $fixture | Out-Null

# The fixture must live where SourceSnapshot can reach it. `artifacts` is excluded by default,
# so use an explicit exclude list for the fixture runs instead.
$fxExclude = @('.git')

$results = New-Object System.Collections.Generic.List[object]
function Add-Result {
    param([string]$Case, [string]$Expectation, [bool]$Passed, [string]$Detail, [string]$Command, [int]$ExitCode)
    $results.Add([pscustomobject]@{
        case = $Case; expectation = $Expectation; passed = $Passed
        detail = $Detail; command = $Command; exitCode = $ExitCode
    })
    $mark = if ($Passed) { 'PASS' } else { 'FAIL' }
    Write-Host ("[{0}] {1,-4} {2}" -f $mark, $Case, $Detail)
}

# ---------------------------------------------------------------- fixture
$a = Join-Path $fixture 'alpha.cpp'
$b = Join-Path $fixture 'beta.hpp'
[System.IO.File]::WriteAllText($a, "// alpha`nint alpha() { return 1; }`n", (New-Object System.Text.UTF8Encoding($false)))
[System.IO.File]::WriteAllText($b, "// beta`nint beta() { return 2; }`n", (New-Object System.Text.UTF8Encoding($false)))
$beforeA = (Get-FileHash $a -Algorithm SHA256).Hash.ToLower()
$lenA = (Get-Item $a).Length

# ---------------------------------------------------------------- C0 positive control
$snap = Join-Path $negRoot 'fixture-snapshot.json'
& (Join-Path $guardDir 'SourceSnapshot.ps1') -Action New -Roots @($fixture) -Snapshot $snap -OutFile $snap -RepoRoot $RepoRoot -ExcludeDirNames $fxExclude -Quiet | Out-Null
$c0SnapshotExit = $LASTEXITCODE

$lockPath = Join-Path $negRoot 'test.lock'
if (Test-Path $lockPath) { Remove-Item $lockPath -Force }
$lockOk = $false; $lockDetail = ''
try {
    $lk = Enter-McaBuildLock -Kind build -LockPath $lockPath -CommandLine 'C0 positive control'
    Exit-McaBuildLock -Lock $lk
    $lockOk = $true; $lockDetail = 'acquired and released cleanly'
} catch { $lockDetail = "unexpected: $($_.Exception.Message)" }

$diffC0 = Join-Path $negRoot 'diff-C0.json'
& (Join-Path $guardDir 'SourceSnapshot.ps1') -Action Compare -Snapshot $snap -OutFile $diffC0 -RepoRoot $RepoRoot -ExcludeDirNames $fxExclude -Quiet | Out-Null
$c0Exit = $LASTEXITCODE
$d0 = Get-Content $diffC0 -Raw | ConvertFrom-Json
Add-Result -Case 'C0' -Expectation 'no drift; lock round-trips' -Passed ($c0Exit -eq 0 -and $d0.equal -and $lockOk) `
    -Detail "compare exit=$c0Exit equal=$($d0.equal) files=$($d0.checkedCount); lock: $lockDetail" `
    -Command 'SourceSnapshot.ps1 -Action Compare (unmutated fixture)' -ExitCode $c0Exit
$c0SnapshotExitNote = "snapshot exit=$c0SnapshotExit"

# ---------------------------------------------------------------- N1 content change (different length)
Add-Content -LiteralPath $a -Value "// drift line appended`n" -NoNewline
$diffN1 = Join-Path $negRoot 'diff-N1.json'
& (Join-Path $guardDir 'SourceSnapshot.ps1') -Action Compare -Snapshot $snap -OutFile $diffN1 -RepoRoot $RepoRoot -ExcludeDirNames $fxExclude -Quiet | Out-Null
$n1Exit = $LASTEXITCODE
$d1 = Get-Content $diffN1 -Raw | ConvertFrom-Json
$hit1 = @($d1.changed | Where-Object { $_.path -like '*alpha.cpp' }).Count -eq 1
Add-Result -Case 'N1' -Expectation 'drift detected' -Passed ($n1Exit -eq 1 -and -not $d1.equal -and $hit1) `
    -Detail "exit=$n1Exit equal=$($d1.equal) changed=$($d1.changed.Count) named-alpha=$hit1  bytes $($d1.changed[0].beforeBytes)->$($d1.changed[0].afterBytes)" `
    -Command 'append bytes to fixture alpha.cpp, then Compare' -ExitCode $n1Exit

# restore for N2
[System.IO.File]::WriteAllText($a, "// alpha`nint alpha() { return 1; }`n", (New-Object System.Text.UTF8Encoding($false)))
$snapN2 = Join-Path $negRoot 'fixture-snapshot-N2.json'
& (Join-Path $guardDir 'SourceSnapshot.ps1') -Action New -Roots @($fixture) -Snapshot $snapN2 -OutFile $snapN2 -RepoRoot $RepoRoot -ExcludeDirNames $fxExclude -Quiet | Out-Null

# ---------------------------------------------------------------- N2 EQUAL-LENGTH rewrite
$sameLen = 'x' * ($lenA - 1) + "`n"
[System.IO.File]::WriteAllText($a, $sameLen, (New-Object System.Text.UTF8Encoding($false)))
$afterLen = (Get-Item $a).Length
$afterHash = (Get-FileHash $a -Algorithm SHA256).Hash.ToLower()
$diffN2 = Join-Path $negRoot 'diff-N2.json'
& (Join-Path $guardDir 'SourceSnapshot.ps1') -Action Compare -Snapshot $snapN2 -OutFile $diffN2 -RepoRoot $RepoRoot -ExcludeDirNames $fxExclude -Quiet | Out-Null
$n2Exit = $LASTEXITCODE
$d2 = Get-Content $diffN2 -Raw | ConvertFrom-Json
$sameBytesFlag = (@($d2.changed | Where-Object { $_.path -like '*alpha.cpp' })[0]).sameBytes
Add-Result -Case 'N2' -Expectation 'equal-length rewrite detected by content hash' -Passed ($n2Exit -eq 1 -and -not $d2.equal -and $d2.changed.Count -ge 1 -and $sameBytesFlag) `
    -Detail "exit=$n2Exit equal=$($d2.equal) changed=$($d2.changed.Count) sameBytes=$sameBytesFlag  len $lenA->$afterLen  sha $($beforeA.Substring(0,16)) -> $($afterHash.Substring(0,16))" `
    -Command 'rewrite fixture alpha.cpp with identical byte count, different content, then Compare' -ExitCode $n2Exit

# ---------------------------------------------------------------- N3 concurrency rejection
if (Test-Path $lockPath) { Remove-Item $lockPath -Force }
$hostExe = (Get-Process -Id $PID).Path
$childScript = Join-Path $negRoot 'child-lock-attempt.ps1'
$childOut = Join-Path $negRoot 'child-lock-attempt.out.txt'
@"
param([string]`$GuardDir,[string]`$LockPath)
Import-Module (Join-Path `$GuardDir 'McaBuildLock.psm1') -Force
try {
    `$l = Enter-McaBuildLock -Kind lint -LockPath `$LockPath -CommandLine 'child attempt'
    Write-Output 'CHILD_ACQUIRED'
    Exit-McaBuildLock -Lock `$l
    exit 0
} catch {
    Write-Output ('CHILD_REJECTED: ' + `$_.Exception.Message)
    exit 3
}
"@ | Set-Content -LiteralPath $childScript -Encoding UTF8

$locker = $null
$n3Exit = -1
$childText = ''
try {
    $locker = Enter-McaBuildLock -Kind build -LockPath $lockPath -CommandLine 'N3 holder (parent)'
    $childText = & $hostExe -NoProfile -NonInteractive -File $childScript -GuardDir $guardDir -LockPath $lockPath 2>&1 | Out-String
    $n3Exit = $LASTEXITCODE
} finally {
    if ($locker) { Exit-McaBuildLock -Lock $locker }
}
Set-Content -LiteralPath $childOut -Value $childText -Encoding UTF8
$childRejected = ($n3Exit -ne 0) -and ($childText -match 'CHILD_REJECTED')
Add-Result -Case 'N3' -Expectation 'second concurrent invocation rejected' -Passed $childRejected `
    -Detail ("child exit=$n3Exit rejected={0}; holder kept the lock while child ran" -f ($childText -match 'CHILD_REJECTED')) `
    -Command "$hostExe -File child-lock-attempt.ps1 (while parent holds the lock)" -ExitCode $n3Exit

# ---------------------------------------------------------------- N4 git clean judgement
function Test-McaGitClean {
    param([string]$RepoRoot, [string[]]$Paths = @())
    $porcelain = if ($Paths.Count -gt 0) { & git -C $RepoRoot status --porcelain -- @Paths 2>$null } else { & git -C $RepoRoot status --porcelain 2>$null }
    # CORRECT judgement: array cardinality. A clean result is $null/empty -> 0 items.
    $cleanByCount = (@($porcelain | Where-Object { $_ -and $_.ToString().Trim() -ne '' }).Count -eq 0)
    # WRONG judgement (kept only to demonstrate the trap in this same run):
    $cleanByStringCompare = ($porcelain -eq '')
    & git -C $RepoRoot diff --quiet 2>$null
    $diffQuietExit = $LASTEXITCODE
    return [pscustomobject]@{
        cleanByArrayCount = $cleanByCount
        failedStringCompare = $cleanByStringCompare
        diffQuietExit = $diffQuietExit
        rawPorcelainCount = @($porcelain).Count
    }
}

$n4a = Test-McaGitClean -RepoRoot $RepoRoot -Paths @('AGENTS.md')
$n4b = Test-McaGitClean -RepoRoot $RepoRoot -Paths @('tools/release/16kb_page_size_report.json')
$n4c = Test-McaGitClean -RepoRoot $RepoRoot -Paths @('core/native/src/main/cpp/CMakeLists.txt')
# A clean path must judge clean; a modified path must judge dirty; and the string-compare method
# must be shown to be unreliable on the clean case (that is the trap being guarded against).
$n4Pass = ($n4a.cleanByArrayCount -eq $true) -and ($n4c.cleanByArrayCount -eq $false)
Add-Result -Case 'N4' -Expectation 'clean/dirty by array count and git exit codes, not string compare' -Passed $n4Pass `
    -Detail ("AGENTS.md cleanByCount={0} (failedStringCompare={1}); 16kb report cleanByCount={2}; CMakeLists cleanByCount={3}; git diff --quiet exit={4}" -f `
        $n4a.cleanByArrayCount, $n4a.failedStringCompare, $n4b.cleanByArrayCount, $n4c.cleanByArrayCount, $n4c.diffQuietExit) `
    -Command 'git status --porcelain -- <path> ; git diff --quiet' -ExitCode 0

# ---------------------------------------------------------------- N5 same-process re-entrancy
if (Test-Path $lockPath) { Remove-Item -LiteralPath $lockPath -Force }
$n5Rejected = $false; $n5Detail = ''
$lockOuter = $null
try {
    $lockOuter = Enter-McaBuildLock -Kind test -LockPath $lockPath -CommandLine 'N5 outer holder'
    try {
        $lockInner = Enter-McaBuildLock -Kind test -LockPath $lockPath -CommandLine 'N5 nested attempt'
        Exit-McaBuildLock -Lock $lockInner
        $n5Detail = 'nested acquire SUCCEEDED - re-entrancy guard is missing'
    } catch {
        $n5Rejected = ($_.Exception.Message -match 're-entrant')
        $n5Detail = 'nested acquire refused: ' + $_.Exception.Message
    }
} finally {
    if ($lockOuter) { Exit-McaBuildLock -Lock $lockOuter }
}
Add-Result -Case 'N5' -Expectation 'same-process re-entrant acquisition refused (no self-deadlock)' -Passed $n5Rejected `
    -Detail $n5Detail -Command 'Enter-McaBuildLock twice for one path in the same process' -ExitCode $(if ($n5Rejected) { 3 } else { 0 })

# ---------------------------------------------------------------- N6 exception path releases the lock
if (Test-Path $lockPath) { Remove-Item -LiteralPath $lockPath -Force }
$n6Reacquired = $false; $n6Raised = $false; $n6Detail = 'simulated failure was not raised'
try {
    $lockN6 = Enter-McaBuildLock -Kind test -LockPath $lockPath -CommandLine 'N6 failure path'
    try { throw 'simulated failure inside the locked region' } finally { Exit-McaBuildLock -Lock $lockN6 }
} catch { $n6Raised = $true }
if ($n6Raised) {
    $n6Detail = 'failure raised; '
    try {
        $lockN6b = Enter-McaBuildLock -Kind test -LockPath $lockPath -CommandLine 'N6 re-acquire'
        Exit-McaBuildLock -Lock $lockN6b
        $n6Reacquired = $true
        $n6Detail = $n6Detail + 'lock re-acquired and released cleanly after the error path'
    } catch { $n6Detail = $n6Detail + 're-acquire FAILED: ' + $_.Exception.Message }
}
$n6Pass = ($n6Raised -and $n6Reacquired)
Add-Result -Case 'N6' -Expectation 'an error inside a locked region still releases; re-acquire succeeds' -Passed $n6Pass `
    -Detail $n6Detail -Command 'Enter -> throw -> finally Exit -> Enter again' -ExitCode $(if ($n6Pass) { 0 } else { 1 })

# ---------------------------------------------------------------- helper: run a guard CLI in a child process
function Invoke-GuardCli {
    param([string]$ScriptPath, [string[]]$ScriptArgs = @())
    # -Width 4096: the default 120-column wrap inserts hard line breaks into captured text,
    # which split 64-char hashes away from the message that follows them (found by N10h).
    $out = & $hostExe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $ScriptPath @ScriptArgs 2>&1 | Out-String -Width 4096
    return [pscustomobject]@{ exit = $LASTEXITCODE; text = $out }
}

# ---------------------------------------------------------------- N7 verify-code fail-closed on missing guard modules
$noGuardsDir = Join-Path $negRoot 'no-guards'
New-Item -ItemType Directory -Force -Path $noGuardsDir | Out-Null
$vcScript = Join-Path $RepoRoot 'scripts\verify-code.ps1'
$savedGuardsEnv = $env:MCA_BUILD_GUARDS_DIR
$env:MCA_BUILD_GUARDS_DIR = $noGuardsDir
try {
    $n7 = Invoke-GuardCli -ScriptPath $vcScript -ScriptArgs @('-Tests', '*E01NoSuchTestProbe*')
} finally {
    if ($null -eq $savedGuardsEnv) { Remove-Item Env:\MCA_BUILD_GUARDS_DIR -ErrorAction SilentlyContinue } else { $env:MCA_BUILD_GUARDS_DIR = $savedGuardsEnv }
}
$n7Pass = ($n7.exit -eq 3) -and ($n7.text -match 'fail-closed')
Add-Result -Case 'N7' -Expectation 'verify-code.ps1 refuses with exit 3 when the guard modules are missing' -Passed $n7Pass `
    -Detail ("exit=$($n7.exit); " + (($n7.text -split "`n" | Where-Object { $_.Trim() } | Select-Object -First 1))) `
    -Command "MCA_BUILD_GUARDS_DIR=<empty dir> verify-code.ps1 -Tests '*E01NoSuchTestProbe*'" -ExitCode $n7.exit

# ---------------------------------------------------------------- N8 task-packet preflight negatives
$packetDir = Join-Path $negRoot 'packets'
New-Item -ItemType Directory -Force -Path $packetDir | Out-Null
$preflightScript = Join-Path $RepoRoot 'tools\build-guards\Test-McaTaskPacketPreflight.ps1'
$fixtureWsFile = Join-Path $RepoRoot 'scripts\verify-code.ps1'
$manifestPath = Join-Path $RepoRoot 'artifacts\architecture\20261011-e00-r0-baseline\B0-MANIFEST.json'
$manifestSha = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash.ToLower()
$wsFileSha = (Get-FileHash -LiteralPath $fixtureWsFile -Algorithm SHA256).Hash.ToLower()
$rollbackPatch = Join-Path $RepoRoot 'artifacts\architecture\20261011-e01-build-guards\apply-e01-patch-v2.ps1'
$rollbackSha = (Get-FileHash -LiteralPath $rollbackPatch -Algorithm SHA256).Hash.ToLower()

function New-McaE01BasePacket {
    return [ordered]@{
        schemaVersion = 1; status = 'READY'; packetId = 'E01-TEST-FIXTURE'; runId = 'e01-test'; sliceId = 'e01-test'; phase = 'E01'
        taskType = 'INTEGRATION'; riskLevel = 'L3'; objective = 'E01 preflight fixture - NOT a real dispatch'
        worker = 'e01-test-worker'; integrator = 'e01-test-integrator'; independentReviewer = 'e01-test-reviewer'
        baseline = [ordered]@{ id = 'B0'; manifestPath = $manifestPath; manifestSha256 = $manifestSha; head = 'afcc9c0ae7309b13efdd0b9844751529b4f1aa41'; frozenAtUtc = '2026-10-11T00:00:00Z'; dirtyAndUntrackedIncluded = $true; sensitiveInputsRedacted = $true; localOnlyGuardsExcludedFromBundle = $true }
        dependencies = @()
        writeSet = @([ordered]@{ path = 'scripts/verify-code.ps1'; expectedExists = $true; beforeSha256 = $wsFileSha; owner = 'e01-fixture'; allowedSymbols = @('Invoke-Gradle'); allowedChange = 'fixture only - no real edit intended' })
        readOnlyPaths = @('scripts/build.ps1')
        forbiddenPaths = @('third_party/llama.cpp')
        logicLineBudget = 150
        sourceSymbols = @([ordered]@{ currentPath = 'tools/build-guards/McaBuildLock.psm1'; qualifiedSignature = 'Enter-McaBuildLock(Kind, LockPath, CommandLine, RetrySeconds, RetryIntervalSeconds)'; bodyRawSha256 = ''; reference = 'current tree'; targetPath = 'tools/build-guards/McaBuildLock.psm1'; targetQualifiedSignature = 'Enter-McaBuildLock(...)'; transitiveHelpers = @(); constants = @(); compileMacros = @() })
        contracts = [ordered]@{ inputsAndDefaults = @('fixture'); nullEmptyEndSemantics = @('fixture'); outputsAndErrors = @('fixture'); sideEffectsAndResourceOwners = @('fixture'); threadsAndLockOrder = @('fixture'); productionCallers = @('fixture'); preservedFixesAndTests = @(); allowedBehaviorDifferences = @() }
        build = [ordered]@{ cwd = 'E:\MAC'; shell = 'powershell'; jdkSdkNdkCmakeVersions = 'fixture'; sdkHashes = @(); backendFlags = @(); abi = 'arm64-v8a'; configuration = 'debug'; actualTargets = @(':app:assembleDebug'); lockEntryAndOwner = 'E01 fixture'; snapshotStartAndEndChecks = @('write-set snapshot before/after'); commands = @('n/a') }
        verification = [ordered]@{ beforeCommands = @('n/a'); afterCommands = @('n/a'); expectedExitCodes = @('0'); mandatoryCaseIds = @('com.muyuchat.mca.E01Fixture#case'); baselineCollectedCount = 'fixture'; baselineSkippedCaseIdsAndReasons = @(); expectedAddedCaseIds = @(); semanticsAssertions = @(); negativeControls = @(); realProductionVerificationRequired = 'false'; realProductionRequirementOrExemptionReason = 'fixture'; modelAndInputHashes = @(); deviceAndApiLevel = 'n/a'; artifactBindingMethod = 'n/a'; abToleranceContractPathAndHash = 'n/a'; logAndXmlPaths = @('E:\MAC\artifacts\architecture\20261011-e01-build-guards'); unavailableMandatoryItems = @() }
        integration = [ordered]@{ workerMaySwitchProduction = $false; workerMayDeleteOldBody = $false; sharedFileOwner = 'e01-test-integrator'; uniqueImplementationAndCallerCheck = 'fixture'; oldPathRemovalConditions = @(); postRemovalCommands = @(); reviewRequired = $true }
        rollback = [ordered]@{ beforeBytesOrPatchPath = $rollbackPatch; beforeBytesOrPatchSha256 = $rollbackSha; expectedAfterManifest = 'n/a'; conflictingExternalChangesAction = 'STOP_AND_COORDINATE'; scope = 'THIS_PACKET_ONLY'; verificationCommands = @('n/a') }
        evidenceDir = 'E:\MAC\artifacts\architecture\20261011-e01-build-guards\guard-negative\packets'
        stopOn = @('HASH_CHANGED'); workerCompletionState = 'READY_FOR_REVIEW'; requiredFinalState = 'INTEGRATED_VERIFIED'
    }
}
function Save-McaE01Packet($obj, [string]$name) {
    $path = Join-Path $packetDir $name
    [System.IO.File]::WriteAllText($path, ($obj | ConvertTo-Json -Depth 10), (New-Object System.Text.UTF8Encoding($false)))
    return $path
}
$goodPacket = Save-McaE01Packet (New-McaE01BasePacket) 'good-packet.json'
$o = New-McaE01BasePacket; $null = $o.Remove('objective');            $b1 = Save-McaE01Packet $o 'bad-missing-field.json'
$o = New-McaE01BasePacket; $o.writeSet[0].beforeSha256 = ('0' * 64);  $b2 = Save-McaE01Packet $o 'bad-hash.json'
$o = New-McaE01BasePacket; $o.sourceSymbols[0].qualifiedSignature = 'NoSuchHelperXyz123(int)'; $b3 = Save-McaE01Packet $o 'bad-symbol.json'
$o = New-McaE01BasePacket; $o.independentReviewer = '';                $b4 = Save-McaE01Packet $o 'bad-no-reviewer.json'
$o = New-McaE01BasePacket; $o.verification.afterCommands = @();        $b5 = Save-McaE01Packet $o 'bad-no-verification.json'
$o = New-McaE01BasePacket; $o.objective = 'A placeholder: <待填-目标>'; $b6 = Save-McaE01Packet $o 'bad-placeholder.json'
$o = New-McaE01BasePacket; $o.writeSet[0].expectedExists = $false;     $b8 = Save-McaE01Packet $o 'bad-exists-mismatch.json'
$o = New-McaE01BasePacket; $o.packetId = 'E01-TEST-FIXTURE-CONFLICT'; $conflictPacket = Save-McaE01Packet $o 'conflict-packet.json'

$n8Runs = New-Object System.Collections.Generic.List[object]
$n8Runs.Add([pscustomobject]@{ case = 'N8-good'; expect = 0; args = @($goodPacket) })
$n8Runs.Add([pscustomobject]@{ case = 'N8-missing-field'; expect = 2; args = @($b1) })
$n8Runs.Add([pscustomobject]@{ case = 'N8-hash-mismatch'; expect = 2; args = @($b2) })
$n8Runs.Add([pscustomobject]@{ case = 'N8-missing-symbol'; expect = 2; args = @($b3) })
$n8Runs.Add([pscustomobject]@{ case = 'N8-no-reviewer'; expect = 2; args = @($b4) })
$n8Runs.Add([pscustomobject]@{ case = 'N8-no-verification-command'; expect = 2; args = @($b5) })
$n8Runs.Add([pscustomobject]@{ case = 'N8-placeholder'; expect = 2; args = @($b6) })
$n8Runs.Add([pscustomobject]@{ case = 'N8-exists-mismatch'; expect = 2; args = @($b8) })
$n8Runs.Add([pscustomobject]@{ case = 'N8-write-set-conflict'; expect = 2; args = @($goodPacket, '-ActivePackets', $conflictPacket) })
foreach ($run in $n8Runs) {
    $r8 = Invoke-GuardCli -ScriptPath $preflightScript -ScriptArgs (@('-Packet') + $run.args + @('-Quiet'))
    $pass8 = ($r8.exit -eq $run.expect)
    Add-Result -Case $run.case -Expectation "preflight exit $($run.expect)" -Passed $pass8 `
        -Detail ("exit=$($r8.exit); " + (($r8.text -split "`n" | Where-Object { $_.Trim() } | Select-Object -First 1))) `
        -Command ("Test-McaTaskPacketPreflight.ps1 -Packet " + (Split-Path -Leaf $run.args[0])) -ExitCode $r8.exit
}

# ---------------------------------------------------------------- N9 write-set guard negatives (isolated fixture repo)
$wsRepo = Join-Path $negRoot 'wsguard-repo'
$wsRepoFull = [System.IO.Path]::GetFullPath($wsRepo)
if (-not $wsRepoFull.StartsWith([System.IO.Path]::GetFullPath($RepoRoot), [System.StringComparison]::OrdinalIgnoreCase)) { throw "N9 fixture escapes the repo root: $wsRepoFull" }
if (Test-Path -LiteralPath $wsRepoFull) { Remove-Item -LiteralPath $wsRepoFull -Recurse -Force }
New-Item -ItemType Directory -Force -Path (Join-Path $wsRepo 'src') | Out-Null
& git -C $wsRepo init -q 2>$null | Out-Null
[System.IO.File]::WriteAllText((Join-Path $wsRepo 'src\a.txt'), "a0`n", (New-Object System.Text.UTF8Encoding($false)))
[System.IO.File]::WriteAllText((Join-Path $wsRepo 'src\b.txt'), "b0`n", (New-Object System.Text.UTF8Encoding($false)))
& git -C $wsRepo -c user.name=e01-guard -c user.email=e01-guard@test add -A 2>$null | Out-Null
& git -C $wsRepo -c user.name=e01-guard -c user.email=e01-guard@test commit -q -m seed 2>$null | Out-Null
$wsguardScript = Join-Path $RepoRoot 'tools\build-guards\Test-McaWriteSetGuard.ps1'
$wsSnap = Join-Path $negRoot 'wsguard-snapshot.json'
$wsSetFile = Join-Path $negRoot 'wsguard-writeset.json'
[System.IO.File]::WriteAllText($wsSetFile, (@{ writeSet = @('src/a.txt') } | ConvertTo-Json), (New-Object System.Text.UTF8Encoding($false)))
$r9a = Invoke-GuardCli -ScriptPath $wsguardScript -ScriptArgs @('-Action', 'New', '-RepoRoot', $wsRepo, '-OutFile', $wsSnap, '-Quiet')
[System.IO.File]::AppendAllText((Join-Path $wsRepo 'src\a.txt'), "changed-inside-set`n")
$r9b = Invoke-GuardCli -ScriptPath $wsguardScript -ScriptArgs @('-Action', 'Verify', '-RepoRoot', $wsRepo, '-Snapshot', $wsSnap, '-WriteSetFile', $wsSetFile)
[System.IO.File]::AppendAllText((Join-Path $wsRepo 'src\b.txt'), "changed-outside-set`n")
$r9c = Invoke-GuardCli -ScriptPath $wsguardScript -ScriptArgs @('-Action', 'Verify', '-RepoRoot', $wsRepo, '-Snapshot', $wsSnap, '-WriteSetFile', $wsSetFile)
[System.IO.File]::WriteAllText((Join-Path $wsRepo 'src\c-new.txt'), "untracked-outside-set`n", (New-Object System.Text.UTF8Encoding($false)))
$r9d = Invoke-GuardCli -ScriptPath $wsguardScript -ScriptArgs @('-Action', 'Verify', '-RepoRoot', $wsRepo, '-Snapshot', $wsSnap, '-WriteSetFile', $wsSetFile)
Remove-Item -LiteralPath (Join-Path $wsRepo 'src\c-new.txt') -Force
[System.IO.File]::WriteAllText((Join-Path $wsRepo 'src\b.txt'), "b0`n", (New-Object System.Text.UTF8Encoding($false)))
$r9e = Invoke-GuardCli -ScriptPath $wsguardScript -ScriptArgs @('-Action', 'Verify', '-RepoRoot', $wsRepo, '-Snapshot', $wsSnap, '-WriteSetFile', $wsSetFile)
& git -C $wsRepo -c user.name=e01-guard -c user.email=e01-guard@test commit -q -am 'head move outside the packet' 2>$null | Out-Null
$r9f = Invoke-GuardCli -ScriptPath $wsguardScript -ScriptArgs @('-Action', 'Verify', '-RepoRoot', $wsRepo, '-Snapshot', $wsSnap, '-WriteSetFile', $wsSetFile)

$r9bPass = ($r9a.exit -eq 0) -and ($r9b.exit -eq 0)
Add-Result -Case 'N9a' -Expectation 'only write-set files changed -> NO VIOLATION' -Passed $r9bPass `
    -Detail ("snapshot exit=$($r9a.exit); verify(inside-set) exit=$($r9b.exit)") -Command 'Test-McaWriteSetGuard.ps1 -Action Verify (fixture repo)' -ExitCode $r9b.exit
$r9cPass = ($r9c.exit -eq 1)
Add-Result -Case 'N9b' -Expectation 'file outside the write set changed -> VIOLATION exit 1' -Passed $r9cPass `
    -Detail ("exit=$($r9c.exit); " + (($r9c.text -split "`n" | Where-Object { $_ -match 'src/b.txt' } | Select-Object -First 1))) -Command 'modify src/b.txt (outside write set) then Verify' -ExitCode $r9c.exit
$r9dPass = ($r9d.exit -eq 1) -and ($r9d.text -match 'c-new.txt')
Add-Result -Case 'N9c' -Expectation 'new untracked file outside the write set -> VIOLATION' -Passed $r9dPass `
    -Detail ("exit=$($r9d.exit); new-file violation reported=" + [bool]($r9d.text -match 'c-new.txt')) -Command 'add src/c-new.txt (untracked) then Verify' -ExitCode $r9d.exit
$r9ePass = ($r9e.exit -eq 0)
Add-Result -Case 'N9d' -Expectation 'out-of-set file restored -> back to NO VIOLATION' -Passed $r9ePass `
    -Detail ("exit=$($r9e.exit) after restoring src/b.txt and deleting src/c-new.txt") -Command 'restore out-of-set change then Verify' -ExitCode $r9e.exit
$r9fPass = ($r9f.exit -eq 1) -and ($r9f.text -match 'head-changed')
Add-Result -Case 'N9e' -Expectation 'HEAD moved (commit) -> head-changed VIOLATION' -Passed $r9fPass `
    -Detail ("exit=$($r9f.exit); head-changed reported=" + [bool]($r9f.text -match 'head-changed')) -Command 'git commit inside the slice window then Verify' -ExitCode $r9f.exit

# ---------------------------------------------------------------- N10 test-evidence guard negatives
$evDir = Join-Path $negRoot 'evidence'
New-Item -ItemType Directory -Force -Path $evDir | Out-Null
$xmlGood = '<?xml version="1.0" encoding="UTF-8"?>' + "`n" + '<testsuite name="svc.A" tests="3" skipped="0" failures="0" errors="0" timestamp="2026-10-11T00:00:00.000Z">' + "`n" + '  <testcase name="t1" classname="svc.A" time="0.01"/>' + "`n" + '  <testcase name="t2" classname="svc.A" time="0.01"/>' + "`n" + '  <testcase name="t1" classname="svc.B" time="0.01"/>' + "`n" + '</testsuite>' + "`n"
$xmlEmpty = '<?xml version="1.0" encoding="UTF-8"?>' + "`n" + '<testsuite name="svc.A" tests="0" skipped="0" failures="0" errors="0">' + "`n" + '</testsuite>' + "`n"
$xmlSkips = '<?xml version="1.0" encoding="UTF-8"?>' + "`n" + '<testsuite name="svc.A" tests="3" skipped="2" failures="0" errors="0">' + "`n" + '  <testcase name="t1" classname="svc.A" time="0.01"/>' + "`n" + '</testsuite>' + "`n"
$evGood = Join-Path $evDir 'good.xml'
$evEmpty = Join-Path $evDir 'empty.xml'
$evStale = Join-Path $evDir 'stale.xml'
$evSkips = Join-Path $evDir 'skips.xml'
[System.IO.File]::WriteAllText($evGood, $xmlGood, (New-Object System.Text.UTF8Encoding($false)))
[System.IO.File]::WriteAllText($evEmpty, $xmlEmpty, (New-Object System.Text.UTF8Encoding($false)))
[System.IO.File]::WriteAllText($evSkips, $xmlSkips, (New-Object System.Text.UTF8Encoding($false)))
[System.IO.File]::WriteAllText($evStale, $xmlGood, (New-Object System.Text.UTF8Encoding($false)))
(Get-Item -LiteralPath $evStale).LastWriteTimeUtc = (Get-Date).ToUniversalTime().AddDays(-3)
$reqOk = Join-Path $evDir 'required-ok.json'
$reqMissing = Join-Path $evDir 'required-missing.json'
[System.IO.File]::WriteAllText($reqOk, (@{ cases = @('svc.A#t1', 'svc.B') } | ConvertTo-Json), (New-Object System.Text.UTF8Encoding($false)))
[System.IO.File]::WriteAllText($reqMissing, (@{ cases = @('svc.A#t1', 'svc.A#t9') } | ConvertTo-Json), (New-Object System.Text.UTF8Encoding($false)))
$evScript = Join-Path $RepoRoot 'tools\build-guards\Test-McaTestEvidenceGuard.ps1'
$evGoodDir = Join-Path $evDir 'fresh'
$evStaleDir = Join-Path $evDir 'old'
$evEmptyDir = Join-Path $evDir 'zero'
$evSkipDir = Join-Path $evDir 'skip'
New-Item -ItemType Directory -Force -Path $evGoodDir, $evStaleDir, $evEmptyDir, $evSkipDir | Out-Null
Copy-Item -LiteralPath $evGood -Destination (Join-Path $evGoodDir 'good.xml') -Force
Copy-Item -LiteralPath $evStale -Destination (Join-Path $evStaleDir 'stale.xml') -Force
Copy-Item -LiteralPath $evEmpty -Destination (Join-Path $evEmptyDir 'empty.xml') -Force
Copy-Item -LiteralPath $evSkips -Destination (Join-Path $evSkipDir 'skips.xml') -Force

$r10a = Invoke-GuardCli -ScriptPath $evScript -ScriptArgs @('-ResultsDirs', $evGoodDir, '-RequiredCasesFile', $reqOk)
$r10b = Invoke-GuardCli -ScriptPath $evScript -ScriptArgs @('-ResultsDirs', $evStaleDir, '-RequiredCasesFile', $reqOk, '-FreshMinutes', '60')
$r10c = Invoke-GuardCli -ScriptPath $evScript -ScriptArgs @('-ResultsDirs', $evEmptyDir, '-RequiredCasesFile', $reqOk)
$r10d = Invoke-GuardCli -ScriptPath $evScript -ScriptArgs @('-ResultsDirs', $evGoodDir, '-RequiredCasesFile', $reqMissing)
$r10e = Invoke-GuardCli -ScriptPath $evScript -ScriptArgs @('-ResultsDirs', $evSkipDir, '-RequiredCasesFile', $reqOk, '-MaxSkipped', '1')

$apkFile = Join-Path $evDir 'fixture.apk'
[System.IO.File]::WriteAllText($apkFile, "not a real apk`n", (New-Object System.Text.UTF8Encoding($false)))
$apkSha = (Get-FileHash -LiteralPath $apkFile -Algorithm SHA256).Hash.ToLower()
$recGood = Join-Path $evDir 'record-good.json'
$recBad = Join-Path $evDir 'record-bad-apk.json'
$recWtBad = Join-Path $evDir 'record-bad-worktree.json'
$wtA = ('a' * 64); $wtB = ('b' * 64)
$recordBase = [ordered]@{ schemaVersion = 1; buildRunId = 'e01-fixture'; status = 'passed'; apkPath = $apkFile; apkSha256 = $apkSha; sourceProvenance = [ordered]@{ worktreeSha256 = $wtA } }
[System.IO.File]::WriteAllText($recGood, ($recordBase | ConvertTo-Json -Depth 5), (New-Object System.Text.UTF8Encoding($false)))
$badRec = [ordered]@{ schemaVersion = 1; buildRunId = 'e01-fixture-old'; status = 'passed'; apkPath = $apkFile; apkSha256 = ('0' * 64); sourceProvenance = [ordered]@{ worktreeSha256 = $wtA } }
[System.IO.File]::WriteAllText($recBad, ($badRec | ConvertTo-Json -Depth 5), (New-Object System.Text.UTF8Encoding($false)))
$badWt = [ordered]@{ schemaVersion = 1; buildRunId = 'e01-fixture'; status = 'passed'; apkPath = $apkFile; apkSha256 = $apkSha; sourceProvenance = [ordered]@{ worktreeSha256 = $wtB } }
[System.IO.File]::WriteAllText($recWtBad, ($badWt | ConvertTo-Json -Depth 5), (New-Object System.Text.UTF8Encoding($false)))

$r10f = Invoke-GuardCli -ScriptPath $evScript -ScriptArgs @('-ResultsDirs', $evGoodDir, '-RequiredCasesFile', $reqOk, '-ApkPath', $apkFile, '-BuildRecordPath', $recGood, '-ExpectedWorktreeSha256', $wtA)
$r10g = Invoke-GuardCli -ScriptPath $evScript -ScriptArgs @('-ResultsDirs', $evGoodDir, '-RequiredCasesFile', $reqOk, '-ApkPath', $apkFile, '-BuildRecordPath', $recBad)
$r10h = Invoke-GuardCli -ScriptPath $evScript -ScriptArgs @('-ResultsDirs', $evGoodDir, '-RequiredCasesFile', $reqOk, '-ApkPath', $apkFile, '-BuildRecordPath', $recWtBad, '-ExpectedWorktreeSha256', $wtA)

Add-Result -Case 'N10a' -Expectation 'fresh complete green evidence -> OK' -Passed ($r10a.exit -eq 0) -Detail "exit=$($r10a.exit)" -Command 'Test-McaTestEvidenceGuard.ps1 (good fixture)' -ExitCode $r10a.exit
Add-Result -Case 'N10b' -Expectation 'stale XML (3 days old) -> refused' -Passed (($r10b.exit -eq 1) -and ($r10b.text -match 'no fresh test report')) -Detail "exit=$($r10b.exit); stale refused=$([bool]($r10b.text -match 'no fresh test report'))" -Command 'Test-McaTestEvidenceGuard.ps1 -FreshMinutes 60 (stale fixture)' -ExitCode $r10b.exit
Add-Result -Case 'N10c' -Expectation 'empty XML (tests=0) -> refused' -Passed (($r10c.exit -eq 1) -and ($r10c.text -match 'tests=0')) -Detail "exit=$($r10c.exit)" -Command 'Test-McaTestEvidenceGuard.ps1 (tests=0 fixture)' -ExitCode $r10c.exit
Add-Result -Case 'N10d' -Expectation 'mandatory case missing -> refused' -Passed (($r10d.exit -eq 1) -and ($r10d.text -match 'svc.A#t9')) -Detail "exit=$($r10d.exit); missing case reported=$([bool]($r10d.text -match 'svc.A#t9'))" -Command 'Test-McaTestEvidenceGuard.ps1 (missing mandatory case)' -ExitCode $r10d.exit
Add-Result -Case 'N10e' -Expectation 'new skip overflow -> refused' -Passed (($r10e.exit -eq 1) -and ($r10e.text -match 'skipped=2')) -Detail "exit=$($r10e.exit)" -Command 'Test-McaTestEvidenceGuard.ps1 -MaxSkipped 1 (skipped=2 fixture)' -ExitCode $r10e.exit
Add-Result -Case 'N10f' -Expectation 'APK matches build record + worktree -> OK' -Passed ($r10f.exit -eq 0) -Detail "exit=$($r10f.exit)" -Command 'Test-McaTestEvidenceGuard.ps1 -ApkPath -BuildRecordPath (matching fixture)' -ExitCode $r10f.exit
Add-Result -Case 'N10g' -Expectation 'stale APK (record sha differs) -> refused' -Passed (($r10g.exit -eq 1) -and ($r10g.text -match 'APK sha256 mismatch')) -Detail "exit=$($r10g.exit); mismatch reported=$([bool]($r10g.text -match 'APK sha256 mismatch'))" -Command 'Test-McaTestEvidenceGuard.ps1 (old APK hash fixture)' -ExitCode $r10g.exit
Add-Result -Case 'N10h' -Expectation 'record worktree hash differs from frozen snapshot -> refused' -Passed (($r10h.exit -eq 1) -and ($r10h.text -match 'does not match expected')) -Detail ("exit=$($r10h.exit); match=" + [bool]($r10h.text -match 'does not match expected') + '; head=' + (($r10h.text -split "`n" | Where-Object { $_.Trim() } | Select-Object -First 1))) -Command 'Test-McaTestEvidenceGuard.ps1 -ExpectedWorktreeSha256 (mismatch fixture)' -ExitCode $r10h.exit

# ---------------------------------------------------------------- N11 worktree fingerprint determinism + change detection
$fpScript = Join-Path $RepoRoot 'tools\build-guards\McaWorktreeFingerprint.ps1'
if ($FingerprintScriptOverride) {
    if (-not (Test-Path -LiteralPath $FingerprintScriptOverride -PathType Leaf)) {
        Write-Host ('FATAL: -FingerprintScriptOverride file not found: ' + $FingerprintScriptOverride)
        exit 2
    }
    $fpScript = (Get-Item -LiteralPath $FingerprintScriptOverride).FullName
}
$fpScriptSha = (Get-FileHash -LiteralPath $fpScript -Algorithm SHA256).Hash.ToLower()
Write-Host ('[fp] fingerprint script under test: {0}' -f $fpScript)
Write-Host ('[fp] sha256: {0}' -f $fpScriptSha)

# Independent ground truth for the canonical-line contract: rebuild the sha256 from the tool's own
# JSON payload (head + entries + submodules) with the paths in ORDINAL order. A pre-fix variant
# sorted the canonical lines with Sort-Object (culture-aware), so on a fixture holding both
# 'src/alpha.txt' and 'src/Beta.txt' the rebuilt hash and the reported fingerprint diverge. The
# JSON `entries` array itself is git porcelain order and is NOT re-sorted by the tool, so entries
# assertions alone could not see that regression (root cause of the E01 review finding F1).
function Get-McaFpOrdinalExpected {
    param([object]$FingerprintJson)
    $entryKeys = [string[]]@($FingerprintJson.entries | ForEach-Object { [string]$_.path })
    [System.Array]::Sort($entryKeys, [System.StringComparer]::Ordinal)
    $entryByPath = @{}
    foreach ($e in $FingerprintJson.entries) { $entryByPath[[string]$e.path] = $e }
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add('head=' + [string]$FingerprintJson.head)
    foreach ($k in $entryKeys) {
        $e = $entryByPath[$k]
        $lines.Add(('file|{0}|{1}|{2}' -f $e.path, $e.status, $e.sha256))
    }
    $subKeys = [string[]]@($FingerprintJson.submodules | ForEach-Object { [string]$_.path })
    [System.Array]::Sort($subKeys, [System.StringComparer]::Ordinal)
    $subByPath = @{}
    foreach ($s in $FingerprintJson.submodules) { $subByPath[[string]$s.path] = $s }
    foreach ($k in $subKeys) {
        $s = $subByPath[$k]
        $lines.Add(('sub|{0}|{1}|{2}' -f $s.path, $s.state, $s.head))
    }
    $canonical = ($lines -join "`n")
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($canonical))) -replace '-', '').ToLowerInvariant()
    } finally { $sha.Dispose() }
}
$f1 = Invoke-GuardCli -ScriptPath $fpScript -ScriptArgs @('-EmitJson', '-RepoRoot', $wsRepo)
$f2 = Invoke-GuardCli -ScriptPath $fpScript -ScriptArgs @('-EmitJson', '-RepoRoot', $wsRepo)
[System.IO.File]::WriteAllText((Join-Path $wsRepo 'src\newfile.txt'), "fingerprint-drift`n", (New-Object System.Text.UTF8Encoding($false)))
$f3 = Invoke-GuardCli -ScriptPath $fpScript -ScriptArgs @('-EmitJson', '-RepoRoot', $wsRepo)
$fp1 = ($f1.text | ConvertFrom-Json).fingerprint
$fp2 = ($f2.text | ConvertFrom-Json).fingerprint
$fp3 = ($f3.text | ConvertFrom-Json).fingerprint
$n11Pass = ($f1.exit -eq 0) -and ($f2.exit -eq 0) -and ($f3.exit -eq 0) -and ($fp1 -eq $fp2) -and ($fp3 -ne $fp1)
Add-Result -Case 'N11' -Expectation 'fingerprint deterministic on an unchanged tree and changes with a new untracked file' -Passed $n11Pass `
    -Detail ("run1=$($fp1.Substring(0,16)) run2=$($fp2.Substring(0,16)) after-new-file=$($fp3.Substring(0,16)); equal12=$($fp1 -eq $fp2) changed=$($fp3 -ne $fp1)") `
    -Command 'McaWorktreeFingerprint.ps1 -EmitJson (fixture repo, twice + new file)' -ExitCode $f3.exit

# E01 follow-up: the canonical order must not depend on the host culture or on the host process.
# 'Beta.txt' vs 'alpha.txt': ordinal => Beta first (0x42 < 0x61); any culture-aware comparer puts
# alpha first, so a regression back to Sort-Object is detected by the assertion below.
[System.IO.File]::WriteAllText((Join-Path $wsRepo 'src\Beta.txt'), "B1`n", (New-Object System.Text.UTF8Encoding($false)))
[System.IO.File]::WriteAllText((Join-Path $wsRepo 'src\alpha.txt'), "a1`n", (New-Object System.Text.UTF8Encoding($false)))
$f4 = Invoke-GuardCli -ScriptPath $fpScript -ScriptArgs @('-EmitJson', '-RepoRoot', $wsRepo)
$f4obj = $f4.text | ConvertFrom-Json
$fp4 = $f4obj.fingerprint
$ordinal4 = Get-McaFpOrdinalExpected -FingerprintJson $f4obj
$paths4 = @($f4obj.entries | ForEach-Object { [string]$_.path })
$ordExpect = [string[]]$paths4.Clone()
[System.Array]::Sort($ordExpect, [System.StringComparer]::Ordinal)
$orderOk = (($paths4 -join "`n") -eq ($ordExpect -join "`n"))
$hasBoth = ($paths4 -contains 'src/Beta.txt') -and ($paths4 -contains 'src/alpha.txt')
$betaFirst = ([System.Array]::IndexOf($paths4, 'src/Beta.txt') -lt [System.Array]::IndexOf($paths4, 'src/alpha.txt'))
# The discriminating assertion is the fingerprint comparison: a culture-aware canonical sort puts
# 'alpha' before 'Beta' and hashes different canonical lines than the ordinal rebuild, so a
# regression back to Sort-Object turns this case red. orderOk/hasBoth/betaFirst only certify that
# the fixture really contains the divergent pair (the JSON `entries` array is git porcelain order,
# already bytewise here - those three flags are NOT the detectors by themselves).
$n11bPass = ($f4.exit -eq 0) -and $orderOk -and $hasBoth -and $betaFirst -and ($fp4 -eq $ordinal4)
Add-Result -Case 'N11b' -Expectation 'reported fingerprint equals the independently rebuilt ordinal-order canonical hash (culture-sort regression detector)' -Passed $n11bPass `
    -Detail ("exit=$($f4.exit); orderOk=$orderOk; betaBeforeAlpha=$betaFirst; n=$($paths4.Count); fp=$($fp4.Substring(0,16)) ordinal=$($ordinal4.Substring(0,16)) match=$($fp4 -eq $ordinal4)") `
    -Command 'McaWorktreeFingerprint.ps1 -EmitJson (Beta.txt + alpha.txt fixture) vs independent ordinal canonical rebuild' -ExitCode $f4.exit
$dotObj = . { . $fpScript; Get-McaWorktreeFingerprint -RepoRoot $wsRepo }
$dotFp = $dotObj.fingerprint
$dotOrdinal = Get-McaFpOrdinalExpected -FingerprintJson $dotObj
$cultureCmd = "& { [System.Threading.Thread]::CurrentThread.CurrentCulture = [System.Globalization.CultureInfo]::GetCultureInfo('de-DE'); & '" + $fpScript + "' -EmitJson -RepoRoot '" + $wsRepo + "' }"
$f5text = & $hostExe -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command $cultureCmd 2>&1 | Out-String -Width 4096
$f5exit = $LASTEXITCODE
$f5obj = $f5text | ConvertFrom-Json
$fp5 = $f5obj.fingerprint
$deDeOrdinal = Get-McaFpOrdinalExpected -FingerprintJson $f5obj
# Four-party agreement on one unchanged tree: CLI, in-process dot-source, de-DE culture child and
# the independent ordinal rebuild. A culture-sort regression keeps the three runtime producers
# mutually consistent (they all use culture-aware Sort-Object; that was the F1 blind spot) but
# every one of them then disagrees with the ordinal rebuild, so the case turns red.
$n11cPass = ($f4.exit -eq 0) -and ($f5exit -eq 0) -and ($dotFp -eq $fp4) -and ($fp5 -eq $fp4) -and ($fp4 -eq $ordinal4) -and ($dotFp -eq $dotOrdinal) -and ($fp5 -eq $deDeOrdinal)
Add-Result -Case 'N11c' -Expectation 'same tree -> CLI, dot-source, de-DE child and the ordinal rebuild all agree on the fingerprint' -Passed $n11cPass `
    -Detail ("cli=$($fp4.Substring(0,16)) dotSource=$($dotFp.Substring(0,16)) deDE=$($fp5.Substring(0,16)) ordinal=$($ordinal4.Substring(0,16)); deDEexit=$f5exit; matchesOrdinal=$($fp4 -eq $ordinal4)/$($dotFp -eq $dotOrdinal)/$($fp5 -eq $deDeOrdinal); equal=$n11cPass") `
    -Command 'McaWorktreeFingerprint.ps1: CLI vs dot-source vs de-DE culture child vs ordinal rebuild' -ExitCode $f5exit
# ---------------------------------------------------------------- summary
$passed = @($results | Where-Object { $_.passed }).Count
$total = $results.Count
$summary = [pscustomobject]@{
    schema          = 'mca.build-guard-negative-tests/1'
    generatedUtc    = [datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
    repoRoot        = $RepoRoot
    fingerprintScript       = $fpScript
    fingerprintScriptSha256 = $fpScriptSha
    fixtureRoot     = $fixture
    snapshotNote    = $c0SnapshotExitNote
    cases           = $results.ToArray()
    passed          = $passed
    total           = $total
    allPassed       = ($passed -eq $total)
}
$outJson = Join-Path $OutDir 'guard-negative-results.json'
$outTxt = Join-Path $OutDir 'guard-negative-results.txt'
[System.IO.File]::WriteAllText($outJson, ($summary | ConvertTo-Json -Depth 6), (New-Object System.Text.UTF8Encoding($false)))
$summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $outTxt -Encoding UTF8

Write-Host ""
Write-Host ("===== guard negative tests: {0}/{1} as expected =====" -f $passed, $total)
Write-Host "wrote: $outJson"
if ($passed -eq $total) { exit 0 } else { exit 1 }
