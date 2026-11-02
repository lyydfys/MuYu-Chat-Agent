# Offline fixtures for the R09 evidence facility.
#
# These tests never call Gradle, adb, install, or a real build. They exercise the failure branches
# of the provenance/binding/summary/exit-code logic with temporary repositories, temporary APK
# containers and a stub gradlew.bat.
#
# Usage:  powershell -NoProfile -ExecutionPolicy Bypass -File tools\evidence\Test-McaEvidenceFixtures.ps1
# Exit:   0 = every fixture passed; 1 = at least one fixture failed; 2 = setup failure.
[CmdletBinding()]
param(
    [switch] $KeepTemp
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
. (Join-Path $repo 'scripts\BuildProvenance.ps1')
. (Join-Path $repo 'scripts\FrameEvidence.ps1')
. (Join-Path $repo 'tools\build-guards\McaWorktreeFingerprint.ps1')

$script:passed = 0
$script:failed = New-Object System.Collections.Generic.List[string]
$script:temps = New-Object System.Collections.Generic.List[string]

function New-FixtureTemp {
    param([string] $Label)
    $dir = Join-Path ([IO.Path]::GetTempPath()) ('mca-evidence-' + $Label + '-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $script:temps.Add($dir) | Out-Null
    return $dir
}

function Write-FixtureText {
    param([string] $Path, [string] $Text)
    $dir = Split-Path -Parent $Path
    if ($dir -and -not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    [IO.File]::WriteAllText($Path, $Text, (New-Object System.Text.UTF8Encoding($false)))
}

function Test-Fixture {
    param([string] $Name, [scriptblock] $Body)
    try {
        & $Body
        $script:passed++
        Write-Host ("PASS  " + $Name) -ForegroundColor Green
    } catch {
        $script:failed.Add(($Name + ' :: ' + $_.Exception.Message)) | Out-Null
        Write-Host ("FAIL  " + $Name + ' :: ' + $_.Exception.Message) -ForegroundColor Red
    }
}

function Assert-True {
    param($Condition, [string] $Message)
    if (-not $Condition) { throw $Message }
}

function Assert-Equal {
    param($Expected, $Actual, [string] $Message)
    if ([string]$Expected -ne [string]$Actual) { throw ("{0} (expected '{1}', actual '{2}')" -f $Message, $Expected, $Actual) }
}

function Assert-Throws {
    param([scriptblock] $Body, [string] $Pattern, [string] $Message)
    $threw = $false
    $actual = ''
    try { & $Body } catch { $threw = $true; $actual = $_.Exception.Message }
    if (-not $threw) { throw ("{0}: expected an exception, none was thrown" -f $Message) }
    if ($Pattern -and $actual -notmatch $Pattern) { throw ("{0}: exception message did not match /{1}/: {2}" -f $Message, $Pattern, $actual) }
    return $actual
}

function Invoke-PowerShellScript {
    param([string] $ScriptPath, [string[]] $Arguments = @())
    $stdout = Join-Path ([IO.Path]::GetTempPath()) ('mca-ps-' + [Guid]::NewGuid().ToString('N') + '.out.log')
    $stderr = Join-Path ([IO.Path]::GetTempPath()) ('mca-ps-' + [Guid]::NewGuid().ToString('N') + '.err.log')
    $script:temps.Add($stdout) | Out-Null
    $script:temps.Add($stderr) | Out-Null
    $engine = (Get-Process -Id $PID).Path
    $all = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $ScriptPath) + $Arguments
    # A child process that reports a usage error writes to stderr on purpose. Under this harness's
    # $ErrorActionPreference='Stop' Windows PowerShell turns that stderr into a terminating
    # NativeCommandError even though both streams are redirected, so the fixture would fail with the
    # child's own reason text instead of asserting the exit code. The exit code is the contract.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $engine @all 1> $stdout 2> $stderr
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    return [pscustomobject]@{
        exitCode = $exitCode
        stdout   = if (Test-Path -LiteralPath $stdout) { [IO.File]::ReadAllText($stdout) } else { '' }
        stderr   = if (Test-Path -LiteralPath $stderr) { [IO.File]::ReadAllText($stderr) } else { '' }
    }
}

function Get-FixtureSha([string] $Text) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Text)))).Replace('-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
}

# ---------------------------------------------------------------------------------------------
# 1. digest / revision validators
# ---------------------------------------------------------------------------------------------
Test-Fixture 'sha256/revision validators reject non-digests' {
    Assert-True (Test-McaSha256Hex ('a' * 64)) '64 hex must be accepted'
    Assert-True (-not (Test-McaSha256Hex ('a' * 63))) '63 chars must be rejected'
    Assert-True (-not (Test-McaSha256Hex 'not-a-hash')) 'non-hex must be rejected'
    Assert-True (-not (Test-McaSha256Hex '')) 'empty must be rejected'
    Assert-True (Test-McaGitRevision ('0123456789abcdef0123456789abcdef01234567')) '40-hex revision must be accepted'
    Assert-True (-not (Test-McaGitRevision 'main')) 'symbolic ref must be rejected'
    Assert-True (-not (Test-McaGitRevision '')) 'empty revision must be rejected'
}

# ---------------------------------------------------------------------------------------------
# 2. build-record schema normalization (debug vs release vs invalid)
# ---------------------------------------------------------------------------------------------
$validSha = ('1' * 64)
$installedSingle = @(@{ path = '/data/app/base.apk'; sha256 = $validSha })

function New-DebugRecordJson {
    param([string] $Status = 'passed', [string] $ApkSha = $validSha, [string] $Variant = 'debug')
    return (@{
        schemaVersion = 1
        status = $Status
        variant = $Variant
        apkSha256 = $ApkSha
        sourceProvenance = @{
            head = '0123456789abcdef0123456789abcdef01234567'
            worktreeSha256 = ('2' * 64)
            trackedDiffSha256 = ('3' * 64)
            manifestSha256 = ('4' * 64)
            submodules = @()
        }
    } | ConvertTo-Json -Depth 10)
}

