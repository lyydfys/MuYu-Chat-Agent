# MCA F1 reproduction / after-fix verification: UI chat send produces no output and hangs in
# "正在思考" (thinking) while the worker stays idle.
#
# WHY THIS SCRIPT EXISTS
#   F1 (see E:\MAC_QA_EVIDENCE\chat\FAIL-llama-ui-send-stall.md) is a reproduced product defect in the
#   UI/main-process dispatch layer: on BOTH chat engines (llama_cpp and litert_lm) a UI send produces
#   zero output while the worker's CPU is flat and its threads are all sleeping, yet the API path on
#   the SAME model/engine returns real content. This script makes that measurement repeatable so a
#   source owner can prove a fix (verdict flips STALL -> OUTPUT) or re-confirm the defect.
#
# WHAT IT DOES
#   1. Group 0: verify the on-device base.apk hash is the frozen under-test artifact.
#   2. Locate the chat input and send control from a FRESH uiautomator dump (never guessed coordinates).
#   3. Type a trivial prompt and tap send.
#   4. Observe for the whole window, recording:
#        - the exact UI status words ("正在思考 (已用时 N 秒)" / "生成中" / "已就绪" / "已停止生成")
#        - worker PID + CPU ticks BEFORE and AFTER (flat ticks == not computing)
#        - worker thread states (all 'S' == sleeping)
#        - files/local_chat_worker_diagnostics/worker-stage.json : mtime + content hash
#        - FGS leases: isForeground / foregroundId for 11436 and 11438
#   5. Emit a VERDICT line: STALL | OUTPUT | INCONCLUSIVE.
#
# SAFETY / DISCIPLINE
#   * Never writes an API key or token anywhere; no Authorization header is used at all.
#   * `ps -A -o ARGS` output is never persisted (it can carry a bearer token); only PID + short name.
#   * Read-only observation otherwise: no install, no data clearing, no model/data deletion.
#
# USAGE
#   powershell -NoProfile -File qa-chat-ui-send-repro.ps1
#   powershell -NoProfile -File qa-chat-ui-send-repro.ps1 -ObserveSeconds 120 -Prompt 'Reply with exactly: OK'

[CmdletBinding()]
param(
    [string]$Device = 'device-a',
    [string]$Out = 'E:\MAC_QA_EVIDENCE\chat',
    [int]$ObserveSeconds = 90,
    [string]$Prompt = 'Reply with exactly: OK',
    [ValidateSet('llama','litert')][string]$Engine = 'llama',
    [string]$ExpectedApkSha256 = '37a5bf7b27c180907f39ffd8942dd2b403bb5ad5018c6ff9346843e11b3c5d89'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$adb = 'D:\model\android-sdk\platform-tools\adb.exe'
New-Item -ItemType Directory -Force -Path $Out | Out-Null
$logPath = Join-Path $Out 'chat-ui-send-repro.log'
$script:lines = New-Object System.Collections.ArrayList
function Say([string]$m) {
    $ts = (Get-Date).ToUniversalTime().ToString('HH:mm:ssZ')
    $line = "[$ts] $m"
    Write-Host $line
    [void]$script:lines.Add($line)
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
        Invoke-Adb @('-s', $Device, 'shell', 'rm', '-f', '/sdcard/qa_repro.xml') | Out-Null
        Invoke-Adb @('-s', $Device, 'shell', 'uiautomator', 'dump', '--compressed', '/sdcard/qa_repro.xml') | Out-Null
        $local = Join-Path $Out 'repro-ui.xml'
        Invoke-Adb @('-s', $Device, 'pull', '/sdcard/qa_repro.xml', $local) | Out-Null
        if ((Test-Path $local) -and ((Get-Item $local).Length -gt 3000)) { return $local }
        Start-Sleep -Milliseconds 900
    }
    return $null
}

