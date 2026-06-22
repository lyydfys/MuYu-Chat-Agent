$ErrorActionPreference = "Stop"

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

$gradleArgs = @(":app:assembleDebug")
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
