param(
    [Parameter(Mandatory = $true)]
    [string]$LibraryPath,
    [string]$ReadElfPath = ""
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $LibraryPath -PathType Leaf)) {
    throw "OpenCL backend library not found: $LibraryPath"
}

if ([string]::IsNullOrWhiteSpace($ReadElfPath)) {
    $candidates = @()
    if ($env:ANDROID_NDK_HOME) {
        $candidates += Get-ChildItem -LiteralPath $env:ANDROID_NDK_HOME -Recurse -Filter llvm-readelf.exe -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty FullName
    }
    if ($env:ANDROID_HOME) {
        $candidates += Get-ChildItem -LiteralPath (Join-Path $env:ANDROID_HOME "ndk") -Recurse -Filter llvm-readelf.exe -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty FullName
    }
    $ReadElfPath = $candidates | Select-Object -First 1
}
if ([string]::IsNullOrWhiteSpace($ReadElfPath) -or -not (Test-Path -LiteralPath $ReadElfPath -PathType Leaf)) {
    throw "llvm-readelf.exe was not found. Pass -ReadElfPath explicitly."
}

$dynamic = & $ReadElfPath -d $LibraryPath 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) { throw "llvm-readelf -d failed: $dynamic" }
if ($dynamic -match 'NEEDED.*\[libOpenCL\.so\]') {
    throw "The backend still depends on DT_NEEDED=libOpenCL.so; Android namespace resolution can fail before CPU fallback."
}
if ($dynamic -notmatch 'NEEDED.*\[libdl\.so\]') {
    throw "The backend does not link libdl.so for runtime OpenCL symbol resolution."
}

$symbols = & $ReadElfPath -Ws $LibraryPath 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) { throw "llvm-readelf -Ws failed: $symbols" }
$unresolvedOpenCl = [regex]::Matches($symbols, '(?m)^\s*\d+:.*\bUND\s+(cl[A-Z][A-Za-z0-9_]*)\s*$') |
    ForEach-Object { $_.Groups[1].Value } |
    Sort-Object -Unique
if ($unresolvedOpenCl.Count -gt 0) {
    throw "Unresolved OpenCL imports remain in the backend: $($unresolvedOpenCl -join ', ')"
}

Write-Host "PASS: $LibraryPath has no OpenCL DT_NEEDED or undefined cl* imports; runtime dispatch is self-contained."
