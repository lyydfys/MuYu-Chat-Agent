[CmdletBinding()]
param(
    [string]$ConfigPath = "",
    [string]$Adb = "D:\model\android-sdk\platform-tools\adb.exe",
    [string]$Serial = "",
    [string]$PackageName = "com.muyuchat.mca",
    [string]$OutDir = "docs\experiments\device-benchmarks",
    [string]$SessionId = "",
    [string]$ApiKey = "",
    [int]$ApiHostPort = 18435,
    [int]$ApiDevicePort = 11435,
    [switch]$NoFailOnRunError,
    [switch]$ValidateConfigOnly,
    [switch]$OfflineValidation,
    [string]$FixtureRoot = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
Import-Module (Join-Path $PSScriptRoot "benchmark-validation.psm1") -Force

function Get-PropertyValue {
    param($Object, [string]$Name)
    if ($null -eq $Object) { return $null }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function Get-NestedValue {
    param($Object, [string[]]$Path)
    $current = $Object
    foreach ($part in $Path) {
        $current = Get-PropertyValue -Object $current -Name $part
        if ($null -eq $current) { return $null }
    }
    return $current
}

function Get-UnresolvedPlaceholderPaths {
    param($Value, [string]$RootPath = '$')
    $paths = New-Object System.Collections.Generic.List[string]
    function Visit-PlaceholderValue {
        param($Current, [string]$Path)
        if ($null -eq $Current) { return }
        if ($Current -is [string]) {
            if ($Current -match '__SET_[A-Z0-9_]+__|@@[A-Z0-9_]+@@') {
                [void]$paths.Add($Path)
            }
            return
        }
        if ($Current -is [pscustomobject]) {
            foreach ($property in $Current.PSObject.Properties) {
                Visit-PlaceholderValue -Current $property.Value -Path "$Path.$($property.Name)"
            }
            return
        }
        if ($Current -is [Collections.IDictionary]) {
            foreach ($key in $Current.Keys) {
                Visit-PlaceholderValue -Current $Current[$key] -Path "$Path.$key"
            }
            return
        }
        if ($Current -is [Collections.IList] -or $Current -is [array]) {
            for ($index = 0; $index -lt $Current.Count; $index++) {
                Visit-PlaceholderValue -Current $Current[$index] -Path "$Path[$index]"
            }
        }
    }
    Visit-PlaceholderValue -Current $Value -Path $RootPath
    return @($paths.ToArray())
}

function Get-FirstValue {
    param([object[]]$Values)
    foreach ($value in $Values) {
        if ($null -eq $value) { continue }
        if ($value -is [string] -and [string]::IsNullOrWhiteSpace($value)) { continue }
        return $value
    }
    return $null
}

function Convert-ToNullableDouble {
    param($Value)
    if ($null -eq $Value) { return $null }
    $number = $null
    if ($Value -is [byte] -or $Value -is [int16] -or $Value -is [int32] -or
        $Value -is [int64] -or $Value -is [single] -or $Value -is [double] -or
        $Value -is [decimal]) {
        $number = [double]$Value
    } else {
        $parsed = 0.0
        $style = [Globalization.NumberStyles]::Float -bor [Globalization.NumberStyles]::AllowThousands
        if ([double]::TryParse("$Value", $style, [Globalization.CultureInfo]::InvariantCulture, [ref]$parsed)) {
            $number = $parsed
        }
    }
    if ($null -eq $number -or [double]::IsNaN($number) -or [double]::IsInfinity($number)) { return $null }
    return $number
}

function Convert-ToBool {
    param($Value, [bool]$Fallback = $false)
    if ($null -eq $Value) { return $Fallback }
    if ($Value -is [bool]) { return $Value }
    $parsed = $false
    if ([bool]::TryParse("$Value", [ref]$parsed)) { return $parsed }
    return $Fallback
}

function Resolve-WorkspacePath {
    param([string]$Path)
    if ([IO.Path]::IsPathRooted($Path)) {
        return [IO.Path]::GetFullPath($Path)
    }
    return [IO.Path]::GetFullPath((Join-Path (Get-Location) $Path))
}

function Write-Utf8Text {
    param([string]$Path, [string]$Content)
    $parent = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    [IO.File]::WriteAllText($Path, $Content, $script:Utf8NoBom)
}

function Get-ArtifactContract {
    param([string]$ArtifactType)
    switch ($ArtifactType) {
        "real_device_failure_diagnostics" {
            return [pscustomobject]@{ required = @("capturedAt", "runId", "reason", "observedPid", "lastTelemetrySample", "evidence") }
        }
        "real_device_telemetry" {
            return [pscustomobject]@{ required = @("runId", "summary", "samples") }
        }
        "real_device_run" {
            return [pscustomobject]@{ required = @("caseId", "kind", "runId", "status", "phase", "phaseRunIndex", "aggregateEligible", "startedAt", "endedAt", "durationMs") }
        }
        "real_device_cooldown" {
            return [pscustomobject]@{ required = @("transitionId", "fromRunId", "toPhase", "status", "startedAt", "endedAt", "durationMs", "conditions", "samples") }
        }
        "real_device_session" {
            return [pscustomobject]@{ required = @("sessionId", "status", "startedAt", "endedAt", "configSourcePath", "device", "cases", "runs", "cooldowns", "artifacts") }
        }
        "benchmark_config_validation" {
            return [pscustomobject]@{ required = @("valid", "configSchemaVersion", "configArtifactType", "configPath", "configSha256", "configEncoding", "caseCount", "cases") }
        }
        "benchmark_offline_validation" {
            return [pscustomobject]@{ required = @("status", "startedAt", "endedAt", "fixtureRoot", "testCount", "tests") }
        }
        default { throw "Unknown benchmark JSON artifactType '$ArtifactType'." }
    }
}

function Write-Utf8Json {
    param([string]$Path, $Value, [int]$Depth = 40)
    $artifactType = Get-PropertyValue -Object $Value -Name "artifactType"
    if ([string]::IsNullOrWhiteSpace("$artifactType")) {
        throw "Refusing to write untyped benchmark JSON artifact: $Path"
    }
    $contract = Get-ArtifactContract -ArtifactType "$artifactType"
    Write-ValidatedBenchmarkJson -Path $Path -Value $Value -ExpectedArtifactType "$artifactType" -Depth $Depth -RequiredProperties $contract.required
}

function Convert-ToSafeId {
    param([string]$Value)
    $safe = ($Value.ToLowerInvariant() -replace "[^a-z0-9._-]+", "-").Trim("-", ".")
    if ([string]::IsNullOrWhiteSpace($safe)) { return "case" }
    return $safe
}

function Get-CaseSetting {
    param($Case, $Defaults, [string]$Name, $Fallback)
    $value = Get-PropertyValue -Object $Case -Name $Name
    if ($null -ne $value) { return $value }
    $value = Get-PropertyValue -Object $Defaults -Name $Name
    if ($null -ne $value) { return $value }
    return $Fallback
}

function Get-ValidatedIntegerSetting {
    param($Case, $Defaults, [string]$Name, [int]$Fallback, [int]$Minimum = 0)
    $raw = Get-CaseSetting -Case $Case -Defaults $Defaults -Name $Name -Fallback $Fallback
    $number = Convert-ToNullableDouble $raw
    if ($null -eq $number -or $number -ne [Math]::Truncate($number) -or $number -lt $Minimum -or $number -gt [int]::MaxValue) {
        $caseId = if ($null -ne $Case) { "$(Get-PropertyValue -Object $Case -Name 'id')" } else { "defaults" }
        throw "Setting '$Name' for '$caseId' must be an integer greater than or equal to $Minimum."
    }
    return [int]$number
}

function Get-CaseRunPlan {
    param($Case, $Defaults)
    $measuredRuns = Get-ValidatedIntegerSetting -Case $Case -Defaults $Defaults -Name "runs" -Fallback 3 -Minimum 3
    $warmupRuns = Get-ValidatedIntegerSetting -Case $Case -Defaults $Defaults -Name "warmupRuns" -Fallback 0 -Minimum 0
    $plan = New-Object System.Collections.Generic.List[object]
    $runIndex = 0
    foreach ($phase in @(
        [pscustomobject]@{ name = "warmup"; count = $warmupRuns; aggregateEligible = $false },
        [pscustomobject]@{ name = "measured"; count = $measuredRuns; aggregateEligible = $true }
    )) {
        for ($phaseRunIndex = 1; $phaseRunIndex -le $phase.count; $phaseRunIndex++) {
            $runIndex++
            [void]$plan.Add([pscustomobject][ordered]@{
                runIndex = $runIndex
                phase = $phase.name
                phaseRunIndex = $phaseRunIndex
                aggregateEligible = [bool]$phase.aggregateEligible
            })
        }
    }
    return @($plan.ToArray())
}

function Read-BenchmarkConfig {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Benchmark config not found: $Path"
    }
    try { $document = Read-StrictUtf8JsonFile -Path $Path }
    catch { throw "Benchmark config is not strict UTF-8 JSON: $($_.Exception.Message)" }
    $config = $document.value
    try { Assert-JsonStringIntegrity -Value $config }
    catch { throw "Benchmark config text validation failed: $($_.Exception.Message)" }
    $script:LastConfigDocument = $document

    $schemaVersion = Get-PropertyValue -Object $config -Name "schemaVersion"
    if ($schemaVersion -isnot [int] -or $schemaVersion -ne 1) { throw "Benchmark config schemaVersion must be integer 1." }
    $artifactType = Get-PropertyValue -Object $config -Name "artifactType"
    if ($artifactType -isnot [string] -or $artifactType -cne "benchmark_config") {
        throw "Benchmark config artifactType must be 'benchmark_config'."
    }
    $defaults = Get-PropertyValue -Object $config -Name "defaults"
    if ($null -ne $defaults -and $defaults -isnot [pscustomobject]) {
        throw "Benchmark config defaults must be a JSON object."
    }

    $caseValue = Get-PropertyValue -Object $config -Name "cases"
    if ($null -eq $caseValue) { throw "Benchmark config must contain at least one case." }
    if ($caseValue -isnot [array] -and $caseValue -isnot [Collections.IList]) {
        throw "Benchmark config cases must be a JSON array."
    }
    $cases = @($caseValue)
    if ($cases.Count -eq 0) { throw "Benchmark config must contain at least one case." }
    $seen = @{}
    $safeSeen = @{}
    foreach ($case in $cases) {
        if ($case -isnot [pscustomobject]) { throw "Each benchmark case must be a JSON object." }
        $id = "$(Get-PropertyValue -Object $case -Name 'id')".Trim()
        $kind = "$(Get-PropertyValue -Object $case -Name 'kind')".Trim().ToLowerInvariant()
        if ([string]::IsNullOrWhiteSpace($id)) { throw "Each benchmark case requires a non-empty id." }
        if ($seen.ContainsKey($id)) { throw "Duplicate benchmark case id: $id" }
        if ($kind -notin @("chat", "vlm", "image", "image_store")) {
            throw "Case '$id' has unsupported kind '$kind'. Use chat, vlm, image, or image_store."
        }
        $definitionOnly = Get-PropertyValue -Object $case -Name "definitionOnly"
        if ($null -ne $definitionOnly -and $definitionOnly -isnot [bool]) {
            throw "Case '$id' definitionOnly must be a JSON boolean."
        }
        [void](Get-ValidatedIntegerSetting -Case $case -Defaults $defaults -Name "runs" -Fallback 3 -Minimum 3)
        [void](Get-ValidatedIntegerSetting -Case $case -Defaults $defaults -Name "warmupRuns" -Fallback 0 -Minimum 0)
        [void](Get-ValidatedIntegerSetting -Case $case -Defaults $defaults -Name "cooldownSeconds" -Fallback 30 -Minimum 0)
        [void](Get-ValidatedIntegerSetting -Case $case -Defaults $defaults -Name "maximumCooldownSeconds" -Fallback 300 -Minimum 1)
        $safeId = Convert-ToSafeId $id
        if ($safeSeen.ContainsKey($safeId)) {
            throw "Case ids '$($safeSeen[$safeId])' and '$id' both map to artifact id '$safeId'. Use distinct ASCII ids."
        }
        $seen[$id] = $true
        $safeSeen[$safeId] = $id
    }
    $script:LastConfigPlaceholderPaths = @(Get-UnresolvedPlaceholderPaths -Value $config)
    return $config
}

function Get-DefaultActivity {
    param([string]$Kind)
    switch ($Kind) {
        "chat" { return ".debug.LocalChatSmokeActivity" }
        "vlm" { return ".debug.LocalChatSmokeActivity" }
        "image" { return ".debug.LocalImageSmokeActivity" }
        "image_store" { return ".debug.LocalImageStoreSmokeActivity" }
        default { throw "Unsupported smoke kind: $Kind" }
    }
}

function Get-RemoteSmokePath {
    param([string]$Kind, [string]$RunId)
    $folder = if ($Kind -in @("chat", "vlm")) { "chat_smoke" } else { "image_bench" }
    return "/storage/emulated/0/Android/data/$PackageName/files/$folder/runs/$RunId.json"
}

function Convert-AdbShellArg {
    param([string]$Value)
    if ($null -eq $Value) { return "''" }
    return "'" + $Value.Replace("'", "'\''") + "'"
}

function Convert-ToInvariantString {
    param($Value)
    if ($Value -is [single] -or $Value -is [double] -or $Value -is [decimal]) {
        return ([double]$Value).ToString("R", [Globalization.CultureInfo]::InvariantCulture)
    }
    return [Convert]::ToString($Value, [Globalization.CultureInfo]::InvariantCulture)
}

function Convert-SmokeExtraToArgs {
    param([string]$Name, $Value)
    if ($null -eq $Value) { return @() }

    $type = Get-PropertyValue -Object $Value -Name "type"
    if ($null -ne $type) {
        $rawValue = Get-PropertyValue -Object $Value -Name "value"
        if ($null -eq $rawValue) { return @() }
        $type = "$type".Trim().ToLowerInvariant()
    } else {
        $rawValue = $Value
        if ($rawValue -is [array] -or $rawValue -is [pscustomobject]) {
            throw "Extra '$Name' must be a scalar or an explicit type/value object."
        }
        if ($rawValue -is [bool]) { $type = "bool" }
        elseif ($rawValue -is [byte] -or $rawValue -is [int16] -or $rawValue -is [int32]) { $type = "int" }
        elseif ($rawValue -is [int64]) {
            if ($rawValue -ge [int]::MinValue -and $rawValue -le [int]::MaxValue) { $type = "int" } else { $type = "long" }
        }
        elseif ($rawValue -is [single] -or $rawValue -is [double] -or $rawValue -is [decimal]) { $type = "float" }
        else { $type = "string" }
    }

    if ($rawValue -is [array] -or $rawValue -is [pscustomobject]) {
        throw "Extra '$Name' value must be a scalar."
    }

    switch ($type) {
        "string" { return @("--es", $Name, "$rawValue") }
        "bool" {
            $boolValue = $false
            if ($rawValue -is [bool]) {
                $boolValue = $rawValue
            } elseif (-not [bool]::TryParse("$rawValue", [ref]$boolValue)) {
                throw "Extra '$Name' value '$rawValue' is not a boolean."
            }
            $boolText = if ($boolValue) { "true" } else { "false" }
            return @("--ez", $Name, $boolText)
        }
        "int" { return @("--ei", $Name, (Convert-ToInvariantString ([int]$rawValue))) }
        "long" { return @("--el", $Name, (Convert-ToInvariantString ([long]$rawValue))) }
        "float" { return @("--ef", $Name, (Convert-ToInvariantString ([double]$rawValue))) }
        default { throw "Extra '$Name' has unsupported explicit type '$type'." }
    }
}

function Invoke-AdbCapture {
    param([string[]]$Arguments, [switch]$WithoutSerial)
    $adbArgs = @()
    if (-not $WithoutSerial -and -not [string]::IsNullOrWhiteSpace($script:Serial)) {
        $adbArgs += @("-s", $script:Serial)
    }
    $adbArgs += $Arguments
    $oldPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $exitCode = -1
    $output = @()
    $exceptionText = $null
    try {
        $output = @(& $Adb @adbArgs 2>&1)
        $exitCode = $LASTEXITCODE
    } catch {
        $exceptionText = $_.Exception.Message
    } finally {
        $ErrorActionPreference = $oldPreference
    }
    $text = (@($output | ForEach-Object { "$_" }) -join [Environment]::NewLine).Trim()
    if (-not [string]::IsNullOrWhiteSpace($exceptionText)) {
        $pieces = @($text, $exceptionText) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
        $text = $pieces -join [Environment]::NewLine
    }
    return [pscustomobject]@{
        ok = ($exitCode -eq 0 -and [string]::IsNullOrWhiteSpace($exceptionText))
        exitCode = $exitCode
        text = $text
    }
}

function Invoke-AdbChecked {
    param([string[]]$Arguments, [switch]$WithoutSerial)
    $result = Invoke-AdbCapture -Arguments $Arguments -WithoutSerial:$WithoutSerial
    if (-not $result.ok) {
        $message = "ADB command failed (exit $($result.exitCode)): adb $($Arguments -join ' ')" +
            [Environment]::NewLine + $result.text
        throw $message
    }
    return $result.text
}

function Require-AdbDevice {
    if (-not (Test-Path -LiteralPath $Adb -PathType Leaf) -and -not (Get-Command $Adb -ErrorAction SilentlyContinue)) {
        throw "ADB executable not found: $Adb"
    }
    $result = Invoke-AdbCapture -Arguments @("devices", "-l") -WithoutSerial
    if (-not $result.ok) { throw "Unable to list ADB devices: $($result.text)" }
    $devices = @()
    foreach ($line in @($result.text -split "\r?\n" | Select-Object -Skip 1)) {
        $parts = $line.Trim() -split "\s+"
        if ($parts.Count -ge 2 -and $parts[1] -eq "device") { $devices += $parts[0] }
    }
    if (-not [string]::IsNullOrWhiteSpace($script:Serial)) {
        if ($script:Serial -notin $devices) { throw "ADB device '$script:Serial' is not connected or authorized." }
    } elseif ($devices.Count -eq 1) {
        $script:Serial = $devices[0]
    } elseif ($devices.Count -eq 0) {
        throw "No connected and authorized ADB device was found."
    } else {
        throw "Multiple ADB devices are connected. Pass -Serial."
    }
}

function Get-AdbProperty {
    param([string]$Name)
    $result = Invoke-AdbCapture -Arguments @("shell", "getprop", $Name)
    if ($result.ok -and -not [string]::IsNullOrWhiteSpace($result.text)) { return $result.text.Trim() }
    return $null
}

function Get-DeviceMetadata {
    $adbVersion = Invoke-AdbCapture -Arguments @("version") -WithoutSerial
    return [ordered]@{
        serial = $script:Serial
        manufacturer = Get-AdbProperty "ro.product.manufacturer"
        model = Get-AdbProperty "ro.product.model"
        device = Get-AdbProperty "ro.product.device"
        soc = Get-FirstValue @(
            (Get-AdbProperty "ro.soc.model"),
            (Get-AdbProperty "ro.board.platform")
        )
        android = Get-AdbProperty "ro.build.version.release"
        sdk = Get-AdbProperty "ro.build.version.sdk"
        buildFingerprint = Get-AdbProperty "ro.build.fingerprint"
        adbVersion = if ($adbVersion.ok) { ($adbVersion.text -split "\r?\n" | Select-Object -First 1) } else { $null }
    }
}

function Convert-BatteryTemperatureC {
    param($RawValue)
    $value = Convert-ToNullableDouble $RawValue
    if ($null -eq $value) { return $null }
    if ([Math]::Abs($value) -gt 1000) { $value = $value / 1000.0 }
    elseif ([Math]::Abs($value) -gt 100) { $value = $value / 10.0 }
    return [Math]::Round($value, 2)
}

function Get-ThermalStatusName {
    param($Rank)
    $value = Convert-ToNullableDouble $Rank
    if ($null -eq $value) { return $null }
    switch ([int]$value) {
        0 { return "NONE" }
        1 { return "LIGHT" }
        2 { return "MODERATE" }
        3 { return "SEVERE" }
        4 { return "CRITICAL" }
        5 { return "EMERGENCY" }
        6 { return "SHUTDOWN" }
        default { return $null }
    }
}

function Get-ThermalStatusRank {
    param($Value)
    if ($null -eq $Value) { return $null }
    $number = Convert-ToNullableDouble $Value
    if ($null -ne $number -and $number -ge 0 -and $number -le 6) { return [int]$number }
    switch ("$Value".Trim().ToUpperInvariant()) {
        "NONE" { return 0 }
        "LIGHT" { return 1 }
        "MODERATE" { return 2 }
        "SEVERE" { return 3 }
        "CRITICAL" { return 4 }
        "EMERGENCY" { return 5 }
        "SHUTDOWN" { return 6 }
        default { return $null }
    }
}

function Get-BatteryChargingState {
    param($Status, $Plugged)
    $statusNumber = Convert-ToNullableDouble $Status
    if ($null -ne $statusNumber) {
        switch ([int]$statusNumber) {
            2 { return "charging" }
            3 { return "discharging" }
            4 { return "not_charging" }
            5 { return "full" }
        }
    }
    $pluggedNumber = Convert-ToNullableDouble $Plugged
    if ($null -ne $pluggedNumber -and $pluggedNumber -gt 0) { return "plugged_unknown" }
    return "unknown"
}

function Get-TelemetrySample {
    param([DateTimeOffset]$RunStartedAt)
    $packageArg = Convert-AdbShellArg $PackageName
    $command = 'pids=$(pidof ' + $packageArg + ' 2>/dev/null); set -- $pids; pid=$1; ' +
        'echo "__MCA_PID__=$pid"; echo "__MCA_STATUS__"; ' +
        'if [ -n "$pid" ]; then cat /proc/$pid/status 2>/dev/null; fi; ' +
        'echo "__MCA_MEMINFO__"; cat /proc/meminfo 2>/dev/null; ' +
        'echo "__MCA_BATTERY__"; dumpsys battery 2>/dev/null; ' +
        'echo "__MCA_BATTERY_SYSFS__"; cat /sys/class/power_supply/battery/temp 2>/dev/null; ' +
        'echo "__MCA_THERMAL__"; dumpsys thermalservice 2>/dev/null'
    $capture = Invoke-AdbCapture -Arguments @("shell", $command)
    $text = $capture.text
    $errors = @()
    if (-not $capture.ok) { $errors += "telemetry shell failed: $($capture.text)" }

    $processId = $null
    $pidMatch = [regex]::Match($text, '(?m)^__MCA_PID__=(\d+)\s*$')
    if ($pidMatch.Success) { $processId = [int]$pidMatch.Groups[1].Value }

    $rssKb = $null
    $rssSource = $null
    $rssMatch = [regex]::Match($text, '(?m)^VmRSS:\s*([0-9]+)\s*kB\s*$')
    if ($rssMatch.Success) {
        $rssKb = [double]$rssMatch.Groups[1].Value
        $rssSource = "proc_status_vmrss"
    } elseif ($null -ne $processId) {
        $meminfo = Invoke-AdbCapture -Arguments @("shell", "dumpsys", "meminfo", $PackageName)
        if ($meminfo.ok) {
            $fallbackMatch = [regex]::Match($meminfo.text, '(?im)TOTAL RSS:\s*([0-9,]+)')
            if ($fallbackMatch.Success) {
                $rssKb = [double]($fallbackMatch.Groups[1].Value -replace ",", "")
                $rssSource = "dumpsys_total_rss"
            }
        } else {
            $errors += "dumpsys meminfo failed: $($meminfo.text)"
        }
    }

    $availableRamKb = $null
    $availableMatch = [regex]::Match($text, '(?m)^MemAvailable:\s*([0-9]+)\s*kB\s*$')
    if ($availableMatch.Success) { $availableRamKb = [double]$availableMatch.Groups[1].Value }

    $dmaBufKb = $null
    $dmaMatch = [regex]::Match($text, '(?m)^DmaBuf:\s*([0-9]+)\s*kB\s*$')
    if ($dmaMatch.Success) { $dmaBufKb = [double]$dmaMatch.Groups[1].Value }
    $cmaTotalKb = $null
    $cmaTotalMatch = [regex]::Match($text, '(?m)^CmaTotal:\s*([0-9]+)\s*kB\s*$')
    if ($cmaTotalMatch.Success) { $cmaTotalKb = [double]$cmaTotalMatch.Groups[1].Value }
    $cmaFreeKb = $null
    $cmaFreeMatch = [regex]::Match($text, '(?m)^CmaFree:\s*([0-9]+)\s*kB\s*$')
    if ($cmaFreeMatch.Success) { $cmaFreeKb = [double]$cmaFreeMatch.Groups[1].Value }

    $batteryTemperatureC = $null
    $batterySource = $null
    $batteryBlock = [regex]::Match($text, '(?ms)^__MCA_BATTERY__\s*$(.*?)(?=^__MCA_BATTERY_SYSFS__\s*$)')
    if ($batteryBlock.Success) {
        $temperatureMatch = [regex]::Match($batteryBlock.Groups[1].Value, '(?im)^\s*temperature:\s*(-?[0-9.]+)\s*$')
        if ($temperatureMatch.Success) {
            $batteryTemperatureC = Convert-BatteryTemperatureC $temperatureMatch.Groups[1].Value
            $batterySource = "dumpsys_battery"
        }
    }
    if ($null -eq $batteryTemperatureC) {
        $sysfsMatch = [regex]::Match($text, '(?ms)^__MCA_BATTERY_SYSFS__\s*$\s*(-?[0-9.]+)')
        if ($sysfsMatch.Success) {
            $batteryTemperatureC = Convert-BatteryTemperatureC $sysfsMatch.Groups[1].Value
            $batterySource = "battery_sysfs"
        }
    }

    $batteryLevelPercent = $null
    $batteryStatus = $null
    $batteryPlugged = $null
    if ($batteryBlock.Success) {
        $levelMatch = [regex]::Match($batteryBlock.Groups[1].Value, '(?im)^\s*level:\s*([0-9]+)\s*$')
        if ($levelMatch.Success) { $batteryLevelPercent = [double]$levelMatch.Groups[1].Value }
        $statusMatch = [regex]::Match($batteryBlock.Groups[1].Value, '(?im)^\s*status:\s*([0-9]+)\s*$')
        if ($statusMatch.Success) { $batteryStatus = [int]$statusMatch.Groups[1].Value }
        $pluggedMatch = [regex]::Match($batteryBlock.Groups[1].Value, '(?im)^\s*plugged:\s*([0-9]+)\s*$')
        if ($pluggedMatch.Success) { $batteryPlugged = [int]$pluggedMatch.Groups[1].Value }
    }
    $chargingState = Get-BatteryChargingState -Status $batteryStatus -Plugged $batteryPlugged

    $thermalRank = $null
    $thermalBlock = [regex]::Match($text, '(?ms)^__MCA_THERMAL__\s*$(.*)$')
    if ($thermalBlock.Success) {
        $thermalMatch = [regex]::Match(
            $thermalBlock.Groups[1].Value,
            '(?im)(?:Thermal\s+Status|mStatus|CurrentThermalStatus)\s*[:=]\s*([0-6])\b'
        )
        if ($thermalMatch.Success) { $thermalRank = [int]$thermalMatch.Groups[1].Value }
    }

    $now = [DateTimeOffset]::Now
    return [pscustomobject][ordered]@{
        timestamp = $now.ToString("o")
        elapsedMs = [Math]::Round(($now - $RunStartedAt).TotalMilliseconds)
        pid = $processId
        appRssKb = $rssKb
        appRssSource = $rssSource
        availableRamKb = $availableRamKb
        dmaBufKb = $dmaBufKb
        cmaTotalKb = $cmaTotalKb
        cmaFreeKb = $cmaFreeKb
        batteryTemperatureC = $batteryTemperatureC
        batteryTemperatureSource = $batterySource
        batteryLevelPercent = $batteryLevelPercent
        batteryStatus = $batteryStatus
        batteryPlugged = $batteryPlugged
        chargingState = $chargingState
        thermalStatusRank = $thermalRank
        thermalStatus = Get-ThermalStatusName $thermalRank
        errors = @($errors)
    }
}

function Get-TelemetrySummary {
    param([object[]]$Samples)
    if ($null -eq $Samples) { $Samples = @() }
    $rss = @($Samples | ForEach-Object { Convert-ToNullableDouble $_.appRssKb } | Where-Object { $null -ne $_ })
    $available = @($Samples | ForEach-Object { Convert-ToNullableDouble $_.availableRamKb } | Where-Object { $null -ne $_ })
    $battery = @($Samples | ForEach-Object { Convert-ToNullableDouble $_.batteryTemperatureC } | Where-Object { $null -ne $_ })
    $levels = @($Samples | ForEach-Object { Convert-ToNullableDouble $_.batteryLevelPercent } | Where-Object { $null -ne $_ })
    $dmaBuf = @($Samples | ForEach-Object { Convert-ToNullableDouble $_.dmaBufKb } | Where-Object { $null -ne $_ })
    $cmaFree = @($Samples | ForEach-Object { Convert-ToNullableDouble $_.cmaFreeKb } | Where-Object { $null -ne $_ })
    $thermalSamples = @($Samples | Where-Object { $null -ne (Get-ThermalStatusRank $_.thermalStatusRank) })
    $rssSources = @($Samples | ForEach-Object { Get-PropertyValue -Object $_ -Name "appRssSource" } | Where-Object { -not [string]::IsNullOrWhiteSpace("$_") } | Select-Object -Unique)
    $batterySources = @($Samples | ForEach-Object { Get-PropertyValue -Object $_ -Name "batteryTemperatureSource" } | Where-Object { -not [string]::IsNullOrWhiteSpace("$_") } | Select-Object -Unique)

    $availableStart = if ($available.Count -gt 0) { $available[0] } else { $null }
    $availableEnd = if ($available.Count -gt 0) { $available[$available.Count - 1] } else { $null }
    $batteryStart = if ($battery.Count -gt 0) { $battery[0] } else { $null }
    $batteryEnd = if ($battery.Count -gt 0) { $battery[$battery.Count - 1] } else { $null }
    $rssStart = if ($rss.Count -gt 0) { $rss[0] } else { $null }
    $rssEnd = if ($rss.Count -gt 0) { $rss[$rss.Count - 1] } else { $null }
    $thermalStartRank = if ($thermalSamples.Count -gt 0) { Get-ThermalStatusRank $thermalSamples[0].thermalStatusRank } else { $null }
    $thermalEndRank = if ($thermalSamples.Count -gt 0) { Get-ThermalStatusRank $thermalSamples[$thermalSamples.Count - 1].thermalStatusRank } else { $null }
    $thermalMaxRank = if ($thermalSamples.Count -gt 0) {
        @($thermalSamples | ForEach-Object { Get-ThermalStatusRank $_.thermalStatusRank } | Measure-Object -Maximum)[0].Maximum
    } else { $null }
    $chargingStates = @($Samples | ForEach-Object { $_.chargingState } | Where-Object { -not [string]::IsNullOrWhiteSpace("$_") } | Select-Object -Unique)
    return [ordered]@{
        sampleCount = $Samples.Count
        rssStartMb = if ($null -ne $rssStart) { [Math]::Round($rssStart / 1024.0, 2) } else { $null }
        peakRssMb = if ($rss.Count -gt 0) { [Math]::Round(($rss | Measure-Object -Maximum).Maximum / 1024.0, 2) } else { $null }
        rssEndMb = if ($null -ne $rssEnd) { [Math]::Round($rssEnd / 1024.0, 2) } else { $null }
        appRssStartMb = if ($null -ne $rssStart) { [Math]::Round($rssStart / 1024.0, 2) } else { $null }
        peakAppRssMb = if ($rss.Count -gt 0) { [Math]::Round(($rss | Measure-Object -Maximum).Maximum / 1024.0, 2) } else { $null }
        appRssEndMb = if ($null -ne $rssEnd) { [Math]::Round($rssEnd / 1024.0, 2) } else { $null }
        appRssSources = $rssSources
        availableRamStartMb = if ($null -ne $availableStart) { [Math]::Round($availableStart / 1024.0, 2) } else { $null }
        availableRamEndMb = if ($null -ne $availableEnd) { [Math]::Round($availableEnd / 1024.0, 2) } else { $null }
        availableRamMinMb = if ($available.Count -gt 0) { [Math]::Round(($available | Measure-Object -Minimum).Minimum / 1024.0, 2) } else { $null }
        batteryTemperatureStartC = $batteryStart
        batteryTemperatureEndC = $batteryEnd
        batteryTemperatureMaxC = if ($battery.Count -gt 0) { [Math]::Round(($battery | Measure-Object -Maximum).Maximum, 2) } else { $null }
        batteryTemperatureDeltaC = if ($null -ne $batteryStart -and $null -ne $batteryEnd) { [Math]::Round($batteryEnd - $batteryStart, 2) } else { $null }
        batteryTemperatureSources = $batterySources
        batteryLevelStartPercent = if ($levels.Count -gt 0) { $levels[0] } else { $null }
        batteryLevelEndPercent = if ($levels.Count -gt 0) { $levels[$levels.Count - 1] } else { $null }
        chargingState = if ($chargingStates.Count -eq 1) { $chargingStates[0] } elseif ($chargingStates.Count -gt 1) { $chargingStates -join "->" } else { $null }
        thermalStatusStart = Get-ThermalStatusName $thermalStartRank
        thermalStatusMax = Get-ThermalStatusName $thermalMaxRank
        thermalStatusEnd = Get-ThermalStatusName $thermalEndRank
        thermalStatusStartRank = $thermalStartRank
        thermalStatusMaxRank = $thermalMaxRank
        thermalStatusEndRank = $thermalEndRank
        dmaBufStartMb = if ($dmaBuf.Count -gt 0) { [Math]::Round($dmaBuf[0] / 1024.0, 2) } else { $null }
        dmaBufPeakMb = if ($dmaBuf.Count -gt 0) { [Math]::Round(($dmaBuf | Measure-Object -Maximum).Maximum / 1024.0, 2) } else { $null }
        dmaBufEndMb = if ($dmaBuf.Count -gt 0) { [Math]::Round($dmaBuf[$dmaBuf.Count - 1] / 1024.0, 2) } else { $null }
        cmaFreeMinMb = if ($cmaFree.Count -gt 0) { [Math]::Round(($cmaFree | Measure-Object -Minimum).Minimum / 1024.0, 2) } else { $null }
    }
}

function Get-ProcessLifecycleState {
    param(
        $ObservedPid,
        $CurrentPid,
        [bool]$TerminalObserved = $false
    )
    $observed = Convert-ToNullableDouble $ObservedPid
    $current = Convert-ToNullableDouble $CurrentPid
    if ($null -ne $current) {
        if ($null -eq $observed) {
            return [pscustomobject]@{ state = "observed"; observedPid = [int]$current; failed = $false }
        }
        if ([int]$current -ne [int]$observed -and -not $TerminalObserved) {
            return [pscustomobject]@{ state = "process_restarted"; observedPid = [int]$observed; failed = $true }
        }
        return [pscustomobject]@{ state = "alive"; observedPid = [int]$observed; failed = $false }
    }
    if ($null -ne $observed -and -not $TerminalObserved) {
        return [pscustomobject]@{ state = "process_died"; observedPid = [int]$observed; failed = $true }
    }
    return [pscustomobject]@{ state = if ($TerminalObserved) { "terminal" } else { "not_observed" }; observedPid = $ObservedPid; failed = $false }
}

function Get-LimitedEvidenceText {
    param([AllowEmptyString()][string]$Text, [int]$MaximumCharacters = 24000)
    if ($null -eq $Text) { return $null }
    if ($Text.Length -le $MaximumCharacters) { return $Text }
    return "...[tail truncated to $MaximumCharacters characters]...`n" + $Text.Substring($Text.Length - $MaximumCharacters)
}

function Capture-FailureDiagnostics {
    param(
        [string]$Reason,
        [string]$RunId,
        [string]$Path,
        $LastTelemetrySample,
        $ObservedPid
    )
    $commands = @(
        [pscustomobject]@{ name = "logcat_crash"; args = @("logcat", "-d", "-v", "threadtime", "-b", "crash", "-t", "200") },
        [pscustomobject]@{ name = "logcat_main_system"; args = @("logcat", "-d", "-v", "threadtime", "-b", "main", "-b", "system", "-t", "300") },
        [pscustomobject]@{ name = "dropbox_data_app_crash"; args = @("shell", "dumpsys dropbox --print data_app_crash 2>/dev/null | tail -n 240") },
        [pscustomobject]@{ name = "dropbox_system_app_crash"; args = @("shell", "dumpsys dropbox --print system_app_crash 2>/dev/null | tail -n 240") },
        [pscustomobject]@{ name = "dropbox_data_app_anr"; args = @("shell", "dumpsys dropbox --print data_app_anr 2>/dev/null | tail -n 240") },
        [pscustomobject]@{ name = "dropbox_lowmem"; args = @("shell", "dumpsys dropbox --print lowmem 2>/dev/null | tail -n 240") },
        [pscustomobject]@{ name = "app_meminfo"; args = @("shell", "dumpsys", "meminfo", $PackageName) },
        [pscustomobject]@{ name = "proc_meminfo"; args = @("shell", "cat /proc/meminfo") },
        [pscustomobject]@{ name = "dmabuf"; args = @("shell", "(cat /sys/kernel/dmabuf/buffers 2>/dev/null || cat /d/dma_buf/bufinfo 2>/dev/null) | tail -n 400") },
        [pscustomobject]@{ name = "thermal"; args = @("shell", "dumpsys thermalservice 2>/dev/null | tail -n 300") }
    )
    $evidence = @()
    foreach ($command in $commands) {
        $capture = Invoke-AdbCapture -Arguments $command.args
        $evidence += [pscustomobject][ordered]@{
            name = $command.name
            ok = $capture.ok
            exitCode = $capture.exitCode
            text = Get-LimitedEvidenceText -Text $capture.text
        }
    }
    $artifact = [pscustomobject][ordered]@{
        schemaVersion = 1
        artifactType = "real_device_failure_diagnostics"
        capturedAt = [DateTimeOffset]::Now.ToString("o")
        runId = $RunId
        reason = $Reason
        observedPid = $ObservedPid
        lastTelemetrySample = $LastTelemetrySample
        evidence = $evidence
    }
    Write-Utf8Json -Path $Path -Value $artifact -Depth 20
    return $artifact
}

function Resolve-RemoteImagePath {
    param([string]$RemoteSmokePath, [AllowEmptyString()][string]$ReportedPath, [string]$RunId)
    if (-not [string]::IsNullOrWhiteSpace($ReportedPath)) {
        if ($ReportedPath.StartsWith('/')) { return $ReportedPath }
        $slash = $RemoteSmokePath.LastIndexOf('/')
        if ($slash -ge 0) { return $RemoteSmokePath.Substring(0, $slash + 1) + $ReportedPath }
    }
    $slash = $RemoteSmokePath.LastIndexOf('/')
    if ($slash -lt 0) { throw "Cannot resolve remote image directory from '$RemoteSmokePath'." }
    return $RemoteSmokePath.Substring(0, $slash + 1) + $RunId + ".png"
}

function Capture-ImageArtifact {
    param(
        $Case,
        [string]$RunId,
        [string]$RemoteSmokePath,
        [AllowEmptyString()][string]$ReportedPath,
        [string]$LocalPath,
        [string]$RelativePath
    )
    $remotePath = Resolve-RemoteImagePath -RemoteSmokePath $RemoteSmokePath -ReportedPath $ReportedPath -RunId $RunId
    $pull = Pull-RemoteFileBinary -RemotePath $remotePath -LocalPath $LocalPath
    if (-not $pull.ok) { throw $pull.error }
    $png = Assert-PngQuality -Path $LocalPath
    $expectedWidth = Convert-ToNullableDouble (Get-ConfiguredExtraValue -Case $Case -Name "width")
    $expectedHeight = Convert-ToNullableDouble (Get-ConfiguredExtraValue -Case $Case -Name "height")
    if ($null -ne $expectedWidth -and $png.width -ne [int]$expectedWidth) {
        throw "PNG width mismatch. Expected $([int]$expectedWidth), got $($png.width)."
    }
    if ($null -ne $expectedHeight -and $png.height -ne [int]$expectedHeight) {
        throw "PNG height mismatch. Expected $([int]$expectedHeight), got $($png.height)."
    }
    return [pscustomobject][ordered]@{
        remotePath = $remotePath
        localPath = $RelativePath
        bytes = $png.bytes
        width = $png.width
        height = $png.height
        chunkCount = $png.chunkCount
        signature = $png.signature
        sha256 = $png.sha256
        quality = $png.quality
    }
}

function Stop-SmokeApp {
    Invoke-AdbChecked -Arguments @("shell", "am", "force-stop", $PackageName) | Out-Null
    Start-Sleep -Milliseconds 500
}

function Remove-RemoteSmokeFile {
    param([string]$RemotePath)
    $quotedPath = Convert-AdbShellArg $RemotePath
    Invoke-AdbChecked -Arguments @("shell", "rm -f $quotedPath") | Out-Null
}

function Start-SmokeActivity {
    param($Case, [string]$Kind, [string]$RunId)
    $activity = Get-FirstValue @(
        (Get-PropertyValue -Object $Case -Name "activity"),
        (Get-DefaultActivity -Kind $Kind)
    )
    $component = if ("$activity".StartsWith(".")) { "$PackageName/$activity" } else { "$activity" }
    $commandArgs = @("am", "start", "-W", "-n", $component, "--es", "runId", $RunId)
    $extras = Get-PropertyValue -Object $Case -Name "extras"
    if ($null -ne $extras) {
        foreach ($property in $extras.PSObject.Properties) {
            if ($property.Name -eq "runId") { continue }
            $commandArgs += @(Convert-SmokeExtraToArgs -Name $property.Name -Value $property.Value)
        }
    }
    $command = ($commandArgs | ForEach-Object { Convert-AdbShellArg "$_" }) -join " "
    $output = Invoke-AdbChecked -Arguments @("shell", $command)
    if ($output -match "Error type 3|does not exist") {
        throw "Smoke activity could not be started: $component. $output"
    }
    return [pscustomobject]@{ activity = $activity; component = $component; output = $output }
}

function Test-RemoteFileExists {
    param([string]$RemotePath)
    $quotedPath = Convert-AdbShellArg $RemotePath
    $capture = Invoke-AdbCapture -Arguments @("shell", "if [ -f $quotedPath ]; then echo __MCA_EXISTS__; fi")
    return [pscustomobject]@{
        exists = ($capture.ok -and $capture.text.Trim() -eq "__MCA_EXISTS__")
        error = if ($capture.ok) { $null } else { $capture.text }
    }
}

function Pull-RemoteFileBinary {
    param([string]$RemotePath, [string]$LocalPath)
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $LocalPath) | Out-Null
    if (Test-Path -LiteralPath $LocalPath -PathType Leaf) {
        Remove-Item -LiteralPath $LocalPath -Force
    }
    $pull = Invoke-AdbCapture -Arguments @("pull", $RemotePath, $LocalPath)
    if (-not $pull.ok -or -not (Test-Path -LiteralPath $LocalPath -PathType Leaf)) {
        return [pscustomobject]@{ ok = $false; error = "adb pull failed for '$RemotePath': $($pull.text)" }
    }
    $length = (Get-Item -LiteralPath $LocalPath).Length
    if ($length -le 0) {
        Remove-Item -LiteralPath $LocalPath -Force -ErrorAction SilentlyContinue
        return [pscustomobject]@{ ok = $false; error = "adb pull produced an empty file for '$RemotePath'." }
    }
    return [pscustomobject]@{ ok = $true; error = $null; bytes = $length }
}

