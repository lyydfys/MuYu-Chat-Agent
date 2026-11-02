#Requires -Version 5.1
<#
.SYNOPSIS
  DSH embedded V2 device acceptance harness (install, launch, navigate, evidence).

.DESCRIPTION
  One run produces a timestamped evidence directory under (or beside)
  artifacts/dsh-v2/verification/. It never marks a step PASS without captured evidence and it
  never installs or launches anything unless a real device serial and a real APK were supplied.

  Runs on Windows PowerShell 5.1 and PowerShell 7 (the implementation avoids 7-only APIs so it can
  be self-tested on hosts that only ship 5.1; prefer pwsh 7 when it is installed).

  Steps and the PARITY_ACCEPTANCE.md scenarios they cover are recorded per step in result.json.

.EXAMPLE
  pwsh -NoProfile -File scripts/dsh/device-dsh-acceptance.ps1 `
      -Device 1234567890 -Apk artifacts/dsh-v2/apk/mca-debug.apk

.EXAMPLE
  # environment-only self-test (no device needed; exits BLOCKED when nothing is attached)
  pwsh -NoProfile -File scripts/dsh/device-dsh-acceptance.ps1 -Device '' -Apk ''

.NOTES
  Exit codes: 0 = every step PASS; 1 = at least one FAIL or NOT_RUN; 3 = BLOCKED
  (missing adb/device/APK, MIUI install restriction, device lost). BLOCKED and FAIL are never
  reported as PASS. See scripts/dsh/DEVICE-ACCEPTANCE.md for the full contract.
#>
[CmdletBinding()]
param(
    [string]$Device = '',
    [string]$Apk = '',
    [string]$OutDir = '',
    [string]$Package = 'com.muyuchat.mca',
    [int]$BootTimeoutSeconds = 180,
    [int]$InstallTimeoutSeconds = 300,
    [int]$ReadyTimeoutSeconds = 150,
    [string[]]$SidebarSelectors = @(-join (([char]0x6253),([char]0x5F00),([char]0x5386),([char]0x53F2)), -join (([char]0x5386),([char]0x53F2)), -join (([char]0x83DC),([char]0x5355)), -join (([char]0x4FA7),([char]0x680F)), -join (([char]0x5BFC),([char]0x822A)), 'Menu', 'Drawer', 'sidebar'),
    [string[]]$DshSelectors = @((-join (([char]0x6253),([char]0x5F00)) + -join (([char]0x5DE5),([char]0x4F5C),([char]0x533A))), 'DSH', 'DeepSeek Harness', 'DeepSeek', -join (([char]0x667A),([char]0x80FD),([char]0x4F53)), 'Agent'),
    [string[]]$ConsoleSelectors = @((-join (([char]0x44),([char]0x53),([char]0x48),([char]0x20)) + -join (([char]0x63A7),([char]0x5236),([char]0x53F0))), -join (([char]0x63A7),([char]0x5236),([char]0x53F0)), 'Console', -join (([char]0x80FD),([char]0x529B)), 'Capabilities', 'Capability'),
    [string[]]$ReadyLogPatterns = @('DSH.*(ready|READY|Ready)', 'readiness', 'DshRuntimePhase', 'WebView.*(loaded|onPageFinished)'),
    [switch]$SkipInstall,
    # Fallback for vendors that refuse `adb install`: use the on-device Files UI (same taps a
    # developer would perform). Never changes system policy and never bypasses user consent.
    [switch]$AllowFilesUiInstall,
    [switch]$SkipUiNavigation,
    [switch]$PreconditionsOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$script:RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$script:DeviceSerial = $Device
$script:Package = $Package
$script:AdbPath = ''
$script:Steps = [System.Collections.Generic.List[object]]::new()
$script:EvidenceFiles = [System.Collections.Generic.List[string]]::new()
$script:StartedAt = (Get-Date)
$script:OutDir = $OutDir
$script:MiuiRemediation = @(
    '1. On the phone: Settings > About phone > tap "MIUI version"/"HyperOS version" 7 times to enable Developer options.',
    '2. Settings > Additional settings > Developer options: enable "USB debugging" and "Install via USB".',
    '3. If "Install via USB" refuses to stay on, MIUI requires a signed-in Mi account and a SIM in the device; that is a vendor server-side restriction. Sign in on the device yourself; the harness never automates accounts.',
    '4. Developer options: turn off "Verify apps over USB"/"Install via USB verification" (name varies by ROM).',
    '5. During install, confirm the on-device MIUI prompt (Allow/?) within the install timeout.',
    '6. Corporate/MDM devices may forbid sideloading entirely; use a personal test device instead of bypassing policy.',
    'Re-run the same command after the device-side change; nothing in the harness bypasses user consent.'
)

# --- generic helpers ---

$script:Utf8NoBom = [System.Text.UTF8Encoding]::new($false)

function Write-TextFile {
    param([string]$Path, [string]$Text)
    $parent = Split-Path -Parent $Path
    if ($parent -and -not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    [System.IO.File]::WriteAllText($Path, $Text, $script:Utf8NoBom)
}

function Add-TextFile {
    param([string]$Path, [string]$Text)
    [System.IO.File]::AppendAllText($Path, $Text, $script:Utf8NoBom)
}

function Write-Evidence {
    param([string]$RelativeName, [string]$Content)
    $path = Join-Path $script:OutDir $RelativeName
    Write-TextFile -Path $path -Text $Content
    $script:EvidenceFiles.Add($RelativeName)
    return $RelativeName
}

function Add-CommandLog {
    param([string]$Line)
    Add-TextFile -Path (Join-Path $script:OutDir 'commands.log') -Text (
        (Get-Date).ToString('HH:mm:ss') + ' ' + $Line + [System.Environment]::NewLine
    )
}

# Windows CreateProcess argument quoting; works for both .NET Framework and .NET (Core).
function Quote-ProcessArgument {
    param([string]$Argument)
    if ($Argument.Length -eq 0) { return '""' }
    if ($Argument -notmatch '[\s"]') { return $Argument }
    $builder = [System.Text.StringBuilder]::new()
    [void]$builder.Append('"')
    $backslashes = 0
    foreach ($character in $Argument.ToCharArray()) {
        if ($character -eq '\') { $backslashes++; continue }
        if ($character -eq '"') {
            [void]$builder.Append(('\' * (($backslashes * 2) + 1)))
            [void]$builder.Append('"')
            $backslashes = 0
            continue
        }
        if ($backslashes -gt 0) { [void]$builder.Append(('\' * $backslashes)); $backslashes = 0 }
        [void]$builder.Append($character)
    }
    if ($backslashes -gt 0) { [void]$builder.Append(('\' * ($backslashes * 2))) }
    [void]$builder.Append('"')
    return $builder.ToString()
}

function Invoke-Native {
    param(
        [string]$FilePath,
        [string[]]$ArgumentList,
        [int]$TimeoutSeconds = 60
    )
    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $FilePath
    $psi.Arguments = (($ArgumentList | ForEach-Object { Quote-ProcessArgument ([string]$_) }) -join ' ')
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $process = [System.Diagnostics.Process]::Start($psi)
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
        try {
            if ($PSVersionTable.PSVersion.Major -ge 6) { $process.Kill($true) } else { $process.Kill() }
        } catch { }
        return [pscustomobject]@{ ExitCode = -999; StdOut = ''; StdErr = 'timeout'; TimedOut = $true }
    }
    return [pscustomobject]@{
        ExitCode  = $process.ExitCode
        StdOut    = $stdout.GetAwaiter().GetResult()
        StdErr    = $stderr.GetAwaiter().GetResult()
        TimedOut  = $false
    }
}

function Invoke-Adb {
    param(
        [string[]]$Arguments,
        [string]$EvidenceName = '',
        [int]$TimeoutSeconds = 60,
        [switch]$Global
    )
    $argv = @()
    if (-not $Global -and $script:DeviceSerial) {
        $argv += @('-s', $script:DeviceSerial)
    }
    $argv += $Arguments
    $result = Invoke-Native -FilePath $script:AdbPath -ArgumentList $argv -TimeoutSeconds $TimeoutSeconds
    $text = (($result.StdOut + "`n" + $result.StdErr).Trim())
    Add-CommandLog ("adb " + ($argv -join ' ') + ' -> exit ' + $result.ExitCode + $(if ($result.TimedOut) { ' (timeout)' } else { '' }))
    if ($EvidenceName) { Write-Evidence ($EvidenceName + '.txt') $text | Out-Null }
    return [pscustomobject]@{
        ExitCode = $result.ExitCode
        Text     = $text
        TimedOut = $result.TimedOut
    }
}