function New-ReleaseRecordJson {
    param([string] $Status = 'passed', [string] $ApkSha = $validSha, [string] $Variant = 'release', [string] $Binding = 'bound')
    return (@{
        schema = 'mca.release-build-record/1'
        runId = 'fixture'
        status = $Status
        variant = $Variant
        artifacts = @{ apk = @{ sha256 = $ApkSha; bytes = 1234 } }
        source = @{
            head = '0123456789abcdef0123456789abcdef01234567'
            worktreeSha256 = ('2' * 64)
            trackedDiffSha256 = ('3' * 64)
            manifestSha256 = ('4' * 64)
            submodules = @()
            bindingStatus = $Binding
            bindingOrigin = 'fixture'
        }
    } | ConvertTo-Json -Depth 10)
}

function Convert-Record([string] $Json) { return ($Json | ConvertFrom-Json) }

Test-Fixture 'debug record normalizes to a PASS binding' {
    $binding = ConvertTo-McaFrameBuildBinding -Record (Convert-Record (New-DebugRecordJson)) -InstalledApks $installedSingle
    Assert-Equal 'PASS' $binding.status 'status'
    Assert-Equal 'debug' $binding.layout 'layout'
    Assert-Equal $validSha $binding.apkSha256 'apk sha'
    Assert-Equal '0123456789abcdef0123456789abcdef01234567' $binding.sourceRevision 'source revision'
}

Test-Fixture 'release record normalizes to a PASS binding' {
    $binding = ConvertTo-McaFrameBuildBinding -Record (Convert-Record (New-ReleaseRecordJson)) -InstalledApks $installedSingle
    Assert-Equal 'PASS' $binding.status 'status'
    Assert-Equal 'release' $binding.layout 'layout'
    Assert-Equal ('2' * 64) $binding.sourceWorktreeSha256 'source fingerprint'
    Assert-Equal 'bound' $binding.bindingStatus 'binding status'
    # bindingOrigin is information from the record; it must be carried through, not rewritten into
    # the status the binding was validated against.
    Assert-Equal 'fixture' $binding.bindingOrigin 'binding origin is carried through'
}

Test-Fixture 'old/unknown schema fails with a specific reason' {
    $old = @{ schema = 'mca.release-build-record/0'; status = 'passed'; variant = 'release' } | ConvertTo-Json
    $message = Assert-Throws { ConvertTo-McaFrameBuildBinding -Record (Convert-Record $old) -InstalledApks $installedSingle } 'Unsupported build record schema' 'unknown schema'
    Assert-True ($message -match 'mca.release-build-record/0') 'reason must name the unknown schema'
}

Test-Fixture 'non-passing record is refused' {
    Assert-Throws { ConvertTo-McaFrameBuildBinding -Record (Convert-Record (New-ReleaseRecordJson -Status 'failed')) -InstalledApks $installedSingle } "status is 'failed'" 'failed record'
    Assert-Throws { ConvertTo-McaFrameBuildBinding -Record (Convert-Record (New-DebugRecordJson -Status 'started')) -InstalledApks $installedSingle } "status is 'started'" 'started record'
}

Test-Fixture 'unverified source binding is refused' {
    Assert-Throws { ConvertTo-McaFrameBuildBinding -Record (Convert-Record (New-ReleaseRecordJson -Binding 'unverified')) -InstalledApks $installedSingle } 'source binding is' 'unverified binding'
}

Test-Fixture 'invalid 64-hex fingerprints are refused' {
    Assert-Throws { ConvertTo-McaFrameBuildBinding -Record (Convert-Record (New-ReleaseRecordJson -ApkSha ('a' * 63))) -InstalledApks $installedSingle } 'APK SHA-256 is not a 64-hex' 'short apk hash'
    Assert-Throws { ConvertTo-McaFrameBuildBinding -Record (Convert-Record (New-ReleaseRecordJson -ApkSha ('z' * 64))) -InstalledApks $installedSingle } 'APK SHA-256 is not a 64-hex' 'non-hex apk hash'
    $badFingerprint = New-ReleaseRecordJson
    $badFingerprint = $badFingerprint.Replace(('2' * 64), '')
    Assert-Throws { ConvertTo-McaFrameBuildBinding -Record (Convert-Record $badFingerprint) -InstalledApks $installedSingle } 'source fingerprint is not a 64-hex' 'empty worktree fingerprint'
    $badHead = New-DebugRecordJson
    $badHead = $badHead.Replace('0123456789abcdef0123456789abcdef01234567', 'unknown')
    Assert-Throws { ConvertTo-McaFrameBuildBinding -Record (Convert-Record $badHead) -InstalledApks $installedSingle } 'source HEAD is not a revision' 'symbolic head'
}

Test-Fixture 'variant/schema mixing is refused' {
    Assert-Throws { ConvertTo-McaFrameBuildBinding -Record (Convert-Record (New-ReleaseRecordJson -Variant 'debug')) -InstalledApks $installedSingle } "expected 'release'" 'release schema with debug variant'
    Assert-Throws { ConvertTo-McaFrameBuildBinding -Record (Convert-Record (New-DebugRecordJson -Variant 'release')) -InstalledApks $installedSingle } "expected 'debug'" 'debug schema with release variant'
}

Test-Fixture 'APK identity mismatch is refused' {
    $other = @(@{ path = '/data/app/base.apk'; sha256 = ('9' * 64) })
    Assert-Throws { ConvertTo-McaFrameBuildBinding -Record (Convert-Record (New-ReleaseRecordJson)) -InstalledApks $other } 'does not match build record' 'different installed apk'
}