function Read-SmokeJson {
    param(
        [string]$Path,
        [string]$ExpectedRunId,
        [switch]$RequireTerminal
    )
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return [pscustomobject]@{ value = $null; contract = $null; error = "smoke JSON was not captured" }
    }
    try {
        $document = Read-StrictUtf8JsonFile -Path $Path
        $contract = Assert-SmokeResultDocument -Value $document.value -ExpectedRunId $ExpectedRunId -RequireTerminal:$RequireTerminal
        return [pscustomobject]@{ value = $document.value; contract = $contract; error = $null; document = $document }
    } catch {
        return [pscustomobject]@{ value = $null; contract = $null; error = "smoke JSON validation failed: $($_.Exception.Message)" }
    }
}

function Get-RemoteSmokeStatus {
    param(
        [string]$RemotePath,
        [string]$LocalPath,
        [string]$ExpectedRunId
    )
    $exists = Test-RemoteFileExists -RemotePath $RemotePath
    if (-not $exists.exists) {
        return [pscustomobject]@{ exists = $false; status = $null; isTerminal = $false; value = $null; error = $exists.error }
    }
    $pull = Pull-RemoteFileBinary -RemotePath $RemotePath -LocalPath $LocalPath
    if (-not $pull.ok) {
        return [pscustomobject]@{ exists = $true; status = $null; isTerminal = $false; value = $null; error = $pull.error }
    }
    $parsed = Read-SmokeJson -Path $LocalPath -ExpectedRunId $ExpectedRunId
    if ($null -ne $parsed.error) {
        return [pscustomobject]@{ exists = $true; status = $null; isTerminal = $false; value = $null; error = $parsed.error }
    }
    return [pscustomobject]@{
        exists = $true
        status = $parsed.contract.status
        isTerminal = $parsed.contract.isTerminal
        value = $parsed.value
        error = $null
    }
}

