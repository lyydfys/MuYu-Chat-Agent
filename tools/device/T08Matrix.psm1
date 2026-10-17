#Requires -Version 5.1
<#
T08 matrix helpers.

Design constraints learned the hard way on this host -- all of these cost real time, so
they are recorded here rather than rediscovered:

  1. Windows PowerShell 5.1 has NO ternary operator. `$x = if (c) { 'a' } else { 'b' }`
     is a PARSE ERROR. Use the [T08Pick] helpers.
  2. A single-quoted PowerShell string must never contain a bare `'`. Shell snippets
     such as  sed -n 's/.../.../p'  therefore have to be built from a here-string or
     from double-quoted fragments. Getting this wrong produces cascading, misleading
     parse errors far from the real line.
  3. `run-as` cannot read /sdcard, so device scripts are piped over stdin, never pushed.
  4. `adb exec-out` truncates binary on this host; file transfer is base64 or adb pull.
  5. The host console is GBK, so UTF-8 device output must be decoded from bytes.

Device shell scripts are stored as here-strings with {TOKEN} placeholders and rendered by
New-T08Script. Nothing in this module embeds a bare single quote in a single-quoted
PowerShell string.
#>

Set-StrictMode -Version Latest

class T08Pick {
    static [string] Status([bool]$condition) {
        if ($condition) { return 'PASS' }
        return 'FAIL'
    }
    static [int] ExitCode([bool]$condition) {
        if ($condition) { return 0 }
        return 1
    }
}

<# The on-device preamble that resolves the bearer key without ever printing it. #>
$script:T08KeyPreamble = @'
KEY=$(grep -o 'name="api_key">[^<]*' shared_prefs/mca_api.xml | sed 's/name="api_key">//')
if [ -z "$KEY" ]; then echo "NO_KEY"; exit 9; fi
'@

<# Renders a device script template, substituting {TOKEN} placeholders. #>
function New-T08Script {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Template,
        [hashtable]$Tokens = @{}
    )
    $out = $script:T08KeyPreamble
    $body = $Template
    foreach ($k in $Tokens.Keys) {
        $body = $body.Replace('{' + $k + '}', [string]$Tokens[$k])
    }
    return ($out + "`n" + $body)
}

<# Authenticated JSON request; echoes __HTTP_CODE__<code>__ and writes the body to a file. #>
$script:T08CurlTemplate = @'
curl -s -S -m {MAXTIME} -X {METHOD} -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" {EXTRA}-o "$PWD/{OUTFILE}" -w "__HTTP_CODE__%{http_code}__" "{URL}"
'@

function New-T08CurlScript {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Url,
        [string]$JsonBody = '',
        [int]$MaxTimeSeconds = 300,
        [string]$ExtraHeader = '',
        [string]$OutFile = 'resp.json'
    )
    $extra = ''
    if ($ExtraHeader) { $extra = '-H "' + $ExtraHeader + '" ' }
    $pre = ''
    if ($JsonBody) {
        $enc = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($JsonBody))
        $pre = 'BODY=$(printf %s ' + $enc + ' | base64 -d)' + "`n" +
               'curl -s -S -m {MAXTIME} -X {METHOD} -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" ' +
               $extra + '-d "$BODY" -o "$PWD/{OUTFILE}" -w "__HTTP_CODE__%{http_code}__" "{URL}"'
    }
    else {
        $pre = 'curl -s -S -m {MAXTIME} -X {METHOD} -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" ' +
               $extra + '-o "$PWD/{OUTFILE}" -w "__HTTP_CODE__%{http_code}__" "{URL}"'
    }
    return (New-T08Script -Template $pre -Tokens @{
            MAXTIME = $MaxTimeSeconds; METHOD = $Method; URL = $Url; OUTFILE = $OutFile
        })
}

