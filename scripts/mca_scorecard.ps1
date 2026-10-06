param(
    [string]$Vm = 'app/src/main/java/com/muyuchat/mca/MainViewModel.kt'
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
Set-Location $repo

$work = Get-Content -Raw $Vm
$head = (& git show "HEAD:$Vm") -join "`n"

function Get-McaMetric([string]$text) {
    $body = $text.TrimEnd("`n")
    $lines = ($body -split "`n")
    $noImportLines = $lines | Where-Object { $_ -notmatch '^import' }
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
