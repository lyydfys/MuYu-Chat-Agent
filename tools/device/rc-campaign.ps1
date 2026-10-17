#Requires -Version 7.0
<#
.SYNOPSIS
  APK-bound 24-hour / 1000-request MCA RC soak and production-fault campaign runner.

.DESCRIPTION
  This host-side wrapper verifies that the installed base APK is the exact final RC APK,
  warms up the authenticated Local API, starts a device-side continuous sampler, executes
  a deterministic mixed workload, performs real production fault injections, and writes
  strict JSON evidence compatible with tools/final-gate/verify_runtime_evidence.py.

  The API key is never written to a file or passed in a command line. Debug builds read the
  key through run-as. Release builds receive MCA_API_KEY (or -ApiKeyEnvVar) over adb stdin.

.NOTES
  PowerShell 7 required. No installer is invoked. The supplied APK must already be the
  installed base APK; a mismatch fails before the campaign starts.
#>
[CmdletBinding()]
param(
    [string]$Serial = '',
    [string]$ApkPath = '',
    [string]$OutDir = '',
    [ValidateRange(0.01, 168)][double]$Hours = 24,
    [ValidateRange(1, 1000000)][int]$Requests = 1000,
    [ValidateRange(0, 10000)][int]$Warmup = 20,
    [switch]$SoakOnly,
    [switch]$FaultOnly,
    [switch]$SkipFaults,
    [switch]$DryRun,
    [switch]$SelfTest,
    [string]$Adb = '',
    [string]$Package = 'com.muyuchat.mca',
    [string]$ChatModel = 'Huihui-Qwen3.5-2B-abliterated.Q4_K_M',
    [string]$ImageModel = 'image:0fc35bfa-9cf3-4b18-955f-e05e8aee49b5',
    [int]$Port = 11435,
    [ValidateRange(5, 3600)][int]$SampleIntervalSeconds = 30,
    [string]$ApiKeyEnvVar = 'MCA_API_KEY',
    [string]$ModelFaultReport = 'tools/fault-injection/fault_matrix_report.json'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$script:RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$script:DeviceScriptPath = Join-Path $PSScriptRoot 'rc-campaign.sh'
$script:SelfPath = $PSCommandPath
$script:SchemaId = 'mca-runtime-evidence/1.0'
$script:AdbPath = ''
$script:ApiKey = ''
$script:AuthMode = ''
$script:RunErrors = [System.Collections.Generic.List[string]]::new()
$script:RunWarnings = [System.Collections.Generic.List[string]]::new()
$script:Ctx = [ordered]@{}
$script:UiEvidenceDir = ''
$script:SegmentDir = ''
$script:LocalRawDir = ''
$script:RemoteRoot = ''
$script:RemoteScript = ''

function Format-Utc([datetimeoffset]$Value) {
    return $Value.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fffZ', [Globalization.CultureInfo]::InvariantCulture)
}

function Now-Utc {
    return Format-Utc ([datetimeoffset]::UtcNow)
}

function Add-RunError([string]$Message) {
    $script:RunErrors.Add($Message)
    Write-Host ("ERROR: " + $Message) -ForegroundColor Red
}

function Add-RunWarning([string]$Message) {
    $script:RunWarnings.Add($Message)
    Write-Host ("WARN: " + $Message) -ForegroundColor Yellow
}

function Protect-Text([string]$Text, [string]$Secret) {
    if ($null -eq $Text) { return '' }
    $result = $Text
    if (-not [string]::IsNullOrEmpty($Secret)) {
        $result = $result.Replace($Secret, '[REDACTED]')
    }
    return [regex]::Replace($result, '(?i)Bearer\s+[A-Za-z0-9._~+/=:-]+', 'Bearer [REDACTED]')
}

function Write-Utf8Lf([string]$Path, [string]$Text) {
    $parent = Split-Path -Parent $Path
    if ($parent) { [IO.Directory]::CreateDirectory($parent) | Out-Null }
    $normalized = ($Text -replace "`r`n", "`n") -replace "`r", "`n"
    [IO.File]::WriteAllText($Path, $normalized, [Text.UTF8Encoding]::new($false))
}

function Write-JsonFile([string]$Path, $Value) {
    $json = $Value | ConvertTo-Json -Depth 100
    Write-Utf8Lf -Path $Path -Text ($json + "`n")
}

function Read-StrictJson([string]$Path) {
    $text = [IO.File]::ReadAllText($Path, [Text.Encoding]::UTF8)
    if ([string]::IsNullOrWhiteSpace($text)) { throw "empty JSON file: $Path" }
    $doc = [Text.Json.JsonDocument]::Parse($text)
    $doc.Dispose()
    return ($text | ConvertFrom-Json -Depth 100)
}

function Read-Ndjson([string]$Path) {
    $records = [System.Collections.Generic.List[object]]::new()
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return [pscustomobject]@{ Records = @(); Errors = @("missing NDJSON file: $Path") }
    }
    $errors = [System.Collections.Generic.List[string]]::new()
    $lineNo = 0
    foreach ($line in [IO.File]::ReadLines($Path, [Text.Encoding]::UTF8)) {
        $lineNo++
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        try {
            $doc = [Text.Json.JsonDocument]::Parse($line)
            $doc.Dispose()
            $records.Add(($line | ConvertFrom-Json -Depth 100))
        }
        catch {
            $errors.Add("line ${lineNo}: $($_.Exception.Message)")
        }
    }
    return [pscustomobject]@{ Records = @($records); Errors = @($errors) }
}

function Get-Sha256Bytes([byte[]]$Bytes) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($Bytes)) -replace '-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
}

function Get-FileSha256([string]$Path) {
    $stream = [IO.File]::OpenRead($Path)
    try {
        $sha = [Security.Cryptography.SHA256]::Create()
        try { return ([BitConverter]::ToString($sha.ComputeHash($stream)) -replace '-', '').ToLowerInvariant() }
        finally { $sha.Dispose() }
    }
    finally { $stream.Dispose() }
}

function Get-RelativeSlashPath([string]$Base, [string]$Path) {
    return [IO.Path]::GetRelativePath($Base, $Path).Replace('\', '/')
}

function Get-EvidenceDescriptor([string]$Path, [string]$Base) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "evidence file does not exist: $Path"
    }
    $item = Get-Item -LiteralPath $Path
    return [ordered]@{
        path = Get-RelativeSlashPath -Base $Base -Path $item.FullName
        sha256 = Get-FileSha256 $item.FullName
        bytes = [int64]$item.Length
    }
}

function Invoke-NativeProcess {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$Arguments = @(),
        [string]$StdIn = $null,
        [int]$TimeoutMs = 120000,
        [string]$WorkingDirectory = $script:RepoRoot
    )
    $psi = [Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $FilePath
    $psi.WorkingDirectory = $WorkingDirectory
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.RedirectStandardInput = $true
    foreach ($arg in $Arguments) { $psi.ArgumentList.Add([string]$arg) }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $psi
    [void]$process.Start()
    $outTask = $process.StandardOutput.ReadToEndAsync()
    $errTask = $process.StandardError.ReadToEndAsync()
    if ($null -ne $StdIn) {
        $process.StandardInput.Write($StdIn)
    }
    $process.StandardInput.Close()
    $timedOut = $false
    if ($TimeoutMs -gt 0) {
        if (-not $process.WaitForExit($TimeoutMs)) {
            $timedOut = $true
            try { $process.Kill($true) } catch { }
        }
    }
    $process.WaitForExit()
    $stdout = ''
    $stderr = ''
    try { $stdout = $outTask.GetAwaiter().GetResult() } catch { }
    try { $stderr = $errTask.GetAwaiter().GetResult() } catch { }
    $exitCode = if ($timedOut) { 124 } else { $process.ExitCode }
    $process.Dispose()
    return [pscustomobject]@{
        ExitCode = $exitCode
        StdOut = Protect-Text -Text $stdout -Secret $script:ApiKey
        StdErr = Protect-Text -Text $stderr -Secret $script:ApiKey
        TimedOut = $timedOut
    }
}

function Start-NativeBackground {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$Arguments = @(),
        [Parameter(Mandatory = $true)][string]$StdOutPath,
        [string]$StdErrPath = ''
    )
    if (-not $StdErrPath) { $StdErrPath = "$StdOutPath.stderr" }
    $psi = [Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $FilePath
    $psi.WorkingDirectory = $script:RepoRoot
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    foreach ($arg in $Arguments) { $psi.ArgumentList.Add([string]$arg) }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $psi
    [void]$process.Start()
    return [pscustomobject]@{
        Process = $process
        StdOutTask = $process.StandardOutput.ReadToEndAsync()
        StdErrTask = $process.StandardError.ReadToEndAsync()
        StdOutPath = $StdOutPath
        StdErrPath = $StdErrPath
    }
}

function Complete-NativeBackground($Handle, [int]$TimeoutMs = 120000) {
    if ($null -eq $Handle) { return }
    $p = $Handle.Process
    $timedOut = $false
    if (-not $p.HasExited) {
        if (-not $p.WaitForExit($TimeoutMs)) {
            $timedOut = $true
            try { $p.Kill($true) } catch { }
            $p.WaitForExit()
        }
    }
    $stdout = ''
    $stderr = ''
    try { $stdout = $Handle.StdOutTask.GetAwaiter().GetResult() } catch { }
    try { $stderr = $Handle.StdErrTask.GetAwaiter().GetResult() } catch { }
    Write-Utf8Lf -Path $Handle.StdOutPath -Text (Protect-Text -Text $stdout -Secret $script:ApiKey)
    Write-Utf8Lf -Path $Handle.StdErrPath -Text (Protect-Text -Text $stderr -Secret $script:ApiKey)
    $exitCode = if ($timedOut) { 124 } else { $p.ExitCode }
    try { $p.Dispose() } catch { }
    return [pscustomobject]@{ TimedOut = $timedOut; ExitCode = $exitCode }
}

function Resolve-AdbPath {
    $candidates = [System.Collections.Generic.List[string]]::new()
    if ($Adb) { $candidates.Add($Adb) }
    if ($env:ADB) { $candidates.Add($env:ADB) }
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) { $candidates.Add($command.Source) }
    $candidates.Add('D:\model\android-sdk\platform-tools\adb.exe')
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    throw 'adb was not found. Supply -Adb or set ADB.'
}

function Invoke-Adb {
    param([string[]]$Arguments, [int]$TimeoutMs = 120000)
    $argsWithSerial = [System.Collections.Generic.List[string]]::new()
    if ($script:Serial) { $argsWithSerial.Add('-s'); $argsWithSerial.Add($script:Serial) }
    foreach ($arg in $Arguments) { $argsWithSerial.Add($arg) }
    return Invoke-NativeProcess -FilePath $script:AdbPath -Arguments @($argsWithSerial) -TimeoutMs $TimeoutMs
}

function Invoke-AdbShell {
    param([string]$Command, [int]$TimeoutMs = 120000)
    # Pass the command as one argument to adb's default shell. Wrapping it in
    # another `sh -c` loses the quoting through adb's argv joining on Windows
    # (mkdir -p then receives no path).
    return Invoke-Adb -Arguments @('shell', $Command) -TimeoutMs $TimeoutMs
}

function Invoke-AdbWithInput {
    param([string[]]$Arguments, [string]$StdIn, [int]$TimeoutMs = 120000)
    $argsWithSerial = [System.Collections.Generic.List[string]]::new()
    if ($script:Serial) { $argsWithSerial.Add('-s'); $argsWithSerial.Add($script:Serial) }
    foreach ($arg in $Arguments) { $argsWithSerial.Add($arg) }
    return Invoke-NativeProcess -FilePath $script:AdbPath -Arguments @($argsWithSerial) -StdIn $StdIn -TimeoutMs $TimeoutMs
}

function Get-DeviceProperty([string]$Name) {
    $r = Invoke-Adb -Arguments @('shell', 'getprop', $Name) -TimeoutMs 30000
    if ($r.ExitCode -ne 0) { return '' }
    return $r.StdOut.Trim()
}

function Assert-AdbDevice {
    $all = Invoke-NativeProcess -FilePath $script:AdbPath -Arguments @('devices', '-l') -TimeoutMs 30000
    if ($all.ExitCode -ne 0) { throw "adb devices failed: $($all.StdErr)" }
    $deviceSerials = @()
    foreach ($line in ($all.StdOut -split "`n")) {
        if ($line -match '^(\S+)\s+device\b') { $deviceSerials += $Matches[1] }
    }
    if ($deviceSerials.Count -eq 0) { throw 'adb devices reports no device in state=device' }
    if (-not $script:Serial) { throw '-Serial is required for a real campaign' }
    if ($deviceSerials -notcontains $script:Serial) {
        throw "adb device '$($script:Serial)' is not present in state=device; visible devices: $($deviceSerials -join ', ')"
    }
    return $all.StdOut
}

function Get-InstalledBaseApkFacts {
    $pm = Invoke-Adb -Arguments @('shell', 'pm', 'path', $Package) -TimeoutMs 60000
    if ($pm.ExitCode -ne 0) { throw "pm path failed: $($pm.StdErr)" }
    $allPaths = @()
    $paths = @()
    foreach ($line in ($pm.StdOut -split "`n")) {
        $line = $line.Trim()
        if ($line -match '^package:(.+\.apk)$') {
            $allPaths += $Matches[1]
            if ($line -match '^package:(.+base\.apk)$') { $paths += $Matches[1] }
        }
    }
    if ($allPaths.Count -ne 1) { throw "single-APK evidence requires exactly one installed APK path, found $($allPaths.Count); split APKs are not accepted" }
    if ($paths.Count -ne 1) { throw "expected exactly one installed base.apk, found $($paths.Count)" }
    $basePath = $paths[0]
    $sha = Invoke-Adb -Arguments @('shell', 'sha256sum', $basePath) -TimeoutMs 180000
    if ($sha.ExitCode -ne 0) {
        $sha = Invoke-Adb -Arguments @('shell', 'toybox', 'sha256sum', $basePath) -TimeoutMs 180000
    }
    if ($sha.ExitCode -ne 0) { throw "device sha256sum failed: $($sha.StdErr)" }
    $shaMatch = [regex]::Match($sha.StdOut, '(?i)\b[0-9a-f]{64}\b')
    if (-not $shaMatch.Success) { throw 'device sha256sum returned no SHA-256' }
    $bytes = Invoke-Adb -Arguments @('shell', 'stat', '-c', '%s', $basePath) -TimeoutMs 60000
    $byteMatch = [regex]::Match($bytes.StdOut, '(?m)^\s*(\d+)\s*$')
    if ($bytes.ExitCode -ne 0 -or -not $byteMatch.Success) {
        throw "device base.apk byte count failed: $($bytes.StdOut) $($bytes.StdErr)"
    }
    return [pscustomobject]@{
        path = $basePath
        sha256 = $shaMatch.Value.ToLowerInvariant()
        bytes = [int64]$byteMatch.Groups[1].Value
    }
}

function Get-HealthState {
    $url = "http://127.0.0.1:$Port/health"
    $r = Invoke-Adb -Arguments @('shell', 'curl', '-sS', '-m', '30', $url) -TimeoutMs 45000
    $status = ''
    $name = ''
    $ok = $false
    if ($r.ExitCode -eq 0 -and -not [string]::IsNullOrWhiteSpace($r.StdOut)) {
        try {
            $doc = [Text.Json.JsonDocument]::Parse($r.StdOut)
            $doc.Dispose()
            $obj = $r.StdOut | ConvertFrom-Json -Depth 20
            $status = [string]$obj.status
            $name = [string]$obj.name
            $ok = $status -eq 'ok'
        }
        catch { }
    }
    return [pscustomobject]@{ ok = $ok; status = $status; name = $name; raw = $r.StdOut; error = $r.StdErr; exit = $r.ExitCode }
}

function Get-ModelsState {
    $url = "http://127.0.0.1:$Port/v1/models"
    $r = Invoke-Adb -Arguments @('shell', 'curl', '-sS', '-m', '60', $url) -TimeoutMs 90000
    if ($r.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($r.StdOut)) {
        return [pscustomobject]@{ ok = $false; raw = $r.StdOut; error = $r.StdErr; entries = @(); loaded = @(); expectedLoaded = @() }
    }
    try {
        $doc = [Text.Json.JsonDocument]::Parse($r.StdOut)
        $doc.Dispose()
        $obj = $r.StdOut | ConvertFrom-Json -Depth 100
    }
    catch {
        return [pscustomobject]@{ ok = $false; raw = $r.StdOut; error = $_.Exception.Message; entries = @(); loaded = @(); expectedLoaded = @() }
    }
    $entries = [System.Collections.Generic.List[object]]::new()
    if ($null -ne $obj.PSObject.Properties['data']) {
        $data = $obj.data
        if ($data -is [System.Array]) {
            foreach ($item in $data) { $entries.Add($item) }
        }
        elseif ($data -is [System.Management.Automation.PSCustomObject]) {
            foreach ($property in $data.PSObject.Properties) { $entries.Add($property.Value) }
        }
        elseif ($null -ne $data -and $data -is [System.Collections.IEnumerable] -and $data -isnot [string]) {
            foreach ($item in $data) { $entries.Add($item) }
        }
    }
    $loaded = @($entries | Where-Object { $_.PSObject.Properties['loaded'] -and $_.loaded -eq $true })
    $expectedLoaded = @($loaded | Where-Object {
        $ids = @()
        foreach ($field in @('id', 'name', 'display_name', 'displayName')) {
            if ($_.PSObject.Properties[$field] -and -not [string]::IsNullOrWhiteSpace([string]$_.$field)) {
                $ids += [string]$_.$field
            }
        }
        $ids -contains $ChatModel
    })
    return [pscustomobject]@{ ok = $true; raw = $r.StdOut; error = ''; entries = @($entries); loaded = $loaded; expectedLoaded = $expectedLoaded }
}

