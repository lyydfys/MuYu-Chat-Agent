# The shared provenance helpers are loaded HERE, at load time. Several functions below call
# Test-McaGitRevision / Invoke-McaGitCapture / Get-McaTextSha256, and relying on the caller having
# dot-sourced BuildProvenance.ps1 first made the binding path fail with "Test-McaGitRevision is not
# recognized" whenever a consumer (measure-frames.ps1) only used the workspace function first.
. (Join-Path $PSScriptRoot 'BuildProvenance.ps1')

# Read-only attribution helpers for measure-frames.ps1. No device/UI mutation occurs here, and
# nothing in this file writes into the repository.
#
# Three separate facts are recorded, never merged:
#   1. the current workspace observation (what the source tree looks like NOW);
#   2. the installed APK identity (what binary is on the device NOW);
#   3. the build-record binding (which source produced that binary, proven by a passing record).
# (1) and (2) can look identical and still not prove (3).

function Test-McaFrameHash([string]$Value) {
    return ($Value -match '^[0-9a-fA-F]{64}$')
}

function Get-McaFrameWorkspaceEvidence {
    param([string] $RepoRoot)
    $head = ((Invoke-McaGitCapture -RepositoryPath $RepoRoot -Arguments @('rev-parse', 'HEAD') `
        -Purpose 'frame workspace HEAD') -join "`n").Trim()
    if (-not (Test-McaGitRevision $head)) { throw "Workspace HEAD is not a revision ('$head')." }
    $diff = @(Invoke-McaGitCapture -RepositoryPath $RepoRoot -Arguments @(
        'diff', 'HEAD', '--binary', '--no-ext-diff', '--', '.',
        ':(exclude)AGENTS.md', ':(exclude)**/UniversalDeviceAdmissionPolicyTest.kt'
    ) -Purpose 'frame workspace tracked diff')
    $untracked = @(Invoke-McaGitCapture -RepositoryPath $RepoRoot `
        -Arguments @('-c', 'core.quotepath=false', 'ls-files', '--others', '--exclude-standard') `
        -Purpose 'frame workspace untracked paths')
    $inputRows = foreach ($relative in ($untracked | Sort-Object)) {
        if (-not (Test-McaBuildInputPath $relative)) { continue }
        $path = Join-Path $RepoRoot $relative
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
            "$relative`t$hash"
        }
    }
    $modules = @()
    if (Test-Path -LiteralPath (Join-Path $RepoRoot '.gitmodules')) {
        $moduleConfig = Invoke-McaGitProcess -RepositoryPath $RepoRoot `
            -Arguments @('config', '--file', '.gitmodules', '--get-regexp', '^submodule\..*\.path$')
        $moduleEntries = @($moduleConfig.output)
        $moduleConfigExit = $moduleConfig.exitCode
        if ($moduleConfigExit -notin @(0, 1)) {
            throw ("Cannot record frame source: reading .gitmodules failed (exit {0})." -f $moduleConfigExit)
        }
        foreach ($entry in $moduleEntries) {
            $relative = ([string]$entry -split '\s+', 2)[1]
            $path = Join-Path $RepoRoot $relative
            if (-not (Test-Path -LiteralPath (Join-Path $path '.git'))) {
                $modules += [ordered]@{ path = $relative; initialized = $false; revision = $null; diffSha256 = $null; status = @() }
                continue
            }
            $revision = ((Invoke-McaGitCapture -RepositoryPath $path -Arguments @('rev-parse', 'HEAD') `
                -Purpose "frame submodule '$relative' HEAD") -join "`n").Trim()
            if (-not (Test-McaGitRevision $revision)) {
                throw "Cannot record frame source: submodule '$relative' HEAD is not a revision ('$revision')."
            }
            $moduleDiff = @(Invoke-McaGitCapture -RepositoryPath $path `
                -Arguments @('diff', 'HEAD', '--binary', '--no-ext-diff') -Purpose "frame submodule '$relative' diff")
            $moduleStatus = @(Invoke-McaGitCapture -RepositoryPath $path `
                -Arguments @('status', '--porcelain=v1') -Purpose "frame submodule '$relative' status")
            $diffSha = Get-McaTextSha256 ($moduleDiff -join "`n")
            if (-not (Test-McaFrameHash $diffSha)) { throw "Cannot record frame source: submodule '$relative' diff hash is invalid." }
            $modules += [ordered]@{
                path = $relative
                initialized = $true
                revision = $revision
                diffSha256 = $diffSha
                status = $moduleStatus
            }
        }
    }
    $trackedDiffSha256 = Get-McaTextSha256 ($diff -join "`n")
    $untrackedInputSha256 = Get-McaTextSha256 ($inputRows -join "`n")
    if (-not (Test-McaFrameHash $trackedDiffSha256) -or -not (Test-McaFrameHash $untrackedInputSha256)) {
        throw 'Cannot record frame source: workspace fingerprints are not 64-hex digests.'
    }
    return [ordered]@{
        algorithm = 'mca.frame-workspace-observation.v1'
        head = $head
        trackedDiffSha256 = $trackedDiffSha256
        untrackedInputSha256 = $untrackedInputSha256
        untrackedInputCount = @($inputRows).Count
        submodules = $modules
        apkSourceProven = $false
        scope = 'Observation of the current workspace, not proof of the installed APK source. Excludes ignored inputs; only a matching passing build record binds an installed binary to source.'
    }
}

