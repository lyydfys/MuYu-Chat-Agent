<#
.SYNOPSIS
  Expands a CMake target's real translation-unit list, so the source-set gate is not blind.

.DESCRIPTION
  Review finding #1: a text-parsing source-set gate cannot see sources that are assembled through
  a helper variable plus list(APPEND ...) - it reads `add_library(mca_native SHARED
  ${MCA_NATIVE_SOURCES})` and concludes the target has one source whose name is a variable. What
  the gate cannot expand, it cannot audit.

  This tool asks the build system instead of the text. Primary source is compile_commands.json,
  which lists every translation unit actually compiled together with the target it belongs to
  (`CMakeFiles/<target>.dir/...`). When a CMake File API codemodel-v2 reply is present in the build
  directory it is used as a cross-check.

  It also reports what a naive text parser would see for the same target, so the blind spot is
  quantified rather than asserted.

.PARAMETER BuildDir
  A configured build directory (contains compile_commands.json and/or .cmake/api/v1/reply).

.PARAMETER Targets
  Target names to report.

.PARAMETER OutJson
  Optional path for the machine-readable result.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$BuildDir,
    [string[]]$Targets = @('mca_native', 'mca_mnn_native', 'mca_qnn_native', 'mca_prompt_handoff'),
    [string]$SourceDir = 'E:\MAC\core\native\src\main\cpp',
    [string]$OutJson
)
$ErrorActionPreference = 'Stop'

