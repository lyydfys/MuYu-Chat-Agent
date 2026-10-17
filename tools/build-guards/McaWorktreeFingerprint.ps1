<#
.SYNOPSIS
  Deterministic content fingerprint of the CURRENT worktree production inputs (E01 run binding).

.DESCRIPTION
  Mirrors the B0 method - HEAD is only one input, never the whole identity:

    * HEAD commit sha.
    * Every `git status --porcelain=v1 --untracked-files=all --no-renames` entry (modified,
      staged, deleted, untracked, unmerged) plus the CONTENT sha256 of the file on disk.
      Deleted/missing files are recorded as MISSING. A tracked file that is clean is pinned by
      HEAD; a dirty or untracked file is pinned by its content hash, so an equal-length rewrite
      is detected.
    * Every submodule: actual HEAD, its relation to the recorded pointer, and the content hashes
      of its locally dirty files (the third_party local patches are part of the input identity).
    * The two local-only guard files (AGENTS.md and the device-admission policy test) are hashed
      even though .git/info/exclude hides them from git status. This tool ONLY reads them: it
      never stages, commits, pushes, or edits .git/info/exclude.

  Excluded by directory name anywhere in the path (default): .git, build, .gradle, .cxx,
  node_modules, artifacts, .idea - build outputs and the evidence directory are not inputs.

  Consumers:
    * scripts/verify-code.ps1  - fingerprint before/after a run; a difference refuses the result
      (`input-changed-during-run`).
    * tools/build-guards/Test-McaWriteSetGuard.ps1 - reuses Get-McaWorktreeDirtyMap to prove a
      slice only changed files inside its approved write set.

.OUTPUTS
  Get-McaWorktreeFingerprint -> { schema, generatedUtc, repoRoot, head, files, fingerprint, entries[], submodules[] }
  fingerprint = sha256 over the canonical lines in ordinal path order ('head=...', 'file|<status>|<path>|<sha>',
  'sub|<path>|<state>|<head>').

.EXIT CODES (CLI mode, -EmitJson)
  0 = ok        2 = usage/IO error (fail closed)
#>
[CmdletBinding()]
param(
    [string]$RepoRoot = 'E:\MAC',
    [string[]]$ExcludeDirNames = @('.git', 'build', '.gradle', '.cxx', 'node_modules', 'artifacts', '.idea'),
    [switch]$EmitJson
)

# Snapshot the CLI intent BEFORE any dot-sourcing can rebind $PSBoundParameters in this scope.
$script:McaFpCliEmitJson = $PSBoundParameters.ContainsKey('EmitJson')
$script:McaFpCliRepoRoot = $RepoRoot
$script:McaFpCliExcludeDirs = $ExcludeDirNames

$script:McaFpSchema = 'mca.worktree-fingerprint/1'
$script:McaFpDefaultExclude = @('.git', 'build', '.gradle', '.cxx', 'node_modules', 'artifacts', '.idea')
$script:McaFpLocalOnlyFiles = @('AGENTS.md', 'app/src/test/java/com/muyuchat/mca/UniversalDeviceAdmissionPolicyTest.kt')

function Test-McaFpPathExcluded {
    param([string]$RelPath, [string[]]$ExcludeDirNames)
    if (-not $RelPath) { return $true }
    foreach ($p in ($RelPath -split '/')) { if ($ExcludeDirNames -contains $p) { return $true } }
    return $false
}

$script:McaFpSha256 = [System.Security.Cryptography.SHA256]::Create()

