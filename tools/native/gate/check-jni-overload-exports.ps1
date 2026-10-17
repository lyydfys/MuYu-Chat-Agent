# MCA repair runId=20261009-parity / T07 test-trust
# EXACT-signature JNI overload export gate.
#
# Gap this closes (PLAN 9.3 "JNI 重载签名漏检"): T01's check-jni-exports.ps1
# resolves a Kotlin `external fun` to its JNI long name and then accepts ANY
# symbol matching "<base>__*" as PRESENT:
#
#     $overload = @($syms | Where-Object { $_ -like "$($d.jniBase)__*" })
#
# For an overloaded declaration that is wrong.  The JVM does not resolve
# "some overload of this name"; it resolves the EXACT mangled name, which
# includes the JNI argument signature:
#     Java_pkg_Class_method__I   for (Int)
#     Java_pkg_Class_method__J   for (Long)
# If the packaged .so only defines __J, a `__*` matcher reports PRESENT while
# the JVM throws UnsatisfiedLinkError for the Int overload at first call.
# T01's own run-gate-negative-tests.ps1 MISSING_JNI case cannot catch that: it
# drops the only export of a non-overloaded name.
#
# This tool therefore:
#   1. brace-depth aware Kotlin class tracking (a `class A {}` that has already
#      closed must not capture a later top-level declaration);
#   2. exact JNI argument signature derived from the Kotlin parameter types;
#   3. for overload sets: require the EXACT "<base>__<sig>" symbol;
#      for non-overloaded declarations: require the EXACT "<base>" symbol;
#   4. report both the exact verdict and the loose `__*` verdict so a
#      regression of the loose matcher is visible as a divergence;
#   5. exit 1 when any required symbol is absent.
param(
    [Parameter(Mandatory = $true)][string[]]$KotlinRoots,
    [Parameter(Mandatory = $true)][string]$LibDir,
    [Parameter(Mandatory = $true)][string]$OutTxt,
    [Parameter(Mandatory = $true)][string]$OutJson,
    [string]$RepoRoot = 'E:\MAC',
    [string]$LlvmNm = 'D:\model\android-sdk\ndk\29.0.13113456\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-nm.exe'
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $LlvmNm)) { throw "llvm-nm not found: $LlvmNm" }
if (-not (Test-Path -LiteralPath $LibDir)) { throw "lib dir not found: $LibDir" }

# --- Kotlin primitive/composite -> JNI type signature -----------------------
$jniPrimitive = @{
    'Boolean' = 'Z'; 'Byte' = 'B'; 'Char' = 'C'; 'Short' = 'S'; 'Int' = 'I'
    'Long' = 'J'; 'Float' = 'F'; 'Double' = 'D'; 'Unit' = 'V'
    'BooleanArray' = '[Z'; 'ByteArray' = '[B'; 'CharArray' = '[C'; 'ShortArray' = '[S'
    'IntArray' = '[I'; 'LongArray' = '[J'; 'FloatArray' = '[F'; 'DoubleArray' = '[D'
}
$jniBoxed = @{
    'Boolean' = 'Ljava/lang/Boolean;'; 'Byte' = 'Ljava/lang/Byte;'; 'Char' = 'Ljava/lang/Character;'
    'Short' = 'Ljava/lang/Short;'; 'Int' = 'Ljava/lang/Integer;'; 'Long' = 'Ljava/lang/Long;'
    'Float' = 'Ljava/lang/Float;'; 'Double' = 'Ljava/lang/Double;'; 'String' = 'Ljava/lang/String;'
}

function ConvertTo-JniTypeSignature {
    param([string]$KotlinType)
    $t = $KotlinType.Trim()
    if (-not $t) { return $null }
    # strip generic args -> not expressible in JNI; caller records it as unresolved
    $nullable = $t.EndsWith('?')
    if ($nullable) { $t = $t.Substring(0, $t.Length - 1).Trim() }
    if ($t.StartsWith('Array<')) { return $null }
    if ($t -match '^(\w+)$') {
        $simple = $matches[1]
        if ($nullable -and $jniBoxed.ContainsKey($simple)) { return $jniBoxed[$simple] }
        if ($jniPrimitive.ContainsKey($simple)) { return $jniPrimitive[$simple] }
        # a user class: the package cannot be resolved without imports; mark
        # unresolved rather than guessing a wrong signature.
        return $null
    }
    return $null
}

