<#
.SYNOPSIS
  E01 task-packet preflight: refuses to dispatch a packet that cannot be executed safely.

.DESCRIPTION
  Implements the "任务包 preflight" row of the S-grade plan (§19.2): a packet is refused (non-zero
  exit) when a mandatory field is empty/placeholder, the status is not READY, the baseline
  manifest hash does not match the file on disk, a write-set file is missing or its before-hash
  does not match, the write set conflicts with read-only/forbidden paths or with another active
  packet, a source symbol cannot be found in its current file, there is no independent reviewer,
  no verification command / expected exit codes / mandatory case list, a lock entry point is
  missing, rollback scope is not THIS_PACKET_ONLY, or any <placeholder>/TBD marker remains.

  This is a static gate over the packet JSON described by
  docs/architecture/MCA-S-GRADE-SLICE-PACKET-TEMPLATE-20261011.md. It never executes packet
  commands and never modifies the repository.

.OUTPUTS
  Human-readable REFUSE lines; optional -OutFile JSON { schema, packet, ok, errors[], checks[] }.

.EXIT CODES
  0 = packet is dispatchable   2 = refused / usage error (fail closed)
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Packet,
    [string]$RepoRoot = 'E:\MAC',
    [string[]]$ActivePackets = @(),
    [string]$OutFile,
    [switch]$SkipSymbolCheck,
    [switch]$AllowOversizeBudget,
    [switch]$Quiet
)

$ErrorActionPreference = 'Stop'

$script:McaPpPlaceholderPattern = '(?i)(<\s*\u5F85\u586B|>\s*\u5F85\u586B|(?<![A-Za-z])TBD(?![A-Za-z]))'
$script:McaPpTaskTypes = @('PURE_MIGRATION', 'CONFIRMED_BUG_FIX', 'INTEGRATION', 'READ_ONLY_AUDIT')
$script:McaPpRiskLevels = @('L0', 'L1', 'L2', 'L3')
$script:McaPpDefaultLineBudget = 300

$errors = New-Object System.Collections.Generic.List[string]
$checks = New-Object System.Collections.Generic.List[object]

function Add-McaPpCheck {
    param([string]$Name, [bool]$Ok, [string]$Detail)
    $checks.Add([pscustomobject]@{ check = $Name; ok = $Ok; detail = $Detail })
    if (-not $Ok) { $errors.Add("$Name : $Detail") }
}

function Test-McaPpPlaceholder {
    param($Value)
    if ($null -eq $Value) { return $true }
    $t = ([string]$Value).Trim()
    if ($t -eq '') { return $true }
    return ($t -match $script:McaPpPlaceholderPattern)
}

function Get-McaPpString {
    param($Obj, [string]$Name)
    if ($null -eq $Obj) { return '' }
    $p = $Obj.PSObject.Properties[$Name]
    if ($null -eq $p -or $null -eq $p.Value) { return '' }
    return ([string]$p.Value)
}

function Get-McaPpArray {
    param($Obj, [string]$Name)
    if ($null -eq $Obj) { return @() }
    $p = $Obj.PSObject.Properties[$Name]
    if ($null -eq $p -or $null -eq $p.Value) { return @() }
    return @($p.Value)
}

function Test-McaPpSha256 {
    param([string]$Value)
    return ($Value -match '^[0-9a-fA-F]{64}$')
}

function Get-McaPpSymbolToken {
    param([string]$Qualified)
    if (-not $Qualified) { return '' }
    $m = [regex]::Match($Qualified, '([A-Za-z_][A-Za-z0-9_]*(::[A-Za-z_][A-Za-z0-9_]*)*)\s*\(')
    if ($m.Success) {
        $parts = $m.Groups[1].Value -split '::'
        return $parts[$parts.Count - 1]
    }
    $m2 = [regex]::Match($Qualified, '^[A-Za-z_][A-Za-z0-9_.]*$')
    if ($m2.Success) {
        $parts = $Qualified -split '[.:]'
        return $parts[$parts.Count - 1]
    }
    return ''
}

