$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$modulePath = Join-Path (Split-Path -Parent $PSScriptRoot) 'DeviceSmoke.psm1'
Import-Module $modulePath -Force

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Assert-Throws {
    param(
        [scriptblock]$Action,
        [string]$ExpectedFragment,
        [string]$Message
    )
    try {
        & $Action
    } catch {
        if ($_.Exception.Message.Contains($ExpectedFragment)) { return }
        throw "$Message Unexpected error: $($_.Exception.Message)"
    }
    throw "$Message Expected an exception."
}

$han = ([string][char]0x4E2D) + ([string][char]0x6587)
$emoji = [char]::ConvertFromUtf32(0x1F600)
$expected = "$han$emoji"
$validText = "prefix-$expected-suffix"
$valid = [pscustomobject]@{
    status = 'completed'
    events = @(
        [pscustomobject]@{
            status = 'generation_ok'
            generation = [pscustomobject]@{
                text = $validText
                textPreview = $validText
            }
        },
        [pscustomobject]@{
            status = 'api_engine_stream_ok'
            apiEngine = [pscustomobject]@{
                text = $validText
                visibleSeen = $true
            }
        }
    )
}

$evidence = Assert-DeviceSmokeMnnChatTextFragments -Json $valid -ExpectedFragments @($han, $emoji)
Assert-True -Condition ($evidence.generationText -ceq $validText) -Message 'Unicode evidence must preserve the exact generated text.'
Assert-True -Condition ($evidence.generationUtf8Hex.Contains('F09F9880')) -Message 'UTF-8 evidence must encode U+1F600 as F0 9F 98 80.'
Assert-True -Condition $evidence.apiTextContainsAll -Message 'API evidence should report the same fragments when present.'

$missingEmoji = [pscustomobject]@{
    status = 'completed'
    events = @(
        [pscustomobject]@{
            status = 'generation_ok'
            generation = [pscustomobject]@{ text = $han; textPreview = $han }
        }
    )
}
Assert-Throws `
    -Action { Assert-DeviceSmokeMnnChatTextFragments -Json $missingEmoji -ExpectedFragments @($emoji) } `
    -ExpectedFragment 'missing expected output fragment' `
    -Message 'Unicode contract must reject output that lost the supplementary character.'

$mojibake = ([string][char]0x00F0) + ([string][char]0x0178) + ([string][char]0x02DC) + ([string][char]0x20AC)
$corrupted = [pscustomobject]@{
    status = 'completed'
    events = @(
        [pscustomobject]@{
            status = 'generation_ok'
            generation = [pscustomobject]@{ text = "$han$mojibake"; textPreview = "$han$mojibake" }
        }
    )
}
Assert-Throws `
    -Action { Assert-DeviceSmokeMnnChatTextFragments -Json $corrupted -ExpectedFragments @($emoji) } `
    -ExpectedFragment 'missing expected output fragment' `
    -Message 'Unicode contract must reject mojibake in place of the emoji.'

Write-Host 'PASS: MNN Unicode contract preserves Chinese plus supplementary UTF-8 and rejects corruption.'
