param(
    [string] $DeviceSerial = '',
    [string] $ApkPath = '',
    # R12 round 2: point this at an earlier run directory (same device, same font scale). The run then
    # writes a11y-naming.json and r12-comparison.json next to its own evidence. These are recorded as
    # evidence and do NOT change the behaviour verdict: naming and visual deltas are reviewed from the
    # two JSON files, not folded into a PASS/FAIL that would mix three different questions.
    [string] $CompareBefore = '',
    [string] $OutRoot = 'artifacts/ui-quality/v9-closeout/device-behavior',
    [switch] $SkipInstall
)
<#
Frontend behaviour smoke for the V9-changed surfaces (R11.3 / R12 preparation).

This is NOT a quality or performance verdict: it installs the supplied debug candidate, exercises the
surfaces this round changed (composer menu lifecycle, research submenu, drawer, model capsule, back
handling, composer input) and records what the device actually showed. Every check is
PASS / FAIL / NOT_RUN with the raw UI dump, screenshot and logcat behind it.

Rules kept from the plan:
  * install with -r, never clear data, never change applicationId;
  * a state that cannot be found is NOT_RUN with the reason, never PASS;
  * a crash or ANR during the run is a hard FAIL (non-zero exit);
  * the APK hash and the device facts are recorded so the evidence names its own binary.
#>
$ErrorActionPreference = 'Stop'

$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runId = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss-fffZ')
$outDir = Join-Path $repo (Join-Path $OutRoot $runId)
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

