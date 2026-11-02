# D24 closure candidate/trial device acceptance harness.
#
# NOT RUN in the authoring slice. This script stages a same-version closure candidate through the
# app's debug-only staging source (no hand-crafted app-private files), asks the operator to start
# the closure upgrade from the DSH console, then proves the trial stop proof and the absence of an
# orphan, and can additionally prove interrupted-trial recovery/rollback via a force-stop.
#
# It never invents a PASS: a step without captured evidence is FAIL, NOT_RUN or BLOCKED, and only
# an all-PASS run exits 0. Read scripts/dsh/D24-CLOSURE-UPGRADE-ACCEPTANCE.md first.

[CmdletBinding()]
param(
    [string]$Device = '',
    [string]$Package = 'com.muyuchat.mca',
    [string]$CandidateId = 'candidate-acceptance-01',
    [string]$OutDir = '',
    [int]$TrialTimeoutSeconds = 150,
    [switch]$SkipStage,
    [switch]$SkipTrial,
    [switch]$CrashRecovery,
    [switch]$Cleanup,
    [switch]$PreconditionsOnly
)

$ErrorActionPreference = 'Stop'
$script:Adb = $null
$script:Steps = New-Object System.Collections.ArrayList
$script:ExitCode = 0
$script:HardStop = $false

function New-Stamp {
    return (Get-Date).ToString('yyyyMMdd-HHmmss')
}

if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path 'artifacts/dsh-v2/verification' ('closure-upgrade-' + (New-Stamp))
}
$OutDir = [System.IO.Path]::GetFullPath($OutDir)
foreach ($sub in @('journals', 'ps', 'instrumentation', 'app')) {
    $null = New-Item -ItemType Directory -Force -Path (Join-Path $OutDir $sub)
}
$StepsLog = Join-Path $OutDir 'steps.log'
$CommandsLog = Join-Path $OutDir 'commands.log'
'' | Set-Content -Path $StepsLog -Encoding UTF8
'' | Set-Content -Path $CommandsLog -Encoding UTF8

function Write-Log {
    param([string]$Message)
    Add-Content -Path $StepsLog -Value ((Get-Date).ToString('HH:mm:ss') + ' ' + $Message) -Encoding UTF8
    Write-Host $Message
}

function Add-Step {
    param(
        [string]$Id,
        [string]$Status,
        [string]$Detail,
        [string[]]$Evidence = @()
    )
    $step = [pscustomobject]@{
        id       = $Id
        status   = $Status
        detail   = $Detail
        evidence = $Evidence
    }
    $null = $script:Steps.Add($step)
    $line = '{0,-10} {1,-8} {2}' -f $Id, $Status, $Detail
    Write-Log $line
    foreach ($file in $Evidence) {
        Write-Log ('           evidence: ' + $file)
    }
    if ($Status -eq 'FAIL') { $script:ExitCode = 1; $script:HardStop = $true }
    if ($Status -eq 'BLOCKED') { $script:ExitCode = 3; $script:HardStop = $true }
    if ($Status -eq 'NOT_RUN' -and $script:ExitCode -eq 0) { $script:ExitCode = 1 }
}

function Resolve-Adb {
    if (-not [string]::IsNullOrWhiteSpace($env:ADB) -and (Test-Path $env:ADB)) {
        return $env:ADB
    }
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($null -ne $command) { return $command.Source }
    $localProperties = Join-Path (Get-Location) 'local.properties'
    if (Test-Path $localProperties) {
        $sdkLine = Get-Content $localProperties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($null -ne $sdkLine) {
            $sdk = ($sdkLine -replace '^sdk\.dir=', '').Trim()
            # Java properties escape ':' and '\' in a Windows path.
            $sdk = $sdk -replace '\\\\', '\' -replace '\\:', ':' -replace '\\ ', ' '
            $candidate = Join-Path $sdk 'platform-tools/adb.exe'
            if (Test-Path $candidate) { return $candidate }
        }
    }
    return $null
}

function Invoke-Adb {
    param([string[]]$Arguments)
    $effective = @()
    if (-not [string]::IsNullOrWhiteSpace($Device)) {
        if ($Arguments.Count -eq 0 -or $Arguments[0] -ne '-s') {
            $effective += @('-s', $Device)
        }
    }
    $effective += $Arguments
    $rendered = 'adb ' + ($effective -join ' ')
    $output = & $script:Adb @effective 2>&1
    $code = $LASTEXITCODE
    $text = ($output | Out-String).Trim()
    Add-Content -Path $CommandsLog -Value ('[' + $code + '] ' + $rendered) -Encoding UTF8
    return [pscustomobject]@{ ExitCode = $code; Output = $text }
}

