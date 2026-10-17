<#
T08 parity matrix runner - ASCII-only source by design.

WHY ASCII-ONLY: this PowerShell host decodes a UTF-8 script file as GBK, so any
non-ASCII string literal corrupts the tokenizer and yields parse errors far from the
real line. All human-readable non-ASCII text is built at run time from code points via
ConvertFrom-T08UnicodeEscapes.

WHAT IT DOES: runs the SHORT device matrix against the currently installed APK and
writes a per-run verdict table, so a rebuilt APK can be re-verified quickly.
Measured durations on this device: chat ~15 s | SSE ~10 s | cancel ~30 s | image ~275 s.

BINDING: each run records APK sha256 + serial. Source-freeze hashes stay separate from
runtime binding.

Usage:
  powershell -NoProfile -ExecutionPolicy Bypass -File tools/device/t08-02-matrix.ps1 `
      -ApkSha256 <sha> -Label <name> [-SkipChat] [-SkipSse] [-SkipImage] [-SkipCancel]

Exit codes: 0 = no FAIL, 1 = at least one FAIL, 2 = aborted precondition.
#>
[CmdletBinding()]
param(
    [string]$Adb = 'D:\model\android-sdk\platform-tools\adb.exe',
    [string]$Serial = 'f9d98aa3',
    [string]$Package = 'com.muyuchat.mca',
    [int]$Port = 11435,
    [string]$ApkSha256 = '',
    [string]$Label = '',
    [switch]$SkipChat,
    [switch]$SkipSse,
    [switch]$SkipImage,
    [switch]$SkipCancel,
    [switch]$SkipUiModelLoad,
    [int]$ImageRuns = 3,
    [string]$RepoRoot = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $RepoRoot) { $RepoRoot = (Get-Location).Path }
Import-Module (Join-Path $RepoRoot 'tools\device\T08Parity.psm1') -Force
Import-Module (Join-Path $RepoRoot 'tools\device\T08Matrix.psm1') -Force

# Boolean -> verdict helpers. Kept as functions (not a PowerShell class) because a class
# exported from an imported module is not resolvable as a type literal at a script's parse
# time: Windows PowerShell 5.1 reports "Unable to find type [T08Pick]".
function Get-T08Status([bool]$condition) {
    if ($condition) { return 'PASS' }
    return 'FAIL'
}
function Get-T08ExitCode([bool]$condition) {
    if ($condition) { return 0 }
    return 1
}

function ConvertFrom-T08UnicodeEscapes([string]$text) {
    $sb = [Text.StringBuilder]::new()
    $i = 0
    while ($i -lt $text.Length) {
        $isStart = $false
        if ($text[$i] -eq '[' -and ($i + 1) -lt $text.Length -and $text[$i + 1] -eq '\') { $isStart = $true }
        if ($isStart) {
            $end = $text.IndexOf(']', $i)
            if ($end -gt $i) {
                $parts = $text.Substring($i + 2, $end - $i - 2) -split '\\u'
                foreach ($p in $parts) {
                    if ($p.Length -eq 4) { [void]$sb.Append([char][Convert]::ToInt32($p, 16)) }
                }
                $i = $end + 1
                continue
            }
        }
        [void]$sb.Append($text[$i])
        $i = $i + 1
    }
    return $sb.ToString()
}

$chatPrompt = ConvertFrom-T08UnicodeEscapes '[\u7528\u4e09\u53e5\u8bdd\u8bf4\u660e\u4f60\u662f\u8c01\u3002]'

$runLabel = 'matrix'
if ($Label) { $runLabel = 'matrix-' + $Label }
$runDir = New-T08RunDirectory -Area 'device' -Name $runLabel -RepoRoot $RepoRoot
$ledger = [System.Collections.ArrayList]::new()
$log = [Text.StringBuilder]::new()
function Log([string]$s) { [void]$log.AppendLine($s); Write-Host $s }
$apiUrl = 'http://127.0.0.1:' + $Port

# --- binding ---------------------------------------------------------------
$pkg = Get-T08PackageState -Adb $Adb -Serial $Serial -Package $Package
if (-not $pkg.installed) { Write-Host 'ABORT: package not installed'; exit 2 }
$installedSha = Get-T08InstalledApkSha -Adb $Adb -Serial $Serial -Package $Package
$apkMatch = $true
if ($ApkSha256) { if ($ApkSha256 -ne $installedSha) { $apkMatch = $false } }
$meta = [ordered]@{
    runName           = $runLabel
    area              = 'device'
    serial            = $Serial
    package           = $Package
    installedVersion  = $pkg.versionName + ' (' + $pkg.versionCode + ')'
    apkSha256         = $installedSha
    expectedApkSha256 = $ApkSha256
    apkHashMatch      = $apkMatch
    chatPrompt        = $chatPrompt
    runDir            = $runDir
    startedUtc        = Get-T08UtcStamp
    bindingNote       = 'Runtime binding = APK sha256 + serial + in-APK lib hashes when supplied.'
}
Write-T08Json -Path (Join-Path $runDir 'binding.json') -Value $meta
Log ('installed base.apk sha256 = ' + $installedSha)
Log ('expected match           = ' + $apkMatch)

# --- preconditions ---------------------------------------------------------
$launch = & $Adb -s $Serial shell am start -W -n ($Package + '/.MainActivity') 2>&1
Start-Sleep -Seconds 4
$fg = Get-T08Foreground -Adb $Adb -Serial $Serial
$isMain = $false
if ($fg -match [regex]::Escape($Package + '/.MainActivity')) { $isMain = $true }
Write-T08Utf8 -Path (Join-Path $runDir 'launch.txt') -Content (($launch -join "`n") + "`n" + $fg)
Add-T08Step -Ledger $ledger -Id 'P0.1' -Checklist 'production-entry' `
    -Description 'production MainActivity is the resumed activity' -Status (Get-T08Status ($isMain)) `
    -Command 'adb shell am start -W -n <pkg>/.MainActivity' -ExitCode (Get-T08ExitCode ($isMain)) `
    -Evidence 'launch.txt' -Notes $fg

