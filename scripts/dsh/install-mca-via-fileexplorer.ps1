#Requires -Version 5.1
<#
.SYNOPSIS
  Installs the MCA debug APK on a MIUI/HyperOS device through the on-device Files UI.

.DESCRIPTION
  MIUI can refuse `adb install` with INSTALL_FAILED_USER_RESTRICTED even when USB debugging is
  enabled (the "USB install" policy is disabled without a SIM/Mi account). Installing through the
  Files app is a normal user install and is not subject to the ADB policy. This helper pushes the
  APK, opens the Downloads folder, taps the file, and confirms only the positive dialogs
  (Allow / Continue install / Install / Done). It never taps Cancel, Exit or Deny.

  This is a test-device convenience only: it automates the same taps a developer would perform.
  It does not change any system policy and does not bypass user consent.

.EXAMPLE
  pwsh -NoProfile -File scripts/dsh/install-mca-via-fileexplorer.ps1 -Device f9d98aa3 -Apk app/build/outputs/apk/debug/app-debug.apk
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Device,
    [Parameter(Mandatory = $true)][string]$Apk,
    [string]$RemotePath = '/sdcard/Download/mca-debug.apk',
    [int]$TimeoutSeconds = 300,
    [string]$Package = 'com.muyuchat.mca',
    [string]$LogPath = 'artifacts/dsh-v2/verification/device-install-via-files.log'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$adb = 'D:\model\android-sdk\platform-tools\adb.exe'
if (-not (Test-Path $adb)) { $adb = 'adb' }
$logDir = Split-Path -Parent $LogPath
if ($logDir -and -not (Test-Path $logDir)) { New-Item -ItemType Directory -Force -Path $logDir | Out-Null }
function Log([string]$line) {
    $stamp = (Get-Date).ToString('HH:mm:ss')
    Write-Host "[$stamp] $line"
    Add-Content -LiteralPath $LogPath -Value "[$stamp] $line" -Encoding UTF8
}
function Adb([string[]]$arguments) {
    # adb writes progress to stderr; under $ErrorActionPreference=Stop a redirected stderr record
    # would abort the helper. Keep the preference local so callers can inspect the text instead.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $adb -s $Device @arguments 2>&1
        return ($out | Out-String)
    }
    finally {
        $ErrorActionPreference = $previous
    }
}
function Get-UiXml {
    Adb @('shell', 'uiautomator', 'dump', '/sdcard/mca-install-ui.xml') | Out-Null
    $raw = Adb @('shell', 'cat', '/sdcard/mca-install-ui.xml')
    if (-not $raw -or $raw -notmatch '<hierarchy') { return $null }
    try { return [xml]$raw } catch { return $null }
}
function Tap-Node($node) {
    if ($null -eq $node) { return $false }
    $bounds = [string]$node.bounds
    $m = [regex]::Match($bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
    if (-not $m.Success) { return $false }
    $x = [int](($m.Groups[1].Value -as [int]) + (($m.Groups[3].Value -as [int]) - ($m.Groups[1].Value -as [int])) / 2)
    $y = [int](($m.Groups[2].Value -as [int]) + (($m.Groups[4].Value -as [int]) - ($m.Groups[2].Value -as [int])) / 2)
    Adb @('shell', 'input', 'tap', "$x", "$y") | Out-Null
    Log "tap '$($node.text)' at $x,$y"
    return $true
}
function Find-TextNode($xml, [string]$text) {
    if ($null -eq $xml) { return $null }
    return $xml.SelectSingleNode("//node[@text='$text']")
}

# Button labels by codepoint so this file stays pure ASCII and parses under 5.1 and 7.
$allow = -join ([char]0x5141, [char]0x8BB8)                      # 允许
$continueInstall = -join ([char]0x7EE7, [char]0x7EED, [char]0x5B89, [char]0x88C5)  # 继续安装
$install = -join ([char]0x5B89, [char]0x88C5)                    # 安装
$continueUpdate = -join ([char]0x7EE7, [char]0x7EED, [char]0x66F4, [char]0x65B0)  # 继续更新
$done = -join ([char]0x5B8C, [char]0x6210)                       # 完成
$stillInstall = -join ([char]0x4ECD, [char]0x7136, [char]0x5B89, [char]0x88C5)    # 仍然安装

Log "device=$Device apk=$Apk"
if (-not (Adb @('get-state')).Trim().StartsWith('device')) {
    Log 'BLOCKED: device not online'
    exit 3
}

$size = (Get-Item -LiteralPath $Apk).Length
Log "push $size bytes -> $RemotePath"
$push = Adb @('push', $Apk, $RemotePath)
if ($push -notmatch '1 file pushed') {
    Log "BLOCKED: push failed: $push"
    exit 3
}

$before = (Adb @('shell', 'dumpsys', 'package', $Package)).ToString()
$beforeUpdate = ([regex]::Match($before, 'lastUpdateTime=([^\r\n]+)')).Groups[1].Value
Log "before lastUpdateTime=$beforeUpdate"

Log 'open Files app (Download)'
Adb @('shell', 'am', 'force-stop', 'com.android.fileexplorer') | Out-Null
Adb @('shell', 'am', 'start', '-n', 'com.android.fileexplorer/.FileExplorerTabActivity') | Out-Null
Start-Sleep -Seconds 3
# MIUI restores the previous scroll position; scroll to the top so the newest file is visible.
for ($s = 0; $s -lt 3; $s++) {
    Adb @('shell', 'input', 'swipe', '700', '900', '700', '2400', '250') | Out-Null
}
Start-Sleep -Seconds 2

# Tap the pushed APK by its exact file name.
$tappedFile = $false
for ($i = 0; $i -lt 6 -and -not $tappedFile; $i++) {
    $ui = Get-UiXml
    $apkName = Split-Path -Leaf $RemotePath
    $node = Find-TextNode $ui $apkName
    if ($null -ne $node) {
        $tappedFile = Tap-Node $node
        if (-not $tappedFile) { Log 'WARN: could not compute APK node bounds' }
    }
    else {
        Log 'APK file not visible yet; refreshing Files'
        Start-Sleep -Seconds 2
    }
}
if (-not $tappedFile) {
    Log 'BLOCKED: could not find the pushed APK in Files'
    exit 3
}

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$installed = $false
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 2
    $after = (Adb @('shell', 'dumpsys', 'package', $Package)).ToString()
    $afterUpdate = ([regex]::Match($after, 'lastUpdateTime=([^\r\n]+)')).Groups[1].Value
    if ($afterUpdate -and $afterUpdate -ne $beforeUpdate) {
        Log "installed: lastUpdateTime=$afterUpdate"
        $installed = $true
        break
    }
    $ui = Get-UiXml
    if ($null -eq $ui) { continue }
    foreach ($label in @($allow, $stillInstall, $continueInstall, $continueUpdate, $install, $done)) {
        $node = Find-TextNode $ui $label
        if ($null -ne $node) {
            Tap-Node $node | Out-Null
            break
        }
    }
}

if (-not $installed) {
    Log 'BLOCKED: install did not complete within the timeout (no lastUpdateTime change)'
    exit 3
}
$version = ([regex]::Match((Adb @('shell', 'dumpsys', 'package', $Package)).ToString(), 'versionName=([^\r\n]+)')).Groups[1].Value
Log "PASS installed versionName=$version"
exit 0
