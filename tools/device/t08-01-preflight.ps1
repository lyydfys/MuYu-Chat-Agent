#Requires -Version 5.1
<#
T08 step 1 - preflight and baseline capture. READ ONLY with respect to the device.

Confirms adb/device state, records device facts, snapshots the installed package,
and captures a byte-verified copy of the user data that later steps must prove was
preserved (never uninstalls the production app, never clears data).

Outputs under artifacts/repair/20261009-parity/device/<stamp>-preflight/.
#>
[CmdletBinding()]
param(
    [string]$Adb = 'D:\model\android-sdk\platform-tools\adb.exe',
    [string]$Serial = 'device-a',
    [string]$Package = 'com.muyuchat.mca',
    [string]$RepoRoot = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not $RepoRoot) { $RepoRoot = (Get-Location).Path }
Import-Module (Join-Path $RepoRoot 'tools\device\T08Parity.psm1') -Force

$runDir = New-T08RunDirectory -Area 'device' -Name 'preflight' -RepoRoot $RepoRoot
$ledger = [System.Collections.ArrayList]::new()

$deviceLine = Assert-T08Device -Adb $Adb -Serial $Serial
$facts = Get-T08DeviceFacts -Adb $Adb -Serial $Serial
Write-T08Json -Path (Join-Path $runDir 'device-facts.json') -Value $facts
Add-T08Step -Ledger $ledger -Id 'S0.1' -Checklist 'preflight' -Description 'adb sees the device in device state' `
    -Status 'PASS' -Command "adb -s $Serial devices -l" -ExitCode 0 -Evidence 'device-facts.json' -Notes $deviceLine

$pkgBefore = Get-T08PackageState -Adb $Adb -Serial $Serial -Package $Package
Write-T08Json -Path (Join-Path $runDir 'package-before.json') -Value $pkgBefore
if (-not $pkgBefore.installed) {
    Add-T08Step -Ledger $ledger -Id 'S0.2' -Checklist 'preflight' -Description 'production package installed' `
        -Status 'FAIL' -Command "adb -s $Serial shell pm list packages $Package" -ExitCode 1 -Notes 'package not installed'
}
else {
    Add-T08Step -Ledger $ledger -Id 'S0.2' -Checklist 'preflight' -Description 'production package installed' `
        -Status 'PASS' -Command "adb -s $Serial shell dumpsys package $Package" -ExitCode 0 `
        -Evidence 'package-before.json' -Notes "versionName=$($pkgBefore.versionName) versionCode=$($pkgBefore.versionCode)"
}

# --- user data baseline (byte-verified) -------------------------------------
$dataDir = Join-Path $runDir 'data-baseline'
New-Item -ItemType Directory -Force -Path $dataDir | Out-Null

$dbPulls = @()
foreach ($rel in @('databases/mca.db', 'databases/mca-runtime.db')) {
    $p = Receive-T08RemoteFile -Adb $Adb -Serial $Serial -RemotePath $rel `
        -LocalDirectory $dataDir -LocalName (Split-Path -Leaf $rel) -RunAsPackage $Package
    $dbPulls += $p
}
foreach ($rel in @('files/mca-models.json')) {
    $p = Receive-T08RemoteFile -Adb $Adb -Serial $Serial -RemotePath $rel `
        -LocalDirectory $dataDir -LocalName (Split-Path -Leaf $rel) -RunAsPackage $Package
    $dbPulls += $p
}
Write-T08Json -Path (Join-Path $runDir 'data-baseline-pulls.json') -Value $dbPulls

