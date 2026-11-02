# MCA chat-backend coverage runner.
#
# PURPOSE
#   Objective requires "at least one model per distinct backend engine, exercised through the real
#   front-end". This script supplies the shared, hardened plumbing so each engine's run uses the same
#   evidence discipline, and so a verdict cannot be produced by a lucky screenshot.
#
# JUDGEMENT RULES BAKED IN (each one exists because an earlier run got it wrong)
#   1. IDENTITY FIRST      - Group 0 verifies the on-device base.apk against the frozen hash. Never
#                            test an unverified artifact.
#   2. FRESH DUMP BEFORE ACTING - the soft keyboard shifts the composer (measured y 2952 -> 1746), so
#                            every locate uses a dump taken moments before the tap. No remembered
#                            coordinates, ever.
#   3. FOREGROUND CHECK    - a third-party app stole focus 6-7 times in one round, silently swallowing
#                            taps. Each action re-checks that MCA is the resumed activity.
#   4. SEND VERIFICATION   - "tap sent" is not evidence. If the composer still holds the prompt, the
#                            tap missed; that yields INCONCLUSIVE, NEVER a STALL verdict.
#   5. INDEPENDENT REPLY SIGNAL - the verdict requires an ASSISTANT-side structural signal, not the
#                            presence of a string that also appears in the user's own message. We use
#                            the per-turn metrics line (上行 N tokens · 下行 M tokens · X tok/s · 用时 Y秒)
#                            which the app renders for a completed turn, plus a distinct answer node.
#   6. POSITIVE ACTION EVIDENCE - "nothing bad happened" is not success. The run must show that the
#                            worker actually did work (CPU ticks advanced) and/or the app's own
#                            worker-stage.json gained a record for THIS request.
#   7. NEW CONVERSATION FIRST - persisted history from a previous version can masquerade as a fresh
#                            reply, so a cross-version retest starts from a new conversation.
#
# SAFETY
#   * No Authorization header is ever used; no key/token is read or written.
#   * `ps -A -o ARGS` output is never persisted (it can carry a bearer token) - only PID + short name.
#   * Read-only observation otherwise: no install, no data clearing, no model/data deletion.
#
# USAGE
#   powershell -NoProfile -File qa-chat-engine-coverage.ps1 -Engine llama
#   powershell -NoProfile -File qa-chat-engine-coverage.ps1 -Engine litert -ObserveSeconds 120

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateSet('llama','litert')][string]$Engine,
    [string]$Device = 'device-a',
    [string]$Out = 'E:\MAC_QA_EVIDENCE\chat',
    [int]$ObserveSeconds = 90,
    [string]$Prompt = 'Reply with exactly: COVERAGE1',
    [string]$ExpectedToken = 'COVERAGE1',
    [string]$ExpectedApkSha256 = '37a5bf7b27c180907f39ffd8942dd2b403bb5ad5018c6ff9346843e11b3c5d89'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$adb = 'D:\model\android-sdk\platform-tools\adb.exe'