Test-Fixture 'split installs are BLOCKED and never matched on base.apk' {
    $split = @(
        @{ path = '/data/app/base.apk'; sha256 = $validSha },
        @{ path = '/data/app/split_config.arm64_v8a.apk'; sha256 = ('8' * 64) }
    )
    $message = Assert-Throws { ConvertTo-McaFrameBuildBinding -Record (Convert-Record (New-ReleaseRecordJson)) -InstalledApks $split } 'split' 'split install'
    Assert-True ($message -match 'BLOCKED') 'split reason must say BLOCKED'
    Assert-Throws { ConvertTo-McaFrameBuildBinding -Record (Convert-Record (New-ReleaseRecordJson)) -InstalledApks @() } 'No installed APK' 'empty install list'
}

Test-Fixture 'workspace observation is never proof of APK source' {
    $dir = New-FixtureTemp 'workspace'
    New-Item -ItemType Directory -Force -Path (Join-Path $dir 'scripts') | Out-Null
    $evidence = Get-McaFrameWorkspaceEvidence -RepoRoot $repo
    Assert-Equal $false $evidence.apkSourceProven 'workspace evidence must not claim APK source'
    Assert-True (Test-McaSha256Hex $evidence.trackedDiffSha256) 'tracked diff hash must be 64-hex'
    Assert-True (Test-McaSha256Hex $evidence.untrackedInputSha256) 'untracked hash must be 64-hex'
}

# ---------------------------------------------------------------------------------------------
# 3. Get-McaFrameBuildBinding file handling
# ---------------------------------------------------------------------------------------------
Test-Fixture 'missing record path returns NOT_RUN (no fake binding)' {
    $binding = Get-McaFrameBuildBinding -BuildRecordPath '' -InstalledApks $installedSingle
    Assert-Equal 'NOT_RUN' $binding.status 'status'
    Assert-True ($binding.reason -match 'not its source') 'reason must explain the boundary'
}

Test-Fixture 'nonexistent / invalid / unreadable records fail closed' {
    $dir = New-FixtureTemp 'record'
    Assert-Throws { Get-McaFrameBuildBinding -BuildRecordPath (Join-Path $dir 'missing.json') -InstalledApks $installedSingle } 'does not exist' 'missing file'
    $badJson = Join-Path $dir 'bad.json'
    Write-FixtureText $badJson '{ not json'
    Assert-Throws { Get-McaFrameBuildBinding -BuildRecordPath $badJson -InstalledApks $installedSingle } 'not valid JSON' 'invalid json'
}

Test-Fixture 'file-based release record binds and records its own digest' {
    $dir = New-FixtureTemp 'record-ok'
    $path = Join-Path $dir 'release.json'
    Write-FixtureText $path (New-ReleaseRecordJson)
    $binding = Get-McaFrameBuildBinding -BuildRecordPath $path -InstalledApks $installedSingle
    Assert-Equal 'PASS' $binding.status 'status'
    Assert-Equal (Get-FixtureSha ([IO.File]::ReadAllText($path))) $binding.recordSha256 'record digest must be the file digest'
}

# ---------------------------------------------------------------------------------------------
# 4. trusted prior-record selection (release -SkipBuild path)
# ---------------------------------------------------------------------------------------------
Test-Fixture 'trusted binding selection ignores failed/mismatched/unknown records' {
    $dir = New-FixtureTemp 'records'
    Write-FixtureText (Join-Path $dir 'a-failed.json') (New-ReleaseRecordJson -Status 'failed')
    Write-FixtureText (Join-Path $dir 'b-other-apk.json') (New-ReleaseRecordJson -ApkSha ('7' * 64))
    Write-FixtureText (Join-Path $dir 'c-unknown-schema.json') (@{ schema = 'other/9'; status = 'passed' } | ConvertTo-Json)
    $none = Select-McaTrustedSourceBinding -RecordRoot $dir -ApkSha256 $validSha
    Assert-Equal 'unverified' $none.status 'no trusted record may stay unverified'
    Write-FixtureText (Join-Path $dir 'd-passed.json') (New-ReleaseRecordJson)
    $bound = Select-McaTrustedSourceBinding -RecordRoot $dir -ApkSha256 $validSha
    Assert-Equal 'bound' $bound.status 'passing record with the same APK must bind'
    Assert-True (Test-McaSha256Hex $bound.recordSha256) 'selected record digest must be 64-hex'
    Assert-Throws { Select-McaTrustedSourceBinding -RecordRoot $dir -ApkSha256 'short' } 'not a 64-hex digest' 'invalid binary sha'
}

# ---------------------------------------------------------------------------------------------
# 5. before/after provenance comparison in a real temporary git repository
# ---------------------------------------------------------------------------------------------
# Fixture-local git runner.
#
# The host's git configuration must not decide whether a fixture passes. With core.autocrlf=true the
# harness's own LF files produce "LF will be replaced by CRLF" warnings, and because this harness runs
# with $ErrorActionPreference='Stop' a native stderr line becomes a terminating NativeCommandError.
# Fixtures therefore run git with autocrlf off and decide success from the exit code only.
function Invoke-FixtureGit {
    param([string] $RepositoryPath, [string[]] $Arguments)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & git -C $RepositoryPath -c core.autocrlf=false -c core.safecrlf=false @Arguments 2>&1 | Out-Null
        return $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
}
function New-FixtureGitRepo {
    $dir = New-FixtureTemp 'git'
    [void](Invoke-FixtureGit -RepositoryPath $dir -Arguments @('init', '-q'))
    if ($LASTEXITCODE -ne 0) { throw 'git init failed' }
    Write-FixtureText (Join-Path $dir 'AGENTS.md') "# fixture guard`n"
    Write-FixtureText (Join-Path $dir 'app\src\test\java\com\muyuchat\mca\UniversalDeviceAdmissionPolicyTest.kt') "// fixture guard`n"
    Write-FixtureText (Join-Path $dir 'app\src\main\java\com\muyuchat\mca\Main.kt') "object Main { const val VERSION = 1 }`n"
    Write-FixtureText (Join-Path $dir 'tools\release\16kb_page_size_report.json') "{`"compliant`": true}`n"
    Write-FixtureText (Join-Path $dir '.gitignore') "artifacts/`n"
    $excludePath = Join-Path $dir '.git\info\exclude'
    Write-FixtureText $excludePath "AGENTS.md`napp/src/test/java/com/muyuchat/mca/UniversalDeviceAdmissionPolicyTest.kt`n"
    [void](Invoke-FixtureGit -RepositoryPath $dir -Arguments @('add', '-A'))
    [void](Invoke-FixtureGit -RepositoryPath $dir -Arguments @('-c', 'user.email=fixture@example.invalid', '-c', 'user.name=fixture', 'commit', '-qm', 'fixture baseline'))
    if ($LASTEXITCODE -ne 0) { throw 'git commit failed' }
    return $dir
}

