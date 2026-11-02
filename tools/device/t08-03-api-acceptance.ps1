#Requires -Version 5.1
<#
T08 authenticated Local API acceptance (checklist A1-A6).

The API is reached over the device's own loopback, so requests run inside
`adb shell` on the device (curl). The bearer key is materialised on-device from
the app's own preference file at run time; it is never printed and never written
to any evidence file.

Creates the Local API precondition by launching the real MainActivity, which
auto-recovers the persisted `api_enabled` preference in startAfterConstruction.
#>
[CmdletBinding()]
param(
    [string]$Adb = 'D:\model\android-sdk\platform-tools\adb.exe',
    [string]$Serial = 'device-a',
    [string]$Package = 'com.muyuchat.mca',
    [int]$Port = 11435,
    [int]$ReadyTimeoutSeconds = 60,
    [int]$ChatTimeoutSeconds = 300,
    [string]$RepoRoot = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $RepoRoot) { $RepoRoot = (Get-Location).Path }
Import-Module (Join-Path $RepoRoot 'tools\device\T08Parity.psm1') -Force

$runDir = New-T08RunDirectory -Area 'device' -Name 'api-acceptance' -RepoRoot $RepoRoot
$ledger = [System.Collections.ArrayList]::new()
$log = [Text.StringBuilder]::new()
function Log([string]$s) { [void]$log.AppendLine($s); Write-Host $s }

$pkg = Get-T08PackageState -Adb $Adb -Serial $Serial -Package $Package
$apkSha = 'unknown'
$p = Invoke-T08Adb -Adb $Adb -Serial $Serial -Arguments @('shell', 'pm', 'path', $Package)
$apkPath = ($p.stdout -replace 'package:', '').Trim()
if ($apkPath) {
    $s = Invoke-T08Adb -Adb $Adb -Serial $Serial -Arguments @('shell', 'sha256sum', $apkPath)
    $m = [regex]::Match($s.stdout, '([0-9a-fA-F]{64})')
    if ($m.Success) { $apkSha = $m.Groups[1].Value.ToLowerInvariant() }
}
$meta = @{
    runName   = 'api-acceptance'
    area      = 'device'
    serial    = $Serial
    package   = $Package
    version   = "$($pkg.versionName) ($($pkg.versionCode))"
    apkSha256 = $apkSha
    port      = $Port
    runDir    = $runDir
    note      = 'Bearer key read on-device at run time; never printed or stored in evidence.'
}

function Invoke-T08DeviceScript {
    <#
      Runs a plain-ASCII shell script as the app uid. `run-as` has no /sdcard read
      access on this device, so the script is piped over stdin instead of pushed.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Script,
        [int]$TimeoutSeconds = 300
    )
    $tmp = Join-Path $runDir ('script-' + [guid]::NewGuid().ToString('n').Substring(0, 8) + '.sh')
    Write-T08Utf8 -Path $tmp -Content $Script
    $outFile = Join-Path $runDir ('output-' + [guid]::NewGuid().ToString('n').Substring(0, 8) + '.txt')
    $psi = [Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = 'cmd.exe'
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.RedirectStandardInput = $true
    $psi.Arguments = "/c `"`"$Adb`" -s $Serial shell run-as $Package sh < `"$tmp`"`""
    $proc = [Diagnostics.Process]::new()
    $proc.StartInfo = $psi
    $proc.Start() | Out-Null
    $so = $proc.StandardOutput.ReadToEndAsync()
    $se = $proc.StandardError.ReadToEndAsync()
    if (-not $proc.WaitForExit($TimeoutSeconds * 1000)) { try { $proc.Kill() } catch { } }
    $stdout = $so.GetAwaiter().GetResult()
    $stderr = $se.GetAwaiter().GetResult()
    $proc.Dispose()
    Remove-Item -Force -ErrorAction SilentlyContinue $tmp
    Write-T08Utf8 -Path $outFile -Content ("STDOUT:`n$stdout`nSTDERR:`n$stderr")
    $code = $null
    $m = [regex]::Match($stdout, '__HTTP_CODE__(\d+)__')
    if ($m.Success) { $code = [int]$m.Groups[1].Value }
    return [pscustomobject]@{
        httpCode = $code
        body     = ($stdout -replace '__HTTP_CODE__\d+__', '')
        stdout   = $stdout
        stderr   = $stderr
        evidence = $outFile
    }
}