$engineDir = Join-Path $Out ("coverage-" + $Engine)
New-Item -ItemType Directory -Force -Path $engineDir | Out-Null
$logPath = Join-Path $engineDir 'coverage.log'
$script:lines = New-Object System.Collections.ArrayList
function Say([string]$m) {
    $ts = (Get-Date).ToUniversalTime().ToString('HH:mm:ssZ')
    $line = "[$ts] $m"
    Write-Host $line
    [void]$script:lines.Add($line)
}
function Finish([int]$code) {
    $script:lines | Set-Content -LiteralPath $logPath -Encoding UTF8
    Say "log written: $logPath"
    exit $code
}
function Invoke-Adb {
    param([string[]]$AdbArgs)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $o = @(& $adb @AdbArgs 2>&1); $c = $LASTEXITCODE } finally { $ErrorActionPreference = $prev }
    return [pscustomobject]@{ Output = $o; Code = $c }
}
function Get-UiDump {
    for ($i = 1; $i -le 4; $i++) {
        Invoke-Adb @('-s', $Device, 'shell', 'rm', '-f', '/sdcard/cov.xml') | Out-Null
        Invoke-Adb @('-s', $Device, 'shell', 'uiautomator', 'dump', '--compressed', '/sdcard/cov.xml') | Out-Null
        $local = Join-Path $engineDir 'cov-ui.xml'
        Invoke-Adb @('-s', $Device, 'pull', '/sdcard/cov.xml', $local) | Out-Null
        if ((Test-Path $local) -and ((Get-Item $local).Length -gt 3000)) { return $local }
        Start-Sleep -Milliseconds 900
    }
    return $null
}
function Get-Nodes {
    param([string]$XmlPath)
    if (-not $XmlPath -or -not (Test-Path $XmlPath)) { return @() }
    [xml]$doc = [System.IO.File]::ReadAllText($XmlPath, [System.Text.Encoding]::UTF8)
    $list = New-Object System.Collections.ArrayList
    function Walk($node) {
        foreach ($c in $node.childNodes) {
            if ($c.NodeType -ne 'Element') { continue }
            if ($c.Name -eq 'node') {
                $b = [string]$c.GetAttribute('bounds')
                if ($b -match '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') {
                    $x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]; $x2 = [int]$Matches[3]; $y2 = [int]$Matches[4]
                    [void]$list.Add([pscustomobject]@{
                        text = [string]$c.GetAttribute('text'); desc = [string]$c.GetAttribute('content-desc')
                        cls  = [string]$c.GetAttribute('class')
                        cx = [int](($x1 + $x2) / 2); cy = [int](($y1 + $y2) / 2)
                    })
                }
            }
            Walk $c
        }
    }
    Walk $doc.DocumentElement
    return $list
}
function Assert-Foreground {
    $r = Invoke-Adb @('-s', $Device, 'shell', 'dumpsys', 'activity', 'activities')
    $top = ($r.Output | Select-String 'topResumedActivity' | Select-Object -First 1)
    $line = if ($top) { $top.Line } else { '' }
    if ($line -notmatch 'com\.muyuchat\.mca') {
        Say "FOREGROUND_LOST: topResumedActivity is not MCA -> '$($line.Trim())'"
        return $false
    }
    return $true
}
function Get-WorkerTicks {
    $suffix = if ($Engine -eq 'litert') { ':litert_chat' } else { ':local_chat' }
    $r = Invoke-Adb @('-s', $Device, 'shell', "ps -A -o PID,ARGS | grep 'com.muyuchat.mca$suffix' | grep -v grep | tr -s ' ' | cut -d' ' -f1 | head -1")
    $pidStr = ($r.Output -join '').Trim()
    if ($pidStr -notmatch '^\d+$') { return [pscustomobject]@{ pid = $null; ticks = 0 } }
    $t = Invoke-Adb @('-s', $Device, 'shell', "awk '{print `$14+`$15}' /proc/$pidStr/stat 2>/dev/null")
    $ticks = 0; [void][int]::TryParse((($t.Output -join '').Trim() -replace '\D', ''), [ref]$ticks)
    return [pscustomobject]@{ pid = $pidStr; ticks = $ticks }
}
function Get-StageFileSignature {
    $r = Invoke-Adb @('-s', $Device, 'shell', "run-as com.muyuchat.mca cat files/local_chat_worker_diagnostics/worker-stage.json 2>/dev/null | md5sum")
    return (($r.Output -join '').Trim())
}

# ---------------------------------------------------------------- Group 0: identity
Say "=== Group 0: artifact identity (engine=$Engine) ==="
$p = (Invoke-Adb @('-s', $Device, 'shell', 'pm', 'path', 'com.muyuchat.mca')).Output -join ' '
$apkPath = ($p -replace 'package:', '').Trim()
$h = (Invoke-Adb @('-s', $Device, 'shell', "sha256sum $apkPath")).Output -join ' '
$sha = ([regex]::Match($h, '([0-9a-f]{64})')).Groups[1].Value
Say "base.apk sha256 = $sha"
if ($sha -ne $ExpectedApkSha256) { Say "IDENTITY_MISMATCH (expected $ExpectedApkSha256) -- ABORT"; Finish 2 }
Say 'IDENTITY_OK'
if (-not (Assert-Foreground)) { Say 'ABORT: MCA not foreground'; Finish 6 }