Test-Fixture 'audit output under artifacts/ does not change source provenance' {
    $dir = New-FixtureGitRepo
    $before = Get-McaSourceProvenance -ProjectRoot $dir
    Write-FixtureText (Join-Path $dir 'artifacts\releases\run1\16kb_page_size_report.json') "{`"run`": 1}`n"
    Write-FixtureText (Join-Path $dir 'artifacts\releases\run1\mca-sbom.spdx.json') '{ "spdxVersion": "SPDX-2.3" }'
    $after = Get-McaSourceProvenance -ProjectRoot $dir
    $comparison = Compare-McaSourceProvenance -Before $before -After $after
    Assert-Equal $true $comparison.equal 'run-scoped audit artifacts must not be build inputs'
    Assert-True (Test-McaSha256Hex $before.manifestSha256) 'manifest digest must be 64-hex'
}

Test-Fixture 'writing audit reports back into tracked tools/ is detected' {
    $dir = New-FixtureGitRepo
    $before = Get-McaSourceProvenance -ProjectRoot $dir
    Write-FixtureText (Join-Path $dir 'tools\release\16kb_page_size_report.json') "{`"compliant`": false}`n"
    $after = Get-McaSourceProvenance -ProjectRoot $dir
    $comparison = Compare-McaSourceProvenance -Before $before -After $after
    Assert-Equal $false $comparison.equal 'a rewritten tracked audit file must be detected'
    Assert-True ((($comparison.changedPaths) -join ';') -match 'tools/release/16kb_page_size_report\.json') 'changed path must be named'
}

Test-Fixture 'real source change during a build is detected' {
    $dir = New-FixtureGitRepo
    $before = Get-McaSourceProvenance -ProjectRoot $dir
    Write-FixtureText (Join-Path $dir 'app\src\main\java\com\muyuchat\mca\Main.kt') "object Main { const val VERSION = 2 }`n"
    $after = Get-McaSourceProvenance -ProjectRoot $dir
    $comparison = Compare-McaSourceProvenance -Before $before -After $after
    Assert-Equal $false $comparison.equal 'source change must be detected'
    Assert-True ((($comparison.changedPaths) -join ';') -match 'Main\.kt') 'changed source file must be named'
    Assert-True ($comparison.reasons.Count -gt 0) 'comparison must explain the change'
}

Test-Fixture 'a failing submodule git command is an error, not an empty revision' {
    $dir = New-FixtureTemp 'submodule'
    [void](Invoke-FixtureGit -RepositoryPath $dir -Arguments @('init', '-q'))
    Write-FixtureText (Join-Path $dir 'AGENTS.md') "# fixture guard`n"
    Write-FixtureText (Join-Path $dir 'app\src\test\java\com\muyuchat\mca\UniversalDeviceAdmissionPolicyTest.kt') "// fixture guard`n"
    Write-FixtureText (Join-Path $dir '.gitmodules') "[submodule `"vendor/broken`"]`n`tpath = vendor/broken`n`turl = https://example.invalid/broken.git`n"
    Write-FixtureText (Join-Path $dir '.git\info\exclude') "AGENTS.md`napp/src/test/java/com/muyuchat/mca/UniversalDeviceAdmissionPolicyTest.kt`n"
    # `.git` is a file that points at a gitdir which does not exist. An empty `.git` directory
    # is not enough: git then walks up to the parent fixture repository and succeeds, which
    # would test nothing. A dangling gitdir makes every git command in that path fail.
    Write-FixtureText (Join-Path $dir 'vendor\broken\.git') "gitdir: ./nonexistent-gitdir`n"
    [void](Invoke-FixtureGit -RepositoryPath $dir -Arguments @('add', '-A'))
    [void](Invoke-FixtureGit -RepositoryPath $dir -Arguments @('-c', 'user.email=fixture@example.invalid', '-c', 'user.name=fixture', 'commit', '-qm', 'fixture with broken submodule'))
    Assert-Throws { Get-McaSourceProvenance -ProjectRoot $dir } 'submodule' 'broken submodule must abort provenance'
    Assert-Throws { Get-McaFrameWorkspaceEvidence -RepoRoot $dir } 'submodule' 'frame evidence must abort on a broken submodule too'
}

# ---------------------------------------------------------------------------------------------
# 6. summary evidence write failures
# ---------------------------------------------------------------------------------------------
Test-Fixture 'summary write succeeds into an existing directory' {
    $dir = New-FixtureTemp 'summary-ok'
    $result = Save-McaFrameSummary -Path (Join-Path $dir 'summary.json') -State ([ordered]@{ schema = 'fixture'; verdict = 'PASS' })
    Assert-Equal $true $result.written 'write result'
    Assert-True ((Get-Item -LiteralPath (Join-Path $dir 'summary.json')).Length -gt 0) 'summary file must not be empty'
}

Test-Fixture 'summary write failure reports failure (caller must exit non-zero)' {
    $dir = New-FixtureTemp 'summary-fail'
    $blocker = Join-Path $dir 'blocker'
    Write-FixtureText $blocker 'not a directory'
    $result = Save-McaFrameSummary -Path (Join-Path $blocker 'summary.json') -State ([ordered]@{ schema = 'fixture' })
    Assert-Equal $false $result.written 'write must fail'
    Assert-True (-not [string]::IsNullOrWhiteSpace([string]$result.error)) 'failure must carry a reason'
}

