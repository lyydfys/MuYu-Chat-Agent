param(
    [Parameter(Mandatory = $true)][string] $BeforeDir,
    [Parameter(Mandatory = $true)][string] $AfterDir,
    [string] $OutFile = '',
    [string] $Label = 'r12-round2',
    [double] $ChangedPixelTolerancePercent = 0.5,
    # Comparing across devices or font scales measures the configuration, not the fix; it is allowed
    # only when the caller says so explicitly.
    [switch] $Force
)
<#
R12 round-2 comparison: before/after evidence from the SAME device, same font scale, same state set.

It compares, per state name (`<state>.png` + `<state>.ui.xml`):
  * unnamed clickable controls (via the same rule as scripts/audit-a11y-naming.ps1);
  * node count and the set of content descriptions, so a rename shows up as a name-level delta;
  * the share of pixels that differ between the two screenshots (downscaled, per-channel tolerance).

It refuses to compare runs from different devices or different font scales unless -Force is passed,
because "visual diff" across two devices or two text sizes measures the config, not the fix.
#>
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

function Read-Dump {
    param([string] $Path)
    if (-not (Test-Path -LiteralPath $Path)) { return $null }
    try { [xml] $doc = Get-Content -LiteralPath $Path -Raw -Encoding UTF8 } catch { return $null }
    return @($doc.SelectNodes('//node'))
}

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

function Measure-Run {
    param([string] $Dir)
    $states = New-Object System.Collections.Generic.List[object]
    foreach ($dump in @(Get-ChildItem -LiteralPath $Dir -Filter '*.ui.xml' -File -ErrorAction SilentlyContinue)) {
        $state = $dump.BaseName -replace '\.ui$', ''
        $nodes = Read-Dump -Path $dump.FullName
        if ($null -eq $nodes) { continue }
        $clickables = @($nodes | Where-Object { ([string]$_.clickable) -eq 'true' -or ([string]$_.'long-clickable') -eq 'true' })
        $unnamed = @($clickables | Where-Object { -not (Test-NodeNamed -Node $_) })
        $descs = @($nodes | ForEach-Object { [string]$_.'content-desc' } | Where-Object { $_ } | Sort-Object -Unique)
        $states.Add([pscustomobject]@{
                state = $state
                nodeCount = @($nodes).Count
                clickables = $clickables.Count
                unnamedClickables = $unnamed.Count
                unnamedBounds = @($unnamed | ForEach-Object { [string]$_.bounds })
                descriptions = $descs
                screenshot = (Join-Path $Dir ($state + '.png'))
            }) | Out-Null
    }
    return $states
}

function Read-RunMeta {
    param([string] $Dir)
    $meta = @{ device = ''; fontScale = ''; apk = '' }
    $run = Join-Path $Dir 'RUN.json'
    if (Test-Path -LiteralPath $run) {
        try {
            $json = Get-Content -LiteralPath $run -Raw -Encoding UTF8 | ConvertFrom-Json
            $meta.device = [string]$json.device
            $meta.apk = [string]$json.apkSha256
            if ($json.PSObject.Properties.Name -contains 'fontScale') { $meta.fontScale = [string]$json.fontScale }
        } catch { }
    }
    return $meta
}

function Get-PixelDelta {
    param([string] $BeforePath, [string] $AfterPath, [int] $ChannelTolerance = 8)
    if (-not (Test-Path -LiteralPath $BeforePath) -or -not (Test-Path -LiteralPath $AfterPath)) { return $null }
    $a = [System.Drawing.Bitmap]::FromFile((Resolve-Path $BeforePath).Path)
    $b = [System.Drawing.Bitmap]::FromFile((Resolve-Path $AfterPath).Path)
    try {
        $w = [Math]::Min($a.Width, $b.Width)
        $h = [Math]::Min($a.Height, $b.Height)
        $stepX = [Math]::Max(1, [int]($w / 360))
        $stepY = [Math]::Max(1, [int]($h / 360))
        $total = 0; $changed = 0
        for ($y = 0; $y -lt $h; $y += $stepY) {
            for ($x = 0; $x -lt $w; $x += $stepX) {
                $pa = $a.GetPixel($x, $y); $pb = $b.GetPixel($x, $y)
                $total++
                if ([Math]::Abs($pa.R - $pb.R) -gt $ChannelTolerance -or
                    [Math]::Abs($pa.G - $pb.G) -gt $ChannelTolerance -or
                    [Math]::Abs($pa.B - $pb.B) -gt $ChannelTolerance) { $changed++ }
            }
        }
        if ($total -eq 0) { return $null }
        return [pscustomobject]@{ sampledPixels = $total; changedPixels = $changed; changedPercent = [Math]::Round(100.0 * $changed / $total, 3) }
    } finally {
        $a.Dispose(); $b.Dispose()
    }
}

