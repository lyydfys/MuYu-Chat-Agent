param(
    [string] $DeviceSerial = '',
    [string] $OutDir = '',
    [string[]] $FontScales = @('1.5', '2.0')
)
<#
R12 pass-1 visual sampling: fixed content, fixed device, font scale as the only variable.

It sets `system font_scale`, restarts the app (a font-scale change recreates the activity), captures the
chat surface plus the open composer menu, then restores the original scale in a `finally` block. Every
capture writes a screenshot and a UI dump; nothing here judges quality -- the images are the evidence a
human reviewer needs for the 1.5x / 2x pass.

The original scale is read first and always restored, including on failure. A failed restore is reported
with a non-zero exit, because leaving the device in a modified state would invalidate later runs.
#>
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$adb = 'D:\model\android-sdk\platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adb)) { $adb = 'adb' }
$package = 'com.muyuchat.mca'
$activity = 'com.muyuchat.mca/.MainActivity'

function Invoke-AdbSerial {
    param([string[]] $Arguments)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $text = (& $adb @Arguments 2>&1 | Out-String)
        return [pscustomobject]@{ exitCode = $LASTEXITCODE; text = $text.Trim() }
    } finally { $ErrorActionPreference = $previous }
}

$online = @((Invoke-AdbSerial @('devices')).text -split "`r?`n" | Where-Object { $_ -match '\sdevice\s*$' } | ForEach-Object { ($_ -split '\s+')[0] })
if (-not $DeviceSerial) {
    if ($online.Count -ne 1) { throw "expected exactly one online device or -DeviceSerial; online: $($online -join ',')" }
    $DeviceSerial = $online[0]
}

$runId = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss-fffZ')
if (-not $OutDir) { $OutDir = Join-Path $repo "artifacts/ui-quality/v9-closeout/font-scale/$runId" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$original = (Invoke-AdbSerial @('-s', $DeviceSerial, 'shell', 'settings', 'get', 'system', 'font_scale')).text.Trim()
if (-not $original) { $original = '1.0' }
Write-Output ("font-scale: original=$original device=$DeviceSerial")

$results = New-Object System.Collections.Generic.List[object]

function Save-Shot {
    param([string] $Name)
    $remote = '/sdcard/mca-font.png'
    Invoke-AdbSerial @('-s', $DeviceSerial, 'shell', 'screencap', '-p', $remote) | Out-Null
    $path = Join-Path $OutDir ($Name + '.png')
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $adb -s $DeviceSerial pull $remote $path *> $null } finally { $ErrorActionPreference = $previous }
    Invoke-AdbSerial @('-s', $DeviceSerial, 'shell', 'rm', '-f', $remote) | Out-Null
    return $path
}

function Save-Dump {
    param([string] $Name)
    $remote = '/sdcard/mca-font.xml'
    Invoke-AdbSerial @('-s', $DeviceSerial, 'shell', 'rm', '-f', $remote) | Out-Null
    Invoke-AdbSerial @('-s', $DeviceSerial, 'shell', 'uiautomator', 'dump', $remote) | Out-Null
    $text = (Invoke-AdbSerial @('-s', $DeviceSerial, 'shell', 'cat', $remote)).text
    $path = Join-Path $OutDir ($Name + '.ui.xml')
    [IO.File]::WriteAllText($path, $text, [Text.UTF8Encoding]::new($false))
    return $path
}

function Restart-App {
    Invoke-AdbSerial @('-s', $DeviceSerial, 'shell', 'am', 'force-stop', $package) | Out-Null
    Start-Sleep -Milliseconds 1200
    Invoke-AdbSerial @('-s', $DeviceSerial, 'shell', 'am', 'start', '-W', '-n', $activity) | Out-Null
    Start-Sleep -Milliseconds 2500
}

try {
    foreach ($scale in (@('1.0') + $FontScales)) {
        Invoke-AdbSerial @('-s', $DeviceSerial, 'shell', 'settings', 'put', 'system', 'font_scale', $scale) | Out-Null
        Start-Sleep -Milliseconds 800
        Restart-App
        $label = 'font-' + ($scale -replace '\.', '_')
        $chatShot = Save-Shot ($label + '-chat')
        $chatDump = Save-Dump ($label + '-chat')
        $nodes = @()
        try {
            [xml] $doc = Get-Content -LiteralPath $chatDump -Raw -Encoding UTF8
            $nodes = @($doc.SelectNodes('//node'))
        } catch { }
        $more = $null
        foreach ($node in $nodes) {
            if ([string]$node.'content-desc' -like '*更多操作*') { $more = $node; break }
        }
        $menuShot = ''
        if ($more -and ([string]$more.bounds -match '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$')) {
            $x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
            $y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
            Invoke-AdbSerial @('-s', $DeviceSerial, 'shell', 'input', 'tap', [string]$x, [string]$y) | Out-Null
            Start-Sleep -Milliseconds 1000
            $menuShot = Save-Shot ($label + '-menu')
            Save-Dump ($label + '-menu') | Out-Null
            Invoke-AdbSerial @('-s', $DeviceSerial, 'shell', 'input', 'keyevent', 'KEYCODE_BACK') | Out-Null
            Start-Sleep -Milliseconds 600
        }
        $results.Add([pscustomobject]@{
                fontScale = $scale
                chatScreenshot = $chatShot
                menuScreenshot = $menuShot
                menuTriggerFound = [bool]$more
            }) | Out-Null
        Write-Output ("font-scale: captured $scale (menu trigger: " + [bool]$more + ')')
    }
} finally {
    Invoke-AdbSerial @('-s', $DeviceSerial, 'shell', 'settings', 'put', 'system', 'font_scale', $original) | Out-Null
    Start-Sleep -Milliseconds 800
    $restored = (Invoke-AdbSerial @('-s', $DeviceSerial, 'shell', 'settings', 'get', 'system', 'font_scale')).text.Trim()
    Restart-App
    $summary = [ordered]@{
        schema = 'mca.font-scale-visual-sample/1'
        runId = $runId
        device = $DeviceSerial
        originalFontScale = $original
        restoredFontScale = $restored
        restored = ($restored -eq $original)
        scope = 'Screenshots for the R12 1x/1.5x/2x review. No quality verdict is recorded here.'
        captures = $results.ToArray()
    }
    [IO.File]::WriteAllText((Join-Path $OutDir 'RUN.json'), ($summary | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
    Write-Output ("font-scale: restored to $restored (match: " + ($restored -eq $original) + ')')
    Write-Output ('EVIDENCE: ' + $OutDir)
    if ($restored -ne $original) { exit 1 }
}
exit 0
