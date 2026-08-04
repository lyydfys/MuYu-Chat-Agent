[CmdletBinding()]
param(
    [switch]$KeepBuildArtifacts
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$buildParent = Join-Path $repoRoot 'build'
$buildRoot = Join-Path $buildParent (
    'native-image-contract-host-' + $PID + '-' + [guid]::NewGuid().ToString('N'))
$originalTemp = $env:TEMP
$originalTmp = $env:TMP

function Resolve-VcVars64 {
    $vswhere = 'C:\Program Files (x86)\Microsoft Visual Studio\Installer\vswhere.exe'
    if (Test-Path -LiteralPath $vswhere) {
        $installation = (& $vswhere -latest -products * `
            -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 `
            -property installationPath | Select-Object -First 1)
        if (-not [string]::IsNullOrWhiteSpace($installation)) {
            $candidate = Join-Path $installation 'VC\Auxiliary\Build\vcvars64.bat'
            if (Test-Path -LiteralPath $candidate) {
                return $candidate
            }
        }
    }
    $fallback = 'C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat'
    if (Test-Path -LiteralPath $fallback) {
        return $fallback
    }
    throw 'Visual Studio Build Tools with vcvars64.bat is required.'
}

function Import-VcVars64Environment {
    param([Parameter(Mandatory = $true)][string]$VcVarsPath)

    $lines = @(& $env:ComSpec /d /s /c "call `"$VcVarsPath`" >nul && set")
    if ($LASTEXITCODE -ne 0) {
        throw "vcvars64.bat failed with exit code $LASTEXITCODE."
    }
    foreach ($line in $lines) {
        $text = [string]$line
        $separator = $text.IndexOf('=')
        if ($separator -le 0) { continue }
        $name = $text.Substring(0, $separator)
        if ($name.StartsWith('=')) { continue }
        Set-Item -LiteralPath ("Env:" + $name) -Value $text.Substring($separator + 1)
    }
}

function Build-HostTest {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string[]]$Sources,
        [string[]]$IncludeDirectories = @(),
        [string[]]$Definitions = @()
    )

    $testBuild = Join-Path $buildRoot $Name
    New-Item -ItemType Directory -Path $testBuild -Force | Out-Null
    $executable = Join-Path $testBuild ($Name + '.exe')
    $arguments = @(
        '/nologo',
        '/std:c++17',
        '/EHsc',
        '/W4',
        '/utf-8',
        '/D_CRT_SECURE_NO_WARNINGS'
    )
    foreach ($definition in $Definitions) {
        $arguments += '/D' + $definition
    }
    foreach ($include in $IncludeDirectories) {
        $arguments += '/I' + ([System.IO.Path]::GetFullPath($include))
    }
    $arguments += $Sources | ForEach-Object { [System.IO.Path]::GetFullPath($_) }
    $arguments += '/Fe:' + $executable

    Write-Host "BUILD $Name"
    Push-Location $testBuild
    try {
        $compilerOutput = @(& $script:cl @arguments 2>&1)
        $compilerExitCode = $LASTEXITCODE
        $compilerOutput | ForEach-Object { Write-Host ([string]$_) }
        if ($compilerExitCode -ne 0) {
            throw "cl failed for $Name with exit code $compilerExitCode."
        }
    }
    finally {
        Pop-Location
    }
    if (-not (Test-Path -LiteralPath $executable -PathType Leaf)) {
        throw "cl did not create $executable."
    }
    return $executable
}

function Run-HostTest {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Executable,
        [string[]]$Arguments = @()
    )

    Write-Host "RUN   $Name"
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE."
    }
    Write-Host "PASS  $Name"
}

New-Item -ItemType Directory -Path $buildRoot -Force | Out-Null
try {
    $vcvars = Resolve-VcVars64
    Import-VcVars64Environment -VcVarsPath $vcvars
    $script:cl = (Get-Command cl.exe -CommandType Application -ErrorAction Stop).Source

    $hostTemp = Join-Path $buildRoot '_tmp'
    New-Item -ItemType Directory -Path $hostTemp -Force | Out-Null
    $env:TEMP = $hostTemp
    $env:TMP = $hostTemp

    $nativeMain = Join-Path $repoRoot 'core\native\src\main\cpp'
    $nativeTests = Join-Path $repoRoot 'core\native\src\test\cpp'
    $sdTests = Join-Path $repoRoot 'core\sd-native\src\test\cpp'
    $sdRoot = Join-Path $repoRoot 'third_party\stable-diffusion.cpp'
    $sdThirdParty = Join-Path $sdRoot 'thirdparty'
    $llamaVendor = Join-Path $repoRoot 'third_party\llama.cpp\vendor'
    $tokenizerRoot = Join-Path $sdRoot 'src\tokenizers'
    $compatRoot = Join-Path $nativeTests 'windows_posix_compat'

    $ultraFix = Build-HostTest `
        -Name 'stable_diffusion_ultrafix_contract_test' `
        -Sources @(Join-Path $sdTests 'stable_diffusion_ultrafix_contract_test.cpp')
    Run-HostTest -Name 'stable_diffusion_ultrafix_contract_test' -Executable $ultraFix -Arguments @(
        (Join-Path $sdRoot 'include\stable-diffusion.h'),
        (Join-Path $sdRoot 'src\stable-diffusion.cpp'),
        (Join-Path $repoRoot 'core\sd-native\src\main\cpp\stable_diffusion_bridge.cpp'),
        (Join-Path $nativeMain 'qnn_native_bridge.cpp'),
        (Join-Path $nativeMain 'qnn_sdxl_isolated_phases.hpp')
    )

    $sdTextualInversion = Build-HostTest `
        -Name 'stable_diffusion_textual_inversion_contract_test' `
        -Sources @(Join-Path $sdTests 'stable_diffusion_textual_inversion_contract_test.cpp')
    Run-HostTest `
        -Name 'stable_diffusion_textual_inversion_contract_test' `
        -Executable $sdTextualInversion `
        -Arguments @(Join-Path $repoRoot 'core\sd-native\src\main\cpp\stable_diffusion_bridge.cpp')

    $sdTokenizer = Build-HostTest `
        -Name 'stable_diffusion_textual_inversion_tokenizer_test' `
        -Sources @(
            (Join-Path $sdTests 'stable_diffusion_textual_inversion_tokenizer_test.cpp'),
            (Join-Path $tokenizerRoot 'tokenize_util.cpp')
        ) `
        -IncludeDirectories @($tokenizerRoot)
    Run-HostTest -Name 'stable_diffusion_textual_inversion_tokenizer_test' -Executable $sdTokenizer

    $qnnInpaint = Build-HostTest `
        -Name 'qnn_inpaint_contract_test' `
        -Sources @(Join-Path $nativeTests 'qnn_inpaint_contract_test.cpp')
    Run-HostTest -Name 'qnn_inpaint_contract_test' -Executable $qnnInpaint

    $imageExecutionMath = Build-HostTest `
        -Name 'image_execution_math_test' `
        -Sources @(
            (Join-Path $nativeTests 'image_execution_math_test.cpp'),
            (Join-Path $nativeMain 'image_execution_math.cpp')
        ) `
        -IncludeDirectories @($nativeMain)
    Run-HostTest -Name 'image_execution_math_test' -Executable $imageExecutionMath

    $mnnPromptProof = Build-HostTest `
        -Name 'mnn_prompt_native_proof_contract_test' `
        -Sources @(Join-Path $nativeTests 'mnn_prompt_native_proof_contract_test.cpp')
    Run-HostTest `
        -Name 'mnn_prompt_native_proof_contract_test' `
        -Executable $mnnPromptProof `
        -Arguments @(Join-Path $nativeMain 'mnn_native_engine.cpp')

    $qnnPromptProof = Build-HostTest `
        -Name 'qnn_prompt_native_proof_contract_test' `
        -Sources @(
            (Join-Path $nativeTests 'qnn_prompt_native_proof_contract_test.cpp'),
            (Join-Path $nativeMain 'image_conditioning.cpp')
        ) `
        -IncludeDirectories @($nativeMain, $sdThirdParty) `
        -Definitions @('MCA_WITH_TOKENIZERS_CPP=0')
    Run-HostTest `
        -Name 'qnn_prompt_native_proof_contract_test' `
        -Executable $qnnPromptProof `
        -Arguments @(
            (Join-Path $nativeMain 'qnn_native_bridge.cpp'),
            (Join-Path $nativeMain 'qnn_sdxl_isolated_phases.hpp')
        )

    $textualInversionLoad = Build-HostTest `
        -Name 'textual_inversion_conditioning_test' `
        -Sources @(
            (Join-Path $nativeTests 'textual_inversion_conditioning_test.cpp'),
            (Join-Path $nativeMain 'textual_inversion_conditioning.cpp'),
            (Join-Path $nativeMain 'image_conditioning.cpp')
        ) `
        -IncludeDirectories @($nativeMain, $llamaVendor, $compatRoot) `
        -Definitions @('MCA_WITH_TOKENIZERS_CPP=0')
    Run-HostTest `
        -Name 'textual_inversion_conditioning_test' `
        -Executable $textualInversionLoad `
        -Arguments @(Join-Path $buildRoot 'textual-inversion-fixtures')

    Write-Host 'PASS: 8 native image contract host tests compiled and ran successfully.'
}
finally {
    $env:TEMP = $originalTemp
    $env:TMP = $originalTmp
    if (-not $KeepBuildArtifacts) {
        $resolvedBuildRoot = [System.IO.Path]::GetFullPath($buildRoot)
        $resolvedBuildParent = [System.IO.Path]::GetFullPath($buildParent).TrimEnd('\') + '\'
        $leaf = Split-Path -Leaf $resolvedBuildRoot
        if ($resolvedBuildRoot.StartsWith(
                $resolvedBuildParent,
                [System.StringComparison]::OrdinalIgnoreCase) -and
            $leaf.StartsWith('native-image-contract-host-', [System.StringComparison]::Ordinal)) {
            Remove-Item -LiteralPath $resolvedBuildRoot -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}
