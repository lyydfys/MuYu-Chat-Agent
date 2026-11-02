param(
    [string[]] $TestClasses = @(
        'com.muyuchat.mca.presentation.startup.TtfdSignalTest',
        'com.muyuchat.mca.ControlledReplayFixtureTest'
    ),
    [string] $OutDir = '',
    [switch] $KeepClasses
)
<#
Runs selected :app unit tests WITHOUT Gradle, against the already-compiled module outputs.

Why this exists: while a second workstream is refactoring :core:dsh-* / :core:agent-runtime, the Gradle
task `:app:testDebugUnitTest` cannot even compile its unit-test sources -- and worse, starting it wipes
`app/build/test-results/testDebugUnitTest` before the compile fails, which destroys previously recorded
XML evidence. This script reproduces the same JUnit run from the module class outputs, so the evidence
can be re-established (and re-checked) while the Gradle graph is broken.

What it is NOT: a replacement for the Gradle run. It compiles only the named test classes, uses the
last successful compilation of :app/:feature:chat main sources, and cannot see main-source internals
unless `-Xfriend-paths` is passed for the owning module (it is, for :app). Any result here is evidence
about those classes on that class-output snapshot, not a statement about the whole module graph.

Exit codes: 0 all requested classes passed; 1 a class failed or could not be run; 2 missing dependency.
#>
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$java = 'E:\model\toolchains\jdk-21\jdk-21.0.12.1+1\bin\java.exe'
if (-not (Test-Path -LiteralPath $java)) { throw "java not found at $java" }
$cache = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'

if (-not $OutDir) { $OutDir = Join-Path $repo 'artifacts\ui-quality\auto\standalone-app-tests' }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$classDir = Join-Path $OutDir 'classes'
if (-not $KeepClasses) { Remove-Item -Recurse -Force $classDir -ErrorAction SilentlyContinue }
New-Item -ItemType Directory -Force -Path $classDir | Out-Null

function Find-CachedJar {
    param([string] $Group, [string] $Artifact, [string] $Pattern)
    $base = Join-Path $cache (Join-Path $Group $Artifact)
    if (-not (Test-Path -LiteralPath $base)) { throw "missing dependency '${Group}:${Artifact}' in the Gradle cache" }
    $jar = Get-ChildItem -LiteralPath $base -Recurse -Filter $Pattern -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources|javadoc' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jar) { throw "no jar matching '$Pattern' under $base" }
    return $jar.FullName
}

function Expand-AarClasses {
    param([string] $Group, [string] $Artifact, [string] $DestName)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $base = Join-Path $cache (Join-Path $Group $Artifact)
    if (-not (Test-Path -LiteralPath $base)) { throw "missing dependency '${Group}:${Artifact}' in the Gradle cache" }
    $aar = Get-ChildItem -LiteralPath $base -Recurse -Filter '*.aar' -File | Sort-Object FullName | Select-Object -Last 1
    if (-not $aar) { throw "no aar under $base" }
    $out = Join-Path $OutDir ($DestName + '-classes.jar')
    $zip = [System.IO.Compression.ZipFile]::OpenRead($aar.FullName)
    try {
        $entry = $zip.Entries | Where-Object { $_.FullName -eq 'classes.jar' } | Select-Object -First 1
        if (-not $entry) { throw "$($aar.Name) has no classes.jar" }
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $out, $true)
    } finally { $zip.Dispose() }
    return $out
}