function Pull-RemoteSmokeFile {
    param([string]$RemotePath, [string]$LocalPath, [string]$ExpectedRunId)
    $pull = Pull-RemoteFileBinary -RemotePath $RemotePath -LocalPath $LocalPath
    if (-not $pull.ok) { return $pull.error }
    $parsed = Read-SmokeJson -Path $LocalPath -ExpectedRunId $ExpectedRunId -RequireTerminal
    return $parsed.error
}

function Get-ConfiguredExtraValue {
    param($Case, [string]$Name)
    $extras = Get-PropertyValue -Object $Case -Name "extras"
    $value = Get-PropertyValue -Object $extras -Name $Name
    if ($null -eq $value) { return $null }
    $explicitType = Get-PropertyValue -Object $value -Name "type"
    if ($null -ne $explicitType) { return Get-PropertyValue -Object $value -Name "value" }
    return $value
}

function Select-NumericMetric {
    param([object[]]$Candidates)
    foreach ($candidate in $Candidates) {
        $number = Convert-ToNullableDouble (Get-PropertyValue -Object $candidate -Name "value")
        if ($null -ne $number -and $number -gt 0) {
            return [pscustomobject]@{
                value = $number
                source = Get-PropertyValue -Object $candidate -Name "source"
            }
        }
    }
    return [pscustomobject]@{ value = $null; source = $null }
}

function Get-LastSmokeEvent {
    param([object[]]$Events, [string[]]$Statuses)
    $matches = @($Events | Where-Object {
        $status = "$(Get-PropertyValue -Object $_ -Name 'status')"
        $status -in $Statuses
    })
    if ($matches.Count -eq 0) { return $null }
    return $matches[$matches.Count - 1]
}

function Get-FirstSmokeEvent {
    param([object[]]$Events, [string[]]$Statuses)
    foreach ($event in $Events) {
        $status = "$(Get-PropertyValue -Object $event -Name 'status')"
        if ($status -in $Statuses) { return $event }
    }
    return $null
}

function Get-SumOfAvailableMetrics {
    param([object[]]$Values)
    $sum = 0.0
    $count = 0
    foreach ($value in $Values) {
        $number = Convert-ToNullableDouble $value
        if ($null -ne $number) {
            $sum += $number
            $count++
        }
    }
    if ($count -eq 0) { return $null }
    return $sum
}

