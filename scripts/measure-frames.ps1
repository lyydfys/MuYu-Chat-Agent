<#
.SYNOPSIS
    Captures `dumpsys gfxinfo` for a package around a short, configurable UI interaction and
    turns the frame summary into PASS/FAIL evidence.

.DESCRIPTION
    Flow:
      1. resolve adb/device/package; bring the app to the chat surface (unless -NoLaunch);
      2. resolve the production selectors needed by the requested actions;
      3. `adb shell dumpsys gfxinfo <Package> reset`;
      4. run the requested actions (drawer open/close, N scroll swipes, model panel open/close,
         and the settings path: app menu -> 系统设置 -> N scroll swipes -> back to chat)
         with configurable sleeps between steps;
      5. capture `adb shell dumpsys gfxinfo <Package>` into gfxinfo.txt;
      6. parse Total frames / Janky frames / 50th / 90th / 95th / 99th percentile and
         Missed Vsync, then judge against parameterized thresholds.

    Defaults: P95 <= 16.7 ms (60 Hz frame budget) and janky <= 3 %. Both are configurable.
    The result is only about this package, this device and this run; it is not a
    Macrobenchmark/Perfetto replacement and it does not measure GPU/native work.

    Honest states:
      PASS      sample is complete, interaction observed, both thresholds met;
      FAIL      sample is complete but P95 and/or janky percentage exceed the thresholds;
      NOT_RUN   gfxinfo produced no frames / too few frames / required metrics missing;
      BLOCKED   adb/device/package/chat surface/selector missing, reset or capture failed,
                or an action did not visibly open its panel (measurement not attributable).
      Exit 2 is reserved for usage/output-directory errors.

    No external PowerShell modules are used. The script is Windows PowerShell 5.1 compatible
    and intentionally saved as UTF-8 WITH BOM because PowerShell 5.1 treats non-BOM scripts as
    ANSI, which would corrupt the Chinese UI selectors below. Keep the BOM when editing.

.PARAMETER DeviceSerial
    adb serial. When omitted and exactly one device is online it is used automatically; zero or
    multiple online devices are BLOCKED rather than guessed.

.PARAMETER Package
    Application id to measure. Default: com.muyuchat.mca.

.PARAMETER OutDir
    Evidence directory. Default: <repo>/artifacts/frames/<runId>.
    An existing non-empty directory gets a run-id child so previous evidence is not overwritten.

.PARAMETER Actions
    Interaction sequence to run while stats are being collected. Supported: drawer, scroll,
    model, settings. The settings action opens the app menu, enters 系统设置, scrolls the default
    settings section N times and returns to chat via 返回聊天. Comma-separated
    (`-Actions drawer,settings`) or a native PowerShell array. At least one action is required.
    Default: drawer,scroll,model.

.PARAMETER ScrollCount
    Number of alternating scroll swipes in the `scroll` action. Default 6.

.PARAMETER StepDelayMs
    Sleep after each action. Default 700 ms.

.PARAMETER ScrollIntervalMs
    Sleep between individual scroll swipes. Default 250 ms.

.PARAMETER PanelOpenMs / PanelCloseMs
    Sleep after opening a panel and after closing it with BACK. Defaults 800 / 600 ms.

.PARAMETER SettleMs
    Sleep after the last action, before capturing gfxinfo. Default 500 ms.

.PARAMETER LaunchSettleMs
    Extra sleep after launching the app before waiting for the chat surface. Default 1500 ms.

.PARAMETER MinFrames
    Minimum `Total frames rendered` required for a valid sample. Default 30. Fewer frames are
    NOT_RUN (insufficient evidence), not PASS.

.PARAMETER MaxP95Ms
    Maximum allowed 95th percentile frame time in milliseconds. Default 16.7.

.PARAMETER MaxJankyPercent
    Maximum allowed janky frame percentage. Default 3.0.

.PARAMETER AdbPath
    Optional explicit adb path. Otherwise PATH, ANDROID_HOME/ANDROID_SDK_ROOT and the local
    Android SDK path used by this repo's tooling are tried.

.PARAMETER NoLaunch
    Do not launch the app; require the chat surface to be already foreground.

.EXIT CODES
    0 = PASS
    1 = FAIL (thresholds exceeded, or an unexpected internal error)
    3 = NOT_RUN / BLOCKED (insufficient/unsupported sample or environment precondition)
    2 = usage or output-directory error

.EXAMPLE
    .\scripts\measure-frames.ps1 -DeviceSerial device-a

.EXAMPLE
    .\scripts\measure-frames.ps1 -DeviceSerial device-a -Actions scroll -ScrollCount 10 -MaxP95Ms 16 -MaxJankyPercent 5

.EXAMPLE
    .\scripts\measure-frames.ps1 -DeviceSerial device-a -Package com.muyuchat.mca -OutDir artifacts/frames/manual-drawer -Actions drawer

.EXAMPLE
    .\scripts\measure-frames.ps1 -DeviceSerial device-a -Actions settings -ScrollCount 6
