param(
    [string]$QnnSdkRoot = "",
    [string]$BundleRoot = "",
    [switch]$RequireDevice,
    [switch]$Json
)

$ErrorActionPreference = "Stop"

function First-NonBlank {
    param([string[]]$Values)
    foreach ($value in $Values) {
        if (-not [string]::IsNullOrWhiteSpace($value)) {
            return $value
        }
    }
    return ""
}

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

function Test-RelativeBundlePath {
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path)) { return $false }
    $normalized = $Path.Replace("\", "/").Trim()
    if ($normalized.StartsWith("/") -or $normalized.StartsWith("./")) { return $false }
    if ($normalized -match "^[A-Za-z]:") { return $false }
    foreach ($segment in $normalized.Split("/")) {
        if ([string]::IsNullOrWhiteSpace($segment) -or $segment -eq "." -or $segment -eq "..") {
            return $false
        }
    }
    return $true
}

function Resolve-BundleFile {
    param(
        [string]$Root,
        [string]$RelativePath
    )
    if (-not (Test-RelativeBundlePath $RelativePath)) { return $null }
    $rootFull = [System.IO.Path]::GetFullPath($Root)
    $candidate = [System.IO.Path]::GetFullPath([System.IO.Path]::Combine($rootFull, $RelativePath))
    if ($candidate -eq $rootFull -or $candidate.StartsWith($rootFull + [System.IO.Path]::DirectorySeparatorChar)) {
        return $candidate
    }
    return $null
}

function Get-DTypeBytes {
    param([string]$DataType)
    switch ($DataType.Trim().ToLowerInvariant()) {
        "bool" { return 1 }
        "int8" { return 1 }
        "uint8" { return 1 }
        "float16" { return 2 }
        "fp16" { return 2 }
        "int16" { return 2 }
        "uint16" { return 2 }
        "float32" { return 4 }
        "fp32" { return 4 }
        "int32" { return 4 }
        "uint32" { return 4 }
        "float64" { return 8 }
        "fp64" { return 8 }
        "int64" { return 8 }
        "uint64" { return 8 }
        default { return 0 }
    }
}

function Get-TensorPlan {
    param(
        [object[]]$Tensors,
        [string]$Role
    )
    $plans = @()
    foreach ($tensor in $Tensors) {
        $name = [string]$tensor.name
        $dtype = [string](First-NonBlank @([string]$tensor.dataType, [string]$tensor.dtype, [string]$tensor.type))
        $shape = @()
        if ($null -ne $tensor.shape) {
            foreach ($dim in $tensor.shape) {
                $value = [int64]$dim
                if ($value -gt 0) { $shape += $value }
            }
        }
        $bytesPerElement = Get-DTypeBytes $dtype
        $elementCount = [int64]1
        $supported = $true
        $reason = ""
        if ([string]::IsNullOrWhiteSpace($name)) {
            $supported = $false
            $reason = "Tensor name is required."
        } elseif ([string]::IsNullOrWhiteSpace($dtype) -or $bytesPerElement -le 0) {
            $supported = $false
            $reason = "Unsupported or missing data type."
        } elseif ($shape.Count -eq 0) {
            $supported = $false
            $reason = "Positive shape is required."
        } else {
            foreach ($dim in $shape) {
                if ($elementCount -gt [int64]::MaxValue / $dim) {
                    $supported = $false
                    $reason = "Tensor element count overflows Int64."
                    break
                }
                $elementCount *= $dim
            }
        }
        $byteSize = [int64]0
        if ($supported) {
            if ($elementCount -gt [int64]::MaxValue / $bytesPerElement) {
                $supported = $false
                $reason = "Tensor byte size overflows Int64."
            } else {
                $byteSize = $elementCount * $bytesPerElement
            }
        }
        $plans += [pscustomobject]@{
            name = $name
            role = $Role
            dataType = $dtype
            shape = $shape
            elementCount = $elementCount
            bytesPerElement = $bytesPerElement
            byteSize = $byteSize
            supported = $supported
            reason = $reason
        }
    }
    return $plans
}

function Test-DuplicateNames {
    param(
        [object[]]$Plans,
        [string]$Role
    )
    $duplicates = $Plans |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_.name) } |
        Group-Object -Property name |
        Where-Object { $_.Count -gt 1 } |
        ForEach-Object { $_.Name }
    foreach ($duplicate in $duplicates) {
        Add-Check (New-Check "bundle.$Role.duplicate.$duplicate" "fail" "Duplicate $Role tensor name: $duplicate.")
    }
}

function Get-ManifestQnnSmokeSpecs {
    param([object]$Manifest)

    # The legacy smoke object describes a semantic image run and may not have
    # graph/tensor bindings. Prefer executable specs from a smoke suite when
    # one is present, without requiring a downloaded bundle rewrite.
    foreach ($arrayName in @("smokes", "smokeSpecs")) {
        $candidate = $Manifest.$arrayName
        [object[]]$items = @()
        if ($null -ne $candidate) { $items = @($candidate) }
        $specs = @($items | Where-Object { $_ -is [pscustomobject] })
        if ($specs.Count -gt 0) { return $specs }
    }
    foreach ($legacyName in @("smoke", "smokeSpec")) {
        $candidate = $Manifest.$legacyName
        if ($candidate -is [pscustomobject]) { return @($candidate) }
    }
    return @()
}

