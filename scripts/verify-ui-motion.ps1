<#
.SYNOPSIS
    Records a short device screen capture, extracts frames and measures whether the UI
    actually moved. Produces machine-readable motion evidence instead of an opinion.

.DESCRIPTION
    Flow (the tool chain verified manually by the lead):
      1. read the device animation scales; if the animator scale is 0 the run is NOT_RUN
         (Compose animations are intentionally frozen), unless -EnableAnimationScale is given;
      2. -EnableAnimationScale temporarily sets all three scales to 1 and ALWAYS restores the
         original values in a finally block (restoration is verified);
      3. adb screenrecord records the window while the requested production UI actions run;
      4. ffmpeg extracts frames at -Fps (default 10);
      5. PIL computes the mean absolute difference between adjacent frames;
      6. a frame counts as "motion" when meanDiff >= -MotionThreshold. The run is PASS only when
         at least -MinMotionFrames frames contain motion; otherwise it is FAIL (never a guessed
         PASS).

    The JSON output (motion.json) contains: recording path/sha256/bytes, frame count/size,
    motion frame count, max/p95 frame difference, the full timeline and merged motion ranges,
    per-action observations, device/app/revision context and the before/effective/after animation
    scales.

    This is evidence of *motion in the recorded pixels*, not evidence of animation quality,
    curve shape, duration correctness or visual equivalence. A PASS says "pixels changed across
    several frames at a rate above the noise threshold"; it does NOT say "the animation is good".

.PARAMETER DeviceSerial
    adb serial. When omitted exactly one online device is used; zero or multiple devices are
    BLOCKED instead of a silent guess.

.PARAMETER Stage
    Free-form label for the evidence bundle (for example `chat-drawer`, `menu-open`, `send`).
    The script still requires the app to be on the chat screen before it records.

.PARAMETER OutDir
    Evidence directory. Default: <repo>/artifacts/ui-motion/<stage>-<timestamp>-<pid>.
    When an existing non-empty directory is supplied, a run-id child directory is created so
    previous evidence is never overwritten.

.PARAMETER Actions
    Ordered production actions to perform while recording. Supported: drawer, menu, model,
    newchat, send. Pass a comma-separated list (`-Actions menu,model`) or a native PowerShell
    array. `send` is always moved to the end because it needs a typed composer; it also
    requires a ready chat model (otherwise the run is BLOCKED, not a fake motion sample).
    An empty list records an idle screen; that is the intentional no-motion FAIL diagnostic.

.PARAMETER EnableAnimationScale
    Temporarily set window/transition/animator scales to 1 for the recording and restore the
    exact original values afterwards. Without this switch a 0 animation scale produces NOT_RUN.

.PARAMETER MotionThreshold
    Adjacent-frame mean absolute difference (0..255, averaged over RGB) that counts as motion.
    Default 1.0. Calibration on a 1440x3200 device: idle chat p95 = 0.03, idle max = 0.45,
    drawer open/close peak = 89.7. Raise it for noisier scenes, lower it for very subtle fades.

.PARAMETER MinMotionFrames
    Minimum number of frames at/above the threshold required for PASS. Default 2: a single
    instant repaint is not an animation.

.PARAMETER Fps
    Frame extraction rate. Default 10. The timeline records milliseconds per frame pair.

.PARAMETER PreRollMs / TailMs
    Static lead-in before the first action and lead-out after the last action.

.PARAMETER AdbPath / FfmpegPath / PythonPath
    Optional explicit tool paths. PATH, ANDROID_HOME/ANDROID_SDK_ROOT and the locally used
    toolchain path are tried automatically.

.PARAMETER PackageName
    Application id to launch and inspect. Default com.muyuchat.mca.

.PARAMETER NoLaunch
    Do not launch the app; only verify that the chat surface is already foreground.

.EXIT CODES
    0 = PASS (motion detected and all action observations succeeded)
    1 = FAIL (no sustained motion, action did not open, send produced no generation, analysis
              failed, or animation-scale restoration failed)
    3 = NOT_RUN (animator scale 0 without -EnableAnimationScale) or BLOCKED (missing tool,
              device, app/stage, or send fixture)
    2 = usage/output-directory error
#>
[CmdletBinding(PositionalBinding = $false)]
param(
    [string] $DeviceSerial = '',
    [ValidateNotNullOrEmpty()]
    [string] $Stage = 'chat',
    [string] $OutDir = '',
    [AllowEmptyCollection()]
    [string[]] $Actions = @('drawer'),
    [switch] $EnableAnimationScale,
    [ValidateRange(0.0, 255.0)]
    [double] $MotionThreshold = 1.0,
    [ValidateRange(1, 1000)]
    [int] $MinMotionFrames = 2,
    [ValidateRange(1, 30)]
    [int] $Fps = 10,
    [ValidateRange(0, 10000)]
    [int] $PreRollMs = 900,
    [ValidateRange(0, 10000)]
    [int] $TailMs = 900,
    [string] $AdbPath = '',
    [string] $FfmpegPath = '',
    [string] $PythonPath = '',
    [string] $PackageName = 'com.muyuchat.mca',
    [switch] $NoLaunch
)

$ErrorActionPreference = 'Continue'
$repo = Split-Path -Parent $PSScriptRoot
$scaleKeys = @('window_animation_scale', 'transition_animation_scale', 'animator_duration_scale')
$allowedActions = @('drawer', 'menu', 'model', 'newchat', 'send')

# Accept both `-Actions menu,model` (one string when the script is launched with
# `powershell -File`) and a native PowerShell string array, then validate explicitly so an
# unknown action fails as a usage error instead of being confused with another parameter.
$requestedActions = New-Object System.Collections.Generic.List[string]
foreach ($rawAction in @($Actions)) {
    foreach ($part in ([string]$rawAction -split ',')) {
        $trimmed = $part.Trim()
        if (-not $trimmed) { continue }
        if ($allowedActions -notcontains $trimmed) {
            [Console]::Error.WriteLine("verify-ui-motion: unknown action '$trimmed'; expected one of: " + ($allowedActions -join ', '))
            exit 2
        }
        if (-not $requestedActions.Contains($trimmed)) { $requestedActions.Add($trimmed) | Out-Null }
    }
}

