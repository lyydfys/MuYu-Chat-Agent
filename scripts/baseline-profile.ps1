<#
.SYNOPSIS
  MCA Baseline Profile 闭环：生成 -> 复制进 :app -> 构建带 profile 的 APK -> 校验进包 -> 证据 JSON。

.DESCRIPTION
  把此前只存在于 BenchmarkTarget 的 `partial-baseline` / `partial-no-baseline` 两种编译模式，
  接上真正的 Baseline Profile 生产链路：

    1. 生成：在真机上运行 `:benchmark:connectedBenchmarkAndroidTest`，测试类是
       `com.muyuchat.mca.benchmark.BaselineProfileGenerator`（androidx.benchmark 的
       BaselineProfileRule）。输出 `mca-baseline-prof.txt` 到 connected test 的
       additional test output。
    2. 复制：写入 `app/src/main/baseline-prof.txt`（AGP 8.13.2 的
       `VariantSources.getArtProfile()` 读取的正是这个路径）。
    3. 构建：`:app:assemble<BuildType>`，让 AGP 的 CompileArtProfileTask 把源 profile 编译成
       `assets/dexopt/baseline.prof`。
    4. 校验：直接解包 APK，检查 `assets/dexopt/baseline.prof` 是否存在且非空，记录条目大小与
       SHA-256；同时记录 APK SHA-256，作为「哪个 APK 带 profile」的证据。
    5. 证据：写入 `<OutDir>/baseline-profile.json`（schema `mca.baseline-profile/1`）。

  诚实边界：
  - 没有真机时不运行生成步骤，verdict 记为 NOT_RUN（exit 3），不伪造任何性能收益。
  - 本脚本不做 A/B 收益计算。`comparison` 字段只给出 `partial-baseline` 与
    `partial-no-baseline` 的完整命令、对照表列定义与判定前提；两侧都没有有效样本时保持 NOT_RUN。
  - 默认构建类型是 `benchmark`（release-like、R8 开启、debug 签名、applicationId 带
    `.benchmark` 后缀），这正是 `partial-baseline` 测量的目标。**不能**用 `debug` 做进包校验：
    AGP 8.13.2 只在 minified variant 上注册 `MergeArtProfileTask`，未 minify 的 debug 会忽略
    `src/main/baseline-prof.txt`（实测 `:app:packageDebug UP-TO-DATE`，APK 内没有
    `assets/dexopt/baseline.prof`）。`release` 也支持，但会覆盖共享的 release APK 证据，默认不用。

.PARAMETER PlanOnly
  只打印步骤、前置条件与 A/B 命令，不构建、不写文件、不占用构建锁。

.PARAMETER DeviceSerial
  目标设备序列号。省略时要求恰好一台 `adb devices` 状态为 device 的设备。

.PARAMETER OutDir
  证据目录，默认 `artifacts/baseline-profile/<yyyyMMdd-HHmmss>-<pid>`（已在本机
  `.git/info/exclude` 中忽略，不会误提交）。

.PARAMETER ProfilePath
  直接使用现成的 human-readable profile（例如人工手写或从别处导入），跳过真机生成。
  仍会执行「复制 -> 构建 -> 校验进包」。

.PARAMETER AllowSyntheticProfile
  无设备时用于证明「打包链路」的最小验证路径：写入一个只包含 `MainActivity` 两个真实方法的
  合成 profile，构建并检查它是否进包。证据里 `profile.source=synthetic`、
  `profile.deviceGenerated=false`，不得当作生产 profile 或收益证据。

.PARAMETER KeepProfileInSource
  校验后保留 `app/src/main/baseline-prof.txt`。默认会把源文件恢复为运行前状态（原来没有就
  删除），避免未审查的 profile 留在工作区。

.PARAMETER BuildType
  benchmark | release。默认 benchmark。debug 被拒绝：未 minify 的 variant 不会合并
  src/main/baseline-prof.txt。release 会复用/覆盖共享的 release APK，除非明确需要，优先 benchmark。

.PARAMETER SkipBuild
  只做「生成/复制/统计」，不构建。此时无法证明进包，verdict 为 NOT_RUN。

.EXAMPLE
  .\scripts\baseline-profile.ps1 -PlanOnly

.EXAMPLE
  .\scripts\baseline-profile.ps1 -AllowSyntheticProfile

.EXAMPLE
  .\scripts\baseline-profile.ps1 -DeviceSerial device-a -BuildType benchmark -KeepProfileInSource
