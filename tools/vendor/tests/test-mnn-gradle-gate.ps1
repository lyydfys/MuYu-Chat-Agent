[CmdletBinding()]
param(
    [string]$JavaHome = $env:JAVA_HOME
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$gradle = Join-Path $repoRoot 'gradlew.bat'
if (-not (Test-Path -LiteralPath $gradle -PathType Leaf)) {
    throw "Gradle wrapper is missing: $gradle"
}
if (-not [string]::IsNullOrWhiteSpace($JavaHome)) {
    $env:JAVA_HOME = [System.IO.Path]::GetFullPath($JavaHome)
}
$missingSource = Join-Path ([System.IO.Path]::GetTempPath()) ("missing-mnn-" + [guid]::NewGuid().ToString('N'))

& $gradle :core:native:verifyMcaMnnVendor --offline --no-daemon
if ($LASTEXITCODE -ne 0) {
    throw "The real MNN Gradle vendor gate failed with exit code $LASTEXITCODE."
}

$previousErrorAction = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    $failureOutput = @(& $gradle `
        :core:native:verifyMcaMnnVendor `
        "-PmcaMnnSourceRoot=$missingSource" `
        --offline `
        --no-daemon 2>&1)
    $failureExitCode = $LASTEXITCODE
}
finally {
    $ErrorActionPreference = $previousErrorAction
}
if ($failureExitCode -eq 0) {
    throw 'The MNN Gradle vendor gate unexpectedly accepted a missing checkout.'
}
if (($failureOutput -join "`n") -notmatch 'MNN vendor checkout is missing') {
    throw "The missing-checkout gate failed with the wrong diagnostic:`n$($failureOutput -join [Environment]::NewLine)"
}

Write-Host 'PASS: Gradle MNN vendor gate accepts the pinned checkout and rejects a missing checkout.'
