param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$deviceDir = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Import-Module (Join-Path $deviceDir 'DeviceSmoke.psm1') -Force
$module = Get-Module DeviceSmoke

function Assert-Equal {
    param($Actual, $Expected, [string]$Message)
    if ($Actual -ne $Expected) {
        throw "$Message (expected=$Expected actual=$Actual)"
    }
}

function Assert-Null {
    param($Actual, [string]$Message)
    if ($null -ne $Actual) {
        throw "$Message (actual=$Actual)"
    }
}

function Parse-ExitInfo {
    param([string]$Text)
    return & $module {
        param($InputText)
        @(ConvertFrom-DeviceSmokeActivityExitInfo -Text $InputText -Package 'com.muyuchat.mca')
    } $Text
}

$android16 = @'
ACTIVITY MANAGER PROCESS EXIT INFO (dumpsys activity exit-info)
  package: com.muyuchat.mca
    Historical Process Exit for uid=10336
        ApplicationExitInfo #0:
          timestamp=2026-07-12 06:03:48.426 pid=13137 realUid=10336 packageUid=10336 definingUid=10336 user=0
          process=com.muyuchat.mca reason=3 (LOW_MEMORY) subreason=0 (UNKNOWN) status=0
          importance=100 pss=0.00 rss=131MB description=null state=empty trace=null
'@
$android16Entries = @(Parse-ExitInfo -Text $android16)
Assert-Equal -Actual $android16Entries.Count -Expected 1 -Message 'Android 16 exit info must produce one matching entry'
Assert-Equal -Actual $android16Entries[0].Process -Expected 'com.muyuchat.mca' -Message 'Android 16 process parsing is wrong'
Assert-Equal -Actual $android16Entries[0].Reason -Expected 'LOW_MEMORY' -Message 'Android 16 reason parsing is wrong'
Assert-Equal -Actual $android16Entries[0].Pid -Expected '13137' -Message 'Android 16 pid parsing is wrong'
Assert-Equal -Actual $android16Entries[0].Status -Expected '0' -Message 'Android 16 status parsing is wrong'

$legacy = @'
ApplicationExitInfo:
  process = com.muyuchat.mca
  reason = 5 (CRASH)
  timestamp = 2026-07-11 21:22:03.466
  pid = 8057
  status = 11
'@
$legacyEntries = @(Parse-ExitInfo -Text $legacy)
Assert-Equal -Actual $legacyEntries.Count -Expected 1 -Message 'Legacy exit info must produce one matching entry'
Assert-Equal -Actual $legacyEntries[0].Reason -Expected 'CRASH' -Message 'Legacy reason parsing is wrong'
Assert-Equal -Actual $legacyEntries[0].Timestamp -Expected '2026-07-11 21:22:03.466' -Message 'Legacy timestamp parsing is wrong'
Assert-Equal -Actual $legacyEntries[0].Pid -Expected '8057' -Message 'Legacy pid parsing is wrong'
Assert-Equal -Actual $legacyEntries[0].Status -Expected '11' -Message 'Legacy status parsing is wrong'

$baseline = [pscustomobject]@{ Available = $true; EntryCounts = @{} }
$childExit = [pscustomobject]@{
    Process = 'com.muyuchat.mca:local_image'
    Identity = 'child|2026-07-12|2001|SIGNALED'
    Reason = 'SIGNALED'
}
$mainExit = [pscustomobject]@{
    Process = 'com.muyuchat.mca'
    Identity = 'main|2026-07-12|2002|CRASH'
    Reason = 'CRASH'
}
$childOnlySnapshot = [pscustomobject]@{ Available = $true; Entries = @($childExit) }
$mainAndChildSnapshot = [pscustomobject]@{ Available = $true; Entries = @($childExit, $mainExit) }
$ignoredChild = & $module {
    param($Baseline, $Snapshot)
    Find-DeviceSmokeNewExitInfo `
        -Baseline $Baseline -Snapshot $Snapshot -RequiredProcess 'com.muyuchat.mca'
} $baseline $childOnlySnapshot
$capturedMain = & $module {
    param($Baseline, $Snapshot)
    Find-DeviceSmokeNewExitInfo `
        -Baseline $Baseline -Snapshot $Snapshot -RequiredProcess 'com.muyuchat.mca'
} $baseline $mainAndChildSnapshot

Assert-Null -Actual $ignoredChild -Message 'IgnoreChildProcessExit filtering must ignore package:local_image exits'
Assert-Equal -Actual $capturedMain.Process -Expected 'com.muyuchat.mca' -Message 'Main-process exit must still be captured'

Write-Host 'PASS: exit-info parser and child-process filtering recognize main versus worker exits.'