function Get-TopActivityLine {
    $r = Invoke-Adb -Arguments @('shell', 'dumpsys', 'activity', 'activities') -TimeoutMs 60000
    if ($r.ExitCode -ne 0) { return '' }
    foreach ($line in ($r.StdOut -split "`n")) {
        if ($line -match 'topResumedActivity|mResumedActivity') { return $line.Trim() }
    }
    return ''
}

function Test-MainActivityForeground {
    $line = Get-TopActivityLine
    return [pscustomobject]@{
        foreground = $line -match [regex]::Escape("$Package/.MainActivity")
        line = $line
    }
}

function Invoke-MainActivityLaunch {
    $r = Invoke-Adb -Arguments @('shell', 'am', 'start', '-W', '-n', "$Package/.MainActivity") -TimeoutMs 120000
    Start-Sleep -Seconds 2
    return [pscustomobject]@{ exit = $r.ExitCode; output = $r.StdOut; error = $r.StdErr }
}

function Get-UiNodes {
    param([string]$Tag = 'dump')
    if (-not $script:UiEvidenceDir) { throw 'UI evidence directory is not initialized' }
    $remote = "/sdcard/mca_rc_ui_$($script:Ctx.campaignId).xml"
    for ($attempt = 1; $attempt -le 5; $attempt++) {
        [void](Invoke-Adb -Arguments @('shell', 'rm', '-f', $remote) -TimeoutMs 30000)
        [void](Invoke-Adb -Arguments @('shell', 'uiautomator', 'dump', '--compressed', $remote) -TimeoutMs 60000)
        $local = Join-Path $script:UiEvidenceDir ("$Tag-$attempt.xml")
        $pull = Invoke-Adb -Arguments @('pull', $remote, $local) -TimeoutMs 60000
        if ($pull.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $local)) {
            Start-Sleep -Milliseconds 900
            continue
        }
        $raw = [IO.File]::ReadAllText($local, [Text.Encoding]::UTF8)
        if ($script:ApiKey) {
            $redacted = $raw.Replace($script:ApiKey, '[REDACTED]')
            if ($redacted -cne $raw) {
                [IO.File]::WriteAllText($local, $redacted, [Text.UTF8Encoding]::new($false))
                $raw = $redacted
            }
        }
        if ($raw.Length -lt 1000) {
            Start-Sleep -Milliseconds 900
            continue
        }
        $nodes = [System.Collections.Generic.List[object]]::new()
        foreach ($m in [regex]::Matches($raw, '<node\s+([^>]*?)/?>')) {
            $attrs = $m.Groups[1].Value
            $text = ''
            $desc = ''
            $bounds = ''
            $tm = [regex]::Match($attrs, 'text="([^"]*)"')
            if ($tm.Success) { $text = [Net.WebUtility]::HtmlDecode($tm.Groups[1].Value) }
            $dm = [regex]::Match($attrs, 'content-desc="([^"]*)"')
            if ($dm.Success) { $desc = [Net.WebUtility]::HtmlDecode($dm.Groups[1].Value) }
            $bm = [regex]::Match($attrs, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
            if (-not $bm.Success) { continue }
            $cm = [regex]::Match($attrs, 'clickable="([^"]*)"')
            $km = [regex]::Match($attrs, 'class="([^"]*)"')
            $bounds = $bm.Value
            $x1 = [int]$bm.Groups[1].Value
            $y1 = [int]$bm.Groups[2].Value
            $x2 = [int]$bm.Groups[3].Value
            $y2 = [int]$bm.Groups[4].Value
            $nodes.Add([pscustomobject]@{
                text = $text
                desc = $desc
                bounds = $bounds
                class = if ($km.Success) { [Net.WebUtility]::HtmlDecode($km.Groups[1].Value) } else { '' }
                clickable = $cm.Success -and $cm.Groups[1].Value -eq 'true'
                top = $y1
                left = $x1
                right = $x2
                cx = [int](($x1 + $x2) / 2)
                cy = [int](($y1 + $y2) / 2)
            })
        }
        if ($nodes.Count -gt 0) { return @($nodes) }
        Start-Sleep -Milliseconds 900
    }
    throw "uiautomator dump did not settle for tag '$Tag'"
}

function Tap-UiText {
    param(
        [string]$Text,
        [string]$Tag,
        [int]$MinTop = 0,
        [int]$MaxTop = 99999,
        [switch]$Required
    )
    $nodes = Get-UiNodes -Tag $Tag
    $hit = $nodes | Where-Object { $_.top -ge $MinTop -and $_.top -le $MaxTop -and (($_.text -and $_.text.Contains($Text)) -or ($_.desc -and $_.desc.Contains($Text))) } | Sort-Object top | Select-Object -First 1
    if (-not $hit) {
        if ($Required) { throw "UI control not found: $Text" }
        return $false
    }
    $fg = Test-MainActivityForeground
    if (-not $fg.foreground) { throw "refusing to tap '$Text': MCA MainActivity is not foreground ($($fg.line))" }
    $tap = Invoke-Adb -Arguments @('shell', 'input', 'tap', [string]$hit.cx, [string]$hit.cy) -TimeoutMs 30000
    Start-Sleep -Milliseconds 700
    return $tap.ExitCode -eq 0
}

function Get-UiTextNodes([string]$Tag) {
    return @(Get-UiNodes -Tag $Tag)
}

function Get-WslPath([string]$WindowsPath) {
    $resolved = (Resolve-Path -LiteralPath $WindowsPath).Path
    if ($resolved -match '^([A-Za-z]):\\(.*)$') {
        $drive = $Matches[1].ToLowerInvariant()
        $rest = $Matches[2].Replace('\', '/')
        return "/mnt/$drive/$rest"
    }
    return $resolved.Replace('\', '/')
}

function Test-ShellSyntax {
    $last = $null
    $wsl = Get-Command wsl.exe -ErrorAction SilentlyContinue
    if ($wsl) {
        $posix = Get-WslPath $script:DeviceScriptPath
        $result = Invoke-NativeProcess -FilePath $wsl.Source -Arguments @('-e', 'sh', '-n', $posix) -TimeoutMs 30000
        if ($result.ExitCode -eq 0) {
            return [pscustomobject]@{ ok = $true; tool = 'wsl.exe sh -n'; output = $result.StdOut + $result.StdErr }
        }
        $last = $result
    }
    $bashCandidates = [System.Collections.Generic.List[string]]::new()
    $bash = Get-Command bash.exe -ErrorAction SilentlyContinue
    if ($bash) { $bashCandidates.Add($bash.Source) }
    foreach ($candidate in @('C:\Program Files\Git\bin\bash.exe','C:\Program Files\Git\usr\bin\sh.exe','C:\Program Files (x86)\Git\bin\bash.exe')) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { $bashCandidates.Add($candidate) }
    }
    $posixPath = ''
    try { $posixPath = Get-WslPath $script:DeviceScriptPath } catch { }
    foreach ($candidate in $bashCandidates) {
        foreach ($pathArg in @($script:DeviceScriptPath, $posixPath)) {
            if ([string]::IsNullOrWhiteSpace($pathArg)) { continue }
            $result = Invoke-NativeProcess -FilePath $candidate -Arguments @('-n', $pathArg) -TimeoutMs 30000
            if ($result.ExitCode -eq 0) {
                return [pscustomobject]@{ ok = $true; tool = "$candidate -n"; output = $result.StdOut + $result.StdErr }
            }
            $last = $result
        }
    }
    return [pscustomobject]@{ ok = $false; tool = 'unavailable'; output = if ($null -ne $last) { $last.StdErr + $last.StdOut } else { 'no WSL/bash shell' } }
}
function Test-LfNoBom([string]$Path) {
    $bytes = [IO.File]::ReadAllBytes($Path)
    $cr = 0
    foreach ($b in $bytes) { if ($b -eq 13) { $cr++ } }
    $bom = $bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF
    return [pscustomobject]@{ ok = ($cr -eq 0 -and -not $bom); cr = $cr; bom = $bom; bytes = $bytes.Length }
}
$script:SelectModelLabel = -join ([char[]](0x9009, 0x62E9, 0x6A21, 0x578B))
$script:LocalInferenceLabel = -join ([char[]](0x672C, 0x5730, 0x63A8, 0x7406))
$script:UnloadLabel = -join ([char[]](0x5378, 0x8F7D))
$script:LoadLabel = -join ([char[]](0x52A0, 0x8F7D))

function Scroll-UiList {
    $size = Invoke-Adb -Arguments @('shell', 'wm', 'size') -TimeoutMs 30000
    $width = 1080
    $height = 1920
    if ($size.StdOut -match '(\d+)x(\d+)') {
        $width = [int]$Matches[1]
        $height = [int]$Matches[2]
    }
    $x = [int]($width / 2)
    $y1 = [int]($height * 0.72)
    $y2 = [int]($height * 0.35)
    [void](Invoke-Adb -Arguments @('shell', 'input', 'swipe', [string]$x, [string]$y1, [string]$x, [string]$y2, '350') -TimeoutMs 30000)
    Start-Sleep -Milliseconds 800
}

function Invoke-UiSmoke {
    $launch = Invoke-MainActivityLaunch
    $foreground = Test-MainActivityForeground
    $nodes = @()
    try { $nodes = @(Get-UiNodes -Tag 'preflight-ui') } catch { }
    $pass = $launch.exit -eq 0 -and $foreground.foreground -and $nodes.Count -gt 0
    $evidence = Join-Path ([string]$script:Ctx['evidenceDir']) 'device-main-activity-ui.txt'
    $text = "campaign_id=$($script:Ctx['campaignId'])`nlaunch_exit=$($launch.exit)`nforeground=$($foreground.line)`nnode_count=$($nodes.Count)`n$($launch.output)`n"
    Write-Utf8Lf -Path $evidence -Text $text
    return [pscustomobject]@{
        status = if ($pass) { 'PASSED' } else { 'FAILED' }
        activity = "$Package/.MainActivity"
        foreground = $foreground.line
        nodeCount = $nodes.Count
        evidencePath = $evidence
    }
}

function Open-LocalModelSheet {
    $nodes = @(Get-UiNodes -Tag 'model-sheet-check')
    $hasLocal = @($nodes | Where-Object { ($_.text -and $_.text.Contains($script:LocalInferenceLabel)) -or ($_.desc -and $_.desc.Contains($script:LocalInferenceLabel)) }).Count -gt 0
    if (-not $hasLocal) {
        [void](Tap-UiText -Text $script:SelectModelLabel -Tag 'model-chip' -MaxTop 900 -Required:$false)
        $deadline = (Get-Date).AddSeconds(12)
        while ((Get-Date) -lt $deadline) {
            $nodes = @(Get-UiNodes -Tag 'model-sheet-wait')
            $hasLocal = @($nodes | Where-Object { ($_.text -and $_.text.Contains($script:LocalInferenceLabel)) -or ($_.desc -and $_.desc.Contains($script:LocalInferenceLabel)) }).Count -gt 0
            if ($hasLocal) { break }
            Start-Sleep -Seconds 1
        }
    }
    if (-not $hasLocal) { throw 'production UI model sheet did not expose the local-inference section' }
    [void](Tap-UiText -Text $script:LocalInferenceLabel -Tag 'local-inference' -MinTop 0 -Required:$true)
    Start-Sleep -Seconds 1
}

function Wait-UiTargetModel([int]$TimeoutSeconds = 20) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $nodes = @(Get-UiNodes -Tag 'target-model-wait')
        $hit = $nodes | Where-Object { ($_.text -and $_.text.Contains($ChatModel)) -or ($_.desc -and $_.desc.Contains($ChatModel)) } | Select-Object -First 1
        if ($hit) { return $hit }
        Scroll-UiList
    }
    return $null
}

function Invoke-UiLoadModel {
    $before = Get-ModelsState
    if ($before.ok -and $before.expectedLoaded.Count -gt 0) {
        return [pscustomobject]@{ status = 'PASSED'; alreadyLoaded = $true; before = $before; after = $before }
    }
    $launch = Invoke-MainActivityLaunch
    if ($launch.exit -ne 0) { throw 'am start MainActivity failed before UI model load' }
    Open-LocalModelSheet
    $target = Wait-UiTargetModel -TimeoutSeconds 20
    if (-not $target) { throw "target chat model is not visible in the production model UI: $ChatModel" }
    [void](Tap-UiText -Text $ChatModel -Tag 'load-target-row' -Required:$true)
    $deadline = (Get-Date).AddSeconds(240)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 4
        $after = Get-ModelsState
        if ($after.ok -and $after.expectedLoaded.Count -gt 0) {
            return [pscustomobject]@{ status = 'PASSED'; alreadyLoaded = $false; before = $before; after = $after }
        }
    }
    throw 'production UI load did not reach /v1/models loaded=true within 240 seconds'
}

function Tap-AppMenuButton {
    $size = Invoke-Adb -Arguments @('shell', 'wm', 'size') -TimeoutMs 30000
    $width = 1440
    $height = 3200
    if ($size.StdOut -match '(\d+)x(\d+)') { $width = [int]$Matches[1]; $height = [int]$Matches[2] }
    for ($attempt = 1; $attempt -le 5; $attempt++) {
        $nodes = @(Get-UiNodes -Tag "app-menu-button-$attempt")
        # The app menu is the rightmost clickable action in the production header.
        # Resolve accessibility bounds and dismiss any open sheet/page if the
        # model selector is still covering the chat header.
        $button = $nodes | Where-Object {
            $_.clickable -and $_.left -ge [int]($width * 0.72) -and
            $_.top -le [int]($height * 0.20) -and $_.right -le $width
        } | Sort-Object left -Descending | Select-Object -First 1
        if ($button) {
            $fg = Test-MainActivityForeground
            if (-not $fg.foreground) { throw 'refusing to open the app menu because MainActivity is not foreground' }
            $tap = Invoke-Adb -Arguments @('shell', 'input', 'tap', [string]$button.cx, [string]$button.cy) -TimeoutMs 30000
            Start-Sleep -Milliseconds 800
            if ($tap.ExitCode -ne 0) { throw 'adb input tap failed while opening the app menu' }
            return $true
        }
        $fg = Test-MainActivityForeground
        if ($fg.foreground) {
            [void](Invoke-Adb -Arguments @('shell', 'input', 'keyevent', '4') -TimeoutMs 30000)
            Start-Sleep -Seconds 1
        } else {
            [void](Invoke-MainActivityLaunch)
        }
    }
    throw 'production app-menu button was not found in the accessibility tree after closing overlays'
}