# ---------------------------------------------------------------- baseline
$w0 = Get-WorkerTicks
$stage0 = Get-StageFileSignature
Say "baseline worker pid=$($w0.pid) ticks=$($w0.ticks) stage_md5=$stage0"

# ---------------------------------------------------------------- new conversation (rule 7)
Say '=== rule 7: start from a NEW conversation to exclude persisted-history false positives ==='
function Test-Composer {
    $n = @(Get-Nodes (Get-UiDump))
    return (@($n | Where-Object { $_.cls -match 'EditText' }).Count -gt 0)
}
$nodes = @(Get-Nodes (Get-UiDump))
$newChat = @($nodes | Where-Object { $_.desc -eq '新建聊天' }) | Select-Object -First 1
if ($newChat) {
    Invoke-Adb @('-s', $Device, 'shell', 'input', 'tap', "$($newChat.cx)", "$($newChat.cy)") | Out-Null
    Start-Sleep -Seconds 3
    # The picker sheet offers '创建新会话' (create new) alongside role cards / past sessions.
    # Prefer it; a role-card tap may only re-open the sheet, which is why we then assert the composer.
    $made = $false
    foreach ($attempt in 1..2) {
        $n2 = @(Get-Nodes (Get-UiDump))
        $mk = @($n2 | Where-Object { $_.text -eq '创建新会话' }) | Select-Object -First 1
        if ($mk) {
            Invoke-Adb @('-s', $Device, 'shell', 'input', 'tap', "$($mk.cx)", "$($mk.cy)") | Out-Null
            Say "tapped '创建新会话' (attempt $attempt)"
            Start-Sleep -Seconds 4
            if (Test-Composer) { $made = $true; break }
        }
        $pick = @($n2 | Where-Object { $_.text -eq '默认助手' -and $_.cx -gt 300 -and $_.cx -lt 800 }) | Select-Object -First 1
        if ($pick -and -not $made) {
            Invoke-Adb @('-s', $Device, 'shell', 'input', 'tap', "$($pick.cx)", "$($pick.cy)") | Out-Null
            Say "tapped role card '默认助手' (attempt $attempt)"
            Start-Sleep -Seconds 4
            if (Test-Composer) { $made = $true; break }
        }
    }
    if ($made) {
        Say 'NEW_CONVERSATION_OK (composer present -- history false positives excluded)'
    } else {
        Say 'NEW_CONVERSATION_NOT_CONFIRMED: composer absent after 2 attempts.'
        Say 'VERDICT=INCONCLUSIVE (could not establish a fresh conversation -- do NOT record a pass)'
        Finish 7
    }
} else {
    Say "NOTE: '新建聊天' not present; continuing in the current conversation"
}