$script:Checks = @()

if ([string]::IsNullOrWhiteSpace($QnnSdkRoot)) {
    $QnnSdkRoot = First-NonBlank @(
        $env:MCA_QNN_SDK_ROOT,
        $env:QNN_SDK_ROOT,
        $env:QAIRT_SDK_ROOT
    )
}

if ([string]::IsNullOrWhiteSpace($QnnSdkRoot)) {
    Add-Check (New-Check "sdk.root" "fail" "QNN SDK root is not set. Use -QnnSdkRoot or MCA_QNN_SDK_ROOT/QNN_SDK_ROOT/QAIRT_SDK_ROOT.")
} else {
    $sdkRootFull = [System.IO.Path]::GetFullPath($QnnSdkRoot)
    if (-not (Test-Path -LiteralPath $sdkRootFull -PathType Container)) {
        Add-Check (New-Check "sdk.root" "fail" "QNN SDK root does not exist: $sdkRootFull")
    } else {
        Add-Check (New-Check "sdk.root" "pass" "QNN SDK root found." @{ path = $sdkRootFull })
        $requiredHeaders = @(
            "include/QNN/QnnInterface.h",
            "include/QNN/QnnBackend.h",
            "include/QNN/QnnContext.h",
            "include/QNN/QnnGraph.h",
            "include/QNN/QnnTensor.h"
        )
        foreach ($header in $requiredHeaders) {
            $path = Join-Path $sdkRootFull $header
            if (Test-Path -LiteralPath $path -PathType Leaf) {
                Add-Check (New-Check "sdk.header.$($header.Replace('/','.'))" "pass" "Header found: $header")
            } else {
                Add-Check (New-Check "sdk.header.$($header.Replace('/','.'))" "fail" "Header missing: $header")
            }
        }
        $runtimeNames = @("libQnnSystem.so", "libQnnHtp.so")
        foreach ($runtimeName in $runtimeNames) {
            $found = Get-ChildItem -LiteralPath $sdkRootFull -Filter $runtimeName -Recurse -File -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($found) {
                Add-Check (New-Check "sdk.runtime.$runtimeName" "pass" "Runtime library found: $runtimeName" @{ path = $found.FullName })
            } else {
                Add-Check (New-Check "sdk.runtime.$runtimeName" "warn" "Runtime library not found under SDK root: $runtimeName")
            }
        }
        $skel = Get-ChildItem -LiteralPath $sdkRootFull -Filter "libQnnHtpV*Skel.so" -Recurse -File -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($skel) {
            Add-Check (New-Check "sdk.runtime.htp_skel" "pass" "HTP skel library found." @{ path = $skel.FullName })
        } else {
            Add-Check (New-Check "sdk.runtime.htp_skel" "warn" "HTP skel library not found under SDK root.")
        }
    }
}

