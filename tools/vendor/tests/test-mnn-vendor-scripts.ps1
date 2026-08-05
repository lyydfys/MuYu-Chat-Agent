[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$vendorTools = Split-Path -Parent $PSScriptRoot
$verifyScript = Join-Path $vendorTools 'verify-mnn-vendor.ps1'
$bootstrapScript = Join-Path $vendorTools 'bootstrap-mnn-vendor.ps1'
$git = (Get-Command git -CommandType Application -ErrorAction Stop |
    Select-Object -First 1).Source
$tempBase = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$tempRoot = Join-Path $tempBase ("mca-mnn-vendor-test-" + [guid]::NewGuid().ToString('N'))

function Invoke-TestGit {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    $previousErrorAction = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& $git @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorAction
    }
    if ($exitCode -ne 0) {
        throw "Test Git command failed: git $($Arguments -join ' ')`n$($output -join [Environment]::NewLine)"
    }
    return (($output | ForEach-Object { [string]$_ }) -join "`n").Trim()
}

function Write-Utf8NoBom {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Text
    )

    [System.IO.File]::WriteAllText($Path, $Text, (New-Object System.Text.UTF8Encoding($false)))
}

function Get-NormalizedSha256 {
    param([Parameter(Mandatory = $true)][string]$Path)

    $text = [System.IO.File]::ReadAllText($Path).Replace("`r`n", "`n").Replace("`r", "`n")
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        return (($sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($text)) |
            ForEach-Object { $_.ToString('x2') }) -join '')
    }
    finally {
        $sha.Dispose()
    }
}

function Assert-FailsLike {
    param(
        [Parameter(Mandatory = $true)][scriptblock]$Action,
        [Parameter(Mandatory = $true)][string]$Pattern,
        [Parameter(Mandatory = $true)][string]$Description
    )

    try {
        & $Action *> $null
    }
    catch {
        if ($_.Exception.Message -notmatch $Pattern) {
            throw "$Description failed with the wrong diagnostic: $($_.Exception.Message)"
        }
        return
    }
    throw "$Description unexpectedly succeeded."
}

