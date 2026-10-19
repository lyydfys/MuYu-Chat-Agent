<#
.SYNOPSIS
    Audits the currently visible app screen for basic accessibility/touch-target problems and
    writes machine-readable findings.

.DESCRIPTION
    Flow:
      1. resolve adb/device/package; launch the app (or reuse the foreground app with -NoLaunch);
      2. wait for a uiautomator dump that belongs to the requested package;
      3. save the raw dump as evidence and build the node tree with parent/path information;
      4. scan visible, interactive (clickable or long-clickable) nodes and report:
           - touch-target-too-small: bounds width or height < MinTargetDp (dp computed from
             `wm density`);
           - duplicate-content-desc-with-text: a node's content-desc equals its own visible text,
             or equals a sibling's visible text under the same parent;
           - missing-accessible-name: an interactive node with neither content-desc nor text
             (descendant labels are recorded in detail for triage, but do not suppress the rule);
      5. write findings.json and judge PASS/FAIL against MaxViolations.

    Honest states:
      PASS      violations <= MaxViolations (default 0);
      FAIL      violations > MaxViolations;
      NOT_RUN   dump is empty/unparseable, has no nodes, or the screen has no interactive nodes;
      BLOCKED   adb/device/package/foreground/density precondition is missing, or the dump failed.
      Exit 2 is reserved for usage/output-directory errors.

    No external PowerShell modules are used. Windows PowerShell 5.1 compatible. The file is
    intentionally saved as UTF-8 WITH BOM because it contains a Chinese UI selector
    (`打开历史` used only to label whether the audited screen is the chat screen). Keep the BOM
    when editing.

    Limits:
      - One screen snapshot only; other tabs/screens are not scanned by this run.
      - Bounds are accessibility bounds, not necessarily the exact visual/hit-test box; parent
        minimum-target enforcement is not inferred.
      - Duplicate detection is trim + ordinal case-sensitive; merged Compose semantics can yield
        false positives/negatives. Findings include node paths and descendant labels for review.
      - This is not a TalkBack runtime test, not a font-scale/large-text sweep, and not a
        substitute for manual accessibility review.

.PARAMETER DeviceSerial
    adb serial. When omitted and exactly one device is online it is used automatically; zero or
    multiple online devices are BLOCKED rather than guessed.

.PARAMETER Package
    Application id to launch/inspect. Default: com.muyuchat.mca.

.PARAMETER OutDir
    Evidence directory. Default: <repo>/artifacts/a11y/<runId>.
    An existing non-empty directory gets a run-id child so previous evidence is not overwritten.

.PARAMETER MinTargetDp
    Minimum touch-target width/height in dp. Default 48.

.PARAMETER MaxViolations
    Maximum number of findings allowed for PASS. Default 0. A single node can appear in more
    than one category (for example too-small and missing-name), and each finding counts.

.PARAMETER AppReadyTimeoutMs
    Maximum wait for the package to appear in a uiautomator dump. Default 25000.

.PARAMETER LaunchSettleMs
    Extra sleep after launching the app before waiting for the package. Default 1200.

.PARAMETER AdbPath
    Optional explicit adb path. Otherwise PATH, ANDROID_HOME/ANDROID_SDK_ROOT and the local
    Android SDK path used by this repo's tooling are tried.

.PARAMETER NoLaunch
    Do not launch the app; audit the currently foreground app.

.EXIT CODES
    0 = PASS
    1 = FAIL (more violations than allowed)
    3 = NOT_RUN / BLOCKED (no usable dump/interactive nodes, or environment precondition)
    2 = usage or output-directory error

.EXAMPLE
    .\scripts\verify-a11y.ps1 -DeviceSerial f9d98aa3

.EXAMPLE
    .\scripts\verify-a11y.ps1 -DeviceSerial f9d98aa3 -NoLaunch -MaxViolations 2

.EXAMPLE
    .\scripts\verify-a11y.ps1 -DeviceSerial f9d98aa3 -MinTargetDp 44 -OutDir artifacts/a11y/manual