function Convert-SmokeToMetrics {
    param($Smoke, $Case, [string]$Kind)
    $isLanguage = $Kind -in @("chat", "vlm")
    if ($null -eq $Smoke) {
        return [ordered]@{
            smokeStatus = $null
            smokeError = $null
            model = Get-PropertyValue -Object $Case -Name "model"
            configuredModel = Get-PropertyValue -Object $Case -Name "model"
            runtime = Get-ConfiguredExtraValue -Case $Case -Name "runtime"
            configuredRuntime = Get-ConfiguredExtraValue -Case $Case -Name "runtime"
            loadTimeMs = $null
            loadTimeSource = $null
            ttftMs = $null
            ttftSource = $null
            promptTokens = $null
            promptTokensSource = $null
            completionTokens = $null
            completionTokensSource = $null
            decodeDurationMs = $null
            decodeDurationSource = $null
            decodeTps = $null
            decodeTpsSource = $null
            totalDurationMs = $null
            totalDurationSource = $null
            imageLatencyMs = $null
            imageLatencySource = $null
            firstProgressMs = $null
            stepsCompleted = $null
            stepsPerSecond = $null
            outputRemotePath = $null
            nativeProfile = $null
        }
    }

    $eventValue = Get-PropertyValue -Object $Smoke -Name "events"
    $events = if ($null -eq $eventValue) { @() } else { @($eventValue) }
    $terminal = Get-LastSmokeEvent -Events $events -Statuses @("completed", "failed")
    if ($null -eq $terminal -and $events.Count -gt 0) { $terminal = $events[$events.Count - 1] }
    $firstLoad = Get-LastSmokeEvent -Events $events -Statuses @("first_load_ok")
    $starting = Get-FirstSmokeEvent -Events $events -Statuses @("starting", "generation_starting")
    if ($null -eq $starting -and $events.Count -gt 0) { $starting = $events[0] }

    $stats = Get-NestedValue -Object $terminal -Path @("nativeStats")
    if ($null -eq $stats) { $stats = Get-NestedValue -Object $firstLoad -Path @("nativeStats") }
    if ($null -eq $stats) { $stats = Get-NestedValue -Object $terminal -Path @("generation", "nativeStats") }
    $nativeProfile = Get-NestedValue -Object $stats -Path @("nativeProfile")
    $result = Get-NestedValue -Object $terminal -Path @("result")
    $modelObject = Get-FirstValue @(
        (Get-PropertyValue -Object $terminal -Name "model"),
        (Get-PropertyValue -Object $starting -Name "model"),
        (Get-PropertyValue -Object $starting -Name "target")
    )
    $configuredModel = Get-PropertyValue -Object $Case -Name "model"
    $configuredRuntime = Get-ConfiguredExtraValue -Case $Case -Name "runtime"
    $model = Get-FirstValue @(
        (Get-PropertyValue -Object $stats -Name "modelName"),
        (Get-PropertyValue -Object $modelObject -Name "displayName"),
        (Get-PropertyValue -Object $modelObject -Name "id"),
        $configuredModel,
        (Get-ConfiguredExtraValue -Case $Case -Name "displayName"),
        (Get-ConfiguredExtraValue -Case $Case -Name "modelPath"),
        (Get-ConfiguredExtraValue -Case $Case -Name "bundleRoot")
    )
    $runtime = Get-FirstValue @(
        (Get-PropertyValue -Object $stats -Name "backend"),
        (Get-PropertyValue -Object $terminal -Name "runtime"),
        (Get-PropertyValue -Object $modelObject -Name "runtime"),
        (Get-PropertyValue -Object $starting -Name "runtime"),
        $configuredRuntime
    )

    $imageContextLoad = Get-SumOfAvailableMetrics @(
        (Get-PropertyValue -Object $result -Name "unetContextLoadMs"),
        (Get-PropertyValue -Object $result -Name "vaeContextLoadMs")
    )
    $loadMetric = Select-NumericMetric @(
        [pscustomobject]@{ source = "first_load_ok.loadMs"; value = Get-PropertyValue -Object $firstLoad -Name "loadMs" },
        [pscustomobject]@{ source = "first_load_ok.nativeStats.loadMs"; value = Get-NestedValue -Object $firstLoad -Path @("nativeStats", "loadMs") },
        [pscustomobject]@{ source = "terminal.nativeStats.loadMs"; value = Get-PropertyValue -Object $stats -Name "loadMs" },
        [pscustomobject]@{ source = "terminal.result.loadMs"; value = Get-PropertyValue -Object $result -Name "loadMs" },
        [pscustomobject]@{ source = "terminal.result.contextLoadMs"; value = Get-PropertyValue -Object $result -Name "contextLoadMs" },
        [pscustomobject]@{ source = "terminal.result.unetContextLoadMs+vaeContextLoadMs"; value = $imageContextLoad }
    )
    $firstToken = Get-FirstSmokeEvent -Events $events -Statuses @("first_token", "first_token_received", "token")
    $ttftMetric = Select-NumericMetric @(
        [pscustomobject]@{ source = "terminal.nativeStats.ttftMs"; value = Get-PropertyValue -Object $stats -Name "ttftMs" },
        [pscustomobject]@{ source = "terminal.nativeStats.nativeProfile.ttftMs"; value = Get-PropertyValue -Object $nativeProfile -Name "ttftMs" },
        [pscustomobject]@{ source = "terminal.nativeStats.nativeProfile.promptTimeMs"; value = Get-PropertyValue -Object $nativeProfile -Name "promptTimeMs" },
        [pscustomobject]@{ source = "terminal.result.ttftMs"; value = Get-PropertyValue -Object $result -Name "ttftMs" },
        [pscustomobject]@{ source = "terminal.ttftMs"; value = Get-PropertyValue -Object $terminal -Name "ttftMs" },
        [pscustomobject]@{ source = "first_token.elapsedMs"; value = Get-PropertyValue -Object $firstToken -Name "elapsedMs" }
    )
    $promptTokensMetric = Select-NumericMetric @(
        [pscustomobject]@{ source = "terminal.nativeStats.promptTokens"; value = Get-PropertyValue -Object $stats -Name "promptTokens" },
        [pscustomobject]@{ source = "terminal.nativeStats.inputTokens"; value = Get-PropertyValue -Object $stats -Name "inputTokens" },
        [pscustomobject]@{ source = "terminal.nativeStats.nativeProfile.promptTokens"; value = Get-PropertyValue -Object $nativeProfile -Name "promptTokens" },
        [pscustomobject]@{ source = "terminal.promptTokens"; value = Get-PropertyValue -Object $terminal -Name "promptTokens" }
    )
    $completionTokensMetric = Select-NumericMetric @(
        [pscustomobject]@{ source = "terminal.nativeStats.completionTokens"; value = Get-PropertyValue -Object $stats -Name "completionTokens" },
        [pscustomobject]@{ source = "terminal.nativeStats.outputTokens"; value = Get-PropertyValue -Object $stats -Name "outputTokens" },
        [pscustomobject]@{ source = "terminal.nativeStats.generatedTokens"; value = Get-PropertyValue -Object $stats -Name "generatedTokens" },
        [pscustomobject]@{ source = "terminal.nativeStats.tokenCount"; value = Get-PropertyValue -Object $stats -Name "tokenCount" },
        [pscustomobject]@{ source = "terminal.nativeStats.nativeProfile.decodeTokens"; value = Get-PropertyValue -Object $nativeProfile -Name "decodeTokens" },
        [pscustomobject]@{ source = "terminal.completionTokens"; value = Get-PropertyValue -Object $terminal -Name "completionTokens" }
    )
    $decodeDurationMetric = Select-NumericMetric @(
        [pscustomobject]@{ source = "terminal.nativeStats.decodeMs"; value = Get-PropertyValue -Object $stats -Name "decodeMs" },
        [pscustomobject]@{ source = "terminal.nativeStats.nativeProfile.decodeTimeMs"; value = Get-PropertyValue -Object $nativeProfile -Name "decodeTimeMs" },
        [pscustomobject]@{ source = "terminal.decodeMs"; value = Get-PropertyValue -Object $terminal -Name "decodeMs" }
    )
    $decodeMetric = Select-NumericMetric @(
        [pscustomobject]@{ source = "terminal.nativeStats.decodeTps"; value = Get-PropertyValue -Object $stats -Name "decodeTps" },
        [pscustomobject]@{ source = "terminal.nativeStats.nativeProfile.decodingSpeed"; value = Get-PropertyValue -Object $nativeProfile -Name "decodingSpeed" }
    )
    if ($null -eq $decodeMetric.value -and $null -ne $completionTokensMetric.value -and $null -ne $decodeDurationMetric.value) {
        $decodeMetric = [pscustomobject]@{
            value = ($completionTokensMetric.value * 1000.0) / $decodeDurationMetric.value
            source = "$($completionTokensMetric.source)/$($decodeDurationMetric.source)"
        }
    }
    $totalDurationMetric = Select-NumericMetric @(
        [pscustomobject]@{ source = "terminal.result.elapsedMs"; value = Get-PropertyValue -Object $result -Name "elapsedMs" },
        [pscustomobject]@{ source = "terminal.elapsedMs"; value = Get-PropertyValue -Object $terminal -Name "elapsedMs" },
        [pscustomobject]@{ source = "root.elapsedMs"; value = Get-PropertyValue -Object $Smoke -Name "elapsedMs" }
    )
    if ($null -eq $totalDurationMetric.value -and $isLanguage -and
        $null -ne $ttftMetric.value -and $null -ne $decodeDurationMetric.value) {
        $totalDurationMetric = [pscustomobject]@{
            value = $ttftMetric.value + $decodeDurationMetric.value
            source = "$($ttftMetric.source)+$($decodeDurationMetric.source)"
        }
    }
    $firstProgress = Get-FirstSmokeEvent -Events $events -Statuses @("first_progress", "progress", "step")
    $firstProgressMetric = Select-NumericMetric @(
        [pscustomobject]@{ source = "first_progress.elapsedMs"; value = Get-PropertyValue -Object $firstProgress -Name "elapsedMs" },
        [pscustomobject]@{ source = "terminal.result.firstProgressMs"; value = Get-PropertyValue -Object $result -Name "firstProgressMs" }
    )
    $stepsMetric = Select-NumericMetric @(
        [pscustomobject]@{ source = "terminal.result.stepsCompleted"; value = Get-PropertyValue -Object $result -Name "stepsCompleted" },
        [pscustomobject]@{ source = "terminal.result.steps"; value = Get-PropertyValue -Object $result -Name "steps" },
        [pscustomobject]@{ source = "terminal.stepsCompleted"; value = Get-PropertyValue -Object $terminal -Name "stepsCompleted" }
    )
    $stepsPerSecondMetric = Select-NumericMetric @(
        [pscustomobject]@{ source = "terminal.result.stepsPerSecond"; value = Get-PropertyValue -Object $result -Name "stepsPerSecond" }
    )
    if ($null -eq $stepsPerSecondMetric.value -and $null -ne $stepsMetric.value -and $null -ne $totalDurationMetric.value) {
        $stepsPerSecondMetric = [pscustomobject]@{
            value = ($stepsMetric.value * 1000.0) / $totalDurationMetric.value
            source = "$($stepsMetric.source)/$($totalDurationMetric.source)"
        }
    }
    $outputRemotePath = Get-FirstValue @(
        (Get-PropertyValue -Object $terminal -Name "outputPath"),
        (Get-PropertyValue -Object $result -Name "outputPath"),
        (Get-NestedValue -Object $terminal -Path @("generation", "outputPath")),
        (Get-PropertyValue -Object $Smoke -Name "outputPath")
    )

    return [ordered]@{
        smokeStatus = Get-FirstValue @(
            (Get-PropertyValue -Object $Smoke -Name "status"),
            (Get-PropertyValue -Object $terminal -Name "status")
        )
        smokeError = Get-FirstValue @(
            (Get-PropertyValue -Object $terminal -Name "error"),
            (Get-NestedValue -Object $result -Path @("error"))
        )
        model = $model
        configuredModel = $configuredModel
        runtime = $runtime
        configuredRuntime = $configuredRuntime
        loadTimeMs = $loadMetric.value
        loadTimeSource = $loadMetric.source
        ttftMs = $ttftMetric.value
        ttftSource = $ttftMetric.source
        promptTokens = $promptTokensMetric.value
        promptTokensSource = $promptTokensMetric.source
        completionTokens = $completionTokensMetric.value
        completionTokensSource = $completionTokensMetric.source
        decodeDurationMs = $decodeDurationMetric.value
        decodeDurationSource = $decodeDurationMetric.source
        decodeTps = $decodeMetric.value
        decodeTpsSource = $decodeMetric.source
        totalDurationMs = $totalDurationMetric.value
        totalDurationSource = $totalDurationMetric.source
        imageLatencyMs = if ($isLanguage) { $null } else { $totalDurationMetric.value }
        imageLatencySource = if ($isLanguage) { $null } else { $totalDurationMetric.source }
        firstProgressMs = if ($isLanguage) { $null } else { $firstProgressMetric.value }
        stepsCompleted = if ($isLanguage) { $null } else { $stepsMetric.value }
        stepsPerSecond = if ($isLanguage) { $null } else { $stepsPerSecondMetric.value }
        outputRemotePath = if ($isLanguage) { $null } else { $outputRemotePath }
        nativeProfile = $nativeProfile
    }
}

function Shorten-Text {
    param([string]$Value, [int]$MaximumLength = 400)
    if ([string]::IsNullOrWhiteSpace($Value)) { return $null }
    $clean = ($Value -replace "\s+", " ").Trim()
    if ($clean.Length -le $MaximumLength) { return $clean }
    return $clean.Substring(0, $MaximumLength) + "..."
}