$unverified = @($dbPulls | Where-Object { -not $_.verified })
if ($unverified.Count -gt 0) {
    Add-T08Step -Ledger $ledger -Id 'S0.3' -Checklist 'B1/B2/B6' -Description 'user-data baseline copied byte-exact' `
        -Status 'FAIL' -Command 'adb shell run-as <pkg> base64 <file>' -ExitCode 1 -Evidence 'data-baseline-pulls.json' `
        -Notes ("hash mismatch or read failure for: " + (($unverified | ForEach-Object { $_.remotePath }) -join ', '))
}
else {
    Add-T08Step -Ledger $ledger -Id 'S0.3' -Checklist 'B1/B2/B6' -Description 'user-data baseline copied byte-exact' `
        -Status 'PASS' -Command 'adb shell run-as <pkg> base64 <file>' -ExitCode 0 -Evidence 'data-baseline-pulls.json' `
        -Notes (($dbPulls | ForEach-Object { "$($_.remotePath)=$($_.localSha256.Substring(0,12))" }) -join ' ')
}

# --- model inventory --------------------------------------------------------
$inventory = [ordered]@{}
foreach ($entry in @(
        @{ key = 'gguf'; path = 'models/3a23bad3e30ac59423384623cbb024f04e404a2dd00171657010811002274e14' },
        @{ key = 'sdcpp'; path = 'image_models/bundle-8a993f0003c3baa15349e476f0017150f9a3490cc518c32cab2dc5d28dc0d826' },
        @{ key = 'qwen'; path = 'image_models/bundle-c0fb8811b72ebf287140954138ec7e841d98498197d2d8de1756b8ebff5f89e6' }
    )) {
    $remote = "/sdcard/Android/data/$Package/files/$($entry.path)"
    $r = Invoke-T08Adb -Adb $Adb -Serial $Serial -Arguments @('shell', 'ls', '-la', $remote)
    $inventory[$entry.key] = [ordered]@{
        remotePath = $remote
        exitCode   = $r.exitCode
        listing    = $r.stdout
    }
}
Write-T08Json -Path (Join-Path $runDir 'model-inventory.json') -Value $inventory
$present = @($inventory.Keys | Where-Object { $inventory[$_].exitCode -eq 0 })
$modelsStatus = 'FAIL'
if ($present.Count -gt 0) { $modelsStatus = 'PASS' }
Add-T08Step -Ledger $ledger -Id 'S0.4' -Checklist 'preflight' -Description 'real models present on device' `
    -Status $modelsStatus -Command 'adb shell ls -la <bundle>' -ExitCode 0 `
    -Evidence 'model-inventory.json' -Notes ("present=" + ($present -join ','))

# --- capability matrix (which checklist rows are even reachable) ------------
$caps = [ordered]@{
    gguf_chat_vision = ($present -contains 'gguf')
    sdcpp_image      = ($present -contains 'sdcpp')
    qwen_image21     = ($present -contains 'qwen')
    mnn_llm          = $false   # no MNN-LLM model installed
    sd15_sana        = $false   # no SD1.5/Sana bundle installed
    qnn_image        = $false   # no QNN model bundle installed
    note             = 'Absent families are NOT_VERIFIED in the ledger, never PASS.'
}
Write-T08Json -Path (Join-Path $runDir 'capability-matrix.json') -Value $caps

$writeLedger = $true
$meta = @{
    runName    = 'preflight'
    area       = 'device'
    serial     = $Serial
    package    = $Package
    deviceModel = $facts.model
    abi        = $facts.abi
    android    = $facts.release
    apkSha256  = 'n/a (pre-install)'
    runDir     = $runDir
    note       = 'Read-only device access. No uninstall, no data clear.'
}
Write-T08Ledger -Ledger $ledger -Directory $runDir -Meta $meta

Write-Host "preflight run dir: $runDir"
Write-Host ("device: {0} / {1} / android {2}" -f $facts.model, $facts.abi, $facts.release)
Write-Host ("package: versionName={0} versionCode={1}" -f $pkgBefore.versionName, $pkgBefore.versionCode)
Write-Host ("capabilities: " + (($caps.Keys | Where-Object { $_ -ne 'note' } | ForEach-Object { "$_=$($caps[$_])" }) -join ' '))
Write-Host ("steps: " + (($ledger | ForEach-Object { "$($_.id)=$($_.status)" }) -join ' '))
if (@($ledger | Where-Object { $_.status -eq 'FAIL' }).Count -gt 0) { exit 1 } else { exit 0 }