function Invoke-UiUnloadLoadViaManagement {
    $launch = Invoke-MainActivityLaunch
    if ($launch.exit -ne 0) { throw 'am start MainActivity failed before model-management unload/load' }
    $entryNodes = @(Get-UiNodes -Tag 'management-entry-direct')
    $moreModels = $entryNodes | Where-Object { $_.text -and $_.text.Trim() -eq '更多模型' } | Select-Object -First 1
    if ($moreModels) {
        # The production model selector exposes the same management surface
        # through "更多模型"; use that real user path when the sheet is open.
        [void](Tap-UiText -Text '更多模型' -Tag 'management-more-models' -Required:$true)
        Start-Sleep -Seconds 2
        $entryNodes = @(Get-UiNodes -Tag 'management-direct')
    }
    if (-not ($entryNodes | Where-Object { $_.text -and $_.text.Contains('模型管理') })) {
        $closeNodes = @(Get-UiNodes -Tag 'management-close')
        $close = $closeNodes | Where-Object { $_.text -and $_.text.Contains('关闭') } | Select-Object -First 1
        if ($close) {
            [void](Invoke-Adb -Arguments @('shell', 'input', 'tap', [string]$close.cx, [string]$close.cy) -TimeoutMs 30000)
            Start-Sleep -Seconds 1
            $afterClose = @(Get-UiNodes -Tag 'management-close-after')
            $stillClose = $afterClose | Where-Object { $_.text -and $_.text.Contains('关闭') } | Select-Object -First 1
            if ($stillClose) {
                [void](Invoke-Adb -Arguments @('shell', 'input', 'tap', [string]$stillClose.cx, [string]$stillClose.cy) -TimeoutMs 30000)
                Start-Sleep -Seconds 1
            }
        }
        if (-not (Tap-AppMenuButton)) { throw 'could not open app menu for model management' }
        $menuDeadline = (Get-Date).AddSeconds(10)
        $menuNodes = @()
        while ((Get-Date) -lt $menuDeadline) {
            $menuNodes = @(Get-UiNodes -Tag 'management-menu')
            if ($menuNodes | Where-Object { $_.text -and $_.text.Contains('模型管理') }) { break }
            Start-Sleep -Seconds 1
        }
        if (-not ($menuNodes | Where-Object { $_.text -and $_.text.Contains('模型管理') })) {
            [void](Tap-AppMenuButton)
            Start-Sleep -Seconds 2
            $menuNodes = @(Get-UiNodes -Tag 'management-menu-retry')
        }
        if (-not ($menuNodes | Where-Object { $_.text -and $_.text.Contains('模型管理') })) {
            throw 'model-management entry was not visible in the production app menu'
        }
        [void](Tap-UiText -Text '模型管理' -Tag 'management-entry' -Required:$true)
        Start-Sleep -Seconds 2
    }
    for ($attempt = 1; $attempt -le 5; $attempt++) {
        $nodes = @(Get-UiNodes -Tag "management-$attempt")
        # The top model selector also shows the exact model name. The local tab's
        # model card is lower in the hierarchy, so prefer the lowest exact match
        # and never anchor on the header's "已加载：..." status sentence.
        $targetMatches = @($nodes | Where-Object {
            (($_.text -and $_.text.Trim() -eq $ChatModel.Trim()) -or
             ($_.desc -and $_.desc.Trim() -eq $ChatModel.Trim())) -and
            -not ($_.text -and $_.text.Trim().StartsWith('已加载：'))
        } | Sort-Object top -Descending)
        if ($targetMatches.Count -eq 0) {
            $targetMatches = @($nodes | Where-Object {
                (($_.text -and $_.text.Contains($ChatModel) -and -not $_.text.Contains('已加载：')) -or
                 ($_.desc -and $_.desc.Contains($ChatModel) -and -not $_.desc.Contains('已加载：')))
            } | Sort-Object top -Descending)
        }
        $target = $targetMatches | Select-Object -First 1
        if ($target) {
            $unload = $nodes | Where-Object {
                $_.text -and $_.text.Trim() -eq $script:UnloadLabel -and $_.top -ge ($target.top - 100) -and $_.top -le ($target.top + 1200)
            } | Sort-Object top | Select-Object -First 1
            if ($unload) {
                $tap = Invoke-Adb -Arguments @('shell', 'input', 'tap', [string]$unload.cx, [string]$unload.cy) -TimeoutMs 30000
                if ($tap.ExitCode -ne 0) { throw 'adb input tap failed for model-management unload' }
                $deadline = (Get-Date).AddSeconds(120)
                while ((Get-Date) -lt $deadline) {
                    Start-Sleep -Seconds 3
                    $state = Get-ModelsState
                    if ($state.ok -and $state.expectedLoaded.Count -eq 0) {
                        # Let the unload transition finish re-enabling the card
                        # actions before touching Load; an immediate tap can be
                        # ignored while Compose is still settling.
                        Start-Sleep -Seconds 3
                        $loadDeadline = (Get-Date).AddSeconds(240)
                        for ($loadAttempt = 1; $loadAttempt -le 2 -and (Get-Date) -lt $loadDeadline; $loadAttempt++) {
                            $nodes = @(Get-UiNodes -Tag "management-load-$loadAttempt")
                            $targetAfter = $nodes | Where-Object {
                                ($_.text -and $_.text.Trim() -eq $ChatModel.Trim()) -or
                                ($_.desc -and $_.desc.Trim() -eq $ChatModel.Trim())
                            } | Sort-Object top -Descending | Select-Object -First 1
                            if (-not $targetAfter) { $targetAfter = $target }
                            $load = $nodes | Where-Object {
                                $_.text -and $_.text.Trim() -eq $script:LoadLabel -and
                                $_.top -ge ($targetAfter.top - 150) -and $_.top -le ($targetAfter.top + 1200)
                            } | Sort-Object top | Select-Object -First 1
                            if (-not $load) { throw 'model-management load control was not visible after unload' }
                            $fg = Test-MainActivityForeground
                            if (-not $fg.foreground) { throw 'refusing to tap load because MainActivity is not foreground' }
                            $loadTap = Invoke-Adb -Arguments @('shell', 'input', 'tap', [string]$load.cx, [string]$load.cy) -TimeoutMs 30000
                            if ($loadTap.ExitCode -ne 0) { throw 'adb input tap failed for model-management load' }
                            $retryAt = (Get-Date).AddSeconds(90)
                            while ((Get-Date) -lt $retryAt -and (Get-Date) -lt $loadDeadline) {
                                Start-Sleep -Seconds 4
                                $state = Get-ModelsState
                                if ($state.ok -and $state.expectedLoaded.Count -gt 0) {
                                    return [pscustomobject]@{ status = 'PASSED'; detail = 'model management card unloaded and loaded the chat model; API observed both states' }
                                }
                            }
                        }
                        throw 'model-management load did not reach loaded=true within 240 seconds after two settled card-local taps'
                    }
                }
                throw '/v1/models did not report unloaded after model-management unload'
            }
        }
        Scroll-UiList
    }
    throw 'model-management loaded card or unload control was not found'
}
function Invoke-UiUnloadModel {
    $before = Get-ModelsState
    if (-not $before.ok -or $before.expectedLoaded.Count -eq 0) {
        throw 'UI unload precondition failed: the expected chat model was not loaded'
    }
    $launch = Invoke-MainActivityLaunch
    if ($launch.exit -ne 0) { throw 'am start MainActivity failed before UI model unload' }
    Open-LocalModelSheet
    $target = Wait-UiTargetModel -TimeoutSeconds 20
    if (-not $target) { throw "target chat model is not visible in the production model UI: $ChatModel" }
    $nodes = @(Get-UiNodes -Tag 'unload-search')
    $unload = $nodes | Where-Object {
        (($_.text -and $_.text.Contains($script:UnloadLabel)) -or ($_.desc -and $_.desc.Contains($script:UnloadLabel))) -and
        $_.top -ge $target.top -and $_.top -le ($target.top + 900)
    } | Sort-Object top | Select-Object -First 1
    if (-not $unload) {
        [void](Tap-UiText -Text $ChatModel -Tag 'unload-target-row' -Required:$true)
        Start-Sleep -Seconds 1
        $nodes = @(Get-UiNodes -Tag 'unload-search-after-row')
        $unload = $nodes | Where-Object {
            (($_.text -and $_.text.Contains($script:UnloadLabel)) -or ($_.desc -and $_.desc.Contains($script:UnloadLabel))) -and
            $_.top -ge $target.top -and $_.top -le ($target.top + 1200)
        } | Sort-Object top | Select-Object -First 1
    }
    if (-not $unload) {
        return Invoke-UiUnloadLoadViaManagement
    }
    $fg = Test-MainActivityForeground
    if (-not $fg.foreground) { throw 'refusing to tap unload because MainActivity is not foreground' }
    $tap = Invoke-Adb -Arguments @('shell', 'input', 'tap', [string]$unload.cx, [string]$unload.cy) -TimeoutMs 30000
    if ($tap.ExitCode -ne 0) { throw 'adb input tap failed for UI unload' }
    $deadline = (Get-Date).AddSeconds(120)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 3
        $after = Get-ModelsState
        if ($after.ok -and $after.expectedLoaded.Count -eq 0) {
            return [pscustomobject]@{ status = 'PASSED'; before = $before; after = $after }
        }
    }
    throw '/v1/models still reported loaded=true after the production UI unload action'
}

function Invoke-UiLoadUnloadScenario {
    $result = [ordered]@{
        schema = 'mca-rc-ui-load-unload/1.0'
        created_at = Now-Utc
        unload_status = 'NOT_RUN'
        load_status = 'NOT_RUN'
        status = 'FAILED'
        detail = ''
    }
    $dir = [string]$script:Ctx['evidenceDir']
    try {
        $unloaded = Invoke-UiUnloadModel
        $result.unload_status = $unloaded.status
        $loaded = Invoke-UiLoadModel
        $result.load_status = $loaded.status
        $result.status = 'PASSED'
        $result.detail = 'production UI unloaded and reloaded the loaded chat model; /v1/models observed both states'
    }
    catch {
        $result.detail = $_.Exception.Message
        Add-RunError ("UI load/unload scenario: " + $_.Exception.Message)
    }
    $uiDumpFiles = @(Get-ChildItem -LiteralPath $script:UiEvidenceDir -File -ErrorAction SilentlyContinue | ForEach-Object { Get-EvidenceDescriptor -Path $_.FullName -Base ([string]$script:Ctx['outDir']) })
    $result['ui_dump_files'] = $uiDumpFiles
    $path = Join-Path $dir 'ui-load-unload.json'
    Write-JsonFile -Path $path -Value $result
    return [pscustomobject]@{ status = $result.status; path = $path; detail = $result.detail; uiDumpFiles = $uiDumpFiles }
}

function Get-IndexesEvenly($Indexes, [int]$Count) {
    $source = @($Indexes)
    if ($Count -le 0 -or $source.Count -eq 0) { return @() }
    if ($Count -ge $source.Count) { return $source }
    $result = [System.Collections.Generic.List[int]]::new()
    $used = [System.Collections.Generic.HashSet[int]]::new()
    for ($i = 0; $i -lt $Count; $i++) {
        $target = [int][Math]::Round((($i + 0.5) * $source.Count) / $Count)
        if ($target -ge $source.Count) { $target = $source.Count - 1 }
        $cursor = $target
        while ($used.Contains([int]$source[$cursor])) {
            $cursor = ($cursor + 1) % $source.Count
        }
        [void]$used.Add([int]$source[$cursor])
        $result.Add([int]$source[$cursor])
    }
    return @($result | Sort-Object)
}

function Find-FreeChatPair($Entries, $ChatIndexes, [double]$Fraction) {
    $source = @($ChatIndexes)
    if ($source.Count -lt 2) { throw 'plan has too few chat slots for an injection pair' }
    $start = [int][Math]::Floor($Fraction * ($source.Count - 1))
    for ($k = 0; $k -lt $source.Count; $k++) {
        $index = [int]$source[($start + $k) % $source.Count]
        if ($index + 1 -ge $Entries.Count) { continue }
        $a = $Entries[$index]
        $b = $Entries[$index + 1]
        if ($a.kind -eq 'chat' -and $b.kind -eq 'chat' -and $a.scenario -eq 'normal' -and $b.scenario -eq 'normal') {
            return @($index, $index + 1)
        }
    }
    throw 'plan has no free consecutive chat pair for an injection'
}

function Find-FreeChatSingle($Entries, $ChatIndexes, [double]$Fraction) {
    $source = @($ChatIndexes)
    $start = [int][Math]::Floor($Fraction * ($source.Count - 1))
    for ($k = 0; $k -lt $source.Count; $k++) {
        $index = [int]$source[($start + $k) % $source.Count]
        if ($Entries[$index].kind -eq 'chat' -and $Entries[$index].scenario -eq 'normal') { return $index }
    }
    throw 'plan has no free chat slot for an injection'
}

function New-CampaignPlan {
    param(
        [Parameter(Mandatory = $true)][int]$Total,
        [Parameter(Mandatory = $true)][string]$CampaignId,
        [bool]$EnableFaults = $true,
        [bool]$NoSchedule = $false
    )
    if ($Total -lt 8) { throw 'campaign requires at least 8 logical requests to build the mandatory matrix' }
    $imageCount = [int][Math]::Floor($Total * 0.05)
    if ($imageCount -lt 3) { $imageCount = 3 }
    if ($imageCount -gt 60) { $imageCount = 60 }
    $workerTarget = [Math]::Min($imageCount, [Math]::Max(3, [int][Math]::Ceiling($Total / 200.0)))
    if ($imageCount -lt $workerTarget) { $imageCount = $workerTarget }
    if ($imageCount -ge ($Total - 7)) { $imageCount = $Total - 7 }
    $chatCount = $Total - $imageCount
    $blocks = [Math]::Min(5, [Math]::Max(1, [int][Math]::Ceiling($imageCount / 10.0)))
    $types = [System.Collections.Generic.List[string]]::new()
    $remainingChat = $chatCount
    $remainingImage = $imageCount
    for ($block = 0; $block -lt $blocks; $block++) {
        $chatTake = if ($block -eq ($blocks - 1)) { $remainingChat } else { [int][Math]::Floor($chatCount / $blocks) }
        $imageTake = if ($block -eq ($blocks - 1)) { $remainingImage } else { [int][Math]::Floor($imageCount / $blocks) }
        for ($i = 0; $i -lt $chatTake; $i++) { $types.Add('chat') }
        for ($i = 0; $i -lt $imageTake; $i++) { $types.Add('image') }
        $remainingChat -= $chatTake
        $remainingImage -= $imageTake
    }
    for ($i = 0; $i -lt $remainingChat; $i++) { $types.Add('chat') }
    for ($i = 0; $i -lt $remainingImage; $i++) { $types.Add('image') }
    if ($types.Count -ne $Total) { throw "plan type expansion mismatch: $($types.Count) != $Total" }

    $entries = [System.Collections.Generic.List[object]]::new()
    for ($i = 0; $i -lt $types.Count; $i++) {
        $seq = $i + 1
        $kind = $types[$i]
        $entries.Add([pscustomobject]@{
            seq = $seq
            requestId = ('{0}-{1:D6}' -f $CampaignId, $seq)
            kind = $kind
            stream = $false
            scenario = if ($kind -eq 'image') { 'load_unload' } else { 'normal' }
            retryOf = ''
            counted = $true
            offsetSeconds = 0
        })
    }

    $chatIndexes = @()
    $imageIndexes = @()
    for ($i = 0; $i -lt $entries.Count; $i++) {
        if ($entries[$i].kind -eq 'chat') { $chatIndexes += $i } else { $imageIndexes += $i }
    }

    $cancelPairs = 0
    $disconnectPairs = 0
    $mainTarget = 0
    $backgroundTarget = 0
    if ($EnableFaults) {
        $workerIndexes = Get-IndexesEvenly -Indexes $imageIndexes -Count $workerTarget
        foreach ($idx in $workerIndexes) { $entries[$idx].scenario = 'worker_death' }

        $cancelPairs = [Math]::Min([int][Math]::Floor($chatIndexes.Count / 10), [Math]::Max(2, [int][Math]::Ceiling($Total / 100.0)))
        $disconnectPairs = [Math]::Max(1, [int][Math]::Ceiling($Total / 500.0))
        $mainTarget = [Math]::Max(1, [int][Math]::Ceiling($Total / 500.0))
        $backgroundTarget = [Math]::Max(2, [int][Math]::Ceiling($Total / 200.0))

        for ($i = 0; $i -lt $cancelPairs; $i++) {
            $fraction = ($i + 1.0) / ($cancelPairs + 1.0)
            $pair = Find-FreeChatPair -Entries $entries -ChatIndexes $chatIndexes -Fraction $fraction
            $entries[$pair[0]].scenario = 'cancel'
            $entries[$pair[1]].scenario = 'retry'
            $entries[$pair[1]].retryOf = $entries[$pair[0]].requestId
        }
        for ($i = 0; $i -lt $disconnectPairs; $i++) {
            $fraction = 0.15 + (0.62 * ($i + 1.0) / ($disconnectPairs + 1.0))
            $pair = Find-FreeChatPair -Entries $entries -ChatIndexes $chatIndexes -Fraction $fraction
            $entries[$pair[0]].scenario = 'disconnect'
            $entries[$pair[1]].scenario = 'retry'
            $entries[$pair[1]].retryOf = $entries[$pair[0]].requestId
        }
        for ($i = 0; $i -lt $mainTarget; $i++) {
            $fraction = 0.32 + (0.43 * $i / [Math]::Max(1, $mainTarget - 1))
            $idx = Find-FreeChatSingle -Entries $entries -ChatIndexes $chatIndexes -Fraction $fraction
            $entries[$idx].scenario = 'main_process_kill'
        }
        for ($i = 0; $i -lt $backgroundTarget; $i++) {
            $fraction = 0.08 + (0.84 * ($i + 1.0) / ($backgroundTarget + 1.0))
            $idx = Find-FreeChatSingle -Entries $entries -ChatIndexes $chatIndexes -Fraction $fraction
            $entries[$idx].scenario = 'background_foreground'
        }
        for ($i = 0; $i -lt $entries.Count; $i++) {
            if ($entries[$i].kind -eq 'chat' -and $entries[$i].scenario -eq 'normal') {
                $entries[$i].stream = (($entries[$i].seq % 3) -ne 0)
            }
            elseif ($entries[$i].kind -eq 'chat' -and $entries[$i].scenario -eq 'cancel') {
                $entries[$i].stream = (($i % 2) -eq 0)
            }
            elseif ($entries[$i].kind -eq 'chat' -and $entries[$i].scenario -eq 'retry') {
                if ($i -gt 0) { $entries[$i].stream = $entries[$i - 1].stream }
            }
            elseif ($entries[$i].kind -eq 'chat' -and $entries[$i].scenario -in @('disconnect', 'main_process_kill', 'background_foreground')) {
                $entries[$i].stream = $true
            }
        }
    }
    else {
        for ($i = 0; $i -lt $entries.Count; $i++) {
            if ($entries[$i].kind -eq 'chat') { $entries[$i].stream = (($entries[$i].seq % 2) -eq 0) }
        }
    }

    $cancelOrdinal = 0
    for ($i = 0; $i -lt $entries.Count; $i++) {
        if ($entries[$i].scenario -eq 'cancel') {
            $entries[$i].stream = (($cancelOrdinal % 2) -eq 0)
            if (($i + 1) -lt $entries.Count -and $entries[$i + 1].scenario -eq 'retry') {
                $entries[$i + 1].stream = $entries[$i].stream
            }
            $cancelOrdinal++
        }
    }
    $totalSeconds = $Hours * 3600.0
    for ($i = 0; $i -lt $entries.Count; $i++) {
        if ($NoSchedule) {
            $entries[$i].offsetSeconds = 0
        }
        else {
            $entries[$i].offsetSeconds = [int64][Math]::Floor(($entries[$i].seq - 1) * $totalSeconds * 0.90 / [Math]::Max(1, $entries.Count - 1))
        }
    }

    $streamCount = @($entries | Where-Object { $_.stream }).Count
    $nonstreamCount = $entries.Count - $streamCount
    if (-not $EnableFaults) {
        $cancelPairs = 0
        $disconnectPairs = 0
        $mainTarget = 0
        $backgroundTarget = 0
        $workerTarget = 0
    }
    $counts = [ordered]@{
        total = $entries.Count
        chat = $chatCount
        image = $imageCount
        stream = $streamCount
        nonstream = $nonstreamCount
        cancel = $cancelPairs
        retry = $cancelPairs + $disconnectPairs
        disconnect = $disconnectPairs
        worker_death = $workerTarget
        restart = $mainTarget
        background_foreground = $backgroundTarget
        load_unload = $imageCount
        worker_death_target = $workerTarget
    }
    return [pscustomobject]@{
        entries = @($entries)
        counts = $counts
        imageCount = $imageCount
        chatCount = $chatCount
    }
}