function Read-AppFile {
    param([string]$RelativePath)
    $result = Invoke-Adb @('shell', 'run-as', $Package, 'cat', $RelativePath)
    if ($result.ExitCode -ne 0) { return $null }
    return $result.Output
}

function Get-SelectorFingerprint {
    $text = Read-AppFile 'files/dsh/closure/selector.json'
    if ([string]::IsNullOrWhiteSpace($text)) { return '' }
    try {
        return ([string](($text | ConvertFrom-Json).fingerprint))
    } catch {
        return ''
    }
}

function Get-ProcessMatches {
    $result = Invoke-Adb @('shell', 'ps', '-A', '-o', 'PID,ARGS')
    if ($result.ExitCode -ne 0) {
        return [pscustomobject]@{ Ok = $false; Matches = @() }
    }
    $matches = @()
    foreach ($line in ($result.Output -split "`r?`n")) {
        if ($line -match 'files/dsh/payload|libdshnode\.so') {
            $trimmed = $line.Trim()
            if ($trimmed.Length -gt 0) { $matches += $trimmed }
        }
    }
    return [pscustomobject]@{ Ok = $true; Matches = $matches }
}

function Get-JournalPhase {
    param([string]$RelativePath)
    $text = Read-AppFile $RelativePath
    if ([string]::IsNullOrWhiteSpace($text)) { return $null }
    try {
        return ($text | ConvertFrom-Json)
    } catch {
        return $null
    }
}

function Invoke-StagingInstrumentation {
    param(
        [string]$Candidate,
        [string]$StageMode,
        [string]$Expect
    )
    $arguments = @(
        'shell', 'am', 'instrument', '-w',
        '-e', 'class', 'com.muyuchat.mca.dsh.DshClosureCandidateDeviceStagingTest',
        '-e', 'dshCandidateStaging', 'true',
        '-e', 'candidateId', $Candidate,
        '-e', 'stage', $StageMode,
        '-e', 'expect', $Expect,
        "$Package.test/androidx.test.runner.AndroidJUnitRunner"
    )
    $result = Invoke-Adb $arguments
    $raw = Join-Path $OutDir ('instrumentation/' + $Candidate + '-' + $StageMode + '-' + $Expect + '.txt')
    Add-Content -Path $raw -Value $result.Output -Encoding UTF8
    return [pscustomobject]@{ ExitCode = $result.ExitCode; Output = $result.Output; Evidence = $raw }
}

# ---------------------------------------------------------------------------- PRE

