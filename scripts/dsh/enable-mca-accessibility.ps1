<#
.SYNOPSIS
    Enables (or verifies) the MCA Agent accessibility service on a connected device, so the
    `device.screen` tool stops failing with SCREEN_SERVICE_DISABLED after every reinstall.

.DESCRIPTION
    Accessibility is one of the few grants Android refuses to hand out from adb: writing
    `enabled_accessibility_services` alone frequently does NOT make the system bind the service
    (`onServiceConnected` never fires), and there is no shell command that performs the user's
    confirmation dialog. This script therefore does three honest things:

      1. Checks whether the service is already BOUND. That is the only reliable success signal,
         and it is what makes the script idempotent: a bound service is left completely alone.
      2. If not bound, tries the only adb-level levers that exist: enable the service in
         `secure` settings (preserving every other enabled service), then broadcast the
         accessibility bind action as a nudge.
      3. If the service still is not bound after the timeout, it does NOT pretend to have
         succeeded. It prints the exact manual path, restores the settings it changed, and exits
         with code 2 so a caller can distinguish "needs a human" from "the script broke".

    The script never attempts root and never tries to bypass the system consent dialog.

.PARAMETER Device
    adb serial. Defaults to the only connected device; required when several are attached.

.PARAMETER TimeoutSeconds
    How long to wait for the service to become bound after enabling it. Default 15.

.PARAMETER AdbPath
    adb executable. Defaults to the SDK on this machine.

.PARAMETER KeepPartialSettings
    Keeps `enabled_accessibility_services` / `accessibility_enabled` as written even when the
    service did not bind. Off by default: a switch that reads ON while the service is disconnected
    is worse than the original state.

.PARAMETER Quiet
    Suppresses the per-step progress lines (the verdict is still printed).

.EXAMPLE
    .\enable-mca-accessibility.ps1 -Device f9d98aa3
    .\enable-mca-accessibility.ps1 -Device f9d98aa3 -TimeoutSeconds 30

.NOTES
    Exit codes:
      0  PASS     - the service is bound (already, or became bound after enabling)
      1  FAIL     - adb/device/package problem; nothing about accessibility could be verified
      2  NEEDS_USER - adb did everything it can; the user must toggle the switch manually
#>
[CmdletBinding()]
param(
    [string]$Device,
    [ValidateRange(1, 300)]
    [int]$TimeoutSeconds = 15,
    [string]$AdbPath = 'E:\model\android-sdk\platform-tools\adb.exe',
    [switch]$KeepPartialSettings,
    [switch]$Quiet
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ExitPass = 0
$ExitFail = 1
$ExitNeedsUser = 2

# The activity/service the `device.screen` tool talks to. Kept in one place so a rename is a
# one-line change here.
$PackageName = 'com.muyuchat.mca'
$ServiceComponent = 'com.muyuchat.mca/com.muyuchat.core.dsh.a11y.android.DshA11yService'
$ServiceShortName = 'DshA11yService'
$ServiceLabel = 'DeepSeek Harness 屏幕助手'
# The DSH screen helper is a different service with its own consent; it is reported but never
# enabled by this script.
$OtherServiceLabel = 'DeepSeek Harness'

function Write-Step([string]$Message) {
    if (-not $Quiet) { Write-Host "  $Message" }
}

function Write-Verdict([string]$Code, [string]$Message) {
    Write-Host "[$Code] $Message"
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory)][string[]]$Arguments,
        [switch]$AllowFailure
    )
    $output = & $AdbPath -s $script:Serial @Arguments 2>&1
    $exit = $LASTEXITCODE
    if ($exit -ne 0 -and -not $AllowFailure) {
        throw "adb $($Arguments -join ' ') failed (exit $exit): $($output -join ' ')"
    }
    return [pscustomobject]@{ Exit = $exit; Text = ($output -join "`n") }
}

function Get-AdbSerial {
    if ($Device) { return $Device }
    $lines = & $AdbPath devices 2>&1
    $ready = @($lines | Select-Object -Skip 1 | Where-Object { $_ -match '^\S+\s+device$' } |
            ForEach-Object { ($_ -split '\s+')[0] })
    if ($ready.Count -eq 1) { return $ready[0] }
    if ($ready.Count -eq 0) {
        throw 'No device is connected. Start the device, run "adb devices", then retry.'
    }
    throw "Several devices are connected ($($ready -join ', ')); pass -Device <serial>."
}