$actionWindowingMs = @{
    drawer  = 2300
    menu    = 2300
    model   = 2300
    newchat = 1200
    send    = 2600
}

# ---------------------------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------------------------
function Resolve-ToolPath {
    param([string] $Explicit, [string] $Name, [string[]] $Candidates)
    if ($Explicit) {
        if (Test-Path -LiteralPath $Explicit) { return (Resolve-Path -LiteralPath $Explicit).Path }
        return $null
    }
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command -and $command.Source) { return $command.Source }
    foreach ($candidate in @($Candidates)) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    return $null
}

function Resolve-OutputDirectory {
    param([string] $Requested, [string] $RepoRoot, [string] $StageName)
    $runId = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $PID
    $slug = ($StageName -replace '[^A-Za-z0-9_.-]', '-')
    if ($slug.Length -gt 60) { $slug = $slug.Substring(0, 60) }
    $target = $Requested
    if (-not $target) {
        $target = Join-Path $RepoRoot (Join-Path 'artifacts\ui-motion' ($slug + '-' + $runId))
    } elseif (-not [System.IO.Path]::IsPathRooted($target)) {
        $target = Join-Path $RepoRoot $target
    }
    if (Test-Path -LiteralPath $target) {
        $existing = @(Get-ChildItem -LiteralPath $target -Force -ErrorAction SilentlyContinue)
        if ($existing.Count -gt 0) {
            $target = Join-Path $target $runId
        }
    }
    New-Item -ItemType Directory -Force -Path $target | Out-Null
    return (Resolve-Path -LiteralPath $target).Path
}

function Invoke-AdbText {
    param([string] $Adb, [string[]] $Arguments)
    $output = & $Adb @Arguments 2>&1
    return (@($output) | ForEach-Object { [string]$_ }) -join "`n"
}

function Get-UiDumpRaw {
    param([string] $Adb, [string] $Serial, [string] $RemoteDumpPath)
    & $Adb -s $Serial shell uiautomator dump $RemoteDumpPath 2>&1 | Out-Null
    $raw = & $Adb -s $Serial exec-out cat $RemoteDumpPath 2>$null
    return (@($raw) | ForEach-Object { [string]$_ }) -join ''
}

function Find-BoundsCenter {
    param([string] $Raw, [string] $Kind, [string] $Value)
    if (-not $Raw) { return $null }
    $escaped = [regex]::Escape($Value)
    $patternA = '<node[^>]*' + $Kind + '="' + $escaped + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    $patternB = '<node[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*' + $Kind + '="' + $escaped + '"'
    foreach ($pattern in @($patternA, $patternB)) {
        $match = [regex]::Match($Raw, $pattern)
        if ($match.Success) {
            $x1 = [int]$match.Groups[1].Value
            $y1 = [int]$match.Groups[2].Value
            $x2 = [int]$match.Groups[3].Value
            $y2 = [int]$match.Groups[4].Value
            return [pscustomobject]@{ x = [int](($x1 + $x2) / 2); y = [int](($y1 + $y2) / 2); bounds = $match.Value }
        }
    }
    return $null
}

function Test-UiMarker {
    param([string] $Raw, [string] $Kind, [string] $Value)
    if (-not $Raw) { return $false }
    return $Raw.Contains($Kind + '="' + $Value + '"')
}

function Get-AnimationScales {
    param([string] $Adb, [string] $Serial)
    $values = [ordered]@{}
    foreach ($key in $scaleKeys) {
        $value = ((& $Adb -s $Serial shell settings get global $key 2>$null) -join '').Trim()
        if (-not $value) { $value = 'null' }
        $values[$key] = $value
    }
    return $values
}

function Test-AnimatorDisabled {
    param($Scales)
    if (-not $Scales) { return $false }
    $value = [string]$Scales['animator_duration_scale']
    return ($value -match '^0(\.0*)?$')
}

function Set-AnimationScalesToOne {
    param([string] $Adb, [string] $Serial)
    foreach ($key in $scaleKeys) {
        & $Adb -s $Serial shell settings put global $key 1 2>&1 | Out-Null
    }
    $after = Get-AnimationScales -Adb $Adb -Serial $Serial
    foreach ($key in $scaleKeys) {
        if ([string]$after[$key] -notmatch '^1(\.0*)?$') {
            throw "Failed to set animation scale '$key' to 1 (read back '$($after[$key])')."
        }
    }
    return $after
}