function Get-McaRelativePath {
    # [System.IO.Path]::GetRelativePath is .NET Core 2.0+ only; this host is PowerShell 5.1
    # (Desktop / .NET Framework), where that call throws. Compute it explicitly.
    param([string]$Base, [string]$Full)
    try {
        $b = (Resolve-Path -LiteralPath $Base).Path.TrimEnd('\', '/')
        $f = (Resolve-Path -LiteralPath $Full).Path
        if ($f.StartsWith($b, [System.StringComparison]::OrdinalIgnoreCase)) {
            return $f.Substring($b.Length).TrimStart('\', '/')
        }
        return $Full
    } catch { return $Full }
}

function Get-NaiveTextCount {
    param([string]$CmakeLists, [string]$Target)
    $text = Get-Content -LiteralPath $CmakeLists -Raw
    $m = [regex]::Match($text, "add_library\(\s*$Target\s+SHARED\s+(?<body>[^)]*)\)", 'Singleline')
    if (-not $m.Success) { return $null }
    $tokens = @($m.Groups['body'].Value -split '\s+' | Where-Object { $_ -and $_ -notmatch '^\s*#' })
    return [pscustomobject]@{
        rawTokens      = $tokens
        variableTokens = @($tokens | Where-Object { $_ -like '*${*' })
        concreteFiles  = @($tokens | Where-Object { $_ -notlike '*${*' -and $_ -match '\.(cpp|c|cc|cxx|hpp|h)$' })
    }
}

# ---- primary: compile_commands.json -------------------------------------------------
$ccPath = Join-Path $BuildDir 'compile_commands.json'
if (-not (Test-Path -LiteralPath $ccPath)) { throw "no compile_commands.json in $BuildDir" }
$cc = Get-Content -LiteralPath $ccPath -Raw | ConvertFrom-Json

$byTarget = @{}
foreach ($e in $cc) {
    $cmdText = if ($e.command) { $e.command } else { ($e.arguments -join ' ') }
    $m = [regex]::Match($cmdText, 'CMakeFiles[\\/](?<t>[^\\/]+)\.dir[\\/]')
    if (-not $m.Success) { continue }
    $t = $m.Groups['t'].Value
    if (-not $byTarget.ContainsKey($t)) { $byTarget[$t] = New-Object System.Collections.Generic.List[string] }
    $rel = $e.file
    if ([System.IO.Path]::IsPathRooted($rel)) {
        $rel = Get-McaRelativePath -Base $SourceDir -Full $rel
    }
    $byTarget[$t].Add(($rel -replace '\\', '/'))
}

# ---- cross-check: CMake File API codemodel-v2 (optional) ----------------------------
$fileApi = @{}
$replyDir = Join-Path $BuildDir '.cmake\api\v1\reply'
if (Test-Path -LiteralPath $replyDir) {
    try {
        $indexPath = (Get-ChildItem -Path (Join-Path $replyDir 'index-*.json') | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1).FullName
        if ($indexPath) {
            $index = Get-Content -LiteralPath $indexPath -Raw | ConvertFrom-Json
            $cmObj = $index.reply.'codemodel-v2'
            if ($cmObj) {
                $cm = Get-Content -LiteralPath (Join-Path $replyDir $cmObj.jsonFile) -Raw | ConvertFrom-Json
                foreach ($cfg in $cm.configurations) {
                    $cfgObj = Get-Content -LiteralPath (Join-Path $replyDir $cfg.jsonFile) -Raw | ConvertFrom-Json
                    foreach ($t in $cfgObj.targets) {
                        $tObj = Get-Content -LiteralPath (Join-Path $replyDir $t.jsonFile) -Raw | ConvertFrom-Json
                        $fileApi[$t.name] = @($tObj.sources | ForEach-Object { $_.path })
                    }
                }
            }
        }
    } catch { }
}

$rows = New-Object System.Collections.Generic.List[object]
foreach ($t in $Targets) {
    $list = if ($byTarget.ContainsKey($t)) { @($byTarget[$t] | Sort-Object -Unique) } else { @() }
    if ($list.Count -eq 0) { continue }
    $rows.Add([pscustomobject]@{
        target          = $t
        expandedCount   = $list.Count
        expanded        = $list
        fileApiCount    = if ($fileApi.ContainsKey($t)) { @($fileApi[$t]).Count } else { $null }
    })
}

$naive = Get-NaiveTextCount -CmakeLists (Join-Path $SourceDir 'CMakeLists.txt') -Target 'mca_native'

Write-Host "=== expansion via compile_commands.json (authoritative) ==="
foreach ($r in $rows) { Write-Host ("  {0,-24} translationUnits={1}" -f $r.target, $r.expandedCount) }
Write-Host ""
Write-Host "=== what the text parser sees for mca_native ==="
Write-Host ("  literal tokens in add_library() : {0}" -f $naive.rawTokens.Count)
Write-Host ("  variable references            : {0}  {1}" -f $naive.variableTokens.Count, ($naive.variableTokens -join ', '))
Write-Host ("  concrete .cpp visible to text  : {0}" -f $naive.concreteFiles.Count)
$mn = $rows | Where-Object { $_.target -eq 'mca_native' } | Select-Object -First 1
if ($mn) {
    Write-Host ("  BLIND SPOT: text sees {0} file(s); build compiles {1}" -f $naive.concreteFiles.Count, $mn.expandedCount)
}

if ($OutJson) {
    # Ordered hashtables (not [pscustomobject]) - the PSCustomObject literal throws an
    # ArgumentException on this PowerShell 5.1 host once nested objects are involved.
    $payload = [ordered]@{
        schema       = 'mca.target-source-expansion/1'
        buildDir     = $BuildDir
        generatedUtc = [datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
        method       = 'compile_commands.json target attribution via CMakeFiles/<target>.dir; File API codemodel-v2 as optional cross-check'
        textParser   = [ordered]@{
            target               = 'mca_native'
            literalTokens        = @($naive.rawTokens)
            variableTokens       = @($naive.variableTokens)
            concreteFilesVisible = @($naive.concreteFiles)
        }
        targets      = @($rows | ForEach-Object {
                [ordered]@{ target = $_.target; expandedCount = $_.expandedCount; expanded = @($_.expanded) }
            })
    }
    $dir = Split-Path -Parent $OutJson
    if ($dir -and -not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    [System.IO.File]::WriteAllText($OutJson, ($payload | ConvertTo-Json -Depth 6), (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "wrote: $OutJson"
}