function Get-McaFrameApkEvidence {
    param([string] $Adb, [string] $Serial, [string] $AppPackage)
    $paths = Invoke-AdbCapture -Adb $Adb -Arguments @('-s', $Serial, 'shell', 'pm', 'path', $AppPackage)
    if ($paths.exitCode -ne 0) { throw 'Cannot attribute installed package: pm path failed.' }
    $apks = @()
    foreach ($line in ($paths.text -split "`r?`n")) {
        if ($line -notmatch '^package:(/[^\r\n]+\.apk)\s*$') { continue }
        $path = $Matches[1]
        # adb shell joins arguments remotely. Only package-manager APK paths are admissible.
        if ($path -notmatch '^/[A-Za-z0-9_./=+@~-]+\.apk$') { throw 'Unexpected APK path from package manager.' }
        $capture = Invoke-AdbCapture -Adb $Adb -Arguments @('-s', $Serial, 'shell', 'sha256sum', $path)
        if ($capture.exitCode -ne 0 -or $capture.text -notmatch '(?im)^([0-9a-f]{64})\s') {
            throw 'Cannot hash the installed APK; frame attribution remains unverified.'
        }
        $apks += [ordered]@{ path = $path; sha256 = $Matches[1].ToLowerInvariant() }
    }
    if ($apks.Count -eq 0) { throw 'Cannot attribute installed package: no APK paths.' }
    return $apks
}

