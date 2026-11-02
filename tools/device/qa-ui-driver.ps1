# MCA 2.3 chat-matrix UI driver.
#
# Discipline enforced by construction:
#   * every tap is preceded by a FRESH uiautomator dump and uses that dump's bounds centre;
#   * a control that is absent from the fresh dump yields MISS and the step is abandoned
#     (never a remembered/hard-coded coordinate);
#   * evidence files never contain a full API key/token, and never contain `ps -A -o ARGS` output.
#
# Usage:
#   powershell -NoProfile -File qa-ui-driver.ps1 -Action dump|tap-label|tap-desc|list|type -Value <text> -Out <dir>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Action,
    [string]$Value = '',
    [string]$Out = 'E:\MAC_QA_EVIDENCE\chat',
    [string]$Device = 'device-a',
    [int]$Index = 0
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$adb = 'D:\model\android-sdk\platform-tools\adb.exe'

function Invoke-Adb {
    param([string[]]$AdbArgs)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $o = @(& $adb @AdbArgs 2>&1); $c = $LASTEXITCODE } finally { $ErrorActionPreference = $prev }
    return [pscustomobject]@{ Output = $o; Code = $c }
}

function Get-UiDump {
    # Fresh dump on every call. uiautomator occasionally returns a null/short root right after a
    # transition, so retry a few times rather than trusting a bad dump.
    for ($i = 1; $i -le 4; $i++) {
        Invoke-Adb @('-s', $Device, 'shell', 'rm', '-f', '/sdcard/qa_ui.xml') | Out-Null
        Invoke-Adb @('-s', $Device, 'shell', 'uiautomator', 'dump', '--compressed', '/sdcard/qa_ui.xml') | Out-Null
        $local = Join-Path $Out 'ui-last.xml'
        $pull = Invoke-Adb @('-s', $Device, 'pull', '/sdcard/qa_ui.xml', $local)
        if (Test-Path $local) {
            $len = (Get-Item $local).Length
            if ($len -gt 3000) { return $local }
        }
        Start-Sleep -Milliseconds 900
    }
    throw "uiautomator dump failed to produce a usable tree after 4 attempts"
}

function Get-Nodes {
    param([string]$XmlPath)
    [xml]$doc = Get-Content -LiteralPath $XmlPath -Raw -Encoding UTF8
    $list = New-Object System.Collections.ArrayList
    function Walk($node) {
        foreach ($child in $node.childNodes) {
            if ($child.NodeType -ne 'Element') { continue }
            if ($child.Name -eq 'node') {
                $text = [string]$child.GetAttribute('text')
                $desc = [string]$child.GetAttribute('content-desc')
                $bounds = [string]$child.GetAttribute('bounds')
                $cls = [string]$child.GetAttribute('class')
                $clickable = [string]$child.GetAttribute('clickable')
                if ($bounds -match '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') {
                    $x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]
                    $x2 = [int]$Matches[3]; $y2 = [int]$Matches[4]
                    [void]$list.Add([pscustomobject]@{
                        text = $text; desc = $desc; cls = $cls; clickable = $clickable
                        cx = [int](($x1 + $x2) / 2); cy = [int](($y1 + $y2) / 2)
                        x1 = $x1; y1 = $y1; x2 = $x2; y2 = $y2
                    })
                }
            }
            Walk $child
        }
    }
    Walk $doc.DocumentElement
    return $list
}

$nodes = Get-Nodes (Get-UiDump)

switch ($Action) {
    'dump' {
        $nodes | ForEach-Object {
            if ($_.text -or $_.desc) {
                "{0}|{1}|{2}|{3}|{4}" -f ($_.text -replace '\|', '/'), ($_.desc -replace '\|', '/'), $_.cx, $_.cy, $_.cls
            }
        }
    }
    'list' {
        # all nodes carrying any label, with coordinates -- for building the locator map
        $nodes | Where-Object { $_.text -or $_.desc } |
            Select-Object -First 60 |
            ForEach-Object { "  text='$($_.text)' desc='$($_.desc)' centre=($($_.cx),$($_.cy)) cls=$($_.cls) clickable=$($_.clickable)" }
    }
    { $_ -in 'tap-label', 'tap-desc' } {
        $field = if ($Action -eq 'tap-label') { 'text' } else { 'desc' }
        $matches = @($nodes | Where-Object { $_.$field -and $_.$field -like "*$Value*" })
        if ($matches.Count -eq 0) {
            Write-Output "MISS|$Action|$Value|control not present in the fresh dump -- step abandoned (no guessed coordinates)"
            exit 3
        }
        $target = $matches[[Math]::Min($Index, $matches.Count - 1)]
        Write-Output "TAP|$Action|$Value|matched='$($target.$field)'|at=($($target.cx),$($target.cy))|of $($matches.Count) match(es)"
        Invoke-Adb @('-s', $Device, 'shell', 'input', 'tap', "$($target.cx)", "$($target.cy)") | Out-Null
        Start-Sleep -Milliseconds 700
    }
    'type' {
        # ADB text input: escape spaces for the device shell. Never used for secrets.
        $escaped = $Value -replace ' ', '%s'
        $escaped = $escaped -replace '([&|;<>()$`"''\\])', '\$1'
        Invoke-Adb @('-s', $Device, 'shell', 'input', 'text', $escaped) | Out-Null
        Start-Sleep -Milliseconds 500
    }
    'key' {
        Invoke-Adb @('-s', $Device, 'shell', 'input', 'keyevent', $Value) | Out-Null
        Start-Sleep -Milliseconds 500
    }
    'fields' {
        # Locate input fields WITHOUT guessing coordinates: any node whose class is an EditText
        # (or which is focusable+editable-adjacent) is reported with its live bounds.
        $fields = @($nodes | Where-Object { $_.cls -match 'EditText|AutoComplete' })
        if ($fields.Count -eq 0) {
            Write-Output "MISS|fields|no EditText node in the fresh dump"
        } else {
            $fields | ForEach-Object { "  FIELD|text='$($_.text)'|centre=($($_.cx),$($_.cy))|bounds=$($_.x1),$($_.y1),$($_.x2),$($_.y2)|cls=$($_.cls)" }
        }
        # also report clickable nodes so the send/stop control can be located by live bounds
        $click = @($nodes | Where-Object { $_.clickable -eq 'true' })
        "  (clickable nodes: $($click.Count))"
        $click | Select-Object -First 14 | ForEach-Object { "  CLICK|text='$($_.text)'|desc='$($_.desc)'|centre=($($_.cx),$($_.cy))" }
    }
    default { throw "unknown action: $Action" }
}