$before = Measure-Run -Dir $BeforeDir
$after = Measure-Run -Dir $AfterDir
$beforeMeta = Read-RunMeta -Dir $BeforeDir
$afterMeta = Read-RunMeta -Dir $AfterDir

if ($beforeMeta.device -and $afterMeta.device -and $beforeMeta.device -ne $afterMeta.device -and -not $Force) {
    throw "refusing to compare different devices: '$($beforeMeta.device)' vs '$($afterMeta.device)' (pass -Force to override)"
}
if ($beforeMeta.fontScale -and $afterMeta.fontScale -and $beforeMeta.fontScale -ne $afterMeta.fontScale -and -not $Force) {
    throw "refusing to compare different font scales: '$($beforeMeta.fontScale)' vs '$($afterMeta.fontScale)' (pass -Force to override)"
}

$rows = New-Object System.Collections.Generic.List[object]
$stateNames = @(@($before | ForEach-Object { $_.state }) + @($after | ForEach-Object { $_.state }) | Sort-Object -Unique)
foreach ($name in $stateNames) {
    $b = $before | Where-Object { $_.state -eq $name } | Select-Object -First 1
    $a = $after | Where-Object { $_.state -eq $name } | Select-Object -First 1
    $pixels = $null
    if ($b -and $a) { $pixels = Get-PixelDelta -BeforePath $b.screenshot -AfterPath $a.screenshot }
    $addedDescs = @()
    $removedDescs = @()
    if ($b -and $a) {
        $addedDescs = @($a.descriptions | Where-Object { $b.descriptions -notcontains $_ })
        $removedDescs = @($b.descriptions | Where-Object { $a.descriptions -notcontains $_ })
    }
    $rows.Add([pscustomobject]@{
            state = $name
            beforeUnnamed = if ($b) { $b.unnamedClickables } else { $null }
            afterUnnamed = if ($a) { $a.unnamedClickables } else { $null }
            beforeNodes = if ($b) { $b.nodeCount } else { $null }
            afterNodes = if ($a) { $a.nodeCount } else { $null }
            changedPixelsPercent = if ($pixels) { $pixels.changedPercent } else { $null }
            addedDescriptions = $addedDescs
            removedDescriptions = $removedDescs
        }) | Out-Null
}

$regressions = @($rows | Where-Object {
        $null -ne $_.afterUnnamed -and $null -ne $_.beforeUnnamed -and $_.afterUnnamed -gt $_.beforeUnnamed
    })
$verdict = if ($regressions.Count -gt 0) { 'REGRESSION' } else { 'OK' }

$report = [ordered]@{
    schema = 'mca.r12-comparison/1'
    label = $Label
    before = [ordered]@{ dir = $BeforeDir; device = $beforeMeta.device; apk = $beforeMeta.apk; fontScale = $beforeMeta.fontScale }
    after = [ordered]@{ dir = $AfterDir; device = $afterMeta.device; apk = $afterMeta.apk; fontScale = $afterMeta.fontScale }
    tolerance = [ordered]@{ channelTolerance = 8; changedPixelTolerancePercent = $ChangedPixelTolerancePercent }
    verdict = $verdict
    regressions = $regressions
    states = $rows.ToArray()
    limits = @(
        'Pixel comparison is a sampled per-channel diff, not a perceptual or layout diff: it reports "something changed", not "the change is correct".',
        'A state missing on one side is reported with null values instead of being treated as equal.',
        'Screenshot diffs are only meaningful for the same device, same font scale and same state.'
    )
}
$json = $report | ConvertTo-Json -Depth 8
if ($OutFile) {
    $dir = Split-Path -Parent $OutFile
    if ($dir) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    [IO.File]::WriteAllText($OutFile, $json, [Text.UTF8Encoding]::new($false))
    Write-Output ('EVIDENCE: ' + $OutFile)
}
Write-Output ('comparison: verdict=' + $verdict + ' states=' + $rows.Count + ' regressions=' + $regressions.Count)
foreach ($row in $rows) {
    Write-Output ('  {0}: unnamed {1}->{2} | nodes {3}->{4} | pixels changed {5}%' -f
        $row.state, $row.beforeUnnamed, $row.afterUnnamed, $row.beforeNodes, $row.afterNodes, $row.changedPixelsPercent)
}
exit 0
