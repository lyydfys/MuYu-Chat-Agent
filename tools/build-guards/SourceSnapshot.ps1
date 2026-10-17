<#
.SYNOPSIS
  Content-level source snapshot and drift comparison for the MCA build guard.

.DESCRIPTION
  Answers the weakness recorded in the 2026-10-10 re-review (§8.2): a gate that only compares
  `git status` strings cannot see "M stayed M" or "?? content changed". This snapshot records a
  per-file CONTENT hash (SHA-256) plus size and mtime, taken before a build, and compares it after.
  The comparison reports exactly which file changed, when, and the before/after hash - attribution
  that a single aggregate worktree hash cannot provide.

  Detection is by content hash, never by size or mtime: an equal-length rewrite (same byte count,
  different bytes) is detected, and that is an explicit negative case (N2).

  Nothing here proposes, performs or configures a commit. The locally protected files
  (AGENTS.md, UniversalDeviceAdmissionPolicyTest.kt) are treated as ordinary snapshot inputs -
  this tool never stages anything and never removes a `.git/info/exclude` entry.

.OUTPUTS
  Snapshot JSON : { schema, generatedUtc, repoRoot, roots[], fileCount, entries[{path,bytes,sha256,mtimeUtc,tracked}] }
  Diff JSON     : { schema, generatedUtc, equal, checkedCount, added[], removed[], changed[] }

.EXIT CODES (CLI mode)
  0 = no drift        1 = drift detected        2 = usage/IO error (fail closed)
#>
[CmdletBinding()]
param(
    [ValidateSet('New', 'Compare')][string]$Action,
    [string[]]$Roots = @(),
    [string]$Snapshot,
    [string]$OutFile,
    [string]$RepoRoot = 'E:\MAC',
    [string[]]$ExcludeDirNames = @('.git', 'build', '.gradle', '.cxx', 'node_modules', 'artifacts', '.idea'),
    [switch]$Quiet
)

$script:McaSnapshotSchema = 'mca.source-snapshot/1'

function Get-McaTrackedSet {
    param([string]$RepoRoot)
    $set = @{}
    $out = & git -C $RepoRoot ls-files --cached 2>$null
    if ($out) { foreach ($p in $out) { if ($p) { $set[$p -replace '\\', '/'] = $true } } }
    return $set
}