# Normalizes one build record into a read-only binding object. The debug record
# (scripts/build.ps1, schemaVersion 1 + apkSha256 + sourceProvenance) and the Release record
# (scripts/release.ps1, mca.release-build-record/1 + artifacts.apk.sha256 + source) use different
# fields; this is the ONLY place that knows both layouts. Unknown schemas, non-passing records,
# invalid 64-hex fingerprints and split installs are rejected with a specific reason - no field is
# guessed and no missing value is filled in.
function ConvertTo-McaFrameBuildBinding {
    param(
        $Record,
        [object[]] $InstalledApks,
        [string] $RecordPath = '',
        [string] $RecordSha256 = ''
    )
    if ($null -eq $Record) { throw 'Build record is empty.' }
    $variant = [string]$Record.variant
    $schema = [string]$Record.schema
    $schemaVersion = $Record.schemaVersion
    $normalized = $null
    if ($schema -eq 'mca.release-build-record/1') {
        $normalized = [ordered]@{
            layout = 'release'
            variant = $variant
            apkSha256 = [string]$Record.artifacts.apk.sha256
            sourceHead = [string]$Record.source.head
            sourceWorktreeSha256 = [string]$Record.source.worktreeSha256
            sourceTrackedDiffSha256 = [string]$Record.source.trackedDiffSha256
            sourceManifestSha256 = [string]$Record.source.manifestSha256
            submodules = $Record.source.submodules
            bindingOrigin = [string]$Record.source.bindingOrigin
            bindingStatus = [string]$Record.source.bindingStatus
        }
        if ($variant -ne 'release') {
            throw ("Build record schema 'mca.release-build-record/1' declares variant '{0}', expected 'release'." -f $variant)
        }
    } elseif ($null -ne $schemaVersion -and [string]$schemaVersion -eq '1' -and $schema -eq '') {
        $normalized = [ordered]@{
            layout = 'debug'
            variant = $variant
            apkSha256 = [string]$Record.apkSha256
            sourceHead = [string]$Record.sourceProvenance.head
            sourceWorktreeSha256 = [string]$Record.sourceProvenance.worktreeSha256
            sourceTrackedDiffSha256 = [string]$Record.sourceProvenance.trackedDiffSha256
            sourceManifestSha256 = [string]$Record.sourceProvenance.manifestSha256
            submodules = $Record.sourceProvenance.submodules
            bindingOrigin = 'record-own'
            bindingStatus = 'bound'
        }
        if ($variant -ne 'debug') {
            throw ("Debug build record declares variant '{0}', expected 'debug'." -f $variant)
        }
    } else {
        throw ("Unsupported build record schema (schema='{0}', schemaVersion='{1}', variant='{2}'); expected mca.release-build-record/1 or schemaVersion 1 debug record." -f
            $schema, [string]$schemaVersion, $variant)
    }
    if ([string]$Record.status -ne 'passed') {
        throw ("Build record status is '{0}', not 'passed'; it cannot bind an installed binary to source." -f [string]$Record.status)
    }
    if ($normalized.bindingStatus -and $normalized.bindingStatus -ne 'bound') {
        throw ("Build record source binding is '{0}', not 'bound'; this record does not prove which source produced the APK." -f $normalized.bindingStatus)
    }
    if (-not (Test-McaFrameHash $normalized.apkSha256)) {
        throw ("Build record APK SHA-256 is not a 64-hex digest ('{0}')." -f $normalized.apkSha256)
    }
    if (-not (Test-McaGitRevision $normalized.sourceHead)) {
        throw ("Build record source HEAD is not a revision ('{0}')." -f $normalized.sourceHead)
    }
    if (-not (Test-McaFrameHash $normalized.sourceWorktreeSha256)) {
        throw ("Build record source fingerprint is not a 64-hex digest ('{0}'); the record cannot prove its source." -f $normalized.sourceWorktreeSha256)
    }
    if ($normalized.sourceManifestSha256 -and -not (Test-McaFrameHash $normalized.sourceManifestSha256)) {
        throw ("Build record manifest fingerprint is not a 64-hex digest ('{0}')." -f $normalized.sourceManifestSha256)
    }
    foreach ($module in @($normalized.submodules)) {
        if ($null -eq $module) { continue }
        if (-not (Test-McaGitRevision ([string]$module.revision))) {
            throw ("Build record submodule '{0}' revision is not a revision ('{1}'); dirty submodule builds are not reproducible from the parent SHA." -f
                [string]$module.path, [string]$module.revision)
        }
    }
    $apks = @($InstalledApks)
    if ($apks.Count -eq 0) { throw 'No installed APK was supplied for binding.' }
    if ($apks.Count -gt 1) {
        throw ("Installed package is split into {0} APKs ({1}). Split APK installs are not supported by this binding: matching only base.apk is not sufficient and a complete split manifest is required (status: BLOCKED)." -f
            $apks.Count, (($apks | ForEach-Object { [string]$_.path }) -join ', '))
    }
    if ([string]$apks[0].sha256 -ne $normalized.apkSha256.ToLowerInvariant()) {
        throw ("Installed APK SHA-256 {0} does not match build record {1} ({2})." -f
            [string]$apks[0].sha256, $normalized.apkSha256.ToLowerInvariant(), $normalized.layout)
    }
    return [ordered]@{
        status = 'PASS'
        layout = $normalized.layout
        recordPath = $RecordPath
        recordSha256 = $RecordSha256
        apkSha256 = $normalized.apkSha256.ToLowerInvariant()
        installedApkPath = [string]$apks[0].path
        variant = $normalized.variant
        sourceRevision = $normalized.sourceHead
        sourceWorktreeSha256 = $normalized.sourceWorktreeSha256.ToLowerInvariant()
        sourceTrackedDiffSha256 = ([string]$normalized.sourceTrackedDiffSha256).ToLowerInvariant()
        sourceManifestSha256 = ([string]$normalized.sourceManifestSha256).ToLowerInvariant()
        # bindingStatus is the proof that the record bound this source to this binary; bindingOrigin
        # only says which run produced that binding. Both are carried through, neither is invented.
        bindingStatus = [string]$normalized.bindingStatus
        bindingOrigin = $normalized.bindingOrigin
        submodules = @($normalized.submodules)
    }
}

