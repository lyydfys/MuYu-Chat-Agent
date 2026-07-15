[CmdletBinding()]
param(
    [string]$SourceRoot,
    [string]$ManifestPath
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

Import-Module (Join-Path $PSScriptRoot 'MnnVendorContract.psm1') -Force
$result = Assert-MnnVendorCheckout -SourceRoot $SourceRoot -ManifestPath $ManifestPath

Write-Host 'MNN vendor checkout verified:'
Write-Host "  source: $($result.SourceRoot)"
Write-Host "  commit: $($result.Commit)"
Write-Host "  patch SHA-256: $($result.PatchSha256)"
Write-Host "  patched files: $($result.Files.Count)"
