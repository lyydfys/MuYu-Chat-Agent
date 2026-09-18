$ErrorActionPreference = "Stop"

$qnnSdkRoot = @(
    $env:MCA_QNN_SDK_ROOT,
    $env:QNN_SDK_ROOT,
    $env:QAIRT_SDK_ROOT
) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -First 1

if ([string]::IsNullOrWhiteSpace($qnnSdkRoot) -or -not (Test-Path -LiteralPath $qnnSdkRoot)) {
    throw "A valid QAIRT/QNN SDK is required. Set MCA_QNN_SDK_ROOT (or QNN_SDK_ROOT/QAIRT_SDK_ROOT) before building the standard APK."
}

$env:MCA_QNN_SDK_ROOT = $qnnSdkRoot
$env:QNN_SDK_ROOT = $qnnSdkRoot
$env:QAIRT_SDK_ROOT = $qnnSdkRoot

$jdkCandidates = @(
    "$PSScriptRoot\..\toolchains\jdk-17",
    "$env:USERPROFILE\.jdks\ms-17.0.15"
)
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    foreach ($candidate in $jdkCandidates) {
        if (Test-Path -LiteralPath (Join-Path $candidate "bin\java.exe")) {
            $env:JAVA_HOME = $candidate
            $env:PATH = (Join-Path $candidate "bin") + ";" + $env:PATH
            break
        }
    }
}

$gradleArgs = @(
    ":app:assembleDebug",
    "-Pmca.abis=arm64-v8a,x86_64",
    "-PmcaQnnSdkRoot=$qnnSdkRoot",
    "-PmcaQnnTypedBindingsRequired=true",
    "--no-daemon",
    "--console=plain"
)
$gradleExecutable = if (Test-Path ".\gradlew.bat") { ".\gradlew.bat" } else { "gradle" }
$vcvars = "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"

if (Test-Path -LiteralPath $vcvars) {
    $quotedArgs = ($gradleArgs | ForEach-Object {
        if ($_ -match '\s') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ }
    }) -join " "
    cmd.exe /d /s /c "call `"$vcvars`" >nul && `"$gradleExecutable`" $quotedArgs"
} else {
    & $gradleExecutable @gradleArgs
}

if ($LASTEXITCODE -ne 0) {
    throw "Gradle APK build failed with exit code $LASTEXITCODE."
}

$apkPath = Join-Path (Get-Location) "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path -LiteralPath $apkPath)) {
    throw "Gradle reported success but the expected APK was not produced: $apkPath"
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($apkPath)
try {
    $entryNames = @($zip.Entries | ForEach-Object { $_.FullName })
    $requiredEntries = @(
        "lib/arm64-v8a/libmca_qnn_native.so",
        "lib/arm64-v8a/libmca_mnn_native.so",
        "lib/arm64-v8a/libmca_sd_native.so",
        "lib/arm64-v8a/libMNN.so",
        "lib/arm64-v8a/libQnnHtpV79.so",
        "lib/x86_64/libmca_native.so",
        "lib/x86_64/libmca_mnn_native.so",
        "lib/x86_64/libmca_sd_native.so",
        "assets/litert-qualcomm/v79/arm64-v8a/libQnnHtpV79Skel.so"
    )
    foreach ($requiredEntry in $requiredEntries) {
        if ($entryNames -notcontains $requiredEntry) {
            throw "APK validation failed: missing $requiredEntry. Check ABI and QAIRT SDK configuration."
        }
    }

    $arm64Count = @($entryNames | Where-Object { $_ -like "lib/arm64-v8a/*" }).Count
    $x86Count = @($entryNames | Where-Object { $_ -like "lib/x86_64/*" }).Count
    if ($arm64Count -eq 0 -or $x86Count -eq 0) {
        throw "APK validation failed: expected both arm64-v8a and x86_64 native libraries (arm64=$arm64Count, x86_64=$x86Count)."
    }
} finally {
    $zip.Dispose()
}

$apkInfo = Get-Item -LiteralPath $apkPath
$apkHash = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Host "Validated APK: $apkPath"
Write-Host "Size: $($apkInfo.Length) bytes"
Write-Host "SHA-256: $apkHash"