# ---------------------------------------------------------------------------------------------
# 7. 16 KB audit tool: run-scoped --output and failure branches
# ---------------------------------------------------------------------------------------------
function New-FixtureElf {
    param([uint16] $Machine = 183, [uint64] $Align = 16384)
    $size = 120
    $bytes = New-Object byte[] $size
    $bytes[0] = 0x7F; $bytes[1] = 0x45; $bytes[2] = 0x4C; $bytes[3] = 0x46
    $bytes[4] = 2; $bytes[5] = 1; $bytes[6] = 1
    [BitConverter]::GetBytes([uint16]3).CopyTo($bytes, 16)          # e_type = ET_DYN
    [BitConverter]::GetBytes($Machine).CopyTo($bytes, 18)            # e_machine
    [BitConverter]::GetBytes([uint32]1).CopyTo($bytes, 20)           # e_version
    [BitConverter]::GetBytes([uint64]64).CopyTo($bytes, 32)          # e_phoff
    [BitConverter]::GetBytes([uint16]64).CopyTo($bytes, 52)          # e_ehsize
    [BitConverter]::GetBytes([uint16]56).CopyTo($bytes, 54)          # e_phentsize
    [BitConverter]::GetBytes([uint16]1).CopyTo($bytes, 56)           # e_phnum
    [BitConverter]::GetBytes([uint32]1).CopyTo($bytes, 64)           # p_type = PT_LOAD
    [BitConverter]::GetBytes([uint32]5).CopyTo($bytes, 68)           # p_flags
    [BitConverter]::GetBytes([uint64]0).CopyTo($bytes, 72)           # p_offset
    [BitConverter]::GetBytes([uint64]0).CopyTo($bytes, 80)           # p_vaddr
    [BitConverter]::GetBytes([uint64]0).CopyTo($bytes, 88)           # p_paddr
    [BitConverter]::GetBytes([uint64]$size).CopyTo($bytes, 96)       # p_filesz
    [BitConverter]::GetBytes([uint64]$size).CopyTo($bytes, 104)      # p_memsz
    [BitConverter]::GetBytes($Align).CopyTo($bytes, 112)             # p_align
    return $bytes
}

function Invoke-FixturePython {
    param([string[]] $Arguments)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $text = (& python @Arguments 2>&1 | Out-String)
        return [pscustomobject]@{ exitCode = $LASTEXITCODE; text = $text.Trim() }
    } finally {
        $ErrorActionPreference = $previous
    }
}
function New-FixtureApk {
    param([string] $Path, [byte[]] $ElfBytes = $null)
    Add-Type -AssemblyName System.IO.Compression | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null
    if (Test-Path -LiteralPath $Path) { Remove-Item -LiteralPath $Path -Force }
    $stream = [IO.File]::Create($Path)
    try {
        $archive = New-Object IO.Compression.ZipArchive($stream, [IO.Compression.ZipArchiveMode]::Create)
        try {
            if ($null -ne $ElfBytes) {
                $entry = $archive.CreateEntry('lib/arm64-v8a/libfixture.so')
                $entryStream = $entry.Open()
                try { $entryStream.Write($ElfBytes, 0, $ElfBytes.Length) } finally { $entryStream.Dispose() }
            }
            $manifest = $archive.CreateEntry('AndroidManifest.xml')
            $manifestStream = $manifest.Open()
            try { $manifestStream.Write([byte[]](1, 2, 3), 0, 3) } finally { $manifestStream.Dispose() }
        } finally { $archive.Dispose() }
    } finally { $stream.Dispose() }
}

Test-Fixture '16 KB audit writes to --output and reports compliant/non-compliant' {
    $python = Get-Command python -ErrorAction SilentlyContinue
    if (-not $python) { throw 'python is not available; the audit tool fixture cannot run' }
    $dir = New-FixtureTemp 'pagesize'
    $tool = Join-Path $repo 'tools\release\verify_16kb_pagesize.py'
    $helpResult = Invoke-FixturePython -Arguments @('-B', $tool, '--help') ; $help = $helpResult.text
    Assert-Equal 0 $helpResult.exitCode 'python --help must succeed'
    Assert-True ($help -match '--output') '--output must be documented'
    $compliantApk = Join-Path $dir 'compliant.apk'
    New-FixtureApk -Path $compliantApk -ElfBytes (New-FixtureElf -Align 16384)
    $out1 = Join-Path $dir 'run1\16kb.json'
    $compliantResult = Invoke-FixturePython -Arguments @('-B', $tool, '--apk', $compliantApk, '--output', $out1) ; $text = $compliantResult.text
    Assert-Equal 0 $compliantResult.exitCode ('compliant APK must exit 0: ' + $text)
    Assert-True (Test-Path -LiteralPath $out1) 'report must be written to the run-scoped path'
    $report = Get-Content -LiteralPath $out1 -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-Equal 1 $report.compliant_binaries 'compliant count'
    $nonCompliantApk = Join-Path $dir 'noncompliant.apk'
    New-FixtureApk -Path $nonCompliantApk -ElfBytes (New-FixtureElf -Align 4096)
    $out2 = Join-Path $dir 'run2\16kb.json'
    $nonCompliantResult = Invoke-FixturePython -Arguments @('-B', $tool, '--apk', $nonCompliantApk, '--output', $out2)
    Assert-Equal 1 $nonCompliantResult.exitCode 'non-compliant APK must exit 1'
    Assert-True (Test-Path -LiteralPath $out2) 'failure report must still be written'
    $noNativeApk = Join-Path $dir 'no-native.apk'
    New-FixtureApk -Path $noNativeApk
    $out3 = Join-Path $dir 'run3\16kb.json'
    $noNativeResult = Invoke-FixturePython -Arguments @('-B', $tool, '--apk', $noNativeApk, '--output', $out3)
    Assert-Equal 1 $noNativeResult.exitCode 'APK without native libraries must exit 1'
    Assert-True (-not (Test-Path -LiteralPath $out3)) 'no report is written when no library was audited'
    $missingResult = Invoke-FixturePython -Arguments @('-B', $tool, '--apk', (Join-Path $dir 'missing.apk'), '--output', (Join-Path $dir 'run4\16kb.json'))
    Assert-Equal 2 $missingResult.exitCode 'missing APK must exit 2'
}

