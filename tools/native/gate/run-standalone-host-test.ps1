# MCA repair runId=20261009-parity / T07 test-trust
# Minimal host runner for the standalone source-contract / behaviour test .cpp files under
# core/native/src/test/cpp.  It mirrors the MSVC discovery that
# tools/tests/run-native-image-contract-host-tests.ps1 uses (vswhere -> vcvars64.bat), compiles ONE
# test executable in an isolated directory and runs it, recording command, exit code and transcript.
#
# It exists so a single host test can be re-run and re-verified without editing the repository-wide
# runner (which is owned elsewhere), and so negative controls can be built in isolated directories.
param(
    [Parameter(Mandatory = $true)][string]$TestSource,       # absolute or repo-relative .cpp
    [string[]]$TestArgs = @(),
    [string]$RepoRoot = 'E:\MAC',
    [string]$OutDir = 'E:\MAC\build\t07-gate-sensitivity\source-contract',
    [string]$EvidenceDir = 'E:\MAC\artifacts\repair\20261009-parity\tests\gate\source-contract',
    [string[]]$ExtraSources = @(),
    [string[]]$ExtraIncludeDirs = @(),
    [string[]]$Definitions = @()
)

$ErrorActionPreference = 'Stop'

$testPath = $TestSource
if (-not [System.IO.Path]::IsPathRooted($testPath)) { $testPath = Join-Path $RepoRoot $TestSource }
if (-not (Test-Path -LiteralPath $testPath)) { throw "test source not found: $testPath" }

function Resolve-VcVars64 {
    $vswhere = 'C:\Program Files (x86)\Microsoft Visual Studio\Installer\vswhere.exe'
    if (Test-Path -LiteralPath $vswhere) {
        $installation = (& $vswhere -latest -products * `
            -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 `
            -property installationPath 2>$null | Select-Object -First 1)
        if ($installation) {
            $candidate = Join-Path $installation.Trim() 'VC\Auxiliary\Build\vcvars64.bat'
            if (Test-Path -LiteralPath $candidate) { return $candidate }
        }
    }
    $fallback = 'C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat'
    if (Test-Path -LiteralPath $fallback) { return $fallback }
    throw 'Visual Studio Build Tools with vcvars64.bat is required.'
}

$vcvars = Resolve-VcVars64
# .NET file APIs resolve relative paths against the process CWD, which is NOT the PowerShell location;
# make both directories absolute up front or the evidence write fails from a different cwd.
if (-not [System.IO.Path]::IsPathRooted($OutDir)) { $OutDir = Join-Path (Get-Location).ProviderPath $OutDir }
if (-not [System.IO.Path]::IsPathRooted($EvidenceDir)) { $EvidenceDir = Join-Path (Get-Location).ProviderPath $EvidenceDir }
New-Item -ItemType Directory -Force -Path $OutDir, $EvidenceDir | Out-Null

$name = [System.IO.Path]::GetFileNameWithoutExtension($testPath)
$exe = Join-Path $OutDir "$name.exe"
if (Test-Path -LiteralPath $exe) { Remove-Item -LiteralPath $exe -Force }

$sources = @($testPath) + @($ExtraSources | ForEach-Object {
    if ([System.IO.Path]::IsPathRooted($_)) { $_ } else { Join-Path $RepoRoot $_ }
})
$includeArgs = @($ExtraIncludeDirs | ForEach-Object { "/I`"$_`"" })
$defineArgs = @($Definitions | ForEach-Object { "/D$_" })
$compile = "cl.exe /nologo /std:c++17 /EHsc /W4 /Fe:`"$exe`" " + (($sources | ForEach-Object { "`"$_`"" }) -join ' ') + ' ' + ($includeArgs -join ' ') + ' ' + ($defineArgs -join ' ')

$bat = Join-Path $OutDir "$name.build.bat"
@(
    '@echo off',
    "call `"$vcvars`" >nul",
    $compile,
    'exit /b %ERRORLEVEL%'
) | Set-Content -LiteralPath $bat -Encoding ascii

$buildOut = @(& $env:ComSpec /d /s /c "`"$bat`"" 2>&1 | ForEach-Object { "$_" })
$buildExit = $LASTEXITCODE

$runOut = @()
$runExit = $null
if ($buildExit -eq 0 -and (Test-Path -LiteralPath $exe)) {
    $runOut = @(& $exe @TestArgs 2>&1 | ForEach-Object { "$_" })
    $runExit = $LASTEXITCODE
}

$stamp = [datetime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
$logPath = Join-Path $EvidenceDir "$name.$stamp.log"
$text = @(
    "===== MCA T07 standalone host test =====",
    "test=$testPath",
    "args=$($TestArgs -join ' ')",
    "vcvars64=$vcvars",
    "compileCommand=$compile",
    "buildExit=$buildExit",
    "runExit=$runExit",
    '',
    '--- build output ---'
) + $buildOut + @('', '--- run output ---') + $runOut
[System.IO.File]::WriteAllText($logPath, (($text -join "`n") + "`n"), (New-Object System.Text.UTF8Encoding($false)))

Write-Output "log: $logPath"
Write-Output "buildExit=$buildExit runExit=$runExit"
foreach ($l in $buildOut) { if ($l -match 'error|warning C') { Write-Output "  $l" } }
foreach ($l in $runOut) { Write-Output "  $l" }

if ($buildExit -ne 0) { exit 2 }
if ($runExit -ne 0) { exit 1 }
exit 0
