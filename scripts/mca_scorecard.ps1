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
    $lines = ($body -split "`n").Count
    $noImport = (($body -split "`n") | Where-Object { $_ -notmatch '^import' }) -join "`n"
    [pscustomobject]@{
        lines      = $lines
        engine     = ([regex]::Matches($noImport, 'engine\.[a-z]')).Count
        store      = ([regex]::Matches($text, 'uiStateStore\.update')).Count
        launch     = ([regex]::Matches($text, 'viewModelScope\.launch')).Count
        thisvm     = ([regex]::Matches($text, 'this@MainViewModel')).Count
        anon       = ([regex]::Matches($text, 'object :')).Count
        atomic     = ([regex]::Matches($text, 'AtomicBoolean|AtomicInteger|AtomicLong|Mutex\(')).Count
        uiupdate   = ([regex]::Matches($text, '_uiState\.update')).Count
        runblocking = ([regex]::Matches($text, 'runBlocking')).Count
    }
}

$w = Get-McaMetric $work
$h = Get-McaMetric $head

"{0,-12} {1,8} {2,8} {3,8}" -f 'metric', 'now', 'head', 'delta'
foreach ($k in 'lines', 'engine', 'store', 'launch', 'thisvm', 'anon', 'atomic', 'uiupdate', 'runblocking') {
    "{0,-12} {1,8} {2,8} {3,8}" -f $k, $w.$k, $h.$k, ($w.$k - $h.$k)
}
