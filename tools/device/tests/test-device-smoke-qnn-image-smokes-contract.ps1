param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$deviceDir = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Import-Module (Join-Path $deviceDir 'DeviceSmoke.psm1') -Force

function Assert-Equal {
    param($Actual, $Expected, [string]$Message)
    if ($Actual -ne $Expected) {
        throw "$Message (expected=$Expected actual=$Actual)"
    }
}

function Select-QnnSmokeSpecs {
    param([object]$Manifest)

    $module = Get-Module DeviceSmoke
    return @(& $module {
        param($innerManifest)
        Get-DeviceSmokeQnnImageSmokeSpecs -Manifest $innerManifest -ManifestPath '/bundle/manifest.json'
    } $Manifest)
}

$manifest = @'
{
  "smoke": {
    "width": 512,
    "height": 512,
    "steps": 4,
    "prompt": "legacy semantic smoke only"
  },
  "smokes": [
    {
      "graphName": "model",
      "contextBinary": "unet.bin",
      "inputs": [{"name":"sample","dataType":"uint16","shape":[1,4,64,64]}],
      "outputs": [{"name":"output","dataType":"uint16","shape":[1,4,64,64]}]
    },
    {
      "graphName": "model",
      "contextBinary": "vae_decoder.bin",
      "inputs": [{"name":"input","dataType":"uint16","shape":[1,4,64,64]}],
      "outputs": [{"name":"output","dataType":"uint16","shape":[1,3,512,512]}]
    }
  ]
}
'@ | ConvertFrom-Json

[object[]]$specs = @(Select-QnnSmokeSpecs -Manifest $manifest)
Assert-Equal -Actual $specs.Count -Expected 2 -Message 'smokes[] must override a graph-incomplete legacy smoke object'
Assert-Equal -Actual $specs[0].contextBinary -Expected 'unet.bin' -Message 'UNet graph must be selected first'
Assert-Equal -Actual $specs[1].contextBinary -Expected 'vae_decoder.bin' -Message 'VAE graph must remain in the smoke suite'

$legacy = [pscustomobject]@{ smoke = $manifest.smoke }
[object[]]$legacySpecs = @(Select-QnnSmokeSpecs -Manifest $legacy)
Assert-Equal -Actual $legacySpecs.Count -Expected 1 -Message 'Legacy graph smoke remains supported when no suite exists'

Write-Host 'PASS: QNN image smoke harness prefers smokes[] and preserves legacy fallback.'