function Invoke-SmokeBenchmarkRun {
    param(
        $Case,
        $Defaults,
        [int]$RunIndex,
        [ValidateSet("warmup", "measured")][string]$Phase,
        [int]$PhaseRunIndex,
        [bool]$AggregateEligible,
        [string]$SessionRoot,
        [string]$SessionStamp
    )
    $caseId = "$(Get-PropertyValue -Object $Case -Name 'id')".Trim()
    $caseLabel = Get-FirstValue @((Get-PropertyValue -Object $Case -Name "label"), $caseId)
    $kind = "$(Get-PropertyValue -Object $Case -Name 'kind')".Trim().ToLowerInvariant()
    $safeCaseId = Convert-ToSafeId $caseId
    $phasePrefix = if ($Phase -eq "warmup") { "w" } else { "m" }
    $runId = "$safeCaseId-$SessionStamp-$phasePrefix$($PhaseRunIndex.ToString('00'))"
    $remotePath = Get-RemoteSmokePath -Kind $kind -RunId $runId
    $relativeRunDir = Join-Path (Join-Path "runs" $safeCaseId) $runId
    $localRunDir = Join-Path $SessionRoot $relativeRunDir
    $smokeRelativePath = Join-Path $relativeRunDir "smoke.json"
    $telemetryRelativePath = Join-Path $relativeRunDir "telemetry.json"
    $runRelativePath = Join-Path $relativeRunDir "run.json"
    $diagnosticsRelativePath = Join-Path $relativeRunDir "failure-diagnostics.json"
    $imageRelativePath = Join-Path $relativeRunDir "output.png"
    $smokePath = Join-Path $SessionRoot $smokeRelativePath
    $telemetryPath = Join-Path $SessionRoot $telemetryRelativePath
    $runPath = Join-Path $SessionRoot $runRelativePath
    $diagnosticsPath = Join-Path $SessionRoot $diagnosticsRelativePath
    $imagePath = Join-Path $SessionRoot $imageRelativePath
    New-Item -ItemType Directory -Force -Path $localRunDir | Out-Null

    $timeoutSeconds = [Math]::Max(1, [int](Get-CaseSetting -Case $Case -Defaults $Defaults -Name "timeoutSeconds" -Fallback 1200))
    $pollIntervalMs = [Math]::Max(250, [int](Get-CaseSetting -Case $Case -Defaults $Defaults -Name "pollIntervalMs" -Fallback 1000))
    $processStartGraceSeconds = [Math]::Max(1, [int](Get-CaseSetting -Case $Case -Defaults $Defaults -Name "processStartGraceSeconds" -Fallback 12))
    $terminalTelemetryDelayMs = [Math]::Max(0, [int](Get-CaseSetting -Case $Case -Defaults $Defaults -Name "stopTelemetryAtLeastMsAfterTerminalEvent" -Fallback 2000))
    $forceStop = Convert-ToBool (Get-CaseSetting -Case $Case -Defaults $Defaults -Name "forceStopBeforeRun" -Fallback $true) $true
    $startedAt = [DateTimeOffset]::Now
    $samples = New-Object System.Collections.Generic.List[object]
    $errors = @()
    $warnings = @()
    $activityInfo = $null
    $terminalStatus = $null
    $remoteSeen = $false
    $timedOut = $false
    $observedPid = $null
    $lifecycleFailure = $null
    $diagnosticsCaptured = $false
    $lastRemoteError = $null

    $launchReady = $true
    try {
        if ($forceStop) { Stop-SmokeApp }
        Remove-RemoteSmokeFile -RemotePath $remotePath
    } catch {
        $launchReady = $false
        $errors += "activity setup failed: $($_.Exception.Message)"
    }

    # Capture the baseline only after the previous app process has been stopped.
    [void]$samples.Add((Get-TelemetrySample -RunStartedAt $startedAt))
    if ($launchReady) {
        try {
            $activityInfo = Start-SmokeActivity -Case $Case -Kind $kind -RunId $runId
        } catch {
            $errors += "activity start failed: $($_.Exception.Message)"
        }
    }

    if ($null -ne $activityInfo) {
        $deadline = (Get-Date).AddSeconds($timeoutSeconds)
        while ((Get-Date) -lt $deadline) {
            $sample = Get-TelemetrySample -RunStartedAt $startedAt
            [void]$samples.Add($sample)
            $remote = Get-RemoteSmokeStatus -RemotePath $remotePath -LocalPath $smokePath -ExpectedRunId $runId
            if ($remote.exists) { $remoteSeen = $true }
            if (-not [string]::IsNullOrWhiteSpace($remote.error)) {
                $lastRemoteError = $remote.error
            }
            if ($remote.isTerminal) {
                $terminalStatus = $remote.status
                break
            }
            $lifecycle = Get-ProcessLifecycleState -ObservedPid $observedPid -CurrentPid $sample.pid
            $observedPid = $lifecycle.observedPid
            if ($lifecycle.failed) {
                $lifecycleFailure = $lifecycle.state
                $errors += "$lifecycleFailure before a valid terminal smoke result (observedPid=$observedPid)."
                try {
                    [void](Capture-FailureDiagnostics -Reason $lifecycleFailure -RunId $runId -Path $diagnosticsPath -LastTelemetrySample $sample -ObservedPid $observedPid)
                    $diagnosticsCaptured = $true
                } catch {
                    $errors += "failure diagnostics capture failed: $($_.Exception.Message)"
                }
                break
            }
            if ($null -eq $observedPid -and (([DateTimeOffset]::Now - $startedAt).TotalSeconds -ge $processStartGraceSeconds)) {
                $lifecycleFailure = "process_not_observed"
                $errors += "App PID was not observed within $processStartGraceSeconds seconds after Activity launch."
                try {
                    [void](Capture-FailureDiagnostics -Reason $lifecycleFailure -RunId $runId -Path $diagnosticsPath -LastTelemetrySample $sample -ObservedPid $null)
                    $diagnosticsCaptured = $true
                } catch {
                    $errors += "failure diagnostics capture failed: $($_.Exception.Message)"
                }
                break
            }
            Start-Sleep -Milliseconds $pollIntervalMs
        }
        if ($null -eq $terminalStatus -and $null -eq $lifecycleFailure -and (Get-Date) -ge $deadline) {
            $timedOut = $true
            $errors += "timed out after $timeoutSeconds seconds waiting for $remotePath"
        }
    }

    [void]$samples.Add((Get-TelemetrySample -RunStartedAt $startedAt))
    if ($null -eq $lifecycleFailure) {
        $finalRemote = Get-RemoteSmokeStatus -RemotePath $remotePath -LocalPath $smokePath -ExpectedRunId $runId
        if ($finalRemote.exists) { $remoteSeen = $true }
        if ($finalRemote.isTerminal) { $terminalStatus = $finalRemote.status }
        if (-not [string]::IsNullOrWhiteSpace($finalRemote.error)) { $lastRemoteError = $finalRemote.error }
    }

    if ($remoteSeen) {
        $captureMessage = Pull-RemoteSmokeFile -RemotePath $remotePath -LocalPath $smokePath -ExpectedRunId $runId
        if (-not [string]::IsNullOrWhiteSpace($captureMessage)) {
            $errors += $captureMessage
        }
    } else {
        $errors += "remote smoke JSON was not created: $remotePath"
    }

    $parsed = Read-SmokeJson -Path $smokePath -ExpectedRunId $runId -RequireTerminal
    if (-not [string]::IsNullOrWhiteSpace($parsed.error)) { $errors += $parsed.error }
    if ($null -eq $parsed.value -and -not [string]::IsNullOrWhiteSpace($lastRemoteError)) {
        $errors += "last remote snapshot error: $lastRemoteError"
    }
    $metrics = Convert-SmokeToMetrics -Smoke $parsed.value -Case $Case -Kind $kind
    if ($metrics.smokeStatus -eq "failed" -and -not [string]::IsNullOrWhiteSpace("$($metrics.smokeError)")) {
        $errors += "smoke failed: $(Shorten-Text "$($metrics.smokeError)")"
    }

    $runStatus = if ($null -eq $activityInfo) {
        "start_failed"
    } elseif ($null -ne $lifecycleFailure) {
        $lifecycleFailure
    } elseif ($timedOut -and $null -eq $terminalStatus) {
        "timed_out"
    } elseif ($null -eq $parsed.value) {
        "parse_failed"
    } else {
        Get-FirstValue @($metrics.smokeStatus, $terminalStatus, "unknown")
    }
    if ($terminalStatus -in @("completed", "failed") -and $terminalTelemetryDelayMs -gt 0) {
        Start-Sleep -Milliseconds $terminalTelemetryDelayMs
        [void]$samples.Add((Get-TelemetrySample -RunStartedAt $startedAt))
    }

    $imageArtifact = $null
    if ($runStatus -eq "completed" -and $kind -notin @("chat", "vlm")) {
        try {
            $imageArtifact = Capture-ImageArtifact -Case $Case -RunId $runId -RemoteSmokePath $remotePath -ReportedPath "$($metrics.outputRemotePath)" -LocalPath $imagePath -RelativePath $imageRelativePath
        } catch {
            $runStatus = "artifact_failed"
            $errors += "image artifact validation failed: $($_.Exception.Message)"
        }
    }
    if ($runStatus -ne "completed" -and -not $diagnosticsCaptured -and $null -ne $activityInfo) {
        $lastSample = if ($samples.Count -gt 0) { $samples[$samples.Count - 1] } else { $null }
        try {
            [void](Capture-FailureDiagnostics -Reason $runStatus -RunId $runId -Path $diagnosticsPath -LastTelemetrySample $lastSample -ObservedPid $observedPid)
            $diagnosticsCaptured = $true
        } catch {
            $errors += "failure diagnostics capture failed: $($_.Exception.Message)"
        }
    }
    $endedAt = [DateTimeOffset]::Now
    $sampleArray = @($samples.ToArray())
    $telemetrySummary = [pscustomobject](Get-TelemetrySummary -Samples $sampleArray)
    $sampleWarnings = @($sampleArray | ForEach-Object { @($_.errors) } | Where-Object { -not [string]::IsNullOrWhiteSpace("$_") })
    $warnings += $sampleWarnings
    $warnings = @($warnings | Where-Object { -not [string]::IsNullOrWhiteSpace("$_") } | Select-Object -Unique)
    $errors = @($errors | Where-Object { -not [string]::IsNullOrWhiteSpace("$_") } | Select-Object -Unique)

    Write-Utf8Json -Path $telemetryPath -Value ([pscustomobject][ordered]@{
        schemaVersion = 1
        artifactType = "real_device_telemetry"
        runId = $runId
        summary = $telemetrySummary
        samples = $sampleArray
    })

    $run = [pscustomobject][ordered]@{
        schemaVersion = 1
        artifactType = "real_device_run"
        caseId = $caseId
        caseLabel = $caseLabel
        kind = $kind
        runIndex = $RunIndex
        phase = $Phase
        phaseRunIndex = $PhaseRunIndex
        aggregateEligible = $AggregateEligible
        runId = $runId
        status = $runStatus
        startedAt = $startedAt.ToString("o")
        endedAt = $endedAt.ToString("o")
        durationMs = [Math]::Round(($endedAt - $startedAt).TotalMilliseconds)
        activity = if ($null -ne $activityInfo) { $activityInfo.activity } else { Get-DefaultActivity -Kind $kind }
        remoteSmokePath = $remotePath
        smokeJsonPath = $smokeRelativePath
        telemetryJsonPath = $telemetryRelativePath
        runJsonPath = $runRelativePath
        diagnosticsJsonPath = if ($diagnosticsCaptured) { $diagnosticsRelativePath } else { $null }
        model = $metrics.model
        configuredModel = $metrics.configuredModel
        runtime = $metrics.runtime
        configuredRuntime = $metrics.configuredRuntime
        loadTimeMs = $metrics.loadTimeMs
        loadTimeSource = $metrics.loadTimeSource
        ttftMs = $metrics.ttftMs
        ttftSource = $metrics.ttftSource
        promptTokens = $metrics.promptTokens
        promptTokensSource = $metrics.promptTokensSource
        completionTokens = $metrics.completionTokens
        completionTokensSource = $metrics.completionTokensSource
        decodeDurationMs = $metrics.decodeDurationMs
        decodeDurationSource = $metrics.decodeDurationSource
        decodeTps = $metrics.decodeTps
        decodeTpsSource = $metrics.decodeTpsSource
        totalDurationMs = $metrics.totalDurationMs
        totalDurationSource = $metrics.totalDurationSource
        imageLatencyMs = $metrics.imageLatencyMs
        imageLatencySource = $metrics.imageLatencySource
        firstProgressMs = $metrics.firstProgressMs
        stepsCompleted = $metrics.stepsCompleted
        stepsPerSecond = $metrics.stepsPerSecond
        imageArtifact = $imageArtifact
        outputWidth = if ($null -ne $imageArtifact) { $imageArtifact.width } else { $null }
        outputHeight = if ($null -ne $imageArtifact) { $imageArtifact.height } else { $null }
        outputSha256 = if ($null -ne $imageArtifact) { $imageArtifact.sha256 } else { $null }
        rssStartMb = $telemetrySummary.rssStartMb
        peakRssMb = $telemetrySummary.peakRssMb
        rssEndMb = $telemetrySummary.rssEndMb
        peakAppRssMb = $telemetrySummary.peakAppRssMb
        availableRamStartMb = $telemetrySummary.availableRamStartMb
        availableRamEndMb = $telemetrySummary.availableRamEndMb
        availableRamMinMb = $telemetrySummary.availableRamMinMb
        batteryTemperatureStartC = $telemetrySummary.batteryTemperatureStartC
        batteryTemperatureEndC = $telemetrySummary.batteryTemperatureEndC
        batteryTemperatureMaxC = $telemetrySummary.batteryTemperatureMaxC
        batteryTemperatureDeltaC = $telemetrySummary.batteryTemperatureDeltaC
        thermalStatusStart = $telemetrySummary.thermalStatusStart
        thermalStatusMax = $telemetrySummary.thermalStatusMax
        thermalStatusEnd = $telemetrySummary.thermalStatusEnd
        thermalStatusStartRank = $telemetrySummary.thermalStatusStartRank
        thermalStatusMaxRank = $telemetrySummary.thermalStatusMaxRank
        thermalStatusEndRank = $telemetrySummary.thermalStatusEndRank
        dmaBufStartMb = $telemetrySummary.dmaBufStartMb
        dmaBufPeakMb = $telemetrySummary.dmaBufPeakMb
        dmaBufEndMb = $telemetrySummary.dmaBufEndMb
        cmaFreeMinMb = $telemetrySummary.cmaFreeMinMb
        observedPid = $observedPid
        processLifecycle = if ($null -ne $lifecycleFailure) { $lifecycleFailure } elseif ($null -ne $observedPid) { "terminal_after_observed_pid" } else { "terminal_without_observed_pid" }
        telemetrySampleCount = $telemetrySummary.sampleCount
        nativeProfile = $metrics.nativeProfile
        metricAvailability = [ordered]@{
            loadTime = ($null -ne $metrics.loadTimeMs)
            ttft = ($null -ne $metrics.ttftMs)
            promptTokens = ($null -ne $metrics.promptTokens)
            completionTokens = ($null -ne $metrics.completionTokens)
            decodeTps = ($null -ne $metrics.decodeTps)
            totalDuration = ($null -ne $metrics.totalDurationMs)
            imageLatency = ($null -ne $metrics.imageLatencyMs)
            imageArtifact = ($null -ne $imageArtifact)
            rss = ($null -ne $telemetrySummary.peakRssMb)
            thermalStatus = ($null -ne $telemetrySummary.thermalStatusMaxRank)
            dmaBuf = ($null -ne $telemetrySummary.dmaBufPeakMb)
            peakAppRss = ($null -ne $telemetrySummary.peakAppRssMb)
            availableRam = ($null -ne $telemetrySummary.availableRamMinMb)
            batteryTemperature = ($null -ne $telemetrySummary.batteryTemperatureMaxC)
        }
        warnings = $warnings
        errors = $errors
    }
    Write-Utf8Json -Path $runPath -Value $run
    return $run
}

function New-CancellationProbeResult {
    param(
        [string]$Result,
        [string]$Detail,
        $StopAccepted,
        $GenerationJobState,
        $StopRequestedAt,
        $RequestFinishedAt,
        $CancelLatencyMs,
        [int]$TimeoutSeconds
    )
    return [pscustomobject][ordered]@{
        result = $Result
        detail = $Detail
        stopAccepted = $StopAccepted
        generationJobState = $GenerationJobState
        stopRequestedAt = if ($null -ne $StopRequestedAt) { $StopRequestedAt.ToString("o") } else { $null }
        requestFinishedAt = if ($null -ne $RequestFinishedAt) { $RequestFinishedAt.ToString("o") } else { $null }
        cancelLatencyMs = $CancelLatencyMs
        timeoutSeconds = if ($TimeoutSeconds -gt 0) { $TimeoutSeconds } else { $null }
    }
}

function Invoke-CancellationProbe {
    param($Case, $Defaults, $ReferenceRun)
    $kind = "$(Get-PropertyValue -Object $Case -Name 'kind')".Trim().ToLowerInvariant()
    if ($kind -notin @("chat", "vlm")) {
        return New-CancellationProbeResult -Result "not_applicable" -Detail "Cancellation probe applies only to chat and VLM cases."
    }
    $enabled = Convert-ToBool (Get-CaseSetting -Case $Case -Defaults $Defaults -Name "cancellationProbe" -Fallback $false)
    if (-not $enabled) {
        return New-CancellationProbeResult -Result "skipped" -Detail "Cancellation probe is disabled for this case."
    }
    if ([string]::IsNullOrWhiteSpace($ApiKey)) {
        return New-CancellationProbeResult -Result "unavailable" -Detail "Pass -ApiKey to run the authenticated cancellation probe."
    }
    if ($null -eq $ReferenceRun -or $ReferenceRun.status -ne "completed") {
        return New-CancellationProbeResult -Result "unavailable" -Detail "No completed measured run left a model ready for the cancellation probe."
    }

    $delayMs = [Math]::Max(0, [int](Get-CaseSetting -Case $Case -Defaults $Defaults -Name "cancellationDelayMs" -Fallback 750))
    $maxTokens = [Math]::Max(32, [int](Get-CaseSetting -Case $Case -Defaults $Defaults -Name "cancellationMaxTokens" -Fallback 512))
    $timeoutSeconds = [Math]::Max(5, [int](Get-CaseSetting -Case $Case -Defaults $Defaults -Name "cancellationTimeoutSeconds" -Fallback 30))
    $baseUrl = "http://127.0.0.1:$ApiHostPort"
    $job = $null
    $forwarded = $false
    $stopRequestedAt = $null
    $requestFinishedAt = $null
    try {
        $forward = Invoke-AdbCapture -Arguments @("forward", "tcp:$ApiHostPort", "tcp:$ApiDevicePort")
        if (-not $forward.ok) { throw "ADB forward failed: $($forward.text)" }
        $forwarded = $true

        $launch = Invoke-AdbCapture -Arguments @("shell", "am", "start", "-n", "$PackageName/.MainActivity")
        if (-not $launch.ok) { throw "MainActivity launch failed: $($launch.text)" }

        $healthReady = $false
        $healthDeadline = (Get-Date).AddSeconds(12)
        while ((Get-Date) -lt $healthDeadline) {
            try {
                $health = Invoke-RestMethod -Method Get -Uri "$baseUrl/health" -TimeoutSec 3
                if ((Get-PropertyValue -Object $health -Name "status") -eq "ok") {
                    $healthReady = $true
                    break
                }
            } catch {
                Start-Sleep -Milliseconds 500
            }
        }
        if (-not $healthReady) { throw "Local API health endpoint did not become ready on device port $ApiDevicePort." }

        $body = [ordered]@{
            messages = @([ordered]@{
                role = "user"
                content = "Write a long numbered list so cancellation can be exercised."
            })
            stream = $false
            max_tokens = $maxTokens
            temperature = 0.1
        } | ConvertTo-Json -Depth 8 -Compress
        $job = Start-Job -ScriptBlock {
            param($Uri, $Key, $RequestBody, $Timeout)
            try {
                $headers = @{ Authorization = "Bearer $Key" }
                $response = Invoke-WebRequest -UseBasicParsing -Method Post -Uri $Uri -Headers $headers -ContentType "application/json" -Body $RequestBody -TimeoutSec $Timeout
                [pscustomobject]@{ ok = $true; statusCode = [int]$response.StatusCode; body = $response.Content; error = $null }
            } catch {
                [pscustomobject]@{ ok = $false; statusCode = $null; body = $null; error = $_.Exception.Message }
            }
        } -ArgumentList "$baseUrl/v1/chat/completions", $ApiKey, $body, ($timeoutSeconds + 10)

        if ($delayMs -gt 0) { Start-Sleep -Milliseconds $delayMs }
        if ($job.State -ne "Running") {
            $requestFinishedAt = [DateTimeOffset]::Now
            $early = @(Receive-Job -Job $job -ErrorAction SilentlyContinue | Select-Object -Last 1)
            $earlyDetail = if ($early.Count -gt 0) { Shorten-Text ($early[0] | ConvertTo-Json -Compress -Depth 4) } else { "generation job state=$($job.State)" }
            return New-CancellationProbeResult -Result "inconclusive_completed_before_stop" -Detail $earlyDetail -GenerationJobState $job.State -RequestFinishedAt $requestFinishedAt -TimeoutSeconds $timeoutSeconds
        }

        $headers = @{ Authorization = "Bearer $ApiKey" }
        $stopRequestedAt = [DateTimeOffset]::Now
        $stopResponse = Invoke-RestMethod -Method Post -Uri "$baseUrl/v1/generate/stop" -Headers $headers -ContentType "application/json" -Body "{}" -TimeoutSec 10
        $stopAccepted = Convert-ToBool (Get-PropertyValue -Object $stopResponse -Name "stopped")
        Wait-Job -Job $job -Timeout $timeoutSeconds | Out-Null
        $jobState = $job.State
        if ($jobState -ne "Running") { $requestFinishedAt = [DateTimeOffset]::Now }
        $cancelLatencyMs = if ($null -ne $requestFinishedAt) {
            [Math]::Round(($requestFinishedAt - $stopRequestedAt).TotalMilliseconds, 2)
        } else { $null }
        $generationResult = @(Receive-Job -Job $job -ErrorAction SilentlyContinue | Select-Object -Last 1)
        $generationDetail = if ($generationResult.Count -gt 0) {
            Shorten-Text ($generationResult[0] | ConvertTo-Json -Compress -Depth 4)
        } else {
            "generation job state=$jobState"
        }
        if (-not $stopAccepted) {
            return New-CancellationProbeResult -Result "failed" -Detail "Stop endpoint did not return stopped=true. $generationDetail" -StopAccepted $false -GenerationJobState $jobState -StopRequestedAt $stopRequestedAt -RequestFinishedAt $requestFinishedAt -CancelLatencyMs $cancelLatencyMs -TimeoutSeconds $timeoutSeconds
        }
        if ($jobState -eq "Running") {
            Stop-Job -Job $job -ErrorAction SilentlyContinue
            return New-CancellationProbeResult -Result "failed" -Detail "Generation remained active for $timeoutSeconds seconds after stop was accepted." -StopAccepted $true -GenerationJobState $jobState -StopRequestedAt $stopRequestedAt -TimeoutSeconds $timeoutSeconds
        }
        return New-CancellationProbeResult -Result "passed" -Detail "Stop was accepted and the generation request reached state '$jobState'. $generationDetail" -StopAccepted $true -GenerationJobState $jobState -StopRequestedAt $stopRequestedAt -RequestFinishedAt $requestFinishedAt -CancelLatencyMs $cancelLatencyMs -TimeoutSeconds $timeoutSeconds
    } catch {
        return New-CancellationProbeResult -Result "unavailable" -Detail (Shorten-Text $_.Exception.Message) -GenerationJobState $(if ($null -ne $job) { $job.State } else { $null }) -StopRequestedAt $stopRequestedAt -RequestFinishedAt $requestFinishedAt -TimeoutSeconds $timeoutSeconds
    } finally {
        if ($null -ne $job) {
            if ($job.State -eq "Running") { Stop-Job -Job $job -ErrorAction SilentlyContinue }
            Remove-Job -Job $job -Force -ErrorAction SilentlyContinue
        }
        if ($forwarded) {
            Invoke-AdbCapture -Arguments @("forward", "--remove", "tcp:$ApiHostPort") | Out-Null
        }
    }
}