$compiler = Find-CachedJar 'org.jetbrains.kotlin' 'kotlin-compiler-embeddable' 'kotlin-compiler-embeddable-*.jar'
$stdlib = Find-CachedJar 'org.jetbrains.kotlin' 'kotlin-stdlib' 'kotlin-stdlib-*.jar'
$coroutines = Find-CachedJar 'org.jetbrains.kotlinx' 'kotlinx-coroutines-core-jvm' 'kotlinx-coroutines-core-jvm-1.9.0.jar'
$coroutinesTest = Find-CachedJar 'org.jetbrains.kotlinx' 'kotlinx-coroutines-test-jvm' 'kotlinx-coroutines-test-jvm-*.jar'
$json = Find-CachedJar 'org.json' 'json' 'json-*.jar'
$junit = Find-CachedJar 'junit' 'junit' 'junit-4.*.jar'
$hamcrest = Find-CachedJar 'org.hamcrest' 'hamcrest-core' 'hamcrest-core-*.jar'
$trove = Find-CachedJar 'org.jetbrains.intellij.deps' 'trove4j' 'trove4j-*.jar'
$annotations = Find-CachedJar 'org.jetbrains' 'annotations' 'annotations-*.jar'
$composeUnit = Expand-AarClasses 'androidx.compose.ui' 'ui-unit-android' 'ui-unit'
$composeGeometry = Expand-AarClasses 'androidx.compose.ui' 'ui-geometry-android' 'ui-geometry'
$composeGraphics = Expand-AarClasses 'androidx.compose.ui' 'ui-graphics-android' 'ui-graphics'

$chatClasses = Join-Path $repo 'feature\chat\build\tmp\kotlin-classes\debug'
$appClasses = Join-Path $repo 'app\build\tmp\kotlin-classes\debug'
foreach ($dir in @($chatClasses, $appClasses)) {
    if (-not (Test-Path -LiteralPath $dir)) {
        throw "missing compiled classes at $dir - run ':app:compileDebugKotlin' once while the tree is healthy"
    }
}

$compileClasspath = (@($chatClasses, $appClasses, $stdlib, $coroutines, $coroutinesTest, $json, $junit, $hamcrest, $composeUnit, $composeGeometry, $composeGraphics)) -join ';'
$compilerClasspath = (@($compiler, $stdlib, $coroutines, $trove, $annotations)) -join ';'

$sources = @(
    Join-Path $repo 'app\src\main\java\com\muyuchat\mca\presentation\startup\TtfdSignal.kt'
    Join-Path $repo 'app\src\test\java\com\muyuchat\mca\presentation\startup\TtfdSignalTest.kt'
    Join-Path $repo 'app\src\test\java\com\muyuchat\mca\ControlledReplayFixtureTest.kt'
) | Where-Object { Test-Path -LiteralPath $_ }

$compileLog = Join-Path $OutDir 'kotlinc.err.txt'
$compileArgs = @(
    '-cp', $compilerClasspath,
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-no-reflect', '-Xskip-metadata-version-check',
    "-Xfriend-paths=$appClasses",
    '-classpath', $compileClasspath,
    '-d', $classDir
) + $sources
Push-Location $repo
try {
    & $java @compileArgs *> $compileLog
    $compileExit = $LASTEXITCODE
} finally { Pop-Location }
if ($compileExit -ne 0) {
    Write-Output ('standalone: compile failed (' + $compileExit + '); see ' + $compileLog)
    Get-Content -LiteralPath $compileLog -TotalCount 5 | ForEach-Object { Write-Output ('  ' + $_) }
    exit 1
}
Write-Output ('standalone: compiled ' + $sources.Count + ' source file(s)')

$runClasspath = (@($classDir, $chatClasses, $appClasses, $stdlib, $coroutines, $coroutinesTest, $json, $junit, $hamcrest, $composeUnit, $composeGeometry, $composeGraphics)) -join ';'
$failures = 0
Push-Location $repo
try {
    foreach ($testClass in $TestClasses) {
        $resultFile = Join-Path $OutDir ($testClass.Split('.')[-1] + '-result.txt')
        & $java -cp $runClasspath org.junit.runner.JUnitCore $testClass *> $resultFile
        $exit = $LASTEXITCODE
        $summary = if (Test-Path -LiteralPath $resultFile) {
            (Get-Content -LiteralPath $resultFile | Where-Object { $_ -match '^OK \(|^Tests run:' } | Select-Object -First 1)
        } else { 'no output' }
        Write-Output ("standalone: {0} -> exit {1} | {2}" -f $testClass, $exit, $summary)
        if ($exit -ne 0) { $failures++ }
    }
} finally { Pop-Location }

Write-Output ('standalone: evidence dir ' + $OutDir)
if ($failures -gt 0) { exit 1 }
exit 0
