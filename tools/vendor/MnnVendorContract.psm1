Set-StrictMode -Version Latest

function Resolve-MnnVendorPath {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$BasePath
    )

    if ([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $BasePath $Path))
}

function Read-MnnVendorProperties {
    param([Parameter(Mandatory = $true)][string]$Path)

    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) {
            continue
        }
        $separator = $trimmed.IndexOf('=')
        if ($separator -le 0) {
            throw "Invalid MNN vendor manifest line: $line"
        }
        $key = $trimmed.Substring(0, $separator).Trim()
        $value = $trimmed.Substring($separator + 1).Trim()
        if ($values.ContainsKey($key)) {
            throw "Duplicate MNN vendor manifest key: $key"
        }
        $values[$key] = $value
    }
    return $values
}

function Get-MnnVendorRequiredProperty {
    param(
        [Parameter(Mandatory = $true)][hashtable]$Properties,
        [Parameter(Mandatory = $true)][string]$Name
    )

    if (-not $Properties.ContainsKey($Name) -or [string]::IsNullOrWhiteSpace($Properties[$Name])) {
        throw "MNN vendor manifest is missing required property '$Name'."
    }
    return [string]$Properties[$Name]
}

function Get-MnnVendorNormalizedText {
    param([Parameter(Mandatory = $true)][string]$Path)

    $text = [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
    return $text.Replace("`r`n", "`n").Replace("`r", "`n")
}

function Get-MnnVendorTextSha256 {
    param([Parameter(Mandatory = $true)][string]$Text)

    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Text)
        return (($sha.ComputeHash($bytes) | ForEach-Object { $_.ToString('x2') }) -join '')
    }
    finally {
        $sha.Dispose()
    }
}

function Get-MnnVendorGitExecutable {
    $git = Get-Command git -CommandType Application -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $git) {
        throw 'Git is required to prepare or verify the pinned MNN vendor checkout.'
    }
    return $git.Source
}

function Invoke-MnnVendorGit {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [switch]$AllowFailure
    )

    $git = Get-MnnVendorGitExecutable
    $previousErrorAction = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& $git @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorAction
    }
    $text = ($output | ForEach-Object { [string]$_ }) -join [Environment]::NewLine
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        $detail = if ([string]::IsNullOrWhiteSpace($text)) { 'no diagnostic output' } else { $text.Trim() }
        throw "Git command failed (exit $exitCode): git $($Arguments -join ' ')`n$detail"
    }
    return [pscustomobject]@{
        ExitCode = $exitCode
        Output = $text
    }
}