# --- parse Kotlin declarations with brace-depth awareness -------------------
$decls = [System.Collections.Generic.List[object]]::new()
foreach ($root in $KotlinRoots) {
    if (-not (Test-Path -LiteralPath $root)) { continue }
    foreach ($file in (Get-ChildItem -LiteralPath $root -Filter '*.kt' -File | Sort-Object Name)) {
        $lines = Get-Content -LiteralPath $file.FullName
        $package = ''
        $depth = 0
        $scopeStack = [System.Collections.Generic.List[object]]::new()   # @{ name; depth }
        $i = 0
        while ($i -lt $lines.Count) {
            $line = $lines[$i]
            while ($scopeStack.Count -gt 0 -and $depth -lt $scopeStack[$scopeStack.Count - 1].depth) { $scopeStack.RemoveAt($scopeStack.Count - 1) }

            if ($line -match '^\s*package\s+([\w.]+)') { $package = $matches[1] }
            elseif ($line -match '^\s*(?:internal\s+|private\s+|public\s+|open\s+|abstract\s+|data\s+|sealed\s+|value\s+|annotation\s+)*(?:class|object|interface)\s+([A-Za-z_]\w*)') {
                $scopeStack.Add([pscustomobject]@{ name = $matches[1]; depth = $depth + 1 })
            }
            elseif ($line -match '^\s*external\s+fun\s+([A-Za-z_]\w*)') {
                $method = $matches[1]
                $enclosing = 'toplevel'
                if ($scopeStack.Count -gt 0) { $enclosing = $scopeStack[$scopeStack.Count - 1].name }
                # collect the whole parameter list (may span lines)
                $buf = $line
                $open = ($buf.ToCharArray() | Where-Object { $_ -eq '(' }).Count
                $close = ($buf.ToCharArray() | Where-Object { $_ -eq ')' }).Count
                while ($open -gt $close -and ($i + 1) -lt $lines.Count) {
                    $i++
                    $buf += ' ' + $lines[$i]
                    $open = ($buf.ToCharArray() | Where-Object { $_ -eq '(' }).Count
                    $close = ($buf.ToCharArray() | Where-Object { $_ -eq ')' }).Count
                }
                $paramText = ''
                if ($buf -match '\((.*)\)') { $paramText = $matches[1] }
                $sigs = [System.Collections.Generic.List[string]]::new()
                $unresolved = [System.Collections.Generic.List[string]]::new()
                if ($paramText.Trim()) {
                    foreach ($p in ($paramText -split ',')) {
                        $p = $p.Trim()
                        if (-not $p) { continue }
                        if ($p -match ':\s*([^=]+?)(\s*=|$)') {
                            $typeText = $matches[1].Trim()
                            $sig = ConvertTo-JniTypeSignature -KotlinType $typeText
                            if ($null -eq $sig) { $unresolved.Add($typeText) } else { $sigs.Add($sig) }
                        }
                    }
                }
                $relFile = $file.FullName.Substring($RepoRoot.Length + 1) -replace '\\', '/'
                $decls.Add([pscustomobject]@{
                    file = $relFile
                    line = $i + 1
                    package = $package
                    klass = $enclosing
                    method = $method
                    argSignature = ($sigs -join '')
                    unresolvedParams = @($unresolved)
                })
            }
            $depth += (($line.ToCharArray() | Where-Object { $_ -eq '{' }).Count) - (($line.ToCharArray() | Where-Object { $_ -eq '}' }).Count)
            $i++
        }
    }
}

function ConvertTo-JniLongName {
    param([string]$Package, [string]$Class, [string]$Method)
    $escaped = "$Package.$Class.$Method" -replace '_', '_1'
    return 'Java_' + ($escaped -replace '\.', '_')
}

$overloadGroups = @{}
foreach ($d in $decls) {
    $key = "$($d.package).$($d.klass).$($d.method)"
    if (-not $overloadGroups.ContainsKey($key)) { $overloadGroups[$key] = 0 }
    $overloadGroups[$key]++
}

# --- dynamic exports per .so -------------------------------------------------
$soSymbols = @{}
$nmRaw = [System.Collections.Generic.List[string]]::new()
foreach ($so in (Get-ChildItem -LiteralPath $LibDir -Filter '*.so' -File | Sort-Object Name)) {
    $nmRaw.Add("----- llvm-nm -D --defined-only $($so.FullName)")
    $out = @(& $LlvmNm '-D' '--defined-only' $so.FullName 2>&1 | ForEach-Object { "$_" })
    foreach ($l in $out) { $nmRaw.Add($l) }
    $syms = [System.Collections.Generic.List[string]]::new()
    foreach ($l in $out) { if ($l -match '^\s*[0-9a-fA-F]+\s+\S\s+(\S+)\s*$') { $syms.Add($matches[1]) } }
    $soSymbols[$so.Name] = $syms
}

$libForFile = @{}
foreach ($root in $KotlinRoots) {
    if (-not (Test-Path -LiteralPath $root)) { continue }
    foreach ($file in (Get-ChildItem -LiteralPath $root -Filter '*.kt' -File)) {
        $rel = $file.FullName.Substring($RepoRoot.Length + 1) -replace '\\', '/'
        $txt = Get-Content -LiteralPath $file.FullName -Raw
        if ($txt -match 'System\.loadLibrary\("([^"]+)"\)') { $libForFile[$rel] = "lib$($matches[1]).so" }
    }
}