# ---------------------------------------------------------------------------------------------
Test-Fixture 'controlled-replay case bodies match their declared length and SHA-256' {
    # Independent of Kotlin: materializes every text/repeat event and compares the answer body, its
    # UTF-16 length and SHA-256 with the fixture's own expectations. The Kotlin driver exercises the
    # production accumulator; this fixture keeps the DATA honest even when the app module cannot
    # compile because another workstream is mid-refactor.
    $verifier = Join-Path $repo 'tools\evidence\verify-replay-fixture.py'
    Assert-True (Test-Path -LiteralPath $verifier) 'the replay verifier must exist'
    $result = Invoke-FixturePython -Arguments @('-B', $verifier)
    Assert-Equal 0 $result.exitCode ('replay fixture bodies must match: ' + $result.text)
}

Test-Fixture 'release quiet-window refuses to build without a proven quiet tree' {
    # Three signed Release attempts in this session were refused by release.ps1's own input comparison
    # because a second workstream edited the shared tree mid-build. The wrapper must therefore (a) only
    # start a build inside a proven-quiet window and (b) refuse instead of building when it cannot get
    # one -- never "start it and hope".
    $script = Join-Path $repo 'scripts\release-quiet-window.ps1'
    Assert-True (Test-Path -LiteralPath $script) 'the quiet-window wrapper must exist'

    $quiet = Invoke-PowerShellScript -ScriptPath $script -Arguments @(
        '-QuietMinutes', '0', '-MaxWaitMinutes', '1', '-PollSeconds', '5', '-DryRun'
    )
    Assert-Equal 0 $quiet.exitCode ('a satisfied quiet window must dry-run cleanly: ' + $quiet.stderr)
    Assert-True ($quiet.stdout -match 'DryRun') 'the dry run must not start a build'

    $refused = Invoke-PowerShellScript -ScriptPath $script -Arguments @(
        '-QuietMinutes', '30', '-MaxWaitMinutes', '1', '-PollSeconds', '10', '-DryRun'
    )
    Assert-Equal 3 $refused.exitCode 'an unreachable quiet window must exit 3 without building'
    Assert-True ($refused.stdout -match 'nothing was built') 'the refusal reason must say nothing was built'
}

Test-Fixture 'r12 comparison reports a naming fix and refuses cross-device pairs' {
    # The R12 second round is "same device, same font scale, before -> after". The comparator must
    # (a) count unnamed clickables from dumps, (b) show the naming delta, and (c) refuse a pair that
    # differs in device, because such a diff measures the configuration instead of the fix.
    $script = Join-Path $repo 'scripts\compare-visual-runs.ps1'
    Assert-True (Test-Path -LiteralPath $script) 'the comparison script must exist'

    $root = Join-Path ([IO.Path]::GetTempPath()) ('r12cmp-' + [guid]::NewGuid().ToString('N'))
    $before = Join-Path $root 'before'
    $after = Join-Path $root 'after'
    $crossDevice = Join-Path $root 'cross'
    foreach ($dir in @($before, $after, $crossDevice)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }

    $unnamedNode = '<?xml version="1.0" encoding="UTF-8"?><hierarchy><node class="android.view.View" clickable="true" content-desc="" text="" bounds="[0,0][100,100]" package="com.muyuchat.mca" /></hierarchy>'
    $namedNode = '<?xml version="1.0" encoding="UTF-8"?><hierarchy><node class="android.view.View" clickable="true" content-desc="关闭菜单" text="" bounds="[0,0][100,100]" package="com.muyuchat.mca" /></hierarchy>'
    [IO.File]::WriteAllText((Join-Path $before 'state-menu-open.ui.xml'), $unnamedNode, [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $after 'state-menu-open.ui.xml'), $namedNode, [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $crossDevice 'state-menu-open.ui.xml'), $namedNode, [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $before 'RUN.json'), '{"device":"device-a","apkSha256":"aaa"}', [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $after 'RUN.json'), '{"device":"device-a","apkSha256":"bbb"}', [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $crossDevice 'RUN.json'), '{"device":"device-b","apkSha256":"ccc"}', [Text.UTF8Encoding]::new($false))

    $outFile = Join-Path $root 'comparison.json'
    $ok = Invoke-PowerShellScript -ScriptPath $script -Arguments @('-BeforeDir', $before, '-AfterDir', $after, '-OutFile', $outFile)
    Assert-Equal 0 $ok.exitCode ('same-device comparison must succeed: ' + $ok.stderr)
    if (-not (Test-Path -LiteralPath $outFile)) { throw 'the comparison must write its report' }
    $report = Get-Content -LiteralPath $outFile -Raw -Encoding UTF8 | ConvertFrom-Json
    $state = @($report.states)[0]
    Assert-Equal 1 $state.beforeUnnamed 'the unfixed dump has one unnamed clickable'
    Assert-Equal 0 $state.afterUnnamed 'the fixed dump has no unnamed clickable'
    Assert-Equal 'OK' $report.verdict 'no regression when unnamed count goes down'
    Assert-True (@($state.addedDescriptions) -contains '关闭菜单') 'the added content description must be reported'

    # Different devices: the comparison must refuse instead of producing a misleading diff.
    $crossResult = Invoke-PowerShellScript -ScriptPath $script -Arguments @('-BeforeDir', $before, '-AfterDir', $crossDevice, '-OutFile', (Join-Path $root 'cross.json'))
    Assert-True ($crossResult.exitCode -ne 0) 'comparing different devices without -Force must fail'
    Assert-True ($crossResult.stderr -match 'different devices') 'the refusal must say why'
}

