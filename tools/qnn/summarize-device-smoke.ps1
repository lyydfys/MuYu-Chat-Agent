param(
    [string]$Root = "docs\experiments\device-smoke",
    [int]$Limit = 50,
    [switch]$AsJson
)

$ErrorActionPreference = "Stop"

function Get-JsonValue {
    param($Object, [string]$Name)
    if ($null -eq $Object) { return $null }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function Get-LastRegexValue {
    param([string]$Text, [string]$Pattern)
    $matches = [regex]::Matches($Text, $Pattern)
    if ($matches.Count -eq 0) { return $null }
    return $matches[$matches.Count - 1].Groups[1].Value
}

function Get-FirstRegexValue {
    param([string]$Text, [string]$Pattern)
    $match = [regex]::Match($Text, $Pattern)
    if (-not $match.Success) { return $null }
    return $match.Groups[1].Value
}

function Shorten-Text {
    param([string]$Value, [int]$Max = 180)
    if ([string]::IsNullOrWhiteSpace($Value)) { return $null }
    $clean = $Value -replace "\\n", " " -replace "\\t", " " -replace "\s+", " "
    if ($clean.Length -le $Max) { return $clean }
    return $clean.Substring(0, $Max) + "..."
}

function Get-SmokeKind {
    param([string]$Path)
    $normalized = $Path -replace "\\", "/"
    if ($normalized -match "/qnn-chat/") { return "qnn-chat" }
    if ($normalized -match "/qnn-smoke/") { return "qnn-image" }
    if ($normalized -match "/mnn-smoke/") { return "mnn-image" }
    return "smoke"
}

function Summarize-SmokeFile {
    param([System.IO.FileInfo]$File)

    $text = Get-Content -LiteralPath $File.FullName -Raw -Encoding UTF8
    $kind = Get-SmokeKind $File.FullName
    $summary = [ordered]@{
        kind = $kind
        file = $File.FullName
        runId = $null
        status = $null
        model = $null
        backend = $null
        backendDevices = $null
        runnerReady = $null
        ok = $null
        npuActive = $null
        width = $null
        height = $null
        steps = $null
        elapsedMs = $null
        ttftMs = $null
        decodeTps = $null
        completionTokens = $null
        unetMsAvg = $null
        outputBytes = $null
        downloaded = $null
        error = $null
        parsedJson = $false
        lastWriteTime = $File.LastWriteTime.ToString("s")
    }

    try {
        $json = $text | ConvertFrom-Json -ErrorAction Stop
        $summary.parsedJson = $true
        $summary.runId = Get-JsonValue $json "runId"
        $summary.status = Get-JsonValue $json "status"
        $summary.elapsedMs = Get-JsonValue $json "elapsedMs"

        $events = @(Get-JsonValue $json "events")
        $terminal = $events | Where-Object { $_.status -eq "completed" -or $_.status -eq "failed" } | Select-Object -Last 1
        if ($null -eq $terminal -and $events.Count -gt 0) {
            $terminal = $events | Select-Object -Last 1
        }
        if ($null -ne $terminal) {
            $candidateRunId = Get-JsonValue $terminal "runId"
            if ($null -eq $summary.runId) { $summary.runId = $candidateRunId }
            $candidateStatus = Get-JsonValue $terminal "status"
            if ($null -ne $candidateStatus) { $summary.status = $candidateStatus }
            $candidateElapsed = Get-JsonValue $terminal "elapsedMs"
            if ($null -ne $candidateElapsed) { $summary.elapsedMs = $candidateElapsed }
            $summary.outputBytes = Get-JsonValue $terminal "outputBytes"
            $summary.error = Shorten-Text (Get-JsonValue $terminal "error")

            $stats = Get-JsonValue $terminal "nativeStats"
            if ($null -ne $stats) {
                $summary.backend = Get-JsonValue $stats "backend"
                $summary.runnerReady = Get-JsonValue $stats "runnerReady"
                $summary.model = Get-JsonValue $stats "modelName"
                $summary.backendDevices = Get-JsonValue $stats "backendDevices"
                $summary.ttftMs = Get-JsonValue $stats "ttftMs"
                $summary.decodeTps = Get-JsonValue $stats "decodeTps"
                $summary.completionTokens = Get-JsonValue $stats "completionTokens"
            }

            $result = Get-JsonValue $terminal "result"
            if ($null -ne $result) {
                $summary.ok = Get-JsonValue $result "ok"
                $summary.npuActive = Get-JsonValue $result "npuActive"
                $summary.width = Get-JsonValue $result "width"
                $summary.height = Get-JsonValue $result "height"
                $summary.steps = Get-JsonValue $result "steps"
                $summary.unetMsAvg = Get-JsonValue $result "unetExecuteMsAvg"
                if ($null -eq $summary.error) {
                    $summary.error = Shorten-Text (Get-JsonValue $result "error")
                }
            }
        }
    } catch {
        $summary.error = Shorten-Text $_.Exception.Message
    }

    if ($null -eq $summary.runId) {
        $summary.runId = Get-FirstRegexValue $text '"runId"\s*:\s*"([^"]+)"'
    }
    if ($null -eq $summary.status) {
        $summary.status = Get-FirstRegexValue $text '"status"\s*:\s*"([^"]+)"'
    }
    if ($null -eq $summary.downloaded) {
        $summary.downloaded = Get-LastRegexValue $text '"downloaded"\s*:\s*"([^"]+)"'
    }
    if ($null -eq $summary.error) {
        $summary.error = Shorten-Text (Get-LastRegexValue $text '"error"\s*:\s*"((?:\\.|[^"])*)"')
    }
    if ($null -eq $summary.backendDevices) {
        $summary.backendDevices = Get-LastRegexValue $text '"backendDevices"\s*:\s*"([^"]+)"'
    }
    if ($null -eq $summary.decodeTps) {
        $summary.decodeTps = Get-LastRegexValue $text '"decodeTps"\s*:\s*([0-9.]+)'
    }
    if ($null -eq $summary.completionTokens) {
        $summary.completionTokens = Get-LastRegexValue $text '"completionTokens"\s*:\s*([0-9]+)'
    }
    if ($null -eq $summary.npuActive) {
        $summary.npuActive = Get-LastRegexValue $text '"npuActive"\s*:\s*(true|false)'
    }

    [pscustomobject]$summary
}

if (-not (Test-Path -LiteralPath $Root -PathType Container)) {
    throw "Smoke root not found: $Root"
}

$items = Get-ChildItem -LiteralPath $Root -Recurse -Filter *.json |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First $Limit |
    ForEach-Object { Summarize-SmokeFile $_ }

if ($AsJson) {
    $items | ConvertTo-Json -Depth 6
} else {
    $items |
        Select-Object kind, status, runId, model, backend, backendDevices, runnerReady, ok, npuActive, downloaded, completionTokens, decodeTps, elapsedMs, error, file |
        Format-Table -AutoSize -Wrap
}
