<#
.SYNOPSIS
  E01 write-set and input-consistency guard: a slice may only change what its packet approved.

.DESCRIPTION
  Takes a BEFORE snapshot of the dirty/untracked worktree state (via
  McaWorktreeFingerprint.ps1 - HEAD, per-file status and content hashes, submodule state), then at
  completion re-reads the SAME map and lists every change that is not covered by the approved
  write set:

    * head-changed      - HEAD moved (a commit/checkout/reset happened): always refused.
    * added             - a path became dirty/untracked that was clean before.
    * changed           - content hash or status of a dirty/untracked path differs.
    * cleaned           - a path that was dirty/untracked before is now clean/gone.

  Write-set entries are repo-relative (or absolute under the repo root) files or directories;
  a directory entry covers everything below it ("third_party/llama.cpp"). Matching is exact or
  prefix-only - there are no globs, so an approved set cannot silently widen.

  Rejection = non-zero exit (1). This guard never stages, commits or publishes anything.

.EXIT CODES
  New/Verify: 0 = ok/no violation, 1 = violation (Verify), 2 = usage/IO error (fail closed).
#>
[CmdletBinding()]
param(
    [ValidateSet('New', 'Verify')][string]$Action,
    [string]$RepoRoot = 'E:\MAC',
    [string[]]$ExcludeDirNames = @('.git', 'build', '.gradle', '.cxx', 'node_modules', 'artifacts', '.idea'),
    [string]$Snapshot,
    [string[]]$WriteSet = @(),
    [string]$WriteSetFile,
    [string]$OutFile,
    [switch]$Quiet
)

# Capture CLI intent before the dot-source below rebinds $PSBoundParameters in this scope.
$wswHasAction = $PSBoundParameters.ContainsKey('Action')
$wswAction   = $Action
$wswRepoRoot = $RepoRoot
$wswExclude  = $ExcludeDirNames
$wswSnapshot = $Snapshot
$wswWriteSet = $WriteSet
$wswWriteSetFile = $WriteSetFile
$wswOutFile  = $OutFile
$wswQuiet    = [bool]$Quiet

. (Join-Path $PSScriptRoot 'McaWorktreeFingerprint.ps1')

$script:McaWswSchema = 'mca.write-set-guard/1'