function ConvertTo-McaPpRepoRelative {
    param([string]$Path, [string]$RepoRoot)
    if (-not $Path) { return '' }
    $p = $Path.Trim() -replace '\\', '/'
    $root = ($RepoRoot.TrimEnd('\', '/') -replace '\\', '/')
    if ($p.StartsWith($root + '/', [System.StringComparison]::OrdinalIgnoreCase)) { $p = $p.Substring($root.Length + 1) }
    return $p.Trim('/')
}

function Test-McaPpPathsOverlap {
    param([string]$A, [string]$B)
    if (-not $A -or -not $B) { return $false }
    if ($A -eq $B) { return $true }
    return ($A.StartsWith($B + '/', [System.StringComparison]::OrdinalIgnoreCase) -or
            $B.StartsWith($A + '/', [System.StringComparison]::OrdinalIgnoreCase))
}

# ------------------------------------------------------------------ load + parse
try {
    if (-not (Test-Path -LiteralPath $Packet -PathType Leaf)) { throw "packet file not found: $Packet" }
    $rawPacket = [System.IO.File]::ReadAllText($Packet)
    $pkt = $rawPacket | ConvertFrom-Json
} catch {
    [Console]::Error.WriteLine('REFUSE: packet cannot be read/parsed: ' + $_.Exception.Message)
    exit 2
}

Add-McaPpCheck -Name 'status' -Ok ((Get-McaPpString $pkt 'status') -eq 'READY') -Detail ("status='" + (Get-McaPpString $pkt 'status') + "' (must be READY; DRAFT is not dispatchable)")

$topRequired = @('packetId', 'runId', 'sliceId', 'phase', 'taskType', 'riskLevel', 'objective', 'worker', 'integrator', 'independentReviewer', 'evidenceDir')
foreach ($f in $topRequired) {
    $v = Get-McaPpString $pkt $f
    Add-McaPpCheck -Name ("field:$f") -Ok (-not (Test-McaPpPlaceholder $v)) -Detail $(if (Test-McaPpPlaceholder $v) { 'missing/placeholder value' } else { $v })
}
Add-McaPpCheck -Name 'schemaVersion' -Ok (($pkt.schemaVersion -eq 1)) -Detail ("schemaVersion=" + $pkt.schemaVersion)
Add-McaPpCheck -Name 'taskType' -Ok ($script:McaPpTaskTypes -contains (Get-McaPpString $pkt 'taskType')) -Detail ((Get-McaPpString $pkt 'taskType') + ' in ' + ($script:McaPpTaskTypes -join '/'))
Add-McaPpCheck -Name 'riskLevel' -Ok ($script:McaPpRiskLevels -contains (Get-McaPpString $pkt 'riskLevel')) -Detail ((Get-McaPpString $pkt 'riskLevel') + ' in ' + ($script:McaPpRiskLevels -join '/'))

$worker = Get-McaPpString $pkt 'worker'
$reviewer = Get-McaPpString $pkt 'independentReviewer'
Add-McaPpCheck -Name 'independentReviewer' -Ok ((-not (Test-McaPpPlaceholder $reviewer)) -and ($reviewer -ne $worker)) -Detail $(if ($reviewer -eq $worker) { 'reviewer equals worker - not independent' } else { "worker='$worker' reviewer='$reviewer'" })

$budget = 0
if ($null -ne $pkt.logicLineBudget) { $budget = [int]$pkt.logicLineBudget }
Add-McaPpCheck -Name 'logicLineBudget' -Ok ($budget -gt 0 -and ($budget -le $script:McaPpDefaultLineBudget -or $AllowOversizeBudget)) -Detail ("budget=$budget (default max $($script:McaPpDefaultLineBudget); -AllowOversizeBudget was " + $(if ($AllowOversizeBudget) { 'given' } else { 'NOT given' }) + ')')

# ------------------------------------------------------------------ baseline manifest
$b = $pkt.baseline
$manifestPath = Get-McaPpString $b 'manifestPath'
$manifestSha = Get-McaPpString $b 'manifestSha256'
$baselineOk = (-not (Test-McaPpPlaceholder (Get-McaPpString $b 'id'))) -and
              (-not (Test-McaPpPlaceholder $manifestPath)) -and
              (Test-McaPpSha256 $manifestSha) -and
              (-not (Test-McaPpPlaceholder (Get-McaPpString $b 'head'))) -and
              (-not (Test-McaPpPlaceholder (Get-McaPpString $b 'frozenAtUtc')))
Add-McaPpCheck -Name 'baseline.fields' -Ok $baselineOk -Detail ("id='" + (Get-McaPpString $b 'id') + "' head='" + (Get-McaPpString $b 'head') + "'")
if ($manifestPath -and (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    $actual = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash.ToLower()
    Add-McaPpCheck -Name 'baseline.manifestSha256' -Ok ($actual -eq $manifestSha.ToLower()) -Detail ("declared=$($manifestSha.ToLower()) actual=$actual path=$manifestPath")
} else {
    Add-McaPpCheck -Name 'baseline.manifestSha256' -Ok $false -Detail "manifest file not found: '$manifestPath'"
}

# ------------------------------------------------------------------ write set
$writeSetEntries = @($pkt.writeSet)
Add-McaPpCheck -Name 'writeSet.nonEmpty' -Ok ($writeSetEntries.Count -gt 0) -Detail ("$($writeSetEntries.Count) entr(y|ies)")
$writePaths = New-Object System.Collections.Generic.List[string]
$wsProblems = New-Object System.Collections.Generic.List[string]
foreach ($w in $writeSetEntries) {
    $rel = ConvertTo-McaPpRepoRelative -Path (Get-McaPpString $w 'path') -RepoRoot $RepoRoot
    if (-not $rel) { $wsProblems.Add('entry with empty path'); continue }
    $writePaths.Add($rel)
    $full = Join-Path $RepoRoot ($rel -replace '/', '\')
    foreach ($f in @('owner', 'allowedChange')) {
        if (Test-McaPpPlaceholder (Get-McaPpString $w $f)) { $wsProblems.Add("$rel : field '$f' missing/placeholder") }
    }
    if ((Get-McaPpArray $w 'allowedSymbols').Count -eq 0) { $wsProblems.Add("$rel : allowedSymbols is empty") }
    $existsDecl = $w.PSObject.Properties['expectedExists']
    if ($null -eq $existsDecl) { $wsProblems.Add("$rel : expectedExists not declared (must be true/false)"); continue }
    if ([bool]$w.expectedExists) {
        if (-not (Test-Path -LiteralPath $full -PathType Leaf)) { $wsProblems.Add("$rel : declared to exist but not found") ; continue }
        $before = Get-McaPpString $w 'beforeSha256'
        if (-not (Test-McaPpSha256 $before)) { $wsProblems.Add("$rel : beforeSha256 is not a 64-hex sha256") ; continue }
        $actual = (Get-FileHash -LiteralPath $full -Algorithm SHA256).Hash.ToLower()
        if ($actual -ne $before.ToLower()) { $wsProblems.Add("$rel : beforeSha256 mismatch (declared=$($before.ToLower()) actual=$actual)") }
    } else {
        if (Test-Path -LiteralPath $full) { $wsProblems.Add("$rel : declared NOT_EXISTS but the path exists") }
    }
}
Add-McaPpCheck -Name 'writeSet.entries' -Ok ($wsProblems.Count -eq 0) -Detail $(if ($wsProblems.Count -eq 0) { 'all entries valid (existence + before hash)' } else { ($wsProblems -join '; ') })

$readOnly = @(Get-McaPpArray $pkt 'readOnlyPaths' | ForEach-Object { ConvertTo-McaPpRepoRelative -Path ([string]$_) -RepoRoot $RepoRoot })
$forbidden = @(Get-McaPpArray $pkt 'forbiddenPaths' | ForEach-Object { ConvertTo-McaPpRepoRelative -Path ([string]$_) -RepoRoot $RepoRoot })
$conflicts = New-Object System.Collections.Generic.List[string]
foreach ($w in $writePaths) {
    foreach ($ro in $readOnly) { if (Test-McaPpPathsOverlap -A $w -B $ro) { $conflicts.Add("write '$w' overlaps readOnly '$ro'") } }
    foreach ($fb in $forbidden) { if (Test-McaPpPathsOverlap -A $w -B $fb) { $conflicts.Add("write '$w' overlaps forbidden '$fb'") } }
}
Add-McaPpCheck -Name 'writeSet.noInternalConflict' -Ok ($conflicts.Count -eq 0) -Detail $(if ($conflicts.Count -eq 0) { 'no overlap with readOnly/forbidden paths' } else { ($conflicts -join '; ') })

# ------------------------------------------------------------------ cross-packet conflicts
if ($ActivePackets.Count -gt 0) {
    $cross = New-Object System.Collections.Generic.List[string]
    foreach ($otherPath in $ActivePackets) {
        if (-not (Test-Path -LiteralPath $otherPath -PathType Leaf)) { $cross.Add("active packet not found: $otherPath"); continue }
        $other = [System.IO.File]::ReadAllText($otherPath) | ConvertFrom-Json
        if ((Get-McaPpString $other 'packetId') -eq (Get-McaPpString $pkt 'packetId')) { continue }
        $otherWs = New-Object System.Collections.Generic.List[string]
        foreach ($ow in @($other.writeSet)) { $otherWs.Add((ConvertTo-McaPpRepoRelative -Path (Get-McaPpString $ow 'path') -RepoRoot $RepoRoot)) }
        foreach ($w in $writePaths) {
            foreach ($ow in $otherWs) {
                if (Test-McaPpPathsOverlap -A $w -B $ow) { $cross.Add("write '$w' overlaps packet '$((Get-McaPpString $other 'packetId'))' write '$ow'") }
            }
        }
        if ((Get-McaPpString $other 'evidenceDir') -eq (Get-McaPpString $pkt 'evidenceDir')) { $cross.Add("evidenceDir is shared with packet '$((Get-McaPpString $other 'packetId'))'") }
    }
    Add-McaPpCheck -Name 'writeSet.noCrossPacketConflict' -Ok ($cross.Count -eq 0) -Detail $(if ($cross.Count -eq 0) { "no overlap with $($ActivePackets.Count) active packet(s)" } else { ($cross -join '; ') })
}

# ------------------------------------------------------------------ source symbols
$symbolProblems = New-Object System.Collections.Generic.List[string]
$symbols = @($pkt.sourceSymbols)
if ($symbols.Count -eq 0) { $symbolProblems.Add('sourceSymbols is empty') }
foreach ($s in $symbols) {
    $cur = ConvertTo-McaPpRepoRelative -Path (Get-McaPpString $s 'currentPath') -RepoRoot $RepoRoot
    $sig = Get-McaPpString $s 'qualifiedSignature'
    if (-not $cur) { $symbolProblems.Add('entry with empty currentPath'); continue }
    if (Test-McaPpPlaceholder $sig) { $symbolProblems.Add("$cur : qualifiedSignature missing/placeholder"); continue }
    $full = Join-Path $RepoRoot ($cur -replace '/', '\')
    if (-not (Test-Path -LiteralPath $full -PathType Leaf)) { $symbolProblems.Add("$cur : file not found"); continue }
    if (-not $SkipSymbolCheck) {
        $token = Get-McaPpSymbolToken -Qualified $sig
        if (-not $token) { $symbolProblems.Add("$cur : cannot extract a symbol token from '$sig'"); continue }
        $text = [System.IO.File]::ReadAllText($full)
        if ($text -notmatch ('\b' + [regex]::Escape($token) + '\b')) { $symbolProblems.Add("$cur : symbol '$token' (from '$sig') not found in the file") }
    }
    if (Test-McaPpPlaceholder (Get-McaPpString $s 'targetPath')) { $symbolProblems.Add("$cur : targetPath missing/placeholder") }
}
Add-McaPpCheck -Name 'sourceSymbols' -Ok ($symbolProblems.Count -eq 0) -Detail $(if ($symbolProblems.Count -eq 0) { "$($symbols.Count) symbol(s) located" } else { ($symbolProblems -join '; ') })

# ------------------------------------------------------------------ build / verification / integration / rollback
$buildProblems = New-Object System.Collections.Generic.List[string]
foreach ($f in @('cwd', 'shell', 'abi', 'configuration', 'lockEntryAndOwner')) {
    if (Test-McaPpPlaceholder (Get-McaPpString $pkt.build $f)) { $buildProblems.Add("build.$f missing/placeholder") }
}
if ((Get-McaPpArray $pkt.build 'actualTargets').Count -eq 0) { $buildProblems.Add('build.actualTargets is empty') }
if ((Get-McaPpArray $pkt.build 'snapshotStartAndEndChecks').Count -eq 0) { $buildProblems.Add('build.snapshotStartAndEndChecks is empty') }
Add-McaPpCheck -Name 'build' -Ok ($buildProblems.Count -eq 0) -Detail $(if ($buildProblems.Count -eq 0) { 'cwd/shell/targets/lock owner declared' } else { ($buildProblems -join '; ') })

$v = $pkt.verification
$vProblems = New-Object System.Collections.Generic.List[string]
if ((Get-McaPpArray $v 'beforeCommands').Count -eq 0) { $vProblems.Add('verification.beforeCommands is empty') }
if ((Get-McaPpArray $v 'afterCommands').Count -eq 0) { $vProblems.Add('verification.afterCommands is empty') }
if ((Get-McaPpArray $v 'expectedExitCodes').Count -eq 0) { $vProblems.Add('verification.expectedExitCodes is empty') }
$mandatory = @(Get-McaPpArray $v 'mandatoryCaseIds')
$taskType = Get-McaPpString $pkt 'taskType'
if ($mandatory.Count -eq 0 -and $taskType -ne 'READ_ONLY_AUDIT') { $vProblems.Add('verification.mandatoryCaseIds is empty (only READ_ONLY_AUDIT may omit)') }
if (Test-McaPpPlaceholder (Get-McaPpString $v 'baselineCollectedCount')) { $vProblems.Add('verification.baselineCollectedCount missing/placeholder') }
Add-McaPpCheck -Name 'verification' -Ok ($vProblems.Count -eq 0) -Detail $(if ($vProblems.Count -eq 0) { "afterCommands + expectedExitCodes + $($mandatory.Count) mandatory case(s)" } else { ($vProblems -join '; ') })

Add-McaPpCheck -Name 'integration.reviewRequired' -Ok ($pkt.integration.reviewRequired -eq $true) -Detail ("reviewRequired=" + $pkt.integration.reviewRequired)

$r = $pkt.rollback
$rProblems = New-Object System.Collections.Generic.List[string]
if (Test-McaPpPlaceholder (Get-McaPpString $r 'beforeBytesOrPatchPath')) { $rProblems.Add('rollback.beforeBytesOrPatchPath missing/placeholder') }
if (-not (Test-McaPpSha256 (Get-McaPpString $r 'beforeBytesOrPatchSha256'))) { $rProblems.Add('rollback.beforeBytesOrPatchSha256 is not a 64-hex sha256') }
if ((Get-McaPpString $r 'scope') -ne 'THIS_PACKET_ONLY') { $rProblems.Add("rollback.scope='" + (Get-McaPpString $r 'scope') + "' (must be THIS_PACKET_ONLY)") }
if ((Get-McaPpString $r 'conflictingExternalChangesAction') -ne 'STOP_AND_COORDINATE') { $rProblems.Add("rollback.conflictingExternalChangesAction='" + (Get-McaPpString $r 'conflictingExternalChangesAction') + "' (must be STOP_AND_COORDINATE)") }
if ((Get-McaPpArray $r 'verificationCommands').Count -eq 0) { $rProblems.Add('rollback.verificationCommands is empty') }
Add-McaPpCheck -Name 'rollback' -Ok ($rProblems.Count -eq 0) -Detail $(if ($rProblems.Count -eq 0) { 'patch path+hash, THIS_PACKET_ONLY, STOP_AND_COORDINATE' } else { ($rProblems -join '; ') })

Add-McaPpCheck -Name 'stopOn' -Ok ((Get-McaPpArray $pkt 'stopOn').Count -gt 0) -Detail ("$((Get-McaPpArray $pkt 'stopOn').Count) stop condition(s)")

$placeholders = [regex]::Matches($rawPacket, $script:McaPpPlaceholderPattern)
Add-McaPpCheck -Name 'noPlaceholders' -Ok ($placeholders.Count -eq 0) -Detail $(if ($placeholders.Count -eq 0) { 'no <placeholder>/TBD remains' } else { "$($placeholders.Count) placeholder occurrence(s) in the raw packet" })

# ------------------------------------------------------------------ verdict
$ok = ($errors.Count -eq 0)
if ($OutFile) {
    $dir = Split-Path -Parent $OutFile
    if ($dir -and -not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    $report = [pscustomobject]@{
        schema       = 'mca.task-packet-preflight/1'
        generatedUtc = [datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
        packet       = (Resolve-Path -LiteralPath $Packet).Path
        packetId     = Get-McaPpString $pkt 'packetId'
        ok           = $ok
        errorCount   = $errors.Count
        errors       = $errors.ToArray()
        checks       = $checks.ToArray()
    }
    [System.IO.File]::WriteAllText($OutFile, ($report | ConvertTo-Json -Depth 6), (New-Object System.Text.UTF8Encoding($false)))
}
if (-not $Quiet) {
    foreach ($c in $checks) {
        $mark = if ($c.ok) { 'ok  ' } else { 'FAIL' }
        Write-Host ("[{0}] {1,-34} {2}" -f $mark, $c.check, $c.detail)
    }
}
if ($ok) {
    if (-not $Quiet) { Write-Host ("PREFLIGHT OK: " + (Get-McaPpString $pkt 'packetId')) }
    exit 0
}
foreach ($e in $errors) { [Console]::Error.WriteLine('REFUSE: ' + $e) }
exit 2