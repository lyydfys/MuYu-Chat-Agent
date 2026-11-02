# Regenerates the controlled-replay fixture digests and manifest.
#
# Run this ONLY when the fixture is intentionally changed. The digest is evidence: silently
# refreshing it after an accidental edit would make every bound run point at different content.
[CmdletBinding()]
param(
    [string] $FixturePath = '',
    [string] $ManifestPath = ''
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (-not $FixturePath) { $FixturePath = Join-Path $repo 'benchmark\src\main\assets\controlled-replay\stream-edge-cases.json' }
if (-not $ManifestPath) { $ManifestPath = Join-Path $repo 'benchmark\src\main\assets\controlled-replay\manifest.json' }
$fixture = Get-Content -LiteralPath $FixturePath -Raw -Encoding UTF8 | ConvertFrom-Json
if ([string]$fixture.schema -ne 'mca.controlled-replay.fixture/1') {
    throw ("fixture schema is '{0}', expected mca.controlled-replay.fixture/1" -f [string]$fixture.schema)
}

function Get-McaReplaySha256([string]$Text) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($Text)))).Replace('-', '').ToLowerInvariant()
    } finally { $sha.Dispose() }
}

function Get-ReplayEventText($Event) {
    $parts = New-Object System.Collections.Generic.List[string]
    if ($Event.PSObject.Properties.Name -contains 'text' -and $null -ne $Event.text) {
        $parts.Add([string]$Event.text) | Out-Null
    }
    if ($Event.PSObject.Properties.Name -contains 'blocks' -and $null -ne $Event.blocks) {
        foreach ($block in @($Event.blocks)) {
            if ([string]$block.kind -ne 'repeat') { throw ("unknown block kind '{0}'" -f [string]$block.kind) }
            $unit = [string]$block.unit
            $count = [int]$block.count
            if ($count -le 0) { throw 'repeat count must be positive' }
            $parts.Add(($unit * $count)) | Out-Null
        }
    }
    return ($parts -join '')
}

$caseIds = New-Object System.Collections.Generic.List[string]
foreach ($case in @($fixture.cases)) {
    $caseIds.Add([string]$case.id) | Out-Null
    # Multi-run cases (cancel then new run) describe one turn each: the final answer belongs to the
    # highest run id only. Text from a cancelled run is never part of the newest answer.
    $maxRun = 0
    foreach ($event in @($case.events)) {
        if ($event.PSObject.Properties.Name -contains 'run') {
            $runValue = [int]$event.run
            if ($runValue -gt $maxRun) { $maxRun = $runValue }
        }
    }
    $finalParts = New-Object System.Collections.Generic.List[string]
    $lateParts = New-Object System.Collections.Generic.List[string]
    $terminalCount = 0
    $cancelCount = 0
    $lateCount = 0
    foreach ($event in @($case.events)) {
        $type = [string]$event.type
        $run = if ($event.PSObject.Properties.Name -contains 'run') { [int]$event.run } else { 0 }
        if ($type -eq 'text') {
            $text = Get-ReplayEventText $event
            if ($event.PSObject.Properties.Name -contains 'late' -and [bool]$event.late) {
                $lateCount++
                $lateParts.Add($text) | Out-Null
            } elseif ($run -eq 0 -or $run -eq $maxRun) {
                $finalParts.Add($text) | Out-Null
            }
        } elseif ($type -eq 'terminal') {
            $terminalCount++
        } elseif ($type -eq 'cancel') {
            $cancelCount++
        }
    }
    $finalText = $finalParts -join ''
    $lateText = $lateParts -join ''
    $hasBlocks = @($case.events | Where-Object { $_.PSObject.Properties.Name -contains 'blocks' }).Count -gt 0
    if ($case.expect.PSObject.Properties.Name -contains 'finalText') {
        if ([string]$case.expect.finalText -ne $finalText) {
            throw ("case '{0}': declared finalText differs from the concatenated text events" -f $case.id)
        }
    } elseif (-not $hasBlocks) {
        # Literal cases keep the expected body inline so a reviewer can read it. Block-generated
        # cases (2048 burst, 100k stress) keep only length+digest: inlining 100k into assets would
        # bloat the test APK without adding evidence.
        $case.expect | Add-Member -NotePropertyName finalText -NotePropertyValue $finalText -Force
    }
    if ($case.expect.eventCount -ne @($case.events).Count) {
        throw ("case '{0}': eventCount {1} != {2}" -f $case.id, $case.expect.eventCount, @($case.events).Count)
    }
    if ($case.expect.terminalCount -ne $terminalCount) {
        throw ("case '{0}': terminalCount {1} != {2}" -f $case.id, $case.expect.terminalCount, $terminalCount)
    }
    if ($case.expect.cancelCount -ne $cancelCount) {
        throw ("case '{0}': cancelCount {1} != {2}" -f $case.id, $case.expect.cancelCount, $cancelCount)
    }
    if ($case.expect.PSObject.Properties.Name -contains 'lateTextCount' -and $case.expect.lateTextCount -ne $lateCount) {
        throw ("case '{0}': lateTextCount {1} != {2}" -f $case.id, $case.expect.lateTextCount, $lateCount)
    }
    if ($lateCount -gt 0 -and $case.expect.PSObject.Properties.Name -contains 'lateText') {
        if ([string]$case.expect.lateText -ne $lateText) {
            throw ("case '{0}': declared lateText differs from the late events" -f $case.id)
        }
    }
    $sha = Get-McaReplaySha256 $finalText
    $case.expect | Add-Member -NotePropertyName finalTextLength -NotePropertyValue $finalText.Length -Force
    $case.expect | Add-Member -NotePropertyName finalTextSha256 -NotePropertyValue $sha -Force
    Write-Host ("case {0}: events={1} terminal={2} cancel={3} late={4} chars={5} sha256={6}" -f
        $case.id, @($case.events).Count, $terminalCount, $cancelCount, $lateCount, $finalText.Length, $sha)
}

