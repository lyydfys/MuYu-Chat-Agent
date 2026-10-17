<#
  T24 Task B: authoritative inventory of untracked PRODUCTION inputs, cross-checked against the
  build manifest of the delivered APK. Read-only with respect to the repository: it does not stage,
  commit or publish anything, and it does not touch `.git/info/exclude`.
#>
[CmdletBinding()]
param(
    [string]$RepoRoot = 'E:\MAC',
    [string]$BuildRecord = 'E:\MAC\artifacts\apk-build-records\debug-20261009-193451-878Z.json',
    [string]$OutDir = 'E:\MAC\artifacts\repair\20261010-audit-fix\T24'
)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$prodExt = @('.cpp', '.hpp', '.h', '.c', '.cc', '.cxx', '.hxx', '.inl', '.kt', '.java', '.rs', '.s', '.S')
# Files under these path fragments are test/tooling inputs, not shipped production inputs.
$testFragments = @('/src/test/', '/src/androidTest/', '/src/debug/', '/tools/', '/scripts/')

function Get-ExtLower([string]$p) { return [System.IO.Path]::GetExtension($p).ToLowerInvariant() }

$rec = Get-Content -LiteralPath $BuildRecord -Raw | ConvertFrom-Json
$man = @($rec.sourceProvenance.manifest)

# ---- manifest side
$manMap = @{}
foreach ($e in $man) {
    $p = ($e.path -replace '\\', '/')
    $manMap[$p] = $e
}
$manUntracked = @($man | Where-Object { $_.parentTracked -eq $false })
$manUntrackedProd = @($manUntracked | Where-Object { $prodExt -contains (Get-ExtLower $_.path) })
$manUntrackedProdShipped = @($manUntrackedProd | Where-Object {
        $p = ($_.path -replace '\\', '/')
        $isTest = $false
        foreach ($f in $testFragments) { if ($p -like "*$f*") { $isTest = $true; break } }
        -not $isTest
    })

# ---- live side
# -uall is REQUIRED: without it git collapses an untracked directory into a single
# `?? dir/` entry, which silently hides every file inside it (for example the vendored
# third_party trees). Comparing a collapsed list against a per-file manifest would
# under-report by thousands of entries.
$statusLines = @(& git -C $RepoRoot status --porcelain -uall 2>$null)
$liveUntracked = @($statusLines |
    Where-Object { $_ -match '^\?\?' } |
    ForEach-Object { (($_ -replace '^\?\?\s*', '').Trim() -replace '"', '') -replace '\\', '/' })
$liveUntrackedProd = @($liveUntracked | Where-Object { $prodExt -contains (Get-ExtLower $_) })
$liveUntrackedProdShipped = @($liveUntrackedProd | Where-Object {
        $isTest = $false
        foreach ($f in $testFragments) { if ($_ -like "*$f*") { $isTest = $true; break } }
        -not $isTest
    })

# ---- inventory: every live untracked production input, with hash and manifest presence
$inventory = foreach ($p in ($liveUntrackedProd | Sort-Object)) {
    $full = Join-Path $RepoRoot ($p -replace '/', '\')
    $sha = ''
    $bytes = -1
    if (Test-Path -LiteralPath $full) {
        $sha = (Get-FileHash -LiteralPath $full -Algorithm SHA256).Hash.ToLowerInvariant()
        $bytes = [int64](Get-Item -LiteralPath $full).Length
    }
    $inManifest = $manMap.ContainsKey($p)
    $manSha = if ($inManifest) { $manMap[$p].sha256 } else { '' }
    $isTest = $false
    foreach ($f in $testFragments) { if ($p -like "*$f*") { $isTest = $true; break } }
    [pscustomobject]@{
        path                = $p
        sha256              = $sha
        bytes               = $bytes
        tracked             = $false
        shippedProduction   = (-not $isTest)
        inBuildManifest     = $inManifest
        buildManifestSha256 = $manSha
        hashMatchesManifest = ($inManifest -and ($manSha -eq $sha))
    }
}

# ---- diffs
$missingFromManifest = @($liveUntrackedProd | Where-Object { -not $manMap.ContainsKey($_) })
$goneFromLive = @($manUntrackedProd | Where-Object {
        $p = ($_.path -replace '\\', '/')
        -not ($liveUntracked -contains $p)
    })

$summary = [pscustomobject]@{
    schema                     = 'mca.untracked-production-inputs/1'
    generatedUtc               = [datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
    repoRoot                   = $RepoRoot
    buildRecord                = $BuildRecord
    buildRecordApkSha256       = $rec.apkSha256
    buildManifestEntries       = $man.Count
    buildManifestUntracked     = $manUntracked.Count
    buildManifestUntrackedProd = $manUntrackedProd.Count
    buildManifestUntrackedShippedProd = $manUntrackedProdShipped.Count
    liveUntrackedTotal         = $liveUntracked.Count
    liveUntrackedProd          = $liveUntrackedProd.Count
    liveUntrackedShippedProd   = $liveUntrackedProdShipped.Count
    inventoryCount             = @($inventory).Count
    inventoryHashMismatch      = @($inventory | Where-Object { $_.inBuildManifest -and -not $_.hashMatchesManifest }).Count
    missingFromManifest        = $missingFromManifest
    goneFromLive               = $goneFromLive
    inventory                  = @($inventory)
}
$jsonPath = Join-Path $OutDir 'untracked-production-inputs.json'
[System.IO.File]::WriteAllText($jsonPath, ($summary | ConvertTo-Json -Depth 6), (New-Object System.Text.UTF8Encoding($false)))

Write-Host "manifest entries                : $($man.Count)"
Write-Host "manifest untracked              : $($manUntracked.Count)"
Write-Host "  of which production ext       : $($manUntrackedProd.Count)"
Write-Host "  of which SHIPPED production   : $($manUntrackedProdShipped.Count)"
Write-Host "live untracked total            : $($liveUntracked.Count)"
Write-Host "live untracked production ext   : $($liveUntrackedProd.Count)"
Write-Host "live untracked SHIPPED prod     : $($liveUntrackedProdShipped.Count)"
Write-Host "inventory rows                  : $(@($inventory).Count)"
Write-Host "hash mismatches vs build        : $(@($inventory | Where-Object { $_.inBuildManifest -and -not $_.hashMatchesManifest }).Count)"
Write-Host "live-not-in-manifest            : $($missingFromManifest.Count)"
Write-Host "manifest-untracked-gone-live    : $($goneFromLive.Count)"
Write-Host "wrote: $jsonPath"