function New-WarmupPlan([string]$CampaignId, [int]$Count) {
    $entries = [System.Collections.Generic.List[object]]::new()
    for ($i = 1; $i -le $Count; $i++) {
        $entries.Add([pscustomobject]@{
            seq = $i
            requestId = ('{0}-warmup-{1:D4}' -f $CampaignId, $i)
            kind = 'chat'
            stream = (($i % 2) -eq 0)
            scenario = 'warmup'
            retryOf = ''
            counted = $false
            offsetSeconds = 0
        })
    }
    return [pscustomobject]@{ entries = @($entries); counts = [ordered]@{ total = $Count } }
}

function New-ControlPlan([string]$CampaignId, [string]$Suffix, [string]$Kind = 'chat') {
    $safeSuffix = ($Suffix -replace '[^A-Za-z0-9._:-]', '-')
    return [pscustomobject]@{
        entries = @([pscustomobject]@{
            seq = 900001
            requestId = ('{0}-control-{1}' -f $CampaignId, $safeSuffix)
            kind = $Kind
            stream = $false
            scenario = 'normal'
            retryOf = ''
            counted = $false
            offsetSeconds = 0
        })
        counts = [ordered]@{ total = 1 }
    }
}

function Write-PlanFile([string]$Path, $Plan) {
    $lines = [System.Collections.Generic.List[string]]::new()
    foreach ($entry in @($Plan.entries)) {
        $retry = if ([string]::IsNullOrWhiteSpace([string]$entry.retryOf)) { '-' } else { [string]$entry.retryOf }
        $counted = if ($entry.counted) { '1' } else { '0' }
        $stream = if ($entry.stream) { '1' } else { '0' }
        $lines.Add(('{0}|{1}|{2}|{3}|{4}|{5}|{6}' -f $entry.seq, $entry.requestId, $entry.kind, $stream, $entry.scenario, $retry, $counted))
    }
    Write-Utf8Lf -Path $Path -Text (($lines -join "`n") + "`n")
}

function New-PlanSegments($Plan) {
    $entries = @($Plan.entries)
    $segments = [System.Collections.Generic.List[object]]::new()
    $current = [System.Collections.Generic.List[object]]::new()
    $currentKind = ''
    $flush = {
        if ($current.Count -eq 0) { return }
        $name = ('s{0:D3}-{1}' -f ($segments.Count + 1), $currentKind)
        $segments.Add([pscustomobject]@{
            index = $segments.Count + 1
            name = $name
            kind = $currentKind
            entries = @($current)
            firstOffsetSeconds = [int64]$current[0].offsetSeconds
        })
        $current.Clear()
    }
    foreach ($entry in $entries) {
        if ($entry.scenario -eq 'main_process_kill') {
            & $flush
            $current.Add($entry)
            $currentKind = $entry.kind
            & $flush
            $currentKind = ''
            continue
        }
        if ($current.Count -gt 0) {
            $limit = if ($currentKind -eq 'chat') { 24 } else { 10 }
            $wouldSplitRetry = $entry.scenario -eq 'retry' -and $current[$current.Count - 1].scenario -eq 'cancel'
            if ($entry.kind -ne $currentKind -or ($current.Count -ge $limit -and -not $wouldSplitRetry)) {
                & $flush
            }
        }
        if ($current.Count -eq 0) { $currentKind = $entry.kind }
        $current.Add($entry)
    }
    & $flush
    return @($segments)
}

function Get-SegmentTimeoutSeconds($Segment) {
    $sum = 120
    foreach ($entry in @($Segment.entries)) {
        switch ($entry.scenario) {
            'worker_death' { $sum += 1200 }
            'main_process_kill' { $sum += 420 }
            'cancel' { $sum += 300 }
            'disconnect' { $sum += 240 }
            'background_foreground' { $sum += 420 }
            default { if ($entry.kind -eq 'image') { $sum += 960 } else { $sum += 330 } }
        }
    }
    return [Math]::Min(7200, [Math]::Max(600, $sum))
}

function Ensure-RemoteLayout {
    $r = Invoke-AdbShell -Command "mkdir -p '$($script:RemoteRoot)/logs' '$($script:RemoteRoot)/tmp' '$($script:RemoteRoot)/receipts' '$($script:RemoteRoot)/plans'" -TimeoutMs 60000
    if ($r.ExitCode -ne 0) { throw "device mkdir failed: $($r.StdErr)" }
}

function Quote-DeviceShellArg([string]$Value) {
    if ($null -eq $Value) { return "''" }
    return "'" + $Value.Replace("'", "'\''") + "'"
}
function Push-DeviceFile([string]$LocalPath, [string]$RemotePath) {
    $r = Invoke-Adb -Arguments @('push', $LocalPath, $RemotePath) -TimeoutMs 300000
    if ($r.ExitCode -ne 0) { throw "adb push failed for ${LocalPath}: $($r.StdErr)" }
}

function Initialize-DeviceScript {
    Ensure-RemoteLayout
    Push-DeviceFile -LocalPath $script:DeviceScriptPath -RemotePath $script:RemoteScript
    $chmod = Invoke-Adb -Arguments @('shell', 'chmod', '700', $script:RemoteScript) -TimeoutMs 30000
    if ($chmod.ExitCode -ne 0) { throw "chmod device script failed: $($chmod.StdErr)" }
    [void](Invoke-AdbShell -Command "rm -f '$($script:RemoteRoot)/logs/requests.ndjson' '$($script:RemoteRoot)/logs/auth-check.ndjson' '$($script:RemoteRoot)/STOP_MONITOR'" -TimeoutMs 30000)
}

function Invoke-DeviceWorkload {
    param(
        [Parameter(Mandatory = $true)]$Plan,
        [Parameter(Mandatory = $true)][string]$Name,
        [switch]$NoRemoteSetup,
        [int]$TimeoutSeconds = 0
    )
    $localPlan = Join-Path ([string]$script:Ctx['plansDir']) ("$Name.plan")
    Write-PlanFile -Path $localPlan -Plan $Plan
    $remoteName = ($Name -replace '[^A-Za-z0-9._-]', '-')
    $remotePlan = "$($script:RemoteRoot)/plans/$remoteName.plan"
    if (-not $NoRemoteSetup) { Ensure-RemoteLayout }
    Push-DeviceFile -LocalPath $localPlan -RemotePath $remotePlan
    $stdin = if ($script:AuthMode -eq 'caller') { $script:ApiKey + "`n" } else { $null }
    $args = @('-s', $script:Serial, 'shell', 'sh', (Quote-DeviceShellArg $script:RemoteScript), 'workload', (Quote-DeviceShellArg $script:RemoteRoot), (Quote-DeviceShellArg $Package), (Quote-DeviceShellArg $ChatModel), (Quote-DeviceShellArg $ImageModel), (Quote-DeviceShellArg $script:AuthMode), (Quote-DeviceShellArg $remotePlan))
    $timeout = if ($TimeoutSeconds -gt 0) { $TimeoutSeconds } elseif ($Plan.entries.Count -eq 1) { 600 } else { Get-SegmentTimeoutSeconds -Segment ([pscustomobject]@{ entries = @($Plan.entries) }) }
    $result = Invoke-NativeProcess -FilePath $script:AdbPath -Arguments $args -StdIn $stdin -TimeoutMs ($timeout * 1000)
    $logPath = Join-Path ([string]$script:Ctx['segmentDir']) ("$remoteName.log")
    Write-Utf8Lf -Path $logPath -Text (($result.StdOut + "`n" + $result.StdErr).TrimEnd() + "`n")
    if ($result.TimedOut) { Add-RunError "device workload timed out: $Name" }
    elseif ($result.ExitCode -ne 0) { Add-RunError "device workload exited $($result.ExitCode): $Name" }
    return $result
}

function Invoke-AuthCheck {
    $stdin = if ($script:AuthMode -eq 'caller') { $script:ApiKey + "`n" } else { $null }
    $args = @('-s', $script:Serial, 'shell', 'sh', (Quote-DeviceShellArg $script:RemoteScript), 'authcheck', (Quote-DeviceShellArg $script:RemoteRoot), (Quote-DeviceShellArg $Package), (Quote-DeviceShellArg $ChatModel), (Quote-DeviceShellArg $ImageModel), (Quote-DeviceShellArg $script:AuthMode))
    $result = Invoke-NativeProcess -FilePath $script:AdbPath -Arguments $args -StdIn $stdin -TimeoutMs 180000
    $path = Join-Path ([string]$script:Ctx['segmentDir']) 'authcheck.log'
    Write-Utf8Lf -Path $path -Text (($result.StdOut + "`n" + $result.StdErr).TrimEnd() + "`n")
    if ($result.ExitCode -ne 0) { Add-RunError "authenticated Local API control failed: $($result.StdErr)" }
    return $result
}

function Start-DeviceMonitor {
    $stdout = Join-Path ([string]$script:Ctx['evidenceDir']) 'monitor-console.log'
    $stderr = Join-Path ([string]$script:Ctx['evidenceDir']) 'monitor-console.stderr.log'
    $safety = [int64][Math]::Ceiling($Hours * 3600.0 + 7200)
    $args = @('-s', $script:Serial, 'shell', 'sh', $script:RemoteScript, 'monitor', $script:RemoteRoot, $Package, [string]$SampleIntervalSeconds, [string]$safety)
    return Start-NativeBackground -FilePath $script:AdbPath -Arguments $args -StdOutPath $stdout -StdErrPath $stderr
}

function Stop-DeviceMonitor($Handle) {
    if ($null -eq $Handle) { return [pscustomobject]@{ TimedOut = $true; ExitCode = 124 } }
    [void](Invoke-AdbShell -Command "touch '$($script:RemoteRoot)/STOP_MONITOR'" -TimeoutMs 30000)
    return Complete-NativeBackground -Handle $Handle -TimeoutMs 240000
}

function Wait-UntilTargetTime([datetimeoffset]$StartTime, [int64]$OffsetSeconds) {
    $target = $StartTime.AddSeconds($OffsetSeconds)
    while ([datetimeoffset]::UtcNow -lt $target) {
        $remaining = ($target - [datetimeoffset]::UtcNow).TotalSeconds
        if ($remaining -le 0) { break }
        $sleepSeconds = [int][Math]::Min(30, [Math]::Ceiling($remaining))
        Start-Sleep -Seconds $sleepSeconds
    }
}

function Get-CrashAndAnrCounts {
    $monitorParsed = Read-Ndjson (Join-Path $script:LocalRawDir 'logs/samples.ndjson')
    $monitorMeta = @($monitorParsed.Records | Where-Object { $_.type -eq 'monitor_meta' } | Select-Object -Last 1)
    $monitorEnd = @($monitorParsed.Records | Where-Object { $_.type -eq 'monitor_end' } | Select-Object -Last 1)
    if ($monitorMeta.Count -eq 0) {
        Add-RunError 'device monitor metadata is missing; crash/ANR capture cannot be verified'
    }
    elseif ($monitorMeta[0].crash_logcat_alive -ne $true -or $monitorMeta[0].anr_logcat_alive -ne $true -or $monitorMeta[0].runtime_logcat_alive -ne $true) {
        Add-RunError 'one or more device logcat capture processes were not alive; crash/ANR evidence is incomplete'
    }
    if ($monitorEnd.Count -eq 0) { Add-RunError 'device monitor end marker is missing' }
    elseif ($script:Ctx.Contains('endedAt') -and [int64]$monitorEnd[0].ended_epoch -lt ($script:Ctx['endedAt'].ToUnixTimeSeconds() - 120)) { Add-RunError 'device monitor stopped before the formal observation ended' }
    elseif ($monitorEnd[0].crash_logcat_alive -ne $true -or $monitorEnd[0].anr_logcat_alive -ne $true -or $monitorEnd[0].runtime_logcat_alive -ne $true) { Add-RunError 'one or more logcat capture processes stopped during the campaign' }
    $crashFiles = @(
        (Join-Path $script:LocalRawDir 'logs/crash-buffer.log'),
        (Join-Path $script:LocalRawDir 'logs/runtime-errors.log')
    ) | Where-Object { Test-Path -LiteralPath $_ }
    $crashText = ''
    foreach ($file in $crashFiles) { $crashText += "`n" + [IO.File]::ReadAllText($file, [Text.Encoding]::UTF8) }
    $packageToken = [regex]::Escape("Process: $Package")
    $crashMatches = [regex]::Matches($crashText, "FATAL EXCEPTION[\s\S]{0,5000}?$packageToken", [Text.RegularExpressions.RegexOptions]::IgnoreCase)
    $anrText = ''
    foreach ($file in @((Join-Path $script:LocalRawDir 'logs/anr-events.log'), (Join-Path $script:LocalRawDir 'logs/runtime-errors.log'))) {
        if (Test-Path -LiteralPath $file) { $anrText += "`n" + [IO.File]::ReadAllText($file, [Text.Encoding]::UTF8) }
    }
    $anrPackage = [regex]::Escape($Package)
    $anrMatches = [regex]::Matches($anrText, "(?im)^.*(?:ANR in|am_anr).*$anrPackage.*$", [Text.RegularExpressions.RegexOptions]::IgnoreCase)
    return [pscustomobject]@{ crash = $crashMatches.Count; anr = $anrMatches.Count; crashText = $crashText; anrText = $anrText }
}
function Initialize-RunContext {
    if ([string]::IsNullOrWhiteSpace($OutDir)) { throw '-OutDir is required for a real campaign' }
    if ([string]::IsNullOrWhiteSpace($ApkPath)) { throw '-ApkPath is required for a real campaign' }
    if ([string]::IsNullOrWhiteSpace($Serial)) { throw '-Serial is required for a real campaign' }
    $fullOut = [IO.Path]::GetFullPath($OutDir)
    if (Test-Path -LiteralPath $fullOut) {
        $existing = @(Get-ChildItem -LiteralPath $fullOut -Force)
        if ($existing.Count -gt 0) { throw "OutDir is not empty; use a fresh evidence directory: $fullOut" }
    }
    [IO.Directory]::CreateDirectory($fullOut) | Out-Null
    $script:Ctx['outDir'] = $fullOut
    $evidenceDir = Join-Path $fullOut 'evidence'
    $faultEvidenceDir = Join-Path $evidenceDir 'fault'
    $plansDir = Join-Path $fullOut 'plans'
    $segmentDir = Join-Path $evidenceDir 'segments'
    foreach ($dir in @($evidenceDir, $faultEvidenceDir, $plansDir, $segmentDir)) {
        [IO.Directory]::CreateDirectory($dir) | Out-Null
    }
    $apkItem = Get-Item -LiteralPath $ApkPath
    if (-not $apkItem.PSIsContainer -and $apkItem.Extension -ieq '.apk') {
        $hostSha = Get-FileSha256 $apkItem.FullName
    }
    else {
        throw "ApkPath is not an APK file: $ApkPath"
    }
    $stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ', [Globalization.CultureInfo]::InvariantCulture)
    $campaignId = "rc-$stamp-$($hostSha.Substring(0, 8))-$([guid]::NewGuid().ToString('N').Substring(0, 4))"
    $script:RemoteRoot = "/data/local/tmp/mca-rc-campaign/$campaignId"
    $script:RemoteScript = "$($script:RemoteRoot)/rc-campaign.sh"
    $script:UiEvidenceDir = Join-Path $evidenceDir 'ui'
    [IO.Directory]::CreateDirectory($script:UiEvidenceDir) | Out-Null
    $script:SegmentDir = $segmentDir
    $script:Ctx['campaignId'] = $campaignId
    $script:Ctx['outDir'] = $fullOut
    $script:Ctx['evidenceDir'] = $evidenceDir
    $script:Ctx['faultEvidenceDir'] = $faultEvidenceDir
    $script:Ctx['plansDir'] = $plansDir
    $script:Ctx['segmentDir'] = $segmentDir
    $script:Ctx['apkPath'] = $apkItem.FullName
    $script:Ctx['apkSha256'] = $hostSha
    $script:Ctx['apkSizeBytes'] = [int64]$apkItem.Length
    $script:Ctx['serial'] = $Serial
    $script:Ctx['startedBuildingAt'] = Now-Utc
    $script:Ctx['errors'] = $script:RunErrors
    $script:Ctx['warnings'] = $script:RunWarnings
    $script:Ctx['uiScenario'] = $null
    $script:Ctx['mainKillRecoveries'] = [System.Collections.Generic.List[object]]::new()
    $script:Ctx['apkGuardChecks'] = [System.Collections.Generic.List[object]]::new()
}

function Get-ApiKeyForMode([string]$Mode) {
    if ($Mode -eq 'debug') { return '' }
    $value = [Environment]::GetEnvironmentVariable($ApiKeyEnvVar)
    if ([string]::IsNullOrEmpty($value)) {
        throw "Release APK requires the API key in environment variable '$ApiKeyEnvVar' (or use -ApiKeyEnvVar). It is never accepted as a command line value."
    }
    if ($value.Contains("`n") -or $value.Contains("`r") -or $value.Contains([string][char]0)) {
        throw "API key environment variable '$ApiKeyEnvVar' contains a newline or NUL"
    }
    if ($value.Length -lt 8 -or $value.Length -gt 4096) {
        throw "API key environment variable '$ApiKeyEnvVar' has an unsafe length"
    }
    if ($value -notmatch '^[A-Za-z0-9._~+/=:-]+$') {
        throw "API key contains characters outside the supported bearer-token alphabet"
    }
    return $value
}