foreach ($required in @($fixture.requiredCases)) {
    if (-not $caseIds.Contains([string]$required)) { throw ("required case '{0}' is missing from the fixture" -f $required) }
}
$fixture | Add-Member -NotePropertyName caseCount -NotePropertyValue $caseIds.Count -Force

$json = ($fixture | ConvertTo-Json -Depth 20) -replace "`r`n", "`n"
[System.IO.File]::WriteAllText($FixturePath, ($json + "`n"), (New-Object System.Text.UTF8Encoding($false)))
$fixtureSha = (Get-FileHash -LiteralPath $FixturePath -Algorithm SHA256).Hash.ToLowerInvariant()

$manifest = [ordered]@{
    schema = 'mca.controlled-replay.manifest/1'
    fixture = 'stream-edge-cases.json'
    fixtureSha256 = $fixtureSha
    fixtureBytes = (Get-Item -LiteralPath $FixturePath).Length
    caseCount = $caseIds.Count
    cases = @($caseIds.ToArray())
    chain = [ordered]@{
        intended = @('production publisher', 'MainUiState', 'chat projection/mapping', 'ChatRoute', 'Compose renderer')
        status = 'BLOCKED'
        reason = 'The real publisher (app/src/main/java/com/muyuchat/mca/presentation/ChatGenerationUiPublisher.kt) is internal to :app. A com.android.test module cannot reference :app classes and the release manifest must not expose a session-injection entry, so this benchmark module cannot drive publisher -> state -> mapping -> ChatRoute -> renderer in-process.'
        notCovered = @('publisher write class (run gating, stats throttling)', 'MainUiState projection', 'ChatRoute mapping', 'Compose renderer frame timing for replayed events')
        minimalFollowUp = @(
            'Add an internal replay driver in :app androidTest (same module, friend path to internal declarations) that constructs ChatGenerationUiPublisher against the real MainActivity view model and replays this fixture on the monotonic clock.',
            'Expose the driver result (planned/consumed events, final text SHA, visible chars, copy SHA, scroll operations) as an instrumentation argument-driven test, or move the replay scenario into :app androidTest entirely.',
            'Do not add an exported activity/service/receiver for replay: the release manifest must stay free of external session injection.'
        )
    }
    observations = 'NOT_RUN until the :app in-process driver exists; this fixture alone proves only the fixture contract (SHA, event plan, terminal/cancel/late-chunk semantics, content shapes).'
    timing = $fixture.timing
}
$manifestJson = ($manifest | ConvertTo-Json -Depth 10) -replace "`r`n", "`n"
[System.IO.File]::WriteAllText($ManifestPath, ($manifestJson + "`n"), (New-Object System.Text.UTF8Encoding($false)))
Write-Host ("fixture: {0} bytes sha256={1}" -f $manifest.fixtureBytes, $fixtureSha)
Write-Host ("manifest: {0}" -f $ManifestPath)