function Get-NumericStatistics {
    param([object[]]$Values)
    $numbers = @($Values | ForEach-Object { Convert-ToNullableDouble $_ } | Where-Object { $null -ne $_ } | Sort-Object)
    $count = $numbers.Count
    $median = $null
    $mean = $null
    $stddev = $null
    $cv = $null
    if ($count -gt 0) {
        $middle = [int][Math]::Floor($count / 2)
        $median = if (($count % 2) -eq 1) { $numbers[$middle] } else { ($numbers[$middle - 1] + $numbers[$middle]) / 2.0 }
        $mean = ($numbers | Measure-Object -Average).Average
    }
    if ($count -gt 1) {
        $sumSquares = 0.0
        foreach ($number in $numbers) { $sumSquares += [Math]::Pow($number - $mean, 2) }
        $stddev = [Math]::Sqrt($sumSquares / ($count - 1))
        if ([Math]::Abs($mean) -gt [double]::Epsilon) { $cv = ($stddev / [Math]::Abs($mean)) * 100.0 }
    }
    return [pscustomobject][ordered]@{
        values = $numbers
        sampleCount = $count
        median = if ($null -ne $median) { [Math]::Round($median, 4) } else { $null }
        mean = if ($null -ne $mean) { [Math]::Round($mean, 4) } else { $null }
        sampleStandardDeviation = if ($null -ne $stddev) { [Math]::Round($stddev, 4) } else { $null }
        coefficientOfVariationPercent = if ($null -ne $cv) { [Math]::Round($cv, 4) } else { $null }
        minimum = if ($count -gt 0) { [Math]::Round($numbers[0], 4) } else { $null }
        maximum = if ($count -gt 0) { [Math]::Round($numbers[$count - 1], 4) } else { $null }
    }
}

function Get-CaseStability {
    param($Case, $Defaults, [object[]]$Runs)
    $kind = "$(Get-PropertyValue -Object $Case -Name 'kind')".Trim().ToLowerInvariant()
    $requiredSamples = Get-ValidatedIntegerSetting -Case $Case -Defaults $Defaults -Name "runs" -Fallback 3 -Minimum 3
    $threshold = Convert-ToNullableDouble (Get-CaseSetting -Case $Case -Defaults $Defaults -Name "stabilityCvThresholdPercent" -Fallback 10.0)
    if ($null -eq $threshold -or $threshold -lt 0) { throw "stabilityCvThresholdPercent must be a non-negative number." }
    $candidateMetrics = if ($kind -in @("chat", "vlm")) {
        @("decodeTps", "ttftMs", "totalDurationMs", "loadTimeMs")
    } else {
        @("imageLatencyMs", "totalDurationMs", "stepsPerSecond", "loadTimeMs")
    }
    $eligibleRuns = @($Runs | Where-Object {
        $_.status -eq "completed" -and $_.phase -eq "measured" -and (Convert-ToBool $_.aggregateEligible)
    })
    $statistics = [ordered]@{}
    $selectedMetric = $null
    $bestMetric = $null
    $bestCount = -1
    foreach ($metric in $candidateMetrics) {
        $values = @($eligibleRuns | ForEach-Object {
            $value = Convert-ToNullableDouble (Get-PropertyValue -Object $_ -Name $metric)
            if ($null -ne $value -and $value -gt 0) { $value }
        })
        $stats = Get-NumericStatistics -Values $values
        $statistics[$metric] = $stats
        if ($stats.sampleCount -gt $bestCount) { $bestMetric = $metric; $bestCount = $stats.sampleCount }
        if ($null -eq $selectedMetric -and $stats.sampleCount -ge $requiredSamples) { $selectedMetric = $metric }
    }
    if ($null -eq $selectedMetric) { $selectedMetric = $bestMetric }
    $selected = if ($null -ne $selectedMetric) { $statistics[$selectedMetric] } else { Get-NumericStatistics -Values @() }
    $unit = switch ($selectedMetric) {
        "decodeTps" { "tokens_per_second" }
        "stepsPerSecond" { "steps_per_second" }
        "ttftMs" { "ms" }
        "loadTimeMs" { "ms" }
        "imageLatencyMs" { "ms" }
        "totalDurationMs" { "ms" }
        default { $null }
    }
    $status = if ($selected.sampleCount -eq 0) { "metric_unavailable" } elseif ($selected.sampleCount -lt $requiredSamples) { "insufficient_samples" } elseif ($null -eq $selected.coefficientOfVariationPercent) { "coefficient_unavailable" } else { "evaluated" }
    $stable = if ($status -eq "evaluated") { $selected.coefficientOfVariationPercent -le $threshold } else { $null }
    return [pscustomobject][ordered]@{
        status = $status
        primaryMetric = $selectedMetric
        unit = $unit
        requiredSamples = $requiredSamples
        sampleCount = $selected.sampleCount
        values = $selected.values
        median = $selected.median
        mean = $selected.mean
        sampleStandardDeviation = $selected.sampleStandardDeviation
        coefficientOfVariationPercent = $selected.coefficientOfVariationPercent
        minimum = $selected.minimum
        maximum = $selected.maximum
        thresholdPercent = [Math]::Round($threshold, 4)
        stable = $stable
        statistics = [pscustomobject]$statistics
        eligibleMeasuredRunCount = $eligibleRuns.Count
        warmupRunsExcluded = @($Runs | Where-Object { $_.phase -eq "warmup" -or -not (Convert-ToBool $_.aggregateEligible) }).Count
        totalRunCount = $Runs.Count
    }
}

function Test-CooldownConditions {
    param(
        $Sample,
        [double]$ElapsedSeconds,
        $BaselineTemperatureC,
        [int]$MinimumSeconds,
        [double]$TemperatureToleranceC,
        $MaximumThermalRank,
        [bool]$RequireTemperature,
        [bool]$RequireThermalStatus
    )
    $baseline = Convert-ToNullableDouble $BaselineTemperatureC
    $currentTemperature = Convert-ToNullableDouble (Get-PropertyValue -Object $Sample -Name "batteryTemperatureC")
    $currentThermalRank = Get-ThermalStatusRank (Get-PropertyValue -Object $Sample -Name "thermalStatusRank")
    $maximumRank = Get-ThermalStatusRank $MaximumThermalRank
    $temperatureTarget = if ($null -ne $baseline) { $baseline + $TemperatureToleranceC } else { $null }
    $minimumElapsed = $ElapsedSeconds -ge $MinimumSeconds
    $temperatureReady = if (-not $RequireTemperature) { $true } else { $null -ne $temperatureTarget -and $null -ne $currentTemperature -and $currentTemperature -le $temperatureTarget }
    $thermalReady = if (-not $RequireThermalStatus) { $true } else { $null -ne $maximumRank -and $null -ne $currentThermalRank -and $currentThermalRank -le $maximumRank }
    return [pscustomobject][ordered]@{
        satisfied = ($minimumElapsed -and $temperatureReady -and $thermalReady)
        minimumElapsed = $minimumElapsed
        minimumSeconds = $MinimumSeconds
        elapsedSeconds = [Math]::Round($ElapsedSeconds, 3)
        temperatureRequired = $RequireTemperature
        baselineTemperatureC = $baseline
        temperatureToleranceC = $TemperatureToleranceC
        temperatureTargetMaxC = $temperatureTarget
        currentTemperatureC = $currentTemperature
        temperatureReady = $temperatureReady
        thermalStatusRequired = $RequireThermalStatus
        maximumThermalStatus = Get-ThermalStatusName $maximumRank
        maximumThermalRank = $maximumRank
        currentThermalStatus = Get-ThermalStatusName $currentThermalRank
        currentThermalRank = $currentThermalRank
        thermalReady = $thermalReady
    }
}

function Invoke-Cooldown {
    param($Case, $Defaults, $PreviousRun, [string]$TransitionId, [string]$ToPhase, [string]$SessionRoot)
    $minimumSeconds = Get-ValidatedIntegerSetting -Case $Case -Defaults $Defaults -Name "cooldownSeconds" -Fallback 30 -Minimum 0
    $maximumSeconds = Get-ValidatedIntegerSetting -Case $Case -Defaults $Defaults -Name "maximumCooldownSeconds" -Fallback 300 -Minimum 1
    if ($maximumSeconds -lt $minimumSeconds) { throw "maximumCooldownSeconds must be greater than or equal to cooldownSeconds." }
    $pollIntervalMs = Get-ValidatedIntegerSetting -Case $Case -Defaults $Defaults -Name "cooldownPollIntervalMs" -Fallback 5000 -Minimum 250
    $tolerance = Convert-ToNullableDouble (Get-CaseSetting -Case $Case -Defaults $Defaults -Name "cooldownUntilWithinStartTemperatureC" -Fallback 2.0)
    if ($null -eq $tolerance -or $tolerance -lt 0) { throw "cooldownUntilWithinStartTemperatureC must be a non-negative number." }
    $maximumThermal = Get-CaseSetting -Case $Case -Defaults $Defaults -Name "cooldownThermalStatusMax" -Fallback "LIGHT"
    if ($null -eq (Get-ThermalStatusRank $maximumThermal)) { throw "cooldownThermalStatusMax must be NONE through SHUTDOWN, or rank 0 through 6." }
    $requireTemperature = Convert-ToBool (Get-CaseSetting -Case $Case -Defaults $Defaults -Name "cooldownRequireTemperature" -Fallback $true) $true
    $requireThermal = Convert-ToBool (Get-CaseSetting -Case $Case -Defaults $Defaults -Name "cooldownRequireThermalStatus" -Fallback $true) $true
    $startedAt = [DateTimeOffset]::Now
    $samples = New-Object System.Collections.Generic.List[object]
    $errors = @()
    try { Stop-SmokeApp } catch { $errors += "force-stop before cooldown failed: $($_.Exception.Message)" }
    $finalConditions = $null
    do {
        $sample = Get-TelemetrySample -RunStartedAt $startedAt
        $elapsedSeconds = ([DateTimeOffset]::Now - $startedAt).TotalSeconds
        $finalConditions = Test-CooldownConditions -Sample $sample -ElapsedSeconds $elapsedSeconds -BaselineTemperatureC $PreviousRun.batteryTemperatureStartC -MinimumSeconds $minimumSeconds -TemperatureToleranceC $tolerance -MaximumThermalRank $maximumThermal -RequireTemperature $requireTemperature -RequireThermalStatus $requireThermal
        [void]$samples.Add([pscustomobject][ordered]@{ sample = $sample; conditions = $finalConditions })
        if ($finalConditions.satisfied -and $errors.Count -eq 0) { break }
        if ($elapsedSeconds -ge $maximumSeconds) { break }
        Start-Sleep -Milliseconds ([Math]::Min($pollIntervalMs, [Math]::Max(1, [int](($maximumSeconds - $elapsedSeconds) * 1000))))
    } while ($true)
    $endedAt = [DateTimeOffset]::Now
    $status = if ($finalConditions.satisfied -and $errors.Count -eq 0) { "passed" } else { "failed" }
    if ($status -eq "failed" -and $errors.Count -eq 0) { $errors += "Cooldown conditions were not met within $maximumSeconds seconds." }
    $relativePath = Join-Path "cooldowns" ($TransitionId + ".json")
    $artifact = [pscustomobject][ordered]@{
        schemaVersion = 1
        artifactType = "real_device_cooldown"
        transitionId = $TransitionId
        fromRunId = $PreviousRun.runId
        toPhase = $ToPhase
        status = $status
        startedAt = $startedAt.ToString("o")
        endedAt = $endedAt.ToString("o")
        durationMs = [Math]::Round(($endedAt - $startedAt).TotalMilliseconds)
        conditions = $finalConditions
        samples = @($samples.ToArray())
        errors = $errors
        artifactPath = $relativePath
    }
    Write-Utf8Json -Path (Join-Path $SessionRoot $relativePath) -Value $artifact -Depth 30
    return $artifact
}

function Convert-RunToCsvRow {
    param($Run, $CaseSummary, [string]$EffectiveSessionId, $Device)
    $stability = Get-PropertyValue -Object $CaseSummary -Name "stability"
    $cancellation = Get-PropertyValue -Object $CaseSummary -Name "cancellation"
    return [pscustomobject][ordered]@{
        sessionId = $EffectiveSessionId
        deviceSerial = Get-PropertyValue -Object $Device -Name "serial"
        deviceModel = Get-PropertyValue -Object $Device -Name "model"
        deviceSoc = Get-PropertyValue -Object $Device -Name "soc"
        caseId = $Run.caseId
        caseLabel = $Run.caseLabel
        kind = $Run.kind
        runIndex = $Run.runIndex
        phase = $Run.phase
        phaseRunIndex = $Run.phaseRunIndex
        aggregateEligible = $Run.aggregateEligible
        runId = $Run.runId
        status = $Run.status
        model = $Run.model
        configuredModel = $Run.configuredModel
        runtime = $Run.runtime
        configuredRuntime = $Run.configuredRuntime
        loadTimeMs = $Run.loadTimeMs
        ttftMs = $Run.ttftMs
        promptTokens = $Run.promptTokens
        completionTokens = $Run.completionTokens
        decodeDurationMs = $Run.decodeDurationMs
        decodeTps = $Run.decodeTps
        totalDurationMs = $Run.totalDurationMs
        imageLatencyMs = $Run.imageLatencyMs
        stepsCompleted = $Run.stepsCompleted
        stepsPerSecond = $Run.stepsPerSecond
        outputWidth = $Run.outputWidth
        outputHeight = $Run.outputHeight
        outputSha256 = $Run.outputSha256
        rssStartMb = $Run.rssStartMb
        peakRssMb = $Run.peakRssMb
        rssEndMb = $Run.rssEndMb
        peakAppRssMb = $Run.peakAppRssMb
        availableRamStartMb = $Run.availableRamStartMb
        availableRamEndMb = $Run.availableRamEndMb
        availableRamMinMb = $Run.availableRamMinMb
        batteryTemperatureStartC = $Run.batteryTemperatureStartC
        batteryTemperatureEndC = $Run.batteryTemperatureEndC
        batteryTemperatureMaxC = $Run.batteryTemperatureMaxC
        batteryTemperatureDeltaC = $Run.batteryTemperatureDeltaC
        thermalStatusStart = $Run.thermalStatusStart
        thermalStatusMax = $Run.thermalStatusMax
        thermalStatusEnd = $Run.thermalStatusEnd
        dmaBufStartMb = $Run.dmaBufStartMb
        dmaBufPeakMb = $Run.dmaBufPeakMb
        dmaBufEndMb = $Run.dmaBufEndMb
        cmaFreeMinMb = $Run.cmaFreeMinMb
        processLifecycle = $Run.processLifecycle
        cancellationResult = Get-PropertyValue -Object $cancellation -Name "result"
        cancellationDetail = Get-PropertyValue -Object $cancellation -Name "detail"
        cancelLatencyMs = Get-PropertyValue -Object $cancellation -Name "cancelLatencyMs"
        stabilityStatus = Get-PropertyValue -Object $stability -Name "status"
        stabilityPrimaryMetric = Get-PropertyValue -Object $stability -Name "primaryMetric"
        stabilitySampleCount = Get-PropertyValue -Object $stability -Name "sampleCount"
        stabilityMean = Get-PropertyValue -Object $stability -Name "mean"
        stabilitySampleStdDev = Get-PropertyValue -Object $stability -Name "sampleStandardDeviation"
        stabilityCvPercent = Get-PropertyValue -Object $stability -Name "coefficientOfVariationPercent"
        stabilityThresholdPercent = Get-PropertyValue -Object $stability -Name "thresholdPercent"
        stable = Get-PropertyValue -Object $stability -Name "stable"
        smokeJsonPath = $Run.smokeJsonPath
        telemetryJsonPath = $Run.telemetryJsonPath
        runJsonPath = $Run.runJsonPath
        warnings = @($Run.warnings) -join " | "
        errors = @($Run.errors) -join " | "
    }
}