$script:Adb = Resolve-Adb
if ($null -eq $script:Adb -or -not (Test-Path $script:Adb)) {
    Add-Step 'PRE-01' 'BLOCKED' 'adb not found (set $env:ADB or put platform-tools on PATH)'
    $null = Add-Step 'PRE-02' 'NOT_RUN' 'no adb'
    $null = Add-Step 'PRE-03' 'NOT_RUN' 'no adb'
} else {
    if ([string]::IsNullOrWhiteSpace($Device)) {
        Add-Step 'PRE-01' 'BLOCKED' 'no -Device serial given'
        $null = Add-Step 'PRE-02' 'NOT_RUN' 'no device'
        $null = Add-Step 'PRE-03' 'NOT_RUN' 'no device'
    } else {
        $devicesFile = Join-Path $OutDir 'app/devices.txt'
        $devices = Invoke-Adb @('-s', $Device, 'get-state')
        Add-Content -Path $devicesFile -Value $devices.Output -Encoding UTF8
        if ($devices.ExitCode -ne 0 -or $devices.Output -notmatch 'device') {
            Add-Step 'PRE-01' 'BLOCKED' ('device ' + $Device + ' is not online') @($devicesFile)
            $null = Add-Step 'PRE-02' 'NOT_RUN' 'device offline'
            $null = Add-Step 'PRE-03' 'NOT_RUN' 'device offline'
        } else {
            Add-Step 'PRE-01' 'PASS' ('device ' + $Device + ' online') @($devicesFile)

            $packageFile = Join-Path $OutDir 'app/package.txt'
            $packageDump = Invoke-Adb @('-s', $Device, 'shell', 'dumpsys', 'package', $Package)
            Add-Content -Path $packageFile -Value $packageDump.Output -Encoding UTF8
            $debuggable = ($packageDump.Output -match 'DEBUGGABLE')
            $versionName = ''
            $versionMatch = [regex]::Match($packageDump.Output, 'versionName=([^\s]+)')
            if ($versionMatch.Success) { $versionName = $versionMatch.Groups[1].Value }
            $abiResult = Invoke-Adb @('-s', $Device, 'shell', 'getprop', 'ro.product.cpu.abi')
            $abi = $abiResult.Output.Trim()
            $abiFile = Join-Path $OutDir 'app/abi.txt'
            Add-Content -Path $abiFile -Value ('abi=' + $abi) -Encoding UTF8
            if (-not $debuggable) {
                Add-Step 'PRE-02' 'BLOCKED' ('package ' + $Package + ' is missing or not debuggable; candidate staging is debug-only') @($packageFile)
                $null = Add-Step 'PRE-03' 'NOT_RUN' 'non-debuggable install'
            } else {
                Add-Step 'PRE-02' 'PASS' ('package=' + $Package + ' version=' + $versionName + ' debuggable=true abi=' + $abi) @($packageFile, $abiFile)
                $assetProbe = Invoke-Adb @('-s', $Device, 'shell', 'run-as', $Package, 'ls', 'files/dsh')
                $incoming = Invoke-Adb @('-s', $Device, 'shell', 'run-as', $Package, 'ls', 'files/dsh/closure/incoming')
                $incomingFile = Join-Path $OutDir 'app/incoming-before.txt'
                Add-Content -Path $incomingFile -Value $incoming.Output -Encoding UTF8
                $beforeSelector = Get-SelectorFingerprint
                Add-Content -Path (Join-Path $OutDir 'journals/selector-before.txt') -Value $beforeSelector -Encoding UTF8
                Add-Step 'PRE-03' 'PASS' ('incoming entries captured; selector=' + ($(if ([string]::IsNullOrWhiteSpace($beforeSelector)) { 'none' } else { $beforeSelector }))) @($incomingFile)
                if (-not $SkipStage) {
                    $incomingLines = @()
                    foreach ($line in ([string]$incoming.Output -split "`r?`n")) {
                        if ($line.Trim().Length -gt 0) { $incomingLines += $line.Trim() }
                    }
                    if ($incomingLines.Count -eq 0) {
                        Add-Step 'PRE-04' 'PASS' 'the closure staging area is empty; a fresh candidate can be staged' @($incomingFile)
                    } else {
                        Add-Step 'PRE-04' 'BLOCKED' ('staged candidates already exist (' + ($incomingLines -join ',') + '); remove them before a fresh acceptance run') @($incomingFile)
                    }
                } else {
                    Add-Step 'PRE-04' 'PASS' '-SkipStage: the staging area is not required to be empty'
                }
            }
        }
    }
}

if ($PreconditionsOnly) {
    Add-Step 'POST-01' 'NOT_RUN' '-PreconditionsOnly: every later step was skipped'
    $script:ExitCode = 1
}

# ---------------------------------------------------------------------------- stage + negative