#>
[CmdletBinding()]
param(
    [switch]$PlanOnly,
    [string]$DeviceSerial = '',
    [string]$OutDir = '',
    [string]$ProfilePath = '',
    [switch]$AllowSyntheticProfile,
    [switch]$KeepProfileInSource,
    [ValidateSet('debug', 'benchmark', 'release')]
    [string]$BuildType = 'benchmark',
    [int]$ProfileMaxIterations = 15,
    [int]$ProfileStableIterations = 3,
    [switch]$SkipBuild,
    [int]$LockRetrySeconds = 900
)

$ErrorActionPreference = 'Stop'

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$BaselineProfileSource = Join-Path $RepoRoot 'app\src\main\baseline-prof.txt'
$GeneratorClass = 'com.muyuchat.mca.benchmark.BaselineProfileGenerator'
$Schema = 'mca.baseline-profile/1'

function Initialize-McaToolchain {
    if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'E:\model\toolchains\jdk-21\jdk-21.0.12.1+1' }
    if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = 'D:\model\android-sdk' }
    if (-not $env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME }
    if (-not $env:MCA_QNN_SDK_ROOT) { $env:MCA_QNN_SDK_ROOT = 'E:\tools\qairt-sdk\2.45.0.260326' }
    # :benchmark 是 opt-in 模块。按项目约定用环境变量开启，不用 -P 传参（避免污染其它 Gradle
    # 调用，也避免 PowerShell 把 -P 当成脚本参数）。
    $env:MCA_INCLUDE_BENCHMARK = 'true'
}

function Resolve-AdbPath {
    $candidate = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
    if (Test-Path -LiteralPath $candidate) { return $candidate }
    return 'adb'
}

function Get-AdbDeviceSerials {
    param([string]$Adb)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $text = (& $Adb devices 2>&1 | Out-String) } finally { $ErrorActionPreference = $previous }
    return @(
        $text -split "`r?`n" |
            Where-Object { $_ -match '^\S+\s+device\s*$' } |
            ForEach-Object { ($_ -split '\s+')[0] }
    )
}

function Invoke-McaGradle {
    param(
        [string[]]$Arguments,
        [string]$LogPath
    )
    $gradlew = Join-Path $RepoRoot 'gradlew.bat'
    if (-not (Test-Path -LiteralPath $gradlew)) { throw "gradlew.bat not found: $gradlew" }
    Write-Host ("[gradle] .\gradlew.bat " + ($Arguments -join ' '))
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $text = (& $gradlew @Arguments 2>&1 | Out-String) } finally { $ErrorActionPreference = $previous }
    $exitCode = $LASTEXITCODE
    [IO.File]::WriteAllText($LogPath, $text, [Text.UTF8Encoding]::new($false))
    return [pscustomobject]@{ exitCode = $exitCode; text = $text; log = $LogPath }
}

function Enter-McaLock {
    param([string]$CommandLine)
    $modulePath = Join-Path $RepoRoot 'tools\build-guards\McaBuildLock.psm1'
    if (-not (Test-Path -LiteralPath $modulePath)) { throw "McaBuildLock.psm1 not found: $modulePath" }
    Import-Module $modulePath -Force
    return Enter-McaBuildLock -Kind test -CommandLine $CommandLine -RetrySeconds $LockRetrySeconds -RetryIntervalSeconds 10
}

function Find-GeneratedProfile {
    $roots = @(
        (Join-Path $RepoRoot 'benchmark\build\outputs\connected_android_test_additional_output'),
        (Join-Path $RepoRoot 'benchmark\build\outputs'),
        (Join-Path $RepoRoot 'benchmark\build')
    ) | Where-Object { Test-Path -LiteralPath $_ }
    $candidates = New-Object System.Collections.Generic.List[object]
    foreach ($root in $roots) {
        foreach ($file in @(Get-ChildItem -LiteralPath $root -Recurse -File -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -like '*-baseline-prof.txt' })) {
            $candidates.Add($file) | Out-Null
        }
    }
    return ($candidates | Sort-Object LastWriteTime -Descending | Select-Object -First 1)
}

function Get-ProfileStats {
    param([string]$Path)
    $lines = @(Get-Content -LiteralPath $Path -ErrorAction Stop)
    $rules = @($lines | Where-Object {
            $trimmed = $_.Trim()
            $trimmed -and -not $trimmed.StartsWith('#')
        })
    return [pscustomobject]@{
        path = (Resolve-Path -LiteralPath $Path).Path
        sha256 = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
        bytes = (Get-Item -LiteralPath $Path).Length
        lines = $lines.Count
        ruleCount = $rules.Count
    }
}

