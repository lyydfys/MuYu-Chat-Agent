param(
    [string]$Vm = 'app/src/main/java/com/muyuchat/mca/MainViewModel.kt'
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
Set-Location $repo

# Force UTF-8 on both reads. The sources carry Chinese comments; under the PS 5.1 default
# (ANSI/GBK) a trailing multi-byte sequence leaves a dangling DBCS lead byte that swallows
# the following newline, which silently merged 41 lines of MainViewModel.kt and under-counted
# every metric that is tallied per line.
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$work = Get-Content -Raw -Encoding UTF8 $Vm
$head = (& git show "HEAD:$Vm") -join "`n"

function Get-McaMetric([string]$text) {
    $body = $text.TrimEnd("`n")
    $lines = ($body -split "`n")
    $noImportLines = $lines | Where-Object { $_ -notmatch '^import' }
    # -match is case-insensitive, so every count below is a case-insensitive line count.
    # Only `engine` is import-filtered; the rest are whole-file. Do not compare these numbers
    # against a case-sensitive grep -c: for `engine` the two differ by the `engine.Capital` hits.
    function Count-Lines([string[]]$ls, [string]$pattern) {
        ($ls | Where-Object { $_ -match $pattern }).Count
    }
    [pscustomobject]@{
        lines       = $lines.Count
        engine      = Count-Lines $noImportLines 'engine\.[a-z]'
        store       = Count-Lines $lines 'uiStateStore\.update'
        launch      = Count-Lines $lines 'viewModelScope\.launch'
        thisvm      = Count-Lines $lines 'this@MainViewModel'
        anon        = Count-Lines $lines 'object :'
        atomic      = Count-Lines $lines 'AtomicBoolean|AtomicInteger|AtomicLong|Mutex\('
        uiupdate    = Count-Lines $lines '_uiState\.update'
        runblocking = Count-Lines $lines 'runBlocking'
    }
}

$w = Get-McaMetric $work
$h = Get-McaMetric $head

"{0,-12} {1,8} {2,8} {3,8}" -f 'metric', 'now', 'head', 'delta'
foreach ($k in 'lines', 'engine', 'store', 'launch', 'thisvm', 'anon', 'atomic', 'uiupdate', 'runblocking') {
    "{0,-12} {1,8} {2,8} {3,8}" -f $k, $w.$k, $h.$k, ($w.$k - $h.$k)
}
