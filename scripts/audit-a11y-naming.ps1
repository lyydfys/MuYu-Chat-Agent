param(
    [string[]] $DumpPaths,
    [string] $OutDir = ''
)
<#
Accessibility naming audit over captured UI dumps (R12/pre-R12 a11y check).

What it checks, exactly:
  * every node with `clickable="true"` has a non-empty `text`, `content-desc`, or a direct child that
    has one (Compose merges semantics onto the clickable parent, so an unnamed clickable parent with a
    named child is normal and is NOT reported);
  * the same for `long-clickable="true"`.
It reports each unnamed control with its class, bounds and a short path so the finding is checkable.

What it does NOT do: TalkBack traversal, focus order, spoken order, switch-access, or any judgement
about whether a name is a GOOD name. Those need a manual pass with TalkBack enabled.
#>
$ErrorActionPreference = 'Stop'

function Test-NodeNamed {
    param($Node)
    if ([string]$Node.text -and ([string]$Node.text).Trim()) { return $true }
    if ([string]$Node.'content-desc' -and ([string]$Node.'content-desc').Trim()) { return $true }
    foreach ($child in @($Node.SelectNodes('.//node'))) {
        if ([string]$child.text -and ([string]$child.text).Trim()) { return $true }
        if ([string]$child.'content-desc' -and ([string]$child.'content-desc').Trim()) { return $true }
    }
    return $false
}

$findings = New-Object System.Collections.Generic.List[object]
$checked = 0
foreach ($path in $DumpPaths) {
    if (-not (Test-Path -LiteralPath $path)) {
        Write-Output ('skip (missing): ' + $path)
        continue
    }
    try {
        [xml] $doc = Get-Content -LiteralPath $path -Raw -Encoding UTF8
    } catch {
        Write-Output ('skip (unparsable): ' + $path)
        continue
    }
    $nodes = @($doc.SelectNodes('//node'))
    $checked += $nodes.Count
    $clickables = @($nodes | Where-Object { ([string]$_.clickable) -eq 'true' -or ([string]$_.'long-clickable') -eq 'true' })
    $unnamed = @($clickables | Where-Object { -not (Test-NodeNamed -Node $_) })
    foreach ($node in $unnamed) {
        $findings.Add([pscustomobject]@{
                file    = Split-Path $path -Leaf
                class   = [string]$node.class
                bounds  = [string]$node.bounds
                package = [string]$node.package
                resourceId = [string]$node.'resource-id'
            }) | Out-Null
    }
    Write-Output ('{0}: nodes={1} namedClickables={2} unnamed={3}' -f (Split-Path $path -Leaf), $nodes.Count,
        ($clickables.Count - $unnamed.Count), $unnamed.Count)
}

$summary = [ordered]@{
    schema = 'mca.a11y-naming-audit/1'
    checkedDumps = @($DumpPaths | Where-Object { Test-Path -LiteralPath $_ })
    totalNodes = $checked
    unnamedClickableCount = $findings.Count
    unnamedClickables = $findings.ToArray()
    limits = @(
        'Static dump audit only: TalkBack traversal, focus order and spoken order are NOT covered.',
        'A named parent with an unnamed child is normal (Compose merges semantics); only fully unnamed clickable subtrees are reported.',
        'Naming quality (is the label meaningful, is it duplicated) is not judged here.'
    )
}
$json = $summary | ConvertTo-Json -Depth 8
if ($OutDir) {
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    [IO.File]::WriteAllText((Join-Path $OutDir 'a11y-naming.json'), $json, [Text.UTF8Encoding]::new($false))
    Write-Output ('EVIDENCE: ' + (Join-Path $OutDir 'a11y-naming.json'))
} else {
    Write-Output $json
}
if ($findings.Count -gt 0) {
    Write-Output ("unnamed clickable controls: " + $findings.Count)
} else {
    Write-Output 'unnamed clickable controls: 0'
}
exit 0
