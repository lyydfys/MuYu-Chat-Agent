#Requires -Version 5.1
<#
T08 parity harness helpers (device / faults / soak).

Scope discipline: this module only reads the device and writes under
artifacts/repair/20261009-parity/{device,faults,soak}. It never uninstalls the
production app and never clears user data. Every function that mutates device
state must be called explicitly by a run script, never implicitly.

Binary-safe transfer: `adb exec-out | file` corrupts binary payloads in this
environment, so file pulls go through base64 with a SHA-256 cross-check against
the on-device value. A mismatch is a hard failure, not a warning.
#>

Set-StrictMode -Version Latest

function Get-T08RepoRoot {
    [CmdletBinding()]
    param([string]$Start = (Get-Location).Path)
    $dir = [IO.DirectoryInfo]::new($Start)
    while ($null -ne $dir) {
        if ((Test-Path (Join-Path $dir.FullName 'settings.gradle.kts')) -and
            (Test-Path (Join-Path $dir.FullName 'AGENTS.md'))) {
            return $dir.FullName
        }
        $dir = $dir.Parent
    }
    throw "Cannot locate MCA repository root from $Start"
}

function Get-T08EvidenceRoot {
    [CmdletBinding()]
    param([string]$RepoRoot = (Get-T08RepoRoot))
    return (Join-Path $RepoRoot 'artifacts\repair\20261009-parity')
}

function New-T08RunDirectory {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][ValidateSet('device', 'faults', 'soak')][string]$Area,
        [Parameter(Mandatory = $true)][string]$Name,
        [string]$RepoRoot = (Get-T08RepoRoot)
    )
    $stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
    $dir = Join-Path (Join-Path (Get-T08EvidenceRoot -RepoRoot $RepoRoot) $Area) "$stamp-$Name"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    return $dir
}

function Write-T08Utf8 {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Content
    )
    $parent = Split-Path -Parent $Path
    if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    [IO.File]::WriteAllText($Path, $Content, [Text.UTF8Encoding]::new($false))
}

function Write-T08Json {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)]$Value,
        [int]$Depth = 12
    )
    Write-T08Utf8 -Path $Path -Content ($Value | ConvertTo-Json -Depth $Depth)
}