function New-T08CurlScript {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [string]$Body = '',
        [int]$TimeoutSeconds = 180,
        [switch]$Stream,
        [int]$MaxTimeSeconds = 0
    )
    $sb = [Text.StringBuilder]::new()
    [void]$sb.AppendLine('KEY=$(sed -n ''s/.*name="api_key">\([^<]*\)<.*/\1/p'' shared_prefs/mca_api.xml)')
    [void]$sb.AppendLine('[ -n "$KEY" ] || { echo "NO_KEY"; exit 9; }')
    $maxtime = if ($MaxTimeSeconds -gt 0) { $MaxTimeSeconds } else { $TimeoutSeconds }
    $curl = "curl -s -S -m $maxtime -X $Method -H `"Authorization: Bearer `$KEY`" -H `"Content-Type: application/json`""
    if ($Stream) { $curl += ' -N' }
    if ($Body) {
        $enc = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Body))
        [void]$sb.AppendLine("BODY=`$(printf %s $enc | base64 -d)")
        $curl += ' -d "$BODY"'
    }
    [void]$sb.AppendLine("$curl -w `"`n__HTTP_CODE__%{http_code}__`" `"http://127.0.0.1:$Port$Path`"")
    return $sb.ToString()
}

# --- A0.1 production entry --------------------------------------------------
$launch = Invoke-T08Adb -Adb $Adb -Serial $Serial -Arguments @('shell', 'am', 'start', '-W', '-n', "$Package/.MainActivity")
$top = Invoke-T08Adb -Adb $Adb -Serial $Serial -Arguments @('shell', 'dumpsys', 'activity', 'activities')
$topLine = (($top.stdout -split "`n") | Where-Object { $_ -match 'topResumedActivity' } | Select-Object -First 1)
$isMain = ($topLine -match [regex]::Escape("$Package/.MainActivity"))
Write-T08Utf8 -Path (Join-Path $runDir 'launch.txt') -Content ($launch.stdout + "`n" + $topLine)
Add-T08Step -Ledger $ledger -Id 'A0.1' -Checklist 'production-entry' -Description 'production MainActivity is the resumed activity' `
    -Status $(if ($isMain) { 'PASS' } else { 'FAIL' }) -Command 'adb shell am start -W -n <pkg>/.MainActivity' -ExitCode 0 `
    -Evidence 'launch.txt' -Notes (($topLine -replace '\s+', ' '))
Log "launch exit=$($launch.exitCode) mainActivity=$isMain"

