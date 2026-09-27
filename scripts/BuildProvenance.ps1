function Get-McaTextSha256([string]$Text) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Text)
        return ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
    } finally { $sha.Dispose() }
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
    $head = & git -C $ProjectRoot rev-parse HEAD 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot freeze build provenance: git HEAD is unavailable.' }
    $paths = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    $tracked = @(& git -C $ProjectRoot -c core.quotepath=false ls-files --cached)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot freeze build provenance: tracked paths are unavailable.' }
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
                sha256 = (Get-FileHash -LiteralPath $absolute -Algorithm SHA256).Hash.ToLowerInvariant()
                parentTracked = $trackedSet.Contains($relative)
            }
        } elseif (-not (Test-Path -LiteralPath $absolute -PathType Container)) {
            [ordered]@{ path = $relative; bytes = $null; sha256 = 'missing'; parentTracked = $trackedSet.Contains($relative) }
        }
    }
    $canonical = ($manifest | ForEach-Object { "$($_.path)`t$($_.bytes)`t$($_.sha256)" }) -join "`n"
    $diff = @(& git -C $ProjectRoot diff HEAD --binary --no-ext-diff -- . ':(exclude)AGENTS.md' ':(exclude)**/UniversalDeviceAdmissionPolicyTest.kt' ':(exclude)**/local.properties' ':(exclude)**/*.jks' ':(exclude)**/*.keystore')
    if ($LASTEXITCODE -ne 0) { throw 'Cannot freeze build provenance: tracked diff is unavailable.' }
    $submodules = @()
    $moduleFile = Join-Path $ProjectRoot '.gitmodules'
    if (Test-Path -LiteralPath $moduleFile) {
        $modulePaths = @(& git -C $ProjectRoot config --file .gitmodules --get-regexp '^submodule\..*\.path$')
        foreach ($entry in $modulePaths) {
            $relative = ([string]$entry -split '\s+', 2)[1]
            $absolute = Join-Path $ProjectRoot $relative
            $initialized = Test-Path -LiteralPath (Join-Path $absolute '.git')
            if ($initialized) {
                $moduleHead = & git -C $absolute rev-parse HEAD 2>$null
                $moduleStatus = @(& git -C $absolute status --porcelain=v1)
                $moduleDiff = @(& git -C $absolute diff HEAD --binary --no-ext-diff)
                $submodules += [ordered]@{
                    path = $relative; initialized = $true; revision = ([string]$moduleHead).Trim()
                    status = $moduleStatus; diffSha256 = Get-McaTextSha256 ($moduleDiff -join "`n")
                }
            } else {
                $submodules += [ordered]@{ path = $relative; initialized = $false; revision = $null; status = @(); diffSha256 = $null }
            }
        }
    }
    $guards = @('AGENTS.md', 'app/src/test/java/com/muyuchat/mca/UniversalDeviceAdmissionPolicyTest.kt')
    $excludePath = ([string](& git -C $ProjectRoot rev-parse --git-path info/exclude)).Trim()
    if (-not [IO.Path]::IsPathRooted($excludePath)) { $excludePath = Join-Path $ProjectRoot $excludePath }
    $excludeLines = @(Get-Content -LiteralPath $excludePath | ForEach-Object { $_.Trim().Replace('\', '/').TrimStart('/') })
    $guardFacts = foreach ($guard in $guards) {
        $ignored = & git -C $ProjectRoot check-ignore -- $guard 2>$null
        $ignoreExit = $LASTEXITCODE
        $indexed = @(& git -C $ProjectRoot ls-files --stage -- $guard)
        $fact = [ordered]@{
            path = $guard; exists = Test-Path -LiteralPath (Join-Path $ProjectRoot $guard)
            localExcluded = $guard -in $excludeLines; ignored = $ignoreExit -eq 0; indexed = $indexed.Count -gt 0
        }
        if (-not $fact.exists -or -not $fact.localExcluded -or -not $fact.ignored -or $fact.indexed) {
            throw "Local-only policy guard is missing, indexed, or absent from .git/info/exclude: $guard"
        }
        $fact
    }
    return [ordered]@{
        schema = 'mca.build-source-provenance.v1'
        head = ([string]$head).Trim()
        worktreeSha256 = Get-McaTextSha256 $canonical
        trackedDiffSha256 = Get-McaTextSha256 ($diff -join "`n")
        trackedDiffSummary = @(& git -C $ProjectRoot diff HEAD --stat)
        manifest = @($manifest)
        submodules = $submodules
        localPolicyGuards = @($guardFacts)
        scope = 'Sorted file bytes outside build/cache/model/credential/local-only safeguard paths; includes untracked and ignored files under production, vendor, scripts, Gradle and initialized submodule roots.'
    }
}

function Test-McaDspArtifactPath([string]$Path) {
    if ($Path -notmatch '/arm64-v8a/') { return $false }
    return $Path -match '(?i)skel\.so$' -or
        $Path -match '^lib/arm64-v8a/libggml-htp-v(?:68|69|73|75|79|81)\.so$' -or
        $Path -match '^lib/arm64-v8a/libQnnHtpV(?:68|69|73|75|79|81)\.so$' -or
        $Path -eq 'lib/arm64-v8a/libQnnHtpV73QemuDriver.so'
}

function Get-McaApkNativeManifest($Archive, [string]$ReadElfPath) {
    $facts = @()
    foreach ($entry in @($Archive.Entries | Where-Object { $_.FullName -match '^(lib|assets)/.*\.so$' } | Sort-Object FullName)) {
        $stream = $entry.Open()
        $memory = [IO.MemoryStream]::new()
        try { $stream.CopyTo($memory); $bytes = $memory.ToArray() } finally { $stream.Dispose(); $memory.Dispose() }
        $sha = [Security.Cryptography.SHA256]::Create()
        try { $hash = ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant() } finally { $sha.Dispose() }
        $elf = $bytes.Length -ge 20 -and $bytes[0] -eq 127 -and $bytes[1] -eq 69 -and $bytes[2] -eq 76 -and $bytes[3] -eq 70
        $machine = if ($elf) { [BitConverter]::ToUInt16($bytes, 18) } else { $null }
        $expected = if (Test-McaDspArtifactPath $entry.FullName) { 164 } elseif ($entry.FullName -match '/arm64-v8a/') { 183 } elseif ($entry.FullName -match '/x86_64/') { 62 } else { $null }
        $needed = @()
        $soname = $null
        $dependencyStatus = 'not_run_readelf_unavailable'
        if ($ReadElfPath -and $elf) {
            $temporary = Join-Path ([IO.Path]::GetTempPath()) ("mca-elf-" + [Guid]::NewGuid().ToString('N') + '.so')
            try {
                [IO.File]::WriteAllBytes($temporary, $bytes)
                $dynamic = @(& $ReadElfPath --dynamic $temporary 2>&1)
                $dependencyStatus = if ($LASTEXITCODE -eq 0) { 'read' } else { 'readelf_failed' }
                foreach ($line in $dynamic) {
                    if ([string]$line -match '\(NEEDED\).*\[([^\]]+)\]') { $needed += $Matches[1] }
                    if ([string]$line -match '\(SONAME\).*\[([^\]]+)\]') { $soname = $Matches[1] }
                }
            } finally { if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary } }
        }
        $facts += [ordered]@{
            path = $entry.FullName; bytes = $entry.Length; sha256 = $hash; elf = $elf
            machine = $machine; expectedMachine = $expected; soname = $soname
            needed = $needed; dependencyStatus = $dependencyStatus
        }
    }
    return $facts
}