Test-Fixture 'closeout wrapper reports preconditions and never builds in plan mode' {
    # The wrapper exists to sequence "build -> install -> verify -> compare" unattended while a second
    # workstream edits the tree. Its plan mode must be able to inspect the environment without ever
    # starting a build, including on the iteration where the environment happens to be usable.
    $script = Join-Path $repo 'scripts\closeout-candidate-round.ps1'
    Assert-True (Test-Path -LiteralPath $script) 'the closeout wrapper must exist'
    $recordsDir = Join-Path $repo 'artifacts\release-build-records'
    $before = @(Get-ChildItem -LiteralPath $recordsDir -Filter '*.json' -File -ErrorAction SilentlyContinue).Count

    $plan = Invoke-PowerShellScript -ScriptPath $script -Arguments @('-PlanOnly', '-MaxWaitMinutes', '1', '-PollSeconds', '10')
    Assert-Equal 0 $plan.exitCode ('plan mode must be able to report the environment: ' + $plan.stderr)
    Assert-True ($plan.stdout -match 'free=') 'plan mode must report free memory'
    Assert-True ($plan.stdout -match 'lock=') 'plan mode must report the build lock state'
    Assert-True ($plan.stdout -match 'quiet=') 'plan mode must report tree quietness'
    Assert-True ($plan.stdout -match 'PlanOnly|plan only') 'plan mode must say it is not building'

    $after = @(Get-ChildItem -LiteralPath $recordsDir -Filter '*.json' -File -ErrorAction SilentlyContinue).Count
    Assert-Equal $before $after 'plan mode must not produce a release record'
}

# 8. controlled-replay fixture contract (data only; no on-device driver exists)
# ---------------------------------------------------------------------------------------------
Test-Fixture 'controlled-replay manifest digest and case coverage' {
    $root = Join-Path $repo 'benchmark\src\main\assets\controlled-replay'
    $manifestPath = Join-Path $root 'manifest.json'
    Assert-True (Test-Path -LiteralPath $manifestPath) 'manifest must exist'
    $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-Equal 'mca.controlled-replay.manifest/1' $manifest.schema 'manifest schema'
    $fixturePath = Join-Path $root ([string]$manifest.fixture)
    Assert-True (Test-Path -LiteralPath $fixturePath) 'fixture must exist'
    $actualSha = (Get-FileHash -LiteralPath $fixturePath -Algorithm SHA256).Hash.ToLowerInvariant()
    Assert-Equal $manifest.fixtureSha256 $actualSha 'fixture digest must match the manifest'
    Assert-Equal 'BLOCKED' $manifest.chain.status 'chain status must stay honest'
    $fixture = Get-Content -LiteralPath $fixturePath -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-Equal 'mca.controlled-replay.fixture/1' $fixture.schema 'fixture schema'
    $ids = @($fixture.cases | ForEach-Object { [string]$_.id })
    foreach ($required in @($fixture.requiredCases)) {
        Assert-True ($ids -contains [string]$required) ('required case missing: ' + $required)
    }
    Assert-Equal $ids.Count $manifest.caseCount 'case count must match the manifest'
    foreach ($case in @($fixture.cases)) {
        Assert-Equal @($case.events).Count $case.expect.eventCount ('event count for ' + $case.id)
        $strings = Get-Content -LiteralPath $fixturePath -Raw -Encoding UTF8
        Assert-True ($case.expect.finalTextSha256 -match '^[0-9a-f]{64}$') ('final text digest for ' + $case.id)
    }
    $shapes = @($fixture.contentShapes)
    foreach ($shape in @('chinese', 'surrogate-zwj-emoji', 'code-block', 'markdown-table', 'math', 'link', 'rich-text-100k')) {
        Assert-True ($shapes -contains $shape) ('content shape missing: ' + $shape)
    }
    $hundredK = $fixture.cases | Where-Object { $_.id -eq 'content-100k' }
    Assert-True ($hundredK.expect.finalTextLength -ge 100000) '100k stress fixture must be >= 100000 UTF-16 units'
    $duplicate = $fixture.cases | Where-Object { $_.id -eq 'duplicate-terminal' }
    Assert-Equal 2 $duplicate.expect.terminalCount 'duplicate-terminal must carry two terminal events'
    $late = $fixture.cases | Where-Object { $_.id -eq 'late-chunk-after-terminal' }
    Assert-Equal 1 $late.expect.lateTextCount 'late chunk case must carry one late text event'
}