function Get-McaSnapshotFiles {
    param([string]$RepoRoot, [string[]]$Roots, [string[]]$ExcludeDirNames)
    $files = New-Object System.Collections.Generic.List[string]
    foreach ($r in $Roots) {
        $full = if ([System.IO.Path]::IsPathRooted($r)) { $r } else { Join-Path $RepoRoot $r }
        if (-not (Test-Path -LiteralPath $full)) { continue }
        $item = Get-Item -LiteralPath $full
        if ($item.PSIsContainer) {
            Get-ChildItem -LiteralPath $full -Recurse -File -ErrorAction SilentlyContinue | ForEach-Object {
                $rel = $_.FullName.Substring($RepoRoot.Length).TrimStart('\', '/') -replace '\\', '/'
                $parts = $rel -split '/'
                $skip = $false
                foreach ($p in $parts) { if ($ExcludeDirNames -contains $p) { $skip = $true; break } }
                if (-not $skip) { $files.Add($rel) }
            }
        } else {
            $rel = $item.FullName.Substring($RepoRoot.Length).TrimStart('\', '/') -replace '\\', '/'
            $files.Add($rel)
        }
    }
    return ($files | Sort-Object -Unique)
}

function New-McaSourceSnapshot {
    <#
      Takes the "before" snapshot. Returns the snapshot object and, when -OutFile is given,
      writes it as JSON.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string[]]$Roots,
        [string]$OutFile,
        [string]$RepoRoot = 'E:\MAC',
        [string[]]$ExcludeDirNames = @('.git', 'build', '.gradle', '.cxx', 'node_modules', 'artifacts', '.idea')
    )
    $tracked = Get-McaTrackedSet -RepoRoot $RepoRoot
    $rel = Get-McaSnapshotFiles -RepoRoot $RepoRoot -Roots $Roots -ExcludeDirNames $ExcludeDirNames
    $entries = New-Object System.Collections.Generic.List[object]
    foreach ($r in $rel) {
        $full = Join-Path $RepoRoot ($r -replace '/', '\')
        try {
            $fi = Get-Item -LiteralPath $full -ErrorAction Stop
            $h = (Get-FileHash -LiteralPath $full -Algorithm SHA256 -ErrorAction Stop).Hash.ToLower()
            $entries.Add([pscustomobject]@{
                path     = $r
                bytes    = [int64]$fi.Length
                sha256   = $h
                mtimeUtc = $fi.LastWriteTimeUtc.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
                tracked  = [bool]$tracked.ContainsKey($r)
            })
        } catch {
            $entries.Add([pscustomobject]@{ path = $r; bytes = -1; sha256 = ''; mtimeUtc = ''; tracked = [bool]$tracked.ContainsKey($r) })
        }
    }
    $snap = [pscustomobject]@{
        schema       = $script:McaSnapshotSchema
        generatedUtc = [datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
        repoRoot     = $RepoRoot
        roots        = @($Roots)
        fileCount    = $entries.Count
        trackedCount = @($entries | Where-Object { $_.tracked }).Count
        entries      = $entries.ToArray()
    }
    if ($OutFile) {
        $dir = Split-Path -Parent $OutFile
        if ($dir -and -not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
        [System.IO.File]::WriteAllText($OutFile, ($snap | ConvertTo-Json -Depth 5), (New-Object System.Text.UTF8Encoding($false)))
    }
    return $snap
}

function Compare-McaSourceSnapshot {
    <#
      Compares the live tree against a previously taken snapshot. Returns a diff object whose
      `equal` flag is the gate result, and writes the diff JSON when -OutFile is given.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Snapshot,
        [string]$OutFile,
        [string]$RepoRoot = 'E:\MAC',
        [string[]]$ExcludeDirNames = @('.git', 'build', '.gradle', '.cxx', 'node_modules', 'artifacts', '.idea')
    )
    if (-not (Test-Path -LiteralPath $Snapshot)) {
        throw "snapshot file not found: $Snapshot"
    }
    $before = Get-Content -LiteralPath $Snapshot -Raw | ConvertFrom-Json
    if ($before.schema -ne $script:McaSnapshotSchema) { throw "unexpected snapshot schema: $($before.schema)" }
    if (-not $before.entries) { throw "snapshot has no entries: $Snapshot" }

    $tracked = Get-McaTrackedSet -RepoRoot $RepoRoot
    $beforeMap = @{}
    foreach ($e in $before.entries) { $beforeMap[$e.path] = $e }

    $liveRel = Get-McaSnapshotFiles -RepoRoot $RepoRoot -Roots @($before.roots) -ExcludeDirNames $ExcludeDirNames
    $liveMap = @{}
    foreach ($r in $liveRel) {
        $full = Join-Path $RepoRoot ($r -replace '/', '\')
        try {
            $fi = Get-Item -LiteralPath $full -ErrorAction Stop
            $h = (Get-FileHash -LiteralPath $full -Algorithm SHA256 -ErrorAction Stop).Hash.ToLower()
            $liveMap[$r] = [pscustomobject]@{
                path = $r; bytes = [int64]$fi.Length; sha256 = $h
                mtimeUtc = $fi.LastWriteTimeUtc.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
                tracked = [bool]$tracked.ContainsKey($r)
            }
        } catch { }
    }

    $added = New-Object System.Collections.Generic.List[object]
    $removed = New-Object System.Collections.Generic.List[object]
    $changed = New-Object System.Collections.Generic.List[object]

    foreach ($p in $liveMap.Keys) {
        if (-not $beforeMap.ContainsKey($p)) {
            $added.Add([pscustomobject]@{ path = $p; afterSha256 = $liveMap[$p].sha256; afterBytes = $liveMap[$p].bytes })
        } elseif ($beforeMap[$p].sha256 -ne $liveMap[$p].sha256) {
            # content hash decides; size and mtime are reported for attribution only
            $changed.Add([pscustomobject]@{
                path          = $p
                beforeSha256  = $beforeMap[$p].sha256
                afterSha256   = $liveMap[$p].sha256
                beforeBytes   = $beforeMap[$p].bytes
                afterBytes    = $liveMap[$p].bytes
                sameBytes     = ([int64]$beforeMap[$p].bytes -eq [int64]$liveMap[$p].bytes)
                beforeMtimeUtc= $beforeMap[$p].mtimeUtc
                afterMtimeUtc = $liveMap[$p].mtimeUtc
                tracked       = $liveMap[$p].tracked
            })
        }
    }
    foreach ($p in $beforeMap.Keys) {
        if (-not $liveMap.ContainsKey($p)) {
            $removed.Add([pscustomobject]@{ path = $p; beforeSha256 = $beforeMap[$p].sha256; beforeBytes = $beforeMap[$p].bytes })
        }
    }

    $diff = [pscustomobject]@{
        schema         = 'mca.source-snapshot-diff/1'
        generatedUtc   = [datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
        snapshotFile   = (Resolve-Path -LiteralPath $Snapshot).Path
        equal          = (($added.Count -eq 0) -and ($removed.Count -eq 0) -and ($changed.Count -eq 0))
        checkedCount   = $beforeMap.Count
        liveCount      = $liveMap.Count
        added          = $added.ToArray()
        removed        = $removed.ToArray()
        changed        = $changed.ToArray()
    }
    if ($OutFile) {
        $dir = Split-Path -Parent $OutFile
        if ($dir -and -not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
        [System.IO.File]::WriteAllText($OutFile, ($diff | ConvertTo-Json -Depth 6), (New-Object System.Text.UTF8Encoding($false)))
    }
    return $diff
}

# ---------------------------------------------------------------- CLI mode
if ($PSBoundParameters.ContainsKey('Action')) {
    if ($Action -eq 'New') {
        if (-not $Roots -or $Roots.Count -eq 0) { [Console]::Error.WriteLine('SourceSnapshot: -Roots is required for -Action New'); exit 2 }
        if (-not $OutFile) { [Console]::Error.WriteLine('SourceSnapshot: -OutFile is required for -Action New'); exit 2 }
        $s = New-McaSourceSnapshot -Roots $Roots -OutFile $OutFile -RepoRoot $RepoRoot -ExcludeDirNames $ExcludeDirNames
        if (-not $Quiet) { Write-Host "snapshot: $($s.fileCount) files ($($s.trackedCount) tracked) -> $OutFile" }
        exit 0
    }
    if ($Action -eq 'Compare') {
        if (-not $Snapshot) { [Console]::Error.WriteLine('SourceSnapshot: -Snapshot is required for -Action Compare'); exit 2 }
        try {
            $d = Compare-McaSourceSnapshot -Snapshot $Snapshot -OutFile $OutFile -RepoRoot $RepoRoot -ExcludeDirNames $ExcludeDirNames
        } catch {
            [Console]::Error.WriteLine("SourceSnapshot: $($_.Exception.Message)")
            exit 2
        }
        if ($d.equal) { if (-not $Quiet) { Write-Host "compare: NO DRIFT over $($d.checkedCount) files" }; exit 0 }
        if (-not $Quiet) {
            Write-Host "compare: DRIFT  added=$($d.added.Count) removed=$($d.removed.Count) changed=$($d.changed.Count)"
            foreach ($c in $d.changed) {
                Write-Host ("  changed : {0}  bytes {1}->{2} (sameBytes={3})  sha {4} -> {5}  mtime {6} -> {7}" -f `
                    $c.path, $c.beforeBytes, $c.afterBytes, $c.sameBytes, $c.beforeSha256.Substring(0, 16), $c.afterSha256.Substring(0, 16), $c.beforeMtimeUtc, $c.afterMtimeUtc)
            }
            foreach ($x in $d.added)   { Write-Host "  added   : $($x.path)" }
            foreach ($x in $d.removed) { Write-Host "  removed : $($x.path)" }
        }
        exit 1
    }
}