function Restore-AnimationScales {
    param([string] $Adb, [string] $Serial, $OriginalScales)
    $ok = $true
    $after = [ordered]@{}
    $errors = New-Object System.Collections.Generic.List[string]
    $expectedByKey = @{}
    foreach ($key in $scaleKeys) {
        $expectedSource = [string]$OriginalScales[$key]
        $expectedByKey[$key] = if ([string]::IsNullOrWhiteSpace($expectedSource) -or $expectedSource -eq 'null') { 'null' } else { $expectedSource }
    }
    foreach ($key in $scaleKeys) {
        # NOTE: PowerShell variable names are case-insensitive. Do not name this local
        # `$original` next to a parameter such as `$Original`: the assignment would overwrite
        # the parameter dictionary and restore only the first key (observed before this fix).
        $originalValue = [string]$OriginalScales[$key]
        try {
            if ([string]::IsNullOrWhiteSpace($originalValue) -or $originalValue -eq 'null') {
                & $Adb -s $Serial shell settings delete global $key 2>&1 | Out-Null
            } else {
                & $Adb -s $Serial shell settings put global $key $originalValue 2>&1 | Out-Null
            }
            $readBack = ((& $Adb -s $Serial shell settings get global $key 2>$null) -join '').Trim()
            if (-not $readBack) { $readBack = 'null' }
            $after[$key] = $readBack
            if ($readBack -ne $expectedByKey[$key]) {
                $ok = $false
                $errors.Add("$key expected '$($expectedByKey[$key])' but is '$readBack'") | Out-Null
            }
        } catch {
            $ok = $false
            $after[$key] = '<error>'
            $errors.Add("$key restore error: " + $_.Exception.Message) | Out-Null
        }
    }
    # Re-read after a short settle window so a provider that applies writes asynchronously cannot
    # report a false success. The final after{} map is the settled reading.
    Start-Sleep -Milliseconds 300
    foreach ($key in $scaleKeys) {
        $settled = ((& $Adb -s $Serial shell settings get global $key 2>$null) -join '').Trim()
        if (-not $settled) { $settled = 'null' }
        $after[$key] = $settled
        if ($settled -ne $expectedByKey[$key]) {
            $ok = $false
            $errors.Add("$key post-settle expected '$($expectedByKey[$key])' but is '$settled'") | Out-Null
        }
    }
    return [pscustomobject]@{ ok = $ok; after = $after; errors = @($errors.ToArray()) }
}

function Get-DeviceInfo {
    param([string] $Adb, [string] $Serial)
    $model = ((& $Adb -s $Serial shell getprop ro.product.model 2>$null) -join '').Trim()
    $brand = ((& $Adb -s $Serial shell getprop ro.product.brand 2>$null) -join '').Trim()
    $api = ((& $Adb -s $Serial shell getprop ro.build.version.sdk 2>$null) -join '').Trim()
    $size = ((& $Adb -s $Serial shell wm size 2>$null) -join '').Trim()
    $density = ((& $Adb -s $Serial shell wm density 2>$null) -join '').Trim()
    return [ordered]@{ serial = $Serial; brand = $brand; model = $model; apiLevel = $api; size = $size; density = $density }
}

function Get-AppVersion {
    param([string] $Adb, [string] $Serial, [string] $Package)
    $text = & $Adb -s $Serial shell dumpsys package $Package 2>$null
    $joined = (@($text) | ForEach-Object { [string]$_ }) -join "`n"
    $match = [regex]::Match($joined, 'versionName=([^\s]+)')
    if ($match.Success) { return $match.Groups[1].Value }
    return ''
}

function Wait-ForChatSurface {
    param([string] $Adb, [string] $Serial, [int] $TimeoutMs = 20000)
    $deadline = (Get-Date).AddMilliseconds($TimeoutMs)
    while ((Get-Date) -lt $deadline) {
        $raw = Get-UiDumpRaw -Adb $Adb -Serial $Serial -RemoteDumpPath '/sdcard/mca-motion-ui.xml'
        if (Test-UiMarker -Raw $raw -Kind 'desc' -Value '打开历史') { return $raw }
        Start-Sleep -Milliseconds 600
    }
    return $null
}

function Stop-MotionRun {
    param([string] $Status, [string] $Reason)
    $script:abortStatus = $Status
    $script:abortReason = $Reason
    throw $Reason
}

# ---------------------------------------------------------------------------------------------
# run state
# ---------------------------------------------------------------------------------------------
$script:abortStatus = $null
$script:abortReason = $null
$script:restoreNeeded = $false
$script:originalScales = $null

try {
    $outDirectory = Resolve-OutputDirectory -Requested $OutDir -RepoRoot $repo -StageName $Stage
} catch {
    [Console]::Error.WriteLine('verify-ui-motion: cannot create output directory: ' + $_.Exception.Message)
    exit 2
}
$runId = Split-Path -Leaf $outDirectory
$recordingPath = Join-Path $outDirectory 'recording.mp4'
$framesDir = Join-Path $outDirectory 'frames'
$analysisPath = Join-Path $outDirectory 'frame-analysis.json'
$jsonPath = Join-Path $outDirectory 'motion.json'
$markdownPath = Join-Path $outDirectory 'RUN.md'
$screenrecordLogPath = Join-Path $outDirectory 'screenrecord.log'
$ffmpegLogPath = Join-Path $outDirectory 'ffmpeg.log'
$analysisLogPath = Join-Path $outDirectory 'analysis.log'
$remoteRecording = '/sdcard/mca-ui-motion-' + $runId + '.mp4'

$state = [ordered]@{
    schema              = 'mca.ui-motion/1'
    runId               = $runId
    stage               = $Stage
    generatedAt         = (Get-Date).ToString('o')
    repo                = $repo
    head                = ''
    worktreeDirtyEntries = $null
    verdict             = 'FAIL'
    reason              = 'not started'
    exitCode            = 1
    device              = $null
    app                 = [ordered]@{ packageName = $PackageName; versionName = '' }
    tools               = [ordered]@{ adb = ''; ffmpeg = ''; python = '' }
    animationScales     = [ordered]@{
        before          = $null
        effective       = $null
        modifiedByScript = $false
        restore         = $null
        restoreOk       = $null
    }
    actions             = @($requestedActions.ToArray())
    actionObservations  = @()
    sendGenerationObserved = $null
    recording           = $null
    frames              = $null
    motion              = $null
    limits              = @(
        'PASS means adjacent-frame mean difference stayed above the threshold for enough frames.',
        'It does not prove animation curve, duration, visual quality or device-wide smoothness.',
        'The measurement covers only the recorded window; unrelated system UI can also move.',
        'Animation-scale changes are temporary and restored/verified by this script.'
    )
}