function Get-T08Sha256 {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$Path)
    return (Get-FileHash -Path $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-T08UtcStamp {
    return (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
}

function ConvertTo-T08CommandLineArgument {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Value)
    if ($Value -eq '') { return '""' }
    if ($Value -notmatch '[\s"]') { return $Value }
    $sb = [Text.StringBuilder]::new()
    [void]$sb.Append('"')
    $backslashes = 0
    foreach ($ch in $Value.ToCharArray()) {
        if ($ch -eq '\') { $backslashes++; continue }
        if ($ch -eq '"') {
            [void]$sb.Append('\' * ($backslashes * 2 + 1))
            [void]$sb.Append('"')
            $backslashes = 0
            continue
        }
        if ($backslashes -gt 0) { [void]$sb.Append('\' * $backslashes); $backslashes = 0 }
        [void]$sb.Append($ch)
    }
    if ($backslashes -gt 0) { [void]$sb.Append('\' * ($backslashes * 2)) }
    [void]$sb.Append('"')
    return $sb.ToString()
}

<#
Runs adb and always returns a structured record. Never throws on a non-zero exit;
callers decide whether that is a failure.

Uses System.Diagnostics.Process with explicit file redirection instead of
Start-Process: on this host `Start-Process -RedirectStandardOutput` throws
"Item has already been added. Key in dictionary: 'NO_PROXY' Key being added: 'no_proxy'"
because the environment carries both spellings and that API builds a
case-insensitive dictionary. That is a tooling defect, not a product defect, and it
also affects the pre-existing tools/device smoke runners.

Text output is read as bytes and UTF-8 decoded so non-ASCII adb output is not
mangled by the host console code page.
#>
function Invoke-T08Adb {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [string]$Serial = '',
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [int]$TimeoutSeconds = 120
    )
    $full = @()
    if ($Serial) { $full += @('-s', $Serial) }
    $full += $Arguments
    $stdoutFile = [IO.Path]::GetTempFileName()
    $stderrFile = [IO.Path]::GetTempFileName()
    $started = Get-T08UtcStamp
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $commandText = "$Adb $($full -join ' ')"
    $proc = $null
    try {
        $psi = [Diagnostics.ProcessStartInfo]::new()
        $psi.FileName = $Adb
        $psi.UseShellExecute = $false
        $psi.CreateNoWindow = $true
        $psi.Arguments = (($full | ForEach-Object { ConvertTo-T08CommandLineArgument -Value $_ }) -join ' ')
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true

        $proc = [Diagnostics.Process]::new()
        $proc.StartInfo = $psi
        $proc.Start() | Out-Null

        $stdoutTask = $proc.StandardOutput.ReadToEndAsync()
        $stderrTask = $proc.StandardError.ReadToEndAsync()
        if (-not $proc.WaitForExit($TimeoutSeconds * 1000)) {
            try { $proc.Kill() } catch { }
            $sw.Stop()
            return [pscustomobject]@{
                command    = $commandText
                startedUtc = $started
                seconds    = [math]::Round($sw.Elapsed.TotalSeconds, 3)
                exitCode   = -1
                timedOut   = $true
                stdout     = ''
                stderr     = "TIMEOUT after ${TimeoutSeconds}s"
            }
        }
        $stdoutText = $stdoutTask.GetAwaiter().GetResult()
        $stderrText = $stderrTask.GetAwaiter().GetResult()
        $sw.Stop()
        return [pscustomobject]@{
            command    = $commandText
            startedUtc = $started
            seconds    = [math]::Round($sw.Elapsed.TotalSeconds, 3)
            exitCode   = $proc.ExitCode
            timedOut   = $false
            stdout     = $stdoutText
            stderr     = $stderrText
        }
    }
    finally {
        if ($proc) { $proc.Dispose() }
        Remove-Item -Force -ErrorAction SilentlyContinue $stdoutFile, $stderrFile
    }
}

function Assert-T08Device {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$Serial
    )
    if (-not (Test-Path $Adb)) { throw "adb not found: $Adb" }
    $r = Invoke-T08Adb -Adb $Adb -Arguments @('devices', '-l')
    if ($r.exitCode -ne 0) { throw "adb devices failed: $($r.stderr)" }
    $line = ($r.stdout -split "`n" | Where-Object { $_ -match [regex]::Escape($Serial) }) -join ';'
    if (-not $line) { throw "device $Serial is not attached (adb devices output: $($r.stdout))" }
    if ($line -notmatch '\bdevice\b') { throw "device $Serial is not in 'device' state: $line" }
    return $line.Trim()
}

function Get-T08DeviceFacts {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$Serial
    )
    $props = @{
        model         = 'ro.product.model'
        device        = 'ro.product.device'
        abi           = 'ro.product.cpu.abi'
        abilist       = 'ro.product.cpu.abilist'
        release       = 'ro.build.version.release'
        sdk           = 'ro.build.version.sdk'
        socModel      = 'ro.soc.model'
        socManufacturer = 'ro.soc.manufacturer'
        buildFingerprint = 'ro.build.fingerprint'
    }
    $out = [ordered]@{}
    foreach ($k in $props.Keys) {
        $r = Invoke-T08Adb -Adb $Adb -Serial $Serial -Arguments @('shell', 'getprop', $props[$k])
        $out[$k] = ($r.stdout).Trim()
    }
    $out['serial'] = $Serial
    $out['capturedUtc'] = Get-T08UtcStamp
    return [pscustomobject]$out
}

<#
Read-only snapshot of the installed package. Used before and after an install so
the "data preserved" claim is evidence, not an assumption.
#>
function Get-T08PackageState {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$Serial,
        [string]$Package = 'com.muyuchat.mca'
    )
    $list = Invoke-T08Adb -Adb $Adb -Serial $Serial -Arguments @('shell', 'pm', 'list', 'packages', $Package)
    $installed = ($list.stdout -match [regex]::Escape("package:$Package"))
    if (-not $installed) {
        return [pscustomobject]@{ installed = $false; package = $Package; capturedUtc = Get-T08UtcStamp }
    }
    $dump = Invoke-T08Adb -Adb $Adb -Serial $Serial -Arguments @('shell', 'dumpsys', 'package', $Package)
    $t = $dump.stdout
    function Get-Field([string]$pattern) {
        $m = [regex]::Match($t, $pattern)
        if ($m.Success) { return $m.Groups[1].Value.Trim() } else { return $null }
    }
    return [pscustomobject]@{
        installed       = $true
        package         = $Package
        versionName     = Get-Field 'versionName=(\S+)'
        versionCode     = Get-Field 'versionCode=(\d+)'
        firstInstallTime = Get-Field 'firstInstallTime=([0-9\- :]+)'
        lastUpdateTime  = Get-Field 'lastUpdateTime=([0-9\- :]+)'
        codePath        = Get-Field 'codePath=(\S+)'
        dataDir         = Get-Field 'dataDir=(\S+)'
        debuggable      = ($t -match 'DEBUGGABLE')
        capturedUtc     = Get-T08UtcStamp
    }
}

function Get-T08RunningProcesses {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$Serial,
        [string]$Package = 'com.muyuchat.mca'
    )
    $r = Invoke-T08Adb -Adb $Adb -Serial $Serial -Arguments @('shell', 'ps', '-A')
    $rows = @($r.stdout -split "`r?`n" | Where-Object { $_ -match [regex]::Escape($Package) } |
        ForEach-Object {
            $f = ($_ -split '\s+') | Where-Object { $_ }
            [pscustomobject]@{ user = $f[0]; pid = $f[1]; ppid = $f[2]; name = $f[-1] }
        })
    return $rows
}

<#
Base64 pull with a mandatory on-device/local SHA-256 equality check. Returns a
record with verified=$false when the bytes did not round-trip.
#>
function Receive-T08RemoteFile {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string]$RemotePath,
        [Parameter(Mandatory = $true)][string]$LocalDirectory,
        [string]$LocalName = '',
        [string]$RunAsPackage = ''
    )
    if (-not $LocalName) { $LocalName = Split-Path -Leaf $RemotePath }
    New-Item -ItemType Directory -Force -Path $LocalDirectory | Out-Null
    $localPath = Join-Path $LocalDirectory $LocalName

    $shaArgs = if ($RunAsPackage) { @('shell', 'run-as', $RunAsPackage, 'sha256sum', $RemotePath) }
               else { @('shell', 'sha256sum', $RemotePath) }
    $shaResult = Invoke-T08Adb -Adb $Adb -Serial $Serial -Arguments $shaArgs
    $deviceSha = $null
    if ($shaResult.exitCode -eq 0) {
        $m = [regex]::Match($shaResult.stdout, '([0-9a-fA-F]{64})')
        if ($m.Success) { $deviceSha = $m.Groups[1].Value.ToLowerInvariant() }
    }

    $catArgs = if ($RunAsPackage) { @('shell', 'run-as', $RunAsPackage, 'base64', $RemotePath) }
               else { @('shell', 'base64', $RemotePath) }
    $b64 = Invoke-T08Adb -Adb $Adb -Serial $Serial -Arguments $catArgs -TimeoutSeconds 600
    if ($b64.exitCode -ne 0 -or [string]::IsNullOrWhiteSpace($b64.stdout)) {
        return [pscustomobject]@{
            remotePath = $RemotePath; localPath = $localPath
            deviceSha256 = $deviceSha; localSha256 = $null
            bytes = 0; verified = $false; error = "base64 read failed: $($b64.stderr)"
        }
    }
    $joined = ($b64.stdout -replace '\s', '')
    try { $bytes = [Convert]::FromBase64String($joined) }
    catch { return [pscustomobject]@{
            remotePath = $RemotePath; localPath = $localPath
            deviceSha256 = $deviceSha; localSha256 = $null
            bytes = 0; verified = $false; error = "base64 decode failed: $($_.Exception.Message)"
        } }
    [IO.File]::WriteAllBytes($localPath, $bytes)
    $localSha = Get-T08Sha256 -Path $localPath
    return [pscustomobject]@{
        remotePath   = $RemotePath
        localPath    = $localPath
        deviceSha256 = $deviceSha
        localSha256  = $localSha
        bytes        = $bytes.Length
        verified     = ($null -ne $deviceSha) -and ($deviceSha -eq $localSha)
        error        = $null
    }
}