# --- A0.2 listener ----------------------------------------------------------
$listening = $false
$deadline = (Get-Date).AddSeconds($ReadyTimeoutSeconds)
$probeLog = [Text.StringBuilder]::new()
while ((Get-Date) -lt $deadline) {
    $probe = Invoke-T08Adb -Adb $Adb -Serial $Serial -Arguments @('shell', 'curl', '-s', '-o', '/dev/null', '-w', '%{http_code}', "http://127.0.0.1:$Port/v1/models")
    $c = $probe.stdout.Trim()
    [void]$probeLog.AppendLine("$([DateTime]::UtcNow.ToString('o')) http=$c")
    if ($c -match '^(200|401|403)$') { $listening = $true; break }
    Start-Sleep -Seconds 2
}
Write-T08Utf8 -Path (Join-Path $runDir 'listener-probe.txt') -Content $probeLog.ToString()
Add-T08Step -Ledger $ledger -Id 'A0.2' -Checklist 'production-entry' -Description "Local API loopback listening on $Port" `
    -Status $(if ($listening) { 'PASS' } else { 'FAIL' }) `
    -Command "adb shell curl -s -o /dev/null -w %{http_code} http://127.0.0.1:$Port/v1/models" `
    -ExitCode $(if ($listening) { 0 } else { 1 }) -Evidence 'listener-probe.txt' -Notes "last=$c"
Log "listener=$listening"

if (-not $listening) {
    Write-T08Ledger -Ledger $ledger -Directory $runDir -Meta $meta
    Write-T08Utf8 -Path (Join-Path $runDir 'run.log') -Content $log.ToString()
    Write-Host "ABORT: Local API listener never became reachable"
    exit 1
}

# --- A6 auth boundary -------------------------------------------------------
$noAuth = Invoke-T08Adb -Adb $Adb -Serial $Serial -Arguments @('shell', 'curl', '-s', '-o', '/dev/null', '-w', '%{http_code}', "http://127.0.0.1:$Port/v1/models")
$c1 = $noAuth.stdout.Trim()
$c2 = (Invoke-T08Adb -Adb $Adb -Serial $Serial -Arguments @('shell', 'curl', '-s', '-o', '/dev/null', '-w', '%{http_code}', '-H', 'Authorization: Bearer mca-invalid-token', "http://127.0.0.1:$Port/v1/models")).stdout.Trim()
Add-T08Step -Ledger $ledger -Id 'A6.1' -Checklist 'A6' -Description 'no bearer token -> rejected' `
    -Status $(if ($c1 -in @('401', '403')) { 'PASS' } else { 'FAIL' }) -Command 'curl (no auth) /v1/models' `
    -ExitCode $(if ($c1 -in @('401', '403')) { 0 } else { 1 }) -Notes "http=$c1"
Add-T08Step -Ledger $ledger -Id 'A6.2' -Checklist 'A6' -Description 'wrong bearer token -> rejected' `
    -Status $(if ($c2 -in @('401', '403')) { 'PASS' } else { 'FAIL' }) -Command 'curl -H Bearer mca-invalid-token /v1/models' `
    -ExitCode $(if ($c2 -in @('401', '403')) { 0 } else { 1 }) -Notes "http=$c2"
Log "auth boundary: no-token=$c1 wrong-token=$c2"

# --- A0.3 / G3 model catalog -----------------------------------------------
$models = Invoke-T08DeviceScript -Script (New-T08CurlScript -Method 'GET' -Path '/v1/models' -TimeoutSeconds 60) -TimeoutSeconds 120
Write-T08Utf8 -Path (Join-Path $runDir 'models.json') -Content $models.body
$modelsOk = ($models.httpCode -eq 200) -and ($models.body -match '"data"')
Add-T08Step -Ledger $ledger -Id 'A0.3' -Checklist 'G3' -Description 'authenticated GET /v1/models returns the catalog' `
    -Status $(if ($modelsOk) { 'PASS' } else { 'FAIL' }) -Command 'curl -H Bearer .../v1/models' `
    -ExitCode $(if ($modelsOk) { 0 } else { 1 }) -Evidence 'models.json' -Notes "http=$($models.httpCode) bytes=$($models.body.Length)"
Log "models: http=$($models.httpCode) bytes=$($models.body.Length)"

Write-T08Ledger -Ledger $ledger -Directory $runDir -Meta $meta
Write-T08Utf8 -Path (Join-Path $runDir 'run.log') -Content $log.ToString()
Write-Host "api-acceptance run dir: $runDir"
Write-Host ("steps: " + (($ledger | ForEach-Object { "$($_.id)=$($_.status)" }) -join ' '))
$fails = @($ledger | Where-Object { $_.status -eq 'FAIL' }).Count
if ($fails -gt 0) { exit 1 } else { exit 0 }
