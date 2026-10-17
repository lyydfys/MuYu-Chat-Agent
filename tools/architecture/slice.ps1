# MCA slice/train tooling (PROCESS-SPEEDUP-SPEC-20261012; V2 pipeline additions 2026-10-12, spec section 7)
# Commands:
#   start  <sliceId> -Files <path...>        : evidence dir + before copies + hashes + env checks
#   fast   <sliceId>                         : stub probe (2 configs) + NDK gate + codegen (per -Tus) + chain replay + object symbol audit
#   train  <trainId>                         : gradlew --stop -> memory check -> full JVM regression -> incremental APK build
#   freeze <trainId>                         : fingerprint + staged check + pyc quarantine report
#   freeze-point <trainId> [-Files/-FileList] [-Force] [-DryRun]
#     : sample the write set into <ev>/review-snapshot + SNAPSHOT-HASHES.json (groups write_set/chain_repro/host_tests,
#       entries {sha256,bytes,snapshot}) + whole-tree <ev>/fp-freeze.json. If SNAPSHOT-HASHES.json already exists this
#       is verify-only (tamper check; live tree may advance per spec 2.3) unless -Force re-samples.
#   guard-frozen <trainId> [-AllowTreeAdvance] [-DryRun]
#     : fail-closed recheck of SNAPSHOT-HASHES.json (write_set three-way live tree/snapshot/registry; other groups
#       snapshot/registry). Default: any live-tree drift fails; -AllowTreeAdvance accepts post-freeze advancement
#       (binding = snapshot + registry) while snapshot tamper still fails.
#   route-jvm <trainId> [-Files/-FileList] [-DryRun]
#     : affected-test routing (file-name + symbol needles) over core/*/src/test + app/src/test + api/*/src/test,
#       --tests fragment + JUnit XML freshness (mtime >= latest write-set edit) + count baseline (engine 518 /
#       app 2447, no new skips) + full-run reminders (every 3rd train / Kotlin+gradle write set / before RC);
#       writes <ev>/route-jvm.json (skipped with -DryRun).
#   pipeline <trainId> [-DryRun]
#     : three-station status (A = edit/fast anchors, B = train gate, C = review/freeze) + monolith-edit gate check
#       (previous train full JVM + APK must be complete before the next batch edits the monolith, spec 2.2).
# All logs use <sliceId>.<step>.<attempt> naming; PYTHONDONTWRITEBYTECODE=1; per-run temp dirs.
# V2 commands (freeze-point/guard-frozen/route-jvm/pipeline) auto-increment attempt numbers.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateSet('start','fast','train','freeze','freeze-point','guard-frozen','route-jvm','pipeline')][string]$Command,
    [Parameter(Mandatory = $true)][string]$Id,
    [string[]]$Files = @(),
    [string]$FileList = '',
    [string[]]$Tus = @(),
    [switch]$SkipFullRegression,
    [switch]$FullApk,
    [switch]$DryRun,
    [switch]$Force,
    [switch]$AllowTreeAdvance
)