function Add-Step {
    param(
        [string]$Id,
        [string]$Title,
        [string[]]$Scenarios,
        [ValidateSet('PASS', 'FAIL', 'NOT_RUN', 'BLOCKED')][string]$Status,
        [string]$Detail = '',
        [string[]]$Evidence = @()
    )
    $step = [ordered]@{
        id         = $Id
        title      = $Title
        scenarios  = $Scenarios
        status     = $Status
        detail     = $Detail
        evidence   = $Evidence
        recordedAt = (Get-Date).ToString('o')
    }
    $script:Steps.Add($step)
    $line = '{0} {1,-7} {2} {3}' -f (Get-Date).ToString('HH:mm:ss'), $Status, $Id, $Detail
    Write-Host $line
    Add-TextFile -Path $script:SummaryLog -Text ($line + [System.Environment]::NewLine)
}

function Get-OverallStatus {
    if ($script:Steps | Where-Object { $_.status -eq 'BLOCKED' }) { return 'BLOCKED' }
    if ($script:Steps | Where-Object { $_.status -eq 'FAIL' }) { return 'FAIL' }
    if ($script:Steps | Where-Object { $_.status -eq 'NOT_RUN' }) { return 'NOT_RUN' }
    if ($script:Steps.Count -eq 0) { return 'NOT_RUN' }
    return 'PASS'
}

function Save-Result {
    $overall = Get-OverallStatus
    $exitCode = switch ($overall) {
        'PASS' { 0 }
        'BLOCKED' { 3 }
        default { 1 }
    }
    $deviceInfo = [ordered]@{
        serial = $script:DeviceSerial
        model  = $script:DeviceModel
        sdk    = $script:DeviceSdk
        abi    = $script:DeviceAbi
        android= $script:DeviceRelease
    }
    $apkInfo = [ordered]@{
        path  = $script:ApkPath
        exists = ($script:ApkPath -and (Test-Path -LiteralPath $script:ApkPath))
        sha256 = $script:ApkSha256
        bytes  = $script:ApkBytes
    }
    $result = [ordered]@{
        schema      = 'mca.dsh.device-acceptance/1'
        startedAt   = $script:StartedAt.ToString('o')
        finishedAt  = (Get-Date).ToString('o')
        host        = [ordered]@{ os = [System.Environment]::OSVersion.VersionString; psVersion = $PSVersionTable.PSVersion.ToString(); repoRoot = $script:RepoRoot }
        device      = $deviceInfo
        apk         = $apkInfo
        package     = $script:Package
        overall     = $overall
        exitCode    = $exitCode
        steps       = $script:Steps
        evidence    = ($script:EvidenceFiles | Select-Object -Unique)
        miuiDetected = $script:MiuiDetected
        notes       = $script:RunNotes
    }
    Write-TextFile -Path (Join-Path $script:OutDir 'result.json') -Text ($result | ConvertTo-Json -Depth 12)

    $lines = [System.Collections.Generic.List[string]]::new()
    $lines.Add('# DSH device acceptance -  ' + $script:StartedAt.ToString('yyyy-MM-dd HH:mm:ss zzz'))
    $lines.Add('')
    $lines.Add('- Device: `' + $script:DeviceSerial + '` model=' + $script:DeviceModel + ' sdk=' + $script:DeviceSdk + ' abi=' + $script:DeviceAbi)
    $lines.Add('- APK: `' + $script:ApkPath + '` sha256=' + $script:ApkSha256 + ' bytes=' + $script:ApkBytes)
    $lines.Add('- Package: `' + $script:Package + '`; MIUI detected: ' + $script:MiuiDetected)
    $lines.Add('- Overall: **' + $overall + '** (exit ' + $exitCode + ')')
    $lines.Add('')
    $lines.Add('| Step | Status | Scenarios | Detail |')
    $lines.Add('| --- | --- | --- | --- |')
    foreach ($step in $script:Steps) {
        $detail = ($step.detail -replace '\|', '/')
        $lines.Add('| ' + $step.id + ' | ' + $step.status + ' | ' + ($step.scenarios -join ', ') + ' | ' + $detail + ' |')
    }
    $lines.Add('')
    $lines.Add('## Evidence files')
    foreach ($file in ($script:EvidenceFiles | Select-Object -Unique)) { $lines.Add('- `' + $file + '`') }
    if ($overall -eq 'BLOCKED') {
        $lines.Add('')
        $lines.Add('## MIUI / vendor install remediation (do this on the device, then re-run)')
        foreach ($item in $script:MiuiRemediation) { $lines.Add($item) }
    }
    $lines.Add('')
    $lines.Add('PASS/FAIL/BLOCKED/NOT_RUN refer to this run only. A step marked PASS has its captured evidence; nothing here is device-proven beyond the files listed.')
    Write-TextFile -Path (Join-Path $script:OutDir 'summary.md') -Text ($lines -join "`n")
    Write-Host ''
    Write-Host ('EVIDENCE ' + $script:OutDir)
    Write-Host ('RESULT ' + $overall + ' exit=' + $exitCode)
    exit $exitCode
}

function Complete-Blocked {
    param([string]$StepId, [string]$Title, [string]$Detail, [string[]]$Scenarios = @(), [string[]]$Evidence = @())
    Add-Step -Id $StepId -Title $Title -Scenarios $Scenarios -Status BLOCKED -Detail $Detail -Evidence $Evidence
    $script:RunNotes.Add($Detail)
    Save-Result
}