# ---------------------------------------------------------------- send
Say '=== locate composer + send from a FRESH dump ==='
if (-not (Assert-Foreground)) { Say 'ABORT: foreground lost before input'; Finish 6 }
$nodes3 = @(Get-Nodes (Get-UiDump))
$field = @($nodes3 | Where-Object { $_.cls -match 'EditText' }) | Select-Object -First 1
if (-not $field) { Say 'MISS: no EditText'; Finish 3 }
Invoke-Adb @('-s', $Device, 'shell', 'input', 'tap', "$($field.cx)", "$($field.cy)") | Out-Null
Start-Sleep -Milliseconds 1500
# CLEAR THE COMPOSER FIRST. After a cross-owner handover the composer can still hold another owner's
# text, and new input CONCATENATES onto it (observed: 'Reply with exactla red apple on a wooden tabley: COVERAGE1').
# Typing into a dirty composer would corrupt the prompt and invalidate the run.
$pre = @(Get-Nodes (Get-UiDump)) | Where-Object { $_.cls -match 'EditText' } | Select-Object -First 1
$preText = if ($pre) { [string]$pre.text } else { '' }
if ($preText.Trim().Length -gt 0) {
    Say ("composer had residual text ({0} chars) -> clearing" -f $preText.Length)
    Invoke-Adb @('-s', $Device, 'shell', 'input', 'keyevent', 'KEYCODE_MOVE_END') | Out-Null
    $dels = [Math]::Min(400, $preText.Length + 20)
    for ($k = 0; $k -lt $dels; $k++) {
        Invoke-Adb @('-s', $Device, 'shell', 'input', 'keyevent', 'KEYCODE_DEL') | Out-Null
    }
    Start-Sleep -Milliseconds 600
    $post = @(Get-Nodes (Get-UiDump)) | Where-Object { $_.cls -match 'EditText' } | Select-Object -First 1
    $postText = if ($post) { [string]$post.text } else { '' }
    Say ("composer after clear: {0} chars" -f $postText.Trim().Length)
    if ($postText.Trim().Length -gt 0) {
        Say 'COMPOSER_NOT_CLEARED: residual text survived the clear attempt.'
        Say 'VERDICT=INCONCLUSIVE (instrumentation: dirty composer would corrupt the prompt)'
        Finish 9
    }
} else {
    Say 'composer verified empty before typing'
}
Invoke-Adb @('-s', $Device, 'shell', 'input', 'text', ($Prompt -replace ' ', '%s')) | Out-Null
Start-Sleep -Milliseconds 1500
# ---- Lead-hardened PRE-SEND three-in-one assertion -------------------------------------------
# A composer that is bound to the IMAGE model has no chat send control at all (observed: the
# composer showed the chip '当前：本地 · Stable Diffusion Turbo · 512x512' and no '发送').
# Sending in that state would judge the wrong surface entirely, so all three must hold:
#   1. an exact '发送' send control exists
#   2. the composer context is a CHAT model, not an image-model chip
#   3. the conversation is the fresh one established above (NEW_CONVERSATION_OK)
# Any failure -> MODE_CONTEXT_MISMATCH -> instrumentation problem, NEVER a product verdict.
$nodes4 = @(Get-Nodes (Get-UiDump))
# (1) exact match only: a near-name/echo node must not satisfy this
$send = @($nodes4 | Where-Object { $_.text -eq '发送' }) | Select-Object -First 1
if (-not $send) { $send = @($nodes4 | Where-Object { $_.desc -eq '发送' }) | Select-Object -First 1 }
$hasSend = [bool]$send
# (2) image-model chip in the composer area? pattern: '当前：本地 · <image model>'
# Detection widened after a real miss: the chip can read lowercase 'stable_diffusion_v1_5-qnn_...'
# (case-sensitive matching missed it), and the definitive signal is the in-chat image MODE itself:
# the composer offers '聊天内生图' / '退出聊天生图模式' and the action button is '生成图片' instead of '发送'.
# PRECISE image-mode detection. Widening must not create false positives: a help sentence that merely
# contains the words '聊天内生图' is NOT the mode. Positive signals only:
#   (a) the composer mode chip           : text starts with '当前：' AND names an image model
#   (b) the exit-mode control            : desc == '退出聊天生图模式'
#   (c) the action button IS '生成图片'  : exact text match (in chat mode it is '发送')
$imgChip = @($nodes4 | Where-Object {
    (($_.text -match '^当前：') -and ($_.text -match '(?i)stable[_-]?diffusion|qwen[_-]?image|mnn|flux|sana|z[_-]?image|longcat|生图')) -or
    ($_.desc -eq '退出聊天生图模式') -or
    ($_.text -eq '生成图片')
}) | Select-Object -First 1
$chatBound = -not [bool]$imgChip
$chipText = '-'
if ($imgChip) { $chipText = [string]$imgChip.text }
Say ("pre_send: hasSend={0} chatBound={1} image_chip='{2}'" -f $hasSend, $chatBound, $chipText)
if (-not ($hasSend -and $chatBound)) {
    Say 'MODE_CONTEXT_MISMATCH: composer is not a ready CHAT surface (missing send control and/or bound to an image model).'
    Say 'VERDICT=INCONCLUSIVE (instrumentation: wrong surface -- do NOT judge the product from this)'
    Finish 8
}
Invoke-Adb @('-s', $Device, 'shell', 'input', 'tap', "$($send.cx)", "$($send.cy)") | Out-Null
Start-Sleep -Milliseconds 1200