function Get-McaFrameBuildBinding {
    param([string] $BuildRecordPath, [object[]] $InstalledApks)
    if (-not $BuildRecordPath) {
        return [ordered]@{
            status = 'NOT_RUN'
            reason = 'No controlled build record supplied; installed APK hashes identify the binary, not its source.'
        }
    }
    if (-not (Test-Path -LiteralPath $BuildRecordPath -PathType Leaf)) {
        throw ("Build record does not exist: {0}" -f $BuildRecordPath)
    }
    try { $record = [IO.File]::ReadAllText($BuildRecordPath, [Text.Encoding]::UTF8) | ConvertFrom-Json }
    catch { throw ("Build record is not valid JSON: {0} ({1})" -f $BuildRecordPath, $_.Exception.Message) }
    $resolved = (Resolve-Path -LiteralPath $BuildRecordPath).Path
    $recordSha = (Get-FileHash -LiteralPath $resolved -Algorithm SHA256).Hash.ToLowerInvariant()
    return ConvertTo-McaFrameBuildBinding -Record $record -InstalledApks $InstalledApks `
        -RecordPath $resolved -RecordSha256 $recordSha
}

function Save-McaFrameEnvironment {
    param([string] $Adb, [string] $Serial, [string] $EvidenceDirectory)
    $facts = [ordered]@{}
    $commands = [ordered]@{
        display = @('shell', 'dumpsys', 'display')
        animatorScale = @('shell', 'settings', 'get', 'global', 'animator_duration_scale')
        transitionScale = @('shell', 'settings', 'get', 'global', 'transition_animation_scale')
        windowScale = @('shell', 'settings', 'get', 'global', 'window_animation_scale')
        fontScale = @('shell', 'settings', 'get', 'system', 'font_scale')
        peakRefreshSetting = @('shell', 'settings', 'get', 'system', 'peak_refresh_rate')
        minRefreshSetting = @('shell', 'settings', 'get', 'system', 'min_refresh_rate')
        battery = @('shell', 'dumpsys', 'battery')
        thermal = @('shell', 'dumpsys', 'thermalservice')
    }
    foreach ($key in $commands.Keys) {
        $capture = Invoke-AdbCapture -Adb $Adb -Arguments (@('-s', $Serial) + $commands[$key])
        if ($key -in @('display', 'thermal', 'battery')) {
            $path = Join-Path $EvidenceDirectory ($key + '.txt')
            [IO.File]::WriteAllText($path, $capture.text, [Text.UTF8Encoding]::new($false))
            $facts[$key] = [ordered]@{ exitCode = $capture.exitCode; path = $path }
        } else {
            $facts[$key] = [ordered]@{ exitCode = $capture.exitCode; value = $capture.text.Trim() }
        }
    }
    $facts['refreshScope'] = 'Display state is a point-in-time snapshot. User peak/min settings are not the actual per-frame refresh rate; deadline/120Hz acceptance requires frame timeline evidence.'
    $facts['powerScope'] = 'Battery/thermal facts are recorded for comparability only; they are not used to accept or reject the sample.'
    return $facts
}

# Writes summary.json. A failed evidence write is a failed run: the caller must exit non-zero
# rather than printing the verdict and continuing.
function Save-McaFrameSummary {
    param([string] $Path, $State)
    try {
        $json = $State | ConvertTo-Json -Depth 12
        $directory = Split-Path -Parent $Path
        if ($directory -and -not (Test-Path -LiteralPath $directory -PathType Container)) {
            throw "Evidence directory does not exist: $directory"
        }
        [System.IO.File]::WriteAllText($Path, $json, (New-Object System.Text.UTF8Encoding($false)))
        return [ordered]@{ written = $true; path = $Path; bytes = (Get-Item -LiteralPath $Path).Length }
    } catch {
        return [ordered]@{ written = $false; path = $Path; error = $_.Exception.Message }
    }
}