$stageReady = (-not $script:HardStop)
if ($stageReady -and -not $PreconditionsOnly) {
    if ($SkipStage) {
        Add-Step 'NEG-01' 'NOT_RUN' '-SkipStage: tampered-candidate check skipped'
        $verifyOnly = Invoke-StagingInstrumentation -Candidate $CandidateId -StageMode 'false' -Expect 'available'
        if ($verifyOnly.Output -match 'DSH_CANDIDATE_ACCEPTED' -and $verifyOnly.Output -match 'components=10') {
            Add-Step 'STAGE-01' 'PASS' 'the already-staged candidate verifies as one 10-component closure' @($verifyOnly.Evidence)
        } else {
            Add-Step 'STAGE-01' 'FAIL' 'no verified 10-component candidate is currently staged' @($verifyOnly.Evidence)
        }
        $existingCandidates = Invoke-Adb @('shell', 'run-as', $Package, 'ls', 'files/dsh/closure/incoming')
        $existingFile = Join-Path $OutDir 'app/incoming-after-verify.txt'
        Add-Content -Path $existingFile -Value $existingCandidates.Output -Encoding UTF8
        $existingManifest = Read-AppFile ('files/dsh/closure/incoming/' + $CandidateId + '/candidate.json')
        $existingManifestFile = Join-Path $OutDir 'journals/candidate.json'
        Add-Content -Path $existingManifestFile -Value $existingManifest -Encoding UTF8
        $existingComponents = ([regex]::Matches([string]$existingManifest, '"identity/')).Count
        if ($existingComponents -eq 10) {
            Add-Step 'STAGE-02' 'PASS' 'the existing candidate manifest declares 10 identity artifacts' @($existingFile, $existingManifestFile)
        } else {
            Add-Step 'STAGE-02' 'FAIL' 'the existing candidate manifest does not declare 10 identity artifacts' @($existingFile, $existingManifestFile)
        }
    } else {
        $negativeId = $CandidateId + '-neg'
        $negativeStage = Invoke-StagingInstrumentation -Candidate $negativeId -StageMode 'true' -Expect 'available'
        if ($negativeStage.Output -notmatch 'DSH_CANDIDATE_ACCEPTED') {
            Add-Step 'NEG-01' 'FAIL' 'throwaway candidate could not be staged or verified' @($negativeStage.Evidence)
        } else {
            $tamperCommand = 'run-as ' + $Package + " sh -c 'printf x >> files/dsh/closure/incoming/" +
                $negativeId + "/identity/payload.json'"
            $tamper = Invoke-Adb @('shell', $tamperCommand)
            if ($tamper.ExitCode -ne 0) {
                Add-Step 'NEG-02' 'FAIL' 'could not tamper the throwaway candidate through run-as' @($negativeStage.Evidence)
            } else {
                $negativeVerify = Invoke-StagingInstrumentation -Candidate $negativeId -StageMode 'false' -Expect 'rejected'
                if ($negativeVerify.Output -match 'DSH_CANDIDATE_REJECTED code=ARCHIVE_INVALID') {
                    Add-Step 'NEG-02' 'PASS' 'tampered candidate rejected with ARCHIVE_INVALID' @($negativeVerify.Evidence)
                } else {
                    Add-Step 'NEG-02' 'FAIL' 'tampered candidate was not rejected with ARCHIVE_INVALID' @($negativeVerify.Evidence)
                }
            }
            $cleanupNeg = Invoke-Adb @('-s', $Device, 'shell', 'run-as', $Package, 'rm', '-rf', ('files/dsh/closure/incoming/' + $negativeId))
            if ($cleanupNeg.ExitCode -eq 0) {
                Add-Step 'NEG-03' 'PASS' 'throwaway candidate removed'
            } else {
                Add-Step 'NEG-03' 'FAIL' 'throwaway candidate could not be removed'
            }
        }

        $stage = Invoke-StagingInstrumentation -Candidate $CandidateId -StageMode 'true' -Expect 'available'
        if ($stage.Output -match 'DSH_CANDIDATE_ACCEPTED' -and $stage.Output -match 'components=10') {
            Add-Step 'STAGE-01' 'PASS' ('candidate ' + $CandidateId + ' staged and verified with 10 components') @($stage.Evidence)
        } else {
            Add-Step 'STAGE-01' 'FAIL' 'candidate staging/verification did not report an accepted 10-component candidate' @($stage.Evidence)
        }

        $candidates = Invoke-Adb @('-s', $Device, 'shell', 'run-as', $Package, 'ls', 'files/dsh/closure/incoming')
        $candidateFile = Join-Path $OutDir 'app/incoming-after-stage.txt'
        Add-Content -Path $candidateFile -Value $candidates.Output -Encoding UTF8
        $manifest = Read-AppFile ('files/dsh/closure/incoming/' + $CandidateId + '/candidate.json')
        $manifestFile = Join-Path $OutDir 'journals/candidate.json'
        Add-Content -Path $manifestFile -Value $manifest -Encoding UTF8
        $componentCount = ([regex]::Matches([string]$manifest, '"identity/')).Count
        if ($candidates.ExitCode -eq 0 -and $componentCount -eq 10) {
            Add-Step 'STAGE-02' 'PASS' ('candidate manifest present with 10 declared artifacts; incoming: ' + (($candidates.Output -split "`r?`n") -join ',')) @($candidateFile, $manifestFile)
        } else {
            Add-Step 'STAGE-02' 'FAIL' 'candidate manifest or artifact count is not exactly 10' @($candidateFile, $manifestFile)
        }
    }
}

# ---------------------------------------------------------------------------- trial + stop proof