function Invoke-Preflight {
    $deviceListing = Assert-AdbDevice
    $hostApk = Get-Item -LiteralPath ([string]$script:Ctx['apkPath'])
    $hostSha = Get-FileSha256 $hostApk.FullName
    if ($hostSha -ne [string]$script:Ctx['apkSha256']) {
        throw 'host APK changed after the campaign directory was created'
    }

    $pkgList = Invoke-Adb -Arguments @('shell', 'pm', 'list', 'packages', '--user', '0', $Package) -TimeoutMs 60000
    $installed = ($pkgList.StdOut -split "`n") | Where-Object { $_.Trim() -eq "package:$Package" }
    if (-not $installed) { throw "package is not installed for user 0: $Package" }

    $base = Get-InstalledBaseApkFacts
    if ($base.sha256 -ne $hostSha -or $base.bytes -ne [int64]$hostApk.Length) {
        throw "installed base.apk is not the supplied RC APK: device sha=$($base.sha256) bytes=$($base.bytes), host sha=$hostSha bytes=$($hostApk.Length)"
    }

    $health = Get-HealthState
    if (-not $health.ok) {
        throw "Local API /health is not healthy before the campaign: exit=$($health.exit) status='$($health.status)' error='$($health.error)'"
    }
    $models = Get-ModelsState
    if (-not $models.ok) {
        throw "GET /v1/models failed before the campaign: $($models.error)"
    }
    $imageModelPresent = @($models.entries | Where-Object {
        $_.PSObject.Properties['id'] -and [string]$_.id -eq $ImageModel
    }).Count -gt 0
    if (-not $imageModelPresent) { throw "image model '$ImageModel' is absent from the /v1/models catalog" }
    if ($models.expectedLoaded.Count -lt 1) {
        throw "expected chat model '$ChatModel' is not present with loaded=true in /v1/models"
    }

    $manufacturer = Get-DeviceProperty 'ro.product.manufacturer'
    $model = Get-DeviceProperty 'ro.product.model'
    $abi = Get-DeviceProperty 'ro.product.cpu.abi'
    $supportedAbis = @((Get-DeviceProperty 'ro.product.cpu.abilist').Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    $qemu = Get-DeviceProperty 'ro.kernel.qemu'
    if ([string]::IsNullOrWhiteSpace($manufacturer) -or [string]::IsNullOrWhiteSpace($model)) { throw 'device manufacturer/model properties are empty' }
    $physical = ($qemu -ne '1' -and $supportedAbis -contains 'arm64-v8a' -and $Serial -notmatch '^(emulator|localhost)')
    if (-not $physical) {
        throw "campaign requires a representative physical arm64-v8a device; qemu='$qemu' abis='$($supportedAbis -join ',')' serial='$Serial'"
    }

    $runAs = Invoke-Adb -Arguments @('shell', 'run-as', $Package, 'id') -TimeoutMs 30000
    $authMode = if ($runAs.ExitCode -eq 0) { 'debug' } else { 'caller' }
    $apiKey = if ($authMode -eq 'caller') { Get-ApiKeyForMode 'caller' } else { '' }
    $script:AuthMode = $authMode
    $script:ApiKey = $apiKey

    $ui = Invoke-UiSmoke
    if ($ui.status -ne 'PASSED') { throw "MainActivity UI smoke failed: foreground='$($ui.foreground)' nodeCount=$($ui.nodeCount)" }

    $preflight = [ordered]@{
        schema = 'mca-rc-preflight/1.0'
        campaign_id = [string]$script:Ctx['campaignId']
        checked_at = Now-Utc
        adb_devices = $deviceListing
        serial = $Serial
        package = $Package
        package_dump = (Invoke-Adb -Arguments @('shell', 'dumpsys', 'package', $Package) -TimeoutMs 60000).StdOut
        host_apk_path = $hostApk.FullName
        host_apk_sha256 = $hostSha
        host_apk_bytes = [int64]$hostApk.Length
        device_base_apk_path = $base.path
        device_base_apk_sha256 = $base.sha256
        device_base_apk_bytes = $base.bytes
        health = [ordered]@{ status = $health.status; name = $health.name; ok = $health.ok }
        models_loaded_id = @($models.expectedLoaded | ForEach-Object { [string]$_.id })
        models_loaded_name = @($models.expectedLoaded | ForEach-Object {
            if ($_.PSObject.Properties['name']) { [string]$_.name } elseif ($_.PSObject.Properties['displayName']) { [string]$_.displayName } else { '' }
        })
        auth_mode = $authMode
        physical = $physical
        abi = $abi
        supported_abis = $supportedAbis
        manufacturer = $manufacturer
        model = $model
        main_activity_ui = $ui.status
    }
    $preflightPath = Join-Path ([string]$script:Ctx['evidenceDir']) 'preflight.json'
    Write-JsonFile -Path $preflightPath -Value $preflight
    $modelsPath = Join-Path ([string]$script:Ctx['evidenceDir']) 'preflight-models.json'
    Write-Utf8Lf -Path $modelsPath -Text ($models.raw + "`n")

    $script:Ctx['preflight'] = [pscustomobject]@{
        deviceListing = $deviceListing
        hostApkPath = $hostApk.FullName
        hostApkSha256 = $hostSha
        hostApkBytes = [int64]$hostApk.Length
        base = $base
        health = $health
        models = $models
        manufacturer = $manufacturer
        model = $model
        abi = $abi
        supportedAbis = $supportedAbis
        physical = $physical
        ui = $ui
        authMode = $authMode
        preflightPath = $preflightPath
    }
    return $script:Ctx['preflight']
}

function Test-ApkBindingUnchanged([string]$Reason) {
    $base = Get-InstalledBaseApkFacts
    $hostItem = Get-Item -LiteralPath ([string]$script:Ctx['apkPath'])
    $hostSha = Get-FileSha256 $hostItem.FullName
    $ok = $base.sha256 -eq [string]$script:Ctx['apkSha256'] -and $hostSha -eq [string]$script:Ctx['apkSha256'] -and $base.bytes -eq [int64]$script:Ctx['apkSizeBytes']
    $check = [ordered]@{
        checked_at = Now-Utc
        reason = $Reason
        ok = $ok
        device_sha256 = $base.sha256
        device_bytes = $base.bytes
        host_sha256 = $hostSha
        expected_sha256 = [string]$script:Ctx['apkSha256']
    }
    $script:Ctx['apkGuardChecks'].Add($check)
    if (-not $ok) { Add-RunError "APK binding changed during campaign: $Reason" }
    return $ok
}

function Receive-DeviceArtifacts {
    $dest = Join-Path ([string]$script:Ctx['outDir']) 'raw'
    if (Test-Path -LiteralPath $dest) { throw "raw evidence destination already exists: $dest" }
    $r = Invoke-Adb -Arguments @('pull', $script:RemoteRoot, $dest) -TimeoutMs 1800000
    if ($r.ExitCode -ne 0) {
        Add-RunError "adb pull of device evidence failed: $($r.StdErr)"
        [IO.Directory]::CreateDirectory($dest) | Out-Null
    }
    $script:LocalRawDir = $dest
    $script:Ctx['localRawDir'] = $dest
    Write-Utf8Lf -Path (Join-Path ([string]$script:Ctx['evidenceDir']) 'adb-pull.log') -Text (($r.StdOut + "`n" + $r.StdErr).TrimEnd() + "`n")
}

function Test-ApiKeyLeak {
    if ([string]::IsNullOrEmpty($script:ApiKey)) { return $true }
    $roots = @()
    if ($script:LocalRawDir) { $roots += $script:LocalRawDir }
    if ($script:Ctx.Contains('evidenceDir')) { $roots += [string]$script:Ctx['evidenceDir'] }
    $found = [System.Collections.Generic.List[string]]::new()
    foreach ($root in $roots) {
        if (-not (Test-Path -LiteralPath $root)) { continue }
        foreach ($file in @(Get-ChildItem -LiteralPath $root -Recurse -File -ErrorAction SilentlyContinue)) {
            if ($file.Length -gt 67108864) { continue }
            if ($file.Extension -notin @('.log','.ndjson','.json','.txt','.xml','.err','.out','.response')) { continue }
            try {
                $text = [IO.File]::ReadAllText($file.FullName, [Text.Encoding]::UTF8)
                if ($text.Contains($script:ApiKey, [StringComparison]::Ordinal)) {
                    $found.Add((Get-RelativeSlashPath -Base ([string]$script:Ctx['outDir']) -Path $file.FullName))
                }
            }
            catch { }
        }
    }
    if ($found.Count -gt 0) {
        Add-RunError ("API key was found in evidence files: " + (($found | Select-Object -First 5) -join ', '))
        return $false
    }
    return $true
}

function Get-EventStatistics {
    $path = Join-Path $script:LocalRawDir 'logs/requests.ndjson'
    $parsed = Read-Ndjson $path
    foreach ($errorItem in $parsed.Errors) { Add-RunError "requests NDJSON: $errorItem" }
    $records = @($parsed.Records | Where-Object { $_.schema -eq 'mca-rc-request-event/1.0' })
    $counted = @($records | Where-Object { $_.counts_toward_workload -eq $true })
    $warmups = @($records | Where-Object { $_.counts_toward_workload -ne $true -and $_.scenario -eq 'warmup' })
    $ids = @($counted | ForEach-Object { [string]$_.request_id })
    $unique = @($ids | Select-Object -Unique)
    $success = @($counted | Where-Object { $_.outcome -eq 'success' }).Count
    $failure = @($counted | Where-Object { $_.outcome -eq 'failure' }).Count
    $cancel = @($counted | Where-Object { $_.outcome -eq 'cancel' }).Count
    $scenarioGroups = @{}
    foreach ($record in $counted) {
        $name = [string]$record.scenario
        if (-not $scenarioGroups.ContainsKey($name)) { $scenarioGroups[$name] = [System.Collections.Generic.List[object]]::new() }
        $scenarioGroups[$name].Add($record)
    }
    return [pscustomobject]@{
        all = $records
        counted = $counted
        warmups = $warmups
        ids = $ids
        uniqueIds = $unique
        duplicateIds = @($ids | Group-Object | Where-Object { $_.Count -gt 1 } | ForEach-Object { $_.Name })
        success = $success
        failure = $failure
        cancel = $cancel
        groups = $scenarioGroups
        path = $path
    }
}

function Test-PlannedRequestExactness($Plan, $Stats) {
    $planned = @($Plan.entries | ForEach-Object { [string]$_.requestId })
    $observed = @($Stats.ids)
    $missing = @($planned | Where-Object { $observed -notcontains $_ })
    $extra = @($observed | Where-Object { $planned -notcontains $_ })
    if ($missing.Count -gt 0) { Add-RunError "logical request IDs missing from raw evidence: $($missing.Count)" }
    if ($extra.Count -gt 0) { Add-RunError "unplanned logical request IDs present: $($extra.Count)" }
    if ($Stats.duplicateIds.Count -gt 0) { Add-RunError "duplicate logical request IDs: $($Stats.duplicateIds -join ', ')" }
    if ($Stats.ids.Count -ne $planned.Count -or $Stats.uniqueIds.Count -ne $planned.Count) {
        Add-RunError "logical request count mismatch: planned=$($planned.Count) observed=$($Stats.ids.Count) unique=$($Stats.uniqueIds.Count)"
    }
    $idPattern = '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
    $seenIds = [System.Collections.Generic.HashSet[string]]::new()
    foreach ($record in @($Stats.counted)) {
        $recordId = [string]$record.request_id
        if ($recordId -notmatch $idPattern) { Add-RunError "logical request ID violates the Local API pattern: $recordId" }
        if ($record.PSObject.Properties['retry_of'] -and -not [string]::IsNullOrWhiteSpace([string]$record.retry_of)) {
            if (-not $seenIds.Contains([string]$record.retry_of)) {
                Add-RunError "retry references a request that was not previously attempted: id=$recordId retry_of=$($record.retry_of)"
            }
        }
        [void]$seenIds.Add($recordId)
    }
    foreach ($record in @($Stats.counted)) {
        $scenario = [string]$record.scenario
        $outcome = [string]$record.outcome
        $scenarioResult = [string]$record.scenario_result
        if ($scenario -in @('normal', 'retry', 'load_unload', 'background_foreground')) {
            if ($outcome -ne 'success' -or $scenarioResult -ne 'PASSED') {
                Add-RunError "unexplained request failure: id=$($record.request_id) scenario=$scenario outcome=$outcome code=$($record.error_code)"
            }
        }
        elseif ($scenario -eq 'cancel') {
            if ($outcome -ne 'cancel' -or $scenarioResult -ne 'PASSED' -or $record.stop_accepted -ne 1) {
                Add-RunError "cancel contract failed: id=$($record.request_id) outcome=$outcome result=$scenarioResult stop=$($record.stop_http_status)"
            }
        }
        elseif ($scenario -in @('worker_death', 'main_process_kill', 'disconnect')) {
            if ($outcome -ne 'failure' -or $scenarioResult -ne 'PASSED') {
                Add-RunError "fault injection failed: id=$($record.request_id) scenario=$scenario outcome=$outcome result=$scenarioResult code=$($record.error_code)"
            }
        }
    }
    $warmupExpected = [Math]::Max(0, $Warmup)
    if ($Stats.warmups.Count -ne $warmupExpected) {
        Add-RunError "warmup count mismatch: expected=$warmupExpected observed=$($Stats.warmups.Count)"
    }
    foreach ($record in @($Stats.warmups)) {
        if ($record.outcome -ne 'success') { Add-RunError "warmup request failed: id=$($record.request_id) code=$($record.error_code)" }
    }
    return [pscustomobject]@{ planned = $planned; missing = $missing; extra = $extra }
}

function Get-NormalizedMemorySamples([datetimeoffset]$StartedAt, [datetimeoffset]$EndedAt) {
    $path = Join-Path $script:LocalRawDir 'logs/samples.ndjson'
    $parsed = Read-Ndjson $path
    foreach ($errorItem in $parsed.Errors) { Add-RunError "samples NDJSON: $errorItem" }
    $result = [System.Collections.Generic.List[object]]::new()
    foreach ($record in @($parsed.Records | Where-Object { $_.type -eq 'memory_sample' })) {
        if ($null -eq $record.PSObject.Properties['timestamp']) { Add-RunError 'memory sample lacks timestamp'; continue }
        try { $ts = [datetimeoffset]::Parse([string]$record.timestamp, [Globalization.CultureInfo]::InvariantCulture) } catch { Add-RunError 'memory sample timestamp is not RFC3339'; continue }
        if ($ts -lt $StartedAt -or $ts -gt $EndedAt) { continue }
        $processes = @($record.processes)
        if ($processes.Count -eq 0) { Add-RunError "memory sample has no processes: $($record.timestamp)"; continue }
        foreach ($proc in $processes) {
            if (-not $proc.PSObject.Properties['pid'] -or [int64]$proc.pid -le 0 -or -not $proc.PSObject.Properties['pss_bytes'] -or [int64]$proc.pss_bytes -le 0) {
                Add-RunError "memory sample process lacks pid/pss: $($record.timestamp)"
            }
        }
        if ([string]$record.quality -ne 'OK') { Add-RunWarning "memory sample quality=$($record.quality) at $($record.timestamp)" }
        $normalized = [ordered]@{
            timestamp = [string]$record.timestamp
            elapsed_seconds = $record.elapsed_seconds
            rss_bytes = $record.rss_bytes
            pss_bytes = $record.pss_bytes
            native_heap_bytes = $record.native_heap_bytes
            fd_count = $record.fd_count
            thread_count = $record.thread_count
            mem_available_bytes = $record.mem_available_bytes
            temperature_c = if ($null -ne $record.PSObject.Properties['temperature_millic']) { [double]$record.temperature_millic / 1000.0 } else { $null }
            quality = $record.quality
            process_presence = $record.process_presence
            processes = $processes
        }
        $result.Add($normalized)
    }
    $unavailable = @($parsed.Records | Where-Object { $_.type -eq 'sample_unavailable' }).Count
    $timedSamples = [System.Collections.Generic.List[datetimeoffset]]::new()
    foreach ($record in @($parsed.Records | Where-Object { $_.type -in @('memory_sample','sample_unavailable','sample_invalid') })) {
        try { $timedSamples.Add([datetimeoffset]::Parse([string]$record.timestamp, [Globalization.CultureInfo]::InvariantCulture)) } catch { }
    }
    $maxSampleGap = 0.0
    $sortedSamples = @($timedSamples | Sort-Object)
    for ($i = 1; $i -lt $sortedSamples.Count; $i++) {
        $gap = ($sortedSamples[$i] - $sortedSamples[$i - 1]).TotalSeconds
        if ($gap -gt $maxSampleGap) { $maxSampleGap = $gap }
    }
    $gapLimit = [Math]::Max(240.0, 4.0 * $SampleIntervalSeconds)
    if ($maxSampleGap -gt $gapLimit) { Add-RunError ("continuous sample gap exceeded: {0:N1}s > {1:N1}s" -f $maxSampleGap, $gapLimit) }
    $invalidSamples = @($parsed.Records | Where-Object { $_.type -eq 'sample_invalid' }).Count
    if ($invalidSamples -gt 0) { Add-RunError "memory samples with present but unreadable process metrics: $invalidSamples" }
    if ($unavailable -gt 0) { Add-RunWarning "samples with no readable package process: $unavailable (expected around force-stop)" }
    if ($result.Count -eq 0) { Add-RunError 'no usable continuous memory samples were captured' }
    return [pscustomobject]@{ samples = @($result); unavailable = $unavailable; maxGapSeconds = [Math]::Round($maxSampleGap, 3) }
}
function Get-ScenarioGroup($Stats, [string]$Name) {
    if ($Stats.groups.ContainsKey($Name)) { return @($Stats.groups[$Name]) }
    return @()
}

function Get-AuthCheckRecord {
    $path = Join-Path $script:LocalRawDir 'logs/auth-check.ndjson'
    $parsed = Read-Ndjson $path
    foreach ($errorItem in $parsed.Errors) { Add-RunError "auth-check NDJSON: $errorItem" }
    if ($parsed.Records.Count -eq 0) { return $null }
    return $parsed.Records[$parsed.Records.Count - 1]
}

function Get-ModelContractSuite {
    $reportPath = if ([IO.Path]::IsPathRooted($ModelFaultReport)) { $ModelFaultReport } else { Join-Path $script:RepoRoot $ModelFaultReport }
    $suite = [ordered]@{
        status = 'FAILED'
        scope = 'CONTRACT_MODEL_ONLY'
        source_path = $reportPath
        source_sha256 = ''
        copied_path = ''
        ids = @()
        error = ''
    }
    if (-not (Test-Path -LiteralPath $reportPath -PathType Leaf)) {
        $suite.error = "model fault contract report not found: $reportPath"
        return $suite
    }
    try {
        $report = Read-StrictJson $reportPath
        $scenarios = @($report.scenarios)
        $verified = @()
        foreach ($scenario in $scenarios) {
            if ([string]$scenario.status -eq 'VERIFIED') { $verified += [string]$scenario.id }
        }
        $copied = Join-Path ([string]$script:Ctx['evidenceDir']) 'model-contract-report.json'
        [IO.File]::Copy($reportPath, $copied, $true)
        $suite.source_sha256 = Get-FileSha256 $reportPath
        $suite.copied_path = $copied
        $suite.ids = @($verified)
        $suite.status = if ($verified.Count -gt 0) { 'PASSED' } else { 'FAILED' }
    }
    catch {
        $suite.error = $_.Exception.Message
    }
    return $suite
}

function Test-ContractIds($Suite, [string[]]$Ids) {
    $missing = @($Ids | Where-Object { $Suite.ids -notcontains $_ })
    return [pscustomobject]@{
        status = if ($Suite.status -eq 'PASSED' -and $missing.Count -eq 0) { 'PASSED' } else { 'FAILED' }
        ids = @($Ids)
        missing = $missing
        source_sha256 = [string]$Suite.source_sha256
        scope = [string]$Suite.scope
    }
}

function Get-RawLogDescriptors {
    $paths = [System.Collections.Generic.List[string]]::new()
    foreach ($relative in @(
        'raw/logs/samples.ndjson',
        'raw/logs/requests.ndjson',
        'raw/logs/crash-buffer.log',
        'raw/logs/anr-events.log',
        'raw/logs/runtime-errors.log',
        'raw/logs/auth-check.ndjson',
        'raw/logs/device-stderr.log'
    )) {
        $p = Join-Path ([string]$script:Ctx['outDir']) $relative
        if (Test-Path -LiteralPath $p -PathType Leaf) { $paths.Add($p) }
    }
    foreach ($p in @(Get-ChildItem -LiteralPath ([string]$script:Ctx['segmentDir']) -File -ErrorAction SilentlyContinue)) { $paths.Add($p.FullName) }
    foreach ($p in @(
        (Join-Path ([string]$script:Ctx['evidenceDir']) 'monitor-console.log'),
        (Join-Path ([string]$script:Ctx['evidenceDir']) 'monitor-console.stderr.log'),
        (Join-Path ([string]$script:Ctx['evidenceDir']) 'preflight.json'),
        (Join-Path ([string]$script:Ctx['evidenceDir']) 'preflight-models.json'),
        (Join-Path ([string]$script:Ctx['evidenceDir']) 'model-contract-report.json'),
        (Join-Path ([string]$script:Ctx['evidenceDir']) 'adb-pull.log')
    )) {
        if (Test-Path -LiteralPath $p -PathType Leaf) { $paths.Add($p) }
    }
    $descriptors = [System.Collections.Generic.List[object]]::new()
    $seen = [System.Collections.Generic.HashSet[string]]::new()
    foreach ($path in $paths) {
        $full = [IO.Path]::GetFullPath($path)
        if (-not $seen.Add($full)) { continue }
        $descriptors.Add((Get-EvidenceDescriptor -Path $full -Base ([string]$script:Ctx['outDir'])))
    }
    if (-not (Test-Path -LiteralPath (Join-Path $script:LocalRawDir 'logs/requests.ndjson'))) { Add-RunError 'raw request NDJSON is missing' }
    if (-not (Test-Path -LiteralPath (Join-Path $script:LocalRawDir 'logs/samples.ndjson'))) { Add-RunError 'raw samples NDJSON is missing' }
    return @($descriptors)
}

function New-FaultScenarioRecords {
    param($Stats, $Plan, $ContractSuite, $UiScenario, $AuthRecord)
    $serial = $Serial
    $abi = [string]$script:Ctx['preflight'].abi
    $rawDescriptors = Get-RawLogDescriptors
    $requestDescriptor = $rawDescriptors | Where-Object { $_.path -eq 'raw/logs/requests.ndjson' } | Select-Object -First 1
    $authDescriptor = $rawDescriptors | Where-Object { $_.path -eq 'raw/logs/auth-check.ndjson' } | Select-Object -First 1
    $cancel = @(Get-ScenarioGroup $Stats 'cancel')
    $retry = @(Get-ScenarioGroup $Stats 'retry')
    $disconnect = @(Get-ScenarioGroup $Stats 'disconnect')
    $worker = @(Get-ScenarioGroup $Stats 'worker_death')
    $mainKill = @(Get-ScenarioGroup $Stats 'main_process_kill')
    $background = @(Get-ScenarioGroup $Stats 'background_foreground')
    $loadUnload = @(Get-ScenarioGroup $Stats 'load_unload')
    $recoveries = @($script:Ctx['mainKillRecoveries'])

    $cancelPass = $cancel.Count -gt 0 -and @($cancel | Where-Object { $_.scenario_result -eq 'PASSED' -and $_.outcome -eq 'cancel' -and $_.stop_accepted -eq 1 }).Count -eq $cancel.Count -and @($retry | Where-Object { $_.outcome -eq 'success' -and $_.scenario_result -eq 'PASSED' }).Count -ge $cancel.Count
    $disconnectPass = $disconnect.Count -gt 0 -and @($disconnect | Where-Object { $_.scenario_result -eq 'PASSED' -and $_.injection_verified -eq 1 }).Count -eq $disconnect.Count -and @($retry | Where-Object { $_.outcome -eq 'success' }).Count -ge ($cancel.Count + $disconnect.Count)
    $workerPass = $worker.Count -ge [int]$Plan.counts.worker_death_target -and @($worker | Where-Object { $_.scenario_result -eq 'PASSED' -and $_.injection_verified -eq 1 -and $_.worker_pid_after -ne $_.worker_pid -and $_.recovery_http_status -eq 200 }).Count -eq $worker.Count
    $mainPass = $mainKill.Count -ge [int]$Plan.counts.restart -and @($mainKill | Where-Object { $_.scenario_result -eq 'PASSED' -and $_.injection_verified -eq 1 -and $_.cold_start_health_status -eq 200 }).Count -eq $mainKill.Count -and $recoveries.Count -ge $mainKill.Count -and @($recoveries | Where-Object { $_.status -eq 'PASSED' }).Count -eq $recoveries.Count
    $backgroundPass = $background.Count -gt 0 -and @($background | Where-Object { $_.scenario_result -eq 'PASSED' -and $_.outcome -eq 'success' -and $_.background_observed -eq 1 -and $_.foreground_resumed -eq 1 }).Count -eq $background.Count
    $uiPass = $null -ne $UiScenario -and $UiScenario.status -eq 'PASSED' -and $loadUnload.Count -gt 0 -and @($loadUnload | Where-Object { $_.outcome -eq 'success' }).Count -eq $loadUnload.Count
    $authPass = $null -ne $AuthRecord -and [int]$AuthRecord.no_auth_http -in @(401, 403) -and [int]$AuthRecord.invalid_auth_http -in @(401, 403) -and [int]$AuthRecord.valid_auth_http -eq 200

    $specs = @(
        [ordered]@{ id = 'CANCEL_RETRY'; name = 'Stream/non-stream cancel with idempotent request IDs and retry recovery'; contracts = @('FI-09','FI-10'); pass = $cancelPass; observed = [ordered]@{ cancel_requests = $cancel.Count; retries = $retry.Count; stop_accepted = @($cancel | Where-Object { $_.stop_accepted -eq 1 }).Count } },
        [ordered]@{ id = 'CLIENT_DISCONNECT'; name = 'Active client disconnect followed by positive recovery'; contracts = @('FI-17'); pass = $disconnectPass; observed = [ordered]@{ disconnected_requests = $disconnect.Count; retries = $retry.Count } },
        [ordered]@{ id = 'WORKER_DEATH'; name = 'In-flight image worker death with verified process loss and same-modality recovery'; contracts = @('FI-12','FI-13'); pass = $workerPass; observed = [ordered]@{ injections = $worker.Count; required = [int]$Plan.counts.worker_death_target; recovered = @($worker | Where-Object { $_.recovery_http_status -eq 200 }).Count } },
        [ordered]@{ id = 'MAIN_PROCESS_KILL_COLD_START'; name = 'Main process force-stop, cold start, production UI reload, and authenticated recovery'; contracts = @('FI-13','FI-14'); pass = $mainPass; observed = [ordered]@{ injections = $mainKill.Count; cold_start_health = @($mainKill | Where-Object { $_.cold_start_health_status -eq 200 }).Count; ui_recovery = @($recoveries | Where-Object { $_.status -eq 'PASSED' }).Count } },
        [ordered]@{ id = 'LOAD_UNLOAD_UI'; name = 'Production UI unload/load plus API-observed image runtime load/unload'; contracts = @('FI-05','FI-06'); pass = $uiPass; observed = [ordered]@{ ui_status = if ($null -ne $UiScenario) { $UiScenario.status } else { 'NOT_RUN' }; image_load_unload_requests = $loadUnload.Count } },
        [ordered]@{ id = 'BACKGROUND_FOREGROUND'; name = 'In-flight stream survives background and foreground recovery'; contracts = @('FI-13','FI-14'); pass = $backgroundPass; observed = [ordered]@{ requests = $background.Count; background_observed = @($background | Where-Object { $_.background_observed -eq 1 }).Count; foreground_resumed = @($background | Where-Object { $_.foreground_resumed -eq 1 }).Count } },
        [ordered]@{ id = 'AUTH_REJECTION'; name = 'Missing and invalid bearer tokens reject, valid bearer token succeeds'; contracts = @('FI-18'); pass = $authPass; observed = [ordered]@{ no_auth_http = if ($null -ne $AuthRecord) { $AuthRecord.no_auth_http } else { 0 }; invalid_auth_http = if ($null -ne $AuthRecord) { $AuthRecord.invalid_auth_http } else { 0 }; valid_auth_http = if ($null -ne $AuthRecord) { $AuthRecord.valid_auth_http } else { 0 } } }
    )

    $records = [System.Collections.Generic.List[object]]::new()
    foreach ($spec in $specs) {
        $contract = Test-ContractIds -Suite $ContractSuite -Ids @($spec.contracts)
        $deviceOk = [bool]$script:Ctx['preflight'].physical -and -not [string]::IsNullOrWhiteSpace($serial) -and $abi -eq 'arm64-v8a' -and ($null -ne $requestDescriptor -or $spec.id -eq 'AUTH_REJECTION' -and $null -ne $authDescriptor)
        $productionOk = [bool]$spec.pass
        $verified = $productionOk -and $deviceOk -and $contract.status -eq 'PASSED'
        $evidence = [ordered]@{
            schema = 'mca-rc-production-fault-scenario/1.0'
            scenario_id = $spec.id
            name = $spec.name
            created_at = Now-Utc
            status = if ($verified) { 'VERIFIED' } else { 'FAILED' }
            dimensions = @('MODEL_TEST','PRODUCTION_INJECTION','DEVICE_EXECUTION')
            model_test = [ordered]@{
                status = $contract.status
                scope = 'CONTRACT_MODEL_ONLY'
                contract_ids = @($spec.contracts)
                missing_ids = @($contract.missing)
                source_sha256 = $contract.source_sha256
                note = 'Model contracts establish expected invariants; they are never counted as production or device execution.'
            }
            production_injection = [ordered]@{
                status = if ($productionOk) { 'PASSED' } else { 'FAILED' }
                observed = $spec.observed
            }
            device_execution = [ordered]@{
                status = if ($deviceOk) { 'PASSED' } else { 'FAILED' }
                serial = $serial
                physical = [bool]$script:Ctx['preflight'].physical
                abi = $abi
            }
            request_evidence = $requestDescriptor
            auth_evidence = $authDescriptor
            ui_evidence = if ($null -ne $UiScenario) { Get-EvidenceDescriptor -Path $UiScenario.path -Base ([string]$script:Ctx['outDir']) } else { $null }
            ui_dump_files = if ($null -ne $UiScenario) { @($UiScenario.uiDumpFiles) } else { @() }
            recovery_records = @($recoveries)
            errors = @($script:RunErrors)
        }
        $evidencePath = Join-Path ([string]$script:Ctx['faultEvidenceDir']) (($spec.id.ToLowerInvariant()) + '.json')
        Write-JsonFile -Path $evidencePath -Value $evidence
        $descriptor = Get-EvidenceDescriptor -Path $evidencePath -Base ([string]$script:Ctx['outDir'])
        $records.Add([pscustomobject]@{
            id = $spec.id
            name = $spec.name
            status = if ($verified) { 'VERIFIED' } else { 'FAILED' }
            dimensions = @('MODEL_TEST','PRODUCTION_INJECTION','DEVICE_EXECUTION')
            device = [ordered]@{ serial = $serial; physical = [bool]$script:Ctx['preflight'].physical; abi = $abi }
            evidence = $descriptor
            production_injection = $productionOk
            device_execution = $deviceOk
            model_test = $contract.status -eq 'PASSED'
        })
    }
    return @($records)
}

function Write-DeviceReport {
    param([datetimeoffset]$StartedAt, [datetimeoffset]$EndedAt, [int]$CrashCount, [int]$AnrCount)
    $pre = $script:Ctx['preflight']
    $uiDescriptor = Get-EvidenceDescriptor -Path ([string]$pre.ui.evidencePath) -Base ([string]$script:Ctx['outDir'])

    $authRecord = $null
    if ($null -ne $script:Ctx['stats']) {
        $authRecord = @($script:Ctx['stats'].warmups | Where-Object { $_.outcome -eq 'success' } | Select-Object -First 1)
        if ($authRecord.Count -eq 0) {
            $authRecord = @($script:Ctx['stats'].counted | Where-Object { $_.outcome -eq 'success' -and [int]$_.http_status -ge 200 -and [int]$_.http_status -le 299 } | Select-Object -First 1)
        }
    }
    $authEvidencePath = Join-Path ([string]$script:Ctx['evidenceDir']) 'device-authenticated-api.json'
    $authOk = $authRecord.Count -gt 0
    $authPayload = [ordered]@{
        schema = 'mca-rc-device-auth-evidence/1.0'
        captured_at = Now-Utc
        authenticated = $authOk
        endpoint = '/v1/chat/completions'
        http_status = if ($authOk) { [int]$authRecord[0].http_status } else { 0 }
        request_id = if ($authOk) { [string]$authRecord[0].request_id } else { '' }
        response_sha256 = if ($authOk) { [string]$authRecord[0].response_sha256 } else { '' }
        response_bytes = if ($authOk) { [int64]$authRecord[0].response_bytes } else { 0 }
        source = 'warmup-or-first-successful-authenticated-request'
    }
    Write-JsonFile -Path $authEvidencePath -Value $authPayload
    $authDescriptor = Get-EvidenceDescriptor -Path $authEvidencePath -Base ([string]$script:Ctx['outDir'])
    $report = [ordered]@{
        schema = $script:SchemaId
        schema_version = '1.0'
        report_type = 'device'
        campaign_id = [string]$script:Ctx['campaignId']
        generated_at = Now-Utc
        status = 'FAILED'
        apk_sha256 = [string]$script:Ctx['apkSha256']
        apk_size_bytes = [int64]$script:Ctx['apkSizeBytes']
        installed_sha256 = [string]$script:Ctx['endBaseApkSha256']
        installed_bytes = [int64]$script:Ctx['endBaseApkBytes']
        serial = $Serial
        device = [ordered]@{
            serial = $Serial
            physical = [bool]$pre.physical
            abi = [string]$pre.abi
            supported_abis = @($pre.supportedAbis)
            manufacturer = [string]$pre.manufacturer
            model = [string]$pre.model
            device_type = 'PHYSICAL_ARM64'
        }
        main_activity_ui = [ordered]@{
            status = [string]$pre.ui.status
            activity = "$Package/.MainActivity"
            foreground = [string]$pre.ui.foreground
            evidence = $uiDescriptor
        }
        authenticated_local_api = [ordered]@{
            status = if ($authOk) { 'PASSED' } else { 'FAILED' }
            authenticated = $authOk
            http_status = if ($authOk) { [int]$authRecord[0].http_status } else { 0 }
            endpoint = '/v1/chat/completions'
            request_id = if ($authOk) { [string]$authRecord[0].request_id } else { '' }
            evidence = $authDescriptor
        }
        apk_path = [string]$script:Ctx['apkPath']
        apkPath = [string]$script:Ctx['apkPath']
        apkSha256 = [string]$script:Ctx['apkSha256']
        installedSha256 = [string]$script:Ctx['endBaseApkSha256']
        mainActivityUiSmoke = [string]$pre.ui.status
        authenticatedLocalApiSmoke = if ($authOk) { 'PASSED' } else { 'FAILED' }
        crashFreeSoak = if ($CrashCount -eq 0) { 'PASSED' } else { 'FAILED' }
        anrSoak = if ($AnrCount -eq 0) { 'PASSED' } else { 'FAILED' }
        crash_count = $CrashCount
        anr_count = $AnrCount
        started_at = Format-Utc $StartedAt
        ended_at = Format-Utc $EndedAt
        errors = @($script:RunErrors)
        warnings = @($script:RunWarnings)
    }
    $pass = $pre.ui.status -eq 'PASSED' -and $authOk -and $CrashCount -eq 0 -and $AnrCount -eq 0 -and $report.installed_sha256 -eq $report.apk_sha256
    $report.status = if ($pass -and $script:RunErrors.Count -eq 0) { 'PASSED' } else { 'FAILED' }
    $path = Join-Path ([string]$script:Ctx['outDir']) 'device-report.json'
    Write-JsonFile -Path $path -Value $report
    return $report
}

function Write-SoakReport {
    param([datetimeoffset]$StartedAt, [datetimeoffset]$EndedAt, $Plan, $Stats, $Memory, $FaultRecords, [bool]$FaultsEnabled)
    $durationSeconds = ($EndedAt - $StartedAt).TotalSeconds
    $durationHours = $durationSeconds / 3600.0
    $streamCount = @($Stats.counted | Where-Object { $_.stream -eq $true }).Count
    $nonstreamCount = @($Stats.counted | Where-Object { $_.stream -ne $true }).Count
    $cancelCount = @(Get-ScenarioGroup $Stats 'cancel').Count
    $retryCount = @(Get-ScenarioGroup $Stats 'retry').Count
    $workerCount = @(Get-ScenarioGroup $Stats 'worker_death').Count
    $restartCount = @(Get-ScenarioGroup $Stats 'main_process_kill').Count
    $loadUnloadCount = @(Get-ScenarioGroup $Stats 'load_unload').Count
    if ($null -ne $script:Ctx['uiScenario'] -and $script:Ctx['uiScenario'].status -eq 'PASSED') { $loadUnloadCount++ }
    $scenarioPass = @{}
    foreach ($record in @($FaultRecords)) { $scenarioPass[[string]$record.id] = $record.status -eq 'VERIFIED' }
    $coverage = [ordered]@{
        stream = $streamCount -gt 0
        nonstream = $nonstreamCount -gt 0
        load_unload = $loadUnloadCount -gt 0 -and ($scenarioPass['LOAD_UNLOAD_UI'] -eq $true -or $loadUnloadCount -gt 0)
        cancel = $cancelCount -gt 0 -and $scenarioPass['CANCEL_RETRY'] -eq $true
        retry = $retryCount -gt 0 -and (@(Get-ScenarioGroup $Stats 'retry' | Where-Object { $_.outcome -eq 'success' }).Count -gt 0)
        worker_death = $workerCount -gt 0 -and $scenarioPass['WORKER_DEATH'] -eq $true
        restart = $restartCount -gt 0 -and $scenarioPass['MAIN_PROCESS_KILL_COLD_START'] -eq $true
    }
    $logicalCounts = [ordered]@{
        stream = $streamCount
        nonstream = $nonstreamCount
        load_unload = $loadUnloadCount
        cancel = $cancelCount
        retry = $retryCount
        worker_death = $workerCount
        restart = $restartCount
    }
    $rawLogs = Get-RawLogDescriptors
    $warmupCount = $Stats.warmups.Count
    $warmupCompleted = $warmupCount -eq $Warmup -and @($Stats.warmups | Where-Object { $_.outcome -eq 'success' }).Count -eq $Warmup
    $unexplainedFailures = @($Stats.counted | Where-Object {
        $_.scenario -in @('normal', 'retry', 'load_unload', 'background_foreground') -and ($_.outcome -ne 'success' -or $_.scenario_result -ne 'PASSED')
    }).Count
    $expectedInjectionFailures = @($Stats.counted | Where-Object {
        $_.scenario -in @('worker_death', 'main_process_kill', 'disconnect') -and $_.outcome -eq 'failure' -and $_.scenario_result -eq 'PASSED'
    }).Count
    $requestIds = @($Stats.ids)
    $planned = @($Plan.entries).Count
    $reconciled = ($Stats.success + $Stats.failure + $Stats.cancel) -eq $Stats.counted.Count
    $durationConsistent = $durationSeconds -gt 0
    $partialSoak = $SoakOnly -or $SkipFaults
    $formalEligible = ($Hours -ge 24.0 -and $Requests -ge 1000 -and $Warmup -ge 1 -and -not $FaultOnly -and -not $partialSoak)
    $report = [ordered]@{
        schema = $script:SchemaId
        schema_version = '1.0'
        report_type = 'soak'
        campaign_id = [string]$script:Ctx['campaignId']
        campaign_kind = if ($FaultOnly) { 'FAULT_ONLY' } elseif ($partialSoak) { 'SOAK_ONLY' } elseif ($formalEligible) { 'FORMAL_RC' } else { 'PILOT' }
        pilot_included_in_formal = $false
        formal_gate_eligible = $formalEligible
        generated_at = Now-Utc
        status = 'FAILED'
        apk_sha256 = [string]$script:Ctx['apkSha256']
        apk_size_bytes = [int64]$script:Ctx['apkSizeBytes']
        apk_path = [string]$script:Ctx['apkPath']
        apkPath = [string]$script:Ctx['apkPath']
        apkSha256 = [string]$script:Ctx['apkSha256']
        installedSha256 = [string]$script:Ctx['endBaseApkSha256']
        serial = $Serial
        started_at = Format-Utc $StartedAt
        ended_at = Format-Utc $EndedAt
        duration_seconds = [Math]::Round($durationSeconds, 3)
        duration_hours = [Math]::Round($durationHours, 6)
        warmup = [ordered]@{
            completed = $warmupCompleted
            requests = $Warmup
            observed = $warmupCount
            duration_seconds = if ($null -ne $script:Ctx['warmupDurationSeconds']) { $script:Ctx['warmupDurationSeconds'] } else { 0 }
        }
        requests = [ordered]@{
            planned = $planned
            attempted = $Stats.counted.Count
            success = $Stats.success
            failure = $Stats.failure
            cancel = $Stats.cancel
            request_ids = $requestIds
            reconciled = $reconciled
            unexplained_failures = $unexplainedFailures
            expected_injection_failures = $expectedInjectionFailures
        }
        logical_counts = $logicalCounts
        coverage = $coverage
        supplemental_logical_counts = [ordered]@{
            background_foreground = @(Get-ScenarioGroup $Stats 'background_foreground').Count
            disconnect = @(Get-ScenarioGroup $Stats 'disconnect').Count
        }
        memory_samples = @($Memory.samples)
        sampling = [ordered]@{
            interval_seconds = $SampleIntervalSeconds
            sample_count = @($Memory.samples).Count
            process_absent_samples = $Memory.unavailable
            max_gap_seconds = $Memory.maxGapSeconds
            roles = @('main', 'local_chat', 'local_image')
            fields = @('pid','pss_bytes','rss_bytes','native_heap_bytes','fd_count','thread_count')
        }
        raw_logs = $rawLogs
        apk_binding_checks = @($script:Ctx['apkGuardChecks'])
        errors = @($script:RunErrors)
        warnings = @($script:RunWarnings)
    }
    $allCoverage = $true
    foreach ($key in @('stream','nonstream','load_unload','cancel','retry','worker_death','restart')) {
        if ($coverage[$key] -ne $true) { $allCoverage = $false }
    }
    $pass = -not $FaultOnly -and $formalEligible -and $Stats.counted.Count -eq $planned -and $Stats.ids.Count -eq $Stats.uniqueIds.Count -and $reconciled -and $durationConsistent -and $warmupCompleted -and $allCoverage -and $unexplainedFailures -eq 0 -and @($Memory.samples).Count -gt 0 -and $script:RunErrors.Count -eq 0
    $report.status = if ($pass) { 'PASSED' } else { 'FAILED' }
    $path = Join-Path ([string]$script:Ctx['outDir']) 'soak-report.json'
    Write-JsonFile -Path $path -Value $report
    return $report
}

function Write-FaultReport {
    param([datetimeoffset]$StartedAt, [datetimeoffset]$EndedAt, $FaultRecords, $ContractSuite, [bool]$FaultsEnabled)
    $rawLogs = Get-RawLogDescriptors
    $records = @($FaultRecords)
    $dimensions = [ordered]@{
        MODEL_TEST = [ordered]@{ count = $records.Count; scope = 'CONTRACT_MODEL_ONLY_REFERENCE' }
        PRODUCTION_INJECTION = [ordered]@{ count = $records.Count; scope = 'REAL_PRODUCTION_PATH' }
        DEVICE_EXECUTION = [ordered]@{ count = $records.Count; scope = 'PHYSICAL_ARM64_DEVICE' }
    }
    $mandatory = [System.Collections.Generic.List[object]]::new()
    foreach ($record in $records) {
        $mandatory.Add([ordered]@{
            id = $record.id
            name = $record.name
            status = $record.status
            dimensions = @('MODEL_TEST','PRODUCTION_INJECTION','DEVICE_EXECUTION')
            device = $record.device
            evidence = $record.evidence
        })
    }
    $status = if (-not $FaultsEnabled) { 'SKIPPED' } elseif ($records.Count -gt 0 -and @($records | Where-Object { $_.status -ne 'VERIFIED' }).Count -eq 0 -and $ContractSuite.status -eq 'PASSED' -and $script:RunErrors.Count -eq 0) { 'VERIFIED' } else { 'FAILED' }
    $report = [ordered]@{
        schema = $script:SchemaId
        schema_version = '1.0'
        report_type = 'fault'
        campaign_id = [string]$script:Ctx['campaignId']
        generated_at = Now-Utc
        status = $status
        apk_sha256 = [string]$script:Ctx['apkSha256']
        apk_size_bytes = [int64]$script:Ctx['apkSizeBytes']
        serial = $Serial
        started_at = Format-Utc $StartedAt
        ended_at = Format-Utc $EndedAt
        dimensions = $dimensions
        mandatory_scenarios = @($mandatory)
        model_test_suite = [ordered]@{
            status = [string]$ContractSuite.status
            scope = [string]$ContractSuite.scope
            source_path = [string]$ContractSuite.source_path
            source_sha256 = [string]$ContractSuite.source_sha256
            verified_contract_ids = @($ContractSuite.ids)
            error = [string]$ContractSuite.error
            note = 'MODEL_TEST is reported separately and never substitutes for production injection or device execution.'
        }
        raw_logs = $rawLogs
        errors = @($script:RunErrors)
        warnings = @($script:RunWarnings)
    }
    $path = Join-Path ([string]$script:Ctx['outDir']) 'fault-report.json'
    Write-JsonFile -Path $path -Value $report
    return $report
}

function Test-ReportSetComplete([string]$OutDir) {
    foreach ($name in @('soak-report.json','fault-report.json','device-report.json')) {
        if (-not (Test-Path -LiteralPath (Join-Path $OutDir $name) -PathType Leaf)) { return $false }
    }
    return $true
}

function Write-NotRunReports([string]$Reason) {
    if (-not $script:Ctx.Contains('outDir')) { return }
    $out = [string]$script:Ctx['outDir']
    $now = Now-Utc
    $sha = if ($script:Ctx.Contains('apkSha256')) { [string]$script:Ctx['apkSha256'] } else { ('0' * 64) }
    $bytes = if ($script:Ctx.Contains('apkSizeBytes')) { [int64]$script:Ctx['apkSizeBytes'] } else { 0 }
    $soak = [ordered]@{
        schema = $script:SchemaId; schema_version = '1.0'; report_type = 'soak'
        generated_at = $now; status = 'FAILED'; campaign_kind = 'NOT_RUN'; pilot_included_in_formal = $false
        formal_gate_eligible = $false; apk_sha256 = $sha; apk_size_bytes = $bytes
        started_at = $now; ended_at = $now; duration_hours = 0
        warmup = [ordered]@{ completed = $false; requests = $Warmup; observed = 0 }
        requests = [ordered]@{ attempted = 0; success = 0; failure = 0; cancel = 0; request_ids = @() }
        logical_counts = [ordered]@{ stream = 0; nonstream = 0; load_unload = 0; cancel = 0; retry = 0; worker_death = 0; restart = 0 }
        coverage = [ordered]@{ stream = $false; nonstream = $false; load_unload = $false; cancel = $false; retry = $false; worker_death = $false; restart = $false }
        memory_samples = @(); raw_logs = @(); errors = @($Reason); warnings = @()
    }
    $fault = [ordered]@{
        schema = $script:SchemaId; schema_version = '1.0'; report_type = 'fault'
        generated_at = $now; status = 'FAILED'; apk_sha256 = $sha; apk_size_bytes = $bytes
        dimensions = [ordered]@{
            MODEL_TEST = [ordered]@{ count = 0 }
            PRODUCTION_INJECTION = [ordered]@{ count = 0 }
            DEVICE_EXECUTION = [ordered]@{ count = 0 }
        }
        mandatory_scenarios = @(); raw_logs = @(); errors = @($Reason); warnings = @()
    }
    $device = [ordered]@{
        schema = $script:SchemaId; schema_version = '1.0'; report_type = 'device'
        generated_at = $now; status = 'FAILED'; apk_sha256 = $sha; installed_sha256 = ''
        installed_bytes = 0; serial = $Serial; device = [ordered]@{ serial = $Serial; physical = $false; abi = '' }
        main_activity_ui = [ordered]@{ status = 'FAILED'; activity = "$Package/.MainActivity"; evidence = $null }
        authenticated_local_api = [ordered]@{ status = 'FAILED'; authenticated = $false; http_status = 0; endpoint = ''; request_id = ''; evidence = $null }
        crash_count = 0; anr_count = 0; errors = @($Reason)
    }
    Write-JsonFile -Path (Join-Path $out 'soak-report.json') -Value $soak
    Write-JsonFile -Path (Join-Path $out 'fault-report.json') -Value $fault
    Write-JsonFile -Path (Join-Path $out 'device-report.json') -Value $device
}
function Test-ParameterCombinations {
    if ($SoakOnly -and $FaultOnly) { throw '-SoakOnly and -FaultOnly are mutually exclusive' }
    if ($FaultOnly -and $SkipFaults) { throw '-FaultOnly with -SkipFaults would execute nothing' }
    if ($Hours -le 0) { throw '-Hours must be greater than zero' }
    if ($Requests -le 0) { throw '-Requests must be greater than zero' }
    if ($Warmup -lt 0) { throw '-Warmup cannot be negative' }
    if ([string]::IsNullOrWhiteSpace($Package)) { throw '-Package cannot be empty' }
    if ([string]::IsNullOrWhiteSpace($ChatModel)) { throw '-ChatModel cannot be empty' }
    if ([string]::IsNullOrWhiteSpace($ImageModel)) { throw '-ImageModel cannot be empty' }
    if ([string]::IsNullOrWhiteSpace($ApiKeyEnvVar)) { throw '-ApiKeyEnvVar cannot be empty' }
}

function Invoke-Campaign {
    $script:Serial = $Serial
    $script:AdbPath = Resolve-AdbPath
    Initialize-RunContext
    $faultsEnabled = -not ($SoakOnly -or $SkipFaults)
    $monitor = $null
    $monitorStopped = $false
    $pulled = $false
    $plan = $null
    $segments = @()
    $startedAt = [datetimeoffset]::UtcNow
    $endedAt = $startedAt
    $stats = $null
    $memory = $null
    $endBase = $null
    $workloadAborted = $false

    try {
        Write-Host "== MCA RC campaign $($script:Ctx['campaignId']) =="
        Write-Host "Stage: preflight (adb, package, APK hash/bytes, /health, loaded model, MainActivity UI)"
        $pre = Invoke-Preflight
        Write-Host "Preflight PASS: apk=$($pre.hostApkSha256.Substring(0,12))... authMode=$($pre.authMode)"

        $faultsEnabled = -not ($SoakOnly -or $SkipFaults)
        if ($FaultOnly) {
            $faultTotal = 40
            $plan = New-CampaignPlan -Total $faultTotal -CampaignId ([string]$script:Ctx['campaignId']) -EnableFaults $true -NoSchedule $true
        }
        else {
            $plan = New-CampaignPlan -Total $Requests -CampaignId ([string]$script:Ctx['campaignId']) -EnableFaults $faultsEnabled -NoSchedule $false
        }
        $segments = @(New-PlanSegments -Plan $plan)
        $segmentEntryCount = 0
        foreach ($segment in $segments) { $segmentEntryCount += @($segment.entries).Count }
        if ($segmentEntryCount -ne @($plan.entries).Count) {
            throw "segment expansion mismatch: entries=$($plan.entries.Count) segments=$segmentEntryCount"
        }
        $script:Ctx['plan'] = $plan
        $script:Ctx['planSegments'] = $segments
        $script:Ctx['faultsEnabled'] = $faultsEnabled

        $warmupPlan = New-WarmupPlan -CampaignId ([string]$script:Ctx['campaignId']) -Count $Warmup
        $script:Ctx['warmupPlan'] = $warmupPlan

        Write-Host "Stage: upload and warmup ($Warmup requests, excluded from the 1000)"
        Initialize-DeviceScript
        $warmupStarted = [datetimeoffset]::UtcNow
        $warmupResult = Invoke-DeviceWorkload -Plan $warmupPlan -Name 'warmup' -NoRemoteSetup -TimeoutSeconds 1800
        $warmupDuration = ([datetimeoffset]::UtcNow - $warmupStarted).TotalSeconds
        $script:Ctx['warmupDurationSeconds'] = [Math]::Round($warmupDuration, 3)
        $warmupPassed = ([regex]::Matches($warmupResult.StdOut, 'REQUEST_DONE\|[^|]+\|warmup\|success\|PASSED')).Count
        if ($warmupPassed -ne $Warmup) { Add-RunError "warmup did not complete: expected=$Warmup passed=$warmupPassed" }
        if ($warmupResult.TimedOut) { throw 'warmup request plan timed out' }

        $startedAt = [datetimeoffset]::UtcNow
        $script:Ctx['startedAt'] = $startedAt
        Write-Host "Formal observation start: $(Format-Utc $startedAt)"
        $monitor = Start-DeviceMonitor
        $lastApkCheck = [datetimeoffset]::UtcNow

        Write-Host "Stage: auth boundary and valid-token control"
        $authResult = Invoke-AuthCheck
        if ($authResult.TimedOut) { Add-RunError 'auth check timed out' }

        if ($faultsEnabled) {
            Write-Host "Stage: production UI unload/load control inside the formal window"
            $uiScenario = Invoke-UiLoadUnloadScenario
            $script:Ctx['uiScenario'] = $uiScenario
        }

        Write-Host "Stage: mixed workload and fault matrix ($($plan.entries.Count) logical requests)"
        $killRecoveryIndex = 0
        foreach ($segment in $segments) {
            if (-not $FaultOnly) {
                Wait-UntilTargetTime -StartTime $startedAt -OffsetSeconds ([int64]$segment.firstOffsetSeconds)
            }
            if (([datetimeoffset]::UtcNow - $lastApkCheck).TotalSeconds -ge 900) {
                try { [void](Test-ApkBindingUnchanged -Reason "periodic-$($segment.name)") }
                catch { Add-RunError ("periodic APK check: " + $_.Exception.Message) }
                $lastApkCheck = [datetimeoffset]::UtcNow
            }
            Write-Host ("  segment {0}/{1}: {2} ({3} requests)" -f $segment.index, $segments.Count, $segment.name, @($segment.entries).Count)
            $segmentResult = Invoke-DeviceWorkload -Plan ([pscustomobject]@{ entries = @($segment.entries); counts = $plan.counts }) -Name $segment.name -TimeoutSeconds (Get-SegmentTimeoutSeconds -Segment $segment)
            if ($segmentResult.TimedOut) {
                Add-RunError "workload segment timed out: $($segment.name)"
                $workloadAborted = $true
                break
            }

            $mainKillInSegment = @($segment.entries | Where-Object { $_.scenario -eq 'main_process_kill' }).Count
            if ($segment.kind -eq 'image' -or $mainKillInSegment -gt 0) {
                try {
                    [void](Invoke-UiLoadModel)
                }
                catch {
                    Add-RunError ("post-fault UI reload failed after {0}: {1}" -f $segment.name, $_.Exception.Message)
                }
            }
            if ($mainKillInSegment -gt 0) {
                for ($i = 0; $i -lt $mainKillInSegment; $i++) {
                    $killRecoveryIndex++
                    $control = New-ControlPlan -CampaignId ([string]$script:Ctx['campaignId']) -Suffix ("kill-$killRecoveryIndex") -Kind 'chat'
                    $cr = Invoke-DeviceWorkload -Plan $control -Name ("kill-control-$killRecoveryIndex") -TimeoutSeconds 600
                    $controlPass = $cr.StdOut -match 'REQUEST_DONE\|[^|]+\|normal\|success\|PASSED'
                    $script:Ctx['mainKillRecoveries'].Add([ordered]@{
                        recovery_index = $killRecoveryIndex
                        status = if ($controlPass) { 'PASSED' } else { 'FAILED' }
                        console = Get-RelativeSlashPath -Base ([string]$script:Ctx['outDir']) -Path (Join-Path ([string]$script:Ctx['segmentDir']) ("kill-control-$killRecoveryIndex.log"))
                        exit_code = $cr.ExitCode
                        timed_out = $cr.TimedOut
                    })
                    if (-not $controlPass) { Add-RunError "main-process-kill positive control failed: index=$killRecoveryIndex" }
                }
            }
        }

        if (-not $FaultOnly -and -not $workloadAborted) {
            Write-Host 'Stage: hold the observation window to the requested duration'
            Wait-UntilTargetTime -StartTime $startedAt -OffsetSeconds ([int64]($Hours * 3600.0))
        }

        Write-Host 'Stage: final health, loaded model, APK binding, and sampler shutdown'
        $finalHealth = Get-HealthState
        if (-not $finalHealth.ok) { Add-RunError "final /health check failed: status='$($finalHealth.status)'" }
        $finalModels = Get-ModelsState
        if (-not $finalModels.ok -or $finalModels.expectedLoaded.Count -eq 0) {
            try { [void](Invoke-UiLoadModel) } catch { Add-RunError ("final UI model reload failed: " + $_.Exception.Message) }
            $finalModels = Get-ModelsState
        }
        if (-not $finalModels.ok -or $finalModels.expectedLoaded.Count -eq 0) {
            Add-RunError 'final /v1/models does not report the expected chat model with loaded=true'
        }
        $endBase = Get-InstalledBaseApkFacts
        $script:Ctx['endBaseApkSha256'] = $endBase.sha256
        $script:Ctx['endBaseApkBytes'] = $endBase.bytes
        [void](Test-ApkBindingUnchanged -Reason 'final')
        if ($monitor -ne $null) {
            $monitorDone = Stop-DeviceMonitor -Handle $monitor
            $monitor = $null
            $monitorStopped = $true
            if ($monitorDone.TimedOut) { Add-RunError 'device monitor did not stop within 240 seconds' }
        }
        $endedAt = [datetimeoffset]::UtcNow
        $script:Ctx['endedAt'] = $endedAt
        Write-Host "Formal observation end: $(Format-Utc $endedAt)"

        Receive-DeviceArtifacts
        $pulled = $true
        [void](Test-ApiKeyLeak)
        $stats = Get-EventStatistics
        $script:Ctx['stats'] = $stats
        [void](Test-PlannedRequestExactness -Plan $plan -Stats $stats)
        $memory = Get-NormalizedMemorySamples -StartedAt $startedAt -EndedAt $endedAt
        $script:Ctx['memory'] = $memory

        $crashAnr = Get-CrashAndAnrCounts
        $rawDescriptorCheck = Get-RawLogDescriptors
        $contractSuite = Get-ModelContractSuite
        $authRecord = Get-AuthCheckRecord
        $faultRecords = @()
        if ($faultsEnabled) {
            $faultRecords = @(New-FaultScenarioRecords -Stats $stats -Plan $plan -ContractSuite $contractSuite -UiScenario $script:Ctx['uiScenario'] -AuthRecord $authRecord)
        }
        else {
            $contractSuite.status = 'SKIPPED'
        }
        $deviceReport = Write-DeviceReport -StartedAt $startedAt -EndedAt $endedAt -CrashCount $crashAnr.crash -AnrCount $crashAnr.anr
        $soakReport = Write-SoakReport -StartedAt $startedAt -EndedAt $endedAt -Plan $plan -Stats $stats -Memory $memory -FaultRecords $faultRecords -FaultsEnabled $faultsEnabled
        $faultReport = Write-FaultReport -StartedAt $startedAt -EndedAt $endedAt -FaultRecords $faultRecords -ContractSuite $contractSuite -FaultsEnabled $faultsEnabled
        if ($FaultOnly) {
            if ($faultReport.status -ne 'VERIFIED' -or $deviceReport.status -ne 'PASSED') {
                Add-RunError "fault-only gate statuses: fault=$($faultReport.status) device=$($deviceReport.status)"
            }
        }
        elseif ($soakReport.status -ne 'PASSED' -or $deviceReport.status -ne 'PASSED' -or ($faultsEnabled -and $faultReport.status -ne 'VERIFIED')) {
            Add-RunError "campaign gate statuses: soak=$($soakReport.status) fault=$($faultReport.status) device=$($deviceReport.status)"
        }
        Write-Host "Reports: soak=$($soakReport.status) fault=$($faultReport.status) device=$($deviceReport.status)"
        Write-Host "Evidence directory: $($script:Ctx['outDir'])"
    }
    catch {
        Add-RunError ("campaign aborted: " + $_.Exception.Message + " @ " + $_.ScriptStackTrace)
        if ($monitor -ne $null) {
            try { [void](Stop-DeviceMonitor -Handle $monitor); $monitorStopped = $true } catch { Add-RunError ("monitor shutdown: " + $_.Exception.Message) }
        }
        if (-not $pulled -and $script:RemoteRoot -and (Test-Path -LiteralPath ([string]$script:Ctx['outDir']))) {
            try { Receive-DeviceArtifacts; $pulled = $true; [void](Test-ApiKeyLeak) } catch { Add-RunError ("evidence pull after abort: " + $_.Exception.Message) }
        }
        if (-not (Test-ReportSetComplete -OutDir ([string]$script:Ctx['outDir']))) {
            Write-NotRunReports -Reason (@($script:RunErrors) -join '; ')
        }
    }
    finally {
        if ($monitor -ne $null -and -not $monitorStopped) {
            try { [void](Stop-DeviceMonitor -Handle $monitor) } catch { }
        }
    }
    if ($script:RunErrors.Count -gt 0) { throw ("campaign failed: " + (@($script:RunErrors) -join '; ')) }
}

function Invoke-SelfTest {
    if ($PSVersionTable.PSVersion.Major -lt 7 -or $PSVersionTable.PSEdition -ne 'Core') { throw 'PowerShell 7 Core is required' }
    $failures = [System.Collections.Generic.List[string]]::new()
    $passes = [System.Collections.Generic.List[string]]::new()
    try {
        $plan = New-CampaignPlan -Total 1000 -CampaignId 'selftest-rc' -EnableFaults $true -NoSchedule $true
        if (@($plan.entries).Count -ne 1000) { $failures.Add('self-test plan count is not 1000') } else { $passes.Add('self-test plan expands to exactly 1000 logical requests') }
        if (@($plan.entries | ForEach-Object { $_.requestId } | Select-Object -Unique).Count -ne 1000) { $failures.Add('self-test request IDs are not unique') } else { $passes.Add('self-test request IDs are unique') }
        if ($plan.counts.stream -le 0 -or $plan.counts.nonstream -le 0) { $failures.Add('self-test stream/non-stream partition is empty') } else { $passes.Add('self-test stream/non-stream partition is populated') }
        if ($plan.counts.cancel -le 0 -or $plan.counts.retry -le 0) { $failures.Add('self-test cancel/retry coverage is empty') } else { $passes.Add('self-test cancel/retry coverage is populated') }
        if ($plan.counts.worker_death -lt [int][Math]::Ceiling(1000 / 200.0)) { $failures.Add('self-test worker-death target is too low') } else { $passes.Add("self-test worker-death target=$($plan.counts.worker_death)") }
        if ($plan.counts.restart -lt 2) { $failures.Add('self-test restart target is too low') } else { $passes.Add("self-test restart target=$($plan.counts.restart)") }
        if ($plan.counts.background_foreground -lt 2) { $failures.Add('self-test background/foreground target is too low') } else { $passes.Add("self-test background/foreground target=$($plan.counts.background_foreground)") }
        if ($plan.counts.load_unload -le 0) { $failures.Add('self-test load/unload coverage is empty') } else { $passes.Add('self-test image load/unload coverage is populated') }
        $segments = @(New-PlanSegments -Plan $plan)
        $sum = 0
        foreach ($segment in $segments) { $sum += @($segment.entries).Count }
        if ($sum -ne 1000) { $failures.Add("self-test segment expansion mismatch: $sum") } else { $passes.Add("self-test segment expansion preserves all entries across $($segments.Count) segments") }
        $warmup = New-WarmupPlan -CampaignId 'selftest-rc' -Count 20
        if (@($warmup.entries).Count -ne 20 -or @($warmup.entries | Where-Object { $_.counted }).Count -ne 0) { $failures.Add('self-test warmup is not excluded from the formal count') } else { $passes.Add('self-test warmup is 20 requests and excluded from formal counts') }
        $faultPlan = New-CampaignPlan -Total 40 -CampaignId 'selftest-rc-fault' -EnableFaults $true -NoSchedule $true
        if ($faultPlan.counts.worker_death -lt 3 -or $faultPlan.counts.restart -lt 1 -or $faultPlan.counts.cancel -lt 1 -or $faultPlan.counts.retry -lt 1) { $failures.Add('self-test fault-only plan is missing mandatory injections') } else { $passes.Add('self-test fault-only plan contains cancel, retry, worker death, restart, and load/unload') }
    }
    catch { $failures.Add("self-test plan: " + $_.Exception.Message) }

    try {
        $shell = Test-ShellSyntax
        if (-not $shell.ok) { $failures.Add("device shell syntax: $($shell.output)") } else { $passes.Add("device shell syntax PASS ($($shell.tool))") }
    }
    catch { $failures.Add("device shell syntax: " + $_.Exception.Message) }

    try {
        $fixture = [ordered]@{ schema = $script:SchemaId; report_type = 'soak'; values = @(1, 2, 3); ok = $true }
        $json = $fixture | ConvertTo-Json -Depth 20
        $doc = [Text.Json.JsonDocument]::Parse($json); $doc.Dispose()
        $passes.Add('strict JSON serializer/parser round-trip PASS')
    }
    catch { $failures.Add("strict JSON round-trip: " + $_.Exception.Message) }

    foreach ($path in @($script:SelfPath, $script:DeviceScriptPath, (Join-Path $PSScriptRoot 'RC-CAMPAIGN-README.md'))) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { $failures.Add("missing required file: $path"); continue }
        $lf = Test-LfNoBom $path
        if (-not $lf.ok) { $failures.Add("file is not UTF-8-without-BOM/LF: $path (CR=$($lf.cr), BOM=$($lf.bom))") }
        else { $passes.Add("LF/no-BOM PASS: $(Split-Path -Leaf $path)") }
    }

    Write-Host 'MCA RC campaign self-test'
    foreach ($item in $passes) { Write-Host "PASS  $item" -ForegroundColor Green }
    foreach ($item in $failures) { Write-Host "FAIL  $item" -ForegroundColor Red }
    Write-Host ("RESULT: {0} passed, {1} failed" -f $passes.Count, $failures.Count)
    if ($failures.Count -gt 0) { return 1 }
    return 0
}