<# SSE capture template: writes "<elapsed_ms>|<line>" per frame, then frame statistics. #>
$script:T08SseTemplate = @'
OUT=$PWD/{OUTFILE}
: > "$OUT"
S=$(date +%s%3N)
echo "$S" > $PWD/.sse_start
curl -s -N -m {MAXTIME} -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" -d "$BODY" {URL} > $PWD/{RAWOUT}
E=$(date +%s%3N)
echo "ELAPSED_MS=$((E-S))"
echo "BYTES=$(wc -c < $PWD/{RAWOUT})"
echo "DONE=$(grep -c '\[DONE\]' $PWD/{RAWOUT})"
echo "CHUNKS=$(grep -c 'chat.completion.chunk' $PWD/{RAWOUT})"
echo "HEARTBEATS=$(grep -c 'mca-prefill' $PWD/{RAWOUT})"
echo "STOP=$(grep -c 'finish_reason":"stop' $PWD/{RAWOUT})"
echo "ROLEFRAMES=$(grep -c '"role":"assistant"' $PWD/{RAWOUT})"
'@

function New-T08SseScript {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [Parameter(Mandatory = $true)][string]$JsonBody,
        [int]$MaxTimeSeconds = 900,
        [string]$RawOut = 'stream-raw.txt'
    )
    $enc = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($JsonBody))
    $body = 'BODY=$(printf %s ' + $enc + ' | base64 -d)' + "`n" + $script:T08SseTemplate
    return (New-T08Script -Template $body -Tokens @{
            MAXTIME = $MaxTimeSeconds; URL = $Url; RAWOUT = $RawOut; OUTFILE = 'stream.sse'
        })
}

<# Cancel template: start a long generation, stop it by request id, report both results. #>
$script:T08CancelTemplate = @'
OUT=$PWD/{OUTFILE}
curl -s -S -m {MAXTIME} -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" -H "X-MCA-Request-Id: {REQID}" -d "$BODY" -w "%{http_code}" {URL} > $PWD/gen.http 2>&1 &
PID=$!
sleep {WAITSECONDS}
curl -s -m 120 -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" -d "$STOPBODY" {STOPURL}
echo ""
wait $PID
echo "GEN_HTTP=$(cat $PWD/gen.http 2>/dev/null)"
echo "GEN_BYTES=$(wc -c < $OUT)"
echo "--- gen body ---"
head -c 500 $OUT
'@

function New-T08CancelScript {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [Parameter(Mandatory = $true)][string]$StopUrl,
        [Parameter(Mandatory = $true)][string]$JsonBody,
        [Parameter(Mandatory = $true)][string]$RequestId,
        [int]$WaitSeconds = 4,
        [int]$MaxTimeSeconds = 900,
        [string]$OutFile = 'cancel.json'
    )
    $enc = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($JsonBody))
    $stopJson = '{"request_id":"' + $RequestId + '"}'
    $stopEnc = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($stopJson))
    $body = 'BODY=$(printf %s ' + $enc + ' | base64 -d)' + "`n" +
            'STOPBODY=$(printf %s ' + $stopEnc + ' | base64 -d)' + "`n" +
            $script:T08CancelTemplate
    return (New-T08Script -Template $body -Tokens @{
            MAXTIME = $MaxTimeSeconds; URL = $Url; STOPURL = $StopUrl; REQID = $RequestId
            WAITSECONDS = $WaitSeconds; OUTFILE = $OutFile
        })
}

<# Runs a rendered device script as the app uid via stdin and parses the http marker. #>
function Invoke-T08DeviceScript {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string]$Package,
        [Parameter(Mandatory = $true)][string]$Script,
        [Parameter(Mandatory = $true)][string]$WorkDirectory,
        [int]$TimeoutSeconds = 300
    )
    $tmp = Join-Path $WorkDirectory ('dev-' + [guid]::NewGuid().ToString('n').Substring(0, 8) + '.sh')
    [IO.File]::WriteAllText($tmp, $Script, [Text.UTF8Encoding]::new($false))
    $psi = [Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = 'cmd.exe'
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.Arguments = '/c ""' + $Adb + '" -s ' + $Serial + ' shell run-as ' + $Package + ' sh < "' + $tmp + '""'
    $proc = [Diagnostics.Process]::new()
    $proc.StartInfo = $psi
    $proc.Start() | Out-Null
    $so = $proc.StandardOutput.ReadToEndAsync()
    $se = $proc.StandardError.ReadToEndAsync()
    $finished = $proc.WaitForExit($TimeoutSeconds * 1000)
    if (-not $finished) { try { $proc.Kill() } catch { } }
    $stdout = $so.GetAwaiter().GetResult()
    $stderr = $se.GetAwaiter().GetResult()
    $proc.Dispose()
    Remove-Item -Force -ErrorAction SilentlyContinue $tmp
    $http = -1
    $m = [regex]::Match($stdout, '__HTTP_CODE__(\d+)__')
    if ($m.Success) { $http = [int]$m.Groups[1].Value }
    return [pscustomobject]@{
        timedOut = (-not $finished)
        httpCode = $http
        stdout   = ($stdout -replace '__HTTP_CODE__\d+__', '')
        raw      = $stdout
        stderr   = $stderr
    }
}