try {
    # --- toolchain ---------------------------------------------------------------------------
    $adb = Resolve-ToolPath -Explicit $AdbPath -Name 'adb' -Candidates @(
        $(if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe' }),
        $(if ($env:ANDROID_SDK_ROOT) { Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe' }),
        (Join-Path $env:USERPROFILE 'AppData\Local\Android\Sdk\platform-tools\adb.exe'),
        # Machine-local Android SDK path already used by this repo's verification tooling.
        'E:\model\toolchains\android-sdk\platform-tools\adb.exe'
    )
    $ffmpeg = Resolve-ToolPath -Explicit $FfmpegPath -Name 'ffmpeg' -Candidates @(
        (Join-Path $env:USERPROFILE 'AppData\Local\Microsoft\WinGet\Packages\Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe\ffmpeg-8.1.1-full_build\bin\ffmpeg.exe')
    )
    $python = Resolve-ToolPath -Explicit $PythonPath -Name 'python' -Candidates @()
    $missingTools = @()
    if (-not $adb) { $missingTools += 'adb' }
    if (-not $ffmpeg) { $missingTools += 'ffmpeg' }
    if (-not $python) { $missingTools += 'python' }
    if ($missingTools.Count -gt 0) {
        Stop-MotionRun -Status 'BLOCKED' -Reason ('missing required tool(s): ' + ($missingTools -join ', '))
    }
    $state.tools.adb = $adb
    $state.tools.ffmpeg = $ffmpeg
    $state.tools.python = $python

    $pilCheck = & $python -c "import PIL; print('ok')" 2>&1
    if (($pilCheck -join '') -notmatch 'ok') {
        Stop-MotionRun -Status 'BLOCKED' -Reason 'Python Pillow (PIL) is required for frame differences but could not be imported'
    }

    $head = (& git -C $repo rev-parse HEAD 2>$null | Select-Object -First 1)
    $state.head = ([string]$head).Trim()
    $statusLines = @(& git -C $repo status --porcelain 2>$null)
    $state.worktreeDirtyEntries = @($statusLines | Where-Object { "$_".Trim().Length -gt 0 }).Count

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
            Stop-MotionRun -Status 'BLOCKED' -Reason ("no -DeviceSerial supplied and adb reports $($online.Count) online device(s); state the device explicitly")
        }
    }
    if (-not ($online -contains $DeviceSerial)) {
        Stop-MotionRun -Status 'BLOCKED' -Reason ("device '$DeviceSerial' is not online (adb devices shows: " + ($online -join ', ') + ')')
    }
    $state.device = Get-DeviceInfo -Adb $adb -Serial $DeviceSerial
    $state.app.versionName = Get-AppVersion -Adb $adb -Serial $DeviceSerial -Package $PackageName

    # --- animation scales (NOT_RUN is decided before touching the UI) -------------------------
    $beforeScales = Get-AnimationScales -Adb $adb -Serial $DeviceSerial
    $script:originalScales = $beforeScales
    $state.animationScales.before = $beforeScales
    if ((Test-AnimatorDisabled -Scales $beforeScales) -and -not $EnableAnimationScale) {
        Stop-MotionRun -Status 'NOT_RUN' -Reason (
            'animator_duration_scale is 0, so Compose animations are intentionally frozen; ' +
            'pass -EnableAnimationScale to temporarily use 1 (the original value is restored). ' +
            'No recording was produced.'
        )
    }

    # --- app foreground / stage --------------------------------------------------------------
    if (-not $NoLaunch) {
        & $adb -s $DeviceSerial shell input keyevent KEYCODE_WAKEUP 2>&1 | Out-Null
        & $adb -s $DeviceSerial shell wm dismiss-keyguard 2>&1 | Out-Null
        $monkey = & $adb -s $DeviceSerial shell monkey -p $PackageName -c android.intent.category.LAUNCHER 1 2>&1
        if (($monkey -join '') -notmatch 'Events injected') {
            & $adb -s $DeviceSerial shell am start -n ($PackageName + '/com.muyuchat.mca.MainActivity') 2>&1 | Out-Null
        }
    }
    $surface = Wait-ForChatSurface -Adb $adb -Serial $DeviceSerial -TimeoutMs 60000
    if (-not $surface) {
        Stop-MotionRun -Status 'BLOCKED' -Reason (
            "chat surface is not foreground for package $PackageName; open the MCA chat screen " +
            'or omit -NoLaunch so the script can launch it'
        )
    }

    # The first launch after an update can take well over 20 s (data migration + first full
    # composition). Require the chat chrome to be stable across two dumps before reading
    # coordinates, otherwise the taps land on the launch surface and the run fails for the
    # wrong reason.
    for ($stabilize = 0; $stabilize -lt 30; $stabilize++) {
        $probe = Get-UiDumpRaw -Adb $adb -Serial $DeviceSerial -RemoteDumpPath '/sdcard/mca-motion-ui.xml'
        if (Test-UiMarker -Raw $probe -Kind 'desc' -Value '打开历史') {
            Start-Sleep -Milliseconds 500
            $probe2 = Get-UiDumpRaw -Adb $adb -Serial $DeviceSerial -RemoteDumpPath '/sdcard/mca-motion-ui.xml'
            if (Test-UiMarker -Raw $probe2 -Kind 'desc' -Value '打开历史') { break }
        }
        Start-Sleep -Milliseconds 600
    }

    # --- plan actions (resolve selectors before recording) ------------------------------------
    $orderedActions = New-Object System.Collections.Generic.List[string]
    foreach ($action in $requestedActions) {
        if (-not $orderedActions.Contains($action)) { $orderedActions.Add($action) | Out-Null }
    }
    $sendIndex = $orderedActions.IndexOf('send')
    if ($sendIndex -ge 0 -and $sendIndex -ne ($orderedActions.Count - 1)) {
        $orderedActions.RemoveAt($sendIndex)
        $orderedActions.Add('send') | Out-Null
    }
    $state.actions = @($orderedActions.ToArray())

    $dump = Get-UiDumpRaw -Adb $adb -Serial $DeviceSerial -RemoteDumpPath '/sdcard/mca-motion-ui.xml'
    $actionPlan = New-Object System.Collections.Generic.List[object]
    $planErrors = New-Object System.Collections.Generic.List[string]
    foreach ($action in $orderedActions) {
        $observedKind = $null
        switch ($action) {
            'drawer' { $selector = @{ kind = 'desc'; value = '打开历史' }; $observedBy = '搜索历史'; $observedKind = 'desc' }
            'menu'   { $selector = @{ kind = 'desc'; value = '打开应用菜单' }; $observedBy = 'MuYu Chat Agent'; $observedKind = 'text' }
            'model'  { $selector = @{ kind = 'desc'; value = '选择模型' }; $observedBy = '推理来源'; $observedKind = 'text' }
            'newchat' { $selector = @{ kind = 'desc'; value = '新建聊天' }; $observedBy = $null; $observedKind = $null }
            'send'   { $selector = $null; $observedBy = $null; $observedKind = $null }
        }
        if ($action -eq 'send') {
            # Check the fixture BEFORE typing so a missing/loading model cannot leave probe text
            # in the composer or open the keyboard. BLOCKED is honest; a fake motion sample is not.
            $notReadyMarkers = @('待加载', 'text="选择模型"', 'text="加载中"', '重新加载中', '恢复参数中', '停止中', '需要重载', '加载失败')
            $notReady = $false
            foreach ($marker in $notReadyMarkers) {
                if ($dump -and $dump.Contains($marker)) { $notReady = $true; break }
            }
            if ($notReady) {
                Stop-MotionRun -Status 'BLOCKED' -Reason (
                    'send action requires a ready chat backend; the header shows an unloaded/loading/error ' +
                    'model state. Load a local or cloud chat model first.'
                )
            }
            $editor = Find-BoundsCenter -Raw $dump -Kind 'class' -Value 'android.widget.EditText'
            if (-not $editor) { $planErrors.Add('send: composer EditText not found') | Out-Null; continue }
            & $adb -s $DeviceSerial shell input tap $editor.x $editor.y 2>&1 | Out-Null
            Start-Sleep -Milliseconds 400
            & $adb -s $DeviceSerial shell input text 'mca_motion_probe' 2>&1 | Out-Null
            Start-Sleep -Milliseconds 700
            $dump = Get-UiDumpRaw -Adb $adb -Serial $DeviceSerial -RemoteDumpPath '/sdcard/mca-motion-ui.xml'
            $sendNode = Find-BoundsCenter -Raw $dump -Kind 'desc' -Value '发送'
            if (-not $sendNode) { $planErrors.Add('send: 发送 button not found after typing') | Out-Null; continue }
            $actionPlan.Add([pscustomobject]@{ action = 'send'; x = $sendNode.x; y = $sendNode.y; observedBy = $null; observedKind = $null; durationMs = $actionWindowingMs['send'] }) | Out-Null
        } else {
            $node = Find-BoundsCenter -Raw $dump -Kind $selector.kind -Value $selector.value
            if (-not $node) { $planErrors.Add($action + ': selector not found (' + $selector.value + ')') | Out-Null; continue }
            $actionPlan.Add([pscustomobject]@{ action = $action; x = $node.x; y = $node.y; observedBy = $observedBy; observedKind = $observedKind; durationMs = $actionWindowingMs[$action] }) | Out-Null
        }
    }
    if ($planErrors.Count -gt 0) {
        Stop-MotionRun -Status 'FAIL' -Reason ('action preparation failed: ' + ($planErrors -join '; '))
    }

    # --- enable animation scales if requested (restore happens in finally) ---------------------
    if ($EnableAnimationScale) {
        $script:restoreNeeded = $true
        $effectiveScales = Set-AnimationScalesToOne -Adb $adb -Serial $DeviceSerial
        $state.animationScales.effective = $effectiveScales
        $state.animationScales.modifiedByScript = $true
    } else {
        $state.animationScales.effective = $beforeScales
        $state.animationScales.modifiedByScript = $false
    }

    # --- record ------------------------------------------------------------------------------
    $plannedMs = $PreRollMs + $TailMs
    foreach ($item in $actionPlan) { $plannedMs += $item.durationMs }
    $timeLimitSeconds = [int]([Math]::Min(30, [Math]::Max(3, [Math]::Ceiling($plannedMs / 1000.0) + 2)))
    & $adb -s $DeviceSerial shell rm -f $remoteRecording 2>&1 | Out-Null

    $recordJob = Start-Job -ScriptBlock {
        param($AdbPath, $Serial, $Remote, $Seconds)
        & $AdbPath -s $Serial shell screenrecord --time-limit $Seconds --bit-rate 8000000 $Remote 2>&1
    } -ArgumentList $adb, $DeviceSerial, $remoteRecording, $timeLimitSeconds

    $fileReady = $false
    for ($i = 0; $i -lt 30; $i++) {
        $listing = & $adb -s $DeviceSerial shell ls -l $remoteRecording 2>$null
        if ((($listing -join '') -match [regex]::Escape($remoteRecording))) { $fileReady = $true; break }
        if ($recordJob.State -ne 'Running') { break }
        Start-Sleep -Milliseconds 200
    }
    if (-not $fileReady) {
        $jobText = @(Receive-Job $recordJob -ErrorAction SilentlyContinue | ForEach-Object { [string]$_ }) -join "`n"
        Stop-Job $recordJob -ErrorAction SilentlyContinue
        Remove-Job $recordJob -Force -ErrorAction SilentlyContinue
        Set-Content -LiteralPath $screenrecordLogPath -Value $jobText -Encoding UTF8
        Stop-MotionRun -Status 'FAIL' -Reason ('screenrecord did not start: ' + ($jobText.Trim()) )
    }

    Start-Sleep -Milliseconds $PreRollMs
    $observations = New-Object System.Collections.Generic.List[object]
    foreach ($item in $actionPlan) {
        & $adb -s $DeviceSerial shell input tap $item.x $item.y 2>&1 | Out-Null
        if ($item.action -eq 'send') {
            Start-Sleep -Milliseconds 2200
            $observations.Add([pscustomobject]@{ action = 'send'; observed = $null; marker = $null; note = 'generation checked after recording' }) | Out-Null
        } elseif ($item.action -eq 'newchat') {
            Start-Sleep -Milliseconds 1000
            $observations.Add([pscustomobject]@{ action = 'newchat'; observed = $null; marker = $null; note = 'no overlay marker; action is visual evidence only' }) | Out-Null
        } else {
            Start-Sleep -Milliseconds 350
            $actionDump = Get-UiDumpRaw -Adb $adb -Serial $DeviceSerial -RemoteDumpPath '/sdcard/mca-motion-ui.xml'
            $observed = Test-UiMarker -Raw $actionDump -Kind $item.observedKind -Value $item.observedBy
            if (-not $observed) {
                Start-Sleep -Milliseconds 250
                $actionDump = Get-UiDumpRaw -Adb $adb -Serial $DeviceSerial -RemoteDumpPath '/sdcard/mca-motion-ui.xml'
                $observed = Test-UiMarker -Raw $actionDump -Kind $item.observedKind -Value $item.observedBy
            }
            $markerText = $item.observedKind + '=' + $item.observedBy
            $observations.Add([pscustomobject]@{ action = $item.action; observed = $observed; marker = $markerText; note = '' }) | Out-Null
            Start-Sleep -Milliseconds 150
            & $adb -s $DeviceSerial shell input keyevent 4 2>&1 | Out-Null
            Start-Sleep -Milliseconds 750
        }
    }
    Start-Sleep -Milliseconds $TailMs

    Wait-Job $recordJob -Timeout ($timeLimitSeconds + 15) | Out-Null
    $recordTimedOut = ($recordJob.State -ne 'Completed')
    if ($recordTimedOut) {
        Stop-Job $recordJob -ErrorAction SilentlyContinue
    }
    $jobText = @(Receive-Job $recordJob -ErrorAction SilentlyContinue | ForEach-Object { [string]$_ }) -join "`n"
    Remove-Job $recordJob -Force -ErrorAction SilentlyContinue
    Set-Content -LiteralPath $screenrecordLogPath -Value $jobText -Encoding UTF8

    & $adb -s $DeviceSerial pull $remoteRecording $recordingPath 2>&1 | Out-Null
    & $adb -s $DeviceSerial shell rm -f $remoteRecording 2>&1 | Out-Null
    $recordingFile = Get-Item -LiteralPath $recordingPath -ErrorAction SilentlyContinue
    if (-not $recordingFile -or $recordingFile.Length -le 0) {
        Stop-MotionRun -Status 'FAIL' -Reason ('screenrecord produced no pullable file; log: ' + $screenrecordLogPath)
    }
    $sha = (Get-FileHash -LiteralPath $recordingPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $state.recording = [ordered]@{
        path               = $recordingPath
        remotePath         = $remoteRecording
        bytes              = $recordingFile.Length
        sha256             = $sha
        timeLimitSeconds   = $timeLimitSeconds
        screenrecordTimedOut = $recordTimedOut
        log                = $screenrecordLogPath
    }

    # --- extract frames ----------------------------------------------------------------------
    if (Test-Path -LiteralPath $framesDir) { Remove-Item -LiteralPath $framesDir -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $framesDir | Out-Null
    $ffmpegArgs = @('-y', '-hide_banner', '-loglevel', 'error', '-i', $recordingPath,
        '-vf', ('fps=' + $Fps), '-q:v', '2', (Join-Path $framesDir 'frame-%04d.png'))
    & $ffmpeg @ffmpegArgs *> $ffmpegLogPath
    $ffmpegExit = $LASTEXITCODE
    $frameFiles = @(Get-ChildItem -LiteralPath $framesDir -Filter 'frame-*.png' -File -ErrorAction SilentlyContinue)
    if ($ffmpegExit -ne 0 -or $frameFiles.Count -lt 2) {
        Stop-MotionRun -Status 'FAIL' -Reason ('ffmpeg frame extraction produced ' + $frameFiles.Count + ' frame(s), exit=' + $ffmpegExit + '; log: ' + $ffmpegLogPath)
    }

    # --- frame difference analysis (PIL) ------------------------------------------------------
    $analyzerSource = @'
import glob, json, os, sys
from PIL import Image, ImageChops, ImageStat

frames = sorted(glob.glob(os.path.join(sys.argv[1], "frame-*.png")))
out_path = sys.argv[2]
threshold = float(sys.argv[3])
min_frames = int(sys.argv[4])
fps = float(sys.argv[5])

prev = None
timeline = []
frame_size = None
for index, path in enumerate(frames):
    image = Image.open(path).convert("RGB")
    if frame_size is None:
        frame_size = [image.width, image.height]
    if prev is not None:
        diff = ImageChops.difference(prev, image)
        channels = ImageStat.Stat(diff).mean
        mean_diff = sum(channels) / float(len(channels))
        gray = diff.convert("L")
        histogram = gray.histogram()
        total_pixels = float(sum(histogram))
        changed_ratio = (sum(histogram[9:]) / total_pixels) if total_pixels > 0 else 0.0
        time_ms = int(round(index * 1000.0 / fps))
        timeline.append({
            "frame": index,
            "timeMs": time_ms,
            "meanDiff": round(mean_diff, 6),
            "changedRatio": round(changed_ratio, 6),
            "motion": mean_diff >= threshold,
        })
    prev = image

diffs = [item["meanDiff"] for item in timeline]
motion_items = [item for item in timeline if item["motion"]]
ranges = []
for item in timeline:
    if not item["motion"]:
        continue
    if not ranges:
        ranges.append({"startMs": item["timeMs"], "endMs": item["timeMs"], "frames": 1})
    else:
        current = ranges[-1]
        if item["timeMs"] - current["endMs"] <= int(1000.0 / fps) + 1:
            current["endMs"] = item["timeMs"]
            current["frames"] += 1
        else:
            ranges.append({"startMs": item["timeMs"], "endMs": item["timeMs"], "frames": 1})

if diffs:
    ordered = sorted(diffs)
    p95 = ordered[min(len(ordered) - 1, max(0, int(len(ordered) * 0.95) - 1))]
else:
    p95 = 0.0

result = {
    "fps": fps,
    "threshold": threshold,
    "minMotionFrames": min_frames,
    "frameCount": len(frames),
    "frameSize": frame_size,
    "motionFrameCount": len(motion_items),
    "maxFrameDiff": round(max(diffs), 6) if diffs else 0.0,
    "p95FrameDiff": round(p95, 6),
    "firstMotionMs": motion_items[0]["timeMs"] if motion_items else None,
    "lastMotionMs": motion_items[-1]["timeMs"] if motion_items else None,
    "motionRanges": ranges,
    "timeline": timeline,
}

with open(out_path, "w", encoding="utf-8") as handle:
    json.dump(result, handle, ensure_ascii=False, indent=2)

print("frames=%d motion=%d max=%.3f threshold=%.3f" % (
    result["frameCount"], result["motionFrameCount"], result["maxFrameDiff"], threshold))
'@
    $analyzerPath = Join-Path $outDirectory 'analyze-motion.py'
    [System.IO.File]::WriteAllText($analyzerPath, $analyzerSource, (New-Object System.Text.UTF8Encoding($false)))
    & $python $analyzerPath $framesDir $analysisPath $MotionThreshold $MinMotionFrames $Fps *> $analysisLogPath
    $analysisExit = $LASTEXITCODE
    if ($analysisExit -ne 0 -or -not (Test-Path -LiteralPath $analysisPath)) {
        Stop-MotionRun -Status 'FAIL' -Reason ('frame analysis failed, exit=' + $analysisExit + '; log: ' + $analysisLogPath)
    }
    $analysis = Get-Content -LiteralPath $analysisPath -Raw | ConvertFrom-Json
    $state.frames = [ordered]@{
        fps       = $Fps
        count     = $analysis.frameCount
        width     = $analysis.frameSize[0]
        height    = $analysis.frameSize[1]
        directory = $framesDir
    }
    $state.motion = [ordered]@{
        threshold       = $analysis.threshold
        minMotionFrames = $analysis.minMotionFrames
        motionFrameCount = $analysis.motionFrameCount
        maxFrameDiff    = $analysis.maxFrameDiff
        p95FrameDiff    = $analysis.p95FrameDiff
        firstMotionMs   = $analysis.firstMotionMs
        lastMotionMs    = $analysis.lastMotionMs
        motionRanges    = $analysis.motionRanges
        timeline        = $analysis.timeline
    }

    # --- send fixture observation -------------------------------------------------------------
    $sendRequested = $orderedActions.Contains('send')
    if ($sendRequested) {
        $postDump = Get-UiDumpRaw -Adb $adb -Serial $DeviceSerial -RemoteDumpPath '/sdcard/mca-motion-ui.xml'
        $state.sendGenerationObserved = (Test-UiMarker -Raw $postDump -Kind 'desc' -Value '停止') -or
            (Test-UiMarker -Raw $postDump -Kind 'desc' -Value '复制消息')
    }

    $state.actionObservations = @($observations.ToArray())
    $actionFailures = @($observations | Where-Object { $_.observed -eq $false })
    $motionOk = ([int]$analysis.motionFrameCount -ge $MinMotionFrames)
    if ($recordTimedOut) {
        $state.verdict = 'FAIL'
        $state.reason = ('screenrecord did not finish within the time limit (' + $timeLimitSeconds + 's); the recording window is incomplete')
    } elseif ($actionFailures.Count -gt 0) {
        $state.verdict = 'FAIL'
        $state.reason = 'action did not open its expected overlay: ' + (($actionFailures | ForEach-Object { $_.action + ' (' + $_.marker + ')' }) -join ', ')
    } elseif ($sendRequested -and -not $state.sendGenerationObserved) {
        $state.verdict = 'FAIL'
        $state.reason = 'send action did not produce a visible generation (no 停止/复制消息); a ready chat model is required for the send transition'
    } elseif (-not $motionOk) {
        $state.verdict = 'FAIL'
        $state.reason = ('no sustained motion: ' + $analysis.motionFrameCount + ' frame(s) >= threshold ' + $MotionThreshold + ', required ' + $MinMotionFrames + '; maxFrameDiff=' + $analysis.maxFrameDiff)
    } else {
        $state.verdict = 'PASS'
        $state.reason = ('motion detected in ' + $analysis.motionFrameCount + ' frame(s) (maxFrameDiff=' + $analysis.maxFrameDiff + ', threshold=' + $MotionThreshold + ')')
    }
} catch {
    if ($script:abortStatus) {
        $state.verdict = $script:abortStatus
        $state.reason = $script:abortReason
    } else {
        $state.verdict = 'FAIL'
        $state.reason = 'unexpected error: ' + $_.Exception.Message
    }
} finally {
    if ($script:restoreNeeded) {
        try {
            $restore = Restore-AnimationScales -Adb $adb -Serial $DeviceSerial -OriginalScales $script:originalScales
            $state.animationScales.restore = $restore
            $state.animationScales.restoreOk = [bool]$restore.ok
            if (-not $restore.ok) {
                $state.verdict = 'FAIL'
                $state.reason = ($state.reason + ' | animation scale restore FAILED: ' + ($restore.errors -join '; '))
            }
        } catch {
            $state.animationScales.restoreOk = $false
            $state.animationScales.restore = [ordered]@{ ok = $false; errors = @($_.Exception.Message) }
            $state.verdict = 'FAIL'
            $state.reason = ($state.reason + ' | animation scale restore threw: ' + $_.Exception.Message)
        }
    } else {
        $state.animationScales.restoreOk = $true
    }
}

if ($state.verdict -eq 'PASS') {
    $state.exitCode = 0
} elseif ($state.verdict -eq 'FAIL') {
    $state.exitCode = 1
} else {
    $state.exitCode = 3
}
$state.generatedAt = (Get-Date).ToString('o')

$jsonText = $state | ConvertTo-Json -Depth 14
try {
    [System.IO.File]::WriteAllText($jsonPath, $jsonText, (New-Object System.Text.UTF8Encoding($false)))
} catch {
    [Console]::Error.WriteLine('verify-ui-motion: cannot write motion.json: ' + $_.Exception.Message)
}

$md = New-Object System.Collections.Generic.List[string]
$md.Add('# UI motion evidence ' + $runId) | Out-Null
$md.Add('') | Out-Null
$md.Add('- verdict: **' + $state.verdict + '** (exit ' + $state.exitCode + ')') | Out-Null
$md.Add('- reason: ' + $state.reason) | Out-Null
$md.Add('- stage: ' + $state.stage) | Out-Null
$md.Add('- actions: ' + (@($state.actions) -join ', ')) | Out-Null
$md.Add('- generated: ' + $state.generatedAt) | Out-Null
$md.Add('- HEAD: ' + $state.head + ' (dirty/untracked entries: ' + $state.worktreeDirtyEntries + ')') | Out-Null
if ($state.device) {
    $md.Add('- device: ' + $state.device.brand + ' ' + $state.device.model + ' / API ' + $state.device.apiLevel + ' / ' + $state.device.size + ' / ' + $state.device.density) | Out-Null
}
$md.Add('- app: ' + $state.app.packageName + ' versionName=' + $state.app.versionName) | Out-Null
$beforeScalesText = if ($state.animationScales.before) { ($scaleKeys | ForEach-Object { $_ + '=' + $state.animationScales.before[$_] }) -join ', ' } else { 'not read' }
$md.Add('- animation scales before: ' + $beforeScalesText) | Out-Null
$md.Add('- animation scales modified by script: ' + $state.animationScales.modifiedByScript) | Out-Null
$md.Add('- animation scales restore OK: ' + $state.animationScales.restoreOk) | Out-Null
if ($state.recording) {
    $md.Add('- recording: ' + $state.recording.path + ' (' + $state.recording.bytes + ' bytes, sha256 ' + $state.recording.sha256 + ')') | Out-Null
}
if ($state.frames) {
    $md.Add('- frames: ' + $state.frames.count + ' at ' + $state.frames.fps + ' fps (' + $state.frames.width + 'x' + $state.frames.height + ') in ' + $state.frames.directory) | Out-Null
}
if ($state.motion) {
    $md.Add('- motion: ' + $state.motion.motionFrameCount + ' frame(s) >= ' + $state.motion.threshold + '; max=' + $state.motion.maxFrameDiff + ', p95=' + $state.motion.p95FrameDiff) | Out-Null
    if ($state.motion.firstMotionMs -ne $null) {
        $md.Add('- motion window: ' + $state.motion.firstMotionMs + ' ms .. ' + $state.motion.lastMotionMs + ' ms') | Out-Null
    }
}
if (@($state.actionObservations).Count -gt 0) {
    $md.Add('') | Out-Null
    $md.Add('## Action observations') | Out-Null
    $md.Add('') | Out-Null
    $md.Add('| action | observed | marker | note |') | Out-Null
    $md.Add('|---|---|---|---|') | Out-Null
    foreach ($item in @($state.actionObservations)) {
        $observedText = if ($null -eq $item.observed) { 'n/a' } else { [string]$item.observed }
        $md.Add('| ' + $item.action + ' | ' + $observedText + ' | ' + ($item.marker) + ' | ' + ($item.note) + ' |') | Out-Null
    }
}
if ($state.sendGenerationObserved -ne $null) {
    $md.Add('- send generation observed: ' + $state.sendGenerationObserved) | Out-Null
}
$md.Add('') | Out-Null
$md.Add('## Limits') | Out-Null
$md.Add('') | Out-Null
foreach ($limit in $state.limits) { $md.Add('- ' + $limit) | Out-Null }
$md.Add('- NOT_RUN is used when the animator scale is 0 (Compose animations are frozen); this is not a pass.') | Out-Null
$md.Add('- FAIL is used when pixels did not move across enough frames, an action overlay never appeared, or the send action produced no generation.') | Out-Null
$md.Add('')
try {
    [System.IO.File]::WriteAllText($markdownPath, ($md -join "`r`n"), (New-Object System.Text.UTF8Encoding($false)))
} catch {
    [Console]::Error.WriteLine('verify-ui-motion: cannot write RUN.md: ' + $_.Exception.Message)
}

Write-Output ('VERDICT: ' + $state.verdict)
Write-Output ('REASON:  ' + $state.reason)
Write-Output ('EVIDENCE: ' + $outDirectory)
if ($state.motion) {
    Write-Output ('MOTION: motionFrames=' + $state.motion.motionFrameCount + ' max=' + $state.motion.maxFrameDiff + ' threshold=' + $state.motion.threshold)
}
Write-Output ('SCALES AFTER: ' + $(if ($adb -and $DeviceSerial) { (Get-AnimationScales -Adb $adb -Serial $DeviceSerial) | ConvertTo-Json -Compress } else { '(not read: no device/tool)' }))
exit $state.exitCode