$listening = $false
$probeLog = [Text.StringBuilder]::new()
$deadline = (Get-Date).AddSeconds(45)
while ((Get-Date) -lt $deadline) {
    $pr = & $Adb -s $Serial shell curl -s -o /dev/null -w '%{http_code}' ($apiUrl + '/v1/models') 2>&1
    $c = (($pr -join '')).Trim()
    [void]$probeLog.AppendLine((Get-T08UtcStamp) + ' http=' + $c)
    if ($c -match '^(200|401|403)$') { $listening = $true; break }
    Start-Sleep -Seconds 2
}
Write-T08Utf8 -Path (Join-Path $runDir 'listener-probe.txt') -Content $probeLog.ToString()
Add-T08Step -Ledger $ledger -Id 'P0.2' -Checklist 'production-entry' `
    -Description ('Local API loopback listening on ' + $Port) -Status (Get-T08Status ($listening)) `
    -Command ('curl ' + $apiUrl + '/v1/models') -ExitCode (Get-T08ExitCode ($listening)) `
    -Evidence 'listener-probe.txt'
Log ('preconditions: mainActivity=' + $isMain + ' listener=' + $listening)
if (-not ($isMain -and $listening)) {
    Write-T08Ledger -Ledger $ledger -Directory $runDir -Meta $meta
    Write-T08Utf8 -Path (Join-Path $runDir 'run.log') -Content $log.ToString()
    Write-Host 'ABORT: preconditions failed'
    exit 2
}

# --- A6 auth boundary ------------------------------------------------------
function Get-HttpCode([string]$path) {
    $o = & $Adb -s $Serial shell curl -s -o /dev/null -w '%{http_code}' ($apiUrl + $path) 2>&1
    return ((($o -join '')).Trim())
}
$cModels = Get-HttpCode '/v1/models'
$cChat = Get-HttpCode '/v1/chat/completions'
$cMetrics = Get-HttpCode '/metrics'
Add-T08Step -Ledger $ledger -Id 'A6.1' -Checklist 'A6' `
    -Description '/v1/models without bearer (known public whitelist; DEFER-1)' `
    -Status (Get-T08Status ($cModels -eq '200')) -Command 'curl (no auth) /v1/models' -ExitCode 0 `
    -Notes ('http=' + $cModels + ' FAIL = deferred contract defect still present, not a regression')