<# Byte-exact device file read (base64; exec-out corrupts binary here). #>
function Receive-T08DeviceFile {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string]$Package,
        [Parameter(Mandatory = $true)][string]$RemoteRelativePath,
        [Parameter(Mandatory = $true)][string]$LocalPath
    )
    $r = & $Adb -s $Serial shell run-as $Package base64 $RemoteRelativePath 2>$null
    $joined = (($r -join '') -replace '\s', '')
    if (-not $joined) { return [pscustomobject]@{ ok = $false; bytes = 0; note = 'empty base64' } }
    $bytes = $null
    try { $bytes = [Convert]::FromBase64String($joined) }
    catch { return [pscustomobject]@{ ok = $false; bytes = 0; note = 'decode failed' } }
    $dir = Split-Path -Parent $LocalPath
    if ($dir) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    [IO.File]::WriteAllBytes($LocalPath, $bytes)
    return [pscustomobject]@{ ok = $true; bytes = $bytes.Length; note = '' }
}

function Get-T08Foreground {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$Adb, [Parameter(Mandatory = $true)][string]$Serial)
    $o = & $Adb -s $Serial shell dumpsys activity activities 2>&1
    $l = ($o | Select-String -Pattern 'topResumedActivity' | Select-Object -First 1)
    if ($l) { return ($l.Line.Trim()) }
    return ''
}

function Get-T08NotifIds {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$Serial,
        [string]$Package = 'com.muyuchat.mca'
    )
    $o = & $Adb -s $Serial shell dumpsys notification --noredact 2>&1
    $ids = @()
    foreach ($l in $o) {
        if ($l -match ('pkg=' + [regex]::Escape($Package))) {
            $m = [regex]::Match($l, 'id=(\d+)')
            if ($m.Success) { $ids += $m.Groups[1].Value }
        }
    }
    return (($ids | Sort-Object -Unique) -join ',')
}

function Get-T08DataFingerprint {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$Serial,
        [string]$Package = 'com.muyuchat.mca'
    )
    $o = & $Adb -s $Serial shell run-as $Package sha256sum databases/mca.db databases/mca-runtime.db files/mca-models.json 2>&1
    return ((($o -join ' ') -replace '\s+', ' ')).Trim()
}

function Get-T08InstalledApkSha {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$Serial,
        [string]$Package = 'com.muyuchat.mca'
    )
    $p = & $Adb -s $Serial shell pm path $Package 2>&1
    $base = ($p | Where-Object { $_ -match 'base\.apk' } | Select-Object -First 1)
    if (-not $base) { return '' }
    $base = ($base -replace 'package:', '').Trim()
    $s = & $Adb -s $Serial shell sha256sum $base 2>&1
    $m = [regex]::Match(($s -join ' '), '([0-9a-fA-F]{64})')
    if ($m.Success) { return $m.Groups[1].Value.ToLowerInvariant() }
    return ''
}

Export-ModuleMember -Function @(
    'New-T08Script', 'New-T08CurlScript', 'New-T08SseScript', 'New-T08CancelScript',
    'Invoke-T08DeviceScript', 'Receive-T08DeviceFile', 'Get-T08Foreground',
    'Get-T08NotifIds', 'Get-T08DataFingerprint', 'Get-T08InstalledApkSha'
)