function New-SyntheticBaselineProfile {
    param([string]$Path)
    $rules = @(
        'HSPLcom/muyuchat/mca/MainActivity;-><init>()V',
        'HSPLcom/muyuchat/mca/MainActivity;->onCreate(Landroid/os/Bundle;)V'
    )
    [IO.File]::WriteAllText($Path, (($rules -join "`n") + "`n"), [Text.UTF8Encoding]::new($false))
    return $rules
}

function Find-BuiltApk {
    param([string]$BuildType)
    $apkRoot = Join-Path $RepoRoot ("app\build\outputs\apk\" + $BuildType)
    if (-not (Test-Path -LiteralPath $apkRoot)) { return $null }
    $apks = @(Get-ChildItem -LiteralPath $apkRoot -Recurse -File -Filter '*.apk' -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch 'androidTest|unsigned|previous' } |
            Sort-Object LastWriteTime -Descending)
    if ($apks.Count -eq 0) { return $null }
    return $apks[0]
}

function Get-ArtProfileTrace {
    <#
      证明「我们的源 profile」真的沿 AGP 流水线进了 APK，而不是被库里 AAR 自带的 profile 冒名顶替：
        app/src/main/baseline-prof.txt
          -> merged_art_profile/<variant>/merge<Variant>ArtProfile/baseline-prof.txt（应包含源文件第一条规则）
          -> binary_art_profile/<variant>/compile<Variant>ArtProfile/baseline.prof（其 SHA-256 应与 APK 内条目一致）
    #>
    param([string]$BuildType, [string]$SourceProfilePath)
    $variant = $BuildType.ToLowerInvariant()
    $suffix = $BuildType.Substring(0, 1).ToUpperInvariant() + $BuildType.Substring(1)
    $mergedPath = Join-Path $RepoRoot ("app\build\intermediates\merged_art_profile\{0}\merge{1}ArtProfile\baseline-prof.txt" -f $variant, $suffix)
    $binaryPath = Join-Path $RepoRoot ("app\build\intermediates\binary_art_profile\{0}\compile{1}ArtProfile\baseline.prof" -f $variant, $suffix)

    $anchorRule = $null
    foreach ($line in @(Get-Content -LiteralPath $SourceProfilePath -ErrorAction Stop)) {
        $trimmed = $line.Trim()
        if ($trimmed -and -not $trimmed.StartsWith('#')) { $anchorRule = $trimmed; break }
    }

    $trace = [ordered]@{
        anchorRule = $anchorRule
        mergedProfilePath = $(if (Test-Path -LiteralPath $mergedPath) { (Resolve-Path -LiteralPath $mergedPath).Path } else { $null })
        mergedProfileSha256 = $null
        mergedContainsAnchor = $false
        binaryProfilePath = $(if (Test-Path -LiteralPath $binaryPath) { (Resolve-Path -LiteralPath $binaryPath).Path } else { $null })
        binaryProfileSha256 = $null
        binaryMatchesApkEntry = $false
        verified = $false
    }
    if (Test-Path -LiteralPath $mergedPath) {
        $trace.mergedProfileSha256 = (Get-FileHash -LiteralPath $mergedPath -Algorithm SHA256).Hash.ToLowerInvariant()
        $mergedText = Get-Content -LiteralPath $mergedPath -Raw
        if ($anchorRule) { $trace.mergedContainsAnchor = $mergedText.Contains($anchorRule) }
    }
    if (Test-Path -LiteralPath $binaryPath) {
        $trace.binaryProfileSha256 = (Get-FileHash -LiteralPath $binaryPath -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    return $trace
}

function Test-ApkBaselineProfile {
    param([string]$ApkPath)
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction Stop
    $profileEntries = New-Object System.Collections.Generic.List[object]
    $dexoptDirPresent = $false
    $zip = [System.IO.Compression.ZipFile]::OpenRead($ApkPath)
    try {
        foreach ($entry in $zip.Entries) {
            if ($entry.FullName -like 'assets/dexopt/*') { $dexoptDirPresent = $true }
            if ($entry.FullName -like '*baseline.prof' -or $entry.FullName -like '*baseline.profm' -or
                $entry.FullName -like '*baseline-prof.txt') {
                $sha = $null
                if ($entry.Length -gt 0) {
                    $stream = $entry.Open()
                    try {
                        $hasher = [System.Security.Cryptography.SHA256]::Create()
                        try {
                            $hashBytes = $hasher.ComputeHash($stream)
                            $sha = ([BitConverter]::ToString($hashBytes) -replace '-', '').ToLowerInvariant()
                        } finally { $hasher.Dispose() }
                    } finally { $stream.Dispose() }
                }
                $profileEntries.Add([pscustomobject]@{
                        name = $entry.FullName
                        bytes = $entry.Length
                        sha256 = $sha
                    }) | Out-Null
            }
        }
    } finally { $zip.Dispose() }

    $primary = @($profileEntries | Where-Object { $_.name -eq 'assets/dexopt/baseline.prof' -and $_.bytes -gt 0 })
    return [pscustomobject]@{
        apkPath = (Resolve-Path -LiteralPath $ApkPath).Path
        apkSha256 = (Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash.ToLowerInvariant()
        dexoptDirPresent = $dexoptDirPresent
        profileEntries = @($profileEntries | ForEach-Object { $_ })
        profileInApk = ($primary.Count -gt 0)
    }
}

function New-ComparisonPlan {
    $common = '.\gradlew.bat :benchmark:connectedBenchmarkAndroidTest'
    return [ordered]@{
        status = 'NOT_RUN'
        reason = '本脚本只负责生成/打包/校验；A/B 需要真机、已安装的 benchmark 变体、可用 fixture 与 >=10 次有效迭代。没有这些条件时保持 NOT_RUN，不计算收益。'
        preconditions = @(
            '$env:MCA_INCLUDE_BENCHMARK=''true''（PowerShell 环境变量，不要用 -P 传参）',
            '物理设备 API 29+；app 的 benchmark 变体（com.muyuchat.mca.benchmark）已安装且为 profileable',
            '同一次对照使用同一个 APK SHA-256、同一设备、同一 fixture、同一刷新率/温度档位',
            '每个模式至少 10 次有效迭代；交错执行 A/B/A/B 以抵消温度漂移',
            'partial-baseline 使用 BaselineProfileMode.Require；profile 未安装时该次运行会失败，这正是「profile 确实生效」的证据，不要把它当性能失败'
        )
        commands = [ordered]@{
            withBaseline = ($common + ' "-Pandroid.testInstrumentationRunnerArguments.class=com.muyuchat.mca.benchmark.ColdStartupBenchmark" "-Pandroid.testInstrumentationRunnerArguments.mca.compilationMode=partial-baseline" "-Pandroid.testInstrumentationRunnerArguments.mca.iterations=10"')
            withoutBaseline = ($common + ' "-Pandroid.testInstrumentationRunnerArguments.class=com.muyuchat.mca.benchmark.ColdStartupBenchmark" "-Pandroid.testInstrumentationRunnerArguments.mca.compilationMode=partial-no-baseline" "-Pandroid.testInstrumentationRunnerArguments.mca.iterations=10"')
            streamingWithBaseline = ($common + ' "-Pandroid.testInstrumentationRunnerArguments.class=com.muyuchat.mca.benchmark.StreamingTokenBenchmark" "-Pandroid.testInstrumentationRunnerArguments.mca.compilationMode=partial-baseline"')
            streamingWithoutBaseline = ($common + ' "-Pandroid.testInstrumentationRunnerArguments.class=com.muyuchat.mca.benchmark.StreamingTokenBenchmark" "-Pandroid.testInstrumentationRunnerArguments.mca.compilationMode=partial-no-baseline"')
            galleryWithBaseline = ($common + ' "-Pandroid.testInstrumentationRunnerArguments.class=com.muyuchat.mca.benchmark.ImageGalleryScrollBenchmark" "-Pandroid.testInstrumentationRunnerArguments.mca.compilationMode=partial-baseline"')
            galleryWithoutBaseline = ($common + ' "-Pandroid.testInstrumentationRunnerArguments.class=com.muyuchat.mca.benchmark.ImageGalleryScrollBenchmark" "-Pandroid.testInstrumentationRunnerArguments.mca.compilationMode=partial-no-baseline"')
        }
        tableColumns = @(
            'scenario', 'compilationMode', 'baselineProfileMode', 'iterations', 'validSamples',
            'metric', 'median', 'min', 'max', 'unit', 'device', 'apkSha256', 'notes'
        )
        rules = @(
            '只比较同一 metric 的中位数/最小值/最大值，不把不同 compilationMode 的数字混在一行',
            'partial-baseline 与 partial-no-baseline 之外的模式（full/none/default）不进这张表',
            'validSamples < 10 或 fixture 未满足时，该行记 NOT_RUN，不计算百分比',
            '收益百分比只在两侧都有 >=10 个有效样本时计算，并同时给出样本数、波动范围与 APK SHA-256',
            '原始输出由 scripts/verify-ui-quality.ps1 -Lane macro 归档；本脚本不复制设备日志，避免夹带会话内容'
        )
    }
}

function New-Evidence {
    return [ordered]@{
        schema = $Schema
        generatedUtc = (Get-Date).ToUniversalTime().ToString('o')
        repoRoot = $RepoRoot
        head = (& git -C $RepoRoot rev-parse HEAD 2>$null | Out-String).Trim()
        buildType = $BuildType
        device = $null
        profile = $null
        steps = @()
        apk = $null
        verdict = 'NOT_RUN'
        reason = ''
        sourceRestored = $null
        comparison = (New-ComparisonPlan)
        limits = @(
            '本脚本不生成、不推断任何性能收益；只有 profile 是否进入 APK 是它证明的范围。',
            'assets/dexopt/baseline.prof 存在只证明打包链路生效，不证明运行时 profile 安装成功；后者由 partial-baseline（BaselineProfileMode.Require）在真机上的成功运行证明。',
            'profile 的生成依赖真机 UI fixture；没有设备时使用 -AllowSyntheticProfile 仅验证打包路径。',
            'APK 内出现 assets/dexopt/baseline.prof 本身不足以证明本次源 profile 生效：AAR 自带的库 profile 也会产生该文件。因此本脚本额外要求 merged_art_profile 含源 profile 的第一条规则，且 binary_art_profile 编译产物的 SHA-256 与 APK 条目一致。'
        )
    }
}

function Write-Evidence {
    param([object]$Evidence, [string]$Directory)
    $json = $Evidence | ConvertTo-Json -Depth 8
    [IO.File]::WriteAllText((Join-Path $Directory 'baseline-profile.json'), $json, [Text.UTF8Encoding]::new($false))
    Write-Host ("[evidence] " + (Join-Path $Directory 'baseline-profile.json'))
}

function Format-Steps {
    param([object[]]$Steps)
    Write-Host ('[steps] ' + (($Steps | ForEach-Object { "$($_.name)=$($_.status)" }) -join ', '))
}

Initialize-McaToolchain
$adb = Resolve-AdbPath
$onlineDevices = Get-AdbDeviceSerials -Adb $adb

if ($PlanOnly) {
    Write-Host '=== MCA Baseline Profile plan (PlanOnly: no build, no writes) ==='
    Write-Host ("RepoRoot           : {0}" -f $RepoRoot)
    Write-Host ("JAVA_HOME          : {0} ({1})" -f $env:JAVA_HOME, $(if (Test-Path -LiteralPath $env:JAVA_HOME) { 'exists' } else { 'MISSING' }))
    Write-Host ("ANDROID_HOME       : {0} ({1})" -f $env:ANDROID_HOME, $(if (Test-Path -LiteralPath $env:ANDROID_HOME) { 'exists' } else { 'MISSING' }))
    Write-Host ("MCA_QNN_SDK_ROOT   : {0} ({1})" -f $env:MCA_QNN_SDK_ROOT, $(if (Test-Path -LiteralPath $env:MCA_QNN_SDK_ROOT) { 'exists' } else { 'MISSING' }))
    Write-Host ("adb                : {0}" -f $adb)
    Write-Host ("online devices     : {0}" -f $(if ($onlineDevices.Count -gt 0) { $onlineDevices -join ',' } else { '<none>' }))
    $benchmarkCache = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1\androidx.benchmark\benchmark-macro-junit4'
    Write-Host ("benchmark deps     : {0}" -f $(if (Test-Path -LiteralPath $benchmarkCache) { 'cached (offline ok)' } else { 'NOT cached (first enabled build needs Google Maven)' }))
    Write-Host ("existing profile   : {0}" -f $(if (Test-Path -LiteralPath $BaselineProfileSource) { $BaselineProfileSource } else { '<absent>' }))
    Write-Host ''
    Write-Host 'Steps:'
    Write-Host '  1) [generate] $env:MCA_INCLUDE_BENCHMARK=true; .\gradlew.bat :benchmark:connectedBenchmarkAndroidTest'
    Write-Host '     -Pandroid.testInstrumentationRunnerArguments.class=com.muyuchat.mca.benchmark.BaselineProfileGenerator'
    Write-Host '     -> mca-baseline-prof.txt under benchmark/build/outputs/connected_android_test_additional_output'
    Write-Host '  2) [copy]     <generated> -> app/src/main/baseline-prof.txt (AGP VariantSources.getArtProfile path)'
    Write-Host '  3) [build]    .\gradlew.bat :app:assemble<BuildType>   (default: benchmark; debug cannot package profiles)'
    Write-Host '  4) [verify]   unzip APK; require non-empty assets/dexopt/baseline.prof; record entry + APK SHA-256'
    Write-Host '  5) [evidence] artifacts/baseline-profile/<timestamp>/baseline-profile.json'
    Write-Host '  * without a device: verdict=NOT_RUN (exit 3). -AllowSyntheticProfile only proves the packaging path.'
    Write-Host ''
    $plan = New-ComparisonPlan
    Write-Host 'A/B comparison (manual; never auto-computed without a device):'
    Write-Host ('  with baseline    : ' + $plan.commands.withBaseline)
    Write-Host ('  without baseline : ' + $plan.commands.withoutBaseline)
    Write-Host '  Same device / APK SHA / fixture / refresh rate; >=10 valid iterations each.'
    exit 0
}

if ($BuildType -eq 'debug') {
    Write-Host ('[usage-error] -BuildType debug cannot prove baseline-profile packaging: AGP registers ' +
        'MergeArtProfileTask only for minified variants, so an unminified debug variant ignores ' +
        'app/src/main/baseline-prof.txt. Use -BuildType benchmark (default) or release.') -ForegroundColor Red
    exit 2
}

if (-not $OutDir) {
    $OutDir = Join-Path $RepoRoot ('artifacts\baseline-profile\' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $PID)
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$evidence = New-Evidence
$steps = New-Object System.Collections.Generic.List[object]
$exitCode = 3

$ready = $true
if (-not $ProfilePath -and -not $AllowSyntheticProfile) {
    if (-not $DeviceSerial) {
        if ($onlineDevices.Count -ne 1) {
            $ready = $false
            $evidence.reason = "Expected exactly one online device (or -DeviceSerial); online: $($onlineDevices -join ','). No profile was generated."
        } else {
            $DeviceSerial = $onlineDevices[0]
        }
    } elseif ($onlineDevices -notcontains $DeviceSerial) {
        $ready = $false
        $evidence.reason = "Device '$DeviceSerial' is not online; online: $($onlineDevices -join ',')."
    }
}

if (-not $ready) {
    $evidence.verdict = 'NOT_RUN'
    Write-Evidence -Evidence $evidence -Directory $OutDir
    Write-Host ("[NOT_RUN] " + $evidence.reason) -ForegroundColor Yellow
    exit 3
}

$lock = $null
$profileFile = $null
$hadPreviousProfile = $false
$backupPath = Join-Path $OutDir 'baseline-prof.previous.txt'
$sourcePath = $BaselineProfileSource

try {
    $lock = Enter-McaLock -CommandLine $MyInvocation.Line

    if ($ProfilePath) {
        if (-not (Test-Path -LiteralPath $ProfilePath)) {
            throw "ProfilePath not found: $ProfilePath"
        }
        $profileFile = (Resolve-Path -LiteralPath $ProfilePath).Path
        $steps.Add([pscustomobject]@{ name = 'generate'; status = 'SKIPPED'; detail = 'profile provided by -ProfilePath' }) | Out-Null
    } elseif ($AllowSyntheticProfile) {
        $profileFile = Join-Path $OutDir 'synthetic-baseline-prof.txt'
        $null = New-SyntheticBaselineProfile -Path $profileFile
        $steps.Add([pscustomobject]@{ name = 'generate'; status = 'SKIPPED'; detail = 'synthetic packaging-path verification (-AllowSyntheticProfile)' }) | Out-Null
    } else {
        $genArgs = @(
            ':benchmark:connectedBenchmarkAndroidTest',
            ("-Pandroid.testInstrumentationRunnerArguments.class=" + $GeneratorClass),
            ("-Pandroid.testInstrumentationRunnerArguments.mca.profileMaxIterations=" + $ProfileMaxIterations),
            ("-Pandroid.testInstrumentationRunnerArguments.mca.profileStableIterations=" + $ProfileStableIterations)
        )
        $genLog = Join-Path $OutDir 'generate.log'
        $gen = Invoke-McaGradle -Arguments $genArgs -LogPath $genLog
        $steps.Add([pscustomobject]@{ name = 'generate'; status = $(if ($gen.exitCode -eq 0) { 'PASS' } else { 'FAIL' }); exitCode = $gen.exitCode; log = $genLog }) | Out-Null
        if ($gen.exitCode -ne 0) {
            $evidence.verdict = 'FAIL'
            $evidence.reason = "BaselineProfileGenerator run failed (exit $($gen.exitCode)). See $genLog"
            $evidence.steps = @($steps | ForEach-Object { $_ })
            Write-Evidence -Evidence $evidence -Directory $OutDir
            exit 1
        }
        $profileFile = Find-GeneratedProfile
        if (-not $profileFile) {
            $evidence.verdict = 'FAIL'
            $evidence.reason = 'Test run succeeded but no *-baseline-prof.txt was found under benchmark/build/outputs. Check the additional test output directory; do not treat this as a packaging PASS.'
            $evidence.steps = @($steps | ForEach-Object { $_ })
            Write-Evidence -Evidence $evidence -Directory $OutDir
            exit 1
        }
        $profileFile = $profileFile.FullName
    }

    $evidence.device = $DeviceSerial
    $hadPreviousProfile = Test-Path -LiteralPath $sourcePath
    if ($hadPreviousProfile) {
        Copy-Item -LiteralPath $sourcePath -Destination $backupPath -Force
    }
    $profileResolved = (Resolve-Path -LiteralPath $profileFile).Path
    $sourceResolved = [System.IO.Path]::GetFullPath($sourcePath)
    if ($profileResolved -ne $sourceResolved) {
        Copy-Item -LiteralPath $profileFile -Destination $sourcePath -Force
    }
    $profileStats = Get-ProfileStats -Path $sourcePath
    $evidence.profile = [ordered]@{
        source = $(if ($AllowSyntheticProfile) { 'synthetic' } elseif ($ProfilePath) { 'provided' } else { 'generated' })
        deviceGenerated = (-not $AllowSyntheticProfile -and -not $ProfilePath)
        generatorClass = $(if ($AllowSyntheticProfile -or $ProfilePath) { $null } else { $GeneratorClass })
        originalPath = $profileFile
        copiedTo = $sourcePath
        sha256 = $profileStats.sha256
        bytes = $profileStats.bytes
        lines = $profileStats.lines
        ruleCount = $profileStats.ruleCount
    }
    $steps.Add([pscustomobject]@{ name = 'copy'; status = 'PASS'; detail = $sourcePath; sha256 = $profileStats.sha256 }) | Out-Null

    if ($SkipBuild) {
        $evidence.verdict = 'NOT_RUN'
        $evidence.reason = 'SkipBuild was requested; profile was copied but no APK was built, so packaging was not proven.'
        $evidence.steps = @($steps | ForEach-Object { $_ })
        Write-Evidence -Evidence $evidence -Directory $OutDir
        exit 3
    }

    $taskSuffix = $BuildType.Substring(0, 1).ToUpperInvariant() + $BuildType.Substring(1)
    $buildLog = Join-Path $OutDir ('build-' + $BuildType + '.log')
    $build = Invoke-McaGradle -Arguments @((':app:assemble' + $taskSuffix)) -LogPath $buildLog
    $steps.Add([pscustomobject]@{ name = 'build'; status = $(if ($build.exitCode -eq 0) { 'PASS' } else { 'FAIL' }); exitCode = $build.exitCode; log = $buildLog; task = (':app:assemble' + $taskSuffix) }) | Out-Null
    if ($build.exitCode -ne 0) {
        $evidence.verdict = 'FAIL'
        $evidence.reason = "APK build failed (exit $($build.exitCode)). See $buildLog"
        $evidence.steps = @($steps | ForEach-Object { $_ })
        Write-Evidence -Evidence $evidence -Directory $OutDir
        exit 1
    }

    $apk = Find-BuiltApk -BuildType $BuildType
    if (-not $apk) {
        $evidence.verdict = 'FAIL'
        $evidence.reason = "Build succeeded but no APK was found under app/build/outputs/apk/$BuildType."
        $evidence.steps = @($steps | ForEach-Object { $_ })
        Write-Evidence -Evidence $evidence -Directory $OutDir
        exit 1
    }

    $apkCheck = Test-ApkBaselineProfile -ApkPath $apk.FullName
    $apkProfileEntry = @($apkCheck.profileEntries | Where-Object { $_.name -eq 'assets/dexopt/baseline.prof' } | Select-Object -First 1)
    $trace = Get-ArtProfileTrace -BuildType $BuildType -SourceProfilePath $sourcePath
    if ($apkProfileEntry -and $trace.binaryProfileSha256) {
        $trace.binaryMatchesApkEntry = ($trace.binaryProfileSha256 -eq $apkProfileEntry.sha256)
    }
    $trace.verified = [bool]($trace.mergedContainsAnchor -and $trace.binaryMatchesApkEntry)
    $evidence.apk = [ordered]@{
        path = $apkCheck.apkPath
        sha256 = $apkCheck.apkSha256
        buildType = $BuildType
        dexoptDirPresent = $apkCheck.dexoptDirPresent
        profileEntries = $apkCheck.profileEntries
        profileInApk = $apkCheck.profileInApk
        profileTrace = $trace
    }
    $steps.Add([pscustomobject]@{
            name = 'verify-in-apk'
            status = $(if ($apkCheck.profileInApk -and $trace.verified) { 'PASS' } else { 'FAIL' })
            detail = 'assets/dexopt/baseline.prof + merged/binary trace'
        }) | Out-Null

    if ($apkCheck.profileInApk -and $trace.verified) {
        $evidence.verdict = 'PASS'
        $evidence.reason = 'The generated profile is in the APK: one of its rules is present in the merged variant profile, and the compiled binary profile SHA-256 matches assets/dexopt/baseline.prof. Runtime install/benefit is NOT measured here.'
        $exitCode = 0
    } elseif ($apkCheck.profileInApk) {
        $evidence.verdict = 'FAIL'
        $evidence.reason = 'APK contains a baseline.prof, but it cannot be attributed to the generated profile (merged profile missing the source rule, or the compiled profile SHA differs). Library AAR profiles alone can produce assets/dexopt/baseline.prof, so this is not accepted as proof.'
        $exitCode = 1
    } else {
        $evidence.verdict = 'FAIL'
        $evidence.reason = 'APK built but assets/dexopt/baseline.prof is missing or empty; the profile did not reach the package.'
        $exitCode = 1
    }
} catch {
    $message = $_.Exception.Message
    if ($message -like '*MCA build lock is held*') {
        # Another live build owns the shared lock. Nothing was built here, so this is BLOCKED,
        # not a failed profile/packaging attempt.
        $evidence.verdict = 'BLOCKED'
        $evidence.reason = ('Shared MCA build lock is held by another live build; re-run after it finishes. ' + $message)
        $steps.Add([pscustomobject]@{ name = 'build-lock'; status = 'BLOCKED'; detail = $message }) | Out-Null
        $exitCode = 3
    } else {
        $evidence.verdict = 'FAIL'
        $evidence.reason = ('Unhandled error: ' + $message)
        $steps.Add([pscustomobject]@{ name = 'error'; status = 'FAIL'; detail = $message }) | Out-Null
        $exitCode = 1
    }
} finally {
    if (-not $KeepProfileInSource -and (Test-Path -LiteralPath $sourcePath)) {
        if ($hadPreviousProfile -and (Test-Path -LiteralPath $backupPath)) {
            Copy-Item -LiteralPath $backupPath -Destination $sourcePath -Force
            $evidence.sourceRestored = 'restored-previous'
        } else {
            Remove-Item -LiteralPath $sourcePath -Force
            $evidence.sourceRestored = 'removed-generated-copy'
        }
    } elseif ($KeepProfileInSource) {
        $evidence.sourceRestored = 'kept-by-request'
    }
    $evidence.steps = @($steps | ForEach-Object { $_ })
    Write-Evidence -Evidence $evidence -Directory $OutDir
    Format-Steps -Steps @($steps | ForEach-Object { $_ })
    if ($lock) { Exit-McaBuildLock -Lock $lock | Out-Null }
}

Write-Host ("[verdict] {0}: {1}" -f $evidence.verdict, $evidence.reason)
if ($evidence.apk -and $evidence.apk.profileInApk) {
    Write-Host ("[apk] {0} sha256={1}" -f $evidence.apk.path, $evidence.apk.sha256)
}
exit $exitCode