if ($FileList) { $Files = @($Files) + @($FileList -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ }) }
$ErrorActionPreference = 'Continue'
$RepoRoot = 'E:\MAC'
$Ev = Join-Path $RepoRoot ('artifacts\architecture\' + $Id)
$Logs = Join-Path $Ev 'logs'
New-Item -ItemType Directory -Force -Path $Logs | Out-Null

function Get-Sha256([string]$Path) {
    $fs = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
    try { $h = [Security.Cryptography.SHA256]::Create().ComputeHash($fs); return ([BitConverter]::ToString($h) -replace '-', '').ToLowerInvariant() }
    finally { $fs.Dispose() }
}
function Invoke-Checked([string]$Name, [scriptblock]$Body) {
    Write-Host "== $Name =="
    & $Body
    if ($LASTEXITCODE -ne 0) { Write-Host "FAST-FAIL: $Name exit=$LASTEXITCODE"; exit 1 }
}

# ---------- V2 pipeline helpers (PROCESS-SPEEDUP-SPEC-V2-PIPELINE-20261012 section 7; 2026-10-12) ----------
function Get-NextAttempt([string]$LogDir, [string]$BaseName, [string]$Step) {
    $max = 0
    foreach ($f in @(Get-ChildItem -LiteralPath $LogDir -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -like "$BaseName.$Step.*.log" })) {
        if ($f.Name -match ('^' + [regex]::Escape($BaseName + '.' + $Step + '.') + '(\d+)\.')) {
            $n = [int]$Matches[1]
            if ($n -gt $max) { $max = $n }
        }
    }
    return ($max + 1)
}
function Write-Utf8Lf([string]$Path, [string]$Text) {
    $dir = Split-Path -Parent $Path
    if ($dir -and -not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    $norm = ($Text -replace "`r`n", "`n") -replace "`r", "`n"
    [IO.File]::WriteAllText($Path, $norm, (New-Object Text.UTF8Encoding($false)))
}
function ConvertTo-JsonLf($Obj, [int]$Depth = 12) {
    return ((($Obj | ConvertTo-Json -Depth $Depth) -replace "`r`n", "`n") + "`n")
}
function Write-Both([object]$Buf, [string]$Line) {
    $Buf.Add([string]$Line) | Out-Null
    Write-Host $Line
}
function Get-ShortSha([string]$Sha) {
    if (-not $Sha) { return 'none' }
    return $Sha.Substring(0, [Math]::Min(12, $Sha.Length))
}
function Get-SliceDirs([string]$Rep, [string]$Id) {
    $arch = Join-Path $Rep 'artifacts\architecture'
    $out = New-Object System.Collections.Generic.List[string]
    $stem = $Id -replace '-train$', ''
    if (Test-Path -LiteralPath $arch) {
        foreach ($d in @(Get-ChildItem -LiteralPath $arch -Directory -ErrorAction SilentlyContinue)) {
            if ($d.Name -eq $Id) { continue }
            if ($d.Name -like ($stem + '-*')) { $out.Add($d.FullName) }
        }
    }
    return @($out.ToArray())
}
function Get-TrainManifest([string]$Ev) {
    foreach ($mf in @(Get-ChildItem -LiteralPath $Ev -File -Filter '*-MANIFEST.json' -ErrorAction SilentlyContinue)) {
        try {
            $m = Get-Content -LiteralPath $mf.FullName -Raw | ConvertFrom-Json
            if ($m.schema -eq 'mca.train-manifest/1') { return $m }
        } catch { }
    }
    return $null
}
function Get-SnapName([string]$Rel) {
    return (($Rel -replace '\\', '/') -replace '/', '__')
}
function Get-TrainWriteSet([string]$Rep, [string]$Ev, [string]$Id) {
    $paths = New-Object System.Collections.Generic.List[string]
    $src = 'none'
    $mf = Get-TrainManifest $Ev
    if ($mf) {
        $add = @()
        if ($mf.writeSetAfter) { $add += @($mf.writeSetAfter.PSObject.Properties.Name) }
        foreach ($s in @($mf.subSlices)) { if ($s -and $s.writeSet) { $add += @($s.writeSet) } }
        foreach ($a in $add) {
            $p = [string]$a
            if (-not $p) { continue }
            if (($p -replace '\\', '/').StartsWith('artifacts/')) { continue }
            if (-not $paths.Contains($p)) { $paths.Add($p) }
        }
        if ($paths.Count -gt 0) { return [pscustomobject]@{ paths = @($paths.ToArray()); source = 'train manifest (writeSetAfter + subSlices.writeSet)' } }
    }
    $reg = Join-Path $Ev 'review-snapshot\SNAPSHOT-HASHES.json'
    if (Test-Path -LiteralPath $reg) {
        try {
            $rg = Get-Content -LiteralPath $reg -Raw | ConvertFrom-Json
            foreach ($q in @($rg.write_set.PSObject.Properties.Name)) {
                $qq = [string]$q
                if ($qq -and -not $paths.Contains($qq)) { $paths.Add($qq) }
            }
            if ($paths.Count -gt 0) { return [pscustomobject]@{ paths = @($paths.ToArray()); source = 'review-snapshot/SNAPSHOT-HASHES.json write_set' } }
        } catch { }
    }
    $dirs = New-Object System.Collections.Generic.List[string]
    $dirs.Add($Ev)
    foreach ($s in @(Get-SliceDirs $Rep $Id)) { $dirs.Add($s) }
    foreach ($d in $dirs) {
        $bf = Join-Path $d 'BEFORE-FILES.json'
        if (-not (Test-Path -LiteralPath $bf)) { continue }
        try {
            foreach ($it in @(Get-Content -LiteralPath $bf -Raw | ConvertFrom-Json)) {
                $p = [string]$it.path
                if ($p -and -not $paths.Contains($p)) { $paths.Add($p) }
            }
            $src = 'BEFORE-FILES.json union (ev + slice dirs)'
        } catch { }
    }
    return [pscustomobject]@{ paths = @($paths.ToArray()); source = $src }
}
function Get-TrainLikeDirs([string]$Rep) {
    $arch = Join-Path $Rep 'artifacts\architecture'
    $out = New-Object System.Collections.Generic.List[object]
    if (-not (Test-Path -LiteralPath $arch)) { return @() }
    foreach ($d in @(Get-ChildItem -LiteralPath $arch -Directory -ErrorAction SilentlyContinue)) {
        $isTrain = $false
        $trainId = ''
        $promo = ''
        $mf = Get-TrainManifest $d.FullName
        if ($mf) { $isTrain = $true; $trainId = [string]$mf.trainId; $promo = [string]$mf.promotion }
        else {
            $bfj = Join-Path $d.FullName 'BEFORE-FILES.json'
            $revs = @(Get-ChildItem -LiteralPath $d.FullName -File -Filter 'REVIEW-VERDICT-*.md' -ErrorAction SilentlyContinue)
            if ((Test-Path -LiteralPath (Join-Path $d.FullName 'fp-freeze.json')) -and ((Test-Path -LiteralPath $bfj) -or ($revs.Count -gt 0))) { $isTrain = $true }
        }
        if (-not $isTrain) { continue }
        $hasFp = $false
        $key = $d.LastWriteTimeUtc
        $fpj = Join-Path $d.FullName 'fp-freeze.json'
        if (Test-Path -LiteralPath $fpj) { $key = (Get-Item -LiteralPath $fpj).LastWriteTimeUtc; $hasFp = $true }
        $out.Add([pscustomobject]@{ name = $d.Name; path = $d.FullName; trainId = $trainId; promotion = $promo; orderKey = $key; hasFp = $hasFp })
    }
    return @($out.ToArray() | Sort-Object -Property orderKey, name)
}
function Get-TrainGate([string]$Rep, [string]$Ev, [string]$Id) {
    $logs = Join-Path $Ev 'logs'
    $full = New-Object System.Collections.Generic.List[object]
    $apk = New-Object System.Collections.Generic.List[object]
    foreach ($f in @(Get-ChildItem -LiteralPath $logs -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -like "$Id.full.*.out.log" } | Sort-Object Name)) {
        $txt = ''
        try { $txt = [string](Get-Content -LiteralPath $f.FullName -Raw) } catch { }
        $st = 'aborted-or-partial'
        if ($txt -match 'BUILD SUCCESSFUL') { $st = 'pass' } elseif ($txt -match 'FAILED') { $st = 'fail' }
        $full.Add([pscustomobject]@{ log = $f.Name; status = $st; mtimeUtc = $f.LastWriteTimeUtc })
    }
    foreach ($f in @(Get-ChildItem -LiteralPath $logs -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -like "$Id.apk.*.out.log" } | Sort-Object Name)) {
        $txt = ''
        try { $txt = [string](Get-Content -LiteralPath $f.FullName -Raw) } catch { }
        $st = 'aborted-or-partial'
        if ($txt -match 'BUILD SUCCESSFUL') { $st = 'pass' } elseif ($txt -match 'FAILED') { $st = 'fail' }
        $apk.Add([pscustomobject]@{ log = $f.Name; status = $st; mtimeUtc = $f.LastWriteTimeUtc })
    }
    $records = New-Object System.Collections.Generic.List[object]
    $floor = $null
    if ($apk.Count -gt 0) {
        $times = @($apk.ToArray() | ForEach-Object { $_.mtimeUtc } | Sort-Object)
        $floor = $times[0].AddMinutes(-45)
    }
    $recDir = Join-Path $Rep 'artifacts\apk-build-records'
    if ($floor -and (Test-Path -LiteralPath $recDir)) {
        foreach ($rf in @(Get-ChildItem -LiteralPath $recDir -File -Filter 'debug-*.json' -ErrorAction SilentlyContinue)) {
            if ($rf.Name -notmatch '^debug-(\d{8})-(\d{6})-(\d{3})Z\.json$') { continue }
            $t = $null
            try { $t = [datetime]::ParseExact($Matches[1] + '-' + $Matches[2] + '-' + $Matches[3], 'yyyyMMdd-HHmmss-fff', [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::AssumeUniversal -bor [Globalization.DateTimeStyles]::AdjustToUniversal) } catch { continue }
            if ($t -lt $floor) { continue }
            try {
                $r = Get-Content -LiteralPath $rf.FullName -Raw | ConvertFrom-Json
                $records.Add([pscustomobject]@{ runId = [string]$r.buildRunId; status = [string]$r.status; apkSha256 = [string]$r.apkSha256; apkBytes = $r.apkBytes; completedUtc = [string]$r.completedAtUtc; failure = [string]$r.failure })
            } catch { }
        }
    }
    $fullPass = (@($full.ToArray() | Where-Object { $_.status -eq 'pass' }).Count -gt 0)
    $apkPass = (@($apk.ToArray() | Where-Object { $_.status -eq 'pass' }).Count -gt 0)
    $recPass = (@($records.ToArray() | Where-Object { $_.status -eq 'passed' }).Count -gt 0)
    return [pscustomobject]@{ full = @($full.ToArray()); apk = @($apk.ToArray()); records = @($records.ToArray()); fullPass = $fullPass; apkPass = $apkPass; recPass = $recPass; complete = ($fullPass -and $apkPass -and $recPass) }
}
function Get-JUnitCounts([string]$Dir) {
    $r = [pscustomobject]@{ dir = $Dir; exists = (Test-Path -LiteralPath $Dir); xs = 0; tests = 0; failures = 0; errors = 0; skipped = 0 }
    if (-not $r.exists) { return $r }
    foreach ($f in @(Get-ChildItem -LiteralPath $Dir -File -Filter '*.xml' -ErrorAction SilentlyContinue)) {
        $head = ''
        try {
            $sr = [IO.StreamReader]::new($f.FullName)
            try {
                $cb = New-Object char[] 4000
                $n = $sr.Read($cb, 0, 4000)
                if ($n -gt 0) { $head = -join $cb[0..($n - 1)] }
            } finally { $sr.Dispose() }
        } catch { continue }
        $m = [regex]::Match($head, '<testsuite\b[^>]*>')
        if (-not $m.Success) { continue }
        $r.xs = [int]$r.xs + 1
        foreach ($k in @('tests', 'failures', 'errors', 'skipped')) {
            $vm = [regex]::Match($m.Value, ('\b' + $k + '="(\d+)"'))
            if ($vm.Success) { $r.$k = [int]$r.$k + [int]$vm.Groups[1].Value }
        }
    }
    return $r
}