Add-T08Step -Ledger $ledger -Id 'A6.2' -Checklist 'A6' `
    -Description '/v1/chat/completions without bearer must be 401' `
    -Status (Get-T08Status ($cChat -eq '401')) -Command 'curl (no auth) /v1/chat/completions' -ExitCode 0 `
    -Notes ('http=' + $cChat)
Add-T08Step -Ledger $ledger -Id 'A6.3' -Checklist 'A6' `
    -Description '/metrics without bearer must be 401' `
    -Status (Get-T08Status ($cMetrics -eq '401')) -Command 'curl (no auth) /metrics' -ExitCode 0 `
    -Notes ('http=' + $cMetrics)
Write-T08Utf8 -Path (Join-Path $runDir 'auth-matrix.txt') -Content (
    'models_no_auth=' + $cModels + "`n" + 'chat_no_auth=' + $cChat + "`n" + 'metrics_no_auth=' + $cMetrics + "`n")
Log ('A6: models=' + $cModels + ' chat=' + $cChat + ' metrics=' + $cMetrics)

# --- chat model precondition (production UI load) ---------------------------
# The authenticated API only serves the model the real MainActivity has loaded
# (409 model_mismatch otherwise). A reinstall does not preserve the loaded runtime, so the
# model is loaded through the production UI here, by coordinate taps only (never BACK: on this
# device BACK exits MCA to another app). Foreground is re-verified before every tap.
if ((-not ($SkipChat -and $SkipSse)) -and (-not $SkipUiModelLoad)) {
    function Invoke-UiTap([int]$x, [int]$y, [string]$why) {
        $fgNow = Get-T08Foreground -Adb $Adb -Serial $Serial
        if ($fgNow -notmatch [regex]::Escape($Package + '/.MainActivity')) {
            Log ('  UI tap skipped (' + $why + '): foreground is not MainActivity -> ' + $fgNow)
            return $false
        }
        & $Adb -s $Serial shell input tap $x $y | Out-Null
        return $true
    }
    function Invoke-NativeTolerant {
        <#
          Runs a native command with $ErrorActionPreference='Continue' for the duration.
          Windows PowerShell 5.1 raises a NativeCommandError for ANY stderr output while the
          preference is 'Stop' (adb prints pull/dump progress to stderr), which otherwise aborts
          the whole run even though the command succeeded.
        #>
        param([Parameter(Mandatory = $true)][scriptblock]$Block)
        $saved = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try { & $Block } finally { $ErrorActionPreference = $saved }
    }
    function Get-UiDump([string]$name) {
        Invoke-NativeTolerant { & $Adb -s $Serial shell uiautomator dump --compressed /sdcard/t08ui.xml 2>$null | Out-Null }
        $local = Join-Path $runDir $name
        Invoke-NativeTolerant { & $Adb -s $Serial pull /sdcard/t08ui.xml $local 2>$null | Out-Null }
        if (-not (Test-Path $local)) { return '' }
        return [Text.Encoding]::UTF8.GetString([IO.File]::ReadAllBytes($local))
    }
    $loadedText = (ConvertFrom-T08UnicodeEscapes '[\u5df2\u52a0\u8f7d]') + '|' + (ConvertFrom-T08UnicodeEscapes '[\u5378\u8f7d]')
    $loaded = $false
    $dump = Get-UiDump 'ui-precheck.xml'
    if ($dump -match $loadedText) { $loaded = $true }
    if (-not $loaded) {
        Log '  chat model not loaded -> driving production UI to load it'
        [void](Invoke-UiTap 607 305 'open model sheet')
        Start-Sleep -Seconds 2
        [void](Invoke-UiTap 655 570 'expand local inference')
        Start-Sleep -Seconds 2
        $picker = Get-UiDump 'ui-picker.xml'
        $m = [regex]::Match($picker, 'text="Huihui-Qwen[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if (-not $m.Success) {
            # the compiled layout may place bounds before text; try the reverse order
            $m = [regex]::Match($picker, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*text="Huihui-Qwen[^"]*"')
        }
        if ($m.Success) {
            $cx = [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2)
            $cy = [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
            Log ('  tapping model row at ' + $cx + ',' + $cy)
            [void](Invoke-UiTap $cx $cy 'select GGUF model')
            for ($w = 1; $w -le 12; $w++) {
                Start-Sleep -Seconds 5
                $d2 = Get-UiDump ('ui-load-' + $w + '.xml')
                if ($d2 -match ((ConvertFrom-T08UnicodeEscapes '[\u5df2\u52a0\u8f7d]') + '|' + (ConvertFrom-T08UnicodeEscapes '[\u5378\u8f7d]'))) { $loaded = $true; Log ('  model loaded after ' + ($w * 5) + 's'); break }
            }
        }
        else { Log '  could not locate the GGUF row in the picker dump' }
    }
    Add-T08Step -Ledger $ledger -Id 'P0.3' -Checklist 'production-entry' `
        -Description 'chat model loaded through the production UI (API requires the MainActivity model)' `
        -Status (Get-T08Status $loaded) -Command 'adb shell input tap (production UI, coordinate only)' `
        -ExitCode (Get-T08ExitCode $loaded) -Evidence 'ui-picker.xml' `
        -Notes ('loaded=' + $loaded + ' (409 model_mismatch otherwise)')
    Log ('chat model loaded = ' + $loaded)

    # Probe with max_tokens=1 so a wrong precondition fails cheaply instead of burning the matrix.
    $probe = @{ model = 'Huihui-Qwen3.5-2B-abliterated.Q4_K_M'
        messages = @(@{ role = 'user'; content = 'hi' }); max_tokens = 1; stream = $false } |
        ConvertTo-Json -Compress -Depth 6
    $probeScript = New-T08CurlScript -Method 'POST' -Url ($apiUrl + '/v1/chat/completions') `
        -JsonBody $probe -MaxTimeSeconds 600 -OutFile 'probe.json'
    $pr = Invoke-T08DeviceScript -Adb $Adb -Serial $Serial -Package $Package -Script $probeScript `
        -WorkDirectory $runDir -TimeoutSeconds 660
    $probeOk = ($pr.httpCode -eq 200)
    Write-T08Utf8 -Path (Join-Path $runDir 'probe.json') -Content $pr.stdout
    Add-T08Step -Ledger $ledger -Id 'P0.4' -Checklist 'production-entry' `
        -Description 'max_tokens=1 probe confirms the engine serves the loaded model' `
        -Status (Get-T08Status $probeOk) -Command 'POST /v1/chat/completions max_tokens=1' `
        -ExitCode (Get-T08ExitCode $probeOk) -Evidence 'probe.json' `
        -Notes ('http=' + $pr.httpCode + ' ' + $pr.stdout.Substring(0, [Math]::Min(160, $pr.stdout.Length)))
    Log ('probe: http=' + $pr.httpCode + ' ' + $pr.stdout.Substring(0, [Math]::Min(160, $pr.stdout.Length)))
}

# --- chat ------------------------------------------------------------------
if (-not $SkipChat) {
    $body = @{
        model = 'Huihui-Qwen3.5-2B-abliterated.Q4_K_M'
        messages = @(@{ role = 'user'; content = $chatPrompt })
        max_tokens = 64; stream = $false; temperature = 0.6; top_p = 0.95; top_k = 20
    } | ConvertTo-Json -Compress -Depth 6
    $script = New-T08CurlScript -Method 'POST' -Url ($apiUrl + '/v1/chat/completions') `
        -JsonBody $body -MaxTimeSeconds 900 -OutFile 'chat.json'
    $t0 = Get-Date
    $r = Invoke-T08DeviceScript -Adb $Adb -Serial $Serial -Package $Package -Script $script `
        -WorkDirectory $runDir -TimeoutSeconds 960
    $el = [math]::Round(((Get-Date) - $t0).TotalSeconds, 1)
    Write-T08Utf8 -Path (Join-Path $runDir 'chat-stdout.txt') -Content $r.raw
    # Always read the response body back, not only on 200: on a failure that body IS the
    # diagnostic (the device-side curl always writes it to chat.json).
    $pulled = Receive-T08DeviceFile -Adb $Adb -Serial $Serial -Package $Package `
        -RemoteRelativePath 'chat.json' -LocalPath (Join-Path $runDir 'chat.json')
    $chatOk = ($r.httpCode -eq 200)
    $usage = ''
    $diag = ''
    if ($pulled.ok) {
        $jsonText = [Text.Encoding]::UTF8.GetString([IO.File]::ReadAllBytes((Join-Path $runDir 'chat.json')))
        $usage = [regex]::Match($jsonText, '"usage":\{[^}]*\}').Value
        $cm = [regex]::Match($jsonText, '"content":"((?:[^\\"]|\\.)*)"')
        if ($cm.Success) { Write-T08Utf8 -Path (Join-Path $runDir 'chat-text.txt') -Content $cm.Groups[1].Value }
        if ($jsonText -notmatch 'finish_reason') { $chatOk = $false }
        if (-not $chatOk) { $diag = $jsonText }
    }
    else {
        $chatOk = $false
        $diag = 'chat.json could not be read back: ' + $pulled.note
    }
    if (-not $chatOk) {
        Write-T08Utf8 -Path (Join-Path $runDir 'chat-failure.txt') -Content (
            'http=' + $r.httpCode + ' elapsed=' + $el + "s`n" + $diag + "`n--- stdout ---`n" + $r.stdout)
    }
    $diagShort = $diag
    if ($diagShort.Length -gt 160) { $diagShort = $diagShort.Substring(0, 160) }
    Add-T08Step -Ledger $ledger -Id 'C1.1' -Checklist 'C1/GGUF-chat' `
        -Description 'authenticated GGUF chat completion' -Status (Get-T08Status ($chatOk)) `
        -Command 'POST /v1/chat/completions' -ExitCode (Get-T08ExitCode ($chatOk)) `
        -Evidence 'chat.json' -Notes ('http=' + $r.httpCode + ' ' + $el + 's ' + $usage + ' ' + $diagShort)
    Log ('chat: http=' + $r.httpCode + ' ' + $el + 's ' + $usage + ' ' + $diagShort)
}

# --- SSE -------------------------------------------------------------------
if (-not $SkipSse) {
    $body = @{
        model = 'Huihui-Qwen3.5-2B-abliterated.Q4_K_M'
        messages = @(@{ role = 'user'; content = 'Count from 1 to 12, one number per line, nothing else.' })
        max_tokens = 48; stream = $true; temperature = 0.6
    } | ConvertTo-Json -Compress -Depth 6
    $script = New-T08SseScript -Url ($apiUrl + '/v1/chat/completions') -JsonBody $body `
        -MaxTimeSeconds 900 -RawOut 'stream-raw.txt'
    $r = Invoke-T08DeviceScript -Adb $Adb -Serial $Serial -Package $Package -Script $script `
        -WorkDirectory $runDir -TimeoutSeconds 960
    Write-T08Utf8 -Path (Join-Path $runDir 'sse-stats.txt') -Content $r.raw
    $done = [int]([regex]::Match($r.stdout, 'DONE=(\d+)').Groups[1].Value)
    $chunks = [int]([regex]::Match($r.stdout, 'CHUNKS=(\d+)').Groups[1].Value)
    $stop = [int]([regex]::Match($r.stdout, 'STOP=(\d+)').Groups[1].Value)
    $hb = [int]([regex]::Match($r.stdout, 'HEARTBEATS=(\d+)').Groups[1].Value)
    $sseOk = ($done -eq 1) -and ($chunks -ge 2) -and ($stop -eq 1)
    Add-T08Step -Ledger $ledger -Id 'A1.1' -Checklist 'A1/A2-SSE' `
        -Description 'SSE two or more chunks, exactly one DONE, exactly one stop frame' `
        -Status (Get-T08Status ($sseOk)) -Command 'POST /v1/chat/completions stream:true' `
        -ExitCode (Get-T08ExitCode ($sseOk)) -Evidence 'sse-stats.txt' `
        -Notes ('chunks=' + $chunks + ' done=' + $done + ' stop=' + $stop + ' heartbeats=' + $hb)
    Log ('sse: chunks=' + $chunks + ' done=' + $done + ' stop=' + $stop + ' heartbeats=' + $hb)
    $pulled = Receive-T08DeviceFile -Adb $Adb -Serial $Serial -Package $Package `
        -RemoteRelativePath 'stream-raw.txt' -LocalPath (Join-Path $runDir 'stream-raw.txt')
    Log ('stream-raw.txt bytes=' + $pulled.bytes)
}