#>
[CmdletBinding(PositionalBinding = $false)]
param(
    [string] $DeviceSerial = '',
    [ValidateNotNullOrEmpty()]
    [string] $Package = 'com.muyuchat.mca',
    [string] $OutDir = '',
    [AllowEmptyCollection()]
    [string[]] $Actions = @('drawer', 'scroll', 'model'),
    [ValidateRange(1, 100)]
    [int] $ScrollCount = 6,
    [ValidateRange(0, 10000)]
    [int] $StepDelayMs = 700,
    [ValidateRange(0, 10000)]
    [int] $ScrollIntervalMs = 250,
    [ValidateRange(0, 10000)]
    [int] $PanelOpenMs = 800,
    [ValidateRange(0, 10000)]
    [int] $PanelCloseMs = 600,
    [ValidateRange(0, 10000)]
    [int] $SettleMs = 500,
    [ValidateRange(0, 30000)]
    [int] $LaunchSettleMs = 1500,
    [ValidateRange(1, 100000)]
    [int] $MinFrames = 30,
    [ValidateRange(0.0, 1000.0)]
    [double] $MaxP95Ms = 16.7,
    [ValidateRange(0.0, 100.0)]
    [double] $MaxJankyPercent = 3.0,
    [string] $AdbPath = '',
    # Controlled build record that proves which source produced the installed APK.
    # Empty means: the installed hashes are recorded but no source binding is claimed.
    [string] $BuildRecord = '',
    [switch] $NoLaunch
)

$ErrorActionPreference = 'Continue'
$repo = Split-Path -Parent $PSScriptRoot
$allowedActions = @('drawer', 'scroll', 'model', 'settings')

# Accept `-Actions drawer,scroll` (single string when launched via `powershell -File`) and native
# arrays. Validate early so an unknown action is a usage error, not a silent typo.
$requestedActions = New-Object System.Collections.Generic.List[string]
foreach ($rawAction in @($Actions)) {
    foreach ($part in ([string]$rawAction -split ',')) {
        $trimmed = $part.Trim()
        if (-not $trimmed) { continue }
        if ($allowedActions -notcontains $trimmed) {
            [Console]::Error.WriteLine("measure-frames: unknown action '$trimmed'; expected one of: " + ($allowedActions -join ', '))
            exit 2
        }
        if (-not $requestedActions.Contains($trimmed)) { $requestedActions.Add($trimmed) | Out-Null }
    }
}
if ($requestedActions.Count -eq 0) {
    [Console]::Error.WriteLine('measure-frames: at least one -Actions value is required (drawer, scroll, model, settings)')
    exit 2
}

function Resolve-OutputDirectory {
    param([string] $Requested, [string] $RepoRoot)
    $runId = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $PID
    $target = $Requested
    if (-not $target) {
        $target = Join-Path $RepoRoot (Join-Path 'artifacts\frames' $runId)
    } elseif (-not [System.IO.Path]::IsPathRooted($target)) {
        $target = Join-Path $RepoRoot $target
    }
    if (Test-Path -LiteralPath $target) {
        $existing = @(Get-ChildItem -LiteralPath $target -Force -ErrorAction SilentlyContinue)
        if ($existing.Count -gt 0) { $target = Join-Path $target $runId }
    }
    New-Item -ItemType Directory -Force -Path $target | Out-Null
    return (Resolve-Path -LiteralPath $target).Path
}

