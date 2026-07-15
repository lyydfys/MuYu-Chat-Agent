param(
    [string]$Adb = "adb",
    [string]$Serial = "",
    [string]$PackageName = "com.muyuchat.mca",
    [string]$OutDir = "",
    [switch]$Pull,
    [switch]$RequireDevice,
    [switch]$Json
)

$ErrorActionPreference = "Stop"

function New-Check {
    param(
        [string]$Id,
        [string]$Status,
        [string]$Message,
        [object]$Details = $null
    )
    [pscustomobject]@{
        id = $Id
        status = $Status
        message = $Message
        details = $Details
    }
}

function Add-Check {
    param([object]$Check)
    $script:Checks += $Check
}

function Invoke-Adb {
    param([string[]]$Arguments)
    $adbArgs = @()
    if (-not [string]::IsNullOrWhiteSpace($Serial)) {
        $adbArgs += @("-s", $Serial)
    }
    $adbArgs += $Arguments
    & $Adb @adbArgs 2>$null
}

function Get-AdbDevices {
    $lines = & $Adb devices -l 2>$null
    $devices = @()
    foreach ($line in $lines | Select-Object -Skip 1) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $parts = $line.Trim() -split "\s+"
        if ($parts.Count -lt 2) { continue }
        $devices += [pscustomobject]@{
            serial = $parts[0]
            state = $parts[1]
            raw = $line.Trim()
        }
    }
    return $devices
}

function Get-Prop {
    param([string]$Name)
    $value = (Invoke-Adb -Arguments @("shell", "getprop", $Name) | Select-Object -First 1)
    if ($null -eq $value) { return "" }
    return ([string]$value).Trim()
}

function Get-RemoteFileInfo {
    param([string]$Path)
    $escaped = $Path.Replace("'", "'\''")
    $command = "if [ -f '$escaped' ]; then wc -c < '$escaped'; fi"
    $sizeText = (Invoke-Adb -Arguments @("shell", $command) | Select-Object -First 1)
    $size = [int64]0
    [void][int64]::TryParse(([string]$sizeText).Trim(), [ref]$size)
    [pscustomobject]@{
        path = $Path
        bytes = $size
    }
}

function Find-QnnRuntimeFiles {
    $dirs = @(
        "/data/local/tmp/qnn",
        "/data/local/tmp/mca-qnn",
        "/vendor/lib64",
        "/vendor/lib/rfsa/adsp",
        "/odm/lib64",
        "/system/lib64",
        "/system_ext/lib64",
        "/product/lib64"
    )
    $files = @()
    foreach ($dir in $dirs) {
        $escaped = $dir.Replace("'", "'\''")
        $command = "if [ -d '$escaped' ]; then find '$escaped' -maxdepth 6 -type f -name 'libQnn*.so' 2>/dev/null; fi"
        $files += Invoke-Adb -Arguments @("shell", $command)
    }
    $files |
        ForEach-Object { ([string]$_).Trim() } |
        Where-Object { $_ -match "^/" -and $_ -match "/libQnn.*\.so$" } |
        Sort-Object -Unique
}

function Find-PackageNativeLibs {
    param([string]$Name)
    if ([string]::IsNullOrWhiteSpace($Name)) { return @() }
    $packagePaths = Invoke-Adb -Arguments @("shell", "pm", "path", $Name) |
        ForEach-Object { ([string]$_).Trim() } |
        Where-Object { $_ -like "package:*" } |
        ForEach-Object { $_.Substring("package:".Length) }
    if (@($packagePaths).Count -eq 0) { return @() }

    $dirs = @()
    foreach ($packagePath in $packagePaths) {
        $packageDir = $packagePath -replace "/base\.apk$", ""
        if (-not [string]::IsNullOrWhiteSpace($packageDir)) {
            $dirs += "$packageDir/lib/arm64"
            $dirs += "$packageDir/lib/arm64-v8a"
        }
    }
    $files = @()
    foreach ($dir in $dirs | Sort-Object -Unique) {
        $escaped = $dir.Replace("'", "'\''")
        $command = "if [ -d '$escaped' ]; then find '$escaped' -maxdepth 2 -type f -name 'libQnn*.so' 2>/dev/null; fi"
        $files += Invoke-Adb -Arguments @("shell", $command)
    }
    $files |
        ForEach-Object { ([string]$_).Trim() } |
        Where-Object { $_ -match "^/" -and $_ -match "/libQnn.*\.so$" } |
        Sort-Object -Unique
}

function Copy-RemoteQnnFile {
    param(
        [string]$RemotePath,
        [string]$TargetDir
    )
    $fileName = Split-Path -Leaf $RemotePath
    if ([string]::IsNullOrWhiteSpace($fileName)) { return $null }
    New-Item -ItemType Directory -Force -Path $TargetDir | Out-Null
    $target = Join-Path $TargetDir $fileName
    $output = Invoke-Adb -Arguments @("pull", $RemotePath, $target)
    [pscustomobject]@{
        remote = $RemotePath
        local = $target
        output = @($output)
        exists = Test-Path -LiteralPath $target -PathType Leaf
    }
}

$script:Checks = @()
$adbCommand = Get-Command $Adb -ErrorAction SilentlyContinue
if (-not $adbCommand) {
    Add-Check (New-Check "adb.command" "fail" "adb command was not found." @{ adb = $Adb })
} else {
    Add-Check (New-Check "adb.command" "pass" "adb command found." @{ adb = $adbCommand.Source })
}