function Read-MnnVendorContract {
    param([Parameter(Mandatory = $true)][string]$ManifestPath)

    $manifest = [System.IO.Path]::GetFullPath($ManifestPath)
    if (-not (Test-Path -LiteralPath $manifest -PathType Leaf)) {
        throw "MNN vendor manifest is missing: $manifest"
    }
    $properties = Read-MnnVendorProperties -Path $manifest
    $manifestDirectory = Split-Path -Parent $manifest
    $commit = (Get-MnnVendorRequiredProperty -Properties $properties -Name 'commit').ToLowerInvariant()
    $patchSha = (Get-MnnVendorRequiredProperty -Properties $properties -Name 'patchSha256').ToLowerInvariant()
    if ($commit -notmatch '^[0-9a-f]{40}$') {
        throw "MNN vendor manifest commit must be a full 40-character SHA-1: $commit"
    }
    if ($patchSha -notmatch '^[0-9a-f]{64}$') {
        throw "MNN vendor manifest patchSha256 must be a full SHA-256: $patchSha"
    }
    $patchPath = Resolve-MnnVendorPath `
        -Path (Get-MnnVendorRequiredProperty -Properties $properties -Name 'patch') `
        -BasePath $manifestDirectory
    if (-not (Test-Path -LiteralPath $patchPath -PathType Leaf)) {
        throw "MNN vendor patch is missing: $patchPath"
    }
    $files = @((Get-MnnVendorRequiredProperty -Properties $properties -Name 'files').Split('|') |
        ForEach-Object { $_.Trim().Replace('\', '/') } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($files.Count -eq 0 -or @($files | Select-Object -Unique).Count -ne $files.Count) {
        throw 'MNN vendor manifest files must contain unique repository-relative paths.'
    }

    return [pscustomobject]@{
        ManifestPath = $manifest
        Repository = Get-MnnVendorRequiredProperty -Properties $properties -Name 'repository'
        Ref = Get-MnnVendorRequiredProperty -Properties $properties -Name 'ref'
        Commit = $commit
        PatchPath = $patchPath
        PatchSha256 = $patchSha
        Files = $files
    }
}

function Get-MnnVendorPatchFiles {
    param([Parameter(Mandatory = $true)][string]$PatchText)

    $paths = New-Object System.Collections.Generic.List[string]
    foreach ($match in [regex]::Matches($PatchText, '(?m)^diff --git a/(.+) b/(.+)$')) {
        $before = $match.Groups[1].Value.TrimEnd("`r")
        $after = $match.Groups[2].Value.TrimEnd("`r")
        if ($before -ne $after) {
            throw "MNN vendor patch renames are not supported by this contract: $before -> $after"
        }
        [void]$paths.Add($before)
    }
    return $paths.ToArray()
}

function Get-MnnVendorCanonicalDiff {
    param([Parameter(Mandatory = $true)][string]$SourceRoot)

    $temporary = [System.IO.Path]::GetTempFileName()
    try {
        $result = Invoke-MnnVendorGit -Arguments @(
            '-c', 'core.safecrlf=false', '-C', $SourceRoot,
            'diff', '--binary', '--full-index', '--no-ext-diff', '--no-color',
            "--output=$temporary", 'HEAD', '--'
        ) -AllowFailure
        if ($result.ExitCode -ne 0) {
            throw "Unable to compute the MNN vendor diff: $($result.Output)"
        }
        return Get-MnnVendorNormalizedText -Path $temporary
    }
    finally {
        Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
    }
}

function Assert-MnnVendorCheckout {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$SourceRoot,
        [Parameter(Mandatory = $true)][string]$ManifestPath
    )

    $source = [System.IO.Path]::GetFullPath($SourceRoot)
    $contract = Read-MnnVendorContract -ManifestPath $ManifestPath
    if (-not (Test-Path -LiteralPath $source -PathType Container)) {
        throw "MNN vendor checkout is missing: $source. Run tools/vendor/bootstrap-mnn-vendor.ps1."
    }

    $inside = Invoke-MnnVendorGit -Arguments @('-C', $source, 'rev-parse', '--is-inside-work-tree') -AllowFailure
    if ($inside.ExitCode -ne 0 -or $inside.Output.Trim() -ne 'true') {
        throw "MNN vendor path is not a Git worktree: $source"
    }

    $patchText = Get-MnnVendorNormalizedText -Path $contract.PatchPath
    $actualPatchSha = Get-MnnVendorTextSha256 -Text $patchText
    if ($actualPatchSha -ne $contract.PatchSha256) {
        throw "MNN vendor patch checksum mismatch. Expected $($contract.PatchSha256), got $actualPatchSha."
    }
    $patchFiles = @(Get-MnnVendorPatchFiles -PatchText $patchText)
    if (($patchFiles -join "`n") -cne ($contract.Files -join "`n")) {
        throw "MNN vendor manifest file list does not match the patch. Patch files: $($patchFiles -join ', ')."
    }

    $head = (Invoke-MnnVendorGit -Arguments @('-C', $source, 'rev-parse', 'HEAD')).Output.Trim().ToLowerInvariant()
    if ($head -ne $contract.Commit) {
        throw "MNN vendor commit mismatch. Expected $($contract.Commit), got $head."
    }

    $status = (Invoke-MnnVendorGit -Arguments @(
        '-C', $source, 'status', '--porcelain=v1', '--untracked-files=all'
    )).Output
    $untracked = @($status -split "`r?`n" | Where-Object { $_ -match '^\?\?' })
    if ($untracked.Count -gt 0) {
        throw "MNN vendor checkout contains untracked drift:`n$($untracked -join [Environment]::NewLine)"
    }

    $currentDiff = Get-MnnVendorCanonicalDiff -SourceRoot $source
    if ([string]::IsNullOrWhiteSpace($currentDiff)) {
        throw 'MNN vendor overlay patch is not applied.'
    }
    if ($currentDiff -cne $patchText) {
        $reverse = Invoke-MnnVendorGit -Arguments @(
            '-C', $source, 'apply', '--reverse', '--check', '--whitespace=nowarn', $contract.PatchPath
        ) -AllowFailure
        $reason = if ($reverse.ExitCode -eq 0) {
            'the required overlay is present, but additional tracked drift exists'
        }
        else {
            'the required overlay is only partially applied or has been modified'
        }
        $statusDetail = if ([string]::IsNullOrWhiteSpace($status)) { '(clean status)' } else { $status.Trim() }
        throw "MNN vendor diff mismatch: $reason.`n$statusDetail"
    }

    $reverseCheck = Invoke-MnnVendorGit -Arguments @(
        '-C', $source, 'apply', '--reverse', '--check', '--whitespace=nowarn', $contract.PatchPath
    ) -AllowFailure
    if ($reverseCheck.ExitCode -ne 0) {
        throw "MNN vendor overlay does not reverse-apply cleanly: $($reverseCheck.Output)"
    }
    $whitespaceCheck = Invoke-MnnVendorGit -Arguments @('-C', $source, 'diff', '--check', 'HEAD', '--') -AllowFailure
    if ($whitespaceCheck.ExitCode -ne 0) {
        throw "MNN vendor overlay contains whitespace errors: $($whitespaceCheck.Output)"
    }

    return [pscustomobject]@{
        SourceRoot = $source
        Commit = $head
        PatchSha256 = $actualPatchSha
        Files = $patchFiles
    }
}

Export-ModuleMember -Function @(
    'Assert-MnnVendorCheckout',
    'Get-MnnVendorGitExecutable',
    'Invoke-MnnVendorGit',
    'Read-MnnVendorContract'
)