function Resolve-AdbPath {
    param([string] $Explicit)
    if ($Explicit) {
        if (Test-Path -LiteralPath $Explicit) { return (Resolve-Path -LiteralPath $Explicit).Path }
        return $null
    }
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command -and $command.Source) { return $command.Source }
    $candidates = @(
        $(if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe' }),
        $(if ($env:ANDROID_SDK_ROOT) { Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe' }),
        (Join-Path $env:USERPROFILE 'AppData\Local\Android\Sdk\platform-tools\adb.exe'),
        # Machine-local Android SDK path already used by this repo's verification tooling.
        'E:\model\toolchains\android-sdk\platform-tools\adb.exe'
    )
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    return $null
}

function Invoke-AdbCapture {
    param([string] $Adb, [string[]] $Arguments)
    $output = & $Adb @Arguments 2>&1
    $text = (@($output) | ForEach-Object { [string]$_ }) -join "`n"
    $code = $LASTEXITCODE
    return [pscustomobject]@{ exitCode = $code; text = $text }
}

function Get-DeviceInfo {
    param([string] $Adb, [string] $Serial)
    $model = (((& $Adb -s $Serial shell getprop ro.product.model 2>$null) -join '')).Trim()
    $brand = (((& $Adb -s $Serial shell getprop ro.product.brand 2>$null) -join '')).Trim()
    $api = (((& $Adb -s $Serial shell getprop ro.build.version.sdk 2>$null) -join '')).Trim()
    $size = (((& $Adb -s $Serial shell wm size 2>$null) -join '')).Trim()
    $density = (((& $Adb -s $Serial shell wm density 2>$null) -join '')).Trim()
    return [ordered]@{ serial = $Serial; brand = $brand; model = $model; apiLevel = $api; size = $size; density = $density }
}

function Get-AppVersion {
    param([string] $Adb, [string] $Serial, [string] $AppPackage)
    $capture = Invoke-AdbCapture -Adb $Adb -Arguments @('-s', $Serial, 'shell', 'dumpsys', 'package', $AppPackage)
    $match = [regex]::Match($capture.text, 'versionName=([^\s]+)')
    if ($match.Success) { return $match.Groups[1].Value }
    return ''
}

function Get-UiDumpRaw {
    param([string] $Adb, [string] $Serial)
    & $Adb -s $Serial shell uiautomator dump /sdcard/mca-frames-ui.xml 2>&1 | Out-Null
    $raw = & $Adb -s $Serial exec-out cat /sdcard/mca-frames-ui.xml 2>$null
    return (@($raw) | ForEach-Object { [string]$_ }) -join ''
}

function Test-UiMarker {
    param([string] $Raw, [string] $Attribute, [string] $Value)
    if (-not $Raw) { return $false }
    return $Raw.Contains($Attribute + '="' + $Value + '"')
}

function ConvertFrom-Bounds {
    param([string] $Value)
    $match = [regex]::Match($Value, '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$')
    if (-not $match.Success) { return $null }
    $left = [int]$match.Groups[1].Value
    $top = [int]$match.Groups[2].Value
    $right = [int]$match.Groups[3].Value
    $bottom = [int]$match.Groups[4].Value
    if ($right -le $left -or $bottom -le $top) { return $null }
    return [pscustomobject]@{ left = $left; top = $top; right = $right; bottom = $bottom; x = [int](($left + $right) / 2); y = [int](($top + $bottom) / 2); width = ($right - $left); height = ($bottom - $top) }
}

function Find-NodeCenter {
    param([string] $Raw, [string] $Attribute, [string] $Value)
    if (-not $Raw) { return $null }
    try {
        $document = New-Object System.Xml.XmlDocument
        $document.LoadXml($Raw)
        foreach ($node in $document.SelectNodes('//node')) {
            if ($node.GetAttribute($Attribute) -ne $Value) { continue }
            $bounds = ConvertFrom-Bounds -Value $node.GetAttribute('bounds')
            if ($bounds) { return $bounds }
        }
    } catch { }
    return $null
}

function Find-NodeCenterContaining {
    param([string] $Raw, [string] $Needle)
    if (-not $Raw) { return $null }
    try {
        $document = New-Object System.Xml.XmlDocument
        $document.LoadXml($Raw)
        foreach ($node in $document.SelectNodes('//node')) {
            $text = $node.GetAttribute('text')
            $description = $node.GetAttribute('content-desc')
            if (($text -and $text.Contains($Needle)) -or ($description -and $description.Contains($Needle))) {
                $bounds = ConvertFrom-Bounds -Value $node.GetAttribute('bounds')
                if ($bounds) { return $bounds }
            }
        }
    } catch { }
    return $null
}

function Find-ScrollableBox {
    param([string] $Raw)
    if (-not $Raw) { return $null }
    try {
        $document = New-Object System.Xml.XmlDocument
        $document.LoadXml($Raw)
        $best = $null
        foreach ($node in $document.SelectNodes('//node')) {
            if ($node.GetAttribute('scrollable') -ne 'true') { continue }
            $box = ConvertFrom-Bounds -Value $node.GetAttribute('bounds')
            if (-not $box) { continue }
            if ($box.height -le $box.width) { continue }
            if ($box.height -lt 200) { continue }
            if (-not $best -or $box.height -gt $best.height) { $best = $box }
        }
        return $best
    } catch { }
    return $null
}

function Wait-ForChatSurface {
    param([string] $Adb, [string] $Serial, [int] $TimeoutMs)
    $deadline = (Get-Date).AddMilliseconds($TimeoutMs)
    while ((Get-Date) -lt $deadline) {
        $raw = Get-UiDumpRaw -Adb $Adb -Serial $Serial
        if (Test-UiMarker -Raw $raw -Attribute 'content-desc' -Value '打开历史') { return $raw }
        Start-Sleep -Milliseconds 600
    }
    return $null
}

function ConvertFrom-GfxInfo {
    param([string] $Text)
    $result = [ordered]@{
        totalFrames  = $null
        jankyFrames  = $null
        jankyPercent = $null
        p50Ms        = $null
        p90Ms        = $null
        p95Ms        = $null
        p99Ms        = $null
        missedVsync  = $null
    }
    if ([string]::IsNullOrWhiteSpace($Text)) { return $result }

    $match = [regex]::Match($Text, '(?m)^\s*Total frames rendered:\s*(\d+)')
    if ($match.Success) { $result.totalFrames = [int]$match.Groups[1].Value }

    $match = [regex]::Match($Text, '(?m)^\s*Janky frames:\s*(\d+)\s*\(\s*([0-9]+(?:\.[0-9]+)?)\s*%\s*\)')
    if ($match.Success) {
        $result.jankyFrames = [int]$match.Groups[1].Value
        $result.jankyPercent = [double]$match.Groups[2].Value
    } else {
        $match = [regex]::Match($Text, '(?m)^\s*Janky frames:\s*(\d+)')
        if ($match.Success) { $result.jankyFrames = [int]$match.Groups[1].Value }
    }

    foreach ($percentile in @(50, 90, 95, 99)) {
        $key = 'p' + $percentile + 'Ms'
        $pattern = '(?m)^\s*' + $percentile + 'th percentile:\s*([0-9]+(?:\.[0-9]+)?)\s*ms'
        $match = [regex]::Match($Text, $pattern)
        if ($match.Success) { $result[$key] = [double]$match.Groups[1].Value }
    }

    $match = [regex]::Match($Text, '(?m)^\s*(?:Number\s+)?Missed Vsync:\s*(\d+)')
    if ($match.Success) { $result.missedVsync = [int]$match.Groups[1].Value }

    if ($null -eq $result.jankyPercent -and $null -ne $result.jankyFrames -and $null -ne $result.totalFrames -and $result.totalFrames -gt 0) {
        $result.jankyPercent = [math]::Round((100.0 * [double]$result.jankyFrames / [double]$result.totalFrames), 2)
    }
    return $result
}

function Stop-FrameRun {
    param([string] $Status, [string] $Reason)
    $script:abortStatus = $Status
    $script:abortReason = $Reason
    throw $Reason
}

# ---------------------------------------------------------------------------------------------
# output + state
# ---------------------------------------------------------------------------------------------
try {
    $outDirectory = Resolve-OutputDirectory -Requested $OutDir -RepoRoot $repo
} catch {
    [Console]::Error.WriteLine('measure-frames: cannot create output directory: ' + $_.Exception.Message)
    exit 2
}
$runId = Split-Path -Leaf $outDirectory
$gfxinfoPath = Join-Path $outDirectory 'gfxinfo.txt'
$resetPath = Join-Path $outDirectory 'gfxinfo-reset.txt'
$summaryPath = Join-Path $outDirectory 'summary.json'

# Read-only evidence helpers: workspace observation, installed APK identity, build-record
# binding, environment capture and the summary writer. FrameEvidence.ps1 loads BuildProvenance.ps1.
. (Join-Path $PSScriptRoot 'FrameEvidence.ps1')

$script:abortStatus = $null
$script:abortReason = $null
$state = [ordered]@{
    schema        = 'mca.frame-diagnostics/1'
    runId         = $runId
    generatedAt   = (Get-Date).ToString('o')
    repo          = $repo
    package       = $Package
    actions       = @($requestedActions.ToArray())
    parameters    = [ordered]@{
        scrollCount      = $ScrollCount
        stepDelayMs      = $StepDelayMs
        scrollIntervalMs = $ScrollIntervalMs
        panelOpenMs      = $PanelOpenMs
        panelCloseMs     = $PanelCloseMs
        settleMs         = $SettleMs
        launchSettleMs   = $LaunchSettleMs
        minFrames        = $MinFrames
        noLaunch         = [bool]$NoLaunch
    }
    thresholds    = [ordered]@{
        maxP95Ms        = $MaxP95Ms
        maxJankyPercent = $MaxJankyPercent
        minFrames       = $MinFrames
    }
    device        = $null
    app           = [ordered]@{ packageName = $Package; versionName = '' }
    tools         = [ordered]@{ adb = '' }
    # Three facts that are never merged: what the workspace looks like, which binary is installed,
    # and which source that binary came from (only a passing build record can say).
    evidence      = [ordered]@{ source = $null; installedApks = @(); buildBinding = $null; environment = $null }
    evidenceWrite = $null
    reset         = $null
    steps         = @()
    gfxinfo       = $null
    metrics       = $null
    verdict       = 'FAIL'
    reason        = 'not started'
    exitCode      = 1
    limits        = @(
        'dumpsys gfxinfo reports UI-thread frame summaries; it does not include GPU/native timing or per-frame traces.',
        'Janky-frame classification and percentile bucket resolution are OS/device dependent.',
        'The P95 default 16.7 ms assumes a 60 Hz frame budget; adjust MaxP95Ms for other refresh rates.',
        'Sleeps between steps are part of the measured window; this is a short UI workload, not a long soak.',
        'The drawer/model/settings verification uses uiautomator dumps inside the measured window; those dumps add a few frames and are part of the sample.',
        'The scroll action is verified only by successful swipe commands, not by a visual content marker; drawer/model/settings provide overlay/page observations.',
        'The settings action measures the default settings section reached from the app menu; it does not switch settings section tabs and relies on the 打开应用菜单 / 系统设置 / 返回聊天 selectors.',
        'PASS applies only to this device, package, build and run; compare before/after on the same device and conditions.'
    )
}

# --- source observation (never proof of the installed binary's source) ---------------------
# Full input hashing walks the whole build input set, so it only runs when a build record is
# supplied: without one there is no binding to compare the observation against.
if ($BuildRecord) {
    try {
        $state.evidence.source = Get-McaFrameWorkspaceEvidence -RepoRoot $repo
    } catch {
        $state.evidence.source = [ordered]@{ status = 'ERROR'; reason = $_.Exception.Message }
    }
} else {
    $state.evidence.source = [ordered]@{
        status = 'NOT_RUN'
        reason = 'No -BuildRecord supplied: the installed binary cannot be bound to this source, so the full input observation is not claimed.'
    }
}

try {
    $adb = Resolve-AdbPath -Explicit $AdbPath
    if (-not $adb) {
        Stop-FrameRun -Status 'BLOCKED' -Reason 'adb was not found (PATH, ANDROID_HOME/ANDROID_SDK_ROOT, local SDK fallback)'
    }
    $state.tools.adb = $adb

    # --- device ------------------------------------------------------------------------------
    $deviceList = & $adb devices 2>$null
    $online = New-Object System.Collections.Generic.List[string]
    foreach ($line in @($deviceList)) {
        $match = [regex]::Match([string]$line, '^(\S+)\s+device\s*$')
        if ($match.Success) { $online.Add($match.Groups[1].Value) | Out-Null }
    }
    if (-not $DeviceSerial) {
        if ($online.Count -eq 1) {
            $DeviceSerial = $online[0]
        } else {
            Stop-FrameRun -Status 'BLOCKED' -Reason ("no -DeviceSerial supplied and adb reports $($online.Count) online device(s)")
        }
    }
    if (-not ($online -contains $DeviceSerial)) {
        Stop-FrameRun -Status 'BLOCKED' -Reason ("device '$DeviceSerial' is not online (adb reports: " + ($online -join ', ') + ')')
    }
    $state.device = Get-DeviceInfo -Adb $adb -Serial $DeviceSerial
    $state.app.versionName = Get-AppVersion -Adb $adb -Serial $DeviceSerial -AppPackage $Package

    # --- package installed -------------------------------------------------------------------
    $packagePath = Invoke-AdbCapture -Adb $adb -Arguments @('-s', $DeviceSerial, 'shell', 'pm', 'path', $Package)
    if ($packagePath.exitCode -ne 0 -or $packagePath.text -notmatch 'package:') {
        Stop-FrameRun -Status 'BLOCKED' -Reason ("package '$Package' is not installed on '$DeviceSerial'")
    }

    # --- binary identity + build binding + environment (before any reset) -------------------
    try {
        $state.evidence.installedApks = @(Get-McaFrameApkEvidence -Adb $adb -Serial $DeviceSerial -AppPackage $Package)
        $state.evidence.buildBinding = Get-McaFrameBuildBinding -BuildRecordPath $BuildRecord `
            -InstalledApks $state.evidence.installedApks
    } catch {
        # A binary whose hash cannot be read, or a record that does not bind it, is not a run we
        # may report as attributable.
        Stop-FrameRun -Status 'BLOCKED' -Reason ('build/source attribution failed: ' + $_.Exception.Message)
    }
    try {
        $state.evidence.environment = Save-McaFrameEnvironment -Adb $adb -Serial $DeviceSerial `
            -EvidenceDirectory $outDirectory
    } catch {
        $state.evidence.environment = [ordered]@{ status = 'ERROR'; reason = $_.Exception.Message }
    }

    # --- foreground chat surface -------------------------------------------------------------
    if (-not $NoLaunch) {
        & $adb -s $DeviceSerial shell input keyevent KEYCODE_WAKEUP 2>&1 | Out-Null
        & $adb -s $DeviceSerial shell wm dismiss-keyguard 2>&1 | Out-Null
        $monkey = Invoke-AdbCapture -Adb $adb -Arguments @('-s', $DeviceSerial, 'shell', 'monkey', '-p', $Package, '-c', 'android.intent.category.LAUNCHER', '1')
        if ($monkey.text -notmatch 'Events injected') {
            & $adb -s $DeviceSerial shell am start -n ($Package + '/com.muyuchat.mca.MainActivity') 2>&1 | Out-Null
        }
        Start-Sleep -Milliseconds $LaunchSettleMs
    }
    $surface = Wait-ForChatSurface -Adb $adb -Serial $DeviceSerial -TimeoutMs 25000
    if (-not $surface) {
        Stop-FrameRun -Status 'BLOCKED' -Reason (
            "chat surface is not foreground for package $Package; open the MCA chat screen or omit -NoLaunch"
        )
    }

    # --- resolve selectors before reset (fail before touching frame stats) --------------------
    $plan = [ordered]@{}
    if ($requestedActions -contains 'drawer') {
        $drawerNode = Find-NodeCenter -Raw $surface -Attribute 'content-desc' -Value '打开历史'
        if (-not $drawerNode) { Stop-FrameRun -Status 'BLOCKED' -Reason 'drawer action requested but the 打开历史 node was not found on the chat surface' }
        $plan.drawer = $drawerNode
    }
    if ($requestedActions -contains 'model') {
        # The model capsule no longer repeats '选择模型' as a content description. Its visible
        # subtitle is the current state, so the trigger is resolved from that text instead: the
        # placeholder while nothing is chosen, the loading/failure labels, and the ready labels for
        # a selected local or cloud model. Tapping the subtitle lands on the capsule that owns it.
        $modelNode = $null
        foreach ($modelNeedle in @('选择模型', '待加载', '需要重载', '加载失败', '正在读取模型权重', '已就绪', '云端 API')) {
            $modelNode = Find-NodeCenterContaining -Raw $surface -Needle $modelNeedle
            if ($modelNode) { $modelSelectorUsed = $modelNeedle; break }
        }
        if (-not $modelNode) {
            # The capsule no longer carries a contentDescription: its visible model name/subtitle
            # names it for accessibility, so accept a text node containing 选择模型 as the target.
            $modelNode = Find-NodeCenterContaining -Raw $surface -Needle '选择模型'
        }
        if (-not $modelNode) {
            Stop-FrameRun -Status 'BLOCKED' -Reason ('model action requested but no model capsule trigger was found; tried: ' + (@('选择模型', '待加载', '需要重载', '加载失败', '正在读取模型权重', '已就绪', '云端 API') -join ', '))
        }
        $plan.model = $modelNode
        $plan.modelSelector = $modelSelectorUsed
    }
    if ($requestedActions -contains 'scroll') {
        $scrollBox = Find-ScrollableBox -Raw $surface
        if (-not $scrollBox) { Stop-FrameRun -Status 'BLOCKED' -Reason 'scroll action requested but no vertical scrollable node was found on the chat surface' }
        $plan.scroll = $scrollBox
    }
    if ($requestedActions -contains 'settings') {
        # Settings path depends on three production selectors: the header menu button, the app
        # menu row and the settings page back control. Resolve all of them before reset; any
        # missing selector is BLOCKED rather than measured as a no-op interaction.
        $settingsMenuButton = Find-NodeCenter -Raw $surface -Attribute 'content-desc' -Value '打开应用菜单'
        if (-not $settingsMenuButton) { Stop-FrameRun -Status 'BLOCKED' -Reason 'settings action requested but the 打开应用菜单 node was not found on the chat surface' }
        & $adb -s $DeviceSerial shell input tap $settingsMenuButton.x $settingsMenuButton.y 2>&1 | Out-Null
        Start-Sleep -Milliseconds $PanelOpenMs
        $menuDump = Get-UiDumpRaw -Adb $adb -Serial $DeviceSerial
        $settingsEntry = Find-NodeCenter -Raw $menuDump -Attribute 'text' -Value '系统设置'
        if (-not $settingsEntry) { Stop-FrameRun -Status 'BLOCKED' -Reason 'settings action requested but the 系统设置 row was not found in the app menu' }
        & $adb -s $DeviceSerial shell input tap $settingsEntry.x $settingsEntry.y 2>&1 | Out-Null
        Start-Sleep -Milliseconds $PanelOpenMs
        $settingsDump = Get-UiDumpRaw -Adb $adb -Serial $DeviceSerial
        if (-not (Test-UiMarker -Raw $settingsDump -Attribute 'text' -Value '系统设置')) {
            Stop-FrameRun -Status 'BLOCKED' -Reason 'settings action requested but the settings page title was not found'
        }
        $settingsBack = Find-NodeCenter -Raw $settingsDump -Attribute 'content-desc' -Value '返回聊天'
        if (-not $settingsBack) { Stop-FrameRun -Status 'BLOCKED' -Reason 'settings action requested but the 返回聊天 control was not found on the settings page' }
        $settingsScrollBox = Find-ScrollableBox -Raw $settingsDump
        if (-not $settingsScrollBox) { Stop-FrameRun -Status 'BLOCKED' -Reason 'settings action requested but no vertical scrollable node was found on the settings page' }
        $plan.settingsMenu = $settingsMenuButton
        $plan.settingsEntry = $settingsEntry
        $plan.settingsBack = $settingsBack
        $plan.settingsScroll = $settingsScrollBox
        # Return to chat before reset so the measured window starts from the same baseline as the
        # other actions. Settings can be layered over the app menu; allow up to three BACKs.
        & $adb -s $DeviceSerial shell input tap $settingsBack.x $settingsBack.y 2>&1 | Out-Null
        Start-Sleep -Milliseconds $PanelCloseMs
        $returnedToChat = $false
        for ($attempt = 0; $attempt -lt 3; $attempt++) {
            $checkDump = Get-UiDumpRaw -Adb $adb -Serial $DeviceSerial
            if (Test-UiMarker -Raw $checkDump -Attribute 'content-desc' -Value '打开历史') { $returnedToChat = $true; break }
            & $adb -s $DeviceSerial shell input keyevent 4 2>&1 | Out-Null
            Start-Sleep -Milliseconds $PanelCloseMs
        }
        if (-not $returnedToChat) {
            Stop-FrameRun -Status 'BLOCKED' -Reason 'could not return to the chat surface after the settings selector preflight'
        }
    }

    # --- reset gfxinfo -----------------------------------------------------------------------
    $reset = Invoke-AdbCapture -Adb $adb -Arguments @('-s', $DeviceSerial, 'shell', 'dumpsys', 'gfxinfo', $Package, 'reset')
    [System.IO.File]::WriteAllText($resetPath, $reset.text, (New-Object System.Text.UTF8Encoding($false)))
    $state.reset = [ordered]@{
        command  = "adb -s $DeviceSerial shell dumpsys gfxinfo $Package reset"
        exitCode = $reset.exitCode
        output   = $reset.text.Trim()
        log      = $resetPath
    }
    if ($reset.exitCode -ne 0) {
        Stop-FrameRun -Status 'BLOCKED' -Reason ('gfxinfo reset failed with exit ' + $reset.exitCode + '; see gfxinfo-reset.txt')
    }

    # --- interaction + measurement -----------------------------------------------------------
    $measureStarted = Get-Date
    $steps = New-Object System.Collections.Generic.List[object]
    $interactionFailures = New-Object System.Collections.Generic.List[string]
    foreach ($action in $requestedActions) {
        $stepStart = Get-Date
        switch ($action) {
            'drawer' {
                & $adb -s $DeviceSerial shell input tap $plan.drawer.x $plan.drawer.y 2>&1 | Out-Null
                Start-Sleep -Milliseconds $PanelOpenMs
                $openedDump = Get-UiDumpRaw -Adb $adb -Serial $DeviceSerial
                $observed = Test-UiMarker -Raw $openedDump -Attribute 'content-desc' -Value '搜索历史'
                & $adb -s $DeviceSerial shell input keyevent 4 2>&1 | Out-Null
                Start-Sleep -Milliseconds $PanelCloseMs
                $steps.Add([pscustomobject]@{ action = 'drawer'; observed = $observed; marker = 'content-desc=搜索历史'; note = ''; elapsedMs = [int](((Get-Date) - $stepStart).TotalMilliseconds) }) | Out-Null
                if (-not $observed) { $interactionFailures.Add('drawer did not open (搜索历史 was not visible)') | Out-Null }
            }
            'model' {
                & $adb -s $DeviceSerial shell input tap $plan.model.x $plan.model.y 2>&1 | Out-Null
                Start-Sleep -Milliseconds $PanelOpenMs
                $openedDump = Get-UiDumpRaw -Adb $adb -Serial $DeviceSerial
                $observed = Test-UiMarker -Raw $openedDump -Attribute 'text' -Value '推理来源'
                & $adb -s $DeviceSerial shell input keyevent 4 2>&1 | Out-Null
                Start-Sleep -Milliseconds $PanelCloseMs
                $steps.Add([pscustomobject]@{ action = 'model'; observed = $observed; marker = 'text=推理来源'; note = ''; elapsedMs = [int](((Get-Date) - $stepStart).TotalMilliseconds) }) | Out-Null
                if (-not $observed) { $interactionFailures.Add('model panel did not open (推理来源 was not visible)') | Out-Null }
            }
            'scroll' {
                $box = $plan.scroll
                $centerX = [int](($box.left + $box.right) / 2)
                $upperY = [int]($box.top + ($box.height * 0.30))
                $lowerY = [int]($box.top + ($box.height * 0.72))
                $swipes = 0
                for ($i = 1; $i -le $ScrollCount; $i++) {
                    if ($i % 2 -eq 1) { $startY = $lowerY; $endY = $upperY } else { $startY = $upperY; $endY = $lowerY }
                    & $adb -s $DeviceSerial shell input swipe $centerX $startY $centerX $endY 240 2>&1 | Out-Null
                    $swipes++
                    Start-Sleep -Milliseconds $ScrollIntervalMs
                }
                $steps.Add([pscustomobject]@{ action = 'scroll'; observed = $true; marker = ''; note = ("alternating vertical swipes: $swipes"); elapsedMs = [int](((Get-Date) - $stepStart).TotalMilliseconds) }) | Out-Null
            }
            'settings' {
                & $adb -s $DeviceSerial shell input tap $plan.settingsMenu.x $plan.settingsMenu.y 2>&1 | Out-Null
                Start-Sleep -Milliseconds $PanelOpenMs
                $menuDump = Get-UiDumpRaw -Adb $adb -Serial $DeviceSerial
                $menuObserved = (Test-UiMarker -Raw $menuDump -Attribute 'text' -Value 'MuYu Chat Agent') -or
                    (Test-UiMarker -Raw $menuDump -Attribute 'content-desc' -Value '关闭')
                & $adb -s $DeviceSerial shell input tap $plan.settingsEntry.x $plan.settingsEntry.y 2>&1 | Out-Null
                Start-Sleep -Milliseconds $PanelOpenMs
                $settingsDump = Get-UiDumpRaw -Adb $adb -Serial $DeviceSerial
                $settingsObserved = (Test-UiMarker -Raw $settingsDump -Attribute 'text' -Value '系统设置') -and
                    (Test-UiMarker -Raw $settingsDump -Attribute 'content-desc' -Value '返回聊天')
                $box = $null
                if ($settingsObserved) { $box = Find-ScrollableBox -Raw $settingsDump }
                if (-not $box) { $box = $plan.settingsScroll; $settingsObserved = $false }
                $swipes = 0
                if ($settingsObserved) {
                    $settingsCenterX = [int](($box.left + $box.right) / 2)
                    $settingsUpperY = [int]($box.top + ($box.height * 0.30))
                    $settingsLowerY = [int]($box.top + ($box.height * 0.72))
                    for ($i = 1; $i -le $ScrollCount; $i++) {
                        if ($i % 2 -eq 1) { $startY = $settingsLowerY; $endY = $settingsUpperY } else { $startY = $settingsUpperY; $endY = $settingsLowerY }
                        & $adb -s $DeviceSerial shell input swipe $settingsCenterX $startY $settingsCenterX $endY 240 2>&1 | Out-Null
                        $swipes++
                        Start-Sleep -Milliseconds $ScrollIntervalMs
                    }
                }
                & $adb -s $DeviceSerial shell input tap $plan.settingsBack.x $plan.settingsBack.y 2>&1 | Out-Null
                Start-Sleep -Milliseconds $PanelCloseMs
                $homeObserved = $false
                for ($attempt = 0; $attempt -lt 3; $attempt++) {
                    $returnDump = Get-UiDumpRaw -Adb $adb -Serial $DeviceSerial
                    if (Test-UiMarker -Raw $returnDump -Attribute 'content-desc' -Value '打开历史') { $homeObserved = $true; break }
                    & $adb -s $DeviceSerial shell input keyevent 4 2>&1 | Out-Null
                    Start-Sleep -Milliseconds $PanelCloseMs
                }
                $observed = ($menuObserved -and $settingsObserved -and $homeObserved)
                $settingsNote = "menu=$menuObserved settings=$settingsObserved home=$homeObserved swipes=$swipes"
                $steps.Add([pscustomobject]@{ action = 'settings'; observed = $observed; marker = '系统设置/返回聊天'; note = $settingsNote; elapsedMs = [int](((Get-Date) - $stepStart).TotalMilliseconds) }) | Out-Null
                if (-not $observed) { $interactionFailures.Add('settings path did not complete (' + $settingsNote + ')') | Out-Null }
            }
        }
        Start-Sleep -Milliseconds $StepDelayMs
    }
    Start-Sleep -Milliseconds $SettleMs

    $capture = Invoke-AdbCapture -Adb $adb -Arguments @('-s', $DeviceSerial, 'shell', 'dumpsys', 'gfxinfo', $Package)
    $measureFinished = Get-Date
    [System.IO.File]::WriteAllText($gfxinfoPath, $capture.text, (New-Object System.Text.UTF8Encoding($false)))
    if ($capture.exitCode -ne 0 -or [string]::IsNullOrWhiteSpace($capture.text)) {
        Stop-FrameRun -Status 'BLOCKED' -Reason ('gfxinfo capture failed or was empty (exit ' + $capture.exitCode + '); see gfxinfo.txt')
    }
    $gfxinfoFile = Get-Item -LiteralPath $gfxinfoPath
    $sha = (Get-FileHash -LiteralPath $gfxinfoPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $state.gfxinfo = [ordered]@{
        path     = $gfxinfoPath
        bytes    = $gfxinfoFile.Length
        sha256   = $sha
        exitCode = $capture.exitCode
    }
    $state.steps = @($steps.ToArray())
    $state.durationMs = [int](($measureFinished - $measureStarted).TotalMilliseconds)

    # --- parse + judge -----------------------------------------------------------------------
    $metrics = ConvertFrom-GfxInfo -Text $capture.text
    $state.metrics = $metrics
    $missing = New-Object System.Collections.Generic.List[string]
    if ($null -eq $metrics.totalFrames) { $missing.Add('Total frames rendered') | Out-Null }
    if ($null -eq $metrics.jankyFrames) { $missing.Add('Janky frames') | Out-Null }
    if ($null -eq $metrics.jankyPercent) { $missing.Add('Janky frames percentage') | Out-Null }
    $requiredPercentiles = [ordered]@{
        p50Ms = '50th percentile'
        p90Ms = '90th percentile'
        p95Ms = '95th percentile'
        p99Ms = '99th percentile'
    }
    foreach ($key in $requiredPercentiles.Keys) {
        if ($null -eq $metrics[$key]) { $missing.Add($requiredPercentiles[$key]) | Out-Null }
    }

    if ($interactionFailures.Count -gt 0) {
        $state.verdict = 'BLOCKED'
        $state.reason = 'interaction did not visibly execute: ' + ($interactionFailures -join '; ') + '; the frame summary is not attributable to the requested workload'
    } elseif ($null -ne $metrics.totalFrames -and $metrics.totalFrames -eq 0) {
        $state.verdict = 'NOT_RUN'
        $state.reason = 'gfxinfo reported zero rendered frames; no evidence of the interaction'
    } elseif ($missing.Count -gt 0) {
        $state.verdict = 'NOT_RUN'
        $state.reason = 'gfxinfo output is missing required metric(s): ' + ($missing -join ', ') + ' (unsupported/partial output; not a PASS)'
    } elseif ($metrics.totalFrames -lt $MinFrames) {
        $state.verdict = 'NOT_RUN'
        $state.reason = ('insufficient sample: Total frames rendered=' + $metrics.totalFrames + ' < MinFrames=' + $MinFrames)
    } else {
        $p95 = [double]$metrics.p95Ms
        $janky = [double]$metrics.jankyPercent
        $p95Pass = ($p95 -le $MaxP95Ms)
        $jankyPass = ($janky -le $MaxJankyPercent)
        if (-not $p95Pass -or -not $jankyPass) {
            $state.verdict = 'FAIL'
            $reasons = @()
            if (-not $p95Pass) { $reasons += ('P95=' + $p95 + 'ms > MaxP95Ms=' + $MaxP95Ms) }
            if (-not $jankyPass) { $reasons += ('janky=' + $janky + '% > MaxJankyPercent=' + $MaxJankyPercent) }
            $state.reason = 'threshold exceeded: ' + ($reasons -join '; ')
        } else {
            $state.verdict = 'PASS'
            $state.reason = ('sample valid: frames=' + $metrics.totalFrames + ', P95=' + $p95 + 'ms <= ' + $MaxP95Ms + 'ms, janky=' + $janky + '% <= ' + $MaxJankyPercent + '%')
        }
    }
} catch {
    if ($script:abortStatus) {
        $state.verdict = $script:abortStatus
        $state.reason = $script:abortReason
    } else {
        $state.verdict = 'FAIL'
        $state.reason = 'unexpected error: ' + $_.Exception.Message
    }
}

if ($state.verdict -eq 'PASS') { $state.exitCode = 0 }
elseif ($state.verdict -eq 'FAIL') { $state.exitCode = 1 }
else { $state.exitCode = 3 }
$state.generatedAt = (Get-Date).ToString('o')

# The evidence write is part of the result: a run whose summary cannot be written must not print
# PASS and exit 0, because the only record of the sample would be missing.
# The write result is itself evidence, so it has to be inside the file it describes. The summary is
# written once to learn the outcome and once with that outcome recorded; the recorded value is the
# first attempt, which is the one that proved the directory is writable.
$state.evidenceWrite = [ordered]@{ written = $false; path = $summaryPath; error = 'not attempted' }
$firstWrite = Save-McaFrameSummary -Path $summaryPath -State $state
$state.evidenceWrite = $firstWrite
[void](Save-McaFrameSummary -Path $summaryPath -State $state)
if ($state.evidenceWrite.written -ne $true) {
    [Console]::Error.WriteLine('measure-frames: cannot write summary.json: ' + [string]$state.evidenceWrite.error)
    $state.verdict = 'FAIL'
    $state.reason = 'evidence write failed: ' + [string]$state.evidenceWrite.error
    $state.exitCode = 4
}

Write-Output ('VERDICT: ' + $state.verdict)
Write-Output ('REASON:  ' + $state.reason)
if ($state.metrics) {
    $m = $state.metrics
    Write-Output ('METRICS: frames=' + $m.totalFrames + ' jankyFrames=' + $m.jankyFrames + ' jankyPercent=' + $m.jankyPercent +
        ' p50=' + $m.p50Ms + 'ms p90=' + $m.p90Ms + 'ms p95=' + $m.p95Ms + 'ms p99=' + $m.p99Ms + 'ms missedVsync=' + $m.missedVsync)
}
if ($state.reset) { Write-Output ('RESET:   exit=' + $state.reset.exitCode) }
Write-Output ('EVIDENCE: ' + $outDirectory)
exit $state.exitCode