New-Item -ItemType Directory -Path $tempRoot -Force | Out-Null
try {
    $seed = Join-Path $tempRoot 'seed'
    $manifestDir = Join-Path $tempRoot 'contract'
    $target = Join-Path $tempRoot 'target'
    $cleanTarget = Join-Path $tempRoot 'clean-target'
    New-Item -ItemType Directory -Path $seed, $manifestDir -Force | Out-Null

    Invoke-TestGit @('init', '--quiet', $seed) | Out-Null
    Invoke-TestGit @('-C', $seed, 'config', 'user.email', 'mnn-vendor-test@example.invalid') | Out-Null
    Invoke-TestGit @('-C', $seed, 'config', 'user.name', 'MNN Vendor Test') | Out-Null
    Write-Utf8NoBom -Path (Join-Path $seed 'sample.txt') -Text "base`n"
    Invoke-TestGit @('-C', $seed, 'add', 'sample.txt') | Out-Null
    Invoke-TestGit @('-C', $seed, 'commit', '--quiet', '-m', 'fixture base') | Out-Null
    $commit = Invoke-TestGit @('-C', $seed, 'rev-parse', 'HEAD')
    Invoke-TestGit @('-C', $seed, 'tag', 'fixture-v1') | Out-Null

    Write-Utf8NoBom -Path (Join-Path $seed 'sample.txt') -Text "patched`n"
    $patchPath = Join-Path $manifestDir 'fixture.patch'
    Invoke-TestGit @(
        '-c', 'core.safecrlf=false', '-C', $seed, 'diff', '--binary', '--full-index',
        '--no-ext-diff', '--no-color', "--output=$patchPath", 'HEAD', '--'
    ) | Out-Null
    $patchSha = Get-NormalizedSha256 -Path $patchPath
    $manifestPath = Join-Path $manifestDir 'fixture.properties'
    Write-Utf8NoBom -Path $manifestPath -Text @"
repository=$seed
ref=fixture-v1
commit=$commit
patch=fixture.patch
patchSha256=$patchSha
files=sample.txt
"@
    Invoke-TestGit @('-C', $seed, 'restore', 'sample.txt') | Out-Null

    & $bootstrapScript `
        -SourceRoot $target `
        -ManifestPath $manifestPath `
        -RepositorySource $seed `
        -SourceRef 'fixture-v1' *> $null
    & $verifyScript -SourceRoot $target -ManifestPath $manifestPath *> $null
    & $bootstrapScript -SourceRoot $target -ManifestPath $manifestPath *> $null

    Write-Utf8NoBom -Path (Join-Path $target 'unexpected.txt') -Text "drift`n"
    Assert-FailsLike `
        -Action { & $verifyScript -SourceRoot $target -ManifestPath $manifestPath } `
        -Pattern 'untracked drift' `
        -Description 'untracked drift verification'
    Remove-Item -LiteralPath (Join-Path $target 'unexpected.txt') -Force

    Write-Utf8NoBom -Path (Join-Path $target 'sample.txt') -Text "patched`nextra`n"
    Assert-FailsLike `
        -Action { & $verifyScript -SourceRoot $target -ManifestPath $manifestPath } `
        -Pattern 'diff mismatch' `
        -Description 'tracked drift verification'
    Write-Utf8NoBom -Path (Join-Path $target 'sample.txt') -Text "patched`n"
    & $verifyScript -SourceRoot $target -ManifestPath $manifestPath *> $null

    Invoke-TestGit @('clone', '--quiet', '--branch', 'fixture-v1', '--single-branch', $seed, $cleanTarget) | Out-Null
    Invoke-TestGit @('-C', $cleanTarget, 'checkout', '--quiet', '--detach', $commit) | Out-Null
    Assert-FailsLike `
        -Action { & $verifyScript -SourceRoot $cleanTarget -ManifestPath $manifestPath } `
        -Pattern 'overlay patch is not applied' `
        -Description 'unapplied patch verification'
    Write-Utf8NoBom -Path (Join-Path $cleanTarget 'sample.txt') -Text "partial`n"
    Assert-FailsLike `
        -Action { & $bootstrapScript -SourceRoot $cleanTarget -ManifestPath $manifestPath } `
        -Pattern 'Refusing to overwrite' `
        -Description 'dirty bootstrap refusal'

    Invoke-TestGit @('-C', $cleanTarget, 'restore', 'sample.txt') | Out-Null
    Invoke-TestGit @('-C', $cleanTarget, 'config', 'user.email', 'mnn-vendor-test@example.invalid') | Out-Null
    Invoke-TestGit @('-C', $cleanTarget, 'config', 'user.name', 'MNN Vendor Test') | Out-Null
    Write-Utf8NoBom -Path (Join-Path $cleanTarget 'second.txt') -Text "second`n"
    Invoke-TestGit @('-C', $cleanTarget, 'add', 'second.txt') | Out-Null
    Invoke-TestGit @('-C', $cleanTarget, 'commit', '--quiet', '-m', 'wrong commit') | Out-Null
    Assert-FailsLike `
        -Action { & $verifyScript -SourceRoot $cleanTarget -ManifestPath $manifestPath } `
        -Pattern 'commit mismatch' `
        -Description 'wrong commit verification'

    $originalPatch = [System.IO.File]::ReadAllText($patchPath)
    Write-Utf8NoBom -Path $patchPath -Text ($originalPatch + "`n")
    Assert-FailsLike `
        -Action { & $verifyScript -SourceRoot $target -ManifestPath $manifestPath } `
        -Pattern 'checksum mismatch' `
        -Description 'patch checksum verification'
    Write-Utf8NoBom -Path $patchPath -Text $originalPatch
    & $verifyScript -SourceRoot $target -ManifestPath $manifestPath *> $null

    Write-Host 'PASS: MNN vendor bootstrap and verification are offline, idempotent, and fail closed.'
}
finally {
    $resolved = [System.IO.Path]::GetFullPath($tempRoot)
    if ($resolved.StartsWith($tempBase, [System.StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path -Leaf $resolved).StartsWith('mca-mnn-vendor-test-', [System.StringComparison]::Ordinal)) {
        Remove-Item -LiteralPath $resolved -Recurse -Force -ErrorAction SilentlyContinue
    }
}