<#
    Reads the "Bound services" section of `dumpsys accessibility` for the current user.

    The dump prints one block per user and the block content differs across Android versions
    (a component name on some, a formatted AccessibilityServiceInfo on others), so the section is
    matched by its own header and closed at the next known header, then searched for either the
    component or the user-visible label. Section bodies are the ONLY thing inspected, so a service
    that merely appears in "Enabled services" or "Binding services" is not counted as bound.
#>
function Get-BoundServiceBlocks {
    param([Parameter(Mandatory)][string]$DumpText)
    $blocks = New-Object System.Collections.Generic.List[string]
    $current = $null
    foreach ($line in ($DumpText -split "`r?`n")) {
        if ($line -match '^\s*Bound services:') {
            if ($null -ne $current) { $blocks.Add($current) }
            $current = $line
            continue
        }
        if ($null -ne $current) {
            if ($line -match '^\s*(Enabled|Binding|Crashed) services:' -or
                $line -match '^\s*User state\[' -or
                $line -match '^\s*Magnification' -or
                $line -match '^\s*currentUserId=') {
                $blocks.Add($current)
                $current = $null
                continue
            }
            $current += "`n$line"
        }
    }
    if ($null -ne $current) { $blocks.Add($current) }
    return $blocks
}

function Test-ServiceBound {
    param([Parameter(Mandatory)][string]$DumpText)
    foreach ($block in (Get-BoundServiceBlocks -DumpText $DumpText)) {
        if ($block -match [regex]::Escape($ServiceShortName) -or
            $block -match [regex]::Escape($ServiceLabel)) {
            return $true
        }
    }
    return $false
}

function Get-AccessibilityDump {
    $result = Invoke-Adb -Arguments @('shell', 'dumpsys', 'accessibility') -AllowFailure
    if ($result.Exit -ne 0 -or [string]::IsNullOrWhiteSpace($result.Text)) {
        throw "Could not read accessibility state: $($result.Text)"
    }
    return $result.Text
}

# ---------------------------------------------------------------- preflight

if (-not (Test-Path -LiteralPath $AdbPath)) {
    Write-Verdict 'FAIL' "adb not found at $AdbPath. Pass -AdbPath."
    exit $ExitFail
}

try {
    $script:Serial = Get-AdbSerial
} catch {
    Write-Verdict 'FAIL' $_.Exception.Message
    exit $ExitFail
}

if (-not $Quiet) { Write-Host "Device: $($script:Serial)" }

try {
    $packages = (Invoke-Adb -Arguments @('shell', 'pm', 'list', 'packages', $PackageName)).Text
    if ($packages -notmatch [regex]::Escape($PackageName)) {
        Write-Verdict 'FAIL' "$PackageName is not installed on $($script:Serial). Install the APK first."
        exit $ExitFail
    }
} catch {
    Write-Verdict 'FAIL' "Device $($script:Serial) is not reachable over adb: $($_.Exception.Message)"
    exit $ExitFail
}

# ---------------------------------------------------------------- 1. already bound?

try {
    $dump = Get-AccessibilityDump
} catch {
    Write-Verdict 'FAIL' $_.Exception.Message
    exit $ExitFail
}

if (Test-ServiceBound -DumpText $dump) {
    Write-Verdict 'PASS' "$ServiceLabel is bound; nothing to do."
    exit $ExitPass
}

Write-Step "not bound yet; enabling $ServiceLabel"

# ---------------------------------------------------------------- 2. enable through settings