function Resolve-Adb {
    $candidates = @()
    if ($env:ANDROID_HOME) { $candidates += (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe') }
    if ($env:ANDROID_SDK_ROOT) { $candidates += (Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe') }
    $local = Join-Path $repo 'local.properties'
    if (Test-Path -LiteralPath $local) {
        $line = (Get-Content -LiteralPath $local | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1)
        if ($line) {
            $sdk = ($line -replace '^sdk\.dir=', '').Trim() -replace '\\:', ':' -replace '\\\\', '\'
            $candidates += (Join-Path $sdk 'platform-tools\adb.exe')
        }
    }
    $candidates += 'D:\model\android-sdk\platform-tools\adb.exe'
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) { return $candidate }
    }
    throw 'adb not found (PATH, ANDROID_HOME/ANDROID_SDK_ROOT, local.properties sdk.dir, known SDK).'
}

$adb = Resolve-Adb

function Invoke-Adb {
    param([string[]] $Arguments)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        # Windows PowerShell surfaces a native command's stderr as ErrorRecord objects when the
        # streams are merged. Keep the real text only: the ErrorRecord decoration ("At line:1 char:..."
        # plus the CategoryInfo block) is noise in evidence files.
        $merged = @(& $adb @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previous }
    $stdout = @($merged | Where-Object { $_ -isnot [System.Management.Automation.ErrorRecord] } |
            ForEach-Object { [string]$_ })
    $stderr = @($merged | Where-Object { $_ -is [System.Management.Automation.ErrorRecord] } |
            ForEach-Object { ([string]$_ -split "`r?`n")[0] })
    $text = (@($stdout) + @($stderr) | Where-Object { $_ }) -join "`n"
    return [pscustomobject]@{ exitCode = $exitCode; text = $text.Trim() }
}

function Invoke-AdbSerial {
    param([string[]] $Arguments)
    return Invoke-Adb -Arguments (@('-s', $script:serial) + $Arguments)
}

$script:serial = $DeviceSerial
$results = New-Object System.Collections.Generic.List[object]

function Add-Result {
    param([string] $Check, [string] $Status, [string] $Detail, [string] $Evidence = '')
    $results.Add([pscustomobject]@{
            check = $Check; status = $Status; detail = $Detail; evidence = $Evidence
        }) | Out-Null
    Write-Output ("[{0}] {1}: {2}" -f $Status, $Check, $Detail)
}

# ---------------------------------------------------------------- device + binary facts
& $adb start-server | Out-Null
$online = @((Invoke-Adb -Arguments @('devices')).text -split "`r?`n" | Where-Object { $_ -match '\sdevice\s*$' } |
        ForEach-Object { ($_ -split '\s+')[0] })
if (-not $script:serial) {
    if ($online.Count -eq 1) { $script:serial = $online[0] }
    else { throw ("expected exactly one online device or -DeviceSerial; online: '{0}'" -f ($online -join ',')) }
}
if (-not ($online -contains $script:serial)) { throw "device '$($script:serial)' is not online" }

$facts = [ordered]@{
    runId = $runId
    serial = $script:serial
    model = (Invoke-AdbSerial @('shell', 'getprop', 'ro.product.model')).text
    sdk = (Invoke-AdbSerial @('shell', 'getprop', 'ro.build.version.sdk')).text
    abi = (Invoke-AdbSerial @('shell', 'getprop', 'ro.product.cpu.abi')).text
    size = (Invoke-AdbSerial @('shell', 'wm', 'size')).text
    density = (Invoke-AdbSerial @('shell', 'wm', 'density')).text
    fontScale = (Invoke-AdbSerial @('shell', 'settings', 'get', 'system', 'font_scale')).text
    animatorScale = (Invoke-AdbSerial @('shell', 'settings', 'get', 'global', 'animator_duration_scale')).text
    peakRefresh = (Invoke-AdbSerial @('shell', 'settings', 'get', 'system', 'peak_refresh_rate')).text
}
Write-Output ('DEVICE: {0} (sdk {1}, {2}, {3}, {4})' -f $facts.model, $facts.sdk, $facts.size, $facts.density, $facts.fontScale)

$package = 'com.muyuchat.mca'
$activity = 'com.muyuchat.mca/.MainActivity'

if (-not $SkipInstall) {
    if (-not $ApkPath) { throw 'ApkPath is required unless -SkipInstall is used.' }
    $apk = (Resolve-Path -LiteralPath $ApkPath).Path
    $facts.apkPath = $apk
    $facts.apkSha256 = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    $installLog = New-Object System.Collections.Generic.List[string]
    $installOk = $false
    $installDetail = ''

    # Some OEM builds reject the streamed `adb install` path with
    # INSTALL_FAILED_USER_RESTRICTED ("Install canceled by user") while the package-manager session
    # API succeeds (verified on 2304FPN6DC / API 36). Try the session path first, then the documented
    # `adb install -r` fallback, and record both attempts either way.
    $size = (Get-Item -LiteralPath $apk).Length
    $session = Invoke-AdbSerial @('shell', 'pm', 'install-create', '-r', '-t', '-S', [string]$size)
    $installLog.Add('install-create: ' + $session.text) | Out-Null
    if ($session.text -match '\[(\d+)\]') {
        $sessionId = $Matches[1]
        $remote = '/data/local/tmp/mca-candidate.apk'
        $previous = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try { & $adb -s $script:serial push $apk $remote *> $null } finally { $ErrorActionPreference = $previous }
        $write = Invoke-AdbSerial @('shell', 'pm', 'install-write', '-S', [string]$size, $sessionId, 'base', $remote)
        $installLog.Add('install-write: ' + $write.text) | Out-Null
        $commit = Invoke-AdbSerial @('shell', 'pm', 'install-commit', $sessionId)
        $installLog.Add('install-commit: ' + $commit.text) | Out-Null
        Invoke-AdbSerial @('shell', 'rm', '-f', $remote) | Out-Null
        if ($commit.text -match 'Success') {
            $installOk = $true
            $installDetail = 'session install: ' + $commit.text
        } else {
            Invoke-AdbSerial @('shell', 'pm', 'install-abandon', $sessionId) | Out-Null
            $installDetail = 'session install failed: ' + $commit.text
        }
    } else {
        $installDetail = 'install-create failed: ' + $session.text
    }
    if (-not $installOk) {
        $install = Invoke-AdbSerial @('install', '-r', $apk)
        $installLog.Add('adb install -r: ' + $install.text) | Out-Null
        if ($install.text -match 'Success') {
            $installOk = $true
            $installDetail = 'adb install -r: ' + $install.text
        } else {
            $installDetail = $installDetail + ' | ' + $install.text
        }
    }
    [IO.File]::WriteAllText((Join-Path $outDir 'install.log'), ($installLog -join "`n"), [Text.UTF8Encoding]::new($false))
    if (-not $installOk) {
        $facts.installDetail = $installDetail
        Add-Result -Check 'install' -Status 'FAIL' -Detail $installDetail -Evidence 'install.log'
        $facts.results = $results.ToArray()
        $facts.hardFailures = @($results | Where-Object { $_.status -eq 'FAIL' }).Count
        $facts.notRun = @($results | Where-Object { $_.status -eq 'NOT_RUN' }).Count
        $facts.verdict = 'FAIL'
        [IO.File]::WriteAllText((Join-Path $outDir 'RUN.json'), ($facts | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
        Write-Output 'VERDICT: FAIL'
        Write-Output ('EVIDENCE: ' + $outDir)
        exit 1
    }
    $installed = (Invoke-AdbSerial @('shell', 'pm', 'path', $package)).text
    Add-Result -Check 'install' -Status 'PASS' -Detail (($installDetail + ' ' + ($installed -replace "`r?`n", ' ')).Trim()) -Evidence 'install.log'
}

# ---------------------------------------------------------------- helpers
function Clear-Logcat {
    Invoke-AdbSerial @('logcat', '-c') | Out-Null
}

function Save-Logcat {
    param([string] $Name)
    $path = Join-Path $outDir ($Name + '.logcat.txt')
    $text = (Invoke-AdbSerial @('logcat', '-d', '-v', 'time')).text
    [IO.File]::WriteAllText($path, $text, [Text.UTF8Encoding]::new($false))
    return $path
}

function Save-Screenshot {
    param([string] $Name)
    # Capture on the device and pull the file: `exec-out ... > file` in Windows PowerShell rewrites
    # the stream as text and corrupts the PNG.
    $path = Join-Path $outDir ($Name + '.png')
    $remote = '/sdcard/mca-shot.png'
    Invoke-AdbSerial @('shell', 'screencap', '-p', $remote) | Out-Null
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $adb -s $script:serial pull $remote $path *> $null
    } finally { $ErrorActionPreference = $previous }
    Invoke-AdbSerial @('shell', 'rm', '-f', $remote) | Out-Null
    if ((Test-Path -LiteralPath $path) -and ((Get-Item -LiteralPath $path).Length -gt 1000)) { return $path }
    return ''
}

function Save-UiDump {
    param([string] $Name)
    $path = Join-Path $outDir ($Name + '.ui.xml')
    # `uiautomator dump` is a separate process and fails with
    # "UiAutomationService ... already registered" when another automation client (a connected test,
    # for example) holds the connection. Retry instead of recording an empty dump as a UI state.
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        # Delete the target first: when `uiautomator dump` fails (another automation client holds
        # UiAutomation) it leaves the previous file in place, and a `cat` would silently record a
        # stale screen from whatever app wrote it last.
        Invoke-AdbSerial @('shell', 'rm', '-f', '/sdcard/mca-ui.xml') | Out-Null
        $dump = Invoke-AdbSerial @('shell', 'uiautomator', 'dump', '/sdcard/mca-ui.xml')
        $dumped = ($dump.text -match 'dumped to') -and ($dump.text -notmatch 'ERROR|Exception')
        $text = ''
        if ($dumped) {
            $text = (Invoke-AdbSerial @('shell', 'cat', '/sdcard/mca-ui.xml')).text
        }
        if ($dumped -and $text -match '<hierarchy' -and $text -match 'bounds=') {
            # Freshness guard. A dump can succeed yet still describe the previous screen: the file is
            # written asynchronously, and a tap that did not land leaves the same tree behind. Two
            # consecutive dumps that agree on the node count are the cheapest proof that the tree is
            # settled; when they disagree the screen is still changing, so retry instead of recording
            # a half-updated state as the UI.
            $firstCount = ([regex]::Matches($text, '<node ')).Count
            Invoke-AdbSerial @('shell', 'rm', '-f', '/sdcard/mca-ui2.xml') | Out-Null
            $secondDump = Invoke-AdbSerial @('shell', 'uiautomator', 'dump', '/sdcard/mca-ui2.xml')
            $secondText = ''
            if (($secondDump.text -match 'dumped to') -and ($secondDump.text -notmatch 'ERROR|Exception')) {
                $secondText = (Invoke-AdbSerial @('shell', 'cat', '/sdcard/mca-ui2.xml')).text
            }
            $secondCount = ([regex]::Matches($secondText, '<node ')).Count
            if ($secondCount -gt 0 -and $secondCount -eq $firstCount) {
                [IO.File]::WriteAllText($path, $text, [Text.UTF8Encoding]::new($false))
                return $path
            }
            Write-Output ('WARN: dump for ' + $Name + ' changed between two reads (' + $firstCount + ' -> ' + $secondCount + '); retrying')
        }
        Start-Sleep -Milliseconds 1500
    }
    [IO.File]::WriteAllText($path, $text, [Text.UTF8Encoding]::new($false))
    Write-Output ('WARN: uiautomator dump did not produce a hierarchy after 3 attempts for ' + $Name)
    return $path
}

function ConvertTo-UiNodes {
    param([string] $XmlPath)
    try {
        [xml] $doc = Get-Content -LiteralPath $XmlPath -Raw -Encoding UTF8
        return @($doc.SelectNodes('//node'))
    } catch { return @() }
}

function Find-UiNode {
    param(
        [object[]] $Nodes,
        [string] $Text = '',
        [string] $Description = ''
    )
    foreach ($node in $Nodes) {
        if ($Text -and $node.text -like ('*' + $Text + '*')) { return $node }
        if ($Description -and $node.'content-desc' -like ('*' + $Description + '*')) { return $node }
    }
    return $null
}

function Get-NodeCenter {
    param($Node)
    $bounds = [string]$Node.bounds
    if ($bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { return $null }
    $x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]; $x2 = [int]$Matches[3]; $y2 = [int]$Matches[4]
    if ($x2 -le $x1 -or $y2 -le $y1) { return $null }
    return [pscustomobject]@{ x = [int](($x1 + $x2) / 2); y = [int](($y1 + $y2) / 2) }
}

function Tap-Node {
    param($Node)
    $center = Get-NodeCenter -Node $Node
    if (-not $center) { return $false }
    Invoke-AdbSerial @('shell', 'input', 'tap', [string]$center.x, [string]$center.y) | Out-Null
    Start-Sleep -Milliseconds 700
    return $true
}

function Get-PopupWindowCount {
    # INFORMATIONAL ONLY. The composer menu is NOT a separate window: it is a same-window overlay
    # (BoxWithConstraints -> AnimatedVisibility -> Surface), so this count is legitimately 0 on every
    # device, including runs where the menu is visibly open. Treating it as evidence for "the popup is
    # not exposed" produced two wrong conclusions before this was checked against fresh dumps.
    $dump = (Invoke-AdbSerial @('shell', 'dumpsys', 'window', 'windows')).text
    return @($dump -split "`r?`n" | Where-Object { $_ -match '^\s*Window #\d+ Window\{.*PopupWindow' }).Count
}

function Confirm-Foreground {
    # A tap that misses the app (system dialog, launcher, another app) leaves a perfectly valid dump
    # that says nothing about our UI. Every interaction step therefore re-checks that the focused
    # window still belongs to the package before believing the dump.
    param([string] $PackageName)
    $focus = (Invoke-AdbSerial @('shell', 'dumpsys', 'window')).text
    $match = [regex]::Match($focus, 'mCurrentFocus=Window\{[0-9a-f]+ u0 ([^/}]+)/')
    if (-not $match.Success) { return $false }
    return $match.Groups[1].Value -eq $PackageName
}

function Wait-ForAppFocus {
    param([string] $PackageName, [int] $TimeoutMs = 12000)
    $deadline = (Get-Date).AddMilliseconds($TimeoutMs)
    while ((Get-Date) -lt $deadline) {
        $focus = (Invoke-AdbSerial @('shell', 'dumpsys', 'window')).text
        $match = [regex]::Match($focus, 'mCurrentFocus=Window\{[0-9a-f]+ u0 ([^/}]+)/')
        if ($match.Success -and $match.Groups[1].Value -eq $PackageName) { return $true }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function Tap-UntilConfirmed {
    param($Node, [scriptblock] $Confirm, [int] $Attempts = 3)
    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        if (-not (Tap-Node -Node $Node)) { return $false }
        Start-Sleep -Milliseconds 900
        if (& $Confirm) { return $true }
    }
    return $false
}

function Press-Back {
    Invoke-AdbSerial @('shell', 'input', 'keyevent', 'KEYCODE_BACK') | Out-Null
    Start-Sleep -Milliseconds 600
}

function Test-CrashInLog {
    param([string] $LogText, [string] $PackageName)
    # Only OUR process counts. A system-wide search is wrong: `uiautomator dump` is a separate
    # process that fails with "UiAutomationService already registered" whenever another automation
    # client holds the connection, and that FATAL says nothing about this app.
    $lines = $LogText -split "`r?`n"
    $signatures = New-Object System.Collections.Generic.List[string]

    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -notmatch 'FATAL EXCEPTION') { continue }
        $window = $lines[$i..([Math]::Min($i + 12, $lines.Count - 1))] -join "`n"
        if ($window -match ('Process:\s*' + [regex]::Escape($PackageName) + '\b')) {
            $signatures.Add(('FATAL EXCEPTION for ' + $PackageName + ' at line ' + ($i + 1))) | Out-Null
        }
    }
    foreach ($line in $lines) {
        if ($line -match ('ANR in ' + [regex]::Escape($PackageName) + '\b')) {
            $signatures.Add('ANR: ' + $line.Trim()) | Out-Null
        }
        if ($line -match ('Force finishing activity .*' + [regex]::Escape($PackageName))) {
            $signatures.Add('Force finishing: ' + $line.Trim()) | Out-Null
        }
    }
    return $signatures.ToArray()
}

# ---------------------------------------------------------------- launch
Clear-Logcat
# A reinstall does not reset the running app's UI state: a drawer or menu left open by a previous
# run survives `am start` (it only brings the existing task forward). Force-stop first so every run
# starts from the same place and the first dump is the chat surface, not whatever was left open.
Invoke-AdbSerial @('shell', 'am', 'force-stop', $package) | Out-Null
Start-Sleep -Milliseconds 1200
$start = Invoke-AdbSerial @('shell', 'am', 'start', '-W', '-n', $activity)
($start.text | Out-File -LiteralPath (Join-Path $outDir 'launch.log') -Encoding utf8)
$launched = $start.text -match 'Status:\s*ok'
Add-Result -Check 'cold launch MainActivity' -Status ($(if ($launched) { 'PASS' } else { 'FAIL' })) `
    -Detail (($start.text -split "`r?`n" | Where-Object { $_ -match 'Status:|TotalTime:|WaitTime:|Error' }) -join ' | ') `
    -Evidence 'launch.log'
Start-Sleep -Milliseconds 2500
$focused = Wait-ForAppFocus -PackageName $package
Add-Result -Check 'app window has focus before interaction' -Status ($(if ($focused) { 'PASS' } else { 'FAIL' })) `
    -Detail ('mCurrentFocus belongs to the package: ' + [bool]$focused)

$chatDump = Save-UiDump 'state-chat'
$chatShot = Save-Screenshot 'state-chat'
$chatNodes = ConvertTo-UiNodes -XmlPath $chatDump
$composer = Find-UiNode -Nodes $chatNodes -Description '消息输入框'
$modelPill = Find-UiNode -Nodes $chatNodes -Description '选择模型'
if (-not $modelPill) { $modelPill = Find-UiNode -Nodes $chatNodes -Text '已就绪' }
if (-not $modelPill) { $modelPill = Find-UiNode -Nodes $chatNodes -Text '待加载' }
Add-Result -Check 'chat surface (composer visible)' -Status ($(if ($composer) { 'PASS' } else { 'FAIL' })) `
    -Detail ('composer=' + [bool]$composer + ' modelCapsule=' + [bool]$modelPill) -Evidence 'state-chat.ui.xml'
$facts.modelCapsuleText = if ($modelPill) { ($modelPill.text + ' ' + $modelPill.'content-desc').Trim() } else { '' }
$facts.screenshotChat = $chatShot

# ---------------------------------------------------------------- composer menu: open -> back
$menuOpened = $false
$more = Find-UiNode -Nodes $chatNodes -Description '更多操作'
if ($more) { $menuOpened = Tap-Node -Node $more }
if (-not $menuOpened) {
    Add-Result -Check 'composer menu opens' -Status 'NOT_RUN' -Detail 'the 更多操作 trigger was not found on the chat surface' -Evidence 'state-chat.ui.xml'
} else {
    $menuDump = Save-UiDump 'state-menu-open'
    $menuNodes = ConvertTo-UiNodes -XmlPath $menuDump
    # The action sheet's rows are the observable markers; 研究模式 is the submenu entry.
    $menuMarkers = @('相机', '照片', '文件', '研究模式')
    $menuRows = @($menuNodes | Where-Object {
            $nodeText = ([string]$_.text) + ' ' + ([string]$_.'content-desc')
            ($menuMarkers | Where-Object { $nodeText -like ('*' + $_ + '*') }).Count -gt 0
        })
    $researchRow = Find-UiNode -Nodes $menuNodes -Text '研究模式'
    $popupWhileOpen = Get-PopupWindowCount
    $menuShot = Save-Screenshot 'state-menu-open'
    $foregroundAfterTap = Confirm-Foreground -PackageName $package
    # Rows are the only real signal: the sheet is a same-window overlay (see Get-PopupWindowCount).
    $menuVisible = ($menuRows.Count -gt 0) -and $foregroundAfterTap
    if (-not $menuVisible) {
        # The popup is composed but this OEM build reports neither it in the accessibility dump nor a
        # PopupWindow in the window list. Record that honestly instead of inventing a signal: the
        # screenshot is the evidence and the state is NOT_RUN, never PASS.
        Add-Result -Check 'composer menu opens' -Status 'NOT_RUN' `
            -Detail ("dumpRows=$($menuRows.Count) popupWindows=$popupWhileOpen researchRow=" + [bool]$researchRow +
                ' foreground=' + $foregroundAfterTap +
                '; no row was found. Check the screenshot: rows missing AND the app in foreground is a product signal, rows missing with the app gone is a tooling signal') `
            -Evidence (Split-Path $menuShot -Leaf)
    } else {
        Add-Result -Check 'composer menu opens' -Status 'PASS' `
            -Detail ("dumpRows=$($menuRows.Count) popupWindows=$popupWhileOpen researchRow=" + [bool]$researchRow) `
            -Evidence 'state-menu-open.ui.xml + state-menu-open.png'
    }

    Press-Back
    $afterBack = Save-UiDump 'state-menu-after-back'
    $afterBackNodes = ConvertTo-UiNodes -XmlPath $afterBack
    $stillComposer = Find-UiNode -Nodes $afterBackNodes -Description '消息输入框'
    $menuStillVisible = @($afterBackNodes | Where-Object {
            $nodeText = ([string]$_.text) + ' ' + ([string]$_.'content-desc')
            ($menuMarkers | Where-Object { $nodeText -like ('*' + $_ + '*') }).Count -gt 0
        }).Count -gt 0
    $popupAfterBack = Get-PopupWindowCount
    Add-Result -Check 'system back closes the menu and keeps the page' `
        -Status ($(if ($stillComposer -and -not $menuStillVisible -and $popupAfterBack -eq 0) { 'PASS' } else { 'FAIL' })) `
        -Detail ('composer=' + [bool]$stillComposer + ' menuRowStillVisible=' + [bool]$menuStillVisible + ' popupWindows=' + $popupAfterBack) `
        -Evidence 'state-menu-after-back.ui.xml'
}

# ---------------------------------------------------------------- drawer: open -> back
$drawerTrigger = Find-UiNode -Nodes $chatNodes -Description '打开历史'
if (-not $drawerTrigger) {
    Add-Result -Check 'history drawer opens' -Status 'NOT_RUN' -Detail 'the 打开历史 trigger was not found' -Evidence 'state-chat.ui.xml'
} else {
    $drawerMarked = {
        param($Nodes)
        @($Nodes | Where-Object {
                $desc = [string]$_.'content-desc'
                $nodeText = [string]$_.text
                ($desc -like '*搜索历史*') -or ($nodeText -eq 'Agent') -or ($nodeText -eq 'DSH') -or
                    ($nodeText -eq '默认助手') -or ($nodeText -like '*条消息*')
            }).Count -gt 0
    }
    $drawerOpened = Tap-Node -Node $drawerTrigger
    Start-Sleep -Milliseconds 900
    $drawerDump = Save-UiDump 'state-drawer-open'
    $drawerNodes = ConvertTo-UiNodes -XmlPath $drawerDump
    $drawerRows = @($drawerNodes | Where-Object { $_.clickable -eq 'true' }).Count
    $drawerHasMarker = $drawerMarked.Invoke($drawerNodes)
    Add-Result -Check 'history drawer opens' -Status ($(if ($drawerOpened -or $drawerHasMarker) { 'PASS' } else { 'FAIL' })) `
        -Detail ("clickable rows=$drawerRows drawerMarker=" + [bool]$drawerHasMarker + ' confirmed=' + [bool]$drawerOpened) `
        -Evidence 'state-drawer-open.ui.xml'
    Save-Screenshot 'state-drawer-open' | Out-Null
    Press-Back
    $afterDrawerBack = Save-UiDump 'state-drawer-after-back'
    $afterDrawerNodes = ConvertTo-UiNodes -XmlPath $afterDrawerBack
    $composerBack = Find-UiNode -Nodes $afterDrawerNodes -Description '消息输入框'
    Add-Result -Check 'system back closes the drawer and keeps the page' `
        -Status ($(if ($composerBack) { 'PASS' } else { 'FAIL' })) `
        -Detail ('composer=' + [bool]$composerBack) -Evidence 'state-drawer-after-back.ui.xml'
}

# ---------------------------------------------------------------- composer input round trip
if ($composer) {
    Tap-Node -Node $composer | Out-Null
    Start-Sleep -Milliseconds 400
    $typed = 'mcav9probe'
    Invoke-AdbSerial @('shell', 'input', 'text', $typed) | Out-Null
    Start-Sleep -Milliseconds 600
    $typedDump = Save-UiDump 'state-composer-typed'
    $typedNodes = ConvertTo-UiNodes -XmlPath $typedDump
    $textPresent = @($typedNodes | Where-Object { ([string]$_.text) -like ('*' + $typed + '*') }).Count -gt 0
    Add-Result -Check 'composer accepts typed text' -Status ($(if ($textPresent) { 'PASS' } else { 'FAIL' })) `
        -Detail ('text visible=' + $textPresent) -Evidence 'state-composer-typed.ui.xml'
    # Leave the field empty again so the device is not left with a draft in the evidence run.
    $count = $typed.Length
    $keys = (@('KEYCODE_DEL') * $count)
    Invoke-AdbSerial @('shell', 'input', 'keyevent', ($keys -join ' ')) | Out-Null
    Start-Sleep -Milliseconds 300
}

# ---------------------------------------------------------------- crash / ANR gate
$logPath = Save-Logcat 'run'
$logText = Get-Content -LiteralPath $logPath -Raw
$crashSignatures = @(Test-CrashInLog -LogText $logText -PackageName $package)
Add-Result -Check 'no crash or ANR during the run' -Status ($(if ($crashSignatures.Count -gt 0) { 'FAIL' } else { 'PASS' })) `
    -Detail ($(if ($crashSignatures.Count -gt 0) { ($crashSignatures -join ' | ') } else { 'no FATAL/ANR/force-finish signature names this package' })) `
    -Evidence (Split-Path $logPath -Leaf)

# ---------------------------------------------------------------- a11y naming + round-2 comparison
if ($CompareBefore) {
    $dumps = @(Get-ChildItem -LiteralPath $outDir -Filter 'state-*.ui.xml' -File | ForEach-Object { $_.FullName })
    if ($dumps.Count -gt 0) {
        & (Join-Path $PSScriptRoot 'audit-a11y-naming.ps1') -DumpPaths $dumps -OutDir $outDir | Out-Null
        $auditPath = Join-Path $outDir 'a11y-naming.json'
        if (Test-Path -LiteralPath $auditPath) {
            $audit = Get-Content -LiteralPath $auditPath -Raw -Encoding UTF8 | ConvertFrom-Json
            $facts.a11yAudit = [ordered]@{
                path = $auditPath
                unnamedClickableCount = $audit.unnamedClickableCount
                unnamedClickables = @($audit.unnamedClickables)
            }
            Write-Output ('A11Y: unnamed clickable controls = ' + $audit.unnamedClickableCount)
        }
    }
    $cmpPath = Join-Path $outDir 'r12-comparison.json'
    & (Join-Path $PSScriptRoot 'compare-visual-runs.ps1') -BeforeDir $CompareBefore -AfterDir $outDir -OutFile $cmpPath | Out-Null
    if (Test-Path -LiteralPath $cmpPath) {
        $cmp = Get-Content -LiteralPath $cmpPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $facts.round2Comparison = [ordered]@{ path = $cmpPath; verdict = [string]$cmp.verdict; before = $CompareBefore }
        Write-Output ('ROUND2: verdict=' + [string]$cmp.verdict + ' (' + $cmpPath + ')')
    } else {
        $facts.round2Comparison = [ordered]@{ path = ''; verdict = 'NOT_RUN'; before = $CompareBefore }
        Write-Output 'ROUND2: comparison was not produced (see the script output above)'
    }
}

# ---------------------------------------------------------------- verdict + record
$facts.results = $results.ToArray()
$facts.hardFailures = @($results | Where-Object { $_.status -eq 'FAIL' }).Count
$facts.notRun = @($results | Where-Object { $_.status -eq 'NOT_RUN' }).Count
$facts.verdict = if ($facts.hardFailures -gt 0) { 'FAIL' } elseif ($facts.notRun -gt 0) { 'PASS_WITH_NOT_RUN' } else { 'PASS' }
$facts.scope = 'Behaviour observation of the supplied APK on this device. Not a visual-quality, accessibility or performance verdict.'
($facts | ConvertTo-Json -Depth 8) | Out-File -LiteralPath (Join-Path $outDir 'RUN.json') -Encoding utf8

Write-Output ''
Write-Output ('VERDICT: ' + $facts.verdict)
Write-Output ('EVIDENCE: ' + $outDir)
if ($facts.hardFailures -gt 0) { exit 1 }
exit 0
