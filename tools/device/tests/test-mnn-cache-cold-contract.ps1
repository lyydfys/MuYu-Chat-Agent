Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$deviceDir = Split-Path -Parent $PSScriptRoot
Import-Module (Join-Path $deviceDir 'DeviceSmoke.psm1') -Force
$tokens = $null
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile(
    (Join-Path $deviceDir 'run-mnn-chat-smoke.ps1'), [ref]$tokens, [ref]$errors)
if ($errors.Count -ne 0) { throw ($errors | Out-String) }
foreach ($name in @('Get-MnnChatSmokeEventValue', 'Assert-MnnChatSmokeModeContract')) {
    $function = $ast.Find({ param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name
    }, $true)
    . ([scriptblock]::Create($function.Extent.Text))
}
function New-Result {
    [pscustomobject]@{
        status = 'completed'
        nativeStats = [pscustomobject]@{ backend='mnn_cpu'; loaded=$true; runnerReady=$true }
        events = @(
            [pscustomobject]@{ status='mnn_cache_ab_first_ok'; generation=[pscustomobject]@{text='7391'} },
            [pscustomobject]@{ status='mnn_cache_ab_second_ok'; generation=[pscustomobject]@{text='Yes'} },
            [pscustomobject]@{ status='mnn_cache_ab_ok'; cacheHit=$true; reusedTokens=3565; secondGenerationSucceeded=$true
                coldControl=[pscustomobject]@{ generationSucceeded=$true; noReuse=$true; sameRequestTranscript=$true
                    stats=[pscustomobject]@{promptCacheHit=$false; reusedTokens=0}
                    generation=[pscustomobject]@{text='Yes'; doneSeen=$true}
                }
            }
        )
    }
}
Assert-MnnChatSmokeModeContract -Json (New-Result) -Mode mnn_cache_ab -ExpectedBackend mnn_cpu
foreach ($fault in @('missing', 'failed', 'different', 'reused', 'empty', 'notDone')) {
    $result = New-Result
    $cache = $result.events[2]
    switch ($fault) {
        'missing' { $cache.coldControl = $null }
        'failed' { $cache.coldControl.generationSucceeded = $false }
        'different' { $cache.coldControl.sameRequestTranscript = $false }
        'reused' { $cache.coldControl.stats.reusedTokens = 5 }
        'empty' { $cache.coldControl.generation.text = ' ' }
        'notDone' { $cache.coldControl.generation.doneSeen = $false }
    }
    $rejected = $false
    try { Assert-MnnChatSmokeModeContract -Json $result -Mode mnn_cache_ab -ExpectedBackend mnn_cpu }
    catch { $rejected = $true }
    if (-not $rejected) { throw "Cold-control contract accepted $fault" }
}
Write-Host 'PASS: cache A/B requires real cold-control text, Done, identical transcript and zero reuse (7 cases).'
