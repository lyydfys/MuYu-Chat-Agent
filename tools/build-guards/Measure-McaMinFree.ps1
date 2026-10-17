<#
.SYNOPSIS
  Samples free physical memory during a build and records the running minimum.

.DESCRIPTION
  Lesson recorded on 2026-10-10: a start-of-build threshold only guards against STARTING too low -
  it does not guard against a plunge DURING the build. A combined build started at 3246 MB free and
  dipped to 1060 MB, far below the 2600 MB exception floor, yet completed without OOM. That was
  luck, not margin. This sampler provides the process-side data.

  Behaviour: appends nothing, just overwrites the state file with the running minimum, and exits
  when the stop file appears or the maximum duration elapses. It NEVER aborts the build - aborting
  mid-way risks a more inconsistent state than finishing, the worktree is already covered by the
  source-stability guard, and a real OOM surfaces as hs_err.

  Output file content (single line, key=value):
    minMB=<int> samples=<int> warnHits=<int> alertHits=<int> firstMB=<int> lastMB=<int> elapsedSec=<int>

.PARAMETER OutFile
  State file to overwrite with the running minimum.

.PARAMETER StopFile
  When this file exists, the sampler stops.

.PARAMETER WarnMB / AlertMB
  Thresholds counted for reporting only. They do NOT stop the build.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$OutFile,
    [Parameter(Mandatory)][string]$StopFile,
    [int]$IntervalSeconds = 10,
    [int]$WarnMB = 1500,
    [int]$AlertMB = 1000,
    [int]$MaxMinutes = 240
)
$ErrorActionPreference = 'Continue'
$started = Get-Date
$min = [int]::MaxValue
$first = -1
$last = -1
$samples = 0
$warnHits = 0
$alertHits = 0
while ($true) {
    if (Test-Path -LiteralPath $StopFile) { break }
    if (((Get-Date) - $started).TotalMinutes -gt $MaxMinutes) { break }
    try {
        $mb = [int][math]::Round((Get-CimInstance Win32_OperatingSystem -ErrorAction Stop).FreePhysicalMemory / 1KB, 0)
    } catch { $mb = -1 }
    if ($mb -ge 0) {
        $samples++
        if ($first -lt 0) { $first = $mb }
        $last = $mb
        if ($mb -lt $min) { $min = $mb }
        if ($mb -lt $WarnMB) { $warnHits++ }
        if ($mb -lt $AlertMB) { $alertHits++ }
        $line = "minMB=$min samples=$samples warnHits=$warnHits alertHits=$alertHits firstMB=$first lastMB=$last elapsedSec=$([int]((Get-Date) - $started).TotalSeconds)"
        try { [System.IO.File]::WriteAllText($OutFile, $line) } catch { }
    }
    Start-Sleep -Seconds $IntervalSeconds
}
if ($min -eq [int]::MaxValue) { $min = -1 }
$final = "minMB=$min samples=$samples warnHits=$warnHits alertHits=$alertHits firstMB=$first lastMB=$last elapsedSec=$([int]((Get-Date) - $started).TotalSeconds)"
try { [System.IO.File]::WriteAllText($OutFile, $final) } catch { }