# --- images ----------------------------------------------------------------
if (-not $SkipImage) {
    $i = 1
    while ($i -le $ImageRuns) {
        $seed = 42 + ($i - 1)
        $body = @{
            model = 'image:0fc35bfa-9cf3-4b18-955f-e05e8aee49b5'
            prompt = 'a single red ceramic cube on a plain white table, soft studio light (variant ' + $i + ')'
            sampler = 'euler_a'; steps = 4; cfg_scale = 1.0
            seed = $seed; size = '512x512'; n = 1; response_format = 'b64_json'
        } | ConvertTo-Json -Compress
        $script = New-T08CurlScript -Method 'POST' -Url ($apiUrl + '/v1/images/generations') `
            -JsonBody $body -MaxTimeSeconds 1200 -OutFile ('img' + $i + '.json')
        $t0 = Get-Date
        $r = Invoke-T08DeviceScript -Adb $Adb -Serial $Serial -Package $Package -Script $script `
            -WorkDirectory $runDir -TimeoutSeconds 1260
        $el = [math]::Round(((Get-Date) - $t0).TotalSeconds, 1)
        $pngOk = $false
        $pngSha = ''
        $dims = ''
        $imgDiag = ''
        if ($r.httpCode -eq 200) {
            $localJson = Join-Path $runDir ('img' + $i + '.json')
            $pulled = Receive-T08DeviceFile -Adb $Adb -Serial $Serial -Package $Package `
                -RemoteRelativePath ('img' + $i + '.json') -LocalPath $localJson
            if ($pulled.ok) {
                $o = (Get-Content $localJson -Raw -Encoding UTF8) | ConvertFrom-Json
                if ($o.data -and $o.data[0].b64_json) {
                    $png = [Convert]::FromBase64String($o.data[0].b64_json)
                    $pp = Join-Path $runDir ('img' + $i + '.png')
                    [IO.File]::WriteAllBytes($pp, $png)
                    $pngSha = Get-T08Sha256 -Path $pp
                    $w = [int]$png[16] * 16777216 + [int]$png[17] * 65536 + [int]$png[18] * 256 + [int]$png[19]
                    $h = [int]$png[20] * 16777216 + [int]$png[21] * 65536 + [int]$png[22] * 256 + [int]$png[23]
                    $dims = [string]$w + 'x' + [string]$h
                    if (($png[0] -eq 0x89) -and ($png[1] -eq 0x50) -and ($w -eq 512) -and ($h -eq 512)) { $pngOk = $true }
                }
                else { $imgDiag = 'response had no data[0].b64_json' }
            }
            else { $imgDiag = 'img json could not be read back: ' + $pulled.note }
        }
        else {
            $imgDiag = 'http ' + $r.httpCode + ': ' + $r.stdout
            Write-T08Utf8 -Path (Join-Path $runDir ('img' + $i + '-failure.txt')) -Content $r.stdout
        }
        Add-T08Step -Ledger $ledger -Id ('D1.' + $i) -Checklist 'D1' `
            -Description ('SDCPP sd_turbo generation #' + $i + ' seed=' + $seed) `
            -Status (Get-T08Status ($pngOk)) -Command 'POST /v1/images/generations' `
            -ExitCode (Get-T08ExitCode ($pngOk)) -Evidence ('img' + $i + '.png') `
            -Notes ('http=' + $r.httpCode + ' ' + $el + 's png=' + $dims + ' sha256=' + $pngSha + ' ' + $imgDiag.Substring(0, [Math]::Min(160, $imgDiag.Length)))
        Log ('image #' + $i + ': http=' + $r.httpCode + ' ' + $el + 's png=' + $dims + ' ' + $pngSha + ' ' + $imgDiag.Substring(0, [Math]::Min(160, $imgDiag.Length)))
        if (-not $pngOk) { break }
        $i = $i + 1
    }
}

# --- cancel ----------------------------------------------------------------
if (-not $SkipCancel) {
    $reqId = 't08-cancel'
    $body = @{
        model = 'image:0fc35bfa-9cf3-4b18-955f-e05e8aee49b5'
        prompt = 'a wide green field under a blue sky, simple, no text'
        sampler = 'euler_a'; steps = 60; cfg_scale = 7.0
        seed = 77; size = '512x512'; n = 1
    } | ConvertTo-Json -Compress
    $script = New-T08CancelScript -Url ($apiUrl + '/v1/images/generations') `
        -StopUrl ($apiUrl + '/v1/generate/stop') -JsonBody $body -RequestId $reqId `
        -WaitSeconds 25 -MaxTimeSeconds 900 -OutFile 'cancel.json'
    $r = Invoke-T08DeviceScript -Adb $Adb -Serial $Serial -Package $Package -Script $script `
        -WorkDirectory $runDir -TimeoutSeconds 1200
    Write-T08Utf8 -Path (Join-Path $runDir 'cancel.txt') -Content $r.raw
    $stopped = $false
    if ($r.stdout -match 'stopped') { $stopped = $true }
    $cancelCode = $false
    if ($r.stdout -match 'image_generation_cancelled') { $cancelCode = $true }
    $fp = Get-T08DataFingerprint -Adb $Adb -Serial $Serial -Package $Package
    Write-T08Utf8 -Path (Join-Path $runDir 'data-fingerprint-after-cancel.txt') -Content $fp
    $cancelOk = ($stopped -and $cancelCode)
    Add-T08Step -Ledger $ledger -Id 'D4.1' -Checklist 'D4' `
        -Description 'cancelled image generation reports cancellation and publishes nothing' `
        -Status (Get-T08Status ($cancelOk)) -Command 'POST /v1/generate/stop + DB inspection' `
        -ExitCode (Get-T08ExitCode ($cancelOk)) -Evidence 'cancel.txt' `
        -Notes ('stopped=' + $stopped + ' cancelledCode=' + $cancelCode + ' dataFp=' + $fp)
    Log ('cancel: stopped=' + $stopped + ' cancelledCode=' + $cancelCode)
}

# --- verdict ---------------------------------------------------------------
$nPass = @($ledger | Where-Object { $_.status -eq 'PASS' }).Count
$nFail = @($ledger | Where-Object { $_.status -eq 'FAIL' }).Count
$nNv = @($ledger | Where-Object { $_.status -eq 'NOT_VERIFIED' }).Count
Write-T08Ledger -Ledger $ledger -Directory $runDir -Meta $meta
Write-T08Utf8 -Path (Join-Path $runDir 'run.log') -Content $log.ToString()
Write-Host ''
Write-Host ('run dir : ' + $runDir)
Write-Host ('verdict : PASS=' + $nPass + ' FAIL=' + $nFail + ' NOT_VERIFIED=' + $nNv)
Write-Host ('steps   : ' + (($ledger | ForEach-Object { $_.id + '=' + $_.status }) -join ' '))
if ($nFail -gt 0) { exit 1 }
exit 0