function Get-Nodes {
    param([string]$XmlPath)
    if (-not $XmlPath -or -not (Test-Path $XmlPath)) { return @() }
    [xml]$doc = Get-Content -LiteralPath $XmlPath -Raw -Encoding UTF8
    $list = New-Object System.Collections.ArrayList
    function Walk($node) {
        foreach ($c in $node.childNodes) {
            if ($c.NodeType -ne 'Element') { continue }
            if ($c.Name -eq 'node') {
                $b = [string]$c.GetAttribute('bounds')
                if ($b -match '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') {
                    $x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]; $x2 = [int]$Matches[3]; $y2 = [int]$Matches[4]
                    [void]$list.Add([pscustomobject]@{
                        text = [string]$c.GetAttribute('text')
                        desc = [string]$c.GetAttribute('content-desc')
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

# Read worker facts WITHOUT ever persisting full ARGS. Returns pid, ticks, thread states.
function Get-WorkerFacts {
    param([string]$ProcessSuffix)
    $r = Invoke-Adb @('-s', $Device, 'shell', "ps -A -o PID,ARGS | grep 'com.muyuchat.mca$ProcessSuffix' | grep -v grep | tr -s ' ' | cut -d' ' -f1 | head -1")
    $pidStr = ($r.Output -join '').Trim()
    if (-not $pidStr -or $pidStr -notmatch '^\d+$') { return [pscustomobject]@{ pid = $null; ticks = $null; threads = @() } }
    $t = Invoke-Adb @('-s', $Device, 'shell', "awk '{print `$14+`$15}' /proc/$pidStr/stat 2>/dev/null")
    $ticks = ($t.Output -join '').Trim()
    $th = Invoke-Adb @('-s', $Device, 'shell', "for T in `$(ls /proc/$pidStr/task 2>/dev/null); do awk '{print `$3}' /proc/$pidStr/task/`$T/stat 2>/dev/null; done | sort | uniq -c")
    return [pscustomobject]@{ pid = $pidStr; ticks = $ticks; threads = @($th.Output) }
}

function Get-StageFileHash {
    $r = Invoke-Adb @('-s', $Device, 'shell', "run-as com.muyuchat.mca cat files/local_chat_worker_diagnostics/worker-stage.json 2>/dev/null | md5sum; run-as com.muyuchat.mca ls -l files/local_chat_worker_diagnostics/worker-stage.json 2>/dev/null")
    return @($r.Output)
}

function Get-FgsLeases {
    $r = Invoke-Adb @('-s', $Device, 'shell', "dumpsys activity services com.muyuchat.mca 2>/dev/null | grep -oE 'foregroundId=[0-9]+|isForeground=[a-z]+' | paste - - 2>/dev/null")
    return @($r.Output)
}

# ---------------------------------------------------------------- Group 0: identity
Say '=== Group 0: verify the on-device artifact identity ==='
$p = (Invoke-Adb @('-s', $Device, 'shell', 'pm', 'path', 'com.muyuchat.mca')).Output -join ' '
$apkPath = ($p -replace 'package:', '').Trim()
$h = (Invoke-Adb @('-s', $Device, 'shell', "sha256sum $apkPath")).Output -join ' '
$sha = ([regex]::Match($h, '([0-9a-f]{64})')).Groups[1].Value
Say "base.apk sha256 = $sha"
if ($sha -ne $ExpectedApkSha256) {
    Say "IDENTITY_MISMATCH expected=$ExpectedApkSha256 -- ABORT (do not test an unverified artifact)"
    $script:lines | Set-Content -LiteralPath $logPath -Encoding UTF8
    exit 2
}
Say 'IDENTITY_OK'

# ---------------------------------------------------------------- baseline
Say '=== baseline: worker facts + FGS + stage file BEFORE send ==='
$engineSuffix = if ($Engine -eq 'litert') { ':litert_chat' } else { ':local_chat' }
$wf0 = Get-WorkerFacts -ProcessSuffix $engineSuffix
Say ("worker{0} pid={1} ticks={2} threadstates={3}" -f $engineSuffix, $wf0.pid, $wf0.ticks, (($wf0.threads) -join ' '))
foreach ($l in Get-FgsLeases) { if ($l.Trim()) { Say "fgs before: $($l.Trim())" } }
foreach ($l in Get-StageFileHash) { if ($l.Trim()) { Say "stage-file before: $($l.Trim())" } }

# ---------------------------------------------------------------- locate + send
Say '=== locate input field and send control from a FRESH dump ==='
$nodes = @(Get-Nodes (Get-UiDump))
$field = @($nodes | Where-Object { $_.cls -match 'EditText' }) | Select-Object -First 1
if (-not $field) {
    Say 'MISS: no EditText in the fresh dump -- cannot input. ABORT (no guessed coordinates).'
    $script:lines | Set-Content -LiteralPath $logPath -Encoding UTF8
    exit 3
}
Say "field centre=($($field.cx),$($field.cy))"
Invoke-Adb @('-s', $Device, 'shell', 'input', 'tap', "$($field.cx)", "$($field.cy)") | Out-Null
Start-Sleep -Milliseconds 1500
$escaped = ($Prompt -replace ' ', '%s')
Invoke-Adb @('-s', $Device, 'shell', 'input', 'text', $escaped) | Out-Null
Start-Sleep -Milliseconds 1500

# re-dump: the keyboard shifts the layout, so the send control must be re-located
$nodes2 = @(Get-Nodes (Get-UiDump))
$send = @($nodes2 | Where-Object { $_.desc -eq '发送' }) | Select-Object -First 1
if (-not $send) { $send = @($nodes2 | Where-Object { $_.text -eq '发送' }) | Select-Object -First 1 }
if (-not $send) {
    Say 'MISS: send control not in the fresh dump. ABORT (no guessed coordinates).'
    $script:lines | Set-Content -LiteralPath $logPath -Encoding UTF8
    exit 4
}
Say "send centre=($($send.cx),$($send.cy))"
Invoke-Adb @('-s', $Device, 'shell', 'input', 'tap', "$($send.cx)", "$($send.cy)") | Out-Null
Start-Sleep -Milliseconds 1200

# --- SEND VERIFICATION (guards against a missed tap being misread as a STALL) ---
# A composer that still holds our prompt text means the tap did NOT land. Treating that as a product
# STALL would be a false verdict, so retry once and otherwise abort as INCONCLUSIVE.
$composerCleared = $false
for ($attempt = 1; $attempt -le 2; $attempt++) {
    $nodesV = @(Get-Nodes (Get-UiDump))
    $fieldV = @($nodesV | Where-Object { $_.cls -match 'EditText' })
    $stillHolds = @($fieldV | Where-Object { $_.text -and $_.text.Trim().Length -gt 0 }).Count
    if ($stillHolds -eq 0) { $composerCleared = $true; break }
    Say "send_check attempt=$attempt composer still holds text -> tap may have missed; re-locating send"
    $sendV = @($nodesV | Where-Object { $_.desc -eq '发送' }) | Select-Object -First 1
    if (-not $sendV) { $sendV = @($nodesV | Where-Object { $_.text -eq '发送' }) | Select-Object -First 1 }
    if ($sendV) {
        Invoke-Adb @('-s', $Device, 'shell', 'input', 'tap', "$($sendV.cx)", "$($sendV.cy)") | Out-Null
        Start-Sleep -Milliseconds 1200
    } else {
        Say 'send_check: send control not found on retry'
    }
}
if ($composerCleared) {
    Say "SEND_VERIFIED (composer cleared) prompt='$Prompt'"
} else {
    Say 'SEND_NOT_VERIFIED: composer still holds the prompt after 2 taps.'
    Say 'VERDICT=INCONCLUSIVE  (the tap did not land -- this is NOT evidence of a product stall)'
    $script:lines | Set-Content -LiteralPath $logPath -Encoding UTF8
    exit 5
}

# ---------------------------------------------------------------- observe
Say "=== observing for ${ObserveSeconds}s ==="
$t0 = Get-Date
$sawThinking = $false
$sawOutput = $false
$lastStatus = ''
$deadline = $t0.AddSeconds($ObserveSeconds)
$pidSamples = New-Object System.Collections.ArrayList
$script:endedThinking = $false
$script:endedReady = $false

while ((Get-Date) -lt $deadline) {
    $nodesN = @(Get-Nodes (Get-UiDump))
    $labels = @($nodesN | ForEach-Object { if ($_.text) { $_.text } elseif ($_.desc) { $_.desc } } | Where-Object { $_ })
    $thinking = @($labels | Where-Object { $_ -match '正在思考|生成中' }) | Select-Object -First 1
    $ready = @($labels | Where-Object { $_ -match '已就绪' }) | Select-Object -First 1
    $stopped = @($labels | Where-Object { $_ -match '已停止生成' }) | Select-Object -First 1
    $stopCtl = @($nodesN | Where-Object { $_.desc -eq '停止' -or $_.text -eq '停止' }).Count -gt 0

    # any NEW assistant-ish text that is not our prompt and not chrome
    $chrome = @('发送', '停止', '已停止生成', '重新生成', '复制消息', '更多消息操作', '固定到上下文', '复制提示词', '更多操作', '打开历史', '新建聊天', '选择模型', 'API')
    $cand = @($labels | Where-Object {
        $_ -and $_ -ne $Prompt -and ($chrome -notcontains $_) -and
        ($_ -notmatch '^(已就绪|待加载|生成中|正在思考|保留 |MCA 设备状态|可用|\d+\.\d+ GB|CPU|GPU|NPU)') -and
        $_.Length -ge 2 -and $_ -notmatch '^\d+$'
    })
    if ($cand.Count -gt 0 -and -not $thinking) { $sawOutput = $true }

    $status = @($thinking, $ready, $stopped) | Where-Object { $_ } | Select-Object -First 1
    if ($thinking) { $sawThinking = $true }
    # end-state tracking: 'thinking' at the LAST sample means still running; ready/stopped means finished
    $script:endedThinking = [bool]$thinking
    $script:endedReady = [bool]$ready
    $el = [int]((Get-Date) - $t0).TotalSeconds
    $w = Get-WorkerFacts -ProcessSuffix $engineSuffix
    [void]$pidSamples.Add("t=${el}s ticks=$($w.ticks)")
    if ($status -ne $lastStatus -or ($el % 15 -lt 3)) {
        Say ("t=${el}s status='$status' stop_control=$stopCtl worker_ticks=$($w.ticks) candidate_output_nodes=$($cand.Count)")
        $lastStatus = $status
    }
    if ($sawOutput -and -not $thinking) { break }
    Start-Sleep -Seconds 3
}

# ---------------------------------------------------------------- after
Say '=== after: worker facts + FGS + stage file ==='
$wf1 = Get-WorkerFacts -ProcessSuffix $engineSuffix
Say ("worker{0} pid={1} ticks={2} threadstates={3}" -f $engineSuffix, $wf1.pid, $wf1.ticks, (($wf1.threads) -join ' '))
foreach ($l in Get-FgsLeases) { if ($l.Trim()) { Say "fgs after: $($l.Trim())" } }
foreach ($l in Get-StageFileHash) { if ($l.Trim()) { Say "stage-file after: $($l.Trim())" } }

$ticksBefore = 0; $ticksAfter = 0
[void][int]::TryParse(($wf0.ticks -replace '\D', ''), [ref]$ticksBefore)
[void][int]::TryParse(($wf1.ticks -replace '\D', ''), [ref]$ticksAfter)
$delta = $ticksAfter - $ticksBefore

Say ''
Say '=== VERDICT ==='
Say "worker_ticks_before=$ticksBefore worker_ticks_after=$ticksAfter delta=$delta"
Say "saw_thinking=$sawThinking saw_output=$sawOutput"
# A successful generation legitimately passes through "生成中/正在思考"; what matters is whether it
# FINISHED with real output, versus being stuck thinking with an idle worker.
$finished = (-not $script:endedThinking) -and ($script:endedReady -or $sawOutput)
if ($sawOutput -and $finished) {
    Say 'VERDICT=OUTPUT  (generation finished with real assistant text -- F1 not reproduced on this build)'
} elseif ($sawOutput) {
    Say 'VERDICT=OUTPUT_PARTIAL  (text appeared but the UI had not returned to ready at the last sample; re-observe)'
} elseif ($script:endedThinking -and -not $sawOutput -and $delta -le 2) {
    Say 'VERDICT=STALL  (UI reported thinking, produced no output, and the worker did no work -> F1 REPRODUCED)'
} else {
    Say 'VERDICT=INCONCLUSIVE  (inspect the samples above; do not record a pass)'
}
Say "tick_samples: $($pidSamples -join ' ')"
$script:lines | Set-Content -LiteralPath $logPath -Encoding UTF8
Say "log written: $logPath"
