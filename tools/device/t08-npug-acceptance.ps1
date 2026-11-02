# Push and run the NPUG/AOT Local API acceptance script, then pull its evidence.
# Does not install or build the APK; the v4 bundle must already be installed and imported.
param(
    [Parameter(Mandatory = $true)][string]$ModelId,
    [int]$Runs = 2,
    [string]$Serial = '',
    [string]$Adb = 'D:\model\android-sdk\platform-tools\adb.exe',
    [string]$OutDir = 'artifacts/device/npug-acceptance'
)
$ErrorActionPreference = 'Continue'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$script = Join-Path $repo 'tools\device\t08-npug-acceptance.sh'
if (-not (Test-Path -LiteralPath $Adb)) { throw "adb not found: $Adb" }
if (-not (Test-Path -LiteralPath $script)) { throw "script not found: $script" }
if ($ModelId -notmatch '^[A-Za-z0-9:_-]+$') { throw "ModelId contains unsupported characters: $ModelId" }
if ($Runs -lt 1 -or $Runs -gt 20) { throw "Runs must be within 1..20" }

$adbArgs = @()
if ($Serial) { $adbArgs = @('-s', $Serial) }
$online = & $Adb @adbArgs get-state 2>&1
if ($LASTEXITCODE -ne 0 -or ($online -join '').Trim() -ne 'device') {
    Write-Host "NOT_RUN: adb device is not online ($($online -join ' '))"
    exit 2
}

& $Adb @adbArgs push $script /data/local/tmp/t08-npug-acceptance.sh | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Host 'FAIL: script push failed'; exit 2 }

$output = & $Adb @adbArgs shell "sh /data/local/tmp/t08-npug-acceptance.sh '$ModelId' $Runs" 2>&1
$text = ($output -join "`n")
Write-Host $text

$resolvedOut = if ([IO.Path]::IsPathRooted($OutDir)) { $OutDir } else { Join-Path $repo $OutDir }
New-Item -ItemType Directory -Force -Path $resolvedOut | Out-Null
$text | Set-Content -LiteralPath (Join-Path $resolvedOut 'run.log') -Encoding utf8
& $Adb @adbArgs pull /data/local/tmp/mca-npug-accept $resolvedOut | Out-Null

if ($text -match 'NPUG_ACCEPTANCE_PASS') {
    Write-Host "PASS: evidence under $resolvedOut"
    exit 0
}
Write-Host 'FAIL: acceptance did not pass; keep run.log, gen/cancel JSON and logcat'
exit 1