function Test-DeviceOnline {
    $result = Invoke-Adb -Arguments @('devices', '-l') -Global -TimeoutSeconds 30 -EvidenceName 'preconditions/adb-devices-latest'
    $script:LastDeviceList = $result.Text
    return ($result.Text -match ('(?m)^' + [regex]::Escape($script:DeviceSerial) + '\s+device\b'))
}

function Get-PropMap {
    $result = Invoke-Adb -Arguments @('shell', 'getprop') -TimeoutSeconds 30 -EvidenceName 'preconditions/getprop'
    $map = @{}
    foreach ($line in (($result.Text -replace "`r", '') -split "`n")) {
        if ($line -match '^\[(?<key>[^\]]+)\]: \[(?<value>.*)\]$') {
            $map[$Matches.key] = $Matches.value
        }
    }
    return $map
}

function Get-UiDump {
    param([string]$Name, [int]$TimeoutSeconds = 30)
    $deviceXml = '/data/local/tmp/dsh-acceptance-ui.xml'
    $dump = Invoke-Adb -Arguments @('shell', 'uiautomator', 'dump', $deviceXml) -TimeoutSeconds $TimeoutSeconds -EvidenceName ($Name + '-dump')
    $localXml = Join-Path $script:OutDir ($Name + '.xml')
    $pull = Invoke-Native -FilePath $script:AdbPath -ArgumentList @('-s', $script:DeviceSerial, 'pull', $deviceXml, $localXml) -TimeoutSeconds $TimeoutSeconds
    if (-not (Test-Path -LiteralPath $localXml)) { return $null }
    $script:EvidenceFiles.Add($Name + '.xml')
    # uiautomator writes UTF-8; Windows PowerShell 5.1 would otherwise decode the file with the
    # ANSI code page and produce mojibake that is not valid XML.
    try {
        $raw = [System.IO.File]::ReadAllText($localXml, [System.Text.Encoding]::UTF8)
        return [xml]$raw
    }
    catch { return $null }
}