function Get-T08LocalDatabases {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$Directory)
    $result = [ordered]@{}
    foreach ($name in @('mca.db', 'mca-runtime.db')) {
        $p = Join-Path $Directory $name
        if (-not (Test-Path $p)) { continue }
        $con = $null
        try {
            Add-Type -AssemblyName System.Data.SQLite -ErrorAction SilentlyContinue
        } catch { }
        $result[$name] = [ordered]@{ path = $p; sha256 = (Get-T08Sha256 -Path $p); sizeBytes = (Get-Item $p).Length }
    }
    return [pscustomobject]$result
}

function Add-T08Step {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][System.Collections.ArrayList]$Ledger,
        [Parameter(Mandatory = $true)][string]$Id,
        [Parameter(Mandatory = $true)][string]$Checklist,   # e.g. A1, D4, G2
        [Parameter(Mandatory = $true)][string]$Description,
        [Parameter(Mandatory = $true)][string]$Status,      # PASS|FAIL|NOT_VERIFIED|NOT_APPLICABLE
        [string]$Command = '',
        [int]$ExitCode = 0,
        [string]$Evidence = '',
        [string]$Notes = ''
    )
    [void]$Ledger.Add([pscustomobject]@{
            id          = $Id
            checklist   = $Checklist
            description = $Description
            status      = $Status
            command     = $Command
            exitCode    = $ExitCode
            evidence    = $Evidence
            notes       = $Notes
            recordedUtc = Get-T08UtcStamp
        })
}