function Invoke-DryRun {
    Test-ParameterCombinations
    $adbResolved = ''
    try { $adbResolved = Resolve-AdbPath } catch { $adbResolved = '<unresolved>' }
    $apkExpanded = if ($ApkPath) { [IO.Path]::GetFullPath($ApkPath) } else { '<missing>' }
    $outExpanded = if ($OutDir) { [IO.Path]::GetFullPath($OutDir) } else { '<missing>' }
    $apkHash = if ($ApkPath -and (Test-Path -LiteralPath $ApkPath -PathType Leaf)) { Get-FileSha256 $ApkPath } else { '<not-hashed>' }
    $total = if ($FaultOnly) { 40 } else { $Requests }
    $plan = New-CampaignPlan -Total $total -CampaignId 'dryrun-rc' -EnableFaults (-not ($SoakOnly -or $SkipFaults)) -NoSchedule $FaultOnly
    $segments = @(New-PlanSegments -Plan $plan)
    $shell = Test-ShellSyntax
    $summary = [ordered]@{
        mode = 'DRY_RUN'
        parameters = [ordered]@{
            serial = $Serial
            apk_path = $apkExpanded
            apk_sha256 = $apkHash
            out_dir = $outExpanded
            hours = $Hours
            requests = $Requests
            warmup = $Warmup
            soak_only = [bool]$SoakOnly
            fault_only = [bool]$FaultOnly
            skip_faults = [bool]$SkipFaults
            sample_interval_seconds = $SampleIntervalSeconds
            api_key_env_var = $ApiKeyEnvVar
        }
        expanded = [ordered]@{
            adb = $adbResolved
            device_script = $script:DeviceScriptPath
            remote_root_pattern = '/data/local/tmp/mca-rc-campaign/<campaign-id>'
            api_key_transport = 'release: environment -> adb stdin -> shell variable -> curl -K - stdin; never argv or files'
        }
        plan_counts = $plan.counts
        segment_count = $segments.Count
        shell_syntax = $shell
        reports = @('soak-report.json','fault-report.json','device-report.json')
        no_device_contact = $true
        no_gradle = $true
    }
    Write-Host ($summary | ConvertTo-Json -Depth 40)
    if (-not $shell.ok) { return 1 }
    return 0
}

if ($SelfTest) {
    exit (Invoke-SelfTest)
}

Test-ParameterCombinations
if ($DryRun) {
    $dryRunExit = Invoke-DryRun
    exit $dryRunExit
}

try {
    Invoke-Campaign
    Write-Host 'Campaign PASS'
    exit 0
}
catch {
    Add-RunError $_.Exception.Message
    if ($script:Ctx.Contains('outDir') -and -not (Test-ReportSetComplete -OutDir ([string]$script:Ctx['outDir']))) {
        try { Write-NotRunReports -Reason (@($script:RunErrors) -join '; ') } catch { }
    }
    Write-Host 'Campaign FAIL' -ForegroundColor Red
    exit 1
}
