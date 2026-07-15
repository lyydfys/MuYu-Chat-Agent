[CmdletBinding()]
param(
    [string]$SourceRoot,
    [string]$ManifestPath,
    [string]$RepositorySource,
    [string]$SourceRef
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if ([string]::IsNullOrWhiteSpace($SourceRoot)) {
    $SourceRoot = Join-Path $repoRoot 'third_party\MNN'
}
if ([string]::IsNullOrWhiteSpace($ManifestPath)) {
    $ManifestPath = Join-Path $repoRoot 'vendor\mnn\mnn-vendor.properties'
}
$SourceRoot = [System.IO.Path]::GetFullPath($SourceRoot)
$ManifestPath = [System.IO.Path]::GetFullPath($ManifestPath)

Import-Module (Join-Path $PSScriptRoot 'MnnVendorContract.psm1') -Force
$contract = Read-MnnVendorContract -ManifestPath $ManifestPath

try {
    $verified = Assert-MnnVendorCheckout -SourceRoot $SourceRoot -ManifestPath $ManifestPath
    Write-Host "MNN vendor checkout is already prepared at $($verified.SourceRoot)."
    return
}
catch {
    $initialFailure = $_.Exception.Message
}

if (-not (Test-Path -LiteralPath $SourceRoot)) {
    $parent = Split-Path -Parent $SourceRoot
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    $repository = if ([string]::IsNullOrWhiteSpace($RepositorySource)) {
        $contract.Repository
    }
    else {
        $RepositorySource
    }
    $ref = if ([string]::IsNullOrWhiteSpace($SourceRef)) { $contract.Ref } else { $SourceRef }
    Write-Host "Cloning pinned MNN ref '$ref' from $repository ..."
    Invoke-MnnVendorGit -Arguments @(
        'clone', '--filter=blob:none', '--depth', '1', '--branch', $ref,
        '--single-branch', '--no-checkout', $repository, $SourceRoot
    ) | Out-Null
    Invoke-MnnVendorGit -Arguments @('-C', $SourceRoot, 'checkout', '--detach', $contract.Commit) | Out-Null
}
elseif (-not (Test-Path -LiteralPath $SourceRoot -PathType Container)) {
    throw "MNN vendor path exists but is not a directory: $SourceRoot"
}

$inside = Invoke-MnnVendorGit -Arguments @('-C', $SourceRoot, 'rev-parse', '--is-inside-work-tree') -AllowFailure
if ($inside.ExitCode -ne 0 -or $inside.Output.Trim() -ne 'true') {
    throw "Refusing to replace existing non-Git MNN vendor directory: $SourceRoot`nInitial verification: $initialFailure"
}
$head = (Invoke-MnnVendorGit -Arguments @('-C', $SourceRoot, 'rev-parse', 'HEAD')).Output.Trim().ToLowerInvariant()
if ($head -ne $contract.Commit) {
    throw "Refusing to reset MNN vendor checkout at unexpected commit $head; expected $($contract.Commit).`nInitial verification: $initialFailure"
}
$status = (Invoke-MnnVendorGit -Arguments @(
    '-C', $SourceRoot, 'status', '--porcelain=v1', '--untracked-files=all'
)).Output
if (-not [string]::IsNullOrWhiteSpace($status)) {
    throw "Refusing to overwrite a partially patched or dirty MNN checkout. Restore a clean pinned commit or fix the drift first.`n$status"
}

Invoke-MnnVendorGit -Arguments @(
    '-C', $SourceRoot, 'apply', '--check', '--whitespace=nowarn', $contract.PatchPath
) | Out-Null
Invoke-MnnVendorGit -Arguments @(
    '-C', $SourceRoot, 'apply', '--intent-to-add', '--whitespace=nowarn', $contract.PatchPath
) | Out-Null

$verified = Assert-MnnVendorCheckout -SourceRoot $SourceRoot -ManifestPath $ManifestPath
Write-Host 'MNN vendor checkout prepared:'
Write-Host "  source: $($verified.SourceRoot)"
Write-Host "  commit: $($verified.Commit)"
Write-Host "  patch SHA-256: $($verified.PatchSha256)"