function Find-UiNode {
    param([xml]$Document, [string[]]$Selectors)
    if ($null -eq $Document) { return $null }
    foreach ($selector in $Selectors) {
        foreach ($node in $Document.SelectNodes('//node')) {
            $text = [string]$node.GetAttribute('text')
            $desc = [string]$node.GetAttribute('content-desc')
            $label = ($text + ' ' + $desc).Trim()
            if ($label.Length -eq 0) { continue }
            if ($label.IndexOf($selector, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
                $bounds = [string]$node.GetAttribute('bounds')
                if ($bounds -match '^\[(?<x1>\d+),(?<y1>\d+)\]\[(?<x2>\d+),(?<y2>\d+)\]$') {
                    return [pscustomobject]@{
                        Selector = $selector
                        Label    = $label
                        X        = [int](($Matches.x1 -as [int]) + ($Matches.x2 -as [int])) / 2
                        Y        = [int](($Matches.y1 -as [int]) + ($Matches.y2 -as [int])) / 2
                        Bounds   = $bounds
                    }
                }
            }
        }
    }
    return $null
}

function Invoke-Tap {
    param([int]$X, [int]$Y, [string]$Name)
    Write-Evidence ($Name + '-tap.txt') "tap $X $Y" | Out-Null
    return Invoke-Adb -Arguments @('shell', 'input', 'tap', "$X", "$Y") -TimeoutSeconds 30 -EvidenceName ($Name + '-tap-result')
}

function Save-Screenshot {
    param([string]$Name)
    $devicePath = '/data/local/tmp/dsh-acceptance-' + $Name + '.png'
    $shot = Invoke-Adb -Arguments @('shell', 'screencap', '-p', $devicePath) -TimeoutSeconds 60 -EvidenceName ($Name + '-screencap')
    if ($shot.ExitCode -ne 0) { return '' }
    $localPath = Join-Path $script:OutDir ($Name + '.png')
    $pull = Invoke-Native -FilePath $script:AdbPath -ArgumentList @('-s', $script:DeviceSerial, 'pull', $devicePath, $localPath) -TimeoutSeconds 60
    Invoke-Adb -Arguments @('shell', 'rm', '-f', $devicePath) -TimeoutSeconds 20 | Out-Null
    if (Test-Path -LiteralPath $localPath) {
        $script:EvidenceFiles.Add($Name + '.png')
        return ($Name + '.png')
    }
    return ''
}

function Get-ForegroundActivity {
    $activity = Invoke-Adb -Arguments @('shell', 'dumpsys', 'activity', 'activities') -TimeoutSeconds 60 -EvidenceName 'app/activities'
    $resumed = ($activity.Text -split "`n" | Where-Object { $_ -match 'mResumedActivity|topResumedActivity' } | Select-Object -First 3) -join ' | '
    if ([string]::IsNullOrWhiteSpace($resumed)) {
        $window = Invoke-Adb -Arguments @('shell', 'dumpsys', 'window', 'windows') -TimeoutSeconds 60 -EvidenceName 'app/window-focus'
        $resumed = ($window.Text -split "`n" | Where-Object { $_ -match 'mCurrentFocus|mFocusedApp' } | Select-Object -First 3) -join ' | '
    }
    return [pscustomobject]@{ Text = $activity.Text; Resumed = $resumed }
}

function Get-ProcessTree {
    $tree = Invoke-Adb -Arguments @('shell', 'ps', '-A', '-o', 'PID,PPID,NAME') -TimeoutSeconds 60 -EvidenceName 'app/ps-tree'
    $lines = $tree.Text -split "`n" | Where-Object { $_ -match [regex]::Escape($script:Package) -or $_ -match '\bnode\b' -or $_ -match ':dsh' }
    Write-Evidence 'app/process-tree-matches.txt' ($lines -join "`n") | Out-Null
    return ($lines -join "`n")
}

function Get-LogcatSlice {
    param([string]$Name, [int]$TailLines = 2000)
    return Invoke-Adb -Arguments @('logcat', '-d', '-v', 'threadtime', '-t', "$TailLines") -TimeoutSeconds 90 -EvidenceName ('logs/' + $Name)
}

function Get-AnrFatalScan {
    param([string]$LogcatText, [string]$Name)
    # A real crash always prints one of these; do not add a bare "Process: <package>" pattern,
    # because ordinary ActivityManager/launch lines mention the package and would false-positive.
    $patterns = @('FATAL EXCEPTION', 'ANR in', 'Force finishing', 'Unable to start activity')
    $hits = [System.Collections.Generic.List[string]]::new()
    foreach ($line in ($LogcatText -split "`n")) {
        foreach ($pattern in $patterns) {
            if ($line -match $pattern) { $hits.Add($line.Trim()); break }
        }
    }
    $text = ($hits | Select-Object -First 80) -join "`n"
    Write-Evidence ('logs/' + $Name + '-anr-fatal.txt') $text | Out-Null
    return ($hits.Count -gt 0)
}

function Initialize-EvidenceDir {
    if ([string]::IsNullOrWhiteSpace($script:OutDir)) {
        $stamp = (Get-Date).ToString('yyyyMMdd-HHmmss')
        $script:OutDir = Join-Path $script:RepoRoot ('artifacts/dsh-v2/verification/device-acceptance-' + $stamp)
    }
    if (-not (Test-Path -LiteralPath $script:OutDir)) {
        New-Item -ItemType Directory -Force -Path $script:OutDir | Out-Null
    }
    $script:SummaryLog = Join-Path $script:OutDir 'steps.log'
    Write-TextFile -Path $script:SummaryLog -Text ''
    Write-TextFile -Path (Join-Path $script:OutDir 'commands.log') -Text ''
}

# --- run state that Save-Result reads even when the run is blocked early ---
$script:DeviceModel = ''
$script:DeviceSdk = ''
$script:DeviceAbi = ''
$script:DeviceRelease = ''
$script:ApkPath = $Apk
$script:ApkSha256 = ''
$script:ApkBytes = ''
$script:MiuiDetected = ''
$script:RunNotes = [System.Collections.Generic.List[string]]::new()
$script:LastDeviceList = ''

$script:StepDefinitions = [ordered]@{
    'PRE-01' = @('adb present and device online', @('environment freeze 3.1'))
    'PRE-02' = @('device inventory and free space', @('environment freeze 3.1'))
    'PRE-03' = @('MIUI/vendor install restriction probe', @('precondition for REG-01'))
    'INST-01' = @('install candidate APK', @('REG-01 precondition'))
    'APP-01' = @('launch MainActivity, process and foreground', @('REG-01', 'REG-02'))
    'APP-02' = @('logcat, ANR/FATAL scan, screenshot, process tree', @('REG-12 precheck'))
    'UI-01' = @('open sidebar', @('REG-01'))
    'UI-02' = @('open DSH entry, wait readiness, first frame', @('REG-01', 'REG-02'))
    'UI-03' = @('open DSH console, capture capability states', @('REG-06'))
    'UI-04' = @('post-navigation foreground/process/ANR capture', @('REG-12 precheck'))
}

try {
    Initialize-EvidenceDir
    Add-CommandLog ('started host=' + [System.Environment]::OSVersion.VersionString + ' ps=' + $PSVersionTable.PSVersion)

    # --- PRE-01: adb + device ---
    $adbCandidate = $null
    foreach ($candidate in @($env:ADB) + @((Get-Command adb -ErrorAction SilentlyContinue | ForEach-Object { $_.Source }))) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) { $adbCandidate = $candidate; break }
    }
    if (-not $adbCandidate) {
        $localProperties = Join-Path $script:RepoRoot 'local.properties'
        if (Test-Path -LiteralPath $localProperties) {
            $sdkLine = Select-String -LiteralPath $localProperties -Pattern '^sdk\.dir=' | Select-Object -First 1
            if ($sdkLine) {
                $rawSdk = $sdkLine.Line.Substring($sdkLine.Line.IndexOf('=') + 1).Trim()
                $sdk = $rawSdk.Replace('\:', ':').Replace('\\', '\')
                $candidate = Join-Path $sdk 'platform-tools\adb.exe'
                if (Test-Path -LiteralPath $candidate) { $adbCandidate = $candidate }
            }
        }
    }
    if (-not $adbCandidate) {
        $common = [System.Collections.Generic.List[string]]::new()
        if ($env:LOCALAPPDATA) { $common.Add((Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')) }
        if ($env:ANDROID_HOME) { $common.Add((Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe')) }
        if ($env:ANDROID_SDK_ROOT) { $common.Add((Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe')) }
        foreach ($candidate in $common) {
            if ($candidate -and (Test-Path -LiteralPath $candidate)) { $adbCandidate = $candidate; break }
        }
    }
    if (-not $adbCandidate) {
        Complete-Blocked 'PRE-01' 'adb present and device online' 'adb not found (PATH, ADB env, local.properties sdk.dir, ANDROID_HOME). Install Android platform-tools.' @('environment freeze 3.1')
    }
    $script:AdbPath = $adbCandidate
    $adbVersion = Invoke-Native -FilePath $script:AdbPath -ArgumentList @('version') -TimeoutSeconds 30
    Write-Evidence 'preconditions/adb-version.txt' (($adbVersion.StdOut + "`n" + $adbVersion.StdErr).Trim()) | Out-Null
    if ($adbVersion.ExitCode -ne 0) {
        Complete-Blocked 'PRE-01' 'adb present and device online' ('adb version failed: ' + $adbVersion.StdErr.Trim()) @('environment freeze 3.1')
    }
    if ([string]::IsNullOrWhiteSpace($script:DeviceSerial)) {
        Complete-Blocked 'PRE-01' 'adb present and device online' 'No -Device serial supplied. Pass the serial from "adb devices -l"; nothing was installed.' @('environment freeze 3.1')
    }
    $deviceList = Invoke-Adb -Arguments @('devices', '-l') -Global -TimeoutSeconds 30 -EvidenceName 'preconditions/adb-devices'
    $script:LastDeviceList = $deviceList.Text
    $serialLine = ($deviceList.Text -split "`n" | Where-Object { $_ -match ('^' + [regex]::Escape($script:DeviceSerial) + '\s') } | Select-Object -First 1)
    if (-not $serialLine) {
        Complete-Blocked 'PRE-01' 'adb present and device online' ('Serial "' + $script:DeviceSerial + '" is not listed by "adb devices -l". Connected: ' + (($deviceList.Text -split "`n" | Where-Object { $_ -match '\sdevice\s' } | ForEach-Object { ($_ -split '\s+')[0] }) -join ', ')) @('environment freeze 3.1')
    }
    if ($serialLine -notmatch '\sdevice\s') {
        Complete-Blocked 'PRE-01' 'adb present and device online' ('Serial "' + $script:DeviceSerial + '" is not in state "device": ' + $serialLine.Trim() + '. Accept the USB debugging prompt on the phone.') @('environment freeze 3.1')
    }
    Add-Step -Id 'PRE-01' -Title 'adb present and device online' -Scenarios @('environment freeze 3.1') -Status PASS -Detail ('adb=' + $script:AdbPath + '; serial=' + $script:DeviceSerial) -Evidence @('preconditions/adb-version.txt', 'preconditions/adb-devices.txt')

    # --- PRE-02: inventory ---
    $props = Get-PropMap
    $script:DeviceModel = [string]$props['ro.product.model']
    $script:DeviceSdk = [string]$props['ro.build.version.sdk']
    $script:DeviceAbi = [string]$props['ro.product.cpu.abi']
    $script:DeviceRelease = [string]$props['ro.build.version.release']
    $miuiName = [string]$props['ro.miui.ui.version.name']
    $miuiCode = [string]$props['ro.miui.ui.version.code']
    $hyperOs = [string]$props['ro.mi.os.version.name']
    $hyperOsIncremental = [string]$props['ro.mi.os.version.incremental']
    $script:MiuiDetected = if ($miuiName -or $miuiCode -or $hyperOs) {
        ('yes miui=' + $miuiName + ' ' + $miuiCode + ' hyperos=' + $hyperOs + ' ' + $hyperOsIncremental).Trim()
    } else {
        'no'
    }
    $df = Invoke-Adb -Arguments @('shell', 'df', '-k', '/data') -TimeoutSeconds 60 -EvidenceName 'preconditions/df-data'
    $freeKb = ''
    foreach ($line in ($df.Text -split "`n")) {
        $fields = $line -split '\s+'
        if ($fields.Count -ge 4 -and $fields[0] -match '^/dev') { $freeKb = $fields[3]; break }
    }
    $screen = Invoke-Adb -Arguments @('shell', 'wm', 'size') -TimeoutSeconds 30 -EvidenceName 'preconditions/wm-size'
    $density = Invoke-Adb -Arguments @('shell', 'wm', 'density') -TimeoutSeconds 30 -EvidenceName 'preconditions/wm-density'
    $verifier = Invoke-Adb -Arguments @('shell', 'settings', 'get', 'global', 'verifier_verify_adb_installs') -TimeoutSeconds 30 -EvidenceName 'preconditions/verifier-verify-adb-installs'
    $setup = Invoke-Adb -Arguments @('shell', 'settings', 'get', 'secure', 'user_setup_complete') -TimeoutSeconds 30 -EvidenceName 'preconditions/user-setup-complete'
    $inventory = @(
        'model=' + $script:DeviceModel,
        'sdk=' + $script:DeviceSdk,
        'abi=' + $script:DeviceAbi,
        'android=' + $script:DeviceRelease,
        'freeKiB=/data=' + $freeKb,
        'screen=' + $screen.Text.Trim(),
        'density=' + $density.Text.Trim(),
        'miui=' + $script:MiuiDetected,
        'verifier_verify_adb_installs=' + $verifier.Text.Trim(),
        'user_setup_complete=' + $setup.Text.Trim()
    ) -join "`n"
    Write-Evidence 'preconditions/inventory.txt' $inventory | Out-Null
    if ([string]::IsNullOrWhiteSpace($script:DeviceSdk) -or [string]::IsNullOrWhiteSpace($script:DeviceAbi)) {
        Add-Step -Id 'PRE-02' -Title 'device inventory and free space' -Scenarios @('environment freeze 3.1') -Status FAIL -Detail 'device did not report ro.build.version.sdk / ro.product.cpu.abi' -Evidence @('preconditions/inventory.txt')
    } else {
        Add-Step -Id 'PRE-02' -Title 'device inventory and free space' -Scenarios @('environment freeze 3.1') -Status PASS -Detail ('model=' + $script:DeviceModel + ' sdk=' + $script:DeviceSdk + ' abi=' + $script:DeviceAbi + ' freeKiB=' + $freeKb + ' miui=' + $script:MiuiDetected) -Evidence @('preconditions/inventory.txt')
    }

    # --- PRE-03: APK + dry-run install probe ---
    if (-not $SkipInstall) {
        $apkOk = $true
        if ([string]::IsNullOrWhiteSpace($script:ApkPath)) { $apkOk = $false }
        elseif (-not (Test-Path -LiteralPath $script:ApkPath -PathType Leaf)) { $apkOk = $false }
        if (-not $apkOk) {
            Complete-Blocked 'PRE-03' 'MIUI/vendor install restriction probe' ('APK not found: "' + $script:ApkPath + '". Pass -Apk <path to the built MCA APK> (or -SkipInstall for an already-installed build).') @('precondition for REG-01')
        }
        $apkItem = Get-Item -LiteralPath $script:ApkPath
        $script:ApkSha256 = (Get-FileHash -LiteralPath $script:ApkPath -Algorithm SHA256).Hash.ToLowerInvariant()
        $script:ApkBytes = $apkItem.Length
        Write-Evidence 'preconditions/apk-identity.txt' (@(
            'path=' + $apkItem.FullName,
            'bytes=' + $apkItem.Length,
            'sha256=' + $script:ApkSha256,
            'package=' + $script:Package
        ) -join "`n") | Out-Null
    } else {
        Write-Evidence 'preconditions/apk-identity.txt' 'SkipInstall: APK path/hash not required; an already-installed package is used.' | Out-Null
    }
    $probe = Invoke-Adb -Arguments @('shell', 'pm', 'install-create', '-r', '-t', '-S', '4096') -TimeoutSeconds 60 -EvidenceName 'preconditions/install-create-probe'
    $sessionId = ''
    if ($probe.Text -match '\[(?<id>\d+)\]') { $sessionId = $Matches.id }
    if ($sessionId) { Invoke-Adb -Arguments @('shell', 'pm', 'install-abandon', $sessionId) -TimeoutSeconds 30 -EvidenceName 'preconditions/install-abandon-probe' | Out-Null }
    $restricted = ($probe.Text -match 'INSTALL_FAILED_USER_RESTRICTED|INSTALL_FAILED_VERIFICATION_FAILURE|INSTALL_CANCELED_BY_USER|SecurityException|not allowed|Permission Denial|INSTALL_FAILED_INTERNAL_ERROR')
    $probeNote = 'session=' + ($(if ($sessionId) { $sessionId } else { 'none' })) + '; output=' + (($probe.Text -split "`n" | Select-Object -First 3) -join ' / ')
    if ($restricted) {
        Add-Step -Id 'PRE-03' -Title 'MIUI/vendor install restriction probe' -Scenarios @('precondition for REG-01') -Status BLOCKED -Detail ('dry-run install session was refused: ' + $probeNote) -Evidence @('preconditions/install-create-probe.txt')
        $script:RunNotes.Add('MIUI/vendor install restriction detected by dry-run probe; nothing was installed.')
    } else {
        Add-Step -Id 'PRE-03' -Title 'MIUI/vendor install restriction probe' -Scenarios @('precondition for REG-01') -Status PASS -Detail ('dry-run session create/abandon ok; ' + $probeNote + '; MIUI=' + $script:MiuiDetected + '. Note: MIUI can still block at commit time; the install step records that exact error.') -Evidence @('preconditions/install-create-probe.txt', 'preconditions/install-abandon-probe.txt')
    }

    # --- INST-01 ---
    if ($SkipInstall) {
        Add-Step -Id 'INST-01' -Title 'install candidate APK' -Scenarios @('REG-01 precondition') -Status NOT_RUN -Detail 'SkipInstall: install not attempted.' -Evidence @('preconditions/apk-identity.txt')
    } elseif (($script:Steps | Where-Object { $_.id -eq 'PRE-03' -and $_.status -eq 'BLOCKED' })) {
        Add-Step -Id 'INST-01' -Title 'install candidate APK' -Scenarios @('REG-01 precondition') -Status BLOCKED -Detail 'not attempted because the dry-run install probe was blocked; see PRE-03 and the MIUI remediation in summary.md.' -Evidence @('preconditions/install-create-probe.txt')
    } else {
        $before = Invoke-Adb -Arguments @('shell', 'pm', 'path', $script:Package) -TimeoutSeconds 30 -EvidenceName 'app/pm-path-before'
        $install = Invoke-Adb -Arguments @('install', '-r', '-t', $script:ApkPath) -TimeoutSeconds $InstallTimeoutSeconds -EvidenceName 'app/install-result'
        if ($install.TimedOut) {
            Add-Step -Id 'INST-01' -Title 'install candidate APK' -Scenarios @('REG-01 precondition') -Status FAIL -Detail ('adb install timed out after ' + $InstallTimeoutSeconds + 's; see app/install-result.txt') -Evidence @('app/install-result.txt')
        } elseif ($install.Text -match 'Success') {
            Add-Step -Id 'INST-01' -Title 'install candidate APK' -Scenarios @('REG-01 precondition') -Status PASS -Detail 'adb install reported Success' -Evidence @('app/install-result.txt')
        } elseif ($install.Text -match 'INSTALL_FAILED_USER_RESTRICTED|INSTALL_FAILED_VERIFICATION_FAILURE|INSTALL_CANCELED_BY_USER|not allowed|SecurityException') {
            $refusal = (($install.Text -split "`n") | Select-Object -First 3) -join ' '
            if ($AllowFilesUiInstall) {
                $helper = Join-Path $script:RepoRoot 'scripts/dsh/install-mca-via-fileexplorer.ps1'
                if (Test-Path -LiteralPath $helper) {
                    $helperLog = Join-Path $script:OutDir 'app/install-via-files.log'
                    $helperArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $helper, '-Device', $script:DeviceSerial, '-Apk', $script:ApkPath, '-LogPath', $helperLog)
                    $helperProcess = Start-Process -FilePath 'powershell' -ArgumentList $helperArgs -Wait -PassThru -NoNewWindow
                    $helperExit = $helperProcess.ExitCode
                    if ($helperExit -eq 0) {
                        Add-Step -Id 'INST-01' -Title 'install candidate APK' -Scenarios @('REG-01 precondition') -Status PASS -Detail ('adb install was refused (' + $refusal + '); installed through the on-device Files UI fallback, see app/install-via-files.log') -Evidence @('app/install-result.txt', 'app/install-via-files.log')
                    } else {
                        Add-Step -Id 'INST-01' -Title 'install candidate APK' -Scenarios @('REG-01 precondition') -Status BLOCKED -Detail ('adb install refused (' + $refusal + ') and the Files UI fallback failed (exit ' + $helperExit + '); see app/install-via-files.log') -Evidence @('app/install-result.txt', 'app/install-via-files.log')
                    }
                } else {
                    Add-Step -Id 'INST-01' -Title 'install candidate APK' -Scenarios @('REG-01 precondition') -Status BLOCKED -Detail ('vendor/OS refused the install and the Files UI helper is missing: ' + $refusal) -Evidence @('app/install-result.txt')
                }
            } else {
                Add-Step -Id 'INST-01' -Title 'install candidate APK' -Scenarios @('REG-01 precondition') -Status BLOCKED -Detail ('vendor/OS refused the install: ' + $refusal) -Evidence @('app/install-result.txt')
            }
        } else {
            Add-Step -Id 'INST-01' -Title 'install candidate APK' -Scenarios @('REG-01 precondition') -Status FAIL -Detail ('adb install failed: ' + (($install.Text -split "`n") | Select-Object -First 4) -join ' ') -Evidence @('app/install-result.txt')
        }
    }

    # Preconditions-only mode: prove adb/device/inventory/install-probe without installing or
    # launching anything. Useful on a device whose vendor restrictions are still being resolved.
    if ($PreconditionsOnly) {
        foreach ($id in @('INST-01', 'APP-01', 'APP-02', 'UI-01', 'UI-02', 'UI-03', 'UI-04')) {
            if ($script:Steps | Where-Object { $_.id -eq $id }) { continue }
            Add-Step -Id $id -Title ($script:StepDefinitions[$id][0]) -Scenarios $script:StepDefinitions[$id][1] -Status NOT_RUN -Detail 'PreconditionsOnly: no install, launch or navigation attempted in this mode.' -Evidence @()
        }
        $script:RunNotes.Add('PreconditionsOnly mode: nothing was installed or launched.')
        Save-Result
    }

    # A blocked/failed install means the installed build (if any) is not this candidate; stop
    # instead of testing stale code.
    $installStep = $script:Steps | Where-Object { $_.id -eq 'INST-01' } | Select-Object -First 1
    if ($installStep -and $installStep.status -in @('BLOCKED', 'FAIL')) {
        foreach ($id in @('APP-01', 'APP-02', 'UI-01', 'UI-02', 'UI-03', 'UI-04')) {
            Add-Step -Id $id -Title ($script:StepDefinitions[$id][0]) -Scenarios $script:StepDefinitions[$id][1] -Status BLOCKED -Detail ('not attempted because INST-01 was ' + $installStep.status + '; no evidence may be attributed to this candidate.') -Evidence @('app/install-result.txt')
        }
        $script:RunNotes.Add('Install did not succeed; launch/navigation steps were not attempted against a possibly stale installed build.')
        Save-Result
    }

    # --- APP-01: launch ---
    $installed = Invoke-Adb -Arguments @('shell', 'pm', 'path', $script:Package) -TimeoutSeconds 30 -EvidenceName 'app/pm-path-after'
    if ($installed.ExitCode -ne 0 -or $installed.Text -notmatch 'package:') {
        Complete-Blocked 'APP-01' 'launch MainActivity, process and foreground' ('Package ' + $script:Package + ' is not installed on the device (pm path empty). Install a build or pass -SkipInstall only when it is already present.') @('REG-01', 'REG-02')
    }
    $resolve = Invoke-Adb -Arguments @('shell', 'cmd', 'package', 'resolve-activity', '--brief', $script:Package) -TimeoutSeconds 30 -EvidenceName 'app/resolve-activity'
    $component = ($resolve.Text -split "`n" | Where-Object { $_ -match '/' } | Select-Object -Last 1)
    $component = if ($component) { $component.Trim() } else { '' }
    if ([string]::IsNullOrWhiteSpace($component)) {
        Complete-Blocked 'APP-01' 'launch MainActivity, process and foreground' ('Cannot resolve a launcher activity for ' + $script:Package + '; the APK may not be installed correctly.') @('REG-01', 'REG-02')
    }
    Write-Evidence 'app/component.txt' $component | Out-Null
    Invoke-Adb -Arguments @('logcat', '-c') -TimeoutSeconds 30 | Out-Null
    $start = Invoke-Adb -Arguments @('shell', 'am', 'start', '-W', '-n', $component) -TimeoutSeconds 90 -EvidenceName 'app/am-start'
    $processStarted = $false
    $deadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $pidof = Invoke-Adb -Arguments @('shell', 'pidof', $script:Package) -TimeoutSeconds 20
        if ($pidof.ExitCode -eq 0 -and $pidof.Text.Trim()) { $processStarted = $true; break }
        if (-not (Test-DeviceOnline)) { break }
        Start-Sleep -Seconds 2
    }
    if (-not $processStarted) {
        if (-not (Test-DeviceOnline)) {
            Complete-Blocked 'APP-01' 'launch MainActivity, process and foreground' 'Device disconnected while waiting for the app process.' @('REG-01', 'REG-02')
        }
        Add-Step -Id 'APP-01' -Title 'launch MainActivity, process and foreground' -Scenarios @('REG-01', 'REG-02') -Status FAIL -Detail ('am start ok=' + ($start.ExitCode -eq 0) + ' but no ' + $script:Package + ' process appeared within ' + $BootTimeoutSeconds + 's; see app/am-start.txt') -Evidence @('app/am-start.txt')
    } else {
        $appPid = (Invoke-Adb -Arguments @('shell', 'pidof', $script:Package) -TimeoutSeconds 20).Text.Trim()
        $foreground = Get-ForegroundActivity
        $isForeground = $foreground.Resumed -match [regex]::Escape($script:Package)
        Add-Step -Id 'APP-01' -Title 'launch MainActivity, process and foreground' -Scenarios @('REG-01', 'REG-02') -Status $(if ($isForeground) { 'PASS' } else { 'FAIL' }) -Detail ('pid=' + $appPid + '; foreground=' + $foreground.Resumed) -Evidence @('app/am-start.txt', 'app/resolve-activity.txt', 'app/activities.txt', 'app/component.txt')
    }

    # --- APP-02: logs, screenshot, process tree ---
    Start-Sleep -Seconds 2
    $logcat = Get-LogcatSlice 'app-first-logs'
    $hasCrash = Get-AnrFatalScan -LogcatText $logcat.Text -Name 'app-first'
    $shot = Save-Screenshot 'app-first-frame'
    $tree = Get-ProcessTree
    $scanStatus = if ($hasCrash) { 'FAIL' } else { 'PASS' }
    Add-Step -Id 'APP-02' -Title 'logcat, ANR/FATAL scan, screenshot, process tree' -Scenarios @('REG-12 precheck') -Status $scanStatus -Detail ('pidof=' + $script:Package + '; anr/fatal=' + $hasCrash + '; screenshot=' + $shot + '; treeMatches=' + (($tree -split "`n").Count)) -Evidence @('logs/app-first-logs.txt', 'logs/app-first-anr-fatal.txt', 'app-first-frame.png', 'app/ps-tree.txt', 'app/process-tree-matches.txt')
    if ($hasCrash) { $script:RunNotes.Add('ANR/FATAL detected after launch; see logs/app-first-anr-fatal.txt') }

    # --- UI flow ---
    if ($SkipUiNavigation) {
        foreach ($id in @('UI-01', 'UI-02', 'UI-03', 'UI-04')) {
            Add-Step -Id $id -Title ($script:StepDefinitions[$id][0]) -Scenarios $script:StepDefinitions[$id][1] -Status NOT_RUN -Detail 'SkipUiNavigation: UI navigation not attempted.' -Evidence @()
        }
        $script:RunNotes.Add('UI navigation skipped by -SkipUiNavigation; REG-01/REG-02/REG-06 have no UI evidence from this run.')
    } else {
        Start-Sleep -Seconds 2
        $ui = Get-UiDump 'ui-01-launch'
        if ($null -eq $ui) {
            Add-Step -Id 'UI-01' -Title 'open sidebar' -Scenarios @('REG-01') -Status FAIL -Detail 'uiautomator dump failed or produced no XML; see ui-01-launch-dump.txt' -Evidence @('ui-01-launch-dump.txt')
        } else {
            $sidebar = Find-UiNode -Document $ui -Selectors $SidebarSelectors
            if (-not $sidebar) {
                # The app resumes on the last tab. The history drawer only exists on the chat
                # surface, so leave the DSH page (back returns to chat) and retry the dump.
                for ($attempt = 0; $attempt -lt 2 -and -not $sidebar; $attempt++) {
                    Invoke-Adb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_BACK') -TimeoutSeconds 15 | Out-Null
                    Start-Sleep -Seconds 2
                    $ui = Get-UiDump ('ui-01-launch-retry' + $attempt)
                    if ($null -ne $ui) { $sidebar = Find-UiNode -Document $ui -Selectors $SidebarSelectors }
                }
            }
            $detail = if ($sidebar) { 'matched "' + $sidebar.Selector + '" label="' + $sidebar.Label + '" at ' + $sidebar.Bounds } else { 'no sidebar selector matched: ' + ($SidebarSelectors -join ', ') + '; inspect ui-01-launch.xml' }
            if (-not $sidebar) {
                Add-Step -Id 'UI-01' -Title 'open sidebar' -Scenarios @('REG-01') -Status FAIL -Detail $detail -Evidence @('ui-01-launch.xml')
            } else {
                Invoke-Tap -X $sidebar.X -Y $sidebar.Y -Name 'ui-01-sidebar' | Out-Null
                Add-Step -Id 'UI-01' -Title 'open sidebar' -Scenarios @('REG-01') -Status PASS -Detail $detail -Evidence @('ui-01-launch.xml', 'ui-01-sidebar-tap.txt', 'ui-01-sidebar-tap-result.txt')
            }
        }
        if (-not (Test-DeviceOnline)) {
            foreach ($id in @('UI-02', 'UI-03', 'UI-04')) {
                Add-Step -Id $id -Title ($script:StepDefinitions[$id][0]) -Scenarios $script:StepDefinitions[$id][1] -Status BLOCKED -Detail 'device disconnected during UI navigation.' -Evidence @()
            }
        } elseif (($script:Steps | Where-Object { $_.id -eq 'UI-01' -and $_.status -ne 'PASS' })) {
            foreach ($id in @('UI-02', 'UI-03', 'UI-04')) {
                Add-Step -Id $id -Title ($script:StepDefinitions[$id][0]) -Scenarios $script:StepDefinitions[$id][1] -Status BLOCKED -Detail 'not attempted because UI-01 (sidebar) did not pass; fix the selector or the UI state and re-run.' -Evidence @()
            }
        } else {
            Start-Sleep -Seconds 1
            $drawer = Get-UiDump 'ui-02-drawer'
            $dshEntry = Find-UiNode -Document $drawer -Selectors $DshSelectors
            if (-not $dshEntry) {
                Add-Step -Id 'UI-02' -Title 'open DSH entry, wait readiness, first frame' -Scenarios @('REG-01', 'REG-02') -Status FAIL -Detail ('no DSH entry selector matched: ' + ($DshSelectors -join ', ') + '; inspect ui-02-drawer.xml') -Evidence @('ui-02-drawer.xml')
            } else {
                Invoke-Tap -X $dshEntry.X -Y $dshEntry.Y -Name 'ui-02-dsh-entry' | Out-Null
                $ready = $false
                $readyEvidence = ''
                $readyDeadline = (Get-Date).AddSeconds($ReadyTimeoutSeconds)
                $attempt = 0
                while ((Get-Date) -lt $readyDeadline) {
                    $attempt++
                    if (-not (Test-DeviceOnline)) { break }
                    $poll = Get-UiDump ('ui-02-ready-' + $attempt) -TimeoutSeconds 20
                    $hasWebView = $false
                    if ($null -ne $poll) {
                        foreach ($node in $poll.SelectNodes('//node')) {
                            if ([string]$node.GetAttribute('class') -match 'WebView') { $hasWebView = $true; break }
                        }
                    }
                    $logs = Get-LogcatSlice ('ui-02-ready-' + $attempt) 800
                    $logHit = $false
                    foreach ($pattern in $ReadyLogPatterns) { if ($logs.Text -match $pattern) { $logHit = $true; break } }
                    if ($hasWebView -or $logHit) {
                        $ready = $true
                        $readyEvidence = 'attempt=' + $attempt + ' webViewNode=' + $hasWebView + ' logMarker=' + $logHit
                        break
                    }
                    Start-Sleep -Seconds 3
                }
                if (-not (Test-DeviceOnline)) {
                    Add-Step -Id 'UI-02' -Title 'open DSH entry, wait readiness, first frame' -Scenarios @('REG-01', 'REG-02') -Status BLOCKED -Detail 'device disconnected while waiting for DSH readiness.' -Evidence @()
                } elseif (-not $ready) {
                    Add-Step -Id 'UI-02' -Title 'open DSH entry, wait readiness, first frame' -Scenarios @('REG-01', 'REG-02') -Status FAIL -Detail ('DSH readiness not observed within ' + $ReadyTimeoutSeconds + 's (no WebView node, no ready log marker); inspect ui-02-ready-* and logs/ui-02-ready-*') -Evidence @('ui-02-drawer.xml', 'ui-02-dsh-entry-tap-result.txt')
                } else {
                    $frame = Save-Screenshot 'ui-02-first-frame'
                    $foreground = Get-ForegroundActivity
                    Add-Step -Id 'UI-02' -Title 'open DSH entry, wait readiness, first frame' -Scenarios @('REG-01', 'REG-02') -Status PASS -Detail ('entry="' + $dshEntry.Label + '"; ' + $readyEvidence + '; screenshot=' + $frame + '; foreground=' + $foreground.Resumed) -Evidence @('ui-02-drawer.xml', 'ui-02-dsh-entry-tap-result.txt', 'ui-02-first-frame.png', 'app/activities.txt')
                }
            }
            $ui02Step = $script:Steps | Where-Object { $_.id -eq 'UI-02' } | Select-Object -First 1
            $ui02Passed = [bool]($ui02Step -and ([string]$ui02Step.status) -eq 'PASS')
            if ((Test-DeviceOnline) -and (-not $ui02Passed)) {
                foreach ($id in @('UI-03', 'UI-04')) {
                    Add-Step -Id $id -Title ($script:StepDefinitions[$id][0]) -Scenarios $script:StepDefinitions[$id][1] -Status BLOCKED -Detail ('not attempted because UI-02 was ' + $ui02Step.status + '; fix the DSH entry/readiness before capturing console evidence.') -Evidence @()
                }
            } elseif (Test-DeviceOnline) {
                $consoleDump = Get-UiDump 'ui-03-console'
                $console = Find-UiNode -Document $consoleDump -Selectors $ConsoleSelectors
                if (-not $console) {
                    Add-Step -Id 'UI-03' -Title 'open DSH console, capture capability states' -Scenarios @('REG-06') -Status FAIL -Detail ('no console selector matched: ' + ($ConsoleSelectors -join ', ') + '; inspect ui-03-console.xml') -Evidence @('ui-03-console.xml')
                } else {
                    Invoke-Tap -X $console.X -Y $console.Y -Name 'ui-03-console' | Out-Null
                    # The console is a paged Compose list; the state words may be Chinese
                    # (\u53ef\u7528 = available, \u672a\u542f\u7528 = not enabled). Build the pattern
                    # from codepoints so this script stays ASCII and parses under Windows PowerShell 5.1.
                    $available = -join ([char]0x53EF, [char]0x7528)
                    $notEnabled = -join ([char]0x672A, [char]0x542F, [char]0x7528)
                    $unavailable = -join ([char]0x4E0D, [char]0x53EF, [char]0x7528)
                    $statePattern = 'GRANTED|DENIED|NOT_DECLARED|NOT_ENABLED|UNSUPPORTED|NOTIFICATION|PRIVILEGED_SHELL|ANDROID_SYSTEM|ACCESSIBILITY|VIRTUAL_SCREEN|BROWSER|' + [regex]::Escape($available) + '|' + [regex]::Escape($notEnabled) + '|' + [regex]::Escape($unavailable)
                    $tokens = [System.Collections.Generic.List[string]]::new()
                    for ($capAttempt = 0; $capAttempt -lt 3 -and $tokens.Count -eq 0; $capAttempt++) {
                        Start-Sleep -Seconds 2
                        $capDump = Get-UiDump ('ui-03-capabilities' + $(if ($capAttempt -eq 0) { '' } else { '-retry' + $capAttempt }))
                        if ($null -eq $capDump) { continue }
                        foreach ($node in $capDump.SelectNodes('//node')) {
                            foreach ($attributeName in @('text', 'content-desc')) {
                                $value = [string]$node.GetAttribute($attributeName)
                                if ($value -match $statePattern) { $tokens.Add($value) }
                            }
                        }
                    }
                    $uniqueTokens = $tokens | Select-Object -Unique
                    Write-Evidence 'ui-03-capability-tokens.txt' (($uniqueTokens) -join "`n") | Out-Null
                    $consoleStatus = if (($uniqueTokens | Measure-Object).Count -gt 0) { 'PASS' } else { 'FAIL' }
                    Add-Step -Id 'UI-03' -Title 'open DSH console, capture capability states' -Scenarios @('REG-06') -Status $consoleStatus -Detail ('console="' + $console.Label + '"; capabilityTokens=' + (($uniqueTokens | Select-Object -First 6) -join ' | ')) -Evidence @('ui-03-console.xml', 'ui-03-console-tap-result.txt', 'ui-03-capabilities.xml', 'ui-03-capability-tokens.txt')
                }
                $finalLogcat = Get-LogcatSlice 'ui-04-final-logs'
                $finalCrash = Get-AnrFatalScan -LogcatText $finalLogcat.Text -Name 'ui-04-final'
                $finalTree = Get-ProcessTree
                $finalForeground = Get-ForegroundActivity
                Add-Step -Id 'UI-04' -Title 'post-navigation foreground/process/ANR capture' -Scenarios @('REG-12 precheck') -Status $(if ($finalCrash) { 'FAIL' } else { 'PASS' }) -Detail ('anr/fatal=' + $finalCrash + '; foreground=' + $finalForeground.Resumed + '; treeMatches=' + (($finalTree -split "`n").Count)) -Evidence @('logs/ui-04-final-logs.txt', 'logs/ui-04-final-anr-fatal.txt', 'app/ps-tree.txt', 'app/activities.txt')
            } else {
                foreach ($id in @('UI-03', 'UI-04')) {
                    Add-Step -Id $id -Title ($script:StepDefinitions[$id][0]) -Scenarios $script:StepDefinitions[$id][1] -Status BLOCKED -Detail 'device disconnected before this step.' -Evidence @()
                }
            }
        }
    }

    Save-Result
} catch {
    $message = $_.Exception.Message
    try {
        if (-not (Test-Path -LiteralPath $script:OutDir)) { Initialize-EvidenceDir }
        Add-Step -Id 'HARNESS' -Title 'unexpected harness error' -Scenarios @() -Status FAIL -Detail ('harness exception: ' + $message) -Evidence @('commands.log')
        $script:RunNotes.Add('harness exception: ' + $message)
        Save-Result
    } catch {
        Write-Error ('device acceptance harness crashed: ' + $message)
        exit 1
    }
}