$selectedDevice = $null
$devices = @()
if ($adbCommand) {
    $devices = @(Get-AdbDevices)
    if (-not [string]::IsNullOrWhiteSpace($Serial)) {
        $selectedDevice = $devices | Where-Object { $_.serial -eq $Serial } | Select-Object -First 1
    } else {
        $selectedDevice = $devices | Where-Object { $_.state -eq "device" } | Select-Object -First 1
    }

    if ($null -eq $selectedDevice) {
        $offline = @($devices | Where-Object { $_.state -eq "offline" })
        if ($offline.Count -gt 0) {
            Add-Check (New-Check "device.adb" "fail" "ADB sees an offline device. Reconnect USB or re-authorize USB debugging." @{ devices = $devices })
        } elseif ($RequireDevice) {
            Add-Check (New-Check "device.adb" "fail" "No authorized ADB device connected." @{ devices = $devices })
        } else {
            Add-Check (New-Check "device.adb" "warn" "No authorized ADB device connected." @{ devices = $devices })
        }
    } else {
        if ([string]::IsNullOrWhiteSpace($Serial)) {
            $Serial = $selectedDevice.serial
        }
        Add-Check (New-Check "device.adb" "pass" "ADB device connected." @{ device = $selectedDevice })
    }
}

$props = $null
$runtimeFiles = @()
$runtimeInfos = @()
$packageFiles = @()
$packageInfos = @()
$pulled = @()

if ($adbCommand -and $null -ne $selectedDevice -and $selectedDevice.state -eq "device") {
    $props = [pscustomobject]@{
        serial = $selectedDevice.serial
        socModel = Get-Prop "ro.soc.model"
        boardPlatform = Get-Prop "ro.board.platform"
        hardware = Get-Prop "ro.hardware"
        productModel = Get-Prop "ro.product.model"
        manufacturer = Get-Prop "ro.product.manufacturer"
        androidRelease = Get-Prop "ro.build.version.release"
        androidSdk = Get-Prop "ro.build.version.sdk"
        supportedAbis = Get-Prop "ro.product.cpu.abilist"
    }
    Add-Check (New-Check "device.props" "pass" "Device properties captured." $props)

    $runtimeFiles = @(Find-QnnRuntimeFiles)
    foreach ($file in $runtimeFiles) {
        $runtimeInfos += Get-RemoteFileInfo $file
    }
    $system = $runtimeInfos | Where-Object { $_.path -match "/libQnnSystem\.so$" -and $_.bytes -gt 0 } | Select-Object -First 1
    $htp = $runtimeInfos | Where-Object { $_.path -match "/libQnnHtp\.so$" -and $_.bytes -gt 0 } | Select-Object -First 1
    $skel = $runtimeInfos | Where-Object { $_.path -match "/libQnnHtpV.*Skel\.so$" -and $_.bytes -gt 0 } | Select-Object -First 1
    if ($system -and $htp -and $skel) {
        Add-Check (New-Check "device.qnn.runtime" "pass" "Complete QNN runtime files found on device." @{
            system = $system
            htp = $htp
            skel = $skel
            files = $runtimeInfos
        })
    } else {
        Add-Check (New-Check "device.qnn.runtime" "warn" "Complete QNN runtime was not found in readable device paths." @{
            hasSystem = [bool]$system
            hasHtp = [bool]$htp
            hasSkel = [bool]$skel
            files = $runtimeInfos
        })
    }

    $packageFiles = @(Find-PackageNativeLibs $PackageName)
    foreach ($file in $packageFiles) {
        $packageInfos += Get-RemoteFileInfo $file
    }
    if ($packageInfos.Count -gt 0) {
        Add-Check (New-Check "device.package.qnn" "pass" "QNN libraries found in package native lib directories." @{
            packageName = $PackageName
            files = $packageInfos
        })
    } else {
        Add-Check (New-Check "device.package.qnn" "warn" "No QNN libraries found in package native lib directories." @{
            packageName = $PackageName
        })
    }

    if ($Pull) {
        if ([string]::IsNullOrWhiteSpace($OutDir)) {
            $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
            $OutDir = Join-Path (Resolve-Path ".").Path ".tmp/qnn-device-runtime-$stamp"
        }
        foreach ($file in @($runtimeInfos + $packageInfos) | Where-Object { $_.bytes -gt 0 }) {
            $pulled += Copy-RemoteQnnFile $file.path $OutDir
        }
        $success = @($pulled | Where-Object { $_.exists }).Count
        if ($success -gt 0) {
            Add-Check (New-Check "device.qnn.pull" "pass" "Pulled QNN libraries from device." @{
                outDir = $OutDir
                pulled = $pulled
            })
        } else {
            Add-Check (New-Check "device.qnn.pull" "warn" "No QNN libraries were pulled." @{
                outDir = $OutDir
                pulled = $pulled
            })
        }
    }
}

$failCount = @($Checks | Where-Object { $_.status -eq "fail" }).Count
$warnCount = @($Checks | Where-Object { $_.status -eq "warn" }).Count
$passCount = @($Checks | Where-Object { $_.status -eq "pass" }).Count
$result = [pscustomobject]@{
    ok = $failCount -eq 0
    pass = $passCount
    warn = $warnCount
    fail = $failCount
    props = $props
    runtimeFiles = $runtimeInfos
    packageFiles = $packageInfos
    pulled = $pulled
    checks = $Checks
}

if ($Json) {
    $result | ConvertTo-Json -Depth 12
} else {
    foreach ($check in $Checks) {
        $prefix = switch ($check.status) {
            "pass" { "[PASS]" }
            "warn" { "[WARN]" }
            default { "[FAIL]" }
        }
        Write-Host "$prefix $($check.id): $($check.message)"
    }
    Write-Host "Summary: pass=$passCount warn=$warnCount fail=$failCount"
}

if ($failCount -gt 0) {
    exit 2
}
exit 0