function Get-ConfigValidationSummary {
    param($Config, [string]$ResolvedConfigPath)
    $document = $script:LastConfigDocument
    if ($null -eq $document) {
        throw "Benchmark config document metadata is unavailable for '$ResolvedConfigPath'."
    }
    $defaults = Get-PropertyValue -Object $Config -Name "defaults"
    $validatedCases = @()
    $definitionOnlyCaseCount = 0
    foreach ($case in @(Get-PropertyValue -Object $Config -Name "cases")) {
        $caseId = "$(Get-PropertyValue -Object $case -Name 'id')".Trim()
        $kind = "$(Get-PropertyValue -Object $case -Name 'kind')".Trim().ToLowerInvariant()
        $runPlan = @(Get-CaseRunPlan -Case $case -Defaults $defaults)
        $runs = @($runPlan | Where-Object { $_.phase -eq "measured" }).Count
        $warmupRuns = @($runPlan | Where-Object { $_.phase -eq "warmup" }).Count
        $definitionOnly = Convert-ToBool (Get-PropertyValue -Object $case -Name "definitionOnly")
        if ($definitionOnly) { $definitionOnlyCaseCount++ }
        $extras = Get-PropertyValue -Object $case -Name "extras"
        $extraCount = 0
        if ($null -ne $extras) {
            if ($extras -isnot [pscustomobject]) { throw "Case '$caseId' extras must be a JSON object." }
            foreach ($property in $extras.PSObject.Properties) {
                if ($property.Name -eq "runId") { continue }
                [void]@(Convert-SmokeExtraToArgs -Name $property.Name -Value $property.Value)
                $extraCount++
            }
        }
        $validatedCases += [pscustomobject][ordered]@{
            id = $caseId
            kind = $kind
            activity = Get-FirstValue @(
                (Get-PropertyValue -Object $case -Name "activity"),
                (Get-DefaultActivity -Kind $kind)
            )
            runs = $runs
            warmupRuns = $warmupRuns
            expectedTotalRuns = $runPlan.Count
            executionPlan = $runPlan
            extraCount = $extraCount
            definitionOnly = $definitionOnly
            cancellationProbe = Convert-ToBool (Get-CaseSetting -Case $case -Defaults $defaults -Name "cancellationProbe" -Fallback $false)
        }
    }
    $placeholderPaths = @($script:LastConfigPlaceholderPaths)
    return [pscustomobject][ordered]@{
        schemaVersion = 1
        artifactType = "benchmark_config_validation"
        valid = $true
        configSchemaVersion = Get-PropertyValue -Object $Config -Name "schemaVersion"
        configArtifactType = Get-PropertyValue -Object $Config -Name "artifactType"
        configPath = $ResolvedConfigPath
        configSha256 = $document.sha256
        configEncoding = $document.encoding
        caseCount = $validatedCases.Count
        definitionOnlyCaseCount = $definitionOnlyCaseCount
        unresolvedPlaceholderCount = $placeholderPaths.Count
        unresolvedPlaceholderPaths = $placeholderPaths
        executionReady = ($definitionOnlyCaseCount -eq 0 -and $placeholderPaths.Count -eq 0)
        cases = $validatedCases
    }
}

function Get-OfflineFixtureValue {
    param($Object, [string]$Name)
    if ($null -eq $Object) { return $null }
    if ($Object -is [Collections.IDictionary]) {
        if ($Object.Contains($Name)) { return $Object[$Name] }
        return $null
    }
    return Get-PropertyValue -Object $Object -Name $Name
}

function Test-OfflineFixtureNumber {
    param($Value)
    return $Value -is [byte] -or $Value -is [sbyte] -or
        $Value -is [int16] -or $Value -is [uint16] -or
        $Value -is [int32] -or $Value -is [uint32] -or
        $Value -is [int64] -or $Value -is [uint64] -or
        $Value -is [single] -or $Value -is [double] -or
        $Value -is [decimal]
}

function Test-OfflineFixtureInteger {
    param($Value, [int]$Expected)
    if (-not (Test-OfflineFixtureNumber $Value)) { return $false }
    $number = Convert-ToNullableDouble $Value
    return $null -ne $number -and $number -eq [Math]::Truncate($number) -and [int]$number -eq $Expected
}

function Assert-OfflineFixtureString {
    param($Value, [string]$Name)
    if ($Value -isnot [string] -or [string]::IsNullOrWhiteSpace($Value)) {
        throw "Offline fixture $Name must be a non-empty string."
    }
    return $Value.Trim()
}