$originalServices = $null
$originalEnabled = $null
$settingsChanged = $false
try {
    $originalServices = (Invoke-Adb -Arguments @('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services') -AllowFailure).Text.Trim()
    if ($originalServices -eq 'null') { $originalServices = $null }
    $originalEnabled = (Invoke-Adb -Arguments @('shell', 'settings', 'get', 'secure', 'accessibility_enabled') -AllowFailure).Text.Trim()
    if ($originalEnabled -eq 'null') { $originalEnabled = $null }

    # Preserve every other enabled service: replacing the list would silently disable the user's
    # screen reader / password manager.
    $existing = @()
    if ($originalServices) {
        $existing = @($originalServices -split ':' | Where-Object {
                $_ -and $_ -ne $ServiceComponent -and $_ -ne $ServiceLabel
            })
    }
    $desired = (@($existing) + $ServiceComponent) -join ':'

    $put = Invoke-Adb -Arguments @('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', $desired) -AllowFailure
    if ($put.Exit -ne 0) {
        Write-Step "settings put was refused: $($put.Text.Trim())"
    } else {
        $settingsChanged = $true
    }
    $enabledPut = Invoke-Adb -Arguments @('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1') -AllowFailure
    if ($enabledPut.Exit -eq 0) { $settingsChanged = $true }

    # Read back: an OEM can accept the command and still discard the value.
    $verified = (Invoke-Adb -Arguments @('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services') -AllowFailure).Text.Trim()
    if ($verified -notmatch [regex]::Escape($ServiceShortName)) {
        Write-Step 'the service list did not keep the new entry'
    } else {
        Write-Step 'service listed in secure settings'
    }
} catch {
    Write-Step "could not write secure settings: $($_.Exception.Message)"
}

# The documented nudge. On production builds this broadcast is protected by
# BIND_ACCESSIBILITY_SERVICE and is expected to be rejected; it is attempted anyway because a
# debug/permissive build may accept it, and a rejection is not a failure of this script.
try {
    $broadcast = Invoke-Adb -Arguments @(
        'shell', 'am', 'broadcast',
        '-a', 'android.accessibilityservice.AccessibilityService.BIND_ACCESSIBILITY_SERVICE'
    ) -AllowFailure
    if ($broadcast.Exit -eq 0 -and $broadcast.Text -notmatch 'SecurityException|Permission Denial') {
        Write-Step 'bind broadcast sent'
    } else {
        Write-Step 'bind broadcast rejected by the system (expected; it needs the signature permission)'
    }
} catch {
    Write-Step "bind broadcast could not be sent: $($_.Exception.Message)"
}

# ---------------------------------------------------------------- 3. poll for the real signal

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$bound = $false
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 1000
    try {
        $dump = Get-AccessibilityDump
    } catch {
        continue
    }
    if (Test-ServiceBound -DumpText $dump) {
        $bound = $true
        break
    }
}

if ($bound) {
    Write-Verdict 'PASS' "$ServiceLabel is bound and ready for device.screen."
    exit $ExitPass
}

# ---------------------------------------------------------------- 4. tell the user exactly what to do

if ($settingsChanged -and -not $KeepPartialSettings) {
    # A switch that reads ON while the service is disconnected is worse than the original state:
    # it hides the fact that consent was never given.
    try {
        if ($null -eq $originalServices) {
            Invoke-Adb -Arguments @('shell', 'settings', 'delete', 'secure', 'enabled_accessibility_services') -AllowFailure | Out-Null
        } else {
            Invoke-Adb -Arguments @('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', $originalServices) -AllowFailure | Out-Null
        }
        $enabledValue = if ([string]::IsNullOrWhiteSpace($originalEnabled)) { '0' } else { $originalEnabled }
        Invoke-Adb -Arguments @('shell', 'settings', 'put', 'secure', 'accessibility_enabled', $enabledValue) -AllowFailure | Out-Null
        Write-Step 'restored the previous accessibility settings'
    } catch {
        Write-Step "could not restore the previous settings: $($_.Exception.Message)"
    }
}

Write-Host ''
Write-Host 'NEEDS_USER: adb cannot grant accessibility; Android requires the user to confirm it.'
Write-Host ''
Write-Host 'On the device, do exactly this once:'
Write-Host '  1. Settings (设置)'
Write-Host '  2. Accessibility (无障碍)'
Write-Host '  3. Downloaded apps / Installed services (已下载的服务 / 已安装的应用)'
Write-Host '  4. DeepSeek Harness 屏幕助手'
Write-Host '  5. Turn it ON (打开/使用该服务)'
Write-Host '  6. Allow / OK on the confirmation dialog (允许)'
Write-Host ''
Write-Host "Then re-run this script; it will print PASS and exit 0."
Write-Host ''
Write-Verdict 'NEEDS_USER' "$ServiceLabel is not bound after ${TimeoutSeconds}s."
exit $ExitNeedsUser