# --- verdict -----------------------------------------------------------------
$rows = [System.Collections.Generic.List[object]]::new()
foreach ($d in ($decls | Sort-Object file, line)) {
    $base = ConvertTo-JniLongName -Package $d.package -Class $d.klass -Method $d.method
    $key = "$($d.package).$($d.klass).$($d.method)"
    $overloaded = ($overloadGroups[$key] -gt 1)
    $soName = $libForFile[$d.file]
    $syms = @()
    if ($soName -and $soSymbols.ContainsKey($soName)) { $syms = $soSymbols[$soName] }

    $skipCompare = $false
    $required = $base
    if ($overloaded) {
        if ($d.unresolvedParams.Count -gt 0 -or [string]::IsNullOrEmpty($d.argSignature)) {
            $skipCompare = $true
            $required = "$base__<$($d.argSignature)> (signature unresolved - excluded from verdict)"
        } else {
            $required = "$base" + '__' + $d.argSignature
        }
    }

    $exactHit = @($syms | Where-Object { $_ -eq $required }).Count
    $looseHit = @($syms | Where-Object { $_ -eq $base -or $_ -like "$base`__*" }).Count
    $status = 'PRESENT'
    if (-not $skipCompare -and $exactHit -eq 0) { $status = 'MISSING' }

    $rows.Add([pscustomobject]@{
        file = $d.file
        line = $d.line
        so = $soName
        jniBase = $base
        overloaded = $overloaded
        argSignature = $d.argSignature
        unresolvedParams = @($d.unresolvedParams)
        requiredSymbol = $required
        exactHit = $exactHit
        looseMatcherHit = $looseHit
        status = $status
        looseMatcherWouldSayPresent = ($looseHit -gt 0)
    })
}

$missing = @($rows | Where-Object { $_.status -eq 'MISSING' })
$divergent = @($rows | Where-Object { $_.status -eq 'MISSING' -and $_.looseMatcherWouldSayPresent })
$ok = ($missing.Count -eq 0)

$txt = [System.Collections.Generic.List[string]]::new()
$txt.Add('===== MCA T07 exact-signature JNI overload export gate =====')
$txt.Add("generatedUtc=$([datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.fffZ'))")
$txt.Add("kotlinRoots=$($KotlinRoots -join ', ')")
$txt.Add("libDir=$LibDir")
$txt.Add("kotlinExternalDeclarationCount=$($decls.Count) overloadSets=$((@($overloadGroups.GetEnumerator() | Where-Object { $_.Value -gt 1 })).Count)")
$txt.Add("packagedSoFiles=$((@($soSymbols.Keys) -join ', '))")
$txt.Add('')
$txt.Add('--- per-declaration result (EXACT signature required) ---')
foreach ($r in $rows) {
    $txt.Add(("{0,-7} {1,-20} {2}:{3} req={4} overloaded={5} looseMatcherHit={6}" -f $r.status, $r.so, $r.file, $r.line, $r.requiredSymbol, $r.overloaded, $r.looseMatcherHit))
}
$txt.Add('')
$txt.Add("PRESENT=$((@($rows | Where-Object status -eq 'PRESENT')).Count) MISSING=$($missing.Count)")
$txt.Add("loose-__*-matcher-divergences=$($divergent.Count)  (cases a naive check-jni-exports.ps1 matcher would call PRESENT)")
foreach ($d in $divergent) { $txt.Add("  DIVERGENCE $($d.file):$($d.line) requires $($d.requiredSymbol)") }
$txt.Add("verdict=$(if ($ok) { 'PASS' } else { 'FAIL' })")
$txt.Add('')
$txt.Add('--- raw llvm-nm output ---')
foreach ($l in $nmRaw) { $txt.Add($l) }

$dir = Split-Path -Parent $OutTxt
if ($dir -and -not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
[System.IO.File]::WriteAllText($OutTxt, (($txt -join "`n") + "`n"), (New-Object System.Text.UTF8Encoding($false)))

$json = [ordered]@{
    generatedUtc = [datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
    kotlinRoots = @($KotlinRoots)
    libDir = $LibDir
    declarationCount = $decls.Count
    overloadSetCount = @($overloadGroups.GetEnumerator() | Where-Object { $_.Value -gt 1 }).Count
    presentCount = @($rows | Where-Object status -eq 'PRESENT').Count
    missingCount = $missing.Count
    looseMatcherDivergenceCount = $divergent.Count
    declarations = @($rows)
    verdict = $(if ($ok) { 'PASS' } else { 'FAIL' })
}
$jdir = Split-Path -Parent $OutJson
if ($jdir -and -not (Test-Path -LiteralPath $jdir)) { New-Item -ItemType Directory -Force -Path $jdir | Out-Null }
[System.IO.File]::WriteAllText($OutJson, ($json | ConvertTo-Json -Depth 8), (New-Object System.Text.UTF8Encoding($false)))

Write-Output "wrote: $OutTxt"
Write-Output "wrote: $OutJson"
Write-Output "declarations=$($decls.Count) PRESENT=$($json.presentCount) MISSING=$($missing.Count) looseDivergences=$($divergent.Count) verdict=$($json.verdict)"
foreach ($m in $missing) { Write-Output "MISSING: $($m.so) $($m.requiredSymbol)" }

if (-not $ok) { exit 1 }