if ($script:HardStop -eq $false -and -not $PreconditionsOnly) {
    if ($SkipTrial) {
        Add-Step 'TRIAL-01' 'NOT_RUN' '-SkipTrial: operator did not run RUN_CLOSURE_UPGRADE'
        Add-Step 'TRIAL-02' 'NOT_RUN' '-SkipTrial: no stop proof captured'
        Add-Step 'TRIAL-03' 'NOT_RUN' '-SkipTrial: no orphan scan performed'
        Add-Step 'ROLL-01' 'NOT_RUN' '-SkipTrial: recovery/rollback not exercised'
    } else {
        $beforeScan = Get-ProcessMatches
        $beforeProcesses = @($beforeScan.Matches)
        Add-Content -Path (Join-Path $OutDir 'ps/before-trial.txt') -Value ('scanOk=' + $beforeScan.Ok + "`n" + ($beforeProcesses -join "`n")) -Encoding UTF8

        Write-Host ''
        Write-Host 'WAITING FOR TRIAL: open the DSH console on the device and run RUN_CLOSURE_UPGRADE now.'
        Write-Host ('Polling files/dsh/trial/active.json for up to ' + $TrialTimeoutSeconds + ' seconds.')
        Write-Host ''

        $deadline = (Get-Date).AddSeconds($TrialTimeoutSeconds)
        $liveRecord = $null
        $sawRunning = $false
        while ((Get-Date) -lt $deadline) {
            $record = Get-JournalPhase 'files/dsh/trial/active.json'
            if ($null -ne $record) {
                if ([string]$record.phase -eq 'RUNNING') {
                    $sawRunning = $true
                    $liveRecord = $record
                    break
                }
            }
            Start-Sleep -Seconds 1
        }

        if (-not $sawRunning) {
            Add-Step 'TRIAL-01' 'NOT_RUN' 'the trial journal never showed RUNNING inside the timeout (operator did not run, or the trial completed faster than the poll)' @((Join-Path $OutDir 'journals'))
            Add-Step 'TRIAL-02' 'NOT_RUN' 'no RUNNING journal to prove a stop from'
            Add-Step 'TRIAL-03' 'NOT_RUN' 'no trial pid to scan for'
            Add-Step 'ROLL-01' 'NOT_RUN' 'no RUNNING window to interrupt'
        } else {
            Add-Content -Path (Join-Path $OutDir 'journals/active-running.json') -Value ($liveRecord | ConvertTo-Json -Depth 4) -Encoding UTF8
            Add-Step 'TRIAL-01' 'PASS' ('trial journal RUNNING observed, pid=' + $liveRecord.pid) @((Join-Path $OutDir 'journals/active-running.json'))

            if ($CrashRecovery) {
                $stop = Invoke-Adb @('-s', $Device, 'shell', 'am', 'force-stop', $Package)
                Add-Content -Path (Join-Path $OutDir 'app/force-stop.txt') -Value ($stop.Output) -Encoding UTF8
                $relaunch = Invoke-Adb @('-s', $Device, 'shell', 'monkey', '-p', $Package, '-c', 'android.intent.category.LAUNCHER', '1')
                Add-Content -Path (Join-Path $OutDir 'app/relaunch.txt') -Value ($relaunch.Output) -Encoding UTF8
                Write-Host 'Recovery: reopen the DSH workspace on the device so the startup sweep runs.'
                $recoveryDeadline = (Get-Date).AddSeconds($TrialTimeoutSeconds)
                $recovered = $null
                while ((Get-Date) -lt $recoveryDeadline) {
                    $upgrade = Get-JournalPhase 'files/dsh/maintenance/upgrade.json'
                    if ($null -ne $upgrade -and [string]$upgrade.phase -eq 'ROLLED_BACK') {
                        $recovered = $upgrade
                        break
                    }
                    Start-Sleep -Seconds 2
                }
                $afterSelector = Get-SelectorFingerprint
                Add-Content -Path (Join-Path $OutDir 'journals/selector-after-recovery.txt') -Value $afterSelector -Encoding UTF8
                $active = Get-JournalPhase 'files/dsh/trial/active.json'
                Add-Content -Path (Join-Path $OutDir 'journals/active-after-recovery.json') -Value ($active | ConvertTo-Json -Depth 4) -Encoding UTF8
                if ($null -ne $recovered -and $beforeSelector -eq $afterSelector) {
                    Add-Step 'ROLL-01' 'PASS' 'interrupted trial recovered to ROLLED_BACK with an unchanged selector' @((Join-Path $OutDir 'journals/active-after-recovery.json'), (Join-Path $OutDir 'journals/selector-after-recovery.txt'))
                } else {
                    Add-Step 'ROLL-01' 'FAIL' 'interrupted trial did not recover to ROLLED_BACK with an unchanged selector' @((Join-Path $OutDir 'journals'))
                }
            } else {
                Add-Step 'ROLL-01' 'NOT_RUN' 'pass -CrashRecovery to force-stop during RUNNING and prove recovery'
            }

            # The upgrade flow itself stops the trial; wait for the durable phases to settle.
            $settleDeadline = (Get-Date).AddSeconds($TrialTimeoutSeconds)
            $settled = $null
            while ((Get-Date) -lt $settleDeadline) {
                $active = Get-JournalPhase 'files/dsh/trial/active.json'
                $upgrade = Get-JournalPhase 'files/dsh/maintenance/upgrade.json'
                if ($null -ne $active -and [string]$active.phase -eq 'STOPPED' -and $null -ne $upgrade -and
                    @('COMMITTED', 'ROLLED_BACK', 'RECOVERY_REQUIRED') -contains [string]$upgrade.phase) {
                    $settled = $upgrade
                    break
                }
                Start-Sleep -Seconds 1
            }
            $activeFinal = Get-JournalPhase 'files/dsh/trial/active.json'
            $upgradeFinal = Get-JournalPhase 'files/dsh/maintenance/upgrade.json'
            Add-Content -Path (Join-Path $OutDir 'journals/active-final.json') -Value ($activeFinal | ConvertTo-Json -Depth 4) -Encoding UTF8
            Add-Content -Path (Join-Path $OutDir 'journals/upgrade-final.json') -Value ($upgradeFinal | ConvertTo-Json -Depth 4) -Encoding UTF8
            if ($null -ne $settled) {
                Add-Step 'TRIAL-02' 'PASS' ('trial stop proof STOPPED and upgrade phase ' + $upgradeFinal.phase) @((Join-Path $OutDir 'journals/active-final.json'), (Join-Path $OutDir 'journals/upgrade-final.json'))
            } else {
                Add-Step 'TRIAL-02' 'FAIL' 'trial did not reach STOPPED with a terminal upgrade phase inside the timeout' @((Join-Path $OutDir 'journals/active-final.json'), (Join-Path $OutDir 'journals/upgrade-final.json'))
            }

            $afterScan = Get-ProcessMatches
            $afterProcesses = @($afterScan.Matches)
            Add-Content -Path (Join-Path $OutDir 'ps/after-trial.txt') -Value ('scanOk=' + $afterScan.Ok + "`n" + ($afterProcesses -join "`n")) -Encoding UTF8
            $trialPid = [string]$liveRecord.pid
            $survivor = @()
            if ($afterScan.Ok) {
                foreach ($line in $afterProcesses) {
                    if ($trialPid.Length -gt 0 -and $line -match ('^\s*' + [regex]::Escape($trialPid) + '\s')) {
                        $survivor += $line
                    }
                }
            }
            if (-not $afterScan.Ok) {
                Add-Step 'TRIAL-03' 'FAIL' 'the process scan is unavailable, so "no orphan" cannot be proven' @((Join-Path $OutDir 'ps/after-trial.txt'))
            } elseif ($survivor.Count -eq 0) {
                Add-Step 'TRIAL-03' 'PASS' ('no process carries the trial pid; payload processes before=' + $beforeProcesses.Count + ' after=' + $afterProcesses.Count + ' (a committed upgrade legitimately leaves the one production host)') @((Join-Path $OutDir 'ps/before-trial.txt'), (Join-Path $OutDir 'ps/after-trial.txt'))
            } else {
                Add-Step 'TRIAL-03' 'FAIL' ('the trial process survived its stop: ' + ($survivor -join ' | ')) @((Join-Path $OutDir 'ps/after-trial.txt'))
            }

            $selectorAfter = Get-SelectorFingerprint
            Add-Content -Path (Join-Path $OutDir 'journals/selector-after-trial.txt') -Value $selectorAfter -Encoding UTF8
            if ($null -ne $upgradeFinal -and [string]$upgradeFinal.phase -eq 'COMMITTED') {
                if ($selectorAfter -eq [string]$upgradeFinal.candidateFingerprint) {
                    Add-Step 'STAGE-03' 'PASS' 'selector advanced to the committed candidate fingerprint' @((Join-Path $OutDir 'journals/selector-after-trial.txt'))
                } else {
                    Add-Step 'STAGE-03' 'FAIL' 'COMMITTED upgrade but the selector does not carry the candidate fingerprint' @((Join-Path $OutDir 'journals/selector-after-trial.txt'))
                }
            } elseif ($null -ne $upgradeFinal -and [string]$upgradeFinal.phase -eq 'ROLLED_BACK') {
                if ($selectorAfter -eq $beforeSelector) {
                    Add-Step 'STAGE-03' 'PASS' 'trial failed and the previous selector stayed authoritative' @((Join-Path $OutDir 'journals/selector-after-trial.txt'))
                } else {
                    Add-Step 'STAGE-03' 'FAIL' 'ROLLED_BACK but the selector changed' @((Join-Path $OutDir 'journals/selector-after-trial.txt'))
                }
            } else {
                Add-Step 'STAGE-03' 'FAIL' 'upgrade did not reach COMMITTED or ROLLED_BACK' @((Join-Path $OutDir 'journals/upgrade-final.json'))
            }
        }
    }
}