#>
[CmdletBinding(PositionalBinding = $false)]
param(
    [string] $DeviceSerial = '',
    [ValidateNotNullOrEmpty()]
    [string] $Package = 'com.muyuchat.mca',
    [string] $OutDir = '',
    [ValidateRange(1.0, 200.0)]
    [double] $MinTargetDp = 48,
    [ValidateRange(0, 100000)]
    [int] $MaxViolations = 0,
    [ValidateRange(1, 120000)]
    [int] $AppReadyTimeoutMs = 25000,
    [ValidateRange(0, 30000)]
    [int] $LaunchSettleMs = 1200,
    [string] $AdbPath = '',
    [switch] $NoLaunch
)

$ErrorActionPreference = 'Continue'
$repo = Split-Path -Parent $PSScriptRoot

function Resolve-OutputDirectory {
    param([string] $Requested, [string] $RepoRoot)
    $runId = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $PID
    $target = $Requested
    if (-not $target) {
        $target = Join-Path $RepoRoot (Join-Path 'artifacts\a11y' $runId)
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
    $densityText = (((& $Adb -s $Serial shell wm density 2>$null) -join '')).Trim()
    # `wm density` may print both "Physical density: X" and "Override density: Y"; the override is
    # the effective density used for dp conversion.
    $densityMatch = [regex]::Match($densityText, 'Override density:\s*(\d+)')
    if (-not $densityMatch.Success) {
        $densityMatch = [regex]::Match($densityText, 'Physical density:\s*(\d+)')
    }
    if (-not $densityMatch.Success) {
        $densityMatch = [regex]::Match($densityText, '(\d+)')
    }
    $densityDpi = $null
    $densityScale = $null
    if ($densityMatch.Success) {
        $densityDpi = [int]$densityMatch.Groups[1].Value
        if ($densityDpi -gt 0) { $densityScale = [double]$densityDpi / 160.0 }
    }
    return [ordered]@{
        serial       = $Serial
        brand        = $brand
        model        = $model
        apiLevel     = $api
        size         = $size
        densityText  = $densityText
        densityDpi   = $densityDpi
        densityScale = $densityScale
    }
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
    & $Adb -s $Serial shell uiautomator dump /sdcard/mca-a11y-ui.xml 2>&1 | Out-Null
    $raw = & $Adb -s $Serial exec-out cat /sdcard/mca-a11y-ui.xml 2>$null
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
    $width = $right - $left
    $height = $bottom - $top
    if ($width -le 0 -or $height -le 0) { return $null }
    return [pscustomobject]@{ left = $left; top = $top; right = $right; bottom = $bottom; width = $width; height = $height }
}

function Find-DescendantLabel {
    param([System.Xml.XmlElement] $Element)
    foreach ($child in $Element.ChildNodes) {
        if ($child.NodeType -ne [System.Xml.XmlNodeType]::Element) { continue }
        if ($child.Name -ne 'node') { continue }
        $childText = ([string]$child.GetAttribute('text')).Trim()
        if ($childText) { return $childText }
        $childDesc = ([string]$child.GetAttribute('content-desc')).Trim()
        if ($childDesc) { return $childDesc }
        $nested = Find-DescendantLabel -Element $child
        if ($nested) { return $nested }
    }
    return ''
}

function Add-A11yNodeRecord {
    param(
        [System.Xml.XmlElement] $Element,
        [int] $ParentIndex,
        [string] $ParentPath
    )
    $index = $script:a11yNextIndex
    $script:a11yNextIndex = $index + 1
    $path = if ($ParentPath) { $ParentPath + '/' + $index } else { [string]$index }
    $boundsRaw = [string]$Element.GetAttribute('bounds')
    $bounds = ConvertFrom-Bounds -Value $boundsRaw
    $leftValue = 0
    $topValue = 0
    $rightValue = 0
    $bottomValue = 0
    $widthPxValue = 0
    $heightPxValue = 0
    if ($bounds) {
        $leftValue = $bounds.left
        $topValue = $bounds.top
        $rightValue = $bounds.right
        $bottomValue = $bounds.bottom
        $widthPxValue = $bounds.width
        $heightPxValue = $bounds.height
    }
    $text = ([string]$Element.GetAttribute('text')).Trim()
    $contentDesc = ([string]$Element.GetAttribute('content-desc')).Trim()
    $clickable = ([string]$Element.GetAttribute('clickable')) -eq 'true'
    $longClickable = ([string]$Element.GetAttribute('long-clickable')) -eq 'true'
    $enabled = ([string]$Element.GetAttribute('enabled')) -ne 'false'
    $visibleAttr = [string]$Element.GetAttribute('visible-to-user')
    $visible = $visibleAttr -ne 'false'
    $validBounds = ($null -ne $bounds)
    $record = [pscustomobject]@{
        nodeIndex     = $index
        parentIndex   = $ParentIndex
        path          = $path
        element       = $Element
        className     = [string]$Element.GetAttribute('class')
        resourceId    = [string]$Element.GetAttribute('resource-id')
        nodePackage   = [string]$Element.GetAttribute('package')
        text          = $text
        contentDesc   = $contentDesc
        clickable     = $clickable
        longClickable = $longClickable
        interactive   = ($clickable -or $longClickable)
        enabled       = $enabled
        visible       = $visible
        bounds        = $boundsRaw
        left          = $leftValue
        top           = $topValue
        right         = $rightValue
        bottom        = $bottomValue
        widthPx       = $widthPxValue
        heightPx      = $heightPxValue
        widthDp       = $null
        heightDp      = $null
        validBounds   = $validBounds
    }
    $script:a11yNodes.Add($record) | Out-Null
    foreach ($child in $Element.ChildNodes) {
        if ($child.NodeType -ne [System.Xml.XmlNodeType]::Element) { continue }
        if ($child.Name -ne 'node') { continue }
        Add-A11yNodeRecord -Element $child -ParentIndex $index -ParentPath $path
    }
}

function Stop-A11yRun {
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
    [Console]::Error.WriteLine('verify-a11y: cannot create output directory: ' + $_.Exception.Message)
    exit 2
}
$runId = Split-Path -Leaf $outDirectory
$dumpPath = Join-Path $outDirectory 'ui-dump.xml'
$findingsPath = Join-Path $outDirectory 'findings.json'

$script:abortStatus = $null
$script:abortReason = $null
$state = [ordered]@{
    schema      = 'mca.a11y-findings/1'
    runId       = $runId
    generatedAt = (Get-Date).ToString('o')
    repo        = $repo
    package     = $Package
    device      = $null
    app         = [ordered]@{ packageName = $Package; versionName = '' }
    tools       = [ordered]@{ adb = '' }
    parameters  = [ordered]@{
        minTargetDp      = $MinTargetDp
        maxViolations    = $MaxViolations
        appReadyTimeoutMs = $AppReadyTimeoutMs
        launchSettleMs   = $LaunchSettleMs
        noLaunch         = [bool]$NoLaunch
    }
    dump        = $null
    summary     = $null
    findings    = @()
    verdict     = 'FAIL'
    reason      = 'not started'
    exitCode    = 1
    limits      = @(
        'Only the currently visible screen is audited; other tabs/screens are not covered by this run.',
        'Bounds are accessibility bounds; parent-enforced minimum touch targets and visual hit areas are not inferred.',
        'Duplicate content-desc/text detection is trim + ordinal case-sensitive; merged Compose semantics can yield false positives/negatives.',
        'Missing-name findings record descendant labels for triage but do not suppress the node-level rule.',
        'Node records are filtered to the requested package when uiautomator exposes a package attribute; other windows (IME/system UI) are not audited.',
        'This is not a TalkBack runtime test and does not cover font-scale/large-text, focus order or announcement quality.',
        'PASS applies only to this device, package, build and screen snapshot; compare before/after on the same setup.'
    )
}

$script:a11yNodes = New-Object System.Collections.Generic.List[object]
$script:a11yNextIndex = 0

try {
    $adb = Resolve-AdbPath -Explicit $AdbPath
    if (-not $adb) {
        Stop-A11yRun -Status 'BLOCKED' -Reason 'adb was not found (PATH, ANDROID_HOME/ANDROID_SDK_ROOT, local SDK fallback)'
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
            Stop-A11yRun -Status 'BLOCKED' -Reason ("no -DeviceSerial supplied and adb reports $($online.Count) online device(s)")
        }
    }
    if (-not ($online -contains $DeviceSerial)) {
        Stop-A11yRun -Status 'BLOCKED' -Reason ("device '$DeviceSerial' is not online (adb reports: " + ($online -join ', ') + ')')
    }
    $state.device = Get-DeviceInfo -Adb $adb -Serial $DeviceSerial
    if ($null -eq $state.device.densityScale) {
        Stop-A11yRun -Status 'BLOCKED' -Reason ('could not parse density from wm density: ' + $state.device.densityText)
    }
    $state.app.versionName = Get-AppVersion -Adb $adb -Serial $DeviceSerial -AppPackage $Package

    # --- package installed -------------------------------------------------------------------
    $packagePath = Invoke-AdbCapture -Adb $adb -Arguments @('-s', $DeviceSerial, 'shell', 'pm', 'path', $Package)
    if ($packagePath.exitCode -ne 0 -or $packagePath.text -notmatch 'package:') {
        Stop-A11yRun -Status 'BLOCKED' -Reason ("package '$Package' is not installed on '$DeviceSerial'")
    }

    # --- launch / reuse foreground app -------------------------------------------------------
    if (-not $NoLaunch) {
        & $adb -s $DeviceSerial shell input keyevent KEYCODE_WAKEUP 2>&1 | Out-Null
        & $adb -s $DeviceSerial shell wm dismiss-keyguard 2>&1 | Out-Null
        $monkey = Invoke-AdbCapture -Adb $adb -Arguments @('-s', $DeviceSerial, 'shell', 'monkey', '-p', $Package, '-c', 'android.intent.category.LAUNCHER', '1')
        if ($monkey.text -notmatch 'Events injected') {
            & $adb -s $DeviceSerial shell am start -n ($Package + '/com.muyuchat.mca.MainActivity') 2>&1 | Out-Null
        }
        Start-Sleep -Milliseconds $LaunchSettleMs
    }
    $packageMarker = 'package="' + $Package + '"'
    $rawDump = $null
    $deadline = (Get-Date).AddMilliseconds($AppReadyTimeoutMs)
    while ((Get-Date) -lt $deadline) {
        $candidate = Get-UiDumpRaw -Adb $adb -Serial $DeviceSerial
        if ($candidate -and $candidate.Contains($packageMarker)) { $rawDump = $candidate; break }
        Start-Sleep -Milliseconds 600
    }
    if (-not $rawDump) {
        Stop-A11yRun -Status 'BLOCKED' -Reason (
            "no uiautomator dump containing $packageMarker was obtained; keep the app foreground or omit -NoLaunch"
        )
    }
    [System.IO.File]::WriteAllText($dumpPath, $rawDump, (New-Object System.Text.UTF8Encoding($false)))
    $dumpFile = Get-Item -LiteralPath $dumpPath
    $dumpSha = (Get-FileHash -LiteralPath $dumpPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $isChatScreen = Test-UiMarker -Raw $rawDump -Attribute 'content-desc' -Value '打开历史'
    $state.dump = [ordered]@{
        path       = $dumpPath
        bytes      = $dumpFile.Length
        sha256     = $dumpSha
        isChatScreen = $isChatScreen
    }

    # --- parse node tree ---------------------------------------------------------------------
    $document = $null
    try {
        $document = New-Object System.Xml.XmlDocument
        $document.LoadXml($rawDump)
    } catch {
        Stop-A11yRun -Status 'NOT_RUN' -Reason ('uiautomator dump is not valid XML: ' + $_.Exception.Message)
    }
    $root = $document.DocumentElement
    if (-not $root) {
        Stop-A11yRun -Status 'NOT_RUN' -Reason 'uiautomator dump has no root hierarchy element'
    }
    foreach ($child in $root.ChildNodes) {
        if ($child.NodeType -ne [System.Xml.XmlNodeType]::Element) { continue }
        if ($child.Name -ne 'node') { continue }
        Add-A11yNodeRecord -Element $child -ParentIndex -1 -ParentPath ''
    }
    if ($script:a11yNodes.Count -eq 0) {
        Stop-A11yRun -Status 'NOT_RUN' -Reason 'uiautomator dump contains no node elements'
    }

    # --- enrich dp + visibility --------------------------------------------------------------
    $densityScale = [double]$state.device.densityScale
    $minTargetPx = [double]$MinTargetDp * $densityScale
    foreach ($record in $script:a11yNodes) {
        if ($record.validBounds) {
            $record.widthDp = [math]::Round(([double]$record.widthPx / $densityScale), 2)
            $record.heightDp = [math]::Round(([double]$record.heightPx / $densityScale), 2)
        }
    }
    $visibleValid = @($script:a11yNodes | Where-Object {
        $_.visible -and $_.validBounds -and ((-not $_.nodePackage) -or ($_.nodePackage -eq $Package))
    })
    $auditedInteractive = @($visibleValid | Where-Object { $_.interactive })

    # --- findings ----------------------------------------------------------------------------
    $findings = New-Object System.Collections.Generic.List[object]
    $seenFindings = @{}
    function Add-A11yFinding {
        param(
            [string] $Category,
            $Record,
            $Detail
        )
        $key = $Category + '|' + [string]$Detail.kind + '|' + [string]$Record.nodeIndex + '|' + [string]$Detail.duplicateOfIndex
        if ($seenFindings.ContainsKey($key)) { return }
        $seenFindings[$key] = $true
        $id = 'F' + ($findings.Count + 1).ToString('000')
        $findings.Add([pscustomobject]@{
            id            = $id
            category      = $Category
            nodeIndex     = $Record.nodeIndex
            path          = $Record.path
            parentIndex   = $Record.parentIndex
            className     = $Record.className
            resourceId    = $Record.resourceId
            nodePackage   = $Record.nodePackage
            text          = $Record.text
            contentDesc   = $Record.contentDesc
            clickable     = $Record.clickable
            longClickable = $Record.longClickable
            enabled       = $Record.enabled
            bounds        = $Record.bounds
            widthPx       = $Record.widthPx
            heightPx      = $Record.heightPx
            widthDp       = $Record.widthDp
            heightDp      = $Record.heightDp
            detail        = $Detail
        }) | Out-Null
    }

    # 1) touch target too small
    foreach ($record in $auditedInteractive) {
        $shortSide = [math]::Min([double]$record.widthDp, [double]$record.heightDp)
        if ($record.widthPx -lt $minTargetPx -or $record.heightPx -lt $minTargetPx) {
            Add-A11yFinding -Category 'touch-target-too-small' -Record $record -Detail ([ordered]@{
                kind        = 'target'
                minTargetDp = $MinTargetDp
                minTargetPx = [math]::Round($minTargetPx, 2)
                widthDp     = $record.widthDp
                heightDp    = $record.heightDp
                shortSideDp = [math]::Round($shortSide, 2)
                note        = 'bounds width or height is below MinTargetDp (compared in pixels against MinTargetDp * density)'
            })
        }
    }

    # 2a) content-desc duplicates this node's own text
    $textual = @($visibleValid | Where-Object { $_.text -or $_.contentDesc })
    foreach ($record in $textual) {
        if ($record.text -and $record.contentDesc -and ($record.text -ceq $record.contentDesc)) {
            Add-A11yFinding -Category 'duplicate-content-desc-with-text' -Record $record -Detail ([ordered]@{
                kind             = 'self'
                duplicateText    = $record.contentDesc
                duplicateOfIndex = $null
                duplicateOfPath  = $null
                note             = 'content-desc equals this node text (trim + ordinal case-sensitive)'
            })
        }
    }

    # 2b) content-desc duplicates a sibling's visible text under the same parent
    $siblingsByParent = @{}
    foreach ($record in $textual) {
        $parentKey = [string]$record.parentIndex
        if (-not $siblingsByParent.ContainsKey($parentKey)) {
            $siblingsByParent[$parentKey] = New-Object System.Collections.Generic.List[object]
        }
        $siblingsByParent[$parentKey].Add($record) | Out-Null
    }
    foreach ($parentKey in $siblingsByParent.Keys) {
        $siblings = $siblingsByParent[$parentKey]
        foreach ($record in $siblings) {
            if (-not $record.contentDesc) { continue }
            foreach ($other in $siblings) {
                if ($other.nodeIndex -eq $record.nodeIndex) { continue }
                if (-not $other.text) { continue }
                if ($record.contentDesc -ceq $other.text) {
                    Add-A11yFinding -Category 'duplicate-content-desc-with-text' -Record $record -Detail ([ordered]@{
                        kind             = 'sibling'
                        duplicateText    = $record.contentDesc
                        duplicateOfIndex = $other.nodeIndex
                        duplicateOfPath  = $other.path
                        duplicateOfText  = $other.text
                        duplicateOfClass = $other.className
                        note             = 'content-desc equals a sibling visible text under the same parent'
                    })
                    break
                }
            }
        }
    }

    # 3) interactive node without content-desc or text
    foreach ($record in $auditedInteractive) {
        if ($record.text -or $record.contentDesc) { continue }
        $descendantLabel = ''
        try { $descendantLabel = Find-DescendantLabel -Element $record.element } catch { $descendantLabel = '' }
        Add-A11yFinding -Category 'missing-accessible-name' -Record $record -Detail ([ordered]@{
            kind             = 'missing-name'
            descendantLabel  = $descendantLabel
            note             = 'interactive node has neither content-desc nor text; descendantLabel is diagnostic only'
        })
    }

    $orderedFindings = @($findings | Sort-Object -Property category, nodeIndex)
    $touchCount = @($orderedFindings | Where-Object { $_.category -eq 'touch-target-too-small' }).Count
    $duplicateCount = @($orderedFindings | Where-Object { $_.category -eq 'duplicate-content-desc-with-text' }).Count
    $missingCount = @($orderedFindings | Where-Object { $_.category -eq 'missing-accessible-name' }).Count
    $violationCount = $orderedFindings.Count
    $passed = ($violationCount -le $MaxViolations)
    $verdict = if ($passed) { 'PASS' } else { 'FAIL' }

    $state.findings = $orderedFindings
    $state.summary = [ordered]@{
        nodeCount             = $script:a11yNodes.Count
        visibleNodeCount      = $visibleValid.Count
        interactiveCount      = @($script:a11yNodes | Where-Object {
            $_.interactive -and ((-not $_.nodePackage) -or ($_.nodePackage -eq $Package))
        }).Count
        auditedInteractiveCount = $auditedInteractive.Count
        violations            = $violationCount
        byCategory            = [ordered]@{
            touchTargetTooSmall = $touchCount
            duplicateContentDescWithText = $duplicateCount
            missingAccessibleName = $missingCount
        }
        minTargetDp           = $MinTargetDp
        minTargetPx           = [math]::Round($minTargetPx, 2)
        maxViolations         = $MaxViolations
        passed                = $passed
        auditValid            = $true
    }
    if ($auditedInteractive.Count -eq 0) {
        $state.verdict = 'NOT_RUN'
        $state.summary.passed = $false
        $state.summary.auditValid = $false
        $state.reason = 'screen has no visible clickable/long-clickable nodes for the requested package; nothing to audit (not a PASS)'
    } else {
        $state.verdict = $verdict
        if ($passed) {
            $state.reason = ('violations=' + $violationCount + ' <= MaxViolations=' + $MaxViolations)
        } else {
            $state.reason = ('violations=' + $violationCount + ' > MaxViolations=' + $MaxViolations +
                ' (touchTarget=' + $touchCount + ', duplicate=' + $duplicateCount + ', missingName=' + $missingCount + ')')
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

try {
    $json = $state | ConvertTo-Json -Depth 12
    [System.IO.File]::WriteAllText($findingsPath, $json, (New-Object System.Text.UTF8Encoding($false)))
} catch {
    [Console]::Error.WriteLine('verify-a11y: cannot write findings.json: ' + $_.Exception.Message)
}

Write-Output ('VERDICT: ' + $state.verdict)
Write-Output ('REASON:  ' + $state.reason)
if ($state.summary) {
    $s = $state.summary
    Write-Output ('SUMMARY: nodes=' + $s.nodeCount + ' visible=' + $s.visibleNodeCount + ' interactive=' + $s.auditedInteractiveCount +
        ' violations=' + $s.violations + ' touchTarget=' + $s.byCategory.touchTargetTooSmall +
        ' duplicate=' + $s.byCategory.duplicateContentDescWithText + ' missingName=' + $s.byCategory.missingAccessibleName)
}
if ($state.dump) { Write-Output ('DUMP:    ' + $state.dump.path + ' chatScreen=' + $state.dump.isChatScreen) }
Write-Output ('EVIDENCE: ' + $outDirectory)
exit $state.exitCode