function Resolve-OfflineFixturePath {
    param(
        [string]$ResolvedFixtureRoot,
        [string]$RelativePath,
        [string]$Description
    )
    $relative = Assert-OfflineFixtureString -Value $RelativePath -Name "$Description path"
    if ([IO.Path]::IsPathRooted($relative)) {
        throw "Offline fixture $Description path must be relative to the fixture root: $relative"
    }
    $root = [IO.Path]::GetFullPath($ResolvedFixtureRoot)
    $candidate = [IO.Path]::GetFullPath((Join-Path $root $relative))
    $rootPrefix = $root.TrimEnd([char[]]@([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)) + [IO.Path]::DirectorySeparatorChar
    if (-not $candidate.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Offline fixture $Description path escapes fixture root: $relative"
    }
    return $candidate
}

function Assert-OfflineFixtureManifest {
    param($Manifest)
    if ($Manifest -isnot [pscustomobject]) {
        throw "Offline fixture manifest root must be a JSON object."
    }
    if (-not (Test-OfflineFixtureInteger -Value (Get-PropertyValue -Object $Manifest -Name "schemaVersion") -Expected 1)) {
        throw "Offline fixture manifest schemaVersion must be integer 1."
    }
    $fixtureType = Get-PropertyValue -Object $Manifest -Name "fixtureType"
    if ($fixtureType -isnot [string] -or $fixtureType -cne "real_device_comparison_offline") {
        throw "Offline fixture manifest fixtureType must be 'real_device_comparison_offline'."
    }
    [void](Assert-OfflineFixtureString -Value (Get-PropertyValue -Object $Manifest -Name "configPath") -Name "manifest configPath")

    $smokeFixtures = Get-PropertyValue -Object $Manifest -Name "smokeFixtures"
    if ($smokeFixtures -isnot [array] -and $smokeFixtures -isnot [Collections.IList]) {
        throw "Offline fixture manifest smokeFixtures must be a JSON array."
    }
    $smokeFixtures = @($smokeFixtures)
    if ($smokeFixtures.Count -eq 0) {
        throw "Offline fixture manifest must contain at least one smoke fixture."
    }
    $fixtureIds = @{}
    foreach ($fixture in $smokeFixtures) {
        if ($fixture -isnot [pscustomobject]) {
            throw "Each offline smoke fixture must be a JSON object."
        }
        $id = Assert-OfflineFixtureString -Value (Get-PropertyValue -Object $fixture -Name "id") -Name "smoke fixture id"
        if ($fixtureIds.ContainsKey($id)) {
            throw "Duplicate offline smoke fixture id: $id"
        }
        $fixtureIds[$id] = $true
        [void](Assert-OfflineFixtureString -Value (Get-PropertyValue -Object $fixture -Name "caseId") -Name "smoke fixture '$id' caseId")
        [void](Assert-OfflineFixtureString -Value (Get-PropertyValue -Object $fixture -Name "path") -Name "smoke fixture '$id'")
        [void](Assert-OfflineFixtureString -Value (Get-PropertyValue -Object $fixture -Name "runId") -Name "smoke fixture '$id' runId")
        $kind = "$(Get-PropertyValue -Object $fixture -Name "kind")".Trim().ToLowerInvariant()
        if ($kind -notin @("chat", "vlm", "image", "image_store")) {
            throw "Offline smoke fixture '$id' has unsupported kind '$kind'."
        }
        $expectedMetrics = Get-PropertyValue -Object $fixture -Name "expectedMetrics"
        if ($expectedMetrics -isnot [pscustomobject] -or @($expectedMetrics.PSObject.Properties).Count -eq 0) {
            throw "Offline smoke fixture '$id' expectedMetrics must be a non-empty JSON object."
        }
    }

    $failureDiagnostics = Get-PropertyValue -Object $Manifest -Name "failureDiagnostics"
    if ($failureDiagnostics -isnot [pscustomobject]) {
        throw "Offline fixture manifest failureDiagnostics must be a JSON object."
    }
    [void](Assert-OfflineFixtureString -Value (Get-PropertyValue -Object $failureDiagnostics -Name "path") -Name "failure diagnostics")
    [void](Assert-OfflineFixtureString -Value (Get-PropertyValue -Object $failureDiagnostics -Name "runId") -Name "failure diagnostics runId")
    [void](Assert-OfflineFixtureString -Value (Get-PropertyValue -Object $failureDiagnostics -Name "reason") -Name "failure diagnostics reason")
    $expectedEvidenceNames = Get-PropertyValue -Object $failureDiagnostics -Name "expectedEvidenceNames"
    if ($expectedEvidenceNames -isnot [array] -and $expectedEvidenceNames -isnot [Collections.IList]) {
        throw "Offline fixture manifest failureDiagnostics.expectedEvidenceNames must be a JSON array."
    }
    if (@($expectedEvidenceNames).Count -eq 0) {
        throw "Offline fixture manifest failureDiagnostics.expectedEvidenceNames must not be empty."
    }
    foreach ($name in @($expectedEvidenceNames)) {
        [void](Assert-OfflineFixtureString -Value $name -Name "failure diagnostics evidence name")
    }
}

function Assert-OfflineMetricValue {
    param(
        $Actual,
        $Expected,
        [string]$Description
    )
    if ($null -eq $Expected) {
        if ($null -ne $Actual) {
            throw "$Description expected null, got '$Actual'."
        }
        return
    }
    if (Test-OfflineFixtureNumber $Expected) {
        $actualNumber = Convert-ToNullableDouble $Actual
        $expectedNumber = Convert-ToNullableDouble $Expected
        if ($null -eq $actualNumber -or [Math]::Abs($actualNumber - $expectedNumber) -gt 0.0001) {
            throw "$Description expected $expectedNumber, got '$Actual'."
        }
        return
    }
    if ($Expected -is [bool]) {
        if ($Actual -isnot [bool] -or $Actual -ne $Expected) {
            throw "$Description expected $Expected, got '$Actual'."
        }
        return
    }
    if ("$Actual" -cne "$Expected") {
        throw "$Description expected '$Expected', got '$Actual'."
    }
}

function Test-OfflineSmokeFixture {
    param(
        $Fixture,
        [string]$ResolvedFixtureRoot,
        [hashtable]$CasesById
    )
    $id = "$(Get-PropertyValue -Object $Fixture -Name "id")".Trim()
    $caseId = "$(Get-PropertyValue -Object $Fixture -Name "caseId")".Trim()
    if (-not $CasesById.ContainsKey($caseId)) {
        throw "Offline smoke fixture '$id' refers to unknown config case '$caseId'."
    }
    $case = $CasesById[$caseId]
    $kind = "$(Get-PropertyValue -Object $Fixture -Name "kind")".Trim().ToLowerInvariant()
    $caseKind = "$(Get-PropertyValue -Object $case -Name "kind")".Trim().ToLowerInvariant()
    if ($kind -cne $caseKind) {
        throw "Offline smoke fixture '$id' kind '$kind' does not match config case '$caseId' kind '$caseKind'."
    }
    $path = Resolve-OfflineFixturePath -ResolvedFixtureRoot $ResolvedFixtureRoot -RelativePath (Get-PropertyValue -Object $Fixture -Name "path") -Description "smoke fixture '$id'"
    $expectedRunId = "$(Get-PropertyValue -Object $Fixture -Name "runId")".Trim()
    $smoke = Read-SmokeJson -Path $path -ExpectedRunId $expectedRunId -RequireTerminal
    if (-not [string]::IsNullOrWhiteSpace($smoke.error)) {
        throw "Offline smoke fixture '$id' is invalid: $($smoke.error)"
    }
    $metrics = Convert-SmokeToMetrics -Smoke $smoke.value -Case $case -Kind $kind
    $metricNames = @(
        "smokeStatus", "smokeError", "model", "configuredModel", "runtime", "configuredRuntime",
        "loadTimeMs", "loadTimeSource", "ttftMs", "ttftSource", "promptTokens", "promptTokensSource",
        "completionTokens", "completionTokensSource", "decodeDurationMs", "decodeDurationSource",
        "decodeTps", "decodeTpsSource", "totalDurationMs", "totalDurationSource", "imageLatencyMs",
        "imageLatencySource", "firstProgressMs", "stepsCompleted", "stepsPerSecond", "outputRemotePath"
    )
    $expectedMetrics = Get-PropertyValue -Object $Fixture -Name "expectedMetrics"
    foreach ($property in $expectedMetrics.PSObject.Properties) {
        if ($property.Name -notin $metricNames) {
            throw "Offline smoke fixture '$id' has unsupported expected metric '$($property.Name)'."
        }
        $actual = Get-OfflineFixtureValue -Object $metrics -Name $property.Name
        Assert-OfflineMetricValue -Actual $actual -Expected $property.Value -Description "Offline smoke fixture '$id' metric '$($property.Name)'"
    }
    return [pscustomobject][ordered]@{
        id = $id
        status = "passed"
        fixturePath = $path
        runId = $smoke.contract.runId
        smokeStatus = $smoke.contract.status
        eventCount = $smoke.contract.eventCount
        assertedMetrics = @($expectedMetrics.PSObject.Properties | ForEach-Object { $_.Name })
    }
}

function Test-OfflineFailureDiagnosticsFixture {
    param(
        $Fixture,
        [string]$ResolvedFixtureRoot
    )
    $path = Resolve-OfflineFixturePath -ResolvedFixtureRoot $ResolvedFixtureRoot -RelativePath (Get-PropertyValue -Object $Fixture -Name "path") -Description "failure diagnostics"
    $document = Read-StrictUtf8JsonFile -Path $path
    $contract = Get-ArtifactContract -ArtifactType "real_device_failure_diagnostics"
    Assert-BenchmarkArtifact -Value $document.value -ExpectedArtifactType "real_device_failure_diagnostics" -RequiredProperties $contract.required

    $runId = "$(Get-PropertyValue -Object $document.value -Name "runId")".Trim()
    $reason = "$(Get-PropertyValue -Object $document.value -Name "reason")".Trim()
    if ($runId -cne "$(Get-PropertyValue -Object $Fixture -Name "runId")") {
        throw "Offline failure diagnostics runId mismatch. Expected '$($Fixture.runId)', got '$runId'."
    }
    if ($reason -cne "$(Get-PropertyValue -Object $Fixture -Name "reason")") {
        throw "Offline failure diagnostics reason mismatch. Expected '$($Fixture.reason)', got '$reason'."
    }
    $capturedAt = "$(Get-PropertyValue -Object $document.value -Name "capturedAt")"
    $parsedCaptureTime = [DateTimeOffset]::MinValue
    if (-not [DateTimeOffset]::TryParse($capturedAt, [ref]$parsedCaptureTime)) {
        throw "Offline failure diagnostics capturedAt must be an ISO-8601 timestamp."
    }
    $observedPid = Get-PropertyValue -Object $document.value -Name "observedPid"
    if ($null -ne $observedPid -and -not (Test-OfflineFixtureNumber $observedPid)) {
        throw "Offline failure diagnostics observedPid must be numeric or null."
    }
    $lastTelemetrySample = Get-PropertyValue -Object $document.value -Name "lastTelemetrySample"
    if ($null -ne $lastTelemetrySample -and $lastTelemetrySample -isnot [pscustomobject]) {
        throw "Offline failure diagnostics lastTelemetrySample must be a JSON object or null."
    }
    $evidence = Get-PropertyValue -Object $document.value -Name "evidence"
    if ($evidence -isnot [array] -and $evidence -isnot [Collections.IList]) {
        throw "Offline failure diagnostics evidence must be a JSON array."
    }
    $evidenceItems = @($evidence)
    $evidenceNames = @{}
    foreach ($entry in $evidenceItems) {
        if ($entry -isnot [pscustomobject]) {
            throw "Offline failure diagnostics evidence entries must be JSON objects."
        }
        $name = Assert-OfflineFixtureString -Value (Get-PropertyValue -Object $entry -Name "name") -Name "failure diagnostics evidence name"
        if ($evidenceNames.ContainsKey($name)) {
            throw "Offline failure diagnostics contains duplicate evidence '$name'."
        }
        $evidenceNames[$name] = $true
        if ((Get-PropertyValue -Object $entry -Name "ok") -isnot [bool]) {
            throw "Offline failure diagnostics evidence '$name' ok must be a JSON boolean."
        }
        if (-not (Test-OfflineFixtureNumber (Get-PropertyValue -Object $entry -Name "exitCode"))) {
            throw "Offline failure diagnostics evidence '$name' exitCode must be numeric."
        }
        $text = Get-PropertyValue -Object $entry -Name "text"
        if ($null -ne $text -and $text -isnot [string]) {
            throw "Offline failure diagnostics evidence '$name' text must be a string or null."
        }
    }
    foreach ($expectedName in @(Get-PropertyValue -Object $Fixture -Name "expectedEvidenceNames")) {
        if (-not $evidenceNames.ContainsKey("$expectedName")) {
            throw "Offline failure diagnostics is missing expected evidence '$expectedName'."
        }
    }
    return [pscustomobject][ordered]@{
        id = "failure_diagnostics"
        status = "passed"
        fixturePath = $path
        runId = $runId
        reason = $reason
        evidenceCount = $evidenceItems.Count
    }
}

function Invoke-OfflineValidation {
    param(
        [string]$ResolvedFixtureRoot,
        [string]$ConfigOverridePath
    )
    if (-not (Test-Path -LiteralPath $ResolvedFixtureRoot -PathType Container)) {
        throw "Offline fixture root not found: $ResolvedFixtureRoot"
    }
    $startedAt = [DateTimeOffset]::Now
    $manifestPath = Join-Path $ResolvedFixtureRoot "fixture-manifest.json"
    $manifestDocument = Read-StrictUtf8JsonFile -Path $manifestPath
    $manifest = $manifestDocument.value
    Assert-OfflineFixtureManifest -Manifest $manifest

    $tests = New-Object System.Collections.Generic.List[object]
    [void]$tests.Add([pscustomobject][ordered]@{
        id = "fixture_manifest_schema"
        status = "passed"
        fixturePath = $manifestPath
        fixtureSha256 = $manifestDocument.sha256
    })

    $configPath = if ([string]::IsNullOrWhiteSpace($ConfigOverridePath)) {
        Resolve-OfflineFixturePath -ResolvedFixtureRoot $ResolvedFixtureRoot -RelativePath (Get-PropertyValue -Object $manifest -Name "configPath") -Description "config"
    } else {
        Resolve-WorkspacePath $ConfigOverridePath
    }
    $config = Read-BenchmarkConfig -Path $configPath
    $configSummary = Get-ConfigValidationSummary -Config $config -ResolvedConfigPath $configPath
    $configContract = Get-ArtifactContract -ArtifactType "benchmark_config_validation"
    Assert-BenchmarkArtifact -Value $configSummary -ExpectedArtifactType "benchmark_config_validation" -RequiredProperties $configContract.required
    [void]$tests.Add([pscustomobject][ordered]@{
        id = "config_schema"
        status = "passed"
        config = $configSummary
    })

    $casesById = @{}
    foreach ($case in @(Get-PropertyValue -Object $config -Name "cases")) {
        $caseId = "$(Get-PropertyValue -Object $case -Name "id")".Trim()
        $casesById[$caseId] = $case
    }
    foreach ($fixture in @(Get-PropertyValue -Object $manifest -Name "smokeFixtures")) {
        [void]$tests.Add((Test-OfflineSmokeFixture -Fixture $fixture -ResolvedFixtureRoot $ResolvedFixtureRoot -CasesById $casesById))
    }
    [void]$tests.Add((Test-OfflineFailureDiagnosticsFixture -Fixture (Get-PropertyValue -Object $manifest -Name "failureDiagnostics") -ResolvedFixtureRoot $ResolvedFixtureRoot))

    $endedAt = [DateTimeOffset]::Now
    $summary = [pscustomobject][ordered]@{
        schemaVersion = 1
        artifactType = "benchmark_offline_validation"
        status = "passed"
        startedAt = $startedAt.ToString("o")
        endedAt = $endedAt.ToString("o")
        fixtureRoot = $ResolvedFixtureRoot
        testCount = $tests.Count
        tests = @($tests.ToArray())
    }
    $summaryContract = Get-ArtifactContract -ArtifactType "benchmark_offline_validation"
    Assert-BenchmarkArtifact -Value $summary -ExpectedArtifactType "benchmark_offline_validation" -RequiredProperties $summaryContract.required
    return $summary
}

if ($OfflineValidation) {
    $defaultFixtureRoot = Join-Path $PSScriptRoot "fixtures\real-device-comparison-offline"
    $resolvedFixtureRoot = if ([string]::IsNullOrWhiteSpace($FixtureRoot)) {
        [IO.Path]::GetFullPath($defaultFixtureRoot)
    } else {
        Resolve-WorkspacePath $FixtureRoot
    }
    $offlineSummary = Invoke-OfflineValidation -ResolvedFixtureRoot $resolvedFixtureRoot -ConfigOverridePath $ConfigPath
    $offlineSummary | ConvertTo-Json -Depth 30
    return
}
$resolvedConfigPath = Resolve-WorkspacePath $ConfigPath
$config = Read-BenchmarkConfig -Path $resolvedConfigPath
$validationSummary = Get-ConfigValidationSummary -Config $config -ResolvedConfigPath $resolvedConfigPath
if ($ValidateConfigOnly) {
    $validationSummary | ConvertTo-Json -Depth 8
    return
}
if (-not $validationSummary.executionReady) {
    throw "Benchmark config is definition-only or contains unresolved placeholders (definitionOnlyCases=$($validationSummary.definitionOnlyCaseCount), placeholders=$($validationSummary.unresolvedPlaceholderCount)). Freeze a separate execution config before using a device."
}

$script:Serial = $Serial
Require-AdbDevice
$defaults = Get-PropertyValue -Object $config -Name "defaults"
$sessionStartedAt = [DateTimeOffset]::Now
$sessionStamp = $sessionStartedAt.ToString("yyyyMMdd-HHmmss-fff")
$effectiveSessionId = if ([string]::IsNullOrWhiteSpace($SessionId)) {
    "real-device-$sessionStamp"
} else {
    Convert-ToSafeId $SessionId
}
$resolvedOutDir = Resolve-WorkspacePath $OutDir
$sessionRoot = Join-Path $resolvedOutDir $effectiveSessionId
New-Item -ItemType Directory -Force -Path $sessionRoot | Out-Null
$configCopyPath = Join-Path $sessionRoot "config.json"
Write-Utf8Text -Path $configCopyPath -Content (Get-Content -LiteralPath $resolvedConfigPath -Raw -Encoding UTF8)
$device = [pscustomobject](Get-DeviceMetadata)
$allRuns = New-Object System.Collections.Generic.List[object]
$allCooldowns = New-Object System.Collections.Generic.List[object]
$caseSummaries = New-Object System.Collections.Generic.List[object]
$caseList = @(Get-PropertyValue -Object $config -Name "cases")

Write-Host "Real-device benchmark session: $effectiveSessionId"
Write-Host "Device: $($device.serial) / $($device.model) / $($device.soc)"
for ($caseIndex = 0; $caseIndex -lt $caseList.Count; $caseIndex++) {
    $case = $caseList[$caseIndex]
    $caseId = "$(Get-PropertyValue -Object $case -Name 'id')".Trim()
    $caseLabel = Get-FirstValue @((Get-PropertyValue -Object $case -Name "label"), $caseId)
    $kind = "$(Get-PropertyValue -Object $case -Name 'kind')".Trim().ToLowerInvariant()
    $runPlan = @(Get-CaseRunPlan -Case $case -Defaults $defaults)
    $runCount = @($runPlan | Where-Object { $_.phase -eq "measured" }).Count
    $warmupRunCount = @($runPlan | Where-Object { $_.phase -eq "warmup" }).Count
    $caseRuns = New-Object System.Collections.Generic.List[object]
    $caseCooldowns = New-Object System.Collections.Generic.List[object]
    $safeCaseId = Convert-ToSafeId $caseId
    $previousRun = $null
    Write-Host "[$($caseIndex + 1)/$($caseList.Count)] $caseLabel ($warmupRunCount warmup + $runCount measured)"

    foreach ($plannedRun in $runPlan) {
        $precedingCooldown = $null
        if ($null -ne $previousRun) {
            $transitionId = "$safeCaseId-$sessionStamp-c$((([int]$plannedRun.runIndex) - 1).ToString('00'))"
            $toPhase = "$($plannedRun.phase)-$(([int]$plannedRun.phaseRunIndex).ToString('00'))"
            Write-Host "  Cooldown before $toPhase ..."
            $precedingCooldown = Invoke-Cooldown -Case $case -Defaults $defaults -PreviousRun $previousRun -TransitionId $transitionId -ToPhase $toPhase -SessionRoot $sessionRoot
            [void]$caseCooldowns.Add($precedingCooldown)
            [void]$allCooldowns.Add($precedingCooldown)
            Write-Host "    cooldown=$($precedingCooldown.status) durationMs=$($precedingCooldown.durationMs)"
        }

        $aggregateEligible = [bool]$plannedRun.aggregateEligible -and ($null -eq $precedingCooldown -or $precedingCooldown.status -eq "passed")
        $phaseTotal = if ($plannedRun.phase -eq "warmup") { $warmupRunCount } else { $runCount }
        Write-Host "  $($plannedRun.phase) $($plannedRun.phaseRunIndex)/$phaseTotal ..."
        $run = Invoke-SmokeBenchmarkRun `
            -Case $case `
            -Defaults $defaults `
            -RunIndex $plannedRun.runIndex `
            -Phase $plannedRun.phase `
            -PhaseRunIndex $plannedRun.phaseRunIndex `
            -AggregateEligible $aggregateEligible `
            -SessionRoot $sessionRoot `
            -SessionStamp $sessionStamp
        if ($null -ne $precedingCooldown -and $precedingCooldown.status -ne "passed") {
            $run.aggregateEligible = $false
            $run.warnings = @($run.warnings) + "Preceding cooldown '$($precedingCooldown.transitionId)' failed; this run is excluded from aggregates."
        }
        [void]$caseRuns.Add($run)
        [void]$allRuns.Add($run)
        $previousRun = $run
        Write-Host "    status=$($run.status) loadMs=$($run.loadTimeMs) ttftMs=$($run.ttftMs) decodeTps=$($run.decodeTps) imageMs=$($run.imageLatencyMs) peakRssMb=$($run.peakAppRssMb)"
    }

    $caseRunArray = @($caseRuns.ToArray())
    $stability = Get-CaseStability -Case $case -Defaults $defaults -Runs $caseRunArray
    $referenceRun = @($caseRunArray | Where-Object {
        $_.status -eq "completed" -and $_.phase -eq "measured" -and (Convert-ToBool $_.aggregateEligible)
    } | Select-Object -Last 1)
    $referenceRunValue = if ($referenceRun.Count -gt 0) { $referenceRun[0] } else { $null }
    Write-Host "  Cancellation probe ..."
    $cancellation = Invoke-CancellationProbe -Case $case -Defaults $defaults -ReferenceRun $referenceRunValue
    $caseSummary = [pscustomobject][ordered]@{
        caseId = $caseId
        caseLabel = $caseLabel
        kind = $kind
        configuredWarmupRuns = $warmupRunCount
        configuredRuns = $runCount
        completedWarmupRuns = @($caseRunArray | Where-Object { $_.phase -eq "warmup" -and $_.status -eq "completed" }).Count
        failedWarmupRuns = @($caseRunArray | Where-Object { $_.phase -eq "warmup" -and $_.status -ne "completed" }).Count
        completedRuns = @($caseRunArray | Where-Object { $_.phase -eq "measured" -and $_.status -eq "completed" }).Count
        failedRuns = @($caseRunArray | Where-Object { $_.phase -eq "measured" -and $_.status -ne "completed" }).Count
        cooldowns = @($caseCooldowns.ToArray() | ForEach-Object { $_.transitionId })
        stability = $stability
        cancellation = $cancellation
    }
    [void]$caseSummaries.Add($caseSummary)
    foreach ($runItem in $caseRunArray) {
        $runItem | Add-Member -NotePropertyName stability -NotePropertyValue $stability -Force
        $runItem | Add-Member -NotePropertyName cancellation -NotePropertyValue $cancellation -Force
        Write-Utf8Json -Path (Join-Path $sessionRoot $runItem.runJsonPath) -Value $runItem
    }
    Write-Host "  Stability: $($stability.status), metric=$($stability.primaryMetric), CV=$($stability.coefficientOfVariationPercent)%"
    Write-Host "  Cancellation: $($cancellation.result)"
}

$sessionEndedAt = [DateTimeOffset]::Now
$runArray = @($allRuns.ToArray())
$cooldownArray = @($allCooldowns.ToArray())
$caseSummaryArray = @($caseSummaries.ToArray())
$hasRunErrors = @($runArray | Where-Object { $_.status -ne "completed" }).Count -gt 0
$hasCooldownFailures = @($cooldownArray | Where-Object { $_.status -ne "passed" }).Count -gt 0
$hasCancellationFailures = @($caseSummaryArray | Where-Object { $_.cancellation.result -eq "failed" }).Count -gt 0
$sessionJsonPath = Join-Path $sessionRoot "session.json"
$sessionCsvPath = Join-Path $sessionRoot "runs.csv"
$session = [pscustomobject][ordered]@{
    schemaVersion = 1
    artifactType = "real_device_session"
    sessionId = $effectiveSessionId
    status = if ($hasRunErrors -or $hasCooldownFailures -or $hasCancellationFailures) { "failed" } else { "completed" }
    startedAt = $sessionStartedAt.ToString("o")
    endedAt = $sessionEndedAt.ToString("o")
    durationMs = [Math]::Round(($sessionEndedAt - $sessionStartedAt).TotalMilliseconds)
    configSourcePath = $resolvedConfigPath
    configSnapshotPath = "config.json"
    device = $device
    packageName = $PackageName
    runCount = $runArray.Count
    hasRunErrors = $hasRunErrors
    hasCooldownFailures = $hasCooldownFailures
    hasCancellationFailures = $hasCancellationFailures
    cases = $caseSummaryArray
    runs = $runArray
    cooldowns = $cooldownArray
    artifacts = [ordered]@{
        json = "session.json"
        csv = "runs.csv"
        cooldownDirectory = "cooldowns"
    }
}
Write-Utf8Json -Path $sessionJsonPath -Value $session -Depth 50

$summaryByCase = @{}
foreach ($summary in $caseSummaryArray) { $summaryByCase[$summary.caseId] = $summary }
$csvRows = @($runArray | ForEach-Object {
    Convert-RunToCsvRow -Run $_ -CaseSummary $summaryByCase[$_.caseId] -EffectiveSessionId $effectiveSessionId -Device $device
})
$csvRows | Export-Csv -LiteralPath $sessionCsvPath -NoTypeInformation -Encoding UTF8

Write-Host "Benchmark complete."
Write-Host "JSON: $sessionJsonPath"
Write-Host "CSV:  $sessionCsvPath"
if (($hasRunErrors -or $hasCooldownFailures -or $hasCancellationFailures) -and -not $NoFailOnRunError) {
    exit 2
}