function ConvertTo-McaWswRepoRelative {
    param([string]$Entry, [string]$RepoRoot)
    if (-not $Entry) { return '' }
    $e = $Entry.Trim() -replace '\\', '/'
    $root = ($RepoRoot.TrimEnd('\', '/') -replace '\\', '/')
    if ($e.StartsWith($root + '/', [System.StringComparison]::OrdinalIgnoreCase)) {
        $e = $e.Substring($root.Length + 1)
    }
    return $e.Trim('/')
}

function Get-McaWswWriteSet {
    param([string[]]$WriteSet, [string]$WriteSetFile, [string]$RepoRoot)
    $raw = New-Object System.Collections.Generic.List[string]
    foreach ($w in @($WriteSet)) { if ($w) { $raw.Add($w) } }
    if ($WriteSetFile) {
        if (-not (Test-Path -LiteralPath $WriteSetFile -PathType Leaf)) { throw "write-set file not found: $WriteSetFile" }
        $obj = [System.IO.File]::ReadAllText($WriteSetFile) | ConvertFrom-Json
        if ($obj -is [System.Array]) {
            foreach ($w in $obj) { if ($w) { $raw.Add([string]$w) } }
        } elseif ($obj.writeSet) {
            foreach ($item in @($obj.writeSet)) {
                if ($item -is [string]) { $raw.Add([string]$item) }
                elseif ($item.path) { $raw.Add([string]$item.path) }
            }
        } else {
            throw "write-set file '$WriteSetFile' has neither an array root nor a writeSet[] list"
        }
    }
    $normalized = New-Object System.Collections.Generic.List[string]
    foreach ($w in $raw) {
        $n = ConvertTo-McaWswRepoRelative -Entry $w -RepoRoot $RepoRoot
        if ($n) { $normalized.Add($n) }
    }
    return @($normalized | Select-Object -Unique)
}

function Test-McaWswAllowed {
    param([string]$Path, [string[]]$WriteSet)
    foreach ($w in @($WriteSet)) {
        if (-not $w) { continue }
        if ($Path -eq $w) { return $true }
        if ($Path.StartsWith($w + '/', [System.StringComparison]::OrdinalIgnoreCase)) { return $true }
    }
    return $false
}

function New-McaWriteSetSnapshot {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$RepoRoot,
        [string]$OutFile,
        [string[]]$ExcludeDirNames = $script:McaFpDefaultExclude
    )
    $fp = Get-McaWorktreeFingerprint -RepoRoot $RepoRoot -ExcludeDirNames $ExcludeDirNames
    $snap = [pscustomobject]@{
        schema       = 'mca.write-set-snapshot/1'
        generatedUtc = $fp.generatedUtc
        repoRoot     = $RepoRoot
        head         = $fp.head
        fingerprint  = $fp.fingerprint
        entries      = $fp.entries
    }
    if ($OutFile) {
        $dir = Split-Path -Parent $OutFile
        if ($dir -and -not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
        [System.IO.File]::WriteAllText($OutFile, ($snap | ConvertTo-Json -Depth 6), (New-Object System.Text.UTF8Encoding($false)))
    }
    return $snap
}

function Test-McaWriteSetDrift {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$SnapshotFile,
        [string[]]$WriteSet = @(),
        [string]$RepoRoot = 'E:\MAC',
        [string[]]$ExcludeDirNames = $script:McaFpDefaultExclude
    )
    if (-not (Test-Path -LiteralPath $SnapshotFile -PathType Leaf)) { throw "snapshot file not found: $SnapshotFile" }
    $snap = [System.IO.File]::ReadAllText($SnapshotFile) | ConvertFrom-Json
    $beforeMap = @{}
    foreach ($e in @($snap.entries)) { $beforeMap[$e.path] = $e }
    $live = Get-McaWorktreeDirtyMap -RepoRoot $RepoRoot -ExcludeDirNames $ExcludeDirNames
    $afterMap = @{}
    foreach ($e in @($live.entries)) { $afterMap[$e.path] = $e }

    $violations = New-Object System.Collections.Generic.List[object]
    $allowedChanged = New-Object System.Collections.Generic.List[string]

    if ($live.head -ne $snap.head) {
        $violations.Add([pscustomobject]@{
            kind = 'head-changed'; path = '(repo)'
            detail = "HEAD moved during the slice: $($snap.head) -> $($live.head)"
        })
    }
    foreach ($path in @($afterMap.Keys | Sort-Object)) {
        $after = $afterMap[$path]
        $inSet = Test-McaWswAllowed -Path $path -WriteSet $WriteSet
        if (-not $beforeMap.ContainsKey($path)) {
            if ($inSet) { $allowedChanged.Add("added: $path") } else {
                $violations.Add([pscustomobject]@{ kind = 'added'; path = $path; detail = "became dirty/untracked outside the approved write set (status $($after.status), sha $($after.sha256.Substring(0, [Math]::Min(16, $after.sha256.Length)))...)" })
            }
        } elseif ($beforeMap[$path].sha256 -ne $after.sha256 -or $beforeMap[$path].status -ne $after.status) {
            if ($inSet) { $allowedChanged.Add("changed: $path") } else {
                $violations.Add([pscustomobject]@{ kind = 'changed'; path = $path; detail = "content/status changed outside the approved write set (sha $($beforeMap[$path].sha256.Substring(0, [Math]::Min(16, $beforeMap[$path].sha256.Length)))... -> $($after.sha256.Substring(0, [Math]::Min(16, $after.sha256.Length)))...)" })
            }
        }
    }
    foreach ($path in @($beforeMap.Keys | Sort-Object)) {
        if (-not $afterMap.ContainsKey($path)) {
            if (Test-McaWswAllowed -Path $path -WriteSet $WriteSet) { $allowedChanged.Add("cleaned: $path") } else {
                $violations.Add([pscustomobject]@{ kind = 'cleaned'; path = $path; detail = 'was dirty/untracked before the slice and is now clean or gone, outside the approved write set' })
            }
        }
    }

    $result = [pscustomobject]@{
        schema            = 'mca.write-set-verify/1'
        generatedUtc      = [datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
        snapshotFile      = (Resolve-Path -LiteralPath $SnapshotFile).Path
        repoRoot          = $RepoRoot
        headBefore        = $snap.head
        headAfter         = $live.head
        writeSet          = @($WriteSet)
        equal             = ($violations.Count -eq 0)
        violationCount    = $violations.Count
        violations        = $violations.ToArray()
        allowedChanges    = $allowedChanged.ToArray()
        beforeEntryCount  = $beforeMap.Count
        afterEntryCount   = $afterMap.Count
    }
    return $result
}

if ($wswHasAction) {
    try {
        if ($wswAction -eq 'New') {
            if (-not $wswOutFile) { [Console]::Error.WriteLine('Test-McaWriteSetGuard: -OutFile is required for -Action New'); exit 2 }
            $s = New-McaWriteSetSnapshot -RepoRoot $wswRepoRoot -OutFile $wswOutFile -ExcludeDirNames $wswExclude
            if (-not $wswQuiet) { Write-Host "write-set snapshot: $($s.entries.Count) dirty/untracked entries, head=$($s.head) -> $wswOutFile" }
            exit 0
        }
        if ($wswAction -eq 'Verify') {
            if (-not $wswSnapshot) { [Console]::Error.WriteLine('Test-McaWriteSetGuard: -Snapshot is required for -Action Verify'); exit 2 }
            $ws = Get-McaWswWriteSet -WriteSet $wswWriteSet -WriteSetFile $wswWriteSetFile -RepoRoot $wswRepoRoot
            if ($ws.Count -eq 0) { [Console]::Error.WriteLine('Test-McaWriteSetGuard: empty write set - refusing to verify (would reject every change)'); exit 2 }
            $d = Test-McaWriteSetDrift -SnapshotFile $wswSnapshot -WriteSet $ws -RepoRoot $wswRepoRoot -ExcludeDirNames $wswExclude
            if ($wswOutFile) {
                $dir = Split-Path -Parent $wswOutFile
                if ($dir -and -not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
                [System.IO.File]::WriteAllText($wswOutFile, ($d | ConvertTo-Json -Depth 6), (New-Object System.Text.UTF8Encoding($false)))
            }
            if ($d.equal) {
                if (-not $wswQuiet) { Write-Host "write-set verify: NO VIOLATION over $($d.beforeEntryCount) before / $($d.afterEntryCount) after entries ($($d.allowedChanges.Count) approved change(s))" }
                exit 0
            }
            if (-not $wswQuiet) {
                Write-Host "write-set verify: VIOLATION ($($d.violationCount))"
                foreach ($v in $d.violations) { Write-Host ("  {0,-12} {1}  {2}" -f $v.kind, $v.path, $v.detail) }
            }
            exit 1
        }
    } catch {
        [Console]::Error.WriteLine('Test-McaWriteSetGuard: ' + $_.Exception.Message)
        exit 2
    }
}