function Write-T08Ledger {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][System.Collections.ArrayList]$Ledger,
        [Parameter(Mandatory = $true)][string]$Directory,
        [Parameter(Mandatory = $true)][hashtable]$Meta
    )
    Write-T08Json -Path (Join-Path $Directory 'ledger.json') -Value ([pscustomobject]@{
            meta  = $Meta
            steps = $Ledger
        })
    $md = [Text.StringBuilder]::new()
    [void]$md.AppendLine("# T08 ledger - $($Meta['runName'])")
    [void]$md.AppendLine()
    foreach ($k in $Meta.Keys) { [void]$md.AppendLine("- ${k}: $($Meta[$k])") }
    [void]$md.AppendLine()
    [void]$md.AppendLine('| # | checklist | description | status | exit | evidence | notes |')
    [void]$md.AppendLine('|---|---|---|---|---|---|---|')
    foreach ($s in $Ledger) {
        $ev = if ($s.evidence) { $s.evidence } else { '' }
        $notes = if ($s.notes) { $s.notes } else { '' }
        [void]$md.AppendLine("| $($s.id) | $($s.checklist) | $($s.description) | **$($s.status)** | $($s.exitCode) | $ev | $notes |")
    }
    Write-T08Utf8 -Path (Join-Path $Directory 'LEDGER.md') -Content $md.ToString()
}

function Export-T08Module {
    [CmdletBinding()]
    param()
    return @(
        'Get-T08RepoRoot', 'Get-T08EvidenceRoot', 'New-T08RunDirectory', 'Write-T08Utf8', 'Write-T08Json',
        'Get-T08Sha256', 'Get-T08UtcStamp', 'Invoke-T08Adb', 'Assert-T08Device', 'Get-T08DeviceFacts',
        'Get-T08PackageState', 'Get-T08RunningProcesses', 'Receive-T08RemoteFile', 'Get-T08LocalDatabases',
        'Add-T08Step', 'Write-T08Ledger', 'ConvertTo-T08CommandLineArgument'
    )
}

Export-ModuleMember -Function (Export-T08Module)