switch ($Command) {
    'start' {
        $Before = Join-Path $Ev 'before'
        New-Item -ItemType Directory -Force -Path $Before, (Join-Path $Ev 'device'), (Join-Path $Ev 'review-snapshot') | Out-Null
        $hashes = @()
        foreach ($f in $Files) {
            $full = Join-Path $RepoRoot $f
            if (-not (Test-Path -LiteralPath $full)) { Write-Host "MISSING: $f"; exit 1 }
            $dest = Join-Path $Before (($f -replace '[\\/]', '__'))
            Copy-Item -LiteralPath $full -Destination $dest -Force
            $hashes += [pscustomobject]@{ path = $f; sha256 = (Get-Sha256 $full); bytes = (Get-Item -LiteralPath $full).Length; beforeCopy = $dest }
        }
        $hashes | ConvertTo-Json -Depth 4 | Set-Content -Encoding utf8 (Join-Path $Ev 'BEFORE-FILES.json')
        $env:PYTHONDONTWRITEBYTECODE = '1'
        "FREE_MB=" + [int]((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1024)
        $adbExe = @("E:\model\android-sdk\platform-tools\adb.exe", "D:\model\android-sdk\platform-tools\adb.exe") | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
        if ($adbExe) { "ADB=" + (& $adbExe devices 2>$null | Out-String).Trim() } else { "ADB=NOT_FOUND" }
        Write-Host "START OK: $Ev (files=$($Files.Count))"
    }
    'fast' {
        $env:PYTHONDONTWRITEBYTECODE = '1'
        $ndk = 'D:\model\android-sdk\ndk\29.0.13113456'
        $clang = Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin\clang++.exe'
        $sysroot = Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64\sysroot'
        # 1) stub probes (MNN=0 combo)
        Invoke-Checked 'stub-probe' {
            & $clang -fsyntax-only -std=c++17 --target=aarch64-none-linux-android26 "--sysroot=$sysroot" `
                -DMCA_WITH_LLAMA_CPP=0 -DMCA_WITH_MNN_DIFFUSION=0 -DMCA_WITH_MNN_LLM=0 -DMCA_WITH_TOKENIZERS_CPP=0 `
                -I"$RepoRoot\core\native\src\main\cpp" -I"$RepoRoot\third_party\llama.cpp\vendor" `
                "$RepoRoot\core\native\src\main\cpp\mnn_native_engine.cpp" 2> (Join-Path $Logs "$Id.stub.1.err.log") 1> (Join-Path $Logs "$Id.stub.1.out.log")
        }
        # 1b) stub probe (MNN=0, TOKENIZERS=1 spelling) - second combination per spec 2.3 / T1 MINOR-3
        Invoke-Checked 'stub-probe-tok1' {
            & $clang -fsyntax-only -std=c++17 --target=aarch64-none-linux-android26 "--sysroot=$sysroot" `
                -DMCA_WITH_LLAMA_CPP=0 -DMCA_WITH_MNN_DIFFUSION=0 -DMCA_WITH_MNN_LLM=0 -DMCA_WITH_TOKENIZERS_CPP=1 `
                -I"$RepoRoot\core\native\src\main\cpp" -I"$RepoRoot\third_party\llama.cpp\vendor" `
                "$RepoRoot\core\native\src\main\cpp\mnn_native_engine.cpp" 2> (Join-Path $Logs "$Id.stub-tok1.1.err.log") 1> (Join-Path $Logs "$Id.stub-tok1.1.out.log")
        }
        # 1c) optional chain replay + codegen hooks (slice-local scripts)
        $evDir = $Ev
        $chain = Join-Path $evDir 'chain-repro.py'
        if (Test-Path -LiteralPath $chain) {
            Invoke-Checked 'chain-replay' { & python $chain 1> (Join-Path $Logs "$Id.chain.1.out.log") 2> (Join-Path $Logs "$Id.chain.1.err.log") }
        }
        $codegen = Join-Path $evDir 'codegen.py'
        if (Test-Path -LiteralPath $codegen) {
            Invoke-Checked 'codegen' { & python $codegen 1> (Join-Path $Logs "$Id.codegen.1.out.log") 2> (Join-Path $Logs "$Id.codegen.1.err.log") }
        }
        # 2) NDK enhanced gate
        Invoke-Checked 'ndk-gate' {
            Push-Location $RepoRoot
            & python 'tools\native\verify_native_targets_compile.py' 2> (Join-Path $Logs "$Id.ndk.1.err.log") 1> (Join-Path $Logs "$Id.ndk.1.out.log")
            $script:ndkExit = $LASTEXITCODE
            Pop-Location
            & { Get-Content (Join-Path $Logs "$Id.ndk.1.out.log") -Tail 3 }
            exit $script:ndkExit
        }
        Write-Host "FAST OK: $Id (stub + ndk)"
    }
    'train' {
        $env:PYTHONDONTWRITEBYTECODE = '1'
        if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'E:\model\toolchains\jdk-21\jdk-21.0.12.1+1' }
        if (-not $env:MCA_QNN_SDK_ROOT) { $env:MCA_QNN_SDK_ROOT = 'E:\tools\qairt-sdk\2.45.0.260326' }
        $env:GIT_CONFIG_COUNT = '1'; $env:GIT_CONFIG_KEY_0 = 'core.safecrlf'; $env:GIT_CONFIG_VALUE_0 = 'false'
        Push-Location $RepoRoot
        & .\gradlew.bat --stop 2>&1 | Out-Null
        $free = [int]((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1024)
        "FREE_MB_BEFORE_TRAIN=$free"
        for ($attempt = 0; $free -lt 2500 -and $attempt -lt 60; $attempt++) {
            Write-Host "WAITING for >=2500 MB free (current $free, attempt $attempt)"
            Start-Sleep -Seconds 20
            $free = [int]((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1024)
        }
        if ($free -lt 2500) { Write-Host "MEMORY FLOOR NOT MET ($free MB) - aborting per spec 7"; exit 4 }
        if (-not $SkipFullRegression) {
            Import-Module 'E:\MAC\tools\build-guards\McaBuildLock.psm1' -Force
            $lock = $null
            try {
                $lock = Enter-McaBuildLock -Kind test -RetrySeconds 3600 -RetryIntervalSeconds 5 -CommandLine "$Id full regression"
                & .\gradlew.bat --console=plain :core:engine:testDebugUnitTest :app:testDebugUnitTest --rerun-tasks 2> (Join-Path $Logs "$Id.full.1.err.log") 1> (Join-Path $Logs "$Id.full.1.out.log")
                Write-Host "FULL_EXIT=$LASTEXITCODE"
            } finally { if ($lock) { Exit-McaBuildLock -Lock $lock } }
        }
        Pop-Location
        Write-Host "TRAIN partial OK (APK step run separately via scripts\build.ps1) : $Id"
    }
    'freeze' {
        Push-Location $RepoRoot
        $env:GIT_CONFIG_COUNT = '1'; $env:GIT_CONFIG_KEY_0 = 'core.safecrlf'; $env:GIT_CONFIG_VALUE_0 = 'false'
        $fp = & pwsh -NoProfile -File 'tools\build-guards\McaWorktreeFingerprint.ps1' -EmitJson 2>$null | ConvertFrom-Json
        "FINGERPRINT=$($fp.fingerprint) FILES=$($fp.files)"
        "STAGED=" + (& git diff --cached --name-only | Measure-Object).Count
        $pyc = Get-ChildItem 'tools\native\__pycache__' -Force -ErrorAction SilentlyContinue
        "PYCACHE_COUNT=" + ($pyc | Measure-Object).Count
        Pop-Location
    }
    'freeze-point' {
        $env:PYTHONDONTWRITEBYTECODE = '1'
        $buf = New-Object System.Collections.Generic.List[string]
        $snap = Join-Path $Ev 'review-snapshot'
        $registry = Join-Path $snap 'SNAPSHOT-HASHES.json'
        $fpFile = Join-Path $Ev 'fp-freeze.json'
        $logFile = ''
        if (-not $DryRun) {
            $att = Get-NextAttempt $Logs $Id 'freeze-point'
            $logFile = Join-Path $Logs "$Id.freeze-point.$att.out.log"
        }
        Write-Both $buf "FREEZE-POINT train=$Id ev=$Ev dryRun=$($DryRun.IsPresent) force=$($Force.IsPresent)"
        $fpScript = Join-Path $RepoRoot 'tools\build-guards\McaWorktreeFingerprint.ps1'
        $fpText = ''
        $fpObj = $null
        try { $fpText = (& pwsh -NoProfile -File $fpScript -EmitJson 2>$null | Out-String) } catch { }
        if ($fpText) { try { $fpObj = $fpText | ConvertFrom-Json } catch { } }
        if ((-not $fpObj) -or (-not $fpObj.fingerprint)) {
            Write-Both $buf "FP-FAIL: whole-tree fingerprint unavailable"
            if ($logFile) { Write-Utf8Lf $logFile ($buf -join "`n") }
            exit 2
        }
        Write-Both $buf ("TREE-FINGERPRINT=" + $fpObj.fingerprint + " files=" + $fpObj.files)
        if ((Test-Path -LiteralPath $registry) -and (-not $Force)) {
            $reg = $null
            try { $reg = Get-Content -LiteralPath $registry -Raw | ConvertFrom-Json } catch { }
            if (-not $reg) {
                Write-Both $buf "REGISTRY-PARSE-FAIL (fail-closed): $registry"
                if ($logFile) { Write-Utf8Lf $logFile ($buf -join "`n") }
                exit 2
            }
            $total = 0; $snapBad = 0; $treeAdv = 0; $treeMissing = 0; $atFreeze = 0
            foreach ($gName in @($reg.PSObject.Properties.Name)) {
                $grp = $reg.$gName
                foreach ($prop in @($grp.PSObject.Properties)) {
                    $rel = [string]$prop.Name
                    $info = $prop.Value
                    $total++
                    $rec = [string]$info.sha256
                    $snapPath = Join-Path $snap ([string]$info.snapshot)
                    $snapSha = 'MISSING'
                    if (Test-Path -LiteralPath $snapPath) { $snapSha = Get-Sha256 $snapPath }
                    if ($snapSha -ne $rec) {
                        $snapBad++
                        Write-Both $buf ("SNAPSHOT-DRIFT [" + $gName + "] " + $rel + " snapshot=" + (Get-ShortSha $snapSha) + " recorded=" + (Get-ShortSha $rec))
                        continue
                    }
                    if ($gName -eq 'write_set') {
                        $full = Join-Path $RepoRoot ($rel -replace '/', '\')
                        if (-not (Test-Path -LiteralPath $full -PathType Leaf)) {
                            $treeMissing++
                            Write-Both $buf ("TREE-MISSING [write_set] " + $rel + " (snapshot binding intact; live file absent)")
                        } else {
                            $liveSha = Get-Sha256 $full
                            if ($liveSha -eq $rec) { $atFreeze++ } else {
                                $treeAdv++
                                Write-Both $buf ("TREE-ADVANCED [write_set] " + $rel + " live=" + (Get-ShortSha $liveSha) + " recorded=" + (Get-ShortSha $rec) + " (allowed: spec 2.3 binding = snapshot + registry)")
                            }
                        }
                    }
                }
            }
            $frozenFp = ''
            if (Test-Path -LiteralPath $fpFile) { try { $frozenFp = [string]((Get-Content -LiteralPath $fpFile -Raw | ConvertFrom-Json).fingerprint) } catch { } }
            if ($frozenFp) {
                if ($frozenFp -eq [string]$fpObj.fingerprint) { Write-Both $buf "FINGERPRINT UNCHANGED vs fp-freeze.json (live tree == freeze point)" }
                else { Write-Both $buf ("FINGERPRINT MOVED: now=" + (Get-ShortSha ([string]$fpObj.fingerprint)) + " frozen=" + (Get-ShortSha $frozenFp) + " (live tree advanced after freeze; allowed)") }
            } else { Write-Both $buf "NOTE: fp-freeze.json absent" }
            if ($snapBad -gt 0) {
                Write-Both $buf ("FREEZE-POINT VERIFY FAILED (fail-closed): snapshot drift=" + $snapBad + " of " + $total)
                if ($logFile) { Write-Utf8Lf $logFile ($buf -join "`n") }
                exit 1
            }
            Write-Both $buf ("FREEZE-POINT VERIFY OK: entries=" + $total + " snapshot==registry; tree at-freeze=" + $atFreeze + " advanced=" + $treeAdv + " missing=" + $treeMissing + "; no writes (already frozen; use -Force to re-sample)")
            if ($logFile) { Write-Utf8Lf $logFile ($buf -join "`n") }
            exit 0
        }
        $wsPaths = New-Object System.Collections.Generic.List[string]
        $wsSource = ''
        if ($Files.Count -gt 0) {
            foreach ($f in $Files) {
                $p = ([string]$f).Trim() -replace '\\', '/'
                if ($p -and -not $wsPaths.Contains($p)) { $wsPaths.Add($p) }
            }
            $wsSource = 'explicit -Files/-FileList'
        } else {
            $ws = Get-TrainWriteSet $RepoRoot $Ev $Id
            foreach ($p in @($ws.paths)) { if (-not $wsPaths.Contains([string]$p)) { $wsPaths.Add([string]$p) } }
            $wsSource = $ws.source
        }
        if ($wsPaths.Count -eq 0) {
            Write-Both $buf "NO-WRITE-SET: pass -Files/-FileList or provide train manifest / BEFORE-FILES.json"
            if ($logFile) { Write-Utf8Lf $logFile ($buf -join "`n") }
            exit 2
        }
        Write-Both $buf ("WRITE-SET (" + $wsSource + "): " + $wsPaths.Count + " files")
        $missing = New-Object System.Collections.Generic.List[string]
        foreach ($p in $wsPaths) {
            $full = Join-Path $RepoRoot ($p -replace '/', '\')
            if (-not (Test-Path -LiteralPath $full -PathType Leaf)) { $missing.Add($p) }
        }
        if ($missing.Count -gt 0) {
            foreach ($m in $missing) { Write-Both $buf ("MISSING: " + $m) }
            Write-Both $buf ("FREEZE-POINT FAILED (fail-closed): " + $missing.Count + " write-set files missing in live tree")
            if ($logFile) { Write-Utf8Lf $logFile ($buf -join "`n") }
            exit 1
        }
        $chainPatterns = @('*-CHAIN-REPRO.json', 'edit*.py', 'chain-repro.py', 'codegen.py')
        $chainItems = New-Object System.Collections.Generic.List[object]
        $seen = New-Object System.Collections.Generic.HashSet[string]
        $stemName = $Id -replace '-train$', ''
        $itemDirs = New-Object System.Collections.Generic.List[object]
        $itemDirs.Add([pscustomobject]@{ dir = $Ev; tag = '' })
        foreach ($sd in @(Get-SliceDirs $RepoRoot $Id)) {
            $tag = (Split-Path -Leaf $sd) -replace ([regex]::Escape($stemName) + '-'), ''
            $itemDirs.Add([pscustomobject]@{ dir = $sd; tag = $tag })
        }
        foreach ($di in $itemDirs) {
            foreach ($pat in $chainPatterns) {
                foreach ($f in @(Get-ChildItem -LiteralPath $di.dir -File -Filter $pat -ErrorAction SilentlyContinue)) {
                    if (-not $seen.Add($f.FullName)) { continue }
                    $rel = $f.FullName.Substring($RepoRoot.Length).TrimStart('\') -replace '\\', '/'
                    $sn = $f.Name
                    if ($di.tag) { $sn = $di.tag + '__' + $f.Name }
                    $chainItems.Add([pscustomobject]@{ rel = $rel; full = $f.FullName; snap = $sn })
                }
            }
        }
        $hostItems = New-Object System.Collections.Generic.List[object]
        $htDir = Join-Path $Ev 'host-tests'
        if (Test-Path -LiteralPath $htDir) {
            foreach ($f in @(Get-ChildItem -LiteralPath $htDir -File -Filter '*.log' -ErrorAction SilentlyContinue)) {
                $rel = $f.FullName.Substring($RepoRoot.Length).TrimStart('\') -replace '\\', '/'
                $hostItems.Add([pscustomobject]@{ rel = $rel; full = $f.FullName; snap = $f.Name })
            }
        }
        Write-Both $buf ("CHAIN-REPRO set=" + $chainItems.Count + " files; HOST-TESTS set=" + $hostItems.Count + " files")
        if ($DryRun) {
            foreach ($p in $wsPaths) {
                $full = Join-Path $RepoRoot ($p -replace '/', '\')
                Write-Both $buf ("  [dry] write_set " + $p + " -> " + (Get-SnapName $p) + " sha256=" + (Get-Sha256 $full) + " bytes=" + (Get-Item -LiteralPath $full).Length)
            }
            foreach ($ci in @($chainItems.ToArray() | Sort-Object rel)) { Write-Both $buf ("  [dry] chain_repro " + $ci.rel + " -> " + $ci.snap) }
            foreach ($hi in @($hostItems.ToArray() | Sort-Object rel)) { Write-Both $buf ("  [dry] host_tests " + $hi.rel + " -> " + $hi.snap) }
            Write-Both $buf ("DRY-RUN OK: nothing written; planned tree fingerprint=" + $fpObj.fingerprint + " snapshot dir=" + $snap)
            exit 0
        }
        New-Item -ItemType Directory -Force -Path $snap | Out-Null
        $manifest = [ordered]@{ write_set = [ordered]@{}; chain_repro = [ordered]@{}; host_tests = [ordered]@{} }
        foreach ($p in $wsPaths) {
            $full = Join-Path $RepoRoot ($p -replace '/', '\')
            $sn = Get-SnapName $p
            Copy-Item -LiteralPath $full -Destination (Join-Path $snap $sn) -Force
            $dst = Join-Path $snap $sn
            $manifest.write_set[$p] = [pscustomobject]@{ sha256 = (Get-Sha256 $dst); bytes = (Get-Item -LiteralPath $dst).Length; snapshot = $sn }
        }
        foreach ($ci in @($chainItems.ToArray() | Sort-Object rel)) {
            Copy-Item -LiteralPath $ci.full -Destination (Join-Path $snap $ci.snap) -Force
            $dst = Join-Path $snap $ci.snap
            $manifest.chain_repro[$ci.rel] = [pscustomobject]@{ sha256 = (Get-Sha256 $dst); bytes = (Get-Item -LiteralPath $dst).Length; snapshot = $ci.snap }
        }
        foreach ($hi in @($hostItems.ToArray() | Sort-Object rel)) {
            Copy-Item -LiteralPath $hi.full -Destination (Join-Path $snap $hi.snap) -Force
            $dst = Join-Path $snap $hi.snap
            $manifest.host_tests[$hi.rel] = [pscustomobject]@{ sha256 = (Get-Sha256 $dst); bytes = (Get-Item -LiteralPath $dst).Length; snapshot = $hi.snap }
        }
        Write-Utf8Lf $registry (ConvertTo-JsonLf $manifest 12)
        Write-Utf8Lf $fpFile ($fpText.Trim() + "`n")
        $selfBad = 0
        foreach ($p in $wsPaths) { $dst = Join-Path $snap (Get-SnapName $p); if ((Get-Sha256 $dst) -ne [string]$manifest.write_set[$p].sha256) { $selfBad++ } }
        foreach ($ci in @($chainItems.ToArray() | Sort-Object rel)) { $dst = Join-Path $snap $ci.snap; if ((Get-Sha256 $dst) -ne [string]$manifest.chain_repro[$ci.rel].sha256) { $selfBad++ } }
        foreach ($hi in @($hostItems.ToArray() | Sort-Object rel)) { $dst = Join-Path $snap $hi.snap; if ((Get-Sha256 $dst) -ne [string]$manifest.host_tests[$hi.rel].sha256) { $selfBad++ } }
        if ($selfBad -gt 0) {
            Write-Both $buf ("FREEZE-POINT SELF-CHECK FAILED: mismatches=" + $selfBad)
            if ($logFile) { Write-Utf8Lf $logFile ($buf -join "`n") }
            exit 1
        }
        $expected = New-Object System.Collections.Generic.HashSet[string]
        foreach ($p in $wsPaths) { [void]$expected.Add((Get-SnapName $p)) }
        foreach ($ci in $chainItems) { [void]$expected.Add($ci.snap) }
        foreach ($hi in $hostItems) { [void]$expected.Add($hi.snap) }
        $extra = 0
        foreach ($f in @(Get-ChildItem -LiteralPath $snap -File -ErrorAction SilentlyContinue)) {
            if ($f.Name -eq 'SNAPSHOT-HASHES.json') { continue }
            if (-not $expected.Contains($f.Name)) { $extra++ }
        }
        if ($extra -gt 0) { Write-Both $buf ("NOTE: " + $extra + " pre-existing snapshot files not referenced by the new manifest (left untouched)") }
        Write-Both $buf ("FREEZE-POINT OK: write_set=" + $wsPaths.Count + " chain_repro=" + $chainItems.Count + " host_tests=" + $hostItems.Count + " fp=" + $fpObj.fingerprint)
        Write-Both $buf ("  snapshot=" + $snap)
        Write-Both $buf ("  registry=" + $registry)
        Write-Both $buf ("  fingerprint=" + $fpFile)
        if ($logFile) { Write-Utf8Lf $logFile ($buf -join "`n") }
        exit 0
    }
    'guard-frozen' {
        $env:PYTHONDONTWRITEBYTECODE = '1'
        $buf = New-Object System.Collections.Generic.List[string]
        $snap = Join-Path $Ev 'review-snapshot'
        $registry = Join-Path $snap 'SNAPSHOT-HASHES.json'
        $logFile = ''
        if (-not $DryRun) {
            $att = Get-NextAttempt $Logs $Id 'guard-frozen'
            $logFile = Join-Path $Logs "$Id.guard-frozen.$att.out.log"
        }
        Write-Both $buf ("GUARD-FROZEN train=$Id allowTreeAdvance=" + $AllowTreeAdvance.IsPresent + " registry=" + $registry)
        if (-not (Test-Path -LiteralPath $registry)) {
            Write-Both $buf "REGISTRY-MISSING (fail-closed)"
            if ($logFile) { Write-Utf8Lf $logFile ($buf -join "`n") }
            exit 2
        }
        $reg = $null
        try { $reg = Get-Content -LiteralPath $registry -Raw | ConvertFrom-Json } catch { }
        if (-not $reg) {
            Write-Both $buf "REGISTRY-PARSE-FAIL (fail-closed)"
            if ($logFile) { Write-Utf8Lf $logFile ($buf -join "`n") }
            exit 2
        }
        $checked = 0; $fail = 0; $snapBad = 0; $treeAdv = 0; $treeMissing = 0; $atFreeze = 0
        foreach ($gName in @($reg.PSObject.Properties.Name)) {
            $gCount = 0
            foreach ($prop in @($reg.$gName.PSObject.Properties)) {
                $rel = [string]$prop.Name
                $info = $prop.Value
                $checked++
                $gCount++
                $rec = [string]$info.sha256
                $snapPath = Join-Path $snap ([string]$info.snapshot)
                $snapSha = 'MISSING'
                if (Test-Path -LiteralPath $snapPath) { $snapSha = Get-Sha256 $snapPath }
                if ($snapSha -ne $rec) {
                    $fail++
                    $snapBad++
                    Write-Both $buf ("FAIL [SNAPSHOT-TAMPER] [" + $gName + "] " + $rel + " snapshot=" + (Get-ShortSha $snapSha) + " recorded=" + (Get-ShortSha $rec))
                    continue
                }
                if ($gName -eq 'write_set') {
                    $full = Join-Path $RepoRoot ($rel -replace '/', '\')
                    if (-not (Test-Path -LiteralPath $full -PathType Leaf)) {
                        $treeMissing++
                        if ($AllowTreeAdvance) { Write-Both $buf ("ACCEPT [TREE-MISSING] [write_set] " + $rel + " (live file absent; snapshot binding intact)") }
                        else {
                            $fail++
                            Write-Both $buf ("FAIL [TREE-MISSING] [write_set] " + $rel + " (live file absent; re-run with -AllowTreeAdvance if the tree legitimately advanced)")
                        }
                    } else {
                        $liveSha = Get-Sha256 $full
                        if ($liveSha -eq $rec) { $atFreeze++ } else {
                            $treeAdv++
                            if ($AllowTreeAdvance) { Write-Both $buf ("ACCEPT [TREE-ADVANCED] [write_set] " + $rel + " live=" + (Get-ShortSha $liveSha) + " recorded=" + (Get-ShortSha $rec) + " (spec 2.3 binding = snapshot + registry)") }
                            else {
                                $fail++
                                Write-Both $buf ("FAIL [TREE-ADVANCED] [write_set] " + $rel + " live=" + (Get-ShortSha $liveSha) + " recorded=" + (Get-ShortSha $rec) + " (live tree no longer equals freeze point; re-run with -AllowTreeAdvance to accept advancement per spec 2.3)")
                            }
                        }
                    }
                }
            }
            Write-Both $buf ("GROUP " + $gName + ": " + $gCount + " entries checked")
        }
        Write-Both $buf ("TOTALS checked=" + $checked + " at-freeze=" + $atFreeze + " tree-advanced=" + $treeAdv + " tree-missing=" + $treeMissing + " snapshot-tamper=" + $snapBad + " failures=" + $fail)
        if ($fail -gt 0) {
            Write-Both $buf "GUARD-FROZEN FAIL (fail-closed, non-zero exit)"
            if ($logFile) { Write-Utf8Lf $logFile ($buf -join "`n") }
            exit 1
        }
        if (($treeAdv -gt 0) -or ($treeMissing -gt 0)) {
            Write-Both $buf ("GUARD-FROZEN OK (snapshot==registry for all " + $checked + " entries; tree advancement accepted: advanced=" + $treeAdv + " missing=" + $treeMissing + ")")
        } else {
            Write-Both $buf ("GUARD-FROZEN OK (write-set tree==snapshot==registry for all " + $checked + " entries)")
        }
        if ($logFile) { Write-Utf8Lf $logFile ($buf -join "`n") }
        exit 0
    }
    'route-jvm' {
        $env:PYTHONDONTWRITEBYTECODE = '1'
        $buf = New-Object System.Collections.Generic.List[string]
        $outJson = Join-Path $Ev 'route-jvm.json'
        $logFile = ''
        if (-not $DryRun) {
            $att = Get-NextAttempt $Logs $Id 'route-jvm'
            $logFile = Join-Path $Logs "$Id.route-jvm.$att.out.log"
        }
        Write-Both $buf "ROUTE-JVM train=$Id dryRun=$($DryRun.IsPresent)"
        $wsPaths = New-Object System.Collections.Generic.List[string]
        $wsSource = ''
        if ($Files.Count -gt 0) {
            foreach ($f in $Files) {
                $p = ([string]$f).Trim() -replace '\\', '/'
                if ($p -and -not $wsPaths.Contains($p)) { $wsPaths.Add($p) }
            }
            $wsSource = 'explicit -Files/-FileList'
        } else {
            $ws = Get-TrainWriteSet $RepoRoot $Ev $Id
            foreach ($p in @($ws.paths)) { if (-not $wsPaths.Contains([string]$p)) { $wsPaths.Add([string]$p) } }
            $wsSource = $ws.source
        }
        if ($wsPaths.Count -eq 0) {
            Write-Both $buf "NO-WRITE-SET: pass -Files/-FileList or provide train manifest / BEFORE-FILES.json / SNAPSHOT-HASHES.json"
            if ($logFile) { Write-Utf8Lf $logFile ($buf -join "`n") }
            exit 2
        }
        $wsExisting = New-Object System.Collections.Generic.List[string]
        $wsMissing = New-Object System.Collections.Generic.List[string]
        $kotlinWsFiles = New-Object System.Collections.Generic.List[string]
        $latestSrcUtc = $null
        foreach ($p in $wsPaths) {
            $full = Join-Path $RepoRoot ($p -replace '/', '\')
            if (-not (Test-Path -LiteralPath $full -PathType Leaf)) { $wsMissing.Add($p); continue }
            $wsExisting.Add($p)
            $t = (Get-Item -LiteralPath $full).LastWriteTimeUtc
            if ((-not $latestSrcUtc) -or ($t -gt $latestSrcUtc)) { $latestSrcUtc = $t }
            $ext = [IO.Path]::GetExtension($full).ToLowerInvariant()
            if (($ext -eq '.kt') -or ($ext -eq '.java') -or ($ext -eq '.gradle') -or ($ext -eq '.kts') -or ($p -replace '\\', '/').StartsWith('gradle/')) { $kotlinWsFiles.Add($p) }
        }
        Write-Both $buf ("WRITE-SET (" + $wsSource + "): " + $wsPaths.Count + " files; existing=" + $wsExisting.Count + " missing=" + $wsMissing.Count)
        foreach ($m in $wsMissing) { Write-Both $buf ("  MISSING: " + $m) }
        $needles = New-Object System.Collections.Generic.HashSet[string]
        $needleSrc = @{}
        $noise = New-Object System.Collections.Generic.HashSet[string]
        foreach ($nw in @('size_t','ssize_t','uint8_t','uint16_t','uint32_t','uint64_t','int8_t','int16_t','int32_t','int64_t','uintptr_t','intptr_t','ptrdiff_t','numeric_limits','runtime_error','logic_error','invalid_argument','out_of_range','length_error','system_error','domain_error','overflow_error','underflow_error','bad_alloc','bad_cast','lock_guard','unique_lock','shared_ptr','unique_ptr','weak_ptr','make_unique','make_shared','string_view','error_code','error_category','condition_variable','initializer_list','static_assert','third_party','constexpr','nullptr_t','stringstream','istringstream','ostringstream','ifstream','ofstream','filesystem','duration_cast','steady_clock','system_clock','to_string','memcpy','memset','strlen','strcmp','snprintf','fprintf','malloc','calloc','realloc','abort','mutex','thread','atomic','chrono','shared_mutex','recursive_mutex','timed_mutex','reinterpret_cast','static_cast','dynamic_cast','const_cast')) { [void]$noise.Add($nw) }
        foreach ($p in $wsExisting) {
            $full = Join-Path $RepoRoot ($p -replace '/', '\')
            $ext = [IO.Path]::GetExtension($full).ToLowerInvariant()
            $stem = [IO.Path]::GetFileNameWithoutExtension($full)
            $text = ''
            try { $text = [IO.File]::ReadAllText($full) } catch { continue }
            $local = New-Object System.Collections.Generic.List[string]
            if (($stem.Length -ge 4) -and ($stem -ne 'CMakeLists')) { $local.Add($stem) }
            if (($ext -eq '.kt') -or ($ext -eq '.java')) {
                foreach ($m in [regex]::Matches($text, '"([^"\r\n]{1,200})"')) {
                    foreach ($t in [regex]::Matches($m.Groups[1].Value, '[A-Za-z_][A-Za-z0-9_]{5,}')) {
                        if ($t.Value.Contains('_')) { $local.Add($t.Value) }
                    }
                }
                foreach ($m in [regex]::Matches($text, '\b(?:mca|mnn|qnn|g)_[A-Za-z0-9_]{3,}\b')) { $local.Add($m.Value) }
                foreach ($m in [regex]::Matches($text, '\b(k[A-Z][A-Za-z0-9_]{4,})\b')) { $local.Add($m.Value) }
            } elseif (($ext -eq '.cpp') -or ($ext -eq '.cc') -or ($ext -eq '.cxx') -or ($ext -eq '.hpp') -or ($ext -eq '.h') -or ($ext -eq '.c')) {
                foreach ($m in [regex]::Matches($text, '\b(?:mca|mnn|qnn|g)_[A-Za-z0-9_]{3,}\b')) { $local.Add($m.Value) }
                foreach ($m in [regex]::Matches($text, '\b(k[A-Z][A-Za-z0-9_]{4,})\b')) { $local.Add($m.Value) }
                foreach ($m in [regex]::Matches($text, '(?m)^[ \t]*[A-Za-z_][A-Za-z0-9_:<>,\.\*&\s\[\]]{0,80}?\b([a-z_][a-z0-9_]{6,})\s*\(')) { $local.Add($m.Groups[1].Value) }
            }
            foreach ($t in $local) {
                if ($t.Length -lt 6) { continue }
                if ($noise.Contains($t)) { continue }
                if (-not ($t.Contains('_') -or ([regex]::Matches($t, '[A-Z]').Count -ge 2))) { continue }
                if ($needles.Add($t)) { $needleSrc[$t] = $p }
            }
        }
        Write-Both $buf ("NEEDLES: " + $needles.Count + " (file stems + symbol names extracted from write-set files)")
        $testRoots = New-Object System.Collections.Generic.List[object]
        $coreDir = Join-Path $RepoRoot 'core'
        if (Test-Path -LiteralPath $coreDir) {
            foreach ($cd in @(Get-ChildItem -LiteralPath $coreDir -Directory -ErrorAction SilentlyContinue | Sort-Object Name)) {
                $r = Join-Path $cd.FullName 'src\test'
                if (Test-Path -LiteralPath $r) { $testRoots.Add([pscustomobject]@{ scan = $r; module = ('core/' + $cd.Name); modulePath = (':core:' + $cd.Name); moduleRoot = $cd.FullName }) }
            }
        }
        $appTest = Join-Path $RepoRoot 'app\src\test'
        if (Test-Path -LiteralPath $appTest) { $testRoots.Add([pscustomobject]@{ scan = $appTest; module = 'app'; modulePath = ':app'; moduleRoot = (Join-Path $RepoRoot 'app') }) }
        $apiDir = Join-Path $RepoRoot 'api'
        if (Test-Path -LiteralPath $apiDir) {
            foreach ($ad in @(Get-ChildItem -LiteralPath $apiDir -Directory -ErrorAction SilentlyContinue | Sort-Object Name)) {
                $r = Join-Path $ad.FullName 'src\test'
                if (Test-Path -LiteralPath $r) { $testRoots.Add([pscustomobject]@{ scan = $r; module = ('api/' + $ad.Name); modulePath = (':api:' + $ad.Name); moduleRoot = $ad.FullName }) }
            }
        }
        $wsRelSet = New-Object System.Collections.Generic.HashSet[string]
        foreach ($p in $wsExisting) { [void]$wsRelSet.Add($p) }
        $affected = New-Object System.Collections.Generic.List[object]
        $scanned = 0
        $scanExts = @('.kt', '.java', '.cpp', '.hpp', '.h', '.cc', '.cxx')
        foreach ($tr in $testRoots) {
            foreach ($f in @(Get-ChildItem -LiteralPath $tr.scan -Recurse -File -ErrorAction SilentlyContinue)) {
                if ($scanExts -notcontains $f.Extension) { continue }
                $scanned++
                if ($f.Length -gt 8MB) { continue }
                $text = ''
                try { $text = [IO.File]::ReadAllText($f.FullName) } catch { continue }
                $rel = $f.FullName.Substring($RepoRoot.Length).TrimStart('\') -replace '\\', '/'
                $tokens = New-Object System.Collections.Generic.HashSet[string]
                foreach ($m in [regex]::Matches($text, '[A-Za-z_][A-Za-z0-9_]*')) { [void]$tokens.Add($m.Value) }
                $hits = New-Object System.Collections.Generic.List[string]
                foreach ($n in $needles) {
                    if ($tokens.Contains($n)) {
                        $hits.Add($n)
                        if ($hits.Count -ge 50) { break }
                    }
                }
                $isWsMember = $wsRelSet.Contains($rel)
                if (($hits.Count -eq 0) -and (-not $isWsMember)) { continue }
                $kind = 'native'
                if (($f.Extension -eq '.kt') -or ($f.Extension -eq '.java')) { $kind = 'gradle' }
                $fqcn = ''
                if ($kind -eq 'gradle') {
                    $fqcn = [IO.Path]::GetFileNameWithoutExtension($f.Name)
                    $pm = [regex]::Match($text, '(?m)^\s*package\s+([A-Za-z0-9_\.]+)')
                    if ($pm.Success) { $fqcn = $pm.Groups[1].Value + '.' + $fqcn }
                }
                $affected.Add([pscustomobject]@{ rel = $rel; module = $tr.module; modulePath = $tr.modulePath; moduleRoot = $tr.moduleRoot; kind = $kind; fqcn = $fqcn; wsMember = $isWsMember; hits = @($hits.ToArray() | Sort-Object) })
            }
        }
        Write-Both $buf ("SCAN: " + $scanned + " test files across " + $testRoots.Count + " roots (core/*/src/test, app/src/test, api/*/src/test) -> affected=" + $affected.Count)
        $freshItems = New-Object System.Collections.Generic.List[object]
        $freshOk = 0; $staleXml = 0; $missingXml = 0
        foreach ($a in $affected) {
            if ($a.kind -ne 'gradle') { continue }
            $xml = Join-Path $a.moduleRoot ('build\test-results\testDebugUnitTest\TEST-' + $a.fqcn + '.xml')
            $xmlExists = Test-Path -LiteralPath $xml
            $isFresh = $false
            $xmlMtime = ''
            if ($xmlExists) {
                $mt = (Get-Item -LiteralPath $xml).LastWriteTimeUtc
                $xmlMtime = $mt.ToString('o')
                if ($latestSrcUtc -and ($mt -ge $latestSrcUtc)) { $isFresh = $true }
            }
            if (-not $xmlExists) { $missingXml++ } elseif ($isFresh) { $freshOk++ } else { $staleXml++ }
            $xmlRel = ''
            if ($xmlExists) { $xmlRel = $xml.Replace($RepoRoot, '').TrimStart('\').Replace('\', '/') }
            $freshItems.Add([pscustomobject]@{ fqcn = $a.fqcn; module = $a.module; xmlRel = $xmlRel; xmlExists = $xmlExists; xmlMtimeUtc = $xmlMtime; fresh = $isFresh })
        }
        $engineCounts = Get-JUnitCounts (Join-Path $RepoRoot 'core\engine\build\test-results\testDebugUnitTest')
        $appCounts = Get-JUnitCounts (Join-Path $RepoRoot 'app\build\test-results\testDebugUnitTest')
        $engineOk = ($engineCounts.exists -and ($engineCounts.tests -ge 518) -and ($engineCounts.failures -eq 0) -and ($engineCounts.errors -eq 0) -and ($engineCounts.skipped -le 2))
        $appOk = ($appCounts.exists -and ($appCounts.tests -ge 2447) -and ($appCounts.failures -eq 0) -and ($appCounts.errors -eq 0) -and ($appCounts.skipped -le 8))
        $trains = @(Get-TrainLikeDirs $RepoRoot)
        $prevTrainName = ''
        for ($i = 0; $i -lt $trains.Count; $i++) {
            if (($trains[$i].path -eq $Ev) -and ($i -gt 0)) { $prevTrainName = $trains[$i - 1].name }
        }
        $fpTrains = @($trains | Where-Object { $_.hasFp })
        $seqStr = 'n/a'
        if ($fpTrains.Count -gt 0) { $seqStr = (($fpTrains | ForEach-Object { $_.name }) -join ' < ') }
        $ordinal = $null
        for ($i = 0; $i -lt $fpTrains.Count; $i++) { if ($fpTrains[$i].path -eq $Ev) { $ordinal = $i + 1 } }
        $routeEligible = $true
        foreach ($p in $wsExisting) {
            $pn = $p -replace '\\', '/'
            if ($kotlinWsFiles.Contains($p)) { $routeEligible = $false }
            if (-not $pn.StartsWith('core/native/')) { $routeEligible = $false }
            if ($pn -match '\.(py|ps1|psm1|kts)$') { $routeEligible = $false }
        }
        $fullReasons = New-Object System.Collections.Generic.List[string]
        if ($kotlinWsFiles.Count -gt 0) { $fullReasons.Add('Kotlin/gradle write set present (' + $kotlinWsFiles.Count + ' files)') }
        if (-not $routeEligible) { $fullReasons.Add('write set not subset of core/native/** (native-only trains are the only route-eligible ones)') }
        if ($ordinal -and (($ordinal % 3) -eq 0)) { $fullReasons.Add('every-3rd-train rule reached (ordinal ' + $ordinal + ')') }
        $nextFullDue = $null
        if ($ordinal) { $nf = [int]$ordinal; while (($nf % 3) -ne 0) { $nf++ }; $nextFullDue = $nf }
        $byModule = [ordered]@{}
        foreach ($a in $affected) {
            if ($a.kind -ne 'gradle') { continue }
            if (-not $byModule.Contains($a.modulePath)) { $byModule[$a.modulePath] = New-Object System.Collections.Generic.List[string] }
            if (-not $byModule[$a.modulePath].Contains($a.fqcn)) { $byModule[$a.modulePath].Add($a.fqcn) }
        }
        $argLines = New-Object System.Collections.Generic.List[string]
        foreach ($k in $byModule.Keys) {
            $parts = @(foreach ($fq in @($byModule[$k].ToArray() | Sort-Object)) { '--tests "' + $fq + '"' })
            $argLines.Add($k + ':testDebugUnitTest ' + ($parts -join ' '))
        }
        Write-Both $buf "AFFECTED TEST CLASSES:"
        $affectedShown = 0
        foreach ($a in @($affected.ToArray() | Sort-Object module, rel)) {
            $affectedShown++
            if ($affectedShown -gt 80) { continue }
            $why = ''
            if ($a.wsMember) { $why = 'write-set member' }
            if ($a.hits.Count -gt 0) {
                $hshow = (@($a.hits | Select-Object -First 8) -join ', ')
                if ($why) { $why = $why + '; hits: ' + $hshow } else { $why = 'hits: ' + $hshow }
            }
            Write-Both $buf ("  [" + $a.module + "] " + $a.rel + "  (" + $why + ")")
        }
        if ($affected.Count -gt 80) { Write-Both $buf ("  ...(+" + ($affected.Count - 80) + " more affected; full list in route-jvm.json)") }
        Write-Both $buf "TESTS ARG FRAGMENT:"
        if ($argLines.Count -eq 0) { Write-Both $buf "  (none - no gradle test classes affected)" }
        foreach ($l in $argLines) { Write-Both $buf ("  " + $l) }
        $latestSrcStr = 'n/a'
        if ($latestSrcUtc) { $latestSrcStr = $latestSrcUtc.ToString('o') }
        Write-Both $buf ("JUNIT FRESHNESS (xml mtime >= latest write-set edit " + $latestSrcStr + "): fresh=" + $freshOk + " stale=" + $staleXml + " missing-xml=" + $missingXml)
        $staleShown = 0
        foreach ($fi in $freshItems) {
            if (-not $fi.fresh) {
                if ($staleShown -lt 20) { Write-Both $buf ("  STALE/MISSING: " + $fi.fqcn + " xml=" + $fi.xmlRel + " mtime=" + $fi.xmlMtimeUtc) }
                $staleShown++
            }
        }
        if ($staleShown -gt 20) { Write-Both $buf ("  ...(+" + ($staleShown - 20) + " more stale/missing; full list in route-jvm.json)") }
        Write-Both $buf ("COUNT BASELINE: engine tests=" + $engineCounts.tests + " f=" + $engineCounts.failures + " e=" + $engineCounts.errors + " s=" + $engineCounts.skipped + " (baseline 518/0/0/2 ok=" + $engineOk + "); app tests=" + $appCounts.tests + " f=" + $appCounts.failures + " e=" + $appCounts.errors + " s=" + $appCounts.skipped + " (baseline 2447/0/0/8 ok=" + $appOk + ")")
        Write-Both $buf ("FULL-RUN REMINDERS: freeze-point train sequence: " + $seqStr + "; this train ordinal=" + $(if ($ordinal) { $ordinal.ToString() } else { 'n/a' }) + "; every-3rd-train next due=" + $(if ($nextFullDue) { ('ordinal ' + $nextFullDue) } else { 'n/a' }) + "; kotlin/gradle write set -> full; before RC -> full")
        if ($fullReasons.Count -gt 0) {
            Write-Both $buf ("ROUTE-JVM VERDICT: FULL JVM REQUIRED (" + ($fullReasons -join '; ') + ")")
        } else {
            Write-Both $buf "ROUTE-JVM VERDICT: filtered route usable (run the --tests fragment above; native cpp tests are covered by fast anchors/NDK gate)"
        }
        $testsArgs = [ordered]@{}
        foreach ($k in $byModule.Keys) { $testsArgs[$k] = @($byModule[$k].ToArray() | Sort-Object) }
        $affectedJson = New-Object System.Collections.Generic.List[object]
        foreach ($a in @($affected.ToArray() | Sort-Object module, rel)) { $affectedJson.Add([pscustomobject]@{ rel = $a.rel; module = $a.module; kind = $a.kind; fqcn = $a.fqcn; writeSetMember = $a.wsMember; hits = @($a.hits) }) }
        $j = [ordered]@{
            schema = 'mca.route-jvm/1'
            trainId = $Id
            generatedUtc = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
            dryRun = [bool]$DryRun.IsPresent
            writeSetSource = $wsSource
            writeSet = @($wsPaths.ToArray())
            writeSetMissing = @($wsMissing.ToArray())
            needleCount = $needles.Count
            scannedTestFiles = $scanned
            affected = @($affectedJson.ToArray())
            testsArgsByModule = $testsArgs
            argLines = @($argLines.ToArray())
            junitFreshness = [ordered]@{ latestSourceEditUtc = $latestSrcStr; fresh = $freshOk; stale = $staleXml; missingXml = $missingXml; items = @($freshItems.ToArray()) }
            counts = [ordered]@{
                engine = [ordered]@{ tests = $engineCounts.tests; failures = $engineCounts.failures; errors = $engineCounts.errors; skipped = $engineCounts.skipped; baselineTests = 518; baselineSkipped = 2; ok = $engineOk }
                app = [ordered]@{ tests = $appCounts.tests; failures = $appCounts.failures; errors = $appCounts.errors; skipped = $appCounts.skipped; baselineTests = 2447; baselineSkipped = 8; ok = $appOk }
            }
            routeEligible = $routeEligible
            fullRequired = ($fullReasons.Count -gt 0)
            fullReasons = @($fullReasons.ToArray())
            trainOrdinal = $ordinal
            trainOrdinalBase = 'freeze-point train sequence (fp-freeze.json)'
            freezePointTrainSequence = $seqStr
            previousTrain = $prevTrainName
            nextFullDue = $nextFullDue
            standingReminders = @('every 3rd train must run full JVM', 'any Kotlin/gradle write set must run full JVM', 'full JVM is mandatory before RC')
        }
        if (-not $DryRun) {
            Write-Utf8Lf $outJson (ConvertTo-JsonLf $j 14)
            Write-Both $buf ("WROTE " + $outJson)
        }
        Write-Both $buf ("ROUTE-JVM DONE (dryRun=$($DryRun.IsPresent); json=" + $outJson + ")")
        if ($logFile) { Write-Utf8Lf $logFile ($buf -join "`n") }
        exit 0
    }
    'pipeline' {
        $env:PYTHONDONTWRITEBYTECODE = '1'
        $buf = New-Object System.Collections.Generic.List[string]
        $logFile = ''
        if (-not $DryRun) {
            $att = Get-NextAttempt $Logs $Id 'pipeline'
            $logFile = Join-Path $Logs "$Id.pipeline.$att.out.log"
        }
        Write-Both $buf "PIPELINE train=$Id ev=$Ev dryRun=$($DryRun.IsPresent)"
        if (-not (Test-Path -LiteralPath $Ev)) {
            Write-Both $buf "EV-MISSING: $Ev"
            if ($logFile) { Write-Utf8Lf $logFile ($buf -join "`n") }
            exit 2
        }
        $slices = @(Get-SliceDirs $RepoRoot $Id)
        $beforeFound = New-Object System.Collections.Generic.List[string]
        foreach ($d in @($Ev) + $slices) {
            $bf = Join-Path $d 'BEFORE-FILES.json'
            if (Test-Path -LiteralPath $bf) { $beforeFound.Add($bf) }
        }
        $logDirs = New-Object System.Collections.Generic.List[string]
        $logDirs.Add($Logs)
        foreach ($s in $slices) {
            $sl = Join-Path $s 'logs'
            if ((Test-Path -LiteralPath $sl) -and ($sl -ne $Logs)) { $logDirs.Add($sl) }
        }
        $chainLogs = 0; $ndkLogs = 0; $stubLogs = 0; $codegenLogs = 0; $ndkPassCount = 0
        $ndkOutList = New-Object System.Collections.Generic.List[string]
        foreach ($ld in $logDirs) {
            foreach ($f in @(Get-ChildItem -LiteralPath $ld -File -ErrorAction SilentlyContinue)) {
                if ($f.Name -match '\.chain\.\d+\.(out|err)\.log$') { $chainLogs++ }
                if ($f.Name -match '\.ndk\.\d+\.(out|err)\.log$') {
                    $ndkLogs++
                    if ($f.Name -match '\.ndk\.\d+\.out\.log$') { $ndkOutList.Add($f.FullName) }
                }
                if ($f.Name -match '\.stub') { $stubLogs++ }
                if ($f.Name -match '\.codegen\.\d+\.(out|err)\.log$') { $codegenLogs++ }
            }
        }
        foreach ($nf in $ndkOutList) {
            $txt = ''
            try { $txt = [string](Get-Content -LiteralPath $nf -Raw) } catch { }
            if ($txt -match 'PASSED') { $ndkPassCount++ }
        }
        $aOk = (($beforeFound.Count -gt 0) -and (($chainLogs -gt 0) -or ($codegenLogs -gt 0)) -and ($ndkLogs -gt 0))
        $aState = 'INCOMPLETE'
        if ($aOk) { $aState = 'OK' }
        Write-Both $buf ("STATION A (edit / fast anchors): " + $aState)
        foreach ($bf in $beforeFound) { Write-Both $buf ("  before-files: " + ($bf.Replace($RepoRoot, '').TrimStart('\'))) }
        Write-Both $buf ("  chain-logs=" + $chainLogs + " codegen-logs=" + $codegenLogs + " stub-logs=" + $stubLogs + " ndk-logs=" + $ndkLogs + " (ndk out logs with PASSED=" + $ndkPassCount + "/" + $ndkOutList.Count + ")")
        $gate = Get-TrainGate $RepoRoot $Ev $Id
        $bState = 'INCOMPLETE'
        if ($gate.complete) { $bState = 'OK' }
        Write-Both $buf ("STATION B (train gate: full JVM + APK + build record): " + $bState)
        foreach ($x in $gate.full) { Write-Both $buf ("  full  " + $x.log + " -> " + $x.status) }
        foreach ($x in $gate.apk) { Write-Both $buf ("  apk   " + $x.log + " -> " + $x.status) }
        foreach ($x in $gate.records) { Write-Both $buf ("  record " + $x.runId + " -> " + $x.status + " sha256=" + (Get-ShortSha $x.apkSha256) + " bytes=" + $x.apkBytes) }
        Write-Both $buf ("  summary: full-pass=" + $gate.fullPass + " apk-pass=" + $gate.apkPass + " build-record-pass=" + $gate.recPass)
        $verdicts = @(Get-ChildItem -LiteralPath $Ev -File -Filter 'REVIEW-VERDICT-*.md' -ErrorAction SilentlyContinue)
        $fpj = Join-Path $Ev 'fp-freeze.json'
        $regj = Join-Path $Ev 'review-snapshot\SNAPSHOT-HASHES.json'
        $regCount = 0
        if (Test-Path -LiteralPath $regj) {
            try {
                $rg = Get-Content -LiteralPath $regj -Raw | ConvertFrom-Json
                foreach ($gName in @($rg.PSObject.Properties.Name)) { $regCount += @($rg.$gName.PSObject.Properties).Count }
            } catch { }
        }
        $cOk = (($verdicts.Count -gt 0) -and (Test-Path -LiteralPath $fpj))
        $cState = 'INCOMPLETE'
        if ($cOk) { $cState = 'OK' }
        Write-Both $buf ("STATION C (review / freeze): " + $cState)
        foreach ($v in $verdicts) { Write-Both $buf ("  verdict: " + $v.Name) }
        if (Test-Path -LiteralPath $fpj) {
            $fpfp = ''
            try { $fpfp = [string]((Get-Content -LiteralPath $fpj -Raw | ConvertFrom-Json).fingerprint) } catch { }
            Write-Both $buf ("  fp-freeze.json: fingerprint=" + (Get-ShortSha $fpfp))
        } else { Write-Both $buf "  fp-freeze.json: MISSING" }
        if (Test-Path -LiteralPath $regj) { Write-Both $buf ("  SNAPSHOT-HASHES.json: entries=" + $regCount) } else { Write-Both $buf "  SNAPSHOT-HASHES.json: MISSING" }
        $trains = @(Get-TrainLikeDirs $RepoRoot)
        $idx = -1
        for ($i = 0; $i -lt $trains.Count; $i++) { if ($trains[$i].path -eq $Ev) { $idx = $i } }
        $prev = $null
        if ($idx -gt 0) { $prev = $trains[$idx - 1] }
        elseif ($idx -lt 0) {
            $nowKey = (Get-Date).ToUniversalTime()
            foreach ($t in $trains) { if ($t.orderKey -le $nowKey) { $prev = $t } }
        }
        if ($prev) {
            $pg = Get-TrainGate $RepoRoot $prev.path $prev.name
            if ($pg.complete) {
                Write-Both $buf ("MONOLITH-EDIT GATE (spec 2.2): OPEN - previous train " + $prev.name + " has full JVM pass + APK pass + passed build record")
            } else {
                Write-Both $buf ("MONOLITH-EDIT GATE (spec 2.2): WARNING - previous train " + $prev.name + " full JVM/APK incomplete (full-pass=" + $pg.fullPass + " apk-pass=" + $pg.apkPass + " build-record-pass=" + $pg.recPass + "): NEXT BATCH FIRST MONOLITH EDIT MUST WAIT; non-monolith prep may proceed")
                foreach ($x in $pg.full) { Write-Both $buf ("  prev full " + $x.log + " -> " + $x.status) }
                foreach ($x in $pg.apk) { Write-Both $buf ("  prev apk  " + $x.log + " -> " + $x.status) }
                foreach ($x in $pg.records) { Write-Both $buf ("  prev record " + $x.runId + " -> " + $x.status) }
            }
        } else {
            Write-Both $buf "MONOLITH-EDIT GATE (spec 2.2): no previous frozen train detected (nothing to gate)"
        }
        if ($gate.complete) {
            Write-Both $buf "WINDOW RULE (spec 2.2): this train full JVM + APK complete -> next batch may start its first monolith edit; device/review/freeze run concurrently"
        } else {
            Write-Both $buf "WINDOW RULE (spec 2.2): this train full JVM + APK NOT complete -> next batch monolith edits blocked; only non-monolith prep allowed"
        }
        Write-Both $buf ("PIPELINE: A=" + $aState + " B=" + $bState + " C=" + $cState)
        if ($logFile) { Write-Utf8Lf $logFile ($buf -join "`n") }
        exit 0
    }
}