# ---------------------------------------------------------------------------- cleanup + report

if ($Cleanup -and -not $PreconditionsOnly) {
    if ($null -eq $script:Adb -or [string]::IsNullOrWhiteSpace($Device)) {
        Add-Step 'CLEAN-01' 'BLOCKED' 'cannot clean up without adb and a device serial'
    } else {
        $remove = Invoke-Adb @('shell', 'run-as', $Package, 'rm', '-rf', ('files/dsh/closure/incoming/' + $CandidateId))
        if ($remove.ExitCode -eq 0) {
            Add-Step 'CLEAN-01' 'PASS' ('removed staged candidate ' + $CandidateId)
        } else {
            Add-Step 'CLEAN-01' 'FAIL' 'could not remove the staged candidate'
        }
    }
}

$failed = @($script:Steps | Where-Object { $_.status -eq 'FAIL' }).Count
$blocked = @($script:Steps | Where-Object { $_.status -eq 'BLOCKED' }).Count
$notRun = @($script:Steps | Where-Object { $_.status -eq 'NOT_RUN' }).Count
$passed = @($script:Steps | Where-Object { $_.status -eq 'PASS' }).Count

$overall = 'PASS'
if ($failed -gt 0) { $overall = 'FAIL' }
elseif ($blocked -gt 0) { $overall = 'BLOCKED'; $script:ExitCode = 3 }
elseif ($notRun -gt 0) { $overall = 'NOT_RUN'; $script:ExitCode = 1 }

