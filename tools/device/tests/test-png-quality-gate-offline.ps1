[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$workspaceRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$validationModule = Join-Path $workspaceRoot 'tools\benchmarks\benchmark-validation.psm1'
if (-not (Test-Path -LiteralPath $validationModule -PathType Leaf)) {
    throw "Benchmark validation module not found: $validationModule"
}

Import-Module $validationModule -Force
Add-Type -AssemblyName System.Drawing

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Assert-Contains {
    param([object[]]$Values, [string]$Expected, [string]$Message)
    if ($Expected -notin @($Values)) { throw "$Message (expected '$Expected', got '$(@($Values) -join ', ')')" }
}

function Write-PngQualityFixture {
    param(
        [string]$Path,
        [ValidateSet('healthy', 'horizontal_stripes', 'soft_horizontal_banding', 'low_dynamic_range', 'monochrome')][string]$Kind
    )

    $bitmap = New-Object System.Drawing.Bitmap -ArgumentList 96, 96
    try {
        for ($y = 0; $y -lt $bitmap.Height; $y++) {
            for ($x = 0; $x -lt $bitmap.Width; $x++) {
                switch ($Kind) {
                    'healthy' {
                        $red = (17 * $x + 11 * $y) % 256
                        $green = (9 * $x + 23 * $y) % 256
                        $blue = ($x * $x + 13 * $y + 41) % 256
                    }
                    'horizontal_stripes' {
                        if (([int]($y / 4) % 2) -eq 0) {
                            $red = 20; $green = 30; $blue = 40
                        } else {
                            $red = 230; $green = 240; $blue = 250
                        }
                    }
                    'soft_horizontal_banding' {
                        $band = [int](78 + 50 * [Math]::Sin($y / 3.0))
                        $texture = (7 * $x + 3 * $y) % 5
                        $red = [Math]::Max(0, [Math]::Min(255, $band + $texture))
                        $green = [Math]::Max(0, [Math]::Min(255, $band + 8 + $texture))
                        $blue = [Math]::Max(0, [Math]::Min(255, $band + 16 + $texture))
                    }
                    'low_dynamic_range' {
                        $level = 118 + (($x + $y) % 10)
                        $red = $level; $green = $level; $blue = $level
                    }
                    'monochrome' {
                        $red = 72; $green = 130; $blue = 188
                    }
                }
                $bitmap.SetPixel($x, $y, [System.Drawing.Color]::FromArgb([int]$red, [int]$green, [int]$blue))
            }
        }
        $bitmap.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally {
        $bitmap.Dispose()
    }
}

function Assert-PngQualityFixture {
    param(
        [string]$Path,
        [bool]$ExpectedPass,
        [AllowEmptyString()][string]$ExpectedFailure
    )

    $result = Get-PngQualityInfo -Path $Path
    Assert-True -Condition ($result.quality.passed -eq $ExpectedPass) -Message "Unexpected quality status for $Path."
    if ($ExpectedPass) {
        $strict = Get-StrictPngInfo -Path $Path
        Assert-True -Condition ($strict.width -eq 96 -and $strict.height -eq 96) -Message "Strict PNG dimensions were not preserved for $Path."
        [void](Assert-PngQuality -Path $Path)
        return
    }

    Assert-Contains -Values @($result.quality.failureReasons) -Expected $ExpectedFailure -Message "PNG quality failure reason mismatch for $Path."
    $qualityError = $null
    try {
        [void](Assert-PngQuality -Path $Path)
    } catch {
        $qualityError = $_.Exception.Message
    }
    Assert-True -Condition (-not [string]::IsNullOrWhiteSpace($qualityError)) -Message "Assert-PngQuality accepted rejected fixture $Path."
    Assert-True -Condition ($qualityError.Contains($ExpectedFailure)) -Message "Quality gate error did not name '$ExpectedFailure' for $Path."
}

$temporaryRoot = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ("mca-png-quality-" + [Guid]::NewGuid().ToString('N'))))
$temporaryParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
if (-not $temporaryRoot.StartsWith($temporaryParent, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to create fixtures outside the temp directory: $temporaryRoot"
}

try {
    New-Item -ItemType Directory -Path $temporaryRoot | Out-Null
    $fixtures = @(
        [pscustomobject]@{ Name = 'healthy'; ExpectedPass = $true; ExpectedFailure = '' },
        [pscustomobject]@{ Name = 'horizontal_stripes'; ExpectedPass = $false; ExpectedFailure = 'horizontal_stripes' },
        [pscustomobject]@{ Name = 'soft_horizontal_banding'; ExpectedPass = $false; ExpectedFailure = 'horizontal_stripes' },
        [pscustomobject]@{ Name = 'low_dynamic_range'; ExpectedPass = $false; ExpectedFailure = 'low_dynamic_range' },
        [pscustomobject]@{ Name = 'monochrome'; ExpectedPass = $false; ExpectedFailure = 'monochrome' }
    )
    foreach ($fixture in $fixtures) {
        $path = Join-Path $temporaryRoot ("$($fixture.Name).png")
        Write-PngQualityFixture -Path $path -Kind $fixture.Name
        Assert-PngQualityFixture -Path $path -ExpectedPass $fixture.ExpectedPass -ExpectedFailure $fixture.ExpectedFailure
    }
    Write-Host 'PASS: offline PNG quality gate accepts healthy output and rejects horizontal stripes, low dynamic range, and monochrome output.'
} finally {
    if (Test-Path -LiteralPath $temporaryRoot -PathType Container) {
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
    }
}
