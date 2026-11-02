function Get-McaFileSha256([string]$Path) {
    # The shared tree is edited by another workstream while observation runs. `Get-FileHash` fails
    # outright when a file is open with an exclusive write handle, which would abort the whole
    # manifest for a transient state. Retry briefly, then fail with the path so the caller can say
    # which file blocked the observation instead of surfacing a bare IOException.
    for ($attempt = 1; $attempt -le 4; $attempt++) {
        try { return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
        catch {
            if ($attempt -eq 4) { throw "cannot hash '$Path' after 4 attempts: $($_.Exception.Message)" }
            Start-Sleep -Milliseconds (150 * $attempt)
        }
    }
}

function Get-McaTextSha256([string]$Text) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Text)
        return ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
    } finally { $sha.Dispose() }
}

# A 64-character lowercase/uppercase hex digest, nothing else. Provenance comparisons must reject
# empty or truncated fingerprints instead of treating them as an equal/unknown value.
function Test-McaSha256Hex([string]$Value) {
    return ($Value -match '^[0-9a-fA-F]{64}$')
}

function Test-McaGitRevision([string]$Value) {
    return ($Value -match '^[0-9a-fA-F]{40}([0-9a-fA-F]{24})?$')
}

# Runs one git command without letting git's stderr text (progress, CRLF/autocrlf warnings) become
# a terminating NativeCommandError. Windows PowerShell 5.1 turns native stderr into an error when
# $ErrorActionPreference is 'Stop', which would abort provenance capture for a harmless warning.
# Success/failure is decided by the exit code only.
#
# stdout and stderr are returned separately. Only stdout may enter a fingerprint or a path list:
# git's CRLF warnings depend on the host's autocrlf setting, so folding them into the data would
# make an identical source tree hash differently per machine.
function Invoke-McaGitProcess {
    param(
        [string] $RepositoryPath,
        [string[]] $Arguments
    )
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        # A native command's stderr arrives here as ErrorRecord objects when the stream is merged;
        # stdout stays a plain string. That is what allows the split below without temp files.
        $merged = @(& git -C $RepositoryPath @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    $standardOut = @($merged | Where-Object {
            $_ -isnot [System.Management.Automation.ErrorRecord]
        } | ForEach-Object { [string]$_ })
    $standardError = @($merged | Where-Object {
            $_ -is [System.Management.Automation.ErrorRecord]
        } | ForEach-Object { [string]$_ })
    return [pscustomobject]@{ exitCode = $exitCode; output = $standardOut; error = $standardError }
}

# Runs one git command and refuses to continue when git itself failed. Every call site that reads
# a revision, a diff or a path list must use this: an empty result from a failed git invocation
# must never be recorded as "clean" or "no change".
function Invoke-McaGitCapture {
    param(
        [string] $RepositoryPath,
        [string[]] $Arguments,
        [string] $Purpose
    )
    $result = Invoke-McaGitProcess -RepositoryPath $RepositoryPath -Arguments $Arguments
    if ($result.exitCode -ne 0) {
        $detail = ((@($result.output) + @($result.error)) | ForEach-Object { [string]$_ }) -join ' '
        if ($detail.Length -gt 400) { $detail = $detail.Substring(0, 400) + '...' }
        throw ("Cannot freeze build provenance: git {0} failed (exit {1}) for {2}. {3}" -f
            ($Arguments -join ' '), $result.exitCode, $Purpose, $detail)
    }
    # The items are returned flat; every caller wraps the call in @() itself. The previous
    # `return , @(...)` nesting made `@(Invoke-McaGitCapture ...)` a ONE-element array even when git
    # printed nothing, so `.Count -gt 0` was always true. That turned the local-policy guard's
    # "is the protected file indexed" question into a permanent yes and aborted provenance capture.
    return @($result.output)
}

function Test-McaBuildInputPath([string]$RelativePath) {
    $path = $RelativePath.Replace('\', '/')
    if ($path -match '^third_party/MNN/project/android/build_[^/.]+(?:/(.*))?$') {
        $runtimeRelative = [string]$Matches[1]
        if ([string]::IsNullOrEmpty($runtimeRelative)) { return $true }
        $runtimeInputs = @('mca-mnn-runtime.properties', 'libMNN.so', 'libMNN_Express.so',
            'libMNN_CL.so', 'libllm.so', 'tools/cv/libMNNOpenCV.so', 'tools/audio/libMNNAudio.so',
            'OFF/arm64-v8a/libMNN.so', 'express/OFF/arm64-v8a/libMNN_Express.so',
            'source/backend/opencl/OFF/arm64-v8a/libMNN_CL.so',
            'tools/cv/OFF/arm64-v8a/libMNNOpenCV.so', 'tools/audio/OFF/arm64-v8a/libMNNAudio.so',
            'OFF/arm64-v8a/libllm.so')
        foreach ($inputPath in $runtimeInputs) {
            if ($runtimeRelative -eq $inputPath -or
                $inputPath.StartsWith("$runtimeRelative/", [StringComparison]::Ordinal)) { return $true }
        }
        return $false
    }
    if ($path -match '(^|/)(?!build-logic(?:/|$))build[-_][^/.]+(/|$)') { return $false }
    if ($path -match '(^|/)(CMakeFiles|__pycache__|\.pytest_cache|_deps)(/|$)') { return $false }
    if ($path -match '(^|/)(CMakeCache\.txt|cmake_install\.cmake|build\.ninja|\.ninja_(deps|log)|[^/]+\.(o|obj|o\.d|obj\.d|pyc|pyo))$') { return $false }
    if ($path -match '(^|/)(\.git|\.gradle|\.cxx|\.kotlin|\.idea|build|out|artifacts|toolchains|node_modules|\.venv|models|downloads)(/|$)') { return $false }
    if ($path -match '(^|/)(AGENTS\.md|UniversalDeviceAdmissionPolicyTest\.kt|local\.properties|key\.properties|keystore\.properties|\.env[^/]*|[^/]+\.(jks|keystore|p12|pem))$') { return $false }
    return $true
}

function Get-McaSourceProvenance([string]$ProjectRoot) {
    $head = (Invoke-McaGitCapture -RepositoryPath $ProjectRoot -Arguments @('rev-parse', 'HEAD') `
        -Purpose 'HEAD revision') -join "`n"
    $head = $head.Trim()
    if (-not (Test-McaGitRevision $head)) { throw "Cannot freeze build provenance: git HEAD is not a revision ('$head')." }
    $paths = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    $tracked = @(Invoke-McaGitCapture -RepositoryPath $ProjectRoot `
        -Arguments @('-c', 'core.quotepath=false', 'ls-files', '--cached') -Purpose 'tracked path list')
    $trackedSet = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($path in $tracked) {
        $relative = ([string]$path).Replace('\', '/')
        [void]$trackedSet.Add($relative)
        if (Test-McaBuildInputPath $relative) { [void]$paths.Add($relative) }
    }
    foreach ($name in @('build.gradle.kts', 'settings.gradle.kts', 'gradle.properties',
            'gradlew', 'gradlew.bat', '.gitmodules', 'CMakeLists.txt', 'Makefile')) {
        $candidate = Join-Path $ProjectRoot $name
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { [void]$paths.Add($name) }
    }
    # Include ignored inputs too: vendor binaries, generated sources and submodule contents can
    # enter an APK despite not appearing in the parent repository's untracked-file list.
    $scanRoots = @('app', 'api', 'core', 'feature', 'scripts', 'gradle', 'build-logic', 'vendor', 'third_party')
    $stack = [System.Collections.Generic.Stack[string]]::new()
    foreach ($root in $scanRoots) {
        $absolute = Join-Path $ProjectRoot $root
        if (Test-Path -LiteralPath $absolute -PathType Container) { $stack.Push($absolute) }
    }
    while ($stack.Count -gt 0) {
        foreach ($item in Get-ChildItem -LiteralPath $stack.Pop() -Force) {
            $relative = $item.FullName.Substring($ProjectRoot.Length).TrimStart('\', '/').Replace('\', '/')
            if (-not (Test-McaBuildInputPath $relative)) { continue }
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { continue }
            if ($item.PSIsContainer) { $stack.Push($item.FullName) } else { [void]$paths.Add($relative) }
        }
    }
    $sorted = [string[]]@($paths)
    [Array]::Sort($sorted, [System.StringComparer]::Ordinal)
    $manifest = foreach ($relative in $sorted) {
        $absolute = Join-Path $ProjectRoot $relative
        if (Test-Path -LiteralPath $absolute -PathType Leaf) {
            $file = Get-Item -LiteralPath $absolute
            [ordered]@{
                path = $relative
                bytes = $file.Length
                sha256 = Get-McaFileSha256 -Path $absolute
                parentTracked = $trackedSet.Contains($relative)
            }
        } elseif (-not (Test-Path -LiteralPath $absolute -PathType Container)) {
            [ordered]@{ path = $relative; bytes = $null; sha256 = 'missing'; parentTracked = $trackedSet.Contains($relative) }
        }
    }
    $canonical = ($manifest | ForEach-Object { "$($_.path)`t$($_.bytes)`t$($_.sha256)" }) -join "`n"
    $diff = @(Invoke-McaGitCapture -RepositoryPath $ProjectRoot -Arguments @(
        'diff', 'HEAD', '--binary', '--no-ext-diff', '--', '.',
        ':(exclude)AGENTS.md', ':(exclude)**/UniversalDeviceAdmissionPolicyTest.kt',
        ':(exclude)**/local.properties', ':(exclude)**/*.jks', ':(exclude)**/*.keystore'
    ) -Purpose 'tracked diff')
    $submodules = @()
    $moduleFile = Join-Path $ProjectRoot '.gitmodules'
    if (Test-Path -LiteralPath $moduleFile) {
        $moduleConfig = Invoke-McaGitProcess -RepositoryPath $ProjectRoot `
            -Arguments @('config', '--file', '.gitmodules', '--get-regexp', '^submodule\..*\.path$')
        $modulePaths = @($moduleConfig.output)
        $moduleConfigExit = $moduleConfig.exitCode
        # `git config --get-regexp` exits 1 when the pattern matches no key; that is an empty
        # submodule list, not a failure. Any other non-zero exit is a real error.
        if ($moduleConfigExit -notin @(0, 1)) {
            throw ("Cannot freeze build provenance: reading .gitmodules failed (exit {0})." -f $moduleConfigExit)
        }
        foreach ($entry in $modulePaths) {
            $relative = ([string]$entry -split '\s+', 2)[1]
            $absolute = Join-Path $ProjectRoot $relative
            $initialized = Test-Path -LiteralPath (Join-Path $absolute '.git')
            if ($initialized) {
                $moduleHead = ((Invoke-McaGitCapture -RepositoryPath $absolute `
                    -Arguments @('rev-parse', 'HEAD') -Purpose "submodule '$relative' HEAD") -join "`n").Trim()
                if (-not (Test-McaGitRevision $moduleHead)) {
                    throw "Cannot freeze build provenance: submodule '$relative' HEAD is not a revision ('$moduleHead')."
                }
                $moduleStatus = @(Invoke-McaGitCapture -RepositoryPath $absolute `
                    -Arguments @('status', '--porcelain=v1') -Purpose "submodule '$relative' status")
                $moduleDiff = @(Invoke-McaGitCapture -RepositoryPath $absolute `
                    -Arguments @('diff', 'HEAD', '--binary', '--no-ext-diff') -Purpose "submodule '$relative' diff")
                $submodules += [ordered]@{
                    path = $relative; initialized = $true; revision = $moduleHead
                    status = $moduleStatus; diffSha256 = Get-McaTextSha256 ($moduleDiff -join "`n")
                }
            } else {
                $submodules += [ordered]@{ path = $relative; initialized = $false; revision = $null; status = @(); diffSha256 = $null }
            }
        }
    }
    $guards = @('AGENTS.md', 'app/src/test/java/com/muyuchat/mca/UniversalDeviceAdmissionPolicyTest.kt')
    $excludeResult = Invoke-McaGitProcess -RepositoryPath $ProjectRoot -Arguments @('rev-parse', '--git-path', 'info/exclude')
    if ($excludeResult.exitCode -ne 0) {
        throw ("Cannot freeze build provenance: git rev-parse --git-path failed (exit {0})." -f $excludeResult.exitCode)
    }
    $excludePath = ([string](@($excludeResult.output) -join "`n")).Trim()
    if (-not [IO.Path]::IsPathRooted($excludePath)) { $excludePath = Join-Path $ProjectRoot $excludePath }
    $excludeLines = @(Get-Content -LiteralPath $excludePath | ForEach-Object { $_.Trim().Replace('\', '/').TrimStart('/') })
    $guardFacts = foreach ($guard in $guards) {
        $ignoreResult = Invoke-McaGitProcess -RepositoryPath $ProjectRoot -Arguments @('check-ignore', '--', $guard)
        $ignoreExit = $ignoreResult.exitCode
        if ($ignoreExit -notin @(0, 1)) {
            throw ("Cannot freeze build provenance: git check-ignore failed (exit {0}) for {1}." -f $ignoreExit, $guard)
        }
        $indexed = @(Invoke-McaGitCapture -RepositoryPath $ProjectRoot `
            -Arguments @('ls-files', '--stage', '--', $guard) -Purpose "index state of $guard")
        $fact = [ordered]@{
            path = $guard; exists = Test-Path -LiteralPath (Join-Path $ProjectRoot $guard)
            localExcluded = $guard -in $excludeLines; ignored = $ignoreExit -eq 0; indexed = $indexed.Count -gt 0
        }
        if (-not $fact.exists -or -not $fact.localExcluded -or -not $fact.ignored -or $fact.indexed) {
            throw "Local-only policy guard is missing, indexed, or absent from .git/info/exclude: $guard"
        }
        $fact
    }
    $diffSummary = @(Invoke-McaGitCapture -RepositoryPath $ProjectRoot `
        -Arguments @('diff', 'HEAD', '--stat') -Purpose 'tracked diff summary')
    return [ordered]@{
        schema = 'mca.build-source-provenance.v1'
        head = $head
        worktreeSha256 = Get-McaTextSha256 $canonical
        manifestSha256 = Get-McaTextSha256 $canonical
        trackedDiffSha256 = Get-McaTextSha256 ($diff -join "`n")
        trackedDiffSummary = $diffSummary
        manifest = @($manifest)
        submodules = $submodules
        localPolicyGuards = @($guardFacts)
        scope = 'Sorted file bytes outside build/cache/model/credential/local-only safeguard paths; includes untracked and ignored files under production, vendor, scripts, Gradle and initialized submodule roots.'
    }
}

# Compares two captures of the SAME algorithm (Get-McaSourceProvenance). The result names the
# inputs that changed so a build log can say what invalidated it instead of only "changed".
function Compare-McaSourceProvenance {
    param($Before, $After)
    if ($null -eq $Before -or $null -eq $After) {
        throw 'Source provenance comparison needs two captured snapshots.'
    }
    $reasons = New-Object System.Collections.Generic.List[string]
    $changedPaths = New-Object System.Collections.Generic.List[string]
    foreach ($field in @('head', 'worktreeSha256', 'manifestSha256', 'trackedDiffSha256')) {
        if ([string]$Before[$field] -ne [string]$After[$field]) {
            $reasons.Add(("{0} changed: '{1}' -> '{2}'" -f $field, $Before[$field], $After[$field])) | Out-Null
        }
    }
    $beforeSubmodules = @($Before['submodules'] | ConvertTo-Json -Depth 8 -Compress)
    $afterSubmodules = @($After['submodules'] | ConvertTo-Json -Depth 8 -Compress)
    if (($beforeSubmodules -join "`n") -ne ($afterSubmodules -join "`n")) {
        $reasons.Add('submodule revisions/status/diffs changed') | Out-Null
    }
    $beforeMap = @{}
    foreach ($row in @($Before['manifest'])) { if ($row) { $beforeMap[[string]$row.path] = [string]$row.sha256 } }
    $afterMap = @{}
    foreach ($row in @($After['manifest'])) { if ($row) { $afterMap[[string]$row.path] = [string]$row.sha256 } }
    foreach ($key in @($beforeMap.Keys + $afterMap.Keys | Sort-Object -Unique)) {
        $left = $beforeMap[$key]
        $right = $afterMap[$key]
        if ($left -ne $right) {
            $changedPaths.Add(("{0} ({1} -> {2})" -f $key, $(if ($left) { $left } else { 'absent' }), $(if ($right) { $right } else { 'absent' }))) | Out-Null
        }
    }
    return [ordered]@{
        algorithm = 'mca.source-provenance.compare.v1'
        equal = ($reasons.Count -eq 0)
        reasons = @($reasons.ToArray())
        changedPaths = @($changedPaths.ToArray())
        beforeWorktreeSha256 = [string]$Before['worktreeSha256']
        afterWorktreeSha256 = [string]$After['worktreeSha256']
    }
}

# Finds the newest previously written passing build record whose APK SHA-256 matches the binary
# that already exists. This is what `release.ps1 -SkipBuild` may reuse: it proves that a trusted
# prior build produced exactly this APK, so the original source binding stays intact. It never
# re-labels a fresh workspace observation as the binary's source.
function Select-McaTrustedSourceBinding {
    param(
        [string] $RecordRoot,
        [string] $ApkSha256,
        [string[]] $Schemas = @('mca.release-build-record/1')
    )
    if (-not (Test-McaSha256Hex $ApkSha256)) {
        throw "Existing binary SHA-256 is not a 64-hex digest ('$ApkSha256')."
    }
    if (-not $RecordRoot -or -not (Test-Path -LiteralPath $RecordRoot -PathType Container)) {
        return [ordered]@{ status = 'unverified'; reason = 'no build record directory exists for this repository' }
    }
    $candidates = @(Get-ChildItem -LiteralPath $RecordRoot -Filter '*.json' -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTimeUtc -Descending)
    foreach ($candidate in $candidates) {
        try { $record = [IO.File]::ReadAllText($candidate.FullName, [Text.Encoding]::UTF8) | ConvertFrom-Json }
        catch { continue }
        if ($null -eq $record) { continue }
        $schema = [string]$record.schema
        if ($Schemas -notcontains $schema) { continue }
        if ([string]$record.status -ne 'passed') { continue }
        $recordSha = [string]$record.artifacts.apk.sha256
        if (-not (Test-McaSha256Hex $recordSha)) { continue }
        if ($recordSha.ToLowerInvariant() -ne $ApkSha256.ToLowerInvariant()) { continue }
        $source = $record.source
        if (-not $source -or -not (Test-McaSha256Hex ([string]$source.worktreeSha256)) -or
            -not (Test-McaGitRevision ([string]$source.head))) { continue }
        return [ordered]@{
            status = 'bound'
            recordPath = (Resolve-Path -LiteralPath $candidate.FullName).Path
            recordSha256 = (Get-FileHash -LiteralPath $candidate.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            recordRunId = [string]$record.runId
            recordSchema = $schema
            apkSha256 = $recordSha.ToLowerInvariant()
            source = $source
            reason = 'Existing binary matches a previously passing build record; original source binding preserved.'
        }
    }
    return [ordered]@{
        status = 'unverified'
        reason = 'No passing build record with this APK SHA-256 exists; the existing binary cannot be attributed to source.'
    }
}

function Test-McaDspArtifactPath([string]$Path) {
    if ($Path -notmatch '/arm64-v8a/') { return $false }
    return $Path -match '(?i)skel\.so$' -or
        $Path -match '^lib/arm64-v8a/libggml-htp(?:-mca)?-v\d+\.so$' -or
        $Path -match '^lib/arm64-v8a/libQnnHtpV\d+\.so$' -or
        $Path -eq 'lib/arm64-v8a/libQnnHtpV73QemuDriver.so'
}

function Get-McaElfArtifactFact([string]$Path, [byte[]]$Bytes, [string]$ReadElfPath, [int]$ExpectedMachine) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $hash = ([BitConverter]::ToString($sha.ComputeHash($Bytes))).Replace('-', '').ToLowerInvariant() } finally { $sha.Dispose() }
    $elf = $Bytes.Length -ge 20 -and $Bytes[0] -eq 127 -and $Bytes[1] -eq 69 -and $Bytes[2] -eq 76 -and $Bytes[3] -eq 70
    $machine = if ($elf) { [BitConverter]::ToUInt16($Bytes, 18) } else { $null }
    $needed = @()
    $soname = $null
    $dependencyStatus = 'not_run_readelf_unavailable'
    if ($ReadElfPath -and $elf) {
        $temporary = Join-Path ([IO.Path]::GetTempPath()) ("mca-elf-" + [Guid]::NewGuid().ToString('N') + '.so')
        try {
            [IO.File]::WriteAllBytes($temporary, $Bytes)
            $dynamic = @(& $ReadElfPath --dynamic $temporary 2>&1)
            $dependencyStatus = if ($LASTEXITCODE -eq 0) { 'read' } else { 'readelf_failed' }
            foreach ($line in $dynamic) {
                if ([string]$line -match '\(NEEDED\).*\[([^\]]+)\]') { $needed += $Matches[1] }
                if ([string]$line -match '\(SONAME\).*\[([^\]]+)\]') { $soname = $Matches[1] }
            }
        } finally { if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary } }
    }
    return [ordered]@{
        path = $Path; bytes = $Bytes.Length; sha256 = $hash; elf = $elf
        machine = $machine; expectedMachine = $ExpectedMachine; soname = $soname
        needed = $needed; dependencyStatus = $dependencyStatus
    }
}

function Get-McaApkNativeManifest($Archive, [string]$ReadElfPath) {
    $facts = @()
    foreach ($entry in @($Archive.Entries | Where-Object { $_.FullName -match '^(lib|assets)/.*\.so$' } | Sort-Object FullName)) {
        $stream = $entry.Open()
        $memory = [IO.MemoryStream]::new()
        try { $stream.CopyTo($memory); $bytes = $memory.ToArray() } finally { $stream.Dispose(); $memory.Dispose() }
        $expected = if (Test-McaDspArtifactPath $entry.FullName) { 164 } elseif ($entry.FullName -match '/arm64-v8a/') { 183 } elseif ($entry.FullName -match '/x86_64/') { 62 } else { 0 }
        $facts += Get-McaElfArtifactFact $entry.FullName $bytes $ReadElfPath $expected
    }
    return $facts
}

function Get-McaDshPayloadNativeManifest($Archive, [string]$ReadElfPath) {
    $payloadEntry = $Archive.GetEntry('assets/dsh/payload.zip')
    if (-not $payloadEntry) { return @() }

    $temporaryArchive = Join-Path ([IO.Path]::GetTempPath()) ("mca-dsh-payload-" + [Guid]::NewGuid().ToString('N') + '.zip')
    $payloadStream = $payloadEntry.Open()
    $fileStream = [IO.File]::Create($temporaryArchive)
    try { $payloadStream.CopyTo($fileStream) } finally { $fileStream.Dispose(); $payloadStream.Dispose() }

    $payload = [IO.Compression.ZipFile]::OpenRead($temporaryArchive)
    try {
        $runtimeLibraries = @($payload.Entries | Where-Object { $_.FullName -match '^runtime/lib/[^/]+\.so$' } | Sort-Object FullName)
        $facts = @()
        foreach ($entry in $runtimeLibraries) {
            $stream = $entry.Open()
            $memory = [IO.MemoryStream]::new()
            try { $stream.CopyTo($memory); $bytes = $memory.ToArray() } finally { $stream.Dispose(); $memory.Dispose() }
            $path = 'dsh-payload/arm64-v8a/' + $entry.FullName
            $facts += Get-McaElfArtifactFact $path $bytes $ReadElfPath 183
        }
        return [ordered]@{
            size = (Get-Item -LiteralPath $temporaryArchive).Length
            sha256 = (Get-FileHash -LiteralPath $temporaryArchive -Algorithm SHA256).Hash.ToLowerInvariant()
            artifacts = @($facts)
        }
    } finally {
        $payload.Dispose()
        if (Test-Path -LiteralPath $temporaryArchive) { Remove-Item -LiteralPath $temporaryArchive }
    }
}