$result = [ordered]@{
    overall     = $overall
    exitCode    = $script:ExitCode
    device      = $Device
    package     = $Package
    candidateId = $CandidateId
    crashRecovery = [bool]$CrashRecovery
    counts      = [ordered]@{ pass = $passed; fail = $failed; blocked = $blocked; notRun = $notRun }
    steps       = $script:Steps
    generatedAt = (Get-Date).ToString('o')
}
$result | ConvertTo-Json -Depth 8 | Set-Content -Path (Join-Path $OutDir 'result.json') -Encoding UTF8

$summary = New-Object System.Text.StringBuilder
$null = $summary.AppendLine('# D24 closure upgrade device acceptance')
$null = $summary.AppendLine('')
$null = $summary.AppendLine(('Result: **' + $overall + '** (exit ' + $script:ExitCode + ')'))
$null = $summary.AppendLine('')
$null = $summary.AppendLine('| Step | Status | Detail |')
$null = $summary.AppendLine('| --- | --- | --- |')
foreach ($step in $script:Steps) {
    $null = $summary.AppendLine('| ' + $step.id + ' | ' + $step.status + ' | ' + $step.detail + ' |')
}
$null = $summary.AppendLine('')
$null = $summary.AppendLine('PASS requires every executed step to be PASS. NOT_RUN and BLOCKED are never success.')
$summary.ToString() | Set-Content -Path (Join-Path $OutDir 'summary.md') -Encoding UTF8

Write-Host ''
Write-Host ('RESULT ' + $overall + ' exit=' + $script:ExitCode + ' evidence=' + $OutDir)
exit $script:ExitCode