Test-Fixture 'mutated fixture is rejected by the manifest digest' {
    $root = Join-Path $repo 'benchmark\src\main\assets\controlled-replay'
    $dir = New-FixtureTemp 'replay-mutation'
    $copy = Join-Path $dir 'stream-edge-cases.json'
    Copy-Item -LiteralPath (Join-Path $root 'stream-edge-cases.json') -Destination $copy
    $text = [IO.File]::ReadAllText($copy)
    [IO.File]::WriteAllText($copy,     $text.Replace('normal-small-chunks', 'normal-small-chunks-mutated'), (New-Object System.Text.UTF8Encoding($false)))
    $manifest = Get-Content -LiteralPath (Join-Path $root 'manifest.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $mutatedSha = (Get-FileHash -LiteralPath $copy -Algorithm SHA256).Hash.ToLowerInvariant()
    Assert-True ($mutatedSha -ne $manifest.fixtureSha256) 'mutated fixture must not match the manifest digest'
}

# ---------------------------------------------------------------------------------------------
# 9. exit-code propagation of the locked Gradle helpers (stub gradlew, no real Gradle)
# ---------------------------------------------------------------------------------------------
Test-Fixture 'tools/_build_locked.ps1 propagates the Gradle exit code' {
    $dir = New-FixtureTemp 'exitcode'
    New-Item -ItemType Directory -Force -Path (Join-Path $dir 'tools\build-guards') | Out-Null
    Write-FixtureText (Join-Path $dir 'tools\build-guards\McaBuildLock.psm1') @'
function Enter-McaBuildLock { param($Kind, $RetrySeconds, $RetryIntervalSeconds, $CommandLine) return @{ path = 'stub-lock' } }
function Exit-McaBuildLock { param($Lock) }
'@
    Copy-Item -LiteralPath (Join-Path $repo 'tools\_build_locked.ps1') -Destination (Join-Path $dir 'tools\_build_locked.ps1')
    $stub = Join-Path $dir 'gradlew.bat'
    Write-FixtureText $stub "@echo off`r`necho STUB GRADLE`r`nexit /b 7`r`n"
    $failed = Invoke-PowerShellScript -ScriptPath (Join-Path $dir 'tools\_build_locked.ps1')
    Assert-Equal 7 $failed.exitCode 'failed Gradle exit code must propagate'
    Assert-True ($failed.stdout -match 'gradle exit=7') 'the raw exit code must also be printed'
    Assert-True (Test-Path -LiteralPath (Join-Path $dir 'artifacts\build-locked.log')) 'the raw log must be kept'
    Write-FixtureText $stub "@echo off`r`necho STUB GRADLE OK`r`nexit /b 0`r`n"
    $ok = Invoke-PowerShellScript -ScriptPath (Join-Path $dir 'tools\_build_locked.ps1')
    Assert-Equal 0 $ok.exitCode 'successful stub must exit 0'
}

# ---------------------------------------------------------------------------------------------
# 10. measure-frames / verify-ui-quality offline failure branches (no adb, no Gradle)
# ---------------------------------------------------------------------------------------------
Test-Fixture 'measure-frames exits 2 on an unknown action' {
    $result = Invoke-PowerShellScript -ScriptPath (Join-Path $repo 'scripts\measure-frames.ps1') -Arguments @('-Actions', 'bogus')
    Assert-Equal 2 $result.exitCode 'unknown action must be a usage error'
    Assert-True ($result.stderr -match 'unknown action') 'reason must name the unknown action'
}

Test-Fixture 'measure-frames without adb writes evidence and exits 3 (BLOCKED)' {
    $dir = New-FixtureTemp 'measure-blocked'
    $out = Join-Path $dir 'evidence'
    $result = Invoke-PowerShellScript -ScriptPath (Join-Path $repo 'scripts\measure-frames.ps1') -Arguments @(
        '-AdbPath', (Join-Path $dir 'no-such-adb.exe'), '-OutDir', $out, '-Actions', 'drawer'
    )
    Assert-Equal 3 $result.exitCode 'missing adb must be BLOCKED (exit 3)'
    $summaryPath = Join-Path $out 'summary.json'
    Assert-True (Test-Path -LiteralPath $summaryPath) 'BLOCKED runs must still write summary.json'
    $summary = Get-Content -LiteralPath $summaryPath -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-Equal 'BLOCKED' $summary.verdict 'verdict'
    Assert-Equal 3 $summary.exitCode 'recorded exit code'
    Assert-True ($summary.evidenceWrite.written -eq $true) 'evidence write result must be recorded'
}

Test-Fixture 'verify-ui-quality macro lane without a device is BLOCKED, never PASS' {
    $dir = New-FixtureTemp 'ui-quality'
    $result = Invoke-PowerShellScript -ScriptPath (Join-Path $repo 'scripts\verify-ui-quality.ps1') -Arguments @(
        '-Lane', 'macro', '-OutputDirectory', $dir
    )
    # A run without a device may never be PASS. Two honest outcomes exist:
    #   exit 3 BLOCKED - nothing ran;
    #   exit 1 FAIL    - the runner noticed that the source tree changed during the run, which makes
    #                    the sample unattributable. That is a real detection, not a fixture failure,
    #                    so it is accepted only together with the lane that explains it.
    Assert-True ($result.exitCode -in @(3, 1)) ('a run without a device must never PASS; exit was ' + $result.exitCode)
    $runJson = @(Get-ChildItem -LiteralPath $dir -Recurse -File -Filter 'RUN.json')
    Assert-True ($runJson.Count -eq 1) 'RUN.json must be written'
    $run = Get-Content -LiteralPath $runJson[0].FullName -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($result.exitCode -eq 3) {
        Assert-Equal 'BLOCKED' $run.overall 'overall verdict'
    } else {
        Assert-Equal 'FAIL' $run.overall 'a changing tree must fail the run'
        $fingerprintLane = @($run.lanes | Where-Object { $_.lane -eq 'input-fingerprint' })[0]
        Assert-True ($null -ne $fingerprintLane) 'the changing-input lane must be recorded'
        Assert-True (([string]$fingerprintLane.reason) -match 'input-changed-during-run') 'the failure reason must name the changed input'
    }
    $macro = @($run.lanes | Where-Object { $_.lane -eq 'macro' })[0]
    Assert-Equal 'BLOCKED' $macro.status 'macro lane status'
    Assert-Equal 'BLOCKED' $macro.conclusions.execution 'execution conclusion'
    Assert-Equal 'NOT_EVALUATED' $macro.conclusions.performanceAcceptance 'performance must never be inferred from exit 0'
    $performance = @($run.lanes | Where-Object { $_.lane -eq 'macro-performance' })[0]
    Assert-Equal 'NOT_RUN' $performance.status 'performance lane must be NOT_RUN'
}

# ---------------------------------------------------------------------------------------------
# summary
# ---------------------------------------------------------------------------------------------
if (-not $KeepTemp) {
    foreach ($dir in $script:temps) {
        try { if (Test-Path -LiteralPath $dir) { Remove-Item -LiteralPath $dir -Recurse -Force } } catch { }
    }
} else {
    Write-Host 'temporary directories kept:'
    foreach ($dir in $script:temps) { Write-Host ('  ' + $dir) }
}

Write-Host ''
Write-Host ('fixtures passed: ' + $script:passed)
Write-Host ('fixtures failed: ' + $script:failed.Count)
if ($script:failed.Count -gt 0) {
    foreach ($item in $script:failed) { Write-Host ('  - ' + $item) -ForegroundColor Red }
    exit 1
}
exit 0