# rule 4: send verification
$cleared = $false
for ($a = 1; $a -le 2; $a++) {
    $nv = @(Get-Nodes (Get-UiDump))
    $holds = @($nv | Where-Object { $_.cls -match 'EditText' -and $_.text -and $_.text.Trim().Length -gt 0 }).Count
    if ($holds -eq 0) { $cleared = $true; break }
    Say "send_check attempt=$a composer still holds text -> re-locating send"
    $sv = @($nv | Where-Object { $_.desc -eq '发送' -or $_.text -eq '发送' }) | Select-Object -First 1
    if ($sv) { Invoke-Adb @('-s', $Device, 'shell', 'input', 'tap', "$($sv.cx)", "$($sv.cy)") | Out-Null; Start-Sleep -Milliseconds 1200 }
}
if (-not $cleared) {
    Say 'SEND_NOT_VERIFIED: composer still holds the prompt after 2 taps.'
    Say 'VERDICT=INCONCLUSIVE (tap did not land -- NOT evidence of a product stall)'
    Finish 5
}
Say "SEND_VERIFIED (composer cleared) prompt='$Prompt'"

# ---------------------------------------------------------------- observe
Say "=== observing ${ObserveSeconds}s (rule 5: independent structural signals) ==="
$t0 = Get-Date
$deadline = $t0.AddSeconds($ObserveSeconds)
$sawMetrics = $false; $sawAnswerNode = $false; $sawThinking = $false
$metricLine = ''
$endedReady = $false
while ((Get-Date) -lt $deadline) {
    $n = @(Get-Nodes (Get-UiDump))
    $labels = @($n | ForEach-Object { if ($_.text) { $_.text } elseif ($_.desc) { $_.desc } } | Where-Object { $_ })
    # rule 5: metrics line is app-rendered for a COMPLETED turn -> independent of user echo
    $m = @($labels | Where-Object { $_ -match '上行\s*\d+\s*tokens.*下行\s*\d+\s*tokens' }) | Select-Object -First 1
    if ($m) { $sawMetrics = $true; $metricLine = $m }
    # a standalone answer node equal to the requested token, on its own (not inside the prompt text)
    $ans = @($n | Where-Object { $_.text -and $_.text.Trim() -eq $ExpectedToken -and $_.text.Trim() -ne $Prompt })
    if ($ans.Count -gt 0) { $sawAnswerNode = $true }
    $thinking = @($labels | Where-Object { $_ -match '正在思考|生成中' }) | Select-Object -First 1
    $ready = @($labels | Where-Object { $_ -match '已就绪' }) | Select-Object -First 1
    $sawThinking = $sawThinking -or [bool]$thinking
    $endedReady = [bool]$ready
    $el = [int]((Get-Date) - $t0).TotalSeconds
    $w = Get-WorkerTicks
    Say ("t=${el}s metrics=$sawMetrics answerNode=$sawAnswerNode thinking=$([bool]$thinking) ready=$([bool]$ready) worker_ticks=$($w.ticks)")
    if ($sawMetrics -and $sawAnswerNode -and -not $thinking) { break }
    Start-Sleep -Seconds 3
}

$w1 = Get-WorkerTicks
$stage1 = Get-StageFileSignature
$delta = $w1.ticks - $w0.ticks
$stageChanged = ($stage0 -ne $stage1)
Say "after: worker ticks=$($w1.ticks) delta=$delta stage_md5=$stage1 stage_changed=$stageChanged"

Say ''
Say '=== VERDICT ==='
Say "saw_metrics_line=$sawMetrics  saw_standalone_answer_node=$sawAnswerNode  worker_tick_delta=$delta  stage_file_changed=$stageChanged"
Say "metrics_line='$metricLine'"
# A PASS needs BOTH an independent assistant-side structural signal AND positive proof the worker ran.
if ($sawMetrics -and $sawAnswerNode -and $delta -gt 2) {
    Say "VERDICT=PASS  (engine=${Engine}: independent metrics line + standalone answer node + worker really executed)"
} elseif ($sawAnswerNode -and -not $sawMetrics) {
    Say 'VERDICT=PARTIAL  (answer node seen but no metrics line -- re-observe; do not record as PASS)'
} elseif ($sawThinking -and -not $sawAnswerNode -and $delta -le 2) {
    Say 'VERDICT=STALL  (thinking with no answer, worker idle)'
} else {
    Say 'VERDICT=INCONCLUSIVE  (inspect samples; do not record a pass)'
}
Finish 0