if (-not [string]::IsNullOrWhiteSpace($BundleRoot)) {
    $bundleRootFull = [System.IO.Path]::GetFullPath($BundleRoot)
    if (-not (Test-Path -LiteralPath $bundleRootFull -PathType Container)) {
        Add-Check (New-Check "bundle.root" "fail" "Bundle root does not exist: $bundleRootFull")
    } else {
        Add-Check (New-Check "bundle.root" "pass" "Bundle root found." @{ path = $bundleRootFull })
        $manifestFile = Get-ChildItem -LiteralPath $bundleRootFull -Filter "manifest.json" -Recurse -File -ErrorAction SilentlyContinue | Select-Object -First 1
        if (-not $manifestFile) {
            Add-Check (New-Check "bundle.manifest" "fail" "manifest.json was not found under bundle root.")
        } else {
            Add-Check (New-Check "bundle.manifest" "pass" "manifest.json found." @{ path = $manifestFile.FullName })
            try {
                $manifest = Get-Content -LiteralPath $manifestFile.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
                [object[]]$smokes = @(Get-ManifestQnnSmokeSpecs -Manifest $manifest)
                if ($smokes.Count -eq 0) {
                    Add-Check (New-Check "bundle.smoke" "fail" "A graph smoke spec in smokes[], smokeSpecs, smoke, or smokeSpec is required.")
                } else {
                    for ($smokeIndex = 0; $smokeIndex -lt $smokes.Count; $smokeIndex++) {
                        $smoke = $smokes[$smokeIndex]
                        $checkPrefix = "bundle.smoke.$smokeIndex"
                        $label = "smoke[$smokeIndex]"
                        $graphName = [string](First-NonBlank @([string]$smoke.graphName, [string]$smoke.graph, [string]$smoke.name))
                        if ([string]::IsNullOrWhiteSpace($graphName)) {
                            Add-Check (New-Check "$checkPrefix.graphName" "fail" "$label.graphName is required.")
                        } else {
                            Add-Check (New-Check "$checkPrefix.graphName" "pass" "$label graph name found." @{ graphName = $graphName })
                        }
                        $contextBinary = [string](First-NonBlank @([string]$smoke.contextBinary, [string]$smoke.context, [string]$smoke.contextPath))
                        if (-not (Test-RelativeBundlePath $contextBinary)) {
                            Add-Check (New-Check "$checkPrefix.contextBinary" "fail" "$label.contextBinary must be a safe bundle-relative path.")
                        } else {
                            $contextFile = Resolve-BundleFile $bundleRootFull $contextBinary
                            if ($null -eq $contextFile -or -not (Test-Path -LiteralPath $contextFile -PathType Leaf)) {
                                Add-Check (New-Check "$checkPrefix.contextBinary" "fail" "$label contextBinary file does not exist inside bundle." @{ contextBinary = $contextBinary })
                            } else {
                                $contextSize = (Get-Item -LiteralPath $contextFile).Length
                                if ($contextSize -le 0) {
                                    Add-Check (New-Check "$checkPrefix.contextBinary" "fail" "$label contextBinary file is empty." @{ contextBinary = $contextBinary; bytes = $contextSize })
                                } else {
                                    Add-Check (New-Check "$checkPrefix.contextBinary" "pass" "$label contextBinary file exists and is non-empty." @{ contextBinary = $contextBinary; bytes = $contextSize })
                                }
                            }
                        }
                        $inputs = @($smoke.inputs)
                        $outputs = @($smoke.outputs)
                        $inputPlans = Get-TensorPlan $inputs "input"
                        $outputPlans = Get-TensorPlan $outputs "output"
                        if ($inputPlans.Count -eq 0) {
                            Add-Check (New-Check "$checkPrefix.inputs" "fail" "$label requires at least one input tensor.")
                        }
                        if ($outputPlans.Count -eq 0) {
                            Add-Check (New-Check "$checkPrefix.outputs" "fail" "$label requires at least one output tensor.")
                        }
                        Test-DuplicateNames $inputPlans "input"
                        Test-DuplicateNames $outputPlans "output"
                        foreach ($plan in $inputPlans + $outputPlans) {
                            if ($plan.supported) {
                                Add-Check (New-Check "$checkPrefix.tensor.$($plan.role).$($plan.name)" "pass" "Tensor is bindable." $plan)
                            } else {
                                Add-Check (New-Check "$checkPrefix.tensor.$($plan.role).$($plan.name)" "fail" "Tensor is not bindable: $($plan.reason)" $plan)
                            }
                        }
                        $totalBytes = [int64](($inputPlans | Measure-Object -Property byteSize -Sum).Sum + ($outputPlans | Measure-Object -Property byteSize -Sum).Sum)
                        if ($totalBytes -gt 512MB) {
                            Add-Check (New-Check "$checkPrefix.bufferBytes" "fail" "$label tensor buffers exceed 512MB." @{ bytes = $totalBytes })
                        } else {
                            Add-Check (New-Check "$checkPrefix.bufferBytes" "pass" "$label tensor buffers are within limit." @{ bytes = $totalBytes })
                        }
                    }
                }
            } catch {
                Add-Check (New-Check "bundle.manifest.parse" "fail" "Failed to parse manifest.json: $($_.Exception.Message)")
            }
        }
    }
}

$adbCommand = Get-Command adb -ErrorAction SilentlyContinue
if ($adbCommand) {
    $devices = & adb devices 2>$null | Select-Object -Skip 1 | Where-Object { $_ -match "\tdevice$" }
    if ($devices.Count -gt 0) {
        $soc = (& adb shell getprop ro.soc.model 2>$null).Trim()
        if ([string]::IsNullOrWhiteSpace($soc)) { $soc = (& adb shell getprop ro.board.platform 2>$null).Trim() }
        Add-Check (New-Check "device.adb" "pass" "ADB device connected." @{ devices = $devices; soc = $soc })
    } elseif ($RequireDevice) {
        Add-Check (New-Check "device.adb" "fail" "No authorized ADB device connected.")
    } else {
        Add-Check (New-Check "device.adb" "warn" "No authorized ADB device connected.")
    }
} elseif ($RequireDevice) {
    Add-Check (New-Check "device.adb" "fail" "adb was not found on PATH.")
} else {
    Add-Check (New-Check "device.adb" "warn" "adb was not found on PATH.")
}

$failCount = @($Checks | Where-Object { $_.status -eq "fail" }).Count
$warnCount = @($Checks | Where-Object { $_.status -eq "warn" }).Count
$passCount = @($Checks | Where-Object { $_.status -eq "pass" }).Count
$result = [pscustomobject]@{
    ok = $failCount -eq 0
    pass = $passCount
    warn = $warnCount
    fail = $failCount
    checks = $Checks
}

if ($Json) {
    $result | ConvertTo-Json -Depth 10
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