function Get-McaFpFileSha {
    param([string]$FullPath)
    try {
        if (-not (Test-Path -LiteralPath $FullPath -PathType Leaf)) { return 'MISSING' }
        # Direct .NET hashing: Get-FileHash costs a cmdlet invocation per file (~40ms * 350 files).
        $fs = [System.IO.File]::Open($FullPath, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
        try { $hash = $script:McaFpSha256.ComputeHash($fs) } finally { $fs.Dispose() }
        return ([BitConverter]::ToString($hash) -replace '-', '').ToLowerInvariant()
    } catch {
        return ('ERROR:' + $_.Exception.GetType().Name)
    }
}

function Get-McaFpSubmodulePaths {
    param([string]$RepoRoot)
    $paths = New-Object System.Collections.Generic.List[string]
    $out = & git -C $RepoRoot submodule status --recursive 2>$null
    if ($out) {
        foreach ($line in @($out)) {
            if ($line -match '^[ +\-U]([0-9a-f]{7,64})\s+(\S+?)(\s+\(.*\))?$') { $paths.Add($Matches[2]) }
        }
    }
    # Defense in depth: a submodule that is not initialized has no `submodule status` line, but
    # when its directory exists its dirty files still matter.
    $gm = & git -C $RepoRoot config -f .gitmodules --get-regexp '^submodule\..*\.path$' 2>$null
    if ($gm) {
        foreach ($line in @($gm)) {
            $kv = $line -split '\s+', 2
            if ($kv.Count -eq 2 -and $kv[1]) { $paths.Add($kv[1].Trim()) }
        }
    }
    return @($paths | Select-Object -Unique)
}

function Get-McaWorktreeDirtyMap {
    <#
      Returns { head, entries[], submodules[] }.
      entries:    [{ path = repo-relative with '/', status = porcelain XY or SUB:<XY> or <XY>:dir, sha256 }]
      submodules: [{ path, state = ' ', '+', '-', 'U' (relation to the recorded pointer), head }]
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$RepoRoot,
        [string[]]$ExcludeDirNames = $script:McaFpDefaultExclude
    )
    $entries = New-Object System.Collections.Generic.List[object]
    $subs = New-Object System.Collections.Generic.List[object]

    $head = ''
    $headLine = & git -C $RepoRoot rev-parse HEAD 2>$null | Select-Object -First 1
    if ($headLine) { $head = ([string]$headLine).Trim() }

    $subPaths = @(Get-McaFpSubmodulePaths -RepoRoot $RepoRoot)
    $subSet = @{}
    foreach ($sp in $subPaths) { $subSet[($sp -replace '\\', '/')] = $true }

    $statusRaw = & git -C $RepoRoot -c core.quotepath=false status --porcelain=v1 --untracked-files=all --no-renames 2>$null
    if ($statusRaw) {
        foreach ($line in @($statusRaw)) {
            if (-not $line -or $line.Length -lt 4) { continue }
            $xy = $line.Substring(0, 2)
            $rel = $line.Substring(3).Trim()
            if ($rel.StartsWith('"') -and $rel.EndsWith('"')) { $rel = $rel.Trim('"') }
            $rel = $rel -replace '\\', '/'
            if ($rel.EndsWith('/')) { $rel = $rel.TrimEnd('/') }
            if (Test-McaFpPathExcluded -RelPath $rel -ExcludeDirNames $ExcludeDirNames) { continue }
            if ($subSet.ContainsKey($rel)) { continue }   # covered by the submodule scan below
            $full = Join-Path $RepoRoot ($rel -replace '/', '\')
            if (Test-Path -LiteralPath $full -PathType Container) {
                # -uall expands directories, but stay defensively correct if one ever appears.
                foreach ($f in @(Get-ChildItem -LiteralPath $full -Recurse -File -ErrorAction SilentlyContinue)) {
                    $r2 = $f.FullName.Substring($RepoRoot.Length).TrimStart('\', '/') -replace '\\', '/'
                    if (Test-McaFpPathExcluded -RelPath $r2 -ExcludeDirNames $ExcludeDirNames) { continue }
                    $entries.Add([pscustomobject]@{ path = $r2; status = ($xy + ':dir'); sha256 = Get-McaFpFileSha -FullPath $f.FullName })
                }
                continue
            }
            $entries.Add([pscustomobject]@{ path = $rel; status = $xy; sha256 = Get-McaFpFileSha -FullPath $full })
        }
    }

    # One `submodule status` call for all submodules: it costs ~1.6s in this repo, and a
    # per-submodule call made the whole fingerprint take 13s instead of ~4s.
    $subStateMap = @{}
    $stOut = & git -C $RepoRoot submodule status --recursive 2>$null
    if ($stOut) {
        foreach ($l in @($stOut)) {
            if ($l -match '^([ +\-U])[0-9a-f]{7,64}\s+(\S+?)(\s+\(.*\))?$') {
                $subStateMap[($Matches[2] -replace '\\', '/')] = $Matches[1]
            }
        }
    }
    foreach ($sp in $subPaths) {
        $spNorm = $sp -replace '\\', '/'
        $subFull = Join-Path $RepoRoot ($spNorm -replace '/', '\')
        if (-not (Test-Path -LiteralPath $subFull -PathType Container)) { continue }
        $subHead = ''
        $subHeadLine = & git -C $subFull rev-parse HEAD 2>$null | Select-Object -First 1
        if ($subHeadLine) { $subHead = ([string]$subHeadLine).Trim() }
        $state = if ($subStateMap.ContainsKey($spNorm)) { $subStateMap[$spNorm] } else { '' }
        $subs.Add([pscustomobject]@{ path = $spNorm; state = $state; head = $subHead })
        $sRaw = & git -C $subFull -c core.quotepath=false status --porcelain=v1 --untracked-files=all --no-renames 2>$null
        if ($sRaw) {
            foreach ($line in @($sRaw)) {
                if (-not $line -or $line.Length -lt 4) { continue }
                $xy = $line.Substring(0, 2)
                $rel = $line.Substring(3).Trim() -replace '\\', '/'
                $entryRel = $spNorm + '/' + $rel
                if (Test-McaFpPathExcluded -RelPath $entryRel -ExcludeDirNames $ExcludeDirNames) { continue }
                $full = Join-Path $subFull ($rel -replace '/', '\')
                $entries.Add([pscustomobject]@{ path = $entryRel; status = ('SUB:' + $xy); sha256 = Get-McaFpFileSha -FullPath $full })
            }
        }
    }

    foreach ($lo in $script:McaFpLocalOnlyFiles) {
        $full = Join-Path $RepoRoot ($lo -replace '/', '\')
        if (Test-Path -LiteralPath $full -PathType Leaf) {
            $entries.Add([pscustomobject]@{ path = ('local-only:' + $lo); status = 'LO'; sha256 = Get-McaFpFileSha -FullPath $full })
        }
    }

    return [pscustomobject]@{ head = $head; entries = $entries.ToArray(); submodules = $subs.ToArray() }
}

function Get-McaWorktreeFingerprint {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$RepoRoot,
        [string[]]$ExcludeDirNames = $script:McaFpDefaultExclude
    )
    $map = Get-McaWorktreeDirtyMap -RepoRoot $RepoRoot -ExcludeDirNames $ExcludeDirNames
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add('head=' + $map.head)
    # Ordinal (culture-invariant) path order. Sort-Object uses the host culture's comparer, and
    # the resulting line order differed between a CLI child process and a dot-sourced session on
    # an unchanged tree, producing two different fingerprints for identical content.
    $entryKeys = [string[]]@($map.entries | ForEach-Object { [string]$_.path })
    [System.Array]::Sort($entryKeys, [System.StringComparer]::Ordinal)
    $entryByPath = @{}
    foreach ($e in $map.entries) { $entryByPath[[string]$e.path] = $e }
    foreach ($k in $entryKeys) {
        $e = $entryByPath[$k]
        $lines.Add(('file|{0}|{1}|{2}' -f $e.path, $e.status, $e.sha256))
    }
    $subKeys = [string[]]@($map.submodules | ForEach-Object { [string]$_.path })
    [System.Array]::Sort($subKeys, [System.StringComparer]::Ordinal)
    $subByPath = @{}
    foreach ($s in $map.submodules) { $subByPath[[string]$s.path] = $s }
    foreach ($k in $subKeys) {
        $s = $subByPath[$k]
        $lines.Add(('sub|{0}|{1}|{2}' -f $s.path, $s.state, $s.head))
    }
    $canonical = ($lines -join "`n")
    $shaAlg = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($canonical)
        $fingerprint = ([BitConverter]::ToString($shaAlg.ComputeHash($bytes)) -replace '-', '').ToLowerInvariant()
    } finally { $shaAlg.Dispose() }
    return [pscustomobject]@{
        schema       = $script:McaFpSchema
        generatedUtc = [datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
        repoRoot     = $RepoRoot
        head         = $map.head
        files        = @($map.entries).Count
        fingerprint  = $fingerprint
        entries      = $map.entries
        submodules   = $map.submodules
    }
}

if ($script:McaFpCliEmitJson) {
    try {
        $fp = Get-McaWorktreeFingerprint -RepoRoot $script:McaFpCliRepoRoot -ExcludeDirNames $script:McaFpCliExcludeDirs
    } catch {
        [Console]::Error.WriteLine('McaWorktreeFingerprint: ' + $_.Exception.Message)
        exit 2
    }
    $fp | ConvertTo-Json -Depth 5
    exit 0
}